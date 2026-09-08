# UltiTools-API — Feature Inventory

This document catalogues every operator- or player-visible function, command, content item and
configuration key in this repository, as read directly from source. It is an internal reference
for UAT execution and issue reconciliation — the public description of these features lives on
https://doc.ultikits.com/. Update this file in the same pull request as any feature change.

## Conventions

- **ID grammar:** `<repo-slug>.<area>.<action>`, dot-separated, every segment lowercase ASCII
  drawn from `[a-z0-9-]`. `<repo-slug>` is the repository name lowercased with no separators —
  `ultitools` here, `ultichat`, `ultiessentials`, and `ultitools-example` for
  `UltiTools-External-Example`. `<area>` is the feature section's slug. `<action>` is the verb.
  A `config` row is the one shape that exceeds three segments:
  `<repo-slug>.config.<file-stem>.<yml key path>`, the key path keeping its own dots. An ID
  changes only when the feature's identity changes, never on rewording. IDs are unique within a
  repository.
- **Kind**, exactly these eight values: `command`, `config`, `event`, `gui`, `scheduled`,
  `placeholder`, `persistence`, `gate`. Each maps one-to-one onto a reconciliation-table line.
- **Tier**, exactly three: `player`, `admin`, `internal`. Judged from what the feature is for,
  not from whether it carries a permission string — most command executors in this repository
  carry one, so judging by the string alone would make nearly everything `admin`.
- **Manual**, exactly three: `detailed`, `brief`, `none`.
- **Target**, exactly three: `player`, `console`, `both` — read straight off `@CmdTarget`; it is
  a property, not a tier.
- **Permission:** the literal node string, or the word `none`. An undeclared permission is
  granted to OP only, by Bukkit's own default; this is stated once here rather than per row.
- **Source:** `ClassName#member`, or the resource path for a `config` row.
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
this repository, matching this document's own row counts for those three command groups exactly
— confirmed by reading `UltiToolsCommands.java`, `PluginInstallCommands.java` and
`CloudLoginCommand.java` directly, not by trusting the count alone.

## /ul — framework administration

`UltiToolsCommands` — class-level `@CmdExecutor(alias = {"ul", "ultitools", "ulti"}, requireOp =
true)`, `@CmdTarget(BOTH)`. `grep -c '@CmdMapping' UltiToolsCommands.java` returns 4 — confirmed
by reading the file directly at lines 30, 41, 65 and 70 (`reload`, `reload <name>`, `help`,
`list`).

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.ul.help | Print the /ul command usage summary | command | `/ul help` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#help |
| ultitools.ul.list | List every currently loaded module and its version | command | `/ul list` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#listPlugins |
| ultitools.ul.reload | Reload every loaded module | command | `/ul reload` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#reloadPlugins |
| ultitools.ul.reload-module | Reload a single named module | command | `/ul reload <name>` | none (requireOp=true) | both | admin | brief | UltiToolsCommands#reloadPlugin |

## /upm — plugin management

