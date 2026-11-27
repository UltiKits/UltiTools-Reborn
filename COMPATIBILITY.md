# Compatibility and Versioning Policy

This guide covers the unreleased UltiTools-API 7.0 line. For compatibility between earlier releases, see
[COMPATIBILITY.md on the `6.x` branch](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md).

Upgrading from 6.x? Start with the [Paper and Java baseline](#paper-and-java-baseline-as-of-700-snapshot)
and [module API floor](#module-api-floor-700-and-plain-module-loading-as-of-700), then check the migrations for
[commands](#command-engine-as-of-700), [messages](#legacy-text-system-removed-as-of-700),
[GUI and confirmation widgets](#legacy-chest-pages-and-confirmation-widgets-removed-as-of-700),
[items and sounds](#native-item-and-sound-apis-as-of-700), and [stored items](#item-descriptions-and-storage-as-of-700).

## What the version number means

### New public API baseline: 7.0.0

**7.0.0 establishes a new baseline and does not compare against 6.x.** The japicmp check is
intentionally disabled (`japicmp.skip=true`) only for `7.0.0-SNAPSHOT` and the `7.0.0` release.
After 7.0.0 is downloadable from Maven Central, set `japicmp.baseline.version=7.0.0` and
`japicmp.skip=false` in `pom.xml` before advancing the development version; run `mvn -B clean verify`.
Default build tests and the registry generator reject later versions with comparison disabled or a
baseline older than 7.0.0. Subsequent releases compare against a released 7.x baseline.

The five compatibility rules continue unchanged in 7.x, with **“published” starting at 7.0.0**:

1. Do not change a published public API signature.
2. Add parameters through overloads, retaining the old signature.
3. Deprecate cautiously.
4. Mark deprecations with `@Deprecated(since = "…", forRemoval = true)`.
5. Remove an API only on that annotation's authority and after at least one MINOR release has
   passed since the warning first shipped (no earlier than 7.0.0 for this baseline).

中文补充：**7.0.0 是新的公开 API 基线，不与 6.x 比较。** 仅 `7.0.0-SNAPSHOT` 与 `7.0.0` 发布允许
`japicmp.skip=true`。7.0.0 可从 Maven Central 下载后，在推进开发版本之前把 `pom.xml` 中的
`japicmp.baseline.version` 改为 `7.0.0`、`japicmp.skip` 改为 `false`，然后运行 `mvn -B clean verify`。
默认构建测试与废弃登记生成器会拒绝后续版本继续关闭检查或使用低于 7.0.0 的基线；后续正式发布比较此前已发布的基线。

7.x 原样沿用五条规则，但“已发布”从 7.0.0 起算：（1）不改已发布公开 API 的签名；（2）新增参数走重载并保留旧签名；
（3）谨慎废弃；（4）废弃须标 `@Deprecated(since = "…", forRemoval = true)`；（5）移除只凭该标注，
并且自警告实际随发布送达起至少跨过一个 MINOR（本基线不早于 7.0.0 起算）。

### Two deliberate deviations from semver

The `MAJOR.MINOR.PATCH` format invites [semver](https://semver.org/spec/v2.0.0.html) expectations.
Two of ours differ from the specification, and we name them here:

- **Timing of removal.** Semver waits for the next MAJOR after deprecation; this project removes in
  a MINOR once one MINOR has passed. If you need a strict binary compatibility guarantee, pin an
  exact PATCH version.
- **We do not adopt the permissive reading of semver clause 6.** That clause allows "correcting
  incorrect behaviour" to ship as a PATCH bug fix. This project does not: any change that alters
  downstream runtime behaviour is assessed for a migration period; a bug-fix label does not bypass that assessment.

### This section covers the framework's own version number only

The rules above apply to `UltiTools-API` itself. Versioning your own module is a separate contract,
decided by whether a server owner has to do anything after dropping in the new JAR. See
[Module versioning](https://dev.ultikits.com/guide/advanced/module-versioning.html)
([Chinese translation](https://dev.ultikits.com/zh/guide/advanced/module-versioning.html)).

The framework version signals product stages rather than strict SemVer:

- **PATCH**: small updates and urgent fixes; no public API removals.
- **MINOR**: feature evolution; removals may follow the deprecation rules above.
- **MAJOR**: a framework-level change of direction, not routine deprecation cleanup.

The module's own version orders releases; it does not decide which duplicate JAR supplies a module's
main class. The module's integer `api-version` governs API admission independently of its Maven
compile dependency and the server's Paper API version.

## Declaring the dependency

The examples target the unreleased `7.0.0-SNAPSHOT` installed locally from this branch.
The latest released artifact on Maven Central is `6.5.0`; use the [6.x compatibility guide](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md) for that release.

Maven:

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>7.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

Gradle (enable `mavenLocal()` for a locally installed snapshot):

```groovy
compileOnly 'com.ultikits:UltiTools-API:7.0.0-SNAPSHOT'
```

**Use `provided` / `compileOnly`.** The POM published to Maven Central is processed by
flatten-maven-plugin and carries no dependency declarations, so placing UltiTools-API in any scope
beyond compile time (Maven `compile`, Gradle `implementation`) gains you no transitive dependencies.
What it does do, if your build has a shade or shadow step, is bundle a copy of the framework into
your module JAR, where it conflicts at runtime with the UltiTools already installed on the server.
Declare the matching `paper-api` separately for code that uses Paper types.

## Paper and Java baseline (as of 7.0.0-SNAPSHOT)

The baseline is **Paper 26.3 build 258 with Java 25**. The build pins
`paper-api:26.3.build.258-stable`, compiles with `--release 25` (class major 69), and the
framework's `paper-plugin.yml` declares `api-version: '26.3'`. Each framework version targets
one latest Paper version; compatibility with older servers is not promised.

The former older-Paper API fixture, its retrieval/skip settings and the Java 8 API-signature
check have been removed from the build. Packaged-runtime checks remain; they do not certify
compatibility with an older Paper release. The historical 6.x notes elsewhere in this document are not a 7.0 support promise.

**Editing saved locations.** Paper 26.3 writes a location's world as `world_key`, not `world`.
The value is a namespaced key of the form `namespace:path`, **not a world name**; reading still
accepts the legacy `world` name. If both fields exist, `world_key` takes precedence. When editing
by hand, **copy `world_key` from a location already saved in the target world**, rather than
constructing it from the world name, and update any existing `world` to the same world's name.
Changing only `world` is ignored if `world_key` remains. An invalid `world_key`, or one whose world
is missing or not loaded, does not fall back to `world`: Paper's `Location.deserialize` throws
`unknown world`; its serialization layer logs the failure and returns null, and the framework's
Bukkit converter rejects the configuration value with a `ConversionException`.

中文补充：7.0.0-SNAPSHOT 的基准是 **Paper 26.3 build 258 + Java 25**，只对准最新的一个
Paper 版本，不承诺兼容旧服务器；本文其他位置的 6.x 旧服务器说明不适用于 7.0。Paper 26.3 把位置的世界
保存为 `world_key`，不写 `world`。`world_key` 是形如 `命名空间:路径` 的键，**不是世界名**；
读取仍兼容旧 `world`（世界名）。两者共存时 `world_key` 优先。手工修改世界时，应**从一个已在目标世界
保存过的位置复制 `world_key` 的值**，不要照世界名自己拼；已有的 `world` 也应改为同一世界的名称。
`world_key` 存在时，只改 `world` 会被忽略。`world_key` 无效或其世界不存在／未加载时，不会回退到
`world`：Paper 的 `Location.deserialize` 抛出 `unknown world`，其序列化层记录失败并返回 null，
框架的 Bukkit 转换器随后以 `ConversionException` 拒绝这个配置值。

## Module API floor 700 and plain module loading (as of 7.0.0)

7.0 removes load-time bytecode adaptation, server-API probing, and both startup and offline
linkage audits. The shared module loader remains a parent-first `URLClassLoader`; compile modules
against the target Paper and UltiTools-API versions rather than relying on bytecode rewriting.
Observed container skips still name the module, bean and missing symbol; those diagnostics are not
an audit or a promise to recover an unavailable capability.

Every module must declare an **integer `api-version` of at least `700`** in its own root
`plugin.yml`. Missing, empty and non-integer values (including `'26.3'`, `1.21` and `abc`) are refused
before main-class resolution, with one translated SEVERE line showing the actual value (or
`<missing>` / `<缺失>`). Quoted integers such as `'700'` are accepted. A declaration below `700`
gets a separate refusal naming the level and minimum. Both messages use the running framework
version, not a hard-coded release name. A module declaring more than the installed framework's
level still follows the existing newer-framework refusal. Unreadable or missing `plugin.yml`,
or a missing `main:`, keeps the existing no-readable-plugin.yml/no-main refusal.

Class-path collection and module discovery reuse one per-start metadata/admission snapshot, read
after recorded updates are applied. An API-refused module JAR is **not on the shared class path**,
so it supplies neither its main class nor helper classes to another module. Each refusal is counted
once in `Failed`, while unrelated modules continue loading. If `m-6.3.0.jar` (630) and
`m-7.0.0.jar` (700) declare the same main class, the older copy is refused once and the new copy's
classes come from the new JAR, even though its file name sorts later. The duplicate warning names
the actual accepted supplier; the JAR index still records both declarations for file management.
Among admitted copies, ordinary file-name ordering is unchanged.

`/upm install` and `/upm update` use the same integer parser and minimum predicate, with
`MIN_INSTALLABLE_API_LEVEL = 700`; downloads above the framework's level are refused too.
Catalogue version requests carry `apiLevel` and `minApiLevel=700`. An earlier API-refused copy is
not treated as a class-path blocker for a staged update. Installation does not automatically delete
older copies or redo the update transaction protocol.

**Operator action:** obtain a module rebuilt against the current UltiTools-API, declaring `700` or
higher, stop the server, replace the obsolete JAR and restart. Merely editing `api-version` does
not repair removed API references. If old and new copies coexist temporarily, the old copy remains
on disk and is refused; keep only the intended current build. The historical 6.3.0 and 6.4.0 sections
below describe those versions, not 7.0 support promises.

**Known limitation — pending 6.x updates during a framework upgrade.** If a 6.x module update is
still waiting for its confirming start when the framework is upgraded to 7.0, the API refusal can
be accompanied by the existing update rollback to the previous 6.x JAR. The restored copy is also
below the new floor. **Before upgrading the framework, let every pending module update complete
one startup on the old framework.** Then replace the module builds and upgrade. No defensive
transaction rewrite is added for this unusual upgrade sequence.

中文补充：7.0 删除加载期适配及启动／离线链接审计。模块必须在自身 `plugin.yml` 声明至少 `700`
的整数 `api-version`；缺失、空值、`'26.3'`、`1.21`、`abc` 一律拒载，带引号的 `'700'` 有效。
被拒 JAR 不进入共享类路径，每个只报一次且计入失败数，其他模块继续加载。同主类的新旧副本并存时，
旧副本被拒、新副本提供类定义；索引保留两份文件身份，不自动删除旧 JAR。请获取重新构建的模块并停服替换，
不要只改 API 声明。已知限制：升级框架前，先在旧框架下让所有挂起的模块更新完成一次启动确认，避免拒载之外
再看到一次旧事务回滚。

## Command engine (as of 7.0.0)

Rebuild module command code against 7.0. Annotation commands are compiled into Brigadier trees;
Paper receives them only during `LifecycleEvents.COMMANDS`. The framework no longer constructs
`PluginCommand` reflectively or edits Bukkit's private command map.

### Source and binary migration

| Removed or changed API | 7.0 replacement |
|---|---|
| `BaseCommandExecutor implements TabExecutor`, `onCommand`, `onTabComplete` | Keep extending `BaseCommandExecutor`; the framework invokes typed mappings through Brigadier |
| `matchMethod`, `parseParameters`, `validateParameterCount`, `parseParameterValue` | No replacement string matcher; use the compiled tree and its argument types |
| Both old protected `suggest` overloads | `suggest(CommandSender sender, String label, String[] args)`; return `null` to retain native argument suggestions |
| `CommandContext.getCommand()` and its builder field | `getCommandName()` and `commandName(String)` |
| `CommandContext.getParsedParams(): Map<String, String[]>` | `Map<String, Object>` containing typed values; `buildMethodParams` reads those values |
| `CommandContext.getParam(String): String[]`, `getParamValue(String): String` | Both return `Object`; cast to the declared parameter type (or the array component type for `getParamValue`) |
| `CommandManager.register(... Class<? extends CommandExecutor> ...)` | The class parameter is `Class<? extends BaseCommandExecutor>` |
| `registerCoreCommand(CommandExecutor)` | `registerCoreCommand(BaseCommandExecutor)` |
| `CommandManager.unregister(String)` | Unregister the owning module or external plugin, not a global label |
| `getPluginByCommand(Command)` | `getPluginByLabel(String)`; accepts primary, alias and namespaced spellings |
| `AbstractCommand`, `AbstractTabExecutor`, `AbstractPlayerCommandExecutor`, `AbstractConsoleCommandExecutor` | `BaseCommandExecutor` or the new `BrigadierCommand` interface |
| `CommandTabCompletionDispatch` | Brigadier suggestions connected to the existing completion-provider registry |
| `TabCompletionManager.suggest(Player, Command, String[])`, `createContext(Player, Command, String[])`, `suggestFirstArgs` | Context-based provider calls; Brigadier supplies literal suggestions |
| `TabCompletionContext.command` / `getCommand()` | `commandName` / `getCommandName()` |
| `WorldParser`, `GameModeParser`, `LocationParser`, `EnchantmentParser` and implicit built-in registry entries | The argument mappings below; the registry keeps explicitly registered module `TypeParser` implementations |

`getMappings`, custom validators, `handleHelp`, `getHelpCommand`, `createDefaultValidatorChain`,
`executeCommand` and `buildMethodParams` remain. An executor without `@CmdExecutor` is refused;
there is no legacy fallback registration. Existing three-argument suggestion methods may still
receive a Bukkit `Command`, resolved by label through the public command map. The removed Bukkit
completion entry point's `null`/online-player fallback convention does not apply to Brigadier:
no candidates means an empty suggestions result for every sender kind.

### Argument mapping

| Java type | Argument and result |
|---|---|
| `String` | One whitespace-delimited token, including non-ASCII text; double-quoted strings are accepted |
| `String` / `String[]` with `<name...>` | Remaining text / whitespace-split array; an omitted tail supplies an empty value |
| `int` / `Integer`, `long` / `Long`, `double` / `Double`, `float` / `Float`, `boolean` / `Boolean` | Brigadier's corresponding typed argument |
| `short` / `Short`, `byte` / `Byte` | Integer argument restricted to the Java type's range, then narrowed |
| `Player` | Paper player selector resolved against the command source |
| `OfflinePlayer` | Player-name token, resolved by Bukkit; online-player-name suggestions |
| `World`, `GameMode`, `UUID` | Paper's native world, game-mode and UUID arguments |
| `Enchantment` | Paper's enchantment registry-resource argument |
| `Material` | Namespaced material key, checked with `Material.matchMaterial` |
| `Location` | Paper fine position, resolved in the source's world: **`x y z`**, including relative coordinates, not the old `world,x,y,z` string |
| Other explicitly registered `TypeParser<T>` types | One token parsed by that parser; its `TypeParseException` detail is retained |
| Other arrays with `<name...>` | Remaining text parsed element by element using the component type's mapping |

Competing parameter children at one position, duplicate normalized paths, non-terminal greedy
parameters, unmapped types and unmatched `@CmdParam` names are compilation errors. A literal and
an argument may share a parent. Named and method-based `@CmdParam(suggest=...)` providers remain;
the executor fallback applies only when that parameter has no explicit provider. Native argument
suggestions are used when neither supplies an override.

Additional 6.x migration differences:

- Boolean values accept only `true` and `false`. The old `Boolean.parseBoolean` conversion silently
  converted other input, including `yes`, to `false`.
- Material values use lower-case namespace keys, such as `minecraft:diamond`, rather than enum
  spellings such as `DIAMOND`.
- Double-quoted text such as `"a b"` is one String argument; an unterminated quote is a syntax error.
- An array must use the terminal `<name...>` form; a non-greedy array is refused at compilation.
- The 6.5.0 built-in Player parser used nullable `Bukkit.getPlayer` and did not supply a dedicated
  localized “player offline / not found” reply. Paper selectors now reject a missing player rather
  than passing null into a module method; native resolution failures use the framework usage reply.

中文补充：布尔参数现在只接受 `true` / `false`，不再把 `yes` 等输入静默转成 `false`；
物品参数使用 `minecraft:diamond` 这样的全小写命名空间键，不接受 `DIAMOND` 枚举写法。
双引号中的 `"a b"` 算一个字符串参数；未闭合的引号是语法错误。数组必须放在末尾并使用
`<name...>`，非贪婪数组在编译命令时被拒绝。`getParam` 与 `getParamValue` 均返回 `Object`，
须按声明的参数类型或数组元素类型取值。6.5.0 的内置玩家解析器没有专用的“玩家不在线／不存在”
提示，而是可能把 null 传给模块；新原生选择器会拒绝找不到的玩家，并由框架给出用法回复。

### Replies, ownership and lifecycle

- Unmatched input retains the framework's existing unknown-command / usage wording.
  Unknown first tokens retain the gated help fallthrough; deeper failures list usages from the
  typed tree. Every intermediate node and non-greedy path has an `args` fallback (or `args_` if
  that name is already used). Such fallbacks can make otherwise-invalid input appear valid to
  the client. They have no custom suggestions and do not ask the server for completion.
  An exact-case unavailable literal is rejected by Brigadier before a framework callback runs;
  the framework handles that syntax event through the same reply path. Unavailable subcommands
  behave as nonexistent for that sender, including when typed explicitly.
  中文补充：没有权限或不允许该发送者使用的子命令，对该发送者表现为不存在；硬输入的回复也相同。
- **Known limitation:** a command that has both a root-level catch-all argument and a
  permission-gated subcommand answers a sender without that permission with the unknown-command
  reply and help when they type the subcommand's name. Neither business mapping executes; other
  words are still handled by the catch-all argument, so such a command does not fully hide that
  the name is special. Module authors who need command-name secrecy should not put these two
  forms at the same level.
  中文补充：**已知限制：**同一层同时有根级任意文字参数与受权限限制的子命令时，无权限发送者
  输入该子命令名会收到未知指令回复与帮助，两种业务都不执行；其他词仍由任意文字参数处理，
  因而无法完全隐藏这个名称的特殊性。需要保密命令名的模块作者不要把这两种形式放在同一层。
- Every literal position is case-insensitive, including literals following typed arguments.
  An argument or fallback callback gives a same-position literal priority regardless of case,
  then re-dispatches at most once through the same live binding and validator chain. Argument
  spelling is not lower-cased. With `<player>` beside `accept`, `Accept` invokes `accept`;
  a quoted `"Accept"` remains a String argument.
- Recovery also normalizes repeated or trailing whitespace when a fallback callback is reached,
  preserving quoted and greedy argument content. **Known limitation:** a native literal followed
  by exactly one trailing space (for example `ul reload `) can fail before any callback runs.
  6.5.0's Bukkit entry point ignored those spaces; trim console/panel input before dispatching.
- Root permissions, operator requirements and sender targets hide unavailable commands in the
  client tree. Exclusive mapping paths hide unavailable subcommands; shared prefixes remain.
  Parameter providers and usage replies filter private mappings and do not invoke their providers.
  **Known limitation:** a real suggesting argument beside a hidden literal can still cause the
  server dispatcher to merge that literal into its completion response; provider-level filtering
  cannot remove sibling literal results. Avoid that tree layout when command-name secrecy matters.
  Execution still performs permission, sender-type, cooldown, lock, asynchronous-dispatch and
  panel-refusal checks; a leaked name does not grant execution permission.
- Modules and external plugins may supply container beans implementing `BrigadierCommand`.
  `node()` is called once per registration; `aliases()` and nullable `description()` supply
  metadata. Bean aliases are deduplicated by identity. These trees do not acquire annotation
  validators, cooldowns or `@CmdParam` completion. Internal redirects point to owned proxies;
  **redirect targets outside the supplied tree are not controlled by module unload**. Such external
  targets are not rebound when a module instance is replaced; refresh the tree through the next
  commands lifecycle event or restart.
- Paper owns a fixed proxy shape; the binding table owns live module instances. Unregistering
  a module hides its proxies and rejects execution. A genuinely registered replacement with the
  same signature rebinds the existing shape. A different owner claiming the same root is refused
  and logged with both owners/classes; the owner's other commands may continue registering.
- **`/ul reload` only reloads configuration.** It does not recreate containers or executors,
  rebind commands, or add/remove conditional commands. A changed conditional-registration key
  still reports that a restart is required. Installing/updating module JARs still needs a restart:
  Paper retains the registered argument-type instances, including a module's custom `TypeParser`.
  Rebinding an executor does not replace those parsers and may retain the old module classes.
- Permission changes while a player stays online do not automatically refresh their client command
  tree. Reconnect or run `/minecraft:reload` to refresh it. Execution permissions are always checked
  live, independently of that cached client tree.
- A root first registered after the initial commands event, or a replacement with a different
  signature, waits for the next `/minecraft:reload` or restart. The console names it in one
  `Command /<label> from <owner> was registered after start-up; it becomes available after
  /minecraft:reload or a restart.` warning. This applies to real registration/connection APIs,
  not to toggling a conditional configuration value with `/ul reload`. The engine never triggers
  a global data-pack reload automatically.
- Paper rejects Bukkit plugin reload after a lifecycle handler is installed. This is distinct
  from `/minecraft:reload`, which reloads data packs and delivers another commands event.

中文迁移要点：7.0 保留注解作者模型，但删除 Bukkit 执行器入口、字符串匹配与反射注册。
自定义类型解析器仍可显式注册；坐标改用 `x y z`。兜底可达时，输错输入继续得到框架提示，
客户端也可能不再标红；大小写完全匹配但不可用的字面量由 Brigadier 直接拒绝。
兜底参数名是 `args`（重名时追加下划线），不注册服务器补全；真实参数补全仍保留。
所有字面位置忽略大小写并优先于同位置参数，最多重新分发一次，参数原样保留：
`<player>` 旁有 `accept` 时，`Accept` 调用 `accept`，带引号的 `"Accept"` 仍是字符串参数。
恢复回调可达时会规范化连续或行尾空白；纯字面量末尾只有一个空格时，Brigadier 可能在回调前
拒绝（如 `ul reload `），这是与 6.5.0 不同的已知限制，控制台／面板应在分发前去掉行尾空格。
权限与发送者条件过滤客户端命令树；参数提供者和用法回复只列可见映射。但真实补全参数旁若有
隐藏字面量，服务器仍可能把该字面量合并进补全响应，参数提供者无法删除同级节点的结果。
需要保密命令名时应避免这种树形布局；名称泄漏不赋予执行权限。
解绑和同形重新绑定只随真实模块注册、
注销、替换发生，`/ul reload` 不改变命令注册，条件开关变动仍要求重启。手写树的树外重定向
目标不受模块卸载控制，也不会随模块实例替换而重新绑定。安装／更新模块 JAR 必须重启：Paper 保留
已注册参数类型和模块自定义 `TypeParser` 实例，旧解析器和其类加载器可能继续被引用。
玩家在线期间权限变化不会自动刷新客户端命令树，须重新登录或执行 `/minecraft:reload`；执行时仍实时检查权限。
模块必须重新编译，不能只修改 `api-version`。

### Command feedback and asynchronous unload (as of 7.0.0)

Framework command feedback now uses `Messages.send(sender, Text)` rather than sentence-key
`i18n` strings. With `language: auto`, each player receives their current client language;
console, command blocks and panel feedback use `fallback-language`. The wording and colours
of the bundled replies are retained. Module help and custom parser exception details remain
module-owned; the parser helpers' fixed English details are not translated.

All thirteen framework keys use the `ultitools:` namespace:

- `command.error.op_required`, `command.error.no_permission`, `command.error.player_only`,
  `command.error.console_only`, `command.error.sender_locked`, `command.error.server_locked`,
  `command.error.cooldown`, `command.error.execution_failed`;
- `command.async.processing`, `command.async.timeout`;
- `command.reply.unknown`, `command.reply.usage`, `command.reply.argument_error`.

Override them in `plugins/UltiTools/messages/<locale>.yml` using dotted or nested YAML keys and
MiniMessage values. `/ul reload` re-reads framework catalogue overrides as well (as of 7.0.0). Arguments are
literal text or components, not MiniMessage templates: `<target>` and `<words...>` in usages
remain visible. Native translatable argument-error components are retained where Paper supplies
them; plain parser details are passed through literally.

`ValidationResult.failure(Text, String)` and `getErrorText()` provide deferred text for validators.
`ChainValidationResult.getErrorText()` reads the first failure, like `getErrorMessage()`. A
Text-backed failure returns **null** from `getErrorMessage()`; consumers must check `getErrorText()`
first. The existing String factories remain supported and custom String messages are sent unchanged.
`@AsyncCommand.processingMessageKey` now takes `<namespace>:<path>`; its colour comes from that
catalogue. An invalid value warns once per executor/value and uses `ultitools:command.async.processing`.

`@RunAsync` and `@AsyncCommand` command bodies remain **framework-owned**, not module-owned.
They use `Scheduler.async().run`; synchronous bodies run inline on the global tick thread, or
are handed to `global().run` off that thread. Bodies are not entity tasks: a player leaving must
not skip the body and its completion/lock-release hooks. A timeout notice alone uses that player's
`entity(player)` context, or `global()` for other senders. Completion cancels its timeout task.
Timeout stops waiting and reports once; it never cancels or interrupts the body.

At server shutdown, **all command executors share about ten seconds of waiting in total**, including
accepted async invocations' completion hooks. Framework-owned executors are explicitly registered
and drained at the start of `onDisable`, before any framework service is shut down; module and root
container drains use the same remaining allowance. Only time actually waiting for command bodies
consumes it, not intervening service cleanup; starting shutdown again does not reset it. A single
module unload outside shutdown gives that module's executors one fresh shared ten-second allowance;
separate module unloads do not consume each other's allowance. Command waiting is independent of
the [UI shutdown allowance](#ui-storage-waits-at-shutdown-as-of-700).
New async submissions to unloading executors are refused. Once the allowance is exhausted, further
executors do not wait. Each executor still running warns once with its name and remaining invocation
count; bodies continue in the background without interruption. Do not block a command waiting for a
global/entity callback during unload: the unloading tick thread cannot run that callback while waiting,
and later framework submissions are refused once its scheduler retires. A module's injected scheduler
has its own retirement rules. Cooldown seconds remain plain integers without locale grouping.

中文补充：框架命令反馈已改用 `Messages`；`language: auto` 时按各玩家当前客户端语言渲染，
控制台、命令方块和面板使用后备语言。上列 13 个键都属于 `ultitools` 命名空间，可在
`plugins/UltiTools/messages/<locale>.yml` 中覆盖；完整的 `/ul reload` 也会重读框架目录覆盖（自 7.0.0 起）。原有回复措辞和
颜色保留，用法里的 `<target>`、`<words...>` 等参数不会当模板解析；模块帮助及解析器异常原文
仍归模块所有，两个固定英文解析错误不翻译。校验失败先读取 `getErrorText()`；由 Text 构造的
失败其 `getErrorMessage()` 为 null，旧 String 工厂及自定义字符串回复继续原样支持。
`processingMessageKey` 改用带命名空间的 `<namespace>:<path>`，颜色由模块目录决定；非法值
每个执行器／值只警告一次，回退默认“处理中...”键。

异步命令体仍归框架调度器所有，同步命令已在 global tick 线程时立即执行，否则交给 global；
只有超时提示跟随玩家实体，其他发送者走 global。命令按时结束会取消看门狗，超时只停止等待、
提示一次，不取消或中断命令体。整次停服的全部命令执行器共用约 10 秒的剩余等待预算，包含
完成钩子与锁释放；框架自带执行器显式登记，在 onDisable 开头、任何框架服务关闭前先等待，
模块与根容器继续使用同一份预算。只有实际等待命令体的时间才扣，服务清理时间不扣，重复开始
停服不恢复预算。非停服时单个模块卸载，其全部执行器共用一次新的约 10 秒等待预算，不同模块
先后单独卸载互不影响。命令预算与[界面关停预算](#ui-storage-waits-at-shutdown-as-of-700)互相独立。
卸载中的执行器拒绝新的异步提交；预算耗尽后其余执行器不再等，仍有运行任务的执行器各警告
一次（类名、剩余数量），命令体不中断、继续在后台运行。卸载期间不要阻塞等待跳回 global／
entity 的回调：等待中的 tick 线程无法执行它，框架调度器收回后再提交也会被拒绝；模块注入的
调度器另按模块生命周期收回。冷却秒数保持无千位分隔符的纯整数。

## Legacy text system removed (as of 7.0.0)

7.0 has one text system: `Messages`, with dotted keys and MiniMessage values. It removes the
legacy `Language`, `Localized`, `MessageUtils`, `FrameworkText`, `OfficialLanguageFiles`, and
`@I18n` types, as well as the `i18n` attribute of `@UltiToolsModule`. The framework no longer
implements `Localized`. Remove overrides of `Localized#supported()` when rebuilding a module.

The removed entry points are `UltiToolsPlugin#i18n(String)`, `UltiToolsPlugin#i18n(String, String)`,
`UltiToolsPlugin#getLanguageCode()`, `UltiToolsPlugin#getConfiguredLanguage()`,
`UltiToolsPlugin#getLanguage()`, `UltiToolsPlugin#overrideLostPlaceholders(...)`,
`UltiToolsPlugin#supported()` (inherited from `Localized`),
`UltiToolsPlugin#commitLanguageProvenance()` (internal; renamed to `publishMessageCatalogue()`),
`UltiTools#i18n(String)`, `UltiTools#i18n(String, String)` (inherited from `Localized`),
`UltiTools#getLanguage()`, and `UltiTools#supported()`. Module code that calls
`UltiToolsPlugin#supported()` no longer compiles against 7.0.

| 6.x | 7.0 |
| --- | --- |
| `i18n("original sentence")` | Inject `Messages` and call `messages.send(sender, messages.key("a.b"), args…)`. |
| `String.format(i18n(k), x)` | Put a named tag such as `<name>` in the catalogue and pass `Arg.string("name", x)` (or `Arg.number` for a number). |
| `getLanguageCode()` | Inject `LocaleResolver`; call `fallback()` for the server's fallback `Locale`. Use `Messages` directly for per-recipient text. |
| Override `supported()` or declare `@I18n` / `@UltiToolsModule(i18n = …)` | Remove the override or declaration and ship `messages/<locale>.yml`. |
| `lang/<code>.json`, `.yml`, or `.yaml` | Ship `messages/<code>.yml` with dotted keys instead of sentence keys. |
| `MessageUtils.sendMessage(...)` | `Messages.send(...)` (use `Text.literal(component)` for an already built Adventure component). |
| Text for `@ConfigEntry(comment = "{k}")` | Put `k` in the module's `messages/<locale>.yml`; see **Module annotation text** in the section above for keys and `previousComments`. |

For example, move the translated template out of the Java call site. `recipient` below is a
player or another Adventure `Audience`.

```java
// Before (6.x, in the module)
void greet(Player recipient, String name) {
    recipient.sendMessage(String.format(i18n("Welcome, %s!"), name));
}

// After (7.0, in a managed module component)
@Autowired
private Messages messages;

void greet(Player recipient, String name) {
    messages.send(recipient, messages.key("greeting.welcome"), Arg.string("name", name));
}
```

```yaml
# messages/en.yml
# Supply the same key in messages/zh.yml with a Chinese value.
greeting.welcome: "Welcome, <name>!"
```

Module JARs no longer load or extract `lang/` resources, and the framework no longer ships its
own `lang/` catalogue. At startup, 7.0 checks only `plugins/UltiTools/lang/` and the `lang/`
folder directly inside each module directory under `plugins/UltiTools/pluginConfig/`. If it
finds any, it logs one INFO line with up to 20 paths and only a count for the rest. It does not
recurse or follow symlinked module directories or `lang` entries; the framework data directory
and `pluginConfig` may themselves be symlinks. The check does not run on `/ul reload`. It does
not read, modify, rename, or delete any legacy files or change the directories. Move any
translations you changed to the new override files manually; there is no automatic import.
A configured custom name such as `zh-myserver` still selects its official base (`zh`), and an
unmatched safe name selects `en`, without reading the old file or adding an invalid-locale warning.

中文补充：7.0 移除上述旧类、注解和方法。模块须删掉 `supported()` 的覆盖；调用从 `Localized` 继承的
`UltiToolsPlugin#supported()` 也无法通过编译。`UltiTools#i18n(String, String)` 同样是从 `Localized`
继承的，现已移除。内部方法 `UltiToolsPlugin#commitLanguageProvenance()` 更名为 `publishMessageCatalogue()`。
模块须改为注入 `Messages`，用点分键和 MiniMessage 编写 `messages/<locale>.yml`，格式参数使用
`Arg.string` 或 `Arg.number`。
需要服务器后备语言时注入 `LocaleResolver` 并调用 `fallback()`；注释令牌按上一节的约定迁移。
7.0 启动时只检查 `plugins/UltiTools/lang/`，以及 `plugins/UltiTools/pluginConfig/` 下各直接模块目录里的 `lang/`。
若发现遗留目录，控制台只记录一行 INFO，最多列出 20 个路径，其余只报数量。检查不递归，也不跟随遇到的模块目录或 `lang` 符号链接；
框架数据目录与 `pluginConfig` 本身可以是符号链接。`/ul reload` 不触发此检查。框架不读取、修改、重命名或删除旧文件，也不改动这些目录。
服主改过的翻译需手动搬到新覆盖文件，不会自动导入。`zh-myserver` 仍按 `zh` 选择基础语言，无匹配前缀的安全名称用 `en`。

## Legacy chest pages and confirmation widgets removed (as of 7.0.0)

7.0 removes `abstracts.gui.BaseInventoryPage`, `abstracts.gui.BasePaginationPage`,
`abstracts.gui.BaseConfirmationPage`, `widgets.Confirm`, `widgets.impl.ChatConfirm`,
`widgets.impl.InventoryConfirm`, `entities.Buttons`, `entities.ViewType` (all under
`com.ultikits.ultitools`), the old `abstracts.gui` package documentation, and
`DependenceManagers#initInventoryAPI`. Rebuild modules against 7.0. The framework no longer bundles
obliviate-invs or UniversalScheduler. Modules using `mc.obliviate.*` or `com.github.Anon8281.*`
directly must package those libraries themselves or rewrite that code; 6.x did not relocate them,
so such modules were using the framework's copies.

| 6.x | 7.0 |
|---|---|
| Extend `BaseInventoryPage`, implement `setupContent`, use `createActionButton` / `addToBottomRow` | Extend `DeclarativeGui`, call `super(ui, scheduler, player, id, Text.plain("Title"), rows)`, return a tree of `Container`, `TextButton` and `ItemDisplay` from `build(BuildContext)`, then call `open()`; see "Declarative screens use the UI service" above |
| Extend `BasePaginationPage`, implement `provideItems()`, call `goToPage(n)` | Keep the current page in a `StatefulWidget`'s `State`; build its data slice with `GridView.builder().startSlot(...).columns(...).items(pageItems, itemBuilder).build()`. Use a `TextButton`'s `onClick(Runnable)` to call `setState(...)` and rebuild the slice. `GridView` has no page-number API. For route navigation, `Navigator.of(context)` may return null; otherwise its state offers `push(route)`, `pop()`, `pushReplacement(route)` and `canPop()` |
| `BaseConfirmationPage`, `BaseConfirmationPage.builder(player)...open()`, or `Confirm.gui(player, title, desc, onConfirm, onCancel)` | `ui.show(player, Confirm.builder(Text.plain(title)).addBody(Text.plain(desc)).build()).result()` using `com.ultikits.ultitools.ui.Confirm`; observe `UiResult.Completed` (`true` = yes, `false` = no) or `UiResult.Closed(reason)`. Labels default to framework keys `ui.yes` / `ui.no`; override with `.yes(Text)` / `.no(Text)` |
| `Confirm.chat(player, title, desc, onConfirm, onCancel)` | Send two clickable Adventure components from the module; see the example below |
| `ViewType`, `Buttons` | No replacement enum; use a `TextButton`'s own label or framework keys `ui.ok` / `ui.cancel` |

### Module-owned clickable chat prompts

Import `java.util.UUID`, `org.bukkit.entity.Player`, `net.kyori.adventure.text.Component`,
`net.kyori.adventure.text.format.NamedTextColor`, and
`net.kyori.adventure.text.event.ClickCallback` / `ClickEvent`. Given a target `Player player` and
`Runnable onConfirm` / `onCancel`, the module can send:

```java
UUID target = player.getUniqueId();
ClickCallback.Options once = ClickCallback.Options.builder().uses(1).build();
Component yes = Component.text("[Confirm]", NamedTextColor.GREEN).clickEvent(ClickEvent.callback(audience -> {
    if (audience instanceof Player clicker && clicker.getUniqueId().equals(target)) {
        onConfirm.run();
    }
}, once));
Component no = Component.text("[Cancel]", NamedTextColor.RED).clickEvent(ClickEvent.callback(audience -> {
    if (audience instanceof Player clicker && clicker.getUniqueId().equals(target)) {
        onCancel.run();
    }
}, once));
player.sendMessage(Component.text("Delete this home?").appendNewline().append(yes).append(Component.space()).append(no));
```

When writing callbacks in a module, account for these properties. Where a bullet mentions 6.x, it
compares with the removed framework chat confirmation (`widgets.impl.ChatConfirm`, which ran its
buttons through the `/ultitools_callback` command), not with callbacks a module wrote itself:

- **Lifetime:** prompts expire on the server after Paper's default callback lifetime (about 12 hours).
  Clicking an expired button does nothing. In 6.x, pending callbacks did not expire.
- **Command pipeline:** clicks do not fire `PlayerCommandPreprocessEvent`, produce the console's
  `issued server command: /ultitools_callback ...` log line, or pass through other plugins' command blockers.
- **Action exceptions:** Paper logs exceptions thrown by a button's action without disconnecting the player;
  the 6.x callback command silently swallowed exceptions.
- **Independent buttons:** clicking confirm and then cancel, or cancel and then confirm, runs each action once
  while both callbacks remain valid. This was also possible in 6.x. Modules that need mutually exclusive
  actions must enforce that condition in their own actions.
- **Module unload:** unloading a module does not revoke its pending callbacks. Until the callback expires
  (by default about 12 hours after registration), clicking an unused old button can still run action code
  from the unloaded module. In 6.x, these callbacks were retained without an expiry.

中文补充：7.0 删除三个旧箱子页面基类、旧 `widgets.Confirm` 及其聊天／箱子实现、`Buttons`、
`ViewType` 与 `DependenceManagers#initInventoryAPI`，不保留废弃桥接；模块须按 7.0 重新构建。
表中通用页面改为 `DeclarativeGui` 的组件树；分页在 `State` 中维护页码，用
`GridView.items(列表, 构建函数)` 放入当前页切片，由 `TextButton` 通过 `setState(...)` 翻页
（`GridView` 没有页码 API）；路线切换可用非空 `Navigator.of(context)`
返回的状态的 `push`、`pop`、`pushReplacement`、`canPop`。确认改用 `ui.Confirm`，结果区分
`Completed(true/false)` 与 `Closed(reason)`，默认按钮键为 `ui.yes` / `ui.no`，可用 `yes` / `no`
覆盖；旧按钮枚举无替代类型，文字改用组件标签或 `ui.ok` / `ui.cancel`。框架不再打包
obliviate-invs、UniversalScheduler，6.x 没有重定位，直接用这些库的模块需自行打包或改写。
需要“聊天里点一下”时由模块自己发送上述 Adventure 回调，按 UUID 限定目标，两个按钮各限一次：
默认约 12 小时过期、不经命令管线、动作异常由 Paper 记录、两按钮互相独立（互斥须模块自己保证）、
卸载模块不会撤销待点回调；模块必须据此安排动作生命周期。

## Native item and sound APIs (as of 7.0.0)

UltiTools-API 7.0 no longer supplies XSeries or `XVersionUtils`, including the builder overloads
accepting `XMaterial` or `XEnchantment`. Rebuild modules against 7.0 and prefer Paper's native APIs.
A module that keeps XSeries must package and relocate it itself (shade + relocate); Paper's
`libraries:` mechanism is not available to UltiTools modules loaded by the framework.
Do not use a 6.x module build to judge the 7.0 framework: a module must be built against 7.0 and
contain no XSeries or `XVersionUtils` references for the native-material and teleport-sound UAT
rows; without such a build, record the row as blocked, not fail.

```java
// Inject ItemRenderer with @Autowired in the consuming bean.
ItemStack item = itemRenderer.render(ItemSpec.builder(Material.STONE).build(), player);
player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 0);
ItemStack enchanted = itemRenderer.render(ItemSpec.builder(Material.FISHING_ROD)
        .data(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.itemEnchantments()
                .add(Enchantment.LUCK_OF_THE_SEA, 1).build()).build(), player);
player.sendActionBar(Component.text("Ready"));
```

Call the sound and enchantment APIs during gameplay, not from static initializers or enum constructors.
`ItemStackBuilder` is removed in 7.0; use `ItemSpec` and an injected `ItemRenderer` instead
(see [item construction migration](#item-construction-migration)).
The native `Material`, `ItemStack` and `Enchantment` entrypoints remain available. GUI glass colours,
button materials, teleport sound (volume 1, pitch 0) and notification text remain unchanged.
The unused `Sounds` enum and `Colors.getCode()` are removed; the `Colors` enum itself remains.
`Colors.getGlassPaneMaterial()` is new in 7.0.0 and does not exist in 6.x; code that has to compile on
both lines uses `Material.<COLOR>_STAINED_GLASS_PANE` directly.

7.0 不再提供 XSeries 与 `XVersionUtils`；保留 XSeries 的模块须自行打包并重定位
（shade + relocate），UltiTools 模块不能使用 Paper 的 `libraries:`。推荐使用上面的 Paper
原生材质、音效、附魔和快捷栏上方文字 API。旧 `ItemStackBuilder` 已删除，物品创建改用
`ItemSpec` 描述和按观看者语言渲染的 `ItemRenderer`，迁移对照见下一小节。
下面 6.x 小节保留历史行为，不是 7.0 的运行库供应承诺。

## Item descriptions and storage (as of 7.0.0)

`ItemSpec` describes an item without creating a stack or choosing a viewer's language.
Inject `ItemRenderer` with `@Autowired` and call `render(spec, player)` when the viewer is known,
or `render(spec, locale)` when a locale is already available. Rendering needs a running Paper server.
Each call returns a new, independent `ItemStack`; the renderer is a framework singleton.
The framework resolves its `Messages` and `LocaleResolver` dependencies lazily from the root container;
module child containers inherit the same renderer. Creating a description alone needs no running server.
Unknown or block-only item keys fail at render time with `IllegalArgumentException`. Paper validates the
rendered component values, including its own lore-line limit; the renderer propagates Paper's rejection
rather than maintaining a second copy of that limit.

中文补充：`ItemSpec` 只描述物品，不创建物品栈，也不提前固定观看者语言。通过 `@Autowired`
取得 `ItemRenderer`，在展示时按玩家或明确的 `Locale` 渲染；每次得到独立物品栈，渲染需要运行中的 Paper。
描述本身可脱离服务器创建。渲染器首次取用时才从根容器解析文本与语言服务，模块子容器共用该实例。
未知键和纯方块键在渲染时抛 `IllegalArgumentException`；说明行等组件限制由 Paper 校验，框架不复制上限规则。

### Item construction migration

Use `ItemSpec` for descriptions that will be rendered for a viewer. Text belongs in the module's
language catalogue as MiniMessage; fixed Adventure components can be supplied through `Text.literal`.
Typed attributes cover names, lore, models, glint and tooltip display; other components use `data` or `remove`.

`ItemStackBuilder` is removed, including its existing-stack and string factories. Recompile callers
against 7.0 and replace them using this table; names and lore belong in language files, not `&` colour codes.

In the replacements below, `builder` is an `ItemSpec.Builder`, `spec` is its built description, and
`stack` is a rendered or existing `ItemStack`. `key`, `key2` and `key3` are catalogue `MessageKey`s.
Apply native stack/meta edits after rendering. The examples are compiled in the test tree's
`item/impl/ItemConstructionMigrationCompileCheck.java`.

| 6.x call | 7.0 replacement |
|---|---|
| `ItemStackBuilder.of(Material.X).name("&aA").lore("&7b").build()` | `ItemSpec.builder(Material.DIAMOND).name(key).addLore(key2).build()` then `itemRenderer.render(spec, player)`; catalogue values use MiniMessage |
| `.lore(String...)` / `.lore(List<String>)` | `builder.lore(List.of(Text.of(key2), Text.of(key3)))` replaces all lore |
| `.addLore(String...)` | `builder.addLore(key2).addLore(key3)` appends lines; fixed components use `builder.addLore(Text.literal(component))` |
| `.amount(n)` | `builder.amount(n)`; accepted range is 1–99, rather than silently clamping |
| `.glow()` | `builder.glint(true)`; no dummy enchantment is added |
| `.enchant(e, lvl)` | `builder.data(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.itemEnchantments().add(e, lvl).build())` |
| `.removeEnchant(e)` | `stack.removeEnchantment(e)` removes only that enchantment; `builder.remove(DataComponentTypes.ENCHANTMENTS)` removes the whole component |
| `.hideAttributes()` | `builder.hiddenInTooltip(Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS))` |
| `.flags(flags)` | `stack.editMeta(meta -> meta.addItemFlags(flags))` preserves the native flag mapping |
| `.hideAll()` | `stack.editMeta(meta -> meta.addItemFlags(ItemFlag.values()))`; like 6.x, hides the flagged details, **not** the name or lore |
| No equivalent: hide the entire tooltip | `builder.tooltipHidden(true)`; this also hides the name and lore, unlike old `hideAll()` |
| `.unbreakable()` | `builder.data(DataComponentTypes.UNBREAKABLE)`; this is a non-valued flag in Paper 26.3 |
| `.customModelData(n)` | `builder.data(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(n).build())` |
| `.leatherColor(c)` | `builder.data(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(c))` |
| `.leatherColor(r, g, b)` | `builder.data(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(r, g, b)))` |
| `.skullOwner(player)` | `builder.data(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(player.getPlayerProfile()))` |
| `.skullOwner(name)` | `builder.data(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().name(name).build())` |
| `.skullOwner(uuid)` | `builder.data(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().uuid(uuid).build())` |
| `.meta(metaModifier)` | `stack.editMeta(metaModifier)` with a `Consumer<ItemMeta>` |
| `.item(itemModifier)` | `itemModifier.accept(stack)` with a `Consumer<ItemStack>` |
| `ItemStackBuilder.of(existingStack)` | `ItemStack stack = existingStack.clone();` then, for example, `stack.setData(DataComponentTypes.MAX_STACK_SIZE, 16)` or `stack.unsetData(DataComponentTypes.MAX_STACK_SIZE)` |
| `.build()` / `.buildClone()` | `itemRenderer.render(spec, player)` already creates a new stack on every call; use `stack.clone()` to copy an existing stack |
| `ItemStackBuilder.of(String)` | `Material matched = Material.matchMaterial(name);` then `matched != null && matched.isItem() && !matched.isAir() ? ItemSpec.builder(matched) : null` |

`Material.matchMaterial` accepts current material names such as `"DIAMOND"`, `"diamond_sword"` and
`"minecraft:stone"`; the replacement above rejects unknown, block-only and air materials.
For an already valid, lowercase namespaced key, `Registry.ITEM.get(Key.key(name))` is another lookup option:
unknown or block-only keys return `null`, but invalid key syntax such as `"DIAMOND"` throws
`InvalidKeyException` in `Key.key`, rather than returning `null`.
The native `ItemFlag` examples deliberately use the same `addItemFlags(ItemFlag.values())` operation as
6.x `hideAll()` instead of maintaining a second flag-to-component mapping.

中文补充：旧构建器及其字符串、已有物品入口全部移除，模块须按上表迁移并重新编译。
名字和说明行放语言文件，以 MiniMessage 描述；`.glint(true)` 只覆盖光效，不添加伪附魔；
旧 `.hideAll()` 只隐藏各类详情，名字和说明仍显示，渲染后用原生 `addItemFlags(ItemFlag.values())` 保持该行为。
`.tooltipHidden(true)` 是隐藏整个提示框的新能力，不是 `.hideAll()` 的等价替换。
字符串材质名用 `Material.matchMaterial`，并排除未知、纯方块和空气；不要把 `"DIAMOND"` 直接传给
`Key.key`，它会抛异常而不是返回 `null`。数量范围改为 1–99，越界拒绝而非静默截断。

For changes to an existing stack, use Paper's `setData` and `unsetData` directly.
Pass-through values are applied unchanged, not localized. Patches are applied in insertion order,
followed by persistent-data markers; a later marker for the same key replaces an earlier one.

中文补充：需要按玩家显示的物品用描述加渲染器；固定组件可用 `Text.literal`，已有物品直接调用
Paper 的 `setData` / `unsetData`。透传值不翻译，按插入顺序应用，最后写入持久标记，同键后写覆盖。

### Item text styles

Names use `ITEM_NAME`, not the italic custom-name component. The renderer does not restyle names.
Lore lines are rendered through `Messages` in the same locale as the name.
Specify any desired colours in the text rather than relying on a framework colour default.
Lore defaults to non-italic only when italic is unset; explicit italic or non-italic styling, including
styling on part of a line, is preserved and colours are unchanged. This deliberately differs from
vanilla's default italic lore.

中文补充：名字使用 `ITEM_NAME`，渲染器不追加名字样式；名字和说明行使用同一语言渲染。
需要指定颜色时请在文本中明确设置，框架不替作者决定颜色；说明行未指定斜体时默认不斜体，
这与原版默认斜体不同。作者明确指定的斜体或正体（包括一行中只有部分文字的样式）保持原样，颜色不变。

### Stored item arrays

`ItemStackUtils` keeps its five static entrypoints and returns `null` for absent input or conversion failure.
New writes use `ItemStack.serializeItemsAsBytes` and `java.util.Base64`, rather than a Bukkit Java object stream.
Reading uses the MIME Base64 decoder, so line-wrapped strings are accepted.
Empty decoded stacks become `null` slots, preserving the array's length and empty-slot positions.
Decoded bytes starting with `0xAC 0xED` are read as the legacy Bukkit object stream written by 6.x
`ItemStackUtils`; the old reader is private and no new writes use that format. This does not promise
support for every module-specific serialization scheme. New strings are not a format that 6.x readers understand.
`serializeItem` and `deserializeItem` use the same array format for one item; `deserializeItem` returns the
first slot, or `null` for an empty array or empty slot. `isValidSerialized` reports whether the array can be read.
Conversion failures are logged at SEVERE and return `null`. A captured 6.x string written on Paper 1.21.11
is covered by a legacy-reader regression test; MockBukkit does not prove Paper's cross-version data upgrading.
Real-server UAT must verify historical named, enchanted, marked and container items on 26.3.

#### Stored-item limitations and backups

- `deserializeItem` returns `null` both for a successfully read empty item and for a read failure.
  Do not use that result alone to decide whether a saved value was corrupt; check the conversion log or
  use the array result from `deserializeItems` when the distinction matters.
- Back up stored inventories and item strings **before upgrading**. A downgrade to an older game version
  cannot read item data written by a newer version. If a caller then saves its current or empty inventory
  normally, it can overwrite the unreadable original. Restore a matching backup when rolling back, and
  do not automatically save over item data that failed to load.
- `ItemStackUtils` is only for trusted data previously saved by your own server. Do not pass player uploads,
  chat input or externally supplied Java object streams into it. Its readers are not an input-sanitization
  or deserialization security boundary; corrupt or hostile input can exhaust memory, and the legacy reader
  has no class allowlist.

中文补充：`ItemStackUtils` 保留五个静态入口及失败返回 `null` 的约定。7.0 以 Paper 物品字节格式
加 Base64 写入，读取接受换行；空物品恢复成 `null`，不改变数组长度和空槽位置。
解码后以 `0xAC 0xED` 开头的内容走私有旧格式读取器，可读 6.x `ItemStackUtils` 保存的对象流，
但不再生成旧格式，也不承诺支持模块各自的所有存档格式；6.x 读取器不识别新写出的格式。
单物品方法复用数组格式，读取空数组或首槽为空时返回 `null`；`isValidSerialized` 判断数组能否读出。
转换失败记 SEVERE 并返回 `null`；单物品读取返回 `null`，既可能是正常空槽，也可能是读取失败，
不能只凭这个返回值判断存档损坏，需要查看日志，或在需要区分时检查 `deserializeItems` 的数组结果。
真实 1.21.11 捕获串已有旧读取路径回归测试，但 MockBukkit 不证明 Paper 的跨版本数据升级；
命名、附魔、自定义标记和容器物品须由 26.3 真机验收证明。
**升级前备份物品与背包存档**：降级后的旧游戏版本读不了新版本写入的数据，模块若接着照常保存当前背包或空背包，
可能覆盖原数据。回退时恢复匹配的备份，不要在读取失败后自动保存覆盖。
此工具仅用于服务器自己保存的可信数据，不接收玩家上传、聊天输入或外部 Java 对象流；读取器不是安全过滤器，
损坏或恶意输入可能耗尽内存，旧格式读取器没有类白名单。

## Removed data-store overloads (as of 6.5.0)

These data-store members are not available on 7.0:
`DataStore#checkOwnership(File, Class)` and `#checkOwnership(UltiToolsPlugin, Class)`, and
`getOperator(UltiToolsPlugin, Class)` / `getOperator(File, Class)` on `JsonStore`, `MysqlDataStore`
and `SQLiteDataStore`. Use `DataStore#getOperator(DataScope, Class)`; modules should call
`UltiToolsPlugin#getDataOperator(Class)`. This is a breaking binary change for third-party callers:
update and recompile before upgrading, or invoking a removed member can throw `NoSuchMethodError`.
The scope-based API performs ownership checks.

## Class-name filter audit and no-op SecurityPolicy methods removed (as of 7.0.0)

The observe-only class-name filter audit and its startup summaries are removed. The deprecated
`SecurityPolicy.isSafeClassName(String)`, `isSafeParameterType(Class<?>)`,
`addTrustedPackage(String)` and `addDangerousClass(String)` no-op methods are removed, as are
`getSecurityPolicySummary()` and the `ClassLoaderUtils.recordClassloadFilterAudit(String, String)` /
`emitClassloadFilterAuditSummary(String)` audit bridges. There is no replacement name filter or
audit configuration. `SecurityPolicy.isValidModuleJar(File)` and
`isSafeFileStructure(long, int)` still reject invalid archives, files above 100 MB and archives
above 10,000 entries; these checks do not sandbox loaded code.

Module-scan diagnostics still report skipped classes through the plugin logger at `SEVERE`,
displayed as `ERROR` in the console and `error` in the panel stream. The 6.3.0 history below
records the audit's behaviour in that release, not its availability in 7.0.0.

中文补充：7.0.0 删除仅作观察的类名过滤审计、启动汇总和上述七个公开方法，不提供替代过滤器或审计配置。
模块扫描诊断仍通过插件日志报告跳过的类；JAR 结构检查、100 MB 大小上限和 10,000 条目上限保留，
但并不隔离已加载代码。下方 6.3.0 的历史记录不代表 7.0.0 仍提供该审计。

## Message catalogues and automatic fallback (as of 7.0.0)

7.0 introduces namespace-bound `Messages` and `LocaleResolver`. Modules ship UTF-8
`messages/<locale>.yml` files containing stable dotted keys and MiniMessage values, optionally
with `source-language` in their own `plugin.yml` (default `en`). Nested mappings and dotted keys
are equivalent. Scalar keys and values are read as literal strings, so unquoted `yes`, `no`,
`Yes`, `true`, `123`, `null` and `~` are not converted to YAML booleans, numbers or nulls.
Lists and non-scalar keys are rejected with warnings; nested mappings still group dotted paths.
Only `.yml` is read (`.yaml` warns), files must be UTF-8, and a UTF-8 BOM is accepted.
`§` and `&` colour codes are not interpreted in these new catalogues.

- **New installations:** `language: auto` follows each player's current client locale.
  `fallback-language: auto` chooses `zh` for the system time-zone IDs `Asia/Shanghai`,
  `Asia/Urumqi`, `Asia/Hong_Kong`, `Asia/Macau` and their listed aliases `PRC`, `Asia/Chongqing`,
  `Asia/Chungking`, `Asia/Harbin`, `Asia/Kashgar`, `Hongkong`, `Asia/Macao`; all other IDs choose
  `en`. UTC offset is deliberately irrelevant: `Asia/Singapore` and `Etc/GMT-8` still choose `en`.
  Startup and a successful full `/ul reload` resolve this once and report the selected language
  and reason. Player locale is never cached.
- **Explicit choices:** `fallback-language: en`, `zh` or another locale always wins, regardless
  of `language`, and bypasses time-zone detection. An automatic, blank or missing fallback first
  inherits a concrete `language`; only an automatic, blank or missing `language` uses the time-zone
  rule. Thus `language: en` with `fallback-language: auto` uses English for both players and console,
  even in China. A custom language such as `zh-myserver` supplies its official base (`zh`).
  To choose Chinese on UTC-based hosts or containers, set `fallback-language: zh`
  in `plugins/UltiTools/config.yml` and execute `/ul reload`.
- **Existing configurations:** operator files are not rewritten. An existing concrete
  `language: zh` or `en` with no fallback keeps that language as its fallback, rather than
  inheriting the newly shipped default through Bukkit defaults. In automatic player mode,
  a missing fallback uses automatic time-zone selection. This means a new UTC installation
  defaults to English, unlike 6.x's shipped Chinese default; Chinese clients still receive
  Chinese whenever their catalogue supplies it. If an operator removed `language` from an existing
  configuration, 7.0 treats it as `auto`, not the former 6.x Chinese default; it does not restore
  that key on disk.
- **Lookup:** requested locale → fallback locale → module source locale → namespaced key;
  regional locales also try their language-only catalogue. Parameters use the requested
  rendering locale, even if the message text came from a fallback catalogue. Missing keys
  warn once per key and requested locale. Ship a base catalogue such as `zh.yml` when supplying
  `zh_CN.yml` or other regional variants; a regional-only language warns on load but is not selected
  for other regions or language-only requests.
- **TPS diagnostic rounding:** in 7.0, `monitor.tps_update` intentionally uses the catalogue renderer's
  half-even rounding (`19.125` → `19.12`), rather than the 6.x `%.2f` half-up result (`19.13`).
- **Operator translations:** put only overrides in
  `plugins/UltiTools/pluginConfig/<module>/messages/<locale>.yml`, or
  `plugins/UltiTools/messages/<locale>.yml` for framework messages. New locale files add
  languages. The framework reads these files but never writes them. It rewrites its own
  `messages/reference/` examples from shipped bytes at startup; editing those examples has no
  effect. `/ul reload <module>` reloads module overrides. `/ul reload` reloads the framework's
  overrides and its locale configuration.
  Log lines, the `/ul reload` summary and module reload reasons are rendered in the server's
  fallback language; chat replies and titles use each player's language.
  If `language` and `fallback-language` are both set and differ, framework logs and reload summaries
  follow `fallback-language`.
  A malformed override file is ignored with a warning; its messages revert to the bundled text, not
  the last valid override. Deleting the override file also restores the bundled text.
  `/ul reload` also re-reads the bundled catalogue from the framework JAR on disk. Replacing or
  partially copying the JAR while the server is running can break framework messages until restart;
  stop the server before replacing the JAR.
  `/ul reload` 同时重读框架覆盖文件与语言配置；日志、重载汇总及模块重载原因使用服务器回退语言，聊天回复与标题使用各玩家的语言。
  两项语言配置不同时，框架日志与汇总跟随 `fallback-language`。覆盖文件格式错误时会告警并回到内置文案，而不是沿用上一次覆盖；删除覆盖文件也会恢复内置文案。
  重载也会重读磁盘上框架 JAR 内的目录，运行中替换或只拷贝了一部分可能导致框架消息在重启前异常，请先停服再替换 JAR。
- **Migration:** 6.x language files, sentence keys, custom language names and operator edits
  are not automatically converted to the new catalogues. Re-create the required overrides
  with dotted keys and MiniMessage syntax; see [Legacy text system removed](#legacy-text-system-removed-as-of-700).
- **Module annotation text:** `@ConfigEntry(comment = "{path}")`, `@CmdExecutor(description = …)`,
  and `@CmdParam(suggest = …)` without a matching provider method look up names only if they match
  `[a-z0-9_-]+(\.[a-z0-9_-]+)*` and are at most 128 characters. A matching key in the server fallback
  language or the module's source language renders as plain text. Other spellings and missing keys
  stay literal: comments keep their token and warn once per field per configuration load (at startup and on
  each reload); descriptions and hints keep their
  original text. A single lowercase word such as `amount` is also a valid path and resolves if the
  module has a top-level message with that name. Use a full sentence for literal descriptions and
  hints, or an explicit dotted key for translations.
  Move 6.x token translations from `lang/` to `messages/<locale>.yml`. If the wording has changed,
  list the old 6.x text in `@ConfigEntry(previousComments = …)` so the framework recognizes the
  existing comment as its own. Text that only matches an old custom `lang/` file stays as operator content.
  Modules using the legacy connector constructor have no module message catalogue, so their tokens
  do not resolve and descriptions and hints stay literal. Switch to the standard no-argument
  module constructor to use a catalogue.
- **Module config comment limits:** Changing or removing an override, or switching the server
  fallback language, can leave the previous override text in the module's `config.yml`. If it no
  longer matches a known rendering, the framework preserves it as operator content. Delete just that
  comment line (not the setting, and do not leave a bare `#`); if no other comment remains attached to
  the setting, the next successful reload or startup that can write the file inserts the current text.
  The framework itself registers modules only at startup; if third-party code constructs a replacement
  while the old instance is still running, construction-time comments use the old instance's published catalogue.

中文补充：7.0 的新装 `language` 与 `fallback-language` 均默认 `auto`。玩家跟随客户端语言；后备
语言只按上述 11 个系统时区 ID 选 `zh`，其他选 `en`，不按时差猜测。云主机和容器常用 UTC，因此
新装默认英文，与 6.x 出厂中文不同；想要中文只需写 `fallback-language: zh` 并执行完整 `/ul reload`。
具体后备语言始终优先、不探测时区；后备为 `auto`、空白或缺失时，先取具体 `language`，只有
`language` 也是自动、空白或缺失时才按时区选。`language: en` 加后备 `auto` 在中国也让控制台用英文；
`zh-myserver` 等自定义名称的后备取官方前缀语言 `zh`。已有配置继续沿用原语言，绝不改写服主文件。
若服主从已有文件删去了 `language`，7.0 按 `auto` 处理，不再取 6.x 的中文默认，也不把该键写回。
时区在启动和配置重载时解析，玩家语言每次投递时读取。
模块的注释令牌、命令描述及无对应方法的补全提示，只有名称符合 `[a-z0-9_-]+(\.[a-z0-9_-]+)*`
且最多 128 个字符，才会按服务器后备语言、再按模块源语言查找模块 `messages/` 中的纯文本。
不符合规则或找不到键时，描述和提示照原文显示，注释保留令牌；每次加载配置（启动以及每次重载）时，每个字段最多告警一次。
`amount` 之类的单个小写词也是合法路径；若与目录中的顶层消息同名，就会被解析。
不需要翻译的描述和提示建议写成完整句子，需要翻译时用带点的键。
6.x 的令牌译文须搬到 `messages/<locale>.yml`；若措辞有变，须把 6.x 的旧文字列入
`@ConfigEntry(previousComments = …)`，框架才会认出自己写过的注释。仅匹配旧自定义 `lang/`
译文的既有注释按服主内容保留。旧连接器构造器不准备模块消息目录，注释令牌不解析，描述和提示保持原文；
要使用消息目录，请改用标准的无参模块构造器。

修改或删除消息覆盖，或切换服务器后备语言后，模块 `config.yml` 里可能仍留着上一次的覆盖文字。
旧文字不再匹配任何已知渲染时，框架会按服主内容保留。要刷新，只删那一行注释，不要删配置项或留下裸 `#`；
如果该配置项没有其他相邻注释，下次成功重载或启动且配置文件可写入时，框架会补写当前文字。
框架只在启动时注册模块；若第三方代码在旧实例运行期间构造替代实例，构造期注释会使用旧实例已发布的目录。
翻译顺序为请求语言、后备语言、模块源语言、键本身；参数仍按请求语言格式化。服主只改上级覆盖
文件，`reference/` 是框架每次启动重写的只读参考；`/ul reload <模块>` 重载该模块的覆盖，完整的 `/ul reload` 同时重读框架覆盖。6.x 的语言
文件、自定义名称和修改不自动迁移，新目录使用点分键与 MiniMessage，不解释 `§` / `&` 颜色码。
旧接口和文件的替换方式见下方「[7.0 移除旧文本体系](#legacy-text-system-removed-as-of-700)」。
语言标量键和值一律按文字读取（`yes`、`No`、`123`、`null`、`~` 不做 YAML 类型转换），列表与非标量键
拒绝并告警，嵌套映射仍用于点分键分组。仅读取 `.yml`，`.yaml` 会告警；文件必须为 UTF-8，允许 BOM。
只带 `zh_CN.yml` 等地区文件却没有 `zh.yml` 时会提示补基础语言文件，不把地区变体回退规则改成模糊匹配。

### External plugin messages (as of 7.0.0)

A connected `JavaPlugin` that ships a top-level `messages/<locale>.yml` in its own JAR
gets an injected `Messages` bound to its plugin name, normalized just like a module name.
The filename before `.yml` must be a locale accepted by the text engine (for example, `en`
or `zh-CN`); other bundled files such as `messages/config.yml` are ignored. The catalogue
format and recipient-locale rules are the same as for modules. The source language comes from
`source-language` in `paper-plugin.yml`, or in `plugin.yml` when there is no `paper-plugin.yml`,
and defaults to `en` when absent or invalid. Without a recognized bundled locale file, no
catalogue is loaded or directory created, and the plugin inherits the framework `Messages`
instance, **with no guarantee about its namespace**. For a framework key, pass an explicit
key such as `MessageKey.of("ultitools", "ul.help")` rather than deriving it from the injected instance.

Put overrides in `plugins/<plugin name>/messages/<locale>.yml`. After a successful connection,
`plugins/<plugin name>/messages/reference/` contains informational copies of the bundled files;
changing those copies does not change messages. A refused connection does not write copies.
Disconnecting withdraws the in-memory catalogue but leaves message files on disk. `/ul reload`
does not refresh these overrides; the plugin can call `UltiToolsAPI.reloadMessages(this)` in
its own reload command. This is a no-op for a connected plugin without a bundled catalogue and
throws `IllegalStateException` for an unconnected plugin.

A plugin that ships a catalogue cannot connect if its normalized namespace is reserved by
UltiTools, matches a loaded module, or is already published by another external plugin.
If the external plugin publishes first, a later module trying to publish that namespace is
refused instead, even if its name matches exactly. A legacy connector module that does not
publish a catalogue cannot replace an external catalogue either.

外部 `JavaPlugin` 在自身 JAR 的 `messages/` 顶层带有 `messages/<locale>.yml` 时，连接后注入绑定自己
命名空间的 `Messages`。去掉 `.yml` 后的文件名须是文本引擎认可的语言标识（如 `en`、`zh-CN`），
`messages/config.yml` 等其他随包文件会被忽略。源语言可在 `paper-plugin.yml`（没有时用 `plugin.yml`）
声明 `source-language`，未声明或无效则用 `en`。未随包提供合法语言文件的插件继承框架实例，但不承诺
它的命名空间，也不创建消息目录。服主覆盖放在
`plugins/<插件名>/messages/`，成功连接后生成的 `reference/` 是参考副本，改它不会生效。
`/ul reload` 不刷新外部覆盖；插件须调用 `UltiToolsAPI.reloadMessages(this)`。拒绝不落盘，断开不删文件。
消息命名空间已被框架、模块或另一个外部目录占用时，连接被拒绝；外部目录先发布时，后加载的标准模块也不能占用它。

## 7.0

- Inject `com.ultikits.ultitools.scheduling.Scheduler` with `@Autowired` in module beans. Each module
  instance owns its scheduler; unloading it cancels pending and repeating work without affecting a
  same-name replacement. An already running one-shot task may finish.
- Do not submit tasks from `onUnregister()` or `@PreDestroy`: the module scheduler has already retired
  and rejects submissions with `RejectedExecutionException`. Perform unload cleanup directly.
- Literal `@Scheduled(async = true)` periods use wall-clock time (50 ms per configured tick). A lagging
  server no longer stretches those periods. Config-bound async tasks still use a global tick trigger.
- A negative literal `@Scheduled(delay = ...)` now refuses the module load; 6.x treated it as zero.
  Use `delay = 0` for no initial wait rather than a negative sentinel.
- A one-shot task submitted through `run`, `runLater`, or `execute` that throws logs exactly one
  WARNING with the owner's name and the original throwable, ends `FAILED`, and completes its handle
  exceptionally. A failing `supply` reports only through its future; if that future was cancelled
  after the body started, the otherwise unobservable failure is logged once instead. Repeating
  failures still log one WARNING per failed execution and do not stop repetition.
- Set `datasource.flushRate` to a positive number of seconds for periodic JSON flushes. A negative
  value no longer causes the single background flush that 6.x performed: no periodic flush is
  started. The normal shutdown checkpoint still saves the JSON data. Correct the value and restart.

中文补充：`run`、`runLater`、`execute` 提交的一次性任务抛出异常时，恰好记录一条带拥有者名称与
原始异常堆栈的 WARNING，句柄进入 `FAILED`，完成阶段以该异常失败。`supply` 的异常只交给返回的
future；若任务开始后该 future 已被取消，无法再观察到的异常才记录一次 WARNING。重复任务仍每次
失败记录一条 WARNING，并继续后续执行。`datasource.flushRate` 必须设置为正数秒；负数不再像 6.x
那样触发一次后台落盘，而是不启动周期刷新。正常停服的最终落盘不变；请修正该值并重启服务器。

A failed module load also retires that instance's injected scheduler and cancels pending and repeating
work submitted during bean construction, `@PostConstruct`, or `registerSelf()`. An already running
one-shot task may finish. This guarantee applies to the module-owned scheduler, not direct Bukkit tasks.

### Chest backend known limitations (as of 7.0.0)

- Framework chest inventories contain display copies, not independent real items. The backend cancels
  clicks and drags before applying its own transfers. Another plugin must not clear that cancellation
  with `setCancelled(false)` or `setResult(ALLOW)`: doing so lets vanilla move the display copies and
  can duplicate items. If unexpected item moves occur, reproduce on a test server without inventory,
  login/freeze or click-modifying plugins, then re-enable them individually and check which listener
  clears the cancellation. Fix that plugin's event handling; do not treat display copies as stored items.
- Number-key swaps and Shift moves depend on the `InventoryAction` Paper computes from the displayed
  copy. While a redraw is pending, an empty or stale copy can produce `NOTHING` or another unsupported
  action, so that input is ignored even if the real container has changed. Wait for the redraw and
  retry; do not repeatedly force the action or modify the display inventory directly.

中文补充：箱子界面里的物品只是展示副本。框架会先取消点击和拖拽，再通过容器执行真实转移。
其他插件不得用 `setCancelled(false)` 或 `setResult(ALLOW)` 清掉取消状态，否则原版可能移动展示
副本并复制物品。出现异常移动时，应在测试服暂时移除修改物品栏、登录冻结或点击事件的插件，
逐个恢复以定位清除取消状态的监听器，修正该插件的事件处理，不把展示副本当作真实库存。
数字键与 Shift 移动依赖 Paper 根据展示副本算出的 `InventoryAction`；重绘尚未到达时，空或过期的
副本可能使动作变成 `NOTHING` 或其他不支持的动作，本次输入因此不生效。请等重绘完成后重试，
不要反复强制动作，也不要直接修改展示物品栏。

## Two-stage Paper startup and known limitations (as of 7.0.0)

The framework JAR now contains `paper-plugin.yml`, not `plugin.yml`. Module JARs still use their
own `plugin.yml`. Paper runs the framework bootstrap before loading worlds; module discovery,
IoC and normal enable hooks still run during plugin enable. If the entire bootstrap preparation
fails, the framework reports the retained error and is disabled during enable; it does not run
successfully with an empty module set.

- **Runtime downloads need network access or a populated cache.** Paper resolves the five coordinates
  in `ultitools-libraries.txt` before the framework can add its own diagnostics. If a download fails,
  Paper may report only `Error resolving libraries`. Check connectivity and access to the Maven mirror.
  To use another mirror, set Paper's `PAPER_DEFAULT_CENTRAL_REPOSITORY` environment variable to its Maven
  repository URL before starting the server. Already cached libraries can be reused without a download.
- **Restart the server after changing JARs.** `/ul reload` reloads configuration, not classes. Server
  `/reload`, plugin-manager hot reload, and disabling then re-enabling UltiTools without a full restart
  are unsupported: the module loader is closed when the framework disables and is not rebuilt by re-enable.
- **A stop during world loading can undo a pending update.** Updates are applied before worlds load,
  but confirmed only after modules finish loading during enable. A stop or crash in that interval leaves
  the update unconfirmed; the next start restores the previous JAR. Let startup finish before stopping.
- **A failed module hook is not a separate class sandbox.** Its module is not enabled, but its JAR remains
  on the shared module class path. Other modules that use its classes can still be affected, especially
  by a failed static initializer. Do not initialize shared libraries that need Bukkit during bootstrap.
- **Declare every server-plugin dependency you use.** Declared `depend`/`softdepend` plugins are visible
  during enable; undeclared plugins are not guaranteed to be visible. A dependency's own loader may expose
  its transitive dependencies (for example through Vault). Such incidental visibility is not a supported
  dependency declaration and must not be relied on.

## Module bootstrap content channels (as of 7.0.0)

An optional `ModuleBootstrap` declared by `bootstrapper:` in the module's `plugin.yml` can declare
new enchantments, item and enchantment tag changes, and data packs bundled in its own JAR. Calls
only declare intent until the hook returns normally; if it throws, no declarations from that hook
are written. Modules must not retain these channels for use during enable or reload. For example:

```java
public final class MyBootstrap implements ModuleBootstrap {
    @Override
    public void bootstrap(ModuleBootstrapContext context) {
        // plugin.yml name: MyModule
        Key edge = Key.key("mymodule:frost_edge");
        Key sword = Key.key("minecraft:diamond_sword");
        context.enchantments().register(edge, (builder, tags) -> builder
                .description(Component.text("Frost Edge"))
                .supportedItems(RegistrySet.keySet(RegistryKey.ITEM, RegistryKey.ITEM.typedKey(sword)))
                .weight(5).maxLevel(3)
                .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 10))
                .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(21, 10))
                .anvilCost(2).activeSlots(EquipmentSlotGroup.MAINHAND));
        context.tags().addToTag(RegistryKey.ENCHANTMENT, Key.key("minecraft:in_enchanting_table"), List.of(edge));
        context.tags().setTag(RegistryKey.ITEM, Key.key("mymodule:frost_weapons"), List.of(sword));
        context.datapacks().embedded("frost");
    }
}
```

Use the module name's `MessageKey.namespaceFor` namespace (lowercase, with characters other than
`a-z`, `0-9`, `_`, `.` and `-` replaced with `_`) for new enchantments and tags created or replaced
via `setTag`. `addToTag` can extend tags in other namespaces. Only the `ITEM` and `ENCHANTMENT`
registries are supported by the tag channel. Tags and data pack discovery replay the declared data
on a native data reload; module code is not run again.

Data pack names use `[a-z0-9_-]{1,32}`. A declared pack appears as `UltiTools/mymodule.frost` in
`datapack list`. The module supplies its own `datapacks/frost/pack.mcmeta` and resources in the JAR;
merely shipping the directory does not activate it. World-generation data under
`data/<namespace>/{worldgen,dimension,dimension_type}/`, including in pack overlays, refuses the
entire module in 7.0.0. This can be reconsidered after platform testing. `timeline/` and
`world_clock/` are outside this check; removing overrides for those registries has not been verified.

Before removing a module, back up your world and check which kinds of content it provided:

- **Enchantments:** Items carrying a removed enchantment lose it permanently when their chunks are loaded and saved.
- **Data packs:** Their recipes and advancements disappear.
- **Tags:** The item categories they added no longer apply.

`bootstrap/contributions.json` and `bootstrap/datapacks/` under the UltiTools data directory are
framework-owned; do not edit them. The record tracks what modules declared, not what Paper
accepted. If a Paper callback fails after some of a module's entries have already been written,
Paper cannot undo those writes during the same start; they remain until the next restart. A module
whose bootstrap succeeds but whose enable fails contributes its content again on every start that
its JAR remains installed and its bootstrap succeeds. To stop those contributions, remove the
module's JAR and restart the server.

**Known limitations:**

- At startup, if a tag refers to another module's enchantment and that enchantment fails to register,
  the module declaring the tag also fails to load.
- If a failed start for a module causes its generated data pack ZIP to be pruned, a prior
  `datapack disable` for that pack is reset. Paper enables the pack again when it reappears.

These channels are experimental because they depend on experimental Paper APIs. Future 7.x releases
may change them when Paper changes; any such changes will be listed here.

**中文补充：** 模块可在 `plugin.yml` 的 `bootstrapper:` 中声明引导器。用 `enchantments()` 登记新附魔，
用 `tags()` 增补或建立物品与附魔标签，用 `datapacks()` 声明 JAR 内的数据包；引导器正常返回后才生效。
新附魔和 `setTag` 的键使用 `MessageKey.namespaceFor` 从模块名得出的命名空间；`addToTag` 可以增补其他
命名空间的标签。模块自行提供 `datapacks/<包名>/pack.mcmeta`；如 `MyModule` 的 `frost` 包在
`datapack list` 中显示为 `UltiTools/mymodule.frost`。7.0.0 拒绝任何包含 `worldgen/`、`dimension/`
或 `dimension_type/` 世界生成数据（含叠加目录）的模块数据包；该模块不加载，待实测后再考虑放开。
限制不检查 `timeline/` 和 `world_clock/`，移除对这两种注册表的覆盖尚未经验证。

移除模块前请备份世界：加载并保存区块后，物品上的自定义附魔会永久丢失；数据包带来的配方与进度会消失；
新增的物品分类会失效。UltiTools 数据目录下的 `bootstrap/contributions.json` 和 `bootstrap/datapacks/`
由框架维护，不要手动编辑。台账记录的是声明，不是 Paper 的写入结果；写入过程中失败时，已写入的内容
可能留到下次重启。如果模块引导成功却在启用阶段加载失败，只要 JAR 还在且引导成功，每次启动仍会写入其内容。
要停止写入，请移走该模块的 JAR，再重启服务器。

**已知限制：**

- 启动时，如果模块的标签引用了另一个模块的附魔，而该附魔未能注册，声明标签的模块也会加载失败。
- 如果模块某次启动失败导致生成的数据包 ZIP 被清理，先前对该包执行的 `datapack disable` 会失效；数据包再次出现时，Paper 会重新启用它。

这些通道依赖 Paper 的实验性接口，后续 7.x 版本如有调整会记在本文。

## Module changes wait for a restart (as of 7.0.0)

`/upm install`, `/upm update` and `/upm uninstall` record changes for the next start instead of changing running modules. Use `/upm pending` to see them and the results of this start, or `/upm revoke <module>` to undo one entry before restarting. When the server is connected to UltiCloud, its owner can view pending changes and revoke them individually in the panel; there is no server-side switch for this feature. As with `/upm revoke`, entries that failed to apply at the last start, need manual attention on the server, or were left unfinished by the last start cannot be revoked in the panel.

**Known limitation — panel revocation timeouts.** A revocation queued behind a long-running request on the same connection may still run after the panel reports a timeout. Refresh the pending list before deciding whether to retry.

- A module has one pending entry. Installing a different version of a running module queues an update.
- Installing the exact running version is a no-op, or cancels a pending update or uninstall.
- Installing or updating to a different version replaces the pending install or update; requesting the same pending version changes nothing.
- Uninstalling a module that is only waiting to be installed cancels that install without queuing a removal.
- Uninstalling replaces any pending update and cancels a download in progress, even when an uninstall is already queued. A repeated uninstall with no download to cancel leaves the existing entry unchanged.
- An update does not override a pending uninstall. Revoke the uninstall first, or use `install` to replace it with the specified version.

A new install checks the JAR's module identity and version before queuing. Unlike 6.x, 7.0 refuses a new install before downloading if its target filename already exists in the modules directory. Move or delete that file and retry. An update can still replace the running module's own recorded JAR. If a conflicting file appears after queuing, startup leaves it alone and records that the install was not applied; later starts do not repeat the failed install. A failed entry cannot be revoked. A new request for that module reports the earlier failure before attempting the new change.

Uninstall cannot disable a module immediately. If a module needs to stop urgently, restart the server after queuing its uninstall. UltiTools does not provide a restart button. A new install that does not load, including when its bootstrap hook throws, is removed again and reported in the start log. An installation whose transaction record cannot be saved is reverted before loading if the JAR can be moved; otherwise startup reports that the rollback could not finish. When an uninstall detects a different file at restart, it leaves that file alone and drops the deletion for it. The module can still load; run `/upm uninstall` again if it should still be removed. Operators are reminded once per server run on joining; if a change is added or replaced, they can receive a new reminder at their next join, but nothing is pushed to operators already online.

Uninstall warns without asking for a second confirmation if a module added custom content:

- **Enchantments:** Items remain, but a missing custom enchantment is permanently stripped once the item's chunk loads and saves. Other plugins can get `IllegalStateException` when reading affected items saved as raw bytes while the enchantment is absent; this does not crash the server.
- **Data packs:** The recipes and advancements supplied by those packs disappear.
- **Tags only:** The item categories added by the module stop working.
- **Unknown content:** If the record could not be read, check what the module added before uninstalling.

If the content is still needed, revoke the uninstall before restarting. Transaction records are replaced atomically but are not synced to disk with `fsync`; a power loss or system crash can lose the most recent queued request. After restarting, check `/upm pending`. Install and update require the modules and transaction directories to be on the same file system; an unavailable atomic rename leaves the modules unchanged and the start log names both directories. On Windows, an open JAR that cannot be moved during an install rollback is removed at a subsequent start. Uninstall does not queue JAR links that resolve outside the modules directory; move the JAR into the modules directory or remove the link with the server stopped. Downgrade to 6.x is unsupported: 6.x does not understand `INSTALL` transaction records and leaves them unapplied. For test fixtures only, the JVM property `ultitools.catalogue.url` accepts a loopback catalogue URL. The internal `PluginInstallUtils.uninstallPlugin*` methods and their immediate-unload exceptions are replaced by `requestUninstall`; `PluginManager.unregister` still performs the framework's lifecycle cleanup.

| 6.x response | 7.0 response |
| --- | --- |
| Installed; restart and manually delete the old module | `upm.install.queued`, or `upm.install.as_update` if running; do not delete the old JAR manually |
| Seven install refusals | Same wording under `upm.install.refused.*`; a known staging failure now explains its reason |
| A new install could overwrite a same-name file | `upm.install.refused.target_exists`; move or delete the file first |
| Uninstalled; identified JARs were deleted | `upm.uninstall.queued`; the module keeps running until restart |
| The unload hook threw after the module was removed | No immediate unload, so the response no longer occurs |
| The loaded module was unloaded but no JAR was found | `upm.uninstall.no_jar`; nothing is queued |
| The JARs could not be deleted now and were deferred | All uninstalls are deferred; `upm.uninstall.queued` |
| Could not delete JARs; delete them manually | `upm.uninstall.not_recorded` if the request could not be saved |
| A staged update was cancelled by uninstall | `upm.uninstall.also_cancelled_update` |
| The update was staged for the next restart | `upm.update.staged`, with a revoke hint |
| There is already a staged update | `upm.pending.unchanged` for the same version; `upm.update.replaced` for a different version |
| New in 7.0 | `/upm pending`, `/upm revoke`, operator reminders and custom-content warnings |


The `/upm list`, `versions`, `check`, `update`, and `help` replies and the start-up log lines for module updates and transaction records now use the framework's `upm.*` message catalogue. The wording is unchanged. Commands reply in the sender's configured locale; start-up logs use the server's fallback locale. Operators can override the bundled messages in `plugins/UltiTools/messages/<locale>.yml`. The console's `/upm list` now uses the same colours as the player's list where they share a line, except for the uncoloured separator. Each list row is sent as a separate message. If start-up exits before the text engine is ready, transaction reports log only the namespaced message key and its argument values. Installation refusals for licensing or integrity checks now use the message catalogue in the server's fallback locale.

**中文补充：** 安装、更新、卸载先记入待生效清单，运行中的模块不变。用 `/upm pending` 查看，用 `/upm revoke <模块>` 逐项撤销。同一模块只保留一项；安装当前版本会取消待更新或待卸载，卸载尚未生效的安装会抵消安装，卸载也会替换待更新，并取消同一模块仍在进行的下载，即使已有卸载记录。失败项不能撤销，下次请求该模块时会报告。7.0 新装不再像 6.x 那样覆盖同名文件；请移走或删除该文件后重试。卸载不再当场停用，急需停止请重启。新装暂存时核对身份与版本；加载失败（包括引导钩子抛异常）会还原并记录。记录写不进时，启动会尝试在加载前还原；无法还原则会明确警告。卸载时文件身份不符就放弃该文件的删除；模块仍可加载，需要时重新执行卸载命令。每位 OP 本次运行首次上线提示，新增或替换后下次上线可再提示。卸载前若仍需要自定义附魔、数据包或标签，请撤销；附魔会在物品区块加载保存后丢失，数据包配方和进度消失，标签分类失效。事务记录没有调用 `fsync`，断电或系统崩溃可能丢失最近排队的请求，重启后请用 `/upm pending` 核对。目录需在同一文件系统；Windows 上被占用的 JAR 可能要下次启动移除；不支持降级到 6.x。

服务器连接 UltiCloud 时，所有者可以在面板查看并逐项撤销，无需在服务器上开启额外开关。与 `/upm revoke` 一样，上次启动未能应用、需要在服务器上手动处理或上次启动未处理完的条目，都不能从面板撤销。

**已知限制——面板撤销超时。** 如果撤销排在同一连接上一个长时间运行的请求之后，面板即使先显示超时，撤销也可能稍后执行。请先刷新待生效清单，再决定是否重试。

`/upm list`、`versions`、`check`、`update`、`help` 的回复及模块更新与事务记录的启动日志改用 `upm.*` 消息目录，原文不变；命令回复按发送者语言，日志按服务器回退语言，服主可在 `plugins/UltiTools/messages/<语言>.yml` 覆盖，控制台列表也带颜色（分隔线除外），列表改为逐行发送。若启动在文字引擎就绪前提前退出，事务报告只记录带命名空间的消息键和参数值。因许可证或完整性检查而拒绝安装时，日志改用消息目录，并按服务器回退语言输出。

## UI storage waits at shutdown (as of 7.0.0)

All modules share one approximately 10-second UI-storage wait budget at server stop; only time spent unloading UI storage is charged, not time spent in other module shutdown hooks. Once storage waits exhaust that budget, later modules receive no remaining wait and finish with `UNKNOWN_KEPT_BY_STORE` without returning items, while an ordinary single-module unload retains its own approximately 10-second limit.

中文补充：停服时所有模块的界面存储等待共用约 10 秒预算，只扣除界面存储卸载等待本身的耗时，不扣其他模块卸载钩子的耗时；存储等待耗尽预算后，后面的模块没有剩余等待时间，按 `UNKNOWN_KEPT_BY_STORE` 结束且不退还物品；非停服的单模块卸载仍各有约 10 秒上限。

### Live-unload waits and Paper watchdog warnings

When a module is unloaded, reloaded or updated while the server is running, command and UI-storage
waits happen on the server thread. If an async command body has not finished within about ten seconds,
or a storage-backed screen has unsaved changes and its store does not answer within that wait, Paper's
default `watchdog.early-warning-delay` of 10000 milliseconds in `config/paper-global.yml` can produce
`The server has not responded for 10 seconds! Creating thread dump` and a thread dump showing the
framework's waiting call stack. This warning is not a crash: the server continues running after the
wait. An unanswered UI store leaves the session with `OWNER_UNLOADED` and `UNKNOWN_KEPT_BY_STORE`.
Investigate the unfinished command body or the store that did not answer, not the server itself.
Command waiting and UI save waiting have independent allowances; if both use their full allowances,
server shutdown or a live module unload can wait about **twenty seconds**, excluding other cleanup.
Paper can repeat the warning during a live-unload wait (default `watchdog.early-warning-every`:
5000 milliseconds). These early
warnings are not emitted during server shutdown.

中文补充：服务器运行中卸载、重载或更新模块时，命令与界面存储等待都发生在服务器线程上。
如果异步命令体约 10 秒内没有结束，或存储型界面有未保存的改动而存储在这段等待中没有答复，
Paper 默认的预警延迟（`config/paper-global.yml` 中 `watchdog.early-warning-delay`，10000 毫秒）
可能触发控制台日志 `The server has not responded for 10 seconds! Creating thread dump` 与线程转储，
其中能看到框架等待的调用栈。这不是崩溃，等待结束后服务器继续运行；存储未答复的界面会话以
`OWNER_UNLOADED` 与 `UNKNOWN_KEPT_BY_STORE` 结束。不需要处理服务器本身，应检查那个没有结束的
命令体或没有答复的存储。命令等待与界面保存等待的预算各自独立，两者都等满时，停服或运行中
卸载最坏会等待约 20 秒，不含其他清理；运行中卸载等待期间 Paper 可能重复预警
（`watchdog.early-warning-every` 默认 5000 毫秒）。停服过程中不会发出这种预警。

## Game-interrupted screens (as of 7.0.0)

For chest grids and bound-container screens, only a player's own close maps to `PLAYER`. Paper's close reasons
`DEATH`, `TELEPORT`, `OPEN_NEW`, `PLUGIN`, `UNLOADED` and `UNKNOWN` map to `INTERRUPTED` unless the
framework has already selected its own outcome. `DISCONNECT` maps to `DISCONNECTED`. `CANT_USE` waits
for a quit in the same tick (`DISCONNECTED`), otherwise the next cleanup tick selects `INTERRUPTED`.
This does not add a tick to storage cleanup: changed COMMIT containers still save through the original
close continuation; unchanged containers do not write. A refused first chest open or title reopen also
ends immediately with `INTERRUPTED`, even when Paper returns a non-null player-inventory view.

A shown Dialog (notice, confirmation, choice or form) ends with `Closed(INTERRUPTED)` when its player dies,
changes worlds, or another inventory opens. Cancelled death and inventory-open events do not interrupt it.
The result follows the existing viewer-context delivery path; the framework does not close the newly opened
inventory. Showing the next framework screen still ends the old session with `REPLACED`; explicit session
close, owner unload and timeout retain `CLOSED_BY_CODE`, `OWNER_UNLOADED` and `TIMED_OUT` for both backends.
Paper has no notification for other ways a Dialog can be displaced, such as another plugin showing its own
Dialog, so those still rely on `TIMED_OUT`. Give long-lived Dialogs a suitable explicit `ShowOptions` timeout
instead of relying on the ten-minute default.

Before showing either backend, if the player's current menu is their own survival or creative backpack and
its cursor or 2x2 crafting inputs are nonempty, the framework closes that menu through vanilla's cleanup:
items return to the backpack, or drop at the player's feet when it is full. They are not carried into the
new screen. An empty backpack causes no extra close, and an already-open foreign container is left to
Paper's normal opening rules. Known limitation: do not open another container for the same storage key while
an existing screen for that key has unsaved changes; end the old screen and await its commit before reading
and showing the key again. The framework does not merge independent container copies.

中文补充：箱子 Grid 与绑定容器只有玩家主动关窗才返回 `PLAYER`。Paper 的 `DEATH`、`TELEPORT`、
`OPEN_NEW`、`PLUGIN`、`UNLOADED`、`UNKNOWN` 均映射为 `INTERRUPTED`（框架已选定自己的结果时除外）；
`DISCONNECT` 为 `DISCONNECTED`。`CANT_USE` 等待同 tick 的退出事件：退出为 `DISCONNECTED`，否则在
下一次清理 tick 选 `INTERRUPTED`，不会给存储清理再多加一个 tick；有改动的 COMMIT 仍按原关闭续体保存，
未改动的不写。首次打开或标题重开被拒绝也立即以 `INTERRUPTED` 结束，即使 Paper 返回非空背包视图。
Dialog（通知、确认、选择、表单）在玩家死亡、换世界或另一个物品栏打开时以 `INTERRUPTED` 结束，
取消的死亡与打开事件不打断；结果沿原玩家上下文投递，不关闭外来新窗口。两后端的框架替换仍是
`REPLACED`，显式关闭、模块卸载、超时仍分别为 `CLOSED_BY_CODE`、`OWNER_UNLOADED`、`TIMED_OUT`。
其他插件的 Dialog 顶替没有 Paper 通知，继续靠超时；长时间挂着的 Dialog 应显式设置合理超时，
不要只依赖默认十分钟。
两后端显示前，若玩家当前只开着生存或创造背包且光标或 2×2 合成输入非空，先走原版关闭清理，
把物品放回背包，满了掉在脚下，不带入新界面；空背包不额外关闭，外来容器沿用 Paper 的打开规则。
已知限制：同一存储键的界面仍开着且有未保存改动时，不要为同一键再开一个；先结束旧界面并等待提交，
再读取存储并打开。框架不合并两份独立容器副本。

## Declarative screens use the UI service (as of 7.0.0)

The declarative engine no longer inherits obliviate's `Gui`. It emits immutable `Grid` snapshots;
`UiService` owns display, click dispatch and session outcomes. Rebuild modules against 7.0.

| 6.x usage | 7.0 replacement |
|---|---|
| `super(player, id, "Title", rows)` or the `Component` title overload | `super(ui, scheduler, player, id, Text.plain("Title"), rows)`; pass the module's own injected `UiService` and `Scheduler` |
| Constructors accepting `InventoryType` | Fixed chest grids with 1–6 rows |
| `ItemDisplay.builder(itemStack).name("Buy").lore("Details")` | `ItemDisplay.builder(ItemSpec.builder(Material.STONE).name(Text.plain("Buy")).lore(List.of(Text.plain("Details"))).build())` |
| `TextButton.builder().text("Buy").lore("Details")` | `TextButton.builder().text(Text.plain("Buy")).lore(List.of(Text.plain("Details")))` |
| `onClick(Consumer<InventoryClickEvent>)` | `onClick(GridAction)`, whose `GridClick` supplies the viewer, slot, click kind and session; `onClick(Runnable)` remains |
| `IconWrapper.builder(itemStack)` | `ItemSpec.builder(Material.STONE)`; `IconWrapper` is deleted |
| `getPlayer()` | `viewer()` |
| `onGuiOpen`, `onGuiClick`, inventory-event `onClose` / `onGuiClose` | Describe content in `build`; put actions on widgets; use `onGuiClose(UiResult<Ack>)` after the tree is disposed |
| `RenderNodeDiffer` / `DiffResult` | Removed; backend-owned grid comparison replaces engine-owned inventory diffs |

For example, the old string/event-based button:

```java
super(player, "shop", "Shop", 1);
// In build(...):
ItemDisplay.builder(new ItemStack(Material.STONE)).name("Buy")
    .onClick(event -> event.getWhoClicked().sendMessage("Bought")).build();
```

becomes a description plus a grid action:

```java
super(ui, scheduler, player, "shop", Text.plain("Shop"), 1);
// In build(...):
ItemDisplay.builder(ItemSpec.builder(Material.STONE).name(Text.plain("Buy")).build())
    .onClick(click -> click.viewer().sendMessage("Bought")).build();
```

`open()` now returns a `GridSession`. The title and row count are fixed per page instance.
Call `open()` on the viewer's entity thread. State mutations happen on the calling thread;
only rebuilding is marshalled to the viewer's entity thread. Hand off background results with
`scheduler.entity(viewer).run(...)` before mutating state.

`ItemSlotArea` is new. It binds existing container indexes without copying or moving their items:

```java
ItemSlotArea.builder(container).firstIndex(page * 9).count(9).startSlot(0).build(); // VIEW
ItemSlotArea.builder(container).firstIndex(page * 9).count(9).startSlot(0)
    .mode(BindingMode.EDITABLE).build(); // Explicit opt-in to moving items
```

A partial last page binds only existing indexes and leaves the remaining area empty. A negative
`firstIndex`, or one at/after the end of a nonempty container, is rejected. An empty container accepts
only `firstIndex(0)`. Negative counts and areas outside a six-row grid are also rejected.

中文补充：**7.0 声明式界面不再继承 obliviate `Gui`，改为向界面服务提交不可变 `Grid`。**
上表与示例给出旧写法到新写法的对应：构造器传模块自己的 `UiService`、`Scheduler` 与 `Text` 标题；
不再接受 `InventoryType`。`ItemDisplay` 使用 `ItemSpec`，`TextButton` 的名称和说明使用 `Text`；
点击改为 `GridAction` / `GridClick`（无参 `Runnable` 仍可用）。删除 `IconWrapper`、`RenderNodeDiffer`
和 `DiffResult`；`getPlayer()` 改为 `viewer()`，旧打开／点击／关闭事件钩子改为组件动作和
`onGuiClose(UiResult<Ack>)`。`open()` 返回 `GridSession`，每个界面实例的标题和行数固定。
状态修改在调用线程立即生效，重建才调度到观看者的实体线程；后台结果应先通过模块调度器切回该线程。
新增 `ItemSlotArea` 默认只读（`VIEW`），允许移动物品必须显式 `.mode(BindingMode.EDITABLE)`；
末页不足时只绑定存在的下标，其余留空，不移动或复制容器内容。负起始下标、非空容器中大于等于大小的
起始下标仍拒绝；空容器仅接受起始下标零，负数量和超出六行网格的区域也拒绝。

## Adventure chat confirmation callbacks (as of 7.0.0)

`ChatCallbackManager` and its hidden `/ultitools_callback` command are removed. Module authors
who used that manager directly must migrate to Adventure's `ClickEvent.callback` with
`ClickCallback.Options.builder().uses(1).build()` for a one-use callback.
The framework's own chat confirmation (`Confirm.chat`/`ChatConfirm`) is removed as well; see the section above.

中文补充：7.0 删除 `ChatCallbackManager` 与隐藏命令 `/ultitools_callback`，直接使用过它的模块
须改用 `ClickEvent.callback`；框架自己的 `Confirm.chat` / `ChatConfirm` 也已删除，替代写法见上节。

## Notifications, toasts and holograms (as of 7.0.0)

`widgets.Toast` and `NotificationService#sendToastNotification` are removed. The advancement
implementation depended on deprecated, version-unstable server internals, and message text
containing quotes could break its generated JSON. Use `sendTitleNotification`,
`sendActionBarNotification` or chat instead. Rebuild callers against 7.0 after migrating.

`widgets.Hologram`, which spawned an invisible armour stand for each text line, is also removed.
Use Paper's native `TextDisplay` API instead; a single display can contain multiple lines.
The following example uses the module's injected `Messages` service (`messages`) for shared text
and disables entity persistence. Call `World#spawn` on the thread that owns the location, switching
with the module's injected `Scheduler` first; `Location#getWorld()` can return null, so check it
before spawning. The caller owns the display and removes it when no longer needed.

```java
World world = location.getWorld();
if (world == null) {
    throw new IllegalArgumentException("The display location must have a world");
}
TextDisplay display = world.spawn(location, TextDisplay.class, d -> {
    d.text(messages.shared(Text.of(messages.key("hologram.welcome"))));
    d.setBillboard(Display.Billboard.CENTER);
    d.setPersistent(false);
});
// later: display.remove();
```

中文补充：7.0.0 删除 `widgets.Toast` 和 `NotificationService#sendToastNotification`，旧实现依赖已弃用、
跨版本不稳定的服务端内部接口，消息中的引号还可能破坏生成的 JSON。请改用 `sendTitleNotification`、
`sendActionBarNotification` 或聊天，并在迁移后针对 7.0 重新构建调用方。

`widgets.Hologram` 也被删除，旧实现为每行文字生成一个隐形盔甲架；请改用 Paper 原生 `TextDisplay`，
单个显示实体即可包含多行文字。示例中的 `messages` 是模块注入的 `Messages` 服务，用于生成共享文本；
`World#spawn` 必须在拥有目标位置的线程上调用，请先通过模块注入的 `Scheduler` 切换线程，
并在生成实体前检查可能为 null 的 `Location#getWorld()`。示例关闭实体持久化，调用方负责管理实体，
不再需要时调用 `display.remove()`。

### Notification service migration

`NotificationService` renders `Text` with the recipient's resolved locale, including keys from other
module namespaces. Its twelve sending methods return `void` (four abstract methods and eight default
convenience overloads), rather than reporting unreliable boolean success. Chat, action bars and titles
are sent on the calling thread; boss-bar creation, replacement, sound and countdown run in the player's
`Scheduler#entity` context. Methods may be called from any thread.

| Before 7.0 | As of 7.0.0 |
|---|---|
| Message, title, subtitle and action-bar `String` arguments | `com.ultikits.ultitools.text.Text` |
| Boss-bar `int seconds` | Positive `java.time.Duration`; default remains 25 seconds |
| `org.bukkit.Sound` | `net.kyori.adventure.sound.Sound`, preserving the caller's source, volume and pitch |
| Title fade-in / stay / fade-out `int` ticks | Adventure `Title.Times`; defaults remain 500 ms / 2.5 s / 1 s |
| `boolean` return values | `void`; required null arguments throw NPE naming the parameter, non-positive duration throws `IllegalArgumentException` |
| Two overloads taking a caller-owned Bukkit `BossBar` | One full overload taking Adventure `BossBar.Color` and `BossBar.Overlay` |
| `new InMemoryNotificationService()` | `new InMemoryNotificationService(Messages, Scheduler)`, or preferably inject `NotificationService` |
| Named aliases `inMemoryNotificationService` and the interface class-name string | Resolve by type: `context.getBean(NotificationService.class)` / `@Autowired NotificationService` |

```java
// Inject Messages and NotificationService. Player/operator text is an argument, never a template.
Text greeting = Text.of(messages.key("notify.hello"), Arg.string("name", player.getName()));
notifications.sendMessageNotification(player, greeting);
notifications.sendActionBarNotification(player, greeting);
notifications.sendSubTitleNotification(player, greeting);
notifications.sendTitleNotification(player, greeting, Text.plain("Ready"), null,
        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500)));
Sound sound = Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f);
notifications.sendBossBarNotification(player, greeting, Duration.ofSeconds(5),
        BossBar.Color.GREEN, BossBar.Overlay.PROGRESS, sound);
// For already-built fixed components, use Text.literal(component); for plain strings, Text.plain(value).
```

Here `Sound`, `BossBar` and `Title` are Adventure types, and the message catalogue's argument placeholder
is `<name>`. No manual MiniMessage escaping or template concatenation is needed.

A player has at most one notification boss bar. A second call reuses it but now **replaces the text,
colour and overlay**, cancels the previous countdown and restarts the complete lifetime. Previously,
the text stayed stale and concurrent countdowns made repeated notifications disappear early. Countdown
updates no longer run on Bukkit's asynchronous scheduler. They advance in 250 ms steps (five nominal
ticks); an already removed player gets no bar, and disconnecting retires the countdown and forgets the
entry. Reconnecting starts a fresh bar. A service instance no longer shares a static player cache.

The root service is constructed and cached on first type lookup, so registration order does not bind
it to the text/scheduling registration block. Both engines are present in 7.0; a custom container must
register `Messages` and `Scheduler` before resolving the service. A missing dependency currently causes
the constructor's named NPE; the failed lookup is not cached and succeeds after registration. Optional
`@Autowired(required = false)` does not swallow an exception thrown by a registered supplier.

The fifteen active module source trees were checked with a positive `UltiToolsPlugin` search control:
only `UltiSocial` calls the service in production (`SocialListener.java`, three chat calls, return values
ignored). No module directly constructs `InMemoryNotificationService`, uses its former test hooks,
looks up either removed alias or supplies a Bukkit boss bar. Modules still need the documented 7.0
rebuild; these findings do not promise compatibility for old binaries or third-party modules.

中文补充：通知服务改用 `Text` 按收件玩家语言渲染，十二个发送方法均返回 `void`，其中四个抽象方法、
八个默认便捷重载。文字参数由 `String` 改成 `Text`，Boss 栏秒数由 `int` 改成正 `Duration`，
声音由 Bukkit `Sound` 改成 Adventure `Sound`，标题 tick 参数改成 `Title.Times`。默认 Boss 栏仍为
25 秒，标题仍为 500 毫秒 / 2.5 秒 / 1 秒；声音使用调用方提供的来源、音量和音调，不再固定音量 10。
必需参数为 null 时抛出以参数名为消息的 NPE，非正时长抛出 `IllegalArgumentException`。

两个接收调用方 Bukkit `BossBar` 的重载删除，改为完整重载传入 Adventure 的颜色与样式。
实现类无参构造器改成 `(Messages, Scheduler)`；更推荐按 `NotificationService` 类型注入。
`inMemoryNotificationService` 和接口全限定类名字符串两个名称别名删除，请改用类型查询或 `@Autowired`。
聊天、动作栏与标题保留在调用线程发送；Boss 栏的创建、替换、声音和倒计时全部进入玩家实体上下文。
同一玩家仍最多一条 Boss 栏，但再次调用会替换文字、颜色、样式并重启唯一倒计时，不再文字不变或加速消失。
更新步长为 250 毫秒（名义五 tick）；已退出玩家不显示，断线退役自动清理状态，重连后可重新显示。

根容器首次按类型取服务时才构造并缓存，不依赖文本和调度服务的注册行序；7.0 已包含两种引擎。
自建容器须先注册 `Messages` 与 `Scheduler`，缺失时目前由构造器抛 `messages` / `scheduler` NPE，
失败不会缓存，补齐后可重试。`@Autowired(required = false)` 不会吞掉已注册供应器抛出的异常。
模块检查有已知必命中的对照：十五个在用模块中仅 `UltiSocial` 的 `SocialListener.java` 有三处生产聊天调用，
均不读返回值；直接构造实现类、使用旧测试钩子、查询被删别名或传入 Bukkit Boss 栏的模块调用均未发现。
请按上表示例迁移并重新构建；玩家或服主提供的内容使用 `Arg.string`，不要拼进 MiniMessage 模板，
占位符写 `<name>`。本地模块检查不构成旧二进制或第三方模块的兼容承诺。

## Config layer (6.3.0)

The public extension point is `com.ultikits.ultitools.config.convert.ConfigConverter<T>`, discovered with `@ConfigConverterFor`. The document tree and writer are internal implementation details.

### The write contract (read this first)

**Configuration an operator wrote is never overwritten automatically**. No framework or module code path may change content an operator wrote in a configuration file unless the
operator explicitly asked for that change. A value the framework cannot use is surfaced before code relies on it - the
value is refused with a warning and its declared default runs, or the module or the start is refused - and is never
"fixed" by rewriting the file. Everything else in this chapter is a consequence of this rule.

**One gate, one outcome.** Every write to an operator-editable configuration file goes through one framework write gate
(`OperatorFileWriter`, internal): the write declares the keys (or the framework's own comment lines) it owns; after
rendering, every byte outside them must equal the file as read, and the file must still hold the bytes it was read from.
Otherwise nothing is written: one WARNING names the file, the keys and the reason - for a layout the renderer cannot keep,
the line number to fix where available (see "Layouts the write gate cannot keep") - and never a value. The program keeps
its in-memory value. A module API call the operator asked for (`saveOperatorChange`, `saveOperatorMapEntry`) throws
`ConfigWriteRefusedException` instead, so the command can reply that nothing was saved and why; a panel edit gets an
error reply naming the reason. Configuration data is not moved out of configuration files.

What an operator does about a refusal: read the WARNING, correct the layout it describes or the line it names (or, for
"the file changed since it was read", run `/ul reload` first), and repeat the change; nothing they wrote has been lost.

A write that is not refused but fails to publish throws a plain `IOException`. The file then also holds its previous
bytes. A write that failed part-way after the file was opened is put back from its framework backup before the exception
is thrown; if even that fails, one SEVERE names the file and the kept backup, and the configuration refuses every write
until a reload (see "Rendering and save fallback", [#622](https://github.com/UltiKits/UltiTools-Reborn/issues/622)). So
a module that rolls its in-memory change back on the `IOException` stays in step with the file.

**A section emptied by deleting its keys is completed again** ([#620](https://github.com/UltiKits/UltiTools-Reborn/issues/620)). Deleting a key is how an operator resets a setting: the
next start puts the key back with its declared default and comment. When the deleted key was the last one of its section,
the section key is left alone on its line with nothing after the colon (`messages:`), which reads as no value at all. The
framework puts the section's keys back
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

**For operators: to leave a text setting blank, write an empty string; do not delete the key** . Write `key: ''`: for a text setting, an empty string is read as blank and is never
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
value text of the one line it names, without rewriting other lines.

### Rendering and save fallback

The internal config storage layer renders the whole YAML document through SnakeYAML, preserving content, comment text and key
order. Its existing line-terminator, BOM, final-newline and supported indentation-style rules remain in effect.

Every automatic write to a module configuration file - creating a file that does not exist, inserting a declared
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
panel edit on a file the gate refuses replies with an error naming the reason, and the file keeps its bytes. A block
comment before a list item prevents a changed write to another key, or one that keeps the list's length (see below).
A write that owns the list and changes its length can go through, but drops the item comments; this is not a comment
preservation guarantee. The storage API signatures are unchanged.

#### Layouts the write gate cannot keep

The renderer generally writes a document back in one canonical layout; as of 6.5.0 it retains the source spelling of
unchanged block scalars with trailing empty lines. If a changed write would alter any byte outside the keys
it owns, the gate refuses it - a first-start insert, a comment rewrite, a save, an operator change or a panel edit.
A no-op returns unchanged without rendering. Ownership matters: a layout change wholly inside an owned path can pass if
every other gate check succeeds. Refusing layout changes is deliberate: the rule forbids normalizing an
operator's layout to make a write pass (orchestrator decision of 2026-10-05 under the maintainer's delegation). The file
keeps its bytes, the declared or in-memory values run, and the warning names the keys and the reason. When the layout
comparison identifies a line to fix, it reports `the file's layout outside the keys this write owns would change (line N)`,
or, for a key sharing its line with another, `a key this write owns shares line N with a key it does not own`.
If the rendered text cannot be parsed back, the self-check refuses it before comparing layout lines: the reason is
`the rendered text would not parse back`, without a line number. An insert below a section written as an explicit empty
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
- changing or removing a block scalar value (`|`, `|-` or `>`) when its trailing separator lines would be lost.
  As of 6.5.0, an unchanged block scalar followed by empty lines no longer prevents inserting a missing default key
  ([#883](https://github.com/UltiKits/UltiTools-Reborn/issues/883)); its original spelling and separators are retained.
  Every other unowned byte must still be preserved, or the existing WARN refuses the write;
- a block comment before any list item (at the item's column or deeper, before the first or a later item, including
  after a block scalar or flow item): a changed write to another key, or one that keeps the list's length, is refused.
  Before a plain or block-mapping item the refusal names the line; before a flow item (`- [1, 2]` or `- {k: v}`) it says
  `the rendered text would not parse back`, without a line number. A refused write keeps the original bytes and mtime
  ([#619](https://github.com/UltiKits/UltiTools-Reborn/issues/619), pinned by
  `OperatorFileWriterLayoutRefusalListTest.aCommentBeforeAListItemRefusesAnotherKeyWithBytesAndMtimeUnchanged`).
  Move the comment above the containing list key, or remove it, before retrying. A write that owns the list and changes
  its length can go through, but drops the item comments (also pinned by that test class). This means a YAML comment,
  not `#` text indented inside a block scalar;
- in a file without a final line break, a last value written as a block scalar with `|`, `|+` or `>`: at the end of
  such a file its value has no final line break, so it is written back as `|-` or `>-` (a block scalar anywhere else
  in such a file, or a last one written `|-` or `>-`, is kept - except that a write which sets the file's last value
  to text ending in a line break is refused, naming that line; no value is ever changed);
- a comment that closes a section after a blank line, indented deeper than the key that follows it, and any comment
  indented deeper than the key below it (including in a comment-only file);
- two indentation widths in one file (two spaces in one section, four in another);
- raw non-ASCII text and double-quoted `\u`, `\U` or `\x` escapes mixed in one file: the renderer keeps one
  Unicode spelling per file, so the other spelling's line is named and the write refused (behaviour unchanged since
  6.3.0, [#836](https://github.com/UltiKits/UltiTools-Reborn/issues/836)). Use one spelling per file - raw UTF-8
  (recommended) or escapes throughout;
- mixed line endings (CRLF on some lines, LF on others);
- a plain value continued onto the next line, a tab inside a value, an explicit `? key`, an explicit tag (`!!str`);
- a file holding only a byte-order mark (an emptied file saved as UTF-8 with BOM; the reason names the mark);
- an entry holding `null` with an inline comment (`foo: ~  # placeholder`) that a write replaces with a map: the YAML
  library cannot emit the comment there, so the write is refused naming that key
  ([#624](https://github.com/UltiKits/UltiTools-Reborn/issues/624), pinned by `WriteGateRuntimeFailureTest`).

Long ordinary plain string keys (about 123 characters and above with the current SnakeYAML 2.2 options) are another ownership-dependent refusal
([#610](https://github.com/UltiKits/UltiTools-Reborn/issues/610) item 1): SnakeYAML emits the key in explicit `? key` form.
A write to another setting is refused at that line because the long key's bytes are outside its owned paths; the original
file remains unchanged. The effective boundary can be shorter than 128 characters because the emitter also counts tag
text: with the current SnakeYAML 2.2 options, the regression's plain ASCII string key keeps its layout at 122 characters,
but changes to explicit form at 123, 128 and 129 characters. If the write owns the long key itself, that rendering can pass
when every other check succeeds; long keys do **not** imply that all writes are refused. Use shorter keys to keep the simple
`key: value` layout; the framework does not raise the emitter's simple-key limit. These cases are pinned by
`OperatorFileWriterLayoutRefusalListTest`.

Kept byte for byte (a write elsewhere goes through): one space before an inline comment, a block scalar without a blank
line after it (including a comment line right after it attached to the next key, exactly as after a plain value -
[#592](https://github.com/UltiKits/UltiTools-Reborn/issues/592)), and, as of 6.5.0, an unchanged block scalar with trailing
empty lines; flow maps and flow lists without inner spaces, quoted
values, consistent four-space indentation, list items at the key's column, CRLF or LF throughout, a missing final line
break (also in a file holding block scalars, apart from the last-value case above), a byte-order mark, `#comment` without
a space, several blank lines, hexadecimal numbers, `~` and empty values. These kept layouts exclude every list item
preceded by a block comment, not just flow items (see above). A file using anchors, aliases or merge keys is refused as
a whole (above), not by line.

An insert directly after a key left with no value (`c:` with nothing after the colon) also keeps that key's bytes,
including when the inserted setting has a declared comment. The implicit empty value has no text span: its parser mark
on a later key's line does not make that line belong to it ([#628](https://github.com/UltiKits/UltiTools-Reborn/issues/628),
pinned by `OperatorFileWriterTest`). This applies to nested, dotted-section and mixed dotted-key layouts, with LF, CRLF
or no final line break. Real shared-line conflicts still refuse. To leave a text setting blank, write `key: ''`;
a key with no value is null, not an empty string.

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

#### Known limitations: shared directories and reserved map keys

- **Separate writable configuration directories per server process.** Do not point two running servers at the same
  writable configuration directory. Stale-temporary cleanup protects staged files only within its own JVM; another JVM's
  load can delete a temporary file between staging and commit, failing that write with an `IOException`. A failed
  initialization flush can leave the entity protected until reload. Use independent writable directories rather than
  sharing live files; the framework provides no cross-process staging or cleanup coordination
  ([#610](https://github.com/UltiKits/UltiTools-Reborn/issues/610) item 3).
- **Do not use the literal `==` as an ordinary map key.** Bukkit reserves it as the serialization marker. A plain map
  containing that key is treated as one serialized value for save and reload, not merged or saved entry by entry. If a
  module changes one entry while an operator changes another, the module's change can remain unsaved or be replaced by the
  file's whole-map value on reload. Use a different ordinary key name; actual Bukkit serialized objects continue to use
  their marker normally ([#610](https://github.com/UltiKits/UltiTools-Reborn/issues/610) item 7).

#### Layouts the reader cannot read

Two valid YAML layouts were observed to be unreadable by the framework's comment-preserving reader with
SnakeYAML 2.2 and comments enabled. Paper's bundled SnakeYAML version varies by server version and build;
these two layouts have not been measured on SnakeYAML 1.32, 1.33 or 2.0. The older-version configuration-write
compatibility harness does not establish their read behaviour. In the measured 2.2 environment, the framework treats such a file as **unparseable**: it is never written (no insert, comment
rewrite, save, operator change or panel edit touches it), the module runs on its declared defaults at start-up (a reload
keeps the running values), and the existing SEVERE line names the file: `Cannot load <file>: <parser location>; file will
not be overwritten`. Nothing is lost - the file keeps every byte - but the operator's values in it are not used until the
layout is changed. These are documented limitations (maintainer decisions of 2026-10-05: document only, no
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

A failure after the in-place write has opened the target no longer leaves a partial target
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
naming the keys. After this double I/O failure ([#626](https://github.com/UltiKits/UltiTools-Reborn/issues/626)), compare
the file with the kept backup, which holds exactly what the file held before the failed write, and copy the backup over
the file if needed before reloading or restarting.
A torn prefix can still be valid YAML: a successful reload clears the write protection, and a restart can insert declared
defaults for keys lost from the file. A successful reload also puts the declared default in memory for each lost key whose
setting the module had not changed, with one warning naming each key. Successful parsing does not prove the configuration
is complete; do not use reload or restart as a substitute for comparing and recovering it first.
A backup that served a successful in-place write is removed only after the next successful strict UTF-8 storage load, and
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
reason is kept on one log line. As of v6.3.2 (#593), raw diagnostic values use the same normalization:
control characters and Unicode line separators are replaced by spaces in warnings only, without changing
configuration values or file content. Unsupported declared types fail preflight before the file is read or created; register
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
module removed that key or emptied the map. The file's other settings are written normally.

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
the module binds `false`; the same holds for any split of the path's dots (`a.b:` / `c: 1`
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
and nothing is written). These module-programming argument errors may include the supplied map keys; do not relay their
messages to players or panel clients. A save never inserts a key the file lacks, never rewrites a comment and never removes a
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
module, or use the targeted operator methods for a single named change.

For a command the operator runs to change exactly one thing, use one of these methods:
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
`IllegalArgumentException`. A call that passes a literal
`null` as the first argument, `saveOperatorMapEntry(null, "path", "key")`, does not compile, because it is ambiguous
between them; cast the `null` to the intended type (it then throws `IllegalArgumentException`).

**Known limitation retained (#626): a new map key that contains a dot is written unquoted and named
by its dotted path.** A map entry added through the write gate (for example `saveOperatorMapEntry` with
the key `x.y`) is rendered as a plain `x.y:` line; an existing key keeps the quoting the operator gave
it. The framework always reads such a key back as one whole key, but Bukkit's own `YamlConfiguration`
splits it into `x` and `y`, so a plugin that reads the file through Bukkit sees a nested section
instead. Warnings and refusal reasons join the path with dots, so `'autoreply.rules.x.y'` can mean the
whole key `x.y` or the nested keys `x` and `y`. Read map settings through the framework, not
through Bukkit, and prefer map keys without dots (for example `-` or `_` as a separator) when an
entry name is chosen by a command or the panel; when a diagnostic names a dotted path, check the file
for both forms.

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

**Partial module reload (accepted behavior, #593 / #598).** The first failing configuration file stops that
module's remaining reload steps. Earlier files keep the values already reloaded; the failed file keeps its running
values. Language rebuilding, the new-language token-comment refresh and the module hook are not reached. Earlier
files can therefore keep old-language framework comments. Fix the failing file and complete a successful reload to
refresh them; a full `/ul reload` applies a changed server-wide language. There is no module-wide rollback.

As of 6.3.2 (#598), `validateProposedProperties` and `updateProperties` report a refused edit as an edit that was
not applied, naming each violating declared configuration key (for example `allowed-commands`, not Java member
`allowedCommands`). The file and running values are unchanged; correct the proposed values and try the edit again.
These edit diagnostics do not ask for a restart.

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

`AbstractConfigEntity#getConfig()` is not available in 7.0.

Use `isPresentInFile(String)` for presence in the last successfully loaded document: undeclared keys
and explicit null count as present; unreadable/unparseable loads report false. The path is read the
way a declared setting path is (#612): split at its dots as nested keys, held as one flat dotted key, or any mix of the
two; the key counts as present when the file holds it in any of these forms, so a whole map key containing a dot is
found too. Read or mutate declared fields and call `save()` instead of mutating Bukkit storage.

An unrecompiled caller invoking the removed accessor sees `NoSuchMethodError`. See the removal
record in `compatibility/archive/6.x/records/6.3.0.md`.

### Registration batches

As of 6.3.0, package/directory configuration registration buffers initialization writes until every
entity binds and validates. A refused batch creates no file and changes no existing file, including
missing-key and language-token comment rewrites. Once accepted, files persist independently; an I/O
failure preserves earlier successful files and protects the failed entity until a successful reload.
The flush writes through the write gate (see "Rendering and save fallback") and only while the file still
holds the bytes read at registration; an edit made in between is kept, with one warning.
Standalone registration still writes immediately. Framework-internal initialization bridges are not
a module transaction API.

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

### Configuration release; nothing written at stop

As of 6.3.0, module unload releases that module instance's configuration registry entry even
when its unload hook or context close throws. **Nothing writes configuration at server stop, at
module unload or uninstall, or at module replacement** (maintainer decision of 2026-10-04: no
shutdown or replacement save of whole entities; a lifecycle event is not an operator's request to
write). At stop, and at a normal unload or uninstall, after each module's unload hook and container
`@PreDestroy` callbacks and just before its release, every configuration of that module holding module
changes that were never saved is named in one WARNING - the file and the changed keys, never values -
and the changes are dropped; the framework's final step at stop names, the same way, anything still
registered.

An operator’s edit made while the server runs is never touched at stop. A module persists a change when it makes it, with
`save()` (only what the module changed, where the file still holds what was read) or
`saveOperatorChange`/`saveOperatorMapEntry` (exactly what an operator's command names).
`ConfigManager#saveAll()` keeps its signature, writes nothing, reports as above and is
`@Deprecated(since = "6.3.0")`.

### Configuration init and reload thread contract

As of 6.3.0, configuration init, reload, manager reloadConfigs and module reloadSelf refuse
calls off the server thread while a server runs. One warning names the module, entity path when
applicable and caller thread; no field/file/lifecycle action occurs. Checks precede entity monitors.

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
`ConfigurationException` naming the file and the safe cause.

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
`BlockVector` coordinate (`y: 64`) loads as that number: the framework widens `x`, `y`
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
This is not a crash-safe multi-file transaction: a JVM crash between moves can leave a partial batch.
The single-file opt-in helper added for #545 in 6.4.0 does not change this limitation or the panel's writer.
 Internal staged-entity
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
the file is touched. It never creates, deletes or renames a file and is not for `@ConfigEntity` files, which keep
their own save rules. Its publication, live-link handling and backed in-place fallback are unchanged by the separate
6.4.0 opt-in API below.

### Strict single-file module YAML publication (6.4.0, opt-in)

`com.ultikits.ultitools.config.CrashSafeYamlFiles` is an additive, YAML-only helper for a module's **explicit operator
create/edit action** (#545). It does not automatically migrate any module, `OperatorFiles.write`, `@ConfigEntity`,
framework state or credential file. It is not an arbitrary byte writer, a whole-file overwrite API, a repair tool,
a delete/rename API or a multi-file transaction.

```java
OperatorFiles.Snapshot snapshot = OperatorFiles.read(kitFile);
OperatorFiles.WriteResult edited = CrashSafeYamlFiles.write(snapshot,
        Collections.singletonMap(Collections.singletonList("items"), serializedItems));
// Or, for an explicit create action (the parent directory already exists):
OperatorFiles.WriteResult created = CrashSafeYamlFiles.create(newKitFile,
        Collections.singletonMap("items", serializedItems));
// A module updates live state only after WRITTEN (or UNCHANGED for an edit).
```

- `write(OperatorFiles.Snapshot, Map<List<String>, Object>) throws IOException` owns only the named complete key
  paths. It reuses the config gate's plain-data validation, snapshot fingerprint, parse/render/reparse checks and
  exact unowned-byte protection (comments, unknown keys, BOM and line endings included). A later operator save is
  `FILE_CHANGED`; malformed, anchored or unpreservable YAML is `REFUSED`, never repaired. A missing target is
  `FILE_CHANGED`, not recreated. An empty edit and a semantic no-op are `UNCHANGED` without staging or metadata changes.
- `create(File, Map<String, Object>) throws IOException` validates and copies the complete plain-data root map.
  Keys containing dots are literal keys, null values are valid, and an empty map is a valid creation. Any existing
  entry, including a directory or dangling symlink, is `FILE_CHANGED` even with identical bytes. It never claims an
  empty final path and then fills it. It returns `WRITTEN`, `FILE_CHANGED` or `REFUSED`, never `UNCHANGED`.
- Both methods copy and validate arguments before I/O. Null arguments/keys, invalid write paths and non-plain values
  throw `IllegalArgumentException`; serialize Bukkit objects first. Neither helper changes the module's live objects.

**Support and metadata are deliberately bounded.** The supported entry points take `File` (a snapshot also comes from
`OperatorFiles.read(File)`) and therefore use the JVM's default filesystem. This first version requires ordinary files
on a local default POSIX filesystem with native atomic rename/link semantics, working POSIX attributes and an already
existing parent. The real parent is resolved before appending the final basename, so a parent symlink followed by `..`
keeps filesystem meaning. There is no path sandbox; modules supply trusted paths. An existing final symlink is refused,
live or dangling; directories/devices and other nonregular write targets are refused, not followed or replaced.

Existing files preserve exactly the nine POSIX permission bits, owner and group, applied and verified on the staged
inode before commit. Failure to reproduce a promised attribute aborts publication. A file the process cannot write, or
with no POSIX write bit, is refused even if directory permissions would permit replacement. Staging is exclusively
created at 0600 before any content, written through that created channel, narrowed to 0000 before any necessary
owner/group transition, then given its final mode and verified. New files keep the process-created owner/group and
0600: **this is a new API default, not a change to old writers**. Non-POSIX providers (normal Windows included) and a
Java-exposed ACL view on parent/target/stage cause refusal. Attribute lookup failures are not evidence of no ACL.

No ACL copier is supplied. The absence of a Java ACL view does **not** establish the absence of Linux `setfacl` or
default ACLs, SELinux labels or other metadata Java does not expose. Do not adopt this helper for a file whose layout
requires preserving those, xattrs, alternate streams, special mode bits, inode identity, hard-link relationships,
creation timestamps or arbitrary flags. Replacement creates a new inode; existing hard-link aliases keep old content.
Unknown metadata follows replacement/new-file semantics; the helper cannot detect every incompatible layout.

**Publication and interruption.** A random same-directory `<name>.ultitools-stage-<32 lower-case hex>.tmp` receives
all UTF-8 bytes and metadata, `FileChannel.force(true)` and successful close **before** publication. Existing-file
commit is successful `Files.move(stage, target, ATOMIC_MOVE)`; unsupported atomic replacement throws (including
`AtomicMoveNotSupportedException` unchanged). Creation uses `Files.createLink(target, completedStage)`, not an atomic
move pretending to be exclusive create. Native POSIX `link` creates a new entry atomically or fails on an existing
entry (including symlinks); this is not a blanket atomicity claim for every NIO provider. Unsupported link publication
throws `IOException`. There is **no** copy, ordinary move, in-place truncation or journal fallback in this helper.

Directory forcing is best effort after publication (creation: after link, and again after temp unlink); a failure warns
once per directory/reason and does not misreport committed data as unchanged. `WRITTEN` means complete publication,
not universal power-loss durability. With a functioning OS/filesystem, a process crash leaves old/absent or complete-new
final content; OS crash, sudden power loss, remote storage, device/controller failure and filesystem recovery remain
outside that guarantee. Same-JVM config writes share a lock and recheck bytes/kind/promised metadata before commit,
but there is no cross-process lock and an external editor/rename/chmod can race the final check-to-publication window.
Do not share these files with concurrent uncoordinated writers.

**No automatic recovery.** There is no startup scan, journal, replay, promotion or rollback. Cleanup touches only the
exact temp this invocation created, and only while a non-null file key and regular-file kind still prove its identity.
Unknown/changed/unreadable identities and failed cleanup leave a warning naming the retained temp. A pre-commit cleanup
failure does not mask the original error; a post-commit one still returns `WRITTEN`. Later invocations never reuse or
remove a prior temp, and strict reads never run the legacy temp/backup maintenance. After stopping the server and
competing writers, inspect the target and named leftovers; do not assume a temp is newer or authoritative or promote it
automatically. Remove only a temp recognized as no longer needed, otherwise keep it and use an independently trusted
backup or the module's explicit recreate action. Retained temps may contain new data with the declared final POSIX mode;
protect them like the YAML file itself.

### Migrating legacy parsers to converters

For a custom type, remove `parser = ...` from the field and place a public top-level converter
with a public no-argument constructor in the module's `@UltiToolsModule.scanBasePackages`.
The package scanner discovers top-level classes only, not nested converter classes. Converters are discovered before configuration
construction and are not IoC beans: do not depend on injected services or constructor side effects.
Two registrations for the same exact class refuse load naming both converters. Lookup uses the module's exact registration, then its
non-exact superclass/interface registrations, then the framework's registrations in the same
order, generic collections/arrays/enums/Object, and registered Bukkit serialization fallback.
`exact = true` prevents a registration from serving subclasses.


```java
public static class Token {
    public String text;
    public Token(String text) { this.text = text; }
    @Override public boolean equals(Object other) {
        return other instanceof Token
                && java.util.Objects.equals(text, ((Token) other).text);
    }
    @Override public int hashCode() { return java.util.Objects.hashCode(text); }
}
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
conversion. Known limit: a custom converter delegating a concrete typed-map subclass through `ctx.toPlain(nested)` inherits
the enclosing location's file value for key-spelling lookup, so the nested map can use a parent's key spelling. Construct
that nested plain map explicitly instead of relying on recursive key-spelling preservation for this case.
A non-plain runtime value in an `Object` slot uses its runtime converter on write;
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

## Default keys after YAML block scalars (as of 6.5.0)

The configuration write gate can insert missing default keys after an unchanged literal (`|`) or folded (`>`)
block scalar followed by empty lines ([#883](https://github.com/UltiKits/UltiTools-Reborn/issues/883)). The original
block spelling and its trailing empty lines are kept byte for byte, including explicit indentation indicators,
strip/clip/keep chomping, comments on the indicator line, and consistent LF/CRLF/CR line endings. Existing values
and comments are not rewritten; the result must parse back to the intended values before publication.

This does not authorize rewriting a block scalar's own value or its separators. Invalid YAML, anchors/aliases,
unlocatable spans, and any unrelated layout that cannot be preserved still refuse with the existing warning and
leave the file unchanged. Add the missing key by hand when a write is refused.

## Error-report ownership and text bounds (as of 6.5.0)

- Command and `@ExceptionCatch` failures identify the loaded module by the concrete executor or
  bean class's JAR, including proxied beans and modules sharing a package and class loader. Missing,
  ambiguous or unmatched JAR sources remain unattributed (`moduleName: null`); packages and descriptions
  are never used to guess ownership. Existing `ErrorReportCollector#reportError` remains available;
  the `reportErrorFromClass(Throwable, Class<?>, TriggerContext)` entry point resolves ownership.
- A missing or invalid module `identify-string` falls back to a valid, trimmed, lower-case `plugin.yml`
  name for error reports. A valid identify-string still wins.
- Error text uses the gateway's UTF-16 length limits without splitting surrogate pairs. Log timestamps
  are clamped to the protocol range (0 through 8,640,000,000,000,000 milliseconds), so one invalid timestamp
  cannot invalidate the whole batch. Log messages remain bounded in memory and by negotiated UTF-8 byte limits.

## Player cache save declarations (as of 6.5.0)

A bean with `@PlayerCache(saveBeforeRemove = true)` must implement `PlayerCacheSaver`,
including when the annotated field is inherited. Registration throws `PluginModuleException`
naming the bean class and field if the saver is missing, just as unsupported cache field shapes
are refused. Implement
`savePlayerData(UUID)` or use `saveBeforeRemove = false` if no save is intended. A rejected
bean is not added to cache tracking. Save failures still log a warning and remove the entry;
there is no persistence or retry guarantee.

- **One failing `@PlayerCache` save or removal does not stop the quit cleanup** (#643). When a player
  quits, the framework runs `PlayerCacheSaver#savePlayerData` for each `saveBeforeRemove = true` field
  and then removes the player's entry from every `@PlayerCache` field. Each field is handled on its own: a failure (any exception, including a checked
  one the saver throws without declaring it, or an `Error`) is logged as one WARNING naming the bean
  class, the field and the player, and the cleanup goes on; a saver that registers or unregisters a
  tracked object during the quit does not stop the quit cleanup. A failed save does not keep the entry; it
  is removed anyway, because `saveBeforeRemove` orders the save
  before the removal. The framework does not retry the save; a module that needs retry handles it in `savePlayerData`.

**Known limitation (#647): the callback runs once per save-marked field,
  not once per bean.** A bean with N `@PlayerCache(saveBeforeRemove = true)` fields receives N
  callbacks on one quit, even if a previous callback failed; this per-field count is pinned by the
  regression tests. Each callback precedes its own field's removal, so later calls may see earlier
  fields already cleaned. These are separate field callbacks, not framework retries of a failed
  save. Keep a bean-wide saver safe to call repeatedly, and use only one save-marked field if that
  callback already persists all of the bean's caches; do not rely on a failed save preserving entries.

## Behavioral changes

### Behavioral changes that need no migration period

- Correcting behaviour that plainly contradicts the documentation (the docs say it returns
  `Optional.empty()`, the code throws an NPE).
- Tightening the handling of previously undefined input (passing `null` used to be undefined
  behaviour, now it throws `IllegalArgumentException`).
- Changes in performance, memory footprint, log wording, or exception message text.

- A refused External Plugin API registration leaves nothing behind (#537). When
  `PluginManager#registerExternal` refuses a plugin after recording its data-folder scope — the
  command-executor contract check, the config-binding refusal of #531, or a failed container
  `refresh()` — it closes the context and removes the scope, the entity ownership and the adapter's
  data scope before rethrowing the same exception, so a corrected connection of the same plugin in the
  same process succeeds (`ultitools.boot.external-refusal-unwind`). A failure in subsequent
  scheduling, player-cache, EventBus, command or listener registration also runs the inverse of every attempted
  registration in reverse order before releasing the context, scope and newly inserted ownership. This includes
  the failing stage, which may have registered some resources before throwing. The original exception is rethrown;
  cleanup failures are attached as suppressed exceptions and do not stop the remaining cleanup attempts. If a
  cleanup operation itself fails, some of its resources may remain until restart; inspect the suppressed exceptions
  before reconnecting. This does not roll back arbitrary side effects performed by plugin code during initialization.

- Use `depend` for a required server plugin. A missing plugin prevents module registration; one installed but not yet enabled is not refused merely for its enable state.

- The JSON storage backend returns detached copies on read and stores a detached copy on insert.
  Mutating an entity returned by a read does not persist that change: call `update` explicitly.
- `Query#delete()` returns the number of rows actually removed. If a matched row has a null ID, deletion is refused before any matched rows are removed.
- SQLite repairs legacy null IDs at initialization. Updating by null ID is refused; `updateCounted` reports whether a non-null ID matched an existing row.
- `DataOperator#updateIf` performs a conditional write and returns whether the row changed. The default implementation refuses the operation. In `updateIf` only, `EQUAL null` means `IS NULL`; do not pass null or non-finite values to unrelated conditions.

- `WhereCondition` comparisons other than `EQUAL` take effect on SQLite and MySQL (#675).
  `getAll`, `exist`, `page`, `del` and `updateIf` apply the comparisons on each backend. `INCLUDE`,
  `STARTSWITH` and `ENDSWITH` match the value literally, as text, on SQLite, MySQL and JSON alike:
  `%`, `_`, `!` and `\` are ordinary characters, not wildcards or escapes. Letter case and accents
  follow the backend: SQLite ignores the case of ASCII letters only; MySQL compares under the
  `utf8mb3_general_ci` collation of the tables the framework creates (`DEFAULT CHARSET=utf8`), which
  ignores case and accents, so `e` matches `é`; JSON compares exactly.
  `GREATER` and `LESS` on a `byte`, `short`, `int`, `long`, `float` or `double` field (or its
  wrapper) compare numbers of that type. Pass the value as a number of that type (`10`, not
  `"10"`): a text value is not converted, and MySQL compares it with a default text column as text,
  so `"10"` sorts below `"2"`.

- `PluginManager#getPluginList()` returns an unmodifiable snapshot. Registrations belong to the module instance, not merely its name. On programmatic replacement, the accepted instance takes over its registrations; a failed replacement restores the outgoing instance’s registrations. Normal `/upm` changes still require a restart.
- A full `/ul reload` isolates failures and continues with the other modules. `ReloadReport` can describe a partial refresh without claiming success. A targeted reload reports only the requested module's outcome.

- Configuration constraint violations refuse load or reload without rewriting the operator’s file. `@NotEmpty` on an empty or null list, set, map or array can use the declared default in memory; the default must itself be non-empty and satisfy `@Size`. Empty text does not get this fallback. `@Range` refuses NaN.

  - **Known limitation retained (#635): constraints are combined, not alternatives selected by value
    kind.** On an `Object` setting, `@Range` plus `@Pattern` refuses every ordinary non-null number
    or text value: a number cannot satisfy the text constraint, and text cannot satisfy the numeric
    constraint. The kind checks and `@Range`, `@Pattern` and `@Size` checks skip `null`, so `null`
    still loads under `@Range` plus `@Pattern` or `@Range` plus `@Size`; adding `@NotEmpty` refuses
    that null. Do not use these combinations as a numeric-or-text rule or assume they reject null.
    Declare the intended type and compatible constraints, add `@NotEmpty` when null must be refused,
    and validate any alternative-kind rule in the module's converter.
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

### Behavioral changes that do need one

- A documented default value flipping.
- Moving from silent degradation to failure (for example, a missing optional dependency was
  previously skipped and is now rejected at load time).
- A change in return-value semantics (previously an empty collection, now `null`, or the reverse).
- A change in the timing or thread of a side effect (previously synchronous, now asynchronous).

The migration period runs in two steps:

- **Version N**: keep the old behaviour, but emit a **one-shot** WARNING when the path is taken. The
  warning names the target version and a feedback issue link.
- **Version N+1**: switch to the new behaviour and remove the warning.

### Declarative GUI known limitations and author guidance

The widget-authoring limitations collected in [#690](https://github.com/UltiKits/UltiTools-Reborn/issues/690)
still apply where described below. The scheduling, session and diagnostic guidance here describes
7.0's UI-service-based engine, not the old obliviate entry points.

- **A failing `initState` can keep requesting frames.** If it calls `setState` and then throws,
  the page retries once per scheduled frame while open and logs each failure. The frame budget
  prevents the old single-frame retry loop, but does not repair the widget. Catch initialization
  failures inside the widget and build an error state; close the broken page to stop its retries.
- **Use stable, unique sibling `SlotKey`s when State identity matters.** After a replacement
  child fails to mount, its old element has already been disposed. Unkeyed surviving siblings
  are paired by position on the next reconciliation, so they can be paired with different widgets
  and lose their State. Keys preserve sibling identity across structural changes, but duplicate
  sibling keys are invalid: an older element can be orphaned and miss cleanup. Never reuse a key
  for two siblings in the same child list.
- **Make `State.dispose()` idempotent and non-throwing.** If it throws while a child is replaced
  or removed, the element can remain owned and disposal can be attempted again when the page
  closes. Release resources safely even after partial initialization; do not rely on the framework
  to recover from a throwing cleanup callback.
- **A failed replacement inside a single-child wrapper can leave a stale child.** A
  `StatelessWidget` or `StatefulWidget` wrapper can retain its already-disposed old child when the
  replacement fails to mount. Later frames can then fail with `State is null`. Avoid throwing
  from `build` or `initState`; after correcting the widget, close and reopen the GUI rather than
  relying on another rebuild to repair that wrapper.

- **An initial build failure propagates out of `DeclarativeGui.open()`.** The first build runs
  synchronously on the viewer's entity thread, outside the scheduled frame's per-task exception handler.
  Opening disposes partial resources and rethrows; it does not display a broken first frame. Handle
  expected failures inside the widget and render an error state.
- **Reopening waits for result completion.** Until the previous result completes, `open()` returns
  the previous session, even if that session has already selected a close outcome. Reopen from the
  close hook or after the result, not immediately after requesting close.
- **`GuiScheduler.flush()` is a test utility, not the production frame path.** It drains the
  entire pending queue synchronously without the per-frame budget, and a task failure can
  propagate to its caller. A task that keeps requeuing itself can keep that drain running.
  Use normal scheduled frames in production, not `flush()` to force an update.
- **Later frames always run on a later tick in the viewer's entity context.** Ordinary task
  exceptions produce a WARNING and are isolated. A JVM `Error` produces one SEVERE record and is
  rethrown unchanged to the scheduling engine; remaining tasks in that frame's budget do not run.
  The last complete grid stays displayed after a failed build. Do not use `Error` for ordinary module
  failure handling. State mutation itself is synchronous on the calling thread, not automatically
  moved into the entity context.
- **Colliding slots and invalid nodes are author errors.** Out-of-grid nodes and duplicate container
  indexes on different slots are warned about and skipped. Multiple nodes claiming one grid slot
  produce a WARNING and the later node wins. Each diagnostic category is logged at most once per
  location per page instance, including across reopenings. Keep actions stable when possible:
  allocating a new action each frame makes otherwise identical `GridItem`s unequal to the backend.
- **One `GridView` cell must produce one rendered leaf.** A wrapper may contain that leaf at
  any depth, but several leaves under the same cell all receive the same computed slot and
  collide. A leaf's explicit `.slot(...)` does not reserve a separate position: the grid's
  computed slot wins, including in a multi-leaf cell. Put separately displayed items in separate
  cells instead of relying on collision or click-handler ordering.

### Other command and task runtime changes (6.3.0) that need no migration period

- **Active `@CmdCD` cooldowns are kept per executor instance** as well as per mapping (#539). One
  `CooldownValidator` shared by two executors of one class — two modules passing one `ValidatorChain`
  — does not refuse a player on executor B for a use through executor A. `clearCooldown(UUID, String)`
  and `getRemainingCooldown(UUID, String)` keep working unchanged for one executor per validator, which
  is the shape both `BaseCommandExecutor` constructors create; when a validator serves several
  executors they span all of them (the longest remaining time, and every executor's cooldown
  cleared). The overloads `clearCooldown(UUID, Object, String)` and
  `getRemainingCooldown(UUID, Object, String)` address one executor. The executor is held weakly, so
  an active cooldown never keeps an unloaded module's executor reachable.

- **A `@UsageLimit` lock is released when the dispatch is refused after it was taken** (#568): by the
  cooldown (which validates after the lock), by the argument-count check, because a parameter did
  not parse, or by an exception from a later validator, a parameter parser or the scheduler. The hook is `CommandValidator#onRefused`, called for
  each validator that passed; its default does nothing, so existing validators are unaffected, and a
  refused dispatch runs no `onComplete`, so it applies no cooldown. Every `onRefused` and every
  `onComplete` hook now runs even when an earlier validator's hook throws; the first exception is rethrown afterwards.

- **`@Scheduled` methods declared on a superclass of a bean are scheduled** (#532), as the annotation's
  javadoc always said. An overridden method is scheduled once, with the most derived declaration's
  annotation; an override without `@Scheduled` is not scheduled.

  - **Executors that share one custom validator chain.** An executor releases the
    `@CmdCD` and `@UsageLimit` validators it dispatched through when its module unloads, and leaves a
    validator alone while another loaded executor that has dispatched still has it in its chain (a
    chain passed to `super(chain)`, or one instance returned from `createDefaultValidatorChain()` by
    several executors). An executor counts as a user from its first dispatch on. If that first-ever
    dispatch runs off the main thread (a command run from another thread)
    at the same moment another sharing executor's module unloads, the unload can release the
    shared lock validator, clearing its locks, while that first command is still running. **Do not
    share one custom validator chain instance across executors;** give each executor its own chain,
    especially across modules. Normal container teardown releases an executor's unshared validators.
  - **A shared validator is unregistered when the last sharing executor is torn down.** Until then
    it stays registered with the player-cache manager. An executor the module container never tears
    down - one built with `new` and registered by hand, outside the container - keeps a shared
    validator registered while that executor remains reachable. Garbage collection alone does not
    trigger unregister: a later executor teardown prunes cleared executor references before deciding
    which validators to release. The effect is delayed cleanup, never a wrong verdict; if no other
    executor sharing the validator is torn down later, it can stay registered until shutdown.
    **Let the module container own its executors.**

### JSON transactions (6.3.0) — staged, atomic and durable

For a JSON transaction opened by `DataOperator#transaction(...)` or by `@Transactional`:

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
  other, and a read-modify-write inside one cannot lose an update. A locking read can wait for another transaction
  that holds the table, at most 5 seconds.
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
  **Retained diagnostic limitation (#694):** a locking read uses the same `JSON write failed` prefix
  (and the timeout's `The write was not applied` wording) even though the attempted operation was a read.
  Diagnose it as that read's lock-wait/deadlock failure; do not infer an attempted write from the message.
- **Read-only.** `readOnly = true` rejects writes. The refusal does not mark the transaction: catching it leaves the
  transaction committable, as a failed statement does on SQLite and MySQL.
- **Timeout.** `@Transactional(timeout = ...)` sets a deadline. Every data operation inside the transaction checks it
  first, and an operation started after it has passed fails with a `DataAccessException` whose message starts
  `JSON transaction timeout`. That failure marks the transaction rollback-only before it is thrown, as Spring does
  when a transaction's deadline has passed, so catching it does not save the transaction: the outermost commit rolls
  back and throws `UnexpectedRollbackException`. **Retained backend difference (#694):** SQLite/MySQL instead
  give each JDBC statement the remaining query-timeout budget, floored at 1 second even after the deadline.
  They do not implement JSON's pre-operation deadline refusal. Neither backend interrupts arbitrary method-body
  work. Do not use `timeout` as a portable wall-clock limit or depend on identical post-deadline outcomes.
- **Propagation as on SQLite and MySQL.** `REQUIRES_NEW` and `NOT_SUPPORTED` suspend the active transaction,
  including its staged writes and table locks, and resume it afterwards. One difference: such a method runs on the
  same thread as the caller it suspended, and table locks belong to the thread, so it does not wait for the locks
  its caller holds. If it writes a key its caller already staged, the caller's later commit is the value that
  stays. (On the JDBC backends the inner call would wait for its own caller's row lock until the lock-wait
  timeout.) This thread-owned locking is retained (#694), not isolation between the suspended and inner
  transactions. Avoid writing the same key in both scopes when the inner value must survive the outer commit.
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
  entry with `update`.

- **A damaged journal record.** If a committed journal record is found damaged at start-up, that plugin's JSON
  data stays closed: its data operations fail with an error naming the file, until the server operator moves the
  file out of the journal folder. The framework never deletes, skips or applies the damaged record. Other plugins
  are not affected. A module that obtains its data operators while it loads is
  refused at that start, so after moving the file aside, restart the server. Only a plugin that first requests
  its data after it has loaded runs recovery again at its next request, without a restart.

**Plain writes.** A JSON write outside a transaction is held in memory and written at the next background
flush; it may wait for a transaction's table lock. The flush is a per-plugin checkpoint: it writes only
the entries changed since the previous checkpoint, crash-atomically as one set across the plugin's tables, and
then empties the journal. If a flush fails, the changed entries stay pending and the next flush writes them again;
the failure is thrown out of the scheduled flush task and appears in the server log.

**What a module author may notice.**

- A plain JSON write can wait for a transaction's table lock, for at most 5 seconds.
- A transaction can fail with a lock-wait or deadlock error and roll back. The framework does not retry it.
  Catching the error inside the transaction does not save it: the commit rolls back and throws
  `UnexpectedRollbackException`.
- A commit that changed something writes and forces one journal record before it returns, so it waits for the
  disk on the committing thread, which is often the server's main thread. This synchronous latency is retained
  (#694): world autosaves or other disk contention can extend a tick. Keep main-thread transactions short,
  budget for disk latency, and do not assume commit is queued asynchronously.

- Map each `@Table` value to one entity class. Two entity classes of one plugin that declare the same `@Table`
  value get two separate caches and two table locks that do not exclude each other, so their writes
  are not ordered against each other and the guarantees above do not hold between them.
- A hidden `.ultitools-journal` folder sits beside each plugin's JSON tables. In a server it is
  `plugins/UltiTools/data/<plugin name>/.ultitools-journal`; for a plain Bukkit plugin that uses the External
  Plugin API it is `<that plugin's data folder>/data/.ultitools-journal`; for an operator built directly over a
  table folder it is `<table folder>.ultitools-journal`, a sibling of that folder. A module that lists a plugin's
  data folder must skip it. **Retained identity limitation (#694):** separately constructed operators
  have separate identities, transaction managers and locks even when their table folders share a parent;
  a common parent does not group their commits. Use framework-managed operators for a plugin-wide
  transaction and do not construct multiple independent operators over the same table folder.
- **No versioned portable filename format (#694).** The store still uses raw `<id>.json` names, not
  a reversible cross-platform encoding or automatic migration. Choose portable ids before writing data,
  and check existing ids and filesystem rules before moving a data folder. The device-name guard above
  deliberately covers `COM1`–`COM9` and `LPT1`–`LPT9` (plus the stated superscripts), not `COM0` or
  `LPT0`; their acceptance on Linux is not a Windows/tool portability guarantee. Avoid device-like names,
  including `COM0`/`LPT0`, for portable ids; UUIDs avoid these naming issues. Do not rename stored files
  independently of their entity ids.

**Durability by platform (known limitations retained, #694).**

- "Durable once `commit` returns" depends on the file system and hardware honoring force operations. On Windows and macOS it holds against a server crash, but
  a power loss or operating-system crash can lose the last whole commits: Windows does not force a rename or its
  directory to disk, and macOS's file force is not a full flush to the platter. A lost commit is lost whole,
  never half. Keep backups that include the journal; if the last acknowledged commit must survive
  power loss, do not rely on the Windows/macOS JSON guarantee as equivalent to Linux's.
- On Windows, a journal file move that another program (an antivirus scanner, an indexer, a backup tool) briefly
  holds is retried for at most 150 ms before the commit fails.
- A directory that cannot be forced to disk because of a real I/O error is logged at `WARNING` (`Could not force
  the directory <directory> to disk ...`). A commit made while that happens is committed and survives a server
  crash; it survives a power loss only once the next flush has completed. A flush or a start-up recovery that
  meets the failure fails and is repeated; records are retained until their deletion step. If the final force
  after journal unlinking fails, the records are already absent, and a start-up recovery retry finding an empty
  journal does not force that directory again. After a failed checkpoint, the next flush writes a new checkpoint
  record, and that write forces the journal directory, which also persists the earlier unlinks.
- Reopening an empty journal can reuse internal sequence numbers. Reuse alone does not lose data: the risk of
  an old deleted record reappearing requires an unsupported or failed durability barrier and an additional
  power-loss window before a successful directory force establishes the new state. The empty-retry caveat
  above does not add a durability barrier; do not treat such a retry as proof of power-loss durability.

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

### A failing joined `@Transactional` method rolls back the whole transaction (6.3.0) — changed without a migration period

A `@Transactional` method that joins a transaction already active on the thread —
`REQUIRED`, the default, or `SUPPORTS` or `MANDATORY` called inside a transaction — and throws an
exception its own `rollbackFor`/`noRollbackFor` rules accept marks the whole transaction
rollback-only, even when the calling method catches the exception. The outermost commit then rolls
back every write of the transaction, the caller's included, and throws
`com.ultikits.ultitools.exceptions.UnexpectedRollbackException`. This holds on SQLite, MySQL and JSON
alike, and it is the same rule a failed nested `DataOperator#transaction(...)` scope follows in 6.3.0.
In detail:

- An exception the joined method's rules do not accept — a checked exception under the default rules,
  or a type listed in its `noRollbackFor` — leaves the transaction committable, and the outer method
  commits both its own writes and the joined method's.
- A joined failure that the outer method does not catch rolls the transaction back  and the
  caller receives the original exception, not `UnexpectedRollbackException`.
- When, after such a mark, the outer method throws an exception its own rules commit despite (for
  example one listed in its `noRollbackFor`), the caller receives `UnexpectedRollbackException` with
  that exception attached as suppressed.
- A joined method's `isolation`, `readOnly` and `timeout` are not applied; they belong to the
  transaction that began it.

**What a module author can do.**

- Let the failure propagate, so the transaction rolls back with the original exception.
- Put work whose failure the caller survives in a `REQUIRES_NEW` method, which commits or rolls back
  alone.
- Or list the exception in the joined method's `noRollbackFor` when its writes are meant to stay.

Code that builds a `TransactionInterceptor` over its own `TransactionManager`: the interceptor
calls `begin()`, then `commit()` or `rollback()`, around a joined method, so `begin()` on an active
transaction must join it, `rollback()` at a nested depth must mark the transaction rollback-only
without ending it, and the outermost `commit()` of a marked transaction must roll back and throw
`UnexpectedRollbackException`, as both built-in managers do. No signature changed.

### A failed nested `DataOperator#transaction(...)` marks the whole transaction rollback-only (6.3.0)

- **SQLite, MySQL and JSON.** The inner failure marks the transaction rollback-only and does not
  end it. The outer scope's later writes stay inside the transaction. The outer commit rolls everything back and
  throws `com.ultikits.ultitools.exceptions.UnexpectedRollbackException`.

**What a module does.** Let the inner failure propagate, so the transaction rolls back with the original
exception, or do the work whose failure the caller survives outside the outer transaction (for example in a
`REQUIRES_NEW` method).

## Configuration-bound scheduling and cooldowns

### A floor no linker enforces: config-bound `@Scheduled` and `@CmdCD` (6.3.0)

**Config-bound async scheduling.** `async = true` can be combined with a binding. A global tick trigger records its fire tick before dispatch to Paper’s async scheduler. Reload preserves the trigger's cycle (last observed fire + new period, or next
tick if overdue), not the worker's start/completion time, and leaves work already dispatched alone.
Slow async bodies may overlap and must not use thread-unsafe Bukkit APIs. Unload cancels the trigger and queued workers; an already-running body may finish.

**Avoid adding a module `@Range` to the bound field.** The binding's own range is the field's
range: 1 to `Integer.MAX_VALUE / 20` seconds for a period or delay, and 0 (no cooldown) to
`Integer.MAX_VALUE` for a cooldown. A panel write outside it is refused like a `@Range` violation.
An out-of-range value on `/ul reload` keeps the running value and logs a WARNING. A `@Range` on the
same field would instead make the configuration reload itself fail, which stops the rest of that
module's reload and reports it as failed (a full `/ul reload` still continues with the next module). A module that already had a `@Range` on a field it now binds should drop it; the binding's range takes
over.

A failure part-way through a config-bound timing refresh is reported honestly: updates applied
before the failure remain in effect, and the remaining updates did not complete. Tasks and executors are still
updated individually; no rollback or lifecycle reordering is introduced. The framework-owned timer/cooldown rules

**Known limitation — internal cooldown state.** Modules must not modify `ConfigBoundCooldownState` or separate its
paired source and running-seconds maps. They are framework-internal state initialized together. If an extension
breaks that pairing, a refusal report can display `null` seconds instead of a running value; the framework does not
repair this unsupported state or invent a fallback cooldown. A normal reload of a null or invalid configured value
retains its already-initialized running cooldown instead.

If your older module overrides `unregisterSelf()` or `reloadSelf()`, migrate those overrides to the `onUnregister()` and `onReload()` hooks before rebuilding; the framework lifecycle entry points are `final`. Do not call the old entry points from their hooks.

## SQLite lock waits under native profiling (planned for 6.3.2)

A read-before-write transaction can still fail immediately when upgrading its read lock would deadlock with another transaction. A busy handler does not remove that SQLite behavior: do not assume every SQLITE_BUSY waits for the full timeout, or retry an entire transaction blindly. The framework does not switch transactions to IMMEDIATE to hide it. Direct changes to SQLite-owned busy handlers or PRAGMA busy_timeout by module code are outside the framework-managed wait policy.

The elapsed-time busy handler is built for the SQLite JDBC driver UltiTools ships (#823). If another plugin or a fork places a different `org.sqlite` driver where UltiTools' classes resolve it, the wait budget can behave differently without a warning. Do not replace or shadow the shipped SQLite driver.

## UltiCloud: re-pair with a code (as of 6.4.0)

Run `ulticloud login` from the server console. Supply the pairing code as an argument or enter it
when the console prompts. The client checks the code's syntax before attempting to pair.
Use `ulticloud status` to inspect the connection, `ulticloud reconnect` to retry the connection,
or `ulticloud logout` to unpair. Do not enter pairing codes in player chat.

## UltiCloud v2 on 7.0

The 7.0 client uses UltiCloud protocol 2.1.0 and the catalogue REST contract 1.1.0. The old cloud wire protocol is not supported.

### Module responders: result shapes and two source-level changes

A `PanelResponderRegistry` responder may answer any JSON value: an object, an array, a primitive, or no
content. A future that completes with `null` or `JsonNull` is a success with no content, sent as `ok` with
`data: null`; in 6.3.0 it was a failure. A responder that returns no future, throws, or completes its future
exceptionally still fails the request (`module_error`). The `module_error` message is value-free by design: when a
responder's result cannot be serialised as JSON, the message does not name the offending field.

The future never completes successfully with a Java `null`: no content is represented by `JsonNull.INSTANCE`.

### Module identifiers and the `module.request` key convention

UltiCloud addresses a module by its catalogue identifier: the module's `identify-string`, trimmed and
lower-cased, not its `plugin.yml` name. This matters where the two differ, for example UltiChat (name
`UltiTools-Chat`, identifier `ultichat`), UltiEconomy (`ultieconomy`), UltiKits (`ultikits.kits`) and UltiMenu
(`ultimenu`). The identifier is what the `hello` declares as `modules:<identifier>@<X.Y.Z>` (version: the first
three numeric parts of the module's `plugin.yml` version), what a panel `module.request` names in `module`, and
what an `error.report` names in `moduleName`. `moduleName` is `null` unless the report names a loaded module.
A module whose `identify-string` is missing or is not a valid identifier (a lower-case letter first, segments of
lower-case letters and digits joined by one `.` or `-`, at most 64 characters) is addressed by its `plugin.yml`
name trimmed and lower-cased instead, when that is a valid identifier. If neither is, its
responders are not declared and one WARNING says so. Error reports use the same fallback.
Prefer a valid catalogue `identify-string` so module requests and reports use the published module's identifier.

The version in `modules:<identifier>@<X.Y.Z>` is always three numbers. The framework takes the first three
runs of digits before any `-` or `+` suffix in the `plugin.yml` version and pads missing ones with `0`:
`2.0`, `2.0-rc1` and `2.0+build.9` become `2.0.0`. UltiCloud's protocol accepts only `X.Y.Z` in a capability name (no pre-release tag), so a `2.0.0-rc1` form would make the gateway
refuse the whole `hello`. Module authors who want the panel to show the version they ship should use a plain
three-number `plugin.yml` version; a pre-release should not reuse the number of a later release.

A `module.request { module, action, params, actor }` is dispatched only to a responder of the module whose
identifier equals `module`, registered under `<identifier>.<action>`. New registrations should use that form.
The 6.3.0 form `<plugin.yml name lower-cased>.<action>` keeps working; the request still arrives with the
identifier. Anything else is answered `module_not_found` and reaches no responder. The responder receives a
copy of `params` with an added `actor` object `{ userId, minecraftUuid }` that the gateway fills and the
framework passes on as received (a `params.actor` the panel sent is replaced). A module that owns no responder
declares no capability, and the gateway sends it no requests. The capability list is built for each
connection's `hello`; `/ul reload` reconnects when the list changed.

### New configuration keys

The following switches under `ultipanel.capabilities` in `plugins/UltiTools/config.yml` default to `false`:

| Key | Gates |
|---|---|
| `ultipanel.capabilities.power-actions` | The panel's structured stop and restart (`server.power`), which do not pass through the command blocklist. A restart needs the `restart-script` of `spigot.yml` to exist; otherwise it is refused as `power_unsupported` and nothing stops. |
| `ultipanel.capabilities.player-actions` | Kick, change game mode, teleport (`player.action`). |

### Message classes the framework supports

| Class | Messages | Switch |
|---|---|---|
| Commands | `command.execute` answered by `command.ack` before it runs and `command.result` with the output lines the command printed before its dispatch returned (capped, with `truncated`). Lines it prints later, for example from an asynchronous body, are written to the server console and reach the panel through the log stream, not the result. The blocklist applies | `commands` |
| Power | `server.power` (stop, restart) answered by `command.ack` and `server.power.result`, sent before the stop begins; `power_disabled` when the switch is off | `power-actions` |
| Structured player actions | `player.action` (kick, game mode, teleport to a player or to coordinates) and its result; `player_not_online`, `world_not_found` | `player-actions` |
| Telemetry and snapshot requests | `status`, `players.snapshot`, `plugins.snapshot`, `worlds.snapshot`; `snapshot.request` pushes the requested snapshots, then answers | `monitoring` |
| Log stream and events | `log.lines` (sensitive commands redacted), `error.report`, reliable `player.join` and `player.leave`, `player.command` (name only), `outbox.overflow` | `logs`, `player-events`, `ultipanel.logging.error-reporting.enabled` |
| Structured configuration | `config.tree`, `config.entries`, `config.update` over the configuration files of loaded modules; an update writes only the named keys through the write gate and answers `file_changed` when the file's SHA-256 is not the `baseVersion` | `file-read` (read), `file-write` (write) |
| Files with versions | `files.list`, `files.read` (content, `version` = SHA-256 of the raw bytes), `files.write` (requires `baseVersion`, `file_changed` on mismatch), `files.delete` (files only), inside the editable roots | `file-read`, `file-write`, `file-delete` |
| `server.properties` | `properties.get` (the 14 common keys, typed, with `version`) and `properties.update` (12 writable keys); `online-mode` and `white-list` are read-only | `server-properties` |
| Module requests | `module.request` and `module.response`, as above | `modules:<id>@<X.Y.Z>` |
| Player links | `player.link.create`, `.created`, `.completed`, `.completed.result`, `.expired` | always declared |
| Credential rotation | `credential.rotate`: the new credential is written to disk atomically, then `credential.rotate.ack` is sent; the open connection keeps the old one and the next connection uses the new one | always |
| Operator settings | `settings.changed { powerActionsEnabled }` after `/ul reload` changes `power-actions` | always |

### Module downloads

`/upm install`, `/upm update` and the start-up update check use only the public catalogue. A module JAR is
downloaded only from the same origin (scheme, host and port) as the configured API base URL; another origin is
refused, and no 3xx redirect is followed. The body must match the version's `sizeBytes` and `sha256` (64
lower-case hex characters in the version metadata), or the temporary file is deleted, an error is reported and
nothing lands under `plugins/UltiTools/plugins/`. Version metadata without a usable hash or size makes the
version uninstallable, never unchecked. The `X-Content-SHA256` response header, when present, must name the
same hash; a missing or upper-case header is accepted, because the body is always checked against the
metadata. The
catalogue's `framework.downloadUrl` (Maven Central) is never downloaded by the framework; only its version is
read for the newest-version check. `/upm update` stages a module against the version it read through the
catalogue route, whatever shape the download URL has.

### Remote action log under protocol 2.1.0

`plugins/UltiTools/security/action.log.0` records panel access decisions. The `action` of a file,
configuration or `server.properties` request is its protocol request type. Power and player actions
are `server.power.<action>` and `player.action.<action>`. A dispatched or blocklisted command uses
`execute_command`; a command refused by a capability switch uses `command.execute`. `ALLOWED`
means the request was carried out. An ordinary file failure is `DENIED` with the reply's reason.
A capability-switch refusal writes a `DENIED` line from the switch
gate. A served `snapshot.request` is one `ALLOWED` line with capability `MONITORING`, actor `panel`
and the requested target names joined by commas in request order as its target. A malformed request,
`config.tree`, `config.entries`, unsolicited telemetry and module requests write no line, and from the first step
of the disable a read (`files.read`, `files.list`, `properties.get`) is still answered but writes no line either;
a `snapshot.request` needs the server thread and is dropped unanswered, writing no line (see the shutdown window
below). No line ever holds file content or a configuration value.

### Sensitive commands in the remote console's local records (as of 6.4.0)

Register a sensitive command using `SensitiveCommandRegistry` to redact its arguments from the
synchronous remote console's local records. The command itself still receives its arguments; the
registration does not filter its result or the output of another plugin or work scheduled for later.

- The registered name is matched against the command as it is dispatched (one leading `/` and surrounding or repeated
  whitespace, no-break spaces included, removed; a `namespace:` prefix and letter case ignored), so `/ logadmin ...`
  or a no-break space after the name does not hide it. The command keeps the spelling it was sent with
  (`/UltiLogin:LogAdmin reset ...` is logged as `UltiLogin:LogAdmin ***`).
- The command itself is still dispatched with its arguments, and its reply (`command.result`) is unchanged: what the
  command prints is the module's own output, and redacting it is the module's decision.

### Shutdown window

From the first step of UltiTools' disable, every request that would change server state is refused with an
explicit failure saying the server is shutting down: `command.execute` and `server.power` are acknowledged and
then fail, so a redelivery after the restart is acknowledged and never run; `files.write`, `files.delete`,
`properties.update`, `config.update`, `player.action` and `module.request` fail with `internal_error`;
`credential.rotate` gets a generic error, and the gateway sends it again after the next `welcome`. A
`player.link.completed` is left unanswered and delivered again after the restart. Read-only requests keep
working until the connection closes, except `snapshot.request`: its server-thread hand-off is dropped, so it
sends neither snapshots nor a result and writes no action-log entry. The disable waits, at most 5 seconds, for
changes already under way,
including work handed to a module responder or the player-link handler, so no change happens after the remote
action log closes.

### Smaller UltiCloud client changes (as of 6.5.0)

- **`files.list` of the empty path lists only roots that exist.** A configured editable root that does not exist, or
  is a file, is not offered to the panel as a directory. Create the directory, or remove the entry from `ultipanel.files.editable-roots`.

### Known limitations (6.3.1)

- **Module JARs are hash-checked, not signed.** The catalogue's `sha256` and `sizeBytes` protect against a
  corrupted or substituted download, not against a compromised catalogue.
- **Version pagination is not a catalogue snapshot.** If a version is published while a multi-page
  lookup is in progress, publication ordering can shift an entry between pages. The client rejects the
  whole lookup as an unreadable response when a version appears on more than one page, or when a page
  reports a `page` or `pageSize` other than the one requested; it never shows a list with a repeated
  entry. A version published onto a page that was already read can still be missing from a lookup that
  succeeds. Retry `/upm versions` after publication has finished. The client adds no snapshot or
  concurrency machinery for this combination.

- **A panel command's late output is not in its result.** `command.result` is sent when the command's dispatch
  returns, so it holds only the lines printed until then. Lines printed later (an asynchronous command body, a
  scheduled reply) are written to the server console unchanged, so they appear in the console, the log files
  and the panel's log stream (sensitive commands redacted, as for every console line). That forwarding lasts
  10 minutes after the result; a line printed later still is dropped, with one WARNING naming the command.

- **An editable root equal to the server root is not listed.** `files.list` with an empty path lists the editable
  roots by their path relative to the server root; the server root itself has no such path and is omitted, although
  requests for paths inside it are still judged normally. Use sub-directories (`plugins`, `logs`) as editable roots.

## UltiCloud client replies and panel-command boundaries (as of 6.5.0)

The remote console dispatches commands on the server thread using a Paper capturing sender.
For an UltiTools Brigadier command, `CommandInvocation` enforces its sender restrictions without
using the remote console's elevated permissions to stand in for a player. The remote command blocklist
matches the dispatch label and Bukkit metadata that resolves to it; it is not a sandbox for arbitrary
code that a permitted command calls. A failed dispatch still gets a reply, and the synchronous dispatch
scope is restored even when the body throws. Output from third-party commands is not guaranteed to be
redacted.

- **An asynchronous teleport has a 25-second reply deadline.** If its future has not completed, `player.action.result`
  reports `internal_error` with `teleport did not complete in time`. This stops waiting, not the teleport: the Bukkit
  operation is not cancelled and may still take effect. Check the player's actual position before retrying. A late
  completion never sends a second result. The deadline uses the existing cloud timer, not a per-request thread.
- **Player-link events no longer get stuck behind a lost create answer.** Closing the connection or receiving an error
  releases held completion ids for redelivery without restarting. Checking a saved receipt and claiming a completion
  are atomic. A held completion or expiry is delivered only after the create future's callbacks have returned;
  cancelling the caller's future cannot prevent transport cleanup. Already-running completion handlers remain
  in flight across reconnects.
- **Module capability versions use only the version core.** Digits in prerelease or build metadata no longer fill a
  missing component: `2.0-rc1` and `2.0+build.9` both declare `2.0.0`, not `2.0.1` or `2.0.9`.

### Remote command defaults (as of 6.5.0)

The shipped `ultipanel.commands.blocklist` contains 13 entries, in this order: `op`, `deop`, `stop`,
`restart`, `reload`, `ban-ip`, `pardon-ip`, `whitelist`, `save-off`, `save-all`, **`execute`, `return`, `function`**.
The three new entries refuse direct nested-dispatch entry points such as `execute run stop` and `function <pack>:x`
because alias/nested matching has the limits described below. Namespaced forms such as `minecraft:execute` are
refused too, with `command_blocked` and `details.matched: execute`.

**Known limitation:** the first word is what is matched, so `schedule function <pack>:x …` still runs a datapack
function later from the scheduler; `schedule` is not on the shipped list because it is an ordinary operator command.
An operator who wants to refuse it adds `schedule` to `ultipanel.commands.blocklist` (stop, edit, start).

New installs and configurations without this key get the new defaults. **An existing operator list is never
extended or rewritten**: existing servers keep their old list and must add `execute`, `return` and `function`
themselves if they want these refusals. To allow those commands, stop the server, delete their three lines from
`ultipanel.commands.blocklist` in `plugins/UltiTools/config.yml`, and start it again. Keep the remaining entries;
`[]` disables the entire configurable list. `/ul reload` does not re-read this list.

### Known limitations of panel command hardening

The remote command blocklist is a guard on what the panel command line resolves to, **not a sandbox**. It checks the
outer request and resolved command's registered spellings, plus synchronous nested calls entering
`BaseCommandExecutor`. It does not cover vanilla or Bukkit commands reached through `execute ... run` / `return run`,
`commands.yml` aliases, datapack functions, third-party re-dispatchers, or later-tick paths such as command blocks and
scheduled commands. Later work does not inherit the synchronous panel-origin scope. Operators who need those paths
unavailable must restrict panel command access with **`ultipanel.capabilities.commands: false`** in
`plugins/UltiTools/config.yml` (the shipped default); do not grant it to untrusted operators. Blocking wrapper names
such as `execute`, `return` and `function` narrows direct entry points (all three are in the shipped default),
but is not a complete sandbox and does not protect against a malicious installed plugin. Existing operator lists
are unchanged; allowing these wrapper names restores the corresponding nested-dispatch paths.

Vanilla Brigadier redirects such as `tp` / `teleport` are not Bukkit alias metadata and remain a known limitation:
listing one redirect spelling does not guarantee that the other is blocked. List each spelling explicitly, or disable
the panel command capability when that boundary is required.

The blocklist is loaded at server start: stop the server, edit `ultipanel.commands.blocklist`, and start it again.
`/ul reload` does not apply a blocklist edit. Checks use the manager's current in-memory policy, including the recheck
before dispatch; editing a file alone cannot revoke an admitted request. `command_blocked.details.matched` for the
fixed authentication refusal names `ulticloud`, not a configured blocklist entry.

中文补充：列入命令的任意已注册别名，会同时拦截该命令的其他拼写；修改黑名单须停服、编辑后重新启动，`/ul reload` 不会应用。黑名单只守住面板命令行的解析入口及同步进入框架执行器的嵌套命令，不覆盖经 `execute ... run`、`return run`、`commands.yml` 别名、数据包函数、第三方转派器或命令方块等后续 tick 路径执行的原版／Bukkit 命令。需要禁用这些路径时，应在 `plugins/UltiTools/config.yml` 中设置 `ultipanel.capabilities.commands: false`，而不是把黑名单当成沙箱。

An expiry received before any player-link handler was registered is still consumed; register the handler before
creating links. The router intentionally has one handler per protocol message type. The remaining file/configuration,
malformed-backend and shutdown-window limitations in [Known limitations (6.3.1)](#known-limitations-631) still apply
except where the fixes above explicitly supersede them. Large player/world snapshots are capped at 1000/200 entries,
though their counts can be higher; custom framework version strings must fit the protocol's 64-character bound.
A command's synchronous output is returned to its requesting sender, not redacted as a log stream; do not print
another user's secrets in a command reply. Outbound values outside the module bridge are assembled by the
framework and are not subjected to an additional global strict-JSON pass.

## Support matrix

Current baseline: **7.0.0-SNAPSHOT** (7.0.0 is not released). Each framework version targets one latest Paper version;
older servers are not promised compatibility.

| Item | Value |
|---|---|
| Server | Paper (plain Spigot is not supported — the code uses Adventure `Component` throughout) |
| Build JDK | 25 |
| Bytecode target | Java 25 (`--release 25`, class major 69) |
| `api-version` in the framework's `paper-plugin.yml` | `26.3` (Paper API level, not a module's integer UltiTools API level) |
| `api-version` in a module's `plugin.yml` | An integer UltiTools API level, not the Paper version; 7.0 requires `700` through the running framework API level, inclusive. A declaration outside that range, missing, empty or non-integer is refused before main-class resolution and excluded from the shared class path; `/upm` uses the same minimum rule |
| Target server | Paper 26.3 build 258 (`paper-api:26.3.build.258-stable`); not a minimum-version range |
| Server Java | Java 25, matching the framework bytecode target |
| Modules | 6.x modules declaring levels such as `630` are refused. Rebuild against UltiTools-API 7.0 and declare `api-version: 700`; changing only the declaration does not repair old bytecode. The server rows are the framework's own requirements |

### Module loading on 7.0

See [Module API floor 700 and plain module loading](#module-api-floor-700-and-plain-module-loading-as-of-700) for admission, duplicate copies and the pending-update upgrade limitation.

The shared module loader uses ordinary parent-first `URLClassLoader` behavior. Module bytecode is
not adapted to a different server API, and neither startup nor an offline launcher audits unresolved
references. Compile against the target Paper and UltiTools-API versions. A missing class or method
produces the JVM's normal `NoClassDefFoundError` or `NoSuchMethodError` when resolution reaches it;
merely increasing `api-version` does not repair a module compiled against removed APIs.

**Observed bean failures still have diagnostics.** If the container actually skips a bean because it
references an absent server class, a warning names the module, bean, missing class and all lost event
handlers, including inherited handlers. Those handlers remain unregistered; unrelated beans continue
loading. Missing-method failures observed during bean creation likewise retain their module/class
summary and exact linkage failure. This does not predict failures in code paths that have not run.
The framework neither substitutes missing capabilities nor renames events, and it does not isolate
individual handlers inside one class.

For the earlier server support matrix, see the [6.x branch](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md#support-matrix).

### Where runtime dependencies come from

As of 7.0.0 the framework JAR bundles no third-party library.
Every runtime dependency arrives by one of two routes:

| Delivery route | Version decided by | Examples |
|---|---|---|
| The Paper `UltiToolsLoader`, using the coordinates in `ultitools-libraries.txt` | **This repository** | commons-dbutils, Java-WebSocket, ByteBuddy, JavaMail (`com.sun.mail:javax.mail`), HikariCP |
| Shipped by the Paper server itself | **The Paper build the server owner installed** | Gson, MySQL Connector/J, protobuf, slf4j, the SQLite driver, native Adventure, log4j, the Maven resolver Paper uses internally for `libraries:` and its dependencies |

This boundary determines who fixes a third-party security advisory. For anything in the first
category, pinning a version in this repository is an effective fix. For anything in the second, the
only fix is **upgrading Paper** — no change in `pom.xml` will alter the jar actually loaded on the
server; it only creates the impression that the problem has been fixed.

Note that Maven's `provided` scope is **not** this boundary: the dependencies of both routes that the
framework compiles against are declared `provided` in `pom.xml` (for example HikariCP from the first, Gson from the
second), and the Paper-shipped libraries it does not compile against are not declared at all. What decides the
category is whether the dependency appears in the Paper loader's coordinate list, not the scope.

Server owners are advised to keep up with Paper builds, security builds in particular.

## Feedback

If you disagree with this policy, or your module is affected by the removals above, please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues).

## Historical excerpt retained for documentation regression tests

The following removal table is a frozen test fixture, not a 7.0 contract. See the [6.x branch](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md) for its surrounding history.

## UltiCloud protocol 2.1.0 (as of 6.3.1)

| Member | Code | 6.3.1 behaviour | Replacement | Removed in |
|---|---|---|---|---|
| `com.ultikits.ultitools.entities.ApiResponse` | U | plain data type, behaviour unchanged; it parsed the v1 Worker envelope `{code, msg, data}` | There is no replacement: this parsed the protocol v1 Worker envelope `{code, msg, data}`, which UltiCloud no longer serves. The protocol 2.1.0 catalogue answers `{data: ...}` and the framework parses it itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.entities.TokenEntity` | U | plain JWT value type; 6.3.1 never issues one | Use CloudAuthManager.login(DeviceLoginListener) for server linking; removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.entities.vo.ServerEntityVO` | U | plain value type; its only user was the v1 `registerServer` | There is no replacement: `/ulticloud login` links a protocol 2.1.0 server. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.CommandExecutionManager#executeCommand(com.google.gson.JsonObject)` | S | still runs the command it is given, blocklist included; its reply goes only to a v1 client, which never connects | Use execute(String, String, Executor, CommandResultSink), which the protocol 2.1.0 session calls for a panel command; this v1 handler replies only over the client handed to setWebSocketClient. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.CommandExecutionManager#setWebSocketClient(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) carries the panel requests. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.CommandExecutionManager$CommandResult` | U | unused value class | There is no replacement: the protocol 2.1.0 command path reports through CommandResultSink. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.FileOperationManager#handleFileOperation(com.google.gson.JsonObject)` | S | still carries out the request it is given; its reply goes only to a v1 client, which never connects | There is no replacement for modules: the framework's own UltiCloud session (protocol 2.1.0) answers the panel's file requests; this v1 handler replies only over the client handed to setWebSocketClient. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.FileOperationManager#setWebSocketClient(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) carries the panel requests. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#getLogTransmitter()` | S | returns the transmitter `initialize(client)` created, otherwise `null` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#handleLogStreamMessage(com.google.gson.JsonObject)` | S | logs an INFO line; its replies are dropped without a log line | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#initialize(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent)` | S | listener method; does nothing without a v1 transmitter | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent)` | S | listener method; does nothing without a v1 transmitter | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#onServerLoad(org.bukkit.event.server.ServerLoadEvent)` | S | listener method; does nothing without a v1 transmitter | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#sendCustomLog(java.lang.String,java.lang.String,java.lang.String)` | S | does nothing and logs nothing without a v1 transmitter, the only 6.3.1 state | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#sendPlayerEventLog(java.lang.String,java.lang.String,java.lang.String)` | S | does nothing and logs nothing without a v1 transmitter, the only 6.3.1 state | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.LogStreamManager#sendPluginActionLog(java.lang.String,java.lang.String)` | S | does nothing and logs nothing without a v1 transmitter, the only 6.3.1 state | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) streams the console itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#initialize(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerChat(org.bukkit.event.player.PlayerChatEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerCommandPreprocess(org.bukkit.event.player.PlayerCommandPreprocessEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerKick(org.bukkit.event.player.PlayerKickEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.PlayerEventManager#onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent)` | S | listener method, registered only by `initialize(client)` | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#isMonitoring()` | S | reports whether such a monitor runs | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#sendMetricsData()` | S | sends only over a v1 client; without one it logs a WARNING and sends nothing | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#sendMetricsDataWithRequestId(java.lang.String)` | S | sends only over a v1 client; without one it logs a WARNING and sends nothing | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#sendPlayerEvent(java.lang.String,org.bukkit.entity.Player,com.google.gson.JsonObject)` | S | sends only over a v1 client; without one it logs a WARNING and sends nothing | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) reports player events itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#sendServerStatus()` | S | sends only over a v1 client; without one it logs a WARNING and sends nothing | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#sendServerStatusWithRequestId(java.lang.String)` | S | sends only over a v1 client; without one it logs a WARNING and sends nothing | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#setWebSocketClient(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) carries the panel requests. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#startMonitoring()` | S | without a client it still starts its executor and two main-thread sampling tasks and logs an INFO line, but delivers nothing | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerMonitorManager#stopMonitoring()` | S | stops a monitor a caller started; the framework calls it in its teardown | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) sends the server's status and telemetry itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerPropertiesManager#handleServerProperties(com.google.gson.JsonObject)` | S | still carries out the request it is given; its reply goes only to a v1 client, which never connects | Use readVersioned(int) and updateWithBaseVersion(String, Map), which the protocol 2.1.0 session calls; this v1 handler replies only over the client handed to setWebSocketClient. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.ServerPropertiesManager#setWebSocketClient(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) carries the panel requests. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.manager.UltiPanelLogTransmitter#UltiPanelLogTransmitter(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient,java.lang.String)` | E | stores the client and writes one INFO line per run; the framework never calls it, and the client it stores never connects | There is no replacement: the framework never calls it, and its own UltiCloud session (protocol 2.1.0) streams the console through a transmitter it builds itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.CloudAuthManager#login(java.lang.Runnable,java.util.function.Consumer,java.lang.Runnable,java.util.function.Consumer,java.util.function.Consumer)` | F | forwards to `login(DeviceLoginListener)` | Use login(DeviceLoginListener) instead; removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.CloudAuthManager$CloudStatus#getExpirationDate()` | R | returns `null`; a 6.3.1 credential carries no user name or expiry | Use getServerName() or getServerId() for identity instead; removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.CloudAuthManager$CloudStatus#getUserName()` | R | returns `null`; a 6.3.1 credential carries no user name or expiry | Use getServerName() or getServerId() instead; removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.HttpRequestUtils#getServerByUUID(java.lang.String,com.ultikits.ultitools.entities.TokenEntity)` | R | returns `Response(-1, "UltiCloud protocol v1 was removed; no request was sent.")` without a request; one INFO line per member per run | There is no replacement: a protocol 2.1.0 server is linked with `/ulticloud login` and identified by its server credential. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.HttpRequestUtils#registerServer(java.lang.String,java.lang.String,int,java.lang.String,boolean,com.ultikits.ultitools.entities.TokenEntity)` | R | returns `Response(-1, "UltiCloud protocol v1 was removed; no request was sent.")` without a request; one INFO line per member per run | There is no replacement: `/ulticloud login` links a protocol 2.1.0 server through the device authorization. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.HttpRequestUtils#updateServer(java.lang.String,int,java.lang.String,boolean,com.ultikits.ultitools.entities.TokenEntity)` | R | returns `Response(-1, "UltiCloud protocol v1 was removed; no request was sent.")` without a request; one INFO line per member per run | There is no replacement: a protocol 2.1.0 server reports its own state over its connection. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.PluginInitiationUtils#startTokenRefreshScheduler()` | R | does nothing; the 6.3.1 server credential does not expire | No replacement is needed for non-expiring server credentials; removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.SimpleHttpClient` | U | still sends the request it is given; the framework no longer calls it | Use HttpURLConnection or an HTTP client library of your own; the framework no longer needs a general HTTP helper. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.SimpleHttpClient$Response` | U | plain response holder of `SimpleHttpClient`, behaviour unchanged; the framework no longer uses it | There is no replacement, as for SimpleHttpClient. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.UltiCloudRequests` | R | every call returns `PATH_NOT_ALLOWED` without opening a connection; the first call in a run writes one INFO line | Use CloudPlayerLinks to link a player; protocol 2.1.0 has no v1 REST cloud, so every call of this class is refused. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.UltiCloudRequests#get(java.lang.String,java.util.Map)` | R | every call returns `PATH_NOT_ALLOWED` without opening a connection; the first call in a run writes one INFO line | Use CloudPlayerLinks#create to link a player; protocol 2.1.0 has no v1 REST cloud, so this call is always refused. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.UltiCloudRequests#post(java.lang.String,java.lang.String)` | R | every call returns `PATH_NOT_ALLOWED` without opening a connection; the first call in a run writes one INFO line | Use CloudPlayerLinks#create to link a player; protocol 2.1.0 has no v1 REST cloud, so this call is always refused. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.utils.UltiCloudRequests$Outcome` | U | plain enum of `UltiCloudRequests` outcomes; every `UltiCloudRequests` call now answers `PATH_NOT_ALLOWED` | There is no replacement: CloudPlayerLinks reports its own outcomes. Removed in 6.4.0 with UltiCloudRequests. | 6.4.0 |
| `com.ultikits.ultitools.utils.UltiCloudRequests$Result` | U | plain value type of `UltiCloudRequests` answers; every call now gives one with `PATH_NOT_ALLOWED`, no status code and no body | There is no replacement: CloudPlayerLinks reports its own outcomes. Removed in 6.4.0 with UltiCloudRequests. | 6.4.0 |
| `com.ultikits.ultitools.websocket.ExponentialBackoffStrategy` | U | delay arithmetic unchanged from 6.3.0; the framework no longer uses it | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) paces its reconnects itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.websocket.ExponentialBackoffStrategy$Builder` | U | delay arithmetic unchanged from 6.3.0; the framework no longer uses it | There is no replacement, as for ExponentialBackoffStrategy. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.websocket.ReconnectStrategy` | U | delay arithmetic unchanged from 6.3.0; the framework no longer uses it | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) paces its reconnects itself. Removed in 6.4.0. | 6.4.0 |
| `com.ultikits.ultitools.websocket.UltiPanelWebSocketClient` | R | `connect()`, both `connectBlocking` forms, `reconnect()`, `reconnectBlocking()` and `run()` open no socket (the blocking forms return `false`) and write one INFO line per run; `sendMessage` reports the closed socket | There is no replacement: the framework's own UltiCloud session (protocol 2.1.0) carries every panel request, and modules answer panel requests through PanelResponderRegistry. Removed in 6.4.0. | 6.4.0 |
