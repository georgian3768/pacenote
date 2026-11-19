# Shift assistant modes — implementation record (2026-11-11)

**Branch:** `shift-modes`, cut from `modern-ui` at `c65cc87` ("docs: the chip reports
a legacy NORMAL…"). **Merge-base with `main`:** `318f399`. `main` is an ancestor —
`git rev-list --left-right --count main...shift-modes` = `0 14` (0 behind; 8
pre-existing `modern-ui` commits + the 6 below). Nothing was merged into `main`,
`main` was not touched, and no firmware file was opened.

**Why not `8de03bf` (2026-11-09 12:56):** it is 24 commits behind `c65cc87`.
Branching there would have dropped the modern UI, P2 and P3. The spec's
`demandUpBoost` belongs to that older tree (removed in `8de03bf`); see §6.2.

---

## 1. Commits (one per change)

| Commit | Change |
|---|---|
| `d07446c` | Per-mode landings; `upCapRpm` / `downFloorRpm` / `downLandingMarginRpm`; capped `upThresholdFor` and floored `downThresholdFor`; `ShiftModesTest` |
| `6cf3c37` | `gearChangeBlankMs` (1000 ms), settled-gear tracking, rpm-rise blank; `Output.accelerating/blanked/rpmRise` |
| `6a6d64c` | `AssistantBank` / `AssistantMode` / `FrameDecision`; `SuppressedBy` + `CueRow`; per-frame `suppressed_by`; `ShadowModeTest` |
| `bcf3a38` | App wiring: `AppSettings.assistantMode`, `AssistantHost` bank + clocks, `CueLogFile` (`files/cues.csv`), SHADOW render skip, `EXTRA_MODE` intent |
| `ad20c22` | Replay harness: `--shadow`, `--cues-out`, `--blank`, per-profile summary |
| `8c2ba11` | Test pinning re-arm points to the landing margin / cap |

---

## 2. Change list, old → new

### 2.1 Thresholds (`shiftassist/.../ShiftAssistant.kt`)

| Symbol | Old (base `c65cc87`) | New | Tag |
|---|---|---|---|
| `DriveMode.ECO` | `(up 1600, down 2200)` | `(1600, 1600)` | POLICY |
| `DriveMode.NORMAL` | `(2000, 2200)` | `(2000, 2000)` | POLICY |
| `DriveMode.SPORT` | `(2800, 2200)` | `(2800, 2000, experimental = true)` | POLICY |
| `Config.upCapRpm` | — | `4000` | POLICY |
| `Config.downFloorRpm` | — | `1100` | POLICY (comfort/economy); lug limit **NO DATA** |
| `Config.downLandingMarginRpm` | — | `300` | POLICY |
| `Config.demandUpBoost` | `0.10` in the initial import only, removed in `8de03bf` before the base | absent | — |
| `upThresholdFor(gear)` | `(landing × L(g)/L(g+1)).toInt()` | `minOf(4000, …)` | DERIVED + POLICY cap |
| `downThresholdFor(gear)` | `(downLanding × L(g)/L(g−1)).toInt()` | `maxOf(1100, ((downLanding − 300) × L(g)/L(g−1)).toInt())` | DERIVED + POLICY floor/margin |

`L` is the calibrated ladder in rpm per km/h: `147.2, 79.3, 53.3, 39.8, 32.3`
(`AssistantHost.kt:35`, `Replay.kt:30`). The arithmetic truncates to `Int`
(`.toInt()`), which is the ≤ 1 rpm difference from an exact evaluation. Those
constants are the **rounded fallback**; the handset stores the six-figure
calibration (147.223 / 79.300 / 53.279 / 39.775 / 32.329), and the formulas
run on whatever `GearEstimator.centers` holds — see §6.1 for the 4–6 rpm
difference on the device.

At the rounded fallback ladder the derived tables are:

| Mode | up 1→2 | 2→3 | 3→4 | 4→5 | down 2→1 | 3→2 | 4→3 | 5→4 |
|---|---|---|---|---|---|---|---|---|
| ECO | 2969 | 2380 | 2142 | 1971 | 1100 | 1100 | 1100 | 1100 |
| NORMAL | 3712 | 2975 | 2678 | 2464 | 1100 | 1142 | 1269 | 1379 |
| SPORT | 4000 | 4000 | 3749 | 3450 | 1100 | 1142 | 1269 | 1379 |

### 2.2 Blank and decision fields (`ShiftAssistant.kt`)

| Symbol | Old | New |
|---|---|---|
| `Config.gearChangeBlankMs` | — | `1000` |
| `settledGear`, `lastGearChangeMs` | — | last settled gear and when it changed |
| `rising` | `rpmRise > 150` | `rpmRise > 150 && !blanked` |
| `Output` | `demand`, `accelMps2`, … | adds `accelerating`, `blanked`, `rpmRise`, `rawGear`, `rpm`, `speedKph`, `braking`, `suppressedBy` |
| `ShiftAssistant.lastCueAtMs` | — | age source for `last_cue_age_ms` |
| `suppressionReason(...)` | — | one token per frame (`gap/on_time/gear_null/brake/not_accelerating/accelerating/over_rev/blank`) |

No cue branch reads `sample.throttle`: `accelerating` is smoothed speed gain or
rpm rise (`ShiftAssistant.kt:289`), up/down read `accelerating`/`braking`
(`:336`, `:348`). `demand` is still computed and returned **for the dashboard
card only**.

### 2.3 Mode runner (`shiftassist/.../AssistantBank.kt`, new)

`AssistantBank` holds one `ShiftAssistant` per `DriveMode` and one shared
`GearEstimator`. `update()` runs the selected profile only, or all three in
`SHADOW`, and `FrameDecision.visible` is `null` in SHADOW. `AssistantMode`
parses `eco|normal|sport|shadow`.

### 2.4 Cue log (`shiftassist/.../CueLog.kt`, new)

`CueRow` fixes the column order and `SuppressedBy` the vocabulary:

```
t_mono_ms,t_wall_ms,frame_t_ms,mode,gear_raw,gear_smoothed,rpm,speed_true_kph,
brake,accel_mps2,rpm_rise,up_thr,down_thr,emitted,suppressed_by,last_cue_age_ms,
visible,audio
```

App side: `CueLogFile` (`app/.../CueLogFile.kt`) appends to `files/cues.csv`,
flushes every 32 rows, rotates at 8 MB keeping one previous file. Rows are
written on the stream thread from `AssistantHost.onFrame`
(`AssistantHost.kt:122-135`), clocks captured monotonic first
(`AssistantHost.kt:100-101`). This is **not**
the `EventLog` ring buffer.

### 2.5 App wiring

| File | Change |
|---|---|
| `Settings.kt:76-87` | `assistantMode` (`assistant_mode` key; falls back to legacy `drive_mode`); `driveMode` is now a view over it |
| `AssistantHost.kt` | owns `AssistantBank`; adds `EXTRA` mode key to its rebuild key; writes one `CueRow` per profile per frame; `shadow` flag; `mode` StateFlow (`setMode` is the only writer, `syncMode` adopts the stored value at boot) |
| `SpikeService.kt:64` | `EXTRA_MODE = "assistant_mode"` intent extra |
| `SpikeService.kt:216-224` | mode switch; `overlay.hide()` in SHADOW, otherwise the overlay policy re-evaluates |
| `SpikeService.kt:335-336` | `deliver()` skips `overlay.onFrame` in SHADOW (no bubble, no audio, no border) |
| `SpikeService.kt:157` | `CueLogFile.init(this)` |
| `TelemetryPanel.kt` | the panel chip reads `AssistantHost.mode` and calls `AssistantMode.next()`; the same composable renders in the overlay and in the landing |

The panel chip cycles `AssistantMode` **ECO → NORMAL → ZOOM → ECO**
(`AssistantMode.next()`). ECO is the factory default
(`AssistantMode.DEFAULT`, and the fallback for a missing or unrecognised stored
value), so a fresh install starts in the mileage profile. NORMAL stays in the
cycle: the old two-state toggle (ECO ↔ ZOOM) left it unreachable once the driver
left it.

`AssistantHost.mode` is a `StateFlow` and `setMode` is its only writer, in both
profiles and in the panel: the chip, the footer and the bank the cues come from
read one value. `syncMode` adopts the stored value at boot, before either host's
first composition, so the chip cannot show a different profile than the bank.
SHADOW is not in the cycle and is displayed as `shadow` when set from the dev
intent, never folded into NORMAL; leaving SHADOW returns to the default. The
panel is shared by the overlay and the landing (`TelemetryPanel.kt`), so this
holds on both surfaces.

Dev switch (service is non-exported, hence `run-as`):

```bash
adb shell run-as com.pocketmechanic.spike am start-foreground-service \
  -n com.pocketmechanic.spike/.SpikeService --es assistant_mode shadow
```

### 2.6 Replay (`shiftassist/.../Replay.kt`)

| Flag | Meaning |
|---|---|
| `--shadow` | run ECO/NORMAL/SPORT on the same frames, render none |
| `--cues-out FILE` | write the frame-level CSV (same header as the phone) |
| `--blank MS` | gear-change blank (default 1000; 0 disables) |
| `--scale` | GPS speed scale, applied once (trips #21/#22 need `0.8931155800819397`) |

The summary prints `REPLAY ON OLD DRIVING, NOT DRIVER RESPONSE` and one
`up/down/over_rev` row per profile. In shadow, trip/gear statistics are reported
for NORMAL. `t_wall_ms` is `0`: a replay has no phone wall clock.

---

## 3. Tests

Command (from `android/`):

```bash
JAVA_HOME=~/devtools/jdk17 PATH=~/devtools/jdk17/bin:$PATH \
  ~/devtools/gradle-8.7/bin/gradle :shiftassist:test --offline --no-daemon
```

Result: **96 tests, 0 failed, 0 skipped** (`:app:compileDebugKotlin` SUCCESS).

New coverage in the shift-modes round (16 tests):

- `ShiftModesTest` (9): `upshiftTable`, `downshiftTable`,
  `sportBorrowsNormalDownLanding`, `downPointInvariants`, `upCapHolds`,
  `rearmPointsKeepTheirMargins`, `formulasFollowTheLadder`,
  `landingDoesNotHunt`, `gearChangeBlank`.
- `ShadowModeTest` (7): shared gear estimate, cue-row columns and clock order,
  `suppressed_by` vocabulary, `on_time` vs `gap`, single-profile visibility,
  SHADOW visibility, `thr1/thr2` independence.
- `ModeCycleTest` (7, 2026-11-12): the factory default is ECO (`AssistantMode.DEFAULT`,
  `DriveMode.DEFAULT`, and `of(null)`/`of("garbage")`), cycle order
  ECO → NORMAL → ZOOM → ECO, the default is reachable from every driving mode,
  three taps return to the start, SHADOW is not reachable, leaving SHADOW lands
  on a driving profile, and only SHADOW has no `DriveMode`.

**Fails on the old code (proof):** `OldCodeHuntTest` (in a `c65cc87` worktree,
not committed) drives ECO 5th at 1600 rpm:

```
ECO 5th: down threshold = 1785, cues = [DOWN, null, null, null, null, null]
org.opentest4j.AssertionFailedError: ECO at 1600 rpm in 5th cues down
```

New code: `down(5) = 1100`, no cue.

`rearmPointsKeepTheirMargins` checks the points the latch actually re-arms at,
per mode/gear — `down + 120 ≤ downLanding − 300` and `up − 150 ≤ 4000` — not
just the firing points.

---

## 4. Replay on the recorded trips

Trips #19 and #20 are the existing logs; #21 and #22 were pulled read-only from
the phone (new trips, `speedScale 0.8931155800819397`). Inputs are CAN frames,
GPS speed is scaled exactly as `TripRecorder` does. "Old" is the branch base
`c65cc87`, run in a separate worktree on the same scaled inputs. The harness
ladder is the rounded fallback; the phone stores the six-figure list, which
moves a threshold by at most 6 rpm (§6.1) — a borderline frame could flip one
count, so read the deltas, not the absolute totals.

**REPLAY ON OLD DRIVING, NOT DRIVER RESPONSE.** A cue count is not a measure of
whether the cue was right for the driver; it is what the rule would have said.

| Trip / window | Mode | Old up/down/over-rev | New up/down/over-rev |
|---|---|---|---|
| #19 full (7647 frames, 697.5 s) | ECO | 15 / 16 / 3 | 15 / **10** / 3 |
| | NORMAL | 11 / 16 / 3 | 11 / **13** / 3 |
| | SPORT | 1 / 17 / 3 | 1 / **13** / 3 |
| #20 t ≥ 109.36 s (5024 frames) | ECO | 8 / 13 / 0 | 8 / **8** / 0 |
| | NORMAL | 4 / 14 / 0 | 4 / **8** / 0 |
| | SPORT | 1 / 14 / 0 | 1 / **8** / 0 |
| #21 (1986 frames) | all | — | 0 / 5 / 0 |
| #22 (918 frames) | all | — | 0 / 4 / 0 |

Ups are unchanged on these trips: the cap changes SPORT's 1st/2nd points
(5198/4145 → 4000) but the car never reaches them at road speed. The 3 over-rev
cues in #19 are the stationary calibration revs (0 km/h, gear unknown,
6150–6390 rpm); #20's window and #21/#22 have none.

Down cues by gear (new code):

| Trip | Mode | 3rd | 4th | 5th |
|---|---|---|---|---|
| #19 | ECO | 3 | 4 | 3 |
| | NORMAL | 4 | 6 | 3 |
| | SPORT | 4 | 6 | 3 |
| #20 | ECO | 2 | 4 | 2 |
| | NORMAL | 5 | 2 | 1 |
| | SPORT | 5 | 2 | 1 |
| #21 | ECO | 2 | 2 | 1 |
| | NORMAL / SPORT | 3 | 1 | 1 |
| #22 | all | 4 | 0 | 0 |

The difference is concentrated in 5th, exactly where the old single 2200 down
landing sat above ECO's own 4→5 landing (1785 vs 1600) and held NORMAL/SPORT
above the point the car pulls from.

`suppressed_by` per frame, #19 full (one row per frame per profile, 7647 rows
each; `none` includes frames where a cue fired — `emitted=1`):

