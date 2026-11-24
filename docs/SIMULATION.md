# Baritone evolution (simulation)

`tools/sim/evolve.py` tunes Baritone's settings with an evolution strategy (CMA-ES) on the
test levels 5-9, in the **real game**: real physics, real Baritone, the mod as it is. Several dev
clients run side by side in turbo mode; the script hands out the runs and keeps the best genome.

## Run it

```
python tools/sim/evolve.py setup --instances 6      # builds the mod, copies world + settings
python tools/sim/evolve.py smoke                    # 1 instance, a few runs: does everything work?
python tools/sim/evolve.py check --levels 5:4,9:2   # today's settings in turbo ...
python tools/sim/evolve.py check --levels 5:4,9:2 --realtime   # ... and in real time: must match
python tools/sim/evolve.py run --instances 6 --total-runs 500000
python tools/sim/evolve.py status
python tools/sim/evolve.py report                   # ~40 lines: success per level start vs now, why and
                                                    # where runs fail, holdout, moved genes, crashes, disk
python tools/sim/evolve.py install                  # best genome -> run/config/baritone-genome.json
python tools/sim/evolve.py install --to "C:\Users\<you>\curseforge\minecraft\Instances\test"
```

Ctrl+C stops; the state is saved after every generation and `run` continues from there.
Run `setup` again after code changes (the instances use a copy of the built classes, so building
while an evolution runs is fine).

## Try a level yourself

In the dev client (`runClient`, world AStarTest): `/airtest level <6-9> [seed]` builds the level
exactly as the bot gets it (map, effects, level 8: wolves + sword, level 9: the seed's random goal)
and puts you at the start; no bot. `/airtest stop` ends it and prints your time.

## Levels

* 6 and 7 are parkour courses in the AStarTest world; they need long jumps (6-8 blocks, diagonal,
  5-8 down). Baritone's parkour can't plan those; `MovementLongJump` (16 directions, reach from the
  real jump height and speed) can. Genes `longJumpPenalty` / `longJumpMaxDrop` tune how bold it is.
* 8 and 9 are rebuilt from recordings; the ground under the recorded map is filled once per session
  (no more void under cliffs and at the scan edge). 8 gives a diamond sword in slot 1.

## Variants under test

Several approaches run side by side; each is a gene, so every generation tries all of them and
`report` shows the success rate per value:

* `turnStyle` 0 = strafe into the turn, 1 = stop (+ `turnSneak`) and turn first when the turn is
  over `turnStopAngle`, 2 = start turning `preTurnDistance` blocks before the corner.
* `dropStyle` 0 = as it comes, 1 = no sprint over an edge, 2 = brake at the edge, then step off.
* `smoothingZigZagOnly`: string pulling only on real zig-zags (straight/diagonal alternating on
  one line), not at real corners.
* `edgeSafetyCost` / `wallSafetyCost` 0..8.
* Long jump calibration: `longJumpRunUp`, `longJumpBoost`, `longJumpAirAccel`, `longJumpSlack`
  (how far a jump really goes with the profile's speed and jump boost).

Runs use one fixed profile, SkyBlock movement: `--speed 14 --jump 5` (Speed XV = 400 %, Jump Boost
VI = jump height 6.13). Default runs are levels 10 and 11 (`--levels 10:10,11:10`): parkour
recordings, rebuilt 1:1 with block states, goal = the recording's end, reference = its time, no
ground filling. Calibration limits for that speed are genes too: `longJumpMaxHeight`,
`longJumpMaxGroundSpeed`, `highAscendMaxBlocks`.

## What happens

* **Instances**: `run-sim/inst-N`, each with a copy of `run/saves/AStarTest`, `run/config` and the
  level recordings. Started with `AIRCLIENT_ASTAR_TEST=1 AIRCLIENT_SIM=1`, below-normal priority.
* **Turbo mode** (`TurboClock`): client and integrated server tick in lockstep as fast as the PC
  can. Packets are handled per tick, the per-frame camera code runs 3x per tick, and the bots'
  clock (`SimClock`) moves 50 ms per tick - click speed, timeouts and turning stay as in a 20 tick/s
  game. While a path is being calculated the game runs in real time, so planning costs the same
  game time as in the real game.
* **Genome**: the Baritone settings in `tools/sim/genes.json` (ranges, int/bool). Start values are the
  settings the walk really uses today (`sim/settings.json`). Applied last in `PathfinderTuning.apply`.
* **Fitness**: successes first (goal reached, all wolves dead, not fallen, not dead); the time of
  the successful runs only breaks ties - it never outweighs one more success.
* **Fair comparison**: all genomes of a generation run the same levels and seeds; every generation
  gets new seeds. Every 20 generations the evolved mean and today's settings run a fixed holdout set
  (`run-sim/evolution/holdout.tsv`); `best_genome.json` is the mean with the best holdout result.

## Speed (measured 2026-11-22, one instance)

Turbo ~110 game ticks/s (~5.5x real time): a client tick costs ~3.7 ms, a server tick ~3.2 ms
(see the end of `sim/heartbeat`). Turbo and real time gave the same results (level 5: 4/4 in both,
times within 0.3 s). Throughput grows with the number of instances (~2 CPU threads each).

## Disk

Per run ~120 KB of logs would be 60 GB for 500k runs. Instead: the per-tick log of a run that
arrived is deleted, only the newest 60 failed-run and path-recorder logs stay per instance, the
instances log with a small log4j config (latest.log max 3 x 20 MB, no debug.log, console only
warnings). Results are kept compactly in `run-sim/evolution/runs.jsonl` (~150 bytes per run).
`best_genome.json` is only written when the evolved genome beats today's settings on the holdout.

## Files

| file | content |
| --- | --- |
| `run-sim/evolution/generations.tsv` | per generation: best genome, successes per level, avg time |
| `run-sim/evolution/holdout.tsv` | evolved vs today's settings on the holdout runs |
| `run-sim/evolution/best_genome.json` | the genome to install |
| `run-sim/inst-N/stdout.log` | game log of an instance |
| `run-sim/inst-N/astar-tests/` | per-tick logs of every run |

## Options

`--lam` genomes per generation (16), `--levels` runs per genome (`5:6,6:3,7:3,8:2,9:6`),
`--holdout` every N generations (20), `--chunk` runs per job (5), `--mem` heap per instance (3G),
`--sigma` start step size (0.2 of each range).
