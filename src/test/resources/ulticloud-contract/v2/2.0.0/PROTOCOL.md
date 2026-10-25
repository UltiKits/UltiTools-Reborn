<!-- 本文件由 pnpm --filter @ulticloud/contracts generate 生成，勿手改 -->
# UltiCloud 协议 v2 契约 2.0.0

插件（UltiTools 框架）↔ 实时网关的插件通道、网关 ↔ 浏览器工作台的浏览器通道，以及插件与 CLI 调用的 REST 接口。源头是 `packages/contracts/src`（zod）；本目录的 JSON Schema、夹具与本文档都由它生成。

## 1. 版本与同步方法

- 契约版本 `contractVersion` = `2.0.0`（`protocol.CONTRACT_VERSION`），与包版本无关。主版本跟协议号；新增类型或字段 = 次版本；只改文档或夹具 = 修订版本；v3 才升主版本。
- 已交给框架的版本登记在 `generated/protocol/v2/RELEASES.json` 的 `frozen`（版本 → 该版本 `MANIFEST.json` 文件的 SHA-256）。已冻结的版本目录不可再变；内容要变必须先升 `CONTRACT_VERSION`。
- 框架把 `generated/protocol/v2/<版本>/` 整个目录（`schemas/`、`fixtures/`、`PROTOCOL.md`、`MANIFEST.json`）vendor 到自己的仓库，在锁文件里记 `{ contractVersion, manifestSha256, sourceCommit }`；CI 重算全部文件的 SHA-256 与 `MANIFEST.json` 比对，再拿 `MANIFEST.json` 的 SHA-256 与锁文件比对。只 vendor 已冻结的版本。

## 2. 夹具布局

- `fixtures/agent/<type>/valid/<name>.json`：完整的插件通道信封（裸帧）。
- `fixtures/agent/<type>/invalid/<name>.json`：`{ "expect": "invalid_message" | "unsupported_type", "why": "…", "frame": { … } }`。
- `fixtures/browser/<type>/{valid,invalid}/<name>.json`：同上，帧为浏览器平铺帧。
- `fixtures/agent/_unknown/invalid/auth-complete.json`：未注册类型（v1 死消息），期望 `unsupported_type`。
- `fixtures/agent-rest/<operation>/<request|response|error>/valid/<name>.json`：裸 body；`…/invalid/<name>.json`：`{ "expect": "invalid", "why": "…", "body": { … } }`。response 与 error 的文件名以 HTTP 状态开头（`200-…`、`426-…`）选对应 schema；没有状态前缀的反例必须被该部分所有 schema 拒收。
- `fixtures/legacy-uuid-canonicalization.json`：`canonicalLegacyUuid` 的金标准用例（`input` → `expected`，含规则原文）。旧服务器 UUID 规范化：不是字符串 → null；去首尾 U+0000–U+0020（同 Java `String.trim()`）；若长于 36 且前 36 个字符是 8-4-4-4-12 的十六进制 UUID，只留这 36 个；去掉所有 `-`；转小写；恰好 32 位 `[0-9a-f]` 才有结果，否则 null。

## 3. 信封

插件通道：`{ v: 2, id: ULID, type, ts: 毫秒时间戳, replyTo?: ULID, payload }`，strict（顶层出现 `seq`、`serverId` 等未知字段即拒收）。关联键一律是信封 `id`/`replyTo`；插件侧没有 `seq`。

| replyTo 规则 | 含义 | 类型 |
|---|---|---|
| required | 信封必须带 replyTo（指向请求的 id） | `welcome`, `credential.rotate.ack`, `command.ack`, `command.result`, `server.power.result`, `snapshot.request.result`, `op.progress`, `files.list.result`, `files.read.result`, `files.write.result`, `files.delete.result`, `config.tree.result`, `config.entries.result`, `config.update.result`, `properties.get.result`, `properties.update.result`, `backups.list.result`, `backups.create.result`, `backups.restore.result`, `backups.delete.result`, `player.action.result`, `module.response`, `player.link.created`, `player.link.completed`, `player.link.completed.result`, `player.link.expired` |
| forbidden | 信封不能带 replyTo（schema 里没有这个属性，strict 即拒收） | `hello`, `settings.changed`, `ack`, `outbox.overflow`, `credential.rotate`, `command.execute`, `server.power`, `player.join`, `player.leave`, `player.command`, `error.report`, `status`, `players.snapshot`, `plugins.snapshot`, `worlds.snapshot`, `snapshot.request`, `log.lines`, `files.list`, `files.read`, `files.write`, `files.delete`, `config.tree`, `config.entries`, `config.update`, `properties.get`, `properties.update`, `backups.list`, `backups.create`, `backups.restore`, `backups.delete`, `player.action`, `module.request`, `player.link.create` |
| optional | 可带可不带 | `error` |

浏览器通道：平铺帧 `{ type, …该类型的字段 }`，没有 `v`、`id`、`ts`、`replyTo`、`payload`；数据帧带 `stream`，有序流的数据帧另带 `seq`。

## 4. 消息表

### 插件通道

