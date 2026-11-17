#!/bin/sh
# 闭环系统性验收：跑一圈，再逐项核对「产物真的到位」。
#
#   sh verify-loop.sh              # 跑一圈并检查
#   CHECK_ONLY=1 sh verify-loop.sh # 只检查现状，不触发下载
#
# 需要先 source 闭环环境（comic-loop.env）。设计取向：
#   - 只读断言优先，唯一的写操作是调用 comic-loop.sh（与人工跑一圈等价）
#   - 每项都是「可观察的事实」，不依赖日志文本
#   - 失败不回滚：验收脚本不替人做决定，只如实报告
set -u

[ -n "${ARCHIVE_DIR:-}" ] || { echo "ARCHIVE_DIR not set" >&2; exit 2; }
[ -n "${RECONCILE_ROOT:-}" ] || { echo "RECONCILE_ROOT not set" >&2; exit 2; }

MANGRAB_URL="${MANGRAB_URL:-http://127.0.0.1:8080}"
LOCAL_ROOT="${ARCHIVE_DIR%/}"
REMOTE_ROOT="${RECONCILE_ROOT%/}"
REMOTE_DEST=$(dirname "$REMOTE_ROOT")
METRICS_FILE="${METRICS_FILE:-}"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

FAILS=0
pass() { printf '[PASS] %s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*"; FAILS=$((FAILS+1)); }
info() { printf '[INFO] %s\n' "$*"; }

psql_q() {
  PGPASSWORD="${PGPASSWORD:-}" psql -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" \
    -d "${PGDB:-comic_meta}" -At -c "$1" 2>/dev/null
}

pg_count() { psql_q "select count(*) from ${SITE}_archive where state='$1'" || echo -1; }

metrics_lines() {
  if [ -n "$METRICS_FILE" ] && [ -f "$METRICS_FILE" ]; then
    wc -l < "$METRICS_FILE" | tr -d ' '
  else
    echo 0
  fi
}

snapshot_cbz() { find "$LOCAL_ROOT" -name '*.cbz' -printf '%P\n' 2>/dev/null | sort; }

echo "== 前置 =="
health=$(curl -fsS -m 5 "$MANGRAB_URL/api/health" 2>/dev/null || echo '')
case "$health" in
  *'"status":"ok"'*) pass "mangrab health ok" ;;
  *) fail "mangrab health: $health" ;;
esac

if [ -d "$REMOTE_ROOT" ]; then pass "远端库根可见（$REMOTE_ROOT）"; else fail "远端库根不可见：$REMOTE_ROOT"; fi

before_done=$(pg_count done)
if [ "$before_done" = "-1" ]; then fail "PG 不可达"; else pass "PG 可查询"; fi

before_failed=$(pg_count failed)
before_metrics=$(metrics_lines)
snapshot_cbz > "$WORK/before.txt"

if [ "${CHECK_ONLY:-0}" = "1" ]; then
  info "CHECK_ONLY=1，跳过闭环执行"
else
  echo "== 执行一圈闭环 =="
  if ( cd "$SCRIPT_DIR" && MODE=all sh ./comic-loop.sh ); then
    pass "comic-loop 退出码 0"
  else
    fail "comic-loop 非零退出"
  fi
fi

snapshot_cbz > "$WORK/after.txt"
after_done=$(pg_count done)
after_failed=$(pg_count failed)
after_metrics=$(metrics_lines)
comm -13 "$WORK/before.txt" "$WORK/after.txt" > "$WORK/new.txt" 2>/dev/null || true
new_count=$(grep -c . "$WORK/new.txt" 2>/dev/null || true)
new_count=${new_count:-0}

echo "== 产物核对 =="
info "本地新增 cbz：$new_count"
info "done：$before_done -> $after_done"
info "failed：$before_failed -> $after_failed"

[ "$after_done" -ge "$before_done" ] && pass "done 未回退" || fail "done 回退（$before_done -> $after_done）"

missing=0
db_missing=0
if [ "$new_count" -gt 0 ]; then
  while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    remote="$REMOTE_DEST/$rel"
    if [ -f "$remote" ]; then
      info "远端到位：$rel"
    else
      missing=$((missing+1))
      fail "远端缺失：$rel"
    fi
    id=$(basename "$rel" .cbz)
    state=$(psql_q "select state from ${SITE}_archive where comic_id='$id'")
    if [ "$state" = "done" ]; then
      info "DB 已回写 done：$id"
    else
      db_missing=$((db_missing+1))
      fail "DB 未回写 done：$id（state=${state:-<空>}）"
    fi
  done < "$WORK/new.txt"
  [ "$missing" = "0" ] && pass "新增文件全部在远端可见"
  [ "$db_missing" = "0" ] && pass "新增文件全部在 DB 标记 done"
else
  info "本轮没有新增文件（可能全是空转），跳过逐文件核对"
fi

if [ "${CHECK_ONLY:-0}" = "1" ]; then
  info "CHECK_ONLY=1，跳过指标断言"
elif [ -n "$METRICS_FILE" ]; then
  if [ "$after_metrics" -gt "$before_metrics" ]; then
    pass "指标追加 1 行（$before_metrics -> $after_metrics）"
  else
    fail "指标未追加（$before_metrics -> $after_metrics）"
  fi
else
  info "METRICS_FILE 未设置，跳过指标断言"
fi

echo "== 结论 =="
if [ "$FAILS" -eq 0 ]; then
  echo "全部通过"
  exit 0
fi
echo "$FAILS 项失败"
exit 1
