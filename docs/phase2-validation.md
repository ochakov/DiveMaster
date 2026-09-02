# Phase 2 — external NDL validation worksheet (v2, EN 13319)

Goal: confirm DiveMaster's ZH-L16C + gradient-factor NDLs against
Subsurface on a grid wide enough to expose coefficient, loading, or
semantics bugs.

v2 change: the grid water type is **EN 13319** (DiveMaster's shipping
default; pick "EN13319 (1.020 kg/L)" in Subsurface — the 1.020 vs 1.0197
difference is negligible). The earlier fresh-water grid is superseded.

## Exact Subsurface setup

Subsurface version used: 6.0.5576 (noted 2026-09-02)

| Setting | Value |
|---|---|
| Deco model | Bühlmann ZHL-16C, GFLow = GFHigh = **100** (pass 1) then **85** (pass 2) |
| Water type | **EN13319 (1.020 kg/L)** |
| ATM pressure | 1013 mbar, altitude 0 m |
| Descent rate | 18 m/min ("drop to first depth" on) |
| Ascent rates | **all four bands** 9 m/min |
| Last stop at 6 m / safety stop / backgas breaks | off |
| Gas | Air, then EAN32, then EAN36 (no He) |

Ignore gas-consumption warnings — cylinder size does not affect deco.

## Two ways to read a cell (both valid; A is much faster)

**Method A — hover (instantaneous NDL, matches the watch display):**
plan a segment comfortably inside the NDL, hover the profile at the first
moment at bottom depth, read "NDL: X min (calc)". Compare against the
**Arrival NDL** column, ±1 min. This is exactly the number DiveMaster's
dive screen computes live.

**Method B — planner boundary (runtime NDL with ascent credit):**
increase the segment duration 1 min at a time; the largest duration with
no deco stop is the boundary. Segment duration includes the descent.
Compare against the **Planner NDL** column: within ±2 min (≤5% for cells
over 100 min), and DiveMaster must **never be more than 1 min more
permissive** — that direction is a stop-ship bug. ">360" means unlimited.

First corroboration recorded: 24 m / Air / GF100 hover read 28 vs our 28.3. ✓

## The grid — DiveMaster values (EN 13319, generated 2026-09-02)

