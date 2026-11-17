# 三位一体拓扑（数据流 / 状态机 / 身份）

> 一张图看清：谁写谁读、状态怎么走、id 怎么对。

## 1. 部署拓扑

```text
                       ┌────────────────────────────────────────┐
                       │        115 云盘（唯一内容存储）          │
                       │  /content/wnacg/oneshots/<bucket>/     │
                       │        <comic_id>.cbz                  │
                       └───────▲──────────────────▲─────────────┘
                               │写                  │读
                    ┌──────────┴────────┐  ┌──────┴──────────┐
                    │ CloudDrive2 (154) │  │ CloudDrive2(生产)│
                    │  /opt/clouddrive2 │  │  /vol1/.../media │
                    └──────────▲────────┘  └──────┬──────────┘
                               │                  │
        ┌──────────────────────┴──────────────────▼───────────────────┐
        │                         服务层                               │
        │   Mangrab :8080                      Komga :25600            │
        │   搜索/下载/打包/落盘                  浏览/阅读/进度          │
        └──────────┬───────────────────────────────▲─────────────────┘
                   │写                             │读（只读角色）
        ┌──────────▼───────────────────────────────┴─────────────────┐
        │              元数据层：comic_meta (PostgreSQL :55432)        │
        │   <site>_volumes (comic_id, title, cover, page_count, ...)   │
        │   <site>_archive (comic_id, state, downloaded, retry_count)  │
        │   <site>_works / _tags / _work_tags / _categories            │
        └──────────▲───────────────────────────────┬─────────────────┘
                   │写（消费 pending 队列）           │写（对账修复）
        ┌──────────┴──────────┐         ┌──────────▼─────────────────┐
        │  download-dispatch  │         │  reconcile-comic-meta      │
        │  pending → /by-id   │         │  state 校正 + 通知扫描       │
        └──────────┬──────────┘         └──────────┬─────────────────┘
                   │ POST /api/download/by-id      │ POST /api/v1/libraries/{id}/scan
                   ▼                               ▼
              (Mangrab)                        (Komga)
```

## 2. 读写矩阵（谁写、谁读）

| 数据 | 写入方 | 读取方 |
|---|---|---|
| cbz 文件 | Mangrab（经 CloudDrive2） | Komga、CloudDrive2 客户端 |
| 标题/标签/页数/封面 URL | 一次性抓取（2026-10），目前**无人持续写** | Komga |
| 归档状态 state | dispatcher 消费、reconciler 修正；**Mangrab 不写** | dispatcher、reconciler |
| 阅读进度 | Komga | Komga 前端 |
| 封面文件 | cover-service（未做） | Komga（镜像目录） |

## 3. 状态机

```text
                    ┌──────────┐
                    │ pending  │◀──────────────┐
                    └────┬─────┘               │ 缺失时回退（未实现）
                         │ dispatcher 下单      │
                         ▼                     │
                  Mangrab 下载中（DB 无状态）    │
                         │ 文件出现在 115       │
                         ▼                     │
                    ┌──────────┐               │
                    │ reconciler│──判定通过──▶ ┌──────────┐
                    └──────────┘              │   done   │
                         │ 判定失败             └────┬─────┘
                         ▼                          │ 文件消失
                    ┌──────────┐                    ▼
                    │  failed  │              （当前未处理：约 16 万条
                    └──────────┘               标 done 但文件不存在）
```

真实分布（154 副本，修复后）：

| state | 数量 | 说明 |
|---|---:|---|
| done（在 115 上） | 150,721 | 全部真实存在 |
| done（文件缺失） | ~126k~160k | LRR 时期清理掉的，DB 仍记 done |
| pending | ~5.6 万 | 从未下载 |
| failed / permanent_failed | 4.5 万 / 8.3 千 | 抓取失败 |

## 4. 身份对照

```text
文件 42409.cbz
  ├── comic_id = 42409      ← comic_meta 主键、Mangrab API、文件名
  ├── book.name = 42409     ← Komga 查元数据的键
  └── lrr_id = f1ec…        ← 历史 LRR id，与下面这个无关

Komga book.id = sha1("wnacg/oneshots/1-50000/1.cbz") = 4ee39cf0…
```

## 5. 实际运行位置

| 组件 | 154（测试） | 飞牛（生产） |
|---|---|---|
| 115 挂载 | CloudDrive2 /opt/clouddrive2/media | CloudDrive2 /vol1/.../media |
| comic_meta | PG :55432（3.1G pgdata 副本） | 原 pgdata（待部署） |
| Komga | :25600，150,721 本已扫，2m42s | 未部署 |
| Mangrab | 未运行（无镜像） | 已有容器/数据（qm 系列在同机） |
| 边车 | 手工跑 | 待接 cron/systemd |

## 6. 缺口（按闭环顺序）

```text
[1] 元数据不再更新 ── 2026-10 之后无人写 volumes/title
[2] Mangrab 不写 state ── 下载完 → 只能等边车补（分钟级）
[3] 16 万条 done 但文件缺失 ── 队列口径未定：忽略 / 回退 pending / 选择性
[4] 封面只有外链 ── 未下载到本地
[5] Komga 库级刷新是整库载入 ── 15 万本会 OOM，需分块
```

## 7. 一次完整流转（理想态）

```text
1. comic_meta 里出现 pending 的 comic_id
2. dispatcher 取 N 个 → POST /api/download/by-id
3. Mangrab 拉站点详情 → 下载图片 → 导出 cbz → 写 115
4. 下一轮 reconciler：文件在 + 页数一致 → state=done → POST Komga scan
5. Komga 扫描（零 I/O）+ 按 comic_id 查 comic_meta → 列表出现真标题
6. 用户打开书 → 首次触发按需分析（读一次包）→ 封面生成 → 之后走缓存
```


## 8. 2026-11-17 状态重写（以 115 实际文件为准）

按“115 上真实存在的文件”重写 `wnacg_archive`：

```text
前：done 310,967 / pending 22,295 / failed 45,139 / permanent_failed 8,268
后：done 150,721 / pending 182,541 / failed 45,139 / permanent_failed 8,268
```

规则：

- 文件在 115 上 → `state=done`
- 原来 done 但文件不在 → `state=pending`（进入待下载队列）
- failed / permanent_failed 保持不动（历史尝试结果）

脚本：`scripts/rebuild-archive-state.py`（默认 dry-run，`--apply` 才写）
备份：应用前 `pg_dump -t wnacg_archive` 到 `/tmp/wnacg_archive_before_rebuild.sql`

注意：本次只重写了 154 上的副本。生产 pgdata 尚未应用，部署时用同一脚本再跑一次即可（幂等）。
