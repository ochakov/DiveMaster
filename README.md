# DiveMaster

A scuba dive computer for Wear OS watches — Bühlmann ZH-L16C with gradient
factors, running on any Wear OS 3+ watch with a barometer (built on a Samsung
Galaxy Watch Ultra 2), with a phone companion app for browsing dive logs.

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
- Hidden dive simulator for dry-land testing (5 taps on the Simulator row in
  settings)

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
