"""Usage: python tools/mining/build_dwarven_graph.py src/client/resources/assets/coflmod/mining/dwarven_graph.json [recordings folder]

Builds the Dwarven Mines travel map from the Interaction Logger recordings ("Google Maps" style):

* Stations: every place the player stood (from all recordings) is grouped on a ~4 block grid and
  put on the real floor (from the recorded blocks). They are clean points, not the wobbly line
  that was walked.
* Links between stations, checked against the recorded blocks:
  - walk: a straight walkable line (floor all the way, head room, steps up to one block, drops ok)
  - tp: an AOTV teleport along a free straight line that lands on the station (5-12 blocks)
  - ew: an etherwarp onto the station's floor block that the eye can see (going up, or far)
  - plus every station-to-station move that was really done in a recording (walk or tp)
* Dijkstra over these links (in the client) then picks the quickest mix of walking and warps.
"""
import glob
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")
OUT = sys.argv[1]
DIR = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.expanduser("~"), "curseforge", "minecraft",
                                                          "Instances", "test", "logs", "interactions")
FMT = re.compile(r"§.")
PUA = re.compile(r"[-⏣]")

GRID = 4.0
EYE = 1.62
TP_REACH = 12.0
TP_MIN = 5.0
EW_MAX = 40.0
WALK_LINK = 9.0

# costs in seconds
WALK_SPEED = 4.6
TP_COST = 0.55
EW_COST = 1.25


def kind_of(name):
    if name in ("light", "short_grass", "tall_grass", "torch", "wall_torch", "rail", "red_mushroom", "brown_mushroom",
                "crimson_roots", "fire", "cactus_flower", "ladder", "stone_button", "light_weighted_pressure_plate",
                "stone_pressure_plate", "water") or name.endswith("_carpet") or name.endswith("_sign"):
        return "pass"
    if name == "lava":
        return "lava"
    if name.endswith("_fence") or name.endswith("_wall") or name.endswith("_fence_gate") or name == "iron_bars":
        return "tall"
    if name.endswith("_slab") or name.endswith("_stairs") or name in ("hopper", "cauldron", "anvil", "player_head",
                                                                         "iron_trapdoor", "mangrove_shelf"):
        return "half"
    return "full"


def area_of(lines):
    cl = [FMT.sub("", l).strip() for l in lines]
    for i, c in enumerate(cl):
        if re.search(r"\d+:\d+(am|pm)", c) and i + 1 < len(cl):
            return PUA.sub("", cl[i + 1]).strip()
    return None


# ------------------------------------------------------------------ load everything
blocks = {}          # (x,y,z) -> kind
ew_points = []       # recordings "ew1", "ew2" ...: the player stands on a block that is a great etherwarp target
mining_centers = []  # recordings "mining pos ...": the middle of a good mining area
known = set()        # 2x2 columns near any recorded point (voxels there were scanned)
ground_points = []   # (x, y, z, area)
moves = []           # consecutive ground points of one recording: (p, q, teleported)
mines = []

for fn in sorted(glob.glob(os.path.join(DIR, "*.json"))):
    d = json.load(open(fn, encoding="utf-8"))
    # areas look like "dwarven mines" or (newer logger) "dwarven mines / the forge"
    if not any(a.split(" / ")[0] == "dwarven mines" for a in d.get("areas", [])):
        continue
    name = d.get("name", "").lower().strip()
    if re.fullmatch(r"ew ?\d*", name) and d["path"]:
        q = d["path"][-1]
        # the block under the feet (a slab's top is half a block up)
        ew_points.append([math.floor(q[1]), math.floor(q[2] - 0.3), math.floor(q[3])])
        continue
    if re.fullmatch(r"min\w* ?pos ?\d*", name) and d["path"]:
        q = d["path"][-1]
        mining_centers.append([round(q[1], 1), round(q[2], 1), round(q[3], 1)])
        continue
    pal = d["map"]["palette"]
    for x, y, z, i in d["map"]["blocks"]:
        blocks[(x, y, z)] = kind_of(pal[i])
    events = d["events"]
    area_times = []
    for e in events:
        if e["type"] == "sidebar":
            a = area_of(e.get("lines", []))
            if a:
                area_times.append((e["t"], a))
    tp_times = [e["t"] for e in events if e["type"] == "teleport"]
    breaks = [e["t"] for e in events if (e["type"] == "command" and "warp" in e.get("command", ""))
              or (e["type"] == "interact_entity" and "RIGHT CLICK" in (e["entity"].get("labels") or []))]

    def area_at(t):
        cur = None
        for at, a in area_times:
            if at <= t + 0.5:
                cur = a
            else:
                break
        return cur

    for e in events:
        if e["type"] == "mine_start" and e["block"] != "bedrock":
            mines.append((area_at(e["t"]), *e["blockPos"], e["block"]))
    last = None
    for p in d["path"]:
        t, x, y, z, on_ground = p[0], p[1], p[2], p[3], p[6]
        for dx in range(-8, 9, 2):
            for dz in range(-8, 9, 2):
                known.add((int(x + dx) >> 1, int(z + dz) >> 1))
        if any(abs(t - b) < 1.5 for b in breaks):
            last = None
        if not on_ground:
            continue
        a = area_at(t)
        ground_points.append((x, y, z, a))
        if last is None:
            last = (t, x, y, z)
            continue
        lt, lx, ly, lz = last
        dist = math.dist((lx, ly, lz), (x, y, z))
        if dist < GRID:
            continue  # keep the anchor until we really moved on
        tp = any(lt - 0.15 <= tt <= t + 0.15 for tt in tp_times)
        if dist < 15 or (tp and dist < 60):
            moves.append(((lx, ly, lz), (x, y, z), tp))
        last = (t, x, y, z)

