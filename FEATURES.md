# UltiTools-API — Feature Inventory

This document catalogues every operator- or player-visible function, command, content item and
configuration key in this repository, as read directly from source. It is an internal reference
for UAT execution and issue reconciliation — the public description of these features lives on
<https://doc.ultikits.com/>. Update this file in the same pull request as any feature change.

## Conventions

- **ID grammar:** `<repo-slug>.<area>.<action>`, dot-separated, every segment lowercase ASCII
  drawn from `[a-z0-9-]`. `<repo-slug>` is the repository name lowercased with no separators —
  `ultitools` here, `ultichat`, `ultiessentials`, and `ultitools-example` for
  `UltiTools-External-Example`. `<area>` is the feature section's slug. `<action>` is the verb.
  A `config` row is the one shape that exceeds three segments and is exempt from the
  lowercase-ASCII rule for its key-path suffix:
  `<repo-slug>.config.<file-stem>.<yml key path>`, the key path keeping its own dots and its own
  casing verbatim from the yml file (`config.yml` itself uses camelCase for several keys, e.g.
  `maximumPoolSize`) — a config ID is a citation of the key, not a re-derived slug, so lowercasing
  it would make it un-greppable against its own source line. An ID changes only when the feature's
  identity changes, never on rewording. IDs are unique within a
  repository.
- **Kind**, exactly these eight values: `command`, `config`, `event`, `gui`, `scheduled`,
  `placeholder`, `persistence`, `gate`. Each maps one-to-one onto a reconciliation-table line.
- **Tier**, exactly three: `player`, `admin`, `internal`. Judged from what the feature is for,
  not from whether it carries a permission string — most command executors in this repository
  carry one, so judging by the string alone would make nearly everything `admin`.
- **Manual**, exactly three: `detailed`, `brief`, `none`.
- **Target**, exactly four: `player`, `console`, `both`, or `n/a` — the first three read straight
  off `@CmdTarget` for a `command` row; it is a property, not a tier. `n/a` is for every other
  Kind (`config`, `event`, `gate`, `gui`, `persistence`, `scheduled`, `placeholder`) — the concept
  of "who this targets" does not apply to a config key or a background task the way it applies to
  a command.
- **Permission:** the literal node string, `none`, or `n/a`, each optionally suffixed with the
  literal text `(requireOp=true)` (preceded by one space) when the row's class-level
  `@CmdExecutor` carries that flag —
  the suffix augments whichever of the three base values applies; it is not a fourth value, and a
  row without it means its class's `requireOp` is `false` (or the row's Kind has no such class at
  all). `none` alone means no permission-node restriction at all, for anyone —
  `PermissionValidator` treats an empty `permission()` as no check to run, not as an implicit OP
  requirement. OP-only access, where it exists (e.g. every `/ul`/`/upm`/`/ulticloud` command in
  this repository, hence every one of their 18 rows carrying the suffix), comes from the separate
  `@CmdExecutor` class-level `requireOp` flag, a different mechanism entirely — the suffix exists
  so a reader does not have to cross-reference the class declaration to learn it. `n/a` is for
  every Kind that is not `command` — a config key or a scheduled task has no permission node to
  declare in the first place, which is a different fact from a command that declares `none`
  deliberately.
