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

Dive detection: start at depth ≥ 1.2 m held 3 s (clock backdated to submersion); end after < 0.8 m held 60 s; re-descend within 60 s continues the same dive; dives < 60 s discarded. Alert defaults: ascent > 10 m/min, descent > 20 m/min, low NDL < 5 min.

Safety stop (Ev's spec, 2026-08-31): arms once the dive passes 10 m; countdown (default 3 min) runs only inside a configurable depth window (default 4–6 m); leaving the window in either direction **pauses** the countdown (no reset, even on deep re-descent — deliberate, differs from computers that restart the stop); resumes on re-entry. Engine states NONE/PENDING/ACTIVE/PAUSED/DONE; dive screen shows a green countdown pill when active, amber + direction hint when paused, checkmark when done. Stop-complete/missed vibration belongs to Phase 6.

## Modules

- `:core:deco` — pure Kotlin JVM, zero Android deps. All pressures **bar absolute**, durations **seconds**, depths **meters**. `Buhlmann` (tissue loading, NDL, ceiling), `ZhL16c` (coefficients), `Oxygen` (ppO₂/MOD/CNS), `DepthConverter`, `Gas`, `GradientFactors`. Fully unit-tested; golden NDL windows in `NdlTest` are deliberately loose until Phase 2 pins exact reference values.
- `:core:engine` — pure Kotlin JVM dive engine, driven entirely by sample timestamps (no wall clock → fully testable, simulator-identical). `DiveEngine` (surface-EMA reference frozen at dive start, backdated start/end state machine, merge/discard rules, tissue+CNS integration per sample, gas=air above 0.5 m), `SensorPipeline` (1 s bucket median), `SimulatorProfile` (20 m / 5 min dev dive), `DiveStats` (crash-recovery stats). Single-threaded by contract — feed from one coroutine.
- `:core:data` — Room database (`dives`, `samples`, single-row `tissue_state` for repetitive-dive correctness across restarts) + `SettingsRepository` (DataStore). Open dives have `endEpochMs = 0` and are hidden from queries until finalized. Depends on `:core:deco`.
- `:app` — Wear OS app. `service/DiveService`: foreground service (specialUse FGS type) owning sensors → pipeline → engine → `DiveSessionRecorder` (Room writes, 15 s tissue snapshots, orphan-dive finalization on start); wake lock while diving; runs while app visible, stays alive through a dive, stops on surface when app closed; simulator at 4× time scale (ACTION_START_SIM/STOP_SIM). Screens: surface (clock + last dive + sim controls), probe, dive (live when a dive is active, else preview), settings (read-only; **5 taps on the Simulator row toggle the hidden simulator**), log.

## Phase status

- [x] Phase 0 — sensor probe screen (hardware go/no-go: pressure sensor range, temp sensor availability)
- [x] Phase 1 — scaffold
- [x] Phase 2 — deco golden values pinned and externally validated. Internal: planner-style harness (`PlannerSim`) + all 60 grid cells pinned in `GoldenNdlTest` (EN 13319; regenerate via `NdlTableGenerator` → `build/ndl-golden.txt`). External (2026-09-02, Subsurface 6.0.5576, grid filled by Ev): instantaneous NDL — the value the watch displays — matched within ±1 min on 24/25 cells; planner-boundary comparison passed with a one-sided quantization/ascent-semantics offset. Full analysis in `docs/phase2-validation.md`. Field profile-replay comparison deferred to Phase 7 (needs sample export).
- [x] Phase 3 — dive engine: foreground service, sensor pipeline (median filter → 1 Hz), state machine, crash recovery, simulator pressure source, live dive-screen values
- [x] Phase 4 — real-water UI: auto-switch to dive screen on submersion (and back on surfacing), swipe-dismiss disabled + all touch consumed while diving (auto-unlocks at surface), screen kept on for the whole dive, notification tap reopens the app, best-effort activity launch from the service on dive start, surface-interval readout on the surface screen
- [x] Phase 5 — editable settings: chips + ToggleChips list; numeric values edit via full-screen wear `Stepper` (route `setting/{NumberSettingId}`, registry in `SettingSpecs.kt`, saves on every tap — no confirm step); water type cycles on tap. Cross-field invariants clamp in `SettingsRepository` (GF low ≤ high, stop window ≥ 1 m tall). `DiveService` hot-reloads settings through the input channel and rebuilds engine+evaluator (carrying tissue/CNS over) **only while on the surface** — mid-dive edits defer to surfacing. Units toggle converts all displays via `ui/Units.kt` (storage stays metric; imperial shows whole feet).
- [x] Phase 6 — alert engine: pure `AlertEvaluator` in `:core:engine` (edge-triggering with hysteresis for low-NDL/deco/CNS/stop-complete; cooldown-repeating for ascent/descent rate, ppO₂, skipped stop; per-dive reset, CNS memory spans dives), `AlertSounder` in `:app` (distinct vibration waveform per alert, most-severe-wins per sample, beeps secondary via ToneGenerator). Alerts: ascent/descent rate, low NDL, deco entered, ppO₂ > max, CNS ≥ 80%/100%, safety-stop complete, safety-stop violated (ascending past unfinished stop). Simulator's final ascent is deliberately 12 m/min to demo the ascent alert.
- [x] Phase 7 — dive log detail: tapping a log entry opens `log/{diveId}` with a Canvas depth-profile chart (surface-down, 5/10 m gridlines), full stats, two-step delete, and CSV export (Subsurface-importable; comment header with dive metadata; Locale.US decimals; written to the app-specific external dir — `adb pull /sdcard/Android/data/com.ochakov.divemaster/files/`). Companion phone app decision (Ev, 2026-09-01): future `:mobile` module in this repo, same applicationId + signing key, one Play listing serving wear+phone APKs, Wearable Data Layer for sync (requires matching package/signature) — candidate Phase 9.
- [x] Phase 8 (code) — first-run disclaimer gate (blocks app until accepted once; `DisclaimerScreen`, flag in DataStore), sensor watchdog (SENSOR badge + buzz when samples stall >5 s mid-dive), battery guard (surface readout; red BAT badge + triple buzz once per dive at ≤15% — health warnings vibrate regardless of alert toggles), version stamp in settings (versionName 0.8.0). **Field program pending execution** — protocol + recording tables in `docs/phase8-field-validation.md` (staged-depth ceiling test, ≥3 side-by-side dives, profile replay, battery endurance). App stays a secondary instrument until that page is checked off.

## Build & test

```
.\gradlew.bat :core:deco:test        # deco unit tests (pure JVM, fast)
.\gradlew.bat :app:assembleDebug     # watch APK
adb install app\build\outputs\apk\debug\app-debug.apk
```

Toolchain: JDK 17 on PATH, SDK at `%LOCALAPPDATA%\Android\Sdk`, Gradle 8.10.2 via wrapper, AGP 8.7.3, Kotlin 2.0.21 + KSP.

## Known hardware risks (why the probe screen exists)

1. Consumer barometer chips often spec only ~1250 hPa → saturation near 2.5 m of water. **Ultra 2 probe findings (2026-08-31):** the HAL declares `maximumRange` ≈ 1013 hPa — a nominal value, not a hard limit — and live readings exceeded it underwater (+~20 hPa in a water bottle → correct 0.2 m). The probe verdict treats a low declared spec as "ceiling unknown" and turns green once readings pass the spec. The **true saturation point is still unknown**: verify with staged depths (watch lowered on a line / pool steps, reading "max seen") before trusting the depth channel, and field-validate against a certified computer.
2. Most Wear watches expose no `TYPE_AMBIENT_TEMPERATURE`. **Ultra 2 has none, but its skin-temperature vendor sensor works via SensorManager** (name-matched `*temp*`/`*thermo*`). Decision (Ev, 2026-08-31): temperature pipeline uses every available source — ambient preferred, skin/vendor fallback, labeled as body-biased and slow to track water.
3. Touch is unreliable underwater (dive UI must be button-driven); speaker beeps are inaudible at depth (vibration is the primary alert channel).

## Working agreement

Ev decides anything with multiple reasonable implementations — present options with trade-offs and a recommendation first (see `~/.claude/projects/.../memory/confirm-design-decisions.md`). Routine engineering details don't need asking.
