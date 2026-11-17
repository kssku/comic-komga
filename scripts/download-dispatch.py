#!/usr/bin/env python3
"""Pull the pending queue from comic_meta and ask Mangrab to download it.

comic_meta.<site>_archive (state='pending')
    -> POST {mangrab}/api/download/by-ids  (batched, server-side concurrency)
    -> Mangrab downloads the archives onto the mount

The script does NOT mark anything as done: reconcile-comic-meta.py does that once
the archive exists, then asks Komga to scan.

Usage:
  PGPASSWORD=... ./download-dispatch.py --site wnacg --limit 20 \
      --mangrab-url http://127.0.0.1:8080 [--batch-size 10] [--concurrency 4] [--dry-run]
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


def post(url, body, token):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return json.loads(resp.read() or b"{}"), None
    except urllib.error.HTTPError as exc:
        return None, "HTTP %s %s" % (exc.code, exc.read()[:200].decode(errors="replace"))
    except Exception as exc:
        return None, str(exc)


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
    ap.add_argument("--batch-size", type=int, default=10, help="ids per /download/by-ids call")
    ap.add_argument("--concurrency", type=int, default=4, help="server-side detail fetches per batch")
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

    base = args.mangrab_url.rstrip("/")
    batch = max(1, args.batch_size)
    created = failed = 0
    for i in range(0, len(ids), batch):
        chunk = ids[i:i + batch]
        payload, err = post(base + "/api/download/by-ids",
                            {"comicIds": chunk, "concurrency": args.concurrency},
                            args.mangrab_token)
        if err:
            print("  batch %d-%d -> %s" % (i + 1, i + len(chunk), err))
            failed += len(chunk)
            continue
        for item in payload.get("created", []):
            print("  %s -> created %s" % (item.get("comicId"), item.get("createdCount")))
            created += 1
        for item in payload.get("failed", []):
            print("  %s -> %s" % (item.get("comicId"), (item.get("error") or "")[:120]))
            failed += 1

    print("created: %d, failed: %d" % (created, failed))


if __name__ == "__main__":
    main()
