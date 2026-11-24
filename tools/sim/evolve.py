"""
Evolution of Baritone's settings in the real game, sped up (Air Client simulation mode).

    python tools/sim/evolve.py setup  --instances 6      # once (and after code changes)
    python tools/sim/evolve.py run    --instances 6 --total-runs 500000
    python tools/sim/evolve.py smoke                     # 1 instance, a few runs: does it work?
    python tools/sim/evolve.py status
    python tools/sim/evolve.py install                   # best genome -> run/config/baritone-genome.json

How it works
  * Every instance is the real dev client (real physics, real Baritone, the mod as it is) in its own
    folder run-sim/inst-N with a copy of the AStarTest world. AIRCLIENT_SIM=1 switches on turbo mode:
    client and integrated server tick in lockstep as fast as the PC allows; while a path is being
    calculated the game runs in real time, so planning costs the same game time as in the real game.
  * A genome = values for the Baritone settings in tools/sim/genes.json.
  * CMA-ES: each generation tries LAMBDA genomes, all on the SAME runs (level + seed), so the
    comparison is fair; every generation gets new seeds (no overfitting to some seeds).
  * Fitness: first how many runs ended at the goal / with all wolves dead (survivors), then -
    only as tie-break, never outweighing one more success - how fast the successful runs were
    (time compared to the median of the generation for the same run).
  * Every HOLDOUT generations the current mean genome and the unchanged settings (baseline) run the
    same fixed holdout set; best_genome.json is the mean with the best holdout score.
  * State is saved after every generation: stop with Ctrl+C, run again to continue.
"""
import argparse
import json
import math
import os
import random
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SIM = ROOT / "run-sim"
EVO = SIM / "evolution"
RUN = ROOT / "run"
WORLD = "AStarTest"
GENES = json.loads((Path(__file__).parent / "genes.json").read_text(encoding="utf-8"))["genes"]
LEVELS_DEFAULT = "10:10,11:10"
# Fixed effects for levels 5-7 (amplifiers: speed 1 = Speed II, jump 3 = Jump Boost IV), set by --speed / --jump.
# Levels 8 and 9 keep the recorded SkyBlock speed (no jump boost there).
PROFILE = {"speed": 14, "jump": 5}
HOLDOUT_SEED_BASE = 10_000_000
STARTUP_GRACE = 300  # s until the first heartbeat of a fresh instance
SILENT_LIMIT = 120  # s without heartbeat: restart
CHUNK_LIMIT = 1800  # s for one chunk of runs: restart


def event(msg):
    EVO.mkdir(parents=True, exist_ok=True)
    with open(EVO / "events.log", "a", encoding="utf-8") as out:
        out.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")


def log(msg):
    stamp = time.strftime("%H:%M:%S")
    print(f"[{stamp}] {msg}", flush=True)


# ----------------------------------------------------------------------------- setup

def gradle(*tasks):
    exe = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(exe), *tasks, "--console=plain", "-q"], cwd=ROOT, check=True)


# Instances log little and capped: latest.log (INFO, max 3 x 20 MB), no debug.log (was up to 1 GB per
# instance), the console (stdout.log) only warnings and errors.
LOG4J = '''<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="SysOut" target="SYSTEM_OUT">
      <ThresholdFilter level="WARN" onMatch="ACCEPT" onMismatch="DENY"/>
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level] (%logger{1}) %msg{nolookups}%n"/>
    </Console>
    <RollingRandomAccessFile name="LatestFile" fileName="logs/latest.log" filePattern="logs/latest-%i.log.gz">
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level] (%logger{1}) %msg{nolookups}%n"/>
      <Policies>
        <SizeBasedTriggeringPolicy size="20MB"/>
        <OnStartupTriggeringPolicy/>
      </Policies>
      <DefaultRolloverStrategy max="3" fileIndex="min"/>
    </RollingRandomAccessFile>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="SysOut"/>
      <AppenderRef ref="LatestFile"/>
    </Root>
  </Loggers>
</Configuration>
'''


def snapshot():
    """Copy the built mod classes, so a new build during the evolution does not change running instances."""
    launch = json.loads((ROOT / "build/sim/launch.json").read_text(encoding="utf-8"))
    snap = SIM / "snapshot"
    if snap.exists():
        shutil.rmtree(snap)
    snap.mkdir(parents=True)
    jvm = []
    replace = {}
    argfile = None
    cfg = None
    for a in launch["jvmArgs"]:
        if a.startswith("@"):
            argfile = Path(a[1:])
        elif a.startswith("-Dfabric.dli.config="):
            cfg = Path(a.split("=", 1)[1])
        else:
            jvm.append(a)
    if argfile is None or cfg is None:
        raise SystemExit("launch.json: no argfile / dli config - Loom changed, adapt snapshot()")
    text = argfile.read_text(encoding="utf-8")
    cp_line = text.splitlines()[1]
    for entry in cp_line.split(os.pathsep):
        p = Path(entry)
        if p.is_dir() and ROOT in p.parents:
            target = snap / "cp" / p.relative_to(ROOT)
            shutil.copytree(p, target)
            replace[str(p)] = str(target)
    cfg_text = cfg.read_text(encoding="utf-8")
    (snap / "log4j.xml").write_text(LOG4J, encoding="utf-8")
    cfg_text = "\n".join(
        ("\tlog4j.configurationFile=" + str(snap / "log4j.xml")) if l.strip().startswith("log4j.configurationFile=") else l
        for l in cfg_text.splitlines())
    for old, new in sorted(replace.items(), key=lambda kv: -len(kv[0])):
        text = text.replace(old, new)
        cfg_text = cfg_text.replace(old, new)
    (snap / "argfile").write_text(text, encoding="utf-8")
    (snap / "launch.cfg").write_text(cfg_text, encoding="utf-8")
    launch["jvmArgs"] = ["-Dfabric.dli.config=" + str(snap / "launch.cfg"), "@" + str(snap / "argfile")] + jvm
    (snap / "launch.json").write_text(json.dumps(launch, indent=1), encoding="utf-8")
    log(f"snapshot: {len(replace)} class folders copied")


