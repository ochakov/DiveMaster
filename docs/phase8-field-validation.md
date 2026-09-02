# Phase 8 — field validation program

The math is validated (see phase2-validation.md); what remains is physical:
does the sensor read true at depth, does detection behave in real water,
and does the battery last. Dive with your certified computer on the other
wrist for every dive in this program. DiveMaster stays a secondary
instrument until this page is fully checked off.

## A. Staged-depth sensor test (the remaining go/no-go)

The Ultra 2's declared pressure spec is nominal; the true ceiling is
unknown. Weight the watch on a line, open the **Sensor probe** screen
(re-zero at the surface), lower it and hold ~30 s per stage, then read
**"Max seen"** after each pull-up.

| Stage | Line depth (m) | Max seen (m) | Tracks? |
|---|---|---|---|
| 1 | 2 | | |
| 2 | 5 | | |
| 3 | 10 | | |
| 4 | 15 | | |
| 5 | 20 | | |
| 6 | 30 (if possible) | | |

Pass: max-seen within ±0.3 m or ±3% of line depth at every stage. A
plateau = the sensor ceiling; the app must not be trusted below it.

## B. Side-by-side dive protocol (per dive)

Setup: certified computer set to the same GF as DiveMaster (Garmin
Descent: custom GF) and same gas. DiveMaster: correct water type; open
the app before entering the water.

Record (slate or memory, once each — no continuous underwater reading):

| Item | Certified | DiveMaster |
|---|---|---|
| Depth at max depth moment | | |
| Depth at safety stop | | |
| NDL on arrival at max depth | | |
| NDL at start of ascent | | |
| Dive time at surfacing | | |
| Max depth (post-dive log) | | |
| Avg depth (post-dive log) | | |
| Water temp (end of dive) | | |

Also note: did the dive auto-start/auto-end correctly? Screen stay on?
Touch lock hold? Any phantom system-shade pulls? Safety-stop countdown
behavior vs the certified computer's? Alerts felt when expected?

## C. Profile replay (after each dive)

1. Dive detail → Export CSV; `adb pull /sdcard/Android/data/com.ochakov.divemaster/files/`.
2. Subsurface → Import → CSV, map time_sec/depth_m/temp_c.
3. Compare Subsurface's computed ceiling/NDL over the profile against the
   logged `ndl_min` column at 3–5 checkpoints.

Pass: same shape, checkpoints within ±2 min (matching GF and water type).

## D. Battery profile

| Metric | Value |
|---|---|
| Battery at water entry | |
| Battery at exit | |
| Dive duration (screen always on) | |
| Implied full-charge dive endurance | |

Pass: ≥ 4 h implied endurance. The low-battery warning fires at 15%
during a dive (triple buzz + red BAT badge).

## Status

- [ ] A. Staged-depth test done, ceiling ≥ 30 m (or documented)
- [ ] B. ≥ 3 side-by-side dives recorded, depth within ±0.3 m / 3%
- [ ] C. Profile replay agrees on ≥ 2 real dives
- [ ] D. Battery endurance ≥ 4 h implied
- [ ] Verdict: DiveMaster approved as trustworthy secondary instrument
