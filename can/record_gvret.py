#!/usr/bin/env python3
"""Record GVRET binary stream from ESP32RET to a raw .bin file.

Usage: record_gvret.py OUT.bin [max_seconds]

Sends 0xE7 (enter binary mode) after a clean hard reset, then dumps the raw
serial stream. Writes OUT.meta with wall-clock start for later alignment.
Reads with the PlatformIO venv python (has pyserial):
  ~/.local/share/pipx/venvs/platformio/bin/python
"""
import sys, time, serial, datetime

out_path = sys.argv[1]
max_s = float(sys.argv[2]) if len(sys.argv) > 2 else 1200
port = '/dev/ttyUSB0'
baud = 1000000

s = serial.Serial(port, baud, timeout=0.2)
s.setDTR(False)
s.setRTS(True)
time.sleep(0.2)
s.setRTS(False)
time.sleep(1.5)          # ROM banner + app init
s.write(b'\xE7')         # GVRET: useBinarySerialComm = true
time.sleep(0.1)

start_epoch = time.time()
with open(out_path + '.meta', 'w') as m:
    m.write(f"start_epoch={start_epoch:.3f}\n")
    m.write(f"start_iso={datetime.datetime.fromtimestamp(start_epoch).isoformat()}\n")
    m.write(f"port={port}\nbaud={baud}\nmax_seconds={max_s}\n")

frames = 0
nbytes = 0
last_stat = time.time()
with open(out_path, 'wb') as f:
    while time.time() - start_epoch < max_s:
        chunk = s.read(4096)
        if chunk:
            f.write(chunk)
            nbytes += len(chunk)
            i = 0
            while True:
                i = chunk.find(b'\xF1\x00', i)
                if i < 0:
                    break
                frames += 1
                i += 2
        now = time.time()
        if now - last_stat >= 5:
            last_stat = now
            print(f"t={now - start_epoch:7.1f}s bytes={nbytes} frames~={frames}", flush=True)
s.close()
print("recording finished", flush=True)
