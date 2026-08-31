# DiveMaster

A standalone Wear OS scuba dive computer (single gas: air/nitrox). Target device: Samsung Galaxy Watch Ultra 2, but any Wear OS 3+ watch with a barometer (the manifest requires `android.hardware.sensor.barometer`).

**Safety-critical project.** The deco math in `:core:deco` must never be edited without keeping its unit tests green, and behavioral changes to it must be surfaced to Ev explicitly. This app is a secondary instrument until field-validated against a certified dive computer.

## Locked design decisions (confirmed by Ev, 2026-08-31)

| Area | Decision |
|---|---|
| Deco model | Bühlmann ZH-L16C (1b variant, 16 compartments), written from scratch, with Erik Baker gradient factors (default GF 40/85, editable). NDL uses GF-high only. |
| Depth | Water type setting: EN 13319 (default) / salt / fresh |
| Surface reference | Rolling average of pre-dive surface pressure, frozen at dive start |
| O₂ safety | MOD alert (ppO₂ 1.4 default) **and** NOAA CNS clock (90-min surface half-time) |
| UI | Compose for Wear OS; dive screen always fully on; touch locked during dive |
| Platform | minSdk 30 (Wear OS 3+), compileSdk 35, Kotlin, package `com.ochakov.divemaster` |
| Storage | Room (dives + 1 Hz samples + persistent tissue state), DataStore for settings |
| Units | Metric default with imperial toggle |
| Dev | Hidden simulator mode feeding synthetic pressure profiles |

Dive detection: start at depth ≥ 1.2 m held 3 s (clock backdated to submersion); end after < 0.8 m held 60 s; re-descend within 60 s continues the same dive; dives < 60 s discarded. Alert defaults: ascent > 10 m/min, descent > 20 m/min, low NDL < 5 min, safety stop 3 min at 5 m once past 10 m.

## Modules

- `:core:deco` — pure Kotlin JVM, zero Android deps. All pressures **bar absolute**, durations **seconds**, depths **meters**. `Buhlmann` (tissue loading, NDL, ceiling), `ZhL16c` (coefficients), `Oxygen` (ppO₂/MOD/CNS), `DepthConverter`, `Gas`, `GradientFactors`. Fully unit-tested; golden NDL windows in `NdlTest` are deliberately loose until Phase 2 pins exact reference values.
- `:core:data` — Room database (`dives`, `samples`, single-row `tissue_state` for repetitive-dive correctness across restarts) + `SettingsRepository` (DataStore). Depends on `:core:deco`.
- `:app` — Wear OS app. Screens: surface (clock + last dive), probe (Phase 0 hardware diagnostic), dive (static preview until Phase 4), settings (read-only until Phase 5), log.

## Phase status

- [x] Phase 0 — sensor probe screen (hardware go/no-go: pressure sensor range, temp sensor availability)
- [x] Phase 1 — scaffold (this state)
- [ ] Phase 2 — pin deco golden values against a reference implementation
- [ ] Phase 3 — dive engine: foreground service, sensor pipeline (median filter → 1 Hz), state machine, crash recovery, simulator pressure source
- [ ] Phase 4 — live dive + surface UI (auto-switch on submersion)
- [ ] Phase 5 — editable settings
- [ ] Phase 6 — alert engine (distinct vibration patterns primary; beep secondary)
- [ ] Phase 7 — dive log detail
- [ ] Phase 8 — hardening, field validation, first-run disclaimer

## Build & test

```
.\gradlew.bat :core:deco:test        # deco unit tests (pure JVM, fast)
.\gradlew.bat :app:assembleDebug     # watch APK
adb install app\build\outputs\apk\debug\app-debug.apk
```

Toolchain: JDK 17 on PATH, SDK at `%LOCALAPPDATA%\Android\Sdk`, Gradle 8.10.2 via wrapper, AGP 8.7.3, Kotlin 2.0.21 + KSP.

## Known hardware risks (why the probe screen exists)

1. Consumer barometer chips often spec only ~1250 hPa → saturation near 2.5 m of water. The probe screen shows the spec range verdict and the max pressure seen while dunked. If the Ultra 2 saturates, the depth concept needs a hardware rethink — check this before building further phases.
2. Most Wear watches expose no `TYPE_AMBIENT_TEMPERATURE`; the probe lists vendor `*temp*` sensors as fallback candidates. If none exist, hide the temperature tile.
3. Touch is unreliable underwater (dive UI must be button-driven); speaker beeps are inaudible at depth (vibration is the primary alert channel).

## Working agreement

Ev decides anything with multiple reasonable implementations — present options with trade-offs and a recommendation first (see `~/.claude/projects/.../memory/confirm-design-decisions.md`). Routine engineering details don't need asking.
