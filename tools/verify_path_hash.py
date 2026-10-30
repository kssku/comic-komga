#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_path_hash.py —— Komga SHA1 相对路径哈希对拍工具

复刻 Komga `Hasher.computePathHash(path, libraryRoot)` 的算法：

    root = libraryRoot.toURI().toPath().normalize()
    base = root.parent ?: root
    relative = base.relativize(path.normalize()).toString()
    return SHA1(relative 的 UTF-8 字节)

关键点：**基准是库根的父目录**，不是库根本身。所以库根必须填到
`.../content/wnacg`，算出来的相对路径才是 `wnacg/oneshots/1-50000/xxx.cbz`。

三个模式，默认都不跑，必须显式给参数选：

  A. --library-root DIR   磁盘 vs 预期：遍历书文件，输出 `相对路径<TAB>sha1`
  B. --lrr-file FILE      与 LRR 的 LRR_FILEMAP 对拍（JSON / JSONL）
     --lrr-aof FILE       与 LRR 的 Redis AOF 对拍（解析 HSET LRR_FILEMAP）
  C. --komga-db FILE      与 Komga 的 database.sqlite 对拍（只读打开）

示例：

  # A：看看库根填这个会得到什么哈希（前 10 条）
  python3 tools/verify_path_hash.py --library-root /opt/clouddrive2/115open/content/wnacg --limit 10

  # A：只看统计
  python3 tools/verify_path_hash.py --library-root /opt/clouddrive2/115open/content/wnacg --summary-only

  # B：和 LRR 导出对拍
  python3 tools/verify_path_hash.py --library-root /opt/clouddrive2/115open/content/wnacg \\
      --lrr-file /tmp/lrr_filemap.json

  # B：直接啃 Redis AOF
  python3 tools/verify_path_hash.py --library-root /opt/clouddrive2/115open/content/wnacg \\
      --lrr-aof /vol1/1000/data/lanraragi/database/appendonlydir/appendonly.aof.1.incr.aof

  # C：和 Komga 库对拍
  python3 tools/verify_path_hash.py --komga-db .test-komga/database.sqlite

只用标准库，无第三方依赖。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import sqlite3
import sys

# 视为「书文件」的扩展名（小写比较）
BOOK_EXTS = {".cbz", ".zip", ".epub", ".rar", ".cbr", ".7z", ".pdf"}
# LRR 侧通常把这些当作归档
LRR_EXTS = BOOK_EXTS

DEFAULT_LIBRARY_ROOT = "/opt/clouddrive2/115open/content/wnacg"


# --------------------------------------------------------------------------
# 核心算法：复刻 Hasher.computePathHash
# --------------------------------------------------------------------------
def compute_path_hash(path: pathlib.PurePath, library_root: pathlib.PurePath) -> str:
    """按 Komga 算法算 SHA1 相对路径哈希。

    base = library_root.parent（库根的父目录），relative = base.relativize(path)。
    与 Kotlin 的 Path.relativize 语义一致：不做存在性检查，纯字符串运算。
    """
    root = pathlib.PurePath(os.path.normpath(str(library_root)))
    base = root.parent if str(root.parent) not in ("", ".") else root
    p = pathlib.PurePath(os.path.normpath(str(path)))
    relative = str(p.relative_to(base))
    return hashlib.sha1(relative.encode("utf-8")).hexdigest()


def relative_from_base(path: pathlib.PurePath, library_root: pathlib.PurePath) -> str:
    """返回相对库根父目录的路径字符串（诊断用）。"""
    root = pathlib.PurePath(os.path.normpath(str(library_root)))
    base = root.parent if str(root.parent) not in ("", ".") else root
    p = pathlib.PurePath(os.path.normpath(str(path)))
    return str(p.relative_to(base))


def file_uri_to_path(uri: str) -> pathlib.PurePath:
    """把 `file:/a/b/` 或 `file:///a/b/` 转成 PurePath。"""
    s = uri
    if s.startswith("file://"):
        s = s[len("file://"):]
    elif s.startswith("file:"):
        s = s[len("file:"):]
    return pathlib.PurePath(s)


