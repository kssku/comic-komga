# 三位一体拓扑（2026-11-18 实测）

> 只写现在真实成立的事。每条结论都标注了取证方式，改动前请重跑一遍
> scripts/verify-loop.sh（见 [ACCEPTANCE.md](ACCEPTANCE.md)）。

## 0. 2026-11-18 单机自包含改造

三位一体（mangrab + comic_meta + komga）现在**全部跑在 154 这一台机器上**，
不再依赖 NAS 或任何跨机链路：

| 原来 | 现在 |
|---|---|
| 154 → ssh NAS → rsync 写 115 | 154 的 mangrab 直接写本机 CF2 挂载 |
| 154 → NFS 读 115（需 remount 刷新） | 154 的 CF2 挂载直读 |
| 154 的 CD2 未挂载（/media 空） | 已挂 /media/115open（rw），150,752 文件可见 |
| reconcile 83 s（NFS 枚举） | reconcile 46 s（CF2 枚举） |

一圈实测（verify-loop.sh）：

    dispatch 2 本 → drain 36s → metrics → push(skip) → reconcile 46s → scan
    round finished，全部 PASS，约 82 秒

### CD2 修复要点

登录态本身是好的（oauth refresh-token 成功），真正缺的是 config.toml 里的挂载点声明：

    [[mount_points]]
    name = "115open"
    source_path = "/115open"
    mount_point = "/media/115open"
    read_only = false
    auto_mount = true

NAS 的 config.toml 有这段，154 的没有，所以 CF2 启动后 mount manager loaded
却没有任何挂载动作。

### 自包含后的路径

    115 云盘
      └── /content/wnacg/oneshots/<bucket>/<id>.cbz
            ↑                    ↑
      mangrab 直写          komga 直读
      archiveDir=           /content 挂载=
      .../115open/content   .../115open/content

    comic_meta  → 127.0.0.1:55432（本机容器）
    reconcile   → 直接读 .../115open/content/wnacg/oneshots

## 1. 部署拓扑

```text
┌────────────────── 115 云盘（唯一内容存储）──────────────────┐
│ /content/wnacg/oneshots/<bucket>/<id>.cbz   150,749 个      │
│ /content/{jm,pika,lrr-test}/                                │
└──────────▲──────────────────────────────────▲──────────────┘
           │ 写（rsync over ssh）               │ 读（直挂同一路径）
┌──────────┴───────────────────┐   ┌───────────┴────────────────────┐
│ NAS redchen /vol1/.../115open│   │ NAS komga-test :25600          │
│   content/  ◀── 154 的 rsync  │──▶│  library 0S8N9PPAQGWPG         │
│   NFS export(rw, all_squash) │   │  comic-meta ❌ 未启用（数字标题）│
└──────────▲───────────────────┘   └────────────────────────────────┘
           │ NFS4 → 154:/mnt/content（fstab 持久化）
┌──────────┴──────────────────────────────────────────────────────┐
│ 154 mx                                                          │
│  mangrab :8080 ─下载/打包─▶ /opt/mangrab/archive/wnacg ─┐        │
│  comic-meta-pg :55432 (comic_meta 副本, komga_ro/mangrab_rw)│    │
│  komga :25600  comic-meta ✅ / library root 不存在 ⚠️（空库）│   │
│  java komga :25602  e2e（/opt/e2e-lib, comic-meta ✅）     │    │
│  /opt/loop: dispatch → drain → metrics → push → reconcile ┘     │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│ redos (dsh) :3080 / :3084                                       │
│ /srv/docker/volumes/db-archive/pgdata 3.1G ← 生产 PG，容器未起   │
└─────────────────────────────────────────────────────────────────┘
```

## 2. 组件清单

| 组件 | 位置 | 端口 | 状态 |
|---|---|---|---|
| CloudDrive2（115 挂载） | NAS 容器 | host | ✅ 挂 /vol1/1000/docker/clouddrive2/media/115open |
| CloudDrive2（154） | 154 容器 | host | ⚠️ 挂载坏，154 不用它读写 115 |
| Mangrab | 154 进程（setsid） | 8080 | ✅ health ok |
| comic_meta | 154 容器 comic-meta-pg | 127.0.0.1:55432 | ✅ healthy，3.1G 副本 |
| Komga（接 PG） | 154 容器 komga | 25600 | ⚠️ comic-meta 通，但库 root 不存在 → 空库 |
| Komga（e2e） | 154 host java | 25602 | ✅ /opt/e2e-lib，comic-meta 通 |
| Komga（内容可读） | NAS 容器 komga-test | 25600 | ✅ 直挂 115 内容，❌ 未接 comic-meta |
| 生产 comic_meta | redos /srv/docker/volumes/db-archive | 55432 | ❌ 容器未启动 |
| 调度边车 | 154 /opt/loop | — | ✅ 手工跑，未接 cron |

## 3. 链路通断（本文档的重点）