| 类型 | 方向 | 类别 | replyTo | 可能的回复 | ackFirst | 可靠 | 所需能力 | 说明 | schema |
|---|---|---|---|---|---|---|---|---|---|
| `hello` | up | control | forbidden | `welcome` |  |  | — | 插件连接后的第一帧：框架版本、协议号、声明的能力与服主开关（没有 resume）。 | [schema](schemas/agent/hello.schema.json) |
| `settings.changed` | up | control | forbidden | — |  |  | — | 服主改了框架配置（例如打开电源动作）后立即上报，不必重连。 | [schema](schemas/agent/settings.changed.schema.json) |
| `welcome` | down | control | required | — |  |  | — | 网关的握手回复：公开 serverId、接受的能力（与 hello 的交集）、本连接的限额与服务器时间；每条连接只发一次。 | [schema](schemas/agent/welcome.schema.json) |
| `ack` | down | control | forbidden | — |  |  | — | 网关批量确认已收到的可靠事件（按信封 id）；插件收到才从 outbox 删除。 | [schema](schemas/agent/ack.schema.json) |
| `error` | both | control | optional | — |  |  | — | 通用错误；回复某个请求时带信封 replyTo（唯一一个 replyTo 可选的类型）。 | [schema](schemas/agent/error.schema.json) |
| `outbox.overflow` | up | control | forbidden | — |  |  | — | 插件报告 outbox 满时丢掉的可靠事件数（自上次发出以来）；网关不 ack 它。 | [schema](schemas/agent/outbox.overflow.schema.json) |
| `credential.rotate` | down | control | forbidden | `credential.rotate.ack` |  |  | — | 网关下发新凭据；当前连接继续使用，下次连接才用新凭据。 | [schema](schemas/agent/credential.rotate.schema.json) |
| `credential.rotate.ack` | up | control | required | — |  |  | — | 插件把新凭据原子写盘之后才回；网关收到即作废旧凭据。 | [schema](schemas/agent/credential.rotate.ack.schema.json) |
| `command.execute` | down | result | forbidden | `command.ack`, `command.result` | 是 |  | `commands` | 下发一条控制台命令（命令编号 = 信封 id）；经命令封锁表检查。 | [schema](schemas/agent/command.execute.schema.json) |
| `command.ack` | up | result | required | — |  |  | — | 收到即回、执行之前：确认一条执行前确认类请求（命令、电源、回档）。 | [schema](schemas/agent/command.ack.schema.json) |
| `command.result` | up | result | required | — |  |  | — | 命令执行结果：发给命令执行者的真实输出行；超过 commandOutputMaxBytes 时截断并置 truncated。 | [schema](schemas/agent/command.result.schema.json) |
| `server.power` | down | result | forbidden | `command.ack`, `server.power.result` | 是 |  | `power` | 结构化的关机或重启，不经命令封锁表（v1 默认封锁表含 stop、restart）。是否允许由服主决定：面板侧只有服主能调；插件侧要服主在框架配置里打开电源动作（默认关闭，经 hello.settings 与 settings.changed 上报）。能力 power 只表示框架支持。 | [schema](schemas/agent/server.power.schema.json) |
| `server.power.result` | up | result | required | — |  |  | — | 电源动作的结果，在开始关服之前发出；电源动作没在框架配置里打开时回 power_disabled。 | [schema](schemas/agent/server.power.result.schema.json) |
| `player.join` | up | event | forbidden | — |  | 是 | `events:players` | 玩家进服（可靠事件：进 outbox，等网关 ack）。 | [schema](schemas/agent/player.join.schema.json) |
| `player.leave` | up | event | forbidden | — |  | 是 | `events:players` | 玩家离服，被踢也算（可靠事件）。 | [schema](schemas/agent/player.leave.schema.json) |
| `player.command` | up | log | forbidden | — |  |  | `events:commands` | 玩家执行了某个命令：只有命令名（取第一个词、去掉开头的 / 与命名空间前缀、转小写），参数结构上不能上云（protocol-v2-decisions f）。 | [schema](schemas/agent/player.command.schema.json) |
| `error.report` | up | event | forbidden | — |  |  | `errors` | 错误报告（不可靠）：occurrences 是自上次上报以来的次数；trigger.detail、message、stackTrace 中已登记敏感命令的参数在源头替换为 ***（与上云日志同一规则，用户决定第 27 条）；moduleName 为 null 表示框架自身。 | [schema](schemas/agent/error.report.schema.json) |
| `status` | up | telemetry | forbidden | — |  |  | `telemetry` | 服务器状态，每 statusIntervalMs 一次；= FE ServerStatus 去掉 asOf（网关用信封 ts 推出）；tps 取 1 分钟值。 | [schema](schemas/agent/status.schema.json) |
| `players.snapshot` | up | telemetry | forbidden | — |  |  | `telemetry` | 在线玩家快照，变化时最多每 playersSnapshotMinIntervalMs 一次。 | [schema](schemas/agent/players.snapshot.schema.json) |
| `plugins.snapshot` | up | telemetry | forbidden | — |  |  | `telemetry` | 插件列表快照，welcome 后一次、变化时与被请求时再发。 | [schema](schemas/agent/plugins.snapshot.schema.json) |
| `worlds.snapshot` | up | telemetry | forbidden | — |  |  | `telemetry` | 世界列表快照，welcome 后一次、变化时与被请求时再发。 | [schema](schemas/agent/worlds.snapshot.schema.json) |
| `snapshot.request` | down | result | forbidden | `snapshot.request.result` |  |  | `telemetry` | 面板要求刷新快照（FE §6.3 刷新、§6.4 重新加载列表）。 | [schema](schemas/agent/snapshot.request.schema.json) |
| `snapshot.request.result` | up | result | required | — |  |  | — | 插件先推所请求的快照，再回本结果。 | [schema](schemas/agent/snapshot.request.result.schema.json) |
| `log.lines` | up | log | forbidden | — |  |  | `logs` | 一批控制台日志行；已登记敏感命令的参数在源头替换为 ***；单行字节、每批行数与速率按 RUNTIME_CHECKS log-line-bytes、log-batch-lines、log-rate。 | [schema](schemas/agent/log.lines.schema.json) |
| `op.progress` | up | result | required | — |  |  | — | 长操作的进度（replyTo 指向请求）；同一请求可多次发送。 | [schema](schemas/agent/op.progress.schema.json) |
| `files.list` | down | result | forbidden | `files.list.result` |  |  | `files:read` | 列出一个目录（"" 为服务器根，只列可编辑根目录）。 | [schema](schemas/agent/files.list.schema.json) |
| `files.list.result` | up | result | required | — |  |  | — | 目录列表；超过 2000 项时截断并置 truncated。 | [schema](schemas/agent/files.list.result.schema.json) |
| `files.read` | down | result | forbidden | `files.read.result` |  |  | `files:read` | 读一个文本文件。 | [schema](schemas/agent/files.read.schema.json) |
| `files.read.result` | up | result | required | — |  |  | — | 文件内容、version（原始字节的 SHA-256）与 sizeBytes；过大、非 UTF-8 或含控制字符回 file_not_editable。 | [schema](schemas/agent/files.read.result.schema.json) |
| `files.write` | down | result | forbidden | `files.write.result` |  |  | `files:write` | 覆盖一个已存在的文件；baseVersion 必填，插件在同一把锁内原子比较，不符回 file_changed。 | [schema](schemas/agent/files.write.schema.json) |
| `files.write.result` | up | result | required | — |  |  | — | 写入结果与新的 version。 | [schema](schemas/agent/files.write.result.schema.json) |
| `files.delete` | down | result | forbidden | `files.delete.result` |  |  | `files:delete` | 删除一个文件（不删目录）。 | [schema](schemas/agent/files.delete.schema.json) |
| `files.delete.result` | up | result | required | — |  |  | — | 删除结果。 | [schema](schemas/agent/files.delete.result.schema.json) |
| `config.tree` | down | result | forbidden | `op.progress`, `config.tree.result` |  |  | `config:read` | 读配置文件树（服务器 → 插件 → 文件）；读取期间可多次回 op.progress（FE §D-17）。 | [schema](schemas/agent/config.tree.schema.json) |
| `config.tree.result` | up | result | required | — |  |  | — | 配置文件树。 | [schema](schemas/agent/config.tree.result.schema.json) |
| `config.entries` | down | result | forbidden | `config.entries.result` |  |  | `config:read` | 读一个配置文件的可编辑条目。 | [schema](schemas/agent/config.entries.schema.json) |
| `config.entries.result` | up | result | required | — |  |  | — | 配置条目与 version（原始字节的 SHA-256）。 | [schema](schemas/agent/config.entries.result.schema.json) |
| `config.update` | down | result | forbidden | `config.update.result` |  |  | `config:write` | 只交改动的键；插件在同一把锁内比较 baseVersion，不符回 file_changed。 | [schema](schemas/agent/config.update.schema.json) |
| `config.update.result` | up | result | required | — |  |  | — | 全部键成功 → ok；部分或全部键校验失败 → partial（失败的键 ok: false 带 message，成功的键已写入；一个都没成功时文件与 version 不变）；整体失败（冲突、路径、权限、解析）→ error。P3 映射 FE outcome：成功键数 = 全部 → saved，> 0 → partial，= 0 → failed。 | [schema](schemas/agent/config.update.result.schema.json) |
| `properties.get` | down | result | forbidden | `properties.get.result` |  |  | `properties:read` | 读 server.properties 的 14 个常用项。 | [schema](schemas/agent/properties.get.schema.json) |
| `properties.get.result` | up | result | required | — |  |  | — | 当前值（按键带类型）、可写键列表（不含 online-mode、white-list，前端据此显示只读）与 version。 | [schema](schemas/agent/properties.get.result.schema.json) |
| `properties.update` | down | result | forbidden | `properties.update.result` |  |  | `properties:write` | 只改 12 个可写键；正版验证与白名单按用户决定第 15 条不开放远程修改。 | [schema](schemas/agent/properties.update.schema.json) |
| `properties.update.result` | up | result | required | — |  |  | — | 逐键结果，语义同 config.update.result。 | [schema](schemas/agent/properties.update.result.schema.json) |
| `backups.list` | down | result | forbidden | `backups.list.result` |  |  | `backups` | 列出备份（也承担 FE 的备份详情）。 | [schema](schemas/agent/backups.list.schema.json) |
| `backups.list.result` | up | result | required | — |  |  | — | 备份列表。 | [schema](schemas/agent/backups.list.result.schema.json) |
| `backups.create` | down | result | forbidden | `op.progress`, `backups.create.result` |  |  | `backups` | 开始一次备份；进度用 op.progress 上报。 | [schema](schemas/agent/backups.create.schema.json) |
| `backups.create.result` | up | result | required | — |  |  | — | 创建结果（备份对象）。 | [schema](schemas/agent/backups.create.result.schema.json) |
| `backups.restore` | down | result | forbidden | `command.ack`, `backups.restore.result` | 是 |  | `backups` | 回档（会重启服务器）：执行前确认类请求，按信封 id 去重，防止重投导致反复回档。 | [schema](schemas/agent/backups.restore.schema.json) |
| `backups.restore.result` | up | result | required | — |  |  | — | 回档结果，在开始关服之前发出。 | [schema](schemas/agent/backups.restore.result.schema.json) |
| `backups.delete` | down | result | forbidden | `backups.delete.result` |  |  | `backups` | 删除一个备份。 | [schema](schemas/agent/backups.delete.schema.json) |
| `backups.delete.result` | up | result | required | — |  |  | — | 删除结果。 | [schema](schemas/agent/backups.delete.result.schema.json) |
| `player.action` | down | result | forbidden | `player.action.result` |  |  | `players:actions` | 踢出、改游戏模式、传送（到在线玩家或到世界坐标）。 | [schema](schemas/agent/player.action.schema.json) |
| `player.action.result` | up | result | required | — |  |  | — | 玩家动作结果：被操作的玩家或目标玩家不在线回 player_not_online，传送到坐标时世界不存在回 world_not_found。 | [schema](schemas/agent/player.action.result.schema.json) |
| `module.request` | down | result | forbidden | `module.response` |  |  | `modules:<module>@<X.Y.Z>` | 面板对某个模块的请求；actor 由网关填（谁发起的）。 | [schema](schemas/agent/module.request.schema.json) |
| `module.response` | up | result | required | — |  |  | — | 模块的回复：data 是任意 JSON（序列化后受 maxFrameBytes 约束）；没有响应者回 module_not_found，响应者超时回 deadline_exceeded。 | [schema](schemas/agent/module.response.schema.json) |
| `player.link.create` | up | result | forbidden | `player.link.created`, `player.link.completed`, `player.link.expired` |  |  | `link` | 玩家在游戏里输入 /panel：插件申请一个链接；offeredProofs 是插件这一侧能支持的身份证明方式。 | [schema](schemas/agent/player.link.create.schema.json) |
| `player.link.created` | down | result | required | — |  |  | — | 链接已生成：url 给玩家点开；expiresAt = 签发时刻 + playerLinkTtlSeconds（600 秒），一次性使用；acceptedProofs 是网关结合网页账号状态后实际允许的方式。link_not_permitted 见语义 ㉔。 | [schema](schemas/agent/player.link.created.schema.json) |
| `player.link.completed` | down | event | required | `player.link.completed.result` |  |  | — | 链接完成（replyTo 指向那次 player.link.create）。provisionGameAccount 为 true 时插件侧必须为该玩家建游戏账号（网页新注册恒为 true；上次建号失败后重新 /panel 时 linked_web_account 也为 true）。至少一次投递：网关在收到回执前每次 welcome 后用同一信封 id 重投，最长 24 小时；插件按 id 去重并重发记下的回执。 | [schema](schemas/agent/player.link.completed.schema.json) |
| `player.link.completed.result` | up | result | required | — |  |  | — | 插件对每一条 player.link.completed 的回执（没要求建号时回 ok + not_requested）；网关以此判断事件已送达，网页据此显示建号结果。auth_plugin_unavailable 只用于没有可用的登录插件；服务器自身规则拒绝建号（同 IP 注册上限、名字被封禁、注册关闭）回 registration_rejected。 | [schema](schemas/agent/player.link.completed.result.schema.json) |
| `player.link.expired` | down | event | required | — |  |  | — | 链接过期、被取消或被拒（replyTo 指向那次 player.link.create）；只是提示，至多一次投递。 | [schema](schemas/agent/player.link.expired.schema.json) |

