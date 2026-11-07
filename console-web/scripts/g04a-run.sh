#!/bin/bash
set -e
ROOT=/mnt/e/学习/Mini-redis项目/tiering-kv
cp -f "$ROOT/target/tiering-kv-5.1.0-shaded.jar" /tmp/kernel.jar
cp -f "$ROOT/console-server/target/tiering-kv-console-5.1.0.jar" /tmp/console.jar
mkdir -p /tmp/kdata /mnt/tiny/backups
pkill -f "kernel.jar" 2>/dev/null || true
pkill -f "console.jar" 2>/dev/null || true
sleep 1
cd /tmp/kdata && nohup java -cp /tmp/kernel.jar io.tieringkv.Main > /tmp/kernel.log 2>&1 &
sleep 8
echo "KERNEL=$( (echo > /dev/tcp/127.0.0.1/6379) >/dev/null 2>&1 && echo open || echo closed)"
# 写入约 4MB 数据（4000 个 1KB 值）
python3 - <<'PY' > /tmp/seed.resp
import sys
val = "x" * 1024
out = []
for i in range(4000):
    key = "g04a:%05d" % i
    out.append("*3\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$%d\r\n%s\r\n" % (len(key), key, len(val), val))
sys.stdout.write("".join(out))
PY
cat /tmp/seed.resp > /dev/tcp/127.0.0.1/6379 || true
sleep 2
printf "DBSIZE\r\n" > /dev/tcp/127.0.0.1/6379 || true
echo "SEEDED"