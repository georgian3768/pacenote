# Roadmap

Not promises. This is the order the work makes sense in, and the reasons.

## 2.0 — make the co-driver actually coach

**Voice and cue polish.** Cues exist but arrive as sounds and small text. The
goal is short spoken callouts ("brake later", "shift up") that land *before*
the corner, not after it. Latency budget matters more than voice quality.

**Predictive cueing.** Instead of reacting to the current frame, look a few
seconds ahead using the same gear/speed model — the difference between telling
someone they braked late and telling them they are about to.

**A model of the driver, not the car.** Per-trip smoothness is currently
absolute. A personal baseline (same road, same traffic) makes the score mean
something. Display-only at first: never let a model's opinion change what the
HUD demands mid-corner.

**Health view.** A single screen for the slow signals — coolant trend, battery
voltage, idle stability — with the "is this normal for this car" comparison
done against recorded history rather than a workshop manual.

**Fuel log.** Manual fill-ups, because the bus carries no fuel-rate signal.
Litres and odometer are enough to produce real consumption; anything more
requires guessing.

**Data portability.** `allowBackup` is off, which is right for privacy and wrong
for durability: a lost phone currently loses every drive. In-app
backup/restore to `Downloads/` fixes that and makes future migrations cheap.

**Database compaction.** Frame history grows faster than trips do. Roll frames
older than 90 days into per-trip summaries unless the trip is starred.

## Later

- **Home-screen widget** — last trip, eco score, next service.
- **Multi-car support** — several DBCs/calibrations, switched per trip.
- **More vehicles** — the method is in `docs/REVERSE-CAPTURE.md`; each car needs
  its own capture and evidence.
- **F-Droid** — feasible (no Google Play Services, no proprietary
  dependencies), but it needs a reproducible build recipe first.

## Deliberately not planned

- **Transmitting on CAN.** Listen-only is the safety property that makes this
  project defensible. It does not change.
- **Firmware OTA.** The partition layout has no slot, and the flashing procedure
  is one command. Not worth the space.
- **Cloud sync.** Trips are yours; there is no server to leak them.