- **Source:** `ClassName#member` — the class and member that actually reads or applies the
  feature — for every Kind, `config` included: all 53 `config` rows below cite the reading
  member (e.g. `JsonStore#initScheduler`, `UltiTools#initDataStore`), not the resource path,
  because the resource path is already stated in the How-to-reach column and does not by itself
  say what code does with the key. `ClassName#member` for a `config` row need not be an
  annotation site — most config keys in this repository are read directly via Bukkit's
  `FileConfiguration`, with no `@ConfigEntity`/`@ConfigEntry` binding at all (see the
  `## Configuration` section's own zero-`@ConfigEntity` note).
- **Row order:** by section, then by ID ascending within the section.
- **No manual prose:** no troubleshooting column, no explanatory paragraphs, no draft page text.
  A hazard noticed while reading becomes a negative checklist row, not a note here.

### Reconciliation command family

The canonical form for counting an annotation site across this repository's real sources:

```bash
find <repo-root> -path '*/src/main/java/*' -name '*.java' -not -path '*/target/*' \
  -not -path '*/.worktrees/*' -print0 | xargs -0 grep -nE '^[[:space:]]*@AnnotationName\b' | wc -l
```

This form defeats three measured traps, each of which produces a wrong-but-plausible number
rather than an error:

1. **Multi-root repositories** — UltiBot's sources live under `ultibot-api/`, `ultibot-core/`
   and `ultibot-v1_21_R1/`, so a naive `<repo>/src/main/java` glob returns 0 for it, silently.
2. **Git worktrees and build output** — UltiEconomy carries
   `.worktrees/economy-v2/src/main/java`, so a `find` without the `-not -path` exclusions above
   reports 48 `@CmdMapping` sites where the real number is 24.
3. **Javadoc and string literals** — requiring the annotation to start its own line (the
   `^[[:space:]]*@` anchor) takes this framework's own `@CmdMapping` count from 46 to 15, and
   drops the two `@CmdExecutor` "hits" at `CommandManager.java` lines 91 and 130, which are
   warning message text, not annotations.

**Positive control:** the line-start form returns `/ul` = 4, `/upm` = 8, `/ulticloud` = 3 for
this repository, matching the number of `@CmdMapping` sites in each class exactly — confirmed by
reading `UltiToolsCommands.java`, `PluginInstallCommands.java` and `CloudLoginCommand.java`
directly, not by trusting the count alone. This document's own row counts diverge from these
annotation-site counts for `/upm` (10 rows) and `/ulticloud` (4 rows) — each divergence is
explained, with its reason, in that command group's own section below.

## /ul — framework administration

`UltiToolsCommands` — class-level `@CmdExecutor(alias = {"ul", "ultitools", "ulti"}, requireOp =
true)`, `@CmdTarget(BOTH)`. `grep -c '@CmdMapping' UltiToolsCommands.java` returns 4 — confirmed
by reading the file directly at lines 30, 41, 65 and 70 (`reload`, `reload <name>`, `help`,
`list`). The `help` site (line 65, `UltiToolsCommands#help`) is counted in that 4 and in the
repository-wide `@CmdMapping` total of 15, but it never actually dispatches:
`BaseCommandExecutor#onCommand` short-circuits a literal `help` argument to `handleGatedHelp` →
`#handleHelp` before `matchMethod` runs at all, exactly the same short-circuit that reaches
`/upm help` and `/ulticloud help` with no `@CmdMapping` site of their own (see those sections
below). The row below therefore cites `#handleHelp`, the method that actually executes, not
`#help` — a live annotation site is not the same fact as a reachable dispatch target.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.ul.help | Print the /ul command usage summary | command | `/ul help` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#handleHelp |
| ultitools.ul.list | List every currently loaded module and its version | command | `/ul list` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#listPlugins |
| ultitools.ul.reload | Reload every loaded module; the framework logs one INFO console line per module reloaded, and any module reload work now runs in that module's `onReload()` hook after the framework's own config/language/drift steps (D-01/D-02/D-03) | command | `/ul reload` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#reloadPlugins |
| ultitools.ul.reload-module | Reload a single named module; the framework logs one INFO console line for that module, and any module reload work now runs in its `onReload()` hook after the framework's own config/language/drift steps (D-01/D-02/D-03) | command | `/ul reload <name>` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#reloadPlugin |
| ultitools.ul.reload-log-line | The framework itself logs exactly one INFO console line naming each module, immediately after that module's own config-reload/language-refresh/drift-report steps and before its `onReload()` hook runs — produced by the framework's own `lang/en.json`/`lang/zh.json` catalogue, never by the module (D-03) | gate | automatic, once per module, during `/ul reload` or `/ul reload <name>` | n/a | n/a | admin | brief | UltiToolsPlugin#reloadSelf |

## /upm — plugin management

`PluginInstallCommands` — class-level `@CmdExecutor(alias = "upm", requireOp = true)`,
`@CmdTarget(BOTH)`. `grep -c '@CmdMapping' PluginInstallCommands.java` returns 8 — confirmed by
reading the file directly (`list <page>`, `list`, `install <plugin> <version>`,
`install <plugin>`, `versions <plugin>`, `uninstall <plugin>`, `check`, `update <plugin>`),
matching D-08's independently stated count exactly. This section carries 10 rows, not 8, for two
reasons, neither an omission. First: `/upm help` (also the bare `/upm` with no arguments) is
reachable and operator-visible, but has no `@CmdMapping` site — `BaseCommandExecutor#onCommand`
short-circuits a `help` argument before format-matching and dispatches straight to
`PluginInstallCommands#handleHelp`, an override with no annotation. `/ul help` is dispatched by
the exact same short-circuit — the presence or absence of a `@CmdMapping(format = "help")` site
on the class (`UltiToolsCommands` has one, `PluginInstallCommands` does not) makes no difference
to which method actually runs, only to whether the annotation-counting instrument sees an extra
site; see the `/ul` section's own note above. The `@CmdMapping` reconciliation line for this
repository stays at 15 regardless — neither of this section's two extra rows is counted against
it, and `UltiToolsCommands#help`'s own site (counted in that 15) is dead code, not a second
dispatch path. Second: `update <plugin>` is one `@CmdMapping` site whose method,
`PluginInstallCommands#updatePlugin`, internally branches on whether the argument is the literal
string `all` — two materially different, independently-observable behaviors
(`ultitools.upm.update` for one named module; `ultitools.upm.update-all` for
`PluginInstallCommands#updateAllPlugins`'s own iteration and partial-failure accounting) sharing
one annotation site, split into two rows here so the checklist can cite a real feature ID for
each rather than inventing a non-negative ID-suffix convention this document does not otherwise
use.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.upm.check | List available framework and module updates | command | `/upm check` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#checkUpdates |
| ultitools.upm.help | Show the `/upm` subcommand help text | command | `/upm help` (also the bare `/upm` with no arguments) | none (requireOp=true) | both | admin | none | PluginInstallCommands#handleHelp |
| ultitools.upm.install | Install the latest version of a plugin from UltiCloud | command | `/upm install <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#installPlugin(plugin) |
| ultitools.upm.install-version | Install a specific version of a plugin from UltiCloud | command | `/upm install <plugin> <version>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#installPlugin(plugin,version) |
| ultitools.upm.list | List page 1 of the plugins available on UltiCloud | command | `/upm list` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#listPlugins(sender) |
| ultitools.upm.list-page | List a chosen page of the plugins available on UltiCloud | command | `/upm list <page>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#listPlugins(sender,page) |
| ultitools.upm.uninstall | Uninstall a plugin and report where its files still live | command | `/upm uninstall <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#uninstallPlugin |
| ultitools.upm.update | Update one named module | command | `/upm update <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#updatePlugin |
| ultitools.upm.update-all | Update every module with an available update in one command, with per-module partial-failure accounting | command | `/upm update all` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#updateAllPlugins |
| ultitools.upm.versions | List every version of a plugin available on UltiCloud | command | `/upm versions <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#listVersions |

## /ulticloud — cloud authentication

`CloudLoginCommand` — class-level `@CmdExecutor(alias = "ulticloud", requireOp = true)`,
`@CmdTarget(CONSOLE)`. `grep -c '@CmdMapping' CloudLoginCommand.java` returns 3 — confirmed by
reading the file directly (`login`, `logout`, `status`), matching D-08's independently stated
count exactly. This section carries 4 rows, not 3, for the same reason as `/upm help` above:
`/ulticloud help` (also the bare `/ulticloud` with no arguments) reaches
`CloudLoginCommand#handleHelp` through `BaseCommandExecutor`'s built-in short-circuit, with no
`@CmdMapping` site of its own — the repository's total `@CmdMapping` count stays 15.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.ulticloud.help | Show the `/ulticloud` subcommand help text | command | `/ulticloud help` (also the bare `/ulticloud` with no arguments) | none (requireOp=true) | console | admin | none | CloudLoginCommand#handleHelp |
| ultitools.ulticloud.login | Request a UltiCloud magic-link login for this server | command | `/ulticloud login` | none (requireOp=true) | console | admin | detailed | CloudLoginCommand#login |
| ultitools.ulticloud.logout | Tear down the cloud connection and clear the saved credential | command | `/ulticloud logout` | none (requireOp=true) | console | admin | brief | CloudLoginCommand#logout |
| ultitools.ulticloud.status | Show whether this server holds a valid UltiCloud authentication token — checks `CloudAuthManager.hasValidToken()` only, never the live WebSocket connection state, so it reports token/authentication status, not whether the panel socket is actually connected | command | `/ulticloud status` | none (requireOp=true) | console | admin | brief | CloudLoginCommand#status |

## Boot sequence and listeners

Non-command operator-visible behaviour driven by the boot sequence (`UltiTools.onEnable()`) and
by the framework's own Bukkit listeners, in scope per D-08. Out of scope, and not listed below:
the developer-facing IoC container, AOP, and ORM internals the boot sequence also wires up —
those are documented on doc.ultikits.com and verified by unit tests, not by a real-machine
session.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.boot.plugin-load-order | Order module loading by declared dependencies (Kahn's topological sort); a cycle or a missing hard dependency excludes only the affected module(s), not the whole load | event | console log during server startup | n/a | n/a | admin | brief | PluginManager#sortPluginsByDependencies |
| ultitools.boot.plugin-load-order-legacy | Opt-in JVM system property that bypasses dependency resolution entirely and loads modules in filesystem order, modeled on Paper's own `-Dpaper.useLegacyPluginLoading=true` precedent | event | `-Dultitools.useLegacyPluginLoading=true` on the server's launch command line | n/a | n/a | internal | brief | PluginManager#sortPluginsByDependencies |
| ultitools.boot.update-check | Check for a newer framework version and newer module versions once, asynchronously, shortly after startup | event | console log during server startup | n/a | n/a | admin | none | UpdateManager#checkUpdatesSync |
| ultitools.listener.placeholderapi-bridge | On a player's first join needing an unregistered PlaceholderAPI expansion, download and reload it automatically | event | join the server as any player while PlaceholderAPI is installed | n/a | n/a | internal | none | PlayerJoinListener#onPlayerJoin |
| ultitools.listener.update-notify | Notify an OP player once per connection, on join, if a framework or module update is available. `UpdateJoinListener`'s own javadoc claims "once per server session", but `PlayerCacheManager#onPlayerQuit` clears the backing `@PlayerCache` set on every quit, so a quit and rejoin re-sends the notification within the same session — a known product defect (UltiKits/UltiTools-Reborn#431), not the intended behaviour | event | join the server as an OP player after ultitools.boot.update-check has found an update | n/a | n/a | admin | none | UpdateJoinListener#onPlayerJoin |

**Reconciliation note (D-07):** the line-start form of the canonical command reports exactly one
`@EventListener` site in this repository (`PlayerJoinListener`) against five `event`-Kind rows
above. This is a deliberate, explained mismatch, not an omission. Two of the five
(`ultitools.listener.placeholderapi-bridge`, `ultitools.listener.update-notify`) are Bukkit join
listeners: `PlayerJoinListener` carries `@EventListener`, but the core framework's own bootstrap
never runs `ComponentScanner`/`ListenerManager` over itself — only a per-plugin
`@UltiToolsModule`'s declared `scanBasePackages` are scanned — so the annotation is not what
causes registration here. Both `PlayerJoinListener` and `UpdateJoinListener` are registered the
same way: a direct `Bukkit.getPluginManager().registerEvents(...)` call inside
`UltiTools.onEnable()` / `scheduleStartupMessages()`. The other three
(`ultitools.boot.plugin-load-order`, `ultitools.boot.plugin-load-order-legacy`,
`ultitools.boot.update-check`) are not driven by any annotation at all and reconcile against
nothing, because module load-order (in either its resolved or legacy-bypass form) and the update
check have no annotation-based instrument in this codebase.

## Economy

New in this section (D-08/D-09, #451, 6.3.0): before this, a module requesting the economy on a
server with no Vault plugin — or with Vault present but no economy provider registered — crashed
the entire framework at boot, before the request itself could even fail. Kept `event`-Kind and
placed in its own section rather than folded into "Boot sequence and listeners" above, since
neither row here is driven by a Bukkit event or an `@Scheduled`/`@EventListener` annotation the
way every row in that section's own reconciliation note accounts for — both are triggered by an
`EconomyUtils` call from module code, not by anything Bukkit dispatches.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.economy.report-startup-state | Log one line at framework start naming the current economy service state — Vault not installed, Vault installed but no provider registered, or hooked into Vault naming the registered provider | event | console log during server startup | n/a | n/a | admin | none | EconomyUtils#logStartupState |
| ultitools.economy.report-unavailable | On a module's first economy request while unavailable, log one WARNING per calling module per server session naming the module, distinguishing "Vault is not installed" from "Vault is installed but no provider is registered", stating the condition is the server's environment rather than a framework or module defect, and giving the install instruction. A request whose calling module cannot be attributed is still logged once, as an unknown caller | event | any module calls an `EconomyUtils` operation (`getBalance`, `has`, `deposit`, `withdraw`, `format`, `getCurrencyName`, `getCurrencyNamePlural`) while Vault is absent or has no registered provider | n/a | n/a | admin | none | EconomyUtils#reportEconomyStateIfUnavailable |

## Scheduled tasks

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.task.player-cache-sweep | Periodically evict expired `@PlayerCache`-backed state on a five-minute clock, independent of any player quitting — but only for a bean that opts in by implementing `PlayerCacheManager.ExpiringPlayerCache`; an ordinary `@PlayerCache` field on a bean that does not implement it is never touched by this task | scheduled | runs automatically every 5 minutes while the server is up | n/a | n/a | internal | none | PlayerCacheManager#sweepExpiredEntries |

**Reconciliation note:** the line-start form of the canonical command reports exactly one
`@Scheduled` site in this repository, and this is that one row — the count balances exactly.

## Data persistence

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.storage.backend-select | Choose the ORM storage backend (`json`, `sqlite`, or `mysql`) via `config.yml`; falls back to `json` if the configured backend is unavailable | persistence | `datasource.type` in `plugins/UltiTools/config.yml`, applied only on a full server restart — `/ul reload` (`UltiTools#reloadPlugins`) reloads config, language, and modules but never re-runs `initDataStore`, so the active data store is unchanged until restart | n/a | n/a | admin | brief | UltiTools#initDataStore |
| ultitools.storage.restart-survival | Data written through a `DataOperator` survives a full server restart, in whichever backend is active | persistence | write data via any module command backed by `@Table`, then restart the server | n/a | n/a | admin | none | DataStoreManager#getDatastore |

## Language

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.language.file-preserve | An `UltiToolsPlugin` module's extracted `lang/<code><ext>` file that has been customised since extraction (its bytes no longer match the recorded provenance hash, or no provenance was ever recorded and its bytes differ from the jar's) is never overwritten; only the individual keys whose `String.format` placeholder arity (distinct argument positions, `%s`/`%d`-style) differs from the jar's bundled value for that key are resolved from the jar instead, each logged once as a WARN naming the module, file and key (D-05/D-06, #441) | gate | any loaded module's `plugins/UltiTools/pluginConfig/<module>/lang/<code><ext>` file, hand-edited after extraction, with the module jar later shipping a changed placeholder count for one of the edited keys — observed on the module's next start | n/a | n/a | admin | brief | UltiToolsPlugin#applyPlaceholderArityOverride |
| ultitools.language.file-refresh | An `UltiToolsPlugin` module's extracted `lang/<code><ext>` file that has never been modified since extraction (its bytes still match the recorded provenance hash, or no provenance was recorded but its bytes already equal the jar's) is silently replaced by the current module jar's bundled copy on the next start, with one informative log line naming the file (D-05/D-06/D-07, #441) | gate | any loaded module's `plugins/UltiTools/pluginConfig/<module>/lang/<code><ext>` file, left untouched since extraction, with the module jar upgraded to a version shipping different `lang/<code><ext>` content — observed on the module's next start | n/a | n/a | admin | brief | UltiToolsPlugin#resolveLanguageWithProvenance |
| ultitools.language.select | Choose the framework's message language (`zh` or `en`) via `config.yml`, applied on next start or `/ul reload` | gate | `language` in `plugins/UltiTools/config.yml`, applied on next start or `/ul reload` — unlike `datasource.type` above, `UltiTools#reloadPlugins` runs `reloadConfig()` then `initLanguage()`, so a reload alone is sufficient | n/a | n/a | admin | brief | UltiTools#initLanguage |

## Panel capabilities

Eight independently-switchable panel-facing capabilities, each with its own
`ultipanel.capabilities.*` key in `config.yml` and its own shipped default (D-08). Every gate
funnels through the same `Capability#isEnabled()` accessor.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.capability.commands | Allow the panel to execute server commands remotely; ships disabled | gate | `ultipanel.capabilities.commands` in config.yml | n/a | n/a | admin | brief | Capability#COMMANDS |
| ultitools.capability.file-delete | Allow the panel to delete files or directories within the editable roots; ships disabled | gate | `ultipanel.capabilities.file-delete` in config.yml | n/a | n/a | admin | brief | Capability#FILE_DELETE |
| ultitools.capability.file-read | Allow the panel to read and list files within the editable roots; ships enabled | gate | `ultipanel.capabilities.file-read` in config.yml | n/a | n/a | admin | brief | Capability#FILE_READ |
| ultitools.capability.file-write | Allow the panel to write or upload files within the editable roots; ships disabled | gate | `ultipanel.capabilities.file-write` in config.yml | n/a | n/a | admin | brief | Capability#FILE_WRITE |
| ultitools.capability.logs | Allow the panel to stream and control the live console log; ships enabled | gate | `ultipanel.capabilities.logs` in config.yml | n/a | n/a | admin | brief | Capability#LOGS |
| ultitools.capability.monitoring | Allow the panel to receive live TPS/memory/world/player monitoring data — the panel's only "server is alive" signal; ships enabled | gate | `ultipanel.capabilities.monitoring` in config.yml | n/a | n/a | admin | detailed | Capability#MONITORING |
| ultitools.capability.player-events | Allow the panel to receive live player events — join, quit, chat, death, kick, command preprocessing, and world change, seven distinct `PlayerEventManager` handlers each with their own `event_type` value and payload shape, not just join/quit/chat; ships enabled | gate | `ultipanel.capabilities.player-events` in config.yml | n/a | n/a | admin | brief | Capability#PLAYER_EVENTS |
| ultitools.capability.server-properties | Allow the panel to read and edit the `server.properties` safe-key whitelist; ships disabled | gate | `ultipanel.capabilities.server-properties` in config.yml | n/a | n/a | admin | brief | Capability#SERVER_PROPERTIES |

## Live log stream controls

Three panel-facing controls over the `log_stream`/`config` WebSocket actions, gated by the
`ultipanel.capabilities.logs` capability above. All three were declared controls that reported
acceptance and changed nothing before this pull request; each is now proven by a test that fails
when its fix is removed (#432, #433, #434).

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.remote.log-stream-pause | The `log_stream` action `pause`/`resume` is rejected outright, not implemented: the delivery messages (`log_stream`/`log_batch`) carry no per-viewer address, so the framework cannot honour a per-viewer pause without server-side viewer identity it does not have; the response states that pausing the live view is the panel view's own action and that the server keeps streaming to every subscribed client regardless (#434 closed — the declaration is removed rather than made to work; D-19). The public `LogStreamManager#pauseLogStream(String)`/`#resumeLogStream(String)` methods were removed in the same change — see `COMPATIBILITY.md` | gate | `log_stream` panel message with `action: "pause"`/`"resume"` | n/a | n/a | admin | brief | LogStreamManager#handleLogStreamMessage |
| ultitools.remote.log-stream-levels | The `config` action's `levels` list actually filters which log levels are delivered, instead of only logging that a request was received; an unrecognised level name is rejected naming the value, with the previously-applied levels left unchanged (#433 fixed). Enabling `"debug"` also lowers `SystemLogHandler`'s own `java.util.logging` level floor (was stuck at `Level.INFO`, rejecting `FINE`/`FINER`/`FINEST` records before the levels check ever ran) so debug-level records are actually reachable, not just nominally accepted (Gate-2 finding, fixed) | gate | `log_stream` panel message with `action: "config"`, field `levels` (array of `info`/`warning`/`error`/`debug`) | n/a | n/a | admin | brief | LogStreamManager#handleConfigUpdate |
| ultitools.remote.log-stream-batch-interval | The `config` action's `batchConfig.interval` field actually reschedules the running batch sender to the new interval, instead of only mutating a field the already-running scheduled task never re-reads (#432 fixed, both halves — see the `ultipanel.logging.batch.interval` config row). Honoured to within one second even when `ultipanel.capabilities.monitoring` is enabled (the shipped default): `ServerMonitorManager`'s own independent 5-second `batch_update` tick used to drain the queue regardless of the configured interval, making the panel action's effect invisible in the default configuration; a first fix gated the drain by the configured interval but the check itself still only ran on that same 5-second tick, quantizing any accepted interval up to a multiple of 5 seconds (1000ms drained no faster than every 5s; 7000ms drained roughly every 10s) — a second, independently-scheduled 1-second check (`ServerMonitorManager#maybeSendLogsOnly`, sharing the same interval gate) now drains and sends a logs-only `batch_update` as soon as the configured interval elapses, honoured to within one second rather than five (Gate-2 findings, fixed). `batchConfig.size` below 1 is now rejected (previously silently stalled delivery — a size ≤ 0 made every enqueue trigger an immediate no-op send), and disabling batching (`batchConfig.enabled: false`) now flushes whatever is already queued first instead of stranding it until batching is re-enabled (both Gate-2 findings, fixed). A `levels` field present but not a JSON array is now rejected outright rather than silently ignored while an accompanying `batchConfig` still applied (Gate-2 finding, fixed) | gate | `log_stream` panel message with `action: "config"`, field `batchConfig.interval`/`batchConfig.size`/`batchConfig.enabled` | n/a | n/a | admin | brief | UltiPanelLogTransmitter#setIntervalMs, ServerMonitorManager#sendBatchUpdate, ServerMonitorManager#maybeSendLogsOnly |
| ultitools.remote.error-auto-report | Automatic `ErrorReportCollector` reporting of `SEVERE`-with-`Throwable` log records to UltiPanel is independent of the panel's own live `log_stream` view: excluding `"error"` from the `levels` filter (or the stream being paused, when pause is later supported) suppresses only what the live view shows, never the separate automatic error-reporting pipeline — the two are declared as distinct surfaces (CR-01/CR-02 from this phase's own review, fixed as part of making the `levels` filter genuinely effective) | gate | any `SEVERE` log record carrying a `Throwable`, regardless of the panel's `levels` configuration | n/a | n/a | admin | none | SystemLogHandler#publish |
| ultitools.remote.log-stream-start-stop | Addendum (D-20, issue #468): the `log_stream` action `start`/`stop` is rejected outright, the same as `pause`/`resume` above — measured end to end, the shipped frontend only ever toggles `start`/`stop` from its own button without gating rendering on the response, the Worker's REST log-stream endpoint is a stateless relay minting a disposable `clientId` per call with no per-browser stream state, and this framework's own `stopLogStream` mutated only bookkeeping nothing on the delivery path ever consulted — `stop` never actually stopped delivery for any real client, on any released version. The public `LogStreamManager#startLogStream(String, String)`/`#startLogStream(String)`/`#stopLogStream(String)`/`#isStreaming()`/`#getSubscriberCount()` methods were removed in the same change — see `COMPATIBILITY.md` | gate | `log_stream` panel message with `action: "start"`/`"stop"` | n/a | n/a | admin | brief | LogStreamManager#handleLogStreamMessage |
| ultitools.remote.log-stream-status | Addendum (D-20, issue #468): the `log_stream` action `status` is read-only introspection, not a control toggle, so it is answered honestly rather than rejected — the response's `subscriberCount`/`streaming` fields (no longer backed by any bookkeeping) are replaced with a `connected` field reporting whether this server's WebSocket connection to the panel is currently up (the same fact `streaming` used to gesture at); `logTransmitterEnabled`/`queueSize` are unchanged, already backed by genuine `UltiPanelLogTransmitter` state | gate | `log_stream` panel message with `action: "status"` | n/a | n/a | admin | brief | LogStreamManager#handleLogStreamMessage |

## Panel upload and metrics honesty

Three declared panel-facing surfaces that answered `success` or a confident number without
actually doing the thing they declared (#435, #436, #437, D-13/D-14). Each is now proven by a
test that fails when its fix is removed.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.remote.upload-config | `upload_config` accepts `plugin_config` only. A `server_properties` `configType` is rejected naming the dedicated `server_properties` message that actually handles server-properties writes; a `permissions` `configType` is rejected as unsupported — nothing in the system defines that type's semantics. Both previously logged at a hidden `FINE` level and answered `success` for a request that did nothing (#435 fixed); the unreachable `requestId`-gated branch this handler carried is also removed, since no sender anywhere in the system — panel, frontend, or Worker route — ever populates that field on this message type (#359 fixed) | gate | `upload_config` panel message, field `configType` | n/a | n/a | admin | brief | PluginInitiationUtils#handleConfigUploadLogic |
| ultitools.remote.disk-usage | `metrics_data`'s `serverPerformance.diskUsage` is the real used percentage of the filesystem holding the server root, following `df`'s own `Use%` convention (`used = total - free` via `File#getFreeSpace`, includes filesystem-reserved space; `percentage = used / (used + avail)` via `File#getUsableSpace`, excludes it) rather than a straight `(total - usable) / total`, rounded to two decimals like the neighbouring `memoryUsage`, instead of a hardcoded `0.0` (#436 fixed; formula corrected to match `df` under WR-03 — the earlier `(total - usable) / total` formula measured roughly one point higher than `df` on a real ext4 volume with its default reserved-block allocation) | gate | `metrics_data` panel message, or observe `batch_update`'s `serverPerformance.diskUsage` | n/a | n/a | admin | brief | ServerMonitorManager#computeDiskUsage |
| ultitools.remote.enabled-plugins | `metrics_data`'s `pluginUsage.enabledPlugins` counts only plugins whose `enabled` flag is set, instead of every plugin installed regardless of whether it actually enabled (#437 fixed) | gate | `metrics_data` panel message, or observe `batch_update`'s `pluginUsage.enabledPlugins` | n/a | n/a | admin | brief | ServerMonitorManager#countEnabledPlugins |

## Remote surface guards

Guards enforced independently of, and in addition to, the capability switches above — a
capability being on does not itself grant access to a blocked command, a non-editable path, or an
unlisted `server.properties` key.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.remote.command-blocklist | Refuse a remote command whose base name (namespace prefix stripped first) is on the operator-editable blocklist; ships with 10 dangerous commands blocked (`op`, `deop`, `stop`, `restart`, `reload`, `ban-ip`, `pardon-ip`, `whitelist`, `save-off`, `save-all`) | gate | `ultipanel.commands.blocklist` in config.yml | n/a | n/a | admin | detailed | CommandExecutionManager#isCommandAllowed |
| ultitools.remote.file-editable-roots | Restrict panel file access to an operator-configured set of root directories (ships as `plugins`, `logs` only), with credential-bearing files and dangerous extensions unconditionally protected regardless of root | gate | `ultipanel.files.editable-roots` in config.yml | n/a | n/a | admin | detailed | FileOperationManager#isPathAllowed |
| ultitools.remote.server-properties-safe-keys | Refuse to write any `server.properties` key not on the fixed safe-key whitelist (`motd`, `max-players`, `view-distance`, `simulation-distance`, `spawn-protection`, `difficulty`, `gamemode`, `pvp`, `allow-nether`, `allow-flight`, `spawn-animals`, `spawn-monsters`, `spawn-npcs`, `enable-command-block`) — a ceiling across every Paper version this framework supports, not a per-server promise. A write to a whitelisted key the RUNNING server's own `server.properties` does not have is also refused, with a reason distinguishing it from "not whitelisted" and "no properties file"; the key was previously written and answered `success` regardless of whether Paper ever reads it back (D-15, SAFE_KEYS issue, fixed). The batch `set_all` path reports the same distinction via its own `notPresentOnServer` response array, kept separate from `failed` (a genuine read/write I/O error) rather than collapsed into it (Gate-2 finding, fixed) | gate | panel `server.properties` edit, or read `ServerPropertiesManager` source directly | n/a | n/a | admin | brief | ServerPropertiesManager#setProperty |
| ultitools.remote.server-properties-safe-keys-read | Return only the same fixed safe-key whitelist's current values, never the full `server.properties` file — a distinct code path (`handleGet`) from the write guard above, not just its mirror image | gate | `server_properties` panel message with `action: "get"` (or omitted, `get` is the default) | n/a | n/a | admin | brief | ServerPropertiesManager#handleGet |

## Configuration

Every leaf key in `src/main/resources/config.yml` (47 keys, counted with
`grep -nE '^[[:space:]]*[a-zA-Z][a-zA-Z0-9_-]*:[[:space:]]*[^[:space:]#]' src/main/resources/config.yml | wc -l`
— 44 plus the three `ultipanel.logging.batch.*` keys added in this same pull request, see the
three rows below —, the reconciliation table's counting command for this Kind) plus the two
operator-relevant keys in `src/main/resources/env.yml` (`api-url` and `version`). This section
catalogues the configuration surface exhaustively at
key granularity; several of these keys already have a behavioural row elsewhere in this document
(storage backend, language, the eight panel capabilities) — that row documents the *feature* the
key drives, this row documents the *key* itself, and both are kept so the reconciliation table
can prove every key is accounted for without also making every behavioural row carry a `config`
Kind. The framework has zero `@ConfigEntity` classes: its own operator configuration is this
shipped `config.yml`, read directly by `Bukkit`'s `FileConfiguration`, not a bound
`@ConfigEntity` entity — so the `@ConfigEntity` reconciliation line in the pull request reads 0
against 0, with this sentence as its reason, rather than being omitted.

This section carries 53 rows total (51 `ultitools.config.config.*` rows for `config.yml`, plus
`ultitools.config.env.api-url` and `ultitools.config.env.version` for `env.yml`), not the 47 the
counting command above measures, and that divergence has a reason rather than being an omission —
the counting command only sees `src/main/resources/config.yml`, the shipped default resource; it
cannot see a key the framework recognises but does not ship a default for, and it was never meant
to count `env.yml` at all (that is a second, separate file, resolved at build time from Maven
properties rather than being a shipped default, with its own two-key row set). Four keys are genuinely
read by production code (`SystemLogHandler`, `CommandExecutionManager`,
`FileOperationManager`, confirmed by reading each) but are absent from the `config.yml` resource:
`ultipanel.commands.blocklist` and `ultipanel.files.editable-roots` are migrated onto disk on
first boot if absent (`UltiTools#migrateKeyIfAbsent`), so a running server always has them even
though the packaged jar's default resource does not; `ultipanel.logging.levels` and
`ultipanel.logging.excluded-loggers` are purely
opt-in — read via `FileConfiguration#contains` with a code-level fallback, present only if the
operator adds them by hand per `config-example.yml`. The three `ultipanel.logging.batch.*` keys
were in this same "opt-in, absent from the shipped resource" group before this pull request;
they are now genuinely shipped in `config.yml` (see the three rows below), so they have moved out
of this enumeration. **Upgrade behaviour for an existing installation (measured, not assumed):**
`UltiTools#getConfig()` is Bukkit's own `FileConfiguration` for this plugin, loaded from whatever
`plugins/UltiTools/config.yml` already exists on disk with no default-key merge step — unlike the
two `migrateKeyIfAbsent` keys above, nothing writes the new `batch` block onto an operator's
existing file. An operator upgrading from a jar built before this pull request keeps a
`config.yml` with no `ultipanel.logging.batch` block at all; `LogStreamManager#loadBatchConfiguration`'s
`FileConfiguration#contains` guards see it as absent and skip applying anything, so
`UltiPanelLogTransmitter`'s own in-code defaults apply instead (batching enabled, batch size 10,
interval 5000ms) — which are the same three values this pull request's shipped defaults use, so
there is no behavioural change for that operator. The new key is invisible until they either add
it to their existing file by hand or delete it and let the framework re-extract the packaged
default. The reconciliation-table verify command's own `keys=47 rows=51`
output (measuring `config.yml` alone, its own stated scope) is therefore the accurate, intentional
result of this reading, not a defect the row count should be forced to match; the section's true
total including both `env.yml` keys is 53.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.config.config.datasource.flushRate | JSON-backend auto-save interval; lower values reduce data-loss risk at the cost of more disk I/O; only takes effect for the JSON storage backend | config | `src/main/resources/config.yml: datasource.flushRate (default: 10)` | n/a | n/a | admin | none | JsonStore#initScheduler |
| ultitools.config.config.datasource.type | Select the ORM storage backend (`json`, `sqlite`, or `mysql`); data does not carry over between backends, so switching loses data | config | `src/main/resources/config.yml: datasource.type (default: sqlite)` | n/a | n/a | admin | brief | UltiTools#initDataStore |
| ultitools.config.config.email.debug | Log the full SMTP protocol exchange to console for troubleshooting | config | `src/main/resources/config.yml: email.debug (default: false)` | n/a | n/a | admin | none | DefaultEmailService#initializeSession |
| ultitools.config.config.email.enable | Enable the optional `EmailService`; modules must call `EmailService#isEnabled()` before relying on it | config | `src/main/resources/config.yml: email.enable (default: false)` | n/a | n/a | admin | brief | DefaultEmailService#isEnabled |
| ultitools.config.config.email.from.address | Sender address stamped on outgoing mail; should match `email.smtp.username` or an authorized alias | config | `src/main/resources/config.yml: email.from.address (default: "noreply@example.com")` | n/a | n/a | admin | brief | DefaultEmailService#sendMailInternal |
| ultitools.config.config.email.from.name | Sender display name stamped on outgoing mail | config | `src/main/resources/config.yml: email.from.name (default: "UltiTools Server")` | n/a | n/a | admin | none | DefaultEmailService#sendMailInternal |
| ultitools.config.config.email.smtp.connectionTimeout | SMTP connection timeout, in milliseconds | config | `src/main/resources/config.yml: email.smtp.connectionTimeout (default: 10000)` | n/a | n/a | admin | none | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.host | SMTP server host (e.g. `smtp.gmail.com`, `smtp.qq.com`, `smtp.163.com`) | config | `src/main/resources/config.yml: email.smtp.host (default: "smtp.example.com")` | n/a | n/a | admin | brief | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.password | SMTP authentication password; some providers require an app-specific authorization code rather than the account password | config | `src/main/resources/config.yml: email.smtp.password (default: "")` | n/a | n/a | admin | brief | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.port | SMTP port (common values: 25, 465 for SSL, 587 for STARTTLS) | config | `src/main/resources/config.yml: email.smtp.port (default: 587)` | n/a | n/a | admin | brief | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.readTimeout | SMTP read timeout, in milliseconds | config | `src/main/resources/config.yml: email.smtp.readTimeout (default: 10000)` | n/a | n/a | admin | none | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.ssl | Use SSL encryption for the SMTP connection; typically `true` when the port is 465 | config | `src/main/resources/config.yml: email.smtp.ssl (default: false)` | n/a | n/a | admin | brief | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.starttls | Use STARTTLS encryption for the SMTP connection; typically `true` when the port is 587 | config | `src/main/resources/config.yml: email.smtp.starttls (default: true)` | n/a | n/a | admin | brief | DefaultEmailService#initializeSession |
| ultitools.config.config.email.smtp.username | SMTP authentication username, usually the mailbox address | config | `src/main/resources/config.yml: email.smtp.username (default: "")` | n/a | n/a | admin | brief | DefaultEmailService#initializeSession |
| ultitools.config.config.language | Framework message language (`en` or `zh`), applied on next start or `/ul reload` | config | `src/main/resources/config.yml: language (default: zh)` | n/a | n/a | admin | brief | UltiTools#initLanguage |
| ultitools.config.config.mysql.cachePrepStmts | HikariCP/MySQL JDBC driver prepared-statement caching switch | config | `src/main/resources/config.yml: mysql.cachePrepStmts (default: true)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.connectionTestQuery | Query HikariCP runs to validate a pooled MySQL connection before handing it out | config | `src/main/resources/config.yml: mysql.connectionTestQuery (default: "select 1")` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.connectionTimeout | HikariCP connection-acquisition timeout, in milliseconds | config | `src/main/resources/config.yml: mysql.connectionTimeout (default: 30000)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.database | MySQL database/schema name; must already exist, the framework does not create it | config | `src/main/resources/config.yml: mysql.database (default: ultitools)` | n/a | n/a | admin | brief | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.enable | Enable the MySQL storage backend (also requires `datasource.type: mysql`) | config | `src/main/resources/config.yml: mysql.enable (default: false)` | n/a | n/a | admin | brief | UltiTools#initDataStore |
| ultitools.config.config.mysql.host | MySQL server host | config | `src/main/resources/config.yml: mysql.host (default: localhost)` | n/a | n/a | admin | brief | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.keepaliveTime | HikariCP keepalive interval for idle pooled connections, in milliseconds | config | `src/main/resources/config.yml: mysql.keepaliveTime (default: 60000)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.maxLifetime | HikariCP maximum lifetime of a pooled connection, in milliseconds | config | `src/main/resources/config.yml: mysql.maxLifetime (default: 1800000)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.maximumPoolSize | HikariCP maximum MySQL connection-pool size | config | `src/main/resources/config.yml: mysql.maximumPoolSize (default: 8)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.password | MySQL authentication password | config | `src/main/resources/config.yml: mysql.password (default: password)` | n/a | n/a | admin | brief | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.port | MySQL server port | config | `src/main/resources/config.yml: mysql.port (default: 3306)` | n/a | n/a | admin | brief | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.prepStmtCacheSize | HikariCP/MySQL JDBC driver prepared-statement cache size | config | `src/main/resources/config.yml: mysql.prepStmtCacheSize (default: 250)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.prepStmtCacheSqlLimit | HikariCP/MySQL JDBC driver maximum SQL length eligible for prepared-statement caching | config | `src/main/resources/config.yml: mysql.prepStmtCacheSqlLimit (default: 2048)` | n/a | n/a | admin | none | MysqlConfig#MysqlConfig |
| ultitools.config.config.mysql.username | MySQL authentication username | config | `src/main/resources/config.yml: mysql.username (default: root)` | n/a | n/a | admin | brief | MysqlConfig#MysqlConfig |
| ultitools.config.config.ultipanel.capabilities.commands | Whether the panel may execute server commands remotely; ships disabled | config | `src/main/resources/config.yml: ultipanel.capabilities.commands (default: false)` | n/a | n/a | admin | brief | Capability#COMMANDS |
| ultitools.config.config.ultipanel.capabilities.file-delete | Whether the panel may delete files or directories within the editable roots; ships disabled | config | `src/main/resources/config.yml: ultipanel.capabilities.file-delete (default: false)` | n/a | n/a | admin | brief | Capability#FILE_DELETE |
| ultitools.config.config.ultipanel.capabilities.file-read | Whether the panel may read and list files within the editable roots; ships enabled | config | `src/main/resources/config.yml: ultipanel.capabilities.file-read (default: true)` | n/a | n/a | admin | brief | Capability#FILE_READ |
| ultitools.config.config.ultipanel.capabilities.file-write | Whether the panel may write or upload files within the editable roots; ships disabled | config | `src/main/resources/config.yml: ultipanel.capabilities.file-write (default: false)` | n/a | n/a | admin | brief | Capability#FILE_WRITE |
| ultitools.config.config.ultipanel.capabilities.logs | Whether the panel may stream and control the live console log; ships enabled | config | `src/main/resources/config.yml: ultipanel.capabilities.logs (default: true)` | n/a | n/a | admin | brief | Capability#LOGS |
| ultitools.config.config.ultipanel.capabilities.monitoring | Whether the panel may receive live TPS/memory/world/player monitoring data; ships enabled | config | `src/main/resources/config.yml: ultipanel.capabilities.monitoring (default: true)` | n/a | n/a | admin | brief | Capability#MONITORING |
| ultitools.config.config.ultipanel.capabilities.player-events | Whether the panel may receive live player join/quit/chat events; ships enabled | config | `src/main/resources/config.yml: ultipanel.capabilities.player-events (default: true)` | n/a | n/a | admin | brief | Capability#PLAYER_EVENTS |
| ultitools.config.config.ultipanel.capabilities.server-properties | Whether the panel may read and edit the `server.properties` safe-key whitelist; ships disabled | config | `src/main/resources/config.yml: ultipanel.capabilities.server-properties (default: false)` | n/a | n/a | admin | brief | Capability#SERVER_PROPERTIES |
| ultitools.config.config.ultipanel.commands.blocklist | Remote command blocklist, fully operator-editable in both directions; not present in the shipped default resource but migrated onto disk on first boot if absent (`UltiTools#migrateKeyIfAbsent`), so a running server always has this key even though `src/main/resources/config.yml` does not ship it | config | `plugins/UltiTools/config.yml: ultipanel.commands.blocklist (default: [op, deop, stop, restart, reload, ban-ip, pardon-ip, whitelist, save-off, save-all])` | n/a | n/a | admin | detailed | CommandExecutionManager#isCommandAllowed |
| ultitools.config.config.ultipanel.files.editable-roots | Root directories (relative to the server root) the panel's file capabilities are confined to; not present in the shipped default resource but migrated onto disk on first boot if absent, same as the blocklist above | config | `plugins/UltiTools/config.yml: ultipanel.files.editable-roots (default: [plugins, logs])` | n/a | n/a | admin | detailed | FileOperationManager#isPathAllowed |
| ultitools.config.config.ultipanel.logging.action-log.max-files | Number of rotated `action.log.<generation>` files retained; there is no key to disable the log itself | config | `src/main/resources/config.yml: ultipanel.logging.action-log.max-files (default: 5)` | n/a | n/a | admin | none | RemoteActionLog#loadConfiguration |
| ultitools.config.config.ultipanel.logging.action-log.max-size-bytes | Rotation size, in bytes, for the active `action.log.0` file before it rolls to the next generation | config | `src/main/resources/config.yml: ultipanel.logging.action-log.max-size-bytes (default: 1048576)` | n/a | n/a | admin | none | RemoteActionLog#loadConfiguration |
| ultitools.config.config.ultipanel.logging.batch.enabled | Whether log-stream delivery to the panel is batched rather than sent line-by-line; disabling actually stops the scheduled batch-send task, not merely the code path that feeds it (#432 fixed) | config | `src/main/resources/config.yml: ultipanel.logging.batch.enabled (default: true)` | n/a | n/a | admin | none | UltiPanelLogTransmitter#setBatchEnabled |
| ultitools.config.config.ultipanel.logging.batch.size | Entries per batch when batched log delivery is enabled, read once at server start | config | `src/main/resources/config.yml: ultipanel.logging.batch.size (default: 10)` | n/a | n/a | admin | none | LogStreamManager#loadBatchConfiguration |
| ultitools.config.config.ultipanel.logging.batch.interval | Milliseconds between scheduled batch sends. Fixed: setting it now actually reschedules the already-running sender rather than being baked into the scheduler at construction and silently ignored thereafter (#432, both halves — the key's own absence from the shipped resource, and `setIntervalMs` not rescheduling — fixed together in the same pull request) | config | `src/main/resources/config.yml: ultipanel.logging.batch.interval (default: 5000)` | n/a | n/a | admin | none | UltiPanelLogTransmitter#setIntervalMs |
| ultitools.config.config.ultipanel.logging.error-reporting.dedup-window-seconds | Time window, in seconds, within which a repeat of the same error fingerprint is not re-reported | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.dedup-window-seconds (default: 300)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.enabled | Whether errors are auto-reported to UltiPanel | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.enabled (default: true)` | n/a | n/a | admin | brief | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.max-errors-per-batch | Maximum number of error reports sent per `batch_update` cycle | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.max-errors-per-batch (default: 10)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.sample-after-count | Number of cumulative occurrences of the same error fingerprint after which sampling begins | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.sample-after-count (default: 10)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.sample-rate | Fraction of a sampled error's repeat occurrences that are still reported (`0.1` = 10%) | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.sample-rate (default: 0.1)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.excluded-loggers | Logger name prefixes excluded from the log stream sent to the panel, to avoid transmitting excessive volume; purely opt-in, same as `batch.enabled` above | config | `config-example.yml: ultipanel.logging.excluded-loggers (default when present: [com.mojang.authlib, net.minecraft.network, org.apache.http, com.zaxxer.hikari, org.eclipse.jetty])` | n/a | n/a | admin | none | SystemLogHandler#loadConfiguration |
| ultitools.config.config.ultipanel.logging.levels | Log levels transmitted to the panel's live log stream; purely opt-in, same as `batch.enabled` above | config | `config-example.yml: ultipanel.logging.levels (default when present: [info, warning, error])` | n/a | n/a | admin | none | SystemLogHandler#loadConfiguration |
| ultitools.config.env.api-url | UltiCloud API base URL; Maven-filtered at build time from the `ultitools.api.url` property, not editable at runtime by the server operator | config | `src/main/resources/env.yml: api-url (default: https://api.ultikits.com, from pom.xml property ultitools.api.url)` | n/a | n/a | admin | none | UltiTools#getEnv |
| ultitools.config.env.version | The framework's own version string, used to gate version-dependent behaviour and drive the update check's "current version" comparison; Maven-filtered at build time from `${project.version}`, not editable at runtime | config | `src/main/resources/env.yml: version (default: the pom.xml project version at build time, e.g. 6.3.0-SNAPSHOT)` | n/a | n/a | admin | brief | UpdateManager#checkFrameworkUpdate (reads `UltiTools.getEnv().getString("version")`; `UltiTools#getPluginVersion` returns the parsed `int` used only by the module-compatibility gate, a different member entirely) |
