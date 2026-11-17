#!/usr/bin/env python3
"""Rewrite wnacg_archive so that the 115 disk is the source of truth.

- every comic_id present on disk  -> state = 'done'
- every comic_id absent from disk -> state = 'pending' (was 'done')
- failed / permanent_failed are kept as-is (historical attempt results)

Run with --apply; default is a dry-run summary.
"""
import argparse, os, subprocess, sys

ap = argparse.ArgumentParser()
ap.add_argument("--root", required=True)
ap.add_argument("--site", default="wnacg")
ap.add_argument("--pg-host", default="127.0.0.1")
ap.add_argument("--pg-port", default="55432")
ap.add_argument("--pg-db", default="comic_meta")
ap.add_argument("--pg-user", default="komga_ro")
ap.add_argument("--list", default="/tmp/disk_ids.txt")
ap.add_argument("--apply", action="store_true")
a = ap.parse_args()

ids = []
for bucket in sorted(os.listdir(a.root)):
    d = os.path.join(a.root, bucket)
    if not os.path.isdir(d):
        continue
    for n in os.listdir(d):
        if n.lower().endswith((".cbz", ".zip")):
            ids.append(n.rsplit(".", 1)[0])
print("on disk:", len(ids))
open(a.list, "w").write("\n".join(ids) + "\n")

def psql(sql, capture=True):
    cmd = ["psql", "-h", a.pg_host, "-p", a.pg_port, "-U", a.pg_user, "-d", a.pg_db,
           "-tAc", sql]
    r = subprocess.run(cmd, capture_output=capture, text=True, env=os.environ)
    if r.returncode != 0:
        sys.exit("psql failed: " + r.stderr[:300])
    return r.stdout

for st, n in (line.split("|") for line in psql(
        "select coalesce(state,'<none>'), count(*) from %s_archive group by 1 order by 2 desc" % a.site).splitlines() if line):
    print("  before %-16s %s" % (st, n))

if not a.apply:
    print("dry-run: nothing written")
    sys.exit(0)

script = """
BEGIN;
CREATE TEMP TABLE disk_ids(comic_id text) ON COMMIT DROP;
\\copy disk_ids from '{list}'
UPDATE {site}_archive SET state='pending', downloaded=0
 WHERE state='done' AND comic_id NOT IN (SELECT comic_id FROM disk_ids);
UPDATE {site}_archive a SET state='done'
  FROM disk_ids d WHERE a.comic_id=d.comic_id AND coalesce(a.state,'') <> 'done';
INSERT INTO {site}_archive(comic_id, state)
  SELECT d.comic_id, 'done' FROM disk_ids d
   WHERE NOT EXISTS (SELECT 1 FROM {site}_archive a WHERE a.comic_id=d.comic_id);
COMMIT;
""".format(list=a.list, site=a.site)
open("/tmp/rebuild_archive.sql", "w").write(script)

r = subprocess.run(["psql", "-h", a.pg_host, "-p", a.pg_port, "-U", a.pg_user, "-d", a.pg_db,
                    "-f", "/tmp/rebuild_archive.sql"], capture_output=True, text=True, env=os.environ)
if r.returncode != 0:
    sys.exit("apply failed: " + r.stderr[:500])
print(r.stdout.strip()[-400:])

for st, n in (line.split("|") for line in psql(
        "select coalesce(state,'<none>'), count(*) from %s_archive group by 1 order by 2 desc" % a.site).splitlines() if line):
    print("  after  %-16s %s" % (st, n))
