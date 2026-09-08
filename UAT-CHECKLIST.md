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