# --------------------------------------------------------------------------
# 工具：输出截断
# --------------------------------------------------------------------------
class Output:
    def __init__(self, verbose: bool, limit: int):
        self.verbose = verbose
        self.limit = limit
        self._shown = 0

    def detail(self, line: str) -> None:
        if self.verbose:
            print(line)
            return
        if self._shown < self.limit:
            print(line)
            self._shown += 1
        elif self._shown == self.limit:
            print(f"... （明细已截断，只显示前 {self.limit} 条；用 --verbose 看全量）")
            self._shown += 1


# --------------------------------------------------------------------------
# 模式 A：磁盘 vs 预期
# --------------------------------------------------------------------------
def iter_book_files(root: pathlib.Path):
    for dirpath, dirnames, filenames in os.walk(root):
        # 跳过隐藏目录，减少噪音
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            if name.startswith("."):
                continue
            if pathlib.PurePath(name).suffix.lower() in BOOK_EXTS:
                yield pathlib.Path(dirpath) / name


def mode_disk(args, out: Output) -> int:
    root = pathlib.Path(args.library_root)
    if not root.is_dir():
        print(f"[错误] 库根不存在或不是目录: {root}", file=sys.stderr)
        return 2

    root_pure = pathlib.PurePath(os.path.normpath(str(root)))
    base = root_pure.parent
    print(f"库根      : {root_pure}")
    print(f"哈希基准  : {base}   （= 库根的父目录）")
    print("")

    count = 0
    for f in iter_book_files(root):
        rel = relative_from_base(pathlib.PurePath(str(f)), root_pure)
        h = hashlib.sha1(rel.encode("utf-8")).hexdigest()
        count += 1
        if not args.summary_only:
            out.detail(f"{rel}\t{h}")

    print("")
    print(f"文件总数  : {count}")
    if args.summary_only:
        print("（--summary-only：未输出明细）")
    return 0


