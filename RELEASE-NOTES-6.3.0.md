# UltiTools-API 6.3.0 Release Notes

6.3.0 consolidates the framework's command, configuration, transaction, module-lifecycle and
panel integrations. These notes describe changes against the **released v6.2.5 contents**, not
commit ancestry. The module EventBus and declarative GUI already existed in 6.2.5; the changes
below repair and extend them rather than introduce them for the first time.

**Prepared for 6.3.0; not a publication announcement.** A formal version in the POM does not by
itself mean a tag, GitHub release or Maven Central artifact has been published.

## Before upgrading

- Back up the server configuration and module data. For JSON storage, back up `.ultitools-journal`
  together with the tables; never delete that journal, and run only one server per data folder.
- Install module builds compiled for UltiTools-API 6.3.0 with module `plugin.yml` declaring
  `api-version: 630`. **Do not assume a 6.2.5 module JAR is binary-compatible.**
- Replace the framework and affected module JARs with the server stopped, then restart.
  `/ul reload` reloads configuration, not JARs.
- The framework supports **Paper 1.19.2 build 163 or newer**, not plain Spigot. Use Java 17 below
  Paper 1.20.5, and Java 21 from Paper 1.20.5. Java 8 bytecode is not a Java 8 server-runtime promise.
- UltiKits, UltiLogin, UltiMail, UltiRemoteBag and UltiTrade require **Paper 1.21 or newer**;
  their inventory screens can fail on older supported framework servers (see known issues).

### Module catalogue availability

**As checked on 2026-10-20, `/upm` has no new 6.3.0 (`api-version: 630`) modules to offer.**
The public catalogue listed all 15 active modules; for every module,
`GET /plugin/<id>/latest?apiLevel=630&minApiLevel=630` returned HTTP 409 (no compatible version).
The same modules' unfiltered latest requests returned HTTP 200 with their older versions;
an invalid API-level request returned HTTP 400. This is an availability observation, not an
inference from a successful build or publication workflow.

Operators must wait for the compatible module builds to be republished to the catalogue before
installing them through `/upm`. A framework upgrade alone does not republish modules or make older
module builds compatible. Check catalogue availability again when upgrading.

## Breaking API changes and migration