`PluginInstallCommands` — class-level `@CmdExecutor(alias = "upm", requireOp = true)`,
`@CmdTarget(BOTH)`. `grep -c '@CmdMapping' PluginInstallCommands.java` returns 8 — confirmed by
reading the file directly (`list <page>`, `list`, `install <plugin> <version>`,
`install <plugin>`, `versions <plugin>`, `uninstall <plugin>`, `check`, `update <plugin>`),
matching D-08's independently stated count exactly.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.upm.check | List available framework and module updates | command | `/upm check` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#checkUpdates |
| ultitools.upm.install | Install the latest version of a plugin from UltiCloud | command | `/upm install <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#installPlugin(plugin) |
| ultitools.upm.install-version | Install a specific version of a plugin from UltiCloud | command | `/upm install <plugin> <version>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#installPlugin(plugin,version) |
| ultitools.upm.list | List page 1 of the plugins available on UltiCloud | command | `/upm list` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#listPlugins(sender) |
| ultitools.upm.list-page | List a chosen page of the plugins available on UltiCloud | command | `/upm list <page>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#listPlugins(sender,page) |
| ultitools.upm.uninstall | Uninstall a plugin and report where its files still live | command | `/upm uninstall <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#uninstallPlugin |
| ultitools.upm.update | Update one named module, or every module with an available update | command | `/upm update <plugin\|all>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#updatePlugin |
| ultitools.upm.versions | List every version of a plugin available on UltiCloud | command | `/upm versions <plugin>` | none (requireOp=true) | both | admin | brief | PluginInstallCommands#listVersions |

## /ulticloud — cloud authentication

`CloudLoginCommand` — class-level `@CmdExecutor(alias = "ulticloud", requireOp = true)`,
`@CmdTarget(CONSOLE)`. `grep -c '@CmdMapping' CloudLoginCommand.java` returns 3 — confirmed by
reading the file directly (`login`, `logout`, `status`), matching D-08's independently stated
count exactly.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.ulticloud.login | Request a UltiCloud magic-link login for this server | command | `/ulticloud login` | none (requireOp=true) | console | admin | detailed | CloudLoginCommand#login |
| ultitools.ulticloud.logout | Tear down the cloud connection and clear the saved credential | command | `/ulticloud logout` | none (requireOp=true) | console | admin | brief | CloudLoginCommand#logout |
| ultitools.ulticloud.status | Show whether this server is currently connected to UltiCloud | command | `/ulticloud status` | none (requireOp=true) | console | admin | brief | CloudLoginCommand#status |

## Boot sequence and listeners

Non-command operator-visible behaviour driven by the boot sequence (`UltiTools.onEnable()`) and
by the framework's own Bukkit listeners, in scope per D-08. Out of scope, and not listed below:
the developer-facing IoC container, AOP, and ORM internals the boot sequence also wires up —
those are documented on doc.ultikits.com and verified by unit tests, not by a real-machine
session.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.boot.plugin-load-order | Order module loading by declared dependencies (Kahn's topological sort); a cycle or a missing hard dependency excludes only the affected module(s), not the whole load | event | console log during server startup | n/a | console | admin | brief | PluginManager#sortPluginsByDependencies |
| ultitools.boot.update-check | Check for a newer framework version and newer module versions once, asynchronously, shortly after startup | event | console log during server startup | n/a | console | admin | none | UpdateManager#checkUpdatesSync |
| ultitools.listener.placeholderapi-bridge | On a player's first join needing an unregistered PlaceholderAPI expansion, download and reload it automatically | event | join the server as any player while PlaceholderAPI is installed | n/a | player | internal | none | PlayerJoinListener#onPlayerJoin |
| ultitools.listener.update-notify | Notify an OP player once per session, on join, if a framework or module update is available | event | join the server as an OP player after ultitools.boot.update-check has found an update | n/a | player | admin | none | UpdateJoinListener#onPlayerJoin |

**Reconciliation note (D-07):** the line-start form of the canonical command reports exactly one
`@EventListener` site in this repository (`PlayerJoinListener`) against the two `event`-Kind rows
above whose Source is a Bukkit join listener, plus a third (`ultitools.listener.update-notify`)
with no framework annotation at all. This is a deliberate, explained mismatch, not an omission:
`PlayerJoinListener` carries `@EventListener`, but the core framework's own bootstrap never runs
`ComponentScanner`/`ListenerManager` over itself — only a per-plugin `@UltiToolsModule`'s declared
`scanBasePackages` are scanned — so the annotation is not what causes registration here. Both
`PlayerJoinListener` and `UpdateJoinListener` are registered the same way: a direct
`Bukkit.getPluginManager().registerEvents(...)` call inside `UltiTools.onEnable()` /
`scheduleStartupMessages()`. The two boot-sequence `event` rows above (`ultitools.boot.*`) are not
driven by any annotation at all and are not counted against the `@EventListener` reconciliation
line either; they reconcile against nothing because module-load-order and the update check have
no annotation-based instrument in this codebase.

## Scheduled tasks

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.task.player-cache-sweep | Periodically evict expired `@PlayerCache`-backed state on a five-minute clock, independent of any player quitting | scheduled | runs automatically every 5 minutes while the server is up | n/a | console | internal | none | PlayerCacheManager#sweepExpiredEntries |

**Reconciliation note:** the line-start form of the canonical command reports exactly one
`@Scheduled` site in this repository, and this is that one row — the count balances exactly.

## Data persistence

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.storage.backend-select | Choose the ORM storage backend (`json`, `sqlite`, or `mysql`) via `config.yml`; falls back to `json` if the configured backend is unavailable | persistence | `datasource.type` in `plugins/UltiTools/config.yml`, applied on next start/`/ul reload` | n/a | console | admin | brief | UltiTools#initDataStore |
| ultitools.storage.restart-survival | Data written through a `DataOperator` survives a full server restart, in whichever backend is active | persistence | write data via any module command backed by `@Table`, then restart the server | n/a | console | admin | none | DataStoreManager#getDatastore |

## Language

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.language.select | Choose the framework's message language (`zh` or `en`) via `config.yml`, applied on next start | gate | `language` in `plugins/UltiTools/config.yml`, applied on next start | n/a | console | admin | brief | UltiTools#initLanguage |

## Panel capabilities

Eight independently-switchable panel-facing capabilities, each with its own
`ultipanel.capabilities.*` key in `config.yml` and its own shipped default (D-08). Every gate
funnels through the same `Capability#isEnabled()` accessor.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.capability.commands | Allow the panel to execute server commands remotely; ships disabled | gate | `ultipanel.capabilities.commands` in config.yml | n/a | console | admin | brief | Capability#COMMANDS |
| ultitools.capability.file-delete | Allow the panel to delete files or directories within the editable roots; ships disabled | gate | `ultipanel.capabilities.file-delete` in config.yml | n/a | console | admin | brief | Capability#FILE_DELETE |
| ultitools.capability.file-read | Allow the panel to read and list files within the editable roots; ships enabled | gate | `ultipanel.capabilities.file-read` in config.yml | n/a | console | admin | brief | Capability#FILE_READ |
| ultitools.capability.file-write | Allow the panel to write or upload files within the editable roots; ships disabled | gate | `ultipanel.capabilities.file-write` in config.yml | n/a | console | admin | brief | Capability#FILE_WRITE |
| ultitools.capability.logs | Allow the panel to stream and control the live console log; ships enabled | gate | `ultipanel.capabilities.logs` in config.yml | n/a | console | admin | brief | Capability#LOGS |
| ultitools.capability.monitoring | Allow the panel to receive live TPS/memory/world/player monitoring data — the panel's only "server is alive" signal; ships enabled | gate | `ultipanel.capabilities.monitoring` in config.yml | n/a | console | admin | detailed | Capability#MONITORING |
| ultitools.capability.player-events | Allow the panel to receive live player join/quit/chat events; ships enabled | gate | `ultipanel.capabilities.player-events` in config.yml | n/a | console | admin | brief | Capability#PLAYER_EVENTS |
| ultitools.capability.server-properties | Allow the panel to read and edit the `server.properties` safe-key whitelist; ships disabled | gate | `ultipanel.capabilities.server-properties` in config.yml | n/a | console | admin | brief | Capability#SERVER_PROPERTIES |

