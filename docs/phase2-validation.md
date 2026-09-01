# Phase 2 — external NDL validation worksheet

Goal: confirm DiveMaster's ZH-L16C + gradient-factor NDLs against an
independent, community-vetted implementation (Subsurface), on a grid wide
enough to expose coefficient, loading, or semantics bugs.

## Exact Subsurface setup (matters — every knob shifts minutes)

Subsurface (free, open source): https://subsurface-divelog.org — note the
version you used: ______

In the **dive planner**:

| Setting | Value |
|---|---|
| Deco model | Bühlmann ZH-L16 + GFLow/GFHigh |
| GF low / high | **100 / 100** for the first pass, **85 / 85** for the second |
| Salinity / water | Fresh (1.000 kg/ℓ) |
| Surface pressure | 1013 mbar |
| Descent rate | 18 m/min |
| Ascent rates | **all bands** 9 m/min |
| Gas | Air, then EAN32, then EAN36 (no He) |
| Safety stop / min stop time extras | off |

Method per cell: plan a single segment to the target depth and increase the
segment duration one minute at a time; record the **largest duration with no
deco stop required**. (Segment duration in Subsurface includes the descent,
matching our "runtime" convention.) If still clean at 360 min, record ">360".
Alternatively, Recreational planner mode reports the max no-stop time
directly — either method is fine, note which you used.

## The grid — DiveMaster values (planner semantics, generated 2026-09-01)

Fill the "Subsurface" column. Minimum useful pass: all Air rows plus EAN32
at 18/24/30/40 m; complete grid is better.

| Depth (m) | Gas | GF | DiveMaster NDL (min) | Subsurface NDL (min) |
|---|---|---|---|---|
| 12 | Air | 100 | 206.0 | |
| 15 | Air | 100 | 98.8 | |
| 18 | Air | 100 | 65.4 | |
| 21 | Air | 100 | 47.2 | |
| 24 | Air | 100 | 35.7 | |
| 27 | Air | 100 | 27.0 | |
| 30 | Air | 100 | 21.6 | |
| 33 | Air | 100 | 18.2 | |
| 36 | Air | 100 | 15.7 | |
| 40 | Air | 100 | 12.8 | |
| 12 | EAN32 | 100 | >360 | |
| 15 | EAN32 | 100 | 240.1 | |
| 18 | EAN32 | 100 | 120.9 | |
| 21 | EAN32 | 100 | 76.9 | |
| 24 | EAN32 | 100 | 57.3 | |
| 27 | EAN32 | 100 | 44.4 | |
| 30 | EAN32 | 100 | 35.3 | |
| 33 | EAN32 | 100 | 28.4 | |
| 36 | EAN32 | 100 | 23.3 | |
| 40 | EAN32 | 100 | 18.9 | |
| 12 | EAN36 | 100 | >360 | |
| 15 | EAN36 | 100 | >360 | |
| 18 | EAN36 | 100 | 165.4 | |
| 21 | EAN36 | 100 | 98.0 | |
| 24 | EAN36 | 100 | 69.6 | |
| 27 | EAN36 | 100 | 53.4 | |
| 30 | EAN36 | 100 | 42.7 | |
| 33 | EAN36 | 100 | 34.6 | |
| 36 | EAN36 | 100 | 28.5 | |
| 40 | EAN36 | 100 | 22.4 | |
| 12 | Air | 85 | 139.2 | |
| 15 | Air | 85 | 74.7 | |
| 18 | Air | 85 | 50.0 | |
| 21 | Air | 85 | 35.7 | |
| 24 | Air | 85 | 25.6 | |
| 27 | Air | 85 | 20.2 | |
| 30 | Air | 85 | 16.5 | |
| 33 | Air | 85 | 13.6 | |
| 36 | Air | 85 | 11.6 | |
| 40 | Air | 85 | 9.7 | |
| 12 | EAN32 | 85 | >360 | |
| 15 | EAN32 | 85 | 157.2 | |
| 18 | EAN32 | 85 | 86.3 | |
| 21 | EAN32 | 85 | 59.8 | |
| 24 | EAN32 | 85 | 44.4 | |
| 27 | EAN32 | 85 | 33.8 | |
| 30 | EAN32 | 85 | 25.7 | |
| 33 | EAN32 | 85 | 21.0 | |
| 36 | EAN32 | 85 | 17.8 | |
| 40 | EAN32 | 85 | 14.7 | |
| 12 | EAN36 | 85 | >360 | |
| 15 | EAN36 | 85 | 248.7 | |
| 18 | EAN36 | 85 | 116.7 | |
| 21 | EAN36 | 85 | 74.2 | |
| 24 | EAN36 | 85 | 53.8 | |
| 27 | EAN36 | 85 | 41.5 | |
| 30 | EAN36 | 85 | 32.5 | |
| 33 | EAN36 | 85 | 25.4 | |
| 36 | EAN36 | 85 | 21.1 | |
| 40 | EAN36 | 85 | 17.3 | |

EAN36 at 33–40 m exceeds ppO₂ 1.4 — never dive those cells; they exist for
math comparison only.

## Acceptance rule

- |DiveMaster − Subsurface| ≤ 2 min at every cell (for >100-min cells: ≤ 5%).
- **DiveMaster must never exceed Subsurface by more than 1 min** — being
  more permissive than the reference is a stop-ship bug; being conservative
  is a footnote.
- Any breach: paste the numbers into the session and we diagnose knob by
  knob (water vapor 0.0627, air fN2 0.79, compartment 1b, GF, rates) before
  touching the model.

## Secondary references (optional triangulation)

- MultiDeco switched to ZH-L16C + GF mode (not V-Planner itself — that is VPM-B).
- dive-deco (Rust crate) and libbuhlmann (C++) on GitHub expose ZH-L16C+GF
  NDL programmatically; useful if we ever want a scripted second opinion.
- Online GF/NDL calculators (e.g. DiveToolbox) only if their parameters are
  stated; unknown-knob calculators prove nothing.

## Status

- [x] Internal pinning: `GoldenNdlTest` locks all 60 cells; `NdlTableGenerator`
      regenerates and cross-checks planner vs analytic solver on every cell.
- [ ] External: Subsurface column filled and acceptance rule evaluated.
- [ ] Field: post-dive profile replay comparison (needs sample export, Phase 7).
