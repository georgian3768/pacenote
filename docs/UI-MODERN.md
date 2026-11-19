# Modern UI — decisions and data reality (branch `modern-ui`)

Status: 2026-11-10, branch `modern-ui` off `main`. This file is the spec the UI work is
built against. It records the choices that were argued out, and — more importantly — the
places where the original design asked for data that does not exist.

Rules that hold everywhere:

- The app is **listen-only**. `CANManager::sendFrame` is compiled to a stub. No screen may
  imply it writes to the vehicle ("ECU calibration" is not a thing that exists here).
- Nothing is invented. If a signal is not decoded, the UI says `--`, not a plausible number.

---

## 1. Vocabulary and precision

| Concept | Wording | Source of truth |
|---|---|---|
| Bus alive | `NO CAN` | firmware `kCanActiveMs = 500` (`ESP32RET-Work/src/telemetry.cpp:21`) |
| Stream dead | `NO LINK` | app `STREAM_LOST_MS = 10_000` (`SpikeService.kt`) |
| Stale value | `--` | firmware `kFreshMs = 2000` per signal |

Precedence in the bubble sub-label: `NO LINK` > `NO CAN` > gear.

Precision split is deliberate:

- **Bubble** rounds — `3.3k`, single-digit gear. It is a glance surface, read at 100 km/h.
- **Panel and dashboard** are exact — `3313 RPM`. They are read parked or by a passenger.

## 2. Palette (semantic tokens, dark + light)

Colours live in one place: `Theme.kt` (`Tokens`, `DarkTokens`, `LightTokens`). Compose reads
`LocalTokens`, View code calls `Context.themeTokens()`, and the dashboard CSS mirrors the same
values under `html[data-theme="light"]`. There is no in-app switch: the app follows the phone's
system theme, and a theme change recreates activities (the overlay re-themes in place).

| Token | Dark | Light | Use |
|---|---|---|---|
| page | `#0A0C10` | `#F2F4F7` | screen background |
| card | `#0A0C10` | `#FFFFFF` | dashboard cards, menu rows |
| raised | `#0C0E12` | `#FFFFFF` | bubble fill, nested blocks |
| border | `#2C3442` | `#D6DCE6` | 1 dp separators |
| ink | `#E9EDF5` | `#131820` | primary text (17.8:1) |
| label | `#8C96A5` | `#5B6676` | small uppercase labels (5.8:1) |
| dim | `#8892A6` | `#4C5868` | secondary text (7.2:1) |
| faint | `#5E6878` | `#66717F` | tertiary text (5.0:1) |
| idle | `#6E7887` | `#6B7787` | idle ring |
| link lost | `#8892A6` | `#4C5868` | NO LINK |
| up | `#2EC27E` | `#0E7A4A` | up-shift cue (5.4:1) |
| down / warn | `#F5A524` | `#B45309` | down-shift cue, warnings (5.0:1) |
| over-rev / rec | `#E5484D` | `#C62828` | over-rev, recording dot (5.6:1) |
| no-can | `#2DD4BF` | `#0F766E` | dashed ring (5.5:1) |
| info | `#78BEE6` | `#0B5E8A` | calibration "saved" (6.4:1) |
| rail idle | `#3A4250` | `#C2CAD6` | empty cue rail |
| track | `#1F2733` | `#E4E8EF` | throttle bar track |
| tick | `#6B7686` | `#8793A3` | calibration ticks (graphic, not text) |
| scrim | black 38 % | black 28 % | modal dim |

Light is deliberately **not** an inversion: the raw cue colours fail on white (`#2EC27E` is
~2.2:1), so each is darkened until it clears 4.5:1. The scrim stays black in both themes — a
white scrim over a white app is invisible. In light mode the bubble also gains a 1 dp border,
because a white fill on a white app would otherwise lose its edge.

Numerals are tabular everywhere. No fourth near-black: earlier drafts had `#07080A`,
`#0A0C10`, `#0C0E12` and `#151A22` in one screen, which reads as inconsistent rather than
layered.

### 2.1 How the theme reaches each surface (2026-11-13)

- **Compose** (`TelemetryPanel`, `ComposeOverlay`): `LocalTokens` composition local. The overlay
  is a `WindowManager` window owned by the service, so it does not get recreated on a theme
  change; `SpikeService.onConfigurationChanged` → `overlay.onOrientationChanged()` refreshes a
  snapshot-state token holder and both the bubble and card recompose in place.
