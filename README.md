# Komga（个人独立分支）

> 为 **115 网盘 / CloudDrive2 FUSE 挂载** 场景改造的 Komga。
> 读一棵三站（wnacg / jm / pica）归档树，封面走独立的本地镜像，元数据来自 PostgreSQL。
>
> **不是上游的补丁集**，与 [gotson/komga](https://github.com/gotson/komga) 各自演进。
> 上游的 Discord / OpenCollective / Weblate 与本分支无关，请勿打扰上游维护者。

## 它在这个栈里的位置

| 组件 | 角色 |
|---|---|
| **comic-komga**（本仓库） | 扫描并阅读那棵归档树 |
| `comic-mangrab` | 下载、打包，落成 `/content/{site}/oneshots/{bucket}/{id}/{id}.cbz` |
| `comic-database` + `comic-meta-pg` | 提供标题与标签，按 `site/comic_id` 查，不打开压缩包 |
| `/covers` | 封面镜像，键由漫画相对路径算出（见下） |

上游假设漫画在本地磁盘上。这里的数据在 115 网盘上，经 FUSE 挂载：**每次 stat 都是一次网络往返**，
单个目录可能有十万个文件。所有改造都是围绕这一点。

## 为什么需要改

| 问题 | 上游行为 | 本分支 |
|---|---|---|
| 扫描时读压缩包 | 开 CBZ 抽首页、读图片尺寸 | **懒模式**：扫描零 I/O，打开阅读器时才分析 |
| 缩略图存数据库 BLOB | 每本一条，32 万本约 6 GB，重建库即丢 | **封面镜像树**：`sha256(相对路径)[:16]` 分片，文件在本地，浏览零流量 |
| 文件身份靠内容哈希 | 每次读整个文件 | **`SHA1(相对路径)`**，零 I/O，与 LANraragi 逐字节互认 |
| 元数据只能读 CBZ 内嵌 ComicInfo | 每本一次网络读 | **外置 PostgreSQL**，按 `site/comic_id` 查标题/标签，不碰压缩包 |
| 单行本/系列靠父目录 | 跨 bucket 的系列会裂成多个 | **LRR 路径模式**：`oneshots/` 与 `series/<bucket>/<id>/` 显式判定并跨 bucket 聚合 |
| divina ZIP 逐条目读 | 每个条目一次 `getInputStream` | 按条目名判类型 + 读中央目录尺寸，零 I/O 枚举 |

每项的完整设计与验证记录见 [`docs/FORK.md`](./docs/FORK.md)。

## 封面怎么工作

封面**不在归档目录里**，而是独立一棵树：

```
key  = sha256(<artwork-hash-base> 之后的相对路径)[:16]
封面 = <artwork-mirror-root>/{key[:2]}/{key}.jpg

例：wnacg/oneshots/400001-450000/405530/405530.cbz
  → fd2f18a53bc6d692  →  /covers/fd/fd2f18a53bc6d692.jpg
```

读取顺序：镜像树 → `THUMBNAIL_BOOK` 表 → 旧 id 分片。打开一本书时若缺封面，
会顺手生成一份写进镜像树（浏览网格永远不触发，因为那才是流量的来源）。

## 关键启动参数

```
--komga.lrr-path-mode.enabled=true
--komga.comic-meta.enabled=true
--komga.comic-meta.source-id=wnacg,jm,pica
--komga.comic-meta.source-root=/content
--komga.artwork-mirror-root=/covers
--komga.artwork-hash-base=/content
--komga.artwork-mirror-only=false
```

| 参数 | 说明 |
|---|---|
| `artwork-mirror-root` | 封面镜像根。**必须本地文件系统**，不能是 FUSE |
| `artwork-hash-base` | 相对路径基准，指向库根挂载点。两者必须成对配置 |
| `artwork-mirror-only` | `true` = 未命中不列 book 目录。封面铺满后再开 |
| `comic-meta.source-id` | 支持多值 `wnacg,jm,pica`，按 `book.path` 第一段选表 |

## 部署

裸 jar + docker compose，端口 25600（见 `docs/创建书库操作手册.md` 与 `docs/FORK.md` §1.2）。

```bash
# 编译与打包
./gradlew :komga:bootJar

# 部署：替换 jar 后必须 restart
docker restart komga
```

> ⚠️ `docker compose up -d` 在配置未变时**不会重建容器**，bind mount 的 jar 换了也不会加载。
> 这个坑踩过一次，整轮验证跑的都是旧代码。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/FORK.md`](./docs/FORK.md) | 每项改造的设计、验证、回滚；当前部署 |
| [`docs/创建书库操作手册.md`](./docs/创建书库操作手册.md) | 建库请求全字段、远程挂载注意事项 |
| [`docs/PLAN.md`](./docs/PLAN.md) | 重做总纲与阶段计划 |

上游官方文档（komga.org）描述的是上游版本，与本分支的改动不完全一致。

## 上游致谢

[![Jetbrains_logo](./.github/readme-images/jetbrains.svg)](https://www.jetbrains.com/?from=Komga)

Thanks to JetBrains for the development environment, and to [Chromatic](https://www.chromatic.com/)
for visual testing. The Komga icon is based on an icon by [Freepik](https://www.freepik.com/home).
