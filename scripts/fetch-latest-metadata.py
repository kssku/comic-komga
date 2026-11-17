#!/usr/bin/env python3
"""Fetch the newest wnacg entries and upsert their metadata into comic_meta.

Where the metadata comes from:
  - the site homepage lists the newest volumes as
    /photos-index-aid-<comic_id>.html with the title in the title= attribute
  - Mangrab's GET /api/comic/<id> already parses title/description/author/tags/cover,
    so we do not re-implement any site parsing

What it writes (idempotent):
  <site>_works       one row per work (series_id, else the comic id itself)
  <site>_volumes     title / cover / description / source_url
  <site>_archive     state='pending' so the download loop picks it up

Default is a dry run: --apply writes.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request

ITEM_RE = re.compile(r'photos-index-aid-(\d+)\.html"[^>]*title="([^"]*)"')


def fetch(url):
    """curl, not urllib: the site redirects to another domain and curl follows it reliably
    through the proxy configured in the environment (http_proxy/https_proxy)."""
    out = subprocess.run(
        ["curl", "-sSL", "-m", "60", "-A", "Mozilla/5.0", url],
        capture_output=True, timeout=90,
    )
    if out.returncode != 0:
        sys.exit("fetch failed: %s: %s" % (url, out.stderr.decode(errors="replace")[:300]))
    return out.stdout.decode("utf-8", errors="replace")


def psql(args, sql, fetch_result=True):
    cmd = ["psql", "-h", args.pg_host, "-p", str(args.pg_port), "-U", args.pg_user,
           "-d", args.pg_db, "-tAF", "\t", "-c", sql]
    r = subprocess.run(cmd, capture_output=True, text=True, env=os.environ)
    if r.returncode != 0:
        sys.exit("psql failed: " + r.stderr.strip()[:400])
    return r.stdout if fetch_result else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", default="wnacg")
    ap.add_argument("--listing-url", default="https://www.wn07.ru/")
    ap.add_argument("--mangrab-url", default="http://127.0.0.1:8080")
    ap.add_argument("--pg-host", default="127.0.0.1")
    ap.add_argument("--pg-port", type=int, default=55432)
    ap.add_argument("--pg-db", default="comic_meta")
    ap.add_argument("--pg-user", default="komga_ro")
    ap.add_argument("--limit", type=int, default=10)
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    if args.listing_url.startswith("http") and os.environ.get("HTTP_PROXY_USE"):
        pass
    html = fetch(args.listing_url)
    items = []
    seen = set()
    for cid, title in ITEM_RE.findall(html):
        if cid in seen:
            continue
        seen.add(cid)
        items.append((cid, title))
    print("listing: %d unique comics" % len(items))

    known = set()
    if items:
        ids = ",".join("'" + c.replace("'", "''") + "'" for c, _ in items)
        out = psql(args, "select comic_id from %s_volumes where comic_id in (%s)" % (args.site, ids))
        known = {line.split("\t")[0] for line in out.splitlines() if line}

    fresh = [(c, t) for c, t in items if c not in known][: args.limit]
    print("already known: %d, new: %d (taking %d)" % (len(known), len(items) - len(known), len(fresh)))
    if not fresh:
        return

    for cid, title in fresh:
        detail = json.loads(fetch("%s/api/comic/%s" % (args.mangrab_url.rstrip("/"), cid)))
        name = detail.get("name") or title
        work_id = str(detail.get("series_id") or cid)
        cover = detail.get("coverUrl") or ""
        desc = (detail.get("description") or "").strip()[:2000]
        # the wnacg plugin repeats the same tag block many times in tags; dedupe and
        # drop the numeric leftovers (a bare 1 etc.) before writing
        raw_tags = detail.get("tags") or []
        tags = [t for t in dict.fromkeys(raw_tags) if t and not t.isdigit()][:20]
        print("  %s  %s  tags=%d" % (cid, name[:50], len(tags)))
        if not args.apply:
            continue

        q = lambda s: s.replace("'", "''")
        psql(args, (
            "INSERT INTO {s}_works (work_id, title) VALUES ('{w}', '{t}') "
            "ON CONFLICT (work_id) DO NOTHING;"
            "INSERT INTO {s}_volumes (comic_id, work_id, title, cover, source_url, description, updated_at_unix) "
            "VALUES ('{c}', '{w}', '{t}', '{cv}', '{u}', '{d}', extract(epoch from now())::bigint) "
            "ON CONFLICT (comic_id) DO UPDATE SET title = excluded.title, cover = excluded.cover, "
            "description = excluded.description, updated_at_unix = excluded.updated_at_unix;"
            "INSERT INTO {s}_archive (comic_id, state, updated_at) VALUES ('{c}', 'pending', extract(epoch from now())::bigint) "
            "ON CONFLICT (comic_id) DO NOTHING;"
        ).format(s=args.site, w=q(work_id), t=q(name), c=q(cid), cv=q(cover), u=q(args.listing_url), d=q(desc)), fetch_result=False)

        for tag in tags[:40]:
            row = psql(args, "select id from %s_tags where name = '%s' limit 1" % (args.site, q(tag))).strip()
            if row:
                tag_id = row.split("\t")[0]
            else:
                tag_id = psql(args, "select coalesce(max(id),0)+1 from %s_tags" % args.site).strip()
                psql(args, "insert into %s_tags (id, name) values (%s, '%s')" % (args.site, tag_id, q(tag)), fetch_result=False)
            psql(args, "insert into %s_work_tags (work_id, tag_id) values ('%s', %s) on conflict do nothing"
                 % (args.site, q(work_id), tag_id), fetch_result=False)

    print("done%s" % ("" if args.apply else " (dry-run: use --apply)"))


if __name__ == "__main__":
    main()
