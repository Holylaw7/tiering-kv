#!/bin/bash
H=$(ip route show default | awk '{print $3}')
pkill -f "console.jar" 2>/dev/null || true
sleep 1
cd /tmp && nohup java -jar /tmp/console.jar \
  --server.port=8084 \
  --console.backup.dir=/mnt/tiny/backups \
  --console.backup.capacity.min-free-bytes=0 \
  --console.backup.capacity.estimate-floor-bytes=102400 \
  --console.backup.capacity.estimate-factor=0.1 \
  --console.backup.retention.enabled=false \
  --spring.datasource.url="jdbc:mysql://$H:3308/tiering_console?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  --spring.datasource.username=console_app \
  --spring.datasource.password="S3cureApp!2026" \
  > /tmp/console.log 2>&1 &
sleep 30
echo "CONSOLE=$( (echo > /dev/tcp/127.0.0.1/8084) >/dev/null 2>&1 && echo open || echo closed)"
tail -3 /tmp/console.log