| Mode | none | gear_null | on_time | gap | blank | accel | brake | not_accel | over_rev |
|---|---|---|---|---|---|---|---|---|---|
| ECO | 4299 | 2912 | 212 | 38 | 18 | 2 | 2 | 99 | 65 |
| NORMAL | 4363 | 2912 | 172 | 17 | 10 | 3 | 4 | 101 | 65 |
| SPORT | 4561 | 2912 | 89 | 17 | 0 | 3 | 0 | 0 | 65 |

`gear_null` is the dominant token (clutch/neutral, speed < 15 km/h, or a ratio
outside ±12 % of the ladder). `coast` is in the vocabulary but is never emitted:
the down rule allows braking and coasting, so there is nothing to suppress.

---

## 5. Replay commands

```bash
cd android
JAVA_HOME=~/devtools/jdk17 PATH=~/devtools/jdk17/bin:$PATH \
~/devtools/gradle-8.7/bin/gradle -q :shiftassist:replay --offline --no-daemon \
  -Pjsonl=/tmp/opencode/replay19.jsonl \
  -PreplayArgs="--shadow --scale 0.8931155800819397 --redline 6100 \
                --cues-out /tmp/opencode/cues_19.csv"
```

`#20` uses the `t_ms >= 109360` window (`final20.jsonl`, 5024 frames); `#21` /
`#22` use their exported JSONL and `--scale 0.8931155800819397`.