### 浏览器通道

| 类型 | 方向 | 类别 | replyTo | 可能的回复 | ackFirst | 可靠 | 所需能力 | 说明 | schema |
|---|---|---|---|---|---|---|---|---|---|
| `subscribe` | up | control | forbidden | — |  |  | — | 订阅流（键为流名、值为订阅参数；有序流带 after 游标）；可再次发送以增减流。 | [schema](schemas/browser/subscribe.schema.json) |
| `unsubscribe` | up | control | forbidden | — |  |  | — | 退订流（例如关掉「实时日志」开关时退订 logs）。 | [schema](schemas/browser/unsubscribe.schema.json) |
| `ready` | down | control | forbidden | — |  |  | — | 订阅确认：已订阅的流与各有序流从哪个 seq 续上。 | [schema](schemas/browser/ready.schema.json) |
| `resync` | down | control | forbidden | — |  |  | — | 这些流补不上了：前端走 REST 拉快照后再 subscribe。 | [schema](schemas/browser/resync.schema.json) |
| `log` | down | log | forbidden | — |  |  | — | 一批日志行；seq 等于本帧最后一行的 seq，可直接当 after 游标。 | [schema](schemas/browser/log.schema.json) |
| `logs_skipped` | down | log | forbidden | — |  |  | — | 背压丢行：前端显示「已跳过 N 行」，用 …/logs?after=fromSeq-1 补拉。 | [schema](schemas/browser/logs_skipped.schema.json) |
| `player_event` | down | event | forbidden | — |  |  | — | 玩家进出（FE §B 里叫 type 的字段改名 event，因为平铺帧的 type 已是消息类型）。 | [schema](schemas/browser/player_event.schema.json) |
| `presence` | down | telemetry | forbidden | — |  |  | — | 在线状态：三个字段 online、lastSeenAt、powerActionsEnabled 都必填，从未连上时为 { online: false, lastSeenAt: null, powerActionsEnabled: null }；powerActionsEnabled 取最近一次 hello.settings 或 settings.changed，离线时保留最后已知值，不为 true 时前端把电源按钮显示为灰色（用户决定第 24 条）；cloudState 见 CloudState（用户决定第 30、31 条）。订阅 status 流时立即推一帧，welcome 之后、settings.changed 之后、判离线时各推一次。 | [schema](schemas/browser/presence.schema.json) |
| `status` | down | telemetry | forbidden | — |  |  | — | 最新的服务器状态。 | [schema](schemas/browser/status.schema.json) |
| `metrics` | down | telemetry | forbidden | — |  |  | — | 给指标曲线追加一个点（由网关从 status 推出）。 | [schema](schemas/browser/metrics.schema.json) |
| `players` | down | telemetry | forbidden | — |  |  | — | 最新的在线玩家列表。 | [schema](schemas/browser/players.schema.json) |
| `error_report` | down | event | forbidden | — |  |  | — | 错误组有更新（不带堆栈）；前端据此刷新错误角标与列表。 | [schema](schemas/browser/error_report.schema.json) |
| `command_status` | down | result | forbidden | — |  |  | — | 命令状态变化（排队、已送达、已确认、完成、过期、撤销）。 | [schema](schemas/browser/command_status.schema.json) |
| `command_result` | down | result | forbidden | — |  |  | — | 命令的输出。 | [schema](schemas/browser/command_result.schema.json) |
| `op_result` | down | result | forbidden | — |  |  | — | 异步操作的结果（按 kind 区分结果形状）；进度另由 op.progress 帧推送。 | [schema](schemas/browser/op_result.schema.json) |
| `op.progress` | down | result | forbidden | — |  |  | — | 同一 opId 在最终 op_result 之前可推多次进度（例如读取配置时「已读 3/9」）；网关由插件的 op.progress 转出。 | [schema](schemas/browser/op.progress.schema.json) |
| `backup_progress` | down | result | forbidden | — |  |  | — | 备份进度与完成、失败。 | [schema](schemas/browser/backup_progress.schema.json) |

