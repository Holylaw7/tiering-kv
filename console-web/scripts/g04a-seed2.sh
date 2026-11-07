#!/bin/bash
python3 - <<'PY' > /tmp/seed2.resp
import sys
val = "y" * 1024
out = []
for i in range(4000, 8000):
    key = "g04a:%05d" % i
    out.append("*3\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$%d\r\n%s\r\n" % (len(key), key, len(val), val))
sys.stdout.write("".join(out))
PY
cat /tmp/seed2.resp > /dev/tcp/127.0.0.1/6379 || true
sleep 2
echo SEED2_DONE