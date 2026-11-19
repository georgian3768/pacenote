#!/usr/bin/env python3
"""Diff the neutral and reverse phases of a Pocket Mechanic reverse capture.

Usage:
    analyze_reverse.py can/reverse_capture_YYYYmmdd_HHMMSS.csv [--min-frames 5]

The phone writes a text GVRET capture with `# phase N` / `# phase R` comment
lines (see docs/REVERSE-CAPTURE.md). This script makes no assumptions about
which frame carries reverse: it reports

  * ids that transmit only in one phase (a module that wakes with reverse),
  * bits that are constant in each phase and opposite between them
    (e.g. 0x318 D1 bit2: 0 in neutral, 1 in reverse),
  * a census of the bus in each phase, so the known frames can be sanity-checked.

It is deliberately conservative: a bit only appears when it never changed
within either phase and there were at least --min-frames frames of that id in
both phases. Capture twice before believing a candidate.
"""
import argparse
import re
import sys
from collections import Counter
from dataclasses import dataclass, field

# Firmware / SavvyCAN text dialect: micros - id S|X bus len b0..bN.
FRAME = re.compile(
    r"(\d{1,10})\s*-\s*([0-9A-Fa-f]{1,8})\s+([SX])\s+(\d)\s+(\d)"
    r"((?:\s+[0-9A-Fa-f]{1,2})+)?"
)
PHASE = re.compile(r"^#\s*phase\s+([NR])\b", re.IGNORECASE)

PHASE_LABEL = {"N": "neutral", "R": "reverse"}


@dataclass
class IdStats:
    frames: int = 0
    first_us: int = 0
    last_us: int = 0
    lengths: Counter = field(default_factory=Counter)
    # bytes[i]: every value seen in data byte i (D1 is index 0).
    bytes: list = field(default_factory=lambda: [Counter() for _ in range(8)])

    def add(self, micros, length, data):
        if self.frames == 0:
            self.first_us = micros
        self.last_us = micros
        self.frames += 1
        self.lengths[length] += 1
        for i, value in enumerate(data):
            self.bytes[i][value] += 1

    def byte_frames(self, index):
        return sum(self.bytes[index].values())


@dataclass
class PhaseStats:
    frames: int = 0
    dropped: int = 0
    first_us: int = None
    last_us: int = None
    ids: dict = field(default_factory=dict)

    def add(self, micros, fid, length, data):
        if self.frames == 0:
            self.first_us = micros
        self.last_us = micros
        self.frames += 1
        self.ids.setdefault(fid, IdStats()).add(micros, length, data)

    def duration_s(self):
        if self.first_us is None:
            return 0.0
        delta = self.last_us - self.first_us
        if delta < 0:  # micros() is a 32-bit counter on the ESP32
            delta += 1 << 32
        return delta / 1_000_000.0


def load(path):
    phases = {"N": PhaseStats(), "R": PhaseStats()}
    markers = []
    phase = "N"
    loose = 0
    examples = []
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\r\n")
            if not line.strip():
                continue
            marker = PHASE.match(line)
            if marker:
                phase = marker.group(1).upper()
                markers.append((phase, line.strip()))
                continue
            if line.lstrip().startswith("#"):
                continue
            match = FRAME.search(line)
            if not match:
                phases[phase].dropped += 1
                if len(examples) < 3:
                    examples.append(line[:80])
                continue
            micros = int(match.group(1))
            fid = int(match.group(2), 16)
            length = int(match.group(5))
            raw_data = match.group(6)
            data = [int(token, 16) for token in raw_data.split()] if raw_data else []
            if len(data) != length:
                phases[phase].dropped += 1
                if len(examples) < 3:
                    examples.append(line[:80])
                continue
            if not markers:
                loose += 1
            phases[phase].add(micros, fid, length, data)
    return phases, markers, examples, loose


def bit_masks(values):
    """(bits always 0, bits always 1) across every value seen for a byte."""
    always_zero = 0xFF
    always_one = 0xFF
    for value in values:
        always_zero &= (~value) & 0xFF
        always_one &= value
    return always_zero, always_one


def value_set(counter):
    return "{" + ",".join(f"{value:02x}" for value in sorted(counter)) + "}"


