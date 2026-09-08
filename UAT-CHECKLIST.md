# UltiTools-API — UAT Checklist

This document is the executable companion to `FEATURES.md`: one row per feature stating the
steps to exercise it and the observable truth that proves it works. It is an internal reference
for real-machine verification, not user-facing documentation.

> Batches are dispatched at 60 rows or fewer, and a batch never spans two repositories. There are
> exactly two legitimate exits to `human-uat-pending`: a row needing the pixel layer while the
> real-client harness is not ready, and a row needing personal credentials. Every other row must
> reach `pass`, `fail`, or `blocked`.

## Conventions

- **Columns:** `ID`, `Preconditions`, `Steps`, `Expected`, `Layer`, `Covers`.
- **ID:** cites its `FEATURES.md` ID verbatim. A negative case suffixes the checklist ID only,
  as `.neg-<slug>` — a negative case still tests the same feature, so the base ID is unchanged.
- **Layer**, copied verbatim from Laojun's own `ultitools-real-client-uat` skill so no
  translation step exists at dispatch time: `protocol`, `java-client`, `os-input`, `pixel`,
  `server`, `human`.
- **Expected** must name an observable truth — an exact chat line, a log line, a database row,
  an inventory slot — and never the words "it works".
- **Covers** back-references a Phase 9 GUI-excluded class name; left blank when no such class
  applies.
- A row whose Preconditions name a prior row must appear after that row in file order.
- **Config-per-file rule (D-06):** one checklist row per `@ConfigEntity`-annotated class or per
  shipped yml file, never one row per key.
- This repository's shipped default is `language: "zh"` in `config.yml`. Every row below whose
  Expected quotes a literal in-game or console line therefore carries the precondition
  `language: en` set in `plugins/UltiTools/config.yml`, so the observed line matches this
  document's English-only text exactly, character for character.

## /ul — framework administration

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.ul.help | `language: en` in config.yml | Run `/ul help` (console or op player) | Chat/console output is exactly `=== UltiTools Commands ===` followed by `/ul reload Reload all modules`, `/ul reload <name> Reload a specific module`, `/ul list Check loaded module list`, `================` | server | |
| ultitools.ul.list | At least one module JAR present in `plugins/UltiTools/plugins/` | Run `/ul list` | One line per loaded module reading `<module name> <version>`, matching `UltiToolsPlugin#getPluginName` and `#getVersion` for every module actually present | server | |
| ultitools.ul.reload | Same precondition as ultitools.ul.list (appears after it) | Run `/ul reload` | Every module's `reloadSelf()` runs with no exception in the console; a module list taken with `/ul list` immediately after is unchanged | server | |
| ultitools.ul.reload-module | `language: en` in config.yml; a loaded module named `<name>` (positive case: an existing module; negative case below: a name that does not exist) | Run `/ul reload <name>`. Negative case: run `/ul reload does-not-exist` | Positive: chat/console line reads `Module <name> has been reloaded` (green). Negative: chat/console line reads `does-not-exist is not a loaded UltiTools module. Use /ul list to see loaded modules` (red) | server | |