## 5. 结果形状 OpOutcome

所有结果类消息的 payload 是同一个按 `status` 区分的联合：`{ status: "ok", data, message? }`、`{ status: "partial", data, code?, message?, details? }`（只有声明了 partial 的类型）、`{ status: "error", code, message, details? }`（`code` 取该类型允许的码或 `internal_error`，不带 `data`）。

| OpOutcome.status | FE OpResult.state |
|---|---|
| `ok` | `succeeded` |
| `partial` | `partial` |
| `error` | `failed` |
| 网关在截止时间内等不到回复 | `timeout` |

## 6. 错误码

| 码 | 含义 | FE 码 | 允许的插件通道类型 |
|---|---|---|---|
| `invalid_message` | 帧不是合法的 JSON、信封或 payload 不符合 schema | — | `error` |
| `unsupported_type` | 信封头部完好，但 type 未注册 | — | `error` |
| `capability_not_accepted` | 该类型所需的能力没有在握手时被接受（details.capability） | — | `error` |
| `rate_limited` | 超出速率限制 | `rate_limited` | `error`, `player.link.created` |
| `payload_too_large` | 帧或回复超过 maxFrameBytes | — | `error` |
| `internal_error` | 未预期的错误 | `internal_error` | `error`, `command.result`, `server.power.result`, `snapshot.request.result`, `files.list.result`, `files.read.result`, `files.write.result`, `files.delete.result`, `config.tree.result`, `config.entries.result`, `config.update.result`, `properties.get.result`, `properties.update.result`, `backups.list.result`, `backups.create.result`, `backups.restore.result`, `backups.delete.result`, `player.action.result`, `module.response`, `player.link.created`, `player.link.completed.result` |
| `command_blocked` | 命令命中封锁表（details.matched） | — | `command.result` |
| `command_failed` | 命令执行失败（dispatchCommand 返回 false 或抛异常） | — | `command.result` |
| `power_disabled` | 框架配置里没有打开电源动作（用户决定第 24 条） | — | `server.power.result` |
| `power_unsupported` | 服务器做不了这个电源动作（例如没配置重启脚本时的 restart） | — | `server.power.result` |
| `path_not_allowed` | 路径不在可编辑根目录内或命中禁止名单 | — | `files.list.result`, `files.read.result`, `files.write.result`, `files.delete.result`, `config.entries.result`, `config.update.result` |
| `not_found` | 文件、目录或配置不存在 | `not_found` | `files.list.result`, `files.read.result`, `files.write.result`, `files.delete.result`, `config.entries.result`, `config.update.result`, `properties.get.result`, `properties.update.result` |
| `is_directory` | 目标是目录，不是文件 | — | `files.read.result`, `files.write.result`, `files.delete.result` |
| `not_a_directory` | 目标是文件，不是目录 | — | `files.list.result` |
| `file_not_editable` | 文件过大、不是合法 UTF-8 或含控制字符（RUNTIME_CHECKS file-content） | `file_not_editable` | `files.read.result`, `files.write.result`, `properties.get.result` |
| `file_changed` | baseVersion 与当前内容的 SHA-256 不符（文件、配置、服务器属性共用） | `file_changed` | `files.write.result`, `config.update.result`, `properties.update.result` |
| `write_denied` | 服务器没有写入权限 | `write_denied` | `files.write.result`, `files.delete.result`, `config.update.result`, `properties.update.result`, `backups.create.result`, `backups.delete.result` |
| `config_invalid` | 配置文件解析失败（YAML） | — | `config.entries.result`, `config.update.result` |
| `backup_not_found` | 备份不存在 | — | `backups.restore.result`, `backups.delete.result` |
| `backup_not_restorable` | 备份没有完成或已失败，不能回档 | `backup_not_restorable` | `backups.restore.result` |
| `backup_in_progress` | 已有备份或回档在进行 | — | `backups.create.result`, `backups.restore.result`, `backups.delete.result` |
| `player_not_online` | 被操作的玩家或传送目标玩家不在线 | — | `player.action.result` |
| `world_not_found` | 传送到坐标时世界不存在 | — | `player.action.result` |
| `link_not_permitted` | 这台服务器或这位玩家不允许生成链接 | `link_not_permitted` | `player.link.created` |
| `module_not_found` | 没有模块响应这个请求 | — | `module.response` |
| `module_error` | 模块响应者抛出异常 | — | `module.response` |
| `deadline_exceeded` | 模块响应者超时 | — | `module.response` |
| `account_exists` | 该玩家已有游戏账号 | — | `player.link.completed.result` |
| `auth_plugin_unavailable` | 服务器没有可用的游戏内登录插件 | — | `player.link.completed.result` |
| `registration_rejected` | 服务器规则拒绝建号 | `registration_rejected` | `player.link.completed.result` |
| `timeout` | 网关在截止时间内没等到服务器的回复 | — | 网关专用：只出现在浏览器 `OpResult.error.code` |
| `server_offline` | 发出前或等待中服务器断线 | `server_offline` | 网关专用：只出现在浏览器 `OpResult.error.code` |

