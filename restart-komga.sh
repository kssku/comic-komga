#!/usr/bin/env bash
# restart-komga.sh — 停止 + 后台启动 + 健康检查。
#
# Komga 冷启动约 41s，这里给足 90s 轮询窗口，避免 30s 误判为失败。
# 启动由 run-komga.sh 负责注入 COMIC_META_DB_PASSWORD（从 PG 容器现取）。

set -euo pipefail

KOMGA_HOME=/root/GitHub/komga-clouddrive
PORT=25600
HEALTH_URL="http://127.0.0.1:${PORT}/actuator/health"
LOG_FILE=/root/.komga/logs/komga.log
NOHUP_OUT=/root/.komga/logs/nohup-komga.out
WAIT_MAX=90

cd "$KOMGA_HOME"

echo "[restart-komga] === 1/3 停止 ==="
./stop-komga.sh

mkdir -p "$(dirname "$NOHUP_OUT")"

echo "[restart-komga] === 2/3 启动（后台 nohup）==="
# setsid 让进程脱离当前会话，SSH 断开也不受影响；nohup 双保险。
setsid nohup ./run-komga.sh >>"$NOHUP_OUT" 2>&1 < /dev/null &
disown || true

echo "[restart-komga] 已下发启动，等待健康检查（最多 ${WAIT_MAX}s）..."

echo "[restart-komga] === 3/3 健康检查 ==="
elapsed=0
while [ "$elapsed" -lt "$WAIT_MAX" ]; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$HEALTH_URL" 2>/dev/null || echo 000)"
  if [ "$code" = "200" ]; then
    echo "[restart-komga] Komga ready (${elapsed}s, HTTP $code)"
    exit 0
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done

echo "[restart-komga] FATAL: ${WAIT_MAX}s 内未就绪 (最后 HTTP $code)" >&2
echo "[restart-komga] ---- nohup 输出尾部 ----" >&2
tail -n 30 "$NOHUP_OUT" 2>/dev/null >&2 || true
echo "[restart-komga] ---- komga.log 尾部 ----" >&2
tail -n 50 "$LOG_FILE" 2>/dev/null >&2 || true
exit 1