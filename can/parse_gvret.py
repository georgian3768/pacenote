#!/usr/bin/env python3
"""Parse a GVRET binary raw capture (record_gvret.py output) to CSV.

Usage: parse_gvret.py IN.bin OUT.csv

Frame layout per commbuffer.cpp sendFrameToBuffer (binary):
  0xF1 | 0x00 | t0 t1 t2 t3 (LE, device micros) | id0..id3 (LE, bit31=ext)
  | (dlc | bus<<4) | data[dlc] | 0x00
"""
import sys

data = open(sys.argv[1], 'rb').read()
out = open(sys.argv[2], 'w')
out.write("t_us,id,bus,dlc," + ",".join(f"d{i}" for i in range(8)) + "\n")

n = len(data)
i = 0
frames = 0
while i + 12 <= n:
    if data[i] == 0xF1 and data[i + 1] == 0x00:
        t = int.from_bytes(data[i + 2:i + 6], 'little')
        fid = int.from_bytes(data[i + 6:i + 10], 'little')
        ext = fid & 0x80000000
        fid &= 0x7FFFFFFF
        lb = data[i + 10]
        dlc = lb & 0xF
        bus = lb >> 4
        if dlc <= 8 and bus <= 3 and i + 12 + dlc <= n and data[i + 11 + dlc] == 0:
            d = list(data[i + 11:i + 11 + dlc]) + [''] * (8 - dlc)
            row = ",".join(f"{b:02x}" if isinstance(b, int) else b for b in d)
            out.write(f"{t},{fid:x},{bus},{dlc},{row}\n")
            frames += 1
            i += 12 + dlc
            continue
    i += 1
out.close()
print(f"frames={frames} bytes={n}")
