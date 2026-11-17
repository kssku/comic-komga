# 闭环验收（ACCEPTANCE）

「跑通」不是「日志没报错」。这份文档定义**怎么证明一条链路真的通了**，
以及每一步用什么事实判定。改动之后按同一个顺序跑，就不必凭感觉。

## 三层

| 层 | 命令 | 判据 | 耗时 |
|---|---|---|---|
| 单元 | `cargo test -p mangrab-server --lib download::` | 全绿 | ~20 s |
| 单圈 | `sh verify-loop.sh` | 见下方断言表 | 2–3 min |
| 全量 | 单圈里已包含 `reconcile --fix` | `repairable` 归零 | 80–100 s |

`CHECK_ONLY=1 sh verify-loop.sh` 只做前置检查与现状核对，不触发下载——适合
在「不想动数据」的时候确认环境是否还活着。

## verify-loop.sh 断言什么

| 断言 | 判据 | 为什么值得单独断言 |
|---|---|---|
| mangrab health | `GET /api/health` 返回 `{"status":"ok"}` | 进程在 ≠ 服务可用 |
| 远端库根可见 | `$RECONCILE_ROOT` 是目录 | NFS 掉挂载时它不存在，后续全是空 |
| PG 可查询 | `select count(*)` 不返回错误 | 元数据库是闭环的真相源 |
| done 未回退 | 环后的 `done` 数 ≥ 环前 | 回退说明有人在写错状态 |
| 新文件远端可见 | 每个新增 CBZ 在 `dirname(RECONCILE_ROOT)/…` 存在 | 「push 成功」不等于「文件到了」 |
| 新文件 DB 已 done | `$(SITE)_archive.state = 'done'` | 「下载成功」不等于「账对上了」 |
| 指标追加 | `$METRICS_FILE` 行数 +1 | 观测链路没断 |

任一 `[FAIL]` 都会让脚本以非零退出，便于挂进 cron / CI。

## 环境依赖（154 上的实际配置）

1. **`/mnt/content` 必须在 `/etc/fstab` 里有条目**（`_netdev,nofail`）。
   它是 NFS 挂载的 115 视图，reconcile 的「磁盘真相」来自它。
   2026-11-18 之前是手工挂载，重启即失效，已补条目并备份 `/etc/fstab.bak-20261118`。
2. **`RECONCILE_REMOUNT` 必须设置**。NFS 的目录属性缓存会让刚 rsync 上去的
   文件**看不见**，reconcile 于是报「nothing to repair」，闭环看起来成功了、
   实际什么都没回写。配置：
   `RECONCILE_REMOUNT="umount -l /mnt/content; sleep 2; mount /mnt/content"`
3. **`METRICS_FILE`** 指向可写文件，每轮回追加一行 jsonl。

## 基线（2026-11-18，154，2 本一圈）

| 项 | 数值 |
|---|---|
| 一圈总耗时 | 138 s |
| 下载 2 本（其中一本 46 张 / 158 MB） | ~30 s，0.97 张/秒 |
| push 增量（2 个新文件） | 25 s |
| push 空转（无新文件） | 1–2 s |
| reconcile（枚举 150,749 个文件） | 83 s |
| 旧方案的 push 全量重传（23 个 CBZ） | 570 s |

reconcile 是现在的大头（83 s / 138 s）。它每次都要枚举整棵树，属已知代价；
要优化就改成「只枚举本轮新增」，但先别动——它现在是唯一能纠偏的环节。

## 踩过的坑（每一个都伪装成「跑通了」）

1. **NFS 缓存吞掉新文件**：push 成功、reconcile 报 zero repair、DB 永远 `pending`。
   修法就是上面的 `RECONCILE_REMOUNT`。
2. **tar 的 chmod 噪音打断整圈**：FUSE 不允许 chmod，`tar -x` 报
   `Cannot change mode` 并以非零退出，文件其实已经写进去了。旧的 push 因此
   把 reconcile/scan 整段跳过。改 rsync 后消失。
3. **rsync 保留 mtime 也报错**：FUSE 拒绝 `utimes`，`-a` 里带的 `-t` 会以
   退出码 23 结束。所以是 `-rlD --size-only --no-times`，不是 `-a`。
4. **创建阶段失败不进指标**：取详情就挂掉的 id 不产生 DownloadTask，
   `chaptersFailed` 永远是 0。已单列 `chaptersCreateFailed`。
5. **「队列空了」不等于「下载完了」**：`/api/tasks/stats` 的 pending+downloading
   归零只说明调度器闲了，产物是否到位要另看。