def patch_options(src, dst):
    keep = {}
    if src.exists():
        for line in src.read_text(encoding="utf-8").splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                keep[k] = v
    keep.update({
        "renderDistance": "6", "simulationDistance": "5", "maxFps": "30", "pauseOnLostFocus": "false",
        "soundCategory_master": "0.0", "fullscreen": "false", "narrator": "0", "tutorialStep": "none",
        "onboardAccessibility": "false", "skipMultiplayerWarning": "true", "joinedFirstServer": "true",
        "graphicsMode": "0", "clouds": "\"false\"", "particles": "2", "entityShadows": "false",
        "inactivityFpsLimit": "\"minimized\"", "enableVsync": "false",
    })
    dst.write_text("\n".join(f"{k}:{v}" for k, v in keep.items()) + "\n", encoding="utf-8")


def make_instance(i, fresh):
    d = SIM / f"inst-{i}"
    d.mkdir(parents=True, exist_ok=True)
    world = d / "saves" / WORLD
    if fresh and world.exists():
        shutil.rmtree(world)
    if not world.exists():
        shutil.copytree(RUN / "saves" / WORLD, world, ignore=shutil.ignore_patterns("session.lock"))
    for sub in ("config", "baritone", "mods"):
        if (RUN / sub).exists():
            if (d / sub).exists():
                shutil.rmtree(d / sub)
            shutil.copytree(RUN / sub, d / sub)
    inter = d / "logs" / "interactions"
    inter.mkdir(parents=True, exist_ok=True)
    for f in (RUN / "logs" / "interactions").glob("*level_*.json"):
        shutil.copy2(f, inter / f.name)
    patch_options(RUN / "options.txt", d / "options.txt")
    (d / "sim").mkdir(exist_ok=True)
    return d


def cmd_setup(args):
    if not (RUN / "saves" / WORLD).exists():
        raise SystemExit(f"world run/saves/{WORLD} missing")
    log("gradle simExport (builds the mod)...")
    gradle("simExport")
    snapshot()
    for i in range(1, args.instances + 1):
        make_instance(i, args.fresh)
    log(f"{args.instances} instances ready in {SIM}")


# ----------------------------------------------------------------------------- instances

class Instance:
    def __init__(self, i, mem, realtime=False):
        self.realtime = realtime
        self.i = i
        self.dir = SIM / f"inst-{i}"
        self.mem = mem
        self.proc = None
        self.started = 0.0
        self.chunk = None
        self.chunk_since = 0.0
        self.log = None

    def start(self):
        launch = json.loads((SIM / "snapshot/launch.json").read_text(encoding="utf-8"))
        simdir = self.dir / "sim"
        for f in simdir.glob("*"):
            if f.name != "settings.json":
                f.unlink()
        args = [launch["java"], f"-Xmx{self.mem}", "-Xms1G", *launch["jvmArgs"], launch["mainClass"], *launch["args"],
                "--quickPlaySingleplayer", WORLD, "--width", "480", "--height", "270", "--username", f"Sim{self.i}"]
        env = dict(os.environ, AIRCLIENT_ASTAR_TEST="1", AIRCLIENT_SIM="1",
                   AIRCLIENT_SIM_TURBO="0" if self.realtime else "1")
        self.log = open(self.dir / "stdout.log", "w", encoding="utf-8", errors="replace")
        flags = 0x00004000 if os.name == "nt" else 0  # BELOW_NORMAL_PRIORITY_CLASS: the PC stays usable
        self.proc = subprocess.Popen(args, cwd=self.dir, env=env, stdout=self.log, stderr=subprocess.STDOUT,
                                     creationflags=flags)
        self.started = time.time()
        self.chunk = None
        log(f"instance {self.i} started (pid {self.proc.pid})")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                pass
        if self.log:
            self.log.close()
        self.proc = None

    def heartbeat(self):
        f = self.dir / "sim" / "heartbeat"
        try:
            return f.stat().st_mtime
        except OSError:
            return 0.0

    def healthy(self):
        if self.proc is None or self.proc.poll() is not None:
            return False
        now = time.time()
        beat = self.heartbeat()
        if beat < self.started:
            return now - self.started < STARTUP_GRACE
        if now - beat > SILENT_LIMIT:
            return False
        if self.chunk and now - self.chunk_since > CHUNK_LIMIT:
            return False
        return True

    def ready(self):
        return self.chunk is None and self.heartbeat() >= self.started

    def submit(self, chunk):
        self.chunk = chunk
        self.chunk_since = time.time()
        tmp = self.dir / "sim" / "job.tmp"
        tmp.write_text(json.dumps({"id": chunk["id"], "genes": chunk["genes"], "runs": chunk["runs"], "profile": PROFILE}),
                       encoding="utf-8")
        os.replace(tmp, self.dir / "sim" / "job.json")

    def collect(self):
        """Results of the current chunk if it is done, else None."""
        if not self.chunk:
            return None
        cid = self.chunk["id"]
        done = self.dir / "sim" / f"done-{cid}"
        if not done.exists():
            return None
        res = self.dir / "sim" / f"result-{cid}.jsonl"
        rows = [json.loads(l) for l in res.read_text(encoding="utf-8").splitlines() if l.strip()]
        done.unlink()
        res.unlink()
        chunk = self.chunk
        self.chunk = None
        return chunk, rows


