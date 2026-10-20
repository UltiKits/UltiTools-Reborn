# Compatibility and Versioning Policy

This document explains what the version numbers of `com.ultikits:UltiTools-API` mean, how
deprecation and removal work, and which removals each release makes (for 6.3.0, see
[Removal list for 6.3.0](#removal-list-for-630)). It is written for downstream module authors.

## Upgrade checklist: moving a module from 6.2.5 to 6.3.0

What a module author has to do, in the order you would do it. Each item is one action and links to the section that
explains it; the sections below are the reference and stay authoritative where this summary is shorter.

1. Raise the `UltiTools-API` dependency in your build to 6.3.0, keep it `provided` (Maven) or `compileOnly` (Gradle), and recompile; a JAR compiled against 6.2.5 is not safe to drop onto 6.3.0 unchanged (see [Declaring the dependency](#declaring-the-dependency) and [What this means for you](#what-this-means-for-you)).
2. Set `api-version: 630` in your module's `plugin.yml` together with the pin: it is the only number the framework checks, an older framework refuses a module that declares a higher level, and `/upm` and the UltiCloud catalogue offer and install only modules declaring `630` up to the framework's own level (see [What this means for you](#what-this-means-for-you)).
3. Rename an `unregisterSelf()` override to `onUnregister()` and a `reloadSelf()` override to `onReload()`, or delete the override when it only logs; both methods are `final` in 6.3.0, and a JAR built against 6.2.5 that overrides either is refused at load with `IncompatibleClassChangeError` (see [Third occurrence: 6.2.5 → 6.3.0, a MINOR](#third-occurrence-625--630-a-minor)). Remove every `super.unregisterSelf()` call from the renamed hook, keep the module's real cleanup work, and let the framework unregister commands and listeners after `onUnregister()` returns.
4. Move every command class from `AbstractCommandExecutor` to `BaseCommandExecutor` and implement `handleHelp(CommandSender)`; the old base class, its misspelled shim and the package-scanning `CommandManager.registerAll(UltiToolsPlugin, String)` overload are removed (see [Migrating off `AbstractCommandExecutor`](#migrating-off-abstractcommandexecutor)).
5. Replace every other removed API you call with its named replacement - for example `UltiTools#getEconomy()` with `EconomyUtils.getEconomy()`, `AbstractDataEntity` with `BaseDataEntity`, and `DependenceManagers#getAdventure()` with Paper's native `Player#sendMessage(Component)` - checking the generated registry and the exceptions list (see [Removal list for 6.3.0](#removal-list-for-630) and [Same-release exceptions applied in 6.3.0](#same-release-exceptions-applied-in-630)).
6. Replace `AbstractConfigEntity#getConfig()` with `isPresentInFile(String)` for presence checks, and read or change declared fields and call `save()` instead of mutating Bukkit storage (see [Removed mutable configuration accessor](#removed-mutable-configuration-accessor)).
7. Persist a configuration change when you make it - `save()` for a change the module made, `saveOperatorChange`/`saveOperatorMapEntry` for exactly what an operator's command names - because nothing writes configuration at server stop, module unload or replacement any more and `save()` writes only what the module changed (see [Configuration release; nothing written at stop](#configuration-release-nothing-written-at-stop) and [Declared types, whole map keys and persistence](#declared-types-whole-map-keys-and-persistence)).
8. Move each legacy parser (`@ConfigEntry(parser = …)`, `Parser`, `ObjectConfigSerializer`, `ConfigParser`, `DefaultConfigParser`, `StringHashMapParser`) to a public top-level `ConfigConverter` annotated `@ConfigConverterFor` in a scanned package, and register a converter for any declared field type the framework does not support, which otherwise refuses the module; the legacy parsers are deprecated in 6.3.0 and announced for removal in 6.4.0 (see [Migrating legacy parsers to converters](#migrating-legacy-parsers-to-converters)).
9. If a field uses `@Scheduled(config = …, periodKey/delayKey = …)` or `@CmdCD(config = …, key = …)`, declare `api-version: 630`, keep a bound `@Scheduled` synchronous, and drop any `@Range` on the bound field, whose range the binding replaces (see [A floor no linker enforces](#a-floor-no-linker-enforces-config-bound-scheduled-and-cmdcd-630)).
10. Check command bodies for the one-tick delay they no longer get: a synchronous body now runs at dispatch, so code inside an `InventoryClickEvent` that opens or closes an inventory or dispatches a command must defer it with `runTask`, and a body that dispatches its own command meets `@UsageLimit` and `@CmdCD` on the same tick (see [Command bodies run at dispatch](#command-bodies-run-at-dispatch-630--changed-without-a-migration-period)).
11. Review the runtime changes that took effect without a migration period and adjust code that relied on the old behaviour - for example JSON-backend reads now return detached copies, so a change needs `update(...)`; `PluginManager#getPluginList()` returns an unmodifiable snapshot; `@NotEmpty` on lists, sets and maps now acts; a constraint the framework cannot check refuses the module at load; and a value that violates `@Range`, `@Size`, `@Pattern` or `@NotEmpty` on text now refuses the module at load (and the reload at `/ul reload`), where 6.2.5 reset it to its default and saved the file (see [Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period)).
12. Keep the framework out of your JAR, and if your module uses XSeries declare it yourself as `provided`/`compileOnly` (`com.github.cryptomorin:XSeries:13.7.1` or your own pin): the framework no longer shades it but still delivers it at runtime through its `libraries:` block, and Gson, MySQL Connector/J, protobuf and slf4j come from the Paper build the server runs (see [Where runtime dependencies come from](#where-runtime-dependencies-come-from)).
13. Know the framework's own server requirements: Paper 1.19.2 build 163 or newer, Java 17 below Paper 1.20.5 and Java 21 from it; the UltiKits modules state only the UltiTools-API version they support (6.3.0 or later for `api-version: 630`) (see [Support matrix](#support-matrix)).
14. If your module uses `DataOperator#transaction(...)` or `@Transactional` on JSON storage, or relies on a failing joined `@Transactional` method or a failing nested `DataOperator#transaction(...)` scope being survivable, review the transaction changes: on `datasource.type: json` transactions are now staged, atomic across a plugin's tables and durable once `commit` returns; a plain JSON write can wait up to 5 seconds for a transaction's table lock; a transaction begun by `DataOperator#transaction(...)` locks each table at its first read, as 6.2.5 ran each callback alone per operator, so a read inside it can wait up to 5 seconds too; a `@Transactional` method that computes a write from a value it read uses `updateIf` or `@Transactional(isolation = Isolation.REPEATABLE_READ)`; and on every backend a failing joined method or nested scope marks the whole transaction rollback-only, so the outermost commit throws `UnexpectedRollbackException` (see [JSON transactions](#json-transactions-630--staged-atomic-and-durable), [A failing joined `@Transactional` method](#a-failing-joined-transactional-method-rolls-back-the-whole-transaction-630--changed-without-a-migration-period) and [A failed nested `DataOperator#transaction(...)`](#a-failed-nested-dataoperatortransaction-marks-the-whole-transaction-rollback-only-630)). If you also run the server, read the operator guidance in the JSON transactions section: back up `.ultitools-journal` with the tables, never delete it, and run one server per data folder.

### 中文补充：模块从 6.2.5 升级到 6.3.0 的检查清单

模块作者需要做的事，按实际操作顺序排列。每一项是一个动作，并链接到详细说明它的章节；本清单较简略之处，以下文各章节为准。

1. 把构建中的 `UltiTools-API` 依赖升到 6.3.0，保持 `provided`（Maven）或 `compileOnly`（Gradle），并重新编译；针对 6.2.5 编译的 JAR 不能原样放到 6.3.0 上运行（见 [Declaring the dependency](#declaring-the-dependency) 和 [What this means for you](#what-this-means-for-you)）。
2. 与依赖版本一起，把模块 `plugin.yml` 的 `api-version` 设为 `630`：这是框架唯一检查的数字，旧框架会拒绝声明更高级别的模块，`/upm` 和 UltiCloud 目录也只提供、只安装声明 `630` 到框架自身级别之间的模块（见 [What this means for you](#what-this-means-for-you)）。
3. 把覆写的 `unregisterSelf()` 改名为 `onUnregister()`、`reloadSelf()` 改名为 `onReload()`，只打日志的覆写直接删除；这两个方法在 6.3.0 为 `final`，针对 6.2.5 构建且覆写了其中之一的 JAR 加载时会以 `IncompatibleClassChangeError` 被拒绝（见 [Third occurrence: 6.2.5 → 6.3.0, a MINOR](#third-occurrence-625--630-a-minor)）。从改名后的钩子中删除所有 `super.unregisterSelf()` 调用，保留模块自己的实际清理逻辑；框架会在 `onUnregister()` 返回后注销命令和监听器。
4. 把所有命令类从 `AbstractCommandExecutor` 改为继承 `BaseCommandExecutor` 并实现 `handleHelp(CommandSender)`；旧基类、拼错名字的兼容类和按包扫描的 `CommandManager.registerAll(UltiToolsPlugin, String)` 重载都已删除（见 [Migrating off `AbstractCommandExecutor`](#migrating-off-abstractcommandexecutor)）。
5. 把调用到的其他已删除 API 换成文中指明的替代——例如 `UltiTools#getEconomy()` 换成 `EconomyUtils.getEconomy()`，`AbstractDataEntity` 换成 `BaseDataEntity`，`DependenceManagers#getAdventure()` 换成 Paper 原生的 `Player#sendMessage(Component)`——并对照生成的登记表和例外清单逐项检查（见 [Removal list for 6.3.0](#removal-list-for-630) 和 [Same-release exceptions applied in 6.3.0](#same-release-exceptions-applied-in-630)）。
6. 用 `isPresentInFile(String)` 替代 `AbstractConfigEntity#getConfig()` 做存在性判断，读取或修改声明的字段后调用 `save()`，不要再直接改 Bukkit 的存储（见 [Removed mutable configuration accessor](#removed-mutable-configuration-accessor)）。
7. 在改动发生时就保存配置——模块自己的改动用 `save()`，服主命令指定的内容用 `saveOperatorChange`/`saveOperatorMapEntry`——因为服务器关闭、模块卸载或替换时都不再写入任何配置，`save()` 也只写模块改过的内容（见 [Configuration release; nothing written at stop](#configuration-release-nothing-written-at-stop) 和 [Declared types, whole map keys and persistence](#declared-types-whole-map-keys-and-persistence)）。
8. 把每个旧解析器（`@ConfigEntry(parser = …)`、`Parser`、`ObjectConfigSerializer`、`ConfigParser`、`DefaultConfigParser`、`StringHashMapParser`）改写为扫描包内带 `@ConfigConverterFor` 的 public 顶层 `ConfigConverter`，并为框架不支持的声明字段类型注册转换器，否则模块会被拒绝；旧解析器在 6.3.0 弃用，公告在 6.4.0 删除（见 [Migrating legacy parsers to converters](#migrating-legacy-parsers-to-converters)）。
9. 字段使用 `@Scheduled(config = …, periodKey/delayKey = …)` 或 `@CmdCD(config = …, key = …)` 时，声明 `api-version: 630`，绑定配置的 `@Scheduled` 保持同步，并删除该字段上的 `@Range`（由绑定自带的范围取代）（见 [A floor no linker enforces](#a-floor-no-linker-enforces-config-bound-scheduled-and-cmdcd-630)）。
10. 检查依赖“延后一刻执行”的命令代码：同步命令体现在于分发时立即执行，因此在 `InventoryClickEvent` 中打开或关闭物品栏、或分发命令的代码要用 `runTask` 推迟，分发自身命令的命令体会在同一刻遇到 `@UsageLimit` 和 `@CmdCD`（见 [Command bodies run at dispatch](#command-bodies-run-at-dispatch-630--changed-without-a-migration-period)）。
11. 查看未设过渡期就生效的运行时变化，调整依赖旧行为的代码——例如 JSON 存储的读取现在返回独立副本，改动必须调用 `update(...)` 才会保存；`PluginManager#getPluginList()` 返回不可修改的快照；列表、集合和映射上的 `@NotEmpty` 现在生效；框架无法检查的约束注解会让模块在加载时被拒绝；违反 `@Range`、`@Size`、`@Pattern` 或文本上 `@NotEmpty` 的值现在会让模块加载被拒绝（`/ul reload` 时拒绝这次重载），而 6.2.5 会把它重置为默认值并保存文件（见 [Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period)）。
12. 不要把框架打进自己的 JAR；模块用到 XSeries 时请自行以 `provided`/`compileOnly` 声明（`com.github.cryptomorin:XSeries:13.7.1` 或你自己固定的版本）：框架不再内嵌它，但仍通过自己的 `libraries:` 在运行时提供，Gson、MySQL Connector/J、protobuf 和 slf4j 由服务器所用的 Paper 构建提供（见 [Where runtime dependencies come from](#where-runtime-dependencies-come-from)）。
13. 了解框架本身的服务器要求：Paper 1.19.2 build 163 或更新，Paper 1.20.5 以下用 Java 17，1.20.5 起用 Java 21；UltiKits 官方模块只写明它们支持的 UltiTools-API 版本（`api-version: 630` 即 6.3.0 或更高）（见 [Support matrix](#support-matrix)）。
14. 如果模块在 JSON 存储上使用 `DataOperator#transaction(...)` 或 `@Transactional`，或者依赖“失败的加入方 `@Transactional` 方法 / 失败的嵌套 `DataOperator#transaction(...)` 作用域可以被调用方承受”，请检查事务相关的变化：`datasource.type: json` 下的事务现在是暂存的、跨同一插件各表原子的，并且在 `commit` 返回后即持久；普通 JSON 写入可能为等待事务的表锁而停顿最多 5 秒；由 `DataOperator#transaction(...)` 开启的事务在第一次读取某张表时就锁住它（与 6.2.5 中每个回调按操作器逐个运行一致），所以其中的读取也可能停顿最多 5 秒；凭读到的值计算写入的 `@Transactional` 方法应使用 `updateIf` 或 `@Transactional(isolation = Isolation.REPEATABLE_READ)`；在所有后端上，失败的加入方方法或嵌套作用域都会把整个事务标记为仅回滚，最外层提交会抛出 `UnexpectedRollbackException`（见 [JSON transactions](#json-transactions-630--staged-atomic-and-durable)、[A failing joined `@Transactional` method](#a-failing-joined-transactional-method-rolls-back-the-whole-transaction-630--changed-without-a-migration-period) 和 [A failed nested `DataOperator#transaction(...)`](#a-failed-nested-dataoperatortransaction-marks-the-whole-transaction-rollback-only-630)）。如果你同时运营服务器，请阅读 JSON 事务一节中的服主须知：把 `.ultitools-journal` 与各表一起备份、永远不要删除它，并且一个数据文件夹只运行一个服务器。

## Config layer (6.3.0)

This section is the configuration migration contract **as of v6.3.0**. The new public extension
point is `com.ultikits.ultitools.config.convert.ConfigConverter<T>`, discovered with
`@ConfigConverterFor`; the document tree and writer remain internal implementation details.
Third-party modules must rebuild if they used the removed accessor, and register converters for
unsupported declared field types before configuration initialization.

### The write contract (read this first)

**Configuration an operator wrote is never overwritten automatically** (the maintainer's foundational rule of
2026-10-04). No framework or module code path may change content an operator wrote in a configuration file unless the
operator explicitly asked for that change. A value the framework cannot use is surfaced before code relies on it - the
value is refused with a warning and its declared default runs, or the module or the start is refused - and is never
"fixed" by rewriting the file. Everything else in this chapter is a consequence of this rule.

What code may write, by file type (the maintainer's table of 2026-10-04):

| File | Code may write | Code never writes |
|---|---|---|
| A **shipped** configuration file (a module's `config.yml` - for example UltiEconomy's, which holds the interest settings - `spawn.yml`, the framework's own `config.yml`) | (1) the file itself, the first time, when it does not exist; (2) a declared key the file lacks, and the framework's own comment lines - inserted only, no existing content changed; (3) exactly the item the operator explicitly asked to change - a command (`/setspawn` writes only the location, `/autoreply` only that rule), a panel edit (only the named key), or a change the operator made through the module and the module saves; (4) a value that still equals the shipped factory text byte for byte, re-rendered after a language switch, and a line that is byte for byte unedited legacy shipped text the module removes | a save at server stop, module unload or replacement; a whole-object "incidental" save; a repair of an invalid value; any layout or formatting normalization; the 6.2 split-dotted-key cleanup; a comment the operator wrote |
| An **operator-created** file (a kit, a menu) | the file, only on the operator's explicit create action (`/kit create`); on an explicit edit, only the edited part (the kit editor writes that kit's items; comments and other keys stay) | anything else |

**One gate, one outcome.** Every write to an operator-editable configuration file goes through one framework write gate
(`OperatorFileWriter`, internal): the write declares the keys (or the framework's own comment lines) it owns; after
rendering, every byte outside them must equal the file as read, and the file must still hold the bytes it was read from.
Otherwise nothing is written: one WARNING names the file, the keys and the reason - for a layout the renderer cannot keep,
the line number to fix (see "Layouts the write gate cannot keep") - and never a value, and the program keeps its
in-memory value. A module API call the operator asked for (`saveOperatorChange`, `saveOperatorMapEntry`) throws
`ConfigWriteRefusedException` instead, so the command can reply that nothing was saved and why; a panel edit gets an
error reply naming the reason. Configuration data is not moved out of configuration files.

What an operator does about a refusal: read the WARNING, fix the line it names (or, for "the file changed since it was
read", run `/ul reload` first), and repeat the change; nothing they wrote has been lost.

A write that is not refused but fails to publish throws a plain `IOException`. The file then also holds its previous
bytes. A write that failed part-way after the file was opened is put back from its framework backup before the exception
is thrown; if even that fails, one SEVERE names the file and the kept backup, and the configuration refuses every write
until a reload (see "Rendering and save fallback", [#622](https://github.com/UltiKits/UltiTools-Reborn/issues/622)). So
a module that rolls its in-memory change back on the `IOException` stays in step with the file.

**A section emptied by deleting its keys is completed again** (as of v6.3.0,
[#620](https://github.com/UltiKits/UltiTools-Reborn/issues/620)). Deleting a key is how an operator resets a setting: the
next start puts the key back with its declared default and comment. When the deleted key was the last one of its section,
the section key is left alone on its line with nothing after the colon (`messages:`), which reads as no value at all. The
maintainer's decision of 2026-10-06 treats that as most likely accidental, so the framework puts the section's keys back
below that line instead of refusing at every start. It is the one line outside a write's own keys that the gate lets the
write touch, and only in this shape: the line keeps the key as written (its indentation and quotes included), the comment
text and the line ending; only whitespace on it may be normalized - a space before the colon, the spaces before the
comment, and trailing spaces after the comment or at the end of a line without one (`messages :   # reset  ` comes back
as `messages: # reset`). The section's value goes from nothing to exactly the inserted keys, and every other line stays
byte for byte. A child the operator commented out under the line stays where it is, below the restored keys, when its
comment lines follow the section line directly, as one run without a blank line, at the file's indentation for that level
(two spaces in a two-space file). Three shapes are refused instead, at every start, with one WARNING naming the line and
the file unchanged: a blank line between the section line and the commented child; two commented runs separated by a
blank line; a child commented out deeper than the file's indentation (four spaces in a two-space file). Removing the
blank line, or moving the comment to the file's indentation, lets the next start put the keys back. The same holds for
an operator command, an operator change and a panel edit that add a key below such a line - for example the first rule
added to an emptied `rules:`. A section written with an explicit empty value - `messages: {}`, `messages: ~` or
`messages: null` - is the operator's own value and is never changed: an insert below it is refused, and the warning says
what to do ([#610](https://github.com/UltiKits/UltiTools-Reborn/issues/610) item 2; see "Layouts the write gate cannot
keep").

**For operators: to leave a text setting blank, write an empty string; do not delete the key** (as of v6.3.0,
maintainer decision of 2026-10-06). Write `key: ''`: for a text setting, an empty string is read as blank and is never
rewritten - not by a start, a reload, or a save of another setting. A deleted key is put back with its declared default on
the next start, which is how to reset a setting, not how to blank it. A number, true/false or other non-text setting has
no blank value: `count: ''` is reported in one WARNING naming the key (`declared as int: found text …`), its declared
default is used in memory, and the file keeps `''` as written.

The public surface of the contract: `AbstractConfigEntity#save()` (only what the module changed, where the file still
holds what was read), `#saveOperatorChange(String...)` and `#saveOperatorMapEntry(String, String...)` (exactly what an
operator's command names), `#saveOperatorMapEntry(EntryPresence, String, String...)` (the same, only while the entry is, or
is not, in the file: [#623](https://github.com/UltiKits/UltiTools-Reborn/issues/623)),
`com.ultikits.ultitools.config.OperatorFiles` (module-managed operator files such as kits), and
`com.ultikits.ultitools.config.ConfigWriteRefusedException` with its subtype `ConfigEntryPresenceException`. Outside YAML, a panel edit of `server.properties` replaces the
value text of the one line it names, and the framework leaves the shared `plugins/bStats/config.yml` byte for byte unless
it is absent (see "Behavioral changes that need no migration period").

**Official language files are framework-owned** - the one exception to the rule (maintainer decision of 2026-10-04). The
`lang/` files the framework and modules extract may be replaced on upgrade, with a backup and a log line; editing one in
place is not a supported way to customise text. Customise by copying an official file under a new name, editing the copy
and selecting it in the main configuration; the copy is operator-owned and never written. The custom-file selection and
its fallback for missing keys are completed in this release
([#608](https://github.com/UltiKits/UltiTools-Reborn/issues/608)). Framework-owned files besides these (the
`.ultitools-backup-<hex>` backups, `.ultitools-resource-hashes.json`, the credential store, module JARs and transaction
records) are not operator configuration.

中文补充（写入约定）：**服主写的配置绝不被自动覆盖**（维护者 2026-10-04 的底层规则）。框架或模块的任何代码都不能改动服主在配置文件里写的内容，除非服主明确要求这一改动；框架用不了的值在代码使用前就挑明（警告并拒绝该值、使用声明默认值，或拒绝模块、拒绝启动），绝不靠改写文件“修好”。按文件类型（维护者 2026-10-04 批准的表）：**出厂配置文件**（主配置（例如保存利息设置的 UltiEconomy `config.yml`）、`spawn.yml`、框架自己的 `config.yml` 等）代码只能（1）首次创建不存在的文件；（2）补入文件缺少的声明键和框架自己的注释——只插入，不改已有内容；（3）只写服主明确要求改的那一项（命令、面板、GUI，或服主通过模块做出、由模块保存的改动）；（4）语言切换后重新渲染仍与出厂文字逐字节相同的值，以及删除逐字节未改过的旧版出厂文字行。除此之外一律不写：不在关服、卸载或替换时保存，不做整对象的顺带保存，不修复无效值，不规整排版，不清理 6.2 拆开的含点键，不改服主写的注释。**服主自己创建的文件**（礼包、菜单）只在服主明确创建时创建、明确编辑时只写编辑的部分。所有写入经同一个写入闸门：每次写入声明自己拥有的键（或框架自己的注释行），渲染后其余每个字节必须与读取时相同，且文件仍是读取时的内容；否则不写，一条 WARNING 列出文件、键和原因（排版无法保留时给出要改的行号），从不列值，内存中的值照常使用。服主命令调用的 `saveOperatorChange`/`saveOperatorMapEntry` 被拒绝时抛 `ConfigWriteRefusedException`，命令据此回复“未保存”及原因；面板编辑收到注明原因的错误。服主看到拒绝时：按警告改掉它指出的那一行（“文件在读取后已被改动”则先 `/ul reload`），再做一次改动即可，所写内容没有丢失。写入未被拒绝、但发布失败时抛出普通 `IOException`，文件同样保持原来的字节：打开文件后中途失败的写入会在抛出异常前从框架备份放回；连放回也失败时，一条 SEVERE 列出文件和保留的备份，该配置在重载前拒绝一切写入（见 "Rendering and save fallback" 一节，#622）。在该 `IOException` 时回滚内存改动的模块因此与文件保持一致。**官方语言文件归框架所有**，是唯一例外（维护者 2026-10-04 决定）：升级时可能被替换（保留备份并记日志），直接改官方文件不是受支持的定制方式；要定制，请复制官方文件、改名、编辑副本并在主配置中选择它，该副本归服主所有、永不被写入（自定义文件的选择与缺失键的回退在本版本内完成，#608）。

中文补充（被清空的节）：删除一个键就是把该设置重置为默认值，下次启动时框架会连同注释把它补回。如果删掉的是某个节里最后一个键，节的键名会单独留在一行、冒号后什么都没有（`messages:`），读作“没有值”。按维护者 2026-10-06 的决定，这多半是误删，框架会把该节的键补回到这一行下面，而不是每次启动都拒绝写入（自 6.3.0 起，#620）。这是闸门允许一次写入触碰的、写入自身键之外的唯一一行，而且只限这种形状：该行按原样保留键（包括缩进和引号）、注释文字和换行符，只有空白可能被规整——冒号前的空格、注释前的空格，以及注释后或无注释行末尾的空格（`messages :   # reset  ` 补键后成为 `messages: # reset`）；节的值只会从“没有值”变成恰好是补入的那些键；其余每一行逐字节不变。服主在这一行下面注释掉的子键行留在原处、位于补回的键下面，前提是这些注释行紧跟在节所在行之后、连成一段中间没有空行，并且位于文件在该层级的缩进处（两空格缩进的文件里就是两个空格）。以下三种形状则会在每次启动时被拒绝，一条 WARNING 写明行号，文件不变：节所在行与注释掉的子键之间有空行；两段注释之间隔着空行；子键被注释在比文件缩进更深的位置（两空格文件里的四个空格）。删掉空行或把注释移到文件的缩进处，下次启动就会补回键。服主命令、服主改动和面板编辑在这样一行下面添加键时同样如此，例如在被清空的 `rules:` 下添加第一条规则。写成显式空值的节（`messages: {}`、`messages: ~` 或 `messages: null`）是服主自己写的值，绝不会被改动：在它下面插入会被拒绝，警告会写明该节及其行号，并说明可以怎么做——删掉这个值、让该行在冒号处结束（下次启动即补回键），或手动添加这些键（#610 第 2 项）。

中文补充（给服主）：**要让某个文本设置留空，请写空字符串，不要删除这个键**（自 6.3.0 起，维护者 2026-10-06 决定）。写成 `key: ''`：对文本设置，空字符串读作“留空”，启动、重载或保存其他设置时都不会改写它。删除的键会在下次启动时以声明的默认值补回——这是重置设置的方法，不是留空的方法。数字、true/false 等非文本设置没有“留空”的值：`count: ''` 会以一条点名该键的 WARNING 报告（`declared as int: found text …`），内存中使用声明默认值，文件里的 `''` 原样保留。

### Rendering and save fallback

The internal config storage layer renders the whole YAML document through SnakeYAML, preserving content, comment text and key
order. Its existing line-terminator, BOM, final-newline and supported indentation-style rules remain in effect.

As of 6.3.0 every automatic write to a module configuration file - creating a file that does not exist, inserting a declared
key the file lacks, rewriting the framework's own token comments at start-up or reload, and the registration batch flush - goes
through one write gate. Each write declares the keys it owns; after rendering, every line outside them must be byte-identical
to the file as read (line terminators, BOM and final line break included), and the file must still hold the bytes it was read
from. Otherwise nothing is written, one warning names the file, the keys and the reason (never a value), and the declared
defaults run in memory. These writes never normalize layout: on a hand-aligned file a missing key stays missing, with a warning
at each start, until the operator adds it. A file using anchors, aliases or merge keys is never written automatically and is
named once per server run. A file the framework creates is created exclusively and never replaces a file that appeared
meanwhile. The residual window between the gate's last read and the replacement cannot be closed between an editor and the JVM.

A module's explicit save, an operator change written through `saveOperatorChange`/`saveOperatorMapEntry`, and a panel edit
go through the same gate, each owning only the settings or map entries it writes (see "Declared types, whole map keys and
persistence", [#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599),
[#600](https://github.com/UltiKits/UltiTools-Reborn/issues/600)). No write normalizes layout or expands anchors any more: a
panel edit on a file the gate refuses replies with an error naming the reason, and the file keeps its bytes. Comments on
individual list items are kept only while the list keeps its length — the same as Bukkit, which keeps none. The storage API
signatures are unchanged.

#### Layouts the write gate cannot keep

The renderer writes a document back in one canonical layout. Wherever a file's layout differs from it, the gate cannot
keep those bytes, so **every** gated write to that file is refused - a first-start insert, a comment rewrite, a save, an
operator change or a panel edit - whichever setting it changes. Refusing is deliberate: the rule forbids normalizing an
operator's layout to make a write pass (orchestrator decision of 2026-10-05 under the maintainer's delegation). The file
keeps its bytes, the declared or in-memory values run, and the warning names the keys and the reason with the line to fix:
`the file's layout outside the keys this write owns would change (line N)`, or, for a key sharing its line with another,
`a key this write owns shares line N with a key it does not own`. An insert below a section written as an explicit empty
value (`{}`, `~`, `null`, `!!null`) is refused as `the section <key> (line N) is written as an explicit empty value, which the
framework does not change; to have keys added below it, delete that value so the line ends after the colon, or add the keys
by hand` (as of v6.3.0, [#610](https://github.com/UltiKits/UltiTools-Reborn/issues/610) item 2). A section left with no value at all (`messages:`) is not refused: the keys
are put back below it ([#620](https://github.com/UltiKits/UltiTools-Reborn/issues/620), "The write contract"). Measured
layouts that refuse (each pinned by a test, `OperatorFileWriterLayoutRefusalListTest`):

- a line holding only spaces, and a file holding only spaces;
- a trailing space after a value or after a section key (`interval: 300 `);
- an inline comment aligned with several spaces (`enabled: true    # note`; one space before `#` is kept);
- more than one space after a colon (`key:  value`), and spaces inside flow brackets (`worlds: [ world ]`);
- a document start marker `---` or end marker `...`;
- a block scalar value (`|` or `>`) followed by a blank line;
- in a file without a final line break, a last value written as a block scalar with `|`, `|+` or `>`: at the end of
  such a file its value has no final line break, so it is written back as `|-` or `>-` (a block scalar anywhere else
  in such a file, or a last one written `|-` or `>-`, is kept - except that a write which sets the file's last value
  to text ending in a line break is refused, naming that line; no value is ever changed);
- a comment that closes a section after a blank line, indented deeper than the key that follows it, and any comment
  indented deeper than the key below it (including in a comment-only file);
- two indentation widths in one file (two spaces in one section, four in another);
- mixed line endings (CRLF on some lines, LF on others);
- a plain value continued onto the next line, a tab inside a value, an explicit `? key`, an explicit tag (`!!str`);
- a file holding only a byte-order mark (an emptied file saved as UTF-8 with BOM; the reason names the mark);
- an entry holding `null` with an inline comment (`foo: ~  # placeholder`) that a write replaces with a map: the YAML
  library cannot emit the comment there, so the write is refused naming that key
  ([#624](https://github.com/UltiKits/UltiTools-Reborn/issues/624), pinned by `WriteGateRuntimeFailureTest`);
- an insert directly after a key left with no value (`c:` with nothing after the colon): a missing setting whose place
  is right after such a key is refused at every start with `a key this write owns shares line N with a key it does not
  own`, because SnakeYAML places an empty value on the next line
  ([#628](https://github.com/UltiKits/UltiTools-Reborn/issues/628), open). To leave a text setting blank, write
  `key: ''` - that shape is kept and does not refuse.

Kept byte for byte (a write elsewhere goes through): one space before an inline comment, a block scalar without a blank
line after it (including a comment line right after it, which is read as the comment of the key or list item below it,
exactly as after a plain value - [#592](https://github.com/UltiKits/UltiTools-Reborn/issues/592)), flow maps and flow lists without inner spaces, quoted values, consistent four-space indentation, list
items at the key's column, CRLF or LF throughout, a missing final line break (also in a file holding block scalars, apart from the last-value case
above), a byte-order mark, `#comment` without a
space, several blank lines, hexadecimal numbers, `~` and empty values. A file using anchors, aliases or merge keys is
refused as a whole (above), not by line.

Measured frequency (the real gate, inserting a key): none of the 32 YAML files shipped by the framework and the
fifteen modules refuses (a whitespace-only line in two official language files, which are framework-owned and not written
through the gate, is the only hit), and none of 3,710 configuration YAML files under `plugins/UltiTools/` on the
maintainer's test servers refused (measured 2026-10-05, during 6.3.0 development). The one shipped file known to refuse is
the framework `config.yml` of every 6.0.x release before 6.0.9 (6.0.0 to 6.0.8)
(see the framework `config.yml` entry under "Behavioral changes that need no migration period"). Fixing the named line -
removing the trailing space, the extra blank line or the alignment - lets the next write through.

A 0-byte file, a file of blank lines only, and a file holding only comments at the start of their lines (optionally after
blank lines) is an empty document to the gate: start-up inserts the declared keys after the existing bytes (there is no
other byte to keep), and an operator change or a panel edit inserts the setting it names; a save never inserts. A file of
spaces, a comment-only file with an indented comment and a BOM-only file are refused as layouts (above), with defaults in
memory and the module loaded; a file holding only a tab, `---` or `...` cannot be parsed and is protected as an
unparseable file (never written). So are the two layouts under "Layouts the reader cannot read" below
([#580](https://github.com/UltiKits/UltiTools-Reborn/issues/580), [#617](https://github.com/UltiKits/UltiTools-Reborn/issues/617)). An operator change or a panel edit of a file deleted since it was read creates the file,
exclusively, holding only the named settings with their comments.

A runtime failure of the YAML library inside the gate is a refusal like any layout the gate cannot keep
([#624](https://github.com/UltiKits/UltiTools-Reborn/issues/624)). This covers a failure while a write's edit is applied
to the document, while the document is rendered, and while the rendering is parsed back for the self-check, on every gated
write. Nothing is written. The gate's one warning names the file, the keys, the step that failed and the failure's
class, never the failure's message, which may quote the file: for example `the file cannot be written at
autoreply.rules.foo: rendering the document failed (EmitterException)`. Any other unchecked failure in the same steps,
reading the file included, is refused the same way. An explicit write (`saveOperatorChange`, `saveOperatorMapEntry`, a panel edit) throws
`ConfigWriteRefusedException` naming the keys. An automatic write keeps its declared defaults in memory, as for any refusal.
No runtime exception from the library reaches the caller, so a module that rolls back on the documented `IOException` stays
in step with the file. Any other unchecked failure at start-up
never refuses the module: one warning names the file and the failure's class, and the declared defaults run in memory.

#### Layouts the reader cannot read

Two valid YAML layouts cannot be read by the framework's comment-preserving reader (SnakeYAML 2.2 with comments enabled,
the version Paper ships). The framework treats such a file as **unparseable**: it is never written (no insert, comment
rewrite, save, operator change or panel edit touches it), the module runs on its declared defaults at start-up (a reload
keeps the running values), and the existing SEVERE line names the file: `Cannot load <file>: <parser location>; file will
not be overwritten`. Nothing is lost - the file keeps every byte - but the operator's values in it are not used until the
layout is changed. These are documented limitations of 6.3.0 (maintainer decisions of 2026-10-05: document only, no
reader change, no upstream report).

- **A block anchor with a comment before its first child**
  ([#580](https://github.com/UltiKits/UltiTools-Reborn/issues/580)):

  ```yaml
  defaults: &defaults
    # Anchor setting note
    setting: inherited
  ```

  SnakeYAML's comment-aware composer reports `expected <block end>, but found '<block mapping start>'` at the first
  child (the server log names only the line and column). To make the file readable, move the comment above the anchored key (`# Anchor setting note` before
  `defaults: &defaults`) or after the first child. (A file that does parse with anchors, aliases or merge keys is read,
  and refused as a whole by the write gate - see above.)
- **A block scalar followed by a comment, a blank line and another comment**
  ([#617](https://github.com/UltiKits/UltiTools-Reborn/issues/617)):

  ```yaml
  a:
    content: |-
      one
    # n

    # m
    b: x
  ```

  SnakeYAML 2.2 throws a `ClassCastException` inside its composer on this layout. To make the file readable, remove
  the blank line between the two comments (or one of the two comments).

Saving first attempts a forced same-directory temporary file and atomic replacement. Only an unsupported atomic move, EBUSY,
EXDEV, or a permission/read-only refusal to create the temporary file allows the narrow fallback: exclusively create a backup
named `<file name>.ultitools-backup-<16 lower-case hex>`, copy and force the existing target's bytes, then overwrite and force the
existing target in place. If this server run already wrote such a backup for the same file and it still holds exactly the bytes
written, it is refreshed from the current target through a forced same-directory temporary and atomic replacement before the
target is opened. A backup creation/write/force or refresh rename failure refuses the save before the target is touched and
preserves the previous backup. No old-backup restoration or validation of the current target is implied by this refresh. Other
staging or move failures refuse the save. A fallback attempt logs one warning identifying the target, backup, cause and outcome.
Symbolic links remain links, with the backup beside the resolved target. The writer reads, writes or deletes no other name: an
operator's own `<file>.bak` is never touched.

As of 6.3.0, a failure after the in-place write has opened the target no longer leaves a partial target
([#622](https://github.com/UltiKits/UltiTools-Reborn/issues/622)). Such a failure is a write that stops part-way, or a
failed force or close. The framework first puts the target back: it writes in place the bytes the target held
immediately before, which are exactly what the forced backup holds. It writes in place because the atomic move this
fallback stands in for was refused. It then removes that backup, and only then reports the `IOException`. So the file
holds what it held before the write, and agrees with a module that rolls its change back on that exception. A panel batch
puts back every file it opened and reports failure ([#582](https://github.com/UltiKits/UltiTools-Reborn/issues/582)).
If putting the target back fails too, one SEVERE names the file and the backup, never content. The backup is kept, and no
load deletes it. The configuration that wrote the file then treats it as changed since it was read, until a reload reads
it again: `isFileModifiedSinceSnapshot()` is true; an operator change or a panel edit is refused with
`ConfigWriteRefusedException` ("the file changed since it was read: …"); and `save()` writes nothing, with one WARNING
naming the keys. To resolve it, compare the file with the backup, copy the backup over the file if needed, then reload. A
backup that served a successful in-place write is removed only after the next successful strict UTF-8 storage load, and
only while its bytes still match what the writer recorded; unreadable or unparseable files keep it. A file of the backup pattern that this server run did not write, or whose bytes changed
after it was written, is kept and named once at INFO; delete it yourself once it is no longer needed. Backup cleanup is best
effort and cannot turn a successful load into a failure. Apart from putting back the write that just failed, no restoration
from an older backup is attempted.

### Declared types, whole map keys and persistence

`AbstractConfigEntity` uses the document, converter registry and atomic writer for initial defaults,
explicit saves and panel updates. Typed collections resolve their full inherited generic types:
convertible values bind, invalid collection/map elements are skipped with a located warning, and an
invalid field value falls back to its initially declared default. Invalid reload values use that
default too. A key missing on reload binds its declared default - with one warning naming the key when the previous load
still found it, so an operator who deletes a key to reset it is told - unless the module changed that setting since the
last load or save, which keeps the module's value; the file is not changed either way, and
no later save re-adds the key ([#596](https://github.com/UltiKits/UltiTools-Reborn/issues/596)).
Warnings name the file, key and failed position/type and, as of 6.3.0 (#590), the converter's own
reason (the message it gave `ConversionException`, without the location prefix) as `(reason: ...)`;
secret-shaped values and nested credentials are redacted, the reason with them, and a multi-line
reason is kept on one log line. Unsupported declared types fail preflight before the file is read or created; register
`@ConfigConverterFor` or declare a supported plain-data shape. The built-in Bukkit serialization
fallback requires a registered alias; registered custom converters keep ownership of their types.
For every value `x` of the declared type whose collections and arrays contain no null element,
`fromPlain(toPlain(x))` equals `x`. Typed collections (including `List<Object>`) and reference arrays
(including `Object[]`) omit null elements on write with one located warning per field. Reading keeps
its existing skip/warning behavior; plain data in a declared `Object` slot is unchanged.
Null map values and null whole fields still round-trip. For every canonical
plain value `p` emitted by the converter (`p = toPlain(x)`), `toPlain(fromPlain(p))` equals `p`.
A converter may accept noncanonical input `q`; `toPlain(fromPlain(q))` is its canonical form (map keys excepted: a key the
file already holds keeps the file's text, see the typed-key paragraph below), and normalization is stable:
`fromPlain(toPlain(fromPlain(q)))` equals `fromPlain(q)`. Approved coercions
(number to String, numeric text to int/float, `"false"` to boolean and duplicate elements to a Set)
remain unchanged. 6.2's default parser stored every list element as text, so a 6.2-saved
`List<Integer>` reads `- '60'`; 6.3.0 binds it as the number 60 and writes `- 60` the next time
its module changes that setting and saves it, or a panel edit sets it. Loading, reloading, a save that
did not change that setting and server stop never rewrite such a file on their own. Equality is semantic value comparison, not object identity; numeric plain values
compare by value. Reload merges and panel leaf edits rely on forward equality. A converter that adds
a value during reading without undoing that change during writing violates this contract.

Whole map keys, including `g.m`, `o.O` and `wave.`, are supported as of 6.3.0.
`@ConfigEntry.path` still splits at every dot: `chat.aliases` selects nested settings, whereas a
key `o.O` inside the bound map is one whole key. These are different path conventions. Legacy 6.2 files in which Bukkit
split a dotted key into nested mappings are read as they are, never automatically merged or renamed, and a save never removes
such an entry: it stays until the operator removes it (maintainer decision of 2026-10-04).

A map declared with non-text keys - `Integer`, `Long`, `Boolean`, `UUID` and the other scalar key types - loads and writes
as of 6.3.0 ([#673](https://github.com/UltiKits/UltiTools-Reborn/issues/673)). An entry the file already holds is always
written under the key text the file holds: a quoted (`'01'`), whole dotted (`1.0`), exponent (`1.0e10`), alternative
(`yes`, `off`) or upper-case spelling stays as written. Only a key the file lacks is written in its canonical text, and only
when that text reads back to the same key (text that would load as a number is quoted, such as `'25565'`). A save, a panel
edit, `saveOperatorChange` and `saveOperatorMapEntry` therefore never rename, requote, reorder or restructure an existing
key, and removing an entry removes exactly its line. `saveOperatorMapEntry`, with or without an `EntryPresence` condition,
accepts the key's canonical text: `saveOperatorMapEntry("limits", "1")` reaches the entry the file spells `'01'`. A key the
operator respells by hand (`'01'` to `1`) is adopted on the next reload, without a conflict and without a write.

When a file holds one such key in several spellings, such as `1:` and `'01':` in a `Map<Integer, String>`, the module still
loads with the existing warning and binds the first spelling, but every write of that setting is refused until the operator
deletes all but one spelling and reloads: a save names the setting's changes in one warning as not written, and an operator
command or a panel edit throws `ConfigWriteRefusedException`. Both reasons name the file, the setting and the key (shown as
`<redacted>` below a secret-shaped key), never a value. The check reads the file's own keys, so it applies even when the
module removed that key or emptied the map. The file's other settings are written normally. A setting that names its own
legacy parser (`@ConfigEntry(parser = ...)`) is outside both rules: its parser alone decides which file keys are one key, so
`1:` and `'01':` may be two keys to it, no several-spellings refusal applies, and `saveOperatorMapEntry` uses the key text
exactly as given.

Known limits of typed map keys. (1) If the operator respells a key by hand (`'01':` to `1:`) while the module holds an
unsaved change to that same entry, the next reload keeps the file (the operator's bytes win, with the existing conflict
warning) and the module's unsaved change to that entry is dropped; save module changes before hand-editing that entry.
(2) If the operator adds a key by hand in one spelling (`'01':`) and the module writes the same key before the next reload,
a second spelling (`1:`) can be inserted; the file then holds one key in two spellings and further writes of that setting
are refused with the several-spellings warning above until the operator removes one and reloads, so reload after
hand-editing map keys before the module writes them. (3) For a `SortedMap` whose key type compares different texts as one key
(`BigDecimal` `1.0` and `1.00`), the several-spellings check does not detect the two spellings as one key, because it
matches keys with `equals`; no shipped module uses such a map, so avoid writing one key in two numeric spellings.

A declared setting written as a flat dotted key is that setting, as of 6.3.0 (#612). When a module declares
`@ConfigEntry(path = "features.chat")` and the file holds `features.chat: false` instead of `features:` / `chat: false`,
the module binds `false`, as 6.2 did through Bukkit; the same holds for any split of the path's dots (`a.b:` / `c: 1`
for `a.b.c`). The framework never adds a nested copy of such a setting: a missing-key insert, a module save, an
operator command (`saveOperatorChange`, `saveOperatorMapEntry`) and a panel edit write the setting on the line that
holds it, through the write gate, and the panel shows it once under its setting path. `@ConditionalOnConfig` now reads
its key with the same document reader and the same rule instead of Bukkit's `YamlConfiguration`, so it reads the value
the module binds, before and after a start-up insert; only a boolean value counts, as before. `isPresentInFile` reads a
path the same way (a flat dotted key counts as present). A setting written in two forms in one file, such as
`features.chat: false` and `features:` / `chat: true`, is refused before module code runs: the module does not load
(a reload is refused and keeps the running values), and the message says the setting is written in two forms and names
the file and the setting path, never a value. The same holds for a path `@ConditionalOnConfig` reads that no config entity
declares: the module's component scan is refused.
Two forms holding the same value are refused too; measured on 3,710 live configuration files, none holds a setting
twice, and accepting equal copies would let a later write change one copy and refuse the module at the next start.
Delete one of the two lines and restart. This applies to declared setting paths only: keys inside a map value keep
their whole-key meaning (`o.O` above).
A setting the file lacks is inserted below the section the file already holds it in, never into a second form of that
section (as of v6.3.0, [#614](https://github.com/UltiKits/UltiTools-Reborn/issues/614)): below the longest prefix of the
setting path the file holds as a section (a mapping, or a key with no value), the rest of the path as nested keys. With
`a.b:` / `c: 3` and a declared `a.b.d`, the start-up insert writes `d:` under `a.b:`. When a declared setting of the same section is written with the rest of its path as
one dotted key below the section, such as `a:` / `  b.c: 3`, the missing `a.b.d` is written the same way beside it,
`  b.d: 2`, never as a nested `b:` / `d:` copy (maintainer decision, gate-1 review of the #614 fix); when such siblings are
written in different splits, the insert is refused as below. When the file holds that section in several forms, the setting goes below the one
form that holds another declared setting of the section; when none or more than one does, the insert is refused: the
WARNING (or, for an operator command, `ConfigWriteRefusedException`) names the file, the settings and the section, never a
value, and the declared default runs in memory. The same holds for an operator command, an operator change and a panel
edit that insert a setting.
For a `Map<String, String>` the wrongly shaped nested value is skipped with a warning.
Explicit `null` is stored and binds to reference fields (primitive null is a mismatch). UUIDs, enums,
sets and registered Bukkit values use converter plain output, not Java class tags. A Bukkit value
loaded into an `Object` slot remains a plain map. Unknown runtime objects refuse saves without
changing the file.

Panel JSON uses the same conversion path as file binding and saves: integral numbers within the long
range become plain `Long`; integers beyond the long range are kept exactly as `BigInteger`.
Fractional numbers keep the existing `Double` route, objects ordered maps and arrays lists before typed conversion.
A panel write persists only touched fields; unrelated unsaved code edits remain dirty. Validation
runs before any missing-key or panel persistence. Snapshots track successful effective values,
separately from the raw document and byte fingerprint. Reordering a map is dirty, but an order-only
save acknowledges its new effective order without changing operator file order, comments or bytes.

As of 6.3.0 a module's `save()` writes only what the module changed in memory since the last load or save, and only
where the file still holds what it was read with ([#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599);
maintainer decision of 2026-10-04, "what code may write, by file type"). A setting is written when all three hold: the
module changed it; the file still holds at that key exactly the value the framework last read or wrote there; and that
value is the one the module started from - it converts, without a conversion warning, to the setting's value as last loaded
or saved. A setting declared as a `Map` is written entry by entry, following the declared map types into nested maps: only
the entries the module added, changed or removed are set or removed (a map that is empty, or that the module empties, is
written as a whole, because its key line changes with its first or last entry). Any other value - a list, a
`ConfigurationSerializable` such as a Bukkit `Location` or `Vector`, the value of a typed map entry that is not itself a map -
is one value, written whole or not at all, so the file never holds a value mixed from the module's and the operator's. A
setting declared as `Map<String, Object>` or as a raw `Map` is split one level only: a plain nested map below it is one value.
The same rule decides what is one value for the reload merge (a conflict over a composite value or a list takes the file's
whole value and names the key only), for a panel edit inside a composite value (the whole value is the unit written - the value the
framework last read with that field changed - and only while the file still holds exactly that whole value at write time;
otherwise the edit is refused naming the setting, "the file changed since it was read; reload first", and nothing is
written), and for `saveOperatorMapEntry` (map keys that reach inside an entry that is not a map throw `IllegalArgumentException`
and nothing is written). A save never inserts a key the file lacks, never rewrites a comment and never removes a
map entry the module did not remove. So a value the operator edited on disk since it was read, a key the operator deleted, a
value or list element the framework could not use (`interval: 3O0`, `[60, 30, 10, abc]`) and a 6.2 split map entry are
never written over by a save, whether or not the module changed that setting
([#596](https://github.com/UltiKits/UltiTools-Reborn/issues/596)). A change that is not written stays in memory, and one
warning per save names the file and those keys, never values; a key the write gate refuses is named in the gate's warning
instead, with its reason. The write goes through the write gate above, so every other line of the file stays byte for byte,
or nothing is written. "The file still holds what was read" compares the text the framework last read or wrote: an
operator edit saved to disk without `/ul reload` - even one that keeps the value, such as `y: 64` changed to `y: 64.0` -
makes a later module change of that setting not written (named in the warning) until a reload reads the file again;
nothing is lost, and the module's value stays in memory. With nothing to write the file is not touched (bytes and modification time stay). A failed write
never acknowledges the pending effective values as saved. Use `save()` for a change the operator asked for through the
module, or for shipped text the module re-renders after a language switch. In 6.2.5 an explicit save rewrote the
whole file from the module's values, replacing an operator's changed value without a warning; 6.3.0 writes only what the
module changed, as above, without a migration period, by the maintainer's rule that operator-written configuration is
never overwritten automatically.

For a command the operator runs to change exactly one thing, two methods are new in 6.3.0 (additive):
`AbstractConfigEntity#saveOperatorChange(String...)` writes exactly the named settings (`/setspawn` names the six
`spawn.location.*` entries), and `#saveOperatorMapEntry(String, String...)` exactly one entry of a setting declared as a
map (`/autoreply` names one rule; the entry is inserted when the file lacks it and removed when the module's map no longer
holds it; each map key is one whole key, so `play.example` is a single key). The operator's request is their consent: the
module's value replaces what the file holds at the named keys, including a value the operator edited there by hand; a
named setting the file lacks is inserted with its comment. Nothing else is written - not even another setting the module
changed - and the write goes through the write gate owning only those keys. Naming a setting declared as a map with
`saveOperatorChange` writes the whole map and drops entries the operator added by hand; use `saveOperatorMapEntry` for a
command that changes one entry. A refusal (a file that cannot be read or
parsed, uses anchors, has a layout the write cannot keep byte for byte, or changed while the write was being prepared)
throws the new `com.ultikits.ultitools.config.ConfigWriteRefusedException`, an `IOException` whose message and
`getReason()` name the reason without any value; the file keeps its bytes and the in-memory value stays, unsaved. A path
that is not a declared entry, or a map-entry call on a setting not declared as a map, throws `IllegalArgumentException`
and writes nothing. Both run under the entity monitor and must be called on the server thread.

A map-entry write can also be made conditional on whether the entry is in the file (additive, 6.3.0,
[#623](https://github.com/UltiKits/UltiTools-Reborn/issues/623)): `#saveOperatorMapEntry(EntryPresence, String, String...)`
with the new enum `com.ultikits.ultitools.config.EntryPresence`. Use `MUST_BE_ABSENT` for a command that creates an entry
(`/autoreply add`) and `MUST_BE_PRESENT` for one that changes an existing entry (`/autoreply setkeyword`). A module therefore
needs no second read of the file with its own parser. The condition is decided on the read whose bytes the write gate
verifies the edit against, and the gate checks those bytes again immediately before it replaces the file. So an entry the
operator added or deleted by hand since the load is seen, and an edit saved after that read makes the gate refuse the write
as "the file changed". "Present" means the entry's whole key path exists where the framework reads the setting: its nested
keys, or the flat dotted form ([#612](https://github.com/UltiKits/UltiTools-Reborn/issues/612)), followed by the map keys,
each one whole key. An entry holding `null` is present. An entry under a missing, `null`, empty (`{}`) or non-map key is
absent, and so is every entry of a missing, empty or comment-only file. When the condition does not hold, nothing is
written (bytes and modification time stay) and the framework logs nothing above FINE. The call then throws the new
`com.ultikits.ultitools.config.ConfigEntryPresenceException`, a `ConfigWriteRefusedException`: `getRequired()` names the
condition that failed, and `getReason()` names the entry's key path, never a value. A `null` condition throws
`IllegalArgumentException`. Both overloads are new in 6.3.0. A call that passes a literal
`null` as the first argument, `saveOperatorMapEntry(null, "path", "key")`, does not compile, because it is ambiguous
between them; cast the `null` to the intended type (it then throws `IllegalArgumentException`).

Unreadable, unparseable and non-UTF-8 files are protected on every entity write path. Initial load
keeps declared defaults and logs one SEVERE naming the file and safe cause. As of 6.3.0 (#589) a
failed reload keeps running fields and the file unchanged and throws `ConfigurationException`
(`CONFIG_LOAD_FAILED` for an unreadable file, `CONFIG_PARSE_FAILED` for one that does not parse)
naming the file and the same safe cause, instead of logging and returning normally; the entity logs
nothing itself, because its caller reports it: `ConfigManager#reloadConfigs` lets it through, the
module's reload logs one SEVERE line naming the module and the cause, `/ul reload <module>` replies
that the module failed to reload, and a full `/ul reload` names the module among its failures. A
module that calls `reload()` itself and catches only `IOException` now sees this unchecked
exception propagate; catch `ConfigurationException` to report it in the module's own reply.
Explicit save does not clear protection; only a later successful load permits writes again.
Parser diagnostics expose only numeric line/column metadata, never source snippets or scalar values.

An annotation comment that is exactly one `{key}` token (surrounding whitespace ignored) is resolved
from the module catalogue. Start-up and reload rewrite, in the current language, only the comment
lines above a token entry that the framework can identify as its own (a save, an operator change and a panel edit rewrite no
comment; a key one of them inserts gets its comment): the entry's comment, as a whole
or as its trailing run of lines, equal byte for byte to what the framework writes (the entry's
column, `#`, a space and the text) for the token's text in a catalogue the module's jar ships, for its current text
in one of the operator's custom language files in the module's `lang/` folder (as of 6.3.0, #615; see the entry on
official language files and custom names below), for the text the module resolves now, or for the bare token. Equality is the only test; the same text without the space
after `#` or at another column is the operator's. Every other comment line - a note an operator wrote above a token entry, a framework comment the
operator edited, a literal-entry comment - is kept byte for byte, permanently (maintainer decision of
2026-10-04, [#604](https://github.com/UltiKits/UltiTools-Reborn/issues/604)). A framework comment whose text no
shipped catalogue holds any more - an older module version's wording - is recognised only when the module
lists that text in the new `@ConfigEntry(previousComments = {...})` attribute (additive, default empty):
a comment equal to a listed text, in the same exact written form, is replaced by the current catalogue
text and follows the language from then on (maintainer decision of 2026-10-04); otherwise it is kept as
it is. A token entry without any comment gets the framework's.

A framework comment line the operator deletes, keeping its setting, is written again at the next start
and at the next `/ul reload`, with or without a language switch, at its place above the setting and in the
language selected at that moment - unless a comment line of the operator's own remains directly above the
setting: that line is then the setting's whole comment, it is the operator's, and nothing is written above
it. No value, key or line the operator wrote changes; with a language switch, the setting's other framework
comments follow the switch as usual. This holds for a comment written from a shipped catalogue and for one
written from a custom language file (pinned by `DeletedFrameworkCommentTest`).
Deleting the line alone is therefore not a way to remove the comment: to leave a token setting without the
framework's comment, replace the line with a comment of your own - even a bare `#` - which the framework
cannot identify as its own, keeps, and writes nothing above. Deleting
the whole setting, its comment and its key, brings it back at the next start with its default value and its
comment, after the remaining settings rather than at its old place.
Catalogue lookup failure keeps the literal token and warns once per entry per load. Comment text
uses the document's YAML line-break/control-character sanitation, including the panel payload.
A failed comment-only rewrite does not fail load or discard bound values, logs one warning and marks
nothing for a later save. No-op comparison includes these comments.

A panel edit replaces an operator-edited value at exactly the keys it names - the operator's consent - and warns about
nothing ([#600](https://github.com/UltiKits/UltiTools-Reborn/issues/600)). A save never replaces an operator-edited value (above). Comment/layout-only edits, equal candidate values and
failed writes report nothing. The check and write run under the same entity monitor.

### Removed mutable configuration accessor

`AbstractConfigEntity#getConfig()` is removed under the **6.3.0 one-time carve-out**.
The accessor worked in 6.2.5: neither the non-functional nor the never-used same-release exception
applies. This is an explicit maintainer-authorized removal, not evidence that the accessor was
broken or that nobody used it.
Use `isPresentInFile(String)` for presence in the last successfully loaded document: undeclared keys
and explicit null count as present; unreadable/unparseable loads report false. The path is read the
way a declared setting path is (#612): split at its dots as nested keys, held as one flat dotted key, or any mix of the
two; the key counts as present when the file holds it in any of these forms, so a whole map key containing a dot is
found too. Read or mutate declared fields and call `save()` instead of mutating Bukkit storage.
The two official callers, UltiEssentials and UltiRemoteBag (`RemovedConfigKeys`), were migrated to
`isPresentInFile` in their 6.3.0-compatible builds; third-party usage is unknown.
An unrecompiled caller invoking the removed accessor sees `NoSuchMethodError`. See the removal
record in `compatibility/records/6.3.0.md`; all other public/protected entity signatures are retained.

### Registration batches

As of 6.3.0, package/directory configuration registration buffers initialization writes until every
entity binds and validates. A refused batch creates no file and changes no existing file, including
missing-key and language-token comment rewrites. Once accepted, files persist independently; an I/O
failure preserves earlier successful files and protects the failed entity until a successful reload.
The flush writes through the write gate (see "Rendering and save fallback") and only while the file still
holds the bytes read at registration; an edit made in between is kept, with one warning.
Standalone registration still writes immediately. Framework-internal initialization bridges are not
a module transaction API.

### Superseded-copy configuration ordering

As of 6.3.0 no configuration of a loaded older module copy is saved before, during or after a newer copy replaces it
(maintainer decision of 2026-10-04, "what code may write, by file type": no replacement save of whole entities, because a
module update is not an operator's request to write). The newer copy is constructed and reads the files as they are; the old
copy's unsaved changes or a protected (unreadable or unparseable) old file do not block the replacement. The old copy is
not unloaded before incoming activation succeeds; once it does, one warning names the dropped file/entry keys (never values)
and the old configuration entities are released. Failed incoming construction, compatibility, assembly or activation
releases only refused incoming configuration owners; existing owners remain registered.
No constructor deferral, early unload or public storage/transaction API is introduced.

Registrations follow the same rule, with one step earlier (#562): every panel responder, EventBus handler and tab
completer recorded against the old copy is released after the incoming copy passes its compatibility gates and before its
container refresh and `registerSelf()`, so the incoming copy can claim the panel message types and completer keys the old
copy held. When incoming assembly or activation fails, what the incoming copy registered is released and the old copy's
registrations are put back, unchanged; the old copy keeps running. When it succeeds, the old copy is unloaded as before.
See "Registrations released per module instance" under "Behavioral changes".

### Configuration registry server-thread confinement

As of 6.3.0, all ConfigManager registry operations are server-thread confined while a server
runs. Direct off-thread register/registerAll/saveAll/unregisterAll/reloadConfigs calls warn once
and do no work. Getters, toJson/getComments and both loadFromJson overloads warn once and throw
IllegalStateException, rather than returning a misleading empty result or successful write.
The no-server case remains supported. getAllConfigEntities preserves null for an unregistered
module and otherwise returns an unmodifiable detached map; its entities are not copied.
Panel update, upload-write and reconnect upload-read callbacks queue their whole operation and
return immediately off-thread, responding only after the queued operation runs. Registry guards
precede entity monitors. Multi-file panel transactions retain deterministically ordered touched-entity
monitors through validation, preparation, commit and acknowledgment or rollback; direct single-entity
persistence uses its existing monitor. No separate manager lock or blocking scheduler wait is added.
Async registry callers must schedule on the server thread. Existing public method signatures and
panel response fields/types are unchanged.

### Configuration release; nothing written at stop

As of 6.3.0, module unload releases that module instance's configuration registry entry even
when its unload hook or context close throws. **Nothing writes configuration at server stop, at
module unload or uninstall, or at module replacement** (maintainer decision of 2026-10-04: no
shutdown or replacement save of whole entities; a lifecycle event is not an operator's request to
write). At stop, and at a normal unload or uninstall, after each module's unload hook and container
`@PreDestroy` callbacks and just before its release, every configuration of that module holding module
changes that were never saved is named in one WARNING - the file and the changed keys, never values -
and the changes are dropped; the framework's final step at stop names, the same way, anything still
registered. A superseded copy is named once by the replacement's own drop warning instead. A configuration whose file
could not be read or parsed at its last load is named once instead (6.2.5 rewrote such a file as soon as it loaded it - with the
declared defaults at start-up, with the running values at a reload - and again from the module's in-memory values at
stop). An operator's edit made
while the server runs is never touched at stop. A module persists a change when it makes it, with
`save()` (only what the module changed, where the file still holds what was read) or
`saveOperatorChange`/`saveOperatorMapEntry` (exactly what an operator's command names).
`ConfigManager#saveAll()` keeps its signature, writes nothing, reports as above and is
`@Deprecated(since = "6.3.0")`. Public existing signatures are unchanged. Through 6.2.5,
`ConfigManager#saveAll()` rewrote every registered configuration file from the module's in-memory values at server
stop; 6.3.0 removes that save without a migration
period; see "Behavioral changes that need no migration period".

### Configuration init and reload thread contract

As of 6.3.0, configuration init, reload, manager reloadConfigs and module reloadSelf refuse
calls off the server thread while a server runs. One warning names the module, entity path when
applicable and caller thread; no field/file/lifecycle action occurs. Checks precede entity monitors.
The no-server test harness case remains allowed. This contract changes no public
signature; `reloadSelf()` is `final` as of 6.3.0 for a separate reason (see
[Third occurrence: 6.2.5 → 6.3.0, a MINOR](#third-occurrence-625--630-a-minor)). Async third-party callers must
schedule their reload on the server thread.

### Reload merge rule

As of 6.3.0, reload compares the last effective disk baseline, current serialized fields, and
incoming disk values. Memory-only changes survive and stay dirty; disk-only changes are adopted.
Maps merge recursively by whole keys; lists and scalars are atomic. Conflicts take the file's value
and warn with the located key and, for a scalar, the discarded value, redacting secret-shaped values; a conflict over a list
or a composite value names the key only. Absent map keys
and explicit null differ. A whole declared field missing from the file binds its declared default when the module had not
changed it (one warning, naming the file and key, when the previous load still held the key - that is, the operator
deleted it), and keeps its live value when the module had (both with the declared-default baseline); the file is not
written.
Unreadable/unparseable reloads keep live values, protect the file and throw
`ConfigurationException` naming the file and the safe cause (#589); 6.2.5 read such a file as an empty configuration,
rewrote it from the module's running values during the reload itself, and rewrote it again at stop.
After a module's language is rebuilt in the reload steps, and before its own reload hook, the framework rewrites
only its own comment lines of that module's configurations in the new language, through the write gate over a fresh
read; no value, key or other comment line is written ([#594](https://github.com/UltiKits/UltiTools-Reborn/issues/594)).

### Panel edits inside map entries

As of 6.3.0, a changed panel leaf inside a declared map setting is applied through that field's
full declared-type converter. Real whole keys containing dots remain whole. A path with multiple
readings refuses with every reading named; an unknown changed key is explicitly refused. Any refused
changed key refuses the whole payload, naming all refused paths. Unchanged displayed leaves are not
edits, including undeclared operator keys and ambiguous paths. Previously these map-entry edits were
silently ignored. Leaf edits preserve unrelated pending in-memory siblings and their dirty state,
and preserve independently edited disk siblings without acknowledging them. Full declared-field
conversion and validation still run: the live field is serialized, only targeted plain leaves are
patched, and the registry binds that candidate once. The round-trip contract preserves untouched
bound siblings without any type-specific map/object merging. Only targeted leaves are persisted, through the write gate
owning exactly those leaves (a map that is empty, or that the edit empties, is owned whole).
The existing `config_update_response` shape is unchanged.

A panel edit of one field inside a composite value (a Bukkit `Vector` or `Location`, also as a map entry) writes that whole
value exactly as the file held it with only the edited field changed, so the other fields keep the bytes the operator wrote
(`y: 64` and `pitch: 0` stay, never `64.0`). A whole number - sent by the panel, or already in another field of the value in
the file - is widened to a floating-point number for the conversion where the module's own value holds one (Bukkit's `Vector`
does not widen by itself), and the edited field is written in that type (`7` is written as `7.0`), so the file the edit leaves
behind loads ([#609](https://github.com/UltiKits/UltiTools-Reborn/issues/609)). A whole number in the file in a `Vector` or
`BlockVector` coordinate (`y: 64`) now loads as that number: Bukkit's `Vector` reads its coordinates as `Double` only, so before
6.3.0 such a value failed with a SEVERE stack trace and the module used the declared default; the framework now widens `x`, `y`
and `z` of a `Vector` in memory only - the file keeps `y: 64`, an unchanged save writes nothing, and no other type is widened.
An edit below a map group the module removed in memory and has not
saved is refused, naming the key with "not found in memory".

### Multi-file panel persistence

As of 6.3.0, `ConfigManager#loadFromJson(String)` validates every touched configuration, stages
all changed files through the write gate, and only then replaces them. Each file is replaced only while it still holds
exactly the bytes it was staged against, checked again immediately before its move: a file an operator saved in between
refuses the whole batch with `ConfigWriteRefusedException` naming that file, the operator's save is kept, and the files
already replaced are restored to exactly the bytes they were verified against. Entity baselines and raw acknowledgments advance
after every commit succeeds. An in-process staging/replacement failure restores the complete prior entity state, removes
staged temporaries, and rethrows the original error; on disk, the rollback acts only on bytes the batch itself still owns
([#673](https://github.com/UltiKits/UltiTools-Reborn/issues/673)). A file the batch did not replace - never reached, or its
replacement failed with an ordinary error, which leaves it holding its previous bytes - is never written or deleted by the
rollback, so an operator save made while the batch was failing is kept. A file the batch replaced is put back only while
it still holds exactly the batch's bytes; one the operator saved after the replacement is kept, and one WARNING names it.
A file whose own put-back failed is left as the SEVERE describes, with its framework backup: the rollback does not write
it again, and its configuration refuses every write until the operator compares the two and reloads.
Recovery errors are attached as suppressed exceptions; persistently unavailable storage can prevent
restoration and is not falsely reported as a successful rollback. Semantic no-ops write nothing.
This is not a crash-safe multi-file transaction: a JVM crash between moves remains deferred to #545.
The panel message shape and public `loadFromJson` signatures are unchanged. Internal staged-entity
coordination bridges are not a module transaction API.

### Writing operator files a module manages

As of 6.3.0, `com.ultikits.ultitools.config.OperatorFiles` (additive) is the way a module writes a YAML file it
manages on an operator's behalf - a kit file, a menu file - on an explicit operator edit (maintainer decision of
2026-10-04: such a file gets only the edited part; comments and other keys are kept). `OperatorFiles.read(File)`
returns the file's text (strict UTF-8) and the SHA-256 of its bytes; `OperatorFiles.write(Snapshot, Map<List<String>,
Object>)` writes only the named whole keys (a key containing `.` is one key), through the configuration write gate,
only while the file still holds the snapshot's bytes, and returns `WRITTEN`, `UNCHANGED`, `FILE_CHANGED` or `REFUSED`
(a layout the gate cannot keep byte for byte, or anchors, with one WARNING naming the file, the keys and the reason).
Values must be plain data - serialize an `ItemStack` first; anything else throws `IllegalArgumentException` before
the file is touched. It never creates, deletes or renames a file, is not a general file API (UltiKits/UltiTools-Reborn#545
stays a later feature), and is not for `@ConfigEntity` files, which keep their own save rules.

### Migrating legacy parsers to converters

The six deprecated announcements are `ConfigEntry#parser()`, `interfaces.Parser`,
`interfaces.ObjectConfigSerializer`, and `interfaces.impl.pasers.ConfigParser`,
`DefaultConfigParser`, `StringHashMapParser` (the published package spelling `pasers` is retained).
Their first release carrying `@Deprecated(since = "6.3.0", forRemoval = true)` is 6.3.0; the next
MINOR, 6.4.0, is the announced removal version. They are retained in 6.3.0, not deleted now.
An explicit non-default `parser = X.class` still selects the frozen legacy adapter. Its detached
input is emitted under one key and loaded by a fresh Bukkit `YamlConfiguration#get`, retaining
6.2 section-based dotted-key splitting and Bukkit `==` alias deserialization at the root and inside
lists/maps, including explicitly parsed `Object` fields. Registry converters do not hydrate these
legacy inputs; legacy output still crosses the plain-data boundary and retains boxed widening.
Bukkit's own alias restrictions remain: integral Vector coordinates deserialize to null, whereas
fractional coordinates deserialize normally. Leaving `parser` at `DefaultConfigParser.class` selects the new registry,
not that legacy class. Third-party subclasses retain their old executable behavior, not the new
built-in collection semantics. The six announcements are indexed in
[`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md).

For a custom type, remove `parser = ...` from the field and place a public top-level converter
with a public no-argument constructor in the module's `@UltiToolsModule.scanBasePackages`.
The package scanner discovers top-level classes only, not nested converter classes. Converters are discovered before configuration
construction and are not IoC beans: do not depend on injected services or constructor side effects.
Two registrations for the same exact class refuse load naming both converters. Lookup uses an
explicit non-default legacy parser first; otherwise the module's exact registration, then its
non-exact superclass/interface registrations, then the framework's registrations in the same
order, generic collections/arrays/enums/Object, and registered Bukkit serialization fallback.
`exact = true` prevents a registration from serving subclasses.

Here is a map-shaped value migration. Put the value/parser/field members in a module class named
`MigrationExample` in package `example.config` (imports go before the outer class). Put the converter
in its own public top-level `TokenConverter.java` in the same scanned package, as shown separately. `Token`
must provide semantic `equals`/`hashCode` in production so round-trip comparisons mean value equality.
The converter deliberately accepts only the one-key shape it can reproduce; accepting extra keys
and dropping them would violate `toPlain(fromPlain(p)) == p`.

```java
import java.util.Map;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;

// Before: inherits the legacy reflective writer and section reader.
public static class Token {
    public String text;
    public Token(String text) { this.text = text; }
    @Override public boolean equals(Object other) {
        return other instanceof Token
                && java.util.Objects.equals(text, ((Token) other).text);
    }
    @Override public int hashCode() { return java.util.Objects.hashCode(text); }
}
public static class TokenParser extends DefaultConfigParser {
    @Override public Object parse(Object raw) {
        Map<?, ?> map = (Map<?, ?>) super.parse(raw);
        return new Token((String) map.get("text"));
    }
}
@ConfigEntry(path = "token", parser = TokenParser.class)
private Token oldToken = new Token("hello");

// After: delete the old field/parser and use this declaration/converter.
@ConfigEntry(path = "token")
private Token token = new Token("hello");

```

```java
// TokenConverter.java: a separate top-level source file.
package example.config;

import java.util.LinkedHashMap;
import java.util.Map;
import example.config.MigrationExample.Token;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConfigConverterFor;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;

@ConfigConverterFor(Token.class)
public class TokenConverter implements ConfigConverter<Token> {
    public TokenConverter() { }
    @Override public Object toPlain(Token value, ConversionContext ctx) {
        if (value == null) { return null; }
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("text", value.text);
        return plain;
    }
    @Override public Token fromPlain(Object plain, ConversionContext ctx)
            throws ConversionException {
        if (plain == null) { return null; }
        if (!(plain instanceof Map)) { throw failure(ctx); }
        Map<?, ?> map = (Map<?, ?>) plain;
        if (map.size() != 1 || !map.containsKey("text")
                || !(map.get("text") == null || map.get("text") instanceof String)) {
            throw failure(ctx);
        }
        return new Token((String) map.get("text"));
    }
    private ConversionException failure(ConversionContext ctx) {
        return new ConversionException("Expected only a text key containing text or null",
                ctx.file(), ctx.path(), ctx.declaredType());
    }
}
```

The plain boundary permits null, strings, booleans, `Integer`, `Long`, `BigInteger`, `Double`,
lists and string-keyed maps of those values. Return no Bukkit section, arbitrary Java bean or
Java class tag. Use `ctx.toPlain(nested)` / `ctx.fromPlain(nested, declaredType)` for recursive
conversion. A non-plain runtime value in an `Object` slot uses its runtime converter on write;
already-plain values stay plain. Integer narrowing is exact and range checked. Float accepts a
decimal only when `Float.toString(parsedFloat)` prints the same decimal value (scale does not
matter): `0.03` and `1.50` are accepted, `0.100000001` is not. Float output uses that printable
decimal as a plain `Double`. This is not a promise of exact binary representation for decimal floats.

### Shutdown and known limits

Nothing is written at shutdown: an operator-only disk edit survives untouched, and a pending code change is named in
one warning and dropped (see "Configuration release; nothing written at stop"). A partial panel save cannot acknowledge an
unrelated pending field. Async module field mutation itself is not protected by registry confinement:
module authors must arrange server-thread mutations. Initialization batches and panel transactions
are different: accepted initialization files persist independently, while the panel stages all
touched files with in-process rollback.

Known limits remain explicit, not guaranteed away: [#578](https://github.com/UltiKits/UltiTools-Reborn/issues/578)
records out-of-threat-model storage findings; two still apply - temporary-file identity through a symlink followed by
`..`, and quadratic style detection in Unicode-heavy files. Its anchored-container and alias-comment items cannot occur,
because no write path writes a file that uses anchors, aliases or merge keys. A valid block anchor with a comment before
its first child key cannot be read and the file is protected as unparseable
([#580](https://github.com/UltiKits/UltiTools-Reborn/issues/580), closed by documenting it; see "Layouts the reader
cannot read"). A file that uses anchors, aliases or merge keys is never written; one warning per file per run names it.
No general alias-preservation guarantee is claimed, and apart from putting back the in-place write that just failed
(#622), nothing is restored from an earlier backup. [#545](https://github.com/UltiKits/UltiTools-Reborn/issues/545) remains the
separate crash-safe multi-file transaction limit.

Three YAML spellings fail the write gate's check (6.3.0). A framework write changes only the keys it owns and verifies
that every other line of the file keeps its bytes and its value; these spellings cannot pass that check. None of them blocks
reading the file, but a write of another key of that file is refused with one WARNING, and the file is left exactly as it
was:

- an unquoted binary value, such as `blob: !!binary YWI=` (it would be rendered as `blob: !!binary 'YWI='`);
- a set written in block form, with `? a` entries (it would be rendered as `a:`);
- a binary value used as a map key, such as `blobs: {!!binary 'YWI=': 1}`.

Workaround: write binary values quoted (`blob: !!binary 'YWI='` or `blob: !!binary "YWI="`) or as a block literal
(`blob: !!binary |` followed by the indented base64 on the next line), and write sets in flow form
(`tags: !!set {a: null, b: null}`); values in these spellings never block a write of another key. A binary map key has no
such spelling: keep binary values out of the keys of a file the framework writes.

### 中文补充：6.3.0 配置层迁移

- 新扩展点是 `ConfigConverter<T>` 和 `@ConfigConverterFor`，转换器必须是扫描包内带 public 无参构造器的 public 顶层类；扫描器不发现嵌套转换器。它不是 IoC bean。未知声明类型在读取或创建文件前拒绝模块加载；官方 UltiRecipe 的配方对象需要模块自己的转换器。
- 注解 `path` 中的点仍表示嵌套路径；映射里的 `g.m`、`o.O`、`wave.` 从 6.3.0 起按完整键保存。6.2 已经拆开的文件原样读取，不会自动合并，保存也不会删除这些条目，留给服主自己删（维护者 2026-10-04 决定）。完整泛型参与绑定；无效集合元素跳过，无效字段用声明默认值；重载时文件里缺少的键改用声明默认值（上次加载时还在、即服主刚删掉的键警告一次）（模块自上次加载或保存后改过该设置时保留模块的值），两种情况都不改文件，之后的保存也不会补回该键（#596）。允许 null 的引用类型可往返；`Object` 中的 Bukkit 序列化对象读取后仍是普通映射。
- 6.3.0 起，非文本键的映射（`Integer`、`Long`、`Boolean`、`UUID` 等标量键）可以加载和写入（#673）。文件已有的条目总按文件里的键文字写入：加引号（`'01'`）、整数写成带点（`1.0`）、指数（`1.0e10`）、别名（`yes`、`off`）或大写的写法都原样保留；只有文件没有的键才按规范文字写入，且该文字必须读回同一个键。保存、面板编辑、`saveOperatorChange` 和 `saveOperatorMapEntry` 都不会改名、改引号、改顺序或改结构，删除条目只删它那一行；`saveOperatorMapEntry("limits", "1")` 能找到文件里写作 `'01'` 的条目。服主手动改写键（`'01'` 改成 `1`）在下次重载时直接采用，不报冲突也不写文件。同一个键在文件中有多种写法（如 `1:` 与 `'01':`）时，模块仍照常加载并给出原有警告，但该设置的所有写入都被拒绝，直到服主删掉多余写法并重载；保存时一条警告列出未写入的改动，服主命令和面板编辑抛出 `ConfigWriteRefusedException`，原因只写文件、设置和键（敏感键之下显示为 `<redacted>`），从不写值。自带旧式解析器（`@ConfigEntry(parser = ...)`）的设置不受上述两条规则约束：哪些文件键是同一个键只由该解析器决定，`1:` 与 `'01':` 对它可以是两个键，不会因多种写法被拒绝，`saveOperatorMapEntry` 也按给出的键文字原样使用。已知限制：（1）服主手动改写某个键（`'01':` 改成 `1:`）时，若模块对同一条目有尚未保存的改动，下次重载保留文件（服主的字节优先，并给出原有的冲突警告），模块对该条目的未保存改动被丢弃；请先保存模块的改动，再手动编辑该条目。（2）服主手动以一种写法加入键（`'01':`），模块在下次重载之前写入同一个键时，可能插入第二种写法（`1:`）；文件随后以两种写法持有同一个键，该设置之后的写入会被拒绝（沿用上述多种写法警告），直到服主删掉一个并重载；请在手动编辑映射键后先重载，再让模块写入。（3）对于键类型把不同文字视为同一个键的 `SortedMap`（如 `BigDecimal` 的 `1.0` 与 `1.00`），多种写法检查按 `equals` 匹配键，因此检测不到这两种写法是同一个键；没有已发布的模块使用这样的映射，请不要用两种数字写法写同一个键。
- 6.3.0 起，声明的设置写成带点的扁平键时就是该设置（#612）：模块声明 `@ConfigEntry(path = "features.chat")`，文件写的是 `features.chat: false` 而不是 `features:` / `chat: false` 时，模块读到 `false`，与 6.2 经 Bukkit 读取时相同；路径的点以任何方式拆分都算（`a.b.c` 写成 `a.b:` / `c: 1` 也可以）。框架不会再为这种设置补一份嵌套写法：启动补键、模块保存、服主命令（`saveOperatorChange`、`saveOperatorMapEntry`）和面板编辑都经写入闸门写在该设置所在的那一行，面板也只按设置路径显示一次。`@ConditionalOnConfig` 改用同一个文档读取器和同一规则读取，不再用 Bukkit 的 `YamlConfiguration`，所以启动补键前后读到的都是模块绑定的值；仍只认布尔值。`isPresentInFile` 同样按此规则判断（扁平点键也算存在）。同一文件里一个设置写了两种形式（例如 `features.chat: false` 和 `features:` / `chat: true`）时，在模块代码运行前拒绝加载该模块（重载则拒绝重载、保留运行中的值），提示说明该设置写了两种形式，只写文件和设置路径，不写值；`@ConditionalOnConfig` 读取的路径即使没有配置实体声明，写了两种形式时同样拒绝该模块的组件扫描。两种形式的值相同也拒绝：在 3,710 个实际配置文件上实测，没有一个文件把同一设置写了两次；若接受相同的两份，之后某次写入只改其中一份，下次启动就会拒绝模块。删掉其中一行再重启即可。此规则只针对声明的设置路径，映射值里的键仍按完整键处理（如上面的 `o.O`）。文件缺少的设置会插入到文件已有的那个节下面，绝不写成该节的第二种形式（自 6.3.0 起，#614）：插入位置是文件以节的形式（映射，或没有值的键）持有的、设置路径最长的那段前缀，其余部分写成嵌套键。例如文件写了 `a.b:` / `c: 3`、模块声明了 `a.b.d`，启动补键会把 `d:` 写在 `a.b:` 下面。如果同一节的某个声明设置把路径的剩余部分写成节下的一个带点键（如 `a:` / `  b.c: 3`），缺少的 `a.b.d` 会以同样的方式写在它旁边（`  b.d: 2`），绝不另写嵌套的 `b:` / `d:`（维护者对 #614 修复的第一道审查意见所作的决定）；这样的兄弟设置若拆分方式不同，则按下文拒绝插入。如果文件把这个节写了几种形式，设置会插入到其中唯一一个还持有该节其他声明设置的形式下；没有或不止一个形式持有时拒绝插入：WARNING（服主命令则抛 `ConfigWriteRefusedException`）写明文件、设置和节，从不写值，内存中使用声明默认值。服主命令、服主改动和面板编辑插入设置时同样如此。
- 保存内容、注释文字、键顺序和支持的文件风格。6.3.0 起，所有自动写入（创建不存在的文件、补写缺失的声明键、启动或重载时改写框架自己的令牌注释、注册批次落盘）都经过同一个写入闸门：每次写入声明自己拥有的键，渲染后其余每一行必须与读取时逐字节相同（含换行符、BOM 和末尾换行），且文件仍是读取时的内容；否则不写，警告一次（只列文件、键和原因，不列值），内存中使用声明默认值。这些写入不会规整排版：手工对齐的文件缺少的键会一直缺少，每次启动警告一次，直到服主自己补上。使用锚点、别名或合并键的文件不会被自动写入，每次运行只提示一次。框架新建文件时独占创建，绝不替换期间出现的文件。模块的显式保存同样经过写入闸门，只拥有它要写的设置（#599，见下）；服主操作写入和面板编辑同样经过写入闸门，只写它们改的设置或映射条目，不再规整排版或展开锚点；闸门拒绝时面板收到注明原因的错误，文件不变（#600）。渲染器只能按一种规范排版写回；文件排版与之不同的地方，闸门无法保留，因此对该文件的**每一次**经闸门写入（启动补键、改写注释、保存、服主操作写入、面板编辑）都会拒绝，无论改的是哪个设置。这是有意的：规则不允许为了让写入通过而规整服主的排版（维护者授权下编排者 2026-10-05 的决定）。文件保持原样，内存中使用相应值，警告列出键和原因，并给出要改的行号（“…would change (line N)”或“…shares line N…”）。实测会拒绝的排版（每种都有测试 `OperatorFileWriterLayoutRefusalListTest` 固定）：只含空格的行或文件；值或节键后面的行尾空格；用多个空格对齐的行内注释（`#` 前只有一个空格的会保留）；冒号后多于一个空格、流式方括号内侧的空格（`[ world ]`）；文档开始标记 `---` 或结束标记 `...`；块标量（`|` 或 `>`）后面跟空行；没有末尾换行的文件里，最后一个值是用 `|`、`|+` 或 `>` 写的块标量（在这种文件末尾它的值没有末尾换行，所以会按 `|-` 或 `>-` 写回；这种文件里其它位置的块标量、以及用 `|-` 或 `>-` 写的最后一个值都会保留——但把文件最后一个值写成以换行结尾的文本的写入会被拒绝，并指出那一行；值永远不会被改动）；一节末尾、空行之后、缩进比下一个键更深的注释，以及任何缩进比下面的键更深的注释；同一文件里两种缩进宽度；混用换行符；续到下一行的普通值、值里的制表符、显式 `? key`、显式标签（`!!str`）；只含 BOM 的文件（原因里注明 BOM）；值为 `null` 且带行内注释的条目（`foo: ~  # placeholder`）被写入改成映射时，YAML 库无法在那里写出该注释，写入被拒绝并指出该键（#624，由 `WriteGateRuntimeFailureTest` 固定）。会保留的排版（其它键的写入照常通过）：`#` 前一个空格的行内注释、后面没有空行的块标量（包括紧跟在它后面的注释行：它被读成下面那个键或列表项的注释，与普通值之后的注释相同，[#592](https://github.com/UltiKits/UltiTools-Reborn/issues/592)）、内侧无空格的流式映射和列表、带引号的值、全文一致的四空格缩进、与键同列的列表项、全文统一的 CRLF 或 LF、没有末尾换行（含有块标量的文件也一样，上面所说的最后一个值除外）、BOM、`#` 后无空格的注释、多个空行、十六进制数、`~` 和空值。使用锚点的文件整体拒绝，不按行。实测：框架和十五个模块自带的 32 个 YAML 文件、维护者测试服务器 `plugins/UltiTools/` 下 3,710 个配置 YAML 文件（2026-10-05 在 6.3.0 开发期间实测），经真实闸门插入一个键全部通过；已知唯一会拒绝的出厂文件是 6.0.9 之前各个 6.0.x 版本（6.0.0 至 6.0.8）的框架 `config.yml`（见下）。改掉警告指出的那一行，下一次写入即可通过。0 字节文件、只有空行的文件、以及只含行首注释（前面可有空行）的文件在闸门看来是空文档：启动时在已有字节之后补入声明的键（没有其它字节需要保留），服主操作写入或面板编辑插入它指定的设置；保存从不插入。只含空格的文件、含缩进注释的纯注释文件和只含 BOM 的文件按排版拒绝（见上），内存使用默认值、模块照常加载；只含制表符、`---` 或 `...` 的文件无法解析，按无法解析的文件保护（永不写入）；块标量后面依次是缩进大于 0 列的注释行、空行、第二个注释行的文件也一样：SnakeYAML 2.2 在这种排版上会在组装节点时抛出异常（[#617](https://github.com/UltiKits/UltiTools-Reborn/issues/617)，未修复）。读取后被删除的文件，服主操作写入或面板编辑会独占地重新创建它，只含指定的设置及其注释。YAML 库在闸门内部抛出的运行时异常（应用写入的修改、渲染文档、为自检重新解析渲染结果时）与闸门无法保留的排版一样按拒绝处理（#624）：不写入；闸门的一条警告列出文件、键、失败的步骤和异常类名，从不含异常消息（消息可能引用文件内容），例如 `the file cannot be written at autoreply.rules.foo: rendering the document failed (EmitterException)`；这些步骤（包括读取文件）中的其它非受检异常同样按拒绝处理；显式写入（`saveOperatorChange`、`saveOperatorMapEntry`、面板编辑）抛出注明键的 `ConfigWriteRefusedException`，自动写入照常在内存中使用声明默认值。库的运行时异常不会再传到调用方，按约定在 `IOException` 时回滚的模块因此与文件保持一致。启动时其它非受检异常不会拒绝模块：一条警告列出文件和异常类名，内存中使用声明默认值。语义无变化不写文件，字节和修改时间不变。浮点数按最短可回读十进制判断，不要求二进制精确。
- 先临时文件、force、原子替换；仅已允许的原子替换/临时创建拒绝才走备份后原地写。备份文件名为 `<文件名>.ultitools-backup-<16 位小写十六进制>`，独占创建；本次运行已为同一文件写过、且内容未变的备份先从当前文件刷新并原子替换，之后才打开目标。写入器不读取、不写入、不删除任何其它文件，服主自己的 `<文件>.bak` 不受影响。6.3.0 起，原地写入在打开目标之后失败（写到一半中断、force 或关闭失败）不再留下写了一半的目标（#622）：框架先把目标放回原样——按原地方式写回目标在此之前的字节，也就是已 force 的备份里的内容（之所以原地写回，是因为这条退路所替代的原子移动已被拒绝）——再删除该备份，然后才报告 `IOException`；因此文件保持写入前的内容，与在该异常时回滚内存改动的模块一致。面板批次以同样方式放回它打开过的每个文件，并报告失败（#582）。若放回本身也失败：一条 SEVERE 列出文件和备份（从不含内容），备份保留且之后的加载不会删除它；写入该文件的配置在重载重新读取文件之前，把它视为读取后已被改动：`isFileModifiedSinceSnapshot()` 为真，服主操作写入和面板编辑以 `ConfigWriteRefusedException`（“the file changed since it was read: …”）拒绝，`save()` 不写入并以一条 WARNING 列出键。处理方法：对比文件与备份，必要时用备份覆盖文件，然后重载。成功的原地写入所用的备份只在成功严格加载当前文件、且备份内容仍与记录一致时才清理；本次运行未写过或已被改动的同类备份保留，并以 INFO 提示一次。除放回刚失败的那次写入外，不从更早的备份还原，不保证多文件崩溃事务。
- 读取器无法读取的两种排版（#580、#617，维护者 2026-10-05 决定：只写入文档，不改读取器，不向上游报告）：框架带注释读取 YAML（Paper 自带的 SnakeYAML 2.2），以下两种合法的 YAML 排版无法读取，文件按无法解析处理：永不写入，启动时模块使用声明默认值（重载保留运行中的值），已有的 SEVERE 日志指出该文件（`Cannot load <文件>: <解析位置>; file will not be overwritten`）。文件的字节不会丢失，但其中服主的值在排版改正之前不会生效。其一：块锚点的第一个子键之前有注释（`defaults: &defaults` 下一行是 `# 注释`，再下一行才是 `setting: inherited`）——把注释移到锚点键上方或第一个子键之后即可读取。其二：块标量（`|-` 或 `>`）后面依次是缩进大于 0 列的注释行、空行、第二个注释行——删掉两个注释之间的空行（或删掉其中一个注释）即可读取。另外，缺少的设置若要插入到某个“冒号后没有值”的键（如 `c:`）的紧后面，写入闸门每次启动都会以“与不属于本次写入的键共用一行”拒绝（#628，未修复，内存中使用默认值）；要把文字设置留空，请写 `key: ''`，这种写法会保留且不会被拒绝。
- 三种 YAML 写法无法通过写入闸门的校验（闸门要求未写入的每一行字节和值都不变）：不加引号的二进制值（`blob: !!binary YWI=`）、块形式的集合（`? a`）、用作映射键的二进制值。它们不影响读取，但同一文件中其它键的写入会被拒绝（一条 WARNING），文件保持原样。变通办法：二进制值加引号（`!!binary 'YWI='`）或写成块字面量（`!!binary |`），集合写成流形式（`!!set {a: null, b: null}`）；二进制映射键没有可用写法，不要在框架会写入的文件中用它作键。
- 不能读取、不能解析、非 UTF-8 文件不会被任何实体写入路径覆盖；初次失败用默认值，重载失败保留运行值。成功加载才解除保护。单独 `{key}` 注释按模块当前语言目录更新，但只改框架能认出是自己写的注释行：该项注释整体或末尾连续几行，与框架对模块 jar 自带任一语言目录中的文字、模块当前解析出的文字或原样 `{key}` 写出的形式逐字节相同（该项的缩进、`#`、一个空格加文字；只按相等判断，`#` 后缺空格或缩进不同即视为服主所写）；服主在令牌项上方手写的注释、改过的框架注释和字面注释逐字节永久保留（#604，维护者 2026-10-04 决定）。已不在任何自带目录中的旧版措辞，只有模块在新增的 `@ConfigEntry(previousComments = {...})`（增量属性，默认为空）中登记了该文字时才算框架所写：逐字节相同即替换为当前目录文字并从此随语言切换（维护者 2026-10-04 决定）；未登记的原样保留。面板编辑以服主同意为准，只替换它指定的键，不发警告（#600）。框架写的注释行被服主删除（设置本身保留）时，下次启动和下次 `/ul reload` 都会把它写回原位，用当时所选的语言，无论是否切换了语言；但如果该设置正上方还留着服主自己的注释行，这行就是该设置的全部注释、属于服主，框架不会在上面再写。服主写的值、键和行都不会变；切换语言时，该设置以外的框架注释照常跟随切换。出厂语言文件写的注释和自定义语言文件写的注释都是如此（由 `DeletedFrameworkCommentTest` 固定）。所以删除注释行去不掉注释：想让令牌设置不带框架注释，请把该行换成自己的注释（哪怕只是一个 `#`），框架认不出是自己的，就会保留，也不会在上面再写。整个设置（注释和键）一起删除时，下次启动会带着默认值和注释写回，位置在剩余设置之后，而不是原来的位置。
- 6.3.0 起模块 `save()` 只写模块自上次加载或保存以来改过的设置，并且只在文件该处仍是框架上次读到或写入的值、且该值正是模块的起始值（能无转换警告地转换为上次加载或保存时的设置值）时才写（#599，维护者 2026-10-04 决定）。声明为 `Map` 的设置按条目写（按声明的映射类型逐层进入嵌套映射），只设置或删除模块增、改、删的条目（空映射或被模块删空的映射整体写，因为它的键行随首条或末条一起变）；其他值——列表、Bukkit `Location`/`Vector` 等 `ConfigurationSerializable`、类型化映射中本身不是映射的条目值——都算一个值，要么整体写入要么不写，文件里不会出现模块与服主各占一部分的值。声明为 `Map<String, Object>` 或原始 `Map` 的设置只拆一层，其下的普通嵌套映射算一个值。重载合并（复合值或列表冲突时整体采用文件的值，只列键名）、面板编辑复合值内的字段（以框架上次读到的整个值加上该字段的改动为写入单位，且仅当写入时文件仍保存着该整个值；否则拒绝并注明设置名“文件在读取后已被改动，请先重载”，不写入）以及 `saveOperatorMapEntry`（键深入到非映射条目内部时抛 `IllegalArgumentException`，不写入）都按同一规则判断什么算一个值。保存从不补写文件缺少的键、从不改写注释、从不删除模块没删的映射条目。因此服主在磁盘上改过的值、删掉的键、框架无法使用的值或列表元素（`interval: 3O0`、`[60, 30, 10, abc]`）以及 6.2 拆开的映射条目，无论模块是否改了该设置，保存都不会覆盖（#596）。没写成的改动留在内存，每次保存一条警告列出文件和这些键，不列值；被写入闸门拒绝的键由闸门的警告连同原因列出。其余每一行逐字节不变，否则不写；没有可写内容时不碰文件。“文件仍是读取时的内容”比较的是框架上次读到或写入的文本：服主不经 `/ul reload` 直接在磁盘上改过的设置——哪怕值不变，比如把 `y: 64` 改成 `y: 64.0`——之后模块对该设置的改动不会写入（警告中列出），直到重载重新读取文件；没有任何内容丢失，模块的值留在内存中。`save()` 用于服主通过模块要求的改动，或语言切换后重新渲染出厂文字。6.2.5 的显式保存会直接覆盖服主在磁盘上改过的值；6.3.0 依维护者“服主写的配置绝不被自动覆盖”的规则取消此行为，没有过渡期。
- 6.3.0 新增两个服主操作写入方法（增量 API）：`saveOperatorChange(String...)` 只写指定的设置（如 `/setspawn` 指定六个 `spawn.location.*`），`saveOperatorMapEntry(String, String...)` 只写映射设置中的一个条目（如 `/autoreply` 的一条规则；文件缺少时插入，模块映射里已删除时从文件删除；每个映射键是完整键，`play.example` 是一个键）。服主的命令即同意：指定键处以模块的值为准，服主手改过的也替换；文件缺少的指定设置连同注释插入。其余一律不写（模块改过但未指定的设置也不写；用 `saveOperatorChange` 指定整个映射设置会整体写入并丢掉服主手加的条目，只改一个条目的命令应使用 `saveOperatorMapEntry`），写入经写入闸门且只拥有这些键。被拒绝时（文件不可读或无法解析、使用锚点、排版无法逐字节保留、准备写入期间文件被改）抛出新的 `com.ultikits.ultitools.config.ConfigWriteRefusedException`（`IOException` 子类），消息和 `getReason()` 说明原因、不含任何值；文件保持原样，内存中的值不变且仍未保存。路径不是已声明的配置项，或对非映射设置调用条目方法，抛 `IllegalArgumentException` 且不写。两者都在实体锁下执行，须在服务器主线程调用。
- 6.3.0 新增 `saveOperatorMapEntry(EntryPresence, String, String...)`（增量 API，#623）：映射条目的写入可附带存在性条件，`MUST_BE_ABSENT` 用于新建条目的命令（如 `/autoreply add`），`MUST_BE_PRESENT` 用于修改已有条目的命令（如 `/autoreply setkeyword`），模块不必再用自己的解析器另读一次文件。条件在写入闸门据以校验的同一次读取上判断，闸门在替换文件前还会再核对一次这些字节：服主自加载以来手动添加或删除的条目都能看到，此后保存的改动则让闸门以“文件已被改动”拒绝写入。“存在”指该条目的完整键路径在文件中存在（框架读取该设置的位置：嵌套键或扁平的带点键，#612，再接映射键，每个都是完整键）；值为 `null` 的条目算存在；上级键缺失、为 `null`、为空映射 `{}` 或不是映射时算不存在，缺失、空白或只有注释的文件不含任何条目。条件不成立时不写入（字节和修改时间不变），框架只记 FINE 级日志，并抛出新的 `com.ultikits.ultitools.config.ConfigEntryPresenceException`（`ConfigWriteRefusedException` 的子类）：`getRequired()` 给出不成立的条件，`getReason()` 给出条目的键路径，从不含值。条件为 `null` 时抛 `IllegalArgumentException`。两个重载都是 6.3.0 新增的。第一个参数直接写字面量 `null` 的调用（`saveOperatorMapEntry(null, "path", "key")`）因两个重载都匹配而无法编译；请把 `null` 强制转换为所需的类型（此时会抛 `IllegalArgumentException`）。
- `getConfig()` 在 6.2.5 确实可用，不能冒称符合两个同版删除例外；维护者通过 6.3.0 一次性 carve-out 删除它。改用 `isPresentInFile` 查询上次成功加载时的存在性（路径按声明设置路径的方式读取（#612）：按点拆成嵌套键、作为一个扁平带点键或两者混合，文件以任一形式持有该键即算存在，因此含点的完整映射键也能查到），修改声明字段后 `save()`。两个官方调用方 UltiEssentials 与 UltiRemoteBag 已在其适配 6.3.0 的版本中改用 `isPresentInFile`；第三方用量未知。
- 六个旧解析器相关声明在 6.3.0 首次带 `forRemoval`，公告 6.4.0 删除。显式非默认 parser 暂时保留冻结的旧行为；默认 parser 改走注册表。迁移示例见上方，转换器必须满足两条互逆等式，不能单向加值或悄悄丢字段。
- 注册批次验证完成才开始独立写文件；面板批次先验证并经写入闸门暂存全部文件，在进程内失败时回滚，持久存储故障可能阻止恢复；每个文件替换前再核对一次字节，期间被服主保存的文件整批拒绝（`ConfigWriteRefusedException` 注明文件）、服主的保存保留，已替换的文件恢复为核对时的原字节。回滚只处理本批次仍拥有的字节（#673）：本批次没有替换的文件（未执行到，或替换以普通错误失败、文件仍是原字节）回滚时绝不写入或删除，批次失败期间服主的保存保留；已替换的文件仅当仍是本批次写入的字节时才放回，之后被服主保存的保留并警告一次；放回本身失败的文件按 SEVERE 所述连同框架备份保留，回滚不再写它，该配置拒绝一切写入，直到服主比对两者并重载。面板唯一映射路径走整字段类型转换，歧义和未知变更拒绝整个请求；无关内存/磁盘兄弟项保留。
- 重载三方合并，内存独有改动保留且仍脏，磁盘独有采用，冲突磁盘胜。重载重建模块语言之后、模块自己的重载钩子之前，框架经写入闸门、按重新读取的文件，只把它能认出的自己的注释行改成新语言，不写任何值、键或其它注释行（#594）。仅磁盘该映射未变时保证内存顺序保留，不写文件。初始化、重载和注册表在服务器主线程执行；异步面板回调整体排队，不能阻塞等待。此规则不改变任何公开签名；`reloadSelf()` 自 6.3.0 起为 `final` 是另一项改动所致。
- 框架自己的 `plugins/UltiTools/config.yml` 启动时补入缺少的面板键（能力开关、`ultipanel.commands.blocklist`、`ultipanel.files.editable-roots`、操作日志轮转键）同样经过写入闸门，只插入（#605）。补键迁移是 6.3.0 新增的（6.2.x 只在 `config.yml` 不存在时写入它）；它在本周期较早的 6.3.0 快照中的第一个版本会整份重写文件：十六进制数变成十进制、锚点被展开、`o.O` 这样的含点键被拆成嵌套映射、显式 `null` 被替换。现在其余每个字节保持不变（已在每个已发布的 6.2.x `config.yml` 上实测）；闸门无法逐字节写回的文件（手工对齐、锚点）保持原样，警告一次列出这些键，由 jar 自带 `config.yml` 中的默认值（现在也列出命令黑名单和可编辑根目录）生效。若文件以扁平含点键（如 `ultipanel.capabilities.logs: false`）或含点的节键保存其中某个设置，而插入的嵌套节会在服务器读取时取代它，同样不写入：警告一次，指出文件和该设置（不含任何值），缺少的键使用 jar 默认值。较早版本实测：6.0.9 和 6.1.x 的 `config.yml` 同样只插入；6.0.9 之前各个 6.0.x 版本（6.0.0 至 6.0.8）的 `config.yml` 含 `trustIp: [ ]`（方括号内侧有空格），每次启动都被拒绝并警告一次、指出第 14 行，缺少的键使用 jar 默认值，不写入任何内容——只有从 6.0.8 或更早版本直接升级才会遇到；改成 `trustIp: []` 或手工补上这些键即可消除警告。
- UltiTools 只在 `plugins/bStats/config.yml` 不存在、或已含 `serverUuid` 时启动 bStats（#606）。该文件由所有使用 bStats 的插件共用；此前无法解析的文件被当作空文件，bStats 的默认值会写在它上面。现在无法读取、无法解析或缺少 `serverUuid` 的文件逐字节保持不变，记一行日志说明，本次运行 UltiTools 不发送统计。在 Paper 上，服务器自带的统计代码会在任何插件加载之前读取该文件，并用新的默认值重写无法解析或缺少 `serverUuid` 的文件（在 Paper 1.21.11 上实测），因此这类文件是被服务器本身替换的，而不是被 UltiTools 替换的，UltiTools 只会看到有效的文件。
- 面板编辑 `server.properties` 时只替换它指定的那个键所在行的值文字（#607）：键、分隔符、注释、顺序和其它每个字节保持不变（含 UTF-8 的 `motd`），按服务器读取该文件的方式解码和编码（严格 UTF-8，不是 UTF-8 时用 ISO-8859-1），校验后原子写入。同一个键定义在多行、或定义续到下一行时拒绝，原因注明行号（不含值）；`set_all` 回复把它列在 `failed` 中，并在新增的 `failureReasons` 字段中给出原因，服务器日志对每个被拒的键只记一次。服务器自己下次启动时仍会整份重写该文件（在 Paper 1.21.11 上实测）。
- 6.3.0 新增 `com.ultikits.ultitools.config.OperatorFiles`（增量 API），供模块在服主明确编辑时写入它代为管理的 YAML 文件（礼包、菜单文件）：只写编辑的部分，注释和其它键保留（维护者 2026-10-04 决定）。`read(File)` 返回文件文本（严格 UTF-8）和字节的 SHA-256；`write(Snapshot, Map<List<String>, Object>)` 只写指定的完整键（含点的键是一个键），经写入闸门，且仅当文件仍是快照时的字节，返回 `WRITTEN`、`UNCHANGED`、`FILE_CHANGED` 或 `REFUSED`（排版无法逐字节保留或使用锚点，警告一次列出文件、键和原因）。值必须是普通数据（`ItemStack` 先序列化），否则在碰文件之前抛 `IllegalArgumentException`。它从不创建、删除或重命名文件，不是通用文件 API（#545 仍是以后的功能），也不用于 `@ConfigEntity` 文件。
- 面板编辑复合值（Bukkit `Vector`、`Location`，或映射条目中的此类值）内的一个字段时，写入的是文件中原样的整个值、只改该字段，其余字段保持服主写下的字节（`y: 64`、`pitch: 0` 不会变成 `64.0`）。面板发送的整数、或文件中该值其它字段里的整数，在模块自身的值为浮点数的位置按浮点数转换（Bukkit 的 `Vector` 自己不放宽），被编辑的字段按该类型写入（`7` 写成 `7.0`），因此编辑后的文件能被重新加载（#609）。文件中 `Vector` 或 `BlockVector` 坐标里的整数（`y: 64`）现在按该数字加载：Bukkit 的 `Vector` 只接受 `Double` 坐标，此前这样的值会以 SEVERE 堆栈失败并使用声明默认值；框架现在只在内存中把 `Vector` 的 `x`、`y`、`z` 放宽为浮点数——文件保持 `y: 64`，未改动的保存不写入，其它类型不放宽。模块在内存中删除且未保存的映射分组，编辑其下的键会被拒绝，注明“not found in memory”。
- 服务器关闭、模块卸载或卸载删除、模块被新版本替换时，一律不写任何配置（维护者 2026-10-04 决定：生命周期事件不是服主的写入请求，不做整对象的关闭保存或替换保存）。关闭时以及正常卸载或卸载删除时，每个模块在卸载钩子和 `@PreDestroy` 之后、释放之前，凡有从未保存的模块改动的配置各警告一次（列文件和键，不列值），改动随之丢弃；框架最后一步对仍注册的配置同样处理。上次加载无法读取或解析的文件只提示一次（6.2.5 在加载时就会重写它：启动时写入声明默认值，重载时写入运行中的值；关闭时再用模块内存中的值重写一次）。新版本副本直接读取现有文件，旧副本的未保存改动或受保护文件不会阻止替换；新副本激活后警告一次丢弃的键。`ConfigManager#saveAll()` 签名不变、不再写入、改为报告并标记 `@Deprecated(since = "6.3.0")`。6.2.5 及以前在服务器关闭时由 `saveAll()` 用模块内存中的值重写所有已注册的配置文件，6.3.0 直接取消，没有过渡期。模块应在改动发生时用 `save()` 或 `saveOperatorChange`/`saveOperatorMapEntry` 保存。卸载时释放该模块的配置实体（6.2.5 不释放）。已知限制：#578 中仍适用的是符号链接后跟 `..` 的临时文件路径和 Unicode 文件的样式检测耗时；锚点容器与别名注释两项不会发生，因为任何写入路径都不写入使用锚点、别名或合并键的文件。#580（见上文读取器无法读取的排版）和多文件崩溃限制 #545 仍存在。登记表同样按此顺序（#562）：新副本通过兼容性检查后、刷新容器和运行 `registerSelf()` 之前，先释放旧副本的面板响应器、EventBus 处理器和补全器；新副本失败时释放它自己的登记、把旧副本的登记原样放回，旧副本照常运行（见“Behavioral changes”中的说明）。

## What the version number means

**This project's version numbers are a product-stage signal, not a strict semver contract.**

- **PATCH** (for example 6.2.4 → 6.2.5): small updates and urgent fixes. No public API is removed.
- **MINOR** (for example 6.2.x → 6.3.0): feature evolution. **May include removal of public API.**
- **MAJOR**: reserved for a change of direction at the framework level. It will not be issued
  merely to clean up deprecated API.

### When a public API becomes eligible for removal

An API is placed on the removal list only when both of the following hold:

1. It carries `@Deprecated(since = "…", forRemoval = true)` in the source;
2. At least one MINOR release has passed since **the first release that carried that annotation**.

The clock in condition 2 starts at the version where the warning actually reached you, not at the
value written in `since`. `since` expresses when we consider the API to have become deprecated and
can be backfilled; the starting point cannot. The registry (`compatibility/DEPRECATIONS.md`) records each entry's `since` value as written in the source. It does
not record the first release that carried the annotation; that release is the one the clock counts from.

These two conditions are the complete basis for removal. Downstream usage counts are no longer part
of it. The usage figures recorded for each removal (in `compatibility/records/6.3.0.md`) are
informational: they tell you which removals carried real migration cost, but "zero references"
only proves that nobody in the repositories we can see uses it, not that no third party outside
the organization does.

`forRemoval` is used rather than a bare `@Deprecated` because javac's `-Xlint:removal` has been **on
by default since JDK 9**, while `-Xlint:deprecation` is **off by default**. An API marked
`forRemoval` is reported by name at every use site in your build; one marked only `@Deprecated`
produces a single summary line with no API name and no line number. We therefore treat "you were
warned by name" as a precondition for removal.

#### Exception: removal in the same release that announces it

The two conditions above are the rule for the ordinary case: a working API that downstream code may
reasonably depend on. They have exactly one general exception, stated here once rather than argued
case-by-case at each occurrence: **an API may be removed in the same release that first carries
`@Deprecated(forRemoval = true)` on it — skipping condition 2 entirely — when at least one of these
two clauses holds, and the removal's own entry states which one and its evidence:**

1. **The API is proven non-functional on the currently released version.** "Proven" means a
   **reproduction** — a run whose output shows the API failing on the version currently published —
   quoted in that removal's own entry. An argument that the API *looks* broken, without a run that
   shows it, does not satisfy this clause. Loosen this standard even once and the clause stops being
   an exception and becomes a second, unwritten removal window that retires the one-MINOR window a
   use at a time, each individual use looking reasonable on its own.
2. **The API was never published in a tagged release, or was shipped but never wired into anything
   that calls it.** There is no working behaviour for a deprecation window to warn anyone away from,
   because no released version could ever have exercised it.

**6.3.0 is a one-time carve-out.** 6.3.0 is this project's first stable release. For 6.3.0 only,
this exception was applied to other breaking changes as well as to removals, and none of the changes
recorded as uses of it required the changed API to have carried `@Deprecated(forRemoval = true)`.
The evidence recorded for those changes is kept as it was applied during the 6.3.0 cycle. It does
not always match the clauses' wording above, and clause 2 in particular was assessed in more than one
way. From the release after 6.3.0 onward, the exception, including both clauses' evidence
requirements, applies strictly as written above.

Each removal that relies on this exception names in its own entry which clause it used and the
evidence for it. For 6.3.0, every removal that relies on it is in
["Same-release exceptions applied in 6.3.0"](#same-release-exceptions-applied-in-630) below — read
that section through rather than reading a number off it, because it carries a list of removals and
then two further entries after that list — and each is recorded in full, with the measurement behind it,
in [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md). No total is stated here: a
count kept away from the list it counts has nothing holding it honest, which is how the count that
used to sit in this paragraph went stale.

That section lists removals only. The exception as written covers removals, but under the
one-time 6.3.0 carve-out above, changes that are not removals also cite its clauses in their own
entries, and they are not listed there: `UltiToolsPlugin#unregisterSelf()` and `#reloadSelf()`
becoming `final` (see the third
occurrence under
[Binary incompatibilities the removal list cannot cover](#binary-incompatibilities-the-removal-list-cannot-cover)),
and `GuiRenderer.initialize`'s first parameter changing from `Widget` to `Supplier<Widget>`. Their
cited clause and evidence are recorded in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

### Two deliberate deviations from semver

The `MAJOR.MINOR.PATCH` format invites [semver](https://semver.org/spec/v2.0.0.html) expectations.
Two of ours differ from the specification, and we name them here:

- **Timing of removal.** Semver waits for the next MAJOR after deprecation; this project removes in
  a MINOR once one MINOR has passed. If you need a strict binary compatibility guarantee, pin an
  exact PATCH version.
- **We do not adopt the permissive reading of semver clause 6.** That clause allows "correcting
  incorrect behaviour" to ship as a PATCH bug fix. This project does not: any change that alters
  downstream runtime behaviour goes through the [Behavioral changes](#behavioral-changes) section
  below, and does not skip the migration period on the grounds of being a bug fix.

### This section covers the framework's own version number only

The rules above apply to `UltiTools-API` itself. Versioning your own module is a separate contract,
decided by whether a server owner has to do anything after dropping in the new JAR. See
[Module versioning](https://dev.ultikits.com/guide/advanced/module-versioning.html)
([Chinese translation](https://dev.ultikits.com/zh/guide/advanced/module-versioning.html)).

The two are intentionally different, and should not be unified. The difference lies in what the
version number is used for:

- The framework's version number is **resolved and linked against**. Maven uses it to select an
  artifact, and already-compiled downstream plugins link against its classes at runtime. Both are
  compatibility questions.
- A module's version number is also machine-read, but **only for ordering**.
  `PluginManager.hasNewerVersionLoaded` and `unregisterSupersededVersions` compare versions to decide
  which JAR wins when two exist for the same module, and `UpdateManager.checkModuleUpdates` compares
  versions to report available updates. All three go through `VersionComparatorUtil.compare`, which
  only asks whether A is greater than B. **None of them inspects whether the difference is MAJOR,
  MINOR or PATCH.** Modules are also not published to Maven and are not linked against by anything.

So: the *ordering* of module version numbers is consumed by machines, while the *meaning* of
MAJOR/MINOR/PATCH is not — that part is addressed to server owners. On the framework side both are
machine-consumed, which is why its version number does not have the same freedom.

## Declaring the dependency

Maven:

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version><!-- see GitHub Releases --></version>
    <scope>provided</scope>
</dependency>
```

Gradle:

```groovy
compileOnly 'com.ultikits:UltiTools-API:<version>'
```

**Use `provided` / `compileOnly`.** The POM published to Maven Central is processed by
flatten-maven-plugin and carries no dependency declarations, so placing UltiTools-API in any scope
beyond compile time (Maven `compile`, Gradle `implementation`) gains you no transitive dependencies.
What it does do, if your build has a shade or shadow step, is bundle the entire shaded framework into
your module JAR, where it conflicts at runtime with the UltiTools already installed on the server.
## Removal list for 6.3.0

The removal list is generated at `mvn verify` from the `@Deprecated(forRemoval = true)`
annotations in the source, cross-checked against the japicmp binary-compatibility report so a
disagreement between the two fails the build (`DeprecationRegistryGenerator` produces
`compatibility/DEPRECATIONS.md`; see `compatibility/deprecations.json` for the machine-readable
form). As of v6.3.0, the mandatory generator is compiled into `target/buildtools-classes`
independently of test compilation. Both `mvn verify -DskipTests` and
`mvn verify -Dmaven.test.skip=true` still run it after japicmp: `skipTests` skips only test
execution, while `maven.test.skip` also skips test compilation. Neither skips registry generation;
existing registry identities and removal history remain cumulative.

This document no longer carries the table itself — three artifacts now split the job:

- **This document** — policy: what removal means, when an API becomes eligible, the same-release
  exception, and permanent lessons.
- [`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md) — the generated, cumulative
  registry: every deprecated/announced/removed member, its `since`, its replacement, and its
  status.
- [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md) — the 6.3.0 cycle's
  behavioural record: what each removal actually deletes, its replacement, and — per this
  document's own downstream-diagnostic design — exactly what an un-recompiled downstream JAR
  sees.

Packages and types marked `@ApiStatus.Internal` are not part of the removal list even when
japicmp's exclusion list carries an entry for them — japicmp reads bytecode, not `@ApiStatus`,
so an internal-only removal still needs an exclusion entry to keep the binary-compatibility gate
green, but it was never public API and its removal is not a compatibility event. See
`compatibility/records/6.3.0.md`'s WIRE-17 entry for a worked example; `manager.ServerPropertiesManager.SetAllResult`'s
public constructor widening from five `List<String>` parameters to six (Gate-2, 6.3.0) is the same
pattern — a same-release, internal-only signature change recorded there for japicmp traceability
only, not because it is itself a compatibility event.

### Same-release exceptions applied in 6.3.0

The removals in the nine entries below used the
[same-release exception](#exception-removal-in-the-same-release-that-announces-it) above, on the
terms of its one-time 6.3.0 carve-out, instead of waiting a full MINOR:

- `aop.CglibProxyFactory` — clause 1, proven non-functional (issue #188).
- `aop.ProxyFactory.createProxy(T)` / `createProxy(Class<T>, T)` and
  `aop.AopProxyBeanPostProcessor` — clause 2, never reached a tagged release or shipped with zero
  callers (issue #190).
- `annotations.Propagation.NESTED` — clause 2, on controllability rather than impossibility:
  savepoint behaviour depends on whichever `sqlite-jdbc` version the server's own Paper build
  happens to ship, which this project can neither pin nor test across.
- `PluginManager`'s seven-argument `register(Class, String, String, List, List, int, String)` —
  clause 1, proven non-functional on every release since 6.2.0 (Phase 1 D-15).
- `ListenerManager.registerAll(UltiToolsPlugin, String)` — clause 2, zero callers anywhere in
  `src/main` at removal time.
- `listeners.EnhancedPlayerEventListener` — clause 1, proven non-functional: it carried
  `@EventListener`, nothing registered it, and all seven of its handlers were measured never to
  fire on a real 1.21.4 server (issue #387).
- `manager.LogStreamManager.pauseLogStream(String)` / `resumeLogStream(String)` — clause 1, proven
  non-functional: `LogStreamManager` auto-subscribes a permanent, never-paused `"auto"` client on
  every WebSocket connect, so the delivery-suppression check these methods drove could never
  observe "no active subscriber" for any real panel session on any released version — reproduced
  by a test that pauses a distinctly-named client while `"auto"`'s entry survives untouched, which
  still delivers (issue #434, maintainer decision D-19). Removed rather than fixed because a true
  per-viewer pause needs Worker-side viewer identity this framework does not have and is not
  authorized to add here; the framework now rejects `pause`/`resume` outright instead.
  **Addendum (D-20, issue #468):** the same reasoning applies to `startLogStream(String, String)` /
  `startLogStream(String)` / `stopLogStream(String)` — measured end to end, the shipped frontend
  only ever toggles `start`/`stop` from its own button without gating rendering on the response,
  the Worker's REST log-stream endpoint is a stateless relay minting a disposable `clientId` per
  call with no per-browser stream state, and `stopLogStream` mutated only bookkeeping nothing on
  the delivery path ever consulted — `stop` never actually stopped delivery for any real client on
  any released version. `start`/`stop` are now rejected the same way as `pause`/`resume`; the
  now-inert `isStreaming()`/`getSubscriberCount()` accessors are removed alongside them, and the
  `status` action is answered with a redefined, genuinely-honest set of fields instead (no
  behavioural contract existed for `status`'s exact response shape, so this is not itself a
  removal).
- `UltiTools#getEconomy()` — clause 2, public and zero measured callers anywhere in this
  framework's own `src/main`, all sixteen product modules of the time (the fifteen active modules and the
  since-discontinued `UltiBot`), and the external example (issue #451).
  It was also the sole cause of a total bootstrap crash on a server with no Vault plugin
  installed: reflecting over `UltiTools`'s declared methods (done the instant the core plugin
  bean is registered) eagerly resolved this accessor's `net.milkbowl.vault.economy.Economy`
  return type, which is absent from the classpath when Vault is absent. Replacement:
  `EconomyUtils.getEconomy()`, on the pre-existing `EconomyUtils` façade. No module calls that
  accessor: measured 2026-09-17 on the `origin/master` of every `Modules/` repository and of
  `Tooling/UltiTools-External-Example`, `EconomyUtils.getEconomy()` has **0** references, while the
  four modules that use `EconomyUtils` at all (`UltiEssentials`, `UltiKits`, `UltiMenu`,
  `UltiRemoteBag`) call its wrapper methods — `isAvailable`, `format`, `getBalance`, `withdraw`,
  `has` — instead.
- `abstracts.gui.declarative.widgets.Container.Builder.background(IconWrapper)` /
  `Container.getBackground()` and `GridView.Builder.rows(int)` / `GridView.getMaxRows()` — clause 2,
  zero callers at removal time (plan 05-13). All four were public in the released 6.2.5. Each pair
  did return through its getter the value its builder method stored, but no framework code consumed
  that value, and no downstream code called any of the four: measured 2026-09-17 on the
  `origin/master` of every `Modules/` repository and of `Tooling/UltiTools-External-Example`, their
  names have **0** hits and no file imports anything under `abstracts.gui.declarative`, against **9**
  imports of the imperative `abstracts.gui` pages in the same search. Their package carries
  `@ApiStatus.Experimental` and neither pair was ever `@Deprecated(forRemoval = true)`, which is why
  the generated removal list does not carry them. See
  [their records entry](compatibility/records/6.3.0.md#recorded-instance-two-declarative-gui-builder-method-pairs-are-removed-rather-than-given-a-guessed-implementation-d-09-630).

Full reasoning and evidence for every entry above live in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

**One further exception, added by plan 16-09 (D-17, root-cause group "cloud session," part 2 of 3
for issue #298) after the list above:** `utils.CloudAuthManager`'s fine-grained public statics —
`loadSavedToken()`, `refreshToken(String)`, `saveToken(TokenEntity)`, `clearToken()`,
`currentCredentialGeneration()`, `invalidateCredentialOperations()`,
`commitTokenIfCurrent(TokenEntity, long)`, `getCurrentToken()`, `hasValidToken()`,
`requestMagicLink(Consumer<String>)`, both `startPolling(String, Consumer<TokenEntity>)` overloads,
`startTokenRefreshScheduler()`, `stopTokenRefreshScheduler()`, `stopPolling()`, and the class's own
implicit public no-arg constructor — plus `utils.PluginInitiationUtils#loginWithToken(TokenEntity)`
and `#activateCloudIfCurrent(long)` — clause 2, zero external callers. Measured 2026-09-14 across
every module repository under `Modules/` (the 15 active modules, the discontinued `UltiBot`, and
the non-product `ultikits-module-parent`) plus `Tooling/UltiTools-External-Example`: `grep -rlI
"CloudAuthManager" --include=*.java` and the same for `"PluginInitiationUtils"`,
`"loginWithToken"`, and `"activateCloudIfCurrent"` each return **0** files across all 18
repositories, against a control query for `"UltiToolsPlugin"` over the same roots returning **173**
files (proving the search itself works — a bare zero is not evidence on its own). Separately,
`strings`-scanning the one downstream consumer jar available locally
(`UltiTools-External-Example-1.0.0.jar`) for the literal string `CloudAuthManager` also returns
**0**, against a control string (`UltiToolsAPI`, the class that jar's `onEnable()` actually calls)
returning **1** — confirming the jar-level probe methodology works and the class name is genuinely
absent from that consumer's compiled bytecode, not merely absent from its source tree. The three
command-facing entry points `CloudAuthManager.login(...)`/`.logout()`/`.status()` are the
replacement surface; the class is now `@ApiStatus.Internal`. Full reasoning, the complete member
list with its own javap output against the 6.2.5 baseline, and the downstream-author paragraph are
in [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md)'s own entry for this removal.

**One further exception, added by plan 18-30 (maintainer decision 2026-10-09, issue #652):**
`manager.DependenceManagers#getAdventure()`, `#initAdventure(UltiTools)` and `#closeAdventure()`
are removed together with the `net.kyori:adventure-platform-bukkit` library declaration in the POM
and `plugin.yml`. This records **clause 2 as applied under the one-time 6.3.0 carve-out**, following
the zero-caller `UltiTools#getEconomy()` precedent above; it does not widen clause 2 for later releases.
Measured 2026-10-10 with `git grep -n -i -E
'getAdventure|initAdventure|closeAdventure|BukkitAudiences|adventure\.platform'` on the fetched default
branch of all **18 repositories** — the fifteen active modules, discontinued `UltiBot`, the POM-only
`ultikits-module-parent`, and `Tooling/UltiTools-External-Example` — every provider query returns
**0** matching lines. The same-scope `UltiToolsPlugin` positive control returns **1,206** lines in
aggregate, including **7** in the external example; the POM-only parent honestly returns **0** for
that control. These measurements do not prove absence of unknown third-party callers.

`javap -public` on the published **6.2.5** JAR confirms all three methods were public, and
`javap -v` confirms the class itself already carried **`@ApiStatus.Internal`**. This document's
internal-only rule would therefore not treat their removal as a public-API compatibility event;
the entry is retained because the maintainer explicitly requested documentation, with exact
member-level binary-gate exclusions rather than a class-wide exemption. Replacement: Paper's native
`Player#sendMessage(Component)`, used by `MessageUtils` since [PR #651](https://github.com/UltiKits/UltiTools-Reborn/pull/651).
On Paper 1.21.11 the old provider 4.3.2 falls back to legacy text and drops click/hover events,
making `/upm list` buttons ineffective. The provider was added in August 2023 for Spigot, which has
no native Adventure support; the framework is now Paper-only and upstream
[`PaperMC/adventure-platform`](https://github.com/PaperMC/adventure-platform) is archived.
An un-recompiled class merely containing an invocation of a removed method can still load;
executing that call site raises `NoSuchMethodError`. Full published signatures, evidence and the
migration boundary are in the new audience-provider entry in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

中文补充：计划 18-30 按维护者 2026-10-09 的决定删除三个方法及 audience-provider 库，并以
6.3.0 一次性豁免下第 2 条的零调用方先例记录，不扩大后续版本的豁免条件。18 个仓库均为零引用，
相同范围的阳性对照合计 1,206 行（外部示例 7 行；仅有 POM 的父项目为 0），不代表未知第三方也无调用。
三个方法在 6.2.5 为 public，但类当时已标记 `@ApiStatus.Internal`；本来属于内部变更，仍按维护者要求
留下记录。替代是 Paper 原生 `Player#sendMessage(Component)`。旧库在 Paper 1.21.11 丢失点击和悬停事件，
其 2023 年为 Spigot 引入的理由已不适用，上游也已归档。旧类仅含调用指令时仍可加载，执行被删除的方法
才抛出 `NoSuchMethodError`；迁移时应替换调用并重新编译。

### Measurement notes carried forward from the 6.3.0 survey

How reference counts were measured (informing which removals were low-risk, though never the
basis for removal itself): on 2026-08-14, across 17 module repositories, 4 tooling projects and
Libraries under the UltiKits organization, covering 310 Java files (the file list was not retained), excluding test directories
and build output, counting only imports and `extends`.

The `6.2.1` starting point used throughout the 6.3.0 removal cycle is a deliberately conservative
choice, not a consequence of 6.2.0 being unverifiable. 6.2.0 was published to Maven Central; it
simply has no corresponding git tag, though it does have a release commit in the repository
history (`0286e26 release: UltiTools-API v6.2.0`) that can be checked. Setting the start at the
later 6.2.1 only lengthens the deprecation period and favours downstream, so it stays.

One verification note worth recording: the release list for this project is Maven Central's
`maven-metadata.xml`, **not `git tag`** — there is no `v6.2.0` among the tags. Inferring "this
version was never released" from the tag list produces a wrong conclusion in this repository.

**If your module references any removed API, please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues).** The registry's reference
counts are not the basis for removal, and do not support a conclusion that nobody is using
something.

## Migrating off `AbstractCommandExecutor`

`abstracts.AbstractCommandExecutor` was the only removed API in 6.3.0 with real downstream users
(15 files across 6 repositories, measured). It has been removed, ahead of those repositories' own
publication — see [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md) for the full
reasoning and the maintainer's decision to proceed.

To migrate to `abstracts.command.BaseCommandExecutor`:

1. Change the superclass: `extends AbstractCommandExecutor` → `extends BaseCommandExecutor`.
2. Implement the new abstract method `protected void handleHelp(CommandSender sender)`.
3. `@CmdMapping` / `@CmdParam` / `@CmdTarget` / `@CmdCD` / `@UsageLimit` keep their semantics.
4. The package-scanning overload `CommandManager.registerAll(UltiToolsPlugin, String)`, which cast each scanned
   class to `AbstractCommandExecutor`, was removed in 6.3.0. Commands resolve through the IoC container
   (`registerAll(UltiToolsPlugin)`), which accepts `BaseCommandExecutor`; nothing beyond steps 1-3 is needed unless
   your module called the removed overload itself.

The misspelled empty shim `AbstractCommendExecutor` extended `AbstractCommandExecutor`, so it was removed in the same
release as its parent, 6.3.0.

The new base class had one gap at 6.2.5, parameter-level tab completion; it is wired up in 6.3.0, so
`@CmdParam(suggest = …)` completes parameters, including at the first argument position.

(A bare command declared with `@CmdMapping(format = "")` was not executable. This was previously
scheduled for 6.2.5 and **has been fixed in 6.2.5**.)

When to migrate depends on who you are:

- **Modules inside the UltiKits organization** were migrated by the maintainer during the 6.3.0 cycle; their
  6.3.0 releases need nothing from you.
- **Third-party modules outside the organization** must migrate before running on 6.3.0: a command class that
  still extends `AbstractCommandExecutor` cannot be loaded there, because that class no longer exists. The
  transition window was 6.2.5, where a migrated command completed only literals at the first argument position;
  6.3.0 completes parameters as well.

## Additions in 6.3.0

New public API in a MINOR release. An addition breaks no existing module: nothing below changes a
signature or a behaviour a module already relies on.

### `UltiCloudRequests`: authenticated UltiCloud requests on a module's behalf

`com.ultikits.ultitools.utils.UltiCloudRequests` (public, `@since 6.3.0`) sends an UltiCloud request
with this server's UltiCloud credential **without giving the module the credential**. The framework
still never hands a module the cloud token: the session that holds it is package-private, and no public
static member of `com.ultikits.ultitools.utils` accepts or returns a `TokenEntity` (the 6.3.0 rule that
a structural test enforces). The helper is the only way for a module to make UltiCloud recognise a
request as coming from this server. UltiLogin's `/panel` web login link is its first user.

- `post(String path, String jsonBody)` and `get(String path, Map<String, String> query)` return a
  `Result` with an `Outcome` (`OK`, `NOT_CONNECTED`, `PATH_NOT_ALLOWED`, `IO_ERROR`), the HTTP status
  (`-1` when no exchange completed) and the response body (`null` when no exchange completed). `OK`
  means an exchange completed, whatever its status; the caller reads the status code.
- **Allow-list, exact match per method:** `POST /auth/magic-link` and `GET /auth/magic-link/poll`.
  Any other path, an allowed path with the other method, or a path containing `..`, `//`, `\`, `%`,
  `?` or `#` returns `PATH_NOT_ALLOWED` and makes no request. Query parameters go through the `query`
  map only, URL-encoded as UTF-8. The list grows only by a documented contract change in a framework
  release; a module cannot widen it.
- **No valid session** (no `/ulticloud login`, after `/ulticloud logout`, or the saved credential has
  expired): `NOT_CONNECTED`, no request. Do not retry anonymously: UltiCloud refuses anonymous
  `/panel` link creation (HTTP 401) and UUID-only polling (HTTP 400; `requestId` is required).
  The operator must log the server in to UltiCloud with `/ulticloud login` before requesting a link.
- **The token is never exposed:** it is not returned, the framework never logs it, and it is not placed
  in an exception message that reaches the caller (a failure of the exchange, including one the JDK
  raises with the header value in its message, is `IO_ERROR` with the message dropped) or in
  `Result.toString()`; no public member of the class or of `Result` is typed `TokenEntity`. Redirects
  are not followed, so the credential is never re-sent to another host; a 3xx comes back as an `OK`
  result with that status.
- **Known limitation:** the JDK's own HTTP client logger, `sun.net.www.protocol.http.HttpURLConnection`,
  prints request headers, the bearer included, when an operator raises it to `FINE` or lower. Its
  default level does not, and the limitation applies equally to every request the framework sends with
  the UltiCloud credential. Do not enable that logger on a production server.
- **Threading:** both methods block on network I/O (connect timeout 10 s, read timeout 30 s) and throw
  `IllegalStateException` on the server's primary thread. Call them from an asynchronous task.
- The helper writes nothing: no file, no configuration, no log line.

中文补充：6.3.0 新增公开类 `com.ultikits.ultitools.utils.UltiCloudRequests`（增量 API，不影响现有模块）。它以本服务器的
UltiCloud 凭据代模块发送请求，但**从不把凭据交给模块**：框架仍不向模块提供云令牌，`utils` 包中没有任何公开静态成员接收或返回
`TokenEntity`（6.3.0 的规则，由结构测试保证）。`post`/`get` 返回 `Result`：结果类型 `Outcome`（`OK`、`NOT_CONNECTED`、
`PATH_NOT_ALLOWED`、`IO_ERROR`）、HTTP 状态码（未完成交换时为 `-1`）和响应正文（未完成交换时为 `null`）；`OK` 表示完成了一次
HTTP 交换，不论状态码，由调用方判断。只允许两个请求，按方法精确匹配：`POST /auth/magic-link` 与 `GET /auth/magic-link/poll`；
其他路径、方法不对，或路径含 `..`、`//`、`\`、`%`、`?`、`#` 时返回 `PATH_NOT_ALLOWED`，不发请求；查询参数只能经 `query`
映射传入，按 UTF-8 编码。允许列表只会随框架版本中有文档记录的协议变更而扩大，模块无法扩大它。服务器未登录 UltiCloud（未执行
`/ulticloud login`，或已 `/ulticloud logout`，或保存的凭据已过期）时返回 `NOT_CONNECTED`，不发请求。不要匿名重试：UltiCloud
拒绝匿名创建 `/panel` 链接（HTTP 401）及仅凭玩家 UUID 轮询（HTTP 400，必须提供 `requestId`）。服主必须先通过
`/ulticloud login` 将服务器登录到 UltiCloud，再请求链接。
令牌不会被返回，框架自身不会把它写入日志，也不会出现在到达调用方的异常消息（交换失败一律为 `IO_ERROR`，异常消息被丢弃，包括 JDK
在消息中带出请求头值的情况）或 `toString()` 中；不跟随重定向，凭据不会被转发到其他主机，3xx 作为 `OK` 结果原样返回。两个方法都会阻塞
网络 I/O（连接超时 10 秒、读取超时 30 秒），在服务器主线程调用时抛出 `IllegalStateException`，请在异步任务中调用。它不写任何
文件、配置或日志。已知限制：服主若把 JDK 自带的 HTTP 客户端日志器 `sun.net.www.protocol.http.HttpURLConnection` 调到 `FINE`
或更低级别，它会打印包括凭据在内的请求头；默认级别不会，框架用该凭据发出的所有请求都同样如此。请勿在正式服务器上开启该日志器。

## Behavioral changes

Some changes leave every method signature untouched yet alter how your module behaves at runtime: a
method that used to return silently starts throwing, a default value flips, a missing optional
dependency turns from degraded operation into a failed load. Signature comparison tools cannot detect
these, and the removal list above does not cover them. This section explains how we handle them.

### Three kinds of compatibility

Following the taxonomy used by
[OpenJDK CSR](https://wiki.openjdk.org/display/csr/Kinds+of+Compatibility) and
[dotnet/runtime](https://github.com/dotnet/runtime/blob/main/docs/coding-guidelines/breaking-change-definitions.md):

- **Source compatibility** — whether your code still compiles. Removing a type, changing a method's
  parameter list, or adding an abstract method to an interface all break it. Note that **changing a
  return type often does not**: callers usually do not spell out the return type's name, and a
  recompile resolves it.
- **Binary compatibility** — whether your **already-compiled** JAR still loads and runs against the
  new framework. The typical symptom of breaking it is `NoSuchMethodError` or
  `NoClassDefFoundError`. The kind of change that "a recompile resolves" in the previous point is
  fatal to a JAR that is not recompiled.
- **Behavioral compatibility** — it compiles, it loads, but **it does something different**.

The removal list and the versioning rules govern the *intentional* part of the first two kinds. For
unintentional binary breakage see
[Binary incompatibilities the removal list cannot cover](#binary-incompatibilities-the-removal-list-cannot-cover).
This section governs the third kind.

### Behavioral changes that need no migration period

- Correcting behaviour that plainly contradicts the documentation (the docs say it returns
  `Optional.empty()`, the code throws an NPE).
- Tightening the handling of previously undefined input (passing `null` used to be undefined
  behaviour, now it throws `IllegalArgumentException`).
- Changes in performance, memory footprint, log wording, or exception message text.
- Security fixes. These may land in a PATCH without prior notice.
- Refreshing an extracted resource file nobody has customised. Before 6.3.0, `saveResources()`
  skipped an already-extracted `lang/` file unconditionally, so an operator who never touched it
  kept whatever an older jar first extracted, forever — a defect (#441), not a documented
  guarantee that the file would stay frozen. As of 6.3.0, a `lang/` file whose recorded extraction
  hash still matches its on-disk bytes is replaced by the current jar's copy on the next start,
  with one INFO line naming the file. A file the operator has edited since it was recorded is
  restored as well (#608; see the entry on official language files below); only when that
  restore cannot be written (a read-only or symbolic-link file) is the edited file kept, with the
  individual keys whose placeholder count moved resolved from the jar instead (see
  `ultitools.language.file-refresh`/`ultitools.language.file-preserve` in `FEATURES.md`). A file
  with no record at all is handled by the next entry.
- **Upgrade note — language files without a provenance record are replaced on the first 6.3.0
  start, including files an operator edited (#459).** Every `lang/` file extracted before 6.3.0
  has no provenance record, so 6.3.0 cannot tell an operator's edit from a file that is merely an
  older jar's wording. The maintainer decided on 2026-09-29 that on the first start after upgrading,
  every such file whose bytes differ from the new module jar's copy is **replaced** by that copy and
  recorded, and accepted in writing that this replaces operator-edited files too. Nothing is
  deleted:
  - the previous file is kept beside the new one, in the same
    `plugins/UltiTools/pluginConfig/<module>/lang/` folder, as `<file>.bak` — for example
    `lang/en.json.bak`. If that name is taken, the backup is `<file>.1.bak`, then `<file>.2.bak`,
    and so on; an existing file is never overwritten. No backup name ends in `.json`, `.yml` or
    `.yaml`, so a backup is never loaded as a catalogue;
  - one WARNING per replaced file names the file and its backup;
  - **to keep your edit, move it into a custom language file — do not rename the backup back.**
    As of #608 an official language file is restored at every start (see the entry on official
    language files below), so a `.bak` renamed back to `<file>` is replaced again on the next start.
    Copy the `.bak` to a new name that starts with the language code and a hyphen, keeping the
    extension — for example `lang/en-myserver.json` — and select that name as described there;
  - a file byte-identical to the jar's copy is only recorded, with no backup and no log line; a
    file that already has a record keeps the rule in the previous entry; a second start changes
    nothing;
  - if the backup, the replacement or the record cannot be written (a read-only or symbolic-link
    file, a folder that is not writable), the original file stays in place, nothing is recorded,
    no backup is left behind, and the file is used as before, with the placeholder-count guard.
  The framework's own `lang/` catalogue was never extracted before 6.3.0, so no framework file is
  affected by this note; as of 6.3.0 the framework writes its official files to
  `plugins/UltiTools/lang/` (see the entry below). No per-release fingerprint list is used to spare
  edited files; that alternative was offered and not chosen. See
  `ultitools.language.unrecorded-replace` in `FEATURES.md`.

  中文补充：升级到 6.3.0 后第一次启动时，所有没有来源记录、且与新版模块 jar 自带版本不同的语言文件都会被替换为新版，
  服主改过的文件也会被替换（维护者于 2026-09-29 书面接受）。旧文件保留在同一个 `lang/` 目录下，名为 `<文件名>.bak`
  （名字已被占用时依次为 `<文件名>.1.bak`、`<文件名>.2.bak`……，绝不覆盖已有文件，也不会被当作语言文件读取），
  每替换一个文件，日志里有一行写明文件和备份。要保留自己的修改，请不要把 `.bak` 改回原名：自 #608 起官方语言文件
  每次启动都会恢复，改回原名的文件下次启动会再次被替换。请把 `.bak` 复制为以语言代码加连字符开头的新名称（保留扩展名，
  例如 `lang/en-myserver.json`），再按下一条的说明在主配置中选择它。
- **Official language files are framework-owned; customise by copy, rename, select (#608,
  maintainer decisions of 2026-10-04).** The framework now writes its official language files,
  `en.json` and `zh.json`, to `plugins/UltiTools/lang/`, and every module keeps writing its own to
  `plugins/UltiTools/pluginConfig/<module>/lang/`. These official files belong to UltiTools:
  - **an official file edited in place is restored.** At every start, an official file whose bytes
    differ from the bundled version is restored to it, the previous file is kept as `<file>.bak`
    (named as in the previous entry), and one WARNING names both and tells you to customise as
    below instead. `/ul reload` does the same for the framework's two files and for each module's
    file of the language in use. A file an earlier release wrote and nobody edited is brought up to
    date without a backup: silently for the framework's two files, with the one INFO line of the previous entry for a
    module's file. A read-only or symbolic-link file is left as it is, with a
    line saying so. A second start changes nothing;
  - **to customise messages**: (1) in the folder whose messages you want to change, copy an official
    file under a new name that starts with its language code and a hyphen, keeping the extension —
    `zh.json` to `zh-myserver.json`; (2) edit the copy; (3) set `language: zh-myserver` in
    `plugins/UltiTools/config.yml` and restart or run `/ul reload`. That one setting selects the
    language for the framework and every module — there is no per-module setting — so make the copy
    in each folder whose messages you want to change;
  - **missing messages**: every message your copy does not contain comes from the official file its
    name starts with (`zh` for `zh-myserver`; the longest shipped code wins, so `zh-CN-myserver`
    uses `zh-CN` when a module ships it). You may delete everything you do not change, and a module
    with no `zh-myserver` file simply uses its official `zh`. A name that starts with no shipped
    code, such as `myserver`, uses English (or, for a module that
    ships no English, its first shipped language) for what it lacks and logs one WARNING. A name with any
    character other than ASCII letters, digits, `_` and `-` is never used as a file name. A value in
    your copy whose `%s`/`%d` placeholder count differs from the official value, which puts a
    different placeholder type at a position the official value also uses (`%d` where it has `%s`,
    or the reverse; positions are counted as `String.format` counts them, so `%2$s … %1$d` against
    `%1$d … %2$s` is the same; `%%` is not a placeholder; #615), or which lacks a `{TOKEN}` the official value
    has (tokens of your own are kept) — typically after an upgrade changed that message, or a typo —
    is replaced by the official value for that key in memory, with one WARNING naming the file and
    the key; your file is not changed, so update the value there;
  - **choose a name no module ships.** A module that does not ship the configured official code
    treats it as a custom name: with `language: en`, a module shipping only `zh` reads a `lang/en.*`
    left in its folder by an older release. And if a later release starts shipping a code you used as
    a custom name, that file becomes an official file and is restored (with a backup) like any other.
    A server-specific suffix such as `zh-myserver` avoids both;
  - **configuration comments follow language switches** (as of 6.3.0, #615). While a custom
    language is selected, a comment the framework writes above a setting comes from your custom
    file's text for it. Such a comment is recognised as the framework's — its text equals your
    custom file's current text for that setting — so after you switch to another language it is
    rewritten in that language, like a comment written from an official file, and switching back
    writes your text again. The framework reads every custom file in the module's `lang/` folder
    for this, and only reads it. **If you later change that text in your custom file, a comment
    written from the earlier text is treated as your own**: it is never rewritten and stays in the
    language it was written in, and no record of earlier texts is kept to recognise it
    (maintainer decision of 2026-10-06). A comment you wrote yourself that happens to equal your
    custom file's text for that same setting is treated as the framework's. The same holds for a
    comment written from a custom file you delete or rename: it then equals no current text and
    stays in the custom wording. To stop using a custom language, switch `language` first (start
    or `/ul reload`) and remove the file afterwards, or delete those comment lines and the framework
    writes them again in the current language. A framework comment line you delete is written again
    in the language selected at that moment, unless a comment line of your own remains directly
    above the setting (see the configuration comments entry of the config layer section). Known
    limitations, documented rather than changed (gate 2 of #636): an unparseable custom `.yml`/`.yaml`
    file left in `lang/` is reported by Bukkit's YAML reader once per start even when it is not the
    selected language - fix or remove it; a custom file replaced by one of exactly the same size with
    its old modification time kept (a copy tool that preserves times) is noticed only at the next
    start; and a module that overrides `supported()` must list every language its jar ships, or a
    shipped file is read as a custom one for this recognition;
  - **your custom file is never written**, replaced, backed up or recorded by any start, reload,
    upgrade or module update;
  - **for module authors**: `UltiToolsPlugin#getLanguageCode()` now returns the official language
    the module's messages are based on — `zh` for `zh-myserver`, and the fallback the module actually
    uses for a name it does not ship — so behaviour that depends on the language (shipped text
    written into configuration files, example content) keeps working. The new
    `UltiToolsPlugin#getConfiguredLanguage()` returns the configured name. Before 6.3.0
    `getLanguageCode()` returned the configured name, which is the same value for every official
    code, so only a server using a custom or unshipped name sees a different value;
  - the framework's official texts are now read as UTF-8 on every platform; before 6.3.0 they were
    decoded with the platform default charset.
  See `ultitools.language.custom-file-framework`, `custom-file-module`, `custom-file-missing-keys`,
  `custom-file-never-written` and `official-file-edit` in `FEATURES.md`.

  中文补充：官方语言文件归 UltiTools 所有。框架现在会把官方语言文件 `en.json`、`zh.json` 写到
  `plugins/UltiTools/lang/`，各模块的官方语言文件仍在 `plugins/UltiTools/pluginConfig/<模块>/lang/`。直接修改官方文件，
  每次启动都会被恢复为自带版本，修改过的文件保留为 `<文件名>.bak`，日志里有一行提示改用自定义文件（`/ul reload` 也会恢复框架的两个文件和各模块当前语言的文件）。
  自定义方法：在要修改的目录里把官方文件复制为以语言代码加连字符开头的新名称（保留扩展名，例如 `zh.json` → `zh-myserver.json`），
  修改这个副本，然后在 `plugins/UltiTools/config.yml` 中设置 `language: zh-myserver` 并重启或执行 `/ul reload`。这一个设置同时作用于框架和所有模块，没有按模块的设置。
  副本里缺少的文本使用名称开头对应的官方文件（`zh-myserver` 对应 `zh`）补充，所以只需保留要改的条目；没有 `zh-myserver` 文件的模块直接使用官方 `zh`。
  名称不以已有语言代码开头（如 `myserver`）时，缺少的文本使用英文（模块不自带英文时使用其自带的第一种语言），并记录一行警告。副本中 `%s`/`%d` 个数与官方文本不同、在官方文本也用到的位置上占位符类型不同（如官方为 `%s` 而副本为 `%d`，或反之；位置按 `String.format` 的规则计算，`%%` 不算占位符；#615）、或缺少官方文本中某个 `{TOKEN}` 的条目（自己添加的占位符会保留）（通常是升级后该消息变了），在内存中改用官方文本并记录一行警告（写明文件和键），文件本身不会被修改。
  配置文件注释会跟随语言切换（6.3.0 起，#615）：选择自定义语言期间，框架在配置项上方写入的注释取自自定义文件中该项的文本；只要注释与自定义文件当前的该项文本完全一致，就被识别为框架写入的注释，切换到其他语言后会改写为该语言，切换回来会再次写成你的文本。框架为此读取模块 `lang/` 目录下的所有自定义文件，只读不写。之后若你在自定义文件里改了这段文本，按旧文本写入的注释视为你自己的内容：永远不会被改写，保持写入时的语言，框架也不保存旧文本的记录（维护者 2026-10-06 决定）。你自己写的、恰好与自定义文件中同一配置项文本一致的注释会被视为框架写入的注释。从被删除或改名的自定义文件写入的注释同理：它不再等于任何当前文本，会保持自定义措辞。要停用自定义语言，请先切换 `language`（启动或 `/ul reload`）再删除文件，或者删掉这些注释行，框架会用当前语言重新写入。被删除的框架注释行会按当时所选的语言写回，除非该设置正上方还留着你自己的注释行（见配置层一节的说明）。已知限制（#636 门禁 2，只写文档）：`lang/` 中无法解析的自定义 `.yml`/`.yaml` 文件即使未被选用，每次启动也会由 Bukkit 的 YAML 读取器报一次错，请修正或删除；被大小完全相同、且保留旧修改时间的文件替换的自定义文件（保留时间的复制工具），要到下次启动才会被注意到；覆写 `supported()` 的模块必须列出 jar 自带的全部语言，否则自带文件会被当作自定义文件参与识别。
  请使用模块不会自带的名称（如 `zh-myserver`）：不自带所配置官方语言的模块会把它当作自定义名称，日后版本若开始自带你用过的名称，该文件会变成官方文件并被恢复。自定义文件在任何启动、重载、升级或模块更新中都不会被写入、替换、备份或登记。
  模块作者注意：`getLanguageCode()` 现在返回自定义名称所基于的官方语言代码（`zh-myserver` 返回 `zh`），新增的 `getConfiguredLanguage()` 返回配置的名称。
- Resolving a module's language only after its resources are extracted (#540). Before 6.3.0 the
  constructors resolved the language first and extracted the bundled resources second. As of 6.3.0 extraction runs
  first in both constructors, so the catalogue in use after start-up is the one the next `/ul reload` resolves from the
  same files - for example after an operator deletes `lang/<code>.json` to get a fresh copy while an older
  `lang/<code>.yml` (read as of 6.3.0, #389) stays beside it (`ultitools.language.boot-resolve-after-extract`). Since #567 the constructors
  extract `config/` and `res/` only; a `lang/` file the disk lacks is read from the jar's copy as if it
  were extracted, and is extracted at the commit step described in the next entry, so the start-up
  catalogue is the same.
- Writing a module's language provenance only after the load gates accept it (#460). The
  refresh of an untouched `lang/` file, its provenance record and the #459 replacement are decided while the module
  is constructed and written only after `PluginManager` has accepted the candidate, so an older copy of a loaded
  module, or a module requiring a newer framework, never rewrites the language files it shares with the accepted
  version. The constructor only computes the decision (and logs nothing about it); `PluginManager` commits it through the
  new `UltiToolsPlugin#commitLanguageProvenance()` right after the gates pass, on both `register`
  entry points. That method is `@ApiStatus.Internal` and public only because `PluginManager` is in
  another package, like `setContext`; a module never needs to call it. A module that registers
  through `PluginManager#register(UltiToolsPlugin)` gets this automatically. As of 6.3.0 (#567) a
  language file the candidate's jar ships and the disk lacks is not extracted during construction
  either: the constructor resolves it from the jar's copy without writing it, and
  `commitLanguageProvenance()` extracts it, with its hash, once the gates accepted the module — so a
  rejected candidate writes no language file at all (`ultitools.language.rejected-candidate-untouched`,
  `ultitools.language.rejected-copy-no-files`). `config/` and `res/` files are still extracted while
  the candidate is constructed, because its configuration is read right after; a rejected candidate
  can still leave one the disk lacked. For module authors: an instance constructed but never
  registered through `PluginManager` (a test, for example) extracts no `lang/` file; call
  `commitLanguageProvenance()` as `PluginManager` does if it needs them on disk.

  中文补充：6.3.0 起（#567）模块在构造时只解压 `config/` 和 `res/`；磁盘上缺少的 `lang/` 文件在构造时直接按 jar 内的副本解析、不写盘，等加载闸门接受该模块后由 `commitLanguageProvenance()` 解压并记录哈希。因此被闸门拒绝的模块副本（已加载更新版本的旧副本、要求更新框架的模块）不会写入任何语言文件；`config/`、`res/` 仍在构造时解压，被拒绝的副本仍可能留下磁盘原本缺少的这类文件。模块作者注意：构造后从未经 `PluginManager` 注册的实例（例如测试）不会解压 `lang/` 文件，需要时请像 `PluginManager` 一样调用 `commitLanguageProvenance()`。
- A refused External Plugin API registration leaves nothing behind (#537). When
  `PluginManager#registerExternal` refuses a plugin after recording its data-folder scope — the
  command-executor contract check, the config-binding refusal of #531, or a failed container
  `refresh()` — it closes the context and removes the scope, the entity ownership and the adapter's
  data scope before rethrowing the same exception, so a corrected connection of the same plugin in the
  same process succeeds (`ultitools.boot.external-refusal-unwind`).
- Naming a missing required plugin instead of printing a class-not-found trace (#554). A module
  that cannot load because a plugin in its `plugin.yml` `depend:` list is not installed or not
  enabled used to log `Cannot initialize plugin for <main class>: <missing class>` with a
  `NoClassDefFoundError` trace. As of 6.3.0 that case logs one WARNING,
  `Module '<name>' requires <plugin>, which is not installed or not enabled; the module is not
  loaded.`, with no trace. Every other load failure keeps the old message and trace
  (`ultitools.boot.missing-required-plugin`). When the required plugin is not installed at all,
  the module is now refused before it is constructed: nothing of it runs, no resource is
  extracted and no class is scanned. One consequence: a module that lists an uninstalled plugin
  under `depend:` but never touched that plugin's classes while loading used to load anyway, and
  is now refused, as Bukkit itself refuses a plugin whose `depend:` is missing. A required
  plugin that is installed but not enabled yet is not refused early, because it may still be
  enabled after UltiTools; that case is refused only if loading fails, as before.
- New in 6.3.0: the first economy request a module makes while Vault is absent or has no provider logs one
  WARNING, `Module '<name>' requested the economy service, but …` (#462, #483, #489). It names a loaded
  module through its declared scan roots and its main class's own package, a connected External Plugin API
  consumer (by plugin name), and a module requesting the economy while it is still being registered (from its
  constructor, a `@PostConstruct` method or `registerSelf()`). A caller none of these covers is reported as
  `an unknown caller`, once per calling package (`ultitools.economy.attribute-caller`). Log output only.
  **Known limitation (#567, maintainer decision of 2026-10-07: documented, not changed).** The main
  class's own package counts as a root together with its sub-packages, like a declared root. A
  module whose main class sits in a broad package shared with other code - for example
  `com.example`, while other plugins or libraries also live under `com.example` - may therefore be
  named as the caller for code in that package that is not its own, and that use spends the
  module's one warning for the session. None of the fifteen first-party modules is affected (each
  has a scan root equal to its main class's package, measured: eleven declare it, and UltiChat,
  UltiEconomy, UltiKits and UltiMenu declare none and default to it). **Module authors: put the main
  class in a package of its own,** such as `com.example.myplugin`, as every first-party module does.

  中文补充：已知限制（#567，维护者 2026-10-07 决定：写入文档，不改代码）。模块主类所在的包连同其子包都算作该模块的归属根，与声明的扫描根相同。主类放在与其他代码共用的宽泛包里（例如 `com.example`，而其他插件或库也在 `com.example` 下）时，该包内并非该模块的代码可能被记到这个模块名下，并用掉该模块本次运行唯一的一次警告。十五个官方模块都不受影响（实测：每个模块的扫描根都等于其主类所在的包，其中十一个显式声明，UltiChat、UltiEconomy、UltiKits、UltiMenu 未声明而默认取主类所在的包）。模块作者请把主类放在自己专用的包里（如 `com.example.myplugin`），官方模块都是这样做的。
- Three internal methods added to published classes for the fixes above, each
  `@ApiStatus.Internal` and public only because the caller is in another package:
  `UltiToolsPlugin#commitLanguageProvenance()` (#460), `PluginManager#getConnectedExternalScanPackages()`
  (#462) and `PluginManager#getModuleBeingRegistered()` (#483). Additions only; no existing
  signature changed. A module never needs to call them.
- Writing no configuration at shutdown (#510, completed by #599). Operator-only disk edits survive a
  clean stop. As of 6.3.0 nothing is written at stop, unload or replacement: a pending module change is named in
  one warning and dropped (see the entry on configuration writes below). The [config-layer contract](#config-layer-630) covers
  protected files, partial panel acknowledgments, semantic no-ops and server-thread confinement.
  Panel callbacks queue their complete operation on the server thread; they do not perform registry
  writes concurrently on the WebSocket thread. An explicit entity save writes only what the module
  changed, where the file still holds what was read, and never replaces an operator edit.

- `PluginInstallUtils.uninstallPlugin(String)` unloading through the framework's one full unload
  path, and reporting the outcome it documents (#503, #501). It used to call
  `plugin.unregisterSelf()` directly, skipping everything `PluginManager#unregister` does first —
  cancelling the module's `@Scheduled` tasks, releasing its `@PlayerCache` beans, its
  tab-completion completers, its EventBus handlers and its conditional-bean records, then closing
  its context — so an "uninstalled" module kept running its repeating tasks until the next restart.
  It also ignored `File#delete()`'s result and stopped at the first matching jar, and returned
  `true` either way. As of 6.3.0 it deletes every jar whose `plugin.yml` `name` matches and
  **throws** where it used to report success: `java.nio.file.FileSystemException` naming every jar
  still on disk when one could not be deleted, `java.nio.file.NoSuchFileException` when a loaded
  module was unloaded but no jar of it was found (which is not a misspelling and must not be
  reported as one), and `IllegalStateException` when the module's own unload threw — the module is
  still removed from the loaded modules and its jars are still deleted in that case, with the jar
  outcome attached as suppressed, because `unregister` has closed its context by then and keeping
  the jar would bring the module back on the next restart. `false` now means only "no jar matched
  and nothing was unloaded either". A caller that checked the boolean alone now sees these as
  exceptions rather than a success it did not get. The method also no longer builds a `jar:file:`
  URL for every entry of the modules folder, so a stray file or a subdirectory there no longer
  fails the uninstall (#504). A loaded module is asked which JAR it came from — its own
  `getProtectionDomain().getCodeSource()`, read before it is unloaded — and that JAR is deleted
  whatever its metadata says now (a file may have been replaced since the start, and a module
  registered from code has no `plugin.yml` the loader read). Every other entry of the modules folder is placed in exactly one of
  three states, each decided by reading the archive — with one exception, an entry the module loader
  itself would never load, judged by the same `.jar` test the start-up scan applies to this
  folder, which is state B without being opened. The states: its `plugin.yml` declares this module,
  or it is a loaded instance's own code-source JAR (deleted); it opened and its `plugin.yml`
  declares another module, or it is a directory (ignored); nothing about it identifies a module,
  because the archive would not open, its `plugin.yml` is not valid YAML, **it carries no
  `plugin.yml` at all or one with no `name:` key** — which say nothing about whether it is a
  module — or the entry is named like a JAR and cannot be resolved at all, a link whose target is
  away (reported as undetermined — the uninstall still succeeds, and the operator is told how many
  entries could not be identified and that one of them, if it is a copy of this module, will load
  it again after a restart). Separately, a modules folder that exists but could not be listed is
  reported as `java.nio.file.AccessDeniedException` naming the folder
  rather than as "no JAR of this module is here", a claim nothing supports when the folder's
  contents are unknown. A second entry point,
  `PluginInstallUtils.uninstallPluginReporting(String)` (`@ApiStatus.Internal`), returns both what
  was deleted and the entries whose identity could not be determined; `uninstallPlugin(String)`
  keeps its signature and returns the first half. When the uninstall leaves by a failure instead —
  a module whose unload threw, a JAR that could not be deleted, or nothing identifiable found —
  those entries travel with it as a suppressed
  `PluginInstallUtils.UndeterminedEntriesException` (`@ApiStatus.Internal`), so no outcome
  discards what another established.
- `/upm update` and `PluginInstallUtils.updatePlugin(String)` no longer replace a module's JAR while
  the server runs (#505, #513). Before 6.3.0 the update downloaded the new JAR into the modules
  folder, deleted the old one with `File#delete()` while ignoring its result, and returned `true` —
  reported as "Update successful" — whether or not the old JAR was gone, so a failed delete left two
  versions of the module to race at the next start. Now the update **takes effect at the next start
  and is committed only after that start shows the module loaded**: the command downloads the new JAR
  into `.ultikits/upm-transactions/` under the server root — beside the credential store and outside
  `plugins/`, where the panel's file interface cannot forge a record — and records it; the
  next start moves the old JAR aside (keeping it) and the new one in, before the module class loader
  is built; after the modules load, the update is kept only if the module is loaded from the new JAR
  at the new version, and otherwise the old JAR is restored and the new one removed, with one log
  line naming both versions. Nothing predicts before the restart whether a JAR will load. A move that
  fails leaves the modules folder as it was and is reported in the start-up log and again by the
  next `/upm update` of that module. The swap is an atomic rename, so when `plugins/` is on a
  different file system from the server root the start refuses it, changes nothing, and names both
  folders in one SEVERE line; there is no copy fallback. `updatePlugin(String)` keeps its signature; its `true` now
  means "staged", and `PluginInstallUtils.stageUpdate(String)` (`@ApiStatus.Internal`) returns what
  was staged or why nothing was. Nothing is staged, and `updatePlugin(String)` returns `false`, when
  another JAR in the modules folder declares the module's `plugin.yml` `main:` and sorts before the
  new JAR's file name: that copy would load instead at the next start, so the update could only be
  rolled back; the reply names it (maintainer decision of 2026-09-30). Likewise when two loaded modules in
  different JARs declare the same identify-string, since the update names a module only by that
  string. The update only ever moves, replaces or deletes a file whose SHA-256 matches its record;
  when another actor has changed one, it does nothing, keeps the record as `NEEDS_OPERATOR` with one
  SEVERE line, and refuses `/upm update` and `/upm uninstall` of that module until the record and its
  folder are deleted. Measured consumers: none of the fifteen module repositories or
  UltiTools-External-Example call either method (their `origin/master`, searched for
  `PluginInstallUtils`, `updatePlugin(` and `uninstallPlugin(`; the only hits are module test and feature
  documents - `UAT-CHECKLIST.md`, and UltiSideBar's `FEATURES.md` - that name the `/upm` commands or the method in
  prose). An uninstall that goes ahead also cancels an update of that module
  still waiting for the next start, and any download of one still running, on every outcome — its
  JARs deleted, recorded for deletion, not deletable, or a modules folder that could not be listed —
  matched on the identity it resolved (the unloaded instances' identify-strings and runtime names,
  the names it was given or their JARs declare, and their JARs), not only on the name typed.
  `PluginInstallUtils.uninstallPlugin(String)` does this too; the command reads the cancelled
  versions through `uninstallPluginReporting(String, List)` (`@ApiStatus.Internal`).
- `/upm` installs only modules built for this UltiTools-API's API level (maintainer
  rulings of 2026-10-11). Every UltiCloud request `/upm` makes now carries `apiLevel` (this
  framework's API level, 630 for 6.3.0) and `minApiLevel=630`, and the catalogue answers `latest`,
  `versions` and the downloads with compatible versions only. `/upm install <plugin>`,
  `/upm install <plugin> <version>` and `/upm update` additionally check what they download: the
  JAR is written as `<id>-<version>.jar.part`, a name the module loader never reads, its
  `plugin.yml` is read again, and a module whose `api-version` is absent, not an integer, below 630
  or above the framework's level is refused with nothing written to the modules folder (an update's
  staging is discarded and nothing is recorded); the reply names the declared level, the framework's
  level and the accepted range. When the catalogue answers that no version is compatible (HTTP 409),
  nothing is downloaded and the reply says so; `/upm versions` with no compatible version prints one
  line naming the range; the update check offers only versions this framework installs and never
  mentions one that needs a newer UltiTools-API. `PluginInstallUtils.installLatestPlugin(String)`,
  `installPlugin(String, String)` and `updatePlugin(String)` keep their signatures and return
  `false` for such a module; `PluginInstallUtils.installReporting(String, String)`
  (`@ApiStatus.Internal`) returns the reason. **Consequence for operators:** a module built for
  UltiTools-API 6.2.x (`api-version: 620`) is refused by `/upm`. Every first-party module built for 6.2.x overrides
  `UltiToolsPlugin.unregisterSelf()`, which 6.3.0 made final, so those builds could not load anyway. A JAR an
  operator places in the modules folder by hand is still judged by the module loader's unchanged
  rule. `/upm list` still lists every catalogue module; Install on one with no compatible version
  replies with the reason.

  中文补充：自 6.3.0 起，`/upm` 只安装为本 UltiTools-API 的 API 级别构建的模块（维护者
  2026-10-11 的裁定）。`/upm` 发往 UltiCloud 的每个请求都带上 `apiLevel`（本框架的 API 级别，6.3.0 为
  630）和 `minApiLevel=630`，云端的 `latest`、`versions` 和下载只返回兼容的版本。`/upm install`、
  `/upm install <插件> <版本>` 和 `/upm update` 还会检查下载到的文件：先写成模块加载器永远不读的
  `<id>-<version>.jar.part`，重新读取其中的 `plugin.yml`，`api-version` 缺失、不是整数、低于 630
  或高于框架级别的模块一律拒绝，模块目录不写入任何东西（更新的暂存会被丢弃，不写记录）；回复写明模块声明的级别、
  框架的级别和接受的范围。云端回答没有兼容版本（HTTP 409）时不下载任何东西，回复说明原因；
  `/upm versions` 没有兼容版本时打印一行说明范围；更新检查只提示本框架能安装的版本，从不提示需要更新框架的版本。
  `PluginInstallUtils.installLatestPlugin(String)`、`installPlugin(String, String)` 和
  `updatePlugin(String)` 签名不变，对这类模块返回 `false`；`PluginInstallUtils.installReporting(String, String)`
  （`@ApiStatus.Internal`）返回原因。**对服主的影响：**为 UltiTools-API 6.2.x 构建的模块（`api-version: 620`）
  会被 `/upm` 拒绝。官方模块的 6.2.x 构建都覆盖了 6.3.0 改为 final 的 `UltiToolsPlugin.unregisterSelf()`，本来也无法加载。
  服主手动放进模块目录的 JAR 仍按模块加载器原有的规则判断。`/upm list` 仍列出云端的全部模块；对没有兼容版本的模块点“安装”会回复原因。
- `PluginInstallUtils.uninstallPlugin(String)` also deletes a JAR whose `plugin.yml` `main:` names
  the loaded module's main class, whatever `name:` it declares (#516). The module loader identifies
  a module JAR by that entry alone (since #548), and its start-up scan now records, per main class,
  every JAR that declares it; before 6.3.0 a second copy of a module whose `plugin.yml` declared a
  different `name:` survived the uninstall and loaded the module again at the next start. A main
  class another loaded module also has is never matched, a file is judged by what it declares at the
  time of the uninstall, and no class is read out of any archive.
- Module JARs are discovered in file-name order (#476). Before 6.3.0 the start-up scan and the module
  class loader took the modules folder in `File#listFiles()` order, which Java does not define (on
  ext4 it is hash order). The order decides which of several JARs carrying the same class supplies it,
  which copy of a duplicated module is read first, which module a `plugin.yml` `name:` shared by two
  modules resolves to, and the order of the opt-in legacy load (`-Dultitools.useLegacyPluginLoading`).
  Modules without a dependency between them already loaded in alphabetical order of their class names
  and still do. An install that relied on one copy winning by listing order may see the other one win
  after upgrading, once, and then the same one on every start and file system.
- Two or more JARs in the modules folder declaring the same `plugin.yml` `main:` class are reported by
  one start-up WARNING naming every one of them and the JAR the classes load from (UltiTools-Dev-Doc#96).
  The copies are not loaded side by side: the classes come from the first such JAR in file-name order. A JAR
  that borrows a class from a JAR that does not declare it is refused with one SEVERE line.
- `PluginInstallUtils.uninstallPlugin(String)` no longer leaves a JAR it cannot delete for the
  operator to delete by hand (#518). On Windows the shared module class loader keeps every module
  JAR open while the server runs, so that instruction could not be followed. The uninstall now
  records such a JAR and the next start deletes it before any module loads, if it is still the
  recorded file (same SHA-256); the failure it raises is
  `PluginInstallUtils.RemovalDeferredException` (`@ApiStatus.Internal`), a
  `java.nio.file.FileSystemException` that names every recorded file. Only when the record cannot
  be written is a plain `FileSystemException` thrown instead. It names only the module JARs still on
  disk, and the record failure travels with it as a suppressed
  `PluginInstallUtils.RemovalNotRecordedException` (`@ApiStatus.Internal`), an `IOException` that is
  not a `FileSystemException` and names no file to delete (#676). The `/upm uninstall` reply then
  lists only those JARs to delete by hand and says on a line of its own that the deletion was not
  recorded, so the next start will not delete them. At the start that applies a record, a recorded
  JAR whose content cannot be read to confirm that it is still the recorded file is neither deleted
  nor dropped: it stays in the modules folder, keeps its entry in the record unchanged for the next
  start, and is reported as one SEVERE line with the read error (#676).
- The modules folder is computed in one place, `<plugin data folder>/plugins` (#517). Before 6.3.0
  the start-up scan read `System.getProperty("user.dir")` + `/plugins/UltiTools/plugins` while the
  module class loader, install, update and uninstall read the data folder; on a server whose JVM was
  started from another working directory the scan found JARs the class loader did not hold, and
  `/upm` acted on a folder the scan did not read. The data folder follows Bukkit's own plugin
  directory, so a server started from its root is unaffected.

- The JSON storage backend no longer hands out the entities it caches (#522). Before 6.3.0,
  `SimpleJsonDataOperator`'s read paths (`getById`, `getAll`, `page`, `getLike`, and every
  `query()` terminal built on them) returned the very instances it kept in memory, and `insert`
  cached the instance it was given, so on `datasource.type: json` changing a loaded (or just
  inserted) entity **without** calling `update(...)` changed the store and was written to disk at
  the next flush. On SQLite and MySQL the same code never persisted anything, because every read
  materialises the row afresh. As of 6.3.0 every JSON read returns a detached copy produced by the
  same Gson form the store writes to disk, and `insert` caches a copy: a change reaches the store
  only through `update(...)` (or `update(column, value, id)`), on every backend alike. `update(T)`
  also fires `onUpdate()` on the entity passed in, before its fields are copied into the store,
  exactly as the relational backends do — so an `AuditableDataEntity`'s `updatedAt`/`updatedBy`
  now show on the caller's instance on the JSON backend too, and `exist(entity)` looks the entry
  up by the entity's id, as the relational backends do, instead of comparing it with the cached
  copy through `equals()`. A module that relied on the old
  aliasing — changing a loaded entity and counting on the next flush to save it — must now call
  `update(...)`; no module in this monorepo was found doing so (see the pull request's consumer
  impact list). The cost is one Gson round trip per entity returned, the same materialisation the
  relational backends already pay (see `ultitools.storage.detached-reads` in `FEATURES.md`).
- `Query#delete()` returns the number of rows actually removed, as its javadoc always said (#521).
  It used to return the number of rows the query *matched*, and it skipped a matched row whose id
  was `null` while still counting it, so a caller reading the `int` as "rows removed" could be told
  a delete succeeded when it removed nothing. As of 6.3.0 the count comes from the backend's own
  affected-row count (a row another writer removed between the read and the delete is not
  counted), and a matched row with a `null` id is refused with a `DataAccessException` naming the
  entity type **before** any row is deleted, since no delete can address it. This corrects
  behaviour that contradicted the documentation, so it takes no migration period. A third-party
  `DataOperator` implementation, which cannot report what its `delById` removed, is counted by
  checking that the row existed immediately before that call and is gone after it (see `ultitools.storage.query-delete-count` in `FEATURES.md`).
- Rows left without an id by UltiTools-API 6.2.0 are repaired, and addressing a row by a null id
  is refused (#546, maintainer decision of 2026-09-27). 6.2.0 did not assign an id in `insert`, and
  SQLite's generated DDL accepted a `NULL` primary key, so every row a module inserted without an
  id on that release was stored with none; such a row could be read, but every `update`/`delete` of
  it bound `WHERE id = NULL`, matched nothing and returned normally, so a change the module
  reported as saved was lost at the next restart. As of 6.3.0, when a SQLite-backed table is
  initialised every row whose `id` is `NULL` is given the id its entity reports through `getId()`,
  or a new UUID when the entity reports none, in either case only if the entity read back with that
  id reports it; a row that no written id would make addressable is left as it is and counted, by
  reason, in one WARNING line per table: a derived id that more than one row without an id reports
  (none of those rows is written — maintainer decision of 2026-09-29, the rule UltiEssentials' own
  repair applies), a derived id another row already holds, or a derived id that is `null` or a row
  that cannot be read as the entity — only the `id`
  column is written, all rows in one transaction, so the repair writes user data at startup, which
  is what the maintainer decided — and one INFO line names the table, the count and how many rows
  took the entity's own id. A second start writes nothing and logs no INFO line; rows that were left without an id
  are counted again, in the same WARNING, at every start. The reported id comes
  first because an entity may derive `getId()` from another column (UltiEssentials'
  `UuidKeyedDataEntity` and UltiKits' `KitClaimData` derive it from a `uuid` column) and every
  lookup binds that value, so a random id would leave such a row exactly as unreachable as `NULL`
  did. For the same reason every write path (`insert`, `insertAll`, `update(T)`, `updateAll`,
  `updateIf`) now stores `getId()` in the `id` column rather than the inherited field: an entity
  that overrides `getId()` never sets that field, so on 6.2.x (and earlier 6.3.0 snapshots) it still
  inserted a `NULL` id on SQLite, and on MySQL its insert failed outright. MySQL never
  accepted a `NULL` id and runs no backfill. Independently, `update(T)`, `update(column, value, id)`,
  `delById` and `updateAll` addressed by a `null` id now throw `DataAccessException` on every
  backend instead of silently matching nothing (the JSON backend used to throw a raw
  `NullPointerException`); `updateAll` checks every entity before it writes any. A call with a
  non-null id that matches no row is unchanged. See `ultitools.storage.null-id-backfill` and
  `ultitools.storage.null-id-refused` in `FEATURES.md`.
- `DataOperator` gains one method, `boolean updateIf(T entity, WhereCondition... expected)` (#543):
  a conditional write that applies only while the stored row still matches every expected
  condition, and reports whether it applied, on the JSON, SQLite and MySQL backends. No existing
  method's signature changes, and it is a `default` method, so a module compiled against 6.2.x
  still links. Its default body throws `UnsupportedOperationException` naming the implementing
  class rather than quietly performing an unconditional write — a third-party `DataOperator`
  implementation keeps working for every other method and must implement `updateIf` before a caller
  can rely on it. The framework's own operators implement it (see
  `ultitools.storage.conditional-update` in `FEATURES.md`). **A `null` expected value means `IS NULL`**
  (#640, maintainer rule of 2026-10-06): a condition `WhereCondition.builder().column(c).value(null)`
  with the default `EQUAL` comparison holds only while the stored column is unset — on SQLite and
  MySQL `<c> IS NULL` inside the same single `UPDATE`, on JSON an entry whose field is absent or JSON
  null — so a compare-and-set against a column that was unset when it was read misses, and keeps the
  other writer's value, when another server filled the column in between. A `null` value under any
  other comparison, a column the entity does not map (also with a `null` value), a `null` id and a
  `null` condition are still refused with `DataAccessException`. The meaning belongs to `updateIf`
  alone: `getAll`, `exist`, `del` and `page` treat a `null` condition value as before. 6.3.0
  snapshots published earlier in this cycle refused a `null` expected value; no release ever had
  `updateIf` (`git tag --contains` on the commit that added it is empty), so only a module compiled
  against such a snapshot that relied on the refusal must change.

  中文补充：`updateIf` 的期望值为 `null` 时表示 `IS NULL`（#640，维护者 2026-10-06 的规则）：默认 `EQUAL` 比较下，
  `WhereCondition.builder().column(c).value(null)` 只在库中该列仍未设置时成立——SQLite 与 MySQL 在同一条 `UPDATE` 中使用
  `<c> IS NULL`，JSON 要求该条目的字段不存在或为 JSON null。因此，对读取时尚未设置的列做比较后写入时，若另一台服务器在
  读取与写入之间写入了该列，本次写入不生效，另一方的值得以保留。其他比较下的 `null`、实体未映射的列（值为 `null` 时也一样）、
  `null` id 和 `null` 条件仍抛 `DataAccessException`。这一含义只属于 `updateIf`：`getAll`、`exist`、`del`、`page` 对 `null`
  条件值的处理不变。本周期较早发布的 6.3.0 快照会拒绝 `null` 期望值；没有任何正式版本包含 `updateIf`，因此只有针对这类快照
  编译、并依赖该拒绝行为的模块需要修改。
- An update by a non-null id that matches no row writes nothing and says so (#558, maintainer
  decision of 2026-09-29). `update(T)`, `update(column, value, id)` and `updateAll` now log one
  WARNING naming the table and the id each time, on JSON, SQLite and MySQL, and return normally —
  as SQLite and MySQL already did, silently; the JSON backend used to throw a raw
  `NullPointerException`, which a module catching `RuntimeException` or `Exception` around the call
  saw as a failed write. The caller learns the outcome through a new method,
  `int updateCounted(T entity)` on `DataOperator`: `1` for a written row, `0` when no row has the id.
  It is a `default` method, so no existing signature changes and a module compiled against 6.2.x
  still links; a third-party implementation that does not override it is counted by whether the row
  exists before its `update` (the one remaining miscount: a delete by another writer during that
  call). See `ultitools.storage.missing-row-update` in `FEATURES.md`.
- A transaction whose own failure path fails no longer ends in an implicit commit (#634). JDBC
  commits an open transaction when auto-commit is switched on, and the framework's transaction
  manager switched it on after every transaction — also after a rollback that had failed, after a
  commit that had failed, and after the failed rollback an outer commit performs for a transaction
  a nested scope marked rollback-only — so rows the caller was told were not written could be
  stored. As of 6.3.0 auto-commit is restored only after a commit or rollback that succeeded;
  otherwise the connection is discarded (a HikariCP pool evicts it, so it is never handed out
  again with the transaction still open; any other connection is closed), and the framework issues
  no further commit for that transaction and never retries one. What is stored afterwards depends
  on where the failure happened. When the action fails and the rollback succeeds, nothing is
  stored. When a SQLite or MySQL rollback fails, including the real rollback an outer commit
  performs for a rollback-only transaction, the connection is discarded and the framework issues no
  commit. When the commit throws `UnexpectedRollbackException` because the transaction was marked
  rollback-only, the transaction was rolled back and none of its writes is stored, on SQLite, MySQL
  and JSON alike. When a SQLite or MySQL commit itself fails (`DataAccessException`,
  `TRANSACTION_FAILED`), the outcome is unknown: the database may have applied the commit before
  its acknowledgement was lost (for example, a dropped MySQL connection), discarding the connection
  cannot undo that, and the framework does not retry the commit. Code that must not apply a write
  twice (payments, rewards) therefore re-reads the data, or uses a conditional write such as
  `DataOperator#updateIf`, before trying again. On JSON, a commit that fails commits nothing (see
  [JSON transactions](#json-transactions-630--staged-atomic-and-durable)). The caller gets the
  original exception, or `UnexpectedRollbackException` when the transaction was marked
  rollback-only. The startup id backfill
  of SQLite tables follows the same rule. `DataOperator#transaction(...)` also rolls back on an
  `Error` (it caught `Exception` only, so an `Error` skipped both the commit and the rollback and
  left the thread's transaction open, and the next transaction on that thread nested into it): the
  relational operators roll back and rethrow the same `Error`, the JSON engine discards the
  transaction's staged writes and rethrows the same instance, and a rollback that itself throws is
  attached to the original as suppressed instead of replacing it. `@Transactional` already rolled back
  on an `Error` and is unchanged, as are nesting, rollback-only marking, propagation, isolation and
  timeout; that statement concerns this fix alone, and the later 6.3.0 changes to a failing joined
  method and a failing nested scope are in
  [A failing joined `@Transactional` method rolls back the whole transaction](#a-failing-joined-transactional-method-rolls-back-the-whole-transaction-630--changed-without-a-migration-period)
  and
  [A failed nested `DataOperator#transaction(...)` marks the whole transaction rollback-only](#a-failed-nested-dataoperatortransaction-marks-the-whole-transaction-rollback-only-630).
  No signature changes. See `ultitools.storage.transaction-failure-paths` in `FEATURES.md`.

  中文补充：事务自身的失败路径再失败时，不再隐式提交（#634）。JDBC 在打开自动提交时会提交尚未结束的事务，而框架的事务管理器
  在每个事务结束后都会打开自动提交——包括回滚失败、提交失败，以及外层提交为被嵌套作用域标记为仅回滚的事务执行真正回滚却失败之后——
  因此调用方被告知“未写入”的行可能已被保存。自 6.3.0 起，只有提交或回滚成功后才恢复自动提交；否则丢弃该连接（HikariCP 连接池
  会将其剔除，不会把仍处于事务中的连接再交给别人；其他连接直接关闭），框架不会再为该事务发出提交，也不会重试提交。之后保存了什么，
  取决于失败发生在哪里：操作失败且回滚成功时，未保存任何写入；SQLite 或 MySQL 的回滚失败时（包括外层提交为仅回滚的事务执行的
  真正回滚），连接被丢弃，框架不发出提交；提交因事务已被标记为仅回滚而抛出 `UnexpectedRollbackException` 时，事务已回滚，
  其写入均未保存，SQLite、MySQL 和 JSON 都是如此；SQLite 或 MySQL 的提交本身失败（`DataAccessException`，
  `TRANSACTION_FAILED`）时，结果未知：数据库可能在确认丢失之前已经应用了该提交（例如 MySQL 连接断开），丢弃连接无法撤销它，
  框架也不会重试提交。因此不能重复写入的代码（支付、奖励）在重试前应先重新读取数据，或使用条件写入（如
  `DataOperator#updateIf`）。在 JSON 上，失败的提交不会提交任何内容（见
  [JSON transactions](#json-transactions-630--staged-atomic-and-durable)）。调用方收到原来的异常；事务被标记为仅回滚时收到
  `UnexpectedRollbackException`。SQLite 表启动时的 id 补全遵循同一规则。`DataOperator#transaction(...)` 遇到 `Error` 时也会回滚（此前只捕获 `Exception`，`Error`
  会跳过提交和回滚，使线程上的事务保持打开，该线程的下一个事务会嵌套进去）：关系型实现回滚后原样抛出同一个 `Error`，JSON 引擎
  丢弃该事务暂存的写入后原样抛出同一个实例；回滚本身抛出的异常作为 suppressed 附在原异常上，而不是替换它。`@Transactional` 本就会在
  `Error` 时回滚，保持不变；嵌套、仅回滚标记、传播、隔离级别和超时均不变（这句话只针对本次修复；6.3.0 随后对失败的加入方方法和失败的
  嵌套作用域所做的改动见本文后面两个小节）。没有签名变化。见 `FEATURES.md` 中的
  `ultitools.storage.transaction-failure-paths`。

- `WhereCondition` comparisons other than `EQUAL` now take effect on SQLite and MySQL (#675).
  6.2.5's SQLite and MySQL operators ignored the comparison and matched every condition by
  equality; JSON already applied it. As of 6.3.0, `getAll`, `exist`, `page`, `del` and `updateIf`
  apply it on every backend with the meanings below, and no table is changed. `INCLUDE`,
  `STARTSWITH` and `ENDSWITH` match the value literally, as text, on SQLite, MySQL and JSON alike:
  `%`, `_`, `!` and `\` are ordinary characters, not wildcards or escapes. Letter case and accents
  follow the backend: SQLite ignores the case of ASCII letters only; MySQL compares under the
  `utf8mb3_general_ci` collation of the tables the framework creates (`DEFAULT CHARSET=utf8`), which
  ignores case and accents, so `e` matches `é`; JSON compares exactly.
  `GREATER` and `LESS` on a `byte`, `short`, `int`, `long`, `float` or `double` field (or its
  wrapper) compare numbers of that type. Pass the value as a number of that type (`10`, not
  `"10"`): a text value is not converted, and MySQL compares it with a default text column as text,
  so `"10"` sorts below `"2"`. On SQLite the typed comparison holds also in a column left on the
  default `VARCHAR(255)` type, with 64-bit integer precision, and a stored value is compared the way
  it reads back: a `float` such as `0.1f`, which SQLite keeps there as the 15 significant digits
  `0.100000001490116`, is neither greater nor less than `0.1f`. That read-back comparison applies to a value
  that is a finite number; an infinite value, a number beyond the range of a `double` and any text are
  compared as they are bound, so `GREATER Float.POSITIVE_INFINITY` matches no row, and a NaN value
  matches no row. JSON does not support an infinite or NaN value: Gson cannot write it as JSON, so a
  condition holding one throws `IllegalArgumentException` when it is evaluated, under any comparison.
  On MySQL such a default text column is
  compared the way MySQL compares text with a number, in double precision, so whole numbers beyond
  ±9,007,199,254,740,992 (2^53) compare approximately there; declare the column with a numeric
  `type` (for example `@Column(value = "stamp", type = "BIGINT")`) where exact results matter. On
  JSON the values are compared as `double`, which is also approximate beyond
  ±9,007,199,254,740,992. A field of any other type is compared as the database compares the stored
  text. Earlier 6.3.0 snapshots treated `%` and `_` as wildcards and compared SQLite text columns as
  text; no release did. `EQUAL`, `getLike` and `Query#like` are unchanged. `exist(...)`,
  `exist(entity)` and an `eq`-only `Query#exists()` threw `ClassCastException` on SQLite from 6.2.x
  on, because sqlite-jdbc reports `COUNT(*)` as an `Integer`; they now answer normally. Measured on
  the fifteen modules' `master` and UltiTools-External-Example (2026-10-19), no `src/main` code uses
  `INCLUDE`, `STARTSWITH`, `ENDSWITH`, `GREATER`, `LESS`, the `comparison(...)` builder method,
  `getLike` or `LikeType` (13 files there use `WhereCondition`, all with the default `EQUAL`), so no
  shipped module changes behaviour. Known limitations, documented rather than changed: on SQLite a
  `double` that needs 16 or 17 significant digits (`1/3.0`) is stored in a default column with 15
  (`0.333333333333333`) and reads back and compares as that value, so `LESS 1/3.0` selects a row
  written as `1/3.0`, where JSON, which keeps every digit, does not; declare the column
  `type = "DOUBLE"` to keep every digit. On SQLite a number that agrees with a stored `float`
  to 15 significant digits but differs after them (for example `Math.nextDown((double) 0.1f)`, a whole
  number of 10^15 or more such as `(1L << 53) + 1`, or a decimal with 16 or more significant digits)
  compares as equal to it, so `GREATER` that value does not select the row holding `0.1f`; this holds in a default
  column and also in a column declared `REAL`, which earlier 6.3.0 snapshots compared exactly. Pass a
  `float` value for a `float` field. On SQLite a NaN comparison value is bound as NULL, so a condition
  holding it matches no row and an `updateIf` with it never applies; do not retry `updateIf` on a NaN
  threshold. A numeric field's column that holds non-numeric text,
  written by hand or by another program, compares on SQLite as its leading number (`'abc'` as 0,
  `'12abc'` as 12), while JSON compares that row as text. See `ultitools.storage.where-comparisons`
  in `FEATURES.md`.

  中文补充：`EQUAL` 以外的 `WhereCondition` 比较方式现在在 SQLite 和 MySQL 上生效（#675）。6.2.5 的 SQLite 与 MySQL
  实现忽略比较方式，所有条件都按相等匹配；JSON 本来就会应用它。自 6.3.0 起，`getAll`、`exist`、`page`、`del` 和
  `updateIf` 在所有后端按以下含义应用比较方式，且不改动任何表。`INCLUDE`、`STARTSWITH` 和 `ENDSWITH` 把值作为文本字面匹配，
  SQLite、MySQL 和 JSON 一致：`%`、`_`、`!` 和 `\` 都是普通字符，不是通配符或转义符。字母大小写和重音取决于后端：
  SQLite 只忽略 ASCII 字母的大小写；MySQL 按框架建表时使用的 `utf8mb3_general_ci` 排序规则（`DEFAULT CHARSET=utf8`）比较，
  它同时忽略大小写和重音，因此 `e` 能匹配 `é`；JSON 精确比较。`GREATER` 和 `LESS` 用于 `byte`、`short`、`int`、`long`、
  `float` 或 `double` 字段（或其包装类型）时，按该类型的数值比较。请以该类型的数字传值（`10`，而不是 `"10"`）：文本值不会被转换，
  MySQL 会把它与默认文本列按文本比较，于是 `"10"` 排在 `"2"` 之前。在 SQLite 上，列保持默认 `VARCHAR(255)` 类型时同样按类型比较，
  整数保持 64 位精度，所存的值按读回时的值比较：`0.1f` 这样的 `float` 在该列中保存为 15 位有效数字 `0.100000001490116`，
  它既不大于也不小于 `0.1f`。这种按读回值的比较只适用于有限数字的值；无穷大、超出 `double` 范围的数字以及任何文本都按绑定时的值比较，
  因此 `GREATER Float.POSITIVE_INFINITY` 不匹配任何行，NaN 值也不匹配任何行。JSON 不支持无穷大或 NaN 值：Gson 无法把它写成 JSON，
  因此含有这种值的条件在求值时会抛出 `IllegalArgumentException`，任何比较方式都如此。在 MySQL 上，这种默认文本列按 MySQL 比较文本与数字的方式以双精度比较，超出
  ±9,007,199,254,740,992（2^53）的整数在那里只是近似比较；需要精确结果时，请为该列声明数值 `type`（例如
  `@Column(value = "stamp", type = "BIGINT")`）。在 JSON 上按 `double` 比较，超出 ±9,007,199,254,740,992 时同样是近似的。
  其他类型的字段按数据库比较所存文本的方式比较。本周期较早的 6.3.0 快照把 `%` 和 `_` 当作通配符，并在 SQLite
  上把文本列按文本比较；没有任何正式版本如此。`EQUAL`、`getLike` 和 `Query#like` 不变。`exist(...)`、`exist(entity)`
  以及只含 `eq` 条件的 `Query#exists()` 自 6.2.x 起在 SQLite 上会抛出 `ClassCastException`（sqlite-jdbc 把
  `COUNT(*)` 报告为 `Integer`），现在能正常返回结果。在十五个模块的 `master` 与 UltiTools-External-Example 上测得
  （2026-10-19）：`src/main` 中没有代码使用 `INCLUDE`、`STARTSWITH`、`ENDSWITH`、`GREATER`、`LESS`、`comparison(...)`
  构建方法、`getLike` 或 `LikeType`（其中 13 个文件使用 `WhereCondition`，都用默认的 `EQUAL`），因此没有已发布模块的行为发生变化。
  已知限制（只写文档，不改代码）：在 SQLite 上，需要 16 或 17 位有效数字的 `double`（如 `1/3.0`）在默认列中只保存 15 位
  （`0.333333333333333`），读回和比较都按这个值，因此 `LESS 1/3.0` 会选中写入时为 `1/3.0` 的行，而保留全部数字的 JSON 不会；
  要保留全部数字，请把该列声明为 `type = "DOUBLE"`。在 SQLite 上，与所存 `float` 前 15 位有效数字相同、之后才不同的任何数值
  （例如 `Math.nextDown((double) 0.1f)`、10^15 及以上的整数如 `(1L << 53) + 1`，或有 16 位及以上有效数字的小数）会被视为与它相等，因此 `GREATER` 该值不会选中保存 `0.1f` 的行；默认列如此，声明为 `REAL`
  的列也如此，而本周期较早的 6.3.0 快照在 `REAL` 列上是精确比较的。对 `float` 字段请传 `float` 值。在 SQLite 上，NaN 比较值会被绑定为 NULL，因此含它的条件不匹配任何行，带它的 `updateIf` 也永远不会生效；请勿对 NaN 阈值重试 `updateIf`。数值字段的列中若有手工或其他程序写入的非数字文本，在 SQLite 上按其开头的数字比较
  （`'abc'` 为 0，`'12abc'` 为 12），JSON 则对该行按文本比较。见 `FEATURES.md` 中的 `ultitools.storage.where-comparisons`。

- `PluginManager#getPluginList()` returning an unmodifiable snapshot, and
  `PluginManager#unregister(UltiToolsPlugin)` delisting the module (#507). `getPluginList()` used to return the manager's live internal `ArrayList`, which callers had
  to mutate to delist a module `unregister` had unloaded, and which a reader on another thread (the
  asynchronous `/upm list`, the economy facade's module attribution) could see fail with a
  `ConcurrentModificationException` or a trailing `null` while the main thread unloaded a module.
  Mutating that list was never a documented contract; it was the mechanism of the defect. As of
  6.3.0 the method keeps its signature and return type and returns a copy taken at the moment of the
  call: it does not change afterwards, and `add`, `remove` and `clear` throw
  `UnsupportedOperationException`. Code that only reads or iterates it is unaffected — which, measured
  against the fifteen modules' `master` and UltiTools-External-Example, is every caller (two modules
  iterate it; none mutates it). `unregister` itself now removes the module from the loaded modules,
  by identity and whether or not the module's unload hook threw. `unregister` also releases the module's
  configuration entities (`ConfigManager#unregisterAll`) after its container is closed; it writes nothing - as of
  6.3.0 nothing writes configuration at stop, unload or replacement (a never-saved change is named once and dropped).
- Registrations released per module instance, and a superseded copy of a module unloaded through the
  full unload path (#506, #528). This path is reached only when code registers a newer instance of a
  loaded module through `PluginManager#register(...)`; two jars of one module in the modules folder
  never supersede each other. The older copy used to get only `unregisterSelf()`: its `@Scheduled`
  tasks kept running, its container stayed open and it stayed listed next to its replacement, and a
  failure thrown by its unload hook was reported as the **incoming** version failing to load and
  aborted that version's activation. As of 6.3.0 the older copy goes through `unregister` — tasks
  cancelled, container closed, registrations released, delisted — and a failure of its unload hook
  is logged against the older copy's name and version while the incoming copy goes on loading. As in
  `PluginManager#close()`, every `Exception` or `Error` the older copy throws counts as its own
  failure. Because both copies share a module name,
  `TabCompletionManager`, `EventBus` and `PanelResponderRegistry` can now also record the registering
  module instance, and `unregister` releases by instance first. The framework records it for
  everything a module registers in the three registries while it loads — during its container
  refresh, where `@PostConstruct` runs, and during `registerSelf()` — through the ordinary name-only
  methods, and for every `@ModuleEventHandler` method. A registration made later is recorded against
  the copy listed under the name it is filed under (#562 item 1, the entry after the next). A
  registration filed under the module's name only is released by name (6.2.5 released only EventBus handlers, by name), except while
  another loaded copy shares that name, when it stays until the last copy of the name is unloaded. One visible consequence: a completer a module
  registers while it loads, including in `registerSelf()`, is now released when the module unloads; 6.2.5 released no
  completer at unload. Added, all `@since 6.3.0`:
  `TabCompletionManager#beginRegistrationScope(String, UltiToolsPlugin)` and
  `#unregisterByOwnerInstance(UltiToolsPlugin)`; `EventBus#register(...)` and `EventBus#subscribe(...)`
  with an owner-instance parameter, `#beginRegistrationScope(UltiToolsPlugin)`,
  `#endRegistrationScope()` and `#unregisterByOwnerInstance(UltiToolsPlugin)`;
  `PanelResponderRegistry#registerResponder(...)` with an owner-instance parameter,
  `#beginRegistrationScope(UltiToolsPlugin)`, `#endRegistrationScope()` and
  `#unregisterByOwnerInstance(UltiToolsPlugin)`; two `HandlerEntry` constructors and a getter for
  the owner instance. Every name-keyed method keeps its signature and behaviour.
- A newer copy of a module takes over its older copy's registrations; a failed newer copy gives them
  back (#562 item 2). Before, the newer copy's `registerSelf()` ran while the older copy still held
  every registration, so (in earlier 6.3.0 snapshots - panel responders are new in 6.3.0) a newer copy that
  registered a panel responder type its older copy held was
  refused with `Cannot register a panel responder for '<type>': already owned by module '<name>'.`
  and did not load; and a newer copy that failed after registering a completer or a subscription left
  them registered, answering from its closed container (a completer key it registered had also
  replaced the older copy's). As of 6.3.0 the order is: the compatibility gates accept the newer copy;
  every panel responder, EventBus handler and tab completer recorded against the older copy is
  released; the newer copy's container refreshes and its `registerSelf()` runs; then either the older
  copy is unloaded (success) or everything recorded against the failed newer copy is released and the
  older copy's registrations are put back exactly as they were (a released EventBus handler at its
  place in its list, so a `Subscription` the older copy holds still removes it). The older copy is
  never unloaded before the newer copy succeeds, so a failed replacement leaves it whole. Any failed
  load now also releases what the failing copy registered in the three registries. A type or key
  another registration claimed while the older copy was released keeps that registration, and one
  WARNING names it. Same reach as the entry above: only code calling `PluginManager#register(...)`
  with a newer instance of a loaded module; no operator command reaches it. Added, `@since 6.3.0` and
  `@ApiStatus.Internal` (for `PluginManager`, not for module authors):
  `TabCompletionManager#releaseForSupersede(UltiToolsPlugin)`,
  `EventBus#releaseForSupersede(UltiToolsPlugin)` and
  `PanelResponderRegistry#releaseForSupersede(UltiToolsPlugin)`, each returning the action that
  restores what it released. Known limitations, documented rather than defended because only code
  reaches this path: a responder or subscription registered by name before the registering copy's
  load scope opens -- in a module's constructor, or in `@PostConstruct` code that runs when the
  framework registers the module instance as a bean (the main class's own, and that of any bean it
  injects), before the container refresh the scope covers -- belongs to the copy listed under that name at the time: no copy at
  the module's first load, so it is not handed over and a newer copy registering the same type is
  still refused; or, while a newer copy is constructed and assembled, the older copy, so it is
  released with the older copy if the newer one loads and restored to the older copy, still running
  the newer copy's code, if the newer one fails. Register in `registerSelf()`, which always runs
  inside the scope, or pass the instance. A subscription the older
  copy removes with `Subscription#unsubscribe()` from another thread while its handlers are released
  comes back if the newer copy then fails. When one newer copy supersedes two loaded copies at once
  (two copies of one version can both be listed) and then fails, both copies get their handlers
  back, but handlers of the same priority may come back in a different order.

  中文补充：模块的新副本接管旧副本的登记；新副本加载失败时把登记还给旧副本（#562 第 2 项）。此前新副本的
  `registerSelf()` 在旧副本仍占有全部登记时运行，所以（在本周期较早的 6.3.0 快照中——面板响应器是 6.3.0 新增的）新副本若登记旧副本已占有的面板响应器类型，会被拒绝
  （`already owned by module`）而无法加载；加载失败的新副本登记过的补全器和事件订阅也会留下，继续用已关闭的容器应答。
  6.3.0 起顺序为：新副本通过兼容性检查后，先释放记在旧副本实例名下的全部面板响应器、EventBus 处理器和补全器，
  再刷新新副本的容器、运行它的 `registerSelf()`；成功则照旧卸载旧副本，失败则先释放失败副本登记的全部内容，
  再把旧副本的登记原样放回（EventBus 处理器回到原来的位置），旧副本照常运行。任何加载失败现在都会释放失败副本在这三个登记表中的登记。
  释放期间被其它登记占用的类型或键归该登记所有，并记一条 WARNING。只有以新实例调用 `PluginManager#register(...)`
  的代码会走到这条路径，服主命令走不到。已知限制（只记录、不做防御，因为只有代码会走到这条路径）：在登记方副本的加载范围打开之前
  （在模块构造器中，或在框架把模块实例注册为 Bean 时运行的 `@PostConstruct` 代码中，包括主类自身及其注入的 Bean 的，早于加载范围所覆盖的容器刷新）按名称登记的内容，
  属于当时以该名称列出的副本：模块首次加载时没有这样的副本，于是不会被移交，新副本登记同一类型仍会被拒绝；新副本构造和装配期间则属于旧副本，
  新副本加载成功时随旧副本释放，加载失败时还给旧副本（其中仍是新副本的代码）。请在 `registerSelf()` 中登记
  （它总在加载范围内运行），或传入实例。旧副本在登记被释放期间从其它线程调用 `Subscription#unsubscribe()` 取消的订阅，若新副本随后加载失败，会被恢复。
  一个新副本同时替换两个已加载副本（同一版本的两个副本可同时被列出）后加载失败时，两者的处理器都会还回，但同优先级处理器的先后顺序可能改变。
- A registration made after load through the name-only APIs belongs to a module instance (#562
  item 1). `PanelResponderRegistry#registerResponder(type, responder, ownerModule)` and the
  `EventBus` methods that take an `ownerModule` (`subscribe(..., ownerModule, consumer)` and the
  name-only `register(...)`), called outside every module's load -- from a scheduled task, a command,
  a listener -- used to file the registration under the name only. In 6.2.x such an `EventBus` registration was
  released by name when any copy of the module unloaded (`PanelResponderRegistry` is new in 6.3.0); in earlier
  6.3.0 snapshots, while a second copy of the module shared the name, it could not be told apart from that copy's
  and stayed, answering from a closed container, until the last copy of the name unloaded. As of 6.3.0 such a registration is
  recorded against the module the framework is loading on the calling thread, if any (unchanged);
  otherwise against the copy listed as loaded under `ownerModule`, the first listed when more than
  one is (`PluginManager#findRegistrationOwner(String)`, added, `@ApiStatus.Internal`). So while a
  newer copy replaces an older one, a registration made on the loading thread belongs to the newer
  copy and one made on any other thread to the older copy, and leaves with it. Unloading or
  superseding a copy releases exactly its own registrations. A name with no loaded copy keeps the
  name-only behaviour, and the instance overloads and load-time attribution are unchanged. A tab
  completer registered after load carries no name and stays unowned, as before. Prefer the overloads
  that take the instance when the module instance is at hand.

  中文补充：模块加载之后通过只带名称的 API 登记的内容属于某个模块实例（#562 第 1 项）。在任何模块加载过程之外
  （定时任务、命令、监听器中）调用三参数 `registerResponder` 或带 `ownerModule` 的 `EventBus` 方法时，此前只按名称记录：
  6.2.x 中，任一同名副本卸载时都会按名称释放这类 `EventBus` 登记（`PanelResponderRegistry` 是 6.3.0 新增的）；在本周期较早的
  6.3.0 快照中，同名的第二个副本存在期间无法与那个副本的登记区分，会一直留到该名称的最后一个副本卸载，并用已关闭的容器应答。
  6.3.0 起：若调用线程上框架正在加载某个模块，记在该模块名下（不变）；否则记在以 `ownerModule` 列为已加载的副本名下，
  列出多个时取最先列出的那个（新增 `PluginManager#findRegistrationOwner(String)`，`@ApiStatus.Internal`）。因此新副本替换旧副本期间，
  加载线程上的登记属于新副本，其它线程上的登记属于旧副本并随它释放。卸载或替换某个副本只释放它自己的登记。没有已加载副本的名称
  仍按名称记录；带实例的重载和加载期间的归属不变；加载后登记的补全器没有名称，仍不属于任何模块。手上有模块实例时请优先使用带实例的重载。
- `/ul reload` and a module's reload reporting what actually happened (#509, #529, #502). Before
  6.3.0 a module reloaded by overriding `reloadSelf()` - there was no `onReload()` hook and no per-module reload
  line - and a reload that threw reached `/ul reload <name>` only as the generic command-error line, while a bare
  `/ul reload` stopped at that module, leaving every module after it unreloaded. (Earlier 6.3.0 snapshots, which made
  `reloadSelf()` final with an `onReload()` hook, logged `Module '<name>' reloaded.` before the hook ran, so a
  throwing hook printed the success line followed by a stack trace.) As of 6.3.0:
  - the per-module line is logged only after the hook returned; when any reload step or the hook
    throws, one SEVERE line names the module and the cause instead, and the failure is rethrown
    unchanged to the caller;
  - a bare `/ul reload` reloads every module in isolation — as in `PluginManager#close()`, every
    `Exception` or `Error` one module throws is that module's failure — and ends with a summary
    naming the modules that failed instead of `All plugins reloaded.`; the summary is also sent to
    the command's sender, which previously got no reply on success and the generic command-error
    line on failure;
  - `/ul reload <name>` replies failure, naming the module and the cause, when the reload threw —
    including, as of #589, when one of the module's configuration files cannot be read or parsed
    (6.2.5 logged Bukkit's own load error and still replied success);
  - a module can report a partial reload without throwing: the new public final class
    `ReloadReport`, the new hook `protected void onReload(ReloadReport report)` — whose default body
    calls `onReload()`, so a module overriding only `onReload()` needs no change — and the
    new `public final ReloadReport reloadWithReport()`, which runs the same reload as `reloadSelf()`
    and returns the report. `reloadSelf()` keeps its `public void` signature; it is `final` as of 6.3.0
    (see [Third occurrence: 6.2.5 → 6.3.0, a MINOR](#third-occurrence-625--630-a-minor)). A partial
    reload logs a WARNING naming the parts instead of the success line, `/ul reload <name>` replies
    with those parts instead of the success reply, and the `/ul reload` summary lists the module
    with them;
  - a per-module reload whose framework `config.yml` on disk holds a different `language` than the
    one the framework runs with keeps the running language — one server-wide setting is not applied
    to one module — and reports the reload as partial, naming both values and that a full
    `/ul reload` applies it. A full `/ul reload` applies the new language to every module, as before.

  The new console lines and replies are in both shipped catalogues. A module that catches a failure
  in `onReload()` and only logs it keeps working unchanged; to make the operator see it, override
  `onReload(ReloadReport)` instead and record the failure with `report.partial(...)`.
- An existing credential file that cannot be read is preserved (#573). As of 6.3.0,
  `CommonUtils.getUltiToolsUUID()` fails with its declared `IOException`, naming the file, when the
  credential file exists but is empty, whitespace-only, the JSON literal `null` or not valid JSON,
  and it leaves the file untouched. 6.2.5 silently wrote a new server UUID over an empty `data.json`
  and let an unparseable one escape as an unchecked `JsonSyntaxException`. The same applies to an
  unreadable pre-6.3.0 `data.json` while no current credential file exists, and saving or clearing
  the UltiCloud token refuses instead of overwriting. The condition is logged once at `SEVERE`. A
  credential file that does not exist at all still starts a new identity. This corrects behaviour
  that contradicted the documentation: the method declares `IOException`, and silently replacing a
  torn credential file with a fresh identity is the defect the credential store exists to prevent.
  It follows the maintainer's rule for an unreadable configuration file (#470: treated like an
  unparseable one, never overwritten). A module that already handles the declared `IOException`
  needs no change. Separately, each credential write now forces the file and its directory to disk
  around the atomic rename, which adds the cost of two disk syncs to each credential write.
  When both the pre-6.3.0 `plugins/UltiTools/data.json` and the current `.ultikits/credentials.json` under the server
  root exist, the old copy is removed only after the current file has been read as a credential document - any JSON
  object, `{}` included ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672)); the current file's content
  wins and is never overwritten with the old one's. A current file that is empty, whitespace-only, the JSON literal
  `null`, not valid JSON, a string or a number instead of an object, or not a regular file does not count: both files are
  kept unchanged, one WARNING names both paths (never any content), no server UUID is generated and no UltiCloud token is
  written or cleared, and `CredentialStore.write` refuses with a `DataAccessException` while a readable pre-6.3.0 copy
  waits behind such a file. The next access after the current file is fixed - restored from a backup, or moved out of the
  way so the old copy migrates - completes the cleanup. A current file that cannot be opened (permission denied) also keeps
  both files and fails the access. Before this change the old copy was deleted as soon as the current file existed, so an
  unreadable current file left no readable credential at all. Migration to a current file that does not exist yet is
  unchanged: the copy is written, read back and compared with the old data before the old file is removed.

  中文补充：两份凭据文件并存时，只有当前文件（服务器根目录下的 `.ultikits/credentials.json`）能被读成凭据文档（任何 JSON 对象，包括 `{}`）之后，才会删除 6.3.0 之前的旧副本（`plugins/UltiTools/data.json`）（#672）；以当前文件的内容为准，不会被旧内容覆盖。当前文件为空、只含空白、为 `null`、不是合法 JSON、是字符串或数字而不是对象，或不是普通文件时，两个文件都原样保留，一条 WARNING 写明两个路径（不含任何文件内容），不生成服务器 UUID，也不写入或清除 UltiCloud 令牌；旧副本可读而当前文件不可读期间，`CredentialStore.write` 以 `DataAccessException` 拒绝。修好当前文件（从备份恢复，或把它移开让旧副本迁移）之后的下一次访问会完成清理。当前文件无法打开（权限不足）时同样保留两个文件，并使这次访问失败。此前只要当前文件存在就会删除旧副本，当前文件不可读时就不剩任何可读的凭据。当前文件尚不存在时的迁移不变：先写入、读回并与旧数据比较，之后才删除旧文件。
- **A remote file request uses the object it authorizes** ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672),
  as of v6.3.0). The remote file API (`read`, `write`, `list`, `delete`) used to judge one form of the requested path and
  use another: the check ran on the path with every `name/..` pair removed textually, while the I/O used the path as
  sent, so a `..` after a symbolic link could reach a file outside the editable roots that the check never looked at, and
  a write to a dangling link created the link's target. Each request is now resolved on the filesystem once, and that one
  result is both what is judged and what is read, written, listed or deleted. What an operator or a panel author sees:
  a request whose real target lies outside every editable root is refused with the configurable
  `ultipanel.files.editable-roots` refusal, and a `list` marks such an entry `accessible: false` with
  `reason: OUTSIDE_ROOTS` (an alias to an outside file used to be listed as accessible); a path that cannot be located safely is
  refused and no configuration changes that - `.` or `..` in the part of the path that does not exist yet, a dangling or
  looping symbolic link, a directory that cannot be searched, and a `delete` that names no ordinary entry (`/`, `.`,
  `dir/.`, `dir/..`; these used to remove the contents of the directory they collapse to); a symbolic link inside a root
  that points to a protected file (a credential file, the remote action log's directory) is refused like that file, and a
  `list` marks it `PROTECTED_CREDENTIAL`; a `delete` of a terminal symbolic link - to a file, to a directory with
  `recursive` absent, `false` or `true`, or to nothing - removes only the link and leaves its target and everything under
  it alone (a link to a directory deleted with `recursive: true` used to empty the directory it pointed to first), and a
  recursive `delete` of a real directory never follows a symbolic link inside the deleted tree: that link is removed as a
  link and its target is untouched (a link inside the tree to an outside directory or to the action log's directory used
  to have its target emptied, and one to the credential directory used to refuse the whole delete). Links and
  junctions are never followed whether they are the delete target or inside the deleted tree, and protected locations
  are judged by real path, whichever editable root a request matched and in whatever order the roots are configured
  (roots `[plugins/shadow, plugins]` with `plugins/shadow` linking to `plugins/UltiTools` do not reach the action log's
  directory through either root), so a `list` also marks a link to a directory by its target like any other alias. Every
  refusal is one `DENIED` entry in the remote action log; a refusal reached while the path was being resolved on disk was
  recorded `ALLOWED` before. A permitted request that then fails for an ordinary reason (a missing file, a path of the
  wrong type, a write without content) is still one `ALLOWED` entry and a failure reply, and a `list` still records one
  entry for the directory, never one per row. A panel that stays inside its roots sees no difference; one that reached a
  file through a `..` after a link is now refused, and the operator who wants that content reachable adds its real
  directory to `ultipanel.files.editable-roots`. Known limitations: a separate local process that swaps a path component
  for a link between the check and the use can still redirect that one request (the panel itself cannot create links);
  a hard link to a credential file that the operator places inside an editable root under another name cannot be told
  apart from an ordinary file by path, so do not hard-link credential files into editable roots; and on a
  case-insensitive macOS filesystem a case variant of the protected `plugins/UltiTools/security` directory name is not
  recognised by the unconditional deny layer. On such a filesystem a request that names a directory in the wrong
  case is treated as an entry rather than as a real directory: a non-empty directory requested in the wrong case is
  refused ("directory not empty") and not deleted, and an empty one is deleted without `recursive`.

  中文补充：**远程文件请求使用的就是被授权的那个对象**（自 6.3.0 起，#672）。远程文件 API（`read`、`write`、`list`、`delete`）原来判断的是路径的一种形式，实际使用的却是另一种：检查时先把路径中每个 `名称/..` 在文本上消去，I/O 却用原样的路径，所以符号链接后面的 `..` 可能到达检查从未看过的可编辑根之外的文件，向悬空链接写入还会创建链接指向的目标。现在每个请求只在文件系统上解析一次，这一个结果既是被判断的对象，也是被读取、写入、列出或删除的对象。服主和面板作者会看到：真实目标在所有可编辑根之外的请求，以可配置的 `ultipanel.files.editable-roots` 拒绝，`list` 会把这样的条目标为 `accessible: false`、`reason: OUTSIDE_ROOTS`（指向根外文件的别名原来被列为可访问）；无法被安全定位的路径一律拒绝且无法通过配置放行——路径中尚不存在的部分里的 `.` 或 `..`、悬空或循环的符号链接、无法搜索的目录，以及不指向任何普通条目的 `delete`（`/`、`.`、`dir/.`、`dir/..`，这些原来会清掉它们所折叠到的目录的内容）；根内指向受保护文件（凭据文件、远程操作日志目录）的符号链接，与该文件本身一样被拒绝，`list` 将其标为 `PROTECTED_CREDENTIAL`；对末端符号链接的 `delete`——无论指向文件、指向目录（`recursive` 缺省、`false` 或 `true`）还是什么都不指向——只删除链接本身，目标及其下的一切不动（带 `recursive: true` 删除指向目录的链接，原来会先清空链接所指的目录）；对真实目录的递归 `delete` 也绝不跟随被删目录树内部的符号链接：该链接作为链接被删除，其目标不动（树内指向根外目录或操作日志目录的链接，原来会让其目标被清空；指向凭据目录的链接，原来会使整个删除被拒绝）。链接与 junction 无论是删除目标本身还是位于被删除的树内，都不会被跟随；受保护位置按真实路径判断，与请求匹配到哪个可编辑根、根的配置顺序无关（根为 `[plugins/shadow, plugins]` 且 `plugins/shadow` 链接到 `plugins/UltiTools` 时，经哪个根都到不了操作日志目录），因此 `list` 也像对待其他别名一样按目标标记指向目录的链接。每次拒绝都在远程操作日志里记为一条 `DENIED`；此前在磁盘上解析路径时才发生的拒绝被记为 `ALLOWED`。获准之后因普通原因失败的请求（文件不存在、类型不符、写入不带内容）仍是一条 `ALLOWED` 加一条失败回复，`list` 仍只为目录记一条，不会每行一条。只在根内活动的面板看不到差别；通过链接后面的 `..` 到达文件的面板现在会被拒绝，想让那些内容可达的服主，请把真实目录加入 `ultipanel.files.editable-roots`。已知限制：另一个本地进程若在检查与使用之间把路径中的某一段换成链接，仍可改变这一次请求的去向（面板本身无法创建链接）；服主以其他名称放进可编辑根的、指向凭据文件的硬链接，按路径无法与普通文件区分，所以不要把凭据文件硬链接进可编辑根；在不区分大小写的 macOS 文件系统上，受保护的 `plugins/UltiTools/security` 目录名的大小写变体不会被无条件拒绝层识别。在这类文件系统上，以错误大小写指定目录的请求会被当作普通条目而非真实目录处理：以错误大小写请求删除非空目录会被拒绝（“directory not empty”）而不会被删除，空目录则无需 `recursive` 即被删除。
- **`update_config` is gated by the target it routes to** ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672),
  as of v6.3.0). The message was gated by `ultipanel.capabilities.file-write` whatever it carried, although a `fileName` of
  `server_properties` is handed to the server-properties editor (a read without content, a write with it). A message for
  that target now needs `ultipanel.capabilities.server-properties` and nothing else, and every other `update_config` (a
  named configuration file, the whole configuration) needs `ultipanel.capabilities.file-write` and nothing else; a
  `fileName` the handler cannot read as one string needs `file-write` and reaches no editor. A refusal is the existing
  `capability_denied` reply naming the switch, with the request's `requestId`, and one `DENIED` entry in the remote action
  log. So a panel that edited `server.properties` through `update_config` with only `file-write` on is refused until the
  operator turns `server-properties` on, and with `server-properties` on and `file-write` off the same message is allowed.
  The safe-key whitelist is unchanged: a key outside it still gets an error reply naming the key, and the file is not
  touched.

  中文补充：**`update_config` 按它实际路由到的目标选择开关**（自 6.3.0 起，#672）。原来这条消息无论内容是什么都由 `ultipanel.capabilities.file-write` 把关，而 `fileName` 为 `server_properties` 的消息其实交给服务器属性编辑器处理（不带内容为读取，带内容为写入）。现在该目标只需要 `ultipanel.capabilities.server-properties`，别无其他；其余所有 `update_config`（指名的配置文件、整份配置）只需要 `ultipanel.capabilities.file-write`；处理器读不成单个字符串的 `fileName` 需要 `file-write`，也到不了任何编辑器。拒绝时仍是现有的、点明开关的 `capability_denied` 回复（带上请求的 `requestId`），并在远程操作日志里记一条 `DENIED`。因此只开了 `file-write` 就通过 `update_config` 编辑 `server.properties` 的面板，在服主打开 `server-properties` 之前会被拒绝；开着 `server-properties`、关着 `file-write` 时，同一条消息现在被允许。安全键白名单不变：白名单之外的键仍得到点明该键的错误回复，文件不被改动。
- **A malformed `ultipanel.commands.blocklist` is rejected whole** ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672),
  as of v6.3.0). The value was read through a converting list getter, so a plain string, an empty string or a list holding a
  mapping installed an empty or partial blocklist, and the panel could run commands the operator meant to block. When the
  framework loads the key at server start it now accepts only a list of which every element is a string (each trimmed and
  lower-cased); anything else - a scalar, a map, or a list with an element that is a mapping, a nested list, null, a number or
  a boolean - is ignored as a whole. One WARNING names `ultipanel.commands.blocklist` and the kind of the value, never the
  value, and the ten-command default stays in force. An explicit empty list `[]` is valid and blocks nothing, and an absent
  key, or `blocklist: ~`, keeps the default. The file is never rewritten. To block nothing, write `[]`; to block a command,
  write it as a string element.

  中文补充：**格式错误的 `ultipanel.commands.blocklist` 会被整体拒绝**（自 6.3.0 起，#672）。原来这个值经由会做类型转换的列表读取方法读入，所以普通字符串、空字符串或含映射的列表会装入一个空的或残缺的黑名单，面板就能执行服主本想拦截的命令。现在框架在服务器启动时加载该键，只接受每个元素都是字符串的列表（每项去掉首尾空白并转小写）；其他任何形式——标量、映射，或含映射、嵌套列表、null、数字、布尔元素的列表——都整体忽略，输出一条 WARNING，写明 `ultipanel.commands.blocklist` 和值的种类（不写值本身），十条命令的默认值继续生效。显式的空列表 `[]` 合法，表示不拦截任何命令；键缺失或写成 `blocklist: ~` 时保留默认值。文件绝不被改写。想不拦截任何命令请写 `[]`；想拦截某条命令，把它写成字符串元素。
- Configuration writes follow the maintainer's rule that operator-written configuration is never
  overwritten automatically (2026-10-04, "what code may write, by file type";
  [#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599)). Changed without a migration period,
  because a migration period would mean continuing to overwrite operators' files: a module's `save()`
  writes only what the module changed, where the file still holds what was read, and never writes over an
  operator's disk edit, deleted key or unusable value (it names the change as not written instead of
  overwriting it, as 6.2.5 did); a panel edit writes only what it touches; and nothing writes configuration at
  server stop, module unload or module replacement - a never-saved module change is named at stop and
  dropped, and `ConfigManager#saveAll()` is deprecated and writes nothing. A module that relied on the stop
  to persist an in-memory change must call `save()` (or `saveOperatorChange`) when it makes the change; the
  stop warning names any configuration that still holds one.
- The framework's own `plugins/UltiTools/config.yml` gets its missing panel keys (the capability switches,
  `ultipanel.commands.blocklist`, `ultipanel.files.editable-roots` and the action-log rotation keys) through the
  same write gate, insert only ([#605](https://github.com/UltiKits/UltiTools-Reborn/issues/605)). The migration that adds
  these keys is new in 6.3.0 (6.2.x wrote `config.yml` only when it was absent). Its first version, in earlier 6.3.0
  snapshots, re-emitted the whole file: hex numbers became decimal, anchors were expanded, a dotted key such as `o.O`
  was split into a nested map, and an explicit `null` was replaced. Now every other byte stays, measured on every released 6.2.x `config.yml`; a file the gate cannot write byte-identically (hand
  alignment, anchors) is left as it is with one WARNING naming the keys, and the defaults shipped in the jar's
  `config.yml` (which now also lists the blocklist and the editable roots) answer for them. A file that holds one of
  these settings as a flat dotted key (`ultipanel.capabilities.logs: false`) or a dotted section key, where the
  inserted nested section would replace it as the server reads the file, is not written either: one WARNING names the
  file and that setting (never a value), and the jar defaults answer for the missing keys. Measured on older
  releases: a 6.0.9 or 6.1.x `config.yml` is written insert-only too; the `config.yml` of every 6.0.x release before 6.0.9
  (6.0.0 to 6.0.8) holds `trustIp: [ ]` (spaces inside flow brackets), so it is refused at every start with that one
  WARNING naming line 14, the missing keys run on the jar's defaults, and nothing is written - only a direct upgrade
  from 6.0.8 or older sees this. Writing `trustIp: []`, or adding the keys by hand, ends the warning.
- UltiTools starts bStats only when `plugins/bStats/config.yml` is absent or already holds `serverUuid`
  ([#606](https://github.com/UltiKits/UltiTools-Reborn/issues/606)). That file is shared by every bStats plugin;
  before 6.3.0 an unparseable copy was read as empty and bStats' defaults were saved over it. A file that cannot
  be read or parsed, or one without `serverUuid`, is now left byte-identical, one line names it, and UltiTools'
  metrics stay off for that run (other plugins' own bStats copies are outside the framework). On Paper, the server's own
  metrics code reads that file at start, before any plugin loads, and rewrites an unparseable copy or one without
  `serverUuid` with fresh defaults (measured on Paper 1.21.11), so on Paper such a file is replaced by the server, never by
  UltiTools, and UltiTools only ever sees a valid file.
- **`@NotEmpty` on a list, set or map now acts** ([#630](https://github.com/UltiKits/UltiTools-Reborn/issues/630), as of
  v6.3.0, maintainer decision of 2026-10-06). Before 6.3.0 the check read only the value's text, so an empty list (`[]`)
  or map (`{}`) passed and the module ran on an empty value its declaration forbids (UltiKits/UltiCleaner#34: an empty
  `warn-times` silently turned the countdown off). Now a value that a load or reload binds empty - empty or `null` in
  the file, or a list whose every entry failed to bind - is replaced **in memory** by the field's declared default, and
  one WARNING names the file, the key, the value kind, the value as written and the default (both redacted when the
  field name, a key segment, or a map key inside the value or the default is secret-shaped). The module loads and the
  file is not written, at load and at reload; a later `save()` does not write the default either. The panel shows the
  file's value (`[]`) while the module runs on the default. A panel write that would empty the value is refused like any
  other violation, and nothing is written. An operator command that writes that setting with `saveOperatorChange` writes
  the running value - the default plus the operator's change - in place of `[]`: that is the operator's explicit request
  for that key. The declared default must itself satisfy the field's constraints - be non-empty, and inside the field's
  `@Size` if it has one (UltiSideBar's `lines` is `@NotEmpty @Size(min = 1, max = 15)`): a default that does not refuses
  the module at load as a declaration error naming the field and the constraint, whatever the file holds. `@NotEmpty` on text refuses
  the module at load, naming the field, like every other constraint violation in 6.3.0 (6.2.x reset the value to its
  default and saved the file). A `@NotEmpty` list written as an explicit `null` (`key: ~`) runs the declared default
  in memory with the warning and the file is not written (6.2.x reset it to the default and wrote the default into
  the file; earlier 6.3.0 snapshots refused the module).

  中文补充：**`@NotEmpty` 用在列表、集合或映射上现在会生效**（自 6.3.0 起，#630，维护者 2026-10-06 决定）。6.3.0 之前只检查值的文本，空列表 `[]` 或空映射 `{}` 都能通过，模块带着声明为“不得为空”的空值运行（UltiKits/UltiCleaner#34：空的 `warn-times` 让倒计时提示悄悄失效）。现在加载或重载时绑定为空的值——文件中为空或为 `null`，或列表中每一项都无法绑定——会在**内存中**改用字段声明的默认值，并输出一条 WARNING，写明文件、键、值的种类、文件中写的值和默认值（字段名、某一级键名，或值与默认值中的映射键像机密时，两者都隐去）。模块照常加载，加载和重载时都不写文件，之后的 `save()` 也不会把默认值写进去。面板显示的是文件中的值（`[]`），而模块运行的是默认值。面板写入会让该值变空时，与其他违规一样被拒绝，什么都不写。服主命令用 `saveOperatorChange` 写这个设置时，写入的是运行中的值（默认值加上服主的改动），替换掉 `[]`：这是服主对该键的明确要求。声明的默认值本身必须满足该字段的约束——非空，并且若字段带 `@Size` 则在其范围内（UltiSideBar 的 `lines` 是 `@NotEmpty @Size(min = 1, max = 15)`）：不满足时属于声明错误，无论文件里写的是什么，加载时都会拒绝该模块并写明字段和约束。文本上的 `@NotEmpty` 与 6.3.0 中其他约束违规一样，拒绝加载并指明字段（6.2.x 会把值重置为默认值并保存文件）。写成显式 `null`（`key: ~`）的 `@NotEmpty` 列表在内存中改用声明的默认值并给出同一条警告，文件不写（6.2.x 会重置为默认值并把默认值写进文件；本周期较早的 6.3.0 快照则拒绝模块）。
- **`@Range` refuses NaN** ([#625](https://github.com/UltiKits/UltiTools-Reborn/issues/625), as of v6.3.0). The check
  compared with `<` and `>`, both false for NaN, so `exp-tax-rate: .nan` passed a `@Range(min = 0.0, max = 1.0)` and
  reached the module (UltiKits/UltiTrade#65). Now a value must satisfy `min <= value <= max`: NaN is out of every range and
  gets the ordinary out-of-range outcome - the module refuses to load at start, and at `/ul reload` the reload is refused
  and the running values are kept, naming the field, the value and the bounds. `.inf` and `-.inf` were already out of
  range for every finite bound and still are; a bound declared as `Double.POSITIVE_INFINITY` (or negative) accepts that
  infinity.

  中文补充：**`@Range` 拒绝 NaN**（自 6.3.0 起，#625）。原来的检查用 `<` 和 `>` 比较，对 NaN 都为假，因此 `exp-tax-rate: .nan` 能通过 `@Range(min = 0.0, max = 1.0)` 并进入模块（UltiKits/UltiTrade#65）。现在值必须满足 `min <= 值 <= max`：NaN 超出任何范围，按普通越界处理——启动时拒绝加载模块，`/ul reload` 时拒绝这次重载并保留运行中的值，都会写明字段、值和范围。`.inf`、`-.inf` 本来就超出任何有限范围，现在仍然如此；边界声明为 `Double.POSITIVE_INFINITY`（或负无穷）时接受对应的无穷大。
- **A constraint annotation the framework cannot check refuses the module at load**
  ([#631](https://github.com/UltiKits/UltiTools-Reborn/issues/631), as of v6.3.0, maintainer decision of 2026-10-06).
  Before 6.3.0 such a declaration did nothing, with no message: `@Range` on anything but a number, `@Pattern` on anything
  but text, `@Size` on a map or an array, `@Size` and `@NotEmpty` on numbers, booleans, enums or value types. Now, for a
  field that is itself a `@ConfigEntry` setting, judged by its declared type. A declared type that can hold a value the
  annotation checks - `Object`, `Serializable`, `Comparable`, `CharSequence`, `Number` or another supertype of a checked
  kind - is not a constraint error: the value bound at load is checked. Of these types only `Object` binds without a
  converter; the others need a module converter (`@ConfigConverterFor`), or the module is refused with `no config
  converter for ...`. A bound value of a kind the annotation cannot read (text in an `Object` setting under `@Range`, a
  number under `@Pattern`, `@Size` or `@NotEmpty`) is a violation, with that annotation's usual outcome: refused at
  load, and at `/ul reload` the reload is refused and the running values are kept; before 6.3.0 it passed silently. The
  rules for such a field:
  - `@Size` counts a map's entries and an array's length (a violation refuses the module like any `@Size` violation).
    Text is a `String` or a `char` for `@NotEmpty`, `@Size` and `@Pattern`.
  - A declared type that can never hold a checkable value is a declaration error: it refuses the module at load, before
    its file is read, with one message naming each field, the annotation and why: `@Range` checks numbers only; `@Pattern` checks text only; `@Size` and `@NotEmpty`
    apply to text, lists, sets, maps and arrays only (`@Range` on a `List<Integer>` is such an error: it does not apply
    to the list itself). A constraint on a field of a config class that is not a `@ConfigEntry` setting is refused too.
  - **The constraint annotations take effect only on fields that are themselves `@ConfigEntry` settings of a config
    class.** An annotation on a field of a value type, a nested class, or anything a converter produces - for example
    `@NotEmpty` on a field of the class a `Map<String, Item>` setting holds - is never checked and not reported; the
    framework does not walk into value types ([#633](https://github.com/UltiKits/UltiTools-Reborn/issues/633), closed by
    the maintainer's decision of 2026-10-06). Validate such fields in the module's converter (skip or refuse the entry
    there).
  - First-party modules: UltiRecipe declares `@NotEmpty` and `@Range` on `RecipeConfig.OutputItem`, a value inside its
    `recipes` setting. They are not checked (it checks them itself), and the module loads; its 6.3.0 build keeps the two
    annotations; they have no effect there. No first-party module is refused by these declaration checks.

  中文补充：**框架无法检查的约束注解会让模块在加载时被拒绝**（自 6.3.0 起，#631，维护者 2026-10-06 决定）。6.3.0 之前这类声明什么也不做、也没有任何提示：用在非数字上的 `@Range`、非文本上的 `@Pattern`、映射或数组上的 `@Size`、数字/布尔/枚举/值类型上的 `@Size` 与 `@NotEmpty`。现在，对本身就是 `@ConfigEntry` 设置的字段，按其声明类型判断；声明类型若能容纳注解可检查的值（`Object`、`Serializable`、`Comparable`、`CharSequence`、`Number` 或其他被检查种类的父类型），则不算约束错误，加载时绑定的值照常检查；这些类型中只有 `Object` 无需转换器即可绑定，其余类型需要模块转换器（`@ConfigConverterFor`），否则模块会以 `no config converter for ...` 被拒绝；绑定的值若是注解无法读取的种类（`@Range` 下 `Object` 设置里的文本，`@Pattern`、`@Size` 或 `@NotEmpty` 下的数字），按该注解的普通违规处理：启动时拒绝加载，`/ul reload` 时拒绝重载并保留运行中的值（6.3.0 之前会静默通过）；这类字段的规则如下：`@Size` 统计映射的条目数和数组长度（违规时与其他 `@Size` 违规一样拒绝模块）；对 `@NotEmpty`、`@Size`、`@Pattern` 而言，文本指 `String` 或 `char`。只有永远不可能容纳可检查值的声明类型才算声明错误：这类声明会在加载时、读取配置文件之前拒绝模块，一条消息写明每个字段、注解和原因：`@Range` 只检查数字；`@Pattern` 只检查文本；`@Size` 与 `@NotEmpty` 只适用于文本、列表、集合、映射和数组（`List<Integer>` 上的 `@Range` 就属于这种错误：它不适用于列表本身）。配置类中不是 `@ConfigEntry` 设置的字段上的约束同样会被拒绝。**约束注解只对本身就是配置类 `@ConfigEntry` 设置的字段生效。** 值类型的字段、嵌套类的字段，以及转换器产生的任何对象上的注解（例如 `Map<String, Item>` 设置中 `Item` 类字段上的 `@NotEmpty`）既不检查也不报告；框架不会深入值类型（#633，按维护者 2026-10-06 的决定关闭）。请在模块的转换器中校验这些字段（在那里跳过或拒绝该条目）。第一方模块：UltiRecipe 在 `RecipeConfig.OutputItem`（其 `recipes` 设置中的值）上声明了 `@NotEmpty` 和 `@Range`，它们不会被检查（模块自行检查），模块照常加载；它面向 6.3.0 的构建保留了这两个注解，但它们在那里不起作用。没有第一方模块会被这些声明检查拒绝。

- **Declarative GUI: one frame runs only the work queued when it starts** (as of v6.3.0). Before 6.3.0 a
  frame kept running until its queue was empty, so a widget whose `State.initState` called `setState` and then
  threw kept the server thread rebuilding that page inside a single frame and never returned. Now a rebuild
  requested during a frame runs on the next one. **Known limitation:** such a broken page is still retried once
  per tick for as long as it is open, and each failure is logged. Fix the widget: a `State` must not throw from
  `initState`; catch the failure there and render an error state instead.

  中文补充：**声明式 GUI：每一帧只执行该帧开始时已排队的任务**（自 6.3.0 起）。6.3.0 之前一帧会一直执行到队列清空，
  因此某个组件的 `State.initState` 先调用 `setState` 再抛出异常时，服务器主线程会在同一帧里反复重建该页面、永不返回。
  现在帧内请求的重建在下一帧执行。**已知限制：**这样的坏页面只要还开着，仍会每个 tick 重试一次，每次失败都会记录日志。
  请修复该组件：`State` 不应从 `initState` 抛出异常，应在其中捕获失败并渲染错误状态。

### Behavioral changes that do need one

- A documented default value flipping.
- Moving from silent degradation to failure (for example, a missing optional dependency was
  previously skipped and is now rejected at load time).
- A change in return-value semantics (previously an empty collection, now `null`, or the reverse).
- A change in the timing or thread of a side effect (previously synchronous, now asynchronous).

The migration period runs in two steps:

- **Version N**: keep the old behaviour, but emit a **one-shot** WARNING when the path is taken. The
  warning text must name the target version and a feedback issue link, for example:

  ```
  [UltiTools] Module <name> relies on the old behaviour of X (<one-line description>).
  This behaviour will change to <new behaviour> in 6.4.0. See <issue link> to migrate.
  This warning is printed once per startup.
  ```

- **Version N+1**: switch to the new behaviour and remove the warning.

This section follows [PEP 387](https://peps.python.org/pep-0387/); the principle is the same one:
tell people which floor they are standing on before removing it.

### Command bodies run at dispatch (6.3.0) — changed without a migration period

This is a change "in the timing of a side effect", the last kind listed above, made in 6.3.0 without
the two-step period by the maintainer's decision of 2026-09-29 (#541), which accepted that it changes
command timing for **every module, third-party modules included**.

**What changed.** Before 6.3.0, `BaseCommandExecutor` ran every synchronous command body one tick after
the command was dispatched. As of 6.3.0 the body runs **at dispatch**, inside the call that dispatched
it, whenever that call is on the server's primary thread — which is where Bukkit dispatches everything
a player, the console, a command block, a command minecart, RCON or the panel sends. It is handed to
`runTask` only when `onCommand` is called from another thread, as Bukkit's own commands behave. An
`@AsyncCommand` or `@RunAsync` body is unchanged: it always runs asynchronously. The deferral had no
recorded reason: it came from sharing one `BukkitRunnable` with `@RunAsync` in 6.0.0.

**Why.** Paper 1.21.11 records a command block's output only while its dispatch is open, and RCON
reads its output as soon as the dispatch returns, so a deferred body's replies to those senders were
lost. (The panel's remote command runs as the server console, whose replies no sender can read; they
reach the panel through the log stream instead — see "The panel's log stream mirrors the server
console" below.) The deferral also let two dispatches in one tick both pass
`@CmdCD`, because the cooldown was recorded when the deferred body finished; the cooldown is now
recorded before the first dispatch returns.

**What a module author has to check.**

- Code that relied on the one-tick delay — for example a command body that expected the dispatching
  event to have finished first — must schedule that work itself with `runTask`.
- **Paper's rule for inventory clicks.** An `InventoryClickEvent` handler (including a GUI library's
  `onClick` callback) must not open or close an inventory directly, and must not call
  `performCommand` or `Bukkit.dispatchCommand` directly either: a module command dispatched there now
  runs its body — which may open or close an inventory — inside the click event. Defer the call with
  `Bukkit.getScheduler().runTask(...)`. The fifteen UltiKits modules were surveyed; the handlers that
  did this were fixed in those modules' 6.3.0 builds (UltiMenu#28, UltiSocial#27, UltiKits#41, UltiMail#43 and
  UltiWorlds#50, all closed).
- **A body that dispatches another command** runs the nested body on the same thread before its own
  has finished. The audit user (`AuditableDataEntity`'s current user, written into `created_by` /
  `updated_by`) is saved before each body and restored after it: the nested body sees its own sender
  (none for a sender that is not a player), the outer body sees its own sender again afterwards, and a
  thread that carried no user before the outermost command carries none after it. Before 6.3.0 the
  body cleared the user when it finished.
- **`@UsageLimit` and re-entry.** A body that dispatches its own command while holding its lock gets
  the nested dispatch refused with the ordinary lock message (`SENDER`: from the same sender; `ALL`:
  from any sender). Acquiring never waits, so nothing blocks; the outer lock is released when the
  outer body returns, normally or by throwing. Schedule the nested call with `runTask` if it must run.
  A body that dispatches its own command **without** `@UsageLimit` and without a stopping condition
  now recurses on the main thread until the stack overflows; before 6.3.0 it repeated once a tick.
- **Two dispatches in one tick meet the cooldown.** The second of two dispatches of a `@CmdCD` command
  by one player in the same tick is refused.

### Other command and task runtime changes (6.3.0) that need no migration period

Each corrects behaviour that contradicted the documentation or left state held by mistake.

- **Active `@CmdCD` cooldowns are kept per executor instance** as well as per mapping (#539). One
  `CooldownValidator` shared by two executors of one class — two modules passing one `ValidatorChain`
  — no longer refuses a player on executor B for a use through executor A. `clearCooldown(UUID, String)`
  and `getRemainingCooldown(UUID, String)` keep working unchanged for one executor per validator, which
  is the shape both `BaseCommandExecutor` constructors create; when a validator serves several
  executors they span all of them (the longest remaining time, and every executor's cooldown
  cleared). The new overloads `clearCooldown(UUID, Object, String)` and
  `getRemainingCooldown(UUID, Object, String)` address one executor. The executor is held weakly, so
  an active cooldown never keeps an unloaded module's executor reachable.
- **A `@UsageLimit` lock is released when the dispatch is refused after it was taken** (#568): by the
  cooldown (which validates after the lock), by the argument-count check, because a parameter did
  not parse, or by an exception from a later validator, a parameter parser or the scheduler. Before 6.3.0 the lock stayed held until the player quit, and every later call of the
  mapping was refused. The mechanism is a new default method, `CommandValidator#onRefused`, called for
  each validator that passed; its default does nothing, so existing validators are unaffected, and a
  refused dispatch runs no `onComplete`, so it applies no cooldown. Every `onRefused` and every
  `onComplete` hook now runs even when an earlier validator's hook throws; the first exception is
  rethrown afterwards.
- **`@Scheduled` methods declared on a superclass of a bean are scheduled** (#532), as the annotation's
  javadoc always said. An overridden method is scheduled once, with the most derived declaration's
  annotation; an override without `@Scheduled` is not scheduled. A method that previously never ran
  because an abstract base declared it now runs; none of the fifteen UltiKits modules declares one.
- **One failing `@PlayerCache` save or removal no longer stops the quit cleanup** (#643). When a player
  quits, the framework runs `PlayerCacheSaver#savePlayerData` for each `saveBeforeRemove = true` field
  and then removes the player's entry from every `@PlayerCache` field. Before 6.3.0 the first save or
  removal that threw (a module's database error, for example) ended the cleanup: that field's entry and
  the entries of every later field and bean stayed in memory, and the exception escaped into Bukkit's
  event dispatch. Each field is now handled on its own: a failure (any exception, including a checked
  one the saver throws without declaring it, or an `Error`) is logged as one WARNING naming the bean
  class, the field and the player, and the cleanup goes on; a saver that registers or unregisters a
  tracked object during the quit no longer ends it either. A failed save does not keep the entry; it
  is removed anyway (maintainer decision, 2026-10-08), because `saveBeforeRemove` orders the save
  before the removal. The framework does not retry the save; a module that must not lose the data
  retries or persists it itself, inside `savePlayerData`. In 6.2.5 the entry stayed in memory, where a
  module's own later code (a shutdown flush, for example) could still write it; it no longer does.
  The periodic expiry sweep (`PlayerCacheManager.ExpiringPlayerCache`)
  also contains an `Error` from one bean, so the beans after it are still swept. None of the fifteen
  UltiKits modules uses `saveBeforeRemove`. No signature changes. See
  `ultitools.runtime.player-cache-quit` in `FEATURES.md`.

  中文补充：一个 `@PlayerCache` 字段的保存或移除失败，不再中断玩家退出时的清理（#643）。玩家退出时，框架对每个
  `saveBeforeRemove = true` 的字段调用 `PlayerCacheSaver#savePlayerData`，再从每个 `@PlayerCache` 字段中移除该玩家的条目。
  6.3.0 之前，第一个抛出异常的保存或移除（例如模块的数据库错误）会结束整个清理：该字段及其后所有字段、所有 bean 中的条目都留在
  内存里，异常还会进入 Bukkit 的事件分发。现在每个字段单独处理：失败（任何异常，包括保存方未声明而抛出的受检异常，或 `Error`）
  记录为一条 WARNING，写明 bean 类、字段和玩家，清理继续进行；保存方在退出清理期间注册或注销被跟踪对象，也不再中断清理。保存失败
  不会保留条目，条目照样移除（维护者决定，2026-10-08），因为 `saveBeforeRemove` 只规定先保存后移除。框架不会重试保存；不能丢失
  数据的模块需要在 `savePlayerData` 内自行重试或持久化。在 6.2.5 中该条目会留在内存里，模块自己之后的代码（例如关服时的写入）
  仍可能写入它；现在不再如此。定期过期清理（`PlayerCacheManager.ExpiringPlayerCache`）同样会拦下单个 bean 抛出的
  `Error`，其后的 bean 仍会被清理。十五个 UltiKits 模块都没有使用 `saveBeforeRemove`。没有签名变化。见 `FEATURES.md` 中的
  `ultitools.runtime.player-cache-quit`。

- **Known limitations of command validators, inherited `@CmdTarget` and tab completion (6.3.0),
  documented rather than defended.** Each needs an unusual combination of conditions or code that
  steps outside the module container; none changes a verdict in normal use. A module author's
  remedy is given with each.
  - **Executors that share one custom validator chain.** An executor releases the
    `@CmdCD` and `@UsageLimit` validators it dispatched through when its module unloads, and leaves a
    validator alone while another loaded executor that has dispatched still has it in its chain (a
    chain passed to `super(chain)`, or one instance returned from `createDefaultValidatorChain()` by
    several executors). An executor counts as a user from its first dispatch on. If that first-ever
    dispatch runs off the main thread (a command run from another thread)
    at the same moment another sharing executor's module unloads, the unload can release the
    shared lock validator, clearing its locks, while that first command is still running. **Do not
    share one custom validator chain instance across executors of different modules;** give each
    executor its own chain. The validators an executor owns itself are always released correctly.
  - **A shared validator is unregistered when the last sharing executor is torn down.** Until then
    it stays registered with the player-cache manager. An executor the module container never tears
    down - one built with `new` and registered by hand, outside the container - keeps a shared
    validator registered until that executor is garbage collected. The effect is a delayed
    unregister, never a wrong verdict. **Let the module container own its executors.**
  - **The inherited `@CmdTarget` check reads the whole superclass chain at scan time.** The load
    check for a method-level `@CmdTarget` that widens or switches the class-level value now covers
    command mappings inherited from base classes, not only methods the class declares. It reads each
    superclass's methods during the component scan, so a base class with a method whose signature
    names a class that is missing at run time (an absent optional dependency) now fails the scan
    with `NoClassDefFoundError`; before, the same failure came when the executor was constructed.
    The framework has no `@ConditionalOnClass`-style annotation for this. **Keep classes that need
    an optional dependency out of command base classes** - or keep that dependency's types out of
    their method signatures - and load them only when the dependency is present.
  - **Later-argument tab completion offers each literal once, but not across sources.** For an
    argument after the first, a literal token several matching formats share (`alpha enable` and
    `alpha enable <target>`) is offered once. A `@CmdParam(suggest = ...)` completer's candidates are
    appended as it returns them, so a completer that returns the same string as a literal already
    offered can still show it twice. **Do not return a subcommand literal from a parameter
    completer.**

  中文补充：命令校验器、继承的 `@CmdTarget` 与补全的已知限制（6.3.0，写入文档，不改代码）。(1) 多个执行器共用同一个自定义
  校验器链（`super(chain)`，或几个执行器从 `createDefaultValidatorChain()` 返回同一个实例）时，若某个执行器首次分发发生在主线程之外，
  且恰在另一个共用它的模块卸载的同一时刻，卸载可能释放共享的锁校验器，而那条命令仍在执行。请勿在不同模块的执行器之间共用同一个
  自定义链，每个执行器使用自己的链。(2) 共享校验器在最后一个共用它的执行器被销毁前一直登记在玩家缓存管理器中；不由模块容器
  销毁的执行器（用 `new` 创建并手动注册）会让它保留到该执行器被垃圾回收，只是注销延后，不会产生错误的判定。请让容器持有执行器。
  (3) 继承的 `@CmdTarget` 检查在组件扫描时读取整条父类链：父类中某个方法签名引用了运行时缺失的类（缺失的可选依赖）时，扫描阶段就会
  抛出 `NoClassDefFoundError`，而不是等到执行器构造。请不要把需要可选依赖的类放进命令基类，或让依赖的类型不出现在其方法签名中。
  (4) 后续参数的补全会对字面量去重，但参数补全器返回与已提供字面量相同的字符串时，仍可能出现两次；请勿从参数补全器返回子命令字面量。

### Panel log stream and panel reply changes (6.3.0) that need no migration period

Each corrects a declared behaviour the stream did not deliver. The panel protocol changes only additively: a
`set_all` reply gains the `failureReasons` field (see the last item), and every existing message type and field keeps
its meaning.

- **`ultipanel.logging.excluded-loggers` ships empty** (#485). The six defaults before 6.3.0
  (`com.mojang.authlib`, `net.minecraft.network`, `org.apache.http`, `com.zaxxer.hikari`,
  `org.eclipse.jetty`, `ErrorReportCollector`) could never match: the stream then received only
  `java.util.logging` records, whose logger names are `Minecraft` (everything logged through
  `Bukkit.getLogger()`), a plugin's own name, or `com.ultikits.ultitools.*`; those libraries log
  through Log4j or SLF4J, and `ErrorReportCollector` never logs through JUL. A configured list is now
  used as given. Nothing that reached the stream before is filtered differently. With the console
  mirror below, Log4j lines reach the stream too, under their Log4j logger names, and a configured
  entry applies to them as well; the default stays empty so the stream shows the whole console.
- **A log batch whose send fails is held and sent first** (#486), on the transmitter's own sender and
  on the `batch_update` drain; no newer record is drained while one is held. Delivery stays best
  effort: a batch whose connection drops just after it was written may arrive twice. Records the
  full queue (1000 records) discards are counted and reported by one WARNING at most once a minute,
  in the server log only. `UltiPanelLogTransmitter#holdUndelivered(JsonArray)` is added to that
  internal class.
- **Records logged before the stream starts reach it** (#487). From `onLoad` until the panel
  connection opens, records are kept in a start-up buffer (2000 records, an estimated 512 KiB, five
  minutes) and sent, oldest first, when the stream starts, in `log_batch` messages of at most
  64 KiB, the first at once and then about one per second, independent of the
  `ultipanel.logging.batch.*` keys: a full buffer drains in seconds and uses at most 10 of the panel's
  50 messages per 10 seconds. Each entry keeps the time its record was logged, a single entry too
  large for one message is shortened (stack trace first) rather than dropped, and a live record
  logged meanwhile can arrive before the last replay messages. The buffer applies the stream's
  filters as records arrive and is released without sending anything when there is no cloud login
  or when its time is up; with the `logs` capability off it is not attached and keeps nothing.
- **Panel log delivery is best effort; the server's `latest.log` is authoritative** (#583, #571).
  The stream is a convenience copy of the console, not a record of it. Two known gaps in 6.3.0: if
  the panel connection drops while the start-up replay above is still sending, the replay entries
  not yet sent move to the new connection's live queue, which keeps the newest 1000 records and
  discards and counts the rest (reported by the queue-full WARNING in the server log), and the
  survivors are then sent like live records, without the replay's pacing; and a record another
  thread logs in the microseconds while the stream first takes over from the start-up buffer can
  be missed, and is not counted. Anything that must not be missed - an audit, an incident - is read
  from `logs/latest.log`, which receives every line.

  中文补充：面板日志推送尽力而为，以服务器的 `latest.log` 为准（#583、#571）。日志流只是控制台的便利副本，不是完整记录。6.3.0
  已知两处缺口：启动回放尚未发完时面板连接断开，未发送的回放记录会转入新连接的实时队列，该队列只保留最新的 1000 条，其余丢弃
  并计数（由服务器日志中的队列已满 WARNING 报告），留下的记录随后按实时记录发送，不再按回放的节奏；以及在日志流首次接管启动缓冲
  的那几微秒内，其他线程记下的一条记录可能漏发，且不计数。凡是不能遗漏的内容（审计、事故排查），请以 `logs/latest.log` 为准，
  它收到每一行。
- **Lines about the panel connection are no longer sent to the panel.** The panel's `error` replies
  and notifications, inbound messages the framework cannot use, the WebSocket client's connect,
  disconnect, heartbeat and reconnect lines, and the warnings about a message that could not be sent
  are written to the server console as before, but the log stream drops them. With
  `ultipanel.logging.batch.enabled: false`, each logged `error` reply used to be streamed, rejected
  by the panel's quota and replied to again: 42,066 `[WebSocket error] Rate limit exceeded` lines in
  one measured run. A panel view that showed these lines no longer receives them.
- **The panel's log stream mirrors the server console** (maintainer decision, 2026-10-03). Paper prints
  its own output through Log4j — command feedback, a module's reply to the console sender, joins and
  quits, chat, vanilla warnings and errors, and player command lines — and before 6.3.0 none of it
  reached the panel, because the stream listened only to `java.util.logging`. The framework now
  installs an appender on Log4j's root logger at load (with the `logs` capability on) and removes it at
  disable; each line passes the same filters, batching and start-up replay as a plugin line, without
  ANSI colour codes. **The stream shows exactly what the console shows, including player command lines
  with their arguments** (`<player> issued server command: /login <password>` included): the panel is
  at the console's trust level, so whatever the console shows the panel may show. A plugin line
  arrives once, although Paper also copies it into Log4j; lines about the panel connection, the
  transmitter's own lines and the WebSocket library's (`org.java_websocket.*`) are never sent. If the
  server's Log4j configuration uses asynchronous loggers, the mirror is not installed, a console
  WARNING says so, and the stream carries plugin lines only. The same holds when Paper's forwarder
  (`org.bukkit.craftbukkit.util.ForwardLogHandler`) is not on the `java.util.logging` root logger — a
  fork, a build that relocates CraftBukkit, or something that replaced the forwarder (#583): without it
  the mirror cannot tell a forwarded plugin line from a console line and would send every plugin line
  twice, so it is not installed, one console WARNING per server run says the panel will not mirror the
  console, and the stream carries plugin lines only. A Log4j `ERROR` line with an exception is
  now also reported to UltiPanel's error collection, once. **New `provided` dependency:**
  `org.apache.logging.log4j:log4j-core` (2.24.1, with `log4j-api` 2.24.1 declared alongside), which
  Paper supplies at runtime; it is not shaded, and a module needs nothing new.

  中文补充：找不到 Paper 的日志转发器（`org.bukkit.craftbukkit.util.ForwardLogHandler`，例如分支服务端、重定位了
  CraftBukkit 的构建，或转发器被替换）时，控制台镜像不安装：否则每条插件日志都会被推送两次。此时每次服务器运行只记一条
  WARNING，说明面板不会镜像控制台，日志流只包含插件日志（#583）。
- **From the moment UltiTools starts disabling, panel requests get no reply** (#621). An inbound
  panel message, a WebSocket handshake that completes, and every task the WebSocket thread would
  hand to the server scheduler are dropped with one `FINE` line, instead of scheduling work the
  scheduler refuses for a disabled plugin (before 6.3.0: one WARNING with an
  `IllegalPluginAccessException` stack in the stop log, and some requests still answered). A panel
  action sent while the server stops therefore gets no reply, and the panel's own timeout applies.
  A remote command caught in the moment the stop begins can be logged in the console and in the
  remote action log as allowed and still never run, with no result reply.

  中文补充：自 UltiTools 开始禁用起，面板请求不再得到回复（#621）。入站面板消息、完成的 WebSocket 握手，以及 WebSocket 线程
  原本要交给服务器调度器的每个任务，都只记一条 `FINE` 日志后丢弃，而不是为已禁用的插件调度会被拒绝的任务（6.3.0 之前：停服日志中
  一条带 `IllegalPluginAccessException` 堆栈的 WARNING，且部分请求仍会得到回复）。因此服务器停止期间发出的面板操作得不到回复，
  以面板自身的超时为准。恰在停服开始那一刻到达的远程命令，可能已在控制台和远程操作日志中记为允许，却不会执行，也没有结果回复。
- **The panel's remote command result no longer claims to carry the command's output.** A panel
  command is typed into the server console: it runs as the server's own console sender, unchanged for
  modules. Paper 1.21.11 replaces any console sender with the real console before running a command,
  so the framework's output capture never received a reply, and every accepted command's `command_result`
  read the invented `Command executed successfully` (a refused one read `Command execution failed`). The result now reads `Command dispatched to the server
  console. Its output appears in the server log stream.`, or `The server console did not accept the
  command. Any message it printed appears in the server log stream.` when the dispatch returned
  false; blocklist refusals, an empty command and dispatch errors are unchanged. The replies themselves
  appear in the log stream (the item above). A panel or tool that showed `output` as the command's
  reply now shows this sentence.
- **A panel write to a `server.properties` key the file does not hold is refused** with `This key is not
  in this server's server.properties` (#473). 6.2.x wrote such a key into the file; earlier 6.3.0 snapshots refused
  it as `This server version has no such key`. Nothing tells a key the running version lacks from one the file
  omits. A panel or tool that matched on the earlier text must match the new one; the UltiPanel worker and frontend do not match on it.
- **A panel edit of `server.properties` changes only the line of the key it names** (#607). Before
  6.3.0 the file was read as ISO-8859-1 and re-emitted whole: every comment dropped, keys reordered,
  and UTF-8 values of other keys (a `motd` with `§` or Chinese text) turned into mojibake. The value
  text of that one line is now replaced - key, separator, comments, order and every other byte kept -
  decoded and encoded as the server reads the file (strict UTF-8, ISO-8859-1 when it is not UTF-8),
  checked, and written atomically. A key defined on more than one line, or continued onto the next
  line, is refused with a reason naming the lines (no value); `set_all` lists it under `failed` and, as an added
  field, gives its reason under `failureReasons` (key to reason); the server log names each refused key once.
  The server itself still re-writes the whole file when it next starts (measured on Paper 1.21.11).
- **Known limitation of panel responder scheduling** (#677). Panel responders are new in 6.3.0: a module's
  responder runs on one of `PanelResponderRegistry`'s worker threads (at most 4 calls at once, at most 64 requests
  waiting) under one 3-second deadline counted from the request's arrival. If the thread admitting a request stalls
  for longer than that deadline between starting it and queueing the request, while all 4 workers are blocked, the
  request, already answered with its timeout error, is queued afterwards and holds one of the 64 waiting slots until a
  worker frees, so a later request can be refused as if the queue were full. No wrong reply is sent.

  中文补充：面板 responder 调度的已知限制（#677）。若接收请求的线程恰好在启动该请求的 3 秒时限与把请求放入队列之间停顿超过
  3 秒，且 4 个工作线程同时阻塞，这个已按超时答复的请求仍会在之后入队，并占用 64 个等待位置中的一个，直到有工作线程空出；
  其间后来的请求可能被当作队列已满而拒绝。不会发出错误的回复。

### Framework text follows `language`; the class-load audit is quiet on a clean start (6.3.0) that need no migration period

Each corrects a declared behaviour the framework did not deliver. The panel protocol is unchanged.

- **The framework's own console, reply and panel-stream text follows `language`** (#556). Until 6.3.0 about 150
  lines of framework text were Chinese string literals that never went through `lang/*.json`, so an English
  server still printed and sent them in Chinese. They now resolve through the catalogue (`en.json` gives the
  English; under `language: zh` the text is unchanged): the reply every command sender gets when a module's
  command body throws (`Command execution failed: <reason>`), the default processing notice of an
  `@AsyncCommand` (`Processing...`), the framework's console lines (server status monitoring, log transmission,
  WebSocket message handling, remote command and file operation logging), the lines it streams to the panel
  (player join, quit and chat, plugin actions, the online-player count), and the `server.properties`
  batch-failure text returned to the panel. A tool that matched one of these lines by its Chinese text on a
  server running `language: en` must match the English text; the UltiPanel worker and frontend do not match on
  any of them (their sources were searched, and the only hits were comments and the panel's own strings).
  The verification e-mail stays bilingual on purpose, because its recipient's language is not the server's.
  The default teleport service's display name, `InMemeryTeleportService#getName()`, is now `TeleportService`
  (it was `传送服务`, the only Chinese service name); its `getResourceFolderName()` still returns `传送服务`, so
  an existing install keeps its folder. Nothing in the framework or the fifteen modules looks the service up by
  its name.
  English log messages that carried full-width punctuation (`Configuration save failed！File path：…`,
  `… load failed！`) now use ASCII punctuation; a tool that matched the full-width form must be updated.
  `FrameworkText` (`com.ultikits.ultitools.utils`, `@ApiStatus.Internal`) is added for this.
- **The class-load audit is quiet on a clean start** (#557). The audit (new in 6.3.0) reports which classes the
  name-based filters removed in 6.3.0 would have refused. A module for which nothing would have been refused is
  logged at `FINE` on the audit's own logger and is not forwarded to the console, and a module with at least one
  such class gets ONE `INFO` line naming the jar and the count, through the plugin logger. (Earlier 6.3.0 snapshots
  printed each summary as two `WARN` lines, twice per module, through the standard error stream, with an internal
  requirement code.) The module-scan diagnostics (also new in 6.3.0) log their `SEVERE` summary for a skipped class
  through the plugin logger, so the console shows it as `ERROR` and the panel stream as `error` (#571; earlier
  6.3.0 snapshots printed it as `WARN` through the standard error stream). `SecurityPolicy`'s one-time deprecation
  warning (new in 6.3.0) carries no internal code either.

  中文补充：模块扫描诊断（6.3.0 新增）的 `SEVERE` 汇总（某模块跳过了加载失败的类）经插件日志输出，控制台显示为 `ERROR`，
  并以 `error` 级别推送到面板（#571；本周期较早的 6.3.0 快照经标准错误流输出，显示为 `WARN`）。

### JSON transactions (6.3.0) — staged, atomic and durable

This changes what `DataOperator#transaction(...)` guarantees when `datasource.type` is `json`, and makes
`@Transactional` work on that backend as it does on SQLite and MySQL. No transaction call is refused: a module that
called JSON `transaction(...)` in 6.2.5 keeps working unchanged, with stronger guarantees; in particular two
`transaction(...)` callbacks that read and write the same table still run one after the other, as in 6.2.5 (see
"`transaction(...)` locks a table at its first read" below). It is a change in
what other threads can see and in when data reaches the disk, made in 6.3.0 without the two-step migration
period by the maintainer's decision of 2026-10-18. In this section a *table* is one entity type of one plugin:
one `@Table`, one folder of `<id>.json` files.

**What 6.2.5 did.**

- JSON `DataOperator#transaction(...)` ran its callback while holding that one operator's lock and, when the
  callback failed, restored a copy of that one operator's data taken before the callback. The callback's writes
  went straight into the live data, so other threads could read them while the callback ran, and two tables of
  one plugin were never rolled back together. A failing nested `transaction(...)` restored only the copy taken
  when that inner scope began: the outer scope's earlier writes stayed and the outer scope completed normally.
- `@Transactional` had no effect on any backend (see the `@Transactional` record in
  [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md)).
- Plain writes reached the disk at the next background flush (every `datasource.flushRate` seconds, 10 by
  default). The flush rewrote every cached entity file in place, one after another, with no temporary file and no
  force, so a crash during a flush could leave files partly written or only some of them updated.

**What 6.3.0 does.** For a transaction opened by `DataOperator#transaction(...)` or by `@Transactional`:

- **Staged writes.** Inside the transaction, writes are staged for that transaction and its own reads see them.
  Other threads see only committed data. Commit applies the staged writes together; rollback discards them and
  never touches anything that was committed.
- **One instant.** Other threads see each commit at one instant across all of the plugin's tables: one read
  returns all of a commit's changes or none of them. A read can pause for the short in-memory step in which a
  commit makes its changes visible (no disk access, no module code).
- **Atomic across tables and across a crash.** A transaction that covers several tables of one plugin commits as
  one unit and stays all-or-nothing across a crash.
- **Durable once `commit` returns.** Before `commit` returns, a journal record holding the transaction's changes
  is written and forced to disk, and the next start replays it. A transaction that changed nothing writes
  nothing and forces nothing. The qualifications for Windows and macOS are under "Durability by platform" below.
- **Table write locks.** The first write to a table inside a transaction locks that table against other writers
  until the transaction ends, so no other write overwrites the transaction's uncommitted change or slips between
  its writes. A read in a `@Transactional` transaction at the default isolation never waits for a table lock.
- **`transaction(...)` locks a table at its first read.** A transaction begun by `DataOperator#transaction(...)`
  (either overload) locks each table at its first read or write, as `REPEATABLE_READ` does, and holds the lock
  until it ends. Two `transaction(...)` callbacks that read and then write one table therefore run one after the
  other, as in 6.2.5, where each callback held its operator's lock, and a read-modify-write inside one cannot lose
  an update. The cost is the one 6.2.5 also had: a read inside `transaction(...)` can wait for another transaction
  that holds the table, at most 5 seconds (bounded as a write is, below; 6.2.5's lock waited without a bound).
  A `transaction(...)` that joins an active transaction (a `@Transactional` method, or an outer
  `transaction(...)`) keeps that transaction's isolation, as a joined scope does in Spring: inside a
  `@Transactional` method at the default isolation its reads take no table lock. `@Transactional` at the default
  isolation does not lock at the first read.
- **In `@Transactional`, read-modify-write needs `updateIf` or a stricter isolation.** At the default isolation two
  `@Transactional` transactions can both read the same value, and the second then writes a value computed from what it read before the first
  committed: that lost update is not prevented. Code that computes a write from a value it read uses
  `DataOperator#updateIf` (the check and the write run together under the table lock) or
  `@Transactional(isolation = Isolation.REPEATABLE_READ)` or `Isolation.SERIALIZABLE`, which lock the table at
  the first read, as `DataOperator#transaction(...)` does.
- **Bounded waits.** A write, or a read that locks the table at its first read, that meets a locked table waits at most 5 seconds, or until the transaction's
  `@Transactional(timeout = ...)` deadline if that comes sooner, and then fails with a `DataAccessException`
  whose message starts `JSON write failed with a lock-wait timeout` (the same text for a waiting read); the call
  is not applied. A wait that would
  complete a cycle (each of two transactions waiting for the other) fails the call that closes the cycle at once,
  with a message starting `JSON write failed with a deadlock`. Either failure marks its transaction rollback-only
  before the exception is thrown, so catching the exception does not save the transaction: its outermost commit
  rolls back everything it wrote and throws `UnexpectedRollbackException`. The transaction keeps its table locks
  until it ends. Plain writes outside a transaction follow the same wait rule; such a write that fails just fails.
- **Read-only.** `readOnly = true` rejects writes. The refusal does not mark the transaction: catching it leaves the
  transaction committable, as a failed statement does on SQLite and MySQL.
- **Timeout.** `@Transactional(timeout = ...)` sets a deadline. Every data operation inside the transaction checks it
  first, and an operation started after it has passed fails with a `DataAccessException` whose message starts
  `JSON transaction timeout`. That failure marks the transaction rollback-only before it is thrown, as Spring does
  when a transaction's deadline has passed, so catching it does not save the transaction: the outermost commit rolls
  back and throws `UnexpectedRollbackException`.
- **Propagation as on SQLite and MySQL.** `REQUIRES_NEW` and `NOT_SUPPORTED` suspend the active transaction,
  including its staged writes and table locks, and resume it afterwards. One difference: such a method runs on the
  same thread as the caller it suspended, and table locks belong to the thread, so it does not wait for the locks
  its caller holds. If it writes a key its caller already staged, the caller's later commit is the value that
  stays. (On the JDBC backends the inner call would wait for its own caller's row lock until the lock-wait
  timeout.)
- **Joined methods.** On SQLite, MySQL and JSON alike, a `@Transactional` method that joins an active transaction
  (`REQUIRED`, or `SUPPORTS` or `MANDATORY` inside one) and throws an exception its `rollbackFor`/`noRollbackFor`
  rules accept marks the whole transaction rollback-only even when the caller catches it, and the outermost
  commit rolls back and throws `UnexpectedRollbackException`. An exception the rules do not accept leaves the
  transaction committable. See [A failing joined `@Transactional` method rolls back the whole transaction](#a-failing-joined-transactional-method-rolls-back-the-whole-transaction-630--changed-without-a-migration-period).
- **Hooks.** `onCreate`, `onUpdate` and `onDelete` fire when the call is made, not at commit.
- **Entity ids name files.** Each entity is stored as `<id>.json`. An `insert` that creates a new id refuses it,
  inside and outside transactions, with a `DataAccessException` (`DATA_ENTITY_INVALID`) that names the id, if the id:
  - is empty, or contains `/`, `\`, NUL or another control character (U+0000 to U+001F, U+007F);
  - is `.` or `..`, or ends in a dot or a space;
  - is a Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM1` to `COM9`, `LPT1` to `LPT9`, and `COM` or `LPT`
    followed by a superscript `¹`, `²` or `³`, in any letter case), with or without an extension: `con.txt` is
    refused too, because the part before the first dot is checked;
  - makes a file name `<id>.json` longer than 255 bytes in UTF-8;
  - names a file the server's own file system cannot name, or one its path rules read as something other than the
    file `<id>.json` in the table folder. On Windows that is an id containing `<`, `>`, `:`, `"`, `|`, `?` or `*`,
    including an id that starts with a letter and a colon, such as `c:foo` or `p:<uuid>`: Windows reads that as a
    drive prefix, so the file would be another id's file or lie outside the data folder. Linux accepts these
    characters, so UltiRemoteBag's `<player uuid>:<page>` ids keep working there.

  Every rule but the last holds on every operating system; the last is the file system's own. A refused insert
  does not mark its transaction rollback-only. An `insert` of an id that already has an entry changes nothing and
  is never refused, and a file that already exists is never renamed or rewritten because of these rules: it keeps
  loading, and reads, updates and deletes of its id keep working. Deleting an existing id these rules refuse and
  inserting it again, also inside one transaction, counts as creating a new id and is refused: replace such an
  entry with `update`. In 6.2.5 an id with `/` or `\` was written to a sub-path and read back as a corrupt entry,
  and on Windows an id with `:` was accepted and then failed every flush. If a flush meets an id the file system
  cannot name, it fails with an error naming the table and the id and writes nothing; if a start-up recovery meets
  one in a journal record (a record copied from Linux to Windows, for example), it refuses that plugin's JSON data
  with an error naming the record, the table and the id, changes no file, and is handled like a damaged record
  (see the next item).
- **A damaged journal record.** If a committed journal record is found damaged at start-up, that plugin's JSON
  data stays closed: its data operations fail with an error naming the file, until the server operator moves the
  file out of the journal folder. The framework never deletes, skips or applies the damaged record. Other plugins
  are not affected. A module that obtains its data operators while it loads, as every shipped module does, is
  refused at that start, so after moving the file aside, restart the server. Only a plugin that first requests
  its data after it has loaded runs recovery again at its next request, without a restart.

**Plain writes.** A JSON write outside a transaction is still held in memory and written at the next background
flush; it may now wait for a transaction's table lock. The flush is now a per-plugin checkpoint: it writes only
the entries changed since the previous checkpoint, crash-atomically as one set across the plugin's tables, and
then empties the journal. If a flush fails, the changed entries stay pending and the next flush writes them again;
the failure is thrown out of the scheduled flush task and appears in the server log.

**What a module author may notice.**

- A plain JSON write can wait for a transaction's table lock, for at most 5 seconds.
- A transaction can fail with a lock-wait or deadlock error and roll back. The framework does not retry it.
  Catching the error inside the transaction does not save it: the commit rolls back and throws
  `UnexpectedRollbackException`.
- A commit that changed something writes and forces one journal record before it returns, so it waits for the
  disk on the committing thread, which is often the server's main thread.
- A read inside `DataOperator#transaction(...)` can wait for another transaction's table lock, for at most 5
  seconds, as a 6.2.5 `transaction(...)` callback waited for its operator's lock.
- In a `@Transactional` method, read-modify-write code uses `updateIf` or `REPEATABLE_READ` (see above); at the
  default isolation it can lose an update. Inside `DataOperator#transaction(...)` it cannot.
- A `REQUIRES_NEW` or `NOT_SUPPORTED` method on the same thread does not wait for its caller's table locks (see
  above).
- A joined `@Transactional` method's accepted failure marks the whole transaction rollback-only (see above and the
  linked subsection).
- A UUID string always works as an id. Lowercase letters, digits, `-` and `_` are safe characters, but an id made
  of them is still refused if it is a Windows device name (`con`, `nul`, `aux`, `prn`, `com1` to `com9`, `lpt1` to
  `lpt9`, with or without an extension), on every operating system. Do not key entities by player names: `Con` and
  `Nul` are valid player names. On Windows, ids containing `<`, `>`, `:`, `"`, `|`, `?` or `*` are refused at
  insert. An existing id these rules refuse keeps working for reads, `update` and delete, but deleting it and
  inserting it again is refused; replace such an entry with `update`. Two ids that differ only in letter case
  name the same file on a case-insensitive file system (Windows, and macOS by default) and overwrite each other
  there.
- Map each `@Table` value to one entity class. Two entity classes of one plugin that declare the same `@Table`
  value get two separate caches and two table locks that do not exclude each other, as in 6.2.5, so their writes
  are not ordered against each other and the guarantees above do not hold between them.
- A hidden `.ultitools-journal` folder sits beside each plugin's JSON tables. In a server it is
  `plugins/UltiTools/data/<plugin name>/.ultitools-journal`; for a plain Bukkit plugin that uses the External
  Plugin API it is `<that plugin's data folder>/data/.ultitools-journal`; for an operator built directly over a
  table folder it is `<table folder>.ultitools-journal`, a sibling of that folder. A module that lists a plugin's
  data folder must skip it.

**Durability by platform.**

- "Durable once `commit` returns" holds fully on Linux. On Windows and macOS it holds against a server crash, but
  a power loss or operating-system crash can lose the last whole commits: Windows does not force a rename or its
  directory to disk, and macOS's file force is not a full flush to the platter. A lost commit is lost whole,
  never half.
- On Windows, a journal file move that another program (an antivirus scanner, an indexer, a backup tool) briefly
  holds is retried for at most 150 ms before the commit fails.
- A directory that cannot be forced to disk because of a real I/O error is logged at `WARNING` (`Could not force
  the directory <directory> to disk ...`). A commit made while that happens is committed and survives a server
  crash; it survives a power loss only once the next flush has completed. A flush or a start-up recovery that
  meets the failure fails and is repeated, and the journal is not emptied meanwhile.

**For server operators.**

- Run one server process per data folder. Two servers must never use the same `plugins` data folder at the same
  time.
- Back up the `.ultitools-journal` folder together with the table folders, or back up with the server stopped.
  A copy such as `cp dir/*` skips folders whose name starts with a dot.
- Never delete `.ultitools-journal`, not even after a crash: it holds commits that are not yet in the table files.
- After a crash, start the server once before you edit any JSON data file by hand: the start replays the journal
  and would overwrite the edit.
- A damaged newest journal record after a power cut usually means the disk or its controller did not honour
  flush requests. The plugin's JSON data stays closed until you move the named file out of the journal folder
  and restart the server.
- One entity file that keeps failing to move (for example, a file another program keeps locked) stops every flush
  of that plugin from emptying the journal: records accumulate, start-up recovery takes longer, and each failed
  flush is reported in the server log. Journal deletions are not retried on Windows; a held file fails that flush
  and the next flush tries again.
- Known limitation: a file system that lets the server open a directory but refuses to force it makes every flush
  of the plugin fail; the journal grows and, after a restart, the plugin's data stays closed until recovery can
  succeed. No common setup is known to do this (ext4, xfs, NFS, NTFS and APFS are fine).
- A JSON data folder whose ids contain `<`, `>`, `:`, `"`, `|`, `?` or `*` (UltiRemoteBag's edit claims use `:`)
  cannot be copied from Linux to Windows: Windows cannot create those files. Data whose ids differ only in letter
  case cannot be moved to Windows or to a default macOS volume either: the files collide. If a journal record
  holding such an id reaches a Windows server, start-up keeps that plugin's JSON data closed and names the record:
  apply it on the Linux server that wrote it, or move it out of the journal folder and lose its changes.

中文补充：JSON 事务（6.3.0）——暂存、原子、持久。在 `datasource.type: json` 下，`DataOperator#transaction(...)` 的保证变得更强，
`@Transactional` 也像在 SQLite、MySQL 上一样生效。事务调用不会被拒绝：6.2.5 中调用 JSON `transaction(...)` 的模块无需修改；特别是，读写同一张表的两个 `transaction(...)` 回调
仍然一个接一个地运行，与 6.2.5 相同（见下文“`transaction(...)` 在第一次读取时锁表”）。
这是 6.3.0 中不设两步迁移期的改动（维护者 2026-10-18 决定）。本节中的“表”指一个插件的一种实体类型，即一个 `@Table`、一个存放
`<id>.json` 文件的文件夹。

**6.2.5 的做法。**

- JSON 的 `DataOperator#transaction(...)` 持有该操作器的锁运行回调，失败时恢复回调之前对这一个操作器所做的副本。回调的写入直接进入
  实时数据，其他线程在回调期间就能读到，同一插件的两张表也不会一起回滚。嵌套的 `transaction(...)` 失败时只恢复内层开始时的副本：
  外层之前的写入保留，外层照常完成。
- `@Transactional` 在任何后端都不起作用（见 [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md) 中的
  `@Transactional` 记录）。
- 普通写入在下一次后台刷新时落盘（每 `datasource.flushRate` 秒，默认 10 秒）。刷新原地逐个重写每个缓存的实体文件，没有临时文件，
  也不强制刷盘，所以刷新期间崩溃可能留下写了一半的文件，或只更新了其中一部分。

**6.3.0 的做法。** 对由 `DataOperator#transaction(...)` 或 `@Transactional` 开启的事务：

- **暂存写入。** 事务内的写入先暂存给该事务，事务自己的读取能看到；其他线程只看到已提交的数据。提交一次性应用暂存的写入，回滚则
  丢弃它们，且不影响任何已提交的内容。
- **同一瞬间可见。** 其他线程在同一瞬间看到一次提交对该插件所有表的改动：一次读取要么看到该提交的全部改动，要么一个也看不到。读取可能
  在提交使改动可见的那一小段纯内存步骤里短暂停顿（无磁盘访问，无模块代码）。
- **跨表、跨崩溃的原子性。** 覆盖同一插件多张表的事务作为一个整体提交，崩溃之后仍然全有或全无。
- **`commit` 返回即持久。** `commit` 返回之前，记录该事务全部改动的日志记录已写入并强制落盘，下次启动时重放。什么都没改的事务不写任何
  东西，也不强制任何落盘。Windows 和 macOS 上的限定条件见下文“按平台的持久性”。
- **表写锁。** 事务内对某张表的第一次写入会锁住该表，其他写入者要等到事务结束，所以别人不会覆盖事务未提交的改动，也不会插入事务的两次
  写入之间。默认隔离级别的 `@Transactional` 事务中的读取从不等待表锁。
- **`transaction(...)` 在第一次读取时锁表。** 由 `DataOperator#transaction(...)`（两个重载都是）开启的事务，在第一次读取或写入
  某张表时就锁住它（与 `REPEATABLE_READ` 相同），直到事务结束。因此读写同一张表的两个 `transaction(...)` 回调一个接一个地运行，
  与 6.2.5 相同（6.2.5 中每个回调持有其操作器的锁），其中的“先读后写”不会丢失更新。代价也与 6.2.5 相同：`transaction(...)` 中的
  读取可能等待持有该表的另一个事务，最多 5 秒（与写入一样有上限，见下；6.2.5 的锁等待没有上限）。加入活动事务的 `transaction(...)`
  （在 `@Transactional` 方法或外层 `transaction(...)` 中调用）沿用该事务的隔离级别，与 Spring 中加入现有事务的作用域相同：在默认
  隔离级别的 `@Transactional` 方法中，它的读取不加表锁。默认隔离级别的 `@Transactional` 不在第一次读取时锁表。
- **在 `@Transactional` 中，“先读后写”需要 `updateIf` 或更严格的隔离级别。** 默认隔离级别下，两个 `@Transactional` 事务可以读到同一个值，后者再按读到的旧值算出新值写入：这种
  丢失更新不会被阻止。凭读到的值计算写入的代码应使用 `DataOperator#updateIf`（检查与写入在表锁下一起完成），或
  `@Transactional(isolation = Isolation.REPEATABLE_READ)` / `Isolation.SERIALIZABLE`，它们像 `DataOperator#transaction(...)` 一样在第一次读取时就锁表。
- **有上限的等待。** 写入（或在第一次读取时锁表的读取）遇到被锁的表，最多等待 5 秒，或者等到事务的 `@Transactional(timeout = ...)` 截止时间（如果更早），然后抛出
  `DataAccessException`，消息以 `JSON write failed with a lock-wait timeout` 开头（等待中的读取也是这段文字），该调用不生效。若这次等待会形成环（两个事务互相等待），
  闭环的那次调用立即失败，消息以 `JSON write failed with a deadlock` 开头。这两种失败都会在抛出异常之前把所在事务标记为只能回滚，
  因此捕获该异常并不能挽救事务：最外层提交会回滚该事务写入的全部内容，并抛出 `UnexpectedRollbackException`。该事务持有的表锁保留到它结束为止。
  事务外的普通写入遵循同一等待规则；这样的写入失败时只是失败。
- **只读。** `readOnly = true` 拒绝写入。这种拒绝不会标记事务：捕获它后事务仍可提交，与 SQLite、MySQL 上一条失败的语句相同。
- **超时。** `@Transactional(timeout = ...)` 设定截止时间。事务内的每个数据操作都先检查它；截止时间过后开始的操作抛出
  `DataAccessException`，消息以 `JSON transaction timeout` 开头。该失败在抛出之前把事务标记为只能回滚（与 Spring 在事务截止时间已过时的做法相同），
  因此捕获它并不能挽救事务：最外层提交会回滚并抛出 `UnexpectedRollbackException`。
- **传播行为与 SQLite、MySQL 相同。** `REQUIRES_NEW` 与 `NOT_SUPPORTED` 挂起活动事务（连同其暂存的写入和表锁），结束后恢复。唯一的差别：
  这类方法与被它挂起的调用方在同一线程上运行，而表锁归线程所有，所以它不会等待调用方持有的锁；若它写入调用方已暂存的同一个键，最终保留
  的是调用方稍后提交的值。（在 JDBC 后端，内层调用会等待自己调用方的行锁，直到锁等待超时。）
- **加入现有事务的方法。** 在 SQLite、MySQL 和 JSON 上一致：加入活动事务的 `@Transactional` 方法（`REQUIRED`，或在事务中调用的
  `SUPPORTS`、`MANDATORY`）抛出其 `rollbackFor`/`noRollbackFor` 规则认可的异常时，即使调用方捕获了该异常，整个事务也被标记为仅回滚，
  最外层提交随后回滚并抛出 `UnexpectedRollbackException`；规则不认可的异常不会使事务无法提交。详见下一小节。
- **钩子。** `onCreate`、`onUpdate`、`onDelete` 在调用发生时触发，而不是在提交时。
- **实体 id 即文件名。** 每个实体存为 `<id>.json`。`insert` 创建新 id 时，若该 id 符合下列任一情况，就抛出指明该 id 的
  `DataAccessException`（`DATA_ENTITY_INVALID`）予以拒绝，事务内外都一样：
  - 为空串，或含 `/`、`\`、NUL 或其他控制字符（U+0000 至 U+001F、U+007F）；
  - 是 `.` 或 `..`，或以点或空格结尾；
  - 是 Windows 设备名（`CON`、`PRN`、`AUX`、`NUL`、`COM1` 至 `COM9`、`LPT1` 至 `LPT9`，以及 `COM` 或 `LPT` 后跟上标
    `¹`、`²` 或 `³`，不分大小写），无论带不带扩展名：`con.txt` 也会被拒绝，因为检查的是第一个点之前的部分；
  - 使文件名 `<id>.json` 的 UTF-8 长度超过 255 字节；
  - 指向服务器自身文件系统无法命名的文件，或其路径规则会把它解读为表文件夹中 `<id>.json` 以外的文件。在 Windows 上即含 `<`、`>`、
    `:`、`"`、`|`、`?` 或 `*` 的 id，包括以一个字母加冒号开头的 id（如 `c:foo` 或 `p:<uuid>`）：Windows 会把它当作盘符前缀，
    文件会变成另一个 id 的文件或落到数据文件夹之外。Linux 接受这些字符，所以 UltiRemoteBag 的 `<玩家 uuid>:<页码>` id 在 Linux 上
    照常可用。

  除最后一条由文件系统自己决定外，其余规则在所有操作系统上都一样。被拒绝的插入不会把事务标记为只能回滚。插入一个已有条目的 id
  不做任何改动，也永远不会被拒绝；已存在的文件也绝不会因这些规则被改名或重写：它照常加载，对该 id 的读取、更新和删除照常可用。删除一个
  这些规则会拒绝的已有 id 后再插入它（在同一个事务中也一样）算作创建新 id，会被拒绝：要替换这样的条目，请用 `update`。6.2.5 会把
  含 `/` 或 `\` 的 id 写进子路径，读回时成为损坏的条目；在 Windows 上含 `:` 的 id 会被接受，之后每次刷新都失败。刷新若遇到文件系统
  无法命名的 id，会以指明表和 id 的错误失败，且不写入任何东西；启动恢复若在日志记录中遇到这样的 id（例如从 Linux 复制到 Windows 的
  记录），会以指明该记录、表和 id 的错误拒绝打开该插件的 JSON 数据，不改动任何文件，处理方式与损坏的记录相同（见下一条）。
- **日志记录损坏。** 启动时若发现已提交的日志记录损坏，该插件的 JSON 数据保持关闭：数据操作会失败，错误信息指明该文件，直到服主把该文件
  移出日志文件夹。框架不会删除、跳过或应用损坏的记录，其他插件不受影响。在加载时就获取数据操作器的模块（所有官方模块都是如此）
  会在那次启动时被拒绝加载，所以把文件移走后需要重启服务器；只有在加载完成之后才首次请求数据的插件，会在下一次请求时再次执行恢复，
  无需重启。

**普通写入。** 事务外的 JSON 写入仍先放在内存里、在下一次后台刷新时写盘，现在可能要等待事务的表锁。后台刷新现在是按插件进行的检查点：
只写上次检查点之后改动的条目，作为跨该插件所有表的一个整体崩溃原子地写入，然后清空日志。刷新失败时，改动的条目保持待写状态，下次刷新再写；
失败会从计划的刷新任务中抛出，并出现在服务器日志里。

**模块作者可能注意到：**

- 普通 JSON 写入可能等待事务的表锁，最多 5 秒。
- 事务可能因锁等待超时或死锁而失败并回滚，框架不会重试。在事务内捕获该错误并不能挽救事务：提交会回滚并抛出
  `UnexpectedRollbackException`。
- 改动了数据的提交会在返回前写入并强制落盘一条日志记录，即在提交线程（常常是服务器主线程）上等待磁盘。
- `DataOperator#transaction(...)` 中的读取可能等待另一个事务的表锁，最多 5 秒，与 6.2.5 中 `transaction(...)` 回调等待其操作器的锁相同。
- 在 `@Transactional` 方法中，“先读后写”的代码使用 `updateIf` 或 `REPEATABLE_READ`（见上）；默认隔离级别下它可能丢失更新。在
  `DataOperator#transaction(...)` 中不会。
- 同一线程上的 `REQUIRES_NEW` 或 `NOT_SUPPORTED` 方法不会等待其调用方的表锁（见上）。
- 加入方方法被规则认可的失败会把整个事务标记为仅回滚（见上与下一小节）。
- UUID 字符串作为 id 总是可用。小写字母、数字、`-` 和 `_` 是安全的字符，但由它们组成的 id 若是 Windows 设备名（`con`、`nul`、`aux`、
  `prn`、`com1` 至 `com9`、`lpt1` 至 `lpt9`，无论带不带扩展名），在所有操作系统上仍会被拒绝。不要用玩家名作为实体 id：`Con` 和
  `Nul` 都是合法的玩家名。在 Windows 上，含 `<`、`>`、`:`、`"`、`|`、`?` 或 `*` 的 id 会在插入时被拒绝。这些规则会拒绝的已有 id
  仍可读取、`update` 和删除，但删除后再插入会被拒绝；要替换这样的条目，请用 `update`。只有大小写不同的两个 id，在不区分
  大小写的文件系统（Windows，以及默认设置的 macOS）上指向同一个文件，会互相覆盖。
- 一个 `@Table` 值只对应一个实体类。同一插件的两个实体类若声明了相同的 `@Table` 值，会得到两份独立的缓存和两把互不排斥的表锁
  （与 6.2.5 相同），它们的写入彼此没有先后保证，上述各项保证在它们之间都不成立。
- 每个插件的 JSON 表旁边多了一个隐藏的 `.ultitools-journal` 文件夹。服务器中是 `plugins/UltiTools/data/<插件名>/.ultitools-journal`；
  使用外部插件 API 的普通 Bukkit 插件是 `<该插件数据文件夹>/data/.ultitools-journal`；直接基于表文件夹构造的操作器是与该文件夹并列的
  `<表文件夹>.ultitools-journal`。遍历插件数据文件夹的模块必须跳过它。

**按平台的持久性。**

- “`commit` 返回即持久”在 Linux 上完全成立。在 Windows 和 macOS 上，它能抵御服务器崩溃，但断电或操作系统崩溃可能丢失最近的几次完整提交：
  Windows 不会强制重命名及其所在目录落盘，macOS 的文件强制刷盘并不是真正刷到盘片。丢失的提交是整笔丢失，不会只丢一半。
- 在 Windows 上，被其他程序（杀毒软件、索引器、备份工具）短暂占用的日志文件移动，最多重试 150 毫秒，之后提交失败。
- 因真实 I/O 错误而无法强制落盘的目录会以 `WARNING` 记录（`Could not force the directory <目录> to disk ...`）。此时完成的提交已经提交，
  能抵御服务器崩溃，要等下一次刷新完成后才能抵御断电；遇到该错误的刷新或启动恢复会失败并重试，期间日志不会被清空。

**服主须知：**

- 一个数据文件夹只运行一个服务器进程：两个服务器绝不能同时使用同一个 `plugins` 数据文件夹。
- 备份时把 `.ultitools-journal` 文件夹与各表文件夹一起备份，或者在服务器停止时备份。`cp dir/*` 这类复制会跳过以点开头的文件夹。
- 永远不要删除 `.ultitools-journal`，崩溃之后也不要：里面保存着尚未写入表文件的提交。
- 崩溃之后，手工编辑任何 JSON 数据文件之前先启动一次服务器：启动会重放日志，并会覆盖你的编辑。
- 断电后发现最新的日志记录损坏，通常说明磁盘或其控制器没有遵守刷盘请求。该插件的 JSON 数据会保持关闭，直到你把指明的文件移出日志文件夹
  并重启服务器。
- 某个实体文件持续无法移动（例如被其他程序一直锁着）会使该插件的每次刷新都无法清空日志：记录不断累积，启动恢复变慢，每次失败的刷新都会出现在
  服务器日志里。日志的删除在 Windows 上不会重试，被占用的文件会使那次刷新失败，下一次刷新再试。
- 已知限制：若某文件系统允许服务器打开目录却拒绝强制其落盘，该插件的每次刷新都会失败，日志不断增长，重启后在恢复成功之前该插件的数据保持关闭。
  没有已知的常见环境如此（ext4、xfs、NFS、NTFS、APFS 都没问题）。
- id 中含 `<`、`>`、`:`、`"`、`|`、`?` 或 `*` 的 JSON 数据文件夹（UltiRemoteBag 的编辑占用记录使用 `:`）无法从 Linux 复制到
  Windows：Windows 无法创建这些文件。id 只有大小写不同的数据也无法迁移到 Windows 或默认设置的 macOS 卷：文件会互相冲突。若含这类 id
  的日志记录到了 Windows 服务器上，启动时该插件的 JSON 数据会保持关闭，错误信息指明该记录：请在写下它的 Linux 服务器上应用它，或把它
  移出日志文件夹并放弃其中的改动。

### A failing joined `@Transactional` method rolls back the whole transaction (6.3.0) — changed without a migration period

This is "moving from silent degradation to failure", listed above among the changes that need a
migration period, made in 6.3.0 without the two-step period by the maintainer's decision of
2026-10-19.

**What changed.** A `@Transactional` method that joins a transaction already active on the thread —
`REQUIRED`, the default, or `SUPPORTS` or `MANDATORY` called inside a transaction — and throws an
exception its own `rollbackFor`/`noRollbackFor` rules accept now marks the whole transaction
rollback-only, even when the calling method catches the exception. The outermost commit then rolls
back every write of the transaction, the caller's included, and throws
`com.ultikits.ultitools.exceptions.UnexpectedRollbackException`. This holds on SQLite, MySQL and JSON
alike, and it is the same rule a failed nested `DataOperator#transaction(...)` scope follows in 6.3.0.
In detail:

- An exception the joined method's rules do not accept — a checked exception under the default rules,
  or a type listed in its `noRollbackFor` — leaves the transaction committable, and the outer method
  commits both its own writes and the joined method's.
- A joined failure that the outer method does not catch rolls the transaction back as before, and the
  caller receives the original exception, not `UnexpectedRollbackException`.
- When, after such a mark, the outer method throws an exception its own rules commit despite (for
  example one listed in its `noRollbackFor`), the caller receives `UnexpectedRollbackException` with
  that exception attached as suppressed.
- A joined method's `isolation`, `readOnly` and `timeout` are not applied; they belong to the
  transaction that began it.

**Before.** `@Transactional` was not wired in 6.2.5 at all (see the `Propagation.NESTED` record in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md)). The 6.3.0 snapshots published
earlier in this cycle ran a joined method without a scope of its own, so a failure the caller caught
left the transaction committable and the outer method committed the failed method's writes. Only a
module compiled against such a snapshot that catches a joined method's exception and expects its
transaction to commit is affected; no first-party module uses `@Transactional`.

**Why without a migration period.** The policy above lists moving from silent degradation to failure
among the changes that need one. The maintainer decided on 2026-10-19 to adopt Spring's default
(`AbstractPlatformTransactionManager`, `globalRollbackOnParticipationFailure = true`) in 6.3.0, instead
of keeping the old behaviour or deferring the change past 6.3.0.

**What a module author does instead.**

- Let the failure propagate, so the transaction rolls back with the original exception.
- Put work whose failure the caller survives in a `REQUIRES_NEW` method, which commits or rolls back
  alone.
- Or list the exception in the joined method's `noRollbackFor` when its writes are meant to stay.

Code that builds a `TransactionInterceptor` over its own `TransactionManager`: the interceptor now
calls `begin()`, then `commit()` or `rollback()`, around a joined method, so `begin()` on an active
transaction must join it, `rollback()` at a nested depth must mark the transaction rollback-only
without ending it, and the outermost `commit()` of a marked transaction must roll back and throw
`UnexpectedRollbackException`, as both built-in managers do. No signature changed.

中文补充：加入现有事务的 `@Transactional` 方法（默认的 `REQUIRED`，或在事务中调用的 `SUPPORTS`、`MANDATORY`）
抛出其自身 `rollbackFor`/`noRollbackFor` 规则认可的异常时，整个事务被标记为仅回滚，即使调用方捕获了该异常也是如此；
最外层提交随后回滚该事务的全部写入（包括调用方自己的写入），并抛出 `UnexpectedRollbackException`。SQLite、MySQL 与
JSON 行为一致，与 6.3.0 中失败的嵌套 `DataOperator#transaction(...)` 作用域遵循同一规则。规则不认可的异常（默认规则下的
受检异常，或列在 `noRollbackFor` 中的类型）不会标记事务，外层方法照常提交双方的写入；外层方法未捕获的失败照旧回滚，调用方
收到原来的异常；标记之后，外层方法若抛出其自身规则仍要求提交的异常，调用方收到 `UnexpectedRollbackException`，该异常作为
suppressed 附在其上；加入现有事务的方法不应用自己的 `isolation`、`readOnly` 和 `timeout`。6.2.5 中 `@Transactional`
完全没有接线；本周期较早发布的 6.3.0 快照中，被捕获的加入方失败不会标记事务，外层方法会提交失败方法的写入。只有针对这类快照
编译、捕获加入方异常并期望事务提交的模块受影响；没有第一方模块使用 `@Transactional`。维护者于 2026-10-19 决定在 6.3.0
直接采用 Spring 的默认规则（`AbstractPlatformTransactionManager`，`globalRollbackOnParticipationFailure = true`），
不设迁移期。模块作者的做法：让失败向外传播；把调用方需要承受其失败的工作放进 `REQUIRES_NEW` 方法，它单独提交或回滚；或者在
加入方方法的 `noRollbackFor` 中列出该异常，以保留其写入。自行以 `TransactionManager` 构造 `TransactionInterceptor` 的代码：
拦截器现在会在加入方方法前后调用 `begin()`，再调用 `commit()` 或 `rollback()`，因此活动事务上的 `begin()` 必须加入该事务，
嵌套层的 `rollback()` 必须只把事务标记为仅回滚而不结束它，被标记事务的最外层 `commit()` 必须回滚并抛出
`UnexpectedRollbackException`，两个内置管理器均如此。没有签名变化。

### A failed nested `DataOperator#transaction(...)` marks the whole transaction rollback-only (6.3.0)

This is the same rule as the joined `@Transactional` method above, for a nested `DataOperator#transaction(...)`
scope. No earlier text announced this change from 6.2.5, and it takes effect in 6.3.0 without a migration period.

**What changed.**

- **6.2.5 on SQLite and MySQL.** An inner `transaction(...)` whose callback failed rolled the whole transaction
  back and ended it at once, whatever its depth: the connection was rolled back, auto-commit was restored and the
  thread's transaction was gone. If the outer scope caught the failure and went on, its later writes ran without
  a transaction. The outer commit then found no transaction, logged the WARNING `Commit called but no active
  transaction` and returned normally. (The JSON backend's 6.2.5 behaviour is described in the JSON transactions
  subsection above.)
- **6.3.0 on SQLite, MySQL and JSON alike.** The inner failure marks the transaction rollback-only and does not
  end it. The outer scope's later writes stay inside the transaction. The outer commit rolls everything back and
  throws `com.ultikits.ultitools.exceptions.UnexpectedRollbackException`.

**What a module does.** Let the inner failure propagate, so the transaction rolls back with the original
exception, or do the work whose failure the caller survives outside the outer transaction (for example in a
`REQUIRES_NEW` method).

中文补充：嵌套的 `DataOperator#transaction(...)` 失败会把整个事务标记为仅回滚（6.3.0）。这与上一小节中加入现有事务的
`@Transactional` 方法遵循同一规则，只是对象是嵌套的 `DataOperator#transaction(...)` 作用域。此前没有文字公布过这一相对 6.2.5 的变化，
它在 6.3.0 中生效，不设迁移期。6.2.5 在 SQLite 和 MySQL 上：内层
`transaction(...)` 的回调失败时，无论嵌套多深，都会立即回滚整个事务并结束它（连接被回滚、自动提交被恢复、线程上的事务消失）；
若外层捕获该失败并继续，之后的写入就在没有事务的情况下执行，外层提交找不到事务，记录一条 WARNING
`Commit called but no active transaction` 后正常返回。（JSON 后端在 6.2.5 中的行为见上文 JSON 事务一节。）6.3.0 在 SQLite、
MySQL 和 JSON 上一致：内层失败只把事务标记为仅回滚，并不结束它；外层之后的写入仍留在事务内；外层提交回滚全部内容并抛出
`UnexpectedRollbackException`。模块的做法：让内层失败向外传播，使事务带着原异常回滚；或者把调用方需要承受其失败的工作放在外层
事务之外（例如放进 `REQUIRES_NEW` 方法）。

## Binary incompatibilities the removal list cannot cover

The removal list only covers changes where somebody knew they were changing an API. Both of its
preconditions — carrying `@Deprecated(forRemoval = true)` and having crossed one MINOR — require the
author to recognise the change as an API change in the first place. One class of change does not meet
that precondition: to its author it is not an API change at all, yet it alters the **JVM method
descriptor** of a public method. Such a change necessarily breaks binary compatibility while
possibly not breaking source compatibility at all, and therefore bypasses every process that assumes
somebody will notice.

A second, narrower class also cannot go on the removal list, for a different reason: the method is
not removed at all, so there is no eventual deletion event to annotate with `@Deprecated(forRemoval =
true)` and no descriptor change either. Adding `final` to a public method is exactly this — the
method's name, return type and parameter types are all unchanged, so the removal list's two
preconditions have no target, yet japicmp still reports it (`METHOD_NOW_FINAL`) because a subclass
that overrode the method can no longer be loaded at all. Unlike the two descriptor cases below, this
one is not invisible to its author — it is deliberate and recorded here in full, with its own
japicmp exclude entry — but the removal-list mechanism still cannot express it, because nothing was
removed for the annotation to describe.

This has happened three times, and all three are recorded here — a JVM-descriptor change in a MINOR,
the same in a PATCH, and a `final` addition in a MINOR (6.3.0) — **no release level
is exempt, and no failure mode is exempt either**:

### First occurrence: 6.1.1 → 6.2.0, a MINOR

When Spring was removed, the type of the context field in `UltiToolsPlugin` changed from
`AnnotationConfigApplicationContext` to `SimpleContainer`. The field carries `@Getter`, so the
**return type** of the Lombok-generated `getContext()` changed with it:

```
A module compiled against 6.0.6 records
  getContext:()Lorg/springframework/context/annotation/AnnotationConfigApplicationContext;
Frameworks from 6.2.0 onward provide
  getContext:()Lcom/ultikits/ultitools/context/SimpleContainer;
```

(The starting point is **6.2.0**, not 6.2.1. 6.2.0 was published to Maven Central but has no git tag
in the repository — **read the release list from `maven-metadata.xml`, not from `git tag`**.
Diagnosing this on 6.2.0 hits the same exception.)

The return type is part of the method descriptor, so to the JVM these are two different methods, and
an old JAR gets a `NoSuchMethodError` inside `registerSelf()`.

**On the source side the outcome depends on how the call is written**, which is exactly why it is
easy to miss. A call like UltiEconomy's `getContext().getBean(X.class)` never names the return type,
so the same source compiles against both versions. But if the source says
`AnnotationConfigApplicationContext ctx = plugin.getContext();`, passes the return value to a method
that accepts the old type, or overrides `getContext()`, then a recompile fails — `SimpleContainer`
has no inheritance relationship with the old type. The accurate statement about this class of change
is therefore "**necessarily breaks binary compatibility; whether it breaks source compatibility
depends on the caller**", not "only breaks binary compatibility".

Three lines of defence fail at once:

- Nothing is "removed", so this cannot be placed on the removal list;
- There is no target to annotate with `@Deprecated`, so `-Xlint:removal` never fires downstream;
- `PluginManager`'s version gate does not catch it either — it only tests
  `api-version > current framework version`, that is, the single direction of "the module requires a
  newer framework than the one installed". The old module's declared floor is satisfied, and it still
  fails.

### Second occurrence: 6.2.0 → 6.2.1, a PATCH

The previous case was a MINOR. The second happened in a **PATCH**, so "watching MINOR releases is
enough" does not hold.

`43f55ea refactor!: replace AbstractDataEntity with BaseDataEntity<String>` replaced the entity type,
and every public member whose signature mentions that type had its descriptor changed with it:

```
6.2.0  DataOperator.insert   (Lcom/ultikits/ultitools/abstracts/AbstractDataEntity;)V
6.2.1  DataOperator.insert   (Lcom/ultikits/ultitools/abstracts/data/BaseDataEntity;)V
```

**The complete list was computed per symbol, not written by hand** (it was written by hand three
times, and each version missed something). The method: unpack both framework JARs, run `javap -s`
over `com/ultikits/ultitools/**`, build a table of `(class, member name) → set of descriptors`, and
compare. **The set matters**, otherwise overloads overwrite each other — that is how `exist(T)` was
masked by `exist(WhereCondition[])` and missed for a round.

The result is **14 public members across 5 types**, with **zero removals and zero additions** in this
change; only descriptors moved:

| Type | Affected members |
|---|---|
| `interfaces.DataOperator` | `exist(T)` · `getById` · `insert(T)` · `update(T)` |
| `interfaces.Query` | `first()` |
| `…impl.data.AbstractRelationalDataOperator` | same four as `DataOperator` |
| `…impl.data.json.SimpleJsonDataOperator` | same four as `DataOperator` |
| `…impl.data.QueryImpl` | `first()` |

What downstream code actually calls statically are the **5 interface members** in the first two rows;
the other 9 are same-named mirrors on implementation classes. Note that `Query.first()` is a separate
entry: a module that only uses `.query()….first()` calls no `DataOperator` method at all and is still
affected.

Overloads that do not mention the entity type (`update(String, Object, Object)`,
`exist(WhereCondition[])`) kept their descriptors. `AbstractDataEntity` itself was not deleted at
the time this section was written, so this case could not go on the removal list yet — that has
since changed: `AbstractDataEntity` was deleted in 6.3.0 by plan 07-13 (GEN-04), and the descriptor
history recorded above (6.2.0 → 6.2.1) remains accurate for servers running those earlier versions.
See [the 6.3.0 removal record](compatibility/records/6.3.0.md#recorded-instance-abstractdataentity-is-gone-basedataentity-now-owns-its-id-field-directly-gen-04-630)
for what an un-recompiled JAR referencing `AbstractDataEntity` now sees.

**Descriptor changes are inherently bidirectional**, and both instances are. When a symbol has the
same name and a different descriptor across two versions, then whichever side you compile against,
the other side does not have it. The old JAR fails on the new framework (looking for
`(AbstractDataEntity)`, which no longer exists) and the new JAR fails on the old framework (looking
for `(BaseDataEntity)`, which does not exist yet). The same source, recompiled with only the pin
changed, produces two artifacts that each run on one side only. The first instance (`getContext()`)
behaves the same way; it is just that only the "old JAR meets new framework" direction was actually
triggered at the time.

**The second direction carries an extra layer: it is let through before it fails.** All 15 official
modules raised the `pom.xml` pin to 6.2.1, while none changed `api-version` in `plugin.yml`, which
remained `620`. The artifact records 6.2.1 descriptors but declares a 6.2.0 floor, and the framework
only sees the latter. The result: a server running 6.2.0 **loads the module successfully**, then hits
`NoSuchMethodError` on the first data read or write. 11 modules were affected (the remaining 4 do not
touch the ORM, which serves as a negative control).

Java resolves lazily, so "it starts up" is not evidence: a server owner who never touches the data
path may never see the problem.

**The same commit also contains an exact counter-example worth remembering.** It changed the generic
bound of `UltiToolsPlugin.getDataOperator` from `AbstractDataEntity` to `BaseDataEntity<String>`, yet
the descriptors are **identical** across both versions — the bound is erased, and `T` was already
`Class` / `DataOperator` in the descriptor:

```
6.2.0  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
6.2.1  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
```

This mirrors the previous situation: **changing a generic bound breaks source compatibility without
breaking binary compatibility, while changing a return type or parameter type breaks binary
compatibility without necessarily breaking source compatibility.** Neither involves a removal, so
neither goes through the removal list.

**This holds only for the `getDataOperator` call site, and should not be generalized to a whole
module.** Once you hold a `DataOperator` you will almost certainly call `insert` / `update` /
`exist` / `getById`, and those four did change descriptors. So a module that uses the ORM **breaks in
both directions**:

| Pin at build time | Running on 6.2.0 | Running on 6.2.1+ |
|---|---|---|
| 6.2.0 | Works | `NoSuchMethodError` (looks for `(AbstractDataEntity)`, no longer present) |
| 6.2.1 | `NoSuchMethodError` (looks for `(BaseDataEntity)`, not yet present) | Works |

The measurement used one module's identical source, recompiled with only the pin changed: the
artifact built against 6.2.0 is missing 3 symbols on 6.2.1 and 0 on 6.2.0; the artifact built against
6.2.1 is the reverse, missing 3 on 6.2.0 and 0 on 6.2.1. It is symmetric, and neither side crosses
over. "Runs but does not compile" describes the `getDataOperator` line only.

### Third occurrence: 6.2.5 → 6.3.0, a MINOR

`UltiToolsPlugin.unregisterSelf()` and `UltiToolsPlugin.reloadSelf()` both gain `final`. Each keeps
its exact name, return type (`void`) and parameter list (none) — there is no descriptor change here,
unlike the first two occurrences — but japicmp still reports both as binary-incompatible
(`METHOD_NOW_FINAL`), because a subclass compiled against 6.2.5 that overrides either method can no
longer be loaded on 6.3.0 at all.

**Same-release exception, clause 1 (proven non-functional).** Both overrides were already the
declared extension point for module unload and reload work, and both were measured broken on every
released version: **15 of 15** module `unregisterSelf()` overrides never call `super.unregisterSelf()`,
so the framework's own `CommandManager.unregisterAll` / `ListenerManager.unregisterAll` step never
runs on module unload — the module's commands keep resolving into a closed container after the
module is gone. **9 of 11** module `reloadSelf()` overrides never call `super.reloadSelf()`, so the
framework's own config-reload / language-refresh / `@ConditionalOnConfig`-drift-report steps never
run on a `/ul reload` of that module. Real-machine instances: Phase 13's UltiLogin#13 and
UltiWorlds#10. Making both methods `final` — with `onUnregister()` and `onReload()` as the new,
unconditional-body-guaranteed hooks — is not a new restriction on working behaviour; it is the fix
for a declared contract that never ran.

**Why this could not simply be deprecated first.** `unregisterSelf()`/`reloadSelf()` are declared on
the public `IPlugin` interface and were never going to be removed — only sealed against override.
There is no future release where deprecation would mature into removal, so the ordinary one-MINOR
warning window has nothing to count down to; the `final` keyword is the entire change, applied once.

**What an un-recompiled downstream JAR sees.** A module JAR compiled against 6.2.5 that overrides
either method fails when the class is loaded — before the module's `registerSelf()` ever runs —
with `IncompatibleClassChangeError: class <ModuleClass> overrides final method
com.ultikits.ultitools.abstracts.UltiToolsPlugin.unregisterSelf()V` (or `reloadSelf()V`). This is a
loud, named failure at load time, not a silent no-op and not a delayed `NoSuchMethodError` on first
use, unlike the first two occurrences in this section — the class naming its own offending method is
exactly what an `IncompatibleClassChangeError` for an overridden final method reports.

**What a server operator sees.** The framework refuses such a module with one SEVERE line, with no
stack trace at SEVERE (the trace is logged at FINE), naming the module JAR and — only when its
`plugin.yml` declares them — its `version` and `api-version`, saying it was built against an older
UltiTools-API version and is not compatible with this one, and ending with the JVM's original
message. Every other module still loads, except a module that names it in
`@PluginDependency(depends = ...)`, which is refused along with it, as for any missing hard
dependency. `plugin.yml`'s `depend:` lists Bukkit server plugins and is not the path by which one
module's refusal reaches another; a module that names it only under `softDepends`, `loadBefore` or
`loadAfter` still loads. The fix is to replace that JAR with a release of the module built for the
current UltiTools-API version.

The opposite mismatch is reported the other way round. When the same kind of failure comes from a
module whose declared `api-version` is above the installed framework's — a module built for a newer
UltiTools-API, installed without upgrading the framework — the line says the module requires a newer
UltiTools-API, gives both API levels, and tells the operator to upgrade UltiTools-API (or install a
release of the module built for this version). When the module declares no `api-version`, or one
that is not an integer, the line keeps the older-module wording above.

**Migration guide for module authors.** The fix is a rename or a deletion — never a rewrite — with at
most a `super` call or a now-dead `lang` key to drop alongside it:

| Your current override | What to do |
|---|---|
| `unregisterSelf()` doing real cleanup work | Rename the override to `onUnregister()`. Remove every `super.unregisterSelf()` call from the renamed hook, keep the module's real cleanup work, and let the framework unregister commands and listeners after `onUnregister()` returns. Keeping the old superclass call would re-enter the hook recursively. |
| `unregisterSelf()` that is empty or log-only | Delete the override outright — the framework's own `onUnregister()` default body already does nothing, and the module's log line (if any) becomes dead weight once removed |
| `reloadSelf()` doing real reload work | Rename the override to `onReload()`, keep the body verbatim. Drop any `super.reloadSelf()` call inside it — it is no longer callable. The framework's own reload steps (configuration reload, config-bound timing refresh, language refresh, `@ConditionalOnConfig` drift report) always run before `onReload()`, whether or not the old override called `super`; the framework's per-module reload line is logged after `onReload()` returns, or a failure line if it throws |
| `reloadSelf()` that only logs "reloaded" or similar | Delete the override outright, and delete the now-dead module `lang` key that message used — the framework logs its own per-module reload line (D-03) |
| `reloadSelf()` that already called `super.reloadSelf()` (2 of 11 modules) | Rename to `onReload()` and drop the `super` call; no other change needed |

Two member-level japicmp excludes cover this occurrence: `UltiToolsPlugin#unregisterSelf()` and
`UltiToolsPlugin#reloadSelf()`, both member-level (the class itself is unchanged and still present),
appended to the end of `pom.xml`'s existing `<excludes>` list. See
[the 6.3.0 removal record](compatibility/records/6.3.0.md) for the full evidence and the
downstream-author paragraph in that file's own format.

### What this means for you

**Pinning low is not the same as being safe.**
[Module versioning](https://dev.ultikits.com/guide/advanced/module-versioning) states that compiling
against an older API will not produce a `NoSuchMethodError` by reaching something newer. That
statement still holds, but it only rules out one direction of cause. The reverse direction — the
framework changing its own descriptors — produces the same exception.

**There is also no free fix for this class of problem.** "Recompile and republish" is insufficient and
can be wrong: if the pin in `pom.xml` is still at 6.0.6, running the build again still generates the
*old* descriptors from 6.0.6 class files, and the artifact still fails on the new framework. A real
fix requires raising the compile dependency to the version that contains the new descriptors and
recompiling against it.

**Raising the pin does only half of the job, and it is the half nobody checks.** These are two
independent numbers and should not be treated as one:

| Number | Determines | Who checks it |
|---|---|---|
| The `UltiTools-API` version in `pom.xml` | Which version's descriptors your bytecode records | Nobody. It is `provided`, does not enter the JAR, and the framework cannot see it at runtime |
| `api-version` in `plugin.yml` | The declared runtime floor | `PluginManager.isUltiToolsVersionCompatible` refuses a module that declares a higher API level than the installed framework. 6.3.0 also reads it to refuse a config binding below `630`, to word a load failure, and in `/upm`, which installs only modules at `630` up to the framework's level. Of the two numbers, this is the only one that is checked |

So "the pin is the floor" is wrong: raising the pin does not raise the floor. An artifact compiled
against a new framework while still declaring an old `api-version` is **let through** by an old
server, and then fails on the first call into a new descriptor — the same `NoSuchMethodError`, in the
opposite direction. **Both numbers must move together.**

**This is not hypothetical.** The second instance above happened exactly this way: 15 official modules
raised the pin to 6.2.1 and left `api-version` at `620`, so 11 of them shipped artifacts declaring a
floor lower than their real requirement (all corrected to `621` on 2026-08-16). **No tool reported
anything**: builds were green, and the plugins worked on every server running 6.2.1 or later. Only a
server sitting exactly on 6.2.0 would load them successfully and then fail on the first data
operation.

To check this yourself, the question is **which symbols the artifact actually references**, not what
the POM says. Unpack your module JAR and the framework JAR for the version you declare in
`api-version`, export the `com/ultikits/ultitools/**` methods and descriptors your module references
with `javap -p -c`, and compare them against `javap -p -s` output from the framework JAR. A ready-made
script is linked from [issue #284](https://github.com/UltiKits/UltiTools-Reborn/issues/284).

**A mismatch does not automatically mean "`api-version` is too low". There are two causes, and the
fixes are opposite.** Look at which version's types the missing symbol mentions:

| The missing symbol references | What it indicates | Fix |
|---|---|---|
| A **new** type (such as `BaseDataEntity`) | The artifact is newer than the floor it declares | **Raise `api-version`**; leave the pin alone |
| An **old** type (such as `AbstractDataEntity`) | The artifact is older than the floor it declares; the pin has not kept up | **Raise the pin and recompile.** Raising `api-version` does not help and makes it worse |

The second case is the other side of the instance in this section: an artifact pinned to 6.2.0 while
declaring `api-version: 621` references `insert(AbstractDataEntity)`, and 6.2.1 does not have that
descriptor — no amount of raising the floor will make it appear. Determine which generation of symbol
is missing before deciding which number to move.

The complete fix is therefore: raise the pin, recompile, **raise `api-version` to the corresponding
API level as well**, and accept the consequences that follow.

What cannot span both sides is, precisely, **the artifact that statically calls the method**.
Descriptors are written into the call site at compile time, so one call site can only match one side.
Three options follow, in increasing cost:

1. **Accept the raised floor** (the default choice). Old servers stay on the old JAR; the new JAR
   serves the new framework only.
2. **Ship separate artifacts per framework range.** This means maintaining two release lines.
3. **Write a compatibility shim**: call reflectively (`getMethod("getContext").invoke(plugin)` to get
   an `Object`, then reflectively call `getBean`), or lazily load different adapter implementations
   per framework version. A reflective call site links statically against neither version's return
   type, so **a single artifact really can run on both sides**. The cost is that this path loses
   compile-time checking, errors surface only at runtime, and you will receive no compiler warning
   when the framework changes it again.

Option 3 is genuinely viable; do not assume it does not exist merely because the first two are listed
first. But it converts a problem detectable at compile time into one that only appears at runtime. It
is worth taking only when you must continue supporting old servers.

In other words, the guidance elsewhere in this document — that a lagging pin is a normal state and
does not need to be changed for its own sake — **does not apply here**. That guidance says not to move
the pin without a reason; a descriptor change is a reason.

As for how often to check: from 6.3.0 the framework's CI build (`mvn verify`, on every push and pull
request) runs the japicmp gate against the configured baseline release (6.2.5 for 6.3.0) and fails on any
binary-incompatible change to a public or protected member that is not excluded in `pom.xml`; a build made with
`mvn package` alone does not run it. Re-verify your
module whenever you raise its pin or its `api-version`.

- This document states above that a PATCH removes no public API. That promise covers *intentional*
  removals, because it relies on a person recognising an API change first. An unintentional
  descriptor change is by definition on no schedule, **so it can appear in a PATCH as well**. That
  sentence began as an inference; the second instance in this section (6.2.0 → 6.2.1) confirmed it —
  that was a PATCH. **"Watching MINOR releases is enough" does not hold.**
- With the japicmp gate wired up (6.3.0), binary compatibility against the baseline release is verified per
  method by a machine in every CI build, a PATCH included; a binary-incompatible change can pass only if it is
  explicitly excluded.

### A floor no linker enforces: config-bound `@Scheduled` and `@CmdCD` (6.3.0)

Everything above is about descriptors, where a missing symbol at least fails loudly with
`NoSuchMethodError`. New **annotation elements** fail silently instead. As of 6.3.0,
`@Scheduled(config = ..., periodKey = ..., delayKey = ...)` and `@CmdCD(config = ..., key = ...)`
read an interval or cooldown from a module config key (#531). A module compiled against 6.3.0 that
uses them still loads on an older framework. The JVM drops annotation elements that the running
annotation type does not declare. Measured: a class compiled with `@Sch(periodKey = "x")` and run
against an older `Sch` without `periodKey` reads back as `@Sch(period=-1L)`. Nothing is logged.
Against 6.2.x, a bound `@Scheduled` therefore runs **once** at load instead of on its interval,
and a bound `@CmdCD` enforces **no** cooldown.

So **a module that uses either binding must declare `api-version: 630`** in its `plugin.yml`. That
floor makes an older framework refuse the module at load, rather than run it with the wrong timing.
Raising the `pom.xml` pin alone does not do this, for the reason given above. So that the mistake
surfaces on the version you develop against, 6.3.0 itself refuses a module that uses a binding while
declaring a lower `api-version`, naming the module, the binding and the required floor. A bound
`@Scheduled` must also be synchronous: `async = true` together with a binding is refused at load,
because a reload can keep an async task's place in its cycle only by predicting when the server
dispatches async work (#535 tracks a design that observes it instead). The binding is
additive: existing literal usages (`@Scheduled(period = 6000)`, `@CmdCD(60)`) behave as before
and need no change.

**A bound field must not also carry a module `@Range`.** The binding's own range is the field's
range: 1 to `Integer.MAX_VALUE / 20` seconds for a period or delay, and 0 (no cooldown) to
`Integer.MAX_VALUE` for a cooldown. A panel write outside it is refused like a `@Range` violation.
An out-of-range value on `/ul reload` keeps the running value and logs a WARNING. A `@Range` on the
same field would instead make the configuration reload itself fail, which stops the rest of that
module's reload and reports it as failed (since #509, fixed in 6.3.0, a full `/ul reload` still continues with the
next module). A module that already had a `@Range` on a field it now binds should drop it; the binding's range takes
over.

### What this means for us

A human process cannot catch this class of change: it would require an author changing a field type to
realise that this alters the descriptor of a public method. What can catch it is a machine comparing
descriptors method by method, that is, the japicmp gate (issue #216). From 6.3.0 that gate
runs in the framework's CI build (`mvn verify`) against the configured baseline release and fails on any
binary-incompatible change to a public or protected member that is not excluded in `pom.xml`, so an unintentional
descriptor change can no longer pass CI unnoticed.

## Support matrix

| Item | Value |
|---|---|
| Server | Paper (plain Spigot is not supported — the code uses Adventure `Component` throughout) |
| Build JDK | 21 |
| Bytecode target | Java 8 (`-source`/`-target`, not `--release`) |
| `api-version` in `plugin.yml` | `1.19` (Bukkit API level, unrelated to the two rows above) |
| `api-version` in a module's `plugin.yml` | `630` for a module built for 6.3.0 (UltiTools API level, unrelated to Bukkit's field of the same name); all fifteen UltiKits modules declare `630` |
| Minimum server | Paper 1.19.2 build 163, the first build bundling SnakeYAML 1.32 (as of 6.3.0). On Paper 1.19.0, 1.19.1 and 1.19.2 builds before 163 the framework fails to enable with a `NoSuchMethodError` on `LoaderOptions.setProcessComments`; Paper 1.18.2 and older refuse `api-version: '1.19'` ("Unsupported API version") and do not load it |
| Server Java | The version the chosen Paper line requires: Java 17 for Paper 1.19.2 to 1.20.4, Java 21 from Paper 1.20.5. This is independent of the build JDK and the bytecode target above |
| Modules | Each of the fifteen UltiKits modules built for 6.3.0 states only the UltiTools-API version it supports: 6.3.0 or later (`api-version: 630`). The server rows of this table are the framework's own requirements |
| Known gap on Paper 1.19.2 to 1.20.4 | The panel's console mirror is not installed, and one WARNING at start-up says the panel will not mirror the server console; the log stream carries plugin lines only. Those builds relocate CraftBukkit's log forwarder (`ForwardLogHandler`), so the mirror cannot find it. This is measured, existing behaviour, documented here and not changed |

### Where runtime dependencies come from

The framework JAR bundles exactly two libraries: obliviate-invs (GUI) and UniversalScheduler
(scheduling). Everything else is not in the JAR and arrives by one of two routes:

| Delivery route | Version decided by | Examples |
|---|---|---|
| The `libraries:` block in `plugin.yml`, downloaded by Paper from coordinates | **This repository** | commons-dbutils, Java-WebSocket, ByteBuddy, JavaMail (`com.sun.mail:javax.mail`), HikariCP, XSeries |
| Shipped by the Paper server itself | **The Paper build the server owner installed** | Gson, MySQL Connector/J, protobuf, slf4j, the SQLite driver, native Adventure, log4j, the Maven resolver Paper uses internally for `libraries:` and its dependencies |

As of 6.3.0, Gson, MySQL Connector/J, protobuf and slf4j are no longer listed under `libraries:`. Paper
bundles all four on every build measured across the supported range (Paper 1.19.2 builds 163 and 307,
and the latest build of thirteen Paper versions from 1.19.4 to 1.21.11), and Paper's own copy always
loaded first, so those entries never took effect. The MySQL driver a server uses is therefore the one its Paper build ships,
and the protobuf version pin from issue #220 never took effect. `adventure-platform-bukkit` is no longer
delivered at all; the framework uses Paper's native Adventure (see the plan 18-30 entry under
[Same-release exceptions applied in 6.3.0](#same-release-exceptions-applied-in-630)).

**XSeries moved out of the bundled JAR in 6.3.0.** Through 6.2.5 it was shaded in — the sentence
above used to say "three libraries", XSeries among them. From 6.3.0 it is `provided` scope and
delivered through the `libraries:` route in the table instead, the same way HikariCP already
was. If you shade this framework's JAR into your own uber-jar, XSeries no longer comes
along for the ride: declare it yourself (`com.github.cryptomorin:XSeries:13.7.1`, or your own
pinned version) if your module uses it.

This boundary determines who fixes a third-party security advisory. For anything in the first
category, pinning a version in this repository is an effective fix. For anything in the second, the
only fix is **upgrading Paper** — no change in `pom.xml` will alter the jar actually loaded on the
server; it only creates the impression that the problem has been fixed.

Note that Maven's `provided` scope is **not** this boundary: the dependencies of both routes that the
framework compiles against are declared `provided` in `pom.xml` (for example HikariCP from the first, Gson from the
second), and the Paper-shipped libraries it does not compile against are not declared at all. What decides the
category is whether the dependency appears in the `libraries:` block, not the scope.

Server owners are advised to keep up with Paper builds, security builds in particular.

## Feedback

If you disagree with this policy, or your module is affected by the removals above, please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues).