- **Views** (`MenuActivity`, `CalibrationActivity`, `TripsActivity`, `MainActivity`,
  `OnboardingActivity`, `SplashActivity`, legacy `OverlayController`): `themeTokens()` from the
  activity's configuration, read once per instance (activities recreate on `uiMode` changes).
- **System widgets and the splash window:** `values/themes.xml` +
  `values-night/themes.xml`. `Theme.DeviceDefault.DayNight` has no `NoActionBar` variant in
  android-33, so the pair is `Theme.DeviceDefault.Light.NoActionBar` /
  `Theme.DeviceDefault.NoActionBar` behind one `AppTheme` name. Only `pm_page` and `pm_accent`
  live in XML — the window background has to paint before any Kotlin runs.
- **WebView:** both palettes are CSS variables; `DashboardActivity.pushConfig()` evaluates
  `window.pm.theme('light'|'dark')` before pushing anything else, and Chromium's auto-dark is
  disabled (`isAlgorithmicDarkeningAllowed` on 33+, `FORCE_DARK_OFF` on 29–32) so it cannot
  re-tint the chosen palette.
- **System bars:** `applySystemBarAppearance(tokens)` flips the status/navigation icon colour;
  the theme XML also sets `windowLightStatusBar`/`windowLightNavigationBar` for the first frame.

## 3. Bubble (72 dp)

72 dp squircle, 22 dp corner, raised fill (near-black, or white with a 1 dp border in the light
theme), ring whose **width as well as colour** encodes state — colour alone fails for red-green
deficiency:

| State | Colour | Stroke |
|---|---|---|
| idle / no cue | `#6E7887` | 1.5 dp |
| shift up | `#2EC27E` | 2.5 dp |
| shift down | `#F5A524` | 2.5 dp |
| over-rev | `#E5484D` | 4.0 dp + 420 ms alpha pulse |
| no can | `#2DD4BF` | 1.5 dp dashed |
| no link | `#8892A6` | 1.5 dp |

The ring inset is derived from the actual stroke width, so a 4 dp ring cannot clip.

Gear sub-label uses `GearDisplay` with a **1.5 s hold**: the estimator needs rpm and speed in
the same frame and suppresses everything below 15 km/h, so it drops out exactly during a
clutch-in. Hold semantics, which matter:

- hold **only** while the estimate is `null`;
- a fresh estimate always wins, immediately;
- clear the hold on any cue, on `NO CAN`, on `NO LINK`.

Anything else shows `3` after the driver has shifted to 4, which is worse than showing
nothing.

Interaction is unchanged from the verified build: drag, edge snap, 450 ms long-press gated at
5 km/h for the hide target, tap to open the panel.

### 3.1 When it is on screen (2026-11-14)

The bubble is a background surface, not a second dashboard. One setting, three states, cycled
by tapping the row (landing and menu use the same order so the gesture cannot diverge):

| Mode | On screen | Drops back |
|---|---|---|
| **Auto** (default) | after 1.5 s of continuous frames, and only while no Pocket Mechanic screen is in front | 10 s without frames; re-arms on the next burst |
| **Always** | whenever the service is alive, our own screens included | only when the driver asks for it again |
| **Off** | never | — |

`SHADOW` beats all three: the assistant renders nothing in that mode.

Rules that are not obvious from the table:

- **Armed, not instant.** Auto counts a *burst*: a gap longer than the arm window starts a new
  one, so one stray frame as the car wakes up cannot flash a bubble on the lock screen.
- **Foreground suppression** is counted from `onActivityStarted`/`onActivityStopped`
  (`PocketMechanicApp` → `AppForeground`), so a permission sheet or a system dialog counts as
  "in front" too. The 1 Hz policy tick reads an atomic; there is no flow to leak.
- **Drop on the hide target** is a dismissal, not a mode change. Auto keeps it hidden until the
  bus has been quiet once (the drive ends); Always keeps it hidden until the row is tapped
  again, because "comes back when the ignition is off" would be the opposite of what that mode
  means.
- **Permission granted later** is picked up: while the policy wants the bubble and the renderer
  is not shown, it retries `show()` only when `canDraw()` is true, so a missing permission does
  not log an attempt every second.