Recompile downstream modules against 6.3.0 with the dependency kept `provided` (Maven) or
`compileOnly` (Gradle). The [upgrade checklist](COMPATIBILITY.md#upgrade-checklist-moving-a-module-from-625-to-630),
[removal registry](compatibility/DEPRECATIONS.md) and
[per-change record](compatibility/records/6.3.0.md) contain the full contracts and replacements.
Notable changes include:

- `AbstractCommandExecutor` and its misspelled `AbstractCommendExecutor` shim are removed.
  Extend `abstracts.command.BaseCommandExecutor` and implement `handleHelp(CommandSender)`.
- `UltiToolsPlugin.unregisterSelf()` and `reloadSelf()` are now `final`. Move overrides to
  `onUnregister()` and `onReload()` and remove calls to the old superclass lifecycle methods.
  An old main class overriding either final method is refused with `IncompatibleClassChangeError`;
  modules with a hard dependency on it are also refused.
- Removed types include `AbstractDataEntity` (use `BaseDataEntity<ID>`), `PagingPage` (use
  `BasePaginationPage`), `OkCancelPage` (use `BaseConfirmationPage`), `VersionWrapper` and
  `DefaultVersionWrapper` (use `XVersionUtils`).
- Removed registration APIs include the six-argument `UltiToolsPlugin` constructor, the old
  seven-argument `PluginManager.register(...)`, package-based command/listener registration
  overloads, and `ListenerManager.register(UltiToolsPlugin, Listener)`. Use the current constructors
  and class-based/module-based registration APIs listed in the registry.
- `TempListener.player(...)` and `PlayerTempListener` are removed; use `TempListener.common(...)`
  with a filter. The ineffective `@OptionalParam` annotation is removed without replacement.
- The old CGLIB factory and proxy-creation APIs are removed; proxy construction uses ByteBuddy
  through `AopProxyResolver`. `Propagation.NESTED` is removed.
- `UltiTools#getEconomy()` is removed; use `EconomyUtils.getEconomy()` or its wrapper methods.
  The archived Adventure audience provider is removed; use Paper's native Adventure audiences.
- `AbstractConfigEntity#getConfig()` is removed. Use `isPresentInFile(...)` for presence checks,
  declared fields for values, and the explicit save APIs below.
- The ineffective declarative GUI builder pairs `Container.background`/`getBackground` and
  `GridView.rows`/`getMaxRows` are removed. `GuiRenderer.initialize` now takes a `Supplier<Widget>`.
- Per-viewer log-stream `start`/`stop`/`pause`/`resume` APIs are removed rather than pretending to
  control delivery without browser identity; these remote actions are rejected.

There is no universal two-outcome rule for an old JAR: a class with an unavailable superclass can
be skipped during scanning, an override of a newly final lifecycle method can refuse the module,
and an old invocation of a removed method can fail only when executed with `NoSuchMethodError`.
Consult the exact per-symbol record rather than treating successful startup as proof of compatibility.

## Configuration and language files

- Operator-written configuration is not automatically repaired or overwritten. Invalid values
  are reported and refused (or use a declared default in memory where documented), not saved back
  as defaults. Unsupported or invalid constraints can refuse the affected module or reload.
- Configuration writes declare the keys they own and refuse a write if unrelated bytes cannot
  be preserved or the file changed since it was read. Read the warning, correct the named layout,
  or reload after an external edit, then retry. See the configuration chapter of `COMPATIBILITY.md`
  for supported YAML layouts, atomic-write fallback and recovery limits.
- `save()` persists only module changes. Operator commands use `saveOperatorChange(...)` or
  `saveOperatorMapEntry(...)` for the named changes; `OperatorFiles` covers module-managed files.
  **No configuration save happens automatically at shutdown, unload or replacement.**
- `ConfigConverter<T>` with `@ConfigConverterFor` is the new conversion extension point. Legacy
  parsers remain deprecated in 6.3.0 and are announced for removal in 6.4.0. Register converters for
  unsupported declared field types before configuration initialization.
- Configuration-bound `@Scheduled` and `@CmdCD` support live configuration values. Declare
  `api-version: 630`, keep bound scheduled methods synchronous and remove the bound field's
  redundant `@Range`; the binding defines its accepted range.
- To leave a text value blank, write `key: ''`; deleting a key requests its default on next start.
- Official language files are framework-owned and may be replaced on upgrade with a backup and
  log message. To customise text, copy a language file under a new name and select that copy;
  custom files remain operator-owned. Framework messages follow the selected `language`.

## Transactions, persistence and dependency injection

- `@Transactional` is wired through the AOP path for MySQL, SQLite and JSON. Rollback rules select
  the shallowest matching exception type; `REQUIRES_NEW` and `NOT_SUPPORTED` suspend the outer scope.
- JSON transactions stage changes, publish atomically across a plugin's tables and recover through
  the journal after a crash. Durability and filesystem-specific limits are documented in
  [JSON transactions](COMPATIBILITY.md#json-transactions-630--staged-atomic-and-durable).
  Ordinary writes and programmatic transaction reads can wait up to five seconds for table locks.
  For a read-modify-write in an annotated JSON transaction, use `updateIf` or `REPEATABLE_READ`.
- A joined `@Transactional` method that throws an exception requiring rollback under its rollback
  rules, or a failing nested `DataOperator#transaction(...)` scope, marks the whole transaction
  rollback-only even if its caller catches the exception. The outermost commit rolls back and
  throws `UnexpectedRollbackException`.
- JSON reads return detached copies; persist changes with `update(...)`. An unconditional `del()`
  is refused instead of deleting a whole table. Access to another module's entity operator is refused.
- ByteBuddy replaces CGLIB. Detect proxies through `ProxyFactory.isProxyClass(...)`, not class names.
  Required injection failures and unproxyable required AOP contracts refuse module load.
- `@Service(priority)` resolves same-type candidates (higher wins); an exact tie is refused.
  `registerSingleton` applies injection, lifecycle callbacks and bean post-processors.
- `scanBasePackageClasses`, composed stereotypes, the module's scan switches and conditional
  listener registration take effect. Missing dependencies and cycles refuse affected modules;
  a module JAR without `name:` is refused instead of sharing another module's storage.
- Name-based class-loading filters are removed; they were not a sandbox. Module JAR validation
  retains the 100 MB size and 10,000-entry limits. Install only modules you trust.

## Commands, tasks, GUI and module lifecycle

- **Synchronous commands now run at dispatch on the primary thread**, not one tick later.
  From an inventory click handler, defer inventory changes and command dispatch with `runTask`.
  Nested command dispatch is immediate; a command dispatching itself meets its usage lock/cooldown
  on the same tick, and uncontrolled recursion can overflow the stack. Async bodies remain async.
- Inherited `@CmdMapping` and `@Scheduled` methods are recognised. `@CmdTarget` uses override with
  narrowing; a widening or lateral override refuses the command class. Tab completion checks
  permission before contributing suggestions or invoking a suggestion method.
- Cooldowns are scoped to executor instances; `@UsageLimit` releases acquired locks on refused
  dispatches as well as completed ones. `ContainConsole()` now defaults to `true`.
  `@AsyncCommand.timeout()` stops waiting and reports the timeout; the body can continue in the background.
- Declarative GUI state changes now repaint, grid placement supports general widget types, and
  clicks in the player's inventory no longer reach GUI-only handlers.
- Unload/reload lifecycle hooks retain framework cleanup; `getPluginList()` returns an
  unmodifiable snapshot. Module replacement is observed at restart before the update is committed;
  an unsuccessful load can restore the retained previous JAR.
- A failed `@PlayerCache` save does not stop other quit cleanup. The entry is still removed and the
  framework does not retry the save: modules needing stronger guarantees must persist/retry themselves.

## UltiCloud and remote management

### Immediate owner-authenticated `/panel` link cutover

**Server-associated `/panel` magic-link creation requires the authenticated server owner immediately:
there is no anonymous fallback and no grace period.** Connect the server with `/ulticloud login`
before requesting a link. This does not require every player opening the link to own the server.
Ordinary website account login, browser magic-link verification and the in-game `/login` command
remain unchanged.

`UltiCloudRequests` sends `POST /auth/magic-link` and `GET /auth/magic-link/poll` with the server
credential. Poll by `requestId`, not player UUID; UUID-only polling is no longer supported.
Handle `NOT_CONNECTED` without retrying anonymously, and call the helper off the primary thread.
The [cloud-login guide](https://dev.ultikits.com/guide/advanced/cloud-login) documents this contract.

### Other remote changes

- Eight `ultipanel.capabilities.*` switches gate panel operations. Monitoring, logs, player events
  and file reads default on; file writes, deletions, command execution and server-property access
  default off. Enable only the capabilities the operator wants.
- Remote paths are resolved before use and constrained to editable roots; recursive deletion
  requires an explicit request. Deleting a symbolic link removes the link, not its target;
  recursive deletion does not follow links. Protected-file aliases are refused.
- The command blocklist is operator-editable. A malformed list is rejected as a whole with a
  warning and the defaults retained; an explicit empty list remains valid.
- Remote file refusals are recorded as `DENIED` in the remote action log. Server-property edits
  and other file writes use their respective capability switches.
- Cloud login/logout, token refresh and WebSocket work are scoped to one session: retired work
  cannot reactivate a logged-out session. Credentials move to the server's `.ultikits` credential
  store. If an old and a malformed current store coexist, neither identity is overwritten;
  correct the current document before retrying.
- The framework's log stream mirrors console output on Paper 1.20.5 and newer. On Paper 1.19.2
  to 1.20.4 the console mirror is unavailable: startup warns and the stream carries plugin lines
  only. Neither case fixes the panel browser's live-connection defects listed below.

## Known issues and workarounds

These limitations are tracked for 6.4.0. Module and panel entries are ecosystem limitations, not
claims that this framework release repairs those separately delivered products.

| Affected product / symptom | What to do meanwhile | Tracking |
|---|---|---|
| Framework: `/upm check` can say no updates during the first seconds of startup | Wait for the console's startup update-check result, then retry. | [#663](https://github.com/UltiKits/UltiTools-Reborn/issues/663) |
| Framework: a too-new module on Paper below 1.21 can be misreported as built against an older framework | Check the module's server requirement before blaming its framework build. | [#658](https://github.com/UltiKits/UltiTools-Reborn/issues/658) |
| UltiKits, UltiLogin, UltiMail, UltiRemoteBag and UltiTrade inventory screens can fail on older Paper | Run these five modules on Paper 1.21 or newer. | [#655](https://github.com/UltiKits/UltiTools-Reborn/issues/655) |
| Framework: a partial module reload repeats its result twice in the log and once in the reply | Read the first result; ignore the duplicates. | [#665](https://github.com/UltiKits/UltiTools-Reborn/issues/665) |
| Framework: reload/config wording may imply a restart or mix languages; YAML-anchor comments may expand after a language change | Correct the named invalid value and run `/ul reload`; the refused panel edit wrote nothing and needs no restart. | [#598](https://github.com/UltiKits/UltiTools-Reborn/issues/598) |
| UltiEconomy: a process stop between debit and credit can lose a transfer without a log entry | No module workaround; after an unclean stop, check reported missing transfers and correct balances manually. | [#44](https://github.com/UltiKits/UltiEconomy/issues/44) |
| UltiEconomy: transfer/refund failure replies can be misleading; a note redemption without a balance row can give no reply and retain the note | After a database problem, inspect balances and logs before paying again. | [#45](https://github.com/UltiKits/UltiEconomy/issues/45) |
| UltiLogin: console/panel `logadmin reset` can expose the new password in logs | Reset passwords in game, where logging is masked, and keep logs private. | [#57](https://github.com/UltiKits/UltiLogin/issues/57) |
| UltiWorlds: failed PvP persistence can leave the live flag changed until another player enters | Repeat the command and confirm the live and saved values agree. | [#58](https://github.com/UltiKits/UltiWorlds/issues/58) |
| Panel: plugin configuration editor is blank/unavailable | Edit configuration files directly on the server. | [#123](https://github.com/UltiKits/UltiPanelFrontend/issues/123) |
| Panel: Settings says saved before the server accepts; some of its 14 settings do not exist on every server | Check the actual server setting; do not treat the success message as confirmation. | [#124](https://github.com/UltiKits/UltiPanelFrontend/issues/124), [#125](https://github.com/UltiKits/UltiPanelFrontend/issues/125) |
| Panel: no browser live push, no remote command output, and the management status strip says Disconnected even when online | Read output in the server console and use the dashboard for online state. | [#127](https://github.com/UltiKits/UltiPanelFrontend/issues/127), [#128](https://github.com/UltiKits/UltiPanelFrontend/issues/128), [#131](https://github.com/UltiKits/UltiPanelFrontend/issues/131) |
| Panel: a refused command still reports Command execution request sent | Check `ultipanel.capabilities.commands` and the server console; commands default off. | [#55](https://github.com/UltiKits/ultipanel-api-worker/issues/55) |
| Panel: legacy paid activation-code redemption for permanent Pro is unavailable or fails | No verified self-service workaround. Eligible holders' historical permanent Pro entitlement remains supported; the complete repair is deferred to 6.4.0 and redemption may remain unavailable until then. The historical inventory's authority and completeness are not established. | [#60](https://github.com/UltiKits/ultipanel-api-worker/issues/60) |
| Developer CLI: `ultikits versions list` prints undefined fields | Look up module versions in the panel or catalogue web page. | [#2](https://github.com/UltiKits/ultikits-cli/issues/2) |

## Further reference

- [COMPATIBILITY.md](COMPATIBILITY.md): migration checklist, configuration write rules, support
  matrix, transaction durability limits and runtime dependency ownership.
- [compatibility/records/6.3.0.md](compatibility/records/6.3.0.md): exact removals and runtime changes.
- [compatibility/DEPRECATIONS.md](compatibility/DEPRECATIONS.md): cumulative generated registry.
- XSeries is no longer shaded into the framework; modules using it declare it themselves as
  `provided`/`compileOnly`. Paper loads the framework's declared libraries and supplies Gson,
  MySQL Connector/J, protobuf and slf4j. The framework's build uses JDK 21 with Java 8 bytecode;
  it does not establish a separate runtime floor for dependencies supplied by Paper.