class Pool:
    def __init__(self, n, mem, realtime=False):
        self.instances = [Instance(i, mem, realtime) for i in range(1, n + 1)]
        for inst in self.instances:
            inst.start()
            time.sleep(8)  # do not load all worlds at the same moment

    def stop(self):
        for inst in self.instances:
            inst.stop()

    def defaults(self, timeout=600):
        """Baritone values of today's settings, written by the first instance that is up."""
        end = time.time() + timeout
        while time.time() < end:
            for inst in self.instances:
                f = inst.dir / "sim" / "settings.json"
                if f.exists() and f.stat().st_mtime >= inst.started - 1:
                    try:
                        return json.loads(f.read_text(encoding="utf-8"))
                    except json.JSONDecodeError:
                        pass
                if not inst.healthy():
                    log(f"instance {inst.i} did not come up - see {inst.dir / 'stdout.log'}")
                    inst.stop()
                    inst.start()
            time.sleep(2)
        raise SystemExit("no instance came up (see run-sim/inst-*/stdout.log)")

    def ticks_per_second(self):
        now, total = time.time(), 0.0
        for inst in self.instances:
            try:
                ticks = int((inst.dir / "sim" / "heartbeat").read_text().split()[1])
            except (OSError, ValueError, IndexError):
                continue
            last = getattr(inst, "_tps", None)
            if last and now > last[0]:
                total += max(0, ticks - last[1]) / (now - last[0])
            inst._tps = (now, ticks)
        return total

    def evaluate(self, chunks, on_chunk=None):
        """Runs all chunks on the instances; returns {chunk id: rows}."""
        todo = list(chunks)
        results = {}
        total = sum(len(c["runs"]) for c in chunks)
        last_print = time.time()
        while todo or any(inst.chunk for inst in self.instances):
            for inst in self.instances:
                got = inst.collect()
                if got:
                    chunk, rows = got
                    if len(rows) < len(chunk["runs"]):
                        done = {(r["level"], r["seed"]) for r in rows}
                        rows += [{"level": l, "seed": s, "result": "FAILED", "ticks": 0, "status": "missing"}
                                 for l, s in chunk["runs"] if (l, s) not in done]
                    results[chunk["id"]] = rows
                    if on_chunk:
                        on_chunk(chunk, rows)
                if not inst.healthy():
                    if inst.chunk:
                        log(f"instance {inst.i} lost chunk {inst.chunk['id']} - again elsewhere")
                        todo.insert(0, inst.chunk)
                    event(f"restart instance {inst.i} ({'crashed' if inst.proc and inst.proc.poll() is not None else 'silent'})")
                    inst.stop()
                    inst.start()
                    continue
                if todo and inst.ready():
                    inst.submit(todo.pop(0))
            if time.time() - last_print > 60:
                n = sum(len(r) for r in results.values())
                tps = self.ticks_per_second()
                log(f"  {n}/{total} runs of this batch, {tps:.0f} game ticks/s over all instances "
                    f"({tps / 20:.0f}x real time)")
                last_print = time.time()
            time.sleep(0.3)
        return results


# ----------------------------------------------------------------------------- genome

def to_value(gene, u):
    u = min(1.0, max(0.0, float(u)))
    if gene["type"] == "bool":
        return 1.0 if u >= 0.5 else 0.0
    v = gene["min"] + u * (gene["max"] - gene["min"])
    return float(round(v)) if gene["type"] == "int" else round(v, 4)


def to_unit(gene, v):
    if "start_unit" in gene:
        return float(gene["start_unit"])  # a choice of variants: start in the middle, all get tried
    if gene["type"] == "bool":
        return 0.75 if v else 0.25
    span = gene["max"] - gene["min"]
    return min(1.0, max(0.0, (float(v) - gene["min"]) / span)) if span > 0 else 0.5


def genes_of(u):
    return {g["name"]: to_value(g, x) for g, x in zip(GENES, u)}