`show_overlay` (boolean) migrates once: `true` → Always, `false` → Auto. An install that had it
off never chose anything, so it inherits the new default instead of staying silently off.

## 4. Panel (modal overlay and landing segment)

The panel is a **modal** surface in the overlay: a full-screen scrim swallows every outside
tap and closes it. It therefore cannot be "the driving surface" while Maps is being used — the
driver could not touch Maps. The resolution is unchanged from the verified build: the panel is
a card-on-demand overlay, and the driver-facing surface stays the bubble.

### 4.1 One panel, two hosts (2026-11-12)

The landing screen now pins the same panel to the bottom of the app, under the menu, so the
numbers are present without the bubble. That is a hosting change, not a second implementation:

- `TelemetryPanel` (`TelemetryPanel.kt`) is the card: six blocks, throttle bar, cue rail, mode
  chip and action row. It is pure UI over `PanelUi` — no clock, no prefs, no service.
- `PanelFeed` (`PanelFeed.kt`) owns the time-dependent parts: the cue hold (a cue stays until
  the gear changes, 15 s safety net), the `GearDisplay` hold across a clutch-in, the 4 Hz number
  refresh, the CAN-silent transition and the link-lost reset. Both hosts feed it from the same
  frames. Audio and the border flash remain overlay-only through `onCue`.
- The overlay supplies its window, scrim and landscape column. The landing supplies one
  `ComposeView` below the WebView and always uses the portrait sheet.
- The landing is **portrait-only** for now (`DashboardActivity` has
  `android:screenOrientation="portrait"`). Landscape stays the overlay's cockpit column over
  another app; merging menu and panel side by side is a later change.
- The panel's `Hide` collapses the landing panel to a `SHOW TELEMETRY` strip; in the overlay it
  still closes the card and brings the bubble back.

Additions to the panel in this branch:

- **Trip header** from `SpikeService.trip` (`TripUi`), published at 1 Hz: recording dot,
  elapsed, distance, and `~NN` eco. The eco number is a live estimate reusing the documented
  formula (`TripAccountant.liveEcoScore`), and it moves early in a trip because the penalties
  are normalised per moving second. It is labelled with a tilde, never presented as a verdict.
- **Throttle bar**, labelled with its channel (`thr1 raw`, 0–255). Calibrated percentage only
  appears once `throttleIdleRaw`/`throttleFullRaw` are set, and only then are idle/WOT tick
  marks drawn — an uncalibrated bar with fake ticks would be a lie.
- **Two clocks for the bar and the digits (2026-11-13).** The stream gives ~10 samples a second.
  Replaying those straight into one snapshot made the bar step visibly at 4 Hz while forcing the
  digits to flicker at 10 Hz. `PanelFeed` now publishes `barThr1` on every frame as its own
  snapshot state, and `TelemetryPanel` reads it through a lambda inside `ThrottleBar`; the bar
  tweens for 120 ms and the animation value is read inside `drawBehind`. Each display frame
  invalidates only that draw — no recomposition, no layout — so the bar runs at the screen's
  60/90/120 Hz while rpm, speed and the throttle number stay on the 250 ms clock. The tween is
  display-only and never reaches the cue logic.
- **Six telemetry blocks in one group**, not spread down the card: speed over its unit, rpm
  over the mode chip, gear over the brake state. They are one glance and belong adjacent.
  Each value sits in a fixed-height slot so the third row (unit · chip · brake) lines up even
  though the speed digit is nearly twice the rpm digit. Landscape sizes the numbers up
  (speed 44 sp, values 26 sp) because the panel has the height; portrait keeps the compact
  sizes, since the sheet sizes itself to its content.
- **Brake** as a discrete indicator (`brake on` / `brake off` / `brake --`), because `brake`
  is a bit, not a level. `--` means the car did not say, which is not the same as off. It
  lives under the gear rather than beside the bar: the fixed 68 dp slot it used to own came
  out of the one control that should span the panel.
- **ECO / NORMAL / ZOOM chip**, tappable: one tap cycles ECO → NORMAL → ZOOM → ECO. ECO is
  the factory default (the mileage profile) and therefore must be in the cycle; the old
  two-state toggle could not reach NORMAL once left, and never offered ECO on a fresh install.
  ZOOM is the existing `SPORT` shift points, so every position selects a profile that already
  has tests behind it instead of inventing thresholds. The selection is
  `AssistantHost.mode` (a `StateFlow`), and `AssistantHost.setMode` is the only writer: the
  chip, the footer and the assistant's bank all read the same value, so a mode changed from the
  dev intent or the landing can never leave a stale label. SHADOW is a measurement mode and is
  **not** in the cycle; when it is set from the dev intent the chip reads `shadow` rather than
  standing in as NORMAL.
