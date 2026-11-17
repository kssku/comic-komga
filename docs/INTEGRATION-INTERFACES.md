# 三方接口清单（Mangrab / comic_meta / Komga）

> 目的：把三个系统的可调用面写清楚，作为集成与对账的依据。
> 事实来源：各仓库代码 + 154 上的实际数据库。

## 0. 时序一眼

```
Mangrab 下载 → 目录 {site}/{comic_id}/{order}/图片 → 导出 {order}.cbz（含 ComicInfo.xml + 元数据.json）
   ├─ 写 115（经 CloudDrive2 挂载点）
   ├─ （缺）写 comic_meta：volumes 元数据 / archive.state
   └─ （缺）通知 Komga 扫描

Komga 扫描 /content/<site> → book.name = comic_id
   └─ 读 comic_meta（只读，按 comic_id）→ 标题/标签，不打开压缩包
```

## 1. comic_meta（PostgreSQL 16）

规模（154 实例）：`wnacg_volumes` 386,669 行，`wnacg_archive` 386,669 行；
另有 `jm_*` / `pica_*` 两套同构表 + `meta`。

### 表结构（以 wnacg 为例，jm/pica 同构）

| 表 | 主键 | 关键字段 |
|---|---|---|
| `<site>_works` | work_id | title, volume_count, cover |
| `<site>_volumes` | comic_id | work_id, title, cover, source_url, description, page_count, version, lrr_id |
| `<site>_archive` | comic_id | state(done/pending/failed/permanent_failed), downloaded, retry_count, updated_at |
| `<site>_tags` | id | name |
| `<site>_work_tags` | (work_id, tag_id) | — |
| `<site>_categories` | id | name |
| `<site>_volume_categories` | (comic_id, category_id) | — |

### 约定

- `comic_id` = 站内 id = **压缩包文件名**（`42409.cbz` → `42409`）
- `lrr_id` 是历史 LRR id（本库 141 万条全有值），**与 Komga 的 book id 不同**，不参与 join
- 数据为 2026-10 抓取的静态快照，`docs/SOURCES.md` 记录最后写入 2026-10-07

### 集成查询

```sql
-- Komga 元数据（只读）
select title, coalesce(page_count,0) from wnacg_volumes where comic_id = $1;
-- 标签（volume -> work -> tags 两跳）
select t.name from wnacg_work_tags wt
  join wnacg_volumes v on v.work_id = wt.work_id
  join wnacg_tags t on t.id = wt.tag_id
 where v.comic_id = $1;
-- 归档状态
select state from wnacg_archive where comic_id = $1;
```

## 2. Mangrab（Rust HTTP 服务，默认 :8080）

### REST（前缀 `/api`）

| 端点 | 方法 | 作用 |
|---|---|---|
| `/health` | GET | 健康检查 |
| `/login` · `/session` · `/auth/check` | POST/GET | 登录/会话 |
| `/sources` | GET | 站点列表与能力 |
| `/search` | GET | 站内搜索 |
| `/comic/:comic_id` | GET | 作品详情（含章节列表） |
| `/download/comic` · `/download/by-id` · `/download/task` | POST | 下单下载 |
| `/tasks` · `/tasks/:chapter_id` · `/tasks/:chapter_id/retry` · `/tasks/stats` | GET/POST | 任务查询/重试/统计 |
| `/export/comic/:comic_id` · `/export/list` | POST/GET | 手动导出 cbz |
| `/config` · `/config/current-source` · `/config/theme` | GET/PUT | 配置 |
| `/logs` · `/server/info` | GET | 日志/信息 |

认证：会话 cookie 或 `Authorization` 头；`/api/ws` 例外（WebSocket 不能带 header，靠同源 + 反代保护）。

### WebSocket 事件（`/api/ws`）

`{ "topic": "...", "payload": {...} }`，话题：

| topic | 含义 |
|---|---|
| `download-task-event` | 任务创建/更新（含进度、重试、错误） |
| `download-all-favorites-event` | 批量收藏下载进度 |
| `update-downloaded-comics-event` | 已下载作品列表变化 |
| `log-event` | 日志 |
| `task-snapshot-event` | 连上时的任务快照 |

### 产物

- 下载目录：`{site}/{comic_id}/{order}`（`dir_fmt` 可配）
- 导出：`{order}.cbz`，内含 `ComicInfo.xml` + 图片，同目录保留 `元数据.json`
- 自身状态：`data/mangrab_server.db`（SQLite：download_task / download_image）

## 3. Komga（本 fork，默认 :25600）

认证：HTTP Basic（API）或登录会话；首次 `POST /api/v1/claim` 创建管理员。

### 集成用到的端点

| 端点 | 方法 | 作用 |
|---|---|---|
| `/api/v1/claim` | GET/POST | 初始化管理员 |
| `/api/v1/libraries` | GET/POST/DELETE | 库管理（root 指向 `/content/<site>`） |
| `/api/v1/libraries/{id}/scan` | POST | 触发扫描（下载完成后调用） |
| `/api/v1/libraries/{id}/metadata/refresh` | POST | 整库刷元数据（注意：会一次性载入全部书籍） |
| `/api/v1/libraries/{id}/analyze` | POST | 整库分析（读包，按需） |
| `/api/v1/series` · `/api/v1/series/list` | GET/POST | 系列查询（支持 oneShot 过滤） |
| `/api/v1/series/{id}` · `/api/v1/series/{id}/books` | GET | 系列详情/书籍 |
| `/api/v1/books/{id}` · `/api/v1/books/{id}/pages` | GET | 书籍详情/页列表（首访触发按需分析） |
| `/api/v1/books/{id}/thumbnail` | GET | 封面（镜像目录或懒生成） |
| `/api/v1/books/{id}/read-progress` | PATCH | 阅读进度（Komga 自用） |
| `/api/v1/tasks` | POST/DELETE | 任务队列 |