## 7. 关闭码与客户端动作

### 插件 WebSocket（网关 → 插件）

| 关闭码 | 名称 | 动作 | 插件做什么 |
|---|---|---|---|
| 1000 | `normal` | `backoff` | 按 BACKOFF 指数退避（1 秒起、上限 60 秒、全抖动）后重连。 |
| 1001 | `goingAway` | `backoff` | 网关重启或部署。按 BACKOFF 指数退避（1 秒起、上限 60 秒、全抖动）后重连。 |
| 1009 | `messageTooBig` | `backoff` | 帧超过平台上限。按 BACKOFF 指数退避（1 秒起、上限 60 秒、全抖动）后重连。 |
| 1011 | `internalError` | `backoff` | 按 BACKOFF 指数退避（1 秒起、上限 60 秒、全抖动）后重连。 |
| 4000 | `superseded` | `superseded` | 同一凭据的新连接顶替了本连接。按 BACKOFF_LONG 等待并在控制台警告「另一处正在使用同一凭据」；连续 SUPERSEDED_RULE.maxConsecutive 次被顶替（中间没有持续 stableConnectionMs 以上的连接）就停止重连并打印 consoleMessage。网关一方始终保留最新连接。 |
| 4400 | `invalidHello` | `backoff_long` | hello 非法，或 welcome 之前发了别的帧。按 BACKOFF_LONG 长退避（60 秒起、上限 15 分钟、全抖动）后重连；这通常是插件自身的问题，快速重连只会重复失败。 |
| 4401 | `credentialRevoked` | `terminate_delete_credential` | 凭据被吊销（服主撤销或删除服务器）。终止重连状态机、删除本地凭据，控制台提示「凭据已失效，请执行 /ulticloud login」。 |
| 4402 | `planLimit` | `plan_limited_retry` | 服务器因账号套餐超限被停用（不是凭据问题）。保留凭据，停止短间隔重连，改为每 PLAN_LIMIT_RULE.retryIntervalMs（15 分钟）静默重试一次；控制台给出原因与 402 响应体里的 manageUrl，/ulticloud reconnect 立即重试。 |
| 4408 | `helloTimeout` | `backoff` | 连接后 helloTimeoutMs 内没收到 hello。按 BACKOFF 指数退避（1 秒起、上限 60 秒、全抖动）后重连。 |
| 4426 | `protocolUnsupported` | `terminate_prompt_upgrade` | hello.protocol 与升级头不一致（防御）。终止重连、保留凭据，控制台提示升级 UltiTools（426 时带 X-UltiCloud-Min-Framework 给出的最低版本）。 |
| 4429 | `abusiveRate` | `backoff_long` | 滥用速率（含 60 秒内超过 20 个非法帧）。按 BACKOFF_LONG 长退避（60 秒起、上限 15 分钟、全抖动）后重连；这通常是插件自身的问题，快速重连只会重复失败。 |

未列出的关闭码 → `backoff`。

### 插件握手（升级请求）的 HTTP 状态

| 状态 | 错误码 | 动作 | 插件做什么 |
|---|---|---|---|
| 401 | `invalid_credential` | `terminate_delete_credential` | 终止重连状态机、删除本地凭据，控制台提示「凭据已失效，请执行 /ulticloud login」。 |
| 402 | `plan_limit` | `plan_limited_retry` | 保留凭据，停止短间隔重连，改为每 PLAN_LIMIT_RULE.retryIntervalMs（15 分钟）静默重试一次；控制台给出原因与 402 响应体里的 manageUrl，/ulticloud reconnect 立即重试。 |
| 426 | `upgrade_required` | `terminate_prompt_upgrade` | 终止重连、保留凭据，控制台提示升级 UltiTools（426 时带 X-UltiCloud-Min-Framework 给出的最低版本）。 |
| 429 | `rate_limited` | `backoff_long` | 按 BACKOFF_LONG 长退避（60 秒起、上限 15 分钟、全抖动）后重连；这通常是插件自身的问题，快速重连只会重复失败。有 Retry-After 时取两者中较大的等待时间。 |

其余 4xx → `backoff_long`；5xx 与网络错误 → `backoff`。

