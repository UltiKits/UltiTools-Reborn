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
| ultitools.ul.reload | Reload every loaded module | command | `/ul reload` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#reloadPlugins |
| ultitools.ul.reload-module | Reload a single named module | command | `/ul reload <name>` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#reloadPlugin |

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

## Remote surface guards

Guards enforced independently of, and in addition to, the capability switches above — a
capability being on does not itself grant access to a blocked command, a non-editable path, or an
unlisted `server.properties` key.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.remote.command-blocklist | Refuse a remote command whose base name (namespace prefix stripped first) is on the operator-editable blocklist; ships with 10 dangerous commands blocked (`op`, `deop`, `stop`, `restart`, `reload`, `ban-ip`, `pardon-ip`, `whitelist`, `save-off`, `save-all`) | gate | `ultipanel.commands.blocklist` in config.yml | n/a | n/a | admin | detailed | CommandExecutionManager#isCommandAllowed |
| ultitools.remote.file-editable-roots | Restrict panel file access to an operator-configured set of root directories (ships as `plugins`, `logs` only), with credential-bearing files and dangerous extensions unconditionally protected regardless of root | gate | `ultipanel.files.editable-roots` in config.yml | n/a | n/a | admin | detailed | FileOperationManager#isPathAllowed |
| ultitools.remote.server-properties-safe-keys | Refuse to write any `server.properties` key not on the fixed safe-key whitelist (`motd`, `max-players`, `view-distance`, `simulation-distance`, `spawn-protection`, `difficulty`, `gamemode`, `pvp`, `allow-nether`, `allow-flight`, `spawn-animals`, `spawn-monsters`, `spawn-npcs`, `enable-command-block`) | gate | panel `server.properties` edit, or read `ServerPropertiesManager` source directly | n/a | n/a | admin | brief | ServerPropertiesManager#setProperty |
| ultitools.remote.server-properties-safe-keys-read | Return only the same fixed safe-key whitelist's current values, never the full `server.properties` file — a distinct code path (`handleGet`) from the write guard above, not just its mirror image | gate | `server_properties` panel message with `action: "get"` (or omitted, `get` is the default) | n/a | n/a | admin | brief | ServerPropertiesManager#handleGet |

## Configuration

