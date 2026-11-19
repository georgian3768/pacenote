# Eco score

A 0–100 number shown per trip. It is a **driving-smoothness heuristic**, not fuel
economy: fuel consumption is out of scope for this project (no fuel-rate signal
on the Celerio bus), so the score measures the things the bus does tell us and
which correlate with wasteful driving.

Formula (implemented in `TripAccountant.ecoScore`, unit tested in
`TripAccountantTest`):

```
penalty = 0.40 * overRev
        + 0.25 * idling
        + 0.20 * accel
        + 0.15 * speeding

score   = round(100 * (1 - penalty)), clamped to 0..100
```

| term | definition | why it is normalised that way |
|---|---|---|
| `overRev` | `overRevMs / movingMs` (rpm ≥ 95 % of the configured redline) | Per **moving second**, not per trip: one redline pull in a 40-minute drive should not score the same as a 3-minute thrash. |
| `idling` | `idleMs / tripDurationMs` (speed < 1 km/h, engine running) | Standing still with the engine on is pure waste and is visible on the bus. |
| `accel` | `hardAccelCount / (distanceKm + 0.5) / 2`, saturated at 1 | Per kilometre, so a long trip is not punished for having more events; the `+0.5` keeps a 100 m trip from dividing by ~0. Counted with a 2 s cooldown per event, threshold 1.5 m/s². |
| `speeding` | `(maxSpeedKph - 100) / 30`, saturated at 1 | Crude by design: the legal limit is not known, 100 km/h is a conservative reference for this car. |

Weights sum to 1.0, so a perfect trip scores exactly 100 and a trip that is
over-revving, idling, flooring it and speeding scores 0.

## What the score is *not*

- **Not comparable across cars or drivers until calibrated.** The redline is a
  per-profile value (6,400 rpm on this car since 2026-11-10, confirmed), so `overRev`
  moves if the profile changes — and every trip row stores the redline it was scored
  against, so old scores stay reproducible.
- **Not affected by missing data.** A trip with no speed signal (CAN silent)
  integrates no distance and no idle time, so it scores 100. That is a lie about
  the drive, not about the formula — trips only start on `engine_running` (the
  0x120/0x318 status bits, `Probable` in `can/celerio.dbc`), so a missed start
  silently costs the whole drive.
- **Not a safety score.** Braking and steering are not measured.

## Distance and `dist_raw`

Distance is integrated from the CAN speed (trapezoid, 1 s max step, gaps ignored).
`dist_raw` (0x314 D3–D4) is **cross-checked, never trusted**: its unit is unknown,
so the app stores the raw delta and `dist_raw_per_km` and shows the ratio per
trip. A ratio that drifts between trips means one of the two sources is broken;
a stable ratio is what will eventually calibrate the raw counter.

Observed on the bench (synthetic drive, 2026-10-31): `dist_raw_per_km ≈ 1710`.
That number is meaningless until a real drive provides a GPS-referenced distance —
it is recorded so the in-car run can compare.