### 浏览器 WebSocket（DO → 浏览器）

| 关闭码 | 名称 | 动作 | 前端做什么 |
|---|---|---|---|
| 4401 | `sessionInvalid` | `redirect_login` | 面板会话失效：跳转登录页（带 redirect）。 |
| 4403 | `forbidden` | `show_forbidden` | 无权访问这台服务器：显示 403。 |
| 4409 | `resyncRequired` | `resync_then_reconnect` | 需要重新同步：走 REST 拉快照，再带新游标重连。 |

其余关闭码（1000、1001、1006、1011 等）→ 退避后带各流 `after` 游标重连。

### 退避参数与规则

| 名称 | 值 |
|---|---|
| BACKOFF | `{"initialMs":1000,"maxMs":60000,"jitter":"full"}` |
| BACKOFF_LONG | `{"initialMs":60000,"maxMs":900000,"jitter":"full"}` |
| BROWSER_BACKOFF | `{"initialMs":1000,"maxMs":30000,"jitter":"full"}` |
| SUPERSEDED_RULE（4000 被顶替） | `{"backoff":"backoff_long","maxConsecutive":3,"stableConnectionMs":600000,"consoleMessage":"另一处正在使用同一个 UltiCloud 凭据；本服务器已停止重连。确认后在要接入的服务器上执行 /ulticloud login。"}` |
| PLAN_LIMIT_RULE（4402 / 402 套餐超限） | `{"retryIntervalMs":900000,"keepCredential":true,"reconnectCommand":"/ulticloud reconnect","consoleMessage":"本服务器因账号套餐超限已暂停云连接（凭据保留）。续费或在面板里把它选为保留后会自动恢复；也可执行 /ulticloud reconnect 立即重试。"}` |

## 8. 限额

`welcome.limits` 的默认值（`DEFAULT_LIMITS`）：

| 字段 | 默认值 |
|---|---|
| `maxFrameBytes` | 1048576 |
| `log.ratePerSec` | 50 |
| `log.burst` | 200 |
| `log.lineMaxBytes` | 8192 |
| `log.batchMaxLines` | 100 |
| `outboxMax` | 1000 |
| `commandDedupeMax` | 1000 |
| `commandDedupeTtlSeconds` | 86400 |
| `commandOutputMaxBytes` | 65536 |
| `fileMaxBytes` | 393216 |

schema 硬上限（`SCHEMA_CAPS`，单位是字符或条数；字符数不会超过 UTF-8 字节数，所以它是字节限额的可靠必要条件）。信封开销上限 `ENVELOPE_OVERHEAD_MAX_BYTES` = 4096。

| 名称 | 值 |
|---|---|
| `logMessageMaxChars` | 8192 |
| `logBatchMaxItems` | 100 |
| `commandOutputMaxItems` | 1000 |
| `commandOutputLineMaxChars` | 4096 |
| `fileContentMaxChars` | 393216 |
| `stackTraceMaxChars` | 16384 |
| `listMaxItems` | 2000 |
| `playersMaxItems` | 1000 |
| `pluginsMaxItems` | 1000 |
| `worldsMaxItems` | 200 |
| `backupsMaxItems` | 500 |
| `configEntriesMaxItems` | 2000 |
| `capabilitiesMaxItems` | 64 |
| `ackIdsMaxItems` | 500 |

**字节规则不在 schema 里，由下面的运行时检查保证**（`RUNTIME_CHECKS`）：

| id | 规则 | 怎么量 | 谁检查 | 违反时 |
|---|---|---|---|---|
| `frame-bytes` | 单帧原始 UTF-8 字节数 ≤ maxFrameBytes | 收到的文本帧的 UTF-8 字节数（JSON.parse 之前） | both | 框架发送前分批保证；网关在 JSON.parse 之前检查，超出回 error { code: payload_too_large } 并丢弃该帧 |
| `log-line-bytes` | 单行日志序列化后 ≤ log.lineMaxBytes | JSON.stringify(line) 的 UTF-8 字节数 | both | 框架截断 message（末尾加 …[truncated]）；网关发现超出即按 frame-bytes 处理整帧 |
| `log-batch-lines` | 每批日志行数 ≤ log.batchMaxLines | log.lines.lines 的元素个数（schema 另查硬上限 100） | both | 框架分批发送；网关拒收整帧（invalid_message） |
| `command-output-bytes` | 命令输出序列化后 ≤ commandOutputMaxBytes | JSON.stringify(output) 的 UTF-8 字节数 | framework | 框架截断输出并置 truncated: true |
| `file-content` | 文件原始字节 ≤ fileMaxBytes，必须是合法 UTF-8，且不含 TAB/LF/CR 以外的 C0 控制字符与 DEL | 磁盘上文件的原始字节；files.write 的 content 按 UTF-8 编码后的字节 | both | 框架回 file_not_editable；files.write 由网关在下发前按同一规则检查 |
| `log-rate` | 每服务器日志令牌桶：log.ratePerSec 每秒、突发 log.burst | 网关按行计数 | gateway | 网关超出即丢，并向浏览器发 logs_skipped；最多每分钟向插件发一次 error { code: rate_limited } |
| `telemetry-coalesce` | 遥测只保留每种快照的最新值 | status、players.snapshot、plugins.snapshot、worlds.snapshot 各一份 | gateway | 网关合并，不排队；新值覆盖旧值 |

## 9. 时序与语义

心跳不是消息：插件与浏览器都发**文本帧** `ping`，DO 用 WebSocket auto-response 回文本帧 `pong`（不唤醒 DO），不入信封。

| 常量 | 值 |
|---|---|
| `version` | 2 |
| `timing.helloTimeoutMs` | 10000 |
| `timing.requestDeadlineMs` | 30000 |
| `timing.heartbeatIntervalMs` | 30000 |
| `timing.heartbeatTimeoutMs` | 90000 |
| `timing.gatewayIdleTimeoutMs` | 120000 |
| `timing.statusIntervalMs` | 10000 |
| `timing.playersSnapshotMinIntervalMs` | 10000 |
| `timing.snapshotCoalesceMs` | 10000 |
| `timing.logFlushIntervalMs` | 1000 |
| `timing.outboxFlushIntervalMs` | 30000 |
| `eventDedupeTtlSeconds` | 86400 |
| `queuedCommandTtlSeconds` | 600 |
| `playerLinkTtlSeconds` | 600 |
| `heartbeat.ping` | ping |
| `heartbeat.pong` | pong |
| `ack.flushMs` | 1000 |
| `ack.batchMax` | 100 |
| `invalidFrames.max` | 20 |
| `invalidFrames.windowMs` | 60000 |
| `rateLimitedNoticeMinIntervalMs` | 60000 |
| `browser.pingIntervalMs` | 30000 |
| `browser.idleReconnectMs` | 75000 |