---

## 6. Conflicts and decisions (with line numbers)

### 6.1 The spec's expected table was computed from the rounded ladder, not the stored one

The handset's gear ladder (pulled from `pm_settings.xml` on 2026-11-11) is the
six-figure wizard capture: 147.22349808875938 / 79.30012370123676 /
53.27881169596888 / 39.77500652159182 / 32.32891425047178. The spec quotes the
same ladder to six figures, but its ±1 rpm expected table is the answer for the
**rounded** three-figure fallback (147.2 / 79.3 / 53.3 / 39.8 / 32.3). With the
stored six-figure values the points move beyond ±1:

| Cell | Spec table / rounded | Six-figure (what the phone computes) |
|---|---|---|
| NORMAL up 4→5 | 2464 | 2460 |
| ECO up 4→5 | 1971 | 1968 |
| NORMAL down 5→4 | 1379 | 1381 |
| SPORT up 4→5 | 3450 | 3444 |

The implementation derives every point from the stored ladder — it does not
carry either table — so the phone will show the six-figure column. The replay
harness uses the rounded fallback so old and new runs share exactly one input.
`formulasFollowTheLadder` (`ShiftModesTest.kt:171`) pins both ladders, so the
derivation is proven either way; the 4–6 rpm gap is far inside the 300 rpm
landing margin. **This is a spec-table conflict, not a code defect.**

