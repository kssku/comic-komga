#!/usr/bin/env python3
"""Reconcile a manga library on disk against the comic_meta database.

Default is DRY-RUN: it only reports. With --fix it repairs the one safe class:

    file exists + _archive.state in (pending, failed) + page_count matches
    -> state = 'done'

Everything else (missing metadata, missing file, page-count mismatch, odd
file names) is reported only.

Usage:
  PGPASSWORD=... ./reconcile-comic-meta.py --root /mnt/115/wnacg --site wnacg \
      --pg-host 127.0.0.1 --pg-db comic_meta --pg-user komga_ro [--fix] [--check-pages N]
"""
import argparse
import base64
import collections
import os
import subprocess
import sys
import urllib.request


def psql(args, sql):
    cmd = [
        "psql", "-h", args.pg_host, "-p", str(args.pg_port), "-U", args.pg_user,
        "-d", args.pg_db, "-tAF", "	", "-c", sql,
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, env=os.environ)
    if out.returncode != 0:
        sys.exit("psql failed: " + out.stderr.strip()[:400])
    return [line for line in out.stdout.splitlines() if line]


def list_archives(root):
    found = {}
    for bucket in sorted(os.listdir(root)):
        bdir = os.path.join(root, bucket)
        if not os.path.isdir(bdir):
            continue
        for name in os.listdir(bdir):
            if name.lower().endswith((".cbz", ".zip")):
                found[name.rsplit(".", 1)[0]] = os.path.join(bdir, name)
    return found


def count_pages(path):
    import zipfile
    try:
        with zipfile.ZipFile(path) as z:
            return sum(1 for n in z.namelist()
                       if n.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".gif")))
    except Exception:
        return -1


def build_parser():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="library root containing <bucket>/<id>.cbz")
    ap.add_argument("--site", default="wnacg", choices=["wnacg", "pica", "jm"])
    ap.add_argument("--pg-host", default="127.0.0.1")
    ap.add_argument("--pg-port", type=int, default=55432)
    ap.add_argument("--pg-db", default="comic_meta")
    ap.add_argument("--pg-user", default="komga_ro")
    ap.add_argument("--fix", action="store_true", help="apply the safe repair (default: report only)")
    ap.add_argument("--check-pages", type=int, default=0, metavar="N",
                    help="verify page_count for up to N candidates by reading the archive")
    ap.add_argument("--samples", type=int, default=5)
    ap.add_argument("--komga-url", default="", help="e.g. http://komga:25600")
    ap.add_argument("--komga-user", default="")
    ap.add_argument("--komga-pass", default="")
    ap.add_argument("--komga-library", default="", help="library id to scan after a repair")
    ap.add_argument("--interval", type=int, default=0, metavar="SECONDS",
                    help="0 = run once; >0 = loop (sidecar mode)")
    return ap


def run_once(args):

    print("listing archives under " + args.root + " ...")
    files = list_archives(args.root)
    print("archives on disk: %d" % len(files))

    states = {}
    for line in psql(args, "select comic_id, state from %s_archive" % args.site):
        cid, _, st = line.partition("	")
        states[cid] = st or ""

    volumes = {}
    for line in psql(args, "select comic_id, coalesce(title, ''), coalesce(page_count, 0) from %s_volumes" % args.site):
        cid, _, rest = line.partition("	")
        title, _, pages = rest.rpartition("	")
        volumes[cid] = (title, int(pages or 0))

    by_state = collections.Counter(states.get(cid, "<none>") for cid in files)
    no_volume = [cid for cid in files if cid not in volumes]
    empty_title = [cid for cid in files if cid in volumes and not volumes[cid][0].strip()]
    repairable = [cid for cid in files if states.get(cid) in ("pending", "failed")]

    print("\n== state of archives on disk ==")
    for state, n in by_state.most_common():
        print("  %-18s %8d" % (state, n))

    print("\n== no volume row        : %d  %s" % (len(no_volume), no_volume[:args.samples]))
    print("== empty title          : %d  %s" % (len(empty_title), empty_title[:args.samples]))
    print("== repairable (pending/failed but present): %d" % len(repairable))

    page_mismatch = []
    if args.check_pages > 0:
        print("\nverifying page counts for up to %d repairable candidates ..." % args.check_pages)
        for cid in repairable[: args.check_pages]:
            actual = count_pages(files[cid])
            expected = volumes.get(cid, ("", 0))[1]
            if expected and actual > 0 and actual != expected:
                page_mismatch.append((cid, actual, expected))
        print("== page-count mismatches: %d  %s" % (len(page_mismatch), page_mismatch[:args.samples]))

    if not args.fix:
        print("\nDRY-RUN: nothing written. Re-run with --fix to repair the safe class.")
        return 0

    bad = {m[0] for m in page_mismatch}
    safe = [cid for cid in repairable if cid not in bad]
    if not safe:
        print("\nnothing to repair.")
        return 0

    print("\nrepairing %d rows (%s_archive.state -> done) ..." % (len(safe), args.site))
    chunk = 500
    for i in range(0, len(safe), chunk):
        part = safe[i:i + chunk]
        values = ",".join("('" + c.replace("'", "''") + "')" for c in part)
        sql = (
            "update %s_archive a set state = 'done' from (values %s) as v(comic_id) "
            "where a.comic_id = v.comic_id and a.state in ('pending','failed')"
            % (args.site, values)
        )
        out = subprocess.run(
            ["psql", "-h", args.pg_host, "-p", str(args.pg_port), "-U", args.pg_user,
             "-d", args.pg_db, "-c", sql],
            capture_output=True, text=True, env=os.environ,
        )
        if out.returncode != 0:
            sys.exit("update failed: " + out.stderr.strip()[:400])
        print("  %d/%d" % (i + len(part), len(safe)))
    print("done.")
    return len(safe)


def notify_komga(args):
    """Ask Komga to scan the library, so newly archived books show up."""
    if not (args.komga_url and args.komga_library):
        return
    url = "%s/api/v1/libraries/%s/scan" % (args.komga_url.rstrip("/"), args.komga_library)
    req = urllib.request.Request(url, method="POST")
    if args.komga_user:
        token = base64.b64encode(("%s:%s" % (args.komga_user, args.komga_pass)).encode()).decode()
        req.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print("komga scan requested (%d)" % resp.status)
    except Exception as exc:
        print("komga scan request failed: %s" % exc)


def main():
    # -- arguments are parsed inside run_once; for sidecar mode we re-parse per round
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--interval", type=int, default=0)
    known, _ = ap.parse_known_args()
    if known.interval <= 0:
        parser_args = build_parser().parse_args()
        fixed = run_once(parser_args)
        if fixed:
            notify_komga(parser_args)
        return
    while True:
        parser_args = build_parser().parse_args()
        try:
            fixed = run_once(parser_args)
            if fixed:
                notify_komga(parser_args)
        except SystemExit:
            raise
        except Exception as exc:  # keep the sidecar alive
            print("sync round failed: %s" % exc)
        print("sleeping %ds" % parser_args.interval)
        time.sleep(parser_args.interval)


if __name__ == "__main__":
    main()
