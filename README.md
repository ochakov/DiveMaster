# DiveMaster

A scuba dive computer for Wear OS watches — Bühlmann ZH-L16C with gradient
factors, running on any Wear OS 3+ watch with a barometer, with a phone
companion app for browsing dive logs. Developed and tested on the Samsung
Galaxy Watch Ultra 2 (see [Compatible watches](#compatible-watches)).

> ## ⚠️ Safety warning
>
> **DiveMaster is experimental software and NOT a certified dive computer.**
> Use it only as a secondary instrument alongside a certified dive computer.
> Depth readings and decompression calculations may be wrong, and the
> pressure sensor's true depth limit on consumer watches is unverified. Your
> watch must be dive-rated by its manufacturer (10 ATM or better) — most
> smartwatches are not. Scuba diving requires training and certification.
> The software is provided **without warranty of any kind** (see LICENSE);
> you assume all risk of using it.

## Compatible watches

The app installs on any Wear OS 3+ watch with a barometer, but a watch is
only scuba-compatible when **all three** hold:

1. **Wear OS 3 or newer** (API 30+)
2. **A barometric pressure sensor** (required by the app — depth is derived
   from it)
3. **A manufacturer dive rating of 10 ATM or better** — this is the hard
   gate. Water resistance ratings like 5 ATM or IP68 are *not* dive ratings.

| Watch | Rating | Scuba use |
|---|---|---|
| Samsung Galaxy Watch Ultra 2 | 10 ATM | ✅ Reference device — the app is developed and tested on it |
| Samsung Galaxy Watch Ultra (2024) | 10 ATM | ✅ Same hardware class |
| Samsung Galaxy Watch 5 Pro / 6 / 7 / 8 | 5 ATM | ❌ Not dive-rated — do not scuba |
| Google Pixel Watch series | 5 ATM | ❌ Not dive-rated — do not scuba |

On any new watch, run the built-in **Sensor probe** screen first (dry, then
dunked): it reports the pressure sensor's declared range, live depth
response, and available temperature sensors. Consumer barometers often
declare only a nominal atmospheric range, and the true saturation depth is
unknown until tested — verify at staged depths before trusting the depth
channel (see `docs/phase8-field-validation.md`).

## Features

- Bühlmann ZH-L16C (1b) decompression model with Erik Baker gradient factors,
  written from scratch and validated against Subsurface (see
  `docs/phase2-validation.md`)
- Live dive screen: depth, max depth, NDL, dive time, temperature, ascent
  rate, gas / ppO₂ / CNS — always-on and touch-locked underwater
- Automatic dive detection with backdated start/end, brief-surfacing merge,
  and crash-safe 1 Hz sample logging
- Safety stop management: configurable depth window, pause outside it,
  resume on re-entry
- Vibration alerts: ascent/descent rate, low NDL, deco entry, ppO₂ over
  limit, CNS 80 % / 100 %, skipped or completed safety stop
- Nitrox (21–40 % O₂), MOD + NOAA CNS clock, repetitive-dive tissue
  persistence across restarts
- On-watch settings, dive log with depth-profile chart, CSV export
  (Subsurface-importable)
- Phone companion app: automatic dive sync via the Wearable Data Layer,
  archive logbook, large depth charts
- Hidden dive simulator for dry-land testing (see
  [Dive simulator](#dive-simulator))

## Dive simulator

The app contains a hidden simulator that feeds a synthetic pressure profile
through the **exact same engine, alert, logging, and sync path** as a real
dive — nothing is mocked except the sensor. It exists for dry-land testing
and for demoing the dive screen.

**Enable it:** open **Settings** and tap the **Simulator** row five times —
it flips to "On". (Same gesture turns it off.)

**Run it:** a **"Start simulated dive"** chip appears on the surface screen.
Tapping it starts the dive; the app auto-switches to the dive screen with an
amber **SIM** badge, and touch is locked exactly as underwater. A **"Stop
simulation"** chip on the surface screen aborts early (reachable after the
dive ends, or via the watch's back/home button mid-sim).

**The profile** compresses a realistic ~12-minute dive into about 3½ minutes
of real time (4× time scale): descent at 18 m/min to **20 m**, five minutes
of bottom time with the NDL visibly counting down, ascent at 9 m/min into
the **safety stop** at 5 m — including a deliberate drift down to 7.5 m so
you see the countdown pause (amber) and resume — the stop-complete
double-tick vibration, and then a deliberately fast **12 m/min final ascent**
that triggers the ascent-rate alert (rapid pulses, red rate bar). The dive
ends automatically after surfacing and appears in the dive log and on the
phone like any real dive.

**Two things to know:** the logged duration is simulated time (~12 min), not
wall time; and simulated dives load the *real* tissue model, so NDL readouts
afterwards reflect that residual nitrogen until surface off-gassing clears it
(hours). Deleting the log entry does not reset tissue state — so don't run
the simulator right before a real dive.

## Project layout

| Module | Contents |
|---|---|
| `:core:deco` | Pure-Kotlin ZH-L16C tissue model, NDL solver, oxygen math — fully unit-tested |
| `:core:engine` | Dive engine: detection state machine, sensor pipeline, alerts, simulator |
| `:core:data` | Room database, settings, watch↔phone sync wire format |
| `:app` | Wear OS app (Compose for Wear OS) |
| `:mobile` | Phone companion (Compose Material 3) |

## Building

JDK 17 and the Android SDK are required.

```
gradlew :core:deco:test :core:engine:test   # unit tests
gradlew :app:assembleDebug                  # watch APK
gradlew :mobile:assembleDebug               # phone APK
```

Both APKs share one `applicationId`; install `app-debug.apk` on the watch and
`mobile-debug.apk` on the phone (matching signatures are required for dive
sync).

## Validation

The deco math is pinned by 60 golden NDL grid cells and externally validated
against Subsurface 6.0 (instantaneous NDL within ±1 min across depths, gases,
and gradient factors). Field validation against a certified dive computer is
tracked in `docs/phase8-field-validation.md` and is a prerequisite for
trusting the app in the water.

## License

[MIT](LICENSE)
