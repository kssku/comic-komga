# 三位一体集成架构（Mangrab + comic_meta + Komga）

> 状态：设计草案（2026-11-17）。代码未改动，仅固化结论。
> 相关：`DESIGN-lrr-komga-storage-alignment.md`（Komga 侧的 LRR 路径模式）

## 1. 三套系统与它们的现状

| 系统 | 职责 | 存储 |
|---|---|---|
| **Mangrab**（comic-downloader-platform，Rust） | 搜索 / 下载 / 打包 cbz / 落盘 115 / 写归档状态 | 自己的 SQLite（download_task） |
| **comic-database**（comic_meta，PostgreSQL） | 元数据与归档状态的权威库 | PostgreSQL 16 |
| **Komga**（本 fork） | 浏览 / 阅读 / 进度 | SQLite（Komga 自用） |

数据链路：

```
Mangrab 下载 → 打包 cbz（内含 ComicInfo.xml + 元数据.json）→ 115
            ↘ 写 comic_meta（volumes 标题/标签/页数/封面 URL，archive.state）
Komga 扫描 115（零 I/O）→ 元数据按 comic_id 查 comic_meta（不打开压缩包）
```

## 2. 已确认的事实

- `comic_meta.wnacg_volumes` 的 `comic_id` **就是压缩包文件名**（`42409.cbz` → `42409`）
- Komga 侧 `book.name` 同样等于它，因此元数据查询无需路径哈希
- `lrr_id` 与 Komga 的 `sha1(相对路径)` book id **不是同一个值**
  （例：`comic_id=1` 的 `lrr_id=f1ec93ec…`，Komga book id=`4ee39cf0…`）
- 包内 `ComicInfo.xml` 的 `<Series>` 对單行本是**作者**、对同人誌是**汉化组**，
  不能直接当 Komga 的系列名；真书名在 `<Title>`，comic_meta 的 `title` 同样可用
- 对账结果（115 上 150,721 个文件 vs comic_meta）：

  | 状态 | 数量 |
  |---|---:|
  | done | 116,517 |
  | pending | 34,196 |
  | failed | 7 |
  | 无记录（`14839(1)`） | 1 |

  页数与 DB 一致（抽样 3 本：66/192/192 页），标题为空 8 本

## 3. 决策

### D1. 数据库不合并，comic_meta 是唯一元数据源

Komga 保留 SQLite（阅读进度、缩略图、搜索索引），只从 comic_meta **只读**取元数据。
理由：内容在云盘、元数据只读、进度天然独立，没有跨系统事务需求；
改 Komga 到 PostgreSQL 需要补一整套 PG 迁移（本 fork 只有 sqlite 迁移），收益低。

预留服务化：若将来 comic_meta 不方便让 Komga 直连，改为 Mangrab 暴露
`GET /api/metadata/{comic_id}`，Komga 改走 HTTP（现有 `ComicMetaRepository` 即替换点）。

### D2. 对账默认只出报告，自动修复只限安全类别

- `reconcile` 默认 dry-run，输出差异清单
- 自动修复只允许：**文件存在 + state ∈ {pending, failed} + page_count 与文件实际页数一致**
  → 改为 done（需显式 `--fix`）
- 其余差异（无元数据、无文件、页数不符、文件名异常）只报告，人工处理

### D3. 封面由 cover-service 下载到本地缓存，Komga 读本地

- comic_meta 里的 `cover` 是外链（wnimg CDN），直接让 Komga 代理会受 CDN 可用性影响
- cover-service 下载并写入本地/NAS 的封面目录，Komga 通过现有 `artworkMirrorRoot`
  分片目录读取（无需打开压缩包，也不需要外网）
- 外链仅作兜底

## 4. 身份规范

**canonical id = `comic_id`（站点 id = 文件名）**

| 身份 | 用途 |
|---|---|
| `comic_id` | 唯一 join key：文件名 / comic_meta 主键 / Komga book.name |
| `lrr_id` | 历史兼容，不参与 join |
| Komga `book.id`（sha1 相对路径） | Komga 内部标识；改目录结构会变，属已知风险 |

## 5. 数据归属

| 数据 | 唯一写入方 | 读取方 |
|---|---|---|
| cbz 文件 | Mangrab（经 CloudDrive2） | Komga |
| 标题 / 标签 / 页数 / 封面 URL | Mangrab → comic_meta | Komga |
| 归档状态 `_archive.state` | Mangrab | 对账任务 |
| 封面文件 | cover-service | Komga |
| 阅读进度 / 书签 | Komga | 前端 |

## 6. 对账任务（P0）

输入：115 目录清单、comic_meta、Komga 已入库书籍。
输出四类差异：

1. 有文件、无 volume/archive 记录
2. 有文件、`state` 非 done（细分 pending / failed）
3. 有元数据 done、无文件（应回落 pending）
4. 页数不符（cbz 内图片数 ≠ `page_count`）——只在抽样或显式要求时全量检查

实现：`scripts/reconcile-comic-meta.py`，默认 dry-run，`--fix` 才写库，
写库前打印将要修改的行数与样例。

## 7. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 对账脚本 + dry-run 报告；状态回填（人工确认后） | 差异可解释、无 orphan |
| P1 | 元数据单源：禁用包内 ComicInfo 回退（可配置开关） | 全库标题来自 comic_meta |
| P2 | 封面导入（cover-service → 本地 → Komga 镜像目录） | 列表有封面、零外网依赖 |
| P3 | 阅读进度对外只读 API（可选） | 前端/其他系统可读进度 |

## 8. 未决

- 对账频率（建议每日一次，低谷时段）
- cover-service 的缓存目录与 Komga `artworkMirrorRoot` 的路径约定
- 多站点（jm / pica）接入时，`comic_id` 是否需要站点前缀（当前靠目录区分）
## 9. 落地：deploy/integrated

一套 compose（deploy/integrated/docker-compose.yml）装四个服务：comic-meta-pg、clouddrive2、
mangrab、komga。

- 主数据库 = comic_meta（PostgreSQL），Komga 只读，Mangrab 读写
- initdb/01-roles.sh 首次初始化时创建 komga_ro（只读）与 mangrab_rw（读写）
- 库根目录约定为 /content/<站点>（如 /content/wnacg）
- 下载完成后由 scripts/komga-scan-notify.sh 触发 Komga 扫描（也可配 KOMGA_SCAN_URL）

已验证：docker compose config -q 通过（Compose v2.26）。全栈起停尚未在 154 上实跑。
