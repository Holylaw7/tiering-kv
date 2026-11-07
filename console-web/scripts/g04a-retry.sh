#!/bin/bash
umount /mnt/tiny 2>/dev/null || true
mount -t tmpfs -o size=64m tmpfs /mnt/tiny
mkdir -p /mnt/tiny/backups
df -h /mnt/tiny | tail -1
BASE=http://127.0.0.1:8084
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
CREATE=$(curl -s -X POST "$BASE/api/v1/backups?instanceId=1" -H "Authorization: Bearer $TOKEN")
ID=$(echo "$CREATE" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
echo "ID=$ID"
for i in $(seq 1 40); do
  STATUS=$(curl -s "$BASE/api/v1/backups?instanceId=1" -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];r=[x for x in d if x["id"]=='$ID'][0];print(r["status"], "|", r.get("keyCount"), r.get("sizeBytes"), "|", r.get("message",""))')
  echo "POLL[$i]=$STATUS"
  if [[ "$STATUS" != RUNNING* ]]; then break; fi
  sleep 1
done
ls -la /mnt/tiny/backups | tail -3
df -h /mnt/tiny | tail -1