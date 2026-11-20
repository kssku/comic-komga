#!/usr/bin/env python3
"""Move Mangrab exports into the Komga/LANraragi library layout.

Mangrab writes  <spool>/<site>/<comic_id>/<order>.cbz
Komga expects   <dest>/oneshots/<bucket>/<comic_id>.cbz

A one-shot wnacg comic has exactly one exported archive, so it is renamed to
<comic_id>.cbz and placed in the id-range bucket. Multi-chapter exports are
left alone (reported) until a series layout is decided.

Default is dry-run.
"""
import argparse
import os
import re
import shutil
import sys

BUCKET = 50000


def bucket_for(comic_id):
    if not comic_id.isdigit():
        return None
    n = int(comic_id)
    start = ((n - 1) // BUCKET) * BUCKET + 1
    return "%d-%d" % (start, start + BUCKET - 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spool", required=True, help="mangrab download dir")
    ap.add_argument("--site", default="wnacg")
    ap.add_argument("--dest", required=True, help="library root, e.g. /content/wnacg")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--keep-spool", action="store_true", help="copy instead of move")
    a = ap.parse_args()

    site_dir = os.path.join(a.spool, a.site)
    if not os.path.isdir(site_dir):
        sys.exit("no such site dir: " + site_dir)

    moved = skipped = 0
    for comic_id in sorted(os.listdir(site_dir)):
        cdir = os.path.join(site_dir, comic_id)
        if not os.path.isdir(cdir):
            continue
        cbzs = [f for f in os.listdir(cdir) if f.lower().endswith(".cbz")]
        if not cbzs:
            continue
        if len(cbzs) > 1:
            print("skip %s: %d archives (multi-chapter, needs series layout)" % (comic_id, len(cbzs)))
            skipped += 1
            continue
        bucket = bucket_for(comic_id)
        if bucket is None:
            print("skip %s: non-numeric id" % comic_id)
            skipped += 1
            continue
        src = os.path.join(cdir, cbzs[0])
        ddir = os.path.join(a.dest, "oneshots", bucket)
        dst = os.path.join(ddir, comic_id + ".cbz")
        if os.path.exists(dst):
            print("skip %s: destination exists" % comic_id)
            skipped += 1
            continue
        print("move %s -> %s" % (src, dst))
        if a.apply:
            os.makedirs(ddir, exist_ok=True)
            if a.keep_spool:
                shutil.copy2(src, dst + ".part")
                os.replace(dst + ".part", dst)
            else:
                shutil.move(src, dst)
        moved += 1

    print("candidates: %d, skipped: %d, applied: %s" % (moved, skipped, a.apply))
    if not a.apply:
        print("dry-run: nothing written (use --apply)")


if __name__ == "__main__":
    main()