- **Cue rail** on the right edge, 18 % of the panel width in landscape and a physical 24 dp in
  portrait (where 18 % of a nearly full-width sheet would be a slab). A wash plus a drawn
  chevron: dim thin line for none, green chevron up, amber chevron down, red double chevron
  pulsing for over-rev. Shape carries the state as well as colour, so the cue survives a
  driver who cannot separate red from green. The wash and chevron are drawn in `drawBehind`
  — no icon assets. Precedence is the bubble's: **NO LINK > NO CAN > cue**, because a green
  arrow over frozen values is the most dangerous thing this screen could show. A cue older
  than 2.5 s dims to 40 % like its text.
- **Full height in landscape**: display height minus system bars, less a 6 dp margin at each
  end. At exactly the available height the bottom border and its 20 dp corners landed on the
  last pixel row and were invisible on the device. Below ~280 dp of height the content column
  scrolls rather than clipping the action row.
- **Service remaining** on its own footer line, refreshed once a minute from Room, not on the
  1 Hz loop. Sharing the status line wrapped `486` away from `km`.
- **Bottom inset** applied from `navigation_bar_height`, since an overlay window has no
  Activity and therefore no insets controller. Without it the card sits under 3-button nav.
- **Dimmed scrim** (38 % black): it exists to swallow outside taps, but undimmed the card
  floated over a bright app and looked like a rendering accident rather than a modal.
- The `Maps · Music · Calib · Hide` action row is **kept**. It is the panel's only in-place
  navigation, and `Calib` is otherwise unreachable from the driving surface.

### 4.2 Navigation HUD (2026-11-16)

While a navigation session is running, the full panel is the wrong surface: it opens as a
modal with a scrim, so the driver cannot touch Maps, and the maneuver card and ETA bar are
exactly where the numbers would go. Split screen was investigated and rejected (the app
cannot ask for it and the ROM tears manual splits down — see `docs/PROGRESS.md`), so the
approved fallback is an **edge HUD**: the bubble's data in a 132 × 146 dp card on the left
edge, with no scrim.

- **A layout, not a mode.** `TelemetryOverlay.setNavLayout(Boolean)` swaps the surface only.
  `AUTO / ALWAYS / OFF`, the hide-target dismissal, the 1.5 s arm and the 10 s silence rule
  all behave exactly as before. The landing row still says `Auto`, because nothing about the
  policy changed.
- **Geometry.** 132 × 146 dp, 18 dp corners, 6 dp from the left edge, vertically centred:
  clear of the maneuver card (top) and the ETA bar (bottom) that navigation apps own. Window
  verified on-device at 363 × 401 px on 440 dpi. `ComposeOverlay` now sizes and clamps by
  width **and** height instead of assuming a square; drag and edge-snap work unchanged.
- **Content.** SPEED label, speed (36 sp) over its unit, RPM | GEAR in two columns, THROTTLE
  label with the calibrated bar pinned to the bottom edge, and the panel's own `CueRail` on
  the right. The rail is the same composable as the panel's (now `internal`), so the
  chevrons, the over-rev pulse and the dimming rule cannot drift apart. `speedLabel()` is
  shared for the same reason.
- **Tap → full panel** (the modal card, scrim included); closing it rebuilds the HUD in the
  new geometry. The HUD is rebuilt, not re-added, on every layout swap — same
  `rebuildBubble` path that fixed the frozen NO LINK bubble.
- **Switching** is owned by the service (`OverlayHost` remembers the layout across renderer
  swaps). The real trigger is the navigation notification from Maps/Waze
  (`NavListenerService`, below); the dev screen keeps `Nav HUD (bubble ⇄ HUD)` so the
  surface can be reviewed on a bench with no Maps session.
- The legacy `OverlayController` ignores the flag (the interface default), so it keeps its
  bubble until it is retired.

#### 4.2.1 The navigation trigger (2026-11-16)

