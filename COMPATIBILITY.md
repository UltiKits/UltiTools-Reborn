# Compatibility and Versioning Policy

This guide covers the unreleased UltiTools-API 7.0 line. For compatibility between earlier releases, see
[COMPATIBILITY.md on the `6.x` branch](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md).

Upgrading from 6.x? Start with the [Paper and Java baseline](#paper-and-java-baseline-as-of-700-snapshot)
and [module API floor](#module-api-floor-700-and-plain-module-loading-as-of-700), then check the migrations for
[commands](#command-engine-as-of-700), [messages](#legacy-text-system-removed-as-of-700),
[GUI and confirmation widgets](#legacy-chest-pages-and-confirmation-widgets-removed-as-of-700),
[items and sounds](#native-item-and-sound-apis-as-of-700), and [stored items](#item-descriptions-and-storage-as-of-700).

For APIs removed or changed in 7.0, see the command, text, GUI and item migrations above, plus
[notifications, toasts and holograms](#notification-service-migration),
[removed data-store overloads](#removed-data-store-overloads-as-of-650), and
[removed class-name audit and security APIs](#class-name-filter-audit-and-no-op-securitypolicy-methods-removed-as-of-700).

## What the version number means

### New public API baseline: 7.0.0

**7.0.0 establishes a new baseline and does not compare against 6.x.** The japicmp check is
intentionally disabled (`japicmp.skip=true`) only for `7.0.0-SNAPSHOT` and the `7.0.0` release.
After 7.0.0 is downloadable from Maven Central, set `japicmp.baseline.version=7.0.0` and
`japicmp.skip=false` in `pom.xml` before advancing the development version; run `mvn -B clean verify`.
Default build tests and the registry generator reject later versions with comparison disabled or a
baseline older than 7.0.0. Subsequent releases compare against the previous released baseline.

The five compatibility rules continue unchanged in 7.x, with **“published” starting at 7.0.0**:

1. Do not change a published public API signature.
2. Add parameters through overloads, retaining the old signature.
3. Deprecate cautiously.
4. Mark deprecations with `@Deprecated(since = "…", forRemoval = true)`.
5. Remove an API only on that annotation's authority and after at least one MINOR release has
   passed since the warning first shipped (no earlier than 7.0.0 for this baseline).

The 6.x registry and removal records are frozen in [`compatibility/archive/6.x/`](compatibility/archive/6.x/).
They are read-only history, not input to the active generator. During the baseline reset the active
`compatibility/deprecations.json` / `DEPRECATIONS.md` are derived only from current source declarations.
The remaining 6.x deprecation annotations keep their original metadata; their presence does not
implicitly authorize deleting those APIs in 7.0. The [historical 6.x examples and exceptions](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md)
explain that release line, not an exception to these five 7.x rules.

中文补充：**7.0.0 是新的公开 API 基线，不与 6.x 比较。** 仅 `7.0.0-SNAPSHOT` 与 `7.0.0` 发布允许
`japicmp.skip=true`。7.0.0 可从 Maven Central 下载后，在推进开发版本之前把 `pom.xml` 中的
`japicmp.baseline.version` 改为 `7.0.0`、`japicmp.skip` 改为 `false`，然后运行 `mvn -B clean verify`。
默认构建测试与废弃登记生成器会拒绝后续版本继续关闭检查或使用低于 7.0.0 的基线；后续正式发布比较此前已发布的基线。

7.x 原样沿用五条规则，但“已发布”从 7.0.0 起算：（1）不改已发布公开 API 的签名；（2）新增参数走重载并保留旧签名；
（3）谨慎废弃；（4）废弃须标 `@Deprecated(since = "…", forRemoval = true)`；（5）移除只凭该标注，
并且自警告实际随发布送达起至少跨过一个 MINOR（本基线不早于 7.0.0 起算）。6.x 的台账与移除记录只读归档在
`compatibility/archive/6.x/`，生成器不消费归档；新基线期间生效 JSON 与 Markdown 仅由当前源码声明生成。
仍存的 6.x 废弃标注保留原有元数据，不代表已授权在 7.0 删除对应 API。[`6.x` 分支的历史例子与例外](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md)只解释旧版本线，
不构成这五条 7.x 规则的例外。

**This project's version numbers are a product-stage signal, not a strict semver contract.**

- **PATCH**: small updates and urgent fixes. No public API is removed.
- **MINOR**: feature evolution. **May include removal of public API.**
- **MAJOR**: reserved for a change of direction at the framework level. It will not be issued
  merely to clean up deprecated API.

Runtime libraries (the Paper loader) follow their own versioning,
not this framework's version-number contract.

Packages and types marked `@ApiStatus.Internal` are not part of the removal list even when
japicmp's exclusion list carries an entry for them — japicmp reads bytecode, not `@ApiStatus`,
so an internal-only removal still needs an exclusion entry to keep the binary-compatibility gate
green, but it was never public API and its removal is not a compatibility event.

The 6.x removal list is frozen in [`compatibility/archive/6.x/DEPRECATIONS.md`](compatibility/archive/6.x/DEPRECATIONS.md)
(machine-readable: `compatibility/archive/6.x/deprecations.json`), outside the active generator's inputs.
The active registry is generated at `mvn verify` from current source deprecation annotations;
when japicmp is enabled, disagreements with its binary-compatibility report fail the build.
During the 7.0.0 baseline reset it is source-only; after comparison is re-enabled, history accumulates
within the new baseline. `DeprecationRegistryGenerator` remains mandatory and is compiled into
`target/buildtools-classes` independently of test compilation. Both `mvn verify -DskipTests` and
`mvn verify -Dmaven.test.skip=true` still generate `compatibility/DEPRECATIONS.md` and
`compatibility/deprecations.json`: neither flag skips registry generation or the post-7.0.0 re-enable guard.

- [`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md) — the active generated registry,
  starting from current declarations at 7.0.0. The frozen 6.x registry is
  [`compatibility/archive/6.x/DEPRECATIONS.md`](compatibility/archive/6.x/DEPRECATIONS.md).
  Historical replacements may themselves have been removed later; use the 7.0 migration sections for the current migration target.

- [`compatibility/archive/6.x/records/6.3.0.md`](compatibility/archive/6.x/records/6.3.0.md) — the 6.3.0 cycle's
  behavioural record: what each removal actually deletes, its replacement, and — per this
  document's own downstream-diagnostic design — exactly what an un-recompiled downstream JAR
  sees.

### When a public API becomes eligible for removal

An API is placed on the removal list only when both of the following hold:

1. It carries `@Deprecated(since = "…", forRemoval = true)` in the source;
2. At least one MINOR release has passed since **the first release that carried that annotation**.

The clock in condition 2 starts at the version where the warning actually reached you, not at the
value written in `since`. `since` expresses when we consider the API to have become deprecated and
can be backfilled; the starting point cannot. The registry (`compatibility/DEPRECATIONS.md`) records each entry's `since` value as written in the source. It does
not record the first release that carried the annotation; that release is the one the clock counts from.

These two conditions are the complete basis for removal. Downstream usage counts are not part
of it. The usage figures recorded for each removal (in `compatibility/archive/6.x/records/6.3.0.md`) are
informational: they tell you which removals carried real migration cost, but "zero references"
only proves that nobody in the repositories we can see uses it, not that no third party outside
the organization does.

`forRemoval` is used rather than a bare `@Deprecated` because javac's `-Xlint:removal` has been **on
by default since JDK 9**, while `-Xlint:deprecation` is **off by default**. An API marked
`forRemoval` is reported by name at every use site in your build; one marked only `@Deprecated`
produces a single summary line with no API name and no line number. We therefore treat "you were
warned by name" as a precondition for removal.

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

Libraries the framework delivers at runtime through its Paper loader (see [Where runtime dependencies come from](#where-runtime-dependencies-come-from))
are not covered by the framework's version number either. They keep their own versioning, and a
framework MINOR or PATCH release may move one of them to a new major version; that release's notes
and its entry in this file name the library's removals. A module that calls such a library directly declares it itself and checks those notes when
the framework moves it.

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
What it does do, if your build has a shade or shadow step, is bundle the framework into
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

**Migration guide for module authors.** The fix is a rename or a deletion — never a rewrite:

| Your current override | What to do |
|---|---|
| `unregisterSelf()` doing real cleanup work | Rename the override to `onUnregister()`. Remove every `super.unregisterSelf()` call from the renamed hook, keep the module's real cleanup work, and let the framework unregister commands and listeners after `onUnregister()` returns. Keeping the old superclass call would re-enter the hook recursively. |
| `unregisterSelf()` that is empty or log-only | Delete the override outright — the framework's own `onUnregister()` default body already does nothing, and the module's log line (if any) becomes dead weight once removed |
| `reloadSelf()` doing real reload work | Rename the override to `onReload()`, keep the body verbatim. Drop any `super.reloadSelf()` call inside it — it is no longer callable. The framework's own reload steps (configuration reload, config-bound timing refresh, `@ConditionalOnConfig` drift report) always run before `onReload()`, whether or not the old override called `super`; the framework's per-module reload line is logged after `onReload()` returns, or a failure line if it throws |
| `reloadSelf()` that only logs "reloaded" or similar | Delete the override outright — the framework logs its own per-module reload line |
| `reloadSelf()` that already called `super.reloadSelf()` | Rename to `onReload()` and drop the `super` call; no other change needed |

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

**What a module author has to check.**

- Code that relied on the one-tick delay — for example a command body that expected the dispatching
  event to have finished first — must schedule that work itself with `runTask`.
- **Paper's rule for inventory clicks.** An `InventoryClickEvent` handler (including a GUI library's
  `onClick` callback) must not open or close an inventory directly, and must not call
  `performCommand` or `Bukkit.dispatchCommand` directly either: a module command dispatched there
  runs its body — which may open or close an inventory — inside the click event. Defer the call with
  `Bukkit.getScheduler().runTask(...)`.
- **A body that dispatches another command** runs the nested body on the same thread before its own
  has finished. The audit user (`AuditableDataEntity`'s current user, written into `created_by` /
  `updated_by`) is saved before each body and restored after it: the nested body sees its own sender
  (none for a sender that is not a player), the outer body sees its own sender again afterwards, and a
  thread that carried no user before the outermost command carries none after it.
- **`@UsageLimit` and re-entry.** A body that dispatches its own command while holding its lock gets
  the nested dispatch refused with the ordinary lock message (`SENDER`: from the same sender; `ALL`:
  from any sender). Acquiring never waits, so nothing blocks; the outer lock is released when the
  outer body returns, normally or by throwing. Schedule the nested call with `runTask` if it must run.
  A body that dispatches its own command **without** `@UsageLimit` and without a stopping condition
  recurses on the main thread until the stack overflows.
- **Two dispatches in one tick meet the cooldown.** The second of two dispatches of a `@CmdCD` command
  by one player in the same tick is refused.

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

The eight data-store members removed are:
`DataStore#checkOwnership(File, Class)` and `#checkOwnership(UltiToolsPlugin, Class)`, and
`getOperator(UltiToolsPlugin, Class)` / `getOperator(File, Class)` on `JsonStore`, `MysqlDataStore`
and `SQLiteDataStore`. Use `DataStore#getOperator(DataScope, Class)`; modules should call
`UltiToolsPlugin#getDataOperator(Class)`. This is a breaking binary change for third-party callers:
update and recompile before upgrading, or invoking a removed member can throw `NoSuchMethodError`.

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
  value no longer causes the single background flush that 6.x performed: in 7.0 it logs one startup
  warning and starts no periodic flush. The normal shutdown checkpoint still saves the JSON data.
  Correct the value and restart.

中文补充：`run`、`runLater`、`execute` 提交的一次性任务抛出异常时，恰好记录一条带拥有者名称与
原始异常堆栈的 WARNING，句柄进入 `FAILED`，完成阶段以该异常失败。`supply` 的异常只交给返回的
future；若任务开始后该 future 已被取消，无法再观察到的异常才记录一次 WARNING。重复任务仍每次
失败记录一条 WARNING，并继续后续执行。`datasource.flushRate` 必须设置为正数秒；负数不再像 6.x
那样触发一次后台落盘；7.0 会在启动时记录一条警告，不启动周期落盘。正常停服的最终落盘不变；
请修正该值并重启服务器。

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

The public extension point is `com.ultikits.ultitools.config.convert.ConfigConverter<T>`, discovered with
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
| A **shipped** configuration file (a module's `config.yml` - for example UltiEconomy's, which holds the interest settings - `spawn.yml`, the framework's own `config.yml`) | (1) the file itself, the first time, when it does not exist; (2) a declared key the file lacks, and the framework's own comment lines - inserted only, no existing content changed; (3) exactly the item the operator explicitly asked to change - a command (`/setspawn` writes only the location, `/autoreply` only that rule), a panel edit (only the named key), or a change the operator made through the module and the module saves; (4) a value that still equals the shipped factory text byte for byte, re-rendered after a language switch, and a line that is byte for byte unedited legacy shipped text the module removes | a save at server stop, module unload or replacement; a whole-object "incidental" save; a repair of an invalid value; any layout or formatting normalization; automatic split-dotted-key cleanup; a comment the operator wrote |
| An **operator-created** file (a kit, a menu) | the file, only on the operator's explicit create action (`/kit create`); on an explicit edit, only the edited part (the kit editor writes that kit's items; comments and other keys stay) | anything else |

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

**A section emptied by deleting its keys is completed again**
([#620](https://github.com/UltiKits/UltiTools-Reborn/issues/620)). Deleting a key is how an operator resets a setting: the
next start puts the key back with its declared default and comment. When the deleted key was the last one of its section,
the section key is left alone on its line with nothing after the colon (`messages:`), which reads as no value at all. The
maintainer's decision of 2026-10-06 treats that as most likely accidental, so the framework puts the section's keys
back below that line instead of refusing at every start. It is the one line outside a write's own keys that the gate
lets the write touch, and only in this shape: the line keeps the key as written (its indentation and quotes included), the comment
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

**For operators: to leave a text setting blank, write an empty string; do not delete the key**
(maintainer decision of 2026-10-06). Write `key: ''`: for a text setting, an empty string is read as blank and is never
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
it is absent.

Framework-owned files (the `.ultitools-backup-<hex>` backups, `.ultitools-resource-hashes.json`, the credential store,
module JARs and transaction records) are not operator configuration.

中文补充（写入约定）：**服主写的配置绝不被自动覆盖**（维护者 2026-10-04 的底层规则）。框架或模块的任何代码都不能改动服主在配置文件里写的内容，除非服主明确要求这一改动；框架用不了的值在代码使用前就挑明（警告并拒绝该值、使用声明默认值，或拒绝模块、拒绝启动），绝不靠改写文件“修好”。按文件类型（维护者 2026-10-04 批准的表）：**出厂配置文件**（主配置（例如保存利息设置的 UltiEconomy `config.yml`）、`spawn.yml`、框架自己的 `config.yml` 等）代码只能（1）首次创建不存在的文件；（2）补入文件缺少的声明键和框架自己的注释——只插入，不改已有内容；（3）只写服主明确要求改的那一项（命令、面板、GUI，或服主通过模块做出、由模块保存的改动）；（4）语言切换后重新渲染仍与出厂文字逐字节相同的值，以及删除逐字节未改过的旧版出厂文字行。除此之外一律不写：不在关服、卸载或替换时保存，不做整对象的顺带保存，不修复无效值，不规整排版，不自动清理拆开的含点键，不改服主写的注释。**服主自己创建的文件**（礼包、菜单）只在服主明确创建时创建、明确编辑时只写编辑的部分。所有写入经同一个写入闸门：每次写入声明自己拥有的键（或框架自己的注释行），渲染后其余每个字节必须与读取时相同，且文件仍是读取时的内容；否则不写，一条 WARNING 列出文件、键和原因（排版差异可定位时给出要改的行号；渲染结果无法回读时说明原因，不带行号），从不列值，内存中的值照常使用。服主命令调用的 `saveOperatorChange`/`saveOperatorMapEntry` 被拒绝时抛 `ConfigWriteRefusedException`，命令据此回复“未保存”及原因；面板编辑收到注明原因的错误。服主看到拒绝时：按警告修正它描述的排版或指出的那一行（“文件在读取后已被改动”则先 `/ul reload`），再做一次改动即可，所写内容没有丢失。写入未被拒绝、但发布失败时抛出普通 `IOException`，文件同样保持原来的字节：打开文件后中途失败的写入会在抛出异常前从框架备份放回；连放回也失败时，一条 SEVERE 列出文件和保留的备份，该配置在重载前拒绝一切写入（见 "Rendering and save fallback" 一节，#622）。在该 `IOException` 时回滚内存改动的模块因此与文件保持一致。

中文补充（被清空的节）：删除一个键就是把该设置重置为默认值，下次启动时框架会连同注释把它补回。如果删掉的是某个节里最后一个键，节的键名会单独留在一行、冒号后什么都没有（`messages:`），读作“没有值”。按维护者 2026-10-06 的决定，这多半是误删，框架会把该节的键补回到这一行下面，而不是每次启动都拒绝写入（#620）。这是闸门允许一次写入触碰的、写入自身键之外的唯一一行，而且只限这种形状：该行按原样保留键（包括缩进和引号）、注释文字和换行符，只有空白可能被规整——冒号前的空格、注释前的空格，以及注释后或无注释行末尾的空格（`messages :   # reset  ` 补键后成为 `messages: # reset`）；节的值只会从“没有值”变成恰好是补入的那些键；其余每一行逐字节不变。服主在这一行下面注释掉的子键行留在原处、位于补回的键下面，前提是这些注释行紧跟在节所在行之后、连成一段中间没有空行，并且位于文件在该层级的缩进处（两空格缩进的文件里就是两个空格）。以下三种形状则会在每次启动时被拒绝，一条 WARNING 写明行号，文件不变：节所在行与注释掉的子键之间有空行；两段注释之间隔着空行；子键被注释在比文件缩进更深的位置（两空格文件里的四个空格）。删掉空行或把注释移到文件的缩进处，下次启动就会补回键。服主命令、服主改动和面板编辑在这样一行下面添加键时同样如此，例如在被清空的 `rules:` 下添加第一条规则。写成显式空值的节（`messages: {}`、`messages: ~` 或 `messages: null`）是服主自己写的值，绝不会被改动：在它下面插入会被拒绝，警告会写明该节及其行号，并说明可以怎么做——删掉这个值、让该行在冒号处结束（下次启动即补回键），或手动添加这些键（#610 第 2 项）。

中文补充（给服主）：**要让某个文本设置留空，请写空字符串，不要删除这个键**（维护者 2026-10-06 决定）。写成 `key: ''`：对文本设置，空字符串读作“留空”，启动、重载或保存其他设置时都不会改写它。删除的键会在下次启动时以声明的默认值补回——这是重置设置的方法，不是留空的方法。数字、true/false 等非文本设置没有“留空”的值：`count: ''` 会以一条点名该键的 WARNING 报告（`declared as int: found text …`），内存中使用声明默认值，文件里的 `''` 原样保留。

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
[#600](https://github.com/UltiKits/UltiTools-Reborn/issues/600)). No write normalizes layout or expands anchors: a
panel edit on a file the gate refuses replies with an error naming the reason, and the file keeps its bytes. A block
comment before a list item prevents a changed write to another key, or one that keeps the list's length (see below).
A write that owns the list and changes its length can go through, but drops the item comments; this is not a comment
preservation guarantee. The storage API signatures are unchanged.

#### Layouts the write gate cannot keep

The renderer generally writes a document back in one canonical layout; it retains the source spelling of
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
by hand` ([#610](https://github.com/UltiKits/UltiTools-Reborn/issues/610) item 2). A section left with no value at all (`messages:`) is not refused: the keys
are put back below it ([#620](https://github.com/UltiKits/UltiTools-Reborn/issues/620), "The write contract"). Measured
layouts that refuse (each pinned by a test, `OperatorFileWriterLayoutRefusalListTest`):

- a line holding only spaces, and a file holding only spaces;
- a trailing space after a value or after a section key (`interval: 300 `);
- an inline comment aligned with several spaces (`enabled: true    # note`; one space before `#` is kept);
- more than one space after a colon (`key:  value`), and spaces inside flow brackets (`worlds: [ world ]`);
- a document start marker `---` or end marker `...`;
- changing or removing a block scalar value (`|`, `|-` or `>`) when its trailing separator lines would be lost.
  An unchanged block scalar followed by empty lines does not prevent inserting a missing default key
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
  Unicode spelling per file, so the other spelling's line is named and the write refused
  ([#836](https://github.com/UltiKits/UltiTools-Reborn/issues/836)). Use one spelling per file - raw UTF-8
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
[#592](https://github.com/UltiKits/UltiTools-Reborn/issues/592)), and an unchanged block scalar with trailing
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

Fixing the named line - removing the trailing space, the extra blank line or the alignment - lets the next write through.

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
SnakeYAML 2.2 and comments enabled. In the measured 2.2 environment, the framework treats such a file as **unparseable**:
it is never written (no insert, comment rewrite, save, operator change or panel edit touches it), the module runs on its
declared defaults at start-up (a reload keeps the running values), and the existing SEVERE line names the file:
`Cannot load <file>: <parser location>; file will not be overwritten`. Nothing is lost - the file keeps every byte - but
the operator's values in it are not used until the layout is changed.

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

A failure after the in-place write has opened the target does not leave a partial target
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
Warnings name the file, key and failed position/type and the converter's own
reason (the message it gave `ConversionException`, without the location prefix) as `(reason: ...)`;
secret-shaped values and nested credentials are redacted, the reason with them, and a multi-line
reason is kept on one log line. Raw diagnostic values use the same normalization:
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
are supported. A `List<Integer>` reads `- '60'` as the number 60 and writes `- 60` the next time
its module changes that setting and saves it, or a panel edit sets it. Loading, reloading, a save that
did not change that setting and server stop never rewrite such a file on their own. Equality is semantic value comparison, not object identity; numeric plain values
compare by value. Reload merges and panel leaf edits rely on forward equality. A converter that adds
a value during reading without undoing that change during writing violates this contract.

Whole map keys, including `g.m`, `o.O` and `wave.`, are supported.
`@ConfigEntry.path` still splits at every dot: `chat.aliases` selects nested settings, whereas a
key `o.O` inside the bound map is one whole key. These are different path conventions. Legacy 6.2 files in which Bukkit
split a dotted key into nested mappings are read as they are, never automatically merged or renamed, and a save never removes
such an entry: it stays until the operator removes it (maintainer decision of 2026-10-04).

A map declared with non-text keys - `Integer`, `Long`, `Boolean`, `UUID` and the other scalar key types - loads and writes
([#673](https://github.com/UltiKits/UltiTools-Reborn/issues/673)). An entry the file already holds is always
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

A declared setting written as a flat dotted key is that setting (#612). When a module declares
`@ConfigEntry(path = "features.chat")` and the file holds `features.chat: false` instead of `features:` / `chat: false`,
the module binds `false`; the same holds for any split of the path's dots (`a.b:` / `c: 1`
for `a.b.c`). The framework never adds a nested copy of such a setting: a missing-key insert, a module save, an
operator command (`saveOperatorChange`, `saveOperatorMapEntry`) and a panel edit write the setting on the line that
holds it, through the write gate, and the panel shows it once under its setting path. `@ConditionalOnConfig` reads
its key with the same document reader and the same rule, so it reads the value
the module binds, before and after a start-up insert; only a boolean value counts. `isPresentInFile` reads a
path the same way (a flat dotted key counts as present). A setting written in two forms in one file, such as
`features.chat: false` and `features:` / `chat: true`, is refused before module code runs: the module does not load
(a reload is refused and keeps the running values), and the message says the setting is written in two forms and names
the file and the setting path, never a value. The same holds for a path `@ConditionalOnConfig` reads that no config entity
declares: the module's component scan is refused.
Two forms holding the same value are refused too; accepting equal copies would let a later write change one copy and
refuse the module at the next start. Delete one of the two lines and restart. This applies to declared setting paths only:
keys inside a map value keep their whole-key meaning (`o.O` above).
A setting the file lacks is inserted below the section the file already holds it in, never into a second form of that
section ([#614](https://github.com/UltiKits/UltiTools-Reborn/issues/614)): below the longest prefix of the
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

A module's `save()` writes only what the module changed in memory since the last load or save, and only
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
value or list element the framework could not use (`interval: 3O0`, `[60, 30, 10, abc]`) and a split map entry are
never written over by a save, whether or not the module changed that setting
([#596](https://github.com/UltiKits/UltiTools-Reborn/issues/596)). A change that is not written stays in memory, and one
warning per save names the file and those keys, never values; a key the write gate refuses is named in the gate's warning
instead, with its reason. The write goes through the write gate above, so every other line of the file stays byte for byte,
or nothing is written. "The file still holds what was read" compares the text the framework last read or wrote: an
operator edit saved to disk without `/ul reload` - even one that keeps the value, such as `y: 64` changed to `y: 64.0` -
makes a later module change of that setting not written (named in the warning) until a reload reads the file again;
nothing is lost, and the module's value stays in memory. With nothing to write the file is not touched (bytes and modification time stay). A failed write
never acknowledges the pending effective values as saved. Use `save()` for a change the operator asked for through the
module, or for shipped text the module re-renders after a language switch.

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
throws `com.ultikits.ultitools.config.ConfigWriteRefusedException`, an `IOException` whose message and
`getReason()` name the reason without any value; the file keeps its bytes and the in-memory value stays, unsaved. A path
that is not a declared entry, or a map-entry call on a setting not declared as a map, throws `IllegalArgumentException`
and writes nothing. Both run under the entity monitor and must be called on the server thread.

A map-entry write can also be made conditional on whether the entry is in the file
([#623](https://github.com/UltiKits/UltiTools-Reborn/issues/623)): `#saveOperatorMapEntry(EntryPresence, String, String...)`
with the enum `com.ultikits.ultitools.config.EntryPresence`. Use `MUST_BE_ABSENT` for a command that creates an entry
(`/autoreply add`) and `MUST_BE_PRESENT` for one that changes an existing entry (`/autoreply setkeyword`). A module therefore
needs no second read of the file with its own parser. The condition is decided on the read whose bytes the write gate
verifies the edit against, and the gate checks those bytes again immediately before it replaces the file. So an entry the
operator added or deleted by hand since the load is seen, and an edit saved after that read makes the gate refuse the write
as "the file changed". "Present" means the entry's whole key path exists where the framework reads the setting: its nested
keys, or the flat dotted form ([#612](https://github.com/UltiKits/UltiTools-Reborn/issues/612)), followed by the map keys,
each one whole key. An entry holding `null` is present. An entry under a missing, `null`, empty (`{}`) or non-map key is
absent, and so is every entry of a missing, empty or comment-only file. When the condition does not hold, nothing is
written (bytes and modification time stay) and the framework logs nothing above FINE. The call then throws
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
keeps declared defaults and logs one SEVERE naming the file and safe cause. A
failed reload keeps running fields and the file unchanged and throws `ConfigurationException`
(`CONFIG_LOAD_FAILED` for an unreadable file, `CONFIG_PARSE_FAILED` for one that does not parse)
naming the file and the same safe cause, instead of logging and returning normally; the entity logs
nothing itself, because its caller reports it: `ConfigManager#reloadConfigs` lets it through, the
module's reload logs one SEVERE line naming the module and the cause, `/ul reload <module>` replies
that the module failed to reload, and a full `/ul reload` names the module among its failures. A
module that calls `reload()` itself and catches only `IOException` sees this unchecked
exception propagate; catch `ConfigurationException` to report it in the module's own reply.
Explicit save does not clear protection; only a later successful load permits writes again.
Parser diagnostics expose only numeric line/column metadata, never source snippets or scalar values.

**Partial module reload (accepted behavior, #593 / #598).** The first failing configuration file stops that
module's remaining reload steps. Earlier files keep the values already reloaded; the failed file keeps its running
Reloading message overrides, refreshing token comments and invoking the module hook are not reached.
files can therefore keep old-language framework comments. Fix the failing file and complete a successful reload to
refresh them; a full `/ul reload` applies a changed server-wide language. There is no module-wide rollback.

`validateProposedProperties` and `updateProperties` report a refused edit as an edit that was
not applied, naming each violating declared configuration key (for example `allowed-commands`, not Java member
`allowedCommands`). The file and running values are unchanged; correct the proposed values and try the edit again.
These edit diagnostics do not ask for a restart.

An annotation comment that is exactly one `{key}` token (surrounding whitespace ignored) is resolved
from the module catalogue. Start-up and reload rewrite, in the current language, only the comment
lines above a token entry that the framework can identify as its own (a save, an operator change and a panel edit rewrite no
comment; a key one of them inserts gets its comment): the entry's comment, as a whole
or as its trailing run of lines, equal byte for byte to what the framework writes (the entry's
column, `#`, a space and the text) for the token's text in a catalogue the module's jar ships, for the text the
module resolves now, or for the bare token. Equality is the only test; the same text without the space
after `#` or at another column is the operator's. Every other comment line - a note an operator wrote above a token entry, a framework comment the
operator edited, a literal-entry comment - is kept byte for byte, permanently (maintainer decision of
2026-10-04, [#604](https://github.com/UltiKits/UltiTools-Reborn/issues/604)). A framework comment whose text no
shipped catalogue holds any more - an older module version's wording - is recognised only when the module
lists that text in the `@ConfigEntry(previousComments = {...})` attribute (default empty):
a comment equal to a listed text, in the same exact written form, is replaced by the current catalogue
text and follows the language from then on (maintainer decision of 2026-10-04); otherwise it is kept as
it is. A token entry without any comment gets the framework's.

A framework comment line the operator deletes, keeping its setting, is written again at the next start
and at the next `/ul reload`, with or without a language switch, at its place above the setting and in the
language selected at that moment - unless a comment line of the operator's own remains directly above the
setting: that line is then the setting's whole comment, it is the operator's, and nothing is written above
it. No value, key or line the operator wrote changes; with a language switch, the setting's other framework
comments follow the switch as usual (pinned by `DeletedFrameworkCommentTest`).
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

Package/directory configuration registration buffers initialization writes until every
entity binds and validates. A refused batch creates no file and changes no existing file, including
missing-key and language-token comment rewrites. Once accepted, files persist independently; an I/O
failure preserves earlier successful files and protects the failed entity until a successful reload.
The flush writes through the write gate (see "Rendering and save fallback") and only while the file still
holds the bytes read at registration; an edit made in between is kept, with one warning.
Standalone registration still writes immediately. Framework-internal initialization bridges are not
a module transaction API.

### Superseded-copy configuration ordering

This ordering concerns registration of a superseding instance, not a managed JAR update (see
[Module changes wait for a restart (as of 7.0.0)](#module-changes-wait-for-a-restart-as-of-700)).

No configuration of a loaded older module copy is saved before, during or after a newer copy replaces it
(maintainer decision of 2026-10-04, "what code may write, by file type": no replacement save of whole entities, because a
module update is not an operator's request to write). The newer copy is constructed and reads the files as they are; the old
copy's unsaved changes or a protected (unreadable or unparseable) old file do not block the replacement. The old copy is
not unloaded before incoming activation succeeds; once it does, one warning names the dropped file/entry keys (never values)
and the old configuration entities are released. Failed incoming construction, compatibility, assembly or activation
releases only refused incoming configuration owners; existing owners remain registered.

Registrations follow the same rule, with one step earlier (#562): every panel responder, EventBus handler and tab
completer recorded against the old copy is released after the incoming copy passes its compatibility gates and before its
container refresh and `registerSelf()`, so the incoming copy can claim the panel message types and completer keys the old
copy held. When incoming assembly or activation fails, what the incoming copy registered is released and the old copy's
registrations are put back, unchanged; the old copy keeps running. When it succeeds, the old copy is unloaded.

### Configuration registry server-thread confinement

All ConfigManager registry operations are server-thread confined while a server
runs. Direct off-thread register/registerAll/saveAll/unregisterAll/reloadConfigs calls warn once
and do no work. Getters, toJson/getComments and both loadFromJson overloads warn once and throw
IllegalStateException, rather than returning a misleading empty result or successful write.
The no-server case remains supported. getAllConfigEntities preserves null for an unregistered
module and otherwise returns an unmodifiable detached map; its entities are not copied.
Panel update, upload-write and reconnect upload-read callbacks queue their whole operation and
return immediately off-thread, responding only after the queued operation runs. Registry guards
precede entity monitors. Multi-file panel transactions retain deterministically ordered touched-entity
monitors through validation, preparation, commit and acknowledgment or rollback; direct single-entity
persistence uses its existing monitor. No separate manager lock or blocking scheduler wait is used.
Async registry callers must schedule on the server thread.

### Configuration release; nothing written at stop

Module unload releases that module instance's configuration registry entry even
when its unload hook or context close throws. **Nothing writes configuration at server stop, at
module unload or uninstall, or at module replacement** (maintainer decision of 2026-10-04: no
shutdown or replacement save of whole entities; a lifecycle event is not an operator's request to
write). At stop, and at a normal unload or uninstall, after each module's unload hook and container
`@PreDestroy` callbacks and just before its release, every configuration of that module holding module
changes that were never saved is named in one WARNING - the file and the changed keys, never values -
and the changes are dropped; the framework's final step at stop names, the same way, anything still
registered. A superseded copy is named once by the replacement's own drop warning instead. A configuration whose file
could not be read or parsed at its last load is named once instead. An operator's edit made
while the server runs is never touched at stop. A module persists a change when it makes it, with
`save()` (only what the module changed, where the file still holds what was read) or
`saveOperatorChange`/`saveOperatorMapEntry` (exactly what an operator's command names).
`ConfigManager#saveAll()` keeps its signature, writes nothing, reports as above and is
`@Deprecated(since = "6.3.0")`.

### Configuration init and reload thread contract

Configuration init, reload, manager reloadConfigs and module reloadSelf refuse
calls off the server thread while a server runs. One warning names the module, entity path when
applicable and caller thread; no field/file/lifecycle action occurs. Checks precede entity monitors.
The no-server test harness case remains allowed. `reloadSelf()` is `final`. Async third-party callers must
schedule their reload on the server thread.

Test fixtures that install a Mockito `Server` must provide a non-null `getLogger()` result, as
Paper does, and explicitly stub `isPrimaryThread()` to match the scenario. An unstubbed server
reports `false` and returns a null logger, so the off-thread refusal encounters a
`NullPointerException` while logging instead of returning normally (#582). For ordinary synchronous
configuration tests, return `true` from `isPrimaryThread()`; for refusal tests, return `false` and
provide a logger. Do not use an incomplete server stub as evidence of a runtime writer failure.

### Reload merge rule

Reload compares the last effective disk baseline, current serialized fields, and
incoming disk values. Memory-only changes survive and stay dirty; disk-only changes are adopted.
Maps merge recursively by whole keys; lists and scalars are atomic. Conflicts take the file's value
and warn with the located key and, for a scalar, the discarded value, redacting secret-shaped values; a conflict over a list
or a composite value names the key only. Absent map keys
and explicit null differ. A whole declared field missing from the file binds its declared default when the module had not
changed it (one warning, naming the file and key, when the previous load still held the key - that is, the operator
deleted it), and keeps its live value when the module had (both with the declared-default baseline); the file is not
written.
Unreadable/unparseable reloads keep live values, protect the file and throw
`ConfigurationException` naming the file and the safe cause (#589).
After a module's message overrides are reloaded, and before its own reload hook, the framework rewrites
only its own comment lines of that module's configurations in the new language, through the write gate over a fresh
read; no value, key or other comment line is written ([#594](https://github.com/UltiKits/UltiTools-Reborn/issues/594)).

### Panel edits inside map entries

A changed panel leaf inside a declared map setting is applied through that field's
full declared-type converter. Real whole keys containing dots remain whole. A path with multiple
readings refuses with every reading named; an unknown changed key is explicitly refused. Any refused
changed key refuses the whole payload, naming all refused paths. Unchanged displayed leaves are not
edits, including undeclared operator keys and ambiguous paths. Leaf edits preserve unrelated pending in-memory siblings and their dirty state,
and preserve independently edited disk siblings without acknowledging them. Full declared-field
conversion and validation still run: the live field is serialized, only targeted plain leaves are
patched, and the registry binds that candidate once. The round-trip contract preserves untouched
bound siblings without any type-specific map/object merging. Only targeted leaves are persisted, through the write gate
owning exactly those leaves (a map that is empty, or that the edit empties, is owned whole).

A panel edit of one field inside a composite value (a Bukkit `Vector` or `Location`, also as a map entry) writes that whole
value exactly as the file held it with only the edited field changed, so the other fields keep the bytes the operator wrote
(`y: 64` and `pitch: 0` stay, never `64.0`). A whole number - sent by the panel, or already in another field of the value in
the file - is widened to a floating-point number for the conversion where the module's own value holds one (Bukkit's `Vector`
does not widen by itself), and the edited field is written in that type (`7` is written as `7.0`), so the file the edit leaves
behind loads ([#609](https://github.com/UltiKits/UltiTools-Reborn/issues/609)). A whole number in the file in a `Vector` or
`BlockVector` coordinate (`y: 64`) loads as that number: Bukkit's `Vector` reads its coordinates as `Double` only, so
the framework widens `x`, `y`
and `z` of a `Vector` in memory only - the file keeps `y: 64`, an unchanged save writes nothing, and no other type is widened.
An edit below a map group the module removed in memory and has not
saved is refused, naming the key with "not found in memory".

### Multi-file panel persistence

`ConfigManager#loadFromJson(String)` validates every touched configuration, stages
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
The single-file opt-in helper for #545 does not change this limitation or the panel's writer.
Internal staged-entity
coordination bridges are not a module transaction API.

### Writing operator files a module manages

`com.ultikits.ultitools.config.OperatorFiles` is the way a module writes a YAML file it
manages on an operator's behalf - a kit file, a menu file - on an explicit operator edit (maintainer decision of
2026-10-04: such a file gets only the edited part; comments and other keys are kept). `OperatorFiles.read(File)`
returns the file's text (strict UTF-8) and the SHA-256 of its bytes; `OperatorFiles.write(Snapshot, Map<List<String>,
Object>)` writes only the named whole keys (a key containing `.` is one key), through the configuration write gate,
only while the file still holds the snapshot's bytes, and returns `WRITTEN`, `UNCHANGED`, `FILE_CHANGED` or `REFUSED`
(a layout the gate cannot keep byte for byte, or anchors, with one WARNING naming the file, the keys and the reason).
Values must be plain data - serialize an `ItemStack` first; anything else throws `IllegalArgumentException` before
the file is touched. It never creates, deletes or renames a file and is not for `@ConfigEntity` files, which keep
their own save rules. Its publication, live-link handling and backed in-place fallback are unchanged by the separate
opt-in API below.

### Strict single-file module YAML publication (6.4.0, opt-in)

`com.ultikits.ultitools.config.CrashSafeYamlFiles` is a YAML-only helper for a module's **explicit operator
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
`OperatorFiles.read(File)`) and therefore use the JVM's default filesystem. The helper requires ordinary files
on a local default POSIX filesystem with native atomic rename/link semantics, working POSIX attributes and an already
existing parent. The real parent is resolved before appending the final basename, so a parent symlink followed by `..`
keeps filesystem meaning. There is no path sandbox; modules supply trusted paths. An existing final symlink is refused,
live or dangling; directories/devices and other nonregular write targets are refused, not followed or replaced.

Existing files preserve exactly the nine POSIX permission bits, owner and group, applied and verified on the staged
inode before commit. Failure to reproduce a promised attribute aborts publication. A file the process cannot write, or
with no POSIX write bit, is refused even if directory permissions would permit replacement. Staging is exclusively
created at 0600 before any content, written through that created channel, narrowed to 0000 before any necessary
owner/group transition, then given its final mode and verified. New files keep the process-created owner/group and
0600: **this is the helper's default, not a change to other writers**. Non-POSIX providers (normal Windows included) and a
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

Here is a map-shaped value migration. Put the value/field members in a module class named
`MigrationExample` in package `example.config` (imports go before the outer class). Put the converter
in its own public top-level `TokenConverter.java` in the same scanned package, as shown separately. `Token`
must provide semantic `equals`/`hashCode` in production so round-trip comparisons mean value equality.
The converter deliberately accepts only the one-key shape it can reproduce; accepting extra keys
and dropping them would violate `toPlain(fromPlain(p)) == p`.

```java
import com.ultikits.ultitools.annotations.ConfigEntry;

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

Known limits remain explicit, not guaranteed away: [#578](https://github.com/UltiKits/UltiTools-Reborn/issues/578)
records a remaining temporary-file identity limit through a symlink followed by `..`. Avoid that path combination and
use the same resolved real path when staging and loading configuration files.
A file that uses anchors, aliases or merge keys is never written; one warning per file per run names it.
No general alias-preservation guarantee is claimed, and apart from putting back the in-place write that just failed
(#622), nothing is restored from an earlier backup. Crash-safe multi-file transactions remain unsupported;
the single-file opt-in helper for [#545](https://github.com/UltiKits/UltiTools-Reborn/issues/545) does not solve that limit.

Three YAML spellings fail the write gate's check. A framework write changes only the keys it owns and verifies
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

- 扩展点是 `ConfigConverter<T>` 和 `@ConfigConverterFor`，转换器必须是扫描包内带 public 无参构造器的 public 顶层类；扫描器不发现嵌套转换器。它不是 IoC bean。未知声明类型在读取或创建文件前拒绝模块加载。
- 注解 `path` 中的点仍表示嵌套路径；映射里的 `g.m`、`o.O`、`wave.` 按完整键保存。已经拆开的文件原样读取，不会自动合并，保存也不会删除这些条目，留给服主自己删（维护者 2026-10-04 决定）。完整泛型参与绑定；无效集合元素跳过，无效字段用声明默认值；重载时文件里缺少的键改用声明默认值（上次加载时还在、即服主刚删掉的键警告一次）（模块自上次加载或保存后改过该设置时保留模块的值），两种情况都不改文件，之后的保存也不会补回该键（#596）。允许 null 的引用类型可往返；`Object` 中的 Bukkit 序列化对象读取后仍是普通映射。
- 非文本键的映射（`Integer`、`Long`、`Boolean`、`UUID` 等标量键）可以加载和写入（#673）。文件已有的条目总按文件里的键文字写入：加引号（`'01'`）、整数写成带点（`1.0`）、指数（`1.0e10`）、别名（`yes`、`off`）或大写的写法都原样保留；只有文件没有的键才按规范文字写入，且该文字必须读回同一个键。保存、面板编辑、`saveOperatorChange` 和 `saveOperatorMapEntry` 都不会改名、改引号、改顺序或改结构，删除条目只删它那一行；`saveOperatorMapEntry("limits", "1")` 能找到文件里写作 `'01'` 的条目。服主手动改写键（`'01'` 改成 `1`）在下次重载时直接采用，不报冲突也不写文件。同一个键在文件中有多种写法（如 `1:` 与 `'01':`）时，模块仍照常加载并给出原有警告，但该设置的所有写入都被拒绝，直到服主删掉多余写法并重载；保存时一条警告列出未写入的改动，服主命令和面板编辑抛出 `ConfigWriteRefusedException`，原因只写文件、设置和键（敏感键之下显示为 `<redacted>`），从不写值。已知限制：（1）服主手动改写某个键（`'01':` 改成 `1:`）时，若模块对同一条目有尚未保存的改动，下次重载保留文件（服主的字节优先，并给出原有的冲突警告），模块对该条目的未保存改动被丢弃；请先保存模块的改动，再手动编辑该条目。（2）服主手动以一种写法加入键（`'01':`），模块在下次重载之前写入同一个键时，可能插入第二种写法（`1:`）；文件随后以两种写法持有同一个键，该设置之后的写入会被拒绝（沿用上述多种写法警告），直到服主删掉一个并重载；请在手动编辑映射键后先重载，再让模块写入。（3）对于键类型把不同文字视为同一个键的 `SortedMap`（如 `BigDecimal` 的 `1.0` 与 `1.00`），多种写法检查按 `equals` 匹配键，因此检测不到这两种写法是同一个键；没有已发布的模块使用这样的映射，请不要用两种数字写法写同一个键。
- 声明的设置写成带点的扁平键时就是该设置（#612）：模块声明 `@ConfigEntry(path = "features.chat")`，文件写的是 `features.chat: false` 而不是 `features:` / `chat: false` 时，模块读到 `false`；路径的点以任何方式拆分都算（`a.b.c` 写成 `a.b:` / `c: 1` 也可以）。框架不会为这种设置补一份嵌套写法：启动补键、模块保存、服主命令（`saveOperatorChange`、`saveOperatorMapEntry`）和面板编辑都经写入闸门写在该设置所在的那一行，面板也只按设置路径显示一次。`@ConditionalOnConfig` 用同一个文档读取器和同一规则读取，所以启动补键前后读到的都是模块绑定的值；只认布尔值。`isPresentInFile` 同样按此规则判断（扁平点键也算存在）。同一文件里一个设置写了两种形式（例如 `features.chat: false` 和 `features:` / `chat: true`）时，在模块代码运行前拒绝加载该模块（重载则拒绝重载、保留运行中的值），提示说明该设置写了两种形式，只写文件和设置路径，不写值；`@ConditionalOnConfig` 读取的路径即使没有配置实体声明，写了两种形式时同样拒绝该模块的组件扫描。两种形式的值相同也拒绝：若接受相同的两份，之后某次写入只改其中一份，下次启动就会拒绝模块。删掉其中一行再重启即可。此规则只针对声明的设置路径，映射值里的键仍按完整键处理（如上面的 `o.O`）。文件缺少的设置会插入到文件已有的那个节下面，绝不写成该节的第二种形式（#614）：插入位置是文件以节的形式（映射，或没有值的键）持有的、设置路径最长的那段前缀，其余部分写成嵌套键。例如文件写了 `a.b:` / `c: 3`、模块声明了 `a.b.d`，启动补键会把 `d:` 写在 `a.b:` 下面。如果同一节的某个声明设置把路径的剩余部分写成节下的一个带点键（如 `a:` / `  b.c: 3`），缺少的 `a.b.d` 会以同样的方式写在它旁边（`  b.d: 2`），绝不另写嵌套的 `b:` / `d:`（维护者对 #614 修复的第一道审查意见所作的决定）；这样的兄弟设置若拆分方式不同，则按下文拒绝插入。如果文件把这个节写了几种形式，设置会插入到其中唯一一个还持有该节其他声明设置的形式下；没有或不止一个形式持有时拒绝插入：WARNING（服主命令则抛 `ConfigWriteRefusedException`）写明文件、设置和节，从不写值，内存中使用声明默认值。服主命令、服主改动和面板编辑插入设置时同样如此。
- 保存内容、注释文字、键顺序和支持的文件风格。所有自动写入（创建不存在的文件、补写缺失的声明键、启动或重载时改写框架自己的令牌注释、注册批次落盘）都经过同一个写入闸门：每次写入声明自己拥有的键，渲染后其余每一行必须与读取时逐字节相同（含换行符、BOM 和末尾换行），且文件仍是读取时的内容；否则不写，警告一次（只列文件、键和原因，不列值），内存中使用声明默认值。这些写入不会规整排版：手工对齐的文件缺少的键会一直缺少，每次启动警告一次，直到服主自己补上。使用锚点、别名或合并键的文件不会被自动写入，每次运行只提示一次。框架新建文件时独占创建，绝不替换期间出现的文件。模块的显式保存同样经过写入闸门，只拥有它要写的设置（#599，见下）；服主操作写入和面板编辑同样经过写入闸门，只写它们改的设置或映射条目，不规整排版或展开锚点；闸门拒绝时面板收到注明原因的错误，文件不变（#600）。渲染器只能按一种规范排版写回；改变内容的写入若会改动本次拥有的键之外的任何字节，闸门就会拒绝（启动补键、改写注释、保存、服主操作写入、面板编辑均如此）。没有变化时直接返回未改变，不进行渲染。拒绝改动排版是有意的：规则不允许为了让写入通过而规整服主的排版（维护者授权下编排者 2026-10-05 的决定）。文件保持原样，内存中使用相应值，警告列出键和原因；排版比较可定位时给出要改的行号（“…would change (line N)”或“…shares line N…”）。若渲染结果无法回读，自检会在比较排版行之前拒绝，原因为 `the rendered text would not parse back`，不带行号。实测会拒绝的排版（每种都有测试 `OperatorFileWriterLayoutRefusalListTest` 固定）：只含空格的行或文件；值或节键后面的行尾空格；用多个空格对齐的行内注释（`#` 前只有一个空格的会保留）；冒号后多于一个空格、流式方括号内侧的空格（`[ world ]`）；文档开始标记 `---` 或结束标记 `...`；改动或删除块标量（`|`、`|-` 或 `>`）的值且会丢掉其后的空行；任何列表项之前的块注释（与列表项同列或更深，在第一项或后续项之前，包括紧跟块标量或流式项之后）：改变内容的写入若写另一个键，或保持列表长度不变，就会被拒绝；在普通项或块式映射项之前时，拒绝原因指出行号，在流式项（`- [1, 2]` 或 `- {k: v}`）之前时，原因为 `the rendered text would not parse back`，不带行号；被拒绝时原文件的字节和修改时间均不变（[#619](https://github.com/UltiKits/UltiTools-Reborn/issues/619)，由 `OperatorFileWriterLayoutRefusalListTest.aCommentBeforeAListItemRefusesAnotherKeyWithBytesAndMtimeUnchanged` 固定）；重试前请将该注释移到整个列表的键上方，或删除该注释；拥有整个列表且改变其长度的写入可以通过，但会丢掉列表项注释（同一测试类也固定了这个例外），不保证保留注释；这里指 YAML 注释，不是缩进在块标量内部的 `#` 文本；没有末尾换行的文件里，最后一个值是用 `|`、`|+` 或 `>` 写的块标量（在这种文件末尾它的值没有末尾换行，所以会按 `|-` 或 `>-` 写回；这种文件里其它位置的块标量、以及用 `|-` 或 `>-` 写的最后一个值都会保留——但把文件最后一个值写成以换行结尾的文本的写入会被拒绝，并指出那一行；值永远不会被改动）；一节末尾、空行之后、缩进比下一个键更深的注释，以及任何缩进比下面的键更深的注释；同一文件里两种缩进宽度；混用换行符；续到下一行的普通值、值里的制表符、显式 `? key`、显式标签（`!!str`）；只含 BOM 的文件（原因里注明 BOM）；值为 `null` 且带行内注释的条目（`foo: ~  # placeholder`）被写入改成映射时，YAML 库无法在那里写出该注释，写入被拒绝并指出该键（#624，由 `WriteGateRuntimeFailureTest` 固定）。会保留的排版（其它键的写入照常通过）：`#` 前一个空格的行内注释、后面没有空行的块标量（包括紧跟在它后面、附属于下一个键的注释行，与普通值之后的注释相同，[#592](https://github.com/UltiKits/UltiTools-Reborn/issues/592)），以及未改动、后面跟空行的块标量（补入缺少的默认键时，原有块标量及分隔空行逐字节保留，[#883](https://github.com/UltiKits/UltiTools-Reborn/issues/883)）、内侧无空格的流式映射和列表、带引号的值、全文一致的四空格缩进、与键同列的列表项、全文统一的 CRLF 或 LF、没有末尾换行（含有块标量的文件也一样，上面所说的最后一个值除外）、BOM、`#` 后无空格的注释、多个空行、十六进制数、`~` 和空值；这些保留情形均不包括前面有块注释的任何列表项，不只是流式项（见上）。使用锚点的文件整体拒绝，不按行。改掉警告指出的那一行，下一次写入即可通过。0 字节文件、只有空行的文件、以及只含行首注释（前面可有空行）的文件在闸门看来是空文档：启动时在已有字节之后补入声明的键（没有其它字节需要保留），服主操作写入或面板编辑插入它指定的设置；保存从不插入。只含空格的文件、含缩进注释的纯注释文件和只含 BOM 的文件按排版拒绝（见上），内存使用默认值、模块照常加载；只含制表符、`---` 或 `...` 的文件无法解析，按无法解析的文件保护（永不写入）；块标量后面依次是缩进大于 0 列的注释行、空行、第二个注释行的文件也一样：SnakeYAML 2.2 在这种排版上会在组装节点时抛出异常（[#617](https://github.com/UltiKits/UltiTools-Reborn/issues/617)，未修复）。读取后被删除的文件，服主操作写入或面板编辑会独占地重新创建它，只含指定的设置及其注释。YAML 库在闸门内部抛出的运行时异常（应用写入的修改、渲染文档、为自检重新解析渲染结果时）与闸门无法保留的排版一样按拒绝处理（#624）：不写入；闸门的一条警告列出文件、键、失败的步骤和异常类名，从不含异常消息（消息可能引用文件内容），例如 `the file cannot be written at autoreply.rules.foo: rendering the document failed (EmitterException)`；这些步骤（包括读取文件）中的其它非受检异常同样按拒绝处理；显式写入（`saveOperatorChange`、`saveOperatorMapEntry`、面板编辑）抛出注明键的 `ConfigWriteRefusedException`，自动写入照常在内存中使用声明默认值。库的运行时异常不会传到调用方，按约定在 `IOException` 时回滚的模块因此与文件保持一致。启动时其它非受检异常不会拒绝模块：一条警告列出文件和异常类名，内存中使用声明默认值。语义无变化不写文件，字节和修改时间不变。浮点数按最短可回读十进制判断，不要求二进制精确。
- 先临时文件、force、原子替换；仅已允许的原子替换/临时创建拒绝才走备份后原地写。备份文件名为 `<文件名>.ultitools-backup-<16 位小写十六进制>`，独占创建；本次运行已为同一文件写过、且内容未变的备份先从当前文件刷新并原子替换，之后才打开目标。写入器不读取、不写入、不删除任何其它文件，服主自己的 `<文件>.bak` 不受影响。原地写入在打开目标之后失败（写到一半中断、force 或关闭失败）不会留下写了一半的目标（#622）：框架先把目标放回原样——按原地方式写回目标在此之前的字节，也就是已 force 的备份里的内容（之所以原地写回，是因为这条退路所替代的原子移动已被拒绝）——再删除该备份，然后才报告 `IOException`；因此文件保持写入前的内容，与在该异常时回滚内存改动的模块一致。面板批次以同样方式放回它打开过的每个文件，并报告失败（#582）。若放回本身也失败：一条 SEVERE 列出文件和备份（从不含内容），备份保留且之后的加载不会删除它；写入该文件的配置在重载重新读取文件之前，把它视为读取后已被改动：`isFileModifiedSinceSnapshot()` 为真，服主操作写入和面板编辑以 `ConfigWriteRefusedException`（“the file changed since it was read: …”）拒绝，`save()` 不写入并以一条 WARNING 列出键。处理方法：对比文件与备份，必要时用备份覆盖文件，然后重载。成功的原地写入所用的备份只在成功严格加载当前文件、且备份内容仍与记录一致时才清理；本次运行未写过或已被改动的同类备份保留，并以 INFO 提示一次。除放回刚失败的那次写入外，不从更早的备份还原，不保证多文件崩溃事务。
- 读取器无法读取的两种排版（#580、#617，维护者 2026-10-05 决定：只写入文档，不改读取器，不向上游报告）：在 SnakeYAML 2.2 开启注释的实测环境中，框架无法读取以下两种合法的 YAML 排版。在已测的 2.2 环境中，文件按无法解析处理：永不写入，启动时模块使用声明默认值（重载保留运行中的值），已有的 SEVERE 日志指出该文件（`Cannot load <文件>: <解析位置>; file will not be overwritten`）。文件的字节不会丢失，但其中服主的值在排版改正之前不会生效。其一：块锚点的第一个子键之前有注释（`defaults: &defaults` 下一行是 `# 注释`，再下一行才是 `setting: inherited`）——把注释移到锚点键上方或第一个子键之后即可读取。其二：块标量（`|-` 或 `>`）后面依次是缩进大于 0 列的注释行、空行、第二个注释行——删掉两个注释之间的空行（或删掉其中一个注释）即可读取。另外（#628，`OperatorFileWriterTest`）：缺少的设置可连同声明注释插入到无值键（如 `c:`）后面，保留该键及其他未拥有行的字节；支持嵌套、点号节和混合点号键，以及 LF、CRLF、无末尾换行。没有文本的隐式空值不会因解析器的位置标记而占用后面的键所在行；真实的同一行冲突仍会拒绝。要把文字设置留空，请写 `key: ''`；无值键是 null，不是空字符串。
- 三种 YAML 写法无法通过写入闸门的校验（闸门要求未写入的每一行字节和值都不变）：不加引号的二进制值（`blob: !!binary YWI=`）、块形式的集合（`? a`）、用作映射键的二进制值。它们不影响读取，但同一文件中其它键的写入会被拒绝（一条 WARNING），文件保持原样。变通办法：二进制值加引号（`!!binary 'YWI='`）或写成块字面量（`!!binary |`），集合写成流形式（`!!set {a: null, b: null}`）；二进制映射键没有可用写法，不要在框架会写入的文件中用它作键。
- 不能读取、不能解析、非 UTF-8 文件不会被任何实体写入路径覆盖；初次失败用默认值，重载失败保留运行值。成功加载才解除保护。单独 `{key}` 注释按模块当前语言目录更新，但只改框架能认出是自己写的注释行：该项注释整体或末尾连续几行，与框架对模块 jar 自带任一语言目录中的文字、模块当前解析出的文字或原样 `{key}` 写出的形式逐字节相同（该项的缩进、`#`、一个空格加文字；只按相等判断，`#` 后缺空格或缩进不同即视为服主所写）；服主在令牌项上方手写的注释、改过的框架注释和字面注释逐字节永久保留（#604，维护者 2026-10-04 决定）。已不在任何自带目录中的旧版措辞，只有模块在`@ConfigEntry(previousComments = {...})`（默认为空）中登记了该文字时才算框架所写：逐字节相同即替换为当前目录文字并从此随语言切换（维护者 2026-10-04 决定）；未登记的原样保留。面板编辑以服主同意为准，只替换它指定的键，不发警告（#600）。框架写的注释行被服主删除（设置本身保留）时，下次启动和下次 `/ul reload` 都会把它写回原位，用当时所选的语言，无论是否切换了语言；但如果该设置正上方还留着服主自己的注释行，这行就是该设置的全部注释、属于服主，框架不会在上面再写。服主写的值、键和行都不会变；切换语言时，该设置以外的框架注释照常跟随切换。这由 `DeletedFrameworkCommentTest` 固定。所以删除注释行去不掉注释：想让令牌设置不带框架注释，请把该行换成自己的注释（哪怕只是一个 `#`），框架认不出是自己的，就会保留，也不会在上面再写。整个设置（注释和键）一起删除时，下次启动会带着默认值和注释写回，位置在剩余设置之后，而不是原来的位置。
- 模块 `save()` 只写模块自上次加载或保存以来改过的设置，并且只在文件该处仍是框架上次读到或写入的值、且该值正是模块的起始值（能无转换警告地转换为上次加载或保存时的设置值）时才写（#599，维护者 2026-10-04 决定）。声明为 `Map` 的设置按条目写（按声明的映射类型逐层进入嵌套映射），只设置或删除模块增、改、删的条目（空映射或被模块删空的映射整体写，因为它的键行随首条或末条一起变）；其他值——列表、Bukkit `Location`/`Vector` 等 `ConfigurationSerializable`、类型化映射中本身不是映射的条目值——都算一个值，要么整体写入要么不写，文件里不会出现模块与服主各占一部分的值。声明为 `Map<String, Object>` 或原始 `Map` 的设置只拆一层，其下的普通嵌套映射算一个值。重载合并（复合值或列表冲突时整体采用文件的值，只列键名）、面板编辑复合值内的字段（以框架上次读到的整个值加上该字段的改动为写入单位，且仅当写入时文件仍保存着该整个值；否则拒绝并注明设置名“文件在读取后已被改动，请先重载”，不写入）以及 `saveOperatorMapEntry`（键深入到非映射条目内部时抛 `IllegalArgumentException`，不写入）都按同一规则判断什么算一个值。保存从不补写文件缺少的键、从不改写注释、从不删除模块没删的映射条目。因此服主在磁盘上改过的值、删掉的键、框架无法使用的值或列表元素（`interval: 3O0`、`[60, 30, 10, abc]`）以及 拆开的映射条目，无论模块是否改了该设置，保存都不会覆盖（#596）。没写成的改动留在内存，每次保存一条警告列出文件和这些键，不列值；被写入闸门拒绝的键由闸门的警告连同原因列出。其余每一行逐字节不变，否则不写；没有可写内容时不碰文件。“文件仍是读取时的内容”比较的是框架上次读到或写入的文本：服主不经 `/ul reload` 直接在磁盘上改过的设置——哪怕值不变，比如把 `y: 64` 改成 `y: 64.0`——之后模块对该设置的改动不会写入（警告中列出），直到重载重新读取文件；没有任何内容丢失，模块的值留在内存中。`save()` 用于服主通过模块要求的改动，或语言切换后重新渲染出厂文字。
- 两个服主操作写入方法：`saveOperatorChange(String...)` 只写指定的设置（如 `/setspawn` 指定六个 `spawn.location.*`），`saveOperatorMapEntry(String, String...)` 只写映射设置中的一个条目（如 `/autoreply` 的一条规则；文件缺少时插入，模块映射里已删除时从文件删除；每个映射键是完整键，`play.example` 是一个键）。服主的命令即同意：指定键处以模块的值为准，服主手改过的也替换；文件缺少的指定设置连同注释插入。其余一律不写（模块改过但未指定的设置也不写；用 `saveOperatorChange` 指定整个映射设置会整体写入并丢掉服主手加的条目，只改一个条目的命令应使用 `saveOperatorMapEntry`），写入经写入闸门且只拥有这些键。被拒绝时（文件不可读或无法解析、使用锚点、排版无法逐字节保留、准备写入期间文件被改）抛出 `com.ultikits.ultitools.config.ConfigWriteRefusedException`（`IOException` 子类），消息和 `getReason()` 说明原因、不含任何值；文件保持原样，内存中的值不变且仍未保存。路径不是已声明的配置项，或对非映射设置调用条目方法，抛 `IllegalArgumentException` 且不写。两者都在实体锁下执行，须在服务器主线程调用。
- `saveOperatorMapEntry(EntryPresence, String, String...)`（#623）：映射条目的写入可附带存在性条件，`MUST_BE_ABSENT` 用于新建条目的命令（如 `/autoreply add`），`MUST_BE_PRESENT` 用于修改已有条目的命令（如 `/autoreply setkeyword`），模块不必用自己的解析器另读一次文件。条件在写入闸门据以校验的同一次读取上判断，闸门在替换文件前还会再核对一次这些字节：服主自加载以来手动添加或删除的条目都能看到，此后保存的改动则让闸门以“文件已被改动”拒绝写入。“存在”指该条目的完整键路径在文件中存在（框架读取该设置的位置：嵌套键或扁平的带点键，#612，再接映射键，每个都是完整键）；值为 `null` 的条目算存在；上级键缺失、为 `null`、为空映射 `{}` 或不是映射时算不存在，缺失、空白或只有注释的文件不含任何条目。条件不成立时不写入（字节和修改时间不变），框架只记 FINE 级日志，并抛出 `com.ultikits.ultitools.config.ConfigEntryPresenceException`（`ConfigWriteRefusedException` 的子类）：`getRequired()` 给出不成立的条件，`getReason()` 给出条目的键路径，从不含值。条件为 `null` 时抛 `IllegalArgumentException`。第一个参数直接写字面量 `null` 的调用（`saveOperatorMapEntry(null, "path", "key")`）因两个重载都匹配而无法编译；请把 `null` 强制转换为所需的类型（此时会抛 `IllegalArgumentException`）。
- 改用 `isPresentInFile` 查询上次成功加载时的存在性（路径按声明设置路径的方式读取（#612）：按点拆成嵌套键、作为一个扁平带点键或两者混合，文件以任一形式持有该键即算存在，因此含点的完整映射键也能查到），修改声明字段后 `save()`。
- 迁移示例见上方，转换器必须满足两条互逆等式，不能单向加值或悄悄丢字段。
- 注册批次验证完成才开始独立写文件；面板批次先验证并经写入闸门暂存全部文件，在进程内失败时回滚，持久存储故障可能阻止恢复；每个文件替换前再核对一次字节，期间被服主保存的文件整批拒绝（`ConfigWriteRefusedException` 注明文件）、服主的保存保留，已替换的文件恢复为核对时的原字节。回滚只处理本批次仍拥有的字节（#673）：本批次没有替换的文件（未执行到，或替换以普通错误失败、文件仍是原字节）回滚时绝不写入或删除，批次失败期间服主的保存保留；已替换的文件仅当仍是本批次写入的字节时才放回，之后被服主保存的保留并警告一次；放回本身失败的文件按 SEVERE 所述连同框架备份保留，回滚不再写它，该配置拒绝一切写入，直到服主比对两者并重载。面板唯一映射路径走整字段类型转换，歧义和未知变更拒绝整个请求；无关内存/磁盘兄弟项保留。
- 重载三方合并，内存独有改动保留且仍脏，磁盘独有采用，冲突磁盘胜。重载重新读取模块消息覆盖之后、模块自己的重载钩子之前，框架经写入闸门、按重新读取的文件，只把它能认出的自己的注释行改成新语言，不写任何值、键或其它注释行（#594）。仅磁盘该映射未变时保证内存顺序保留，不写文件。初始化、重载和注册表在服务器主线程执行；异步面板回调整体排队，不能阻塞等待。`reloadSelf()` 为 `final`。
- 框架自己的 `plugins/UltiTools/config.yml` 启动时补入缺少的面板键（能力开关、`ultipanel.commands.blocklist`、`ultipanel.files.editable-roots`、操作日志轮转键）同样经过写入闸门，只插入（#605）。闸门无法逐字节写回的文件（手工对齐、锚点）保持原样，警告一次列出这些键，由 jar 自带 `config.yml` 中的默认值（列出命令黑名单和可编辑根目录）生效。若文件以扁平含点键（如 `ultipanel.capabilities.logs: false`）或含点的节键保存其中某个设置，而插入的嵌套节会在服务器读取时取代它，同样不写入：警告一次，指出文件和该设置（不含任何值），缺少的键使用 jar 默认值。
- UltiTools 只在 `plugins/bStats/config.yml` 不存在、或已含 `serverUuid` 时启动 bStats（#606）。该文件由所有使用 bStats 的插件共用；无法读取、无法解析或缺少 `serverUuid` 的文件逐字节保持不变，记一行日志说明，本次运行 UltiTools 不发送统计。
- 面板编辑 `server.properties` 时只替换它指定的那个键所在行的值文字（#607）：键、分隔符、注释、顺序和其它每个字节保持不变（含 UTF-8 的 `motd`），按服务器读取该文件的方式解码和编码（严格 UTF-8，不是 UTF-8 时用 ISO-8859-1），校验后原子写入。同一个键定义在多行、或定义续到下一行时拒绝，原因注明行号（不含值）；`set_all` 回复把它列在 `failed` 中，并在`failureReasons` 字段中给出原因，服务器日志对每个被拒的键只记一次。
- `com.ultikits.ultitools.config.OperatorFiles` 供模块在服主明确编辑时写入它代为管理的 YAML 文件（礼包、菜单文件）：只写编辑的部分，注释和其它键保留（维护者 2026-10-04 决定）。`read(File)` 返回文件文本（严格 UTF-8）和字节的 SHA-256；`write(Snapshot, Map<List<String>, Object>)` 只写指定的完整键（含点的键是一个键），经写入闸门，且仅当文件仍是快照时的字节，返回 `WRITTEN`、`UNCHANGED`、`FILE_CHANGED` 或 `REFUSED`（排版无法逐字节保留或使用锚点，警告一次列出文件、键和原因）。值必须是普通数据（`ItemStack` 先序列化），否则在碰文件之前抛 `IllegalArgumentException`。它从不创建、删除或重命名文件，不是通用文件 API，也不用于 `@ConfigEntity` 文件。独立选择性入口 `CrashSafeYamlFiles`（#545）提供已有 YAML 指定键编辑与完整新映射的排他创建；不改变旧入口的链接或 fallback 行为。该入口仅支持默认本地 POSIX 文件系统的原生 rename/link 发布，保留九位权限和 owner/group，新文件 0600；非 POSIX 或 Java 暴露 ACL view 时拒绝，原子替换/link 不支持时失败且无退路，不保留所有隐藏 ACL/xattr/安全标签或硬链接关系。无自动恢复或遗留文件扫描，不保证断电与多文件事务；完整边界、并发限制和服主清理步骤见上方英文小节。
- 面板编辑复合值（Bukkit `Vector`、`Location`，或映射条目中的此类值）内的一个字段时，写入的是文件中原样的整个值、只改该字段，其余字段保持服主写下的字节（`y: 64`、`pitch: 0` 不会变成 `64.0`）。面板发送的整数、或文件中该值其它字段里的整数，在模块自身的值为浮点数的位置按浮点数转换（Bukkit 的 `Vector` 自己不放宽），被编辑的字段按该类型写入（`7` 写成 `7.0`），因此编辑后的文件能被重新加载（#609）。文件中 `Vector` 或 `BlockVector` 坐标里的整数（`y: 64`）按该数字加载：Bukkit 的 `Vector` 只接受 `Double` 坐标，框架只在内存中把 `Vector` 的 `x`、`y`、`z` 放宽为浮点数——文件保持 `y: 64`，未改动的保存不写入，其它类型不放宽。模块在内存中删除且未保存的映射分组，编辑其下的键会被拒绝，注明“not found in memory”。
- 服务器关闭、模块卸载或卸载删除、模块被新版本替换时，一律不写任何配置（维护者 2026-10-04 决定：生命周期事件不是服主的写入请求，不做整对象的关闭保存或替换保存）。关闭时以及正常卸载或卸载删除时，每个模块在卸载钩子和 `@PreDestroy` 之后、释放之前，凡有从未保存的模块改动的配置各警告一次（列文件和键，不列值），改动随之丢弃；框架最后一步对仍注册的配置同样处理。上次加载无法读取或解析的文件只提示一次。注册替换现有模块实例的新版本副本直接读取现有文件，旧副本的未保存改动或受保护文件不会阻止替换；新副本激活后警告一次丢弃的键。`ConfigManager#saveAll()` 不写入、只报告并标记 `@Deprecated(since = "6.3.0")`。模块应在改动发生时用 `save()` 或 `saveOperatorChange`/`saveOperatorMapEntry` 保存。卸载时释放该模块的配置实体。已知限制：#578 中仍适用的是符号链接后跟 `..` 的临时文件路径；锚点容器与别名注释两项不会发生，因为任何写入路径都不写入使用锚点、别名或合并键的文件。#580（见上文读取器无法读取的排版）和多文件崩溃限制仍存在（#545 的单文件 helper 不解决多文件事务）。登记表同样按此顺序（#562）：新副本通过兼容性检查后、刷新容器和运行 `registerSelf()` 之前，先释放旧副本的面板响应器、EventBus 处理器和补全器；新副本失败时释放它自己的登记、把旧副本的登记原样放回，旧副本照常运行。

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
  `reportErrorFromClass(Throwable, Class<?>, TriggerContext)` entry point resolves ownership.
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
this adds no persistence or retry guarantee.

## Behavioral changes

Some changes leave every method signature untouched yet alter how your module behaves at runtime: a
method that used to return silently starts throwing, a default value flips, a missing optional
dependency turns from degraded operation into a failed load. Signature comparison tools cannot detect
these. This section explains how we handle them.

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

The versioning rules govern the *intentional* part of the first two kinds.
This section governs the third kind.

### Behavioral changes that need no migration period

- Correcting behaviour that plainly contradicts the documentation (the docs say it returns
  `Optional.empty()`, the code throws an NPE).
- Tightening the handling of previously undefined input (passing `null` used to be undefined
  behaviour, now it throws `IllegalArgumentException`).
- Changes in performance, memory footprint, log wording, or exception message text.
- Security fixes. These may land in a PATCH without prior notice.

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
- Naming a missing required plugin instead of printing a class-not-found trace (#554). A module
  that cannot load because a plugin in its `plugin.yml` `depend:` list is not installed or not
  enabled logs one WARNING,
  `Module '<name>' requires <plugin>, which is not installed or not enabled; the module is not
  loaded.`, with no trace. Every other load failure keeps the message and trace
  (`ultitools.boot.missing-required-plugin`). When the required plugin is not installed at all,
  the module is refused before it is constructed: nothing of it runs, no resource is
  extracted and no class is scanned. A required
  plugin that is installed but not enabled yet is not refused early, because it may still be
  enabled after UltiTools; that case is refused only if loading fails.
- The first economy request a module makes while Vault is absent or has no provider logs one
  WARNING, `Module '<name>' requested the economy service, but …` (#462, #483, #489). It names a loaded
  module through its declared scan roots and its main class's own package, a connected External Plugin API
  consumer (by plugin name), and a module requesting the economy while it is still being registered (from its
  constructor, a `@PostConstruct` method or `registerSelf()`). A caller none of these covers is reported as
  `an unknown caller`, once per calling package (`ultitools.economy.attribute-caller`). Log output only.
  **Known limitation (#567).** The main
  class's own package counts as a root together with its sub-packages, like a declared root. A
  module whose main class sits in a broad package shared with other code - for example
  `com.example`, while other plugins or libraries also live under `com.example` - may therefore be
  named as the caller for code in that package that is not its own, and that use spends the
  module's one warning for the session. **Module authors: put the main
  class in a package of its own,** such as `com.example.myplugin`.

  中文补充：已知限制（#567）。模块主类所在的包连同其子包都算作该模块的归属根，与声明的扫描根相同。主类放在与其他代码共用的宽泛包里（例如 `com.example`，而其他插件或库也在 `com.example` 下）时，该包内并非该模块的代码可能被记到这个模块名下，并用掉该模块本次运行唯一的一次警告。模块作者请把主类放在自己专用的包里（如 `com.example.myplugin`）。
- `PluginManager#getConnectedExternalScanPackages()` (#462) and `PluginManager#getModuleBeingRegistered()`
  (#483) are `@ApiStatus.Internal` and public only because the caller is in another package.
  A module never needs to call them.
- Writing no configuration at shutdown (#510, completed by #599). Operator-only disk edits survive a
  clean stop. Nothing is written at stop, unload or replacement: a pending module change is named in
  one warning and dropped (see the entry on configuration writes below). The [config-layer contract](#config-layer-630) covers
  protected files, partial panel acknowledgments, semantic no-ops and server-thread confinement.
  Panel callbacks queue their complete operation on the server thread; they do not perform registry
  writes concurrently on the WebSocket thread. An explicit entity save writes only what the module
  changed, where the file still holds what was read, and never replaces an operator edit.

- Every entry of the modules folder considered for uninstall is placed in exactly one of
  three states, each decided by reading the archive — with one exception, an entry the module loader
  itself would never load, judged by the same `.jar` test the start-up scan applies to this
  folder, which is ignored without being opened. The states: its `plugin.yml` declares this module,
  or it is a loaded instance's own code-source JAR (queued for deletion); it opened and its `plugin.yml`
  declares another module, or it is a directory (ignored); nothing about it identifies a module,
  because the archive would not open, its `plugin.yml` is not valid YAML, **it carries no
  `plugin.yml` at all or one with no `name:` key** — which say nothing about whether it is a
  module — or the entry is named like a JAR and cannot be resolved at all, a link whose target is
  away (reported as undetermined — the operator is told which entries could not be identified).
  Separately, a modules folder that exists but could not be listed is reported as unreadable,
  naming the folder rather than claiming that no JAR of this module is here.
- `/upm update` **takes effect at the next start
  and is committed only after that start shows the module loaded**: the command downloads the new JAR
  into `.ultikits/upm-transactions/` under the server root and records it; the
  next start moves the old JAR aside (keeping it) and the new one in, before the module class loader
  is built; after the modules load, the update is kept only if the module is loaded from the new JAR
  at the new version, and otherwise the old JAR is restored and the new one removed, with one log
  line naming both versions. Nothing predicts before the restart whether a JAR will load. A move that
  fails leaves the modules folder as it was and is reported in the start-up log and again by the
  next `/upm update` of that module. The swap is an atomic rename, so when `plugins/` is on a
  different file system from the server root the start refuses it, changes nothing, and names both
  folders in one SEVERE line; there is no copy fallback. `updatePlugin(String)`'s `true`
  means "staged", and `PluginInstallUtils.stageUpdate(String)` (`@ApiStatus.Internal`) returns what
  was staged or why nothing was. If a downloaded JAR passes structural validation
  but reading its metadata or hashing its content then fails, the staging refusal reports the read
  error rather than claiming its identify-string or version is missing; staging is discarded and
  the installed JAR is unchanged. The update only ever moves, replaces or deletes a file whose SHA-256 matches its record;
  when another actor has changed one, it does nothing, keeps the record as `NEEDS_OPERATOR` with one
  SEVERE line, and refuses `/upm update` and `/upm uninstall` of that module. **An unreadable file enters this same hold, even when the read failure is
  transient and nobody changed its bytes.** Later starts do not automatically retry the held update
  after readability returns. Stop the server, check the named files and recover any JAR you need,
  then follow the logged instructions to discard the transaction before requesting another update;
  do not remove a record or its backup while the server runs.
- When the catalogue answers that no version is compatible (HTTP 409),
  nothing is downloaded and the reply says so; `/upm versions` with no compatible version prints one
  line naming the range; the update check offers only versions this framework installs and never
  mentions one that needs a newer UltiTools-API. `/upm list` lists every catalogue module; Install on one with no compatible version
  replies with the reason.

  中文补充：云端回答没有兼容版本（HTTP 409）时不下载任何东西，回复说明原因；
  `/upm versions` 没有兼容版本时打印一行说明范围；更新检查只提示本框架能安装的版本，从不提示需要更新框架的版本。
  `/upm list` 列出云端的全部模块；对没有兼容版本的模块点“安装”会回复原因。
- Uninstall also queues deletion of a JAR whose `plugin.yml` `main:` names
  the loaded module's main class, whatever `name:` it declares (#516). A main
  class another loaded module also has is never matched, a file is judged by what it declares at the
  time of the uninstall, and no class is read out of any archive.
- Module JARs are discovered in file-name order (#476). The order decides which of several JARs carrying the same class supplies it,
  which copy of a duplicated module is read first, which module a `plugin.yml` `name:` shared by two
  modules resolves to, and the order of the opt-in legacy load (`-Dultitools.useLegacyPluginLoading`).
  Modules without a dependency between them load in alphabetical order of their class names.
- When two or more modules share a main-class simple name (for example two different `Main`
  classes) and another module names that simple name in `@PluginDependency` (`depends`, `softDepends`,
  `loadBefore`) or a `plugin.yml` `loadAfter`, one start-up WARNING lists every fully qualified class and the
  module the name resolves to - the last discovered
  ([#841](https://github.com/UltiKits/UltiTools-Reborn/issues/841)). Name the intended module by its
  `plugin.yml` `name:` instead. A shared simple name that nothing references is not reported.
- A JAR
  that borrows a class from a JAR that does not declare it is refused with one SEVERE line.
- At the start that applies a record, a recorded
  JAR whose content cannot be read to confirm that it is still the recorded file is neither deleted
  nor dropped: it stays in the modules folder, keeps its entry in the record unchanged for the next
  start, and is reported as one SEVERE line with the read error (#676). This includes failures to read the JAR's size or modification time. An uninstall that cannot read those attributes cannot record a new
  deletion and reports that failure instead of recording a zero size or timestamp.

  **Module lifecycle known limitations (#692; documented, not changed):**
  - Non-Paper launchers using `union:` code-source URLs are unsupported. If such a launcher also
    places the server under a percent-escaped path, server-JAR discovery can truncate that path.
    Use a supported Paper launcher rather than relying on the `union:` compatibility branch.
  - If a start stops after moving the new JAR out of staging but before recording that step, and
    a target-JAR read fails during resume but succeeds during the following safety check, the
    failure diagnostic can say `<missing>` instead of naming the transient read error. Inspect the
    named staged, target and backup paths with the server stopped; do not treat that word alone as
    proof that a JAR was permanently lost. A persistently unreadable target is held for the operator
    by the rule above, not silently replaced.
  - If the retained backup is absent while the new target remains, and reading that target fails
    only after a successful identity preflight, rollback can leave the target in place without its
    usual no-backup warning and discard the transaction record. Keep framework-owned transaction
    records and backups intact; when recovering a damaged transaction, stop the server and inspect
    both target and backup before deciding which JAR to keep. Do not hand-edit transaction files
    while the server runs.
- The modules folder is computed in one place, `<plugin data folder>/plugins` (#517). The data folder follows Bukkit's own plugin
  directory.

- The JSON storage backend does not hand out the entities it caches (#522). Every JSON read returns a detached copy produced by the
  same Gson form the store writes to disk, and `insert` caches a copy: a change reaches the store
  only through `update(...)` (or `update(column, value, id)`), on every backend alike. `update(T)`
  also fires `onUpdate()` on the entity passed in, before its fields are copied into the store,
  exactly as the relational backends do — so an `AuditableDataEntity`'s `updatedAt`/`updatedBy`
  show on the caller's instance on the JSON backend, and `exist(entity)` looks the entry
  up by the entity's id, as the relational backends do. The cost is one Gson round trip per entity returned, the same materialisation the
  relational backends already pay (see `ultitools.storage.detached-reads` in `FEATURES.md`).
- `Query#delete()` returns the number of rows actually removed (#521). The count comes from the backend's own
  affected-row count (a row another writer removed between the read and the delete is not
  counted), and a matched row with a `null` id is refused with a `DataAccessException` naming the
  entity type **before** any row is deleted, since no delete can address it. A third-party
  `DataOperator` implementation, which cannot report what its `delById` removed, is counted by
  checking that the row existed immediately before that call and is gone after it (see `ultitools.storage.query-delete-count` in `FEATURES.md`).
- Rows left without an id by UltiTools-API 6.2.0 are repaired, and addressing a row by a null id
  is refused (#546). When a SQLite-backed table is
  initialised every row whose `id` is `NULL` is given the id its entity reports through `getId()`,
  or a new UUID when the entity reports none, in either case only if the entity read back with that
  id reports it; a row that no written id would make addressable is left as it is and counted, by
  reason, in one WARNING line per table: a derived id that more than one row without an id reports
  (none of those rows is written), a derived id another row already holds, or a derived id that is `null` or a row
  that cannot be read as the entity — only the `id`
  column is written, all rows in one transaction, so the repair writes user data at startup — and one INFO line names the table, the count and how many rows
  took the entity's own id. A second start writes nothing and logs no INFO line; rows that were left without an id
  are counted again, in the same WARNING, at every start. The reported id comes
  first because an entity may derive `getId()` from another column and every
  lookup binds that value, so a random id would leave such a row exactly as unreachable as `NULL`
  did. For the same reason every write path (`insert`, `insertAll`, `update(T)`, `updateAll`,
  `updateIf`) stores `getId()` in the `id` column rather than the inherited field. MySQL never
  accepted a `NULL` id and runs no backfill. Independently, `update(T)`, `update(column, value, id)`,
  `delById` and `updateAll` addressed by a `null` id throw `DataAccessException` on every
  backend; `updateAll` checks every entity before it writes any.  See `ultitools.storage.null-id-backfill` and
  `ultitools.storage.null-id-refused` in `FEATURES.md`.
- `DataOperator#updateIf(T entity, WhereCondition... expected)` (#543):
  a conditional write that applies only while the stored row still matches every expected
  condition, and reports whether it applied, on the JSON, SQLite and MySQL backends. Its default body throws `UnsupportedOperationException` naming the implementing
  class rather than quietly performing an unconditional write — a third-party `DataOperator`
  implementation keeps working for every other method and must implement `updateIf` before a caller
  can rely on it. The framework's own operators implement it (see
  `ultitools.storage.conditional-update` in `FEATURES.md`). **A `null` expected value means `IS NULL`**
  (#640): a condition `WhereCondition.builder().column(c).value(null)`
  with the default `EQUAL` comparison holds only while the stored column is unset — on SQLite and
  MySQL `<c> IS NULL` inside the same single `UPDATE`, on JSON an entry whose field is absent or JSON
  null — so a compare-and-set against a column that was unset when it was read misses, and keeps the
  other writer's value, when another server filled the column in between. A `null` value under any
  other comparison, a column the entity does not map (also with a `null` value), a `null` id and a
  `null` condition are refused with `DataAccessException`. The meaning belongs to `updateIf`
  alone: `getAll`, `exist`, `del` and `page` refuse a `null` condition value
  (#815, see the comparison entry below).

  中文补充：`updateIf` 的期望值为 `null` 时表示 `IS NULL`（#640）：默认 `EQUAL` 比较下，
  `WhereCondition.builder().column(c).value(null)` 只在库中该列仍未设置时成立——SQLite 与 MySQL 在同一条 `UPDATE` 中使用
  `<c> IS NULL`，JSON 要求该条目的字段不存在或为 JSON null。因此，对读取时尚未设置的列做比较后写入时，若另一台服务器在
  读取与写入之间写入了该列，本次写入不生效，另一方的值得以保留。其他比较下的 `null`、实体未映射的列（值为 `null` 时也一样）、
  `null` id 和 `null` 条件抛 `DataAccessException`。这一含义只属于 `updateIf`：`getAll`、`exist`、`del`、`page` 对 `null`
  条件值会被拒绝（#815，见下方比较条目）。
- An update by a non-null id that matches no row writes nothing and says so (#558). `update(T)`, `update(column, value, id)` and `updateAll` log one
  WARNING naming the table and the id each time, on JSON, SQLite and MySQL, and return normally. The caller learns the outcome through
  `int updateCounted(T entity)` on `DataOperator`: `1` for a written row, `0` when no row has the id.
  It is a `default` method; a third-party implementation that does not override it is counted by whether the row
  exists before its `update` (the one remaining miscount: a delete by another writer during that
  call). See `ultitools.storage.missing-row-update` in `FEATURES.md`.
- A transaction whose own failure path fails does not end in an implicit commit (#634). JDBC
  commits an open transaction when auto-commit is switched on; auto-commit is restored only after a commit or rollback that succeeded;
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
  `Error` : the
  relational operators roll back and rethrow the same `Error`, the JSON engine discards the
  transaction's staged writes and rethrows the same instance, and a rollback that itself throws is
  attached to the original as suppressed instead of replacing it. `@Transactional` also rolls back
  on an `Error`; see
  [A failing joined `@Transactional` method rolls back the whole transaction](#a-failing-joined-transactional-method-rolls-back-the-whole-transaction-630--changed-without-a-migration-period)
  and
  [A failed nested `DataOperator#transaction(...)` marks the whole transaction rollback-only](#a-failed-nested-dataoperatortransaction-marks-the-whole-transaction-rollback-only-630). See `ultitools.storage.transaction-failure-paths` in `FEATURES.md`.

  中文补充：事务自身的失败路径再失败时，不会隐式提交（#634）。JDBC 在打开自动提交时会提交尚未结束的事务；只有提交或回滚成功后才恢复自动提交；否则丢弃该连接（HikariCP 连接池
  会将其剔除，不会把仍处于事务中的连接再交给别人；其他连接直接关闭），框架不会再为该事务发出提交，也不会重试提交。之后保存了什么，
  取决于失败发生在哪里：操作失败且回滚成功时，未保存任何写入；SQLite 或 MySQL 的回滚失败时（包括外层提交为仅回滚的事务执行的
  真正回滚），连接被丢弃，框架不发出提交；提交因事务已被标记为仅回滚而抛出 `UnexpectedRollbackException` 时，事务已回滚，
  其写入均未保存，SQLite、MySQL 和 JSON 都是如此；SQLite 或 MySQL 的提交本身失败（`DataAccessException`，
  `TRANSACTION_FAILED`）时，结果未知：数据库可能在确认丢失之前已经应用了该提交（例如 MySQL 连接断开），丢弃连接无法撤销它，
  框架也不会重试提交。因此不能重复写入的代码（支付、奖励）在重试前应先重新读取数据，或使用条件写入（如
  `DataOperator#updateIf`）。在 JSON 上，失败的提交不会提交任何内容（见
  [JSON transactions](#json-transactions-630--staged-atomic-and-durable)）。调用方收到原来的异常；事务被标记为仅回滚时收到
  `UnexpectedRollbackException`。SQLite 表启动时的 id 补全遵循同一规则。`DataOperator#transaction(...)` 遇到 `Error` 时也会回滚：关系型实现回滚后原样抛出同一个 `Error`，JSON 引擎
  丢弃该事务暂存的写入后原样抛出同一个实例；回滚本身抛出的异常作为 suppressed 附在原异常上，而不是替换它。`@Transactional` 也会在
  `Error` 时回滚；失败的加入方方法和嵌套作用域见本文后面两个小节。见 `FEATURES.md` 中的
  `ultitools.storage.transaction-failure-paths`。

  **Known limitation — third-party JDBC drivers** (#642): after a rollback fails, the framework
  requests no further commit, but closing the unresolved transaction follows the driver's policy.
  A third-party driver that commits on close can therefore persist writes; neither closing nor
  evicting its connection is a driver-independent rollback guarantee. Use a driver whose close policy
  matches the application's rollback requirements, and verify its behaviour before relying on it.
  A failed commit still has an unknown outcome as described above; closing cannot undo an applied commit.

  中文补充：第三方 JDBC 驱动的已知限制（#642）：回滚失败后，框架不会再请求提交，但关闭未结束事务的结果取决于驱动策略。
  若第三方驱动在 close 时提交，写入仍可能保存；关闭或剔除连接都不是跨驱动的回滚保证。请使用关闭策略符合应用回滚要求的驱动，
  并在依赖该行为前验证。提交本身失败时，结果仍如上所述未知；关闭连接无法撤销已应用的提交。

- `WhereCondition` comparisons other than `EQUAL` take effect on SQLite and MySQL (#675).
  `getAll`, `exist`, `page`, `del` and `updateIf`
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
  that is a finite number; a number beyond the range of a `double` and any text are compared as they are
  bound. An infinite or NaN `double` or `float` value is refused on every backend (see the
  paragraph on null and non-finite values below).
  On MySQL such a default text column is
  compared the way MySQL compares text with a number, in double precision, so whole numbers beyond
  ±9,007,199,254,740,992 (2^53) compare approximately there; declare the column with a numeric
  `type` (for example `@Column(value = "stamp", type = "BIGINT")`) where exact results matter. On
  JSON the values are compared as `double`, which is also approximate beyond
  ±9,007,199,254,740,992. A field of any other type is compared as the database compares the stored
  text. In particular, numeric
  `EQUAL` on a default MySQL text column and on JSON can match neighbouring whole numbers beyond
  ±2^53; an `updateIf` precondition can therefore match a different value from the one read. On JSON,
  the Gson map used to evaluate conditions already converts numbers to `double` before the equality
  fallback compares them, so declaring a numeric column type does not restore exact matching there.
  Do not use such numbers as exact compare-and-set tokens. On MySQL, declare a `BIGINT` column when
  exact integral equality is needed. Known limitations, documented rather than changed: on SQLite a
  `double` that needs 16 or 17 significant digits (`1/3.0`) is stored in a default column with 15
  (`0.333333333333333`) and reads back and compares as that value, so `LESS 1/3.0` selects a row
  written as `1/3.0`, where JSON, which keeps every digit, does not; declare the column
  `type = "DOUBLE"` to keep every digit. On SQLite a number that agrees with a stored `float`
  to 15 significant digits but differs after them (for example `Math.nextDown((double) 0.1f)`, a whole
  number of 10^15 or more such as `(1L << 53) + 1`, or a decimal with 16 or more significant digits)
  compares as equal to it, so `GREATER` that value does not select the row holding `0.1f`; this holds in a default
  column and also in a column declared `REAL`. Pass a
  `float` value for a `float` field. **A null or non-finite condition value is refused on every
  backend ([#815](https://github.com/UltiKits/UltiTools-Reborn/issues/815),
  [#696](https://github.com/UltiKits/UltiTools-Reborn/issues/696)).** `getAll`, `exist`, `page`, `del` and
  `updateIf` (and `Query`, whose `eq(null)` builds such a condition) throw `DataAccessException` with
  `DATA_ENTITY_INVALID` on SQLite, MySQL and JSON alike, before anything is read, deleted or written, when a
  condition's value is `null` under any comparison, or is a `Double` or `Float` that is infinite or NaN. The one
  exception is a `null` value under `EQUAL` in `updateIf`, which keeps its meaning `IS NULL` (#640).
  `del(WhereCondition.empty())` is refused the same way on JSON, as on SQLite and MySQL, and `exist(entity)` answers
  `false` for an entity without an id on every backend. Check the value first (a null check,
  `Double.isFinite`) or catch `DataAccessException`.
  A numeric field's column that holds non-numeric text,
  written by hand or by another program, compares on SQLite as its leading number (`'abc'` as 0,
  `'12abc'` as 12), while JSON compares that row as text. See `ultitools.storage.where-comparisons`
  in `FEATURES.md`.

  中文补充：`EQUAL` 以外的 `WhereCondition` 比较方式在 SQLite 和 MySQL 上生效（#675）。`getAll`、`exist`、`page`、`del` 和
  `updateIf` 在所有后端按以下含义应用比较方式，且不改动任何表。`INCLUDE`、`STARTSWITH` 和 `ENDSWITH` 把值作为文本字面匹配，
  SQLite、MySQL 和 JSON 一致：`%`、`_`、`!` 和 `\` 都是普通字符，不是通配符或转义符。字母大小写和重音取决于后端：
  SQLite 只忽略 ASCII 字母的大小写；MySQL 按框架建表时使用的 `utf8mb3_general_ci` 排序规则（`DEFAULT CHARSET=utf8`）比较，
  它同时忽略大小写和重音，因此 `e` 能匹配 `é`；JSON 精确比较。`GREATER` 和 `LESS` 用于 `byte`、`short`、`int`、`long`、
  `float` 或 `double` 字段（或其包装类型）时，按该类型的数值比较。请以该类型的数字传值（`10`，而不是 `"10"`）：文本值不会被转换，
  MySQL 会把它与默认文本列按文本比较，于是 `"10"` 排在 `"2"` 之前。在 SQLite 上，列保持默认 `VARCHAR(255)` 类型时同样按类型比较，
  整数保持 64 位精度，所存的值按读回时的值比较：`0.1f` 这样的 `float` 在该列中保存为 15 位有效数字 `0.100000001490116`，
  它既不大于也不小于 `0.1f`。这种按读回值的比较只适用于有限数字的值；超出 `double` 范围的数字以及任何文本都按绑定时的值比较。
  无穷大或 NaN 的 `double`/`float` 值在所有后端都会被拒绝（见下文关于 null 与非有限值的说明）。在 MySQL 上，这种默认文本列按 MySQL 比较文本与数字的方式以双精度比较，超出
  ±9,007,199,254,740,992（2^53）的整数在那里只是近似比较；需要精确结果时，请为该列声明数值 `type`（例如
  `@Column(value = "stamp", type = "BIGINT")`）。在 JSON 上按 `double` 比较，超出 ±9,007,199,254,740,992 时同样是近似的。
  其他类型的字段按数据库比较所存文本的方式比较。特别是，MySQL 默认文本列和
  JSON 的数值 `EQUAL` 可能同时匹配超出 ±2^53 的相邻整数，因此 `updateIf` 的前提也可能匹配不同于先前读到的值。
  JSON 用于条件求值的 Gson map 在 equality 回退比较之前就已将数字转为 `double`，声明数值列类型不能恢复它的精确匹配。
  不要把这类数字用作要求精确匹配的 compare-and-set 标记；MySQL 需要精确整数相等时，请显式声明 `BIGINT` 列。
  已知限制（只写文档，不改代码）：在 SQLite 上，需要 16 或 17 位有效数字的 `double`（如 `1/3.0`）在默认列中只保存 15 位
  （`0.333333333333333`），读回和比较都按这个值，因此 `LESS 1/3.0` 会选中写入时为 `1/3.0` 的行，而保留全部数字的 JSON 不会；
  要保留全部数字，请把该列声明为 `type = "DOUBLE"`。在 SQLite 上，与所存 `float` 前 15 位有效数字相同、之后才不同的任何数值
  （例如 `Math.nextDown((double) 0.1f)`、10^15 及以上的整数如 `(1L << 53) + 1`，或有 16 位及以上有效数字的小数）会被视为与它相等，因此 `GREATER` 该值不会选中保存 `0.1f` 的行；默认列如此，声明为 `REAL`
  的列也如此。对 `float` 字段请传 `float` 值。**null 或非有限的条件值在所有后端都会被拒绝（#815、#696）。** `getAll`、`exist`、`page`、`del` 与 `updateIf`（以及 `eq(null)` 会构造此类条件的 `Query`）在 SQLite、MySQL 和 JSON 上一致地于读取、删除或写入之前抛出带 `DATA_ENTITY_INVALID` 的 `DataAccessException`：条件值在任何比较方式下为 `null`，或是无穷大或 NaN 的 `Double`/`Float`。唯一例外是 `updateIf` 中 `EQUAL` 下的 `null` 值，仍表示 `IS NULL`（#640）。JSON 上的 `del(WhereCondition.empty())` 也同样被拒绝（与 SQLite、MySQL 一致），`exist(entity)` 对没有 id 的实体在所有后端都返回 `false`。应先检查值（判空、`Double.isFinite`）或捕获 `DataAccessException`。数值字段的列中若有手工或其他程序写入的非数字文本，在 SQLite 上按其开头的数字比较
  （`'abc'` 为 0，`'12abc'` 为 12），JSON 则对该行按文本比较。见 `FEATURES.md` 中的 `ultitools.storage.where-comparisons`。

- `PluginManager#getPluginList()` returning an unmodifiable snapshot, and
  `PluginManager#unregister(UltiToolsPlugin)` delisting the module (#507). `getPluginList()` returns a copy taken at the moment of the
  call: it does not change afterwards, and `add`, `remove` and `clear` throw
  `UnsupportedOperationException`. `unregister` itself removes the module from the loaded modules,
  by identity and whether or not the module's unload hook threw. `unregister` also releases the module's
  configuration entities (`ConfigManager#unregisterAll`) after its container is closed; nothing writes configuration at stop, unload or replacement (a never-saved change is named once and dropped).
- Registrations released per module instance (#506, #528). `TabCompletionManager`, `EventBus` and `PanelResponderRegistry` can record the registering
  module instance, and `unregister` releases by instance first. The framework records it for
  everything a module registers in the three registries while it loads — during its container
  refresh, where `@PostConstruct` runs, and during `registerSelf()` — through the ordinary name-only
  methods, and for every `@ModuleEventHandler` method. A registration made later is recorded against
  the copy listed under the name it is filed under (#562 item 1). A
  registration filed under the module's name only is released by name, except while
  another loaded copy shares that name, when it stays until the last copy of the name is unloaded. One visible consequence: a completer a module
  registers while it loads, including in `registerSelf()`, is released when the module unloads. `TabCompletionManager#register(key, completer)` still replaces an existing
  completer for that key; it does not keep a stack of previous registrants. If a loading module
  overwrites another registrant's key and then fails, cleanup removes the failed module's completer
  without restoring the overwritten one (#639). Use globally unique, module-prefixed custom keys
  and do not overwrite another module's or a built-in completer's key.
- Any failed
  load releases what the failing copy registered in the three registries.
  Register in `registerSelf()`, which always runs inside the scope, or pass the instance. The scope
  opens before component scanning and the module's singleton registration, so `@PostConstruct` on
  the module and on beans it injects is covered.

  中文补充：任何加载失败都会释放失败副本在这三个登记表中的登记。请在 `registerSelf()` 中登记
  （它总在加载范围内运行），或传入实例。加载范围在组件扫描与模块单例注册之前打开，因此模块主类及其注入的 Bean 的 `@PostConstruct` 也被覆盖。
- A registration made after load through the name-only APIs belongs to a module instance (#562
  item 1). `PanelResponderRegistry#registerResponder(type, responder, ownerModule)` and the
  `EventBus` methods that take an `ownerModule` (`subscribe(..., ownerModule, consumer)` and the
  name-only `register(...)`), called outside every module's load -- from a scheduled task, a command,
  a listener -- are recorded against the module the framework is loading on the calling thread, if any;
  otherwise against the copy listed as loaded under `ownerModule`, the first listed when more than
  one is (`PluginManager#findRegistrationOwner(String)`). A name with no loaded copy keeps the
  name-only behaviour. A tab
  completer registered after load carries no name and stays unowned. Prefer the overloads
  that take the instance when the module instance is at hand.

  中文补充：模块加载之后通过只带名称的 API 登记的内容属于某个模块实例（#562 第 1 项）。在任何模块加载过程之外
  （定时任务、命令、监听器中）调用三参数 `registerResponder` 或带 `ownerModule` 的 `EventBus` 方法时，若调用线程上框架正在加载某个模块，记在该模块名下；否则记在以 `ownerModule` 列为已加载的副本名下，
  列出多个时取最先列出的那个（`PluginManager#findRegistrationOwner(String)`，`@ApiStatus.Internal`）。没有已加载副本的名称
  仍按名称记录；加载后登记的补全器没有名称，仍不属于任何模块。手上有模块实例时请优先使用带实例的重载。
- `/ul reload` and a module's reload reporting what actually happened (#509, #529, #502).
  - the per-module line is logged only after the hook returned; when any reload step or the hook
    throws, one SEVERE line names the module and the cause instead, and the failure is rethrown
    unchanged to the caller;
  - a bare `/ul reload` reloads every module in isolation — as in `PluginManager#close()`, every
    `Exception` or `Error` one module throws is that module's failure — and ends with a summary
    naming the modules that failed; the summary is also sent to
    the command's sender;
  - `/ul reload <name>` replies failure, naming the module and the cause, when the reload threw —
    including when one of the module's configuration files cannot be read or parsed;
  - a module can report a partial reload without throwing: the public final class
    `ReloadReport`, the hook `protected void onReload(ReloadReport report)` — whose default body
    calls `onReload()`, so a module overriding only `onReload()` needs no change — and the
    `public final ReloadReport reloadWithReport()`, which runs the same reload as `reloadSelf()`
    and returns the report. `reloadSelf()` is `public final void`. A partial
    reload logs a WARNING naming the parts instead of the success line, `/ul reload <name>` replies
    with those parts instead of the success reply, and the `/ul reload` summary lists the module
    with them.

  The console lines and replies are in both shipped catalogues. A module that catches a failure
  in `onReload()` and only logs it can make the operator see it: override
  `onReload(ReloadReport)` instead and record the failure with `report.partial(...)`.
- An existing credential file that cannot be read is preserved (#573). `CommonUtils.getUltiToolsUUID()` fails with its declared `IOException`, naming the file, when the
  credential file exists but is empty, whitespace-only, the JSON literal `null` or not valid JSON,
  and it leaves the file untouched. The same applies to an
  unreadable pre-6.3.0 `data.json` while no current credential file exists, and saving or clearing
  the UltiCloud token refuses instead of overwriting. The condition is logged once at `SEVERE`. A
  credential file that does not exist at all starts a new identity. This corrects behaviour
  that contradicted the documentation: the method declares `IOException`, and silently replacing a
  torn credential file with a fresh identity is the defect the credential store exists to prevent.
  It follows the maintainer's rule for an unreadable configuration file (#470: treated like an
  unparseable one, never overwritten). A module that already handles the declared `IOException`
  needs no change. Separately, each credential write forces the file and, where the platform allows, its directory to disk
  around the atomic rename.
  When both the pre-6.3.0 `plugins/UltiTools/data.json` and the current `.ultikits/credentials.json` under the server
  root exist, the old copy is removed only after the current file has been read as a credential document - any JSON
  object, `{}` included, and also a JSON array such as `[]`, which the reader parses as the empty document ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672)); the current file's content
  wins and is never overwritten with the old one's. A current file that is empty, whitespace-only, the JSON literal
  `null`, not valid JSON, a string or a number instead of an object or array, or not a regular file does not count: both files are
  kept unchanged, one WARNING names both paths (never any content), no server UUID is generated and no UltiCloud token is
  written or cleared, and `CredentialStore.write` refuses with a `DataAccessException` while a readable pre-6.3.0 copy
  waits behind such a file. The next access after the current file is fixed - restored from a backup, or moved out of the
  way so the old copy migrates - completes the cleanup. A current file that cannot be opened (permission denied) also keeps
  both files and fails the access. When the current file does not exist yet, the copy is written, read back and compared with the old data before the old file is removed.

  中文补充：两份凭据文件并存时，只有当前文件（服务器根目录下的 `.ultikits/credentials.json`）能被读成凭据文档（任何 JSON 对象，包括 `{}`；JSON 数组如 `[]` 也一样，读取器把它解析为空文档）之后，才会删除 6.3.0 之前的旧副本（`plugins/UltiTools/data.json`）（#672）；以当前文件的内容为准，不会被旧内容覆盖。当前文件为空、只含空白、为 `null`、不是合法 JSON、是字符串或数字而不是对象或数组，或不是普通文件时，两个文件都原样保留，一条 WARNING 写明两个路径（不含任何文件内容），不生成服务器 UUID，也不写入或清除 UltiCloud 令牌；旧副本可读而当前文件不可读期间，`CredentialStore.write` 以 `DataAccessException` 拒绝。修好当前文件（从备份恢复，或把它移开让旧副本迁移）之后的下一次访问会完成清理。当前文件无法打开（权限不足）时同样保留两个文件，并使这次访问失败。当前文件尚不存在时：先写入、读回并与旧数据比较，之后才删除旧文件。
- **A remote file request uses the object it authorizes** ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672)). Each request is resolved on the filesystem once, and that one
  result is both what is judged and what is read, written, listed or deleted. What an operator or a panel author sees:
  a request whose real target lies outside every editable root is refused with the configurable
  `ultipanel.files.editable-roots` refusal; a path that cannot be located safely is
  refused and no configuration changes that - `.` or `..` in the part of the path that does not exist yet, a dangling or
  looping symbolic link, a directory that cannot be searched, and a `delete` that names no ordinary entry (`/`, `.`,
  `dir/.`, `dir/..`); a symbolic link inside a root
  that points to a protected file (a credential file, the remote action log's directory) is refused like that file; a `delete` of a terminal symbolic link - to a file, to a directory, or to nothing - removes only the link and leaves its target and everything under
  it alone. Protected locations are judged by real path, whichever editable root a request matched and in whatever
  order the roots are configured. A path authorization
  refusal is one `DENIED` entry in the remote action log. Known limitations: a separate local process that swaps a path component
  for a link between the check and the use can still redirect that one request (the panel itself cannot create links);
  a hard link to a credential file that the operator places inside an editable root under another name cannot be told
  apart from an ordinary file by path, so do not hard-link credential files into editable roots; and on a
  case-insensitive macOS filesystem a case variant of the protected `plugins/UltiTools/security` directory name is not
  recognised by the unconditional deny layer.

  More known limitations of the remote file API (#691), documented rather than defended. `files.delete`
  never deletes a directory (it answers `is_directory`).
  - The `files.*` handlers validate the path first and answer `invalid_message`.
  - If the read-back of a migrated credential file does not match what was written, the old copy is kept and one
    WARNING is logged. Do not edit credential files while the server runs.

  中文补充：**远程文件请求使用的就是被授权的那个对象**（#672）。每个请求只在文件系统上解析一次，这一个结果既是被判断的对象，也是被读取、写入、列出或删除的对象。服主和面板作者会看到：真实目标在所有可编辑根之外的请求，以可配置的 `ultipanel.files.editable-roots` 拒绝；无法被安全定位的路径一律拒绝且无法通过配置放行——路径中尚不存在的部分里的 `.` 或 `..`、悬空或循环的符号链接、无法搜索的目录，以及不指向任何普通条目的 `delete`（`/`、`.`、`dir/.`、`dir/..`）；根内指向受保护文件（凭据文件、远程操作日志目录）的符号链接，与该文件本身一样被拒绝；对末端符号链接的 `delete`——无论指向文件、指向目录还是什么都不指向——只删除链接本身，目标及其下的一切不动。已知限制：另一个本地进程若在检查与使用之间把路径中的某一段换成链接，仍可改变这一次请求的去向（面板本身无法创建链接）；服主以其他名称放进可编辑根的、指向凭据文件的硬链接，按路径无法与普通文件区分，所以不要把凭据文件硬链接进可编辑根；在不区分大小写的 macOS 文件系统上，受保护的 `plugins/UltiTools/security` 目录名的大小写变体不会被无条件拒绝层识别。

  中文补充（远程文件 API 的更多已知限制，#691，只写文档不做防御）：`files.delete` 从不删除目录（以 `is_directory` 回复）。`files.*` 处理器先校验路径并回复 `invalid_message`。迁移后的凭据文件读回与写入内容不一致时，保留旧副本并记一条 WARNING。服务器运行期间不要编辑凭据文件。

- **A malformed `ultipanel.commands.blocklist` is rejected whole** ([#672](https://github.com/UltiKits/UltiTools-Reborn/issues/672)). When the
  framework loads the key at server start it accepts only a list of which every element is a string (each trimmed and
  lower-cased); anything else - a scalar, a map, or a list with an element that is a mapping, a nested list, null, a number or
  a boolean - is ignored as a whole. One WARNING names `ultipanel.commands.blocklist` and the kind of the value, never the
  value, and the shipped default stays in force (13 commands). An explicit empty list `[]` is valid and blocks nothing, and an absent
  key, or `blocklist: ~`, keeps the default. The file is never rewritten. To block nothing, write `[]`; to block a command,
  write it as a string element.

  中文补充：**格式错误的 `ultipanel.commands.blocklist` 会被整体拒绝**（#672）。框架在服务器启动时加载该键，只接受每个元素都是字符串的列表（每项去掉首尾空白并转小写）；其他任何形式——标量、映射，或含映射、嵌套列表、null、数字、布尔元素的列表——都整体忽略，输出一条 WARNING，写明 `ultipanel.commands.blocklist` 和值的种类（不写值本身），随包默认值继续生效（十三条命令）。显式的空列表 `[]` 合法，表示不拦截任何命令；键缺失或写成 `blocklist: ~` 时保留默认值。文件绝不被改写。想不拦截任何命令请写 `[]`；想拦截某条命令，把它写成字符串元素。
- Configuration writes follow the maintainer's rule that operator-written configuration is never
  overwritten automatically (2026-10-04, "what code may write, by file type";
  [#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599)). Changed without a migration period,
  because a migration period would mean continuing to overwrite operators' files: a module's `save()`
  writes only what the module changed, where the file still holds what was read, and never writes over an
  operator's disk edit, deleted key or unusable value (it names the change as not written instead of
  overwriting it); a panel edit writes only what it touches; and nothing writes configuration at
  server stop, module unload or module replacement - a never-saved module change is named at stop and
  dropped, and `ConfigManager#saveAll()` is deprecated and writes nothing. A module that relied on the stop
  to persist an in-memory change must call `save()` (or `saveOperatorChange`) when it makes the change; the
  stop warning names any configuration that still holds one.
- The framework's own `plugins/UltiTools/config.yml` gets its missing panel keys (the capability switches,
  `ultipanel.commands.blocklist`, `ultipanel.files.editable-roots` and the action-log rotation keys) through the
  same write gate, insert only ([#605](https://github.com/UltiKits/UltiTools-Reborn/issues/605)). Every other byte stays; a file the gate cannot write byte-identically (hand
  alignment, anchors) is left as it is with one WARNING naming the keys, and the defaults shipped in the jar's
  `config.yml` (which also lists the blocklist and the editable roots) answer for them. A file that holds one of
  these settings as a flat dotted key (`ultipanel.capabilities.logs: false`) or a dotted section key, where the
  inserted nested section would replace it as the server reads the file, is not written either: one WARNING names the
  file and that setting (never a value), and the jar defaults answer for the missing keys. If the file changes on disk while this check runs, or the
  inserted text cannot be read back the way the server reads it, that WARNING names `the whole file` instead of a
  setting; nothing is written and the next start retries
  ([#828](https://github.com/UltiKits/UltiTools-Reborn/issues/828)).
- UltiTools starts bStats only when `plugins/bStats/config.yml` is absent or already holds `serverUuid`
  ([#606](https://github.com/UltiKits/UltiTools-Reborn/issues/606)). That file is shared by every bStats plugin;
  a file that cannot
  be read or parsed, or one without `serverUuid`, is left byte-identical, one line names it, and UltiTools'
  metrics stay off for that run (other plugins' own bStats copies are outside the framework).
- **Configuration constraint violations refuse load or reload instead of rewriting the file**. A value violating `@Range`, `@Size`, `@Pattern` or `@NotEmpty` on text refuses the module
  at load. At `/ul reload`, the reload is refused and the running values are kept. The error names
  the field and its constraint; the operator's file is not rewritten to a default. The in-memory default for an empty
  `@NotEmpty` list, set or map is a separate case, described in the next entry.

  中文补充：**配置约束违规时拒绝加载或重载，不再改写文件**。违反 `@Range`、`@Size`、
  `@Pattern` 或文本上 `@NotEmpty` 的值会让模块在加载时被拒绝；`/ul reload` 时拒绝这次重载并保留运行中的值，
  错误会写明字段和约束，不会把服主的文件改写成默认值。空的 `@NotEmpty` 列表、集合或映射在内存中使用默认值，属于下一条说明的独立情况。
- **`@NotEmpty` on a list, set or map uses a default for an empty value** ([#630](https://github.com/UltiKits/UltiTools-Reborn/issues/630)). A value that a load or reload binds empty - empty or `null` in
  the file, or a list whose every entry failed to bind - is replaced **in memory** by the field's declared default, and
  one WARNING names the file, the key, the value kind, the value as written and the default (both redacted when the
  field name, a key segment, or a map key inside the value or the default is secret-shaped). The module loads and the
  file is not written, at load and at reload; a later `save()` does not write the default either. The panel shows the
  file's value (`[]`) while the module runs on the default. A panel write that would empty the value is refused like any
  other violation, and nothing is written. An operator command that writes that setting with `saveOperatorChange` writes
  the running value - the default plus the operator's change - in place of `[]`: that is the operator's explicit request
  for that key. The declared default must itself satisfy the field's constraints - be non-empty, and inside the field's
  `@Size` if it has one (UltiSideBar's `lines` is `@NotEmpty @Size(min = 1, max = 15)`): a default that does not meet them refuses
  the module at load as a declaration error naming the field and the constraint, whatever the file holds. `@NotEmpty` on text refuses
  the module at load, naming the field, like every other constraint violation. An `@NotEmpty`
  list written as an explicit `null` (`key: ~`) runs the declared default in memory with the warning and the file is
  not written.
  **Known limitation retained (#635): the empty-container fallback uses the declared field type.**
  An `@NotEmpty Object` holding an empty list, map or array is refused, not replaced by its declared
  default, even when that default is a non-empty container. Declare the setting as a collection,
  map or array type when you need the in-memory default substitution; declaring it as `Object`
  does not make it a container setting. Refusal still leaves the operator's file unchanged.

  中文补充：**`@NotEmpty` 用在列表、集合或映射上会在空值时使用默认值**（#630）。加载或重载时绑定为空的值——文件中为空或为 `null`，或列表中每一项都无法绑定——会在**内存中**改用字段声明的默认值，并输出一条 WARNING，写明文件、键、值的种类、文件中写的值和默认值（字段名、某一级键名，或值与默认值中的映射键像机密时，两者都隐去）。模块照常加载，加载和重载时都不写文件，之后的 `save()` 也不会把默认值写进去。面板显示的是文件中的值（`[]`），而模块运行的是默认值。面板写入会让该值变空时，与其他违规一样被拒绝，什么都不写。服主命令用 `saveOperatorChange` 写这个设置时，写入的是运行中的值（默认值加上服主的改动），替换掉 `[]`：这是服主对该键的明确要求。声明的默认值本身必须满足该字段的约束——非空，并且若字段带 `@Size` 则在其范围内（UltiSideBar 的 `lines` 是 `@NotEmpty @Size(min = 1, max = 15)`）：不满足时属于声明错误，无论文件里写的是什么，加载时都会拒绝该模块并写明字段和约束。文本上的 `@NotEmpty` 拒绝加载并指明字段。写成显式 `null`（`key: ~`）的 `@NotEmpty` 列表在内存中改用声明的默认值并给出同一条警告，文件不写。
- **`@Range` refuses NaN** ([#625](https://github.com/UltiKits/UltiTools-Reborn/issues/625)). A value must satisfy `min <= value <= max`: NaN is out of every range and
  gets the ordinary out-of-range outcome - the module refuses to load at start, and at `/ul reload` the reload is refused
  and the running values are kept, naming the field, the value and the bounds. `.inf` and `-.inf` are out of
  range for every finite bound; a bound declared as `Double.POSITIVE_INFINITY` (or negative) accepts that
  infinity.

  中文补充：**`@Range` 拒绝 NaN**（#625）。值必须满足 `min <= 值 <= max`：NaN 超出任何范围，按普通越界处理——启动时拒绝加载模块，`/ul reload` 时拒绝这次重载并保留运行中的值，都会写明字段、值和范围。`.inf`、`-.inf` 超出任何有限范围；边界声明为 `Double.POSITIVE_INFINITY`（或负无穷）时接受对应的无穷大。
- **A constraint annotation the framework cannot check refuses the module at load**
  ([#631](https://github.com/UltiKits/UltiTools-Reborn/issues/631)).
  For a field that is itself a `@ConfigEntry` setting, a declared type that can hold a value the
  annotation checks - `Object`, `Serializable`, `Comparable`, `CharSequence`, `Number` or another supertype of a checked
  kind - is not a constraint error: the value bound at load is checked. Of these types only `Object` binds without a
  converter; the others need a module converter (`@ConfigConverterFor`), or the module is refused with `no config
  converter for ...`. A bound value of a kind the annotation cannot read (text in an `Object` setting under `@Range`, a
  number under `@Pattern`, `@Size` or `@NotEmpty`) is a violation, with that annotation's usual outcome: refused at
  load, and at `/ul reload` the reload is refused and the running values are kept. The
  rules for such a field:
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
    framework does not walk into value types ([#633](https://github.com/UltiKits/UltiTools-Reborn/issues/633)). Validate such fields in the module's converter (skip or refuse the entry
    there).

  中文补充：**框架无法检查的约束注解会让模块在加载时被拒绝**（#631）。对本身就是 `@ConfigEntry` 设置的字段，按其声明类型判断；声明类型若能容纳注解可检查的值（`Object`、`Serializable`、`Comparable`、`CharSequence`、`Number` 或其他被检查种类的父类型），则不算约束错误，加载时绑定的值照常检查；这些类型中只有 `Object` 无需转换器即可绑定，其余类型需要模块转换器（`@ConfigConverterFor`），否则模块会以 `no config converter for ...` 被拒绝；绑定的值若是注解无法读取的种类（`@Range` 下 `Object` 设置里的文本，`@Pattern`、`@Size` 或 `@NotEmpty` 下的数字），按该注解的普通违规处理：启动时拒绝加载，`/ul reload` 时拒绝重载并保留运行中的值；这类字段的规则如下：`@Size` 统计映射的条目数和数组长度（违规时与其他 `@Size` 违规一样拒绝模块）；对 `@NotEmpty`、`@Size`、`@Pattern` 而言，文本指 `String` 或 `char`。只有永远不可能容纳可检查值的声明类型才算声明错误：这类声明会在加载时、读取配置文件之前拒绝模块，一条消息写明每个字段、注解和原因：`@Range` 只检查数字；`@Pattern` 只检查文本；`@Size` 与 `@NotEmpty` 只适用于文本、列表、集合、映射和数组（`List<Integer>` 上的 `@Range` 就属于这种错误：它不适用于列表本身）。配置类中不是 `@ConfigEntry` 设置的字段上的约束同样会被拒绝。**约束注解只对本身就是配置类 `@ConfigEntry` 设置的字段生效。** 值类型的字段、嵌套类的字段，以及转换器产生的任何对象上的注解（例如 `Map<String, Item>` 设置中 `Item` 类字段上的 `@NotEmpty`）既不检查也不报告；框架不会深入值类型（#633）。请在模块的转换器中校验这些字段（在那里跳过或拒绝该条目）。

- **Declarative GUI: one frame runs only the work queued when it starts**. A rebuild
  requested during a frame runs on the next one. **Known limitation:** such a broken page is still retried once
  per tick for as long as it is open, and each failure is logged. Fix the widget: a `State` must not throw from
  `initState`; catch the failure there and render an error state instead.

  中文补充：**声明式 GUI：每一帧只执行该帧开始时已排队的任务**。帧内请求的重建在下一帧执行。**已知限制：**这样的坏页面只要还开着，仍会每个 tick 重试一次，每次失败都会记录日志。
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
  This behaviour will change to <new behaviour> in <target version>. See <issue link> to migrate.
  This warning is printed once per startup.
  ```

- **Version N+1**: switch to the new behaviour and remove the warning.

This section follows [PEP 387](https://peps.python.org/pep-0387/); the principle is the same one:
tell people which floor they are standing on before removing it.

### Declarative GUI known limitations and author guidance

The widget-authoring limitations collected in [#690](https://github.com/UltiKits/UltiTools-Reborn/issues/690)
apply where described below. The scheduling, session and diagnostic guidance here describes
7.0's UI-service-based engine.

- **A failing `initState` can keep requesting frames.** If it calls `setState` and then throws,
  the page retries once per scheduled frame while open and logs each failure. The frame budget
  limits retries, but does not repair the widget. Catch initialization
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
- **Close through the session.** `GridSession.close()` selects an outcome and schedules completion;
  it does not synchronously dispose an active build. On result completion the engine cancels pending
  frames, attempts tree disposal, then invokes `onGuiClose(UiResult<Ack>)`. A disposal failure and a
  close-hook failure each produce a WARNING naming the screen and module; disposal failure does not
  prevent the hook. A throwing disposal callback can still leave descendants incompletely cleaned up.
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

  中文补充：**声明式 GUI 的已知限制与编写建议**（[#690](https://github.com/UltiKits/UltiTools-Reborn/issues/690)）。
  `initState` 先请求重建再抛异常时仍可能逐帧重试，应在组件内处理失败；同级 `SlotKey` 必须稳定且唯一，
  `State.dispose()` 应幂等且不抛异常，单子组件包装器替换失败后应关闭再重开。通过 `GridSession.close()`
  请求关闭不会同步拆掉正在构建的树；结果完成时先取消帧并尝试清理，再执行关闭钩子。清理和钩子的异常
  分别记录包含界面与模块的 WARNING，清理失败不阻止钩子，但可能留下未清理完的子组件。
  首次构建失败从 `DeclarativeGui.open()` 抛出且不显示界面；结果尚未完成时再次 `open()` 仍返回原会话，
  即使它已选定关闭结果。`GuiScheduler.flush()` 仅供测试，可能持续清空自重排队的任务。
  后续帧总在下一 tick 的观看者实体线程执行；普通异常记 WARNING，JVM `Error` 记一次 SEVERE 后原样抛给
  调度引擎，当前帧剩余任务不执行，失败构建保留上一张完整快照。状态修改本身仍在调用线程立即发生。
  越界节点和不同格子上的重复容器下标记警告并跳过；同一格子被重复占用时记警告、后者覆盖前者；
  每个界面实例的同类同位置警告只记一次，重开不重置。每帧新建点击动作会令快照不相等。
  `GridView` 每格仅应渲染一个叶子，多叶子会被分配相同位置，显式 `.slot(...)` 也不能避开覆盖。

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
  cooldown (which validates after the lock), by an exception from a later validator, or if the mapped
  invocation cannot proceed. The mechanism is the default method `CommandValidator#onRefused`, called for
  each validator that passed; its default does nothing, and a refused dispatch runs no `onComplete`,
  so it applies no cooldown. Every `onRefused` and every `onComplete` hook runs even when an earlier
  validator's hook throws; the first exception is rethrown afterwards. A validator's `onComplete` and
  `onRefused` should therefore not throw.

- **`@Scheduled` methods declared on a superclass of a bean are scheduled** (#532), as the annotation's
  javadoc says. An overridden method is scheduled once, with the most derived declaration's
  annotation; an override without `@Scheduled` is not scheduled.

- **One failing `@PlayerCache` save or removal does not stop the quit cleanup** (#643). When a player
  quits, the framework runs `PlayerCacheSaver#savePlayerData` for each `saveBeforeRemove = true` field
  and then removes the player's entry from every `@PlayerCache` field. Each field is handled on its own:
  a failure (any exception, including a checked one the saver throws without declaring it, or an `Error`)
  is logged as one WARNING naming the bean
  class, the field and the player, and the cleanup goes on; a saver that registers or unregisters a
  tracked object during the quit does not end it either. A failed save does not keep the entry; it
  is removed anyway, because `saveBeforeRemove` orders the save
  before the removal. The framework does not retry the save; a module that must not lose the data
  retries or persists it itself, inside `savePlayerData`.
  **Known limitation (#647): the callback runs once per save-marked field,
  not once per bean.** A bean with N `@PlayerCache(saveBeforeRemove = true)` fields receives N
  callbacks on one quit, even if a previous callback failed; this per-field count is pinned by the
  regression tests. Each callback precedes its own field's removal, so later calls may see earlier
  fields already cleaned. These are separate field callbacks, not framework retries of a failed
  save. Keep a bean-wide saver safe to call repeatedly, and use only one save-marked field if that
  callback already persists all of the bean's caches; do not rely on a failed save preserving entries.
  The periodic expiry sweep (`PlayerCacheManager.ExpiringPlayerCache`)
  also contains an `Error` from one bean, so the beans after it are still swept. See
  `ultitools.runtime.player-cache-quit` in `FEATURES.md`.

  中文补充：一个 `@PlayerCache` 字段的保存或移除失败，不会中断玩家退出时的清理（#643）。玩家退出时，框架对每个
  `saveBeforeRemove = true` 的字段调用 `PlayerCacheSaver#savePlayerData`，再从每个 `@PlayerCache` 字段中移除该玩家的条目。
  每个字段单独处理：失败（任何异常，包括保存方未声明而抛出的受检异常，或 `Error`）
  记录为一条 WARNING，写明 bean 类、字段和玩家，清理继续进行；保存方在退出清理期间注册或注销被跟踪对象，也不会中断清理。保存失败
  不会保留条目，条目照样移除，因为 `saveBeforeRemove` 只规定先保存后移除。框架不会重试保存；不能丢失
  数据的模块需要在 `savePlayerData` 内自行重试或持久化。定期过期清理（`PlayerCacheManager.ExpiringPlayerCache`）同样会拦下单个 bean 抛出的
  `Error`，其后的 bean 仍会被清理。见 `FEATURES.md` 中的
  `ultitools.runtime.player-cache-quit`。

- **Command validator lifecycle, inherited `@CmdTarget` scan isolation.**
  The shared-validator lifecycle limits below are documented rather than defended in code.
  Inherited-signature linkage
  failures are isolated to the affected class during scanning; they do not abort the whole
  component scan. A module author's remedy is given with each.
  - **Executors that share one custom validator chain.** An executor releases the
    `@CmdCD` and `@UsageLimit` validators it dispatched through when its module unloads, and leaves a
    validator alone while another loaded executor that has dispatched still has it in its chain (a
    chain passed to `super(chain)`, or one instance returned from `createDefaultValidatorChain()` by
    several executors). An executor counts as a user from its first dispatch on. **Do not
    share one custom validator chain instance across executors;** give each executor its own chain,
    especially across modules. Normal container teardown releases an executor's unshared validators.
  - **A shared validator is unregistered when the last sharing executor is torn down.** Until then
    it stays registered with the player-cache manager. An executor the module container never tears
    down - one built with `new` and registered by hand, outside the container - keeps a shared
    validator registered while that executor remains reachable. Garbage collection alone does not
    trigger unregister: a later executor teardown prunes cleared executor references before deciding
    which validators to release. If no other
    executor sharing the validator is torn down later, it can stay registered until shutdown.
    **Let the module container own its executors.**
  - **The inherited `@CmdTarget` check isolates a missing signature dependency to one class.** The
    load check for a method-level `@CmdTarget` that widens or switches the class-level value covers
    command mappings inherited from base classes, not only methods the class declares. It reads each
    superclass's methods during the component scan. If a signature names an absent optional
    dependency, `NoClassDefFoundError` is caught by the scanner's per-class `LinkageError` boundary
    in both directory and JAR scans. That command class is skipped, a warning names it, and the
    remaining classes are still scanned; the package's diagnostic summary also records the skip.
    The framework has no `@ConditionalOnClass`-style annotation for this. **Keep optional dependency
    types out of command base-class signatures**, or load those command classes only when the
    dependency is present.

  中文补充：命令校验器生命周期、继承的 `@CmdTarget` 扫描隔离。(1) 请勿在执行器之间共用同一个自定义链，
  尤其不要跨模块共用；每个执行器使用自己的链。(2) 共享校验器在最后一个共用它的执行器被销毁前一直登记在玩家缓存管理器中；不由模块容器
  销毁的执行器（用 `new` 创建并手动注册）在仍可达时会让它保持登记。垃圾回收本身不会触发注销；后续执行器销毁时才会清理失效的弱引用，
  再决定释放哪些校验器。如果之后没有其他共用它的执行器被销毁，它会一直登记到服务器关闭。请让容器持有执行器。(3) 继承的 `@CmdTarget` 检查在组件扫描时读取整条父类链：
  父类方法签名引用了缺失的可选依赖时，目录和 JAR 扫描都会逐类捕获 `NoClassDefFoundError`，跳过该命令类并记录警告与扫描摘要，
  继续扫描其余类，不会中断整个组件扫描。请让可选依赖的类型不出现在命令基类的方法签名中，或只在依赖存在时加载相应的命令类。

The default teleport service's display name, `InMemeryTeleportService#getName()`, is `TeleportService`;
its `getResourceFolderName()` still returns `传送服务`, so an existing install keeps its folder.

### Panel log stream and panel reply changes (6.3.0) that need no migration period

- **`ultipanel.logging.excluded-loggers` ships empty** (#485). A configured list is used as given.
  Log4j lines reach the stream under their Log4j logger names, and a configured entry applies to them as well.

- **A log batch whose send fails is returned to the front of the queue** (#486). Delivery stays best effort.

- **Records logged before the stream starts reach it** (#487). From `onLoad` until the panel
  connection opens, records are kept in a start-up buffer (2000 records, an estimated 512 KiB, five
  minutes) and replayed, oldest first, when the stream starts. Each entry keeps the time its record was
  logged. The buffer applies the stream's filters as records arrive and is released without sending
  anything when there is no cloud login or when its time is up; with the `logs` capability off it is
  not attached and keeps nothing.

- **Known limitations of custom logging and reloads** (#571):
  - Custom logging formatters and log-parameter `toString()` implementations must not write another
    eligible root log record each time a buffered record is rendered. Doing so can feed the start-up
    replay continuously and prevent it from finishing. Keep formatting side-effect free; remove or
    correct a recursively logging formatter or parameter implementation rather than relying on the
    log stream to stop that recursion.
  - Bukkit `/reload` is unsupported. If an old plugin class loader remains reachable after such a
    reload, its scan logger bridge can remain attached and duplicate diagnostic lines. Use a
    full server stop and start after changing plugin JARs. `/ul reload` reloads module configuration;
    it is not Bukkit `/reload` and does not replace plugin JARs.

- **Panel log delivery is best effort; the server's `latest.log` is authoritative** (#583, #571).
  The stream is a convenience copy of the console, not a record of it. A record another thread logs
  while the stream first takes over from the start-up buffer can be missed, and is not counted.
  Anything that must not be missed - an audit, an incident - is read from `logs/latest.log`.

  中文补充：面板日志推送尽力而为，以服务器的 `latest.log` 为准（#583、#571）。日志流只是控制台的便利副本，不是完整记录。
  在日志流首次接管启动缓冲时，其他线程记下的一条记录可能漏发，且不计数。凡是不能遗漏的内容（审计、事故排查），请以 `logs/latest.log` 为准。

- **Lines about the panel connection are not sent to the panel.** The panel's `error` replies
  and notifications, inbound messages the framework cannot use, the WebSocket client's connect,
  disconnect, heartbeat and reconnect lines, and the warnings about a message that could not be sent
  are written to the server console but the log stream drops them.

- **The panel's log stream mirrors the server console.** Paper prints
  its own output through Log4j — command feedback, a module's reply to the console sender, joins and
  quits, chat, vanilla warnings and errors, and player command lines — and the framework
  installs an appender on Log4j's root logger at load (with the `logs` capability on) and removes it at
  disable; each line passes the same filters, batching and start-up replay as a plugin line, without
  ANSI colour codes. A plugin line arrives once, although Paper also copies it into Log4j; lines about
  the panel connection, the
  transmitter's own lines and the WebSocket library's (`org.java_websocket.*`) are never sent. If the
  server's Log4j configuration uses asynchronous loggers, the mirror is not installed, a console
  WARNING says so, and the stream carries plugin lines only. The same holds when Paper's forwarder
  (`org.bukkit.craftbukkit.util.ForwardLogHandler`) is not on the `java.util.logging` root logger — a
  fork, a build that relocates CraftBukkit, or something that replaced the forwarder (#583): without it
  the mirror cannot tell a forwarded plugin line from a console line and would send every plugin line
  twice, so it is not installed, one console WARNING per server run says the panel will not mirror the
  console, and the stream carries plugin lines only. A Log4j `ERROR` line with an exception is
  also reported to UltiPanel's error collection, once. `org.apache.logging.log4j:log4j-core` is a
  `provided` dependency which Paper supplies at runtime; it is not shaded, and a module needs nothing new.

  中文补充：找不到 Paper 的日志转发器（`org.bukkit.craftbukkit.util.ForwardLogHandler`，例如分支服务端、重定位了
  CraftBukkit 的构建，或转发器被替换）时，控制台镜像不安装：否则每条插件日志都会被推送两次。此时每次服务器运行只记一条
  WARNING，说明面板不会镜像控制台，日志流只包含插件日志（#583）。

  **Known limitation — an earlier instance whose disable never ran** (#642): its `ForwardScope`
  wrapper can remain around Paper's forwarder on the JUL root logger. A new instance does not
  recognise that foreign-classloader wrapper as the forwarder, so the "was not found" WARNING does
  not prove that the underlying forwarder is absent. Let UltiTools disable normally; stop and restart
  the server when replacing an instance or recovering from a stale wrapper. Do not replace a running
  plugin instance without its normal disable lifecycle.

  中文补充：旧实例未执行禁用流程时的已知限制（#642）：它的 `ForwardScope` 包装器可能仍在 JUL 根日志器上包着 Paper 的
  转发器。新实例无法把来自旧类加载器的包装器识别为转发器，因此“was not found”告警不能证明底层转发器不存在。
  请让 UltiTools 正常完成禁用流程；替换实例或恢复遗留包装器时，停服后重新启动，不要绕过正常禁用流程替换运行中的插件实例。

- **A panel write to a `server.properties` key the file does not hold is refused** with
  `server.properties does not define this key` (#473). Nothing tells a key the running version lacks
  from one the file omits.

- **A panel edit of `server.properties` changes only the line of the key it names** (#607). The value
  text of that one line is replaced - key, separator, comments, order and every other byte kept -
  decoded and encoded as the server reads the file (strict UTF-8, ISO-8859-1 when it is not UTF-8),
  checked, and written atomically. A key defined on more than one line, or continued onto the next
  line, is refused with a reason naming the lines (no value).

- **Known limitation of panel responder scheduling** (#677). A module's
  responder runs on one of `PanelResponderRegistry`'s worker threads (at most 4 calls at once, at most 64 requests
  waiting) under one 3-second deadline counted from the request's arrival. If the thread admitting a request stalls
  for longer than that deadline between starting it and queueing the request, while all 4 workers are blocked, the
  request, already answered with its timeout error, is queued afterwards and holds one of the 64 waiting slots until a
  worker frees, so a later request can be refused as if the queue were full. No wrong reply is sent.

  中文补充：面板 responder 调度的已知限制（#677）。若接收请求的线程恰好在启动该请求的 3 秒时限与把请求放入队列之间停顿超过
  3 秒，且 4 个工作线程同时阻塞，这个已按超时答复的请求仍会在之后入队，并占用 64 个等待位置中的一个，直到有工作线程空出；
  其间后来的请求可能被当作队列已满而拒绝。不会发出错误的回复。

### JSON transactions (6.3.0) — staged, atomic and durable

In this section a *table* is one entity type of one plugin:
one `@Table`, one folder of `<id>.json` files.

For a transaction opened by `DataOperator#transaction(...)` or by `@Transactional`:

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
  other, and a read-modify-write inside one cannot lose an update. A read inside `transaction(...)` can wait for
  another transaction that holds the table, at most 5 seconds (bounded as a write is, below).
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
    characters.

  Every rule but the last holds on every operating system; the last is the file system's own. A refused insert
  does not mark its transaction rollback-only. An `insert` of an id that already has an entry changes nothing and
  is never refused, and a file that already exists is never renamed or rewritten because of these rules: it keeps
  loading, and reads, updates and deletes of its id keep working. Deleting an existing id these rules refuse and
  inserting it again, also inside one transaction, counts as creating a new id and is refused: replace such an
  entry with `update`. If a flush meets an id the file system cannot name, it fails with an error naming the
  table and the id and writes nothing; if a start-up recovery meets
  one in a journal record (a record copied from Linux to Windows, for example), it refuses that plugin's JSON data
  with an error naming the record, the table and the id, changes no file, and is handled like a damaged record
  (see the next item).
- **A damaged journal record.** If a committed journal record is found damaged at start-up, that plugin's JSON
  data stays closed: its data operations fail with an error naming the file, until the server operator moves the
  file out of the journal folder. The framework never deletes, skips or applies the damaged record. Other plugins
  are not affected. A module that obtains its data operators while it loads is refused at that start,
  so after moving the file aside, restart the server. Only a plugin that first requests
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
- A read inside `DataOperator#transaction(...)` can wait for another transaction's table lock, for at most 5
  seconds.
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
  value get two separate caches and two table locks that do not exclude each other so their writes
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
- One entity file that keeps failing to move (for example, a file another program keeps locked) stops every flush
  of that plugin from emptying the journal: records accumulate, start-up recovery takes longer, and each failed
  flush is reported in the server log. Unlike moves, entity-file and journal-file deletions have no
  bounded Windows retry (#694); even a brief scanner/indexer hold can fail that flush, and the next
  flush tries again. Release the external hold and check that flushing resumes; do not delete the
  journal to suppress the warning.
- Known limitation: a file system that lets the server open a directory but refuses to force it makes every flush
  of the plugin fail; the journal grows and, after a restart, the plugin's data stays closed until recovery can
  succeed.
- A JSON data folder whose ids contain `<`, `>`, `:`, `"`, `|`, `?` or `*` cannot be copied from Linux to
  Windows: Windows cannot create those files; new ids with these characters are refused there. This raw-name
  portability limitation is retained (#694), not fixed by copying only the table files or renaming them.
  Data whose ids differ only in letter case cannot be moved to Windows or to a default macOS volume either:
  the files collide and can overwrite each other, with no case-collision registry. Choose ids that remain
  distinct ignoring case before writing data. If a journal record holding an unnameable id reaches a Windows
  server, start-up keeps that plugin's JSON data closed and names the record: apply it on the Linux server
  that wrote it, or move it out of the journal folder and lose its changes.

中文补充：JSON 事务（6.3.0）——暂存、原子、持久。在 `datasource.type: json` 下，`@Transactional` 也像在 SQLite、MySQL 上一样生效。
本节中的“表”指一个插件的一种实体类型，即一个 `@Table`、一个存放 `<id>.json` 文件的文件夹。

对由 `DataOperator#transaction(...)` 或 `@Transactional` 开启的事务：

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
  其中的“先读后写”不会丢失更新。`transaction(...)` 中的读取可能等待持有该表的另一个事务，最多 5 秒（与写入一样有上限，见下）。加入活动事务的 `transaction(...)`
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
    文件会变成另一个 id 的文件或落到数据文件夹之外。Linux 接受这些字符。

  除最后一条由文件系统自己决定外，其余规则在所有操作系统上都一样。被拒绝的插入不会把事务标记为只能回滚。插入一个已有条目的 id
  不做任何改动，也永远不会被拒绝；已存在的文件也绝不会因这些规则被改名或重写：它照常加载，对该 id 的读取、更新和删除照常可用。删除一个
  这些规则会拒绝的已有 id 后再插入它（在同一个事务中也一样）算作创建新 id，会被拒绝：要替换这样的条目，请用 `update`。刷新若遇到文件系统
  无法命名的 id，会以指明表和 id 的错误失败，且不写入任何东西；启动恢复若在日志记录中遇到这样的 id（例如从 Linux 复制到 Windows 的
  记录），会以指明该记录、表和 id 的错误拒绝打开该插件的 JSON 数据，不改动任何文件，处理方式与损坏的记录相同（见下一条）。
- **日志记录损坏。** 启动时若发现已提交的日志记录损坏，该插件的 JSON 数据保持关闭：数据操作会失败，错误信息指明该文件，直到服主把该文件
  移出日志文件夹。框架不会删除、跳过或应用损坏的记录，其他插件不受影响。在加载时就获取数据操作器的模块
  会在那次启动时被拒绝加载，所以把文件移走后需要重启服务器；只有在加载完成之后才首次请求数据的插件，会在下一次请求时再次执行恢复，
  无需重启。

**普通写入。** 事务外的 JSON 写入先放在内存里、在下一次后台刷新时写盘，可能要等待事务的表锁。后台刷新是按插件进行的检查点：
只写上次检查点之后改动的条目，作为跨该插件所有表的一个整体崩溃原子地写入，然后清空日志。刷新失败时，改动的条目保持待写状态，下次刷新再写；
失败会从计划的刷新任务中抛出，并出现在服务器日志里。

**模块作者可能注意到：**

- 普通 JSON 写入可能等待事务的表锁，最多 5 秒。
- 事务可能因锁等待超时或死锁而失败并回滚，框架不会重试。在事务内捕获该错误并不能挽救事务：提交会回滚并抛出
  `UnexpectedRollbackException`。
- 改动了数据的提交会在返回前写入并强制落盘一条日志记录，即在提交线程（常常是服务器主线程）上等待磁盘。
- `DataOperator#transaction(...)` 中的读取可能等待另一个事务的表锁，最多 5 秒。
- 在 `@Transactional` 方法中，“先读后写”的代码使用 `updateIf` 或 `REPEATABLE_READ`（见上）；默认隔离级别下它可能丢失更新。在
  `DataOperator#transaction(...)` 中不会。
- 同一线程上的 `REQUIRES_NEW` 或 `NOT_SUPPORTED` 方法不会等待其调用方的表锁（见上）。
- 加入方方法被规则认可的失败会把整个事务标记为仅回滚（见上与下一小节）。
- UUID 字符串作为 id 总是可用。小写字母、数字、`-` 和 `_` 是安全的字符，但由它们组成的 id 若是 Windows 设备名（`con`、`nul`、`aux`、
  `prn`、`com1` 至 `com9`、`lpt1` 至 `lpt9`，无论带不带扩展名），在所有操作系统上仍会被拒绝。不要用玩家名作为实体 id：`Con` 和
  `Nul` 都是合法的玩家名。在 Windows 上，含 `<`、`>`、`:`、`"`、`|`、`?` 或 `*` 的 id 会在插入时被拒绝。这些规则会拒绝的已有 id
  仍可读取、`update` 和删除，但删除后再插入会被拒绝；要替换这样的条目，请用 `update`。只有大小写不同的两个 id，在不区分
  大小写的文件系统（Windows，以及默认设置的 macOS）上指向同一个文件，会互相覆盖。
- 一个 `@Table` 值只对应一个实体类。同一插件的两个实体类若声明了相同的 `@Table` 值，会得到两份独立的缓存和两把互不排斥的表锁，它们的写入彼此没有先后保证，上述各项保证在它们之间都不成立。
- 每个插件的 JSON 表旁边有一个隐藏的 `.ultitools-journal` 文件夹。服务器中是 `plugins/UltiTools/data/<插件名>/.ultitools-journal`；
  使用外部插件 API 的普通 Bukkit 插件是 `<该插件数据文件夹>/data/.ultitools-journal`；直接基于表文件夹构造的操作器是与该文件夹并列的
  `<表文件夹>.ultitools-journal`。遍历插件数据文件夹的模块必须跳过它。

**按平台的持久性。**

- “`commit` 返回即持久”取决于文件系统和硬件是否遵守刷盘请求。在 Windows 和 macOS 上，它能抵御服务器崩溃，但断电或操作系统崩溃可能丢失最近的几次完整提交：
  Windows 不会强制重命名及其所在目录落盘，macOS 的文件强制刷盘并不是真正刷到盘片。丢失的提交是整笔丢失，不会只丢一半。
- 在 Windows 上，被其他程序（杀毒软件、索引器、备份工具）短暂占用的日志文件移动，最多重试 150 毫秒，之后提交失败。
- 因真实 I/O 错误而无法强制落盘的目录会以 `WARNING` 记录（`Could not force the directory <目录> to disk ...`）。此时完成的提交已经提交，
  能抵御服务器崩溃，要等下一次刷新完成后才能抵御断电；遇到该错误的刷新或启动恢复会失败并重试，最终删除后强制落盘失败时日志记录已不存在。

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
- id 中含 `<`、`>`、`:`、`"`、`|`、`?` 或 `*` 的 JSON 数据文件夹无法从 Linux 复制到
  Windows：Windows 无法创建这些文件。id 只有大小写不同的数据也无法迁移到 Windows 或默认设置的 macOS 卷：文件会互相冲突。若含这类 id
  的日志记录到了 Windows 服务器上，启动时该插件的 JSON 数据会保持关闭，错误信息指明该记录：请在写下它的 Linux 服务器上应用它，或把它
  移出日志文件夹并放弃其中的改动。

### A failing joined `@Transactional` method rolls back the whole transaction (6.3.0) — changed without a migration period

A `@Transactional` method that joins a transaction already active on the thread —
`REQUIRED`, the default, or `SUPPORTS` or `MANDATORY` called inside a transaction — and throws an
exception its own `rollbackFor`/`noRollbackFor` rules accept marks the whole transaction
rollback-only, even when the calling method catches the exception. The outermost commit then rolls
back every write of the transaction, the caller's included, and throws
`com.ultikits.ultitools.exceptions.UnexpectedRollbackException`. This holds on SQLite, MySQL and JSON
alike, and it is the same rule a failed nested `DataOperator#transaction(...)` scope follows.
In detail:

- An exception the joined method's rules do not accept — a checked exception under the default rules,
  or a type listed in its `noRollbackFor` — leaves the transaction committable, and the outer method
  commits both its own writes and the joined method's.
- A joined failure that the outer method does not catch rolls the transaction back, and the
  caller receives the original exception, not `UnexpectedRollbackException`.
- When, after such a mark, the outer method throws an exception its own rules commit despite (for
  example one listed in its `noRollbackFor`), the caller receives `UnexpectedRollbackException` with
  that exception attached as suppressed.
- A joined method's `isolation`, `readOnly` and `timeout` are not applied; they belong to the
  transaction that began it.

**What a module author does instead.**

- Let the failure propagate, so the transaction rolls back with the original exception.
- Put work whose failure the caller survives in a `REQUIRES_NEW` method, which commits or rolls back
  alone.
- Or list the exception in the joined method's `noRollbackFor` when its writes are meant to stay.

Code that builds a `TransactionInterceptor` over its own `TransactionManager`: the interceptor
calls `begin()`, then `commit()` or `rollback()`, around a joined method, so `begin()` on an active
transaction must join it, `rollback()` at a nested depth must mark the transaction rollback-only
without ending it, and the outermost `commit()` of a marked transaction must roll back and throw
`UnexpectedRollbackException`, as both built-in managers do.

中文补充：加入现有事务的 `@Transactional` 方法（默认的 `REQUIRED`，或在事务中调用的 `SUPPORTS`、`MANDATORY`）
抛出其自身 `rollbackFor`/`noRollbackFor` 规则认可的异常时，整个事务被标记为仅回滚，即使调用方捕获了该异常也是如此；
最外层提交随后回滚该事务的全部写入（包括调用方自己的写入），并抛出 `UnexpectedRollbackException`。SQLite、MySQL 与
JSON 行为一致，与失败的嵌套 `DataOperator#transaction(...)` 作用域遵循同一规则。规则不认可的异常（默认规则下的
受检异常，或列在 `noRollbackFor` 中的类型）不会标记事务，外层方法照常提交双方的写入；外层方法未捕获的失败会回滚，调用方
收到原来的异常；标记之后，外层方法若抛出其自身规则仍要求提交的异常，调用方收到 `UnexpectedRollbackException`，该异常作为
suppressed 附在其上；加入现有事务的方法不应用自己的 `isolation`、`readOnly` 和 `timeout`。模块作者的做法：让失败向外传播；把调用方需要承受其失败的工作放进 `REQUIRES_NEW` 方法，它单独提交或回滚；或者在
加入方方法的 `noRollbackFor` 中列出该异常，以保留其写入。自行以 `TransactionManager` 构造 `TransactionInterceptor` 的代码：
拦截器会在加入方方法前后调用 `begin()`，再调用 `commit()` 或 `rollback()`，因此活动事务上的 `begin()` 必须加入该事务，
嵌套层的 `rollback()` 必须只把事务标记为仅回滚而不结束它，被标记事务的最外层 `commit()` 必须回滚并抛出
`UnexpectedRollbackException`，两个内置管理器均如此。

### A failed nested `DataOperator#transaction(...)` marks the whole transaction rollback-only (6.3.0)

This is the same rule as the joined `@Transactional` method above, for a nested `DataOperator#transaction(...)`
scope.

- **SQLite, MySQL and JSON alike.** The inner failure marks the transaction rollback-only and does not
  end it. The outer scope's later writes stay inside the transaction. The outer commit rolls everything back and
  throws `com.ultikits.ultitools.exceptions.UnexpectedRollbackException`.

**What a module does.** Let the inner failure propagate, so the transaction rolls back with the original
exception, or do the work whose failure the caller survives outside the outer transaction (for example in a
`REQUIRES_NEW` method).

中文补充：嵌套的 `DataOperator#transaction(...)` 失败会把整个事务标记为仅回滚。这与上一小节中加入现有事务的
`@Transactional` 方法遵循同一规则，只是对象是嵌套的 `DataOperator#transaction(...)` 作用域。在 SQLite、
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
that overrode the method can no longer be loaded at all. The removal-list mechanism still cannot express it, because nothing was
removed for the annotation to describe.

### What this means for you

**Pinning low is not the same as being safe.**
[Module versioning](https://dev.ultikits.com/guide/advanced/module-versioning) states that compiling
against an older API will not produce a `NoSuchMethodError` by reaching something newer. That
statement still holds, but it only rules out one direction of cause. The reverse direction — the
framework changing its own descriptors — produces the same exception.

**Raising the pin does only half of the job, and it is the half nobody checks.** These are two
independent numbers and should not be treated as one:

| Number | Determines | Who checks it |
|---|---|---|
| The `UltiTools-API` version in `pom.xml` | Which version's descriptors your bytecode records | Nobody. It is `provided`, does not enter the JAR, and the framework cannot see it at runtime |
| `api-version` in `plugin.yml` | The declared runtime floor | On 7.0, `PluginManager.loadPluginMainClass` refuses a declared integer below `700` before loading the main class; `isUltiToolsVersionCompatible` still refuses a higher level than the installed framework. `/upm` installs only integer levels from `700` through the framework's level and rejects missing declarations. Manual loading also rejects missing, empty and non-integer declarations before main-class resolution and excludes their JARs from the shared class path. Of the two numbers, this is the only one checked |

So "the pin is the floor" is wrong: raising the pin does not raise the floor. An artifact compiled
against a new framework while still declaring an old `api-version` is **let through** by an old
server, and then fails on the first call into a new descriptor — the same `NoSuchMethodError`, in the
opposite direction. **Both numbers must move together.**

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

The complete fix is therefore: raise the pin, recompile, **raise `api-version` to the corresponding
API level as well**, and accept the consequences that follow.

What cannot span both sides is, precisely, **the artifact that statically calls the method**.
Descriptors are written into the call site at compile time, so one call site can only match one side.
Three options follow, in increasing cost:

1. **Accept the raised floor** (the default choice). Old servers stay on the old JAR; the new JAR
   serves the new framework only.
2. **Ship separate artifacts per framework range.** This means maintaining two release lines.

In other words, the guidance elsewhere in this document — that a lagging pin is a normal state and
does not need to be changed for its own sake — **does not apply here**. That guidance says not to move
the pin without a reason; a descriptor change is a reason.

Re-verify your module whenever you raise its pin or its `api-version`.

### A floor no linker enforces: config-bound `@Scheduled` and `@CmdCD` (6.3.0)

`@Scheduled(config = ..., periodKey = ..., delayKey = ...)` and `@CmdCD(config = ..., key = ...)`
read an interval or cooldown from a module config key (#531).

**Config-bound async scheduling (#535).** `async = true` can be combined with a binding. A synchronous
trigger records its fire tick on the main thread, where `/ul reload` also runs, before dispatching the
method body with a one-tick async delay (50 ms of wall-clock time, not a server tick). Reload preserves the trigger's cycle (last observed fire + new period, or next
tick if overdue), not the worker's start/completion time, and leaves work already dispatched alone.
Slow async bodies may overlap and must not use thread-unsafe Bukkit APIs. Unload cancels the trigger
and queued workers; a body already executing may finish.

**A bound field must not also carry a module `@Range`.** The binding's own range is the field's
range: 1 to `Integer.MAX_VALUE / 20` seconds for a period or delay, and 0 (no cooldown) to
`Integer.MAX_VALUE` for a cooldown. A panel write outside it is refused like a `@Range` violation.
An out-of-range value on `/ul reload` keeps the running value and logs a WARNING. A `@Range` on the
same field would instead make the configuration reload itself fail, which stops the rest of that
module's reload and reports it as failed (a full `/ul reload` still continues with the
next module). A module that already had a `@Range` on a field it now binds should drop it; the binding’s range takes
over.

A failure part-way through a config-bound timing refresh is reported honestly: updates applied
before the failure remain in effect, and the remaining updates did not complete. Tasks and executors are still
updated individually; no rollback or lifecycle reordering is introduced. The framework-owned timer/cooldown rules
and incomplete-config reasons in reload reports follow the server's language, including Chinese; arbitrary exception
messages supplied by other code are not translated.

**Known limitation — internal cooldown state.** Modules must not modify `ConfigBoundCooldownState` or separate its
paired source and running-seconds maps. They are framework-internal state initialized together. If an extension
breaks that pairing, a refusal report can display `null` seconds instead of a running value; the framework does not
repair this unsupported state or invent a fallback cooldown. A normal reload of a null or invalid configured value
retains its already-initialized running cooldown instead.

## SQLite lock waits under native profiling (planned for 6.3.2)

The fix for [#645](https://github.com/UltiKits/UltiTools-Reborn/issues/645) measures SQLite busy waits with a monotonic elapsed-time budget. It preserves the 3000 ms default, DEFERRED transactions, and JDBC statement timeout selection; native profiler signals do not consume the budget merely by interrupting requested sleeps.

Servers with long-held SQLite write locks should use MySQL; keep SQLite transactions short and do not share or externally modify a live SQLite file.

A read-before-write transaction can still fail immediately when upgrading its read lock would deadlock with another transaction. A busy handler does not remove that SQLite behavior: do not assume every SQLITE_BUSY waits for the full timeout, or retry an entire transaction blindly. The framework does not switch transactions to IMMEDIATE to hide it. Direct changes to SQLite-owned busy handlers or PRAGMA busy_timeout by module code are outside the framework-managed wait policy.

The elapsed-time busy handler is built for the SQLite JDBC driver Paper ships (#823). If another plugin or a fork places a different `org.sqlite` driver where UltiTools' classes resolve it, the wait budget can behave differently without a warning. Do not replace or shadow the shipped SQLite driver.

## UltiCloud: re-pair with a code (as of 6.4.0)

- **`/ulticloud login <code>`** (console only, like `/ulticloud login`). The panel's "Revoke and re-pair"
  action, for a server whose credential may have leaked, revokes every credential of the server, closes its
  connection with 4401 and shows the owner a single-use code, valid for 10 minutes, bound to the **same** server
  record. Running `/ulticloud login <code>` on the server exchanges that code for a new credential through the
  `POST /v1/device/token`, stores it exactly as a device login does, and reconnects; the server keeps
  its server ID, name and history. No device code is requested and nothing has to be confirmed in the panel.
- The code is a secret: the console shows `Redeeming the re-pair code with UltiCloud...` and never the code.
  Text that cannot be a code (not 43 to 128 characters of `A-Z a-z 0-9 _ -`) is refused with one red line and
  is not sent. A used, unknown or expired code is answered `expired_token` by UltiCloud and logged as "UltiCloud:
  this re-pair code has expired or was already used. Create a new one in the panel (server settings > Revoke and
  re-pair), or run /ulticloud login without a code."
- A server that is still linked refuses the code exactly as it refuses `/ulticloud login` ("already linked",
  naming `/ulticloud logout`). A server that was offline when the owner used "Revoke and re-pair" deletes its
  revoked credential at the next connection attempt (HTTP 401), after which the code can be used while it is
  still valid.
  The 4401/401 warning names both ways back (see [Connection outcomes an operator sees](#connection-outcomes-an-operator-sees)).
- The framework's sensitive-command registry holds `ulticloud`: a line such as `Steve issued server command:
  /ulticloud login <code>` (a player typing the console-only command by mistake) reaches UltiCloud's console
  stream as `/ulticloud ***`. The local console and log files are unchanged.

中文补充：面板的“撤销并重新配对”会撤销该服务器的全部凭据、以 4401 断开连接，并给出一次性代码（10 分钟内有效，绑定同一服务器记录）。在服务器控制台运行 `/ulticloud login <code>` 即可换取新凭据并重连，服务器 ID、名称和历史保持不变；控制台不会显示代码本身。代码已使用、未知或过期时会提示到面板重新生成，或直接运行不带参数的 `/ulticloud login`。

## UltiCloud v2 on 7.0

The 7.0 client uses UltiCloud protocol 2.1.0 and the catalogue REST contract 1.1.0. The old cloud wire protocol is not supported.

### Cut-over: the protocol v1 cloud stops

The framework connects to `wss://api.ultikits.com/v1/agent` with a per-server credential, and
`/upm` reads the public catalogue under `/v1/catalogue/*`.

- A server is linked with `/ulticloud login`, which uses a device code: the console prints a
  verification URI, an `XXXX-XXXX` user code and a complete link, valid for 10 minutes; the owner confirms in
  the panel and names the server there. Over RCON the command answers at once that the code is printed in
  the server console, and every line also goes to the console. The credential is stored as the
  `cloud_credential` entry of `<server root>/.ultikits/credentials.json` (owner-only on POSIX). It does not
  expire. `/ulticloud logout` revokes it on UltiCloud and then deletes it; when UltiCloud cannot be reached it
  still deletes it locally and tells the operator to revoke it in the panel. `/ulticloud status` names the
  server, its server ID and the connection state. `/ulticloud reconnect` retries at once, at most once
  every 10 seconds.
- At start-up, a `cloud_token` left by 6.3.0 (including one migrated from the pre-6.3.0
  `data.json`) is deleted with one WARNING ending "Run /ulticloud login to re-attach this server."; the
  32-hex `uuid` entry is kept byte for byte and is sent as `legacyServerUuid` with the device login, so
  the panel can offer to re-attach the existing server. A file with no `cloud_token` is not
  rewritten. The old token is never sent anywhere.
- **A server that lost its files keeps its panel record.** The server's identity on UltiCloud is its server record
  in the panel, not a file on disk. If `<server root>/.ultikits/credentials.json` is lost (a wiped plugin folder, a
  reinstall), run `/ulticloud login` again and, on the panel's confirmation page, choose to attach the server to
  your existing server instead of creating a new one: the panel record and its history stay with the server.
  If the panel answers that the existing server still has an active credential, revoke that credential in the
  panel first, then confirm again. Do not copy `credentials.json` between servers: two servers using one credential
  replace each other's connection (close 4000), and after three replacements the framework stops reconnecting.

### New public API

| API | Purpose |
|---|---|
| `websocket.SensitiveCommandRegistry`, `UltiTools#getSensitiveCommandRegistry()` | A module registers its secret-carrying commands and aliases (`register("login", "l", "register", "reg")`) from its enable hook. Every line, chat message and error report sent to UltiCloud keeps such a command's name and replaces its arguments with `***`; the local console and log files are unchanged, except the framework's own records of a remote-console command (see [Sensitive commands in the remote console's local records](#sensitive-commands-in-the-remote-consoles-local-records-as-of-640)). The framework's own registry holds `ulticloud`, whose `login <code>` form carries a re-pair code; matching ignores a leading `/`, a `namespace:` prefix and case, and there is no unregister. A `player.command` event carries only the normalised command name, registered or not. |
| `websocket.CloudPlayerLinks`, `UltiTools#getCloudPlayerLinks()` | The player-link API that replaces the magic-link calls: `create(...)` returns a one-time link valid for 600 seconds; one module-owned `LinkEventHandler` receives completions and expiries on a framework worker thread; the framework persists each result before it answers the gateway, so a completion redelivered after a restart is answered without calling the module again. `getCloudPlayerLinks()` returns `null` only before the framework has enabled. |
| `PanelResponderRegistry#ownerModuleOf(String)`, `#ownerModules()` | Read-only views of responder ownership. |
| `websocket.CloudChannel`, `CloudRequest`, `CloudRequestHandler`, `CloudLimits` | The framework's `@ApiStatus.Internal` request and reply seam. Public only because handlers live in two packages; not for module use, and they may change without notice. |

`PanelResponderRegistry`, `SensitiveCommandRegistry` and `CloudPlayerLinks` are
stable extension points although their package carries `@ApiStatus.Internal`.

### Module responders: result shapes and two source-level changes

A `PanelResponderRegistry` responder may answer any JSON value: an object, an array, a primitive, or no
content. A future that completes with `null` or `JsonNull` is a success with no content, sent as `ok` with
`data: null`. A responder that returns no future, throws, or completes its future
exceptionally still fails the request (`module_error`). The `module_error` message is value-free by design: when a
responder's result cannot be serialised as JSON, the message does not name the offending field.

- `registerResponder(String, Function<JsonObject, ? extends CompletableFuture<? extends JsonElement>>, String)`
  and the four-argument overload. A lambda or a `Function<JsonObject, CompletableFuture<JsonObject>>`
  still compiles. A subclass that overrides either method with the old parameter type no longer
  compiles (the override clashes with the new signature) and must take the new parameter type.
- `dispatch(String, JsonObject, String)` returns `CompletableFuture<JsonElement>` instead of
  `CompletableFuture<JsonObject>`. A caller that assigns the result to a `CompletableFuture<JsonObject>` no
  longer compiles. The future never completes successfully with a Java `null`: no content is
  `JsonNull.INSTANCE`.

### Module identifiers and the `module.request` key convention

UltiCloud addresses a module by its catalogue identifier: the module's `identify-string`, trimmed and
lower-cased, not its `plugin.yml` name. This matters where the two differ, for example UltiChat (name
`UltiTools-Chat`, identifier `ultichat`), UltiEconomy (`ultieconomy`), UltiKits (`ultikits.kits`) and UltiMenu
(`ultimenu`). The identifier is what the `hello` declares as `modules:<identifier>@<X.Y.Z>` (version: the first
three numeric parts of the module's `plugin.yml` version), what a panel `module.request` names in `module`, and
what an `error.report` names in `moduleName`. `moduleName` is `null` unless the report names a loaded module.
A module whose `identify-string` is missing or is not a valid identifier (a lower-case letter first, segments of
lower-case letters and digits joined by one `.` or `-`, at most 64 characters) is addressed by its `plugin.yml`
name trimmed and lower-cased instead, when that is a valid identifier; if neither is, its
responders are not declared and one WARNING says so. Error reports use the same valid normalized name
when the identify-string is missing or invalid.
Prefer a valid catalogue `identify-string` so module requests and reports use the published module's identifier.

The version in `modules:<identifier>@<X.Y.Z>` is always three numbers. The framework takes the first three
runs of digits before any `-` or `+` suffix in the `plugin.yml` version and pads missing ones with `0`:
`2.0`, `2.0-rc1` and `2.0+build.9` become `2.0.0`. UltiCloud's protocol accepts only `X.Y.Z` in a capability name (no pre-release tag), so a `2.0.0-rc1` form would make the gateway
refuse the whole `hello`. Module authors who want the panel to show the version they ship should use a plain
three-number `plugin.yml` version; a pre-release should not reuse the number of a later release.

A `module.request { module, action, params, actor }` is dispatched only to a responder of the module whose
identifier equals `module`, registered under `<identifier>.<action>`. New registrations should use that form.
The form `<plugin.yml name lower-cased>.<action>` keeps working; the request still arrives with the
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

An existing `config.yml` gains the two keys once at start-up, with their comments and the value `false`. Only
missing keys are inserted, through the configuration write gate, and every operator-written byte stays as it
was; a layout the gate cannot keep gets one start-up WARNING naming the keys and the reason, the file is not
written, and both switches stay off. Every request is
checked against the switch's current value: with the switch off it is refused with `capability_not_accepted`
naming the key, and a power request with `power_disabled`. A `/ul reload` that turns a switch on or off reconnects, so the next `hello` declares the new
set; a changed `power-actions` value alone does not reconnect and is reported to the panel as
`settings.changed`, because `power` is always declared and the switch is checked in its handler.

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

The framework never declares `backups`, so the panel's backup requests are answered by the gateway as unavailable and
never reach the server. The panel cannot start, stop, pause or reconfigure the log stream: protocol
2.1.0 has no log-control message.

### Connection outcomes an operator sees

The framework retries a lost connection with full-jitter backoff from 1 second up to 60 seconds, building a new
socket and reading the credential afresh on every attempt, and logs one line per attempt and per scheduled
retry. The gateway's refusals and closes end in these states:

| Signal | Outcome |
|---|---|
| HTTP 401 at the upgrade, or close 4401 (credential revoked or the server deleted) | Reconnecting stops, the local credential and the reliable-delivery state are deleted, and the console says: "UltiCloud rejected this server's credential (it was revoked or is no longer valid). The saved credential was deleted. If you used "Revoke and re-pair" in the panel, run /ulticloud login <code> with the code it shows; otherwise run /ulticloud login to link this server again." |
| HTTP 426, or close 4426 | Reconnecting stops, the credential is kept, and the console says this UltiTools version is no longer accepted, with the minimum version when the gateway names one. Upgrade UltiTools and restart. |
| HTTP 402, or close 4402 (plan limit) | The credential is kept; the framework retries quietly every 15 minutes and prints the reason, the end of the grace period and the plan's manage link once. `/ulticloud status` shows the pause; `/ulticloud reconnect` retries at once. The connection resumes on the next retry after the plan is renewed. |
| HTTP 429, close 4400 or 4429, any other 4xx | Long backoff, from 60 seconds up to 15 minutes; a 429 waits at least its `Retry-After`, at most one hour. |
| Close 4000 (another connection uses the same credential) | Long backoff; after three in a row with no connection lasting 10 minutes in between, reconnecting stops and the console says another connection uses the credential. |
| A `welcome` naming another server ID than the linked one | Reconnecting stops, the credential is kept, and the console tells the operator to contact support or run `/ulticloud logout` and `/ulticloud login`. |
| Close 1000, 1001, 1009, 1011, 4408, a network error, a 5xx, a missing `welcome` within 15 seconds, or 90 seconds without a `pong` | Normal backoff. |

### Module downloads

`/upm install`, `/upm update` and the start-up update check use only the public catalogue. A module JAR is
downloaded only from the same origin (scheme, host and port) as the configured API base URL; another origin is
refused, and no 3xx redirect is followed. The body must match the version's `sizeBytes` and `sha256` (64
lower-case hex characters in the version metadata), or the temporary file is deleted, an error is reported and
nothing lands under `plugins/UltiTools/plugins/`. Version metadata without a usable hash or size makes the
version uninstallable, never unchecked. The `X-Content-SHA256` response header, when present, must name the
same hash; a missing or upper-case header is accepted, because the body is always checked against the
metadata. A version whose metadata is broken ends in the generic "Install Failed!" line without a reason. The
catalogue's `framework.downloadUrl` (Maven Central) is never downloaded by the framework; only its version is
read for the newest-version check. `/upm update` stages a module against the version it read through the
catalogue route, whatever shape the download URL has.

Version lists explicitly request `page=N&pageSize=5` and keep the `apiLevel` and `minApiLevel`
filters on every page. `PluginInstallUtils.getPluginVersions` and `/upm versions` return all pages in
catalogue order, not just the first five entries. The newest-version lookup uses the first `isLatest`
marker, or the greatest version across the filtered list when no marker remains after filtering.
Reading stops at the first page's `total`, a page with fewer than five items, or page 1,000,000.
A missing or invalid `total`, or any requested page that fails, makes the whole lookup fail without
exposing a partial list; only a first-page 404 means the module is unknown. Each JSON response still
has a 4,194,304-byte limit, which fits five worst-case 64,000-character changelogs without retaining
the changelogs across pages. JAR size and hash verification are unchanged.

### Remote action log under protocol 2.1.0

`plugins/UltiTools/security/action.log.0` keeps one JSON line per panel request that reached a decision. The
`action` of a file, configuration or `server.properties` request is the protocol 2.1.0 request type
(`files.read`, `files.list`, `files.write`, `files.delete`, `config.update`, `properties.get`, `properties.update`);
power and player actions are `server.power.<action>`
and `player.action.<action>`, and a run or blocked command keeps `execute_command` (a switch refusal is written with
the request type, `command.execute`). A permitted file request that
then fails for an ordinary reason (a missing file, a directory where a file was expected, a version that no longer
matches) is `DENIED` with the reply’s message as `reason`. `ALLOWED` means the
request was carried out: the file read, the directory listed, the change made, at least one configuration key
written. A request refused because its `ultipanel.capabilities.*` switch is off is one `DENIED` line from the switch
gate. A served `snapshot.request` is one `ALLOWED` line with capability `MONITORING`, actor `panel`
and the requested target names joined by commas in request order as its target. A malformed request,
`config.tree`, `config.entries`, unsolicited telemetry and module requests write no line, and from the first step
of the disable a read (`files.read`, `files.list`, `properties.get`) is still answered but writes no line either;
a `snapshot.request` needs the server thread and is dropped unanswered, writing no line (see the shutdown window
below). No line ever holds file content or a configuration value.

### Sensitive commands in the remote console's local records (as of 6.4.0)

When the panel's remote console (`command.execute`) runs a
command a module registered with `UltiTools#getSensitiveCommandRegistry()`, the framework's own records of that
request do not carry its arguments. The `[Remote command] > ...` echo and the `[Remote command] Blocked: ...` line show the command followed by `***` (the line for a command that throws while being dispatched names only the command, `[Remote command] Command threw an exception: logadmin`, never its arguments), and so do the server console, `logs/latest.log` and the panel's
log stream, which all receive those lines; the request's line in `plugins/UltiTools/security/action.log.<n>` has the
same text as its `target`. For example, with UltiLogin registering `logadmin`, the panel command
`logadmin reset Steve <password>` is logged as `[Remote command] > logadmin ***` and recorded with
`"target":"logadmin ***"`.

- The registered name is matched against the command as it is dispatched (one leading `/` and surrounding or repeated
  whitespace, no-break spaces included, removed; a `namespace:` prefix and letter case ignored), so `/ logadmin ...`
  or a no-break space after the name does not hide it. The command keeps the spelling it was sent with
  (`/UltiLogin:LogAdmin reset ...` is logged as `UltiLogin:LogAdmin ***`).
- The command itself is still dispatched with its arguments, and its reply (`command.result`) is unchanged: what the
  command prints is the module's own output, and redacting it is the module's decision.
- A command that is not registered is logged and recorded exactly as received.
- Out of scope: a player's own `issued server command` line in the local log files (written by Paper, unchanged
  locally and redacted on the way to UltiCloud), and lines other plugins write.

中文补充：面板远程控制台（`command.execute`）运行模块通过
`UltiTools#getSensitiveCommandRegistry()` 注册的命令时，框架为该请求写下的记录不包含参数：`[Remote command] > ...`
回显行和 `[Remote command] Blocked: ...` 行都只显示命令名加 `***`（命令分发时抛出异常的那一行只记命令名，如 `[远程命令] 命令执行异常: logadmin`，不含参数），服务器控制台、`logs/latest.log` 和面板日志流
（它们都收到这些行）同样如此；`plugins/UltiTools/security/action.log.<n>` 中该请求的 `target` 也是这段文字。例如 UltiLogin
注册了 `logadmin` 后，面板命令 `logadmin reset Steve <密码>` 记为 `[Remote command] > logadmin ***`，动作日志为
`"target":"logadmin ***"`。注册名按实际分发的命令匹配（去掉一个前导 `/`、首尾和重复空白（含不换行空格），
忽略 `namespace:` 前缀和大小写）；命令本身仍带参数分发，回复（`command.result`）不变；未注册的命令照原样记录。
玩家自己的 `issued server command` 行（由 Paper 写入）
和其他插件写的行不在此列。

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
- **Header and body values use the contract's ranges.** A `Retry-After` header on the device-login endpoints is
  read as 1 to 86400 seconds, like the body's `retryAfterSeconds`; a value
  outside that range is ignored and the body's value is used. A device-login poll never waits past the code's
  expiry, so an expired code is still reported on time. The `X-UltiCloud-Protocol-Supported` header of a refused
  connection is read as 1 to 99, like the body’s `supportedProtocols`.
- **Cooldown seconds round up.** `/ulticloud login` refused by its one-minute cooldown names the remaining seconds
  rounded up (9.9 seconds left is shown as 10, and less than a second as 1, never 0).
- **Integers in the credential file stay integers.** An update of `<server root>/.ultikits/credentials.json`
  (for example a credential rotation) writes `userId` and `linkedAt` back as integers (`12`, `1700000000000`) rather
  than `12.0` and `1.7E12`. Both forms are read the same way.
- **Telemetry and log buffering.** The players, plugins and worlds snapshots are rebuilt only when a new
  server sample exists, and a snapshot the connection refused (larger than the negotiated frame size) is offered
  again only once its content changes, not every second. A buffered console line keeps at most 8193 characters
  until it is sent; a line is cut to the negotiated `lineMaxBytes` (at most 8192 bytes).

### Known limitations (6.3.1)

These are documented, not defended; each needs a crash, an unusual layout, or a step the operator should not
take.

- **JDK HTTP debug logging can expose the server credential.** The JDK-internal logger
  `sun.net.www.protocol.http.HttpURLConnection` at `FINE` or lower can print request headers,
  including `Authorization: Bearer ...` sent by `CloudHttp` when logout revokes the credential at
  `/v1/agent/credential/revoke`. Retiring `UltiCloudRequests` does not remove this limitation;
  the framework does not redact the JDK's wire logs. The WebSocket library's logger
  `org.java_websocket` at `TRACE` can likewise record the bearer-bearing handshake on connect and reconnect.
  Do not enable either logger on production servers; if enabled for debugging, treat the resulting logs
  as credential-bearing material.
- **Commands are at most once.** A `command.execute` or `server.power` id is persisted before the
  acknowledgement and before the command runs. A crash between persisting the id and running the command loses
  that command; it is never run twice.
- **Reliable events can lose the last 30 seconds.** Unacknowledged `player.join` and `player.leave` events live
  in memory and in `<server root>/.ultikits/cloud-outbox.json` (owner-only), written every 30 seconds when
  changed and at shutdown, at most 1000 events by default (UltiCloud's `welcome` can set another limit for the
  connection), with the oldest dropped first (reported once as `outbox.overflow`). A crash loses what changed
  since the last write.
- **A quit after UltiTools is disabled is not captured.** During a server stop, a player quit that fires after
  UltiTools has already been disabled is not recorded: Bukkit no longer calls a disabled plugin's listeners.
- **A crash can ask a player-link handler twice.** If the server crashes after the module's handler decided a
  completion but before the framework recorded the result, the redelivered completion calls the handler again.
  A result larger than 4 KiB is not stored either, so its redelivery also asks again. When the framework cannot
  resolve its state directory, ids and results are not kept at all.
- **Module JARs are hash-checked, not signed.** The catalogue's `sha256` and `sizeBytes` protect against a
  corrupted or substituted download, not against a compromised catalogue.
- **Version pagination is not a catalogue snapshot.** If a version is published while a multi-page
  lookup is in progress, publication ordering can shift an entry between pages. The client rejects the
  whole lookup as an unreadable response when a version appears on more than one page, or when a page
  reports a `page` or `pageSize` other than the one requested; it never shows a list with a repeated
  entry. A version published onto a page that was already read can still be missing from a lookup that
  succeeds. Retry `/upm versions` after publication has finished. The client adds no snapshot or
  concurrency machinery for this combination.
- **The state directory guard assumes the default layout.** The remote file guard finds `.ultikits/` through the
  server's working directory, while the credential store finds it from the plugin data folder
  (`<plugins dir>/../.ultikits`). With a relocated plugins folder and editable roots widened to reach it,
  `cloud-outbox.json` is not covered by the `.ultikits` refusal; `credentials.json` still is, by name. Do not
  widen the editable roots beyond the server root.
- **Case-insensitive file systems.** On a case-insensitive file system such as the default macOS one, whether a
  request spelled `.ULTIKITS/...` is refused depends on how the JVM resolves the real path.
- **Hand edits of `credentials.json` while the server runs are not picked up.** The running session holds the
  current credential, and a rotation is written to disk before it is acknowledged; an out-of-band edit or
  deletion takes effect at the next start. Do not edit framework-owned files while the server runs.
- **A rotation during shutdown can outlive its grace period.** A `credential.rotate` that reaches the server in
  the shutdown window is refused; the gateway sends it again after the next `welcome`. If the server stays down
  longer than the rotation's grace period (24 hours by default), the old credential expires, the next
  connection is refused with HTTP 401, and the operator runs `/ulticloud login` again, which reconnects the same
  server.
- **A command accepted in the last moment before a stop may not run.** A request that needs the server thread,
  such as a command, a player action or a configuration read or edit, admitted after the server began stopping
  but before UltiTools' own disable started (Paper disables plugins loaded after UltiTools first), may never get
  its server-thread task run. The panel then reports a timeout, and nothing changed.
- **Console-only plugin commands are refused from the panel.** A panel command runs with Paper's capturing
  command sender, which has console permissions but is not the console sender, so that the panel receives the
  command's real output. A plugin command that accepts only the console sender refuses it. Run such commands in
  the server console. There is no automatic console retry.
- **A panel command's immediate reply is not echoed to the console.** It is captured in
  `command.result` `data.output` on success; when dispatch is refused, the captured text is in the
  `command_failed` error's `message` instead. Neither is echoed to the console, its log files or the panel's
  log stream. The console records the audit line (English: `[UltiTools] [Remote command] > <command>`;
  its text follows the server's `language` setting) and any late output, not a copy
  of the immediate reply (`CommandExecutionManager#execute` writes the audit line;
  `CommandExecutionManager#executeCaptured` captures the reply and forwards late output).
- **A panel command's late output is not in its result.** `command.result` is sent when the command's dispatch
  returns, so it holds only the lines printed until then. Lines printed later (an asynchronous command body, a
  scheduled reply) are written to the server console unchanged, so they appear in the console, the log files
  and the panel's log stream (sensitive commands redacted, as for every console line). That forwarding lasts
  10 minutes after the result; a line printed later still is dropped, with one WARNING naming the command.
- **File and property requests share one worker per connection.** They run in arrival order on one thread, so a
  slow `files.list` delays the next file or property request, never
  heartbeats, acknowledgements or other request types. More than 64 waiting requests are answered
  `rate_limited`, and a switch turned off while a request already waits does not stop that request.
- **Error-report attribution.** `error.report.moduleName` is `null` when no loaded module can be identified.
  Command executors and AOP beans are attributed by their concrete class's JAR (see
  [Error-report ownership and text bounds](#error-report-ownership-and-text-bounds-as-of-650)); an unavailable,
  ambiguous or unmatched source remains unattributed.
- **A panel command's audit line follows `language`, and a refusal is in `message`.** The console audit line is
  written through the framework's text catalogue, so it reads `[UltiTools] [Remote command] > <command>` in English
  and `[UltiTools] [远程命令] > <command>` with the default `language: zh`. The text of a refused command (for
  example a blocklisted one) is the `message` of the `command_failed` error reply, not `data.output` of a
  `command.result`.
- **The command blocklist is read when the request is admitted.** A request is checked against the blocklist at
  admission, before it waits for the server thread, so a reload can race that outer check. The resolved
  command is also checked on the server thread just before dispatch, and a command entering `BaseCommandExecutor`
  checks the current list at execution.
- **`server.power` is acknowledged before it is scheduled.** Its `ok` reply is sent first, and the stop or restart
  is scheduled to begin one second later. If UltiTools starts disabling between the reply and the scheduling, the
  action is dropped with only a FINE log line although the panel saw `ok`. Check the console and send the request
  again if the server is still running.
- An expiry that arrives before any module has registered a handler is consumed and not delivered, so register
  the handler before asking for a link.
- **Some malformed backend answers look like other failures.** A device-login grant that is `200` but unusable (for
  example an invalid `userId` or `serverId`) is logged as a WARNING without values and polling continues, so the
  operator finally sees the device code expire although the backend issued a credential: run `/ulticloud login`
  again and revoke the orphaned credential in the panel. A revoke answered `200` without `revoked: true` is counted
  as revoked. Neither happens with a contract-valid backend.
- **While a logout's revoke is in flight, `status` and `login` still see the server as linked.** The revoke has a
  15-second read timeout after a 10-second connect timeout (25 seconds at most); during it `/ulticloud status`
  shows the link and `/ulticloud login` answers that the server is already linked. Wait for the logout outcome
  line before logging in again.
- **Configuration the panel cannot show.** A module setting whose value is outside the contract bounds (text over
  8192 characters, a list over 500 items or an item over 1024 characters, an integer outside the safe-integer
  range) is left out of `config.entries` instead of being truncated, and a module lists at most the contract's
  entry cap. Edit such a setting in the file.
- **A panel edit that names one key twice keeps the last value.** A `config.update` whose `changes` list the same
  key more than once answers every item `ok` and applies them in order, so the file ends with the last value. The
  official panel never sends a key twice; a custom client should send each key once.
- **Map-valued settings are not shown in the panel's configuration view.** `config.entries` lists only settings
  of a type the contract can carry (text, whole number, number, true/false, a list of text, an enum). A setting
  declared as a map, an object, a Bukkit value or a list of anything but text is left out and cannot be edited from
  the panel. Edit it in the module's file and run `/ul reload`.
- **Configuration values are visible to the panel under the file-read switch, secrets included.** Reading a
  module's configuration through the panel (`config:read`) is governed by `ultipanel.capabilities.file-read`, the
  same switch as reading files, and the values are sent as the file holds them: a password or token kept in a
  module's configuration is shown to whoever can read that server in the panel. The framework does not mask values
  by key name, because a name-based guess is unreliable. Keep `file-read` off on servers whose configuration holds
  secrets you do not want in the panel.
- **An editable root equal to the server root is not listed.** `files.list` with an empty path lists the editable
  roots by their path relative to the server root; the server root itself has no such path and is omitted, although
  requests for paths inside it are still judged normally. Use sub-directories (`plugins`, `logs`) as editable roots.
- **A panel config edit replaces a module's unsaved in-memory change to the same key.** The panel writes the file and
  reloads the module's config entity; the value on disk wins and the module's unsaved
  change to that key is dropped. Modules should save their own changes before an operator edits the same key.

## UltiCloud client replies and panel-command boundaries (as of 6.5.0)

- **Nested framework commands recheck the remote blocklist.** While a synchronous panel dispatch is active,
  `CommandInvocation` checks the invoked label, resolved command name, registered label and every registered alias
  before help, validators or the command body. A listed sibling alias blocks the command's other spellings too:
  listing `ultitools` refuses `ul help`, `ulti help` and `ultitools:ul help`, and the synchronous framework target in
  `execute run ul help` when the operator allows `execute` itself. Matching is case-insensitive and namespace-free.
  This covers synchronous wrappers only when their target enters the framework's command engine.
  The in-body refusal
  of panel-issued `ulticloud login`, `login <code>` and `logout` remains independent of the configurable list.
  A nested refusal is captured as ordinary command output **only when the nested command keeps the panel's sender**: a
  wrapper that re-dispatches as the console or as a player sends the refusal to that sender's chat or to the server
  log instead, and the panel's `command.result` is `ok` with no refusal line. A direct refusal still returns
  `command_blocked`.
- **Bukkit alias matching is symmetric over registered metadata.** Immediately before dispatch and at the synchronous
  nested framework boundary, on the server thread, a blocklist entry matches either the resolved command's name,
  label or aliases, or a command resolved from that entry whose name, label or aliases contains the invoked label.
  Both sides are case-insensitive and namespace-free. A label the server does not know keeps its admission
  decision; it is not refused merely for being unknown. See the wrapper limitations below.
- **Dispatch failures still get a result after their acknowledgement.** Repository unit fixtures expose
  the escaping-`Error` branches: `PanelDispatchHardeningTest#commandLinkageFailureIsAnsweredWithoutKillingDispatch`
  proves a linkage failure is answered without rethrow, and
  `#commandErrorStillAnswersAfterAckAndRestoresOriginBeforeRethrowing` proves other errors are answered before rethrow.
  A `BaseCommandExecutor` body instead reports its reflected failure as ordinary command output, for example
  `Command execution failed: <message>`. The framework's WARNING header names only the command, but a third-party
  exception's message or stack can include arguments; the echo and action-log target also keep unregistered arguments.
  Do not use these records as a secret-redaction boundary. The panel-origin scope is always restored, and late feedback
  follows the existing console-forwarding rules.
- **An asynchronous teleport has a 25-second reply deadline.** If its future has not completed, `player.action.result`
  reports `internal_error` with `teleport did not complete in time`. This stops waiting, not the teleport: the Bukkit
  operation is not cancelled and may still take effect. Check the player's actual position before retrying. A late
  completion never sends a second result. The deadline uses the existing cloud timer, not a per-request thread.
- **Player-link events held behind a lost create answer.** Closing the connection or receiving an error
  releases held completion ids for redelivery without restarting. Checking a saved receipt and claiming a completion
  are atomic. A held completion or expiry is delivered only after the create future's callbacks have returned;
  cancelling the caller's future cannot prevent transport cleanup. Already-running completion handlers remain
  in flight across reconnects.
- **Module capability versions use only the version core.** Digits in prerelease or build metadata do not fill a
  missing component: `2.0-rc1` and `2.0+build.9` both declare `2.0.0`, not `2.0.1` or `2.0.9`.

### Remote command defaults (as of 6.5.0)

The shipped `ultipanel.commands.blocklist` contains 13 entries, in this order: `op`, `deop`, `stop`,
`restart`, `reload`, `ban-ip`, `pardon-ip`, `whitelist`, `save-off`, `save-all`, **`execute`, `return`, `function`**.
The three entries refuse direct nested-dispatch entry points such as `execute run stop` and `function <pack>:x`
because alias/nested matching has the limits described below. Namespaced forms such as `minecraft:execute` are
refused too, with `command_blocked` and `details.matched: execute`.

**Known limitation:** the first word is what is matched, so `schedule function <pack>:x …` still runs a datapack
function later from the scheduler; `schedule` is not on the shipped list because it is an ordinary operator command.
An operator who wants to refuse it adds `schedule` to `ultipanel.commands.blocklist` (stop, edit, start).

New installs and configurations without this key get the defaults. **An existing operator list is never
extended or rewritten**: existing servers keep their list and must add `execute`, `return` and `function`
themselves if they want these refusals. To allow those commands, stop the server, delete their three lines from
`ultipanel.commands.blocklist` in `plugins/UltiTools/config.yml`, and start it again. Keep the remaining entries;
`[]` disables the entire configurable list. `/ul reload` does not re-read this list.

中文补充：默认列表包含 `execute`、`return`、`function`，新安装或缺少此键的配置采用默认值。已有列表绝不追加或改写；服务器如需拦截这三项须自行添加。要允许它们，停服后删除列表中对应三行，再启动服务器；`/ul reload` 不会重读此列表。已知限制：`schedule function …` 仍可由调度器稍后执行数据包函数，默认列表不含 `schedule`；如需拦截，请自行加入后重启。

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
creating links. The router intentionally has one handler per protocol message type.
Large player/world snapshots are capped at 1000/200 entries,
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

**Module-author pattern for optional events.** Keep required handlers in a class whose signatures do
not refer to optional server types. Put optional handlers in separate classes outside **every**
`@UltiToolsModule.scanBasePackages` root. Check all declared roots. Probe the optional
event with `Class.forName` before loading/constructing and explicitly registering its listener; do not
make it an automatically scanned IoC component. Unregister manually registered listeners on module
unload. One absent parameter type can prevent reflection of the entire class, so a probe does not
protect required handlers left in that same class.

**Relocate shaded libraries outside the scan roots.** Relocate shaded libraries outside every `scanBasePackages` root (for example
`com.example.libs.gson`), which also keeps the component scan from loading their classes.

For the earlier server support matrix, see the [6.x branch](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md#support-matrix).

### Where runtime dependencies come from

As of 7.0.0 the framework JAR bundles no third-party library.
Every runtime dependency arrives by one of two routes:

| Delivery route | Version decided by | Examples |
|---|---|---|
| The Paper `UltiToolsLoader`, using the coordinates in `ultitools-libraries.txt` | **This repository** | commons-dbutils, Java-WebSocket, ByteBuddy, JavaMail (`com.sun.mail:javax.mail`), HikariCP |
| Shipped by the Paper server itself | **The Paper build the server owner installed** | Gson, MySQL Connector/J, protobuf, slf4j, the SQLite driver, native Adventure, log4j, the Maven resolver Paper uses internally for `libraries:` and its dependencies |

In 7.0, XSeries is no longer supplied by the framework; modules that use it must shade and relocate their own copy.

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

If you disagree with this policy, or your module is affected by an API removed or changed in 7.0 (see the [command](#command-engine-as-of-700), [text](#legacy-text-system-removed-as-of-700),
[GUI](#legacy-chest-pages-and-confirmation-widgets-removed-as-of-700), [item and sound](#native-item-and-sound-apis-as-of-700),
[stored item](#item-descriptions-and-storage-as-of-700), [notification](#notification-service-migration),
[data store](#removed-data-store-overloads-as-of-650), and [security](#class-name-filter-audit-and-no-op-securitypolicy-methods-removed-as-of-700) migrations), please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues).

## Historical excerpt retained for documentation regression tests

`src/test/java/com/ultikits/ultitools/devdocs/UltiCloudDocSnippetsTest.java` reads the heading and table below
verbatim. This historical excerpt does not describe 7.0 behavior. Do not edit or rename the heading or table.

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
