#!/usr/bin/env bash
# stop-komga.sh — 干净停止 Komga（裸 java 进程，非 systemd/容器）。
#
# 逻辑：按 jar 名精确匹配进程 -> SIGTERM -> 等真正退出(最多60s) -> 超时 SIGKILL
#       -> 确认端口释放。不会误杀其它 java 进程。

set -euo pipefail

JAR_PATTERN='komga/build/libs/komga-1.0.0.jar'
PORT=25600
GRACE=60

# --- 找目标 PID（只匹配命令行含该 jar 路径的 java 进程）---
mapfile -t PIDS < <(pgrep -f -- "java .*$JAR_PATTERN" || true)

if [ "${#PIDS[@]}" -eq 0 ]; then
  echo "[stop-komga] 未发现运行中的 Komga 进程。"
  # 仍然检查端口，避免端口被别的进程占着却以为没事
  if ss -ltn "( sport = :$PORT )" 2>/dev/null | grep -q ":$PORT"; then
    echo "[stop-komga] WARN: 端口 $PORT 仍被占用，但不是 Komga 进程:" >&2
    ss -ltnp "( sport = :$PORT )" 2>/dev/null >&2 || true
    exit 1
  fi
  echo "[stop-komga] 端口 $PORT 空闲。"
  exit 0
fi

if [ "${#PIDS[@]}" -gt 1 ]; then
  echo "[stop-komga] FATAL: 匹配到多个 Komga 进程: ${PIDS[*]} — 拒绝操作，请人工确认。" >&2
  exit 1
fi

PID="${PIDS[0]}"
echo "[stop-komga] 目标 PID=$PID，发送 SIGTERM..."

if ! kill -TERM "$PID" 2>/dev/null; then
  echo "[stop-komga] WARN: kill 返回失败（进程可能已退出）。" >&2
fi

# --- 等进程真正消失 ---
waited=0
while kill -0 "$PID" 2>/dev/null; do
  if [ "$waited" -ge "$GRACE" ]; then
    echo "[stop-komga] 等待 ${GRACE}s 后仍未退出，发送 SIGKILL..." >&2
    kill -KILL "$PID" 2>/dev/null || true
    sleep 3
    if kill -0 "$PID" 2>/dev/null; then
      echo "[stop-komga] FATAL: PID $PID SIGKILL 后仍存活（僵尸/D 状态？）。" >&2
      exit 1
    fi
    break
  fi
  sleep 1
  waited=$((waited + 1))
done

echo "[stop-komga] 进程已退出（耗时约 ${waited}s）。"

# --- 确认端口释放（TIME_WAIT 不算 LISTEN，只看 LISTEN）---
for i in $(seq 1 10); do
  if ! ss -ltn "( sport = :$PORT )" 2>/dev/null | grep -q ":$PORT"; then
    echo "[stop-komga] 端口 $PORT 已释放。"
    exit 0
  fi
  sleep 1
done

echo "[stop-komga] FATAL: 端口 $PORT 仍处于 LISTEN 状态:" >&2
ss -ltnp "( sport = :$PORT )" 2>/dev/null >&2 || true
exit 1