- **`NavListenerService`** is a `NotificationListenerService`; the only supported public
  signal for "turn-by-turn is running" is the ongoing notification both apps post with
  `Notification.CATEGORY_NAVIGATION`. Only `com.google.android.apps.maps` and `com.waze` are
  trusted, because the category is self-declared and any app could otherwise take over the
  screen.
- **The active key set is the state.** Maps can have more than one navigation notification up
  (a re-route, a detached variant), so removing one must not end the session while another
  remains. `onListenerConnected` rebuilds the set from `activeNotifications`: a process
  restart loses every callback that came before it.
- **`NavState`** is a process-wide flag with named listeners, so ordering never matters:
  the listener can be bound before `SpikeService` starts (the service applies
  `NavState.isNavigating` in `onCreate`), and the service can outlive a listener unbind
  (revoked notification access falls back to the bubble).
- **Access is granted outside the app.** The landing row `Navigation HUD` opens
  `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30+, with the list screen as fallback)
  and shows `On · Maps and Waze switch the bubble to the edge HUD` when
  `NotificationManagerCompat.getEnabledListenerPackages` contains this package; there is no
  in-app toggle, because the grant is the feature.
- **The app's own notifications must also be on** for the navigation app in question. This
  handset had Maps notifications disabled (`POST_NOTIFICATION: ignore`), so Maps posted no
  navigation notification at all and the HUD could not trigger; enabling Maps notifications
  in system settings is part of the setup, not something the app can or should change.
- The legacy renderer ignores the layout, so a notification trigger on the legacy engine is
  a no-op rather than a broken surface.

## 5. Home: menu (WebView) + native panel

Home is a split screen since 2026-11-12: the menu is still the bundled WebView
(`assets/dashboard/index.html`), and the telemetry panel is a native `ComposeView` pinned
below it. The panel is the same `TelemetryPanel` the overlay renders — see 4.1.

The WebView contract is now only `window.pm.{config,lastTrip}`:

- `config` paints the floating-overlay row (`mode`, description) and the car/driver strings.
- `lastTrip` paints the last-trip strip and the `N drives` subtitle; Room is queried on
  resume and when a trip ends, not on the 1 Hz publish.
- `frame`, `link`, `trip` and `cue` are gone on purpose. Telemetry rendered here would be a
  second implementation of the panel, which is exactly what the merge removed.

Menu rows: Trip history, Sensor calibration, Vehicle & driver, Floating overlay (with the
live mode), and a demoted Diagnostics row. Each row calls a small bridge method; the
stream itself is never touched from JS.

The old HTML telemetry (rpm bar, big speed, cue text, footer status) was removed with the
merge. Its rules moved into the native panel unchanged: the 95 % over-rev threshold, amber
down-shift, `NO CAN` / `NO LINK`, and the 2.5 s cue dim.

### Tradeoff: WebView menu vs native menu

| | WebView menu (chosen) | Native (Compose) menu |
|---|---|---|
| Cost | already written; the merge added the panel beside it | rewrite the five rows and the last-trip strip in Compose |
| Risk | low — the bridge was already load-tested on device | re-implements a working screen for no data gain |
| Iteration | edit HTML/JS, no Kotlin rebuild | rebuild for every tweak |
| Consistency | two rendering systems on one screen, but the panel is shared with the overlay rather than copied | one stack |
| Ceiling | the menu can be restyled quickly; the panel cannot be drawn in HTML at all | full |

The panel is native because it must be the same code as the overlay. The menu stays HTML
because it is the part that changes most often and owns no telemetry.

## 6. Sensor calibration (was "ECU calibration")

- Title renamed everywhere. The firmware cannot write to the ECU, and a screen that implies
  otherwise undermines the safety property that is worth advertising.
- **No coolant temperature, no `≥80 °C` pre-check.** `tempRaw` is not a temperature: it falls
  as the engine warms, pins at 4000 once warm, and glitched to 4800 in 33 single frames. It is
  deliberately not stored. A check on it would either always pass or always fail.
- The **11 resumable steps are kept**, grouped under four visual section headers (baseline /
  throttle / gears / speed & GPS). Sections are labels; they gate nothing. The resumable
  property is the wizard's best feature — calibrating 3rd today and 4th next week.
- The **redline confirmation is kept**. It gates over-rev alerts and marks trip scores
  provisional.
- Pre-checks are per step, not global: idle/accel/release need engine + link, brake needs link,
  gears need link + speed, GPS needs speed + permission, redline needs nothing but a decision.
  A global "engine running" gate would block re-confirming a redline or re-measuring the brake
  bit with the engine off, which are valid link-only operations today.
- Idle band `674–924 rpm` is shown as a **hint**, not a gate. It is the observed span on this
  car, and cold fast idle sits above it.
- New: rpm sparkline (~20 s, ~10 Hz, no allocation in `onDraw`), pinned footer, header with a
  listen-only note.

### 6.1 Reverse capture (2026-11-14)

Reverse is the one gear that is not broadcast on this bus (0x1AC carries a 3rd/reverse
ambiguity and no clutch or gear-position frame was found in Session A), so the screen gained
a separate raw capture instead of a guessed decoder:

- **Start** opens the ESP32's telnet port 23 on `WifiController.espNetwork`, sends one GVRET
  keepalive (`F1 09`) and stores the text frames in arrival order. The keepalive is the only
  byte written; no CAN frame is ever sent.
- **Reverse is selected now** writes a `# phase R` comment at the current row. The split is
  positional, so the phone's clock and the ESP's `micros()` counter never have to agree.
