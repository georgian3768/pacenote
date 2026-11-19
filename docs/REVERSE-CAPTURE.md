# Reverse capture

A one-purpose facility: find out whether this bus says "reverse is engaged", and
if it does, which bit says it. Nothing in the app decodes reverse yet.

## Why a raw capture

Session A/B decoded rpm, throttle, brake and the thermal candidate, but not
reverse:

- the Celerio is a manual, and no clutch or gear-position frame was found;
- `0x1AC` has a 3rd/reverse ambiguity (same value in both);
- a reverse signal may live on a body module that only transmits when reverse is
  selected, which is invisible in a forward-drive capture.

A guessed decoder would be a lie on the panel. A raw capture answers the question
with evidence, and the same file can be opened in SavvyCAN because it is the
normal text GVRET dialect.

## Where it runs

`Sensor calibration` → **reverse capture** (below the bench demo). Android only.

| Action | What happens |
|---|---|
| Start | connects to `192.168.4.1:23` on the ESP's own network, sends one keepalive, records |
| Reverse is selected now | writes `# phase R` at the current row (one-way per capture) |
| Stop and save | closes the socket, writes `Downloads/PocketMechanic/reverse_capture_<stamp>.csv`, opens the share sheet |
| leave the screen | socket closed, nothing saved |

The capture is listen-only. The firmware's telnet buffer only starts filling
after it receives a client byte, so one GVRET `PROTO_KEEPALIVE` (`F1 09`) is sent
at connect. That is a protocol command, not a CAN frame.

## Procedure

Parked, handbrake on, engine running, gearbox in neutral:

1. Join the ESP32's Wi-Fi (`A0RETSSID`). Close SavvyCAN's **Wi-Fi** connection if
   one is open — the ESP serves one telnet client (`MAX_CLIENTS 1`). The USB
   GVRET stream is independent and does not need to be closed.
2. Calibration → Start reverse capture. The status line must reach
   `recording · N frames`. If it says `failed`, read the reason on the line.
3. Let it record ~5 s of neutral. The frame count should climb.
4. Press the clutch, select reverse, then tap **Reverse is selected now** while
   reverse is actually engaged. Keep the pedal down so the car stays still.
5. Hold reverse for ~5–10 s, then tap **Stop and save** and share the file to the
   laptop.
6. Repeat once. A candidate that appears in one capture and not the next is
   noise, not reverse.

The capture keeps running if the activity is backgrounded, but it is discarded
when the activity is destroyed. A five-minute capture is plenty; the in-memory
log caps at 500,000 frames (~30 MB).

## File format

Text GVRET, one frame per line, plus `#` comments:

```
# Pocket Mechanic reverse capture
# started 2026-11-14 20:15:03
# source 192.168.4.1:23 (ESP32RET GVRET over telnet, text)
# format micros - id S|X bus length b0..bN (id and bytes are hex)
# listen-only: one keepalive (F1 09) at connect; no CAN frame is written
# phases: N = neutral before the driver selects reverse, R = after
# phase N - neutral, engine running, stationary
1234567 - 120 S 0 8 21 4e 00 00 00 00 00 00
...
# phase R - driver selected reverse at 12.4s (frame 832)
1234567 - 318 S 0 8 04 46 00 00 00 00 00 00
...
# end - reverse marked at 12.4s; 2140 frames stored, 0 lines dropped, duration 21.8s
```

`micros` is the ESP32's 32-bit `micros()` counter, the same time base SavvyCAN
shows. `S` is standard (11-bit) and `X` extended (29-bit); ids and bytes are
lowercase hex without `0x`. The split is **row order**, not time: everything
between `# phase N` and `# phase R` is neutral, everything after the `R` marker
is the reverse attempt.

The keepalive reply (`F1 09 DE AD`, four binary bytes with no newline) is glued
to the next text line on the wire. The app's parser finds the frame in the line
and re-emits it canonically, so the file contains no binary prefix.

## Analysis

```
python3 can/analyze_reverse.py can/reverse_capture_20261114_201530.csv
```

Options: `--min-frames 5` (frames of an id required in each phase before a bit can
be a candidate), `--top 20`, `--census 12`.

The script reports:

1. **IDs only after reverse** — a module that wakes with reverse. These are worth
   following even at low counts, but a handful of frames can also be a rare
   broadcast that simply did not occur during the short neutral phase.
2. **IDs only before reverse** — a frame that stops in reverse, e.g. a
   gear-position message that drops out.
3. **Bit candidates** — bits fixed in *every* frame of each phase and opposite
   between them, with the values that were seen. Example from the known bus:
   `0x318 D1 bit2` reads `{00}` neutral / `{04}` reverse when the brake is not
   pressed; that is the brake bit and would be a false reverse candidate if the
   driver held the brake in one phase only.
4. **Bus census** — id, frame count and rate per phase, so a capture with a dead
   bus or a missing module is obvious before reading candidates.

`D1` is the first data byte, `bit 0` is the LSB, matching `can/notes.md`.

### Pitfalls

- **Brake and engine state are confounders.** 0x318 D1 bit2 is brake; 0x318 D2
  and 0x120 D1/D4 track engine-running state; 0x124 D4–D5 is the thermal
  candidate. Release the brake in both phases and keep the engine running
  throughout.
- **Short phases lie.** Five frames in reverse can make many bits look constant.
  Capture at least 5 s per phase; the default `--min-frames 5` is a floor, not a
  guarantee.
- **Absence is not proof.** A body module may only transmit reverse over a
  different bus, on a different connector, or not at all on a base trim. Two
  clean captures with no candidate mean "not on this bus", not "bad script".
- **micros() wraps** after ~71 minutes. Two captures are cheap; do not record a
  feature-length film and expect the duration column to be exact.
- The file is the raw bus. It contains no GPS and no audio, but it may contain
  identifiers the car broadcasts; treat it like a vehicle log.

## If no candidate exists

The next option is a hardware reverse input: tap the reverse-lamp circuit through
an optocoupler into a spare ESP32 GPIO, stream that state alongside CAN, and use
it as the reverse signal. That is a firmware and wiring change, deliberately not
started before a capture proves the bus cannot answer it. The capture facility
stays useful either way: it is how the input would be validated against the car.