## /upm — plugin management

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.upm.check | `language: en` in config.yml; at least one update available (module or framework) | Run `/upm check` | Console/chat shows `Available updates:` followed by one `<name> <current> → <latest>` line per available update — `checkUpdates` formats the transition with a Unicode arrow `→`, not the ASCII sequence `->` | server | |
| ultitools.upm.check.neg-none | `language: en` in config.yml; no update available for any module or the framework | Run `/upm check` | Chat/console line reads `No updates available.` (green) | server | |
| ultitools.upm.help | `language: en` in config.yml | Run `/upm help` | Chat/console shows `========|Plugin Install Help|========` followed by eight usage lines, all green, starting with `/upm list [page] - View available plugin list` and ending with `/upm update all - Update all plugins`. This path has no `@CmdMapping` site — `BaseCommandExecutor#onCommand` matches the literal `help` argument and dispatches straight to `handleHelp` | server | |
| ultitools.upm.help.neg-bare | `language: en` in config.yml | Run bare `/upm` with no arguments | A preceding red `Unknown command, please enter /upm help for help` line, THEN the same help block as `ultitools.upm.help` — `matchMethod` returns null for zero args, so `onCommand` sends the unknown-command message before falling through to the same `handleHelp` call; the two invocations are not identical | server | |
| ultitools.upm.install | `language: en` in config.yml; `<plugin>` exists on UltiCloud and is not yet installed | Run `/upm install <plugin>` | Chat/console line reads `Installed! Please restart the server! Please be sure to delete the old version module!` (green); the module JAR now exists under `plugins/UltiTools/plugins/` | server | |
| ultitools.upm.install.neg-not-found | `language: en` in config.yml | Run `/upm install does-not-exist-on-ulticloud` | Chat/console line reads `Install Failed!` (red); no new file appears under `plugins/UltiTools/plugins/` | server | |
| ultitools.upm.install-version | `language: en` in config.yml; `<plugin>` has more than one version on UltiCloud; `<plugin>` is NOT already installed — `PluginInstallUtils#installPlugin(id, version)` only downloads the requested version and, unlike `updatePlugin`, never deletes an existing JAR, so an already-installed plugin would leave two JARs present and make the reported post-restart version unreliable | Run `/upm install <plugin> <version>`, choosing an older version | Chat/console line reads `Installed! Please restart the server! Please be sure to delete the old version module!` (green); the installed JAR reports `<version>` after restart | server | |
| ultitools.upm.install-version.neg-not-found | `language: en` in config.yml | Run `/upm install <plugin> 0.0.0-does-not-exist` | Chat/console line reads `Install Failed!` (red) | server | |
| ultitools.upm.list | `language: en` in config.yml | Run `/upm list` as a player, then separately as console | Page 1 of the UltiCloud catalogue is shown in both cases, ending with `======== Page 1 ========`. As a player, each entry carries an install-state marker (` installed` or ` not installed`) distinct between installed and uninstalled plugins — `listPlugins`'s `Player` branch only. Console output carries no such marker: each entry lists only the name, an `Install Command：/upm install <id>` line, and the description | server | |
| ultitools.upm.list-page | `language: en` in config.yml; the catalogue has more than one page | Run `/upm list 2` | The footer line reads `======== Page 2 ========` and the listed entries differ from page 1's | server | |
| ultitools.upm.list-page.neg-bad-page | `language: en` in config.yml | Run `/upm list not-a-number` | Chat/console line reads `Failed to parse 'not-a-number' as Integer` (red); no plugin listing is produced | server | |
| ultitools.upm.uninstall | `language: en` in config.yml; `<plugin>` is currently installed | Run `/upm uninstall <plugin>` | Chat/console line reads `Uninstalled! Please delete local files manually, otherwise it will be enabled after restart!` followed by `File Location: <plugins folder path>/plugins` (capital L; both green) | server | |
| ultitools.upm.uninstall.neg-not-found | `language: en` in config.yml | Run `/upm uninstall does-not-exist` | Chat/console line reads `Uninstall Failed! Please check if the spelling is correct!` (red) | server | |
| ultitools.upm.update | `language: en` in config.yml; a named module has an update available (appears after ultitools.upm.check) | Run `/upm update <module-name>` | Chat/console shows `Updating <module-name>...` then `Update successful! Please restart the server to apply.` (both, one yellow one green) | server | |
| ultitools.upm.update.neg-no-update | `language: en` in config.yml; the named module has no update available | Run `/upm update <module-name>` | Chat/console line reads `No updates available.` (red — this branch of `updatePlugin` uses `ChatColor.RED`, unlike `checkUpdates`'s green use of the same string) | server | |
| ultitools.upm.versions | `language: en` in config.yml; `<plugin>` exists on UltiCloud | Run `/upm versions <plugin>` | Numbered version list is shown, the last entry followed by `   Install Command：/upm install <plugin> [version]` (full-width colon `：` and capital `Command`, matching the framework's own translation string verbatim — not an ASCII `Install command:`) | server | |
| ultitools.upm.versions.neg-not-found | `language: en` in config.yml | Run `/upm versions does-not-exist-on-ulticloud` | Chat/console line reads `Could not fetch the version list!` (red) | server | |

## /ulticloud — cloud authentication

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.ulticloud.help | Console access | Run `/ulticloud help` | Console shows `=== UltiCloud Commands ===` (aqua) followed by `/ulticloud login - Authenticate with UltiCloud`, `/ulticloud logout - Clear saved credentials`, `/ulticloud status - Show connection status` (white command, gray description). This path has no `@CmdMapping` site — same `BaseCommandExecutor#onCommand` dispatch as `ultitools.upm.help` | server | |
| ultitools.ulticloud.help.neg-bare | Console access | Run bare `/ulticloud` with no arguments | A preceding red `Unknown command, please enter /ulticloud help for help` line, THEN the same help block as `ultitools.ulticloud.help` — same `matchMethod`-returns-null fallthrough as `ultitools.upm.help.neg-bare`; the two invocations are not identical | server | |
| ultitools.ulticloud.login | Console access; this server not currently authenticated with UltiCloud; the maintainer's own UltiCloud account and browser access to open the printed link | Run `/ulticloud login` from console, then open the printed URL and complete the login in a browser within 5 minutes | Console prints a boxed `Open this URL in your browser to login:` block with a real URL, then confirms the login once the browser flow completes; `/ulticloud status` (see below) then reports Connected | human | |
| ultitools.ulticloud.login.neg-already-logged-in | This server already authenticated with UltiCloud | Run `/ulticloud login` | Chat/console line reads `Already logged in to UltiCloud. Use /ulticloud logout first to re-login.` (yellow) | server | |
| ultitools.ulticloud.login.neg-rate-limited | Run `/ulticloud login` once first so a login attempt has just been made | Run `/ulticloud login` again immediately | Chat/console line reads `Please wait <N> seconds before trying again.` (red) | server | |
| ultitools.ulticloud.logout | This server currently authenticated with UltiCloud (appears after ultitools.ulticloud.login) | Run `/ulticloud logout` | Chat/console shows `Successfully logged out of UltiCloud. Cloud features are now disabled.` then `Use /ulticloud login to re-authenticate.` (green, then gray) | server | |
| ultitools.ulticloud.logout.neg-not-logged-in | This server not currently authenticated with UltiCloud | Run `/ulticloud logout` | Chat/console shows `Not currently logged in to UltiCloud.` then `Cloud features have been stopped regardless.` (yellow, then gray) | server | |
| ultitools.ulticloud.status | This server authenticated with UltiCloud under the maintainer's own account (appears after ultitools.ulticloud.login) | Run `/ulticloud status` | Chat/console line reads `UltiCloud: Connected as <the maintainer's real UltiCloud username>` (green), naming the real connected account rather than a placeholder | human | |
| ultitools.ulticloud.status.neg-not-connected | This server not currently authenticated with UltiCloud | Run `/ulticloud status` | Chat/console shows `UltiCloud: Not connected` then `Use /ulticloud login to authenticate.` (yellow, then gray) | server | |

## Boot sequence and listeners

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.boot.plugin-load-order | At least two modules installed with a declared hard `@PluginDependency` between them; the server is NOT launched with `-Dultitools.useLegacyPluginLoading=true` (that flag bypasses dependency resolution entirely — see ultitools.boot.plugin-load-order-legacy below) | Restart the server and read the console output during startup | Console shows `[UltiTools-API] Plugin load order resolved successfully.`, and the dependent module's own `onEnable` log line appears after its dependency's | server | |
| ultitools.boot.plugin-load-order.neg-missing | One installed module declares a hard `@PluginDependency` on a module name that is not installed, and no other installed module depends on it; the server is NOT launched with `-Dultitools.useLegacyPluginLoading=true` | Restart the server and read the console output during startup | Console shows `[UltiTools-API] A required plugin dependency is missing.` followed by the resolver's own message naming the missing dependency; every module with no dependency path (direct or transitive) to it still loads and appears in `/ul list`. `PluginDependencyResolver#expandForward` excludes not just the module with the missing dependency but every module that transitively depends on it — a module `C` that depends on `A`, which depends on the missing module, is excluded too, even though `C` never itself declares the missing dependency | server | |
| ultitools.boot.plugin-load-order.neg-circular | Two installed modules declare a hard `@PluginDependency` on each other, and no other installed module depends on either; the server is NOT launched with `-Dultitools.useLegacyPluginLoading=true` | Restart the server and read the console output during startup | Console shows `[UltiTools-API] Circular dependency detected among plugins.` followed by a `Loop: A -> B -> A`-shaped line naming both modules; every module with no dependency path (direct or transitive) into the cycle still loads. Kahn's algorithm never assigns a node downstream of the cycle an in-degree of 0, so a module that depends on a cycle member is excluded too, not just the two modules forming the cycle itself | server | |
| ultitools.boot.plugin-load-order-legacy | Server launched with `-Dultitools.useLegacyPluginLoading=true` (modeled on Paper's own `-Dpaper.useLegacyPluginLoading=true` precedent); at least two modules with a declared hard `@PluginDependency`, or a missing/circular one | Restart the server with that flag set and read the console output during startup | `PluginManager#sortPluginsByDependencies` skips `PluginDependencyResolver` entirely and returns filesystem order; console shows `[UltiTools-API] Legacy unsorted plugin load order is ACTIVE because -Dultitools.useLegacyPluginLoading=true is set on the command line. Dependency resolution is skipped entirely - modules load in filesystem order and may fail to initialize if they rely on load order.` — none of the three rows above's success/missing/circular messages appear, and a module may fail to initialize regardless of any declared dependency | server | |
| ultitools.boot.update-check | `language: en` in config.yml; server has outbound access to the update-check endpoint; a newer framework or module version exists | Restart the server and read the console output roughly one tick after startup completes | Console shows `[UltiTools-API] Checking for updates...`; if a newer framework version exists, `[UltiTools-API] UltiTools-API update available: <latest> (current: <current>)` then `[UltiTools-API] Download URL: https://github.com/UltiKits/UltiTools-Reborn/releases/latest`; if any module updates exist, `[UltiTools-API] Module updates available (<N>):` then one `[UltiTools-API]   <module> <current> -> <latest>` line per module — this startup log path is not i18n'd for the arrow, so it is the ASCII `->`, unlike `/upm check`'s chat output which uses `→` | server | |
| ultitools.boot.update-check.neg-none | `language: en` in config.yml; no newer framework or module version exists | Restart the server and read the console output roughly one tick after startup completes | Console shows `[UltiTools-API] All plugins are up to date!` | server | |
| ultitools.listener.placeholderapi-bridge | PlaceholderAPI installed and enabled; a UltiTools-provided placeholder expansion (e.g. `player`) not yet registered | Join the server as any player | Within about 60 seconds the console shows the framework dispatching `papi ecloud download Player` (and, if any expansion needed downloading, a later `papi reload`) from the console sender | server | |
| ultitools.listener.update-notify | An OP player joins after ultitools.boot.update-check found at least one update, and has not yet been notified this session | Join the server as an OP player | Chat shows `[UltiTools] <N> update(s) available. Run /upm check for details.` (green prefix, yellow count) exactly once for that connection | server | |
| ultitools.listener.update-notify.neg-repeat-after-quit | Same precondition as ultitools.listener.update-notify, then the same player quits | Rejoin as the same OP player, without restarting the server | The notification is sent again — `PlayerCacheManager#onPlayerQuit` clears the `@PlayerCache`-backed `notifiedPlayers` set for that UUID on every quit, so `UpdateJoinListener`'s own javadoc claim of "once per server session" does not hold across a quit/rejoin. This is a known product defect (framework#431, not a checklist error) — the row exists to document the actual behaviour, not the intended one | server | |

## Scheduled tasks

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.task.player-cache-sweep | A module bean using `@PlayerCache` AND implementing `PlayerCacheManager.ExpiringPlayerCache` is registered, with at least one expired entry from a player who is no longer online — `sweepExpiredEntries` invokes `sweepExpired()` only on beans implementing that interface; a bean without it is never swept by this task | Wait for one 5-minute sweep interval, or read the console log for the sweep's own warning path on a forced failure | No `ConcurrentModificationException` or `Error running expiry sweep for <class>` warning appears in the console under normal operation; the bean's own `sweepExpired()` runs and the expired entry is gone from its tracked state afterward | server | |

## Data persistence

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.storage.backend-select | `language: en` in config.yml; `datasource.type: sqlite` in config.yml (shipped default); at least one loaded module has performed a `@Table`-backed data operation since the last restart — `SQLiteDataStore` creates its `.db` file lazily from `getOperator`, never at startup, so a server with zero data operations has no file to find | Restart the server, trigger one module data operation, and read the startup console log | Console shows `Data Storage Method: sqlite`; a `<plugin>.db` (or `data.db`) file now exists under the plugin's data folder | server | |
| ultitools.storage.backend-select.neg-fallback | `datasource.type: mysql` in config.yml but no reachable MySQL server configured | Restart the server and read the startup console log | The backend actually obtained falls back to `json`, and the console reports the requested backend and the one actually used are different, rather than silently reporting `mysql` | server | |
| ultitools.storage.restart-survival | A row written through a `DataOperator` while the server is up (any module command backed by `@Table`) | Stop the server completely, then start it again, then read the same row back through the same module command | The same value is returned after restart, in whichever backend `ultitools.storage.backend-select` is currently active | server | |

## Language

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.language.select | `language: en` in config.yml, server restarted after the change | Run `/ul help` | Output is the English text `=== UltiTools Commands ===` block, not the Chinese equivalent | server | |
| ultitools.language.select.neg-default | `language: zh` in config.yml (shipped default), server restarted after the change | Run `/ul help` | Output is the localized block from `lang/zh.json`'s help-text key, visibly different from the English `=== UltiTools Commands ===` block above, not a Chinese/English mix | server | |

## Panel capabilities

Every row below is exercised by toggling the named `ultipanel.capabilities.*` key, then sending
the corresponding panel message type (or reading `RemoteActionLog` for the same effect without a
live panel session). The refusal text checked below is `Capability#configurableRefusal`'s literal
wording, produced by `Capability#refusalMessage()`.

**How the toggle takes effect differs by capability, and only five of the eight can use `/ul
reload`.** `commands`, `file-read`, `file-write`, `file-delete`, and `server-properties` are
gated per-request — `PluginInitiationUtils#dispatchWithCapabilityGate` calls
`Capability#isEnabled()` fresh on every inbound message, which reads the live (already-reloaded)
`config.yml`, so `/ul reload` is sufficient. `monitoring`, `logs`, and `player-events` are gated
once, at connect time, inside `PluginInitiationUtils#wireManagers` — `/ul reload`'s
`UltiTools#reloadPlugins` never calls `wireManagers`, so toggling one of these three requires a
full server restart (or a cloud reconnect) to take effect, not just a reload.

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.capability.commands | `ultipanel.capabilities.commands: true` | Send an `execute_command` panel message (or dispatch through `CommandExecutionManager` directly) | The command executes and `RemoteActionLog` records an `ALLOWED` verdict for the `execute_command` action | server | |
| ultitools.capability.commands.neg-disabled | `ultipanel.capabilities.commands: false` (shipped default) | Send an `execute_command` panel message | The command is refused; `RemoteActionLog` records a `DENIED` verdict whose reason is `Blocked by policy — edit 'ultipanel.capabilities.commands' in plugins/UltiTools/config.yml to change this.` | server | |
| ultitools.capability.file-delete | `ultipanel.capabilities.file-delete: true` | Send a file-delete panel message for a file inside an editable root | The file is deleted and `RemoteActionLog` records an `ALLOWED` verdict | server | |
| ultitools.capability.file-delete.neg-disabled | `ultipanel.capabilities.file-delete: false` (shipped default) | Send a file-delete panel message | The delete is refused with the reason naming `ultipanel.capabilities.file-delete` | server | |
| ultitools.capability.file-read | `ultipanel.capabilities.file-read: true` (shipped default) | Send a file-read/list panel message for a file inside an editable root | The file contents/listing are returned | server | |
| ultitools.capability.file-read.neg-disabled | `ultipanel.capabilities.file-read: false` | Send a file-read panel message | The read is refused with the reason naming `ultipanel.capabilities.file-read` | server | |
| ultitools.capability.file-write | `ultipanel.capabilities.file-write: true` | Send a file-write/upload panel message for a file inside an editable root | The file is written and `RemoteActionLog` records an `ALLOWED` verdict | server | |
| ultitools.capability.file-write.neg-disabled | `ultipanel.capabilities.file-write: false` (shipped default) | Send a file-write panel message | The write is refused with the reason naming `ultipanel.capabilities.file-write` | server | |
| ultitools.capability.logs | `ultipanel.capabilities.logs: true` (shipped default) | Open a log-stream panel session | Live console log lines stream to the panel | server | |
| ultitools.capability.logs.neg-disabled | `ultipanel.capabilities.logs: false` | Open a log-stream panel session | The stream is refused with the reason naming `ultipanel.capabilities.logs` | server | |
| ultitools.capability.monitoring | `ultipanel.capabilities.monitoring: true` (shipped default) | Observe the panel connection for one `batch_update` cycle | `status` and `metrics` fields are present every cycle | server | |
| ultitools.capability.monitoring.neg-disabled | `ultipanel.capabilities.monitoring: false` | Observe the panel connection for one `batch_update` cycle | The server reads as offline to the panel — this capability is the panel's only "server is alive" signal | server | |
| ultitools.capability.player-events | `ultipanel.capabilities.player-events: true` (shipped default) | Join/quit/chat as a player while a panel session is open | The corresponding `player_event` message reaches the panel | server | |
| ultitools.capability.player-events.neg-disabled | `ultipanel.capabilities.player-events: false` | Join/quit/chat as a player while a panel session is open | No `player_event` message reaches the panel for that action | server | |
| ultitools.capability.server-properties | `ultipanel.capabilities.server-properties: true` | Send a `server_properties` edit for a key on `ServerPropertiesManager`'s safe-key list | The key is written to `server.properties` and the response reports success | server | |
| ultitools.capability.server-properties.neg-disabled | `ultipanel.capabilities.server-properties: false` (shipped default) | Send a `server_properties` edit | The edit is refused with the reason naming `ultipanel.capabilities.server-properties` | server | |

## Remote surface guards

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.remote.command-blocklist | `ultipanel.capabilities.commands: true`; blocklist at its shipped default | Send an `execute_command` panel message for `stop` | `CommandExecutionManager#isCommandAllowed` refuses it; `RemoteActionLog` records a `DENIED` verdict citing `ultipanel.commands.blocklist` and naming `stop` | server | |
| ultitools.remote.command-blocklist.neg-namespaced | Same as above | Send an `execute_command` panel message for `minecraft:stop` | Refused identically to the bare `stop` case — the namespace prefix is stripped before the blocklist check | server | |
| ultitools.remote.file-editable-roots | `ultipanel.capabilities.file-read: true`; editable roots at their shipped default (`plugins`, `logs`) | Send a file-read panel message for a path inside `plugins/` | The read succeeds | server | |
| ultitools.remote.file-editable-roots.neg-outside-root | Same as above | Send a file-read panel message for a path outside every configured editable root (e.g. the server's own `world/` folder) | Refused, naming the path as outside the editable-root set | server | |
| ultitools.remote.file-editable-roots.neg-protected | `ultipanel.capabilities.file-read: true` | Send a file-read panel message for `server.properties` | Refused unconditionally — `'server.properties' is a protected server file` — regardless of the editable-root and capability settings | server | |
| ultitools.remote.server-properties-safe-keys | `ultipanel.capabilities.server-properties: true` | Send a `server_properties` edit for `motd` | The key is written | server | |
| ultitools.remote.server-properties-safe-keys.neg-unsafe-key | `ultipanel.capabilities.server-properties: true` | Send a `server_properties` edit for a key not on the safe list (e.g. `online-mode`) | Rejected — `setProperty` returns `false` for anything outside `SAFE_KEYS`, and `server.properties` is unchanged | server | |

## Configuration

One row per shipped yml file (D-06's config-per-file rule), not per key: `config.yml` (44 keys)
and `env.yml` (1 operator-relevant key). Each row confirms every key in the file is present at
its `FEATURES.md`-documented default, then flips one representative key and observes the
behaviour follow.

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.config.config-yml | Fresh `plugins/UltiTools/config.yml` at its shipped default (not hand-edited); server started at least once, so the two migrated keys have run | Load the file; confirm each of the 44 keys listed under this document's companion `FEATURES.md` `## Configuration` section is present at its documented default, PLUS the two migrated keys (`ultipanel.commands.blocklist`, `ultipanel.files.editable-roots`) present at their migrated default; then set `language: "en"`, restart the server, and run `/ul help`; separately, add the five opt-in keys (`ultipanel.logging.levels`, `ultipanel.logging.excluded-loggers`, `ultipanel.logging.batch.enabled`, `ultipanel.logging.batch.size`, `ultipanel.logging.batch.interval`) verbatim from `config-example.yml`, restart, and confirm no startup error — their absence is the shipped state, so this second pass proves the opt-in path itself, not a default | Every one of the 44 shipped-resource keys is present at its documented default before the change; both migrated keys are present in the on-disk file even though absent from the packaged resource; after the restart, `/ul help` prints the English `=== UltiTools Commands ===` block instead of the `lang/zh.json`-localized block, proving the flipped key took effect; with the five opt-in keys added, the server starts cleanly and `SystemLogHandler`/`LogStreamManager` read them (no error log naming an unrecognized key) | server | |
| ultitools.config.env-yml | A clean `mvn -B -q clean package -DskipTests` build of the current commit, no `-Dultitools.api.url` override | Unzip the built jar and read `env.yml`; confirm `api-url` equals the shipped default `https://api.ultikits.com`; rebuild with `-Dultitools.api.url=http://localhost:8787` and re-extract `env.yml` from the new jar | The first jar's `env.yml` reads `api-url: "https://api.ultikits.com"`; the second jar's `env.yml` reads `api-url: "http://localhost:8787"` — the packaged value tracks the Maven property exactly, and there is no runtime key to change it after packaging | protocol | |