Every leaf key in `src/main/resources/config.yml` (44 keys, counted with
`grep -nE '^[[:space:]]*[a-zA-Z][a-zA-Z0-9_-]*:[[:space:]]*[^[:space:]#]' src/main/resources/config.yml | wc -l`,
the reconciliation table's counting command for this Kind) plus the two operator-relevant keys in
`src/main/resources/env.yml` (`api-url` and `version`). This section catalogues the configuration surface exhaustively at
key granularity; several of these keys already have a behavioural row elsewhere in this document
(storage backend, language, the eight panel capabilities) — that row documents the *feature* the
key drives, this row documents the *key* itself, and both are kept so the reconciliation table
can prove every key is accounted for without also making every behavioural row carry a `config`
Kind. The framework has zero `@ConfigEntity` classes: its own operator configuration is this
shipped `config.yml`, read directly by `Bukkit`'s `FileConfiguration`, not a bound
`@ConfigEntity` entity — so the `@ConfigEntity` reconciliation line in the pull request reads 0
against 0, with this sentence as its reason, rather than being omitted.

This section carries 53 rows total (51 `ultitools.config.config.*` rows for `config.yml`, plus
`ultitools.config.env.api-url` and `ultitools.config.env.version` for `env.yml`), not the 44 the
counting command above measures, and that divergence has a reason rather than being an omission —
the counting command only sees `src/main/resources/config.yml`, the shipped default resource; it
cannot see a key the framework recognises but does not ship a default for, and it was never meant
to count `env.yml` at all (that is a second, separate file, resolved at build time from Maven
properties rather than being a shipped default, with its own two-key row set). Seven keys are genuinely
read by production code (`SystemLogHandler`, `LogStreamManager`, `CommandExecutionManager`,
`FileOperationManager`, confirmed by reading each) but are absent from the `config.yml` resource:
`ultipanel.commands.blocklist` and `ultipanel.files.editable-roots` are migrated onto disk on
first boot if absent (`UltiTools#migrateKeyIfAbsent`), so a running server always has them even
though the packaged jar's default resource does not; `ultipanel.logging.levels`,
`ultipanel.logging.excluded-loggers`, and the three `ultipanel.logging.batch.*` keys are purely
opt-in — read via `FileConfiguration#contains` with a code-level fallback, present only if the
operator adds them by hand per `config-example.yml`, and have no effect at all otherwise (except
`batch.interval`, which has no effect regardless — see the row below and
UltiKits/UltiTools-Reborn#432). The reconciliation-table verify command's own `keys=44 rows=51`
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
| ultitools.config.config.ultipanel.logging.batch.enabled | Whether log-stream delivery to the panel is batched rather than sent line-by-line; purely opt-in — absent from both the shipped resource and the migration path, has effect only if the operator adds it by hand per `config-example.yml` | config | `config-example.yml: ultipanel.logging.batch.enabled (code default when present: true)` | n/a | n/a | admin | none | LogStreamManager#loadBatchConfiguration |
| ultitools.config.config.ultipanel.logging.batch.size | Entries per batch when batched log delivery is enabled; purely opt-in, same as `batch.enabled` above | config | `config-example.yml: ultipanel.logging.batch.size (code default when present: 10)` | n/a | n/a | admin | none | LogStreamManager#loadBatchConfiguration |
| ultitools.config.config.ultipanel.logging.batch.interval | Documented as the send interval, in milliseconds, for batched log delivery — but setting it has no observable effect: `UltiPanelLogTransmitter`'s constructor starts its fixed-delay scheduler with the hardcoded 5000ms default before `LogStreamManager#loadBatchConfiguration` applies the configured value, and `setIntervalMs` only mutates the field without rescheduling the already-running task. A known product defect (UltiKits/UltiTools-Reborn#432), not fixed here per this plan's zero-new-code rule | config | `config-example.yml: ultipanel.logging.batch.interval (code default when present: 5000; setting it has no effect, see #432)` | n/a | n/a | admin | none | LogStreamManager#loadBatchConfiguration, UltiPanelLogTransmitter#UltiPanelLogTransmitter |
| ultitools.config.config.ultipanel.logging.error-reporting.dedup-window-seconds | Time window, in seconds, within which a repeat of the same error fingerprint is not re-reported | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.dedup-window-seconds (default: 300)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.enabled | Whether errors are auto-reported to UltiPanel | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.enabled (default: true)` | n/a | n/a | admin | brief | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.max-errors-per-batch | Maximum number of error reports sent per `batch_update` cycle | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.max-errors-per-batch (default: 10)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.sample-after-count | Number of cumulative occurrences of the same error fingerprint after which sampling begins | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.sample-after-count (default: 10)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.error-reporting.sample-rate | Fraction of a sampled error's repeat occurrences that are still reported (`0.1` = 10%) | config | `src/main/resources/config.yml: ultipanel.logging.error-reporting.sample-rate (default: 0.1)` | n/a | n/a | admin | none | ErrorReportCollector#loadConfiguration |
| ultitools.config.config.ultipanel.logging.excluded-loggers | Logger name prefixes excluded from the log stream sent to the panel, to avoid transmitting excessive volume; purely opt-in, same as `batch.enabled` above | config | `config-example.yml: ultipanel.logging.excluded-loggers (default when present: [com.mojang.authlib, net.minecraft.network, org.apache.http, com.zaxxer.hikari, org.eclipse.jetty])` | n/a | n/a | admin | none | SystemLogHandler#loadConfiguration |
| ultitools.config.config.ultipanel.logging.levels | Log levels transmitted to the panel's live log stream; purely opt-in, same as `batch.enabled` above | config | `config-example.yml: ultipanel.logging.levels (default when present: [info, warning, error])` | n/a | n/a | admin | none | SystemLogHandler#loadConfiguration |
| ultitools.config.env.api-url | UltiCloud API base URL; Maven-filtered at build time from the `ultitools.api.url` property, not editable at runtime by the server operator | config | `src/main/resources/env.yml: api-url (default: https://api.ultikits.com, from pom.xml property ultitools.api.url)` | n/a | n/a | admin | none | UltiTools#getEnv |
| ultitools.config.env.version | The framework's own version string, used to gate version-dependent behaviour and drive the update check's "current version" comparison; Maven-filtered at build time from `${project.version}`, not editable at runtime | config | `src/main/resources/env.yml: version (default: the pom.xml project version at build time, e.g. 6.3.0-SNAPSHOT)` | n/a | n/a | admin | brief | UltiTools#getPluginVersion |
