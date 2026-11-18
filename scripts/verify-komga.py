#!/usr/bin/env python3
"""阅读层（L2）验收：Komga 能看到内容，且标题来自 comic-meta。

L1（数据闭环）由 verify-loop.sh 负责，两者互不依赖：
Komga 扫描慢是阅读层的事，不该拖住下载/对账。

用法：
    python3 verify-komga.py
环境变量：KOMGA_URL / KOMGA_USER / KOMGA_PASS / SAMPLES
"""

import base64
import json
import os
import sys
import urllib.request

URL = os.environ.get("KOMGA_URL", "http://127.0.0.1:25600").rstrip("/")
USER = os.environ.get("KOMGA_USER", "test@local.test")
PASS = os.environ.get("KOMGA_PASS", "testtest123")
SAMPLES = int(os.environ.get("SAMPLES", "20"))

FAILS = 0


def out(tag, msg):
    print(f"[{tag}] {msg}")


def fail(msg):
    global FAILS
    FAILS += 1
    out("FAIL", msg)


def get(path):
    req = urllib.request.Request(URL + path)
    token = base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    req.add_header("Authorization", "Basic " + token)
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.load(resp)


def main():
    try:
        libs = get("/api/v1/libraries")
    except Exception as err:  # noqa: BLE001 - 验收脚本，任何异常都算失败
        fail(f"komga 不可达: {err}")
        return
    out("PASS", f"komga 可达（{len(libs)} 个库）")

    for lib in libs:
        out("INFO", f"库 {lib['name']} root={lib['root']}")

    page = get("/api/v1/books?size=1")
    total = page.get("totalElements", 0)
    if total <= 0:
        fail("库里没有书（扫描没跑完或库根不对）")
        return
    out("PASS", f"库里有书：{total} 本")

    sample = get(f"/api/v1/books?size={SAMPLES}")
    books = sample.get("content", [])
    if not books:
        fail("抽样为空")
        return

    real = 0
    for b in books:
        title = (b.get("metadata") or {}).get("title") or ""
        # 标题等于文件名（数字）说明 comic-meta 没接上
        if title and title != b.get("name"):
            real += 1

    ratio = real / len(books)
    out("INFO", f"抽查 {len(books)} 本，真标题 {real} 本（{ratio:.0%}）")
    if ratio >= 0.9:
        out("PASS", "标题来自 comic-meta")
    else:
        fail(f"真标题比例过低（{ratio:.0%}），comic-meta 可能没接上")

    # 按需分析：读一本的页列表应当能拿到页数
    probe = books[0]
    try:
        pages = get(f"/api/v1/books/{probe['id']}/pages")
        if isinstance(pages, list) and pages:
            out("PASS", f"按需分析可用（{probe['name']} 共 {len(pages)} 页）")
        else:
            fail(f"按需分析返回空页列表（{probe['name']}）")
    except Exception as err:  # noqa: BLE001
        fail(f"按需分析失败（{probe['name']}）: {err}")


main()
print("== 结论 ==")
if FAILS == 0:
    print("全部通过")
    sys.exit(0)
print(f"{FAILS} 项失败")
sys.exit(1)