- **Stop and save** waits for the reader to finish, writes
  `Downloads/PocketMechanic/reverse_capture_<stamp>.csv` through `Exports` and opens the share
  sheet. Leaving the screen abandons the socket and saves nothing.

The parser (`GvretText` in `shiftassist`) tolerates the one ugly thing the wire does: the
keepalive reply is four binary bytes with no newline, so it prefixes the next text frame.

The file is a normal SavvyCAN text capture plus comments. Analysis is a separate, offline
script (`can/analyze_reverse.py`); the app does no reverse detection. Format, procedure and
pitfalls are in `docs/REVERSE-CAPTURE.md`.

## 7. Open items

- Throttle channel identity (`thr1` vs `thr2`) is unresolved; every surface names the byte it
  draws rather than implying "throttle".
- Tail decoration colours: `NO CAN` uses teal to stay distinguishable from over-rev red and
  down-shift amber; needs a look on a real screen at 72 dp.
- Real over-rev beep by ear, in-car cue validation, GPS scale on a known route — all still
  need the car.

## 8. Screen-on (2026-11-15)

The phone was sleeping mid-drive because the app had no `FLAG_KEEP_SCREEN_ON` and no wake
lock — the system timeout applied even with the panel over Google Maps. The fix is the window
flag, not a wake lock:

- `AppSettings.keepScreenOn` defaults on; the menu row "Keep screen on" turns it off.
- `DashboardActivity` sets the flag on its own window in `onCreate`/`onResume`.
- Both overlay renderers set it on the card and the bubble. `onKeepScreenOnChanged` patches a
  live window when the menu row is toggled, so the change does not wait for a rebuild.
- `SCREEN_BRIGHT_WAKE_LOCK` was rejected: deprecated and a no-op on modern Android. A window
  flag lapses with the window, so a crash or a finished drive cannot leave the screen lit.

Gap to know: with the overlay hidden and the app in the background the flag is gone, and the
system timeout applies again. That is deliberate — the flag follows what the driver is looking
at rather than holding the phone awake for an entire trip.

## 9. ESP32 Wi-Fi row (2026-11-15)

The landing menu gained a third connection row beside `Floating overlay` and `Keep screen
on`: **ESP32 Wi-Fi**. It reports one of four states from `WifiController.describe()`, shared
with the native menu so the two surfaces cannot word it differently:

| State | Row |
|---|---|
| connected, saved | `Saved · connected now` |
| connected, not saved | `Connected now · tap to save for auto-reconnect` |
| saved, absent | `Saved · Android rejoins when the ESP returns` |
| not saved, absent | `Not saved · Android may not rejoin after power-off` |

The tap opens the system add-network dialog (`Settings.ACTION_WIFI_ADD_NETWORKS`, API 30+),
which puts `A0RETSSID` in the platform's saved list — that is the list Android actually
rejoins after an AP power-cycle. The `WifiNetworkSuggestion` stays registered as the API-29
fallback and as a second chance on newer releases. A loss no longer goes silent either: the
service arms a 45 s timer on the passive callback and posts a low-importance "ESP32 not
connected" notification whose tap opens the Wi-Fi panel. The timer is only armed by a real
loss, so starting the app at home with the ESP off does not nag.
