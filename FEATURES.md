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
