#!/usr/bin/env python3
"""Pull the pending queue from comic_meta and ask Mangrab to download each id.

This is the missing link between the metadata database and the downloader:

    comic_meta.<site>_archive (state='pending')
        -> POST {mangrab}/api/download/by-id {"comicId": ...}
        -> Mangrab downloads the archive onto the 115 mount

The script does NOT mark anything as done: the reconciler (reconcile-comic-meta.py
--fix) does that once the archive actually exists, and then asks Komga to scan.

Usage:
  PGPASSWORD=... ./download-dispatch.py --site wnacg --limit 20 \
      --mangrab-url http://127.0.0.1:8080 --mangrab-token $JM_AUTH_TOKEN [--dry-run]
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request


def psql(args, sql):
    out = subprocess.run(
        ["psql", "-h", args.pg_host, "-p", str(args.pg_port), "-U", args.pg_user,
         "-d", args.pg_db, "-tAF", "\t", "-c", sql],
        capture_output=True, text=True, env=os.environ,
    )
    if out.returncode != 0:
        sys.exit("psql failed: " + out.stderr.strip()[:400])
    return [line for line in out.stdout.splitlines() if line]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", default="wnacg", choices=["wnacg", "pica", "jm"])
    ap.add_argument("--pg-host", default="127.0.0.1")
    ap.add_argument("--pg-port", type=int, default=55432)
    ap.add_argument("--pg-db", default="comic_meta")
    ap.add_argument("--pg-user", default="komga_ro")
    ap.add_argument("--mangrab-url", default="http://127.0.0.1:8080")
    ap.add_argument("--mangrab-token", default=os.environ.get("JM_AUTH_TOKEN", ""))
    ap.add_argument("--limit", type=int, default=20)
    ap.add_argument("--max-retry", type=int, default=5, help="skip ids that failed this often")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    sql = (
        "select comic_id from %s_archive "
        "where coalesce(state,'') = 'pending' and coalesce(retry_count,0) < %d "
        "order by coalesce(updated_at,0) asc limit %d"
        % (args.site, args.max_retry, args.limit)
    )
    ids = [line.split("\t")[0] for line in psql(args, sql)]
    print("pending ids selected: %d" % len(ids))
    if not ids:
        return

    if args.dry_run:
        for cid in ids:
            print("  would download: %s" % cid)
        return

    headers = {"Content-Type": "application/json"}
    if args.mangrab_token:
        headers["Authorization"] = "Bearer " + args.mangrab_token
    else:
        print("warning: no mangrab token, sending unauthenticated requests")

    ok = failed = 0
    for cid in ids:
        body = json.dumps({"comicId": cid}).encode()
        req = urllib.request.Request(
            args.mangrab_url.rstrip("/") + "/api/download/by-id",
            data=body, method="POST",
            headers=headers,
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = json.loads(resp.read() or b"{}")
            print("  %s -> created %s" % (cid, payload.get("createdCount")))
            ok += 1
        except urllib.error.HTTPError as exc:
            detail = exc.read()[:200].decode(errors="replace")
            print("  %s -> HTTP %s %s" % (cid, exc.code, detail))
            failed += 1
        except Exception as exc:
            print("  %s -> %s" % (cid, exc))
            failed += 1

    print("created: %d, failed: %d" % (ok, failed))


if __name__ == "__main__":
    main()