### 本 fork 的扩展参数

| 参数 | 作用 |
|---|---|
| `--komga.lrr-path-mode.enabled=true` | LANraragi 路径模式：单行本一个文件一个系列、多卷跨 bucket 聚合 |
| `--komga.comic-meta.enabled=true` | 启用外部元数据库读取 |
| `--komga.comic-meta.db-url=...` | JDBC 地址（PostgreSQL） |
| `--komga.comic-meta.source-id=wnacg` | 站点表前缀（jm/pica/wnacg） |

## 4. CloudDrive2（:19798，Web UI）

- 115 挂载点：`<CLOUDDRIVE_MEDIA>/115open`（FUSE，容器内 `/media/115open`）
- 挂载由 Web UI 管理，配置文件在 `CLOUDDRIVE_CONFIG`（含账号 token，注意保密）
- 容器重建会留下 stale FUSE 挂载点，需 `umount -l` 后在 UI 重新挂载

## 5. 现有缺口（按接口级列出）

| 缺口 | 影响 | 最小改法 |
|---|---|---|
| **没有系统写 comic_meta** | 元数据/归档状态自 2026-10-07 起不再更新，34,203 条状态漂移 | A) Mangrab 增加 PG 写入（导入 `tokio-postgres`）；B) 独立同步器读 Mangrab SQLite + 目录回写 |
| **Mangrab 无 Komga 通知** | 下载完不会自动出现在 Komga | 加 `KOMGA_SCAN_URL` 配置，导出成功后 POST 扫描 |
| **Mangrab 无 comic_meta 状态回写** | 对账只能发现，不能自愈 | 同上 A/B |
| **Komga 库级刷新是整库载入** | 15 万本会 OOM | 改成分块任务，或依赖扫描时逐系列刷新 |
| **封面只在 comic_meta 里有 URL** | 列表无封面 | cover-service 下载到本地，Komga 读镜像目录 |

## 6. 对接约定（建议固化）

1. 目录：`/content/<site>/oneshots/<bucket>/<comic_id>.cbz` 与 `series/<bucket>/<seriesId>/<volume>.cbz`
2. 身份：`comic_id` = 文件名；任何新表/新接口都以它为主键
3. 写入方唯一：元数据与归档状态只有 Mangrab（或同步器）写，Komga 只读
4. 事件：下载完成 → 更新 comic_meta → POST Komga scan；三步失败要可重试、幂等

## 7. 按 ID 下载的契约（Mangrab）

这是目前唯一「面向脚本」的下载入口，也是接 comic_meta 的关键。

### 请求

```http
POST /api/download/by-id
Authorization: Bearer <JM_AUTH_TOKEN>
Content-Type: application/json

{ "comicId": "42409", "chapterId": "42409-1" }   // chapterId 可省略
```

- 只给 `comicId`：下载该漫画**所有未下载章节**
- 同时给 `chapterId`：只下这一章（忽略整本逻辑）
- `comicId` 就是站点 id，等于 comic_meta 的 `comic_id`、等于压缩包文件名

### 响应

```json
{
  "comicId": "42409",
  "comicTitle": "...",
  "createdChapters": ["..."],
  "skippedChapters": [],
  "alreadyRunningChapters": [],
  "conflictedChapters": [],
  "createdCount": 1
}
```

### 前置条件

- 站点已登录：`POST /api/login`，会话落在 `config.json`，账号密码落在 `credentials.json`（可自动重登）
- 服务端用 `utils::get_comic(comic_id)` **现场向站点拉详情**，所以断网/未登录时这个接口会失败
- 脚本鉴权：环境变量 `JM_AUTH_TOKEN`（未设置时启动随机生成并打印）；WebSocket 用 `?token=xxx`

### 数据流

```
POST /download/by-id
  → get_comic(comic_id)            拉取章节列表
  → create_download_task × N       每章一个任务
  → 下载图片到 {site}/{comic_id}/{order}/
  → 自动导出 {order}.cbz（ComicInfo.xml + 图片），同目录留 元数据.json
```

## 8. 缺口与建议的调度器

**缺口**：Mangrab 只提供「按 id 单本/单章下单」，没有「从 comic_meta 拉一批 pending 的 id 批量下单」的能力；也没有把结果写回 comic_meta。

**最小调度器**（脚本级，不用改 Rust）：

```text
loop:
  ids = SELECT comic_id FROM wnacg_archive WHERE state='pending' ORDER BY updated_at LIMIT 20
  for id in ids:
      POST /api/download/by-id {comicId: id}       # 失败则记日志，不阻塞
  wait for tasks to drain (GET /api/tasks/stats)
  reconcile --fix --komga-url ...                  # 回写 state=done + 通知 Komga
  sleep N
```

这样三种数据就形成闭环：comic_meta 的 pending 队列 → Mangrab 下载 → 115 → 状态回写 → Komga 可见。
唯一还缺的是「Mangrab 直接把 state 写成 done」的实时性（现在靠边车每轮补）。


实现见 `scripts/download-dispatch.py`（只负责下单，不写状态）：

```bash
PGPASSWORD=... ./scripts/download-dispatch.py --site wnacg --limit 20 \
  --mangrab-url http://127.0.0.1:8080 --mangrab-token "$JM_AUTH_TOKEN" [--dry-run]
```
