#!/bin/bash
H=$(ip route show default | awk '{print $3}')
echo "HOSTIP=$H"
if (echo > /dev/tcp/$H/3308) >/dev/null 2>&1; then echo "MYSQL=open"; else echo "MYSQL=closed"; fi
if (echo > /dev/tcp/127.0.0.1/6379) >/dev/null 2>&1; then echo "KERNEL=open"; else echo "KERNEL=closed"; fi