def fmt_id(fid):
    return f"0x{fid:03x}"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("capture", help="reverse capture CSV written by the app")
    parser.add_argument(
        "--min-frames", type=int, default=5,
        help="frames of an id required in each phase before a bit is a candidate (default 5)",
    )
    parser.add_argument("--top", type=int, default=20, help="candidate rows to show (default 20)")
    parser.add_argument("--census", type=int, default=12, help="ids per phase in the census (default 12)")
    args = parser.parse_args()

    phases, markers, examples, loose = load(args.capture)
    neutral, reverse = phases["N"], phases["R"]

    print(f"file        {args.capture}")
    print(
        f"frames      {neutral.frames + reverse.frames} "
        f"(N {neutral.frames}, R {reverse.frames}), dropped lines "
        f"{neutral.dropped + reverse.dropped}"
    )
    print(
        f"duration    N {neutral.duration_s():.1f} s, R {reverse.duration_s():.1f} s"
    )
    if markers:
        print("markers     " + " | ".join(text for _, text in markers))
    if loose:
        print(f"note        {loose} frames arrived before the first # phase marker; counted as N")
    if examples:
        print("dropped e.g. " + " | ".join(examples))

    if neutral.frames == 0:
        print("\nNo frames in the neutral phase. Was the engine running and the key on?", file=sys.stderr)
        return 2
    if reverse.frames == 0:
        print(
            "\nThe reverse phase is empty: no `# phase R` marker or no frames after it."
            "\nStart a new capture and tap \"Reverse is selected now\" while the car is in reverse.",
            file=sys.stderr,
        )
        return 1

    # --- ids that only transmit in one phase --------------------------------
    only_r = sorted(
        (fid for fid in reverse.ids if fid not in neutral.ids),
        key=lambda fid: reverse.ids[fid].frames, reverse=True,
    )
    only_n = sorted(
        (fid for fid in neutral.ids if fid not in reverse.ids),
        key=lambda fid: neutral.ids[fid].frames, reverse=True,
    )

    print(f"\nIDs only after reverse ({len(only_r)}):")
    if not only_r:
        print("  none")
    for fid in only_r[:args.top]:
        stats = reverse.ids[fid]
        note = "  (low count - could be chance)" if stats.frames < args.min_frames else ""
        print(f"  {fmt_id(fid):8} {stats.frames:6} frames  len={dict(stats.lengths)}{note}")

    print(f"\nIDs only before reverse ({len(only_n)}):")
    if not only_n:
        print("  none")
    for fid in only_n[:args.top]:
        stats = neutral.ids[fid]
        note = "  (low count - could be chance)" if stats.frames < args.min_frames else ""
        print(f"  {fmt_id(fid):8} {stats.frames:6} frames  len={dict(stats.lengths)}{note}")

    # --- bits that are fixed in each phase and opposite between them --------
    candidates = []
    skipped_small = 0
    for fid in sorted(set(neutral.ids) & set(reverse.ids)):
        n, r = neutral.ids[fid], reverse.ids[fid]
        for index in range(8):
            n_values, r_values = n.bytes[index], r.bytes[index]
            n_frames, r_frames = n.byte_frames(index), r.byte_frames(index)
            if n_frames == 0 or r_frames == 0:
                continue
            if n_frames < args.min_frames or r_frames < args.min_frames:
                skipped_small += 1
                continue
            n_zero, n_one = bit_masks(n_values)
            r_zero, r_one = bit_masks(r_values)
            for bit in range(8):
                mask = 1 << bit
                n_value = 1 if n_one & mask else 0 if n_zero & mask else None
                r_value = 1 if r_one & mask else 0 if r_zero & mask else None
                if n_value is None or r_value is None or n_value == r_value:
                    continue
                candidates.append(
                    {
                        "fid": fid,
                        "byte": index,
                        "bit": bit,
                        "n": n_value,
                        "r": r_value,
                        "evidence": min(n_frames, r_frames),
                        "n_values": n_values,
                        "r_values": r_values,
                    }
                )

    candidates.sort(key=lambda c: (-c["evidence"], c["fid"], c["byte"], c["bit"]))
    print(f"\nBit candidates (fixed in each phase, opposite between phases, top {args.top}):")
    if skipped_small:
        print(f"  ({skipped_small} byte positions skipped: fewer than {args.min_frames} frames in a phase)")
    if not candidates:
        print("  none - see docs/REVERSE-CAPTURE.md for what to try next")
    else:
        print("  rank  id      byte bit  N  R   frames N/R   values N / R")
        for rank, c in enumerate(candidates[:args.top], 1):
            print(
                f"  {rank:4}  {fmt_id(c['fid']):8} D{c['byte'] + 1:<2} {c['bit']:<3}  "
                f"{c['n']}  {c['r']}   {c['evidence']:>8}      "
                f"{value_set(c['n_values'])} / {value_set(c['r_values'])}"
            )

    # --- census --------------------------------------------------------------
    for key, stats in (("N", neutral), ("R", reverse)):
        print(f"\nBus census, phase {key} ({PHASE_LABEL[key]}), top {args.census} of {len(stats.ids)} ids:")
        duration = max(stats.duration_s(), 1e-9)
        top = sorted(stats.ids.items(), key=lambda item: item[1].frames, reverse=True)
        for fid, id_stats in top[:args.census]:
            print(
                f"  {fmt_id(fid):8} {id_stats.frames:6} frames  "
                f"{id_stats.frames / duration:7.1f}/s  len={dict(id_stats.lengths)}"
            )

    print(
        "\nRemember: brake (0x318 D1 bit2) and engine/thermal bytes also move between"
        "\na parked neutral capture and a reverse capture. A reverse candidate must"
        "\nflip when reverse is selected and return when it is released - capture twice."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