| Depth (m) | Gas | GF | Planner NDL (min) | Arrival NDL (min) | Subsurface A (hover) | Subsurface B (boundary) |
|---|---|---|---|---|---|---|
| 12 | Air | 100 | 188.5 | 182.1 | | |
| 15 | Air | 100 | 93.6 | 90.1 | | |
| 18 | Air | 100 | 63.0 | 59.4 | | |
| 21 | Air | 100 | 45.4 | 41.5 | | |
| 24 | Air | 100 | 34.2 | 28.3 | 28 ✓ | |
| 27 | Air | 100 | 25.7 | 20.9 | | |
| 30 | Air | 100 | 20.8 | 15.7 | | |
| 33 | Air | 100 | 17.5 | 12.7 | | |
| 36 | Air | 100 | 14.9 | 10.1 | | |
| 40 | Air | 100 | 12.2 | 7.8 | | |
| 12 | EAN32 | 100 | >360 | >360 | | |
| 15 | EAN32 | 100 | 223.0 | 213.4 | | |
| 18 | EAN32 | 100 | 112.2 | 105.4 | | |
| 21 | EAN32 | 100 | 73.5 | 70.0 | | |
| 24 | EAN32 | 100 | 54.6 | 50.5 | | |
| 27 | EAN32 | 100 | 42.7 | 36.7 | | |
| 30 | EAN32 | 100 | 33.8 | 26.7 | | |
| 33 | EAN32 | 100 | 26.9 | 20.6 | | |
| 36 | EAN32 | 100 | 22.3 | 16.0 | | |
| 40 | EAN32 | 100 | 18.2 | 12.5 | | |
| 12 | EAN36 | 100 | >360 | >360 | | |
| 15 | EAN36 | 100 | >360 | >360 | | |
| 18 | EAN36 | 100 | 153.7 | 147.0 | | |
| 21 | EAN36 | 100 | 92.4 | 86.9 | | |
| 24 | EAN36 | 100 | 66.7 | 62.3 | | |
| 27 | EAN36 | 100 | 51.0 | 46.7 | | |
| 30 | EAN36 | 100 | 41.0 | 33.6 | | |
| 33 | EAN36 | 100 | 33.1 | 25.6 | | |
| 36 | EAN36 | 100 | 27.0 | 19.9 | | |
| 40 | EAN36 | 100 | 21.4 | 14.8 | | |
| 12 | Air | 85 | 131.7 | 125.5 | | |
| 15 | Air | 85 | 71.7 | 69.5 | | |
| 18 | Air | 85 | 48.0 | 44.5 | | |
| 21 | Air | 85 | 33.8 | 28.4 | | |
| 24 | Air | 85 | 24.4 | 19.8 | | |
| 27 | Air | 85 | 19.4 | 14.7 | | |
| 30 | Air | 85 | 15.8 | 11.4 | | |
| 33 | Air | 85 | 13.1 | 9.0 | | |
| 36 | Air | 85 | 11.2 | 7.3 | | |
| 40 | Air | 85 | 9.4 | 5.8 | | |
| 12 | EAN32 | 85 | >360 | >360 | | |
| 15 | EAN32 | 85 | 146.9 | 142.0 | | |
| 18 | EAN32 | 85 | 82.0 | 78.4 | | |
| 21 | EAN32 | 85 | 56.9 | 52.9 | | |
| 24 | EAN32 | 85 | 42.3 | 36.0 | | |
| 27 | EAN32 | 85 | 31.9 | 25.5 | | |
| 30 | EAN32 | 85 | 24.5 | 18.5 | | |
| 33 | EAN32 | 85 | 20.2 | 14.5 | | |
| 36 | EAN32 | 85 | 17.2 | 11.7 | | |
| 40 | EAN32 | 85 | 14.1 | 8.7 | | |
| 12 | EAN36 | 85 | >360 | >360 | | |
| 15 | EAN36 | 85 | 229.3 | 217.4 | | |
| 18 | EAN36 | 85 | 108.4 | 101.9 | | |
| 21 | EAN36 | 85 | 70.9 | 66.8 | | |
| 24 | EAN36 | 85 | 51.4 | 47.0 | | |
| 27 | EAN36 | 85 | 39.4 | 32.0 | | |
| 30 | EAN36 | 85 | 30.5 | 23.9 | | |
| 33 | EAN36 | 85 | 24.2 | 17.7 | | |
| 36 | EAN36 | 85 | 20.2 | 14.1 | | |
| 40 | EAN36 | 85 | 16.6 | 10.7 | | |

Arrival NDL = analytic runtime − descent time (descent at 18 m/min:
12 m→0:40, 15→0:50, 18→1:00, 21→1:10, 24→1:20, 27→1:30, 30→1:40,
33→1:50, 36→2:00, 40→2:13).

EAN36 at 33–40 m exceeds ppO₂ 1.4 — math-comparison cells only, never dive
them.

Minimum useful pass: all Air rows via Method A, plus Method B on four
spot cells (18/24/30/40 m air GF100). Full grid is better.

## Secondary references (optional triangulation)

- MultiDeco in ZH-L16C + GF mode (not V-Planner — that is VPM-B).
- dive-deco (Rust) / libbuhlmann (C++) on GitHub for a scripted second opinion.

## Status

- [x] Internal pinning: `GoldenNdlTest` locks all 60 cells (EN 13319);
      `NdlTableGenerator` regenerates and cross-checks planner vs analytic.
- [x] First corroboration: 24 m/Air/GF100 hover 28 vs 28.3.
- [ ] External: grid filled and acceptance rule evaluated.
- [ ] Field: post-dive profile replay comparison (needs sample export, Phase 7).
