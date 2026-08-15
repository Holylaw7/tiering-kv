#!/usr/bin/env bash
set -uo pipefail

# container-e2e 最小 RESP 冒烟（ADR-0343）：读取并断言 SET/GET 响应，
# 禁止“只写不读”的伪冒烟；有界重试吸收 Raft 选举就绪竞态。
# 独立脚本文件而非 workflow 内嵌 bash -c：避免 $'\r\n' 单引号
# 提前终止外层单引号参数（真实 Runner 门禁踩坑）。

GATEWAY_HOST="${TIERINGKV_GATEWAY_HOST:-127.0.0.1}"
GATEWAY_PORT="${TIERINGKV_GATEWAY_PORT:-6379}"
ATTEMPTS="${TIERINGKV_SMOKE_ATTEMPTS:-10}"
READ_TIMEOUT="${TIERINGKV_SMOKE_READ_TIMEOUT:-3}"

for ((i = 1; i <= ATTEMPTS; i++)); do
  if exec 3<>"/dev/tcp/${GATEWAY_HOST}/${GATEWAY_PORT}" 2>/dev/null; then
    printf "*3\r\n\$3\r\nSET\r\n\$2\r\nk1\r\n\$2\r\nv1\r\n" >&3
    if ! IFS=$'\r\n' read -r -t "$READ_TIMEOUT" line <&3; then
      echo "attempt $i: SET read failed" >&2
      exec 3<&- 3>&-
      sleep 1
      continue
    fi
    echo "attempt $i: SET response=[$line]" >&2
    if [ "$line" = "+OK" ]; then
      printf "*2\r\n\$3\r\nGET\r\n\$2\r\nk1\r\n" >&3
      if ! IFS=$'\r\n' read -r -t "$READ_TIMEOUT" line <&3; then
        echo "attempt $i: GET header read failed" >&2
        exec 3<&- 3>&-
        sleep 1
        continue
      fi
      if ! IFS=$'\r\n' read -r -t "$READ_TIMEOUT" body <&3; then
        echo "attempt $i: GET body read failed" >&2
        exec 3<&- 3>&-
        sleep 1
        continue
      fi
      echo "attempt $i: GET header=[$line] body=[$body]" >&2
      exec 3<&- 3>&-
      if [ "$line" = '$2' ] && [ "$body" = "v1" ]; then
        echo "smoke SET/GET ok (attempt $i)"
        exit 0
      fi
    fi
    exec 3<&- 3>&- 2>/dev/null
  else
    echo "attempt $i: connect failed" >&2
  fi
  sleep 1
done

echo "smoke SET/GET failed after ${ATTEMPTS} attempts" >&2
exit 1
