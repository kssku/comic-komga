# Komga Fork — 1.30.1

> 本仓库是 [gotson/komga](https://github.com/gotson/komga) 的个人 fork。
> 目的：让 Komga 在 **115 网盘（CloudDrive2 FUSE 挂载）** 上可用。
>
> 本文档只记录**代码与磁盘能证实的事实**。任何推测都明确标注为「待确认」。

## 0. 与上游的关系（2026-11-22）

- 基线：官方 **1.30.1**（`db2ad643`），fork 的改动叠在其上
- 合并脚本：`git merge upstream/master`，冲突 11 个文件，全部手工解
- 验证：`:komga:test` 1051 个用例，仅 2 个失败（`TaskProcessorTest`，升级前 master 上就挂，
  原因是 springmockk 的 mock 没替换真实 bean，与本 fork 无关）

### 0.1 升级已有库

fork 自己维护迁移，时间戳可能与稍后进入的上游迁移交错（fork `V20261122` vs 上游 `V20261002`）。
因此 `application.yml` 默认开了 `spring.flyway.out-of-order: true`，任务库的 Flyway
（`FlywayTasksMigrationInitializer`）也跟着这个开关走。全新安装不受影响。

### 0.2 合并后待重做

**单行本 / 多卷切换视图**：`next-ui/src/pages/libraries/[viewId]/series.vue` 上游已重构成
`ItemBrowser` + `asItems` 结构，fork 原来的「单行本用 BookCard 渲染」工具栏与卡片分叉
在合并时用了官方版本，**该视图暂时丢失**，需在新结构上重做。

---

## 1. 部署环境

| 项 | 值 |
|---|---|
| 节点 | Dell MX（hostname `mx`）|
| 挂载 | `/opt/clouddrive2/115open`（CloudFS, fuse）|
| 漫画根 | `/opt/clouddrive2/115open/content/` |
| Komga 库根 | `/opt/clouddrive2/115open/content/wnacg/`（填到 wnacg 层，见 §1.1 与 Hasher 注释）|
| 端口 | 25600 |

### 1.1 实际目录结构（2026-10-09 实测）

```
/opt/clouddrive2/115open/
├── content/                    ← 漫画本体
│   ├── jmacg/
│   ├── pika/
│   └── wnacg/
│       ├── oneshots/
│       │   └── 1-50000/13748.cbz
│       └── series/
└── （电视剧/电影/动漫/backup/bilibili 等无关目录）
```

> **注意**：旧文档记载的库根 `.../comic/wnacg/` **在磁盘上已不存在**。
> `.../comic` 这一层从未存在过。

### 1.2 当前部署（2026-11-20 实测）

| 项 | 值 |
|---|---|
| 节点 | Dell 154（`192.168.31.154`），测试机 |
| 编排 | `/opt/comic-stack/docker-compose.yml`，project `comic-stack` |
| 容器 | `komga`（eclipse-temurin:21-jre + `/opt/komga-test.jar`）、`comic-meta-pg` |
| 库挂载 | `/opt/clouddrive2/media:/content:ro`（115 CloudDrive2）|
| 封面镜像 | `/opt/lab/covers:/covers`（**可写**，生成时落盘）|
| 配置目录 | `/opt/komga-test/config` |
| 端口 | 25600 |

**关键启动参数**：

```
--komga.lrr-path-mode.enabled=true
--komga.comic-meta.enabled=true
--komga.comic-meta.source-id=wnacg,jm,pica
--komga.comic-meta.source-root=/lab
--komga.artwork-mirror-root=/covers
--komga.artwork-hash-base=/lab
--komga.artwork-mirror-only=true
```

> `source-root` 与 `artwork-hash-base` 必须指向**库根挂载点**。上表是实验环境的 `/lab`，
> 生产库建在 `/content` 下时改成 `/content`。

> ⚠️ **部署方式**：替换 jar 后必须 `docker restart komga`。`docker compose up -d` 在配置
> 没有变化时不会重建容器，bind mount 的 jar 换了也不会加载——这个坑踩过一次，整轮验证跑的都是旧代码。

---

## 2. 四项改造

分支 `master` 相对上游 `c7d353a7`（分叉基点）共 **9 个提交**。

### 2.1 SHA-1 相对路径哈希

**提交**：`2b1e76fa` + `f346b30d`

**问题**：上游用 XXH3-128 哈希**文件内容**。在 FUSE 挂载上，每本书都要读一遍全文，
全库扫描不可用。

**解法**：改为哈希**相对路径**，零 I/O。

```kotlin
// Hasher.kt:75-84
fun computePathHash(path: Path, libraryRoot: URL): String {
  val root = libraryRoot.toURI().toPath().normalize()
  val base = root.parent ?: root          // ← 取父目录，只此一处
  val relative = base.relativize(path.normalize()).toString()
  return computePathHash(relative)        // SHA1(UTF-8(relative))
}
```

**4 处调用点**均只传 `(book.path, library.root)`：

- `BookLifecycle.kt:128`
- `LibraryContentLifecycle.kt:176`
- `LibraryContentLifecycle.kt:306`
- `LibraryContentLifecycle.kt:383`

**与 LANraragi 互认的条件**：

| | 哈希输入 |
|---|---|
| LRR | `wnacg/<分片>/<文件>.cbz`（相对其 content 根）|
| Komga | `base.relativize(path)`，`base = libraryRoot.parent` |

两者相等的**前提是库根指向 `content/wnacg/`**。见 §4。

**迁移**：`V20260924120000__sha1_relpath_hash.sql` —— 清空 `BOOK.FILE_HASH`、
`MEDIA_PAGE.FILE_HASH`、`PAGE_HASH`、`PAGE_HASH_THUMBNAIL`；保留 
`SYNC_POINT_BOOK.BOOK_FILE_HASH`（须与 Kobo 设备同步）。

### 2.2 divina ZIP 零 I/O 条目枚举

**提交**：`a10e7aad`

**问题**：构建页面列表时，每个 ZIP 条目都要 `getInputStream` 读图片头取尺寸 ——
一本 200 页的 CBZ = 200 次网络往返。

**解法**：

- `ContentDetector.detectMediaTypeByName(fileName)` —— Tika 内存查表，零 I/O
- `ZipExtractor` 不再读流；`dimension` 恒为 `null`

**代价（已接受）**：

1. 阅读器无法预知页面尺寸 —— 本库 7,899,110 页全部同一格式，可接受
2. 按文件名判类型 —— 本库 `jpg` × 100%，文件名与内容一致，可接受
3. 加密 ZIP 会静默变 READY —— **已修**：检查中央目录 
   `generalPurposeBit.usesEncryption()`，抛 `IllegalStateException`，
   落到 `catch (ex: Exception)` → `ERROR` + `ERR_1008`，与上游一致

> 换到异构库（含 .avif/.jxl/无扩展名）**不能照搬此方案**。

### 2.3 懒缩略图模式

**提交**：`dd058334`

**问题**：入库扫描时抽取首页生成缩略图 —— 扫描路径上唯一读 cbz 本体的热点。

**解法**：`KomgaProperties.thumbnailMode`，**默认 `LAZY`**。

```kotlin
// KomgaProperties.kt:46
var thumbnailMode: ThumbnailMode = ThumbnailMode.LAZY

enum class ThumbnailMode { LAZY, AUTO }
```

- `LAZY`：入库不生成，首次请求时按需生成（`BookLifecycle.kt:242`）
- `AUTO`：上游行为

**环境变量**：`KOMGA_THUMBNAIL_MODE`

> ⚠️ **升级上游时的静默风险**：此 fork **默认行为已改**。若上游覆盖 
> `KomgaProperties.kt` 的这一行，默认会回到 `AUTO`，扫库立刻退化成逐本读 cbz。
> 合流时必须逐行核对此文件。

### 2.4 外部 ComicInfo.xml 导入

**提交**：`12b56af6` + `20efaccd`

**动机**：部分下载源把元数据放 cbz **同级**的 `ComicInfo.xml`，而非内嵌。

**开关**：`Library.importComicInfoExternalXml`，默认 `false`。
迁移：`V20261009120000__library_comicinfo_external_xml.sql`。

**核心逻辑**（`ComicInfoProvider.kt:202-207`）：

```kotlin
private fun readExternalComicInfo(book: BookWithMedia): ComicInfo? {
  val external = book.book.path.resolveSibling(COMIC_INFO)
  if (!Files.isRegularFile(external)) return null
  return Files.newInputStream(external).use { mapper.readValue(it, ComicInfo::class.java) }
}
```

外部文件优先于内嵌。测试：31 个全绿（新增 3 个）。

### 2.5 封面镜像树（内容寻址，独立于库）

**提交**：`36149db7`（路径寻址）、`5f1847d1`（镜像优先 + 启动自检 + mirror-only）、`8fbc193f`（生成写镜像）

**问题**：封面没有好地方放。放进漫画目录当 `cover.jpg`——oneshot 库下 Komga 直接跳过 series sidecar
（`getSeriesThumbnails` 里 `if (series.oneshot) return emptyList()`），32 万本等于白放；
存进 `THUMBNAIL_BOOK`——BLOB 列，按每本 18 KB 算是 6 GB，塞进 `database.sqlite`，且重建库即全丢。

**解法**：封面独立成一棵镜像树，键从**漫画相对路径**算出来。任何工具只要知道路径就能重算，
不需要映射表，也就不存在「表与文件不同步」这类问题。

```
key  = sha256(<artwork-hash-base> 之后的相对路径)[:16]
封面 = <artwork-mirror-root>/{key[:2]}/{key}.jpg
```

例：相对路径 `wnacg/oneshots/400001-450000/405530/405530.cbz`
→ key `fd2f18a53bc6d692` → `covers/fd/fd2f18a53bc6d692.jpg`。256 个分片目录，每片约 1300 张。

**读取顺序**（`BookLifecycle.getThumbnail`）：

1. `THUMBNAIL_BOOK` 里**用户产生**的选中行（`USER_UPLOADED` / `SIDECAR`）——用户意图优先，
   否则网页上「上传封面 / 选为封面」会被镜像里的生成图无声覆盖
2. 镜像树（生成封面的权威来源）
3. `THUMBNAIL_BOOK` 表里剩下的行（`GENERATED`，回退）
4. 旧 id 分片 `<mirrorRoot>/<bookId 前2字符>/<bookId>.jpg`（兼容旧布局）

三条都未命中且 `thumbnail.generate=false` → 返回空，**不打开压缩包**。

**写入**：`generateThumbnailAndPersist` 生成后**先写镜像**（临时文件 + ATOMIC_MOVE，避免半截文件被读到），
镜像未配置或不可写才回退数据库 BLOB。打开一本书时会触发这条路径（`AnalyzeBook` 的
`REFRESH_METADATA` 级联出 `GenerateBookThumbnail`），所以「打开一次就自动补全」是顺带的，
浏览网格永远不会触发。

**配置**：

| 配置 | 默认 | 说明 |
|---|---|---|
| `komga.artwork-mirror-root` | 空（禁用） | 镜像树根。**必须本地文件系统**，不能是 FUSE |
| `komga.artwork-hash-base` | 空 | 相对路径基准，等于库挂载点，如 `/content` |
| `komga.artwork-mirror-only` | `false` | true = 未命中不列 book 目录，直接返回空 |

> ⚠️ **两个参数必须成对配置**。`mirror-root` 设了但 `hash-base` 为空，只走旧 id 分片；
> 基准写错一个字符，整片封面静默 404。启动时会打一行 INFO（正常）或 WARN（配置错），
> 见 `LocalArtworkProvider` 的 init 块。

> ⚠️ **路径即主键**。bucket 宽度、oneshot 布局、站点目录名，任何一项改了，全部 key 失效。
> 迁移工具必须同时搬迁封面。

### 2.6 comic-meta 多源（按 book.path 选表）

**提交**：`30690f44`

**问题**：`komga.comic-meta.source-id` 原为单值，查询只看文件名（`WHERE comic_id = ?`）。
wnacg 与 jm 的数字 id 重叠严重（`1`、`10`、`28411`…），配成 wnacg 时 jm 的书会读到
**wnacg 那本的标题和 tags** —— 静默串数据，没有任何报错；另两站则永远拿不到元数据。

**解法**：

- `source-id` 支持逗号分隔多值或 `*`：`wnacg,jm,pica`
- 新增 `komga.comic-meta.source-root`（默认 `/content`），从 `book.path` 第一段解析站点
- `findBookMetadata` / `findKnownComicIds` 增加 site 维度；扫描时的批量检查按 site 分组，每站一条 `IN (...)`
- 单值配置下 `siteForPath` 直接返回该源，行为与改造前完全一致

### 2.7 LRR 路径模式：单行本与系列的判定

**测试提交**：`c5a206a1`（`LrrPathModeTest`，7 例，Jimfs 内存文件系统，2.3 秒）

```
<root>/oneshots/...                      → 单行本，每本一个 series，oneshot=true
<root>/series/<bucket>/<seriesId>/<vol>  → 系列，同一 seriesId 跨 bucket 聚合为一个
其他任意路径                              → 父目录 = 一个 series（上游默认行为）
```

- 单行本判定是「路径中**任意一段**等于 oneshots」，不限于第一层，所以库根填 `/content` 或
  `/content/wnacg` 都能识别。
- 系列判定要求 `seriesDir` 之后**恰好两段**（bucket + seriesId）再加文件名。
  `series/<seriesId>/<vol>` 这种缺 bucket 层的不会被聚合，退回父目录分组。
- LRR 模式真正的价值是**跨 bucket 聚合**：同一 seriesId 的章节被分散到不同 bucket 时，
  不开此模式会裂成多个 series。

> ⚠️ 关闭 LRR 模式时走的是 `pathString.contains(oneshotsDir)` —— **字符串包含**，
> `my-oneshots-archive` 这类目录名也会命中。生产环境保持 LRR 模式开启。

---

## 3. 构建与部署

```bash
# 编译
JAVA_TOOL_OPTIONS="-Xmx4g" ./gradlew :komga:compileKotlin --no-daemon

# 测试
./gradlew :komga:test --rerun-tasks

# 打包
JAVA_TOOL_OPTIONS="-Xmx4g" ./gradlew :komga:build -x test --no-daemon
```

### 3.1 ⚠️ 版本号曾兼任构建开关（1.0.0 出包踩坑）

**现象**：切到 `version=1.0.0` 后，`:komga:kspKotlin` 以 `PROCESSING_ERROR` 失败，
报 89 处上游 `@Deprecated` 为 error（`Deprecated code should be removed`），
分布在 `ReferentialV1Controller.kt`、`ReferentialDao.kt`、`SeriesController.kt` 等 10 个文件。
**均为上游代码，非本 fork 改动。**

**根因**：上游 `komga/build.gradle.kts` 把注解处理器的开关写成了版本号判断：

```kotlin
if (version.toString().endsWith(".0.0")) {
  ksp("com.github.gotson.bestbefore:bestbefore-processor-kotlin:0.2.0")
}
```

上游版本号一直是 `1.x.y`，条件**恒假**，processor 从未加载。
本 fork 改成 `1.0.0` 后条件**首次为真**，processor 在 Kotlin 2.4 下
把上游那些刻意保留的 `@Deprecated` 全判为 error。

**反直觉之处**：改版本号本身没错，错在版本号被当成构建开关用。
不先看 `build.gradle.kts` 就追 KSP/编译参数，会一路白追。

**修复**（提交 `4fcec1ec`）：判据与版本号解耦，默认关闭（与上游 1.x 实际行为一致）：

```kotlin
if (providers.gradleProperty("bestbefore").orNull == "true") {
  ksp("com.github.gotson.bestbefore:bestbefore-processor-kotlin:0.2.0")
}
```

需要该 processor 时显式传 `-Pbestbefore=true`。

**同轮修复**：上游从 kapt 迁到 ksp 时漏给 `kspKotlin` 声明对 `generateTasksJooq`
的依赖，Gradle 9 把隐式依赖升级为硬失败；已在 `tasks.whenTaskAdded` 中补 `dependsOn`。

**验证**：`./gradlew :komga:webuiCopyIndex :komga:nextuiCopyIndex :komga:bootJar :komga-tray:jar`
→ `BUILD SUCCESSFUL in 43s`，产物 `komga-1.0.0.jar` (117 MB) / `komga-tray-1.0.0.jar` (58 KB)。

---

## 4. ⚠️ 待确认：库根层级

**这是当前唯一悬空的关键项。**

```kotlin
val base = libraryRoot.parent
```

代入两种可能：

| 库根指向 | `base` | 相对路径 | 与 LRR 对得上？|
|---|---|---|---|
| `.../content/wnacg/` | `.../content/` | `wnacg/oneshots/1-50000/13748.cbz` | ✅ |
| `.../content/` | `.../115open/` | `content/wnacg/oneshots/1-50000/13748.cbz` | ❌ 多一层 |

**结论**：库根**必须**指向 `.../content/wnacg/`。

**但**：

1. 生产实例当前**未运行**（25600 无监听），`LIBRARY.ROOT` 无从读取
2. `.test-komga/` 里的库根是 `test/pika-format/`，**是测试实例，不是生产库**
3. `oneshots`/`series` 这层**已进入相对路径**，LRR 侧是否同构待查

**旧基准已失效**：早期文档记载的 1849 条对拍，对应 `comic/wnacg/` 旧结构，
该结构在磁盘上已不存在。**新结构下必须重做对拍。**

---

## 5. 上游同步清单

merge 上游时**必须逐行比对**：

| 文件 | 风险 |
|---|---|
| `infrastructure/hash/Hasher.kt` | 算法被覆盖 → 哈希全变 |
| `infrastructure/configuration/KomgaProperties.kt` | `thumbnailMode` 默认值被改回 AUTO |
| `domain/service/BookLifecycle.kt` | 调用点 + 懒缩略图判断 |
| `domain/service/LibraryContentLifecycle.kt` | 3 处调用点 |
| `domain/service/ComicInfoProvider.kt` | 外部 xml 逻辑 |
| `komga/src/flyway/resources/db/migration/sqlite/V20260924120000__sha1_relpath_hash.sql` | 迁移编号冲突 |

---

## 6. 已知遗留

| # | 事项 | 状态 |
|---|---|---|
| 1 | 库根层级待确认（§4）| ⬜ 未结 |
| 2 | 新旧结构哈希基准需重做 | ⬜ 未做 |
| 3 | 13 个 lucene 二进制在 `dd058334` 历史中 | ✅ 已决策：接受，不重写历史 |
| 4 | 外部封面 Provider（见 §2.5 / §6.1）| ✅ 已实现 |
| 5 | PR 未开 | ⬜ 未开 |

### 6.1 外部封面 Provider（已实现，见 §2.5）

浏览时读独立封面目录，cbz 只在阅读时读。路径规则：

```
{外部封面根}/{book.fileHash}.{ext}
```

**时序陷阱**：`fileHash` 由 `hashAndPersist` 异步补，受 library `hashFiles` 开关控制。
Provider 遇空 `fileHash` **必须返回空**，不得拼出 `/.jpg`。

**须先定三个参数**：封面根绝对路径、分片规则、扩展名查找顺序。

**分片是硬需求**：FUSE 上单目录十万项会让 `Files.list` 慢到不可用。

### 6.2 原设计的修订（2026-11-20）

上面 6.1 的原始设计用「{封面根}/{book.fileHash}.{ext}」，实现时改成了**路径哈希**：

| | fileHash（原设计） | 路径哈希（实际采用） |
|---|---|---|
| 何时可得 | 由 hashAndPersist 异步补，受 library hashFiles 开关控制 | 扫描到路径时同步就能算 |
| 空值风险 | Provider 必须判空，否则拼出 /.jpg | 不存在空值 |
| 文件变动 | 内容一变 hash 就变，封面跟着失联 | 路径不变就不变 |
| 外部工具可用 | 得先查到 book 行才知道 hash | 任何工具拿路径就能重算 |

「分片是硬需求」的结论没变，只是分片键从 id 换成了哈希前两位（256 个分片目录）。

**真正的时序陷阱挪到了别处**：镜像根必须是**本地**文件系统。当年踩过一次——
book.path 活在 CloudDrive2 的 FUSE provider 里，用它去 resolve 另一个挂载点下的路径，
isDirectory() 会**不发任何系统调用**直接返回 false，镜像树等于从未被探测。
现在统一用宿主默认文件系统解析（见 findMirroredBookThumbnail 的注释）。

---

## 7. 回退

| 层 | 手段 |
|---|---|
| 代码 | `git checkout master`（各改造分支独立）|
| 数据库 | `config/database.sqlite.bak-*`（迁移前快照）|
| 哈希 | 迁移只清空不转换，重扫即恢复 |

---

## 8. 版本

**1.1** —— 2026-11-20

- 部署迁到 Dell 154，Komga 跑在 `comic-stack` compose 里（§1.2）
- 新增 §2.5 封面镜像树（内容寻址）、§2.6 comic-meta 多源、§2.7 LRR 路径模式测试
- 封面生成改为**写镜像树**，不再存 `THUMBNAIL_BOOK` 的 BLOB；镜像优先于数据库行
- 打开书籍时自动补封面（`AnalyzeBook` → `REFRESH_METADATA` → `GenerateBookThumbnail`），浏览不触发
- `LrrPathModeTest` 7 例全绿；修好 `SeriesLifecycleTest` 的 MockK 类型推断，测试代码重新可编译

**1.0** —— 2026-10-09

- master @ `480d82ee`
- 4 项改造全部落地，编译通过，31 测试全绿
- 库根层级待确认（§4）