- **①heartbeat** 心跳：插件每 heartbeatIntervalMs 发文本帧 ping，heartbeatTimeoutMs 内没收到 pong 即关闭并按退避重连；网关 gatewayIdleTimeoutMs 内没收到任何帧判离线，但不主动断开连接（连接由插件的 pong 超时来关）。不要依赖 WebSocket 协议层的 ping 控制帧。
- **②ack-first** 执行前确认类请求（ackFirst：command.execute、server.power、backups.restore）：收到即回 command.ack，先把信封 id 落盘进去重集合再执行；重复 id 只重回 command.ack，不重新执行、不要求缓存结果；网关在截止时间内没拿到结果，状态停在 acked。
- **③at-most-once** 其余请求至多一次：断线或超过 requestDeadlineMs 即按超时结束，网关不排队、不重投。
- **④snapshot-request** snapshot.request：插件先推所请求的快照（status、plugins.snapshot、worlds.snapshot），再回 snapshot.request.result。
- **⑤power** server.power：插件先回 command.ack，再回 server.power.result，然后才开始关服或重启；电源动作不经命令封锁表，框架配置里没打开时回 power_disabled。
- **⑥telemetry-cadence** 遥测节奏：status 每 statusIntervalMs 一次；players.snapshot 变化时最多每 playersSnapshotMinIntervalMs 一次；plugins.snapshot、worlds.snapshot 在 welcome 后各一次、变化时（合并 snapshotCoalesceMs）与被请求时再发；日志每 logFlushIntervalMs 冲刷一次或满批即发。
- **⑦reliable-events** 可靠事件（player.join、player.leave）：进插件 outbox（内存 + 每 outboxFlushIntervalMs 有改动时落盘、关服时落盘），网关批量回 ack { ids }，插件收到才删除；outbox 满时丢最旧的并在下次 welcome 后发 outbox.overflow；网关按 id 去重，保留 eventDedupeTtlSeconds。崩溃丢最后 30 秒属已知限制。
- **⑧capabilities** 网关只发对方 accepted 里有能力的类型，插件只发自己声明且被接受的能力的类型；回复类型继承请求的能力；module.request 只发给声明了 modules:<module>@<版本> 的插件。
- **⑨cross-element** 数组元素唯一、done ≤ total 等跨元素约束不在 schema 里（JSON Schema 与 zod 无法一致表达），由接收方容忍：去重或忽略。
- **⑩hello-first** 连接建立后插件的第一帧必须是 hello，welcome 之前发出的其它帧一律被丢弃并以 4400 关闭（outbox 在 welcome 之后重发，不会丢）；welcome 每条连接只发一次，限额只在重连时变化。
- **⑪ack-cadence** 网关收到可靠事件后最迟 ack.flushMs 或攒满 ack.batchMax 个就回一次 ack（schema 上限每个 ack 500 个 id）。
- **⑫invalid-frames** 非法帧回 error { code: invalid_message, replyTo? } 并丢弃该帧，连接保持；invalidFrames.windowMs 内超过 invalidFrames.max 个非法帧以 4429 关闭。
- **⑬oversized-reply** 一条回复本身超过 maxFrameBytes（回复无法分批）时，插件改回 error { code: payload_too_large, replyTo }；网关下发请求前同样检查帧大小。
- **⑭log-rate-notice** 网关的日志令牌桶超出时最多每 rateLimitedNoticeMinIntervalMs 向插件发一次 error { code: rate_limited }（无 replyTo）；插件只需在本地按 log.ratePerSec/burst 节流，不必回报。
- **⑮credential-rotate** credential.rotate（graceSeconds 默认 86400）：当前连接继续使用，插件原子写盘后回 credential.rotate.ack，网关收到 ack 即作废旧凭据（当前连接已在升级时认证，不受影响），下次连接才用新凭据；面板发起的轮换在浏览器通道以 op_result { kind: credential.rotate } 报告，requestDeadlineMs 内收到 ack 为 succeeded，否则 timeout（轮换继续）；插件没 ack 时网关在之后每次 welcome 后重发同一次轮换，直到 ack 或 graceSeconds 到期。
- **⑯server-id-mismatch** welcome.serverId 必须等于设备授权时拿到的 serverId；不等时插件以 1000 关闭、停止重连、保留凭据并在控制台报错（这只会是服务端缺陷）。
- **⑰browser-heartbeat** 浏览器每 browser.pingIntervalMs 发文本帧 ping，DO 用 auto-response 回 pong；browser.idleReconnectMs 内没收到任何帧就断开，并带各流 after 游标重连。
- **⑱string-length** 字符串上限：zod 按 UTF-16 码元计数，JSON Schema maxLength 按码点计数，二者只在「接近上限且含四字节字符」时结论不同；契约的上限都远大于实际值，这一差异只写进文档、不另设防。
- **⑲link-completed** player.link.completed 至少一次投递：网关在收到 player.link.completed.result 之前，每次 welcome 之后用同一信封 id 重投，最长 eventDedupeTtlSeconds；插件按 id 去重（与命令共用去重集合）并记下当次回执，重复时不再建号、只重发回执。player.link.expired 只是提示，至多一次投递。
- **⑳presence** 网关向订阅了 status 流的浏览器推 presence：订阅时立即推当前值，插件 welcome 之后、收到 settings.changed 之后、判离线时各推一次；从未连上的服务器推 { online: false, lastSeenAt: null, powerActionsEnabled: null }。
- **㉑outbox-overflow** outbox.overflow 的 dropped 是自上次成功发出 outbox.overflow 以来丢掉的事件数；插件在 welcome 之后发一次、发出即清零；它是控制消息，网关不 ack 它。
- **㉒upgrade-headers** 升级请求缺 X-UltiCloud-Protocol 或 X-UltiCloud-Framework 任一个头都回 426（upgrade.ts）；hello.protocol 与升级头不一致时网关以 4426 关闭。
- **㉓plan-limit** 套餐超限停用（用户决定第 30 条，protocol-v2-decisions.md i）：服务器进入「因超限停用」时，网关立即以 4402 关闭其当前连接，停用期间升级请求一律回 402 plan_limit；插件保留本地凭据，停止短间隔重连，改为每 15 分钟静默重试一次，控制台给出原因与面板链接（manageUrl），/ulticloud reconnect 立即重试；续费或服主把这台选为保留后，下一次重试即恢复。7 天宽限期内连接照常，hello/welcome 不变。4402 与「凭据已吊销」（删凭据并终止）严格区分，动作见 close-codes.ts。
- **㉔player-link** 玩家链接（protocol-v2-decisions.md j）：链接有效期固定 10 分钟（playerLinkTtlSeconds = 600，expiresAt = 签发时刻 + 600s），一次性使用；同一玩家再次 /panel 会使旧链接 cancelled。link_not_permitted（player.link.created 的失败码，后端判定）：服务器处于停用状态（suspended_plan_limit / needs_reconnect）、服主关闭了玩家链接，或该游戏身份被后端封禁 → 不签发链接。player.link.expired.reason = denied：网页上的用户在确认页明确点了「不是我 / 拒绝」；cancelled：插件或玩家主动取消（下线、重新 /panel 覆盖旧链接）；expired：到期未完成。player.link.completed.result 的 registration_rejected：服务器自身规则拒绝建号（如 UltiLogin 同 IP 注册上限、名字被封禁、注册关闭）；auth_plugin_unavailable 只用于「没有可用的登录插件」；面板对 registration_rejected 显示「这台服务器的规则不允许为你建游戏账号，请联系服主」。

