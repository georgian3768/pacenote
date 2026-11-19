# CAN Bus Decode Notes — Maruti Suzuki Celerio 2016

Machine-readable decode: `can/celerio.dbc` (validated with cantools against the
session A capture). Keep it in step with the table below.

Evidence: the captures these notes were derived from are **not published** —
they are raw logs of a privately owned vehicle. What is published is the
conclusion, the method (`record_gvret.py` / `parse_gvret.py`) and the DBC, so
the work can be repeated on any car.

- Parked/cold session: 215,113 frames over 519.6 s, ignition on → crank → idle →
  throttle sweeps. Frame counts and timings referenced below are from that log.
- A separate 264 s log (ignition → start → revs) was used as a second opinion.
- Timestamps below are relative to the start of the log.

## Confirmed signals

| Signal | ID | Bytes (1-indexed) | Format | Scale / Offset | Period |
|---|---|---|---|---|---|
| **Engine RPM** | 0x124 | D2–D3 | 16-bit big-endian | ×0.25 rpm/LSB, offset 0 | 10 ms |
| Engine RPM (cross-check) | 0x13F | D1–D2 | 16-bit little-endian | ×0.25 rpm/LSB, offset 0 | 20 ms |
| **Throttle position** (2 ch) | 0x120 | D3 + D5 | 8-bit each | idle ≈37, WOT ≈239 (range ~12–240); D3/D5 diverge on transients | 10 ms |
| **Brake pedal switch** | 0x318 | D1 | bitfield | bit2 (0x04)=brake pressed, 0=released; bit0 (0x01)=engine-running flag | 100 ms |
| **Engine-running status** | 0x120 | D1 + D4 | status | D1 0x01→0x00 + D4 0x13→0x03, flips ~9–10 s after crank onset | 10 ms |
| Engine-running status (2) | 0x318 | D2 | status | 0x46 (engine off) → 0x06 at crank onset | 100 ms |
| **Wheel speed** (raw) | 0x314 | D2–D3 | 16-bit big-endian | /256; km/h = raw × spdscale × GPS correction | 20 ms |
| **Slow thermal/state** (candidate: coolant temp, inverted NTC) | 0x124 | D4–D5 | 16-bit big-endian | engine-off ≈0x120F (4623); running ≈0x0FB5–0x1010 (4021–4112), drifts down as engine warms (−18/−20 per ~10 s early) | 10 ms |
| Rolling counter | 0x310 | D2 | 8-bit | increments +1 across events (103→110 over session) | 100 ms |
| Rolling counter | 0x120 | D6 | 8-bit | 0–3 | 10 ms |
| Rolling counter | 0x122 | D5 | 8-bit | counter-like | 10 ms |
| Rolling counter | 0x1AC | D1 | 8-bit | 0x00/0x40/0x80/0xC0 pattern | 12 ms |
| **Keepalive** | 0x3B8 | — | constant | sole ID still alive after key-out (100 ms); bus sleeps otherwise | 100 ms |

### RPM verification
- Engine off → 0 rpm. Crank onset at 159.5 s: ramp 46 → ~1104 rpm.
- Crank transient peak 5446 rpm at 160.4 s is **spurious** (decoding junk during crank onset); ignore.
- Idle ~700 rpm. WOT peak **4845 rpm** at 415 s during a full-throttle sweep.
- 0x13F D1–D2 LE correlates ~0.998 with 0x124 D2–D3.

### Throttle (0x120.D3/D5) — 2-channel
- D3 and D5 track each other at steady state but **diverge on transients** (e.g. at 378 s: D3=102, D5=140) → redundant 2-channel pedal/throttle sensor.
- Idle steady ≈ D3 37 / D5 33. Full press pegs both to ~218–239.
- Across twelve deliberate presses (375–389 s) D3 oscillates 12 (released) ↔ 239 (full).
- RPM **lags** throttle by ~100–300 ms (throttle leads, RPM follows) — confirms it is the input, not a load/RPM-derived value.

### Brake (0x318.D1)
- bit2 (0x04) set while brake pressed, clear when released. bit0 (0x01) = engine-running flag.
- Engine-off press = 0x04; engine-running press = 0x05 (01|04). Handbrake kept ON whole session (separate, not isolated here).

### Engine-running status (0x120.D1/D4, 0x318.D2)
- 0x120.D1 01→00 and D4 13→03 flip ~9–10 s after crank onset (engine confirmed running / ECU fully awake), **not** tied to clutch/brake/gear.
- 0x318.D2 flips 0x46→0x06 at crank onset.

### Slow thermal/state (0x124.D4–D5) — coolant-temp candidate
- Engine-off ≈ 0x120F (4623). Running ≈ 0x0FB5–0x1010 (4021–4112), slowly drifting **down** as engine warms (−18/−20 per ~10 s early, then slower). Consistent with an inverted NTC (coolant temp): cold=high, warming=lower.
- No 0x124 frames at all while key-out / bus asleep (signal exists only with ignition ON).

## Driving sessions — speed

The parked session could not answer speed; two driving sessions did. The wheel
speed lives on **0x314 (ABS_1), bytes 2–3, 16-bit big-endian, /256**. It is a
two-wire wheel sensor value, not km/h: the app multiplies it by a per-car
`spdscale` (2.2 by default) and then by a GPS-derived correction. Once those
two factors are set, the reading matches the speedometer to roughly 4 km/h RMSE.

The DBC keeps the signal **raw** (`WheelSpeedRaw`, /256) on purpose. A DBC with
a vehicle-specific multiplier baked in is a trap for anybody else: the factor
belongs to the car, not the frame.

## Not found (likely not broadcast)

- **Clutch switch** — no clean 2-state byte toggling with press/release.
- **Gear position** — `0x1AC` D3/D4 vary per event, but 3rd gear and reverse
  produce identical bytes (D3=20, D4=7), and the other candidates are monotonic
  counters or the thermal signal. This manual Celerio does not broadcast gear;
  the app computes it from rpm and road speed against calibrated ratios.
- **Fuel rate** — nothing on the bus behaves like injector pulse width or fuel
  consumption. Hence no fuel economy in the app.

## Open questions

1. Throttle scaling: D3 and D5 are clearly two channels of the same pedal, but
   the transfer function is not linear in pedal travel. Percentage is a
   normalisation, not a measurement.
2. Coolant `0x124.D4–D5` is an inverted NTC value, not degrees. A conversion
   would need a reference thermometer across a full warm-up; until then the app
   shows a trend.
3. Reverse gear is still undecoded — no bit is trusted until a real capture
   says so.

## Capture protocol notes
- Scripted GVRET capture: `can/record_gvret.py` (sends 0xE7 to enable binary mode, RAM-only) + `can/parse_gvret.py` (bin→CSV). Use PlatformIO venv python (`~/.local/share/pipx/venvs/platformio/bin/python`).
- `/dev/ttyUSB0` exclusive — close SavvyCAN before upload/monitor. Console & GVRET both at 1,000,000 baud.
- Reverse probe (phone-side, no laptop): Calibration → **reverse capture** records the ESP's text telnet stream (`192.168.4.1:23`) with `# phase N` / `# phase R` markers; analyze with `python3 can/analyze_reverse.py <file>`. Procedure and pitfalls: `docs/REVERSE-CAPTURE.md`. Reverse itself is still undecoded — no bit is trusted until a real capture says so.