## Remote surface guards

Guards enforced independently of, and in addition to, the capability switches above — a
capability being on does not itself grant access to a blocked command, a non-editable path, or an
unlisted `server.properties` key.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultitools.remote.command-blocklist | Refuse a remote command whose base name (namespace prefix stripped first) is on the operator-editable blocklist; ships with 10 dangerous commands blocked (`op`, `deop`, `stop`, `restart`, `reload`, `ban-ip`, `pardon-ip`, `whitelist`, `save-off`, `save-all`) | gate | `ultipanel.commands.blocklist` in config.yml | n/a | console | admin | detailed | CommandExecutionManager#isCommandAllowed |
| ultitools.remote.file-editable-roots | Restrict panel file access to an operator-configured set of root directories (ships as `plugins`, `logs` only), with credential-bearing files and dangerous extensions unconditionally protected regardless of root | gate | `ultipanel.files.editable-roots` in config.yml | n/a | console | admin | detailed | FileOperationManager#isPathAllowed |
| ultitools.remote.server-properties-safe-keys | Refuse to write any `server.properties` key not on the fixed safe-key whitelist (`motd`, `max-players`, `view-distance`, `simulation-distance`, `spawn-protection`, `difficulty`, `gamemode`, `pvp`, `allow-nether`, `allow-flight`, `spawn-animals`, `spawn-monsters`, `spawn-npcs`, `enable-command-block`) | gate | panel `server.properties` edit, or read `ServerPropertiesManager` source directly | n/a | console | admin | brief | ServerPropertiesManager#setProperty |
