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

**Result (2026-09-25) — passed by real dives instead of a line test.** With
a certified dive computer on the other wrist, DiveMaster's barometric depth
read correctly throughout a 24-min open-water dive to **29.5 m** and a
38-min shallow dive to 8.6 m (Ev's side-by-side comparison). No plateau:
the sensor ceiling is **≥ 30 m**, so the go/no-go is a GO and hardware
risk #1 is retired. Depths beyond 30 m are still unverified — treat deeper
readings as untested until a deeper comparison dive is logged.

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

### Dives logged so far

| # | Date | DiveMaster (max / avg / time / lowest NDL) | Certified (max / NDL at max / temp) | Notes |
|---|---|---|---|---|
| 1 | 2026-09-25 09:44 | 29.5 m / 12.6 m / 24:15 / 9 min | depth agrees (Ev); NDL + temp to record | 1456 samples for 1455 s = exactly 1 Hz, no gaps, recorded headless; ~5-min plateau at ~5 m (safety stop) visible in the profile; surface 1015 mbar; **min temp missing** |
| 2 | 2026-09-25 15:08 | 8.6 m / 4.3 m / 38:20 / 501 min | depth agrees (Ev); NDL + temp to record | 2301 samples for 2300 s = exactly 1 Hz, no gaps; both dives synced to the phone with full profiles; **min temp missing** |

| 3 | 2026-09-25 14:27 | (fragment) 3.8 m / 1.6 m / 3:07 / — | — | **Failure case.** The service had stood itself down on the boat (old 20-min idle guard); Ev relaunched the app underwater with the side button and the engine took the pressure at ~4.7 m as the surface ("1487 mbar"), so the fragment reads 4.7 m too shallow, shows negative depth where Ev rose above 4.7 m, and ended after 60 s of that. Fixed 2026-09-27: remembered surface pressure + cold-start rules, LATE START flag, mid-dive reference correction, 3 h idle guard (CLAUDE.md "Cold-start surface reference"). |

### What the exported CSVs of dives 1 and 2 showed (2026-09-28)

- Both files end at their last sample with the 60 s surface hold never completed: the dives were closed by **orphan finalization** on the next app launch, i.e. the service **process died 15–65 s after the wrist left the water** — at 10:08:24 and again at 15:46:17. Ev does not recall closing the app. The recording was intact. Cause: unknown until SERVICE HISTORY (v0.9.1+) is read after the next dive.
- Dive 1 duration 24:15 was inflated by the surface float; the first surface touch was at 23:03 (now the rule).
- Safety stop, dive 1: armed at 4:42, band entered ~16:13, countdown **DONE at 19:40**, two more minutes at ~4.1 m. The stop-complete buzz should have fired at 19:40.
- Ascent-rate alerts would have fired: dive 1 at 13:01, 13:36–13:43, 13:57, 15:21 (11–15 m/min); dive 2 at 33:52–34:01 (16.7 m/min) and at the exit. **Ev: were these buzzes felt?** (item "alerts felt when expected").
- Lowest NDL 8.5 min at 28.2 m (dive 1). Temperature column empty (known issue).

### Next-dive checklist for the 2026-09-27 fixes (unverified in water)

- [ ] After surfacing, leave the watch alone for two minutes, then check SERVICE HISTORY for the dive end and any process exit.
- [ ] Dive record shows battery start→end, safety-stop result, max ascent rate, CNS at end, app version and an exit position (entry position too if the watch had a recent fix).

- [ ] Before the dive, open **Sensor probe → SERVICE HISTORY**: "Remembered surface" shows a value a few minutes old.
- [ ] Open the app on the boat, leave it, gear up for > 20 min: it must still be running at water entry (notification says "Watching for a dive · auto-off HH:MM").
- [ ] Deliberate test on a shallow dive: force-stop the app on the surface, descend to 3–5 m, relaunch with the side button. Expect: dive screen within ~5 s, correct depth (not ~0), amber **LATE** badge; afterwards the log entry says "Late start · began underwater" and its surface pressure matches the morning's.
- [ ] After the day, SERVICE HISTORY: any "stood down" or OS kill lines explain every gap; note the exit reasons here.

Open issue from these dives: **min temp "—" on both** — the engine keeps
`minTempC` null only when every 1 Hz sample arrived with `tempC = null`, so
no temperature sensor delivered a single event to the service during
either dive (the skin-temp vendor sensor did report on the probe screen on
land, 2026-08-31). To be diagnosed.

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

- [x] A. Sensor ceiling ≥ 30 m — **passed 2026-09-25** by real-dive comparison (correct to 29.5 m vs a certified computer)
- [ ] B. ≥ 3 side-by-side dives recorded, depth within ±0.3 m / 3% — **2 of 3 logged** (2026-09-25, depth agreement confirmed by Ev); certified NDL/temp figures still to be written down
- [ ] C. Profile replay agrees on ≥ 2 real dives
- [ ] D. Battery endurance ≥ 4 h implied — figures not yet recorded
- [ ] Verdict: DiveMaster approved as trustworthy secondary instrument