## 10. 升级请求与拒绝

插件连接 `wss://api.ultikits.com/v1/agent`，请求头：

| 用途 | 请求头 |
|---|---|
| authorization | `Authorization` |
| protocol | `X-UltiCloud-Protocol` |
| framework | `X-UltiCloud-Framework` |

判定顺序 `protocol → framework → credential → plan`：缺 `X-UltiCloud-Protocol`（v1 与 6.3.0 风格的握手都没有它）或值不在 `2` 里 → 426；缺 `X-UltiCloud-Framework` 或不是合法 Semver、或低于网关配置的最低版本 → 426；再验凭据，无效 → 401；再查套餐，服务器因超限停用 → 402。429 随时可能出现并带 `Retry-After`。`hello.protocol` 与升级头不一致时网关以 4426 关闭。

| 响应头 | 名称 |
|---|---|
| supportedProtocols | `X-UltiCloud-Protocol-Supported` |
| minFramework | `X-UltiCloud-Min-Framework` |
| retryAfter | `Retry-After` |

四种拒绝的响应体都是 `{ error: { code, message, requestId, details? } }`：

| 状态 | error.code | details | schema |
|---|---|---|---|
| 426 | `upgrade_required` | 必填 `{ supportedProtocols, minFramework }` | [schema](schemas/agent-rest/agentUpgrade.error.426.schema.json) |
| 401 | `invalid_credential` | 无 | [schema](schemas/agent-rest/agentUpgrade.error.401.schema.json) |
| 402 | `plan_limit` | 必填 `{ reason: "pro_expired_over_limit", graceEndedAt: ISO 时间, manageUrl: https URL }` | [schema](schemas/agent-rest/agentUpgrade.error.402.schema.json) |
| 429 | `rate_limited` | 无（等待秒数只在 `Retry-After` 头里） | [schema](schemas/agent-rest/agentUpgrade.error.429.schema.json) |

## 11. 浏览器通道

连接 `wss://api.ultikits.com/v1/servers/:serverId/live`（面板会话 Cookie + Origin）。平铺帧；`subscribe.streams` 是按流名的对象，可再次发送以增减流；补不上时网关发 `resync { streams }`，前端走 REST 拉快照后再订阅。

| 流 | 有序 | 订阅参数 | 承载的帧 |
|---|---|---|---|
| `logs` | 是（帧带 seq） | `{ after: seq \| null }` | `log`, `logs_skipped` |
| `events` | 是（帧带 seq） | `{ after: seq \| null }` | `player_event` |
| `status` | 否（只发最新值或结果） | `{}` | `presence`, `status`, `metrics`, `players`, `error_report` |
| `commands` | 否（只发最新值或结果） | `{}` | `command_status`, `command_result`, `op_result`, `op.progress`, `backup_progress` |

- 有序流的数据帧带 `seq`；`log` 帧的 `seq` 等于本帧最后一行的 `seq`，可直接当 `after` 游标。控制帧 `ready`、`resync` 不带 `stream`。
- 异步操作：`op_result` 的 `opId` 就是网关下发给插件的请求信封 `id`；同一 `opId` 在最终 `op_result` 之前可推多次 `op.progress`。`OpResult.kind`：`refresh`, `config.tree`, `config.entries`, `config.update`, `properties.update`, `files.list`, `files.read`, `files.write`, `files.delete`, `properties.get`, `player.action`, `credential.rotate`。
- 心跳：浏览器每 30000 毫秒发文本帧 `ping`；75000 毫秒内没收到任何帧就断开，带各流 `after` 游标重连。

## 12. 校验器配置

- TS 侧：zod（`packages/contracts/src`）。
- 第二校验器：Ajv 8 的 Draft 2020-12 版本，两组配置都要与 zod 结论一致——format 开启（`ajv-formats`）与 format 关闭（`validateFormats: false`）。
- Java 侧推荐：在测试作用域用 networknt json-schema-validator，Draft 2020-12，不开 format 断言，用 JDK 正则。契约保证这种配置与 zod 结论一致：schema 里没有 `format` 关键字，`$ref` 都是文件内部的，每个 `pattern` 以 `^` 开头 `$` 结尾且只用可移植子集（显式字符类，没有前后行断言、`\p{}`、命名组、反向引用、`\d \w \s \b`）。兼容 Java 8 字节码的版本线由 P11 选定。
- 字符串长度：zod 按 UTF-16 码元计数，JSON Schema 按码点计数；只在接近上限且含四字节字符时结论不同，见语义 ⑱。

## 13. 已定的用户决定（2026-10-24）

- 正版验证与白名单不开放远程修改：`properties.update` 只接受 12 个可写键；`online-mode`、`white-list` 只读，`RESERVED_SECURITY_PROPERTY_KEYS` 与保留能力名 `properties:security` 只是扩展位，任何消息都不使用。
- 传送支持到在线玩家与到世界坐标两种目标；改游戏模式、踢出、传送都是结构化动作。
- 电源动作是结构化的 `server.power`，不经命令封锁表，框架配置默认关闭；开关经 `hello.settings` 与 `settings.changed` 上报，浏览器 `presence.powerActionsEnabled` 显示。
- 玩家链接用通用的身份证明语义，协议里不传任何密码。
- 敏感命令脱敏只作用于上云的日志、错误报告和聊天。
- 套餐超限停用：4402 / 402，保留凭据、每 15 分钟静默重试。
