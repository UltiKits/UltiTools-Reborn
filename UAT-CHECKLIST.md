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
| ultitools.upm.check | `language: en` in config.yml; at least one update available (module or framework) | Run `/upm check` | Console/chat shows `Available updates:` followed by one `<name> <current> -> <latest>` line per available update | server | |
| ultitools.upm.check.neg-none | `language: en` in config.yml; no update available for any module or the framework | Run `/upm check` | Chat/console line reads `No updates available.` (green) | server | |
| ultitools.upm.install | `language: en` in config.yml; `<plugin>` exists on UltiCloud and is not yet installed | Run `/upm install <plugin>` | Chat/console line reads `Installed! Please restart the server! Please be sure to delete the old version module!` (green); the module JAR now exists under `plugins/UltiTools/plugins/` | server | |
| ultitools.upm.install.neg-not-found | `language: en` in config.yml | Run `/upm install does-not-exist-on-ulticloud` | Chat/console line reads `Install Failed!` (red); no new file appears under `plugins/UltiTools/plugins/` | server | |
| ultitools.upm.install-version | `language: en` in config.yml; `<plugin>` has more than one version on UltiCloud | Run `/upm install <plugin> <version>`, choosing an older version | Chat/console line reads `Installed! Please restart the server! Please be sure to delete the old version module!` (green); the installed JAR reports `<version>` after restart | server | |
| ultitools.upm.install-version.neg-not-found | `language: en` in config.yml | Run `/upm install <plugin> 0.0.0-does-not-exist` | Chat/console line reads `Install Failed!` (red) | server | |
| ultitools.upm.list | `language: en` in config.yml | Run `/upm list` (as console, or as a player for the click-to-install variant) | Page 1 of the UltiCloud catalogue is shown, ending with `======== Page 1 ========`; every installed plugin shows an install-state marker distinct from an uninstalled one | server | |
| ultitools.upm.list-page | `language: en` in config.yml; the catalogue has more than one page | Run `/upm list 2` | The footer line reads `======== Page 2 ========` and the listed entries differ from page 1's | server | |
| ultitools.upm.list-page.neg-bad-page | `language: en` in config.yml | Run `/upm list not-a-number` | Chat/console line reads `Failed to parse 'not-a-number' as Integer` (red); no plugin listing is produced | server | |
| ultitools.upm.uninstall | `language: en` in config.yml; `<plugin>` is currently installed | Run `/upm uninstall <plugin>` | Chat/console line reads `Uninstalled! Please delete local files manually, otherwise it will be enabled after restart!` followed by `File location: <plugins folder path>/plugins` (both green) | server | |
| ultitools.upm.uninstall.neg-not-found | `language: en` in config.yml | Run `/upm uninstall does-not-exist` | Chat/console line reads `Uninstall Failed! Please check if the spelling is correct!` (red) | server | |
| ultitools.upm.update | `language: en` in config.yml; a named module has an update available (appears after ultitools.upm.check) | Run `/upm update <module-name>` | Chat/console shows `Updating <module-name>...` then `Update successful! Please restart the server to apply.` (both, one yellow one green) | server | |
| ultitools.upm.update.neg-no-update | `language: en` in config.yml; the named module has no update available | Run `/upm update <module-name>` | Chat/console line reads `No updates available.` (green) | server | |
| ultitools.upm.versions | `language: en` in config.yml; `<plugin>` exists on UltiCloud | Run `/upm versions <plugin>` | Numbered version list is shown, the last entry followed by `Install command: /upm install <plugin> [version]` | server | |
| ultitools.upm.versions.neg-not-found | `language: en` in config.yml | Run `/upm versions does-not-exist-on-ulticloud` | Chat/console line reads `Could not fetch the version list!` (red) | server | |

