#!/bin/bash
mount -t tmpfs -o size=2m tmpfs /mnt/tiny 2>/dev/null || true
mkdir -p /mnt/tiny/backups
df -h /mnt/tiny | tail -1
BASE=http://127.0.0.1:8084
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
echo "TOKEN_LEN=${#TOKEN}"
CREATE=$(curl -s -X POST "$BASE/api/v1/backups?instanceId=1" -H "Authorization: Bearer $TOKEN")
echo "CREATE=$CREATE"
ID=$(echo "$CREATE" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("data",{}).get("id",""))' 2>/dev/null)
if [ -n "$ID" ]; then
  for i in $(seq 1 30); do
    STATUS=$(curl -s "$BASE/api/v1/backups?instanceId=1" -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];r=[x for x in d if x["id"]=='$ID'][0];print(r["status"], "|", r.get("message",""))' 2>/dev/null)
    echo "POLL[$i]=$STATUS"
    if [[ "$STATUS" != RUNNING* ]]; then break; fi
    sleep 1
  done
fi
echo "FILES:"; ls -la /mnt/tiny/backups | tail -4
echo "DF:"; df -h /mnt/tiny | tail -1