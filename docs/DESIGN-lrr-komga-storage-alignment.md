# 网盘漫画库：LRR 与 Komga 的存储语义对齐设计

> 目标读者：同时维护 `lrr-custom` 与 `komga-clouddrive` 的人（含下一次会话的 AI）。
> 本文只描述**设计**与**约束**，不描述尚未实现的代码。所有引用的代码位置均已核对。

---

## 0. 一句话结论

两个 fork 各自独立地解决了同一个问题——**在 CloudDrive2/115 这类高延迟 FUSE 挂载上，如何让十万级漫画库可扫描、可翻页、可打开**——但解法的**切面不同**：LRR 把成本压到「入库零文件读取」，Komga 把成本压到「扫描零分析、打开即惰性解析」。两者在**ID 语义**上已经意外收敛（都是路径哈希），在**系列聚合语义**上则完全分叉（LRR 靠路径正则，Komga 靠目录即系列）。本文给出：哪些语义必须对齐、哪些允许分叉、以及分叉处的转换规则。

---

## 1. 两边的事实基线

### 1.1 LRR（`lrr-custom`）

| 维度 | 事实 | 来源 |
|---|---|---|
| 归档 ID | `SHA1(UTF-8(<来源>/<分片>/<文件>.cbz))`，即 `content/` 相对路径哈希 | `FORK_CHANGES.md` §7 |
| 入库 I/O | 零文件内容读取，判定全靠路径 | §1.1、§9、§15 |
| 系列聚合 | 路径正则 `<series_dir>/<bucket>/<series_id>/<volume>.cbz`，尾部锚定 | §15 |
| 聚合幂等 | `LRR_SERIES_MAP`（db0 hash）缓存 `series_key -> tank_id`，`hsetnx` 抢占 | §15 |
| 跨库隔离 | `tankoubon_library_roots` 取最长匹配库根 → `<library>:<series_id>` | §15 |
| 分页 | `arcids_idx` zset，单调递增 score，永不复用 | §3 |
| 首扫 | `readdir` 而非 `File::Find`，避免逐条目 `-d` | §9 |
| 缩略图 | 列表页不主动生成；打开阅读器只生成封面 | §10、§12 |

### 1.2 Komga（`komga-clouddrive`）

| 维度 | 事实 | 来源 |
|---|---|---|
| Book ID | `SHA1(相对库根父目录的路径)`，对齐 LRR | `FileSystemScanner.kt:70-74` |
| 扫描 | `Files.walkFileTree` + `FOLLOW_LINKS`，目录即 Series | `FileSystemScanner.kt:90-115` |
| 页列表 | 只读 ZIP 中央目录，`setIgnoreLocalFileHeader(true)`，**不读本地头、不探测尺寸** | `ZipExtractor.kt:23-60` |
| 分析时机 | 扫描时惰性（`MEDIA.status = UNKNOWN`），打开书籍时才 `analyzeOnDemand` | `BookController.kt:450-465` |
| 页列表缓存 | `BookPageListResolver` + `BookPageMemoryCache`，**无 DB 读写** | `BookPageListResolver.kt:1-40` |
| 单飞 | `inflight` 用 `putIfAbsent` 而非 `computeIfAbsent`，避免桶锁住慢读 | `BookPageListResolver.kt:41-56` |
| 失败语义 | 解析失败返回空列表而非抛异常（降级可渲染） | `BookPageListResolver.kt:60-70` |
| 单文件读取 | 中央目录快路径失败才退回本地头慢路径 | `ZipFileUtils.kt:14-34` |

---

## 2. 必须对齐的三条语义

### 2.1 ID 必须是「相对于某个稳定根的路径哈希」

**规则**：`id = SHA1(UTF-8(relpath))`，其中 `relpath` **不含**任何绝对路径成分、不含挂载点、不含机器名。

- LRR 的根是 `content/`（经 `get_userdir()` 求值，处理 `LRR_DATA_DIRECTORY` 覆盖）。
- Komga 的根是「库根目录的父目录」。这是个**刻意的偏移**：取父目录使得 `Series/Book.cbz` 的相对路径形如 `Series/Book.cbz`，与 LRR 的 `<来源>/<分片>/<文件>.cbz` 结构同构。