## /ulticloud — cloud authentication

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
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
| ultitools.boot.plugin-load-order | At least two modules installed with a declared hard `@PluginDependency` between them | Restart the server and read the console output during startup | Console shows `[UltiTools-API] Plugin load order resolved successfully.`, and the dependent module's own `onEnable` log line appears after its dependency's | server | |
| ultitools.boot.plugin-load-order.neg-missing | One installed module declares a hard `@PluginDependency` on a module name that is not installed | Restart the server and read the console output during startup | Console shows `[UltiTools-API] A required plugin dependency is missing.` followed by the resolver's own message naming the missing dependency; every module without that dependency still loads and appears in `/ul list` | server | |
| ultitools.boot.plugin-load-order.neg-circular | Two installed modules declare a hard `@PluginDependency` on each other | Restart the server and read the console output during startup | Console shows `[UltiTools-API] Circular dependency detected among plugins.` followed by a `Loop: A -> B -> A`-shaped line naming both modules; every module outside the cycle still loads | server | |
| ultitools.boot.update-check | Server has outbound access to the update-check endpoint; a newer framework or module version exists | Restart the server and read the console output roughly one tick after startup completes | Console shows `[UltiTools-API] Checking for updates...` followed by one `UltiTools-API update available: <latest> (current: <current>)` or `<module> <current> -> <latest>` line per available update | server | |
| ultitools.boot.update-check.neg-none | No newer framework or module version exists | Restart the server and read the console output roughly one tick after startup completes | Console shows `[UltiTools-API] All plugins are up to date!` | server | |
| ultitools.listener.placeholderapi-bridge | PlaceholderAPI installed and enabled; a UltiTools-provided placeholder expansion (e.g. `player`) not yet registered | Join the server as any player | Within about 60 seconds the console shows the framework dispatching `papi ecloud download Player` (and, if any expansion needed downloading, a later `papi reload`) from the console sender | server | |
| ultitools.listener.update-notify | An OP player joins after ultitools.boot.update-check found at least one update, and has not yet been notified this session | Join the server as an OP player | Chat shows `[UltiTools] <N> update(s) available. Run /upm check for details.` (green prefix, yellow count); joining again in the same session does not repeat the message | server | |

## Scheduled tasks

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.task.player-cache-sweep | A module bean using `@PlayerCache` is registered and has at least one expired entry from a player who is no longer online | Wait for one 5-minute sweep interval, or read the console log for the sweep's own warning path on a forced failure | No `ConcurrentModificationException` or `Error running expiry sweep for <class>` warning appears in the console under normal operation; the expired entry is gone from the tracked bean's own state after the sweep | server | |

## Data persistence

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.storage.backend-select | `datasource.type: sqlite` in config.yml (shipped default) | Restart the server and read the startup console log | Console shows `Data Storage Method: sqlite`; a `data.db` file (or `<plugin>.db`) exists under the plugin's data folder | server | |
| ultitools.storage.backend-select.neg-fallback | `datasource.type: mysql` in config.yml but no reachable MySQL server configured | Restart the server and read the startup console log | The backend actually obtained falls back to `json`, and the console reports the requested backend and the one actually used are different, rather than silently reporting `mysql` | server | |
| ultitools.storage.restart-survival | A row written through a `DataOperator` while the server is up (any module command backed by `@Table`) | Stop the server completely, then start it again, then read the same row back through the same module command | The same value is returned after restart, in whichever backend `ultitools.storage.backend-select` is currently active | server | |

## Language

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.language.select | `language: en` in config.yml, server restarted after the change | Run `/ul help` | Output is the English text `=== UltiTools Commands ===` block, not the Chinese equivalent | server | |
| ultitools.language.select.neg-default | `language: zh` in config.yml (shipped default), server restarted after the change | Run `/ul help` | Output is the localized block from `lang/zh.json`'s help-text key, visibly different from the English `=== UltiTools Commands ===` block above, not a Chinese/English mix | server | |

## Panel capabilities

Every row below is exercised by toggling the named `ultipanel.capabilities.*` key, restarting or
reloading, then sending the corresponding panel message type (or reading `RemoteActionLog` for
the same effect without a live panel session). The refusal text checked below is
`Capability#configurableRefusal`'s literal wording, produced by
`Capability#refusalMessage()`.

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
