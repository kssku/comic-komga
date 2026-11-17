# 三位一体部署（Mangrab + comic_meta + Komga）

一套 compose 起四个服务，装完即用：

| 服务 | 作用 | 端口 |
|---|---|---|
| comic-meta-pg | 主元数据库（PostgreSQL 16） | 127.0.0.1:55432 |
| clouddrive2 | 115 网盘挂载 | 19798（Web UI） |
| mangrab | 搜索/下载/打包/归档 | 8080 |
| komga | 浏览/阅读 | 25600 |

## 快速开始

```bash
cd deploy/integrated
cp .env.example .env
# 编辑 .env：三个数据库密码、115 挂载路径
cp /path/to/komga-1.0.0.jar ./komga.jar
docker compose up -d
```

首次启动时 `initdb/01-roles.sh` 会创建两个账号：

- `komga_ro`：只读（Komga 用）
- `mangrab_rw`：读写（Mangrab 用）

## 已有的 comic_meta 数据

1. 复用已有 pgdata：把原数据目录放进 `comic-meta-pgdata` 卷（此时 initdb 脚本不执行，账号需自建）
2. 导入 dump：把 `pg_dump` 产物放到 `initdb/02-restore.sql`（首次初始化自动执行）
3. 空库起步：按 `comic-database/schema/schema.sql` 建表

## 目录约定

```
115:  .../content/<站点>/            ← 一个站点一个库（wnacg / jm / pica）
      .../content/wnacg/oneshots/<bucket>/<comic_id>.cbz
      .../content/wnacg/series/<bucket>/<seriesId>/<volume>.cbz
容器: /content/wnacg                ← Komga 库根目录
      /content                     ← Mangrab 落盘根目录
```

Komga 侧按 LANraragi 路径模式工作：单行本一个文件一个系列，多卷系列跨 bucket 聚合。

## 验证

```bash
docker logs komga 2>&1 | grep -i "comicmeta"
# → ComicMeta database enabled: source=wnacg

KOMGA_URL=http://127.0.0.1:25600 KOMGA_LIBRARY_ID=<id> \
KOMGA_USER=<email> KOMGA_PASS=<pass> ../scripts/komga-scan-notify.sh
```

## 下载完成后自动可见

Mangrab 打包成功后调用 Komga 扫描接口即可（`KOMGA_SCAN_URL` 或直接跑脚本）：

```bash
scripts/komga-scan-notify.sh
```

## 已知前提

- CloudDrive2 需要 `/dev/fuse`、`--privileged`，宿主要 `mount --make-rshared /`
  （否则 bind mount 报 “not a shared mount”）
- 同一个 115 账号不要在两台机器上同时挂载（会互相干扰）
- 备份：`./data/komga` + comic_meta 即可；漫画文件在 115，不需要备份；封面缓存以后另算

## 常见问题：重建 clouddrive2 后 115 挂载消失

删掉容器后宿主机上的 FUSE 挂载点会变成“传输端点尚未连接”（stale），新容器不会再自动挂上。

处理：

```bash
docker compose stop clouddrive2
umount -l /path/to/media/115open || fusermount -uz /path/to/media/115open
rm -rf /path/to/media/115open
docker compose up -d clouddrive2
# 若仍未挂上：打开 http://<host>:19798 在 CloudDrive2 里把该网盘重新“挂载”
```

这也是为什么 production 的 compose 要保留 CLOUDDRIVE_CONFIG 卷：不要 docker compose down -v。

## 对账/同步边车

`scripts/reconcile-comic-meta.py` 既是报告工具，也能当边车：

```bash
# 一次性：报告（默认 dry-run）
PGPASSWORD=... python3 scripts/reconcile-comic-meta.py --root /content/wnacg/oneshots

# 修复安全类别（文件在、状态 pending/failed、页数一致 -> done），并让 Komga 扫描
PGPASSWORD=... python3 scripts/reconcile-comic-meta.py \
  --root /content/wnacg/oneshots --pg-user mangrab_rw --fix \
  --komga-url http://komga:25600 --komga-user <email> --komga-pass <pass> \
  --komga-library <libraryId>

# 常驻边车：每 10 分钟一轮
PGPASSWORD=... python3 scripts/reconcile-comic-meta.py \
  --root /content/wnacg/oneshots --pg-user mangrab_rw --fix --interval 600 \
  --komga-url http://komga:25600 --komga-user <email> --komga-pass <pass> \
  --komga-library <libraryId>
```

建议用宿主机的 systemd timer 或 cron 跑（镜像里没有 python，别塞进 compose 主栈）：

```cron
*/10 * * * * PGPASSWORD=... /usr/bin/python3 /opt/komga-clouddrive/scripts/reconcile-comic-meta.py --root /content/wnacg/oneshots --pg-user mangrab_rw --fix --komga-url http://127.0.0.1:25600 --komga-user ... --komga-pass ... --komga-library ... >> /var/log/comic-sync.log 2>&1
```