print("blocks", len(blocks), "ground points", len(ground_points), "moves", len(moves))


def solid(x, y, z):
    k = blocks.get((x, y, z))
    return k is not None and k not in ("pass",)


def is_known(x, z):
    return (int(math.floor(x)) >> 1, int(math.floor(z)) >> 1) in known


def floor_top(x, y, z):
    """Height of the floor under feet position (x, y, z), searching 1 up and 4 down; None if none."""
    bx, bz = int(math.floor(x)), int(math.floor(z))
    by = int(math.floor(y + 0.01))
    for vy in range(by + 1, by - 5, -1):
        k = blocks.get((bx, vy, bz))
        if k is None or k == "pass":
            continue
        if k == "lava":
            return None
        top = vy + (0.5 if k == "half" else 1.5 if k == "tall" else 1.0)
        if k == "tall":
            return None
        # head room: two blocks above the top must be free
        if solid(bx, int(math.floor(top + 0.01)), bz) and blocks.get((bx, int(math.floor(top + 0.01)), bz)) != "half":
            continue
        if solid(bx, int(math.floor(top + 1.0)), bz) or solid(bx, int(math.floor(top + 1.7)), bz):
            continue
        return top
    return None


# ------------------------------------------------------------------ stations
cells = defaultdict(list)
for x, y, z, a in ground_points:
    cells[(math.floor(x / GRID), math.floor(z / GRID), round(y / 3))].append((x, y, z, a))
stations = []
for key, pts in cells.items():
    # the point nearest to the cell centre, on the floor
    cx = (key[0] + 0.5) * GRID
    cz = (key[1] + 0.5) * GRID
    pts.sort(key=lambda p: math.hypot(p[0] - cx, p[2] - cz))
    x, y, z, _ = pts[0]
    # a clean point: centre of the block the player stood on
    sx, sz = math.floor(x) + 0.5, math.floor(z) + 0.5
    top = floor_top(sx, y, sz)
    if top is None:
        top = floor_top(x, y, z)
        sx, sz = x, z
    if top is None:
        top = y  # floor not recorded: trust the recording
    areas = Counter(p[3] for p in pts if p[3])
    stations.append([round(sx, 2), round(top, 2), round(sz, 2), areas.most_common(1)[0][0] if areas else ""])
print("stations", len(stations))