### 6.2 `demandUpBoost` does not exist at the base

Spec: "remove/neutralise `demandUpBoost` and all throttle use in cue logic."
The field exists only in the initial import (`6d1a4f4`, `ShiftAssistant.kt:41`
`val demandUpBoost: Double = 0.10`, used at `:168`); `8de03bf` removed it.
`git grep demandUpBoost c65cc87` is empty. At the base, `demand` is display-only
(`ShiftAssistant.kt:285`) and the cue rule uses `accelerating` (`:289`, `:336`,
`:348`). `ShadowModeTest.thr1/thr2 cannot change a cue decision` proves the
independence. Nothing to remove on this branch.

### 6.3 `coast` is vocabulary, not a produced token

`SuppressedBy.COAST` (`CueLog.kt:20`) is defined and documented as unreachable
(`CueLog.kt:7`): a downshift is allowed while braking or coasting, so no gate
can emit it. The alternative — inventing a coast gate — would change cue
behaviour to make a log token appear. Not done.

### 6.4 SHADOW stays out of the chip

Spec: the mode selector must not change layout or drawing; ask before adding a
UI label. The chip cycles the three driving modes only (ECO → NORMAL → ZOOM);
SHADOW is reachable from the service intent (`SpikeService.kt:64,216`) and is
shown as `shadow` on the chip when active, not folded into NORMAL. Since
2026-11-12 the same chip renders on the landing and in the overlay, so the rule
holds on both surfaces.