class CMA:
    """(mu/mu_w, lambda)-CMA-ES (Hansen's tutorial), maximising, on [0,1]^n (clipped)."""

    def __init__(self, x0, sigma, lam):
        n = len(x0)
        self.n = n
        self.lam = lam or 4 + int(3 * math.log(n))
        self.mu = self.lam // 2
        w = np.log(self.mu + 0.5) - np.log(np.arange(1, self.mu + 1))
        self.w = w / w.sum()
        self.mueff = 1.0 / np.sum(self.w ** 2)
        self.cc = (4 + self.mueff / n) / (n + 4 + 2 * self.mueff / n)
        self.cs = (self.mueff + 2) / (n + self.mueff + 5)
        self.c1 = 2 / ((n + 1.3) ** 2 + self.mueff)
        self.cmu = min(1 - self.c1, 2 * (self.mueff - 2 + 1 / self.mueff) / ((n + 2) ** 2 + self.mueff))
        self.damps = 1 + 2 * max(0.0, math.sqrt((self.mueff - 1) / (n + 1)) - 1) + self.cs
        self.chi = math.sqrt(n) * (1 - 1 / (4 * n) + 1 / (21 * n * n))
        self.mean = np.array(x0, dtype=float)
        self.sigma = sigma
        self.pc = np.zeros(n)
        self.ps = np.zeros(n)
        self.C = np.eye(n)
        self.gen = 0

    def _eig(self):
        self.C = np.triu(self.C) + np.triu(self.C, 1).T
        d, B = np.linalg.eigh(self.C)
        return B, np.sqrt(np.maximum(d, 1e-20))

    def ask(self, rng):
        B, D = self._eig()
        z = rng.standard_normal((self.lam, self.n))
        x = self.mean + self.sigma * (z * D) @ B.T
        return np.clip(x, 0.0, 1.0)

    def tell(self, xs, fit):
        n = self.n
        B, D = self._eig()
        order = np.argsort(-np.asarray(fit))
        old = self.mean.copy()
        sel = xs[order[:self.mu]]
        self.mean = self.w @ sel
        y = (self.mean - old) / self.sigma
        invsqrt = B @ np.diag(1 / D) @ B.T
        self.ps = (1 - self.cs) * self.ps + math.sqrt(self.cs * (2 - self.cs) * self.mueff) * invsqrt @ y
        hs = np.linalg.norm(self.ps) / math.sqrt(1 - (1 - self.cs) ** (2 * (self.gen + 1))) / self.chi < 1.4 + 2 / (n + 1)
        self.pc = (1 - self.cc) * self.pc + hs * math.sqrt(self.cc * (2 - self.cc) * self.mueff) * y
        art = (sel - old) / self.sigma
        self.C = ((1 - self.c1 - self.cmu) * self.C
                  + self.c1 * (np.outer(self.pc, self.pc) + (not hs) * self.cc * (2 - self.cc) * self.C)
                  + self.cmu * art.T @ np.diag(self.w) @ art)
        self.sigma *= math.exp((self.cs / self.damps) * (np.linalg.norm(self.ps) / self.chi - 1))
        self.sigma = float(min(0.6, max(0.002, self.sigma)))
        self.gen += 1

    def state(self):
        return {"mean": self.mean.tolist(), "sigma": self.sigma, "pc": self.pc.tolist(), "ps": self.ps.tolist(),
                "C": self.C.tolist(), "gen": self.gen, "lam": self.lam}

    @staticmethod
    def load(s):
        c = CMA(s["mean"], s["sigma"], s["lam"])
        c.pc, c.ps, c.C, c.gen = np.array(s["pc"]), np.array(s["ps"]), np.array(s["C"]), s["gen"]
        return c


# ----------------------------------------------------------------------------- fitness

def parse_levels(text):
    out = []
    for part in text.split(","):
        lvl, cnt = part.split(":")
        out.append((int(lvl), int(cnt)))
    return out


def run_set(levels, seed_rng, base=0):
    runs = []
    for lvl, cnt in levels:
        for _ in range(cnt):
            runs.append([lvl, base + seed_rng.randrange(1, 1_000_000)])
    return runs


def chunks_for(tag, genes, runs, size):
    long_runs = [r for r in runs if r[0] == 8]  # wolf hunts take long: alone
    short = [r for r in runs if r[0] != 8]
    out = []
    for r in long_runs:
        out.append([r])
    for i in range(0, len(short), size):
        out.append(short[i:i + size])
    return [{"id": f"{tag}-{k}", "tag": tag, "genes": genes, "runs": c} for k, c in enumerate(out)]


def score(rows_by_tag, tags):
    """Survivors first, speed only as tie-break: fitness = successes + 0.999 * mean speed bonus (< 1)."""
    ref = {}
    for tag in tags:
        for r in rows_by_tag.get(tag, []):
            if r["result"] == "ARRIVED" and r["ticks"] > 0:
                ref.setdefault((r["level"], r["seed"]), []).append(r["ticks"])
    ref = {k: float(np.median(v)) for k, v in ref.items()}
    out = {}
    for tag in tags:
        rows = rows_by_tag.get(tag, [])
        ok = [r for r in rows if r["result"] == "ARRIVED" and r["ticks"] > 0]
        speed = sum(min(ref[(r["level"], r["seed"])] / r["ticks"], 2.0) / 2.0 for r in ok)
        n = max(1, len(rows))
        out[tag] = {"fitness": len(ok) + 0.999 * speed / n, "success": len(ok), "runs": len(rows),
                    "ticks": sum(r["ticks"] for r in ok) / max(1, len(ok)),
                    "by_level": by_level(rows)}
    return out