**约束**：
- 换挂载点、换机器、换 content 根目录时，ID **必须**不变。这是两边都已实现的性质，任何改动都不得破坏。
- 非 ASCII 文件名一律先 UTF-8 编码再哈希。两边实现一致，所以同一个文件在 LRR 与 Komga 里算出**同一个 ID**。
- 不要引入「文件内容哈希」作为主键。内容哈希在 FUSE 上是数百毫秒/文件，等于把扫描时间乘以页面数。

**由此得到的免费收益**：LRR 与 Komga 对同一批文件的 ID 可以直接互查。做迁移、做对账、做去重，都不需要映射表。

### 2.2 扫描阶段不得读归档内容

**规则**：扫描只允许 `stat` 级别的 I/O（目录项、文件大小、mtime）。归档内部结构只在**首次需要展示**时读取。

- LRR：入库路径上完全不打开归档（§15「判定纯靠路径，归档本体从不打开」）。
- Komga：扫描时 `MEDIA.status = UNKNOWN`；`getBookPages` 首次调用才 `analyzeOnDemand`，且 `BookPageListResolver` 明确「不读不写 MEDIA/MEDIA_PAGE」。

**约束**：
- 任何「扫描时顺便读一下封面/尺寸/页数」的改动都是回退，必须拒绝。
- Komga 侧 `ZipExtractor` 的 `analyzeDimensions` 参数**故意不消费**：读尺寸必须打开每个条目，正是要避免的事。尺寸只是展示提示，缺失可接受。

### 2.3 单飞（single-flight）必须在慢 I/O 之外加锁

**规则**：合并并发重复请求时，锁的粒度必须小于「整个慢操作」。

- Komga 的教训写在代码注释里：`computeIfAbsent` 的映射函数在桶锁内执行，而函数体是数秒的远端归档读取，会把无关书籍串行化。改用 `putIfAbsent` 手动认领。
- LRR 的教训：`hget -> create -> hset` 是 read-modify-write 竞态，两个入库线程各建一个 Tank，后写覆盖前者，产生孤儿。改用 `hsetnx` 原子抢占 + 哨兵 `__PENDING__` + 输家轮询/接管。

**约束**：两边新写的任何「检查-然后-创建」路径，都必须回答：两个线程同时到达会怎样？

---

## 3. 允许分叉的两条语义

### 3.1 系列（Series / Tankoubon）的聚合规则

这是两边**唯一实质性的语义分叉**，且不应强行统一。

| | LRR | Komga |
|---|---|---|
| 什么是系列 | 路径正则命中 `<series_dir>/<bucket>/<series_id>/` | 文件所在的**直接父目录** |
| 系列名 | `<series_id>` 目录名，**原样取值** | 目录名（空则用完整路径） |
| 单本（oneshot） | `<oneshot_dir>` 下保持独立 | 目录下无兄弟文件时的语义 |
| 跨库同名 | `tankoubon_library_roots` 最长前缀匹配隔离 | 天然隔离：系列属于库 |

**为什么不该统一**：LRR 的系列是**事后聚合的虚拟实体**（一个 Tankoubon 可以横跨多个归档目录），Komga 的系列是**文件系统的直接投影**。把 LRR 的路径正则搬进 Komga，会破坏「目录即系列」这个 Komga 全部 API（OPDS、Kobo 同步、readlist）所依赖的不变式；反过来把 Komga 的规则搬进 LRR，则会让 §15 的自动 Tankoubon 失去意义。

**分叉处的转换规则**（需要跨系统对账时使用）：

```
Komga.Series  ≡  LRR 的「系列目录」
Komga.Book    ≡  LRR 的「归档」
LRR.Tankoubon ⊃  Komga.Series        （一对多，跨目录聚合）
```

因此：**Komga → LRR 方向是单射**（每个 Komga 系列对应一个 LRR 系列目录），**LRR → Komga 方向是一对多**（一个 Tankoubon 展开成多个 Komga 系列）。任何双向同步都必须以 Book 为最小单位，不能以 Series 为单位。

### 3.2 分页索引的维护方式

- LRR 用显式 zset `arcids_idx`，因为它要支持 `?start=N` 且顺序必须稳定（`KEYS` 在十万级键上是秒级 + 数 MB 传输）。
- Komga 用数据库 + Spring Data 分页，无需额外索引。