| 链路 | 状态 | 取证 |
|---|---|---|
| 站点 → Mangrab 下载 | ✅ | 46 张 / 158 MB / 47.4 s，0.97 张/秒 |
| Mangrab → 本地 archive | ✅ | /opt/mangrab/archive 575 MB |
| 本地 → 115（rsync 增量） | ✅ | 2 个新文件 25 s；空转 1–2 s |
| 115 → 154 NFS 视图 | ✅ | 需 RECONCILE_REMOUNT，否则看不见新文件 |
| 磁盘 → archive.state=done | ✅ | reconcile 修复 5 行，done 150,749 |
| PG → Komga(154, :25600) | ✅ 配置 | --komga.comic-meta.enabled=true |
| PG → Komga(NAS, :25600) | ❌ 未启用 | 容器 command 里没有 --komga.comic-meta.* |
| Komga(154) 读内容 | ❌ | 库 root /opt/clouddrive2/media/... 在容器内不存在 |
| Komga(NAS) 读内容 | ✅ | ScanLibrary 3m05s 扫完 15 万文件 |
| 生产 PG 在线 | ❌ | redos 只跑 metacubexd/portainer/cloudflared/mihomo |

一句话：**内容线通在 NAS，元数据线通在 154，两条线还没在同一台机器上合上。**

## 4. 状态机与真实分布

```text
pending ──dispatch──▶ Mangrab 下载 ──文件落到 115──▶ reconcile ──▶ done
   ▲                                                        │
   └────────── rebuild-archive-state.py（按磁盘重写）◀────────┘
```

| state | 数量（154 副本，2026-11-18 03:43） |
|---|---:|
| done | 150,749 |
| pending | 182,518 |
| failed | 45,139 |
| permanent_failed | 8,268 |

## 5. 身份对照

```text
文件 42409.cbz
  ├── comic_id = 42409       ← comic_meta 主键、Mangrab API、文件名
  ├── book.name = 42409      ← Komga 查元数据的键
  └── lrr_id                 ← 历史 LRR id，已不参与任何链路

Komga book.id = sha1("<相对库根的路径>")
  例：sha1("oneshots/1-50000/1.cbz")
```

## 6. 缺口与修复顺序

| # | 缺口 | 影响 | 修法 |
|---|---|---|---|
| 1 | NAS Komga 未接 comic-meta | 阅读器标题是数字 | 给 komga-test 加 --komga.comic-meta.* 指向 154 的 PG（154 的 55432 当前只绑 127.0.0.1，需先放开或走隧道） |
| 2 | 154 Komga 库 root 不存在 | 154 实例空库 | root 改成容器内 /content/wnacg，或只在 NAS 上跑实例 |
| 3 | 生产 PG 未启动 | 生产只有副本 | redos 起 db-archive compose，先跑 rebuild-archive-state.py dry-run |
| 4 | 封面未落地 | 只有外链 | cover-service → artworkMirrorRoot |
| 5 | reconcile 全量枚举 83 s | 每圈最长一段 | 只枚举本轮新增（先别动，它是唯一纠偏环节） |
| 6 | 边车未接 cron | 每圈靠手工 | verify-loop.sh 通过后接 systemd timer |

缺口 1 是「三位一体」真正合体的最后一步：**把元数据送到已经能读内容的那个 Komga 上**。

## 7. 已知代价：Komga 在 NFS 上全量扫描极慢

154 的 Komga 库根已改为 /content/wnacg（容器内），实际落在 /mnt/content（NFS）。实测：

| 操作 | 耗时 |
|---|---|
| find 全树 150,749 个文件 | 1.76 s |
| Komga 首次全量扫描（NFS） | > 45 min（实测仍在跑） |
| NAS 同库扫描（CF2 直挂） | 3m05s |

原因不是磁盘慢，是元数据路径不同：

- find 用 readdir 批量拿目录项，几乎没有额外 RPC；
- Komga 的 Files.walkFileTree 对每个文件做多次 stat，NFS 不缓存元数据，
  每次 stat 一次 RPC（约 4 ms）；
- CF2 是用户态 FUSE，有本地元数据缓存，所以 NAS 上快一个数量级。

结论：**Komga 不适合跨 NFS 扫全库**。

- 测试机：接受一次性代价，把这次扫完即可；
- 生产：Komga 与 115 挂载放同一台机器（NAS），不要跨 NFS。

这也意味着：闭环（下载 → 落 115 → 回写 done）**不应该依赖 Komga 扫描**。
扫描是阅读层的后台动作，不是数据闭环的一环。

## 8. 测试机验收的两级

| 级别 | 内容 | 判据 |
|---|---|---|
| L1 数据闭环 | 下载 → rsync → reconcile → DB done | verify-loop.sh 全绿 |
| L2 阅读层 | Komga 能看到内容 + 标题来自 comic-meta | 抽查 book 的 metadata.title 是真标题 |

L1 每轮可跑（138 s）；L2 是低频验证，不进每轮循环。