def by_level(rows):
    d = {}
    for r in rows:
        s = d.setdefault(r["level"], [0, 0])
        s[1] += 1
        s[0] += r["result"] == "ARRIVED"
    return {str(k): f"{v[0]}/{v[1]}" for k, v in sorted(d.items())}


def evaluate_genomes(pool, named, runs, chunk_size, gen=None):
    """named: {tag: genes}; every genome on the same runs. Every run goes to runs.jsonl (for report)."""
    chunks = []
    for tag, genes in named.items():
        chunks += chunks_for(tag, genes, runs, chunk_size)
    # every finished chunk goes to runs.jsonl at once: a stopped generation is not lost for report
    res = pool.evaluate(chunks, (lambda c, rs: record_runs(gen, c["tag"], rs)) if gen is not None else None)
    rows = {}
    for c in chunks:
        rows.setdefault(c["tag"], []).extend(res.get(c["id"], []))
    return rows


def record_runs(gen, tag, rs):
    EVO.mkdir(parents=True, exist_ok=True)
    with open(EVO / "runs.jsonl", "a", encoding="utf-8") as out:
        for r in rs:
            out.write(json.dumps({"g": gen, "tag": tag, "l": r["level"], "s": r["seed"],
                                  "ok": r["result"] == "ARRIVED", "t": r["ticks"], "h": r.get("human"),
                                  "p": r.get("pos"), "why": r.get("status", "")[:90]}) + "\n")


# ----------------------------------------------------------------------------- main loop

def load_state():
    f = EVO / "state.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else None


def save_state(st):
    EVO.mkdir(parents=True, exist_ok=True)
    tmp = EVO / "state.tmp"
    tmp.write_text(json.dumps(st), encoding="utf-8")
    os.replace(tmp, EVO / "state.json")


def append(name, line, header):
    f = EVO / name
    new = not f.exists()
    with open(f, "a", encoding="utf-8") as out:
        if new:
            out.write(header + "\n")
        out.write(line + "\n")


def set_profile(args):
    PROFILE["speed"], PROFILE["jump"] = args.speed, args.jump


def cmd_run(args):
    set_profile(args)
    if not (SIM / "snapshot/launch.json").exists():
        raise SystemExit("run setup first: python tools/sim/evolve.py setup --instances N")
    for i in range(1, args.instances + 1):
        if not (SIM / f"inst-{i}" / "saves" / WORLD).exists():
            make_instance(i, False)
    levels = parse_levels(args.levels)
    holdout_levels = levels if getattr(args, "smoke", False) else [(l, max(2, c)) for l, c in levels]
    st = load_state()
    pool = Pool(args.instances, args.mem)
    try:
        if st is None:
            old = [f for f in ("generations.tsv", "holdout.tsv", "runs.jsonl", "events.log", "candidates.jsonl")
                   if (EVO / f).exists()]
            if old:
                arch = EVO / f"old-{time.strftime('%Y%m%d-%H%M%S')}"
                arch.mkdir(parents=True)
                for f in old:
                    shutil.move(EVO / f, arch / f)
            defaults = pool.defaults()
            x0 = [to_unit(g, defaults.get(g["name"], (g.get("min", 0) + g.get("max", 1)) / 2)) for g in GENES]
            cma = CMA(x0, args.sigma, args.lam)
            st = {"cma": cma.state(), "runs": 0, "best_holdout": None, "baseline_genes": genes_of(x0),
                  "started": time.time()}
            save_state(st)
            log(f"start: {len(GENES)} genes, lambda {cma.lam}, from today's settings")
        cma = CMA.load(st["cma"])
        t0, runs0 = time.time(), st["runs"]
        gens_done = 0
        while st["runs"] < args.total_runs and (args.gens <= 0 or gens_done < args.gens):
            gen = cma.gen
            seed_rng = random.Random(gen * 1_000_003 + 17)
            runs = run_set(levels, seed_rng)
            rng = np.random.default_rng(gen * 7919 + 1)
            xs = cma.ask(rng)
            named = {f"g{gen}c{k}": genes_of(x) for k, x in enumerate(xs)}
            rows = evaluate_genomes(pool, named, runs, args.chunk, gen)
            sc = score(rows, list(named))
            fit = [sc[t]["fitness"] for t in named]
            with open(EVO / "candidates.jsonl", "a", encoding="utf-8") as cf:
                for t in named:
                    cf.write(json.dumps({"g": gen, "tag": t, "genes": named[t], "ok": sc[t]["success"],
                                         "n": sc[t]["runs"], "fit": round(sc[t]["fitness"], 4)}) + "\n")
            cma.tell(xs, fit)
            st["runs"] += sum(len(r) for r in rows.values())
            best_tag = max(named, key=lambda t: sc[t]["fitness"])
            b = sc[best_tag]
            rate = (st["runs"] - runs0) / max(1.0, time.time() - t0)
            eta = (args.total_runs - st["runs"]) / max(rate, 1e-9) / 3600
            log(f"gen {gen}: best {b['success']}/{b['runs']} ok, {b['ticks'] / 20:.1f}s avg "
                f"{b['by_level']} | mean success {np.mean([sc[t]['success'] for t in named]):.1f} | "
                f"sigma {cma.sigma:.3f} | {st['runs']} runs, {rate:.2f} runs/s, ~{eta:.1f} h left")
            append("generations.tsv", "\t".join(map(str, [
                gen, st["runs"], f"{b['fitness']:.4f}", b["success"], b["runs"], f"{b['ticks']:.0f}",
                json.dumps(b["by_level"]), f"{cma.sigma:.4f}", json.dumps(named[best_tag])])),
                   "gen\truns\tbest_fitness\tbest_success\truns_per_genome\tbest_avg_ticks\tby_level\tsigma\tbest_genes")
            (EVO / "last_best_candidate.json").write_text(json.dumps({"gen": gen, "score": b, "genes": named[best_tag]},
                                                                     indent=1), encoding="utf-8")
            st["cma"] = cma.state()
            save_state(st)
            gens_done += 1
            if cma.gen % args.holdout == 0 or st["runs"] >= args.total_runs:
                holdout(pool, cma, st, holdout_levels, args.chunk)
                save_state(st)
        log("done" if st["runs"] >= args.total_runs else "stopped after the requested generations")
    except KeyboardInterrupt:
        log("stopped (state is saved after every generation; run again to continue)")
    finally:
        pool.stop()


