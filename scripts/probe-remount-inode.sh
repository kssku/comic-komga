#!/usr/bin/env bash
# =============================================================================
# probe-remount-inode.sh — U1 探针：重新挂载（umount/mount）后 inode 是否稳定
# =============================================================================
#
# 【这是什么】
#   验证 CloudFS(FUSE) 挂载在 umount + mount 之后，文件的 st_ino 是否保持不变。
#   对应设计文档 .dsh/design-comic-manager.md 第 12.3 节 U1 条目。
#
# 【为什么重要】
#   第 5.4 节把 inode 作为重命名认领的主依据，前提是「inode 在单次挂载期内可信」。
#   若重挂载后 inode 全变，全库记录会被判为消失并软删（第 5.7 节 R1）。
#   这条结果决定用户敢不敢正常重启容器。
#
# 【风险】
#   ⚠️ 真实执行会中断 CloudFS 挂载。正在读写的进程（扫描、阅读、缩略图生成）
#      会收到 IO 错误。务必在维护窗口执行，先停掉 komga 服务。
#   ⚠️ 若 mount 恢复失败，挂载点会处于未挂载状态。脚本会打印原始 mount 命令，
#      请立刻手动恢复，不要继续跑任何写入。
#
# 【用法】
#   ./probe-remount-inode.sh            # 默认 dry-run，只打印将要做什么
#   ./probe-remount-inode.sh --yes      # 真实执行，仍需二次交互确认
#
# 【输出】
#   一张「保持 / 变化」表 + 总结论（inode 稳定 / inode 变化）。
#
# =============================================================================

set -uo pipefail

# ---------- 参数解析 ----------
DRY_RUN=1
for arg in "$@"; do
  case "$arg" in
    --yes) DRY_RUN=0 ;;
    -h|--help)
      sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数: $arg（只支持 --yes / --help）" >&2
      exit 2
      ;;
  esac
done