# --------------------------------------------------------------------------
# 模式 B：与 LRR 对拍
# --------------------------------------------------------------------------
def load_lrr_json(path: str) -> dict[str, str]:
    """读 JSON / JSONL 形式的 LRR_FILEMAP。

    支持的形状：
      - {"<path>": "<arcid>", ...}                 扁平 dict
      - [{"path": "...", "id": "..."}, ...]        列表
      - [{"file": "...", "arcid": "..."}, ...]     列表别名
      - JSONL：每行一个上述对象
    """
    p = pathlib.Path(path)
    text = p.read_text(encoding="utf-8", errors="replace").strip()
    if not text:
        return {}

    result: dict[str, str] = {}
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        data = None
        for lineno, line in enumerate(text.splitlines(), 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            _absorb_lrr_obj(obj, result)
        return result

    _absorb_lrr_obj(data, result)
    return result


def _absorb_lrr_obj(obj, result: dict[str, str]) -> None:
    if isinstance(obj, dict):
        # 扁平 dict：key 是路径，value 是 arcid
        looks_flat = all(isinstance(v, (str, int)) for v in obj.values())
        if looks_flat and obj:
            for k, v in obj.items():
                result[str(k)] = str(v)
            return
        # 单条记录 {"path": ..., "id": ...}
        key = obj.get("path") or obj.get("file") or obj.get("filename")
        val = obj.get("id") or obj.get("arcid") or obj.get("hash")
        if key is not None and val is not None:
            result[str(key)] = str(val)
        return
    if isinstance(obj, list):
        for item in obj:
            _absorb_lrr_obj(item, result)


def load_lrr_aof(path: str) -> dict[str, str]:
    """从 Redis / Valkey AOF 中解析 HSET LRR_FILEMAP 记录。

    兼容两种格式：
      - RESP 数组（*N\\r\\n$len\\r\\n...）
      - 内联命令（HSET LRR_FILEMAP key value）
    只关心 db0 中的 LRR_FILEMAP；遇到 SELECT 非 0 库时跳过后续写入。
    """
    result: dict[str, str] = {}
    with open(path, "rb") as fh:
        raw = fh.read()

    # 快路径：RESP 解析
    pos = 0
    n = len(raw)
    current_db = 0
    pending: list[str] = []

    def read_resp_line(idx: int):
        end = raw.find(b"\r\n", idx)
        if end == -1:
            return None, n
        return raw[idx:end], end + 2

    while pos < n:
        if raw[pos:pos + 1] == b"*":
            line, pos = read_resp_line(pos + 1)
            if line is None:
                break
            try:
                argc = int(line)
            except ValueError:
                pos += 1
                continue
            if argc < 0:
                continue
            args: list[bytes] = []
            ok = True
            for _ in range(argc):
                if pos >= n:
                    ok = False
                    break
                marker = raw[pos:pos + 1]
                if marker != b"$":
                    ok = False
                    break
                line, pos = read_resp_line(pos + 1)
                if line is None:
                    ok = False
                    break
                try:
                    blen = int(line)
                except ValueError:
                    ok = False
                    break
                if blen < 0:
                    args.append(b"")
                    continue
                args.append(raw[pos:pos + blen])
                pos += blen + 2  # 跳过尾部 CRLF
            if not ok:
                pos += 1
                continue
            pending = [a.decode("utf-8", "surrogateescape") for a in args]
        else:
            end = raw.find(b"\r\n", pos)
            if end == -1:
                break
            try:
                text = raw[pos:end].decode("utf-8", "surrogateescape")
            except Exception:
                pos = end + 2
                continue
            pos = end + 2
            pending = _split_inline(text)

        if not pending:
            continue
        cmd = pending[0].upper()
        if cmd == "SELECT" and len(pending) >= 2:
            try:
                current_db = int(pending[1])
            except ValueError:
                pass
        elif cmd in ("HSET", "HMSET") and current_db == 0 and len(pending) >= 4:
            if pending[1] == "LRR_FILEMAP":
                rest = pending[2:]
                for i in range(0, len(rest) - 1, 2):
                    result[rest[i]] = rest[i + 1]
        elif cmd in ("HDEL",) and current_db == 0 and len(pending) >= 3:
            if pending[1] == "LRR_FILEMAP":
                for field in pending[2:]:
                    result.pop(field, None)
        pending = []

    return result


def _split_inline(text: str) -> list[str]:
    """粗分内联命令（够用即可，路径里一般无空格）。"""
    return [part for part in text.split(" ") if part != ""]


def _looks_like_sha1(value: str) -> bool:
    """LRR_FILEMAP 的 value 是 arcid（通常 40 位十六进制），不一定是 sha1。

    仅当它「像哈希」时才拿来做哈希比对；否则只算命中（同书不同 ID 体系）。
    这里从严：必须 40 位且全小写十六进制 —— 全 0/全 f 这类明显是占位 arcid 的，
    也按「不像哈希」处理，避免把 ID 误报成哈希不一致。
    """
    if len(value) != 40:
        return False
    if not all(c in "0123456789abcdef" for c in value):
        return False
    # 全同字符视为占位符
    if len(set(value)) == 1:
        return False
    return True


def _tail_keys(path: str, n: int = 2) -> tuple[str, ...]:
    """取路径尾部 n 段，用作跨根匹配的键。"""
    parts = [p for p in pathlib.PurePath(path).parts if p not in ("/", "\\")]
    return tuple(parts[-n:]) if len(parts) >= n else tuple(parts)


def _build_tail_index(mapping: dict[str, str], n: int) -> dict[tuple[str, ...], list[tuple[str, str]]]:
    idx: dict[tuple[str, ...], list[tuple[str, str]]] = {}
    for k, v in mapping.items():
        idx.setdefault(_tail_keys(k, n), []).append((k, v))
    return idx


def mode_lrr(args, out: Output) -> int:
    root = pathlib.Path(args.library_root)
    if not root.is_dir():
        print(f"[错误] 库根不存在或不是目录: {root}", file=sys.stderr)
        return 2

    if args.lrr_file:
        lrr = load_lrr_json(args.lrr_file)
        src = args.lrr_file
    elif args.lrr_aof:
        lrr = load_lrr_aof(args.lrr_aof)
        src = args.lrr_aof
    else:
        print("[错误] 需要 --lrr-file 或 --lrr-aof", file=sys.stderr)
        return 2

    print(f"LRR 数据源: {src}")
    print(f"LRR 记录数: {len(lrr)}")
    print(f"库根      : {root}")
    print("")

    if not lrr:
        print("[警告] LRR 侧没有解析到任何记录，检查文件格式。")
        return 1

    # 磁盘侧
    disk: dict[str, str] = {}
    for f in iter_book_files(root):
        rel = relative_from_base(pathlib.PurePath(str(f)), root)
        disk[rel] = hashlib.sha1(rel.encode("utf-8")).hexdigest()

    # 匹配策略：按相对路径尾部 n 段
    matched = 0
    hash_mismatch = 0
    disk_only = 0
    lrr_only = 0
    mismatch_samples: list[str] = []

    for n in (3, 2, 1):
        lrr_idx = _build_tail_index(lrr, n)
        used_lrr: set[str] = set()
        matched = hash_mismatch = disk_only = 0
        mismatch_samples = []

        for rel, sha in disk.items():
            key = _tail_keys(rel, n)
            candidates = lrr_idx.get(key, [])
            # 取一条尚未被占用的 LRR 记录（路径 -> arcid）
            picked = None
            for lrr_path, _lrr_id in candidates:
                if lrr_path in used_lrr:
                    continue
                picked = (lrr_path, _lrr_id)
                break
            if picked is None:
                disk_only += 1
                continue
            lrr_path, lrr_id = picked
            used_lrr.add(lrr_path)

            if _looks_like_sha1(lrr_id):
                if lrr_id.lower() == sha.lower():
                    matched += 1
                else:
                    hash_mismatch += 1
                    mismatch_samples.append(f"{rel}\n  disk={sha}\n  lrr ={lrr_id}")
            else:
                # LRR 的 value 是 arcid（另一套 ID），不参与哈希比对，只算命中
                matched += 1

        lrr_only = len(lrr) - len(used_lrr)
        if disk_only == 0 and lrr_only == 0:
            break
        if matched > 0:
            break

    total_disk = len(disk)
    print("=== 对拍结果 ===")
    print(f"磁盘文件数        : {total_disk}")
    print(f"LRR 记录数        : {len(lrr)}")
    print(f"命中（同书同哈希）: {matched}")
    print(f"哈希不一致        : {hash_mismatch}")
    print(f"仅磁盘有          : {disk_only}")
    print(f"仅 LRR 有         : {lrr_only}")
    if total_disk:
        print(f"命中率            : {matched}/{total_disk} = {matched * 100.0 / total_disk:.2f}%")
    print("")

    if mismatch_samples:
        print(f"=== 哈希不一致样例（最多 {args.limit} 条）===")
        for s in mismatch_samples[:args.limit]:
            print(s)
        print("")

    if not args.verbose:
        print(f"（明细已截断，--verbose 看全量）")
    return 0


# --------------------------------------------------------------------------
# 模式 C：与 Komga 库对拍
# --------------------------------------------------------------------------
def mode_komga(args, out: Output) -> int:
    db_path = pathlib.Path(args.komga_db)
    if not db_path.is_file():
        print(f"[错误] Komga 数据库不存在: {db_path}", file=sys.stderr)
        return 2

    uri = f"file:{db_path.as_posix()}?mode=ro"
    try:
        con = sqlite3.connect(uri, uri=True)
    except sqlite3.Error as exc:
        print(f"[错误] 只读打开失败: {exc}", file=sys.stderr)
        return 2

    try:
        cur = con.cursor()
        libraries = {}
        for lib_id, name, root_uri in cur.execute("SELECT ID, NAME, ROOT FROM LIBRARY"):
            libraries[lib_id] = (name, root_uri)

        print(f"Komga 数据库: {db_path}")
        print(f"库数量      : {len(libraries)}")
        for lib_id, (name, root_uri) in libraries.items():
            print(f"  - {name}: {root_uri}")
        print("")

        rows = list(cur.execute("SELECT ID, LIBRARY_ID, URL, FILE_HASH FROM BOOK"))
        print(f"BOOK 记录数 : {len(rows)}")
        print("")

        ok = bad = skipped = 0
        bad_samples: list[str] = []
        for book_id, lib_id, url, file_hash in rows:
            if lib_id not in libraries:
                skipped += 1
                continue
            lib_name, root_uri = libraries[lib_id]
            if not root_uri:
                skipped += 1
                continue
            lib_root = file_uri_to_path(root_uri)
            book_path = file_uri_to_path(url)
            try:
                expected = compute_path_hash(book_path, lib_root)
            except ValueError:
                skipped += 1
                continue
            if expected.lower() == (file_hash or "").lower():
                ok += 1
                out.detail(f"OK   {book_id}  {url}")
            else:
                bad += 1
                out.detail(
                    f"BAD  {book_id}\n"
                    f"     url      = {url}\n"
                    f"     stored   = {file_hash}\n"
                    f"     expected = {expected}"
                )
                bad_samples.append(f"{book_id} {url}\n  stored={file_hash}\n  expect={expected}")

        total = ok + bad
        print("")
        print("=== 对拍结果 ===")
        print(f"比对条数    : {total}")
        print(f"一致        : {ok}")
        print(f"不一致      : {bad}")
        if skipped:
            print(f"跳过        : {skipped}")
        if total:
            print(f"一致率      : {ok}/{total} = {ok * 100.0 / total:.2f}%")
        if bad and not args.verbose:
            print("")
            print(f"（不一致样例最多 {args.limit} 条，--verbose 看全量）")
            for s in bad_samples[:args.limit]:
                print(s)
        return 0 if bad == 0 else 1
    finally:
        con.close()


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="verify_path_hash.py",
        description="复刻 Komga Hasher.computePathHash 并做三方对拍（磁盘 / LRR / Komga 库）。",
        epilog=(
            "示例：\n"
            f"  python3 tools/verify_path_hash.py --library-root {DEFAULT_LIBRARY_ROOT} --limit 10\n"
            f"  python3 tools/verify_path_hash.py --library-root {DEFAULT_LIBRARY_ROOT} --summary-only\n"
            "  python3 tools/verify_path_hash.py --komga-db .test-komga/database.sqlite\n"
            "  python3 tools/verify_path_hash.py --library-root ... --lrr-file /tmp/lrr_filemap.json\n"
            "  python3 tools/verify_path_hash.py --library-root ... --lrr-aof .../appendonly.aof.1.incr.aof\n"
            "\n"
            "算法说明：哈希基准是库根的【父目录】，所以库根要填到 .../content/wnacg，\n"
            "相对路径才会是 wnacg/oneshots/1-50000/xxx.cbz。\n"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--library-root",
        default=None,
        help=f"库根目录，显式给出时进入模式 A；不给出时退回默认 {DEFAULT_LIBRARY_ROOT}（仅用于模式 B）",
    )
    parser.add_argument(
        "--lrr-file",
        help="LRR_FILEMAP 导出（JSON / JSONL），与磁盘对拍",
    )
    parser.add_argument(
        "--lrr-aof",
        help="Redis / Valkey AOF 文件，从中解析 HSET LRR_FILEMAP 记录",
    )
    parser.add_argument(
        "--komga-db",
        help="Komga 的 database.sqlite（只读打开，绝不写库）",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=20,
        help="非 verbose 时明细最多显示几条，默认 20",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="输出全量明细，不截断",
    )
    parser.add_argument(
        "--summary-only",
        action="store_true",
        help="模式 A 只输出统计，不输出路径哈希明细",
    )
    return parser


def main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    # 默认全都不跑：必须显式选模式。
    # 注意用 is not None 判断，而不是比较默认值 —— 否则显式传入与默认值相同的
    # --library-root 会被误判成「没选模式」。
    mode_a = args.library_root is not None
    mode_b = bool(args.lrr_file or args.lrr_aof)
    mode_c = bool(args.komga_db)

    if not (mode_a or mode_b or mode_c):
        parser.print_help()
        print("")
        print("[提示] 默认不跑任何模式，请显式指定 --library-root / --lrr-file / --lrr-aof / --komga-db。")
        return 2

    # 模式 B 需要库根来遍历磁盘，未显式给出时退回默认
    if args.library_root is None:
        args.library_root = DEFAULT_LIBRARY_ROOT

    out = Output(verbose=args.verbose, limit=max(0, args.limit))

    rc = 0
    if args.komga_db:
        rc = max(rc, mode_komga(args, out))
    if args.lrr_file or args.lrr_aof:
        rc = max(rc, mode_lrr(args, out))
    elif not args.komga_db:
        rc = max(rc, mode_disk(args, out))
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))