sgrid = defaultdict(list)
for i, s in enumerate(stations):
    sgrid[(int(s[0] // 8), int(s[2] // 8))].append(i)


def near_stations(p, radius):
    cx, cz = int(p[0] // 8), int(p[2] // 8)
    r = int(radius // 8) + 1
    out = []
    for dx in range(-r, r + 1):
        for dz in range(-r, r + 1):
            for i in sgrid.get((cx + dx, cz + dz), ()):
                d = math.dist(stations[i][:3], p)
                if d <= radius:
                    out.append((d, i))
    out.sort()
    return out


def station_of(p):
    best = near_stations(p, 4.5)
    return best[0][1] if best else None


# ------------------------------------------------------------------ link checks
JUMP_PENALTY = 2.5  # seconds: in the mines a jump (stairs, a ledge) goes wrong far more often than an etherwarp


def walk_ok(a, b):
    """Straight walk from station a to b: floor all the way, head room, steps up <= 1, drops <= 4.
    Returns None when not walkable, else the biggest step up (> 0.6 = needs a jump)."""
    ax, ay, az = a[:3]
    bx, by, bz = b[:3]
    dist = math.hypot(bx - ax, bz - az)
    if dist < 0.5:
        return None
    steps = max(2, int(dist / 0.4))
    prev = ay
    biggest = 0.0
    for i in range(1, steps + 1):
        f = i / steps
        x, z = ax + (bx - ax) * f, az + (bz - az) * f
        if not is_known(x, z):
            return None
        expect = prev + 0.6
        top = floor_top(x, expect, z)
        if top is None or top - prev > 1.05 or prev - top > 4.0:
            return None
        biggest = max(biggest, top - prev)
        prev = top
    return biggest if abs(prev - by) < 1.1 else None


def free_tp(x, y, z):
    k = blocks.get((int(math.floor(x)), int(math.floor(y)), int(math.floor(z))))
    return k is None or k == "pass"


def tp_ok(a, b):
    """AOTV from station a aimed at b's eye height: the ray stays free and lands on b."""
    ax, ay, az = a[:3]
    bx, by, bz = b[:3]
    ex, ey, ez = ax, ay + EYE, az
    tx, ty, tz = bx, by + EYE, bz
    d = math.dist((ex, ey, ez), (tx, ty, tz))
    if d < TP_MIN or d > TP_REACH:
        return False
    ux, uy, uz = (tx - ex) / d, (ty - ey) / d, (tz - ez) / d
    last = None
    t = 0.5
    while t <= TP_REACH + 1e-6:
        px, py, pz = ex + ux * t, ey + uy * t, ez + uz * t
        if not is_known(px, pz):
            return False
        if not free_tp(px, py, pz) or not free_tp(px, py - 1, pz):
            break
        last = (math.floor(px) + 0.5, math.floor(py) - 1.0, math.floor(pz) + 0.5)
        t += 0.5
    if last is None:
        return False
    if math.hypot(last[0] - bx, last[2] - bz) > 1.6:
        return False
    land = floor_top(last[0], last[1], last[2])
    return land is not None and abs(land - by) < 1.1


def ew_ok(a, b):
    """Etherwarp from a onto the floor block of b: the line from the eye to its top is free."""
    ax, ay, az = a[:3]
    bx, by, bz = b[:3]
    ex, ey, ez = ax, ay + EYE, az
    tx, ty, tz = math.floor(bx) + 0.5, by - 0.02, math.floor(bz) + 0.5
    d = math.dist((ex, ey, ez), (tx, ty, tz))
    if d > EW_MAX or d < 4:
        return False
    target = (int(math.floor(bx)), int(math.floor(by - 0.5)), int(math.floor(bz)))
    if not solid(*target):
        return False
    steps = int(d / 0.3)
    for i in range(1, steps):
        f = i / steps
        px, py, pz = ex + (tx - ex) * f, ey + (ty - ey) * f, ez + (tz - ez) * f
        if not is_known(px, pz):
            return False
        v = (int(math.floor(px)), int(math.floor(py)), int(math.floor(pz)))
        if v != target and solid(*v):
            return False
    return True


edges = {}


def add(a, b, mode, cost):
    old = edges.get((a, b))
    if old is None or cost < old[1]:
        edges[(a, b)] = (mode, cost)


# recorded moves (always possible: they were done)
for p, q, tp in moves:
    a, b = station_of(p), station_of(q)
    if a is None or b is None or a == b:
        continue
    d = math.dist(stations[a][:3], stations[b][:3])
    if tp:
        add(a, b, 1, TP_COST * max(1.0, d / TP_REACH))
    else:
        up = stations[b][1] - stations[a][1]
        add(a, b, 0, d / WALK_SPEED + max(0.0, up) * 0.3 + (JUMP_PENALTY if up > 0.6 else 0.0))

checked = 0
for a, s in enumerate(stations):
    for d, b in near_stations(s[:3], EW_MAX):
        if a == b:
            continue
        t = stations[b]
        dy = t[1] - s[1]
        checked += 1
        up = walk_ok(s, t) if d <= WALK_LINK else None
        if up is not None:
            # mode 3 = checked straight line: walked straight ahead (stairs included), no pathfinder.
            # A bit cheaper than an unchecked walk, so clean lines win.
            add(a, b, 3, d / WALK_SPEED * 0.95 + max(0.0, dy) * 0.3 + (JUMP_PENALTY if up > 0.6 else 0.0))
        if TP_MIN <= d <= TP_REACH + 1 and tp_ok(s, t):
            add(a, b, 1, TP_COST)
        if (dy >= 2.0 or d >= 14) and ew_ok(s, t):
            # only computed from the blocks, never done: a bit dearer than a shown etherwarp
            add(a, b, 2, EW_COST * 1.5 + d * 0.01)
print("pairs checked", checked)

# ------------------------------------------------------------------ the player's etherwarp points
# Each one becomes a stop; every stop that can see its block gets a cheap etherwarp to it, so the
# planner uses them whenever they are on the way.
EW_POINT_COST = 0.45
ew_links = 0
for bx, by, bz in ew_points:
    k = blocks.get((bx, by, bz))
    top = by + (0.5 if k == "half" else 1.0)
    i = len(stations)
    stations.append([bx + 0.5, top, bz + 0.5, ""])
    sgrid[(int((bx + 0.5) // 8), int((bz + 0.5) // 8))].append(i)
    for d, j in near_stations(stations[i][:3], 45.0):
        if j == i:
            continue
        if ew_ok(stations[j], stations[i]):
            add(j, i, 2, EW_POINT_COST + d * 0.003)
            ew_links += 1
        if d <= 4.0:
            add(i, j, 3 if walk_ok(stations[i], stations[j]) is not None else 0, d / WALK_SPEED)
            add(j, i, 3 if walk_ok(stations[j], stations[i]) is not None else 0, d / WALK_SPEED)
    # from the point on: what can be seen / walked from there like from any stop
    for d, j in near_stations(stations[i][:3], EW_MAX):
        if j != i and (stations[j][1] - top >= 2.0 or d >= 14) and ew_ok(stations[i], stations[j]):
            add(i, j, 2, EW_COST * 1.5 + d * 0.01)
        if j != i and TP_MIN <= d <= TP_REACH + 1 and tp_ok(stations[i], stations[j]):
            add(i, j, 1, TP_COST)
print("etherwarp points", len(ew_points), "links to them", ew_links)


# ------------------------------------------------------------------ mining spots
MITHRIL = {"gray_wool", "cyan_terracotta", "prismarine", "prismarine_bricks", "dark_prismarine", "light_blue_wool",
           "polished_diorite"}
clusters = defaultdict(list)
for area, x, y, z, block in mines:
    if block in MITHRIL:
        clusters[(area, x // 10, y // 10, z // 10)].append((x, y, z))
spots = []
for (area, _, _, _), pts in sorted(clusters.items(), key=lambda kv: -len(kv[1])):
    if len(pts) < 4:
        continue
    spots.append({"area": area or "", "pos": [round(sum(p[0] for p in pts) / len(pts), 1),
                                              round(sum(p[1] for p in pts) / len(pts), 1),
                                              round(sum(p[2] for p in pts) / len(pts), 1)],
                  "blocks": len(pts)})

areas = sorted({s[3] for s in stations if s[3]})
out = {
    "info": "Station map built from Interaction Logger recordings. nodes=[x,y,z,areaIndex] (feet on the floor), "
            "edges=[from,to,mode,costSeconds] mode 0=walk (pathfinder) 1=teleport 2=etherwarp 3=straight walk",
    "version": 2,
    "areas": areas,
    "nodes": [[s[0], s[1], s[2], areas.index(s[3]) if s[3] in areas else -1] for s in stations],
    "edges": [[a, b, m, round(c, 3)] for (a, b), (m, c) in edges.items()],
    "spots": spots,
    "etherwarpPoints": ew_points,
    "miningCenters": mining_centers,
    "places": {
        "king": [129.5, 196.0, 195.5],
        "kingNpc": [129.5, 196.0, 196.5],
        "forgeWarp": [0.5, 149.0, -68.5],
        "walkers": [0.0, 128.0, 158.0],
        "goblins": [-86.8, 145.0, 172.3],
    },
}
json.dump(out, open(OUT, "w", encoding="utf-8"), separators=(",", ":"))
modes = Counter(m for (m, c) in edges.values())
print("stations", len(stations), "links", len(edges), "walk", modes[0], "straight", modes[3], "teleport", modes[1],
      "etherwarp", modes[2],
      "spots", len(spots))
