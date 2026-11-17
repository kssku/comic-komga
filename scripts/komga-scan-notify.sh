#!/bin/sh
# 打包/归档完成后让 Komga 扫描指定库。
#   KOMGA_URL=http://127.0.0.1:25600 KOMGA_USER=... KOMGA_PASS=... \
#   KOMGA_LIBRARY_ID=... ./komga-scan-notify.sh
set -eu
: "${KOMGA_URL:?set KOMGA_URL}"
: "${KOMGA_LIBRARY_ID:?set KOMGA_LIBRARY_ID}"
: "${KOMGA_USER:?set KOMGA_USER}"
: "${KOMGA_PASS:?set KOMGA_PASS}"

curl -fsS -u "${KOMGA_USER}:${KOMGA_PASS}" -X POST \
  "${KOMGA_URL}/api/v1/libraries/${KOMGA_LIBRARY_ID}/scan" -o /dev/null
echo "scan requested for library ${KOMGA_LIBRARY_ID}"
