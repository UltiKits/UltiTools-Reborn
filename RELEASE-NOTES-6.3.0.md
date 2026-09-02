# UltiTools-API 6.3.0 Release Notes (draft)

**Status: draft, unreleased.** This document is prepared alongside Phase 7 (Generational
Removals) of the 6.3.0 milestone, on the `alpha` branch. It is not a release announcement — no
tag has been cut, no artifact has been published to Maven Central, and cutting the release
remains a separate, explicit maintainer decision. It exists so the release, when authorized,
has accurate notes ready rather than a reconstruction from memory.

**Core theme.** 6.3.0 is a correctness-and-consolidation release, not a feature release: several
annotations and API surfaces the framework declared were silently doing nothing (AOP interceptors
never wired in, `@ConditionalOnConfig` ignored on one path, config validation never enforced, a
declarative-GUI repaint pipeline that never repainted). This release wires them up, which is why
so many entries below are "behavioral changes" rather than API removals — the signatures did not
move, but what they now actually do did.

## Breaking: removed APIs

A downstream module JAR **compiled against 6.2.5 and not recompiled** either continues to load and
run unaffected, or fails in exactly one of the ways this section and
[`COMPATIBILITY.md`](COMPATIBILITY.md) describe: one `SEVERE` summary at module scan naming the
module and the skipped class, with the rest of the module unaffected. Full per-symbol reasoning,
replacements, and the exact downstream failure shape for every entry below live in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md); the generated, cumulative
list of every deprecated/announced/removed member is
[`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md).

### `AbstractCommandExecutor` — the one removal with real downstream users

`abstracts.AbstractCommandExecutor` and its misspelled empty `@Deprecated` shim
`AbstractCommendExecutor` are gone. This is the only removal in 6.3.0 with real, currently
maintained downstream users — 15 files across 6 repositories
(`Modules/UltiEconomy`, `Modules/UltiBot`, `Modules/UltiChat`, `Modules/UltiKits`,
`Modules/UltiMenu`, `Tooling/UltiTools-External-Example`) — and it is removed **ahead of those
repositories' own publication**, on an explicit maintainer decision recorded in
`compatibility/records/6.3.0.md` and `.planning/phases/07-generational-removals/07-DOWNSTREAM-PREFLIGHT.md`.
Merging this removal to `-SNAPSHOT` `alpha` is not releasing; **all six downstream modules must be
migrated, merged, and published before a 6.3.0 tag is cut** — that is now a hard precondition of
the 6.3.0 framework release gate, not something this removal itself satisfies.

Replacement: `abstracts.command.BaseCommandExecutor`. See `COMPATIBILITY.md`'s "Migrating off
`AbstractCommandExecutor`" section for the full migration guide.

**What an un-recompiled downstream JAR sees**, observed on a real Paper 1.21.1 server (plan 07-04)
and reproduced by this removal (plan 07-15):

```
SEVERE: Module '<module>' skipped N class(es) that failed to load during startup and continued
loading without them: <Command1>, <Command2>, .... This usually means the module was built
against an older UltiTools-API version -- see COMPATIBILITY.md for the list of APIs removed or
changed in this release.
```

The module still loads and enables — `Done (...)!` still prints — with exactly its
`AbstractCommandExecutor`-derived command classes silently absent from the command table, and this
one named `SEVERE` line pointing at `COMPATIBILITY.md`.

### Everything else: zero measured downstream references

Every other removal below was deprecated for at least one full MINOR release (or qualified for the
same-release exception described in `COMPATIBILITY.md`) and, at removal time, had zero measured
references across the 17 in-house module repositories, 4 tooling projects, and Libraries under the
UltiKits organization, and zero references across all 16 pre-built module JARs already deployed on
the maintainer's local dev server.

- **Version adaptation.** The `VersionWrapper` interface (all 14 default methods) and
  `DefaultVersionWrapper`, its implementation, are gone, along with all three
  `getVersionWrapper()` accessors (`UltiTools`, the static one on `UltiToolsPlugin`, and the
  `@Bean`-registered one on `UltiToolsBean`). Replacement: `utils.XVersionUtils`, a strict
  superset.
- **Data and GUI base classes.** `AbstractDataEntity` is gone; `BaseDataEntity<ID>` now owns its
  `id` field directly instead of inheriting it. `PagingPage` is gone
  (replacement: `BasePaginationPage`). `OkCancelPage` is gone (replacement:
  `BaseConfirmationPage`; the framework's own `InventoryConfirm` migrated in the same change).
- **Listeners and registration.** `TempListener.player(Class)`, its nested
  `TempListener$PlayerTempListenerBuilder`, and `PlayerTempListener` are gone (replacement:
  `TempListener.common(Class)` narrowed with `.filter(Function)`). `@OptionalParam` is gone with
  no replacement — it was never implemented and had no effect on command parsing.
- **Plugin construction and registration.** `UltiToolsPlugin`'s six-argument constructor is gone
  (replacement: the seven-argument constructor, passing `resourceFolderPath` explicitly).
  `PluginManager`'s seven-argument `register(...)` overload and its private with-args
  `initializePlugin` are gone — this overload never worked on any released version (Phase 1
  D-15) and used the same-release exception. `CommandManager`'s two `register(CommandExecutor,
  ...)` overloads and its `registerAll(UltiToolsPlugin, String)` are gone (replacement:
  `register(UltiToolsPlugin, Class, ...)` / `registerAll(UltiToolsPlugin)`).
  `ListenerManager.register(UltiToolsPlugin, Listener)` and
  `registerAll(UltiToolsPlugin, String)` are gone (replacement: `register(UltiToolsPlugin,
  Class)` / `registerAll(UltiToolsPlugin)`, the latter also honouring `manualRegister()`).
- **AOP.** `aop.CglibProxyFactory` is gone — it could never work on a Paper server without a JVM
  flag Paper does not set. `aop.ProxyFactory.createProxy(T)` / `createProxy(Class<T>, T)` and
  `aop.AopProxyBeanPostProcessor` are gone — neither reached a tagged release, or shipped with
  zero callers. `annotations.Propagation.NESTED` is gone — implementable, but dropped on
  controllability rather than impossibility (savepoint behaviour depends on whichever
  `sqlite-jdbc` version the server's own Paper build ships). Replacement for proxy creation:
  `aop.ProxyFactory.createProxyClass(Class<T>, Set<Method>)` via `AopProxyResolver`.

### Internal-only removal, not a public-API event

The seven-type WIRE-17 WebSocket dispatch cluster (`websocket.MessageHandlerRegistry`,
`websocket.WebSocketMessageHandler`, and the five `websocket.handlers.*` implementations) is
deleted. All seven carried `@ApiStatus.Internal` and none carried `@Deprecated`, so per
`COMPATIBILITY.md`'s own policy this is not a compatibility event and does not appear on the
removal list above — it is included here for completeness, because japicmp still needed exclusion
entries for it (bytecode does not see `@ApiStatus`). Replacement, for anyone who was reaching into
internal API anyway: `UltiPanelWebSocketClient`, the `PluginInitiationUtils` inbound-message
dispatch table, and `PanelResponderRegistry` for a module claiming its own request/response
responder.

## Behavioral changes (no signature change, different runtime behavior)

These do not show up in a diff of method signatures and `japicmp` cannot detect them. Full detail,
worked before/after examples, and measurement evidence for every item below are in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

**AOP and proxying.**

- The AOP engine switched from CGLIB to ByteBuddy; generated proxy class names changed shape
  (`Foo$$EnhancerByCGLIB$$xxxx` → `Foo$ByteBuddy$xxxx`). Detect a proxy with
  `ProxyFactory.isProxyClass(Class<?>)`, not by pattern-matching the class name.
- `@Transactional` moves from silently doing nothing to being wired end to end on all three
  storage backends. `rollbackFor`/`noRollbackFor` now resolve by shallowest-inheritance-depth
  match instead of `noRollbackFor` winning unconditionally. `REQUIRES_NEW`/`NOT_SUPPORTED` now
  genuinely suspend the outer transaction.
- `@ExceptionCatch` can now refuse to load a module: a `final` bean class fails if anything asks
  to intercept it, including an inherited `@ExceptionCatch` method.
- A `final` bean carrying an AOP annotation it merely inherits, and an unresolvable required
  `@Autowired` dependency, both now fail module load instead of degrading silently.
- Same-type bean resolution now adjudicates ties by `@Service(priority)` (higher wins) and
  refuses an exact tie rather than picking one arbitrarily.
- `registerSingleton` now fully assembles its argument through the same
  autowire/`@PostConstruct`/`BeanPostProcessor` pipeline as every other bean, and can refuse an
  AOP-annotated instance under the same rules.

**Commands.**

- `@CmdMapping` methods declared on a command executor's superclass are now registered — they
  were previously silently ignored.
- `@CmdTarget` class/method composition changed from an undocumented intersection-vs-override
  split between the two command-executor generations to one shared override-with-narrowing rule;
  a widening or lateral method-level override now refuses that command class at load.
- Command-validator side effects (cooldown ticking, usage-limit acquire/release) moved into the
  validator chain itself; an unenforceable `@CmdCD`/`@UsageLimit` now refuses to load.
  `BaseCommandExecutor#executeCommand` gained a fourth parameter as part of this change.
- `@UsageLimit` now actually serialises concurrent invocations as its name promises;
  `ContainConsole()`'s default flipped to `true`.
- `@CmdParam.suggest`'s resolution contract widened from three steps to four; an unknown `@key`
  now refuses to load instead of silently falling through.
- Tab completion now filters a `@CmdMapping` by permission before it can contribute a suggestion
  or be reflectively invoked.
- `@AsyncCommand.timeout()` is now honoured; the default path's double async dispatch is removed.

**Persistence and data.**

- `del()` called with no conditions is now refused outright (a security fix, no migration
  period) — it previously emptied the entire table.
- Entity ownership is now enforced: `getDataOperator(Class)` on another module's entity now
  throws `DataAccessException`/`ENTITY_NOT_OWNED` instead of silently handing over that module's
  real operator.
- A module JAR whose `plugin.yml` has no `name:` key now refuses to load instead of sharing
  `sqliteDB/unknown.db` with every other name-less module ever deployed.
- `TransactionManager.getConnection()`/`setIsolationLevel(int)`/`setReadOnly(boolean)` are now
  `@Deprecated` `default` methods rather than abstract ones; `DataStore.getOperator(...)` (both
  overloads) and four `SimpleTempListener` constructors are newly deprecated, not yet
  removal-eligible.

**Container, injection, and module loading.**

- `scanBasePackageClasses()` now takes effect; package-source resolution becomes additive rather
  than first-match-wins.
- The `eventListener`/`cmdExecutor`/`config` `@AliasFor` switches on `@UltiToolsModule` now take
  effect. `@ConditionalOnConfig` is now honoured on the listener package-scan path.
- Composed stereotype annotations more than one meta-level above `@Component` are now recognised.
- Config validation goes live: a violating value now refuses the affected module at load instead
  of loading with an invalid config silently accepted.
- A dependency cycle or missing dependency now refuses the affected modules instead of degrading
  silently. A module JAR failing validation never reaches the classpath.
- Both plugin registration entry points now produce identical containers; an external connector's
  beans now receive the connector's own `JavaPlugin`, not the framework's.
- `ComponentScanner`'s failure and skip diagnostics now carry a level and a stack trace.
- `supported()` is derived from shipped language files and now participates in language
  resolution.

**Declarative GUI.**

- The repaint pipeline actually repaints now — before 6.3.0, a change to widget state after
  first render silently never showed up. `GuiRenderer.initialize`'s signature changes to accept a
  `Supplier<Widget>` instead of a single `Widget`.
- A click on the player's own inventory (the lower half of a split view) can no longer reach a
  declarative-GUI click handler meant for the GUI's own inventory.
- `GridView` now positions any widget type using parent data written at render time, not just the
  types it originally special-cased.
- Two declarative-GUI builder-method pairs (`Container#background`/`Container.Builder#background`,
  `GridView#getMaxRows`/`GridView.Builder#rows`) are removed rather than given a guessed
  implementation — they were previously accepted and silently ignored.

**Remote/panel surface.**

- Eight `ultipanel.capabilities.*` switches now gate every panel-facing capability, with a split
  default (read capabilities default open, write/execute capabilities default closed).
- A recursive directory delete through the remote file API now requires an explicit
  `recursive: true`; the remote file `list` response now marks refused entries instead of
  silently omitting them.
- The remote command blocklist becomes fully operator-editable, with no unoverridable floor.
- `CommandExecutionManager.isCommandAllowed` and `FileOperationManager.isPathAllowed` change
  return type from `boolean` to `AccessDecision` (an internal, non-public-API change; recorded
  here because `japicmp` reports it, not because it is a compatibility event for module authors).

## Where to look for more detail

- [`COMPATIBILITY.md`](COMPATIBILITY.md) — version-number policy, when an API becomes eligible
  for removal, the two same-release exceptions, the `AbstractCommandExecutor` migration guide,
  and the two binary-incompatibility post-mortems this project has had before.
- [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md) — this cycle's full
  behavioural record: every item above, with its replacement, its evidence, and the exact
  downstream failure shape.
- [`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md) — the generated, cumulative
  registry of every deprecated, announced, and removed member across the project's history.

If your module references any removed API, or you disagree with this policy, please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues) before this release is tagged.
