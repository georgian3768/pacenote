#!/usr/bin/env python3
"""Export recorded trip frames from the app's trips.db to replay JSONL.

The Android app stores every telemetry frame per trip in `trip_frames`
(schema v1: one row per merged frame, ~13 Hz). This turns one trip into the
JSONL the shift-assistant replay harness reads, so a change can be measured
against a drive that already happened instead of waiting for the next one.

Usage:
    adb exec-out run-as com.pocketmechanic.spike cat databases/trips.db > trips.db
    python3 tools/trips_to_jsonl.py trips.db 19 20 -o /tmp/replay

Then:
    gradle :shiftassist:replay -Pjsonl=/tmp/replay/trip19.jsonl \
        -PreplayArgs="--scale 0.893 --redline 6100 --idle 31 --full 219"

Notes:
  * speed_kph is exported RAW (as the CAN frame carried it). The replay applies
    --scale once, exactly like TripRecorder does, so pass the row's own
    speed_scale; trips recorded before 2026-11-09 were stored unscaled.
  * `frames` is the table name in database copies pulled before DB v4; current
    builds call it `trip_frames`. Both are accepted.
"""

import argparse
import json
import os
import sqlite3
import sys

FIELDS = ("tMs", "rpm", "speedKph", "thr1", "thr2", "brake", "engineRunning",
          "canActive", "distRaw")


def table_name(conn):
    names = {r[0] for r in conn.execute("select name from sqlite_master where type='table'")}
    for candidate in ("trip_frames", "frames"):
        if candidate in names:
            return candidate
    raise SystemExit("no trip_frames/frames table in this database")


def export(conn, table, trip_id, out_dir):
    cur = conn.execute(
        f"select {', '.join(FIELDS)} from {table} where tripId = ? order by tMs",
        (trip_id,),
    )
    path = os.path.join(out_dir, f"trip{trip_id}.jsonl")
    n = 0
    with open(path, "w") as f:
        for tMs, rpm, speed, thr1, thr2, brake, engine, can, dist in cur:
            rec = {
                "t_ms": tMs,
                "rpm": rpm,
                "speed_kph": speed,
                "thr1": thr1,
                "thr2": thr2,
                "brake": None if brake is None else bool(brake),
                "engine_running": None if engine is None else bool(engine),
                "can_active": bool(can),
                "dist_raw": dist,
            }
            f.write(json.dumps(rec) + "\n")
            n += 1
    return path, n


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("db", help="trips.db pulled from the app")
    ap.add_argument("trips", nargs="+", type=int, help="trip ids to export")
    ap.add_argument("-o", "--out", default=".", help="output directory")
    args = ap.parse_args()

    if not os.path.isfile(args.db):
        sys.exit(f"no such database: {args.db}")
    os.makedirs(args.out, exist_ok=True)

    conn = sqlite3.connect(args.db)
    table = table_name(conn)
    for trip in args.trips:
        path, n = export(conn, table, trip, args.out)
        if n == 0:
            print(f"trip {trip}: no frames", file=sys.stderr)
            continue
        print(f"trip {trip}: {n} frames -> {path}")


if __name__ == "__main__":
    main()