# ---------- 工具函数 ----------
say()  { printf '%s\n' "$*"; }
warn() { printf '\033[33m[WARN]\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }
ok()   { printf '\033[32m[OK]\033[0m %s\n' "$*"; }

# ---------- 1. 识别挂载点 ----------
say '=== 步骤 1：识别 CloudFS 挂载点 ==='

MOUNT_LINE="$(mount | grep -i 'type fuse' | grep -i 'clouddrive2' | head -1)"
if [ -z "$MOUNT_LINE" ]; then
  MOUNT_LINE="$(mount | grep -iE 'CloudFS|clouddrive2' | head -1)"
fi
if [ -z "$MOUNT_LINE" ]; then
  err '未识别到 CloudFS / clouddrive2 挂载点。'
  err '请手动确认 mount 输出，脚本不猜测挂载点，退出。'
  exit 1
fi

# 解析 "SOURCE on TARGET type FSTYPE (OPTS)"
MOUNT_SRC="$(printf '%s' "$MOUNT_LINE" | awk '{print $1}')"
MOUNT_TGT="$(printf '%s' "$MOUNT_LINE" | awk '{for(i=1;i<=NF;i++) if($i=="on") print $(i+1)}')"
MOUNT_FS="$(printf '%s' "$MOUNT_LINE"  | awk '{for(i=1;i<=NF;i++) if($i=="type") print $(i+1)}')"

if [ -z "$MOUNT_TGT" ] || [ ! -d "$MOUNT_TGT" ]; then
  err "解析出的挂载点无效: '${MOUNT_TGT}'"
  err "原始行: $MOUNT_LINE"
  exit 1
fi

say "  原始 mount 行 : $MOUNT_LINE"
say "  源 (SOURCE)   : $MOUNT_SRC"
say "  挂载点 (TARGET): $MOUNT_TGT"
say "  文件系统类型  : $MOUNT_FS"
say ''

# ---------- 2. 采样文件 ----------
say '=== 步骤 2：采样文件 ==='

# 在挂载点下找 5 个不同目录，每个取最多 3 个普通文件。
#
# 采样策略（按优先级）：
#   1. 环境变量 PROBE_ROOT 指定的目录（明确指定时最优先）；
#   2. 挂载点下的 content/（漫画库根，29 万本都在这里）；
#   3. 退回挂载点根。
# 目的：让采样落在真实数据上，而不是 Photos 之类的边角目录。
# 深度 4 足够覆盖 jm/oneshots/1-50000/ 这类「库/系列/桶/文件」结构。
SAMPLE_LIST="$(mktemp /tmp/probe-inode-samples.XXXXXX)"
DIRS_LIST=/tmp/probe-inode-dirs.txt
PICKED_LIST=/tmp/probe-inode-picked.txt
trap 'rm -f "$SAMPLE_LIST" "$DIRS_LIST" "$PICKED_LIST" /tmp/probe-inode-before.* /tmp/probe-inode-after.* 2>/dev/null' EXIT

if [ -n "${PROBE_ROOT:-}" ] && [ -d "$PROBE_ROOT" ]; then
  SCAN_ROOT="$PROBE_ROOT"
elif [ -d "$MOUNT_TGT/content" ]; then
  SCAN_ROOT="$MOUNT_TGT/content"
else
  SCAN_ROOT="$MOUNT_TGT"
fi
say "  采样根: $SCAN_ROOT"

# 深度 4 内找目录，取前 20 个作为候选（只为凑满 5 个有效目录）
find "$SCAN_ROOT" -maxdepth 4 -type d 2>/dev/null | head -20 > "$DIRS_LIST"

if [ ! -s "$DIRS_LIST" ]; then
  err "在 $SCAN_ROOT 下没找到任何目录，无法采样。"
  exit 1
fi

# 只挑「目录下确实有普通文件」的，凑满 5 个为止
: > "$PICKED_LIST"
PICKED=0
while IFS= read -r d; do
  [ "$PICKED" -ge 5 ] && break
  if [ -n "$(find "$d" -maxdepth 1 -type f 2>/dev/null | head -1)" ]; then
    printf '%s\n' "$d" >> "$PICKED_LIST"
    PICKED=$((PICKED + 1))
  fi
done < "$DIRS_LIST"

if [ "$PICKED" -eq 0 ]; then
  err "在 $SCAN_ROOT 深度 4 内没找到含文件的目录。"
  err '可用 PROBE_ROOT=<目录> 显式指定采样根后重试。'
  exit 1
fi

while IFS= read -r d; do
  find "$d" -maxdepth 1 -type f 2>/dev/null | head -3
done < "$PICKED_LIST" > "$SAMPLE_LIST"

SAMPLE_COUNT="$(wc -l < "$SAMPLE_LIST" | tr -d ' ')"
if [ "$SAMPLE_COUNT" -eq 0 ]; then
  err '没采到任何文件，无法比对。退出。'
  exit 1
fi

say "  采样目录数: $(wc -l < "$PICKED_LIST" | tr -d ' ')"
say "  采样文件数: $SAMPLE_COUNT"
say '  前几个样本:'
head -3 "$SAMPLE_LIST" | sed 's/^/    /'
say ''

# ---------- 3. 记录 before ----------
BEFORE=/tmp/probe-inode-before.tsv
say '=== 步骤 3：记录挂载前 inode ==='
# 格式: path \t ino \t dev \t size
while IFS= read -r f; do
  stat -c '%n\t%i\t%d\t%s' "$f" 2>/dev/null
done < "$SAMPLE_LIST" > "$BEFORE"

if [ ! -s "$BEFORE" ]; then
  err '记录 inode 失败，全部 stat 返回空。退出。'
  exit 1
fi
say "  已记录 $(wc -l < "$BEFORE" | tr -d ' ') 条"
say ''

# ---------- 4. dry-run 到此为止 ----------
if [ "$DRY_RUN" -eq 1 ]; then
  say '=== DRY-RUN：以下命令未执行 ==='
  say ''
  say "  将执行: umount '$MOUNT_TGT'"
  say "  将执行: mount '$MOUNT_SRC' '$MOUNT_TGT' -t '$MOUNT_FS' $(printf '%s' "$MOUNT_LINE" | sed -n 's/.*(\(.*\))/-o \1/p')"
  say ''
  say '  ⚠️ 真实执行会中断 CloudFS 挂载。确认维护窗口后加 --yes 运行。'
  say ''
  say '  当前采样的 before 快照已丢弃（dry-run 不留状态）。'
  exit 0
fi

# ---------- 5. 二次确认 ----------
say '=== 步骤 4：二次确认 ==='
say ''
warn '你即将中断 CloudFS 挂载。'
warn '正在使用该挂载的进程（komga 扫描、阅读、缩略图生成）会遇到 IO 错误。'
warn "挂载点: $MOUNT_TGT"
say ''
printf '确认继续？输入大写 YES 继续，其他任意键中止: '
read -r CONFIRM
if [ "$CONFIRM" != 'YES' ]; then
  say '已中止，未做任何挂载操作。'
  exit 0
fi
say ''

# ---------- 6. umount ----------
say '=== 步骤 5：umount ==='
# 先试普通 umount；失败则报错退出（不自动 -l 强制卸载，避免掩盖占用）
if umount "$MOUNT_TGT"; then
  ok "umount 成功: $MOUNT_TGT"
else
  err "umount 失败: $MOUNT_TGT"
  err '可能仍有进程占用。请先 lsof +f -- '"$MOUNT_TGT"' 排查。'
  err '脚本终止，挂载状态未改变。'
  exit 1
fi
sleep 1
say ''

# ---------- 7. mount 恢复（最危险步骤） ----------
say '=== 步骤 6：mount 恢复 ==='
# 从原始 mount 行提取 -o 选项
MOUNT_OPTS="$(printf '%s' "$MOUNT_LINE" | sed -n 's/.*(\(.*\))/\1/p')"
MOUNT_OPTS="${MOUNT_OPTS// /,}"   # mount 输出里选项可能带空格

RECOVER_CMD="mount -t '$MOUNT_FS' -o '$MOUNT_OPTS' '$MOUNT_SRC' '$MOUNT_TGT'"
say "  恢复命令: $RECOVER_CMD"

if mount -t "$MOUNT_FS" -o "$MOUNT_OPTS" "$MOUNT_SRC" "$MOUNT_TGT"; then
  ok 'mount 恢复成功'
else
  err '=========================================================='
  err '  mount 恢复失败！挂载点当前处于未挂载状态。'
  err '  请立刻手动执行以下命令恢复：'
  err ''
  err "    $RECOVER_CMD"
  err ''
  err "  若仍失败，检查 CloudFS 进程是否存活: pgrep -a clouddrive"
  err '=========================================================='
  exit 1
fi
sleep 2
say ''

# ---------- 8. 记录 after 并比对 ----------
say '=== 步骤 7：比对 inode ==='
AFTER=/tmp/probe-inode-after.tsv
while IFS= read -r f; do
  stat -c '%n\t%i\t%d\t%s' "$f" 2>/dev/null
done < "$SAMPLE_LIST" > "$AFTER"

if [ ! -s "$AFTER" ]; then
  err '挂载后 stat 全失败，可能挂载未真正就绪。请手动检查。'
  exit 1
fi

SAME=0
DIFF=0
MISSING=0

printf '\n%-52s %-12s %-12s %s\n' 'FILE' 'INO(before)' 'INO(after)' 'RESULT'
printf '%s\n' '--------------------------------------------------------------------------------'

# 用 awk 做 join，保持顺序
awk -F'\t' '
  NR==FNR { b[$1]=$2; bd[$1]=$3; bs[$1]=$4; order[++n]=$1; next }
  {
    a=$2; ad=$3; as=$4; p=$1;
    seen[p]=1;
    if (!(p in b)) { next }
    if (b[p]==a && bd[p]==ad && bs[p]==as) printf "%-52s %-12s %-12s KEEP\n", p, b[p], a;
    else printf "%-52s %-12s %-12s CHANGED\n", p, b[p], a;
  }
  END {
    for (i=1;i<=n;i++) if (!(order[i] in seen)) printf "%-52s %-12s %-12s MISSING\n", order[i], b[order[i]], "-";
  }
' "$BEFORE" "$AFTER"

# 统计
while IFS=$'\t' read -r p ino dev size; do
  a="$(grep -F -- "$p"$'\t' "$AFTER" 2>/dev/null | head -1)"
  if [ -z "$a" ]; then
    MISSING=$((MISSING+1))
    continue
  fi
  a_ino="$(printf '%s' "$a" | cut -f2)"
  if [ "$ino" = "$a_ino" ]; then SAME=$((SAME+1)); else DIFF=$((DIFF+1)); fi
done < "$BEFORE"

say ''
say '=== 总结 ==='
say "  保持不变 (KEEP)   : $SAME"
say "  发生变化 (CHANGED): $DIFF"
say "  挂载后缺失 (MISSING): $MISSING"
say ''

if [ "$DIFF" -eq 0 ] && [ "$MISSING" -eq 0 ]; then
  ok '结论：重新挂载后 inode 稳定（在本样本上）。'
  say '  → U1 可通过，第 5.7 节 R1 风险降低。建议扩大样本再确认一次。'
  EXIT_CODE=0
elif [ "$DIFF" -eq 0 ] && [ "$MISSING" -gt 0 ]; then
  warn '结论：inode 未变，但有文件在重挂载后不可达。'
  say '  → 可能是采样目录被并发修改，或挂载未完全就绪。建议重跑。'
  EXIT_CODE=3
else
  err '结论：重新挂载后 inode 发生变化！'
  say '  → U1 被证伪。第 5.4 节 inode 主认领在重挂载后失效，'
  say '    第 5.7 节 R1 的症状链会真实发生。'
  say '  → 必须依赖「按 rel_path 回填」恢复，并启用扫描消失比例熔断。'
  EXIT_CODE=4
fi

say ''
say "before 快照: $BEFORE"
say "after  快照: $AFTER"
say "（保留在 /tmp 供进一步分析，脚本未删除）"
exit "$EXIT_CODE"