**约束**：LRR 侧该索引**不能**命名为 `LRR_*` 前缀——Minion 会 MOVE-drain 该前缀的键。这是个容易踩的坑，已在 §3 记录。

---

## 4. 两侧共用的设计原则（建议写进各自的维护契约）

1. **路径是唯一真相**。ID、系列归属、封面来源，都从路径推导；不引入需要额外存储来维持的派生状态。LRR 的 `LRR_SERIES_MAP` 是例外，但它是**缓存**而非真相——缺失时可重建。
2. **惰性是默认，不是优化**。扫描不分析、列表不缩略图、打开才解析。任何提前做的工作都要说明它换来了什么。
3. **降级优于报错**。Komga 的页列表解析失败返回空列表；LRR 的缩略图失败有熔断器。FUSE 挂载会间歇性抽风，可渲染的降级状态比 500 有用。
4. **索引缺失必须自愈**。LRR 的 `arcids_idx` 缺失时回退 `KEYS` 并就地重建；Komga 的 `BookPageMemoryCache` 缺失只是缓存未命中。新部署不该需要手工迁移脚本才能用。
5. **慢 I/O 不进临界区**。

---

## 5. 已知未决问题

| # | 问题 | 影响 | 建议方向 |
|---|---|---|---|
| 1 | Komga 的 `libraryRootUrl` 取「库根的父目录」，而 LRR 取 `content/`。若两边的挂载布局不是同构（例如 Komga 库根直接指向 `content/<来源>`），同一文件的 ID 会不一致 | 跨系统对账失败 | 明确部署布局：Komga 库根必须指向 `content/` 的**下一级**（即某个 `<来源>`），使其父目录恰好是 `content/` |
| 2 | LRR 禁用启动时 `build_stat_hashes`（§8）后，新增归档的搜索索引不自动更新；而 Komga 的惰性分析是**按书触发**的。两者的一致性窗口不同 | 跨系统搜索对账时会有时间差 | 以 Book 的 `fileLastModified` 为对账锚点，不依赖索引新鲜度 |
| 3 | `BookPageListResolver` 失败返回空列表，与「书确实没有页」不可区分 | 用户看到空白页无从判断是坏了还是空的 | 至少在返回空列表时记 warn 日志（现已记 book id），前端可加「重试」入口 |
| 4 | LRR 的 `TANK_<10位>` id 宽度是硬约束（枚举正则 `TANK_??????????`），Komga 的 book id 是 TSID | 跨系统映射表需同时容纳两种 id 形态 | 映射表以 `(system, id)` 复合键存储，不做 id 空间合并 |
| 5 | 两侧都假设「路径稳定」，但网盘侧可能发生服务端重命名/迁移（115 的目录整理） | 路径一变 ID 全失效，且**无内容哈希可回退** | 这是路径哈希方案的固有代价。缓解：入库后禁止在盘上重命名；或引入可选的内容哈希作为**辅助**列（不替换主键），代价是重扫 |

---

## 6. 落地检查清单

改动任一侧的扫描/ID/系列逻辑前，逐条确认：

- [ ] 换挂载点后，同一文件的 ID 是否不变？
- [ ] 扫描路径上是否新增了任何归档内部的读取？
- [ ] 新增的「检查-然后-创建」是否有并发保护？
- [ ] 新增索引缺失时能否自愈？
- [ ] 失败路径返回的是降级状态还是异常？
- [ ] 若改动了系列归属规则，是否更新了 §3.1 的转换规则？
- [ ] LRR 新增 Redis 键是否避开了 `LRR_*` 前缀？
- [ ] 文档是否在同一次提交里更新（两个 fork 都要求）？

---

## 7. 参考

- `lrr-custom/FORK_CHANGES.md` §1.1、§7、§9、§15、§3
- `lrr-custom/PROJECT.md`（维护契约）
- `komga-clouddrive/komga/src/main/kotlin/org/gotson/komga/domain/service/FileSystemScanner.kt`
- `.../domain/service/BookPageListResolver.kt`
- `.../infrastructure/mediacontainer/divina/ZipExtractor.kt`
- `.../infrastructure/util/ZipFileUtils.kt`
- `.../interfaces/api/rest/BookController.kt`
- `.../interfaces/api/CommonBookController.kt`