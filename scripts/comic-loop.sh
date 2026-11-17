#!/bin/sh
# 跑一圈完整闭环：
#   comic_meta pending -> Mangrab 下载导出 -> 落到 115 -> 对账回写 done -> 通知 Komga 扫描
#
# 所有参数走环境变量，见 scripts/comic-loop.env.example。
# 失败不静默：任何一步非零退出即停止；重复执行安全（幂等）。
set -eu

: "${PGHOST:?}" ; : "${PGUSER:?}" ; : "${SITE:?}"
MODE="${MODE:-all}"     # all | dispatch | drain | push | reconcile
LIMIT="${LIMIT:-20}"
ARCHIVE_DIR="${ARCHIVE_DIR:?archiveDir of mangrab}"
MANGRAB_URL="${MANGRAB_URL:-http://127.0.0.1:8080}"
MANGRAB_TOKEN="${MANGRAB_TOKEN:-}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-3600}"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

dispatch() {
  log "dispatch: up to ${LIMIT} pending ${SITE} ids"
  python3 "${SCRIPT_DIR}/download-dispatch.py" \
    --site "${SITE}" --limit "${LIMIT}" \
    --pg-host "${PGHOST}" --pg-port "${PGPORT:-5432}" --pg-db "${PGDB:-comic_meta}" --pg-user "${PGUSER}" \
    --mangrab-url "${MANGRAB_URL}" --mangrab-token "${MANGRAB_TOKEN}"
}

drain() {
  log "drain: waiting for mangrab tasks (timeout ${DRAIN_TIMEOUT}s)"
  waited=0
  while :; do
    stats=$(curl -fsS -m 15 "${MANGRAB_URL}/api/tasks/stats" 2>/dev/null || echo '{}')
    busy=$(printf '%s' "${stats}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("pending",0)+d.get("downloading",0))' 2>/dev/null || echo 0)
    if [ "${busy}" = "0" ]; then
      log "drain: queue empty"
      return 0
    fi
    if [ "${waited}" -ge "${DRAIN_TIMEOUT}" ]; then
      log "drain: timeout with ${busy} task(s) still running"
      return 1
    fi
    sleep 15
    waited=$((waited + 15))
    log "drain: ${busy} task(s) running (${waited}s)"
  done
}

push() {
  if [ -z "${PUSH_SSH:-}" ]; then
    log "push: skipped (ARCHIVE_DIR is already the library path)"
    return 0
  fi
  : "${PUSH_DEST:?}"
  log "push: ${ARCHIVE_DIR} -> ${PUSH_SSH}:${PUSH_DEST}"
  # --no-same-permissions: the FUSE mount refuses chmod on directories, which would
  # make tar exit non-zero even though every file was written.
  tar -C "${ARCHIVE_DIR}" -cf - . | ssh ${PUSH_SSH} "tar --no-same-owner --no-same-permissions --no-overwrite-dir -xmf - -C ${PUSH_DEST}"
}

reconcile() {
  # NFS/FUSE directory caches can hide freshly pushed files; an optional refresh keeps
  # the round self-contained.
  if [ -n "${RECONCILE_REMOUNT:-}" ]; then
    log "reconcile: refreshing mount view"
    sh -c "${RECONCILE_REMOUNT}" || log "reconcile: mount refresh failed, continuing"
  fi
  log "reconcile: repair state for archives present on disk"
  python3 "${SCRIPT_DIR}/reconcile-comic-meta.py" \
    --site "${SITE}" --root "${RECONCILE_ROOT}" \
    --pg-host "${PGHOST}" --pg-port "${PGPORT:-5432}" --pg-db "${PGDB:-comic_meta}" --pg-user "${PGUSER}" \
    --fix \
    ${KOMGA_URL:+--komga-url "${KOMGA_URL}"} \
    ${KOMGA_USER:+--komga-user "${KOMGA_USER}"} \
    ${KOMGA_PASS:+--komga-pass "${KOMGA_PASS}"} \
    ${KOMGA_LIBRARY:+--komga-library "${KOMGA_LIBRARY}"}
}

# 抓一次 Mangrab 的进程内指标，追加成 jsonl 一行一个快照。
# METRICS_FILE 为空则不采集（默认关闭，避免在意外目录建文件）。
metrics() {
  if [ -z "${METRICS_FILE:-}" ]; then
    log "metrics: skipped (METRICS_FILE not set)"
    return 0
  fi
  if curl -fsS -m 15 "${MANGRAB_URL}/api/download/stats" 2>/dev/null |
    python3 -c 'import datetime,json,sys
d = json.load(sys.stdin)
print(json.dumps({"at": datetime.datetime.now().astimezone().isoformat(timespec="seconds"), "stats": d}, ensure_ascii=False))' >> "${METRICS_FILE}"; then
    log "metrics: appended to ${METRICS_FILE}"
  else
    # 端点不可用不该让整圈失败——指标是观测，不是闭环的一环。
    log "metrics: endpoint unavailable, skipped"
  fi
}

case "${MODE}" in
  all) dispatch; drain; metrics; push; reconcile ;;
  metrics) metrics ;;
  dispatch) dispatch ;;
  drain) drain ;;
  push) push ;;
  reconcile) reconcile ;;
  *) echo "unknown MODE: ${MODE}" >&2; exit 2 ;;
esac

log "round finished"