### 6.5 Re-arm semantics match the base

The spec's "thresholds are BASE firing points; re-arm = threshold ± hysteresis"
is already how `c65cc87` works (`armedUp`/`armedDown`,
`ShiftAssistant.kt:325-329`, from `3dab5c7`). `8de03bf` still added hysteresis
to the firing point, which is why an old-code replay is not directly comparable
except through the pre-scaled input runs above.

### 6.6 One row per frame per *running* profile

A real mode runs one assistant, so one row per frame; SHADOW runs three and
writes three rows. The spec's "one row per frame per mode" is therefore exact
in SHADOW, and the log never invents rows for profiles that did not run.

### 6.7 `t_wall_ms` exists but a replay cannot fill it

The phone writes `System.currentTimeMillis()` on the stream thread
(`AssistantHost.kt:101`); the replay writes `0` rather than inventing a
clock. The monotonic column is first in both.

---

## 7. What is not verified / open

- **No car since 2026-11-10.** Everything above is replay, not driver response.
- **Lug limit: NO DATA.** 1100 is a comfort/economy floor (factory AMT reference),
  not a measured engine limit; 4000 is a design cap, not a measured power peak.
- **NORMAL 2000 stays** (decision 2026-11-10); the replay evidence is one
  driver's two drives.
- SHADOW has not been run on the handset against a live stream; the cue file is
  written but not yet pulled in anger.
- The display bug ("G is shown instead of the actual gear") is fixed and
  verified on the handset: the dashboard tile receives the assistant's
  estimate (with the bubble's 1.5 s hold), the panel reads `gearShown`, and an
  unknown gear is a dash everywhere. The app-side synthetic drive showed `-`
  before any frame and `2` once frames arrived.
