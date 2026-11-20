#!/usr/bin/env bash
# run-komga.sh — 可靠启动 Komga。
#
# 解决的问题：
#   /root/.komga/application.yml 里 comic-meta.db-password 写作
#   ${COMIC_META_DB_PASSWORD}，是运行时从环境变量解析的。
#   以前这个变量靠手工注入，进程一重启就丢失，Komga 启动即失败。
#   本脚本在每次启动前从 PostgreSQL 容器 comic-meta-pg 的
#   POSTGRES_PASSWORD 现场取回密码并注入进程环境，不落盘、不回显。
#
# 用法: ./run-komga.sh        (前台运行，被 restart-komga.sh 以 nohup 调用)

set -euo pipefail

KOMGA_HOME=/root/GitHub/komga-clouddrive
PG_CONTAINER=comic-meta-pg
JAR_REL=komga/build/libs/komga-1.0.0.jar

cd "$KOMGA_HOME"

[ -f "$JAR_REL" ] || { echo "[run-komga] FATAL: jar not found: $KOMGA_HOME/$JAR_REL" >&2; exit 1; }

# --- 从容器取密码（不打印、不写文件）---
_db_pw="$(docker inspect "$PG_CONTAINER" \
            --format '{{range .Config.Env}}{{println .}}{{end}}' \
          | grep '^POSTGRES_PASSWORD=' | cut -d= -f2- || true)"

if [ -z "${_db_pw:-}" ]; then
  echo "[run-komga] FATAL: 无法从容器 $PG_CONTAINER 取到 POSTGRES_PASSWORD" >&2
  echo "[run-komga]        容器是否在运行? 检查: docker ps | grep $PG_CONTAINER" >&2
  exit 1
fi

export COMIC_META_DB_PASSWORD="$_db_pw"
unset _db_pw

echo "[run-komga] COMIC_META_DB_PASSWORD 已注入 (长度 ${#COMIC_META_DB_PASSWORD})"
echo "[run-komga] cwd=$KOMGA_HOME  jar=$JAR_REL"
echo "[run-komga] exec java ..."

# 当前进程实际无额外 JVM 参数（cmdline 仅 java -jar <jar>），保持一致。
exec java -jar "$JAR_REL"