def holdout(pool, cma, st, levels, chunk):
    runs = run_set(levels, random.Random(424242), HOLDOUT_SEED_BASE)
    mean_genes = genes_of(cma.mean)
    rows = evaluate_genomes(pool, {"mean": mean_genes, "baseline": {}}, runs, chunk, f"H{cma.gen}")
    st["runs"] += sum(len(r) for r in rows.values())
    sc = score(rows, ["mean", "baseline"])
    m, b = sc["mean"], sc["baseline"]
    log(f"HOLDOUT gen {cma.gen}: evolved {m['success']}/{m['runs']} ok {m['ticks'] / 20:.1f}s {m['by_level']} | "
        f"today's settings {b['success']}/{b['runs']} ok {b['ticks'] / 20:.1f}s {b['by_level']}")
    append("holdout.tsv", "\t".join(map(str, [cma.gen, st["runs"], m["success"], m["runs"], f"{m['ticks']:.0f}",
                                              b["success"], b["runs"], f"{b['ticks']:.0f}", json.dumps(m["by_level"]),
                                              json.dumps(b["by_level"])])),
           "gen\truns\tevolved_success\truns\tevolved_avg_ticks\tbaseline_success\truns\tbaseline_avg_ticks\tevolved_by_level\tbaseline_by_level")
    best = st.get("best_holdout")
    if m["fitness"] <= b["fitness"]:
        log("evolved genome not better than today's settings on the holdout - best_genome.json unchanged")
    elif best is None or m["fitness"] > best["fitness"]:
        st["best_holdout"] = {"fitness": m["fitness"], "gen": cma.gen, "success": m["success"], "runs": m["runs"],
                              "ticks": m["ticks"], "baseline_success": b["success"], "baseline_ticks": b["ticks"]}
        (EVO / "best_genome.json").write_text(json.dumps({"gen": cma.gen, "holdout": m, "baseline": b,
                                                          "genes": mean_genes}, indent=1), encoding="utf-8")
        log(f"new best genome -> {EVO / 'best_genome.json'}")


def cmd_smoke(args):
    args.instances, args.total_runs, args.gens = 1, 10 ** 9, 1
    # one generation of 2 genomes, then evolved mean vs today's settings on the same runs
    args.lam, args.levels, args.holdout, args.chunk, args.smoke = 2, "5:1,6:1,9:1,8:1", 1, 4, True
    if (EVO / "state.json").exists():
        shutil.move(EVO / "state.json", EVO / f"state-before-smoke-{int(time.time())}.json")
    cmd_run(args)
    f = EVO / "state.json"
    if f.exists():
        f.unlink()  # a smoke test is no evolution start
    log("smoke test finished - results above, details in run-sim/inst-1/stdout.log")


def cmd_check(args):
    """Today's settings on fixed runs - with --realtime without turbo: both must give the same results."""
    set_profile(args)
    runs = run_set(parse_levels(args.levels), random.Random(args.seed), HOLDOUT_SEED_BASE)
    pool = Pool(args.instances, args.mem, args.realtime)
    try:
        genes = json.loads(args.genes) if args.genes else {}
        rows = evaluate_genomes(pool, {"baseline": genes}, runs, args.chunk)["baseline"]
    finally:
        pool.stop()
    mode = "real time" if args.realtime else "turbo"
    for r in sorted(rows, key=lambda r: (r["level"], r["seed"])):
        print(f"  level {r['level']} seed {r['seed']}: {r['result']:8s} {r['ticks'] / 20:6.1f}s  {r.get('status', '')}")
    ok = sum(r["result"] == "ARRIVED" for r in rows)
    log(f"check ({mode}): {ok}/{len(rows)} ok {by_level(rows)}")


def norm_reason(why):
    """'timeout 300s: wolves left 34, searching' -> 'timeout: wolves left N, searching'."""
    import re
    w = re.sub(r"\d+(\.\d+)?", "N", why or "")
    w = re.sub(r"timeout Ns: ", "timeout: ", w)
    return w[:60]


def cmd_report(args):
    """Short summary for checking an evolution without reading logs (~50 lines)."""
    st = load_state()
    rows = []
    f = EVO / "runs.jsonl"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    if not rows:
        print("no runs yet")
        return
    gens = sorted({r["g"] for r in rows if isinstance(r["g"], int)})
    took = time.time() - st["started"] if st and st.get("started") else 0
    print(f"== {len(rows)} runs, generations {gens[0] if gens else '-'}..{gens[-1] if gens else '-'}"
          + (f", sigma {st['cma']['sigma']:.3f}" if st else "")
          + (f", {len(rows) / took:.2f} runs/s over {took / 3600:.1f} h" if took > 60 else ""))
    # success per level: first vs last generations (all candidates of those generations)
    k = max(1, args.window)
    first = [r for r in rows if r["g"] in gens[:k]]
    last = [r for r in rows if r["g"] in gens[-k:]]
    print(f"\n-- level: success first {k} gens -> last {k} gens | median time ok (human)")
    for lvl in sorted({r["l"] for r in rows}):
        a = [r for r in first if r["l"] == lvl]
        b = [r for r in last if r["l"] == lvl]
        ok_b = [r["t"] for r in b if r["ok"]]
        hum = [r["h"] for r in b if r["ok"] and r.get("h")]
        def rate(x):
            return f"{100 * sum(r['ok'] for r in x) / len(x):5.1f}% of {len(x):4d}" if x else "      -      "
        med = f"{np.median(ok_b) / 20:5.1f}s" if ok_b else "   - "
        hm = f" ({np.median(hum):.1f}s)" if hum else ""
        print(f"  {lvl}: {rate(a)} -> {rate(b)} | {med}{hm}")
    # why runs fail (last window) and where
    fails = [r for r in last if not r["ok"]]
    print(f"\n-- failures, last {k} gens: {len(fails)}")
    for lvl in sorted({r["l"] for r in fails}):
        fl = [r for r in fails if r["l"] == lvl]
        reasons = {}
        for r in fl:
            reasons[norm_reason(r["why"])] = reasons.get(norm_reason(r["why"]), 0) + 1
        top = sorted(reasons.items(), key=lambda kv: -kv[1])[:3]
        print(f"  level {lvl} ({len(fl)}): " + "; ".join(f"{n}x {w}" for w, n in top))
        spots = {}
        for r in fl:
            if r.get("p"):
                key = tuple(int(v // 3 * 3) for v in r["p"])
                spots[key] = spots.get(key, 0) + 1
        hot = sorted(spots.items(), key=lambda kv: -kv[1])[:3]
        if hot:
            print("      ended at: " + ", ".join(f"{n}x ~{list(p)}" for p, n in hot))
    # variants (choice genes): success rate per chosen value, last window
    cf = EVO / "candidates.jsonl"
    if cf.exists():
        cands = [json.loads(l) for l in cf.read_text(encoding="utf-8").splitlines() if l.strip()]
        recent = [c for c in cands if c["g"] in gens[-max(k, 5):]]
        choice = [g for g in GENES if g["type"] == "bool" or (g["type"] == "int" and g["max"] - g["min"] <= 3)]
        lines = []
        for g in choice:
            per = {}
            for c in recent:
                v = c["genes"].get(g["name"])
                if v is None:
                    continue
                a = per.setdefault(int(v), [0, 0])
                a[0] += c["ok"]
                a[1] += c["n"]
            if len(per) > 1:
                lines.append(f"  {g['name']}: " + "  ".join(f"{v}={100 * a[0] / max(1, a[1]):.0f}% ({a[1]})"
                                                          for v, a in sorted(per.items())))
        if lines:
            print(f"\n-- variants, success per value (last {max(k, 5)} gens, runs in brackets)")
            print("\n".join(lines))
    # holdout: evolved vs today's settings
    hf = EVO / "holdout.tsv"
    if hf.exists():
        lines = hf.read_text(encoding="utf-8").splitlines()[1:]
        print("\n-- holdout (evolved vs today's settings): gen | ok | avg s")
        for line in lines[-3:]:
            c = line.split("\t")
            print(f"  gen {c[0]}: {c[2]}/{c[3]} vs {c[5]}/{c[6]} | {float(c[4]) / 20:.1f}s vs {float(c[7]) / 20:.1f}s")
    # genes that moved most from the start
    if st:
        start = st.get("baseline_genes", {})
        mean = genes_of(st["cma"]["mean"]) if len(st["cma"]["mean"]) == len(GENES) else {}
        moved = []
        for g in GENES:
            a, b = start.get(g["name"]), mean.get(g["name"])
            if a is None or b is None:
                continue
            span = 1.0 if g["type"] == "bool" else (g["max"] - g["min"]) or 1.0
            moved.append((abs(b - a) / span, g["name"], a, b))
        moved.sort(reverse=True)
        print("\n-- genes moved most (start -> mean now)")
        for d, name, a, b in moved[:8]:
            if d > 0.02:
                print(f"  {name}: {a:g} -> {b:g}")
    # health: restarts, crashes, disk
    ef = EVO / "events.log"
    restarts = ef.read_text(encoding="utf-8").count("restart") if ef.exists() else 0
    since = st.get("started", 0) if st else 0
    crash_files = [p for d in SIM.glob("inst-*") for p in (d / "crash-reports").glob("*.txt") if p.stat().st_mtime >= since]
    crashes = len(crash_files)
    size = sum(p.stat().st_size for p in SIM.rglob("*") if p.is_file() and "saves" not in p.parts) / 1e6
    print(f"\n-- health: {restarts} restarts, {crashes} crash reports, logs+results {size:.0f} MB")
    if crashes:
        newest = max(crash_files, key=lambda p: p.stat().st_mtime)
        head = [l for l in newest.read_text(encoding="utf-8", errors="replace").splitlines()[:12] if l.strip()]
        print(f"   newest {newest.relative_to(SIM)}: " + " | ".join(head[2:6])[:300])


def cmd_status(_args):
    st = load_state()
    if not st:
        print("no evolution yet")
        return
    print(f"generation {st['cma']['gen']}, {st['runs']} runs, sigma {st['cma']['sigma']:.3f}")
    if st.get("best_holdout"):
        b = st["best_holdout"]
        print(f"best holdout (gen {b['gen']}): {b['success']}/{b['runs']} ok, {b['ticks'] / 20:.1f}s "
              f"| today's settings {b['baseline_success']}/{b['runs']} ok, {b['baseline_ticks'] / 20:.1f}s")
    f = EVO / "generations.tsv"
    if f.exists():
        print("last generations:")
        for line in f.read_text(encoding="utf-8").splitlines()[-5:]:
            print("  " + line[:160])


def cmd_install(args):
    src = EVO / "best_genome.json"
    if not src.exists():
        raise SystemExit("no best_genome.json yet (comes with the first holdout)")
    genes = json.loads(src.read_text(encoding="utf-8"))["genes"]
    targets = [RUN / "config" / "baritone-genome.json"]
    if args.to:
        targets.append(Path(args.to) / "config" / "baritone-genome.json")
    for t in targets:
        t.parent.mkdir(parents=True, exist_ok=True)
        t.write_text(json.dumps({"genes": genes}, indent=1), encoding="utf-8")
        log(f"installed -> {t}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("setup")
    s.add_argument("--instances", type=int, default=4)
    s.add_argument("--fresh", action="store_true", help="copy the world again")
    for name in ("run", "smoke"):
        r = sub.add_parser(name)
        r.add_argument("--instances", type=int, default=4)
        r.add_argument("--total-runs", type=int, default=500_000)
        r.add_argument("--gens", type=int, default=0, help="stop after this many generations (0 = until total-runs)")
        r.add_argument("--lam", type=int, default=16, help="genomes per generation")
        r.add_argument("--sigma", type=float, default=0.2, help="start step size (0..1 of each range)")
        r.add_argument("--levels", default=LEVELS_DEFAULT, help="runs per genome: level:count,...")
        r.add_argument("--holdout", type=int, default=20, help="holdout check every N generations")
        r.add_argument("--chunk", type=int, default=5, help="runs per job sent to one instance")
        r.add_argument("--mem", default="3G", help="max heap per instance")
        r.add_argument("--speed", type=int, default=14, help="Speed amplifier (14 = Speed XV = SkyBlock 400 %%)")
        r.add_argument("--jump", type=int, default=5, help="Jump Boost amplifier (5 = VI = SkyBlock jump 6.1)")
    c = sub.add_parser("check", help="today's settings on fixed runs (turbo or --realtime)")
    c.add_argument("--instances", type=int, default=1)
    c.add_argument("--levels", default="10:2,11:2")
    c.add_argument("--seed", type=int, default=7)
    c.add_argument("--realtime", action="store_true")
    c.add_argument("--genes", help='settings to try, JSON, e.g. {"highAscendMaxBlocks": 5}')
    c.add_argument("--chunk", type=int, default=5)
    c.add_argument("--mem", default="3G")
    c.add_argument("--speed", type=int, default=14)
    c.add_argument("--jump", type=int, default=5)
    sub.add_parser("status")
    rp = sub.add_parser("report", help="short summary of the evolution (for checking results)")
    rp.add_argument("--window", type=int, default=3, help="generations compared at start and end")
    i = sub.add_parser("install")
    i.add_argument("--to", help="also into this Minecraft instance folder (e.g. the CurseForge one)")
    args = ap.parse_args()
    {"setup": cmd_setup, "run": cmd_run, "check": cmd_check, "report": cmd_report, "smoke": cmd_smoke, "status": cmd_status, "install": cmd_install}[args.cmd](args)


if __name__ == "__main__":
    main()
