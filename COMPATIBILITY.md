# Compatibility and Versioning Policy

[English](#compatibility-and-versioning-policy) · [中文](#兼容性与版本策略)

This document explains what the version numbers of `com.ultikits:UltiTools-API` mean, how
deprecation and removal work, and which removals are currently scheduled. It is written for
downstream module authors.

## What the version number means

**This project's version numbers are a product-stage signal, not a strict semver contract.**

- **PATCH** (for example 6.2.4 → 6.2.5): small updates and urgent fixes. No public API is removed.
- **MINOR** (for example 6.2.x → 6.3.0): feature evolution. **May include removal of public API.**
- **MAJOR**: reserved for a change of direction at the framework level. It will not be issued
  merely to clean up deprecated API.

### When a public API becomes eligible for removal

An API is placed on the removal list only when both of the following hold:

1. It carries `@Deprecated(since = "…", forRemoval = true)` in the source;
2. At least one MINOR release has passed since **the first release that carried that annotation**.

The clock in condition 2 starts at the version where the warning actually reached you, not at the
value written in `since`. `since` expresses when we consider the API to have become deprecated and
can be backfilled; the starting point cannot. The tables below name that version for every entry.

These two conditions are the complete basis for removal. Downstream usage counts are no longer part
of it. The usage figures included in the tables are informational: they tell you which entries carry
real migration cost, but "zero references" only proves that nobody in the repositories we can see
uses it, not that no third party outside the organization does.

`forRemoval` is used rather than a bare `@Deprecated` because javac's `-Xlint:removal` has been **on
by default since JDK 9**, while `-Xlint:deprecation` is **off by default**. An API marked
`forRemoval` is reported by name at every use site in your build; one marked only `@Deprecated`
produces a single summary line with no API name and no line number. We therefore treat "you were
warned by name" as a precondition for removal.

#### Exception: removal in the same release that announces it

The two conditions above are the rule for the ordinary case: a working API that downstream code may
reasonably depend on. They have exactly one general exception, stated here once rather than argued
case-by-case at each occurrence: **an API may be removed in the same release that first carries
`@Deprecated(forRemoval = true)` on it — skipping condition 2 entirely — when at least one of these
two clauses holds, and the removal's own entry states which one and its evidence:**

1. **The API is proven non-functional on the currently released version.** "Proven" means a
   **reproduction** — a run whose output shows the API failing on the version currently published —
   quoted in that removal's own entry. An argument that the API *looks* broken, without a run that
   shows it, does not satisfy this clause. Loosen this standard even once and the clause stops being
   an exception and becomes a second, unwritten removal window that retires the one-MINOR window a
   use at a time, each individual use looking reasonable on its own.
2. **The API was never published in a tagged release, or was shipped but never wired into anything
   that calls it.** There is no working behaviour for a deprecation window to warn anyone away from,
   because no released version could ever have exercised it.

The three AOP removals in the [AOP](#aop) table above are existing instances of this rule, not new
permissions granted by writing it down: `aop.CglibProxyFactory` under clause 1
(`--add-opens java.base/java.lang=ALL-UNNAMED` is not a flag a Paper server sets, so the class
throws `ExceptionInInitializerError` on first use — the constructor throwing on every call is
itself the reproduction), and `aop.ProxyFactory.createProxy(T)`/`createProxy(Class<T>, T)` plus
`aop.AopProxyBeanPostProcessor` under clause 2 (neither ever reached a tagged release, or shipped
but had zero callers in `src/main`).

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
([中文](https://dev.ultikits.com/zh/guide/advanced/module-versioning.html)).

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

## Declaring the dependency

Maven:

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version><!-- see GitHub Releases --></version>
    <scope>provided</scope>
</dependency>
```

Gradle:

```groovy
compileOnly 'com.ultikits:UltiTools-API:<version>'
```

**Use `provided` / `compileOnly`.** The POM published to Maven Central is processed by
flatten-maven-plugin and carries no dependency declarations, so placing UltiTools-API in any scope
beyond compile time (Maven `compile`, Gradle `implementation`) gains you no transitive dependencies.
What it does do, if your build has a shade or shadow step, is bundle the entire shaded framework into
your module JAR, where it conflicts at runtime with the UltiTools already installed on the server.

## Removal list for 6.3.0

**This list is generated from the `@Deprecated(forRemoval = true)` annotations in the source and is
no longer maintained by hand.** The annotation is the single source of truth; this document no longer
lists types that carry no annotation. Packages and types marked `@ApiStatus.Internal` are not
included — they were never public API, so removing them is not a compatibility event.

"Removal announced in" is the release in which the API first carried the `forRemoval` annotation,
that is, the first version in which your build named it in a warning.

### Commands

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| `abstracts.AbstractCommandExecutor` | 6.2.1 | `abstracts.command.BaseCommandExecutor` | **15 files / 6 repositories** |
| `abstracts.AbstractCommendExecutor` (misspelled empty shim) | 6.2.5 | same as above | 0 |
| Both overloads of `manager.CommandManager.register(CommandExecutor, …)` | 6.2.5 | `register(UltiToolsPlugin, Class, String, String, String…)` | 0 |
| `manager.CommandManager.registerAll(UltiToolsPlugin, String)` | 6.2.5 | `registerAll(UltiToolsPlugin)` | 0 |
| `annotations.command.@OptionalParam` | 6.2.5 | No replacement. The annotation was never implemented and applying it does not affect parsing; write one `@CmdMapping` per acceptable argument shape instead | 0 |

### Version adaptation

The whole `interfaces.VersionWrapper` cluster is superseded by `utils.XVersionUtils`, which is a
superset of it.

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| `interfaces.VersionWrapper` and its 14 default methods | 6.2.5 | `utils.XVersionUtils` | 0 |
| `interfaces.impl.DefaultVersionWrapper` | 6.2.5 | same as above | 0 |
| `UltiTools.getVersionWrapper()` | 6.2.5 | same as above | 0 |
| `abstracts.UltiToolsPlugin.getVersionWrapper()` (static) | 6.2.5 | same as above | 0 |

### Data and GUI base classes

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| `abstracts.AbstractDataEntity` | 6.2.1 | `abstracts.data.BaseDataEntity<ID>` | 0 |
| `abstracts.guis.PagingPage` | 6.2.1 | `abstracts.gui.BasePaginationPage` | 0 |
| `abstracts.guis.OkCancelPage` | 6.2.1 | `abstracts.gui.BaseConfirmationPage` | 0 |

### Listeners and registration

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| `interfaces.TempListener.player(Class)` and `TempListener.PlayerTempListenerBuilder` | 6.2.5 | `TempListener.common(Class)`, narrowed to player events with `filter(Function)` | 0 |
| `interfaces.impl.PlayerTempListener` | 6.2.5 | same as above | 0 |
| `manager.ListenerManager.register(UltiToolsPlugin, Listener)` | 6.2.5 | `register(UltiToolsPlugin, Class)` — the old overload takes an already-constructed instance and therefore performs no dependency injection | 0 |
| `manager.ListenerManager.registerAll(UltiToolsPlugin, String)` | 6.3.0 | `registerAll(UltiToolsPlugin)` — resolves listeners as beans from the module's own container instead of package-scanning, and honours `manualRegister()` where the package-scan overload does not (GEN-05, #337) | 0 |

### Plugin base class

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| The six-argument `abstracts.UltiToolsPlugin(String, String, List, List, int, String)` constructor | 6.2.5 | The seven-argument constructor, passing `resourceFolderPath` explicitly (the six-argument overload hard-codes it to `<dataFolder>/pluginConfig/<pluginName>`) | 0 |
| `manager.PluginManager.register(Class, String, String, List, List, int, String)` (seven-argument overload) | 6.3.0 | `register(UltiToolsPlugin)` — construct the plugin instance yourself; this overload has failed on every release since 6.2.0 (Phase 1 D-15). Measured at HEAD: `SecurityPolicy#isSafeParameterType` rejects the two `List`-typed constructor arguments (`authors`, `loadAfter`) by exact runtime-class-name prefix before construction is ever attempted — `Arrays.asList(...)`'s runtime type never matches `java.util.List`/`ArrayList`/`HashMap`/`HashSet` literally (#332) | 0 |

How the reference counts were measured: on 2026-08-14, across 17 module repositories, 4 tooling
projects and Libraries under the UltiKits organization, covering 310 Java files, excluding test
directories and build output, counting only imports and `extends`.

The `6.2.1` starting point is a deliberately conservative choice, not a consequence of 6.2.0 being
unverifiable. 6.2.0 was published to Maven Central; it simply has no corresponding git tag, though it
does have a release commit in the repository history (`0286e26 release: UltiTools-API v6.2.0`) that
can be checked. Setting the start at the later 6.2.1 only lengthens the deprecation period and
favours downstream, so it stays.

One verification note worth recording: the release list for this project is Maven Central's
`maven-metadata.xml`, **not `git tag`** — there is no `v6.2.0` among the tags. Inferring "this
version was never released" from the tag list produces a wrong conclusion in this repository.

**If your module references any entry above, please open an issue before 6.3.0 ships and we will
reassess.** The reference counts in the tables are not the basis for removal, and do not support a
conclusion that nobody is using something.

### AOP

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| `aop.CglibProxyFactory` | 6.3.0 | `aop.ProxyFactory` — same constructor shape (a list of interceptors); its `createProxy` methods have no direct replacement, see the next two rows | 0 |
| `aop.ProxyFactory.createProxy(T)` and `createProxy(Class<T>, T)` | 6.3.0 | `aop.ProxyFactory.createProxyClass(Class<T>, Set<Method>)`, used through the container. Proxy creation moved to *before* the bean is constructed (issue #190: a `BeanPostProcessor` only ever sees an already-built instance, which is structurally too late) | 0 |
| `aop.AopProxyBeanPostProcessor` | 6.3.0 | `aop.AopProxyResolver`, consulted by the container before construction instead of after | 0 |
| `annotations.Propagation.NESTED` | 6.3.0 | No direct replacement — the six remaining constants (`REQUIRED`, `REQUIRES_NEW`, `SUPPORTS`, `NOT_SUPPORTED`, `MANDATORY`, `NEVER`) are exactly Jakarta Transactions 2.0's `TxType` set | 0 |

All four entries above are removed in the same release that announces them, which the policy
above normally does not allow, and for two different reasons:

- `aop.CglibProxyFactory` could not work on any supported server: it requires
  `--add-opens java.base/java.lang=ALL-UNNAMED`, a flag a Paper server does not set and a plugin
  cannot add. Keeping it through a deprecation cycle would preserve an API that throws
  `ExceptionInInitializerError` on first use. See issue #188.
- `aop.ProxyFactory.createProxy(T)`/`createProxy(Class<T>, T)` and `aop.AopProxyBeanPostProcessor`
  are a different case: both worked, but neither was ever published. `aop.ProxyFactory` itself was
  added after 6.2.5, within this same still-unreleased 6.3.0 development cycle (PR #305), so its
  `createProxy` methods never reached a tagged release. `AopProxyBeanPostProcessor` is older
  (`@since 6.2.0`, and it did ship in 6.2.1 through 6.2.5), but it was never wired into the
  container — `addBeanPostProcessor` had zero callers in `src/main` — so removing it breaks no
  working integration. Both were superseded within this cycle by `AopProxyResolver`, which resolves
  the proxy class before the bean is constructed; a `BeanPostProcessor` cannot do that by interface
  contract, since both of its callbacks take an already-built instance. Neither had any downstream
  references. See issue #190.
- `annotations.Propagation.NESTED` uses clause 2 for a reason distinct from the other three: all
  seven original `Propagation` values, `NESTED` included, *are* implementable against the storage
  layer — `NESTED` maps cleanly to `Connection.setSavepoint()`. It is dropped on
  **controllability, not impossibility**: savepoint behaviour depends on whichever `sqlite-jdbc`
  version the server's own Paper build happens to ship (`org.xerial:sqlite-jdbc` is not declared in
  this project's `pom.xml` or `plugin.yml` — Paper supplies it — and versions `3.45.3.0`, `3.46.0.0`
  and `3.47.0.0` have all been observed across local server caches), which this project can neither
  pin nor test across. Clause 2's evidence, stated rather than argued: `@Transactional` has never
  executed in any released version — the published 6.2.5 jar's `AopProxyBeanPostProcessor` is
  referenced only by itself, and `PluginManager` has zero references to `AopAdvisor`, `addAdvisor`,
  or `TransactionInterceptor` — so no released server ever evaluated `NESTED` regardless of this
  reasoning. Across 17 in-house module/plugin repositories, `TransactionManager` /
  `@Transactional` / `.transaction(` score **zero** hits, against a control of **94** hits for
  `getDataOperator` (confirming the survey mechanism itself works). `NESTED` may be restored later
  if savepoint behaviour ever becomes reliably pinnable across supported Paper builds.

## Migrating off `AbstractCommandExecutor`

`abstracts.AbstractCommandExecutor` is the only entry on the list with real downstream users
(15 files measured). It will be removed in 6.3.0, and **downstream migration will be coordinated
within the same release cycle as the removal.**

To migrate to `abstracts.command.BaseCommandExecutor`:

1. Change the superclass: `extends AbstractCommandExecutor` → `extends BaseCommandExecutor`.
2. Implement the new abstract method `protected void handleHelp(CommandSender sender)`.
3. `@CmdMapping` / `@CmdParam` / `@CmdTarget` / `@CmdCD` / `@UsageLimit` keep their semantics.
4. Commands registered through `@CmdExecutor` package scanning must move to explicit registration:
   the scanning path casts the new base class to the old one. The main loading path, which resolves
   commands through the IoC container, is unaffected.

The misspelled empty shim `AbstractCommendExecutor` extends `AbstractCommandExecutor`, so it **must
be removed in the same release as its parent**; keeping it alone is not an option.

The new base class has one known gap:

| Gap | Scheduled for |
|---|---|
| Parameter-level tab completion is not wired up | **6.3.0** (ahead of the maintainer's migration of downstream repositories) |

(A bare command declared with `@CmdMapping(format = "")` was not executable. This was previously
scheduled for 6.2.5 and **has been fixed in 6.2.5**.)

When to migrate depends on who you are:

- **Modules inside the UltiKits organization** will be migrated by the maintainer during the 6.3.0
  cycle. You do not need to do anything.
- **Third-party modules outside the organization** should migrate **during 6.2.5**. This carries one
  cost: parameter-level tab completion is not wired up until 6.3.0, so until then a migrated command
  only completes literals at the first argument position. It is nonetheless the only approach that
  leaves you a real transition window — the release that completes tab completion (6.3.0) is the same
  release that removes the old base class, so migrating at that point leaves no buffer at all.

## Behavioral changes

Some changes leave every method signature untouched yet alter how your module behaves at runtime: a
method that used to return silently starts throwing, a default value flips, a missing optional
dependency turns from degraded operation into a failed load. Signature comparison tools cannot detect
these, and the removal list above does not cover them. This section explains how we handle them.

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

The removal list and the versioning rules govern the *intentional* part of the first two kinds. For
unintentional binary breakage see
[Binary incompatibilities the removal list cannot cover](#binary-incompatibilities-the-removal-list-cannot-cover).
This section governs the third kind.

### Behavioral changes that need no migration period

- Correcting behaviour that plainly contradicts the documentation (the docs say it returns
  `Optional.empty()`, the code throws an NPE).
- Tightening the handling of previously undefined input (passing `null` used to be undefined
  behaviour, now it throws `IllegalArgumentException`).
- Changes in performance, memory footprint, log wording, or exception message text.
- Security fixes. These may land in a PATCH without prior notice.

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
  This behaviour will change to <new behaviour> in 6.4.0. See <issue link> to migrate.
  This warning is printed once per startup.
  ```

- **Version N+1**: switch to the new behaviour and remove the warning.

This section follows [PEP 387](https://peps.python.org/pep-0387/); the principle is the same one:
tell people which floor they are standing on before removing it.

### Recorded instance: AOP proxy class naming (6.3.0)

The AOP engine switched from CGLIB to ByteBuddy in 6.3.0 (see [AOP](#aop) above). Public method
signatures on proxied beans are unchanged, but the class name ByteBuddy generates for a proxy
differs from CGLIB's: `Foo$$EnhancerByCGLIB$$xxxx` became `Foo$ByteBuddy$xxxx`. The exact shape of
a generated proxy class name was never part of the documented contract, so this falls under "no
migration period" above — but it is worth recording because it is real breakage for any downstream
code that detected a proxy by pattern-matching the class name, the way this framework's own
`TaskManager` used to. Code doing that must switch to the supported check,
`com.ultikits.ultitools.aop.ProxyFactory.isProxyClass(Class<?>)`.

### Recorded instance: `@CmdMapping` methods on a command executor's superclass are now registered (6.3.0)

Before 6.3.0, `BaseCommandExecutor.scanCommandMappings()` and the deprecated
`AbstractCommandExecutor.scanCommandMappings()` scanned only `this.getClass().getDeclaredMethods()`,
so a `@CmdMapping` method declared on a command executor's **superclass** was silently never
registered — the subcommand simply did not exist. 6.3.0 fixes this as part of the AOP proxy work
(issue #190: an inheritance-based proxy only overrides the methods it intercepts, so any consumer
that scanned `getDeclaredMethods()` directly lost annotations on the rest of the class). The fix
walks the full hierarchy, so a `@CmdMapping` method on a superclass is now registered like any
other.

Method signatures are unchanged, so `japicmp` cannot detect this. It is recorded here because it is
real behavioral breakage for a downstream module that (knowingly or not) relied on a base-class
`@CmdMapping` method staying unregistered — for example, an abstract command base class shared
across modules that declares a subcommand meant to be opt-in per subclass. Such a module must
remove or rename that method before upgrading to 6.3.0, or it will start responding to a subcommand
it previously ignored. This framework's own command classes (`PluginInstallCommands`,
`UltiToolsCommands`, `CloudLoginCommand`) were checked and are unaffected: none of their
superclasses declare their own `@CmdMapping`.

This falls under "no migration period" above: the previous behaviour — a declared `@CmdMapping`
method silently not being registered — was never a documented contract, so there is no default or
timing to phase out. It is recorded for the same reason as the AOP proxy naming change above: real
breakage for code that depended on the gap, even though the gap itself was never guaranteed.

### Recorded instance: `@CmdTarget` class/method composition is now override-with-fail-on-widening (6.3.0)

Before 6.3.0 the two command-executor generations disagreed with each other about what a
class-level `@CmdTarget` combined with a method-level one actually meant. The deprecated
`abstracts.AbstractCommandExecutor` path ran an **intersection**: the class-level check and the
method-level check both had to pass independently. The current `abstracts.command.BaseCommandExecutor`
path, through `SenderTypeValidator.determineTargetType`, already ran an **override**: whenever a
method carried its own `@CmdTarget`, the class-level value was ignored entirely, with no widening
check at all. 6.3.0 makes both generations share one rule,
`abstracts.command.validation.CmdTargetComposition`: the method-level value **overrides** the
class-level one when it narrows, and any combination that is not a narrowing — a class-level
restriction widened by a method-level `BOTH`, or switched laterally between `PLAYER` and `CONSOLE`
— is refused rather than silently resolved either way.

**Worked example, built from a real command class.** UltiKits's own UltiMenu module ships
`com.ultikits.plugins.menu.commands.MenuCommands`, on the deprecated `AbstractCommandExecutor`
generation, class-level `@CmdTarget(BOTH)`, with its `open <name>` mapping narrowed to
`@CmdTarget(PLAYER)`:

- **On 6.2.5** (intersection): a console sender running `/menu open <name>` is checked against
  `BOTH` (class-level, passes) AND `PLAYER` (method-level, fails) — the AND fails, so the command
  is **refused**.
- **After 6.3.0** (override with narrowing): the method-level `PLAYER` value narrows the
  class-level `BOTH`, so the effective restriction is `PLAYER` — a console sender is **refused**,
  the same outcome.

For every mapping that only narrows — which `MenuCommands` exclusively does — intersection and
override-with-narrowing always agree: a set intersected with its own subset equals the subset,
which is exactly what override-with-narrowing resolves to. This is why the two generations could
disagree on the *general* rule for years while every downstream command class that only narrows
never noticed the difference.

**The mirror case.** `UltiSideBar`'s `com.ultikits.plugins.sidebar.commands.SideBarCommand` carries
the identical annotation shape — class-level `@CmdTarget(BOTH)`, with `toggle`, `on` and `off` each
narrowed to `@CmdTarget(PLAYER)` — but on the **current** `BaseCommandExecutor` generation, which
already ran override before this change. Its resolved sender restriction is identical to
`MenuCommands`'s. A class migrating from `AbstractCommandExecutor` to `BaseCommandExecutor` without
editing its `@CmdTarget` annotations therefore resolves to the same sender restriction on both
sides of the migration, as long as it only narrows — which is the common case.

**What does change.** Two shapes stop registering entirely: a class-level restriction *widened* by
a method-level `BOTH` (for example class-level `PLAYER`, method-level `BOTH`), and a class-level
restriction *switched laterally* by a method-level opposite value (class-level `PLAYER`,
method-level `CONSOLE` — `CmdTargetType` is a three-value enum, not a total order, so this is
neither a narrowing nor a widening). Before 6.3.0 these were ambiguous and generation-dependent:
intersection could resolve them to a non-empty or an empty set depending on the values involved,
while override just took the method's value with no widening check at all. From 6.3.0,
`ComponentScanner` refuses to register **that command class only** at plugin load — the error
names the class and the offending method, and the rest of the module still loads. If your command
class has a mapping whose method-level `@CmdTarget` widens or laterally switches the class-level
one, edit the annotations so the method narrows or matches the class-level value.

### Recorded instance: the six-argument connector constructor has failed on every release since 6.2.0

This is not itself a 6.3.0 change — it is recorded here because it is the evidentiary basis for
that constructor's [same-release removal exception](#when-a-public-api-becomes-eligible-for-removal)
below, and because a downstream connector author deserves the same evidence a maintainer would
need, not a bare "it doesn't work" assertion.

`abstracts.UltiToolsPlugin`'s six-argument constructor (`(String, String, List, List, int, String)`)
is reached through `manager.PluginManager.register(...)`'s with-args branch inside
`initializePlugin`. Before 6.2.0, `initializePlugin` used Spring's
`AnnotationConfigApplicationContext.registerBean(pluginClass, constructorArgs)`, whose constructor
resolver does compatible type matching — unboxing and widening included. Release commit `0286e26`
(v6.2.0) replaced that with hand-written matching that is still present verbatim:

```java
paramTypes[i] = constructorArgs[i].getClass();
// ...
Constructor<? extends UltiToolsPlugin> constructor =
    pluginClass.getDeclaredConstructor(paramTypes);
```

`getDeclaredConstructor` requires an **exact** parameter-type match. Measured, not inferred: a
probe reproducing the six-argument call shape yields
`paramTypes = [String, String, ArrayList, ArrayList, Integer, String]` against a constructor
declared `(String, String, List, List, int, String)`. Three of the six parameter positions can
never match: `ArrayList` is never assignable-checked against the declared `List` parameter — the
declared type must match literally, not merely be assignable — twice; and the autoboxed `Integer`
never equals the declared primitive `int`, once. `getDeclaredConstructor` therefore always throws
`NoSuchMethodException` for this call shape.

The failure is swallowed on its way out: the reflective exception is wrapped in an
`IllegalStateException` (`PluginManager.java:738`), caught by `register(...)`'s own
`catch (Exception | Error e)` (`:178`), logged at `WARNING`, and turned into a `false` return. The
caller sees no exception — only a boolean.

**The overload has therefore failed 100% of the time on every release since 6.2.0.** There is no
argument shape by which the six-argument call can ever satisfy `getDeclaredConstructor`'s
exact-match requirement. Connector authors hitting this should use
`api.UltiToolsAPI.connect(JavaPlugin)` instead, which does not go through reflective constructor
resolution at all.

### Recorded instance: `del()` with no conditions is now refused (6.3.0)

Before 6.3.0, `AbstractRelationalDataOperator.del()` built its `DELETE FROM <table>` statement from
whatever `WhereCondition`s it was given, with no check that there were any. Calling `del()` with a
zero-length varargs array — a bare `dataOperator.del()`, no arguments at all — produced a
WHERE-less `DELETE FROM <table>` and emptied the entire table in one statement. An explicit
`del((WhereCondition[]) null)` reached the same code path.

6.3.0 refuses both shapes outright: `del()`'s zero-condition guard is the literal first statement
of the method, ahead of building the SQL `StringBuilder` and ahead of touching the operator's
`QueryRunner` at all — proven against a Mockito spy (zero interactions recorded after the throw),
not merely the absence of a thrown exception. A `del(condition)` call carrying at least one real
condition is unaffected.

**This takes the security-fix channel** ("Behavioral changes that need no migration period"
above), not the two-step migration period the "silent degradation becoming failure" bucket would
otherwise call for. The case-specific reason, not a generic "it's a bug fix" appeal: an
unconditioned `del()` is an **unbounded data-loss primitive** reachable from any code that holds a
`DataOperator` — one missing argument, or one `WhereCondition[]` built from a collection that
happened to be empty at runtime, and the entire table is gone, with no transaction boundary on
most call paths that could undo it. That is qualitatively different from a bug whose worst case is
a wrong query result; here the worst case is unrecoverable data loss triggered by an easy-to-write
mistake. Continuing to allow it for one more MINOR under a warning is not a safer default than
refusing it now — the failure mode a warning would be protecting against is "I read the warning
and kept shipping code that empties tables on typos," which a louder log line does not meaningfully
improve.

A control-grouped survey (both a negative and a control query, per this project's own search-hazard
rule) found no shipping caller relying on the old behaviour: `src/main` — 0 hits for a zero-argument
`.del()`/null-array call, against 1 control hit (an unrelated `FileUtils.del(file)`); `src/test` —
0 hits, against 11 control hits on `.del(` calls carrying real conditions; the 17 in-house
`Modules/*` repositories — 0 hits, against 42 control hits on `.insert(` (confirming the grep
mechanism itself works); the legacy, pre-6.2.0 `Plugins/` tree — 0 hits on both queries.

### Recorded instance: `@Transactional` moves from refusing to load a module to being wired (6.3.0)

**Through the refusal described in earlier `6.3.0-SNAPSHOT` builds of this file, `@Transactional`
was recognised by the framework's annotations but never wired to anything real.** No AOP container
integration existed at all before this milestone, so a bean carrying `@Transactional` loaded
normally and ran completely untransacted, with no warning that the annotation had done nothing.
Rather than leave that silent no-op in place once AOP itself started working elsewhere in 6.3.0's
development, `PluginManager.wireAop` declared `@Transactional` explicitly **unavailable**:
`AopProxyResolver.rejectUnavailableAnnotations` refused to load any bean carrying it — including a
method or class merely inherited or extended, not just one you wrote yourself — logging a WARNING
and returning `false` from `register(...)` instead. That refusal is now withdrawn.

**6.3.0 ships `@Transactional` wired end to end, on all three storage backends:**

- **SQLite and MySQL**, through `JdbcTransactionManager` — one manager instance per plugin
  container, shared between the AOP interceptor and every `DataOperator` the store hands out
  (`SQLiteDataStore.transactionManagerFor(DataScope)` / `MysqlDataStore.transactionManagerFor(DataScope)`,
  each backed by a per-identity cache), so a `@Transactional` method that calls
  `insertAll`/`updateAll` opens exactly one transaction, not two. SQLite is keyed by the plugin's
  own backing `.db` file (one manager per file, mirroring the one-pool-per-file fix elsewhere in
  this release); MySQL is keyed by requesting identity, since that backend has one global
  `DataSource` shared by every plugin.
- **JSON**, through `JsonTransactionManager` — a snapshot-based manager: on first touch inside a
  transaction, an operator's cache is deep-copied; on rollback, the whole cache is restored from
  that snapshot. **What this does not guarantee:** the restore replaces an operator's *entire*
  cache, not individual entities. `REQUIRES_NEW`/`NOT_SUPPORTED`'s independence from an outer scope
  is therefore only observable when the inner and outer scopes touch *different*
  `SimpleJsonDataOperator` instances — if both write to the *same* operator, the outer scope's
  eventual rollback restores that operator's cache to its pre-outer-write snapshot, discarding the
  inner scope's already-committed write too. That is a property of whole-cache-granularity
  rollback, not a defect in the suspend/resume mechanism.

`REQUIRES_NEW` and `NOT_SUPPORTED` now genuinely suspend the active transaction on every backend
(JDBC via a sibling `ThreadLocal<Deque<TransactionContext>>`; JSON via the equivalent construction
on `JsonTransactionManager`) instead of silently nesting into it. `Propagation.NESTED` is removed
— see its entry under [AOP](#aop) above.

**Worked before/after example.** `rollbackFor` is now **additive** to the unchecked
`RuntimeException`/`Error` default, not a replacement for it — matching what the javadoc already
said before this fix ("Specify **additional** exception types") but the code did not do. When both
`rollbackFor` and `noRollbackFor` match the thrown exception, the rule whose listed class is the
**shallower inheritance-depth match** wins (Spring's `RuleBasedTransactionAttribute` tiebreak); an
exact-depth tie — including the same class listed in both arrays — favours rollback.

```java
class OrderException extends RuntimeException { }
class ValidationException extends OrderException { }

@Transactional(rollbackFor = ValidationException.class, noRollbackFor = OrderException.class)
public void processOrder(Order order) throws ValidationException {
    if (!order.hasShippingAddress()) {
        throw new ValidationException("missing shipping address");
    }
}
```

`ValidationException` is an exact (depth-0) match for `rollbackFor`, and a depth-1 match for
`noRollbackFor` (`ValidationException` → `OrderException`, one step up its own hierarchy).

- **On 6.2.5** (`noRollbackFor` checked first, unconditionally): `noRollbackFor` matches, so the
  method **commits** — the write that threw `ValidationException` is silently kept.
- **After 6.3.0** (shallowest depth wins): `rollbackFor`'s depth-0 match beats `noRollbackFor`'s
  depth-1 match, so the method **rolls back**.

The plainer case this fix targets — an exception matching neither array:

```java
@Transactional(rollbackFor = BusinessException.class)
public void chargeCard(Payment payment) throws BusinessException {
    // ...
    throw new NullPointerException(); // unrelated to BusinessException
}
```

- **On 6.2.5**: a non-empty `rollbackFor` replaced the default rule entirely, so an exception not
  on the list **committed** instead of rolling back — this exact annotation committed on an
  unrelated `NullPointerException`.
- **After 6.3.0**: neither array matches, so `shouldRollback` falls through to the unchanged
  `RuntimeException`/`Error` default, and the method **rolls back** — exactly as it would with no
  `rollbackFor` attribute at all.

Both directions are proven through an external call and a self-invocation call on a real ByteBuddy
proxy with a real JDBC connection, on both the JDBC and JSON backends.

**If you removed `@Transactional` or switched to `DataOperator.transaction(Callable)` to work
around the pre-6.3.0 refusal**, you can now re-add the annotation — but re-read the worked example
above first if your method combines `rollbackFor` and `noRollbackFor`: the rollback direction may
have changed under you.

See the entry below, "Entity ownership is now enforced, and other 6.3.0 persistence
deprecations," for `TransactionManager.getConnection()`/`setIsolationLevel(int)`/`setReadOnly(boolean)`,
now `@Deprecated` `default` methods rather than abstract ones.

### Recorded instance: `@ExceptionCatch` can now stop a module from loading (6.3.0)

Before 6.3.0 `@ExceptionCatch` did nothing at all. AOP was never wired into the container, so the
annotation was recognised, ignored, and had no effect on whether a module loaded. 6.3.0 wires it,
which means the framework now has to build a proxy for the beans that carry it — and one shape
cannot be proxied at all.

**A `final` bean class fails the load if anything asks to intercept it**, including an
`@ExceptionCatch` method it merely inherits:

```java
public class GuardedBase {
    @ExceptionCatch(silent = true)
    public String guarded() { ... }
}

public final class MyService extends GuardedBase { }   // 6.2.5: loads. 6.3.0: refuses.
```

Remove the `final` keyword and mark the class `@Final` instead, which keeps the non-extendable
contract while allowing AOP. Lombok's `@Value` and `@UtilityClass` also emit `final`; switch to
`@Data` if one of those is the source.

Nothing else about `@ExceptionCatch` can stop a module loading. An annotation on a method no
inheritance proxy can reach — `private`, `static`, `final`, package-private from another package,
or hidden by the bridge a generic override generates — is ignored with a startup warning naming the
method, which is what Spring does for a method it cannot advise. The annotation does nothing there,
but the module loads.

Method signatures are unchanged, so `japicmp` cannot detect any of this.

### Recorded instance: entity ownership is now enforced, and other 6.3.0 persistence deprecations (SILENT-04)

**Entity ownership.** Before 6.3.0, `getDataOperator(Class)` never asked *whose* entity you were
asking for — a third-party module writing `getDataOperator(SomeoneElsesEntity.class)` received that
other module's real `DataOperator` and could read and write its rows. Fixing this release's
cache-keying isolation defect alone (see the pool-per-file/operator-per-scope work elsewhere in
this milestone) would have made this *worse*, not better: with the cache keyed per requesting
identity instead of entity class alone, the same call would instead return a **fresh, empty**
operator over the caller's own storage, and every query on it would silently return nothing —
"shouldn't work but does" becoming "looks like it works, always empty," exactly the defect class
this milestone exists to remove.

6.3.0 closes it with a real check instead. `DataStore.getOperator(DataScope, Class)` — the new,
recommended entry point — refuses an entity outside the caller's own scope with
`DataAccessException`/`ErrorCode.ENTITY_NOT_OWNED` (3010), naming the entity, the owning module
(when known), and pointing at that module's own exposed service or the `EventBus` instead.
`UltiToolsPlugin.getDataOperator(Class)` and `UltiToolsAPI.getDataOperator(JavaPlugin, Class)`
apply the identical check before delegating to the legacy overloads, and — closing a gap disclosed
mid-phase — `SQLiteDataStore`, `MysqlDataStore`, and `JsonStore` now run the same check as the
*first statement* of their own `getOperator(UltiToolsPlugin, Class)`/`getOperator(File, Class)`
overrides too, so reaching persistence through `UltiTools.getInstance().getDataStore()` directly
(public in the published jar) no longer bypasses it. One caveat remains, stated in `DataStore.java`'s
own javadoc rather than left implicit: nothing mechanically stops a *fourth*, hypothetical
`DataStore` implementation from skipping the check inside its own override of the still-abstract
`getOperator(UltiToolsPlugin, Class)` — the framework's own call-site checks
(`UltiToolsPlugin.getDataOperator`/`UltiToolsAPI.getDataOperator`) are the backstop for that case.

This takes **no migration period**, for the same reason the AOP proxy class naming and the
superclass `@CmdMapping` registration entries above did: cross-module entity access was never a
documented contract. No javadoc, guide page, or annotation ever stated that `getDataOperator`
could reach another module's entity; the previous permissive behaviour was an accident of a shared
cache key, not a promise. Measured, not assumed: 21 `@Table` classes across 17 in-house module
repositories, **zero** naming collisions — and, per the maintainer's own framing of the risk, the
realistic danger was never accidental collision but a third-party developer deliberately
requesting an entity they already knew was not theirs.

**The `"unknown"` plugin-name fallback is refused, not silently shared, at load.** Before 6.3.0, a
module JAR whose `plugin.yml` carried no `name:` key resolved to the literal string `"unknown"`
(`pluginConfig.getString("name", "unknown")`) and shared `sqliteDB/unknown.db` with every other
name-less module ever deployed on that server. 6.3.0 refuses to load such a module at all —
`PluginModuleException`, naming the JAR, thrown as the first statement of `UltiToolsPlugin`'s
no-arg constructor, before any resource extraction or config initialisation runs. Like the
entity-ownership case above, this takes **no migration period**: the `"unknown"` fallback was
never a documented contract either — an internal default value with an accidental cross-module
consequence, not a feature. A control-grouped survey of all 17 in-house module `plugin.yml` files
found zero missing a `name:` key (17/17 control hits confirm the grep itself works), so no
shipping module is refused at load by this change.

*Fate of existing `sqliteDB/unknown.db` data.* This release does not migrate it. Measured on a live
deployment: 96 KB, 10 tables belonging to 8 different modules, all zero rows except
`world_settings` (3 rows — the same 3 rows already present in the module's own correctly-named
`UltiWorlds.db`, so this measurement found no uniquely-held data there). If a name-less module's
data existed *only* in `unknown.db` on your server, fixing its `plugin.yml` and reloading gives it
a fresh, correctly-scoped `.db` file — it does not automatically recover the old shared file's
rows. Inspect `sqliteDB/unknown.db` yourself before upgrading if you are unsure whether any of your
modules were affected.

### Recorded instance: an unresolvable required `@Autowired` dependency now fails module load (SILENT-05, 6.3.0)

Before 6.3.0, `SimpleContainer.autowireBean` and the constructor-injection path logged a `WARNING`
and injected `null` for an unresolvable `@Autowired(required = true)` field or constructor
parameter, instead of the failure the `required = true` default promises. 6.3.0 throws
`ContainerException`, naming the declaring class, the field (or constructor parameter position),
and the unresolvable dependency type, and the module fails to load rather than continuing with a
silently-null collaborator. `required = false` is unaffected — the field or parameter is still
left `null` with no warning.

This falls under "moving from silent degradation to failure," which normally needs a migration
period — but that period has already run. Issue #182's one-shot `WARNING` on this exact path
shipped in `v6.2.5`, giving downstream module authors one full release of notice before 6.3.0
turns it into a load-time failure. That warning is the precondition D-08 relied on to treat 6.3.0
as the "N+1" step rather than starting the two-step process over.

Constructor injection carries the identical fix: an unresolvable required constructor dependency
now throws `ContainerException` rather than a bare `RuntimeException`.

### Recorded instance: same-type resolution now adjudicates by `@Service(priority)` and refuses an exact tie (SILENT-06, 6.3.0)

Before 6.3.0, `SimpleContainer.getBean(Class)` resolving a type with more than one matching
candidate returned whichever the internal map iteration reached first — an unspecified,
implementation-dependent choice that this repository's own `@Service` javadoc did not document,
even though it shipped a `priority` attribute. 6.3.0 makes `priority` load-bearing: candidates are
ranked by `@Service(priority)`, **higher value wins** — the framework's own direction, chosen
because it is what this codebase's javadoc already promised in 6.2.5, and deliberately not
Spring's `@Priority`, where a lower value wins. An exact tie between the top two candidates
(including the common case of both left at the default priority `0`) now throws
`ContainerException` at the first `getBean(Class)` call that hits the ambiguity, naming both
candidate classes and pointing at `@Service(priority = ...)` as the remedy.

**Scope.** Candidates are collected from the resolving container only; the parent container is
consulted solely on a total miss (no candidate found locally). A module that overrides a
framework-provided default service by registering its own implementation in its own child
container is unaffected by this change — the override is never compared against the framework's
default, because the two never sit in the same candidate pool.

This is "moving from silent degradation to failure": before 6.3.0, two same-priority
implementations resolved silently to an unspecified one and that choice was cached for the
container's lifetime; after 6.3.0, the same module fails to load until it disambiguates with
`priority`.

### Recorded instance: `registerSingleton` now fully assembles its argument and can refuse an AOP-annotated instance (SILENT-09, SILENT-10, 6.3.0)

Before 6.3.0, `SimpleContainer.registerSingleton` stored whatever object it was given as-is: no
`@Autowired` injection, no `@PostConstruct` invocation, and no `BeanPostProcessor` chain ran
against it. 6.3.0 widens its contract from "register" to "register and fully assemble" — every
object passed to `registerSingleton` now goes through the same
`postProcessBeforeInitialization → autowireBean → @PostConstruct → postProcessAfterInitialization`
sequence a container-constructed bean does. This reaches config entities, `@ContextEntry` beans,
`@Configuration` instances, and `@Bean` products registered via this path, none of which were
previously assembled.

As part of the same widening, `registerSingleton` now **refuses** an instance whose class carries
`@Transactional` or `@ExceptionCatch` at method or class level — `ContainerException`,
`ErrorCode.UNPROXYABLE_SINGLETON` (2007) — because an object registered this way was never routed
through AOP proxy generation and the annotation would otherwise silently do nothing. An
already-generated proxy instance is exempt.

Measured in-house impact: **0** occurrences of `@Transactional`/`@ExceptionCatch` on any object
registered via `registerSingleton` across `Modules/`, `Plugins/`, and the framework's own
`src/main` (control: `@Service` appears in 39 downstream files, confirming the search mechanism
finds real matches). A module registering such an object for the first time in 6.3.0+ needs to
declare it `@Service`/`@Component` instead, so the container constructs — and can proxy — it.

**Blast radius when reached through `@Bean`/`@Configuration`.** `registerConfiguration` and
`processBeanMethod` (the paths that call `registerSingleton` for `@Configuration` instances and
`@Bean` products) previously caught any exception, logged it at `SEVERE`, and continued scanning
the rest of the module. As of 6.3.0 (see the `@Bean` naming entry below) a `ContainerException` —
whether from this refusal or from a malformed `@Bean` declaration — is no longer caught there: it
escapes `processClass` and aborts the module's entire `scanPackage` call. A failure that was
previously scoped to one bean is now scoped to the whole module.

Both changes fall under "moving from silent degradation to failure": before 6.3.0, an unassembled
singleton or an AOP-annotated one was accepted and silently non-functional; after 6.3.0, the
module fails to load until it is fixed.

### Recorded instance: `@Bean(name=)`/`@Bean(value=)` now determine the registered bean name (WIRE-09, 6.3.0)

Before 6.3.0, `@Bean`'s `name` and `value` attributes were declared but read by nobody — every
`@Bean` method registered under its own method name regardless of what `name()`/`value()` said.
6.3.0 makes both attributes load-bearing: when either is declared, its first array element becomes
the registered bean name, and any remaining elements register as aliases resolving to the same
instance; with neither set, the method name is still used, unchanged. A module doing
`getBean("<methodName>")` for a `@Bean` method that also declares a non-default `name()`/`value()`
needs to change to `getBean("<declaredName>")` after upgrading — the method-name key no longer
resolves once a custom name is declared.

A malformed declaration — conflicting non-empty `name()` and `value()` content, or any blank
declared name element — now fails module load with `ContainerException`, where before it silently
registered under the method's own name (both attributes were previously read by nobody). See the
entry above for the blast radius this failure carries as of 6.3.0.

Measured in-house impact: **0** occurrences of `@Bean(name=` / `@Bean(value=` in `Modules/`,
`Plugins/`, or the framework's own `src/main` — every existing `@Bean` method relies on the
method-name default, unchanged by this release, so this is a forward-looking compatibility note
rather than an observed break.

### Recorded instance: `scanBasePackageClasses` now takes effect, and package-source resolution becomes additive (GEN-06, 6.3.0)

Before 6.3.0, `scanBasePackageClasses()` on `@UltiToolsModule`/`@ComponentScan` was declared but
read by nobody at either `PluginManager.getPluginScanPackages` or
`SimpleContainer.processConfigurationClass`. 6.3.0 reads it at both sites, purely additively: any
module that already declared the attribute (0 occurrences measured) sees no change, since a
previously-inert declaration cannot regress by starting to work.

The same change makes package-source resolution additive rather than first-match: a module
declaring more than one of `scanBasePackages`, `scanBasePackageClasses`, or a directly-declared
`@ComponentScan.basePackages` previously had every source after the first silently ignored; all
now contribute. Measured in-house impact: **0** modules declare more than one source today, so no
existing module's scanned-package set changes size. Falls under "correcting behaviour that plainly
contradicts the documentation" — no migration period.

### Recorded instance: the `eventListener`/`cmdExecutor`/`config` `@AliasFor` switches on `@UltiToolsModule` now take effect (WIRE-08, 6.3.0)

Before 6.3.0, `@UltiToolsModule`'s `eventListener`, `cmdExecutor`, and `config` attributes were
declared `@AliasFor`s onto `@EnableAutoRegister`'s corresponding attributes, but `registerBukkit`
resolved `@EnableAutoRegister` by direct reflection, which never followed the alias — so setting
any of the three to `false` on `@UltiToolsModule` had no effect at all; auto-registration ran
regardless. 6.3.0 makes `registerBukkit` resolve `@EnableAutoRegister` through the same
merged-annotation lookup the rest of this phase's work uses, which honours `@AliasFor`, so the
three switches now do what their own annotation declared them to do since they were added.

This is recorded under "correcting behaviour that plainly contradicts the documentation" — no
migration period — because the previous behaviour was not merely an undocumented gap; it directly
contradicted `@AliasFor`'s own stated contract on the same annotation. Measured in-house impact:
**0** downstream modules set any of the three switches to a non-default value today, so no
existing module's registered set of commands/listeners/configs changes as a result of this fix.

### Recorded instance: `@ConditionalOnConfig` is now honoured on the listener package-scan path (WIRE-07, 6.3.0)

Before 6.3.0, `ListenerManager.registerAll(plugin, packageName)` registered every discovered
listener regardless of `@ConditionalOnConfig`, so a listener whose condition evaluated `false`
still received events. 6.3.0 evaluates the condition on this path the same way the IoC component
scan already does; a listener whose condition is false is registered with no events delivered.

**Scope correction.** `@ConditionalOnConfig` on a `@CmdExecutor` class already worked before this
release on the standard module-JAR path — that path resolves command classes as container beans,
and a class whose condition is `false` was never constructed as a bean in the first place. This
entry covers only the listener package-scan gap that was real. Falls under "correcting behaviour
that plainly contradicts the documentation" — no migration period.

### Recorded instance: `ComponentScanner`'s failure and skip diagnostics now carry a level and a stack trace (SILENT-07, 6.3.0)

Before 6.3.0, `ComponentScanner` reported six distinct failure and skip conditions — an ambiguous
`@CmdTarget` refusal, a component/configuration registration exception, a `@Bean` method
invocation exception, an unresolvable package, and an unreadable JAR — with direct,
stack-trace-free `System.err` writes that no log handler ever saw. 6.3.0 replaces all six with
leveled `java.util.logging.Logger` calls carrying the original `Throwable` wherever one exists.
Four registration-failure sites now log `Level.SEVERE`; because `SystemLogHandler` auto-forwards
any `Level.SEVERE` record carrying a `Throwable` to `ErrorReportCollector` and on to the UltiPanel
dashboard, these four failures — previously visible only on the server's own console — now reach
the panel for any server with error reporting enabled. Two skip-and-continue sites log
`Level.WARNING` and stay local.

Separately, `scanJar` and `scanDirectory` now catch the identical `ClassNotFoundException |
LinkageError` union per class, closing a mismatch where a class referencing an absent optional type
skipped just that one class in production (JAR mode) but aborted the entire package's scan in
development (directory mode); both modes now skip-and-continue identically.

This falls under "changes in ... log wording" — no migration period — but the routing-to-panel
fact is named explicitly because it changes what an existing server transmits off-box, not merely
what it logs locally.

### Recorded instance: composed stereotype annotations more than one meta-level above `@Component` are now recognised (6.3.0)

Before 6.3.0, `ComponentScanner.hasComponentAnnotation` walked only one level of meta-annotation
composition by hand. 6.3.0 collapses this onto the same `MergedAnnotationResolver` used across the
rest of this phase's `AnnotationUtils.findAnnotation` migration (see the deprecation entry below),
which walks the full composition graph — a stereotype annotation composed two or more levels above
`@Component` is now recognised as a component where it previously was not. `UltiToolsPlugin`
subclasses are excluded from this widened reach so that a module's own main class (which composes
`@Configuration` → `@Component` through `@UltiToolsModule`) is not accidentally registered a
second time as a component bean.

Recorded honestly: the set of downstream classes this newly registers across `Modules/` and
`Plugins/` was **not** measured — this is an absence of evidence, not a measured zero, unlike the
other entries above.

**Newly deprecated, not yet removal-eligible.** Three more `@Deprecated(since = "6.3.0", forRemoval =
true)` additions land in this release but do not appear in the "Removal list for 6.3.0" table
above, because condition 2 of [eligibility](#when-a-public-api-becomes-eligible-for-removal) — one
MINOR since the *first* release carrying the annotation — is not yet satisfied by any of the three:
6.3.0 is that first release, so none is eligible for removal before 6.4.0 at the earliest.

| Type / member | Replacement | Downstream references (informational) |
|---|---|---|
| `interfaces.TransactionManager.getConnection()` / `setIsolationLevel(int)` / `setReadOnly(boolean)` | `interfaces.JdbcTransactionManager`, which carries the same three as real abstract methods | 0 — `TransactionManager` appears in no published-jar signature of `DataStore`/`DataOperator`/`UltiToolsPlugin`/`UltiTools`; a module can only reach it today by downcasting `DataOperator` to `AbstractRelationalDataOperator` and supplying its own `DataSource` |
| `interfaces.DataStore.getOperator(UltiToolsPlugin, Class)` and `getOperator(File, Class)` | `getOperator(DataScope, Class)` | Not separately measured — the documented, correct entry point for module authors is `getDataOperator(Class)` (94 downstream hits across 17 repositories, per the survey above), which still calls the deprecated overloads on your behalf and is unaffected by this deprecation |
| `utils.AnnotationUtils.findAnnotation` | `context.MergedAnnotationResolver.find` | 0 — no occurrences of `AnnotationUtils` across `Modules/` and `Plugins/`; the method body is unchanged, serving only as the compatibility fallback for anything not yet migrated to the resolver |

On `TransactionManager`, turning three previously-abstract methods into `@Deprecated` `default`
methods that throw `UnsupportedOperationException` is, per `japicmp` 0.26.1's own
`METHOD_ABSTRACT_NOW_DEFAULT` classification, reported as `binaryCompatible="false"`. Read
literally in isolation that looks like a breaking change; in practice no compiled 6.2.x
`TransactionManager` implementor breaks at link time from it. A previously-compiled **concrete**
class was required to implement all nine of the interface's abstract methods to compile in the
first place, so its own three overrides still resolve via ordinary virtual dispatch — the new
`default` bodies are never reached for it. A previously-compiled **abstract** class that left the
three JDBC-specific methods unimplemented (legal, since it was abstract) now has a working
`default` fallback where none existed before, resolved via interface-method inheritance — exactly
the case `default` methods exist to make safe. Neither case produces an `AbstractMethodError` or a
`NoSuchMethodError`. `japicmp`'s conservative classification appears stricter than the JVM's actual
binary-compatibility guarantee for this specific transformation (abstract → default, same
signature, same return type) — recorded here so a reader trusting the raw `japicmp` report is not
misled, without this document's own compatibility promise overstating it either.

### Recorded instance: config validation goes live, and a violating value refuses the module (SILENT-14, 6.3.0)

Before 6.3.0, `AbstractConfigEntity.validateFields()` obtained the default instance it diffs
against directly via `(String)` constructor reflection and returned at `Level.FINE` — with no
enforcement at all — for any config class that does not declare that constructor. Eleven
production config classes across nine modules use only the no-arg `super(path)` idiom (the same
idiom `ConfigManager.registerAll`'s own construction fallback already supports), so
`@Range`/`@NotEmpty`/`@Size`/`@Pattern` never executed on them: **146 constraints had never fired
on a single one of them**, across every release, worst individually `UltiLogin/LoginConfig` 37,
`UltiCleaner/CleanerConfig` 26, `UltiTrade/TradeConfig` 22. On the seven classes that do carry a
`(String)` constructor, validation did already run, but a violation was handled by resetting the
field to its initializer value and calling `config.save()` — silently discarding the operator's
edit and overwriting their file.

6.3.0 makes `validateFields()` obtain its default instance through the identical two-step
constructor fallback `ConfigManager.registerAll` already uses — `(String)` first, no-arg second —
so validation now runs on every config class that registers successfully, dormant or not. A
violation on any of those classes now throws, naming module, file, field, actual value, and the
violated constraint; the operator's yml file is never touched, on either path. A config class that
resolves through *neither* constructor idiom is refused by name instead of silently vanishing from
registration.

**Measured blast radius: 146 previously-dormant constraints across 11 production config classes in
9 modules begin enforcing for the first time.** A live server holding an out-of-range value in one
of those 11 files' fields will see that module refuse to load on its next start. The fix is the
operator's edit — the framework will not rewrite the file for them, on either the dormant-class
path or the previously-working path.

**Bucket.** Two distinct changes are folded into one fix, and they land in different buckets of
this document's own criterion:

- The 146 dormant constraints activating is "correcting behaviour that plainly contradicts the
  documentation" — the annotations' own javadoc has always promised validation, and for these 11
  classes it silently never ran. **No migration period.**
- The 47 constraints on the 7 already-working classes moving from reset-and-save to refuse is, on
  its face, "moving from silent degradation to failure" — the bucket that normally requires the
  two-step warn-then-switch process below. It instead takes the same case-specific override the
  `del()` recorded instance above took, for the reason the maintainer stated directly (D-01,
  `04-CONTEXT.md`): *"we may refuse to load and we may error out. We may NEVER silently modify
  \[the operator's\] file."* Reset-and-save is not a softer failure mode than refusal here — it is
  the exact defect class D-01 was written to delete, because it silently substitutes a value the
  operator never chose while reporting success. There is no migration period under which
  continuing to silently rewrite the operator's file is the safer default. **No migration period.**

#### The write path also validates now (CR-01, closing SILENT-14's remaining gap, 6.3.0)

The recorded instance above covers `init()`/`reload()` — the load path. It left one path
unvalidated: `AbstractConfigEntity.updateProperties(JsonObject)`, the method that applies a config
change submitted from outside the process. Before this gap closure, `updateProperties` set every
`@ConfigEntry` field the JSON named by reflection and called `config.save(...)` unconditionally,
with no call to `validateFields()` anywhere in the method — a `@Range(min = 1, max = 1200)` field
could be set to `9999` through this path with no refusal, no warning, and the out-of-range value
written straight to the operator's yml.

**Who reaches it:** both `ConfigManager.loadFromJson(String)` (the whole-map form) and
`ConfigManager.loadFromJson(String, String)` (the single-file form, issue #236's shape) call
`updateProperties` to apply the entries they parse. Both are called only from
`utils/ConfigEditorUtils`, which in turn is reached from `utils/PluginInitiationUtils`'s
`update_config` and `upload_config` WebSocket message handlers — the panel's two config-editing
entry points. There is no other production caller.

**What a panel operator now sees:** a submission that violates its own `@Range`/`@NotEmpty`/
`@Size`/`@Pattern` declaration is rejected. The panel receives an error naming the module, the
config file, the field, the actual value, and the violated constraint — the same five-part shape
the load path has produced since the recorded instance above, from the identical
`validateFields()` implementation. The yml file is not written, the entity's in-memory fields are
restored to the values they held before the call, and the module keeps running on that
pre-call state rather than on the rejected one.

**Bucket.** Same classification and the same reasoning as the 47-constraint case above, and it
takes the same case-specific override for the same D-01 reason: on its face this is "moving from
silent degradation to failure," but the previous behaviour did not degrade quietly — it reported
success while writing a value the module's own declaration rejects. D-01 grants the framework
permission to refuse; it grants no permission to persist a value the module's declarations reject,
and the panel being the caller rather than a hand-edited file does not change who owns the file.
There is no migration period under which continuing to accept and write such a submission is the
safer default. **No migration period.**

**Measured blast radius: all 193 constraints across the 18 production config classes named in the
recorded instance above — the 146 newly activated plus the 47 already working — are now enforced
on the write path too, not only the 47 that were already enforced on load.** Before this change, a
panel submission violating any of those 193 constraints was accepted and written; now it is
rejected, the file is unchanged, and the panel is told what to fix.

### Recorded instance: a dependency cycle or missing dependency refuses the affected modules (SILENT-08, 6.3.0)

Before 6.3.0, `PluginManager.sortPluginsByDependencies` caught `PluginDependencyResolver`'s
`CircularDependencyException`/`MissingDependencyException`, logged two `SEVERE` lines, and
returned `new ArrayList<>(plugins)` — every module, including ones with no involvement in the
cycle, loaded in raw filesystem order with no dependency ordering applied at all.

6.3.0 partitions instead of degrading wholesale: both exceptions now expose a structured
`getSortedPrefix()`/`getRefusedPlugins()` (and, for cycles, `getCyclePaths()`), and
`sortPluginsByDependencies` returns only the sortable prefix — the cycle (or the module missing a
hard dependency) and everything transitively depending on it are refused; every unrelated module
still loads, still ordered. The console names a cycle as an edge path (`A -> B -> C -> A`), not an
unordered set. The pre-6.3.0 all-unsorted behaviour survives only behind an explicit,
cost-stating opt-in, `-Dultitools.useLegacyPluginLoading=true` — mirroring Paper's own
`-Dpaper.useLegacyPluginLoading=true` precedent (measured against `SimpleProviderStorage.handleCycle`
in a live `paper-1.21.4.jar`) rather than inventing a new switch shape.

**Measured blast radius: `@PluginDependency` has 0 downstream users across `Modules/` and
`Plugins/`** (control: `@Service`, 39 files), so no in-house module is affected today. A
third-party module whose declared dependency graph is currently broken — a cycle, or a hard
dependency on a module that is not installed — will see its module (and anything depending on it)
refuse to load where it previously loaded anyway, unordered.

**Bucket.** "Moving from silent degradation to failure," verbatim — the criterion's own worked
example is "a missing optional dependency was previously skipped and is now rejected at load
time." The migration period for this release is not the generic two-step warn-then-switch process;
it is the standing, Paper-precedented opt-in switch above, which — unlike a two-release window —
remains available indefinitely, so an operator running third-party modules with a genuinely broken
graph can keep the old behaviour while they get it fixed, rather than being forced onto a fixed
timetable.

### Recorded instance: a module JAR failing validation never reaches the classpath (WIRE-11, 6.3.0)

Before 6.3.0, `UltiTools.getModuleUrls()` added every `.jar` under `plugins/` to the URL array
handed to the module `URLClassLoader` unconditionally; `SecurityPolicy`'s size/entry-count/
structure checks ran only afterward, defensively, inside `PluginManager.loadPluginMainClass`
during class scanning. A JAR that ultimately failed that later check had, for the interval between
classloader construction and that check, already had its URL present in a live `URLClassLoader` —
reachable by anything holding a reference to that loader, not only by the specific class-scan call
site that eventually rejected it.

6.3.0 moves the identical check earlier: the new `SecurityPolicy.isValidModuleJar(File)` — static,
Bukkit-free, callable before any Bukkit server exists — runs inside the new
`UltiTools.collectModuleJarUrls` for every candidate JAR, and only a JAR that passes has its URL
added to the array at all. A failing JAR is named in a `WARNING` and skipped; the bootstrap
continues, every other module still loads (module-granularity skip, not a bootstrap abort).
`PluginManager`'s own former private copy of this same rule (`validateJarFile`) is deleted — there
is exactly one implementation now, called from both the pre-classpath scan and the defensive
class-scan check.

**This changes *when* the check runs, not *what* it checks** — the 100 MB size limit and the
10,000-entry limit are still enforced, unmodified, by `SecurityPolicy.isSafeFileStructure`.

**Bucket.** No migration period — this is a security fix: it closes the interval during which a
JAR that will ultimately be rejected is nonetheless present in a live `URLClassLoader`'s URL
array, reachable by anything else running in the same JVM before the deferred check runs. Per this
document's own "no migration period" list, "security fixes... may land in a PATCH without prior
notice," and this narrows an existing exposure rather than introducing a new restriction on
previously-valid input.

### Recorded instance: a `getAllConfigs()` override under auto-registration is diffed against what actually registered (SILENT-18, 6.3.0)

Before 6.3.0, a module's `getAllConfigs()` override was consulted only when
`@UltiToolsModule(config = false)` opted the module out of auto-registration — which is not the
default. `@EnableAutoRegister.config()` defaults `true`, and `@UltiToolsModule` carries
`@EnableAutoRegister` as a meta-annotation, so every module has auto-registration on unless its
author explicitly turns it off. A module author who overrode `getAllConfigs()` while leaving
auto-registration on had that override silently ignored — package-scan auto-registration ran
instead, with no signal that the override existed or that it did nothing.

6.3.0 calls `getAllConfigs()` once after auto-registration completes and diffs its declared
`configFilePath` set against what auto-registration actually registered. An empty diff (every path
the override names was also auto-registered) is pure redundancy and logs one `Level.FINE` line. A
non-empty diff — the override names a `configFilePath` auto-registration never registered — is
real capability loss: the module is refused, and every missing entity is named. `config = false`
modules are structurally untouched; `getAllConfigs()` remains their sole registration path and no
diff runs.

**Measured blast radius: 0 downstream `getAllConfigs()` overrides exist today** under
auto-registration across the modules surveyed, so no in-house module is refused by this change.

**Bucket.** No migration period — "correcting behaviour that plainly contradicts the
documentation": `getAllConfigs()` is documented as the extension point for declaring a module's
config entities, and silently ignoring it while auto-registration is on (its own default)
contradicts that contract regardless of whether any override exists today to be affected by it.

### Recorded instance: `supported()` is derived from shipped language files, and now participates in resolution (WIRE-10, 6.3.0)

Before 6.3.0, `Localized#supported()` derived its return value from `@I18n.value()` — a
hand-maintained annotation attribute nobody consulted. Language resolution never called
`supported()` at all: an operator who configured a language code with no matching
`lang/<code>.json` file on disk got `createLanguageFromPath` returning `new Language("{}")`, an
empty dictionary, silently — so a server configured `language: en` for a module whose
default-catalogue text is Chinese source (`i18n()` is routinely called with the Chinese string
itself as the lookup key) displayed Chinese regardless of the operator's setting.

6.3.0 derives `supported()` from the module's own code source instead — the `lang/*.json` files
actually shipped in its JAR or exploded directory, enumerated the same way `saveResources()`
already enumerates embedded resources, correct even on a module's first cold start before
`saveResources()` has extracted anything to disk. `UltiToolsPlugin` now consults it, once, before
constructing `Language`: a configured code present in a non-empty `supported()` is used unchanged;
an absent one falls back — `en` if `supported()` contains it, otherwise `supported()`'s first
entry — with one `WARNING` naming the module, the requested code, and what actually exists. An
empty `supported()` (today's default for 14 modules that never override it) is read as "no
information," not "supports nothing," and produces neither warning nor behaviour change.
`Localized`'s javadoc, which previously claimed `i18n(code, str)` uses its `code` parameter and
that `supported()` gates it per call — neither is true; `UltiToolsPlugin.i18n` is `final` and
discards `code` entirely — is corrected in the same change.

**Measured blast radius: all 8 downstream `supported()` overrides return
`Arrays.asList("zh", "en")` on modules shipping exactly `en.json` and `zh.json`**, so all 8 are
unaffected by the derivation change; the 14 modules with no override are read as "no information,"
also unaffected. The behaviour change reaches only a server operator who configures a language
code for which the module ships no matching file — previously silent (empty dictionary), now a
named `WARNING` plus a fallback to a language that actually loads content.

**Bucket.** No migration period — "correcting behaviour that plainly contradicts the
documentation" for the fallback fix (an operator's configured `language: en` silently rendering
Chinese contradicts the setting's own documented purpose), and "tightening the handling of
previously undefined input" for the derivation change (an unsupported code was previously
undefined — an empty dictionary with no diagnostic — and now produces a named warning and a
working fallback).

### Recorded instance: both registration entry points now produce identical containers (WIRE-05 / WIRE-06, 6.3.0)

Before 6.3.0, `register(UltiToolsPlugin)` (the connector entry point) and `initializePlugin` (the
standard module-JAR load path) built two independently-maintained container-assembly sequences
that had drifted apart by nine measured capabilities. 6.3.0 closes all nine by extracting one
shared `PluginManager.assemblePluginContainer(...)` both entry points now call, plus deleting the
boolean fork inside `registerBukkit` that caused four of the nine:

| # | Difference | Closed by |
|---|---|---|
| 1 | `@ContextEntry` not honoured on the JAR-load path | 04-07 |
| 2 | config entities not registered as beans on the connector path | 04-07 |
| 3 | the static `instance` field not populated on the connector path | 04-07 |
| 4 | `autowireBean(plugin)` only inside the `@ContextEntry` block | 04-07 |
| 5 | `setContext` timing (before vs. after `refresh()`) | 04-07 |
| 6 | command/listener registration mode (package scan vs. bean resolution) | 04-08 |
| 7 | `manualRegister()` not honoured on the listener side (GEN-05) | 04-08 |
| 8 | `@ConditionalOnConfig` not honoured on `@CmdExecutor` | 04-08 |
| 9 | `BaseCommandExecutor` triggering an uncaught `ClassCastException` (issue #272) | 04-08 |

**The two consequences with downstream-visible behaviour**, called out because `japicmp` cannot
see either: `register(UltiToolsPlugin)` now resolves commands and listeners as **beans** from the
module's own container rather than package-scanning them — so `manualRegister()` and
`@ConditionalOnConfig` now take effect on the connector path exactly as they already did on the
JAR-load path; and `plugin.setContext(...)` now runs before `refresh()` on the JAR-load path too,
so a `@PostConstruct` method that calls `plugin.getContext()` no longer observes `null`.

**Measured blast radius: `register(UltiToolsPlugin)` has 0 downstream callers** (control:
`getPluginManager()`, 49 hits in framework `src/main`) — it is a public API path with no known
users today, but one that must nevertheless behave correctly.

**Bucket.** No migration period — "correcting behaviour that plainly contradicts the
documentation": both entry points are documented as producing a working, container-managed
`UltiToolsPlugin`, and a capability silently present on one path and absent on the other
contradicts that shared contract regardless of which path a given behaviour happened to be missing
from.

### Recorded instance: an external connector's beans receive the connector's own `JavaPlugin` (SILENT-16, 6.3.0)

Before 6.3.0, `PluginManager.registerExternal` created the connector's child container, set its
parent to the core `UltiTools` context, and scanned components — but never registered the
connector's own `JavaPlugin` instance into that child container. A `@Service` bean
constructor-injecting `JavaPlugin` (or the connector's own concrete plugin class) therefore missed
the child container entirely, fell through to the parent, and matched the parent's registered
singleton `"ultiTools"` via `isInstance` — silently receiving the framework core's `UltiTools`
instance instead of the connector's own.

6.3.0 registers `adapter.getJavaPlugin()` into the child container — by both its declared
`JavaPlugin` type and its own concrete runtime class — before `scanComponents` runs. A
child-container hit stops the search (the same rule Phase 3 established for the framework's own
container hierarchy), so the parent fallback is blocked with no new lookup mechanism required.

**Measured blast radius: `UltiToolsAPI` has 0 hits across `Modules/` and `Plugins/`** — no
external connector exists in-house today to be affected, but the corrected identity now applies to
any that adopt the External Plugin API.

**Bucket.** No migration period — "correcting behaviour that plainly contradicts the
documentation": the External Plugin API's own contract is that a connector receives its own
plugin instance through dependency injection, not the framework's; silently substituting the wrong
instance is the exact defect being corrected, with a measured 0 in-house callers depending on the
substitution.

### Recorded instance: three previously-inert declarations now take effect (WIRE-18 / WIRE-19 / SILENT-22, 6.3.0)

Three unrelated declarations shared the same defect shape — accepted by the framework, read by
nobody — and are closed together because each follows the identical "a previously-inert
declaration cannot regress by starting to work" reasoning this document's `GEN-06` entry above
already established:

- **`@ConfigEntry(comment)` now reaches the generated yml.** Before 6.3.0, `comment()` had exactly
  one reader — `AbstractConfigEntity:200`, feeding the UltiPanel editor — and never reached the
  file `init()` writes. 6.3.0 follows a newly-added key's `config.set(path, default)` with
  `config.setComments(path, ...)`, in the one branch D-01 already permits the framework to write
  silently (a key the operator never had). A key the operator already has, and any comment they
  wrote themselves, is untouched byte-for-byte.
- **`plugin.yml`'s `loadAfter` now participates in load ordering.** Before 6.3.0, the framework
  read `@PluginDependency`'s `depends`/`softDepends`/`loadBefore` but never `plugin.yml`'s
  `loadAfter`. 6.3.0 merges both into one graph through a plugin.yml-name/simple-class-name alias
  map, via the new `PluginYmlReader`. No new attribute was added to `@PluginDependency`.
- **`DependencyUtils.getPluginPackages` now sees meta-annotated scan declarations.** Before 6.3.0,
  it resolved `@ComponentScan`/`@EnableAutoRegister` via `Class#isAnnotationPresent`, which returns
  `false` when the annotation is present only as a meta-annotation — exactly `@UltiToolsModule`'s
  shape. 6.3.0 resolves both through the same `MergedAnnotationResolver` the rest of this phase's
  work uses, and accumulates every declared source (`value()`, `basePackages()`,
  `basePackageClasses()`, `scanPackage()`) additively rather than first-match-wins.

**Measured blast radius, all three: 0.** 11 modules declare `plugin.yml`'s `loadAfter:`, **all
empty arrays**. 12 modules declare `scanBasePackages`, all with exactly one entry equal to the
module's own package — the shape the additive-union resolver reproduces identically. No module
today writes a `@ConfigEntry(comment())` whose written comment this change changes, since the
change only ever adds a comment to a key that did not previously exist in the operator's file.

**Bucket.** No migration period for all three — each is "correcting behaviour that plainly
contradicts the documentation": a declared attribute that the framework accepts, documents, and
silently never reads is a self-contradicting contract regardless of whether it happens to have
zero current users.

### Recorded instance: command-validator side effects move into the chain, and an unenforceable `@CmdCD`/`@UsageLimit` now refuses to load (SILENT-11, 6.3.0)

Before 6.3.0, `BaseCommandExecutor.executeCommand` called `cooldownValidator.applyCooldown` /
`lockValidator.acquireLock` / `lockValidator.releaseLock` by field reference, unconditionally —
regardless of whether those validators were actually present in the executor's validator chain.
An executor built through the single-argument `BaseCommandExecutor(ValidatorChain)` constructor
stored a custom chain as given, without adding `CooldownValidator`/`UsageLockValidator` unless the
caller did so explicitly. `@CmdCD` and `@UsageLimit` looked declared and appeared to be recording
state (`getRemainingCooldown()` returned plausible values) while enforcing nothing — no invocation
was ever rejected (#312).

6.3.0 closes the gap two ways:

- `CommandValidator` gains a `default void onComplete(CommandContext, boolean)` post-action hook;
  `ValidatorChain.ChainValidationResult#getPassedValidators()` is now the single ordered source of
  truth for which validators actually ran a given invocation. `executeCommand` drives every
  validator's side effect exclusively from that list — "in the chain" and "has side effects" are
  the same fact by construction, so no construction path can desync them.
- At plugin load, `PluginManager.validateCommandExecutorContracts` refuses any class or
  `@CmdMapping` method carrying `@CmdCD` or `@UsageLimit(SENDER|ALL)` whose validator chain has no
  matching validator, naming the offending class and, when known, the offending method
  (`ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE`, 4006). `@UsageLimit(NONE)` is exempt, since it
  declares no limit to enforce. `@CmdCD`/`@UsageLimit`'s `@Target` is additionally widened from
  `{METHOD}` to `{METHOD, TYPE}` so the same check can also apply to a class-level declaration —
  purely additive; no previously-annotated site changes shape.

**No opt-out exists for the load-time refusal.** Phase 3's module-granularity isolation is the
accepted escape hatch: the offending module alone fails to load, every other module still starts.
Delivered by plan 05-01 (`BaseCommandExecutorTest$ChainDrivenPostActionTests`) and plan 05-02
(`PluginManagerCommandContractTest`).

**Bucket.** The refusal is "moving from silent degradation to failure" and would ordinarily need
the two-step warning period, but ships without one under the same reasoning as this document's
`del()` entry above: a declared `@CmdCD`/`@UsageLimit` that enforces nothing is always a
module-author bug, not a case a louder log line meaningfully helps — cooldowns are abuse
protection on a Minecraft server (`UltiBackup.BackupCommand` carries `@CmdCD(30)` on
`/backup start`; losing it silently means a player can spam full-server backups). Measured **zero**
downstream custom-`ValidatorChain` construction sites in the monorepo (control: `extends
BaseCommandExecutor`, 19 files), so no known module hits this refusal today.

`japicmp` cannot detect the validator-chain/load-time-refusal behaviour described above.
`CommandValidator#onComplete` is a source- and binary-compatible default-method addition —
confirmed via `javap` against the released 6.2.0 through 6.2.5 jars, whose existing shape is 1
abstract method plus 3 default methods; adding a 4th default continues that convention rather than
overturning it. The load-time refusal and the `@Target` widening are behavioural changes only,
invisible to `japicmp` either way.

**Correction (Phase 7, plan 07-01):** the sentence that used to close this section here — "no
existing method signature changed" — was wrong, and is corrected rather than repeated. The same
SILENT-11 change plumbed `executeCommand`'s new `validationResult` argument through by adding a
fourth parameter to the existing `protected` method, which **is** a method-signature change
`japicmp` can and does detect — see the next recorded instance below. The false claim went
unnoticed because `japicmp`'s `accessModifier` was `public` until this plan widened it to
`protected` (D-06); `executeCommand` itself is `protected`, so it never entered the comparison
before.

### Recorded instance: `BaseCommandExecutor#executeCommand` gains a fourth parameter, invisible until `japicmp` widened to `accessModifier=protected` (SILENT-11, 6.3.0)

`executeCommand(CommandContext, Method, Object[])` — the 3-argument overload released in 6.2.5 —
was replaced by `executeCommand(CommandContext, Method, Object[], ValidatorChain.ChainValidationResult)`
as part of the same SILENT-11 change above: the fourth parameter is `onCommand`'s own
`ChainValidationResult`, needed so `executeCommand` can drive each ran validator's
`onComplete(CommandContext, boolean)` from the authoritative "validators that actually ran" list
instead of re-validating (re-validating would run every validator twice and re-trigger their
gates, e.g. a second lock acquisition attempt).

`japicmp` reports this directly once measured at `accessModifier=protected` (report generated by
this plan, `target/japicmp/japicmp.diff`):

```
***  MODIFIED CLASS: PUBLIC ABSTRACT com.ultikits.ultitools.abstracts.command.BaseCommandExecutor
	!!! METHOD REMOVED: PROTECTED void executeCommand(com.ultikits.ultitools.abstracts.command.CommandContext, java.lang.reflect.Method, java.lang.Object[])
```

**Practical impact.** `executeCommand` is `protected`, internal command-dispatch plumbing — not a
method any of the 15 downstream `AbstractCommandExecutor`-generation files or the 19 already-migrated
`BaseCommandExecutor`-generation files call or override anywhere in this monorepo (measured: zero
call sites or overrides outside `BaseCommandExecutor.java` itself and its own tests). A third-party
module that did override or directly call the 3-argument overload would fail to compile against
6.3.0 and would need to add the fourth parameter, forwarding the `ChainValidationResult` its own
`onCommand` override received. This entry exists so that reader is not left to discover the change
only from a raw `japicmp` report with no explanation attached — the japicmp `<excludes>` entry
covering this symbol (`abstracts.command.BaseCommandExecutor#executeCommand(...)`) is in `pom.xml`;
its full justification and the delete-one-entry proof are in
`.planning/phases/07-generational-removals/07-JAPICMP-BASELINE.md`.

### Recorded instance: `@UsageLimit` now actually serialises what its name promises, and `ContainConsole()`'s default flips to `true` (GEN-09, 6.3.0)

Before 6.3.0, `UsageLockValidator.acquireLock`'s boolean return value was discarded by its only
caller — a bare statement at `BaseCommandExecutor.java:261` — so a failed acquisition never blocked
execution. `releaseLock`'s `ALL`-scope branch removed the server-wide lock unconditionally
(`serverLocks.remove(methodKey)`, no ownership check), so any sender's completion could release a
lock a *different* sender was still holding (Pitfall 5 / T-05-03). `@UsageLimit` recorded a lock
and released it on the wrong owner's schedule; it did not serialise anything.

6.3.0 fixes both defects as part of the same rewrite as the entry above: acquisition now happens
inside `validate()` itself (acquire-as-you-validate), so a failed acquisition is an ordinary
validation rejection and the mapped method is never invoked; `releaseLock`'s `ALL`-scope branch is
now ownership-gated via an atomic `Map#remove(key, ownerUuid)`. `UsageLimit.ContainConsole()`'s
default additionally flips from `false` to `true` — a console sender is now subject to a limit
unless a mapping opts out explicitly. Delivered by plan 05-01,
`UsageLockValidatorTest$SerializationGuaranteeTests` (8 tests).

**Bucket.** The acquisition-gate and ownership-gated-release fixes are "correcting behaviour that
plainly contradicts the documentation" — the annotation's own name always promised serialisation
and single-owner release (#209) — no migration period. `ContainConsole()`'s default flip is a
documented-default flip and would ordinarily need the two-step warning period, but is bundled at
zero measured cost: **zero** production `@UsageLimit` use sites monorepo-wide (control: `@CmdCD`,
8 sites across 3 modules), so no known caller observes the flip.

`japicmp` cannot detect any of this — no method signature changed on `UsageLimit` or
`UsageLockValidator`'s public surface.

### Recorded instance: `@CmdParam.suggest`'s resolution contract widens from three steps to four, and an unknown `@key` now refuses to load (WIRE-01, 6.3.0)

Before 6.3.0, `suggest()` resolved in three steps — own-class method name, `@CmdSuggest`-referenced
class method name, i18n hint-text fallback. The four built-in completers the released jar ships
(`MaterialsCompleter`, `OnlinePlayersCompleter`, `WorldsCompleter`, `StaticSuggestionsCompleter`)
had no declarative entry point at all; five downstream modules hand-rolled equivalents instead
(`suggestWorlds` × 11 sites, `suggestOnlinePlayers` × 2 sites, plus `suggestBooleans`), because
there was no way to declare them.

6.3.0 adds a new first step: a value starting with `@` (for example `"@players"`) resolves through
a registered `TabCompleter` — one of the four built-ins, or a key a module registered at runtime.
`@` is not a legal Java identifier start, so no existing method-name value can collide; all 24
measured downstream `@CmdParam(suggest=)` sites need zero change. An **unknown** `@key` refuses the
declaring module to load, at the same load-time validation pass the entry above introduces, naming
the class, the method and the key — it does **not** fall through to the i18n hint fallback. The
three previously-existing steps, including the method-not-found → i18n hint fallback, are
otherwise unchanged. Delivered by plan 05-06,
`BaseCommandExecutorTabCompletionTest$SuggestKeyNotationTests` (dual notation) and
`PluginManagerCommandContractTest$UnknownSuggestKeyTests` (8 tests, load-time refusal).

**Bucket.** The additive resolution step needs no migration period on its own — it is gated behind
a syntax (`@`) no existing value can produce. The **refusal** half is "moving from silent
degradation to failure" and ships without a warning period under the same D-04 reasoning as the
entry above: measured **zero** existing `@key`-notation sites anywhere in the monorepo (all 24
measured sites use plain method names), so the refusal has no known site to warn.

`japicmp` cannot detect this — `CmdParam.suggest()`'s signature is unchanged; only the runtime
resolution of its `String` value changes.

### Recorded instance: tab completion now filters a `@CmdMapping` by permission before it can contribute a suggestion or be reflectively invoked (SILENT-25, 6.3.0)

Before 6.3.0, `BaseCommandExecutor.suggest` ran with **zero** permission checks. Every `@CmdMapping`
on a command class was offered to `TAB` regardless of the sender's `@CmdMapping(permission=)` or
`requireOp()` value — on the first-token literal list, on the multi-token literal-sibling scan, and
on the `<param>` slot for a mapping the sender could never invoke. That last path is not merely
information disclosure: a resolved `<param>` slot is handed to
`commands/tabcomplete/MethodInvocationCompleter`, which **reflectively invokes** the method behind
`@CmdParam(suggest = "methodName")` to produce its suggestions. With no permission gate, a sender
pressing `TAB` on a permission-gated mapping's parameter could cause that method to be invoked
before ever attempting to run the command itself. The deprecated
`abstracts.AbstractCommandExecutor.suggest` already carried an equivalent guard on its first-token
and parameter-slot branches, but not on its multi-token literal-sibling branch — so migrating a
command onto the then-current `BaseCommandExecutor` generation silently widened the exposure rather
than narrowing it.

6.3.0 closes this as part of the same WIRE-01 rewrite that unifies tab completion onto one dispatch
implementation for both command-executor generations (see the entry above):
`abstracts.command.CommandTabCompletionDispatch.isVisible(CommandSender, Method)` — the mapping's
declared permission checked, then its `requireOp()` — gates every one of the three dispatch paths
(first-token, `<param>`-slot, and the multi-token literal-sibling scan) before a mapping can
contribute anything, on both command-executor generations. Delivered by plan 05-05,
`BaseCommandExecutorTabCompletionTest$PermissionFilterTests` (6 tests) plus
`$ArgumentPositionResolutionTests#permissionFilterHoldsOnMultiTokenPath` and
`$ShellAndParityTests` (agreement between both generations, including withholding a
permission-gated mapping).

**What a module author should check.** A `@CmdMapping` carrying a `permission` its players do not
hold will now stop appearing in tab completion for them — this is the intended fix, not a defect,
but it is a visible behaviour change: a sender who previously saw (and could trigger reflective
invocation for) every sub-command now sees only the ones they are permitted to run.

**Bucket.** This is recorded as a **security fix**, not a neutral behaviour tidy-up, for the same
reason as the GUI click-dispatch entry below: a sender who could previously see a permission-gated
mapping's suggestions, or trigger its suggestion method's reflective invocation, was exploiting a
missing check, not relying on a documented contract. Per
[Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period)
above, security fixes may land without prior notice; no warning period applies here.

`suggest(Player, Command, String[])`'s public signature is unchanged, so `japicmp` cannot detect
this — the entry exists precisely because this document's criterion requires recording what a
signature diff cannot show.

### Recorded instance: tab completion's permission filter now filters silently, dispatch's rejection message is unchanged (SILENT-25 follow-up, 6.3.0)

Real-machine UAT on a built 6.3.0 snapshot found a second defect in the same mechanism the entry
above describes: `CommandTabCompletionDispatch.isVisible` — the predicate gating a permission- or
`requireOp()`-restricted `@CmdMapping` out of tab completion — called `checkPermission`/`checkOp`
directly, and those methods `sendMessage` the "no permission" / "no OP" notice on every denial.
Tab completion evaluates every mapping's visibility on every keystroke, so a player without
permission typing anywhere near a gated sub-command received the rejection message repeatedly,
not once per attempted invocation.

`isVisible` now consults silent predicates (`isPermissionSatisfied`/`isOpSatisfied`) that carry
the exact same permission/`requireOp()` logic without messaging. `checkPermission` and `checkOp`
themselves are unchanged — they remain the actual-dispatch guard for the deprecated
`AbstractCommandExecutor.onCommand`, and continue to message on denial exactly as before.

**What a module author should check.** A player without permission for a gated sub-command no
longer receives a chat message while merely tab-completing near it; they still receive the
rejection message when they actually attempt to run the command. A module or test that asserted a
message was sent during tab completion (as opposed to on actual dispatch) will need updating — this
is expected to be rare, since messaging during completion was itself the defect.

**Bucket.** Recorded as a **security-adjacent fix** under the same no-migration-period rule as the
entry above: the messaging was a side effect of the permission check UAT found, not a documented
contract a module could have been relying on.

`checkPermission(CommandSender, Method)` and `checkOp(CommandSender, Method)`'s public signatures
and behaviour are unchanged, so `japicmp` cannot detect this either — same reasoning as the entry
above.

### Recorded instance: `@AsyncCommand.timeout()` is now honoured, and the default-path double async dispatch is removed (WIRE-12, 6.3.0)

Before 6.3.0, `timeout() > 0` — the default, since `timeout()` defaults to `30` — wrapped the
command's runnable in a *second* runnable whose entire body was to schedule the **original**
runnable async a second time: a double dispatch that enforced no deadline whatsoever and never
reported anything to the sender. This was worse than inert, and it was the default path for every
`@AsyncCommand`. The javadoc claimed the async operation would be "cancelled" on timeout; nothing
was ever cancelled, or even timed (#322).

6.3.0 schedules the command body asynchronously exactly once, unconditionally. When
`timeout() > 0`, a wholly separate watcher task is armed via `runTaskLaterAsynchronously`; if the
deadline passes with the body still running, the sender receives exactly one timeout message,
delivered via the main thread. The body is **never** interrupted — it always runs to completion on
its own; the timeout report and the body's own eventual effects are independent and race-free via
a shared `AtomicBoolean` CAS guard. `timeout() = 0` is unaffected (already single-dispatch, no
watcher, before and after); `@RunAsync` is unaffected (a different, untouched branch). The javadoc
is rewritten to state exactly this — a deadline on how long the framework *waits*, never a
cancellation of the command body — matching the register Phase 2 D-10 already established for
`@Transactional#timeout()`'s structurally identical case. Delivered by plan 05-07,
`BaseCommandExecutorTest$AsyncCommandTimeoutTests` (6 tests).

**Bucket.** "Moving from silent degradation to failure" plus a return-semantics change: a sender
who previously received no timeout-related message under any circumstance may now receive one.
Ships without the two-step warning period under this document's own proven-non-functional
reasoning, reproduced directly rather than argued: reading `BaseCommandExecutor.java:385-409` at
the pre-fix HEAD confirmed the outer runnable's entire body was a second `runTaskAsynchronously`
call with no deadline tracked anywhere. **Measured blast radius: zero** `@AsyncCommand` downstream
use sites across `Modules/`, `Plugins/`, `Libraries/`, `Tooling/` (control: `@CmdMapping`, 71
files); the six real async-command sites in the workspace (`UltiBackup` × 3, `UltiWorlds` × 2,
`UltiEssentials` × 1) all use the older `@RunAsync` annotation, which takes the `asyncCommand ==
null` branch and never entered the double-dispatch path, pre- or post-fix. This is a workspace
measurement, not a claim about consumers on Maven Central, who are unobservable from here.

`japicmp` cannot detect this — confirmed empirically, not merely by inspection: `japicmp`'s own
`clean verify` run reports `AsyncCommand` as `UNCHANGED ANNOTATION` and `BaseCommandExecutor`'s
public constructors as `UNCHANGED CONSTRUCTOR`.

### Recorded instance: `.filter(x).build().register()` now actually filters, and four confusable `SimpleTempListener` constructors are deprecated (SILENT-12 / SILENT-13, 6.3.0)

Before 6.3.0, `TempListener.DefaultTempListenerBuilder.build()` called the
`(Class, TempEventHandler, EventPriority)` `SimpleTempListener` constructor — the one three-argument
overload with **no** filter parameter — so a filter set via `.filter(...)` was silently discarded
and `build()` always produced a listener that delivered every event, filter or not (#313). Its
sibling `listen()`, three lines below, already called the correct four-argument overload. The root
design defect: the released jar's two three-argument overloads differ only in whether the *last*
parameter is `EventPriority` or `Function<E, Boolean>` — same arity, no compile-time signal for
picking the wrong one.

6.3.0 makes `build()` call the same four-argument `(Class, EventPriority, TempEventHandler,
Function)` constructor `listen()` already used. `.filter(p).build().register()` now genuinely drops
a rejected event and delivers an accepted one. Because `TempListener`'s interface already carries
both `register()` and `unregister()`, this single change also closes a second, related issue (#324)
as a derivation rather than separate work: one `.filter(p).build()` call now yields one instance
that both filters *and* can be unregistered, with no separate mechanism needed. The four confusable
constructors — no-argument, `(Class, TempEventHandler)`, `(Class, TempEventHandler, Function)`,
`(Class, TempEventHandler, EventPriority)` — are marked `@Deprecated(since = "6.3.0", forRemoval =
true)`; the four-argument all-args constructor is the one kept, unchanged. These four automatically
enrol in the [removal list](#removal-list-for-630) above — generated from the annotation, per this
document's own policy — and are scheduled for deletion in Phase 7; they are not listed by hand.
Delivered by plan 05-08, `TempListenerTest$DefaultTempListenerBuilderTests` and
`TempListenerTest$UnregisterTests`.

**Bucket.** Security-fix / "correcting behaviour that plainly contradicts the documentation"
channel — `build()`'s own contract (a builder whose `.filter(...)` method exists specifically to be
applied) was silently violated. Worth noting explicitly: `interfaces.impl.PlayerTempListener` is
itself `@Deprecated`, with javadoc recommending `TempListener#common(Class)` filtered by player as
the replacement — meaning the framework's own documented migration path was steering callers onto
the exact broken filter this fixes. No warning period: measured **zero** `TempListener` downstream
use sites monorepo-wide (control: `extends UltiToolsPlugin` 20 hits, `registerSelf` 30 hits,
`@EventListener` 46 hits — confirming the search mechanism works), so no known caller currently
depends on the silently-inert filter.

`japicmp` cannot detect the `build()` behaviour change — no signature changed. The four constructor
deprecations do surface, as `japicmp`'s own `"Annotation deprecated added"` classification,
confirmed non-breaking by this plan's own `mvn clean verify` run.
### Recorded instance: the declarative GUI repaint pipeline actually repaints, and `GuiRenderer.initialize`'s signature changes to carry a `Supplier<Widget>` (D-09 items 1-3 / WIRE-02, 6.3.0)

Before 6.3.0, nothing in the declarative GUI layer repainted after the first frame. `GuiRenderer`
built its `Widget` tree once, at `initialize(Widget, BuildContext)`, and never re-derived it;
`Element.update()` never marked itself dirty; and `collectRenderNodesRecursive` stored the same
live, cached `RenderObjectElement.getRenderNode()` reference into `lastRenderNodes` on every frame,
so `RenderNodeDiffer.diff()` always compared a node against itself. A lore-only, display-name-only,
or item-type-only widget change produced no inventory update at all — `UI = f(state)`, the
subsystem's own thesis, did not hold.

6.3.0 closes this. `GuiRenderer.initialize(Supplier<Widget> widgetSupplier, BuildContext context)`
re-derives the `Widget` tree from current state exactly once per `performBuild()`, including the
first frame; `Element.update()` marks its element dirty unconditionally, in the base class, for all
three subclasses; `collectRenderNodesRecursive` snapshots each `RenderNode` via `.copy()` before
storing, breaking the self-comparison. A second, previously undocumented gap was found and closed
in the same plan: `State.setState()` on a nested `StatefulWidget` bubbles toward the mounted root,
but nothing connected that bubbling to `GuiRenderer.scheduleBuild()` — root `CLAUDE.md`'s own
documented `CounterButton` example did not actually hold until this fix
(`Element.setRootBuildScheduler(Runnable)`, registered by `GuiRenderer.mountRoot()`). Delivered by
plan 05-11, `GuiRendererRepaintTest` (14 tests).

**Unlike every other entry in this document's "Behavioral changes" section, this one is not
invisible to `japicmp`.** `GuiRenderer.initialize`'s parameter type changed from `Widget` to
`Supplier<Widget>` — a genuine JVM method-descriptor change — and `japicmp`'s own report
(`target/japicmp/japicmp.diff`, baseline the released 6.2.5 jar, per this repository's japicmp
configuration) names it directly:

```
***! MODIFIED CLASS: PUBLIC com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer  (not serializable)
---! REMOVED METHOD: PUBLIC(-) void initialize(com.ultikits...core.Widget, com.ultikits...core.BuildContext)
+++  NEW METHOD: PUBLIC(+) void initialize(java.util.function.Supplier<com.ultikits...core.Widget>, com.ultikits...core.BuildContext)
```

An earlier draft of the plan that produced this entry asserted "none of the behaviour changes
alters a signature, so `japicmp` cannot surface any of them" for the whole GUI lane. That is wrong
for this one member specifically, and the claim is not repeated here — see plan 05-15's own SUMMARY
for the correction. Every other entry below this one in this document's GUI-lane record is a true
behavior-only change with no signature diff; this is the one exception, and `japicmp`'s own diff is
quoted above rather than asserted.

**Bucket.** `DeclarativeGui.onOpen()` is the only in-tree caller of `initialize(...)` and was
updated in the same plan, so this is source- and binary-incompatible for any external caller. The
entire `abstracts.gui.declarative` package tree carries `@ApiStatus.Experimental`, and this method
was measured with **zero** downstream call sites across the monorepo (control: the older, stable
imperative `abstracts.gui` generation has 8 — confirming the search mechanism works; see
`05-CONTEXT.md` D-12). No `@Deprecated(forRemoval = true)` warning period preceded this change, and
none is offered here, under the same reasoning this document already applies to the AOP removals in
[the same-release removal exception](#exception-removal-in-the-same-release-that-announces-it):
clause 2 ("shipped but never wired into anything that calls it") holds on the zero-adoption
measurement above, and clause 1 ("proven non-functional on the currently released version") holds
independently — the pre-6.3.0 method was itself non-functional in the released 6.2.5 jar, since a
`Widget` supplied once at `initialize()` time and never re-derived meant every subsequent frame
silently failed to repaint; the released jar's own behaviour is the reproduction, not an argument
about it. This method does not appear on the [removal list](#removal-list-for-630) above because it
was never `@Deprecated` — it cannot be entered there by this document's own generation rule, which
is exactly why it is recorded here by hand instead.

### Recorded instance: a click on the player's own inventory can no longer reach a declarative-GUI handler (D-09 item 4 / WIRE-02, 6.3.0)

Before 6.3.0, `GuiRenderer.handleClick` looked its `clickHandlers` map up by `event.getSlot()` — a
slot number relative to whichever inventory the click landed in, which collides between the GUI's
own top inventory and the player's own inventory shown below it in the same `InventoryView`. A click
in the **player's own inventory**, at a slot numerically equal to a slot the GUI had registered a
handler for, invoked that handler — an unintended cross-inventory dispatch, not a documented
capability.

6.3.0 bounds-checks `event.getRawSlot()` against `gui.getSize()` before any handler lookup, and
looks the handler up in the same slot space `RenderNode.getSlotIndex()` already populates
`clickHandlers` with. This rejects every player-inventory raw slot (which always follows the GUI's
own slots in the combined `InventoryView`) and the `-999` sentinel Bukkit reports for a click
outside the window entirely. `clickHandlers` remains the sole record of slot ownership — only the
lookup key changed, from a colliding relative slot to a bounds-checked raw slot. Delivered by plan
05-12, `GuiClickDispatchTest` (7 tests), including the whole colliding-slot range, shift-click and
number-key clicks, and a repaint that moves a handler's slot moving its click target with it.

**Bucket.** This is recorded as a **security fix**, not a neutral behaviour tidy-up: a caller who
could previously trigger a GUI handler by clicking a numerically-colliding slot in their own
inventory was exploiting a defect, not relying on a documented contract. Per
[Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period)
above, security fixes may land without prior notice; no warning period applies here.

`handleClick`'s public signature is unchanged (`public void handleClick(InventoryClickEvent
event)`), so `japicmp` cannot detect this — the entry exists precisely because this document's
criterion requires recording what a signature diff cannot show.

### Recorded instance: `GridView` positions any widget type as parent data written at render time (D-11 / WIRE-03, 6.3.0)

Before 6.3.0, `GridView.Builder.items(items, itemBuilder)` special-cased `ItemDisplay` — a
six-field hand copy computed each child's slot and constructed the `ItemDisplay` directly. Any
other widget type added to a `GridView` via `.child(Widget)`/`.children(...)` — a `TextButton`, or a
mixed-type grid — was never auto-positioned; it simply stacked on its own declared (or default)
slot, which the grid never touched.

6.3.0 writes each child's grid-computed slot onto the `RenderNode`(s) that child's own subtree
produced, at render time (`GridViewElement.applyGridPositions()`, called from both `mount()` and
`performRebuild()`, before `GuiRenderer`'s snapshot collection) — mirroring
[Flutter's `ParentDataWidget`](https://api.flutter.dev/flutter/widgets/ParentDataWidget-class.html)
/ `Stack`+`Positioned`, the pattern this subsystem's own documentation already claims to port: a
layout parent attaches data the child never sees, rather than the child computing its own position.
`GridView.Builder.items()`'s `ItemDisplay`-only special case is deleted; any widget type now
positions correctly inside a `GridView`. `Widget`'s own API gained no slot-related method, so no
downstream custom widget needs to change. Delivered by plan 05-13, `GridViewTest` (15 tests) and
`GridViewElementTest` (13 tests — D-09 item 5's keyed-reconciliation convergence that this
positioning is built on).

**Conflict rule (D-11).** A `GridView` always wins over an explicit child slot, **and** emits a
`WARNING` naming the child — mirroring Flutter's hard failure on a misplaced `Positioned` rather
than silently ignoring it. A child with no explicit slot, or legitimately declared at slot `0` (the
`ItemDisplay`/`TextButton` builders' own pre-existing default, treated as the "unset" signal since
neither builder carries a separate sentinel — a widget explicitly calling `.slot(0)` inside a
`GridView` is therefore indistinguishable from one that never called `.slot()` at all, and neither
triggers the warning; see 05-13-SUMMARY.md's stated tradeoff), produces no warning.

`GridView.Builder.items()`'s signature is unchanged — its `ItemDisplay`-only branch was deleted, not
the method itself — so `japicmp` cannot detect this behavioural change.

### Recorded instance: two declarative-GUI builder-method pairs are removed rather than given a guessed implementation (D-09, 6.3.0)

`Container.Builder.background(IconWrapper)`/`Container.getBackground()` and
`GridView.Builder.rows(int)`/`GridView.getMaxRows()` are deleted, not implemented. Both pairs were
fluent builder methods whose stored value was never read by any downstream code:

- `Container` carries no spatial bounds of its own — no `startSlot`/`columns`/`rows` the way
  `GridView` does — so "fill the unoccupied slots" has no answerable scope from inside
  `ContainerElement` without risking a silent overwrite of a sibling subtree's `RenderNode`s, the
  only region actually visible from a `Container`'s own subtree. A wrong implementation would have
  looked correct for a single root-level `Container` and corrupted the display the moment one was
  nested next to other content.
- `GridView.Builder.rows(int)`'s `maxRows` field was set but never read by
  `calculateSlotForChild` (grep-confirmed across `src/main` and `src/test`); implementing an
  overflow-capping rule using it would have meant inventing new layout semantics outside this
  plan's own scope.

Root `CLAUDE.md` gotcha #14 previously — and, before plan 05-13, correctly — described
`Container.background(...)` as dead code that "silently does nothing." As of this plan, calling
either deleted method is a **compile error**, not a silent no-op; the gotcha has been corrected to
say so. `IconWrapper.java` is left in place, though `Container.background(...)` was its only
production call site and it is therefore now fully unused in `src/main` — flagged for a future
cleanup plan, not resolved here. Delivered by plan 05-13,
`GridViewTest#containerNoLongerExposesBackground` / `#gridViewNoLongerExposesRows` (reflection-based
regression guards asserting `NoSuchMethodException`).

**`japicmp` read, per this phase's stated obligation — not assumed.** Both classes carry
`@ApiStatus.Experimental`; neither method pair ever carried `@Deprecated(forRemoval = true)`, so
neither is entered on the [removal list](#removal-list-for-630) above by this document's own
generation rule — that list is generated from the annotation, and this lane deprecates nothing, it
deletes outright. `japicmp`'s own report (`target/japicmp/japicmp.diff`, `mvn -B clean verify`
against the baseline released 6.2.5 jar) was read directly and confirms exactly these two pairs,
nothing more:

```
---! REMOVED METHOD: PUBLIC(-) com.ultikits.ultitools.abstracts.gui.declarative.widgets.IconWrapper getBackground()
---! REMOVED METHOD: PUBLIC(-) com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container$Builder background(com.ultikits.ultitools.abstracts.gui.declarative.widgets.IconWrapper)
---! REMOVED METHOD: PUBLIC(-) int getMaxRows()
---! REMOVED METHOD: PUBLIC(-) com.ultikits.ultitools.abstracts.gui.declarative.widgets.GridView$Builder<T> rows(int)
```

The gate is **report-only** — `pom.xml` sets no `breakBuildOn*` flag for `japicmp-maven-plugin` — so
this `mvn verify` passing is not itself evidence of compatibility; only the diff read directly is.
This is also the list Phase 7 is expected to lift into its japicmp exclusion list member-by-member,
matching this entry, when that phase arms the gate.

**Bucket.** Same reasoning as the `initialize(...)` entry above: clause 2 of
[the same-release removal exception](#exception-removal-in-the-same-release-that-announces-it)
("shipped but never wired into anything that calls it"), confirmed by the grep above for both
fields. No warning period, because there is no working behaviour a warning period would protect — a
fluent method whose stored value nothing reads has no observable effect to preserve in the first
place.

### Recorded instance: `@ApiStatus.Experimental` is retained on the declarative GUI packages; only the wording changes (D-12, 6.3.0)

No removal, no signature change, no behavioural change — recorded here only so the three entries
above are not misread as "the surface is now stable." All six `package-info.java` files under
`abstracts.gui.declarative` (root, `core`, `engine`, `widgets`, `util`, `widgets.navigation`) keep
`@ApiStatus.Experimental`. Only the accompanying javadoc — and the documentation site's matching
`::: warning` — changes, from describing "known gaps in v6.2.5" to naming the three seams this
phase closed (repaint re-derivation, click-dispatch bounds-checking, `GridView` any-widget-type
positioning) and stating why the marker is retained: zero downstream real-server feedback on four
rewritten core mechanisms at once. Delivered by plan 05-14.

### Recorded instance: eight `ultipanel.capabilities.*` switches gate every panel-facing capability, with a split default (REMOTE-01, 6.3.0)

Before 6.3.0, every remote capability the panel exposes — monitoring, logs, player events, file
read/write/delete, remote command execution, `server.properties` editing — was reachable the moment
a server authenticated to UltiCloud, with no per-capability switch at all. 6.3.0 adds eight config
keys under `ultipanel.capabilities`, one per capability, each independently enforced at both the
inbound dispatch gate and, for the four capabilities with no inbound entry point, at the point their
collection would otherwise start.

The defaults are **split, not uniform**: `monitoring`, `logs`, `player-events` and `file-read`
default `true`; `commands`, `file-write`, `file-delete` and `server-properties` default `false`.
`monitoring` in particular is deliberately excluded from the default-off set — `batch_update`'s
status payload is the panel's only "server is alive" signal, and defaulting it off would make an
upgraded server read as *offline*, sending the operator to check networking and tokens rather than
the one config block that actually explains it. The worst failure shape a default can produce is one
where the symptom points the reader the wrong way, and a false "offline" reading does exactly that.

**The observable change:** on a server upgraded to 6.3.0 with no config edits, the panel's remote
command execution, file writing, file deletion, and `server.properties` editing all stop working
until an operator explicitly enables the corresponding key in `plugins/UltiTools/config.yml`. Every
one of those refusals arrives as a message naming its own config key and the file to edit — never a
silent no-op and never a generic "access denied." Monitoring, log streaming, player events, and file
reading keep working exactly as before, unchanged by this release.

**Bucket.** This is a documented default value flipping to a more restrictive state on four of eight
capabilities, which would ordinarily need a migration-period warning — but it is filed under
[Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period)
as a security fix: narrowing an unauthenticated-by-default remote write/execute surface to
default-deny is exactly the class of change that section already exempts from prior notice. A module
author or operator relying on any of the four now-off capabilities sees an immediate, clearly-worded
refusal rather than a silent behavior change, which is the outcome a migration-period warning exists
to produce anyway.

### Recorded instance: a recursive directory delete through the remote file API now requires an explicit `recursive: true` (REMOTE-03, 6.3.0)

Before 6.3.0, a `delete` request naming a directory recursively removed it and everything inside,
with no flag and no confirmation of any kind — the same shape as `rm -r` with none of `rm -r`'s
opt-in. 6.3.0 requires the request to carry a JSON boolean `recursive: true`; a directory delete
request that omits the field, sets it to `false`, or sets it to anything other than a real JSON
boolean is refused before any filesystem access, naming the missing field.

**The observable change:** an older panel build that does not yet send the `recursive` field has
every directory-delete request refused with a clear reason, where it previously succeeded
immediately. The break is accepted narrowly: `file-delete` defaults `false` (see the capability
entry above), so a panel able to reach this code path at all has already had the capability
deliberately enabled by an operator who can update the panel in step.

**Bucket.** No migration period — filed as a security fix under
[Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period).
An unconfirmed recursive delete of an arbitrary directory (including, before this release, the
server's own world folder) is the kind of defect this document's security-fix exemption exists for,
not a documented capability being withdrawn.

### Recorded instance: the remote file `list` response now marks refused entries instead of omitting them (REMOTE-03, 6.3.0)

Before 6.3.0, `file_operation_result`'s `list` payload silently dropped any child entry the caller was
not permitted to see — a credential file or a path outside the editable-root set simply did not
appear, and the panel had no way to distinguish "this file does not exist" from "this file is hidden
by policy." 6.3.0 adds two fields to every entry, `accessible` (boolean) and, on a refused entry,
`reason` (`PROTECTED_CREDENTIAL` for the unconditional credential-deny layer, `OUTSIDE_ROOTS` for the
operator-configured editable-root boundary). A refused entry now appears in the listing with
`accessible: false` and its `reason`, carrying no `size`/`lastModified`/`readable`/`writable`; an
accessible entry gains `accessible: true` alongside its existing six fields, unchanged. `totalCount`
counts the returned array's length, so it now includes refused entries too.

**This is a backward-compatible schema addition, not a breaking change.** An older panel build that
does not know about `accessible`/`reason` renders exactly what it rendered before this release, plus
the rows that used to be hidden — nothing it already reads changes shape or meaning.

**Bucket.** No migration period needed — this is a pure additive change under
[Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period);
recorded here anyway because `totalCount`'s new inclusion of refused entries is a real, if narrow,
semantic shift a panel author comparing `totalCount` against a rendered row count should know about.

### Recorded instance: the remote command blocklist becomes fully operator-editable, with no unoverridable floor (REMOTE-02, 6.3.0)

Before 6.3.0, the set of remote commands the panel refuses to execute was a hardcoded, unconfigurable
list of ten entries. 6.3.0 moves it to `ultipanel.commands.blocklist`, an operator-editable YAML list
seeded with the same ten entries, and the operator may add to it or remove from it in either
direction — including emptying it entirely.

**There is deliberately no unoverridable floor.** The panel is the operator's remote console, not an
autonomous agent, and the party this configuration constrains is the operator, not an attacker: an
attacker who has compromised the panel credential already holds the operator's own identity, and a
hardcoded floor constrains only the legitimate operator's own choices while doing nothing to stop
that attacker from reaching the same outcomes through other in-game means. The compensating control
for an operator who empties the list is the remote action log recording every command executed
through the panel, not a floor that would not have stopped a compromised credential in the first
place.

**Bucket.** No migration period — filed under
[Behavioral changes that need no migration period](#behavioral-changes-that-need-no-migration-period).
The ten shipped entries are unchanged from the prior hardcoded set, so no existing operator observes
any difference in default behaviour; only the ability to edit the list is new.

### Recorded instance: `CommandExecutionManager.isCommandAllowed` and `FileOperationManager.isPathAllowed` change return type from `boolean` to `AccessDecision` (REMOTE-02 / REMOTE-03, 6.3.0)

Both methods now return the new `entities.AccessDecision` type instead of `boolean`, so a caller
learns not only whether a command or a file path is permitted but, on refusal, whether the cause is
operator-configurable (naming the config key to change) or an unconditional restriction that no
configuration can lift — the distinction the panel's refusal messages depend on throughout this
phase's other recorded instances.

**Both `CommandExecutionManager` and `FileOperationManager` carry `@ApiStatus.Internal`**, and this
document's own [removal-list preamble](#removal-list-for-630) states that `@ApiStatus.Internal` types
were never public API, so a change to either is not a compatibility event by this document's own
rule — this entry is recorded anyway. `japicmp` reads compiled bytecode; it has no visibility into
`@ApiStatus` annotations, which are a source/IDE-level signal only, and it will report both of these
as a `MODIFIED METHOD` / return-type change against the released 6.2.5 baseline regardless of the
`@ApiStatus.Internal` exemption. A `japicmp`-reported break with no matching entry in this document
leaves the next reader unable to tell whether it was deliberate — the exact gap the
[`GuiRenderer.initialize`](#recorded-instance-the-declarative-gui-repaint-pipeline-actually-repaints-and-guirendererinitializes-signature-changes-to-carry-a-supplierwidget-d-09-items-1-3--wire-02-630)
entry above exists to not repeat, and this entry exists for the same reason.

**Measured blast radius: zero.** Downstream call sites for `isPathAllowed`, `isCommandAllowed`,
`setBlockedCommands`, `getFileOperationManager` and `getCommandExecutionManager` across `Modules/`,
`Plugins/`, `Libraries/` and `Tooling/`: **0**. Control group proving the search itself works:
`UltiToolsPlugin` — the type every module extends — matches **156** downstream files in the same
search. The 11 raw hits a bare search for `isCommandAllowed` returns are all a single unrelated
method, `UltiLogin`'s own `LoginService.isCommandAllowed` (`LoginService.java:717`), not a caller of
either changed method — a hit count alone is not evidence without inspecting what each hit is.

**Bucket.** No migration period — both types are `@ApiStatus.Internal`, so no downstream author had a
supported contract to migrate off in the first place; recorded for `japicmp` traceability only.

## Binary incompatibilities the removal list cannot cover

The removal list only covers changes where somebody knew they were changing an API. Both of its
preconditions — carrying `@Deprecated(forRemoval = true)` and having crossed one MINOR — require the
author to recognise the change as an API change in the first place. One class of change does not meet
that precondition: to its author it is not an API change at all, yet it alters the **JVM method
descriptor** of a public method. Such a change necessarily breaks binary compatibility while
possibly not breaking source compatibility at all, and therefore bypasses every process that assumes
somebody will notice.

This has happened twice, and both cases are recorded here. The first in a MINOR, the second in a
PATCH — **no release level is exempt**:

### First occurrence: 6.1.1 → 6.2.0, a MINOR

When Spring was removed, the type of the context field in `UltiToolsPlugin` changed from
`AnnotationConfigApplicationContext` to `SimpleContainer`. The field carries `@Getter`, so the
**return type** of the Lombok-generated `getContext()` changed with it:

```
A module compiled against 6.0.6 records
  getContext:()Lorg/springframework/context/annotation/AnnotationConfigApplicationContext;
Frameworks from 6.2.0 onward provide
  getContext:()Lcom/ultikits/ultitools/context/SimpleContainer;
```

(The starting point is **6.2.0**, not 6.2.1. 6.2.0 was published to Maven Central but has no git tag
in the repository — **read the release list from `maven-metadata.xml`, not from `git tag`**.
Diagnosing this on 6.2.0 hits the same exception.)

The return type is part of the method descriptor, so to the JVM these are two different methods, and
an old JAR gets a `NoSuchMethodError` inside `registerSelf()`.

**On the source side the outcome depends on how the call is written**, which is exactly why it is
easy to miss. A call like UltiEconomy's `getContext().getBean(X.class)` never names the return type,
so the same source compiles against both versions. But if the source says
`AnnotationConfigApplicationContext ctx = plugin.getContext();`, passes the return value to a method
that accepts the old type, or overrides `getContext()`, then a recompile fails — `SimpleContainer`
has no inheritance relationship with the old type. The accurate statement about this class of change
is therefore "**necessarily breaks binary compatibility; whether it breaks source compatibility
depends on the caller**", not "only breaks binary compatibility".

Three lines of defence fail at once:

- Nothing is "removed", so this cannot be placed on the removal list;
- There is no target to annotate with `@Deprecated`, so `-Xlint:removal` never fires downstream;
- `PluginManager`'s version gate does not catch it either — it only tests
  `api-version > current framework version`, that is, the single direction of "the module requires a
  newer framework than the one installed". The old module's declared floor is satisfied, and it still
  fails.

### Second occurrence: 6.2.0 → 6.2.1, a PATCH

The previous case was a MINOR. The second happened in a **PATCH**, so "watching MINOR releases is
enough" does not hold.

`43f55ea refactor!: replace AbstractDataEntity with BaseDataEntity<String>` replaced the entity type,
and every public member whose signature mentions that type had its descriptor changed with it:

```
6.2.0  DataOperator.insert   (Lcom/ultikits/ultitools/abstracts/AbstractDataEntity;)V
6.2.1  DataOperator.insert   (Lcom/ultikits/ultitools/abstracts/data/BaseDataEntity;)V
```

**The complete list was computed per symbol, not written by hand** (it was written by hand three
times, and each version missed something). The method: unpack both framework JARs, run `javap -s`
over `com/ultikits/ultitools/**`, build a table of `(class, member name) → set of descriptors`, and
compare. **The set matters**, otherwise overloads overwrite each other — that is how `exist(T)` was
masked by `exist(WhereCondition[])` and missed for a round.

The result is **14 public members across 5 types**, with **zero removals and zero additions** in this
change; only descriptors moved:

| Type | Affected members |
|---|---|
| `interfaces.DataOperator` | `exist(T)` · `getById` · `insert(T)` · `update(T)` |
| `interfaces.Query` | `first()` |
| `…impl.data.AbstractRelationalDataOperator` | same four as `DataOperator` |
| `…impl.data.json.SimpleJsonDataOperator` | same four as `DataOperator` |
| `…impl.data.QueryImpl` | `first()` |

What downstream code actually calls statically are the **5 interface members** in the first two rows;
the other 9 are same-named mirrors on implementation classes. Note that `Query.first()` is a separate
entry: a module that only uses `.query()….first()` calls no `DataOperator` method at all and is still
affected.

Overloads that do not mention the entity type (`update(String, Object, Object)`,
`exist(WhereCondition[])`) kept their descriptors. `AbstractDataEntity` itself was not deleted, so
this case likewise cannot go on the removal list.

**Descriptor changes are inherently bidirectional**, and both instances are. When a symbol has the
same name and a different descriptor across two versions, then whichever side you compile against,
the other side does not have it. The old JAR fails on the new framework (looking for
`(AbstractDataEntity)`, which no longer exists) and the new JAR fails on the old framework (looking
for `(BaseDataEntity)`, which does not exist yet). The same source, recompiled with only the pin
changed, produces two artifacts that each run on one side only. The first instance (`getContext()`)
behaves the same way; it is just that only the "old JAR meets new framework" direction was actually
triggered at the time.

**The second direction carries an extra layer: it is let through before it fails.** All 15 official
modules raised the `pom.xml` pin to 6.2.1, while none changed `api-version` in `plugin.yml`, which
remained `620`. The artifact records 6.2.1 descriptors but declares a 6.2.0 floor, and the framework
only sees the latter. The result: a server running 6.2.0 **loads the module successfully**, then hits
`NoSuchMethodError` on the first data read or write. 11 modules were affected (the remaining 4 do not
touch the ORM, which serves as a negative control).

Java resolves lazily, so "it starts up" is not evidence: a server owner who never touches the data
path may never see the problem.

**The same commit also contains an exact counter-example worth remembering.** It changed the generic
bound of `UltiToolsPlugin.getDataOperator` from `AbstractDataEntity` to `BaseDataEntity<String>`, yet
the descriptors are **identical** across both versions — the bound is erased, and `T` was already
`Class` / `DataOperator` in the descriptor:

```
6.2.0  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
6.2.1  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
```

This mirrors the previous situation: **changing a generic bound breaks source compatibility without
breaking binary compatibility, while changing a return type or parameter type breaks binary
compatibility without necessarily breaking source compatibility.** Neither involves a removal, so
neither goes through the removal list.

**This holds only for the `getDataOperator` call site, and should not be generalized to a whole
module.** Once you hold a `DataOperator` you will almost certainly call `insert` / `update` /
`exist` / `getById`, and those four did change descriptors. So a module that uses the ORM **breaks in
both directions**:

| Pin at build time | Running on 6.2.0 | Running on 6.2.1+ |
|---|---|---|
| 6.2.0 | Works | `NoSuchMethodError` (looks for `(AbstractDataEntity)`, no longer present) |
| 6.2.1 | `NoSuchMethodError` (looks for `(BaseDataEntity)`, not yet present) | Works |

The measurement used one module's identical source, recompiled with only the pin changed: the
artifact built against 6.2.0 is missing 3 symbols on 6.2.1 and 0 on 6.2.0; the artifact built against
6.2.1 is the reverse, missing 3 on 6.2.0 and 0 on 6.2.1. It is symmetric, and neither side crosses
over. "Runs but does not compile" describes the `getDataOperator` line only.

### What this means for you

**Pinning low is not the same as being safe.**
[Module versioning](https://dev.ultikits.com/guide/advanced/module-versioning) states that compiling
against an older API will not produce a `NoSuchMethodError` by reaching something newer. That
statement still holds, but it only rules out one direction of cause. The reverse direction — the
framework changing its own descriptors — produces the same exception.

**There is also no free fix for this class of problem.** "Recompile and republish" is insufficient and
can be wrong: if the pin in `pom.xml` is still at 6.0.6, running the build again still generates the
*old* descriptors from 6.0.6 class files, and the artifact still fails on the new framework. A real
fix requires raising the compile dependency to the version that contains the new descriptors and
recompiling against it.

**Raising the pin does only half of the job, and it is the half nobody checks.** These are two
independent numbers and should not be treated as one:

| Number | Determines | Who checks it |
|---|---|---|
| The `UltiTools-API` version in `pom.xml` | Which version's descriptors your bytecode records | Nobody. It is `provided`, does not enter the JAR, and the framework cannot see it at runtime |
| `api-version` in `plugin.yml` | The declared runtime floor | `PluginManager.isUltiToolsVersionCompatible`. This is the only value that is checked |

So "the pin is the floor" is wrong: raising the pin does not raise the floor. An artifact compiled
against a new framework while still declaring an old `api-version` is **let through** by an old
server, and then fails on the first call into a new descriptor — the same `NoSuchMethodError`, in the
opposite direction. **Both numbers must move together.**

**This is not hypothetical.** The second instance above happened exactly this way: 15 official modules
raised the pin to 6.2.1 and left `api-version` at `620`, so 11 of them shipped artifacts declaring a
floor lower than their real requirement (all corrected to `621` on 2026-08-16). **No tool reported
anything**: builds were green, and the plugins worked on every server running 6.2.1 or later. Only a
server sitting exactly on 6.2.0 would load them successfully and then fail on the first data
operation.

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

The second case is the other side of the instance in this section: an artifact pinned to 6.2.0 while
declaring `api-version: 621` references `insert(AbstractDataEntity)`, and 6.2.1 does not have that
descriptor — no amount of raising the floor will make it appear. Determine which generation of symbol
is missing before deciding which number to move.

The complete fix is therefore: raise the pin, recompile, **raise `api-version` to the corresponding
API level as well**, and accept the consequences that follow.

What cannot span both sides is, precisely, **the artifact that statically calls the method**.
Descriptors are written into the call site at compile time, so one call site can only match one side.
Three options follow, in increasing cost:

1. **Accept the raised floor** (the default choice). Old servers stay on the old JAR; the new JAR
   serves the new framework only.
2. **Ship separate artifacts per framework range.** This means maintaining two release lines.
3. **Write a compatibility shim**: call reflectively (`getMethod("getContext").invoke(plugin)` to get
   an `Object`, then reflectively call `getBean`), or lazily load different adapter implementations
   per framework version. A reflective call site links statically against neither version's return
   type, so **a single artifact really can run on both sides**. The cost is that this path loses
   compile-time checking, errors surface only at runtime, and you will receive no compiler warning
   when the framework changes it again.

Option 3 is genuinely viable; do not assume it does not exist merely because the first two are listed
first. But it converts a problem detectable at compile time into one that only appears at runtime. It
is worth taking only when you must continue supporting old servers.

In other words, the guidance elsewhere in this document — that a lagging pin is a normal state and
does not need to be changed for its own sake — **does not apply here**. That guidance says not to move
the pin without a reason; a descriptor change is a reason.

As for how often to check, that depends on whether the gate below is wired up. **It is not yet, so
the answer is: re-verify whenever the framework version changes, including a PATCH.**

- This document states above that a PATCH removes no public API. That promise covers *intentional*
  removals, because it relies on a person recognising an API change first. An unintentional
  descriptor change is by definition on no schedule, **so it can appear in a PATCH as well**. That
  sentence began as an inference; the second instance in this section (6.2.0 → 6.2.1) confirmed it —
  that was a PATCH. **"Watching MINOR releases is enough" does not hold.**
- Once the japicmp gate is wired up, binary compatibility in a PATCH will have been verified per
  method by a machine. Only then does it become reasonable to worry about this only across MINOR
  releases.

### What this means for us

A human process cannot catch this class of change: it would require an author changing a field type to
realise that this alters the descriptor of a public method. What can catch it is a machine comparing
descriptors method by method, that is, the japicmp gate (issue #216). Until that is wired up, this
document's promise about binary compatibility is **limited to intentional removals**. Unintentional
descriptor changes can only be recorded after the fact; we cannot guarantee to catch them beforehand.

## Support matrix

| Item | Value |
|---|---|
| Server | Paper (plain Spigot is not supported — the code uses Adventure `Component` throughout) |
| Build JDK | 21 |
| Bytecode target | Java 8 (`-source`/`-target`, not `--release`) |
| `api-version` in `plugin.yml` | `1.19` (Bukkit API level, unrelated to the two rows above) |
| `api-version` in a module's `plugin.yml` | `620` (UltiTools API level, unrelated to Bukkit's field of the same name) |

### Where runtime dependencies come from

The framework JAR bundles exactly two libraries: obliviate-invs (GUI) and UniversalScheduler
(scheduling). Everything else is not in the JAR and arrives by one of two routes:

| Delivery route | Version decided by | Examples |
|---|---|---|
| The `libraries:` block in `plugin.yml`, downloaded by Paper from coordinates | **This repository** | Gson, MySQL Connector/J, HikariCP, Java-WebSocket, ByteBuddy, XSeries |
| Shipped by the Paper server itself | **The Paper build the server owner installed** | log4j, the Maven resolver Paper uses internally for `libraries:` and its dependencies |

**XSeries moved out of the bundled JAR in 6.3.0.** Through 6.2.5 it was shaded in — the sentence
above used to say "three libraries", XSeries among them. From 6.3.0 it is `provided` scope and
delivered through the `libraries:` route in the table instead, the same way Gson and HikariCP
already were. If you shade this framework's JAR into your own uber-jar, XSeries no longer comes
along for the ride: declare it yourself (`com.github.cryptomorin:XSeries:13.0.0`, or your own
pinned version) if your module uses it.

This boundary determines who fixes a third-party security advisory. For anything in the first
category, pinning a version in this repository is an effective fix. For anything in the second, the
only fix is **upgrading Paper** — no change in `pom.xml` will alter the jar actually loaded on the
server; it only creates the impression that the problem has been fixed.

Note that Maven's `provided` scope is **not** this boundary: both categories are declared `provided`
in `pom.xml`. What decides the category is whether the dependency appears in the `libraries:` block,
not the scope.

Server owners are advised to keep up with Paper builds, security builds in particular.

## Feedback

If you disagree with this policy, or your module is affected by the removals above, please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues).

---

# 兼容性与版本策略

[English](#compatibility-and-versioning-policy) · [中文](#兼容性与版本策略)

本文件说明 `com.ultikits:UltiTools-API` 的版本号含义、废弃与移除策略，
以及当前正在进行的移除动作。面向下游模块作者。

## 版本号语义

**本项目的版本号是产品阶段信号，不是严格的 semver 契约。**

- **PATCH**（例如 6.2.4 → 6.2.5）：小更新与紧急修复。不移除公开 API。
- **MINOR**（例如 6.2.x → 6.3.0）：功能演进。**可能包含公开 API 的移除**。
- **MAJOR**：保留给框架层面的方向性变更。不会仅仅为了清理废弃 API 而发布。

### 一个公开 API 何时可以被移除

只有同时满足以下两条，才会被列入移除清单：

1. 它在代码里带有 `@Deprecated(since = "…", forRemoval = true)`；
2. 从**首个带上这个标注的发布**算起，已经跨过至少一个 MINOR 版本。

第 2 条的起算点是**警告真正发到你手里的那个版本**，不是标注里 `since` 写的值——
`since` 表达的是「我们认为它何时该被废弃」，可以回填；起算点不行。
下方清单为每一项标出了这个版本。

这两条是移除的**全部依据**。下游引用量不再作为依据：
清单里附带的引用量实测只是参考信息，它能告诉你哪几项的迁移成本真实存在，
但「零引用」只证明我们能看到的仓库里没人用，证明不了组织外的第三方没在用。

为什么用 `forRemoval` 而不是普通的 `@Deprecated`：javac 的 `-Xlint:removal` 自 JDK 9 起
**默认开启**，`-Xlint:deprecation` **默认关闭**。带 `forRemoval` 的 API 会在你的构建里
逐处点名报警；只带普通 `@Deprecated` 的则只有一句不含 API 名、不含行号的笼统提示。
所以我们把「你确实被点名警告过」当作可以移除的前提。

#### 例外：在宣布的同一个发布里移除

上面两条是**一般情形**的规则——面向一个下游代码可能合理依赖的、能正常工作的 API。
它们只有一个总的例外，在这里统一说明一次，而不是每次出现时都单独论证一遍：
**当以下两条中至少一条成立，且该移除条目自己注明是哪一条、附带证据时，一个 API 可以在
它第一次带上 `@Deprecated(forRemoval = true)` 的同一个发布里被移除——完全跳过第 2 条。**

1. **该 API 在当前已发布版本上被证明不可用。**「证明」指的是**复现**——一次运行，
   其输出显示该 API 在当前已发布版本上确实失败——并原样引用在该移除条目自己的记录里。
   仅仅论证「它看起来是坏的」而没有一次实际运行支撑，不满足这一条。这个标准只要松一次，
   这条例外就不再是例外，而会变成一个未写明的第二套移除窗口，一次看似合理的使用接一次地
   悄悄废掉那个一个 MINOR 的等待期。
2. **该 API 从未在任何已打标签的发布里对外发布过，或者发布了但从未被任何调用它的地方接线。**
   没有任何已发布版本曾经真正运行过它的行为，因此也就没有什么行为需要一个废弃期去提醒任何人
   远离。

上面 [AOP](#aop) 表里的三项移除是这条规则的既有实例，不是写下规则之后新增的许可：
`aop.CglibProxyFactory` 属于第 1 条（`--add-opens java.base/java.lang=ALL-UNNAMED` 不是 Paper
服务端会设置的参数，所以这个类第一次使用就抛 `ExceptionInInitializerError`——构造器每次调用
都抛异常，这本身就是复现）；`aop.ProxyFactory.createProxy(T)`/`createProxy(Class<T>, T)` 和
`aop.AopProxyBeanPostProcessor` 属于第 2 条（两者都从未进入过已打标签的发布，或者发布了但在
`src/main` 里零调用方）。

### 与 semver 的两处明确偏离

格式是 `MAJOR.MINOR.PATCH`，容易让人套用 [semver](https://semver.org/spec/v2.0.0.html) 的预期。
以下两点我们和 semver 原文不一致，特此点名：

- **移除时机**。semver 的做法是废弃后要等到下一个 MAJOR 才移除；
  本项目在跨过一个 MINOR 之后就会在 MINOR 移除。如果你需要严格的二进制兼容保证，
  请锁定具体的 PATCH 版本。
- **不采用 semver 条款 6 的宽松解读**。该条款允许把「修正错误行为」当作可以走 PATCH 的
  bug fix。本项目不这么做：任何会改变下游运行时行为的变更，一律按下方
  [行为变更](#行为变更) 一节处理，不会因为「这是在修 bug」就绕开迁移期。

### 本节只管框架自己的版本号

上面这套规则**只适用于 `UltiTools-API` 本身**。给你自己的模块定版本号是另一套契约，
判据是「服主换上新 JAR 之后需不需要动手」，见
[模块版本规范](https://dev.ultikits.com/zh/guide/advanced/module-versioning.html)
（[English](https://dev.ultikits.com/guide/advanced/module-versioning.html)）。

两者不一致是**故意的**，不要试图统一。差别在于版本号被拿去做什么：

- 框架的版本号会被**解析和链接**——Maven 拿它挑构件，已编译的下游插件在运行时链接它的类。
  这两件事问的都是兼容性问题。
- 模块的版本号也会被机器读，但**只用来排序**：`PluginManager.hasNewerVersionLoaded`
  和 `unregisterSupersededVersions` 在同一模块存在两个 JAR 时比较版本决定谁赢，
  `UpdateManager.checkModuleUpdates` 比较版本以报告有无更新。三者都走
  `VersionComparatorUtil.compare`，问的只是「A 是不是比 B 大」，**没有一个会去看
  这个差异属于 MAJOR、MINOR 还是 PATCH**。模块也不发布到 Maven、不被任何东西链接。

所以：模块版本号的**顺序**被机器消费，**MAJOR/MINOR/PATCH 的含义**不被机器消费，
后者是说给服主听的。框架这边两者都被机器消费，所以它的版本号没有同样的自由度。

## 依赖声明

Maven：

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version><!-- 见 GitHub Releases --></version>
    <scope>provided</scope>
</dependency>
```

Gradle：

```groovy
compileOnly 'com.ultikits:UltiTools-API:<version>'
```

**务必使用 `provided` / `compileOnly`。** 发布到 Maven Central 的 POM 经
flatten-maven-plugin 处理后不含依赖声明，因此把 UltiTools-API 放进编译期以外的范围
（Maven `compile`、Gradle `implementation`）不会给你带来任何传递依赖，
却会在你的构建带有 shade / shadow 步骤时，把整个 shaded 框架打进你的模块 JAR，
在运行时与服务器上已有的 UltiTools 冲突。

## 6.3.0 的移除清单

**这份清单按代码里的 `@Deprecated(forRemoval = true)` 标注生成，不再手工维护。**
标注是唯一的真相来源；文档不会再列出代码里没有标注的类型。
标为 `@ApiStatus.Internal` 的包与类型不在此列——它们本就不属于公开 API，
移除它们不构成兼容性事件。

「移除预告发自」是该 API 首次带上 `forRemoval` 标注的发布版本，
也就是你的构建第一次点名警告它的版本。

### 命令

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `abstracts.AbstractCommandExecutor` | 6.2.1 | `abstracts.command.BaseCommandExecutor` | **15 个文件 / 6 个仓库** |
| `abstracts.AbstractCommendExecutor`（拼写错误的空 shim） | 6.2.5 | 同上 | 0 |
| `manager.CommandManager.register(CommandExecutor, …)` 的两个重载 | 6.2.5 | `register(UltiToolsPlugin, Class, String, String, String…)` | 0 |
| `manager.CommandManager.registerAll(UltiToolsPlugin, String)` | 6.2.5 | `registerAll(UltiToolsPlugin)` | 0 |
| `annotations.command.@OptionalParam` | 6.2.5 | 无替代——该注解从未实现，标注它不影响解析；改为每种可接受的参数形态各写一条 `@CmdMapping` | 0 |

### 版本适配

`interfaces.VersionWrapper` 整簇由 `utils.XVersionUtils` 取代，后者是前者的超集。

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `interfaces.VersionWrapper` 接口及其 14 个 default 方法 | 6.2.5 | `utils.XVersionUtils` | 0 |
| `interfaces.impl.DefaultVersionWrapper` | 6.2.5 | 同上 | 0 |
| `UltiTools.getVersionWrapper()` | 6.2.5 | 同上 | 0 |
| `abstracts.UltiToolsPlugin.getVersionWrapper()`（static） | 6.2.5 | 同上 | 0 |

### 数据与界面基类

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `abstracts.AbstractDataEntity` | 6.2.1 | `abstracts.data.BaseDataEntity<ID>` | 0 |
| `abstracts.guis.PagingPage` | 6.2.1 | `abstracts.gui.BasePaginationPage` | 0 |
| `abstracts.guis.OkCancelPage` | 6.2.1 | `abstracts.gui.BaseConfirmationPage` | 0 |

### 监听器与注册

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `interfaces.TempListener.player(Class)` 及 `TempListener.PlayerTempListenerBuilder` | 6.2.5 | `TempListener.common(Class)`，用 `filter(Function)` 收窄到玩家事件 | 0 |
| `interfaces.impl.PlayerTempListener` | 6.2.5 | 同上 | 0 |
| `manager.ListenerManager.register(UltiToolsPlugin, Listener)` | 6.2.5 | `register(UltiToolsPlugin, Class)`——旧重载接收已构造好的实例，因此不执行依赖注入 | 0 |

### 插件基类

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `abstracts.UltiToolsPlugin(String, String, List, List, int, String)` 六参数构造 | 6.2.5 | 七参数构造，显式传入 `resourceFolderPath`（六参数重载把它硬编码成 `<dataFolder>/pluginConfig/<插件名>`） | 0 |

引用量口径：2026-08-14 对 UltiKits 组织下 17 个模块仓库、4 个工具项目与 Libraries
共 310 个 Java 文件的实测，排除测试目录与构建产物，只统计 import 与 extends。
`6.2.1` 这个起算点是**刻意取的保守值**，不是因为 6.2.0 无从查证。6.2.0 确实发布到了
Maven Central、只是没有对应的 git 标签，但它在仓库历史里有发布提交
（`0286e26 release: UltiTools-API v6.2.0`），可以核对。把起算点定在更晚的 6.2.1 只会
延长废弃期、对下游更有利，所以保持不动。

**顺带记一条查证方法**：本项目的发布列表是 Maven Central 的 `maven-metadata.xml`，
**不是 `git tag`**——`git tag` 里没有 `v6.2.0`。用标签列表推断「某个版本从未发布」在
这个仓库会得出错误结论。

**若你的模块引用了其中任何一项，请在 6.3.0 发布前提 issue，我们会重新评估。**
上表的引用量不是移除依据，也不构成「没人在用」的结论。

### AOP

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `aop.CglibProxyFactory` | 6.3.0 | `aop.ProxyFactory` —— 构造器形状相同（都接收拦截器列表）；其 `createProxy` 方法没有直接替代，见下两行 | 0 |
| `aop.ProxyFactory.createProxy(T)` 与 `createProxy(Class<T>, T)` | 6.3.0 | `aop.ProxyFactory.createProxyClass(Class<T>, Set<Method>)`，经容器间接使用。代理创建时机提前到 bean 构造*之前*（issue #190：`BeanPostProcessor` 拿到的永远是已经造好的实例，结构上就晚了） | 0 |
| `aop.AopProxyBeanPostProcessor` | 6.3.0 | `aop.AopProxyResolver`，由容器在构造前而非构造后咨询 | 0 |
| `annotations.Propagation.NESTED` | 6.3.0 | 无直接替代——剩下的六个常量（`REQUIRED`、`REQUIRES_NEW`、`SUPPORTS`、`NOT_SUPPORTED`、`MANDATORY`、`NEVER`）恰好就是 Jakarta Transactions 2.0 的 `TxType` 取值集合 | 0 |

以上四条都在宣布移除的同一个版本里被移除，这不符合上面的常规策略，理由分两类：

- `aop.CglibProxyFactory` 在任何受支持的服务器上都无法工作：它需要
  `--add-opens java.base/java.lang=ALL-UNNAMED`，而 Paper 服务器不会设置这个参数，插件也无法
  自行添加。让它走完废弃周期，只会保留一个首次使用就抛 `ExceptionInInitializerError` 的 API。
  详见 issue #188。
- `aop.ProxyFactory.createProxy(T)`/`createProxy(Class<T>, T)` 和 `aop.AopProxyBeanPostProcessor`
  是另一类情况：两者都能正常工作，只是都从未真正发布过。`aop.ProxyFactory` 本身是在 6.2.5 之后、
  在这个尚未发布的 6.3.0 开发周期内才新增的（PR #305），它的 `createProxy` 方法从未进入过任何
  已发布版本。`AopProxyBeanPostProcessor` 则更早（`@since 6.2.0`，且确实在 6.2.1 到 6.2.5 中发布
  过），但它从未真正接入容器——`addBeanPostProcessor` 在 `src/main` 里的调用方为零——所以移除它
  不会破坏任何真正在工作的集成。二者都在本周期内被 `AopProxyResolver` 取代：它在 bean 构造之前
  就解析出代理类，而 `BeanPostProcessor` 按接口约定拿到的两个回调都只能是已构造好的实例，结构上
  做不到这一点。两者都没有任何下游引用。详见 issue #190。
- `annotations.Propagation.NESTED` 走第 2 条的理由和前三条不同：原本七个 `Propagation` 取值——
  包括 `NESTED`——**在存储层上全都能实现**，`NESTED` 完全可以映射到 `Connection.setSavepoint()`。
  它被砍掉是因为**可控性，不是不可实现**：保存点的实际行为取决于服务器所装 Paper 构建自带的
  `sqlite-jdbc` 版本（`org.xerial:sqlite-jdbc` 既不在本项目 `pom.xml` 也不在 `plugin.yml` 里声明，
  是 Paper 自带的——本机服务器缓存里就观测到过 `3.45.3.0`、`3.46.0.0`、`3.47.0.0` 三个不同版本），
  这不是本项目能钉住或能跨版本测试的东西。第 2 条的证据，如实陈述而非论证：`@Transactional` 从未
  在任何已发布版本上真正执行过——已发布的 6.2.5 jar 里 `AopProxyBeanPostProcessor` 只被自己引用，
  `PluginManager` 对 `AopAdvisor`、`addAdvisor`、`TransactionInterceptor` 的引用为零——所以无论上面
  这条理由是否成立，都没有任何已发布的服务器真正求值过 `NESTED`。对 UltiKits 组织内 17 个模块/
  插件仓库的实测：`TransactionManager` / `@Transactional` / `.transaction(` 命中 **零次**，对照组
  `getDataOperator` 命中 **94** 次（证明这次调查方法本身是有效的）。如果保存点行为将来能在受支持的
  Paper 构建之间稳定钉住，`NESTED` 可以考虑恢复。

## `AbstractCommandExecutor` 的迁移

`abstracts.AbstractCommandExecutor` 是清单里唯一有真实下游用户的类型（实测 15 个文件）。
它将在 6.3.0 移除，**下游迁移会与移除在同一个版本周期内协调完成**。

迁移到 `abstracts.command.BaseCommandExecutor`：

1. 改继承：`extends AbstractCommandExecutor` → `extends BaseCommandExecutor`。
2. 实现新增的抽象方法 `protected void handleHelp(CommandSender sender)`。
3. `@CmdMapping` / `@CmdParam` / `@CmdTarget` / `@CmdCD` / `@UsageLimit` 语义不变。
4. 通过 `@CmdExecutor` 包扫描注册的命令需要改为显式注册——
   扫描路径会把新基类强转为旧基类。走 IoC 路由的主加载路径不受影响。

拼写错误的空壳 `AbstractCommendExecutor` 继承自 `AbstractCommandExecutor`，
因此它**必须与父类在同一个版本移除**，不存在单独保留的选项。

新基类目前有一个已知缺口：

| 缺口 | 排期 |
|---|---|
| 参数级 tab 补全尚未接线 | **6.3.0**（排在维护者对下游仓库的迁移之前） |

（`@CmdMapping(format = "")` 的裸命令不可执行，此前排期 6.2.5，**已在 6.2.5 修复**。）

迁移时机取决于你是谁：

- **UltiKits 组织内的模块**由维护者在 6.3.0 周期内统一迁移，你不需要自己动手。
- **组织外的第三方模块**应当在 **6.2.5 期间**就完成迁移。这样做要接受一个代价：
  参数级 tab 补全要到 6.3.0 才接线，在此之前迁移过去的命令只在第一个参数位补全字面量。
  但这是唯一能给你留出真实过渡期的做法——补齐 tab 补全的版本（6.3.0）
  同时也是移除旧基类的版本，等到那时再迁移就没有缓冲了。

## 行为变更

有一类变更方法签名一动不动，却会改变你的模块在运行时的表现：
某个方法从静默返回改成抛异常、某个默认值翻转、缺少可选依赖时从降级运行改成加载失败。
签名比对工具查不出这类变更，上面的移除清单也覆盖不到它们。本节说明我们怎么处理。

### 三类兼容性

沿用 [OpenJDK CSR](https://wiki.openjdk.org/display/csr/Kinds+of+Compatibility) 与
[dotnet/runtime](https://github.com/dotnet/runtime/blob/main/docs/coding-guidelines/breaking-change-definitions.md)
的划分：

- **源码兼容（source）**——你的代码还能不能通过编译。移除类型、改方法的参数表、
  给接口加抽象方法都会破坏它。注意**改返回类型往往不破坏它**：调用方通常没写出返回
  类型的名字，重新编译一次就过去了。
- **二进制兼容（binary）**——你**已经编译好**的 JAR 还能不能在新框架上加载和运行。
  破坏它的典型表现是 `NoSuchMethodError` / `NoClassDefFoundError`。
  上一条里那种「重新编译就过去了」的改动，对不重新编译的 JAR 就是致命的。
- **行为兼容（behavioral）**——编译过了、也加载起来了，但**做的事情变了**。

移除清单和版本号规则管的是前两类里**有意为之**的那部分；无意打破二进制兼容的情况见
[移除清单覆盖不到的二进制不兼容](#移除清单覆盖不到的二进制不兼容)。本节管第三类。

### 哪些行为变更可以在 MINOR 直接做

不需要迁移期：

- 修正明确违反文档的行为（文档说返回 `Optional.empty()`，实际抛了 NPE）。
- 收紧此前未定义的输入的处理方式（此前传 `null` 是未定义行为，现在明确抛 `IllegalArgumentException`）。
- 性能、内存占用、日志文案、异常消息文本的变化。
- 修复安全问题。这一类可能在 PATCH 就发生，恕不预告。

### 哪些需要迁移期

需要迁移期：

- 有文档记载的默认值翻转。
- 从静默降级改成失败（例如可选依赖缺失时，此前跳过、之后拒绝加载）。
- 返回值语义变化（此前返回空集合、之后返回 `null`，反之亦然）。
- 副作用的时机或线程变化（此前同步、之后异步）。

迁移期走两步：

- **版本 N**：保持旧行为，但在触发该路径时打**一次性** WARNING。
  警告文本必须写明具体的目标版本号和反馈 issue 链接，例如：

  ```
  [UltiTools] 模块 <name> 依赖了 X 的旧行为（<旧行为的一句话描述>）。
  该行为将在 6.4.0 改为 <新行为>。迁移方式见 <issue 链接>。
  本警告每次启动只打一次。
  ```

- **版本 N+1**：真正切换到新行为，并移除该警告。

这一节的写法参考了 [PEP 387](https://peps.python.org/pep-0387/)，
核心是同一条：**先让人知道自己踩在了哪块地板上，再抽走它。**

### 已记录的实例：AOP 代理类命名（6.3.0）

6.3.0 把 AOP 引擎从 CGLIB 换成了 ByteBuddy（见上文 [AOP](#aop)）。被代理 Bean 的公开方法签名
没有变化，但 ByteBuddy 生成的代理类名和 CGLIB 的不一样：`Foo$$EnhancerByCGLIB$$xxxx` 变成了
`Foo$ByteBuddy$xxxx`。生成的代理类名具体长什么样从来没有写进过文档承诺的契约，所以按上面的
分类属于「不需要迁移期」——但值得单独记一笔，因为它对任何用类名模式匹配来识别代理的下游代码
都是真实的破坏，框架自己的 `TaskManager` 以前就是这么做的。这样的代码必须改用受支持的判断
方式：`com.ultikits.ultitools.aop.ProxyFactory.isProxyClass(Class<?>)`。

### 已记录的实例：命令执行器父类上的 `@CmdMapping` 方法现在会被注册（6.3.0）

6.3.0 之前，`BaseCommandExecutor.scanCommandMappings()` 和已废弃的
`AbstractCommandExecutor.scanCommandMappings()` 只扫描 `this.getClass().getDeclaredMethods()`，
所以声明在命令执行器**父类**上的 `@CmdMapping` 方法会被静默漏掉——那个子命令根本不存在。
6.3.0 作为 AOP 代理工作的一部分修复了这个问题（issue #190：继承式代理只覆盖被拦截的方法，
任何直接扫 `getDeclaredMethods()` 的消费方都会丢掉类上其余方法的注解）。修法是遍历完整继承链，
所以父类上的 `@CmdMapping` 方法现在会和其他方法一样被注册。

方法签名没有变化，`japicmp` 抓不到这个变更。之所以记在这里，是因为它对某个依赖（有意或无意）
基类 `@CmdMapping` 方法保持未注册状态的下游模块是真实的行为破坏——例如多个模块共用的抽象命令
基类上声明了一个本意是「子类按需启用」的子命令。这样的模块升级到 6.3.0 前必须移除或改名该方法，
否则会开始响应此前从未响应过的子命令。本框架自身的命令类（`PluginInstallCommands`、
`UltiToolsCommands`、`CloudLoginCommand`）已核实不受影响：它们的父类都没有声明自己的
`@CmdMapping`。

按上面的分类，这属于「不需要迁移期」：此前「声明了 `@CmdMapping` 却被静默漏注册」从来不是
文档承诺的契约，没有默认值或时机需要逐步淘汰。记录它的理由与上面的 AOP 代理类命名变更一致：
对依赖了这个缺口的代码是真实破坏，尽管这个缺口本身从未被保证过。

### 已记录的实例：`@CmdTarget` 类级/方法级组合语义改为「覆盖并对放宽拒绝」（6.3.0）

6.3.0 之前，两代命令执行器对「类级 `@CmdTarget` 加上方法级 `@CmdTarget` 到底意味着什么」
互相不一致。已废弃的 `abstracts.AbstractCommandExecutor` 路径走的是**交集**：类级检查和
方法级检查必须**各自独立**通过。当前的 `abstracts.command.BaseCommandExecutor` 路径，
通过 `SenderTypeValidator.determineTargetType`，走的已经是**覆盖**：只要方法自己带了
`@CmdTarget`，类级的值就完全被忽略，也完全没有放宽检查。6.3.0 让两代共用同一条规则
`abstracts.command.validation.CmdTargetComposition`：方法级的值在**收窄**类级值时**覆盖**它；
任何不是收窄的组合——类级限制被方法级 `BOTH` **放宽**，或者在 `PLAYER` 与 `CONSOLE` 之间
**横向切换**——都会被拒绝，而不是被悄悄解析成某一边。

**用真实命令类构造的前后对比。** UltiKits 自己的 UltiMenu 模块里，
`com.ultikits.plugins.menu.commands.MenuCommands` 就在已废弃的 `AbstractCommandExecutor`
一代上，类级 `@CmdTarget(BOTH)`，其中 `open <name>` 这条映射用方法级 `@CmdTarget(PLAYER)`
收窄：

- **在 6.2.5 上**（交集）：控制台执行 `/menu open <name>` 会同时对 `BOTH`（类级，通过）
  和 `PLAYER`（方法级，不通过）做检查——AND 失败，命令被**拒绝**。
- **6.3.0 之后**（覆盖并收窄）：方法级的 `PLAYER` 收窄了类级的 `BOTH`，有效限制变成
  `PLAYER`——控制台仍被**拒绝**，结果相同。

对任何只做收窄的映射——`MenuCommands` 全部映射都是如此——交集和「覆盖并收窄」永远一致：
一个集合和自己的子集取交集，结果就是那个子集，这恰好也是「覆盖并收窄」解析出的结果。
这正是两代实现在*一般规则*上能不一致地共存这么多年、而每一个只做收窄的下游命令类
却从未察觉差异的原因。

**镜像案例。** `UltiSideBar` 的 `com.ultikits.plugins.sidebar.commands.SideBarCommand`
带着完全相同的注解形状——类级 `@CmdTarget(BOTH)`，`toggle`、`on`、`off` 各自用
`@CmdTarget(PLAYER)` 收窄——但它在**当前**的 `BaseCommandExecutor` 一代上，这一代在
本次变更之前就已经是覆盖语义。它解析出的发送者限制和 `MenuCommands` 完全一样。
所以一个类从 `AbstractCommandExecutor` 迁到 `BaseCommandExecutor`、不改动其
`@CmdTarget` 注解，只要它只做收窄——这是常见情形——迁移前后解析出的发送者限制不变。

**真正会变的地方。** 有两种形状会彻底不再注册：类级限制被方法级 `BOTH` **放宽**
（例如类级 `PLAYER`、方法级 `BOTH`），以及类级限制被方法级的相反值**横向切换**
（类级 `PLAYER`、方法级 `CONSOLE`——`CmdTargetType` 是三值枚举，不是全序，所以这既不是
收窄也不是放宽）。6.3.0 之前这两种是歧义的，且结果因代而异：交集会按具体取值解析成
非空或空集合，覆盖则直接取方法值、完全不做放宽检查。从 6.3.0 起，`ComponentScanner`
会在插件加载时**只拒绝那一个命令类**——错误信息点名该类和出问题的方法，模块的其余部分
照常加载。如果你的命令类里有某个映射的方法级 `@CmdTarget` 放宽或横向切换了类级值，
把注解改成方法收窄或匹配类级值即可。

### 已记录的实例：六参数连接器构造器自 6.2.0 起在每一个发布上都失败

这本身**不是**一个 6.3.0 变更——之所以记在这里，是因为它是该构造器
[同一发布内移除例外](#一个公开-api-何时可以被移除)的证据基础，也是因为下游连接器作者
应当得到和维护者一样的证据，而不是一句「它不能用」的断言。

`abstracts.UltiToolsPlugin` 的六参数构造器（`(String, String, List, List, int, String)`）
是通过 `manager.PluginManager.register(...)` 里 `initializePlugin` 的带参分支到达的。
6.2.0 之前，`initializePlugin` 用的是 Spring 的
`AnnotationConfigApplicationContext.registerBean(pluginClass, constructorArgs)`，
它的构造器解析器做兼容类型匹配——包括拆箱和拓宽。发布提交 `0286e26`（v6.2.0）把它换成了
手写匹配，至今原样保留：

```java
paramTypes[i] = constructorArgs[i].getClass();
// ...
Constructor<? extends UltiToolsPlugin> constructor =
    pluginClass.getDeclaredConstructor(paramTypes);
```

`getDeclaredConstructor` 要求**精确**的参数类型匹配。实测而非推断：复现六参数调用形状得到
`paramTypes = [String, String, ArrayList, ArrayList, Integer, String]`，对上声明为
`(String, String, List, List, int, String)` 的构造器。六个参数位里有三个永远匹配不上：
`ArrayList` 从不会按可赋值性去匹配声明的 `List` 参数——声明类型必须字面相同，不能只是
可赋值——出现两次；自动装箱的 `Integer` 永远不等于声明的基本类型 `int`，出现一次。
`getDeclaredConstructor` 对这个调用形状因此必定抛出 `NoSuchMethodException`。

这个失败在向外传播的路上被吞掉：反射异常被包进 `IllegalStateException`
（`PluginManager.java:738`），被 `register(...)` 自己的 `catch (Exception | Error e)`
（`:178`）捕获，按 `WARNING` 级别记日志，然后变成一个 `false` 返回值。调用方看不到任何异常，
只看到一个布尔值。

**所以这个重载自 6.2.0 起，在每一个发布上都是 100% 失败的。** 不存在任何一种参数形状能让
六参数调用满足 `getDeclaredConstructor` 的精确匹配要求。命中这个问题的连接器作者应改用
`api.UltiToolsAPI.connect(JavaPlugin)`，它完全不走反射构造器解析这条路。

### 已记录的实例：`del()` 不带任何条件时现在会被拒绝（6.3.0）

6.3.0 之前，`AbstractRelationalDataOperator.del()` 拿到什么 `WhereCondition` 就用什么拼
`DELETE FROM <table>`，从不检查条件是否存在。不带任何参数直接调用 `del()`——一次裸调用，零参
数——会生成一条没有 WHERE 子句的 `DELETE FROM <table>`，一条语句清空整张表。显式传入
`del((WhereCondition[]) null)` 会走到同一条代码路径。

6.3.0 把这两种形状都直接拒绝：`del()` 的零条件保护是方法的字面第一条语句，早于构建 SQL 用的
`StringBuilder`，也早于碰操作器自己的 `QueryRunner`——用 Mockito spy 验证过（抛出之后
`QueryRunner` 上零次交互记录），而不只是「没抛异常」这种弱证据。带至少一个真实条件的
`del(condition)` 调用不受影响。

**这走的是安全修复通道**（见上方「哪些行为变更可以在 MINOR 直接做」），而不是「从静默降级改成
失败」那一类通常要求的两步迁移期。给出针对这个具体案例的理由，而不是笼统的「这是在修 bug」：
一个不带条件的 `del()` 是任何持有 `DataOperator` 的代码都能触发的**无界数据丢失原语**——少传
一个参数，或者一个运行时恰好为空集合构建出来的 `WhereCondition[]`，整张表就没了，而且大多数
调用路径上没有事务边界能撤销它。这和「最坏情况是查询结果错了」这类 bug 有质的不同：这里的最坏
情况是一个很容易写错就触发的、不可恢复的数据丢失。让它在警告下再多活一个 MINOR 并不比现在直接
拒绝更安全——警告要防的失败模式是「我看到警告了，但照样继续发布会把表清空的代码」，这不是一条
更响的日志能有效改善的。

一次控制分组调查（按本项目自己的搜索陷阱规则，同时带负向查询和对照查询）没有找到任何依赖旧行为
的现网调用方：`src/main`——零参数 `.del()`/null 数组调用 0 命中，对照组（一个无关的
`FileUtils.del(file)`）1 命中；`src/test`——0 命中，对照组（带真实条件的 `.del(` 调用）11 命中；
17 个内部 `Modules/*` 仓库——0 命中，对照组 `.insert(` 命中 42 次（证明 grep 机制本身有效）；
6.2.0 之前的遗留 `Plugins/` 目录——两个查询都是 0 命中。

### 已记录的实例：`@Transactional` 从拒绝加载模块变为真正接线（6.3.0）

**在本文件早期 `6.3.0-SNAPSHOT` 版本所记录的那次拒绝期间，框架的注解体系认得 `@Transactional`，
但它没有真正接到任何东西上。** 在本里程碑之前，AOP 完全没有接入容器，所以带 `@Transactional`
的 bean 照常加载、完全不受事务保护地运行，也没有任何警告提示这个注解其实什么都没做。与其在
6.3.0 开发过程中 AOP 本身已经在别处开始工作之后，继续放任这种静默空转，`PluginManager.wireAop`
把 `@Transactional` 显式声明为**不可用**：`AopProxyResolver.rejectUnavailableAnnotations` 拒绝
加载任何带有该注解的 bean——包括仅仅继承或扩展来的方法/类，不只是你自己写的那些——打一条
WARNING，然后让 `register(...)` 返回 `false`。**这条拒绝现在被撤回了。**

**6.3.0 在全部三个存储后端上把 `@Transactional` 端到端接了线：**

- **SQLite 与 MySQL**，通过 `JdbcTransactionManager`——每个插件容器一个管理器实例，
  AOP 拦截器与该 store 分发出去的每一个 `DataOperator` 共用同一个实例
  （`SQLiteDataStore.transactionManagerFor(DataScope)` / `MysqlDataStore.transactionManagerFor(DataScope)`，
  各自由一个按身份缓存的 map 支撑），所以一个调用了 `insertAll`/`updateAll` 的 `@Transactional`
  方法只会开出恰好一个事务，不是两个。SQLite 按插件自己的 `.db` 文件路径分 key（一个文件一个
  管理器，与本版本其他地方「一个文件一个连接池」的修法呼应）；MySQL 按请求方身份分 key，因为
  该后端所有插件共用同一个全局 `DataSource`。
- **JSON**，通过 `JsonTransactionManager`——一个基于快照的管理器：事务内第一次触碰某个操作器时
  深拷贝其缓存；回滚时从该快照整体恢复。**这不保证什么：** 恢复替换的是操作器**整个**缓存，
  不是逐个实体的撤销。因此 `REQUIRES_NEW`/`NOT_SUPPORTED` 相对外层作用域的独立性，只有在内外
  层触碰的是**不同的** `SimpleJsonDataOperator` 实例时才能被观察到——如果两者写的是**同一个**
  操作器，外层作用域最终的回滚会把该操作器的缓存恢复到外层写入之前的快照，把内层作用域已经
  提交的写入也一并丢弃。这是整体缓存粒度回滚的固有属性，不是 suspend/resume 机制本身的缺陷。

`REQUIRES_NEW` 与 `NOT_SUPPORTED` 现在在每个后端上都真正挂起当前活动事务（JDBC 侧靠一个
sibling 的 `ThreadLocal<Deque<TransactionContext>>`；JSON 侧在 `JsonTransactionManager` 上有
同等构造），而不是静默嵌套进去。`Propagation.NESTED` 已被移除——见上文 [AOP](#aop) 一节的条目。

**前后对比的实例。** `rollbackFor` 现在是对未受检 `RuntimeException`/`Error` 默认规则的
**叠加**，不是替换——这正是修复前 javadoc 已经写着的话（"指定**额外**的异常类型"），只是代码
没有照做。当 `rollbackFor` 与 `noRollbackFor` 同时匹配抛出的异常时，胜出的是**继承深度更浅**
的那条规则所列的类（Spring `RuleBasedTransactionAttribute` 的 tiebreak）；深度恰好相同——包括
同一个类同时出现在两个数组里——时回滚胜出。

```java
class OrderException extends RuntimeException { }
class ValidationException extends OrderException { }

@Transactional(rollbackFor = ValidationException.class, noRollbackFor = OrderException.class)
public void processOrder(Order order) throws ValidationException {
    if (!order.hasShippingAddress()) {
        throw new ValidationException("missing shipping address");
    }
}
```

`ValidationException` 对 `rollbackFor` 是精确（深度 0）匹配，对 `noRollbackFor` 是深度 1 匹配
（`ValidationException` → `OrderException`，往上一层）。

- **在 6.2.5 上**（`noRollbackFor` 无条件优先检查）：`noRollbackFor` 匹配，方法**提交**——
  抛出 `ValidationException` 的那次写入被静默保留。
- **6.3.0 之后**（更浅深度胜出）：`rollbackFor` 的深度 0 匹配胜过 `noRollbackFor` 的深度 1
  匹配，方法**回滚**。

这个修法真正要解决的更朴素的情形——异常两个数组都不匹配：

```java
@Transactional(rollbackFor = BusinessException.class)
public void chargeCard(Payment payment) throws BusinessException {
    // ...
    throw new NullPointerException(); // 与 BusinessException 无关
}
```

- **在 6.2.5 上**：非空的 `rollbackFor` 会把默认规则整个替换掉，所以没列在其中的异常会
  **提交**而不是回滚——这个注解就是会在一个无关的 `NullPointerException` 上提交。
- **6.3.0 之后**：两个数组都不匹配，`shouldRollback` 落到未受检的 `RuntimeException`/`Error`
  默认规则，方法**回滚**——和完全没写 `rollbackFor` 属性时一样。

两个方向都在一个真实的 ByteBuddy 代理、真实 JDBC 连接上，通过外部调用和自调用两种路径证明过，
JDBC 与 JSON 两个后端皆然。

**如果你曾经因为 6.3.0 之前的那次拒绝而移除了 `@Transactional`，或改用了
`DataOperator.transaction(Callable)`**，现在可以把注解加回来——但如果你的方法同时用了
`rollbackFor` 和 `noRollbackFor`，先重读上面的对比实例：回滚方向可能已经在你没注意的情况下
变了。

`TransactionManager.getConnection()`/`setIsolationLevel(int)`/`setReadOnly(boolean)` 现在是
`@Deprecated` 的 `default` 方法而不是抽象方法，见下文「实体归属现在会被强制检查，以及 6.3.0
其他持久化层面的废弃」一条。

### 已记录的实例：`@ExceptionCatch` 现在可能让模块无法加载（6.3.0）

6.3.0 之前 `@ExceptionCatch` 什么都不做。AOP 从未接入容器，这个注解只是被认得、被忽略，不影响
模块能否加载。6.3.0 把它接上了，于是框架必须为带它的 bean 生成代理——而有一种形状根本无法生成。

**只要有任何东西要求拦截，一个 `final` 的 bean 类就会让加载失败**，包括它仅仅是继承来的
`@ExceptionCatch` 方法：

```java
public class GuardedBase {
    @ExceptionCatch(silent = true)
    public String guarded() { ... }
}

public final class MyService extends GuardedBase { }   // 6.2.5：能加载。6.3.0：拒绝。
```

去掉 `final` 关键字，改标 `@Final` 注解——它保留「不可继承」的约定，同时允许 AOP。Lombok 的
`@Value` 与 `@UtilityClass` 也会生成 `final`，如果是它们造成的，请改用 `@Data`。

除此之外，`@ExceptionCatch` 不会再有别的方式阻止模块加载。标在继承式代理够不着的方法上的注解
——`private`、`static`、`final`、声明在别的包里的 package-private、或被泛型覆写生成的桥接方法
遮蔽的——会被忽略，并打一条点名到该方法的启动期警告，这与 Spring 对「织不进去的方法」的处理
一致。注解在那里不起作用，但模块照常加载。

方法签名没有变化，`japicmp` 抓不到上述任何一条。

### 已记录的实例：实体归属现在会被强制检查，以及 6.3.0 其他持久化层面的废弃（SILENT-04）

**实体归属。** 6.3.0 之前，`getDataOperator(Class)` 从不问你在请求**谁的**实体——第三方模块
写 `getDataOperator(SomeoneElsesEntity.class)` 会拿到另一个模块真实的 `DataOperator`，能读写
它的数据行。单独修好本里程碑的缓存分 key 隔离缺陷（见本里程碑其他地方的「一个文件一个连接池 /
一个作用域一个操作器」修法）反而会让这个问题**更糟而不是更好**：缓存改按请求方身份分 key 之后，
同样的调用会返回一个针对调用者自己存储的**全新、空**操作器，其上的每一次查询都会静默返回空——
「不该工作但确实工作」变成了「看起来在工作，但永远是空的」，这正是本里程碑要消灭的那一类缺陷。

6.3.0 改用真正的检查来堵住它。`DataStore.getOperator(DataScope, Class)`——新的、推荐的入口——
对超出调用者自身作用域的实体直接以 `DataAccessException`/`ErrorCode.ENTITY_NOT_OWNED`（3010）
拒绝，点名该实体、（在已知的情况下）它所属的模块，并指向该模块自己暴露的服务或 `EventBus`。
`UltiToolsPlugin.getDataOperator(Class)` 与 `UltiToolsAPI.getDataOperator(JavaPlugin, Class)`
在委托给旧重载之前会执行同样的检查；本阶段中途披露过的一个缺口也已补上——`SQLiteDataStore`、
`MysqlDataStore`、`JsonStore` 现在也会在各自 `getOperator(UltiToolsPlugin, Class)`/
`getOperator(File, Class)` 重载的**第一条语句**里执行同样的检查，所以直接通过
`UltiTools.getInstance().getDataStore()`（在已发布的 jar 里是 `public` 的）访问持久层
也不再能绕过它。还留有一个未解决的边界，`DataStore.java` 自己的 javadoc 里明说了，没有藏着：
没有任何机制能阻止一个**第四方**、假设性的 `DataStore` 实现在自己覆写仍然是抽象方法的
`getOperator(UltiToolsPlugin, Class)` 时跳过这个检查——框架自己那两个入口点的检查
（`UltiToolsPlugin.getDataOperator`/`UltiToolsAPI.getDataOperator`）是这种情况下唯一的兜底。

这**不需要迁移期**，理由和上面 AOP 代理类命名、父类 `@CmdMapping` 注册这两条一样：跨模块访问
实体从来都不是文档承诺的契约。没有任何 javadoc、指南页面或注解曾经说过 `getDataOperator` 可以
拿到别的模块的实体；此前那种放任行为只是共享缓存 key 造成的意外，不是承诺。实测而非假设：
17 个内部模块仓库里的 21 个 `@Table` 类，**零**命名冲突——按维护者自己的框定，现实中的风险从来
不是意外撞名，而是第三方开发者明知某个实体不属于自己，仍然故意去请求它。

**`"unknown"` 插件名回退现在会在加载期直接拒绝，而不是静默共享。** 6.3.0 之前，一个
`plugin.yml` 没有 `name:` 键的模块 JAR 会解析成字面字符串 `"unknown"`
（`pluginConfig.getString("name", "unknown")`），和这台服务器上曾经部署过的每一个同样没有
名字的模块共享同一个 `sqliteDB/unknown.db`。6.3.0 直接拒绝加载这样的模块——`PluginModuleException`
点名该 JAR，在 `UltiToolsPlugin` 无参构造器的第一条语句就抛出，早于任何资源释放或配置初始化。
和上面的实体归属检查一样，这**不需要迁移期**：`"unknown"` 回退同样从来不是文档承诺的契约——
它是一个带有意外跨模块后果的内部默认值，不是一项功能。对 17 个内部模块的 `plugin.yml` 做的
控制分组调查一个都没找到缺失 `name:` 键的（17/17 命中确认 grep 本身有效），所以这一改动不会
拒绝任何现网模块加载。

*已有 `sqliteDB/unknown.db` 数据的去向。* 本版本不会迁移它。在一台真实部署机器上实测：96 KB，
10 张表分属 8 个不同模块，全部零行，只有 `world_settings` 例外（3 行——同样的 3 行也存在于该
模块自己正确命名的 `UltiWorlds.db` 里，所以这次测量没有在这里找到独占持有的数据）。如果你服务器
上某个没有名字的模块的数据**只**存在于 `unknown.db`，修好它的 `plugin.yml` 并重载会让它拿到一个
全新的、正确归属的 `.db` 文件——不会自动找回旧共享文件里的行。升级前如果拿不准哪些模块受影响，
请自行检查 `sqliteDB/unknown.db`。

### 已记录的实例：无法解析的必需 `@Autowired` 依赖现在会导致模块加载失败（SILENT-05，6.3.0）

6.3.0 之前，对于一个无法解析的 `@Autowired(required = true)` 字段或构造器参数，
`SimpleContainer.autowireBean` 与构造器注入路径只会记一条 `WARNING` 并注入 `null`，
而不是 `required = true` 这个默认值本该承诺的失败。6.3.0 改为抛出 `ContainerException`，
点名声明该依赖的类、字段（或构造器参数位置）以及无法解析的依赖类型，模块直接加载失败，
而不是带着一个静默为 `null` 的协作对象继续跑下去。`required = false` 不受影响——字段或参数
仍然是 `null`，也不会有任何警告。

这属于「从静默降级变为失败」，通常需要一个迁移期——但这个迁移期已经跑完了。issue #182
在这条路径上加的一次性 `WARNING` 已经在 `v6.2.5` 里发布，给了下游模块作者整整一个发布周期
的提前通知，6.3.0 才把它变成加载期失败。这条警告正是 D-08 依赖的前提条件，让 6.3.0 可以
被当作两步流程里的「N+1」步，而不必重新走一遍两步流程。

构造器注入携带完全相同的修法：一个无法解析的必需构造器依赖现在抛出的是 `ContainerException`，
而不是裸的 `RuntimeException`。

### 已记录的实例：同类型解析现在按 `@Service(priority)` 裁决，并对精确并列直接拒绝（SILENT-06，6.3.0）

6.3.0 之前，`SimpleContainer.getBean(Class)` 在解析一个有多个匹配候选者的类型时，会返回
内部 map 迭代先碰到的那一个——这是一个未指定、依赖具体实现的选择，尽管本仓库自己的
`@Service` javadoc 从未这样文档化过，即便它早就带了一个 `priority` 属性。6.3.0 让 `priority`
真正起作用：候选者按 `@Service(priority)` 排序，**数值更高者胜出**——这是框架自己选定的方向，
之所以这样选，是因为这正是本代码库 6.2.5 版 javadoc 早已承诺的方向，刻意不采用 Spring 的
`@Priority`（那里是数值更低者胜出）。排名前两位候选者精确并列时（包括两者都留在默认优先级
`0` 这种常见情况），现在会在第一次触发该歧义的 `getBean(Class)` 调用上抛出
`ContainerException`，点名两个候选类，并指向 `@Service(priority = ...)` 作为解决办法。

**范围。** 候选者只从发起解析的那个容器里收集；父容器只在本地完全没有候选者命中时才会被
查询。一个模块如果在自己的子容器里注册了自己的实现，用来覆盖框架提供的默认服务，这次
改动不会影响它——这个覆盖压根不会和框架的默认实现放在同一个候选池里比较。

这是「从静默降级变为失败」：6.3.0 之前，两个优先级相同的实现会静默解析成某个未指定的一个，
并且这个选择会在容器的整个生命周期内被缓存；6.3.0 之后，同一个模块在用 `priority` 消除歧义
之前会直接加载失败。

### 已记录的实例：`registerSingleton` 现在会完整装配它的参数，并可能拒绝携带 AOP 注解的实例（SILENT-09、SILENT-10，6.3.0）

6.3.0 之前，`SimpleContainer.registerSingleton` 会原样存下传给它的任何对象：不会执行
`@Autowired` 注入，不会调用 `@PostConstruct`，也不会跑 `BeanPostProcessor` 链。6.3.0 把它的
契约从「注册」拓宽为「注册并完整装配」——现在，传给 `registerSingleton` 的每个对象都会经过
和容器自己构造的 bean 完全相同的
`postProcessBeforeInitialization → autowireBean → @PostConstruct → postProcessAfterInitialization`
流程。这会波及通过这条路径注册的配置实体、`@ContextEntry` bean、`@Configuration` 实例，
以及 `@Bean` 产物——此前它们都没有被装配过。

作为同一次拓宽的一部分，`registerSingleton` 现在会**拒绝**一个类上带有方法级或类级
`@Transactional`/`@ExceptionCatch` 的实例——抛出 `ContainerException`，
`ErrorCode.UNPROXYABLE_SINGLETON`（2007）——因为以这种方式注册的对象从未走过 AOP
代理生成，否则这些注解只会静默地什么都不做。一个已经生成好的代理实例不受此限制。

内部实测影响：在 `Modules/`、`Plugins/` 以及框架自己的 `src/main` 里，**0** 个通过
`registerSingleton` 注册的对象带有 `@Transactional`/`@ExceptionCatch`（对照组：`@Service`
在下游 39 个文件里出现，确认了这个搜索机制本身能找到真实命中）。一个模块如果要在 6.3.0+
第一次注册这样一个对象，需要改成声明它为 `@Service`/`@Component`，这样容器才会构造它——
并且能够代理它。

**通过 `@Bean`/`@Configuration` 触发时的影响范围。** `registerConfiguration` 与
`processBeanMethod`（分别为 `@Configuration` 实例和 `@Bean` 产物调用 `registerSingleton`
的路径）此前会捕获任何异常，以 `SEVERE` 级别记录日志，然后继续扫描模块的其余部分。
从 6.3.0 起（见下面 `@Bean` 命名那条实例），一个 `ContainerException`——无论是来自这个
拒绝，还是来自一个格式错误的 `@Bean` 声明——不再在那里被捕获：它会逃出 `processClass`，
中止整个模块的 `scanPackage` 调用。此前局限在单个 bean 上的失败，现在会波及整个模块。

这两处改动都属于「从静默降级变为失败」：6.3.0 之前，一个未装配的单例或一个带 AOP 注解的
单例会被接受，然后静默地不起作用；6.3.0 之后，模块在问题修好之前都会加载失败。

### 已记录的实例：`@Bean(name=)`/`@Bean(value=)` 现在决定注册的 bean 名称（WIRE-09，6.3.0）

6.3.0 之前，`@Bean` 的 `name` 和 `value` 属性只是声明了，从来没人读过——不管
`name()`/`value()` 写了什么，每个 `@Bean` 方法都用自己的方法名注册。6.3.0 让这两个属性
真正起作用：只要声明了任意一个，其数组的第一个元素就成为注册的 bean 名称，其余元素则
注册为解析到同一实例的别名；两者都没设置时，仍然用方法名，行为不变。一个模块如果对某个
同时声明了非默认 `name()`/`value()` 的 `@Bean` 方法写 `getBean("<方法名>")`，升级后需要改成
`getBean("<声明的名称>")`——一旦声明了自定义名称，方法名这个 key 就不再能解析了。

一个格式错误的声明——`name()` 与 `value()` 内容冲突且都非空，或声明的名称元素中有任何一个
是空白——现在会以 `ContainerException` 使模块加载失败，而此前会静默地用方法自己的名称注册
（两个属性此前都没人读）。这个失败带来的波及范围见上一条实例。

内部实测影响：在 `Modules/`、`Plugins/` 以及框架自己的 `src/main` 里，**0** 处出现
`@Bean(name=`/`@Bean(value=`——现存的每一个 `@Bean` 方法都依赖方法名默认值，本版本不改变
这一点，所以这是一条前瞻性的兼容性说明，而不是一个已观测到的破坏。

### 已记录的实例：`scanBasePackageClasses` 现在真正生效，包扫描来源解析变为累加而非首个命中（GEN-06，6.3.0）

6.3.0 之前，`@UltiToolsModule`/`@ComponentScan` 上的 `scanBasePackageClasses()` 只是声明了，
在 `PluginManager.getPluginScanPackages` 和 `SimpleContainer.processConfigurationClass` 这
两个读取点都没人读它。6.3.0 在这两处都读取它，纯粹是增量式的：任何已经声明了这个属性的模块
（实测 0 例）都不会有任何变化，因为一个此前静默失效的声明，开始生效并不会造成回归。

同一次改动也让包来源的解析从「首个命中」变为「累加」：一个同时声明了 `scanBasePackages`、
`scanBasePackageClasses`，或直接声明 `@ComponentScan.basePackages` 中一个以上的模块，此前
第一个之后的来源都会被静默忽略；现在全部都会生效。内部实测影响：**0** 个模块今天同时声明
了一个以上的来源，所以现存模块被扫描的包集合大小都不会改变。属于「修正与文档明显矛盾的
行为」——不需要迁移期。

### 已记录的实例：`@UltiToolsModule` 上 `eventListener`/`cmdExecutor`/`config` 的 `@AliasFor` 开关现在真正生效（WIRE-08，6.3.0）

6.3.0 之前，`@UltiToolsModule` 的 `eventListener`、`cmdExecutor`、`config` 属性都被声明为
指向 `@EnableAutoRegister` 对应属性的 `@AliasFor`，但 `registerBukkit` 是通过直接反射来解析
`@EnableAutoRegister` 的，从不跟随别名——所以在 `@UltiToolsModule` 上把这三个属性中任意一个
设为 `false` 完全没有效果；自动注册照常进行。6.3.0 让 `registerBukkit` 改用本阶段其余工作
统一使用的合并注解查找来解析 `@EnableAutoRegister`，这个查找会遵循 `@AliasFor`，于是这三个
开关现在终于做到了它们自己那个注解从被加上那天起就声明要做的事。

这条被记在「修正与文档明显矛盾的行为」下——不需要迁移期——因为此前的行为不只是一个未文档化
的缺口，而是直接和同一个注解上 `@AliasFor` 自己声明的契约相矛盾。内部实测影响：今天**没有**
任何下游模块把这三个开关中的任何一个设为非默认值，所以现存模块注册的命令/监听器/配置集合
不会因为这个修复而改变。

### 已记录的实例：`@ConditionalOnConfig` 现在在监听器包扫描路径上生效（WIRE-07，6.3.0）

6.3.0 之前，`ListenerManager.registerAll(plugin, packageName)` 会不管 `@ConditionalOnConfig`
一律注册所有发现的监听器，所以一个条件求值为 `false` 的监听器仍然会收到事件。6.3.0 在这条
路径上按 IoC 组件扫描早已使用的同样方式求值该条件；一个条件为假的监听器会被注册，但不会
收到任何事件。

**范围纠正。** `@ConditionalOnConfig` 在 `@CmdExecutor` 类上，在标准模块 JAR 路径上其实
在本版本之前就已经生效了——那条路径把命令类当作容器 bean 解析，一个条件为 `false` 的类
从一开始就不会被构造成 bean。这条记录只覆盖真实存在的监听器包扫描缺口。属于「修正与文档
明显矛盾的行为」——不需要迁移期。

### 已记录的实例：`ComponentScanner` 的失败与跳过诊断现在带有级别和堆栈（SILENT-07，6.3.0）

6.3.0 之前，`ComponentScanner` 报告六种不同的失败与跳过情形——一次歧义 `@CmdTarget` 拒绝、
一次组件/配置注册异常、一次 `@Bean` 方法调用异常、一个无法解析的包、一个无法读取的
JAR——全部直接写到 `System.err`，没有堆栈，也没有任何日志处理器看得到。6.3.0 把这六处全部
换成带级别的 `java.util.logging.Logger` 调用，在有 `Throwable` 的地方都带上原始异常。四个
注册失败点现在以 `Level.SEVERE` 记录；由于 `SystemLogHandler` 会自动把任何携带
`Throwable` 的 `Level.SEVERE` 记录转发给 `ErrorReportCollector` 并进一步转发到 UltiPanel
控制台，这四个此前只在服务器自己控制台可见的失败，现在会送达面板——对任何开启了错误报告的
服务器都是如此。两个「跳过并继续」的位置以 `Level.WARNING` 记录，且只留在本地。

另外，`scanJar` 和 `scanDirectory` 现在对每个类都捕获完全相同的
`ClassNotFoundException | LinkageError` 联合类型，弥合了此前的一个不一致：一个引用了缺失
可选类型的类，在生产环境（JAR 模式）下只会跳过那一个类，但在开发环境（目录模式）下会中止
整个包的扫描；现在两种模式的「跳过并继续」行为完全一致。

这属于「日志措辞……的变化」——不需要迁移期——但转发到面板这个事实被明确记下来，因为它改变
了一台现有服务器向外发送的内容，而不只是它本地记录了什么。

### 已记录的实例：组合层级超过 `@Component` 一层的元注解现在也能被识别（6.3.0）

6.3.0 之前，`ComponentScanner.hasComponentAnnotation` 只手写遍历了一层元注解组合。6.3.0
把它收拢到本阶段其余工作（`AnnotationUtils.findAnnotation` 迁移，见下面的废弃条目）统一
使用的同一个 `MergedAnnotationResolver` 上，它会遍历完整的组合图——一个组合层级在
`@Component` 之上两层或更多层的原型注解，现在会被识别为组件，此前不会。`UltiToolsPlugin`
的子类被排除在这次拓宽之外，这样一个模块自己的主类（通过 `@UltiToolsModule` 组合了
`@Configuration` → `@Component`）就不会被意外地当作组件 bean 二次注册。

如实记录：这次改动在 `Modules/` 和 `Plugins/` 里新登记的下游类集合**没有**被实测过——这是
证据缺失，不是一个实测出来的零，和上面其他条目不同。

**新增废弃、尚未到可移除的时候。** 本版本还带来三处新增的 `@Deprecated(since = "6.3.0",
forRemoval = true)`，但都没有出现在上面「6.3.0 的移除清单」表里，因为三者都不满足
[可移除性](#一个公开-api-何时可以被移除)的第 2 条——从**首个**带上该标注的发布起算已跨过一个
MINOR：6.3.0 正是那个首个发布，所以最早也要到 6.4.0 才有资格被移除。

| 类型 / 成员 | 替代方案 | 下游引用（参考） |
|---|---|---|
| `interfaces.TransactionManager.getConnection()` / `setIsolationLevel(int)` / `setReadOnly(boolean)` | `interfaces.JdbcTransactionManager`，把同样这三个方法作为真正的抽象方法保留 | 0——`TransactionManager` 不出现在已发布 jar 里 `DataStore`/`DataOperator`/`UltiToolsPlugin`/`UltiTools` 的任何签名中；今天一个模块只能通过把 `DataOperator` 强转成 `AbstractRelationalDataOperator` 并自带 `DataSource` 才能碰到它 |
| `interfaces.DataStore.getOperator(UltiToolsPlugin, Class)` 与 `getOperator(File, Class)` | `getOperator(DataScope, Class)` | 未单独实测——模块作者应当使用、也是文档承诺的正确入口是 `getDataOperator(Class)`（按上面的调查，17 个仓库里下游命中 94 次），它仍然会替你调用这两个已废弃的重载，不受这次废弃影响 |
| `utils.AnnotationUtils.findAnnotation` | `context.MergedAnnotationResolver.find` | 0——`Modules/` 和 `Plugins/` 里没有任何 `AnnotationUtils` 出现；方法体本身未改动，只作为尚未迁移到该解析器的下游代码的兼容性兜底 |

在 `TransactionManager` 上，把三个原本抽象的方法改成会抛 `UnsupportedOperationException` 的
`@Deprecated` `default` 方法，按 `japicmp` 0.26.1 自己的 `METHOD_ABSTRACT_NOW_DEFAULT` 分类，
报告结果是 `binaryCompatible="false"`。单看这行字面意思像是破坏性变更；但实践中没有任何已编译的
6.2.x `TransactionManager` 实现者会在链接期因此崩掉。一个此前已编译的**具体**类，为了能编译通过
本来就必须实现该接口全部九个抽象方法，所以它自己那三个覆写方法仍然按普通虚方法分派解析——新的
`default` 方法体对它而言从来不会被走到。一个此前已编译、把三个 JDBC 专属方法留空的**抽象**类
（当时合法，因为它是抽象类）现在多了一个此前不存在的可用 `default` 兜底，通过接口方法继承解析——
这正是 `default` 方法存在的意义所在，用来保证这种情况的安全。两种情况都不会产生
`AbstractMethodError` 或 `NoSuchMethodError`。`japicmp` 这个保守的分类，对这个具体变换（抽象
变默认、签名不变、返回类型不变）来说，看起来比 JVM 实际的二进制兼容保证更严格——记在这里是为了
不让只看 `japicmp` 原始报告的读者被误导，同时也不让本文件自己的兼容性承诺说得比实际更满。

### 已记录的实例：指令校验器的副作用移入链本身，无法被链强制执行的 `@CmdCD`/`@UsageLimit` 现在会拒绝加载（SILENT-11，6.3.0）

6.3.0 之前，`BaseCommandExecutor.executeCommand` 无条件地按字段引用调用
`cooldownValidator.applyCooldown` / `lockValidator.acquireLock` / `lockValidator.releaseLock`——
无论这些校验器是否真的出现在执行器自己的校验链里。通过单参数构造器
`BaseCommandExecutor(ValidatorChain)` 构建的执行器会原样存下传入的链，除非调用方自己显式添加，
否则 `CooldownValidator`/`UsageLockValidator` 不会被加入。`@CmdCD` 与 `@UsageLimit` 看起来已经
声明、状态也在正常记录（`getRemainingCooldown()` 返回看似合理的值），实际上却拦不住任何一次
调用（#312）。

6.3.0 从两个方向关闭这个缺口：

- `CommandValidator` 新增一个 `default void onComplete(CommandContext, boolean)` 后置动作钩子；
  `ValidatorChain.ChainValidationResult#getPassedValidators()` 现在是「这次调用到底跑过哪些
  校验器」的唯一有序事实来源。`executeCommand` 的每一个校验器副作用都只从这份列表驱动——
  「在链里」与「有副作用」在结构上就是同一件事，不存在能让两者失步的构造路径。
- 在插件加载时，`PluginManager.validateCommandExecutorContracts` 会拒绝任何标注了 `@CmdCD` 或
  `@UsageLimit(SENDER|ALL)`、而其校验链中缺少对应校验器的类或 `@CmdMapping` 方法，并指出问题类
  与（已知时）问题方法（`ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE`，4006）。
  `@UsageLimit(NONE)` 豁免，因为它本就没有声明任何限制。`@CmdCD`/`@UsageLimit` 的 `@Target` 也
  从 `{METHOD}` 拓宽为 `{METHOD, TYPE}`，使同一检查也能应用于类级声明——纯增量变更，不改变任何
  已有标注位置的语义。

**加载时拒绝没有开关。** Phase 3 的模块粒度隔离是被接受的退路：只有问题模块本身加载失败，其余
模块照常启动。由 plan 05-01（`BaseCommandExecutorTest$ChainDrivenPostActionTests`）与 plan 05-02
（`PluginManagerCommandContractTest`）交付。

**归类。** 这次拒绝属于「从静默降级变为失败」，按惯例应当经过两步警告期，但基于与本文档
`del()` 条目相同的理由跳过了警告期：一个已声明却拦不住任何调用的 `@CmdCD`/`@UsageLimit` 永远
是模块作者的缺陷，而不是一条更响的日志能真正解决的问题——冷却时间是 Minecraft 服务器上的滥用
防护（`UltiBackup.BackupCommand` 的 `/backup start` 就标注了 `@CmdCD(30)`；静默失去它意味着
玩家可以刷屏触发整服备份）。经测量，整个仓库中**零**个下游自定义 `ValidatorChain` 构造点（对照组：
`extends BaseCommandExecutor`，19 个文件），因此目前没有已知模块会命中这次拒绝。

`japicmp` 无法检测到这一切。`CommandValidator#onComplete` 是源码级与二进制级都兼容的 default
方法新增——已通过对已发布 6.2.0 至 6.2.5 jar 运行 `javap` 确认，其既有形状是 1 个抽象方法加 3 个
default 方法；新增第 4 个 default 方法延续了这个既有约定，而非推翻它。加载时拒绝与 `@Target`
拓宽都只是行为变化，没有任何既有方法签名改变。

### 已记录的实例：`@UsageLimit` 现在真正实现了名字所承诺的串行化，`ContainConsole()` 默认值改为 `true`（GEN-09，6.3.0）

6.3.0 之前，`UsageLockValidator.acquireLock` 的布尔返回值被其唯一调用方丢弃——
`BaseCommandExecutor.java:261` 处只是一条裸语句——所以获取失败从未阻止过执行。`releaseLock` 的
`ALL` 范围分支无条件移除服务器级锁（`serverLocks.remove(methodKey)`，没有所有权检查），因此任意
发送者的调用完成都可能释放**另一个**发送者仍持有的锁（Pitfall 5 / T-05-03）。`@UsageLimit`
记录了一把锁，却按错误的所有者节奏释放它——它没有真正串行化任何东西。

6.3.0 在与上一条相同的改写中修复了这两个缺陷：获取现在发生在 `validate()` 内部本身
（验证即获取），因此获取失败就是一次普通的验证拒绝，映射方法永远不会被调用；`releaseLock` 的
`ALL` 范围分支现在通过原子的 `Map#remove(key, ownerUuid)` 做所有权门控。`UsageLimit.ContainConsole()`
的默认值同时从 `false` 改为 `true`——除非某个映射显式排除，否则控制台发送者现在也受此限制约束。
由 plan 05-01 交付，`UsageLockValidatorTest$SerializationGuaranteeTests`（8 个测试）。

**归类。** 获取门槛与所有权门控释放这两处修复属于「纠正与文档明显矛盾的行为」——该注解的名字
本身就一直在承诺串行化与单一所有者释放（#209）——不需要迁移期。`ContainConsole()` 的默认值翻转
属于已文档化默认值的翻转，按惯例需要两步警告期，但以零测量成本随同上面的修复一并发布：整个
仓库中**零**个生产环境 `@UsageLimit` 使用点（对照组：`@CmdCD`，3 个模块共 8 处），因此没有已知
调用方会观察到这次翻转。

`japicmp` 无法检测到这一切——`UsageLimit` 或 `UsageLockValidator` 的公开签名均未改变。

### 已记录的实例：`@CmdParam.suggest` 的解析契约从三步拓宽为四步，未知的 `@key` 现在会拒绝加载（WIRE-01，6.3.0）

6.3.0 之前，`suggest()` 按三步解析——本类方法名、`@CmdSuggest` 指向类的方法名、i18n 提示文本
兜底。已发布 jar 自带的四个内置补全器（`MaterialsCompleter`、`OnlinePlayersCompleter`、
`WorldsCompleter`、`StaticSuggestionsCompleter`）根本没有声明式入口；五个下游模块因此各自手写了
等价实现（`suggestWorlds` 11 处、`suggestOnlinePlayers` 2 处，加上 `suggestBooleans`），因为没有
办法声明它们。

6.3.0 新增了第一步：以 `@` 开头的值（例如 `"@players"`）会通过一个已注册的 `TabCompleter`
解析——可以是四个内置之一，也可以是模块在运行时注册的键。`@` 不是合法的 Java 标识符起始字符，
因此任何既有方法名值都不会与之冲突；实测的全部 24 个下游 `@CmdParam(suggest=)` 使用点都不需要
任何改动。**未知**的 `@key` 会在上一条引入的同一次加载时校验中拒绝声明它的模块加载，并指明类、
方法与键——它**不会**退回到 i18n 提示兜底。原有三步（包括方法未找到时的 i18n 提示兜底）保持不变。
由 plan 05-06 交付，`BaseCommandExecutorTabCompletionTest$SuggestKeyNotationTests`（双记法）与
`PluginManagerCommandContractTest$UnknownSuggestKeyTests`（8 个测试，加载时拒绝）。

**归类。** 新增的解析步骤本身不需要迁移期——它被限定在一个任何既有值都无法产生的语法（`@`）
之后才触发。**拒绝**这一半属于「从静默降级变为失败」，基于与上一条相同的 D-04 理由跳过了警告期：
整个仓库中实测**零**个既有的 `@key` 记法使用点（全部 24 个实测点都是普通方法名），因此这次拒绝
没有任何已知位置需要警告。

`japicmp` 无法检测到这一点——`CmdParam.suggest()` 的签名没有变化，只是其 `String` 值的运行时
解析方式变了。

### 已记录的实例：Tab 补全现在会在一个 `@CmdMapping` 能贡献建议或被反射调用之前先按权限过滤它（SILENT-25，6.3.0）

6.3.0 之前，`BaseCommandExecutor.suggest` **完全没有**权限检查。命令类上的每一个 `@CmdMapping`
都会不加区分地提供给 `TAB` 补全，无论发送者是否持有其 `@CmdMapping(permission=)` 或
`requireOp()` 所要求的权限——首个 token 的字面量列表、多 token 的同级字面量扫描、以及为某个
发送者永远无法调用的映射所解析出的 `<param>` 槽位，都不例外。最后这一条不仅是信息泄露：一个
已解析的 `<param>` 槽位会被交给 `commands/tabcomplete/MethodInvocationCompleter`，后者会
**反射调用** `@CmdParam(suggest = "methodName")` 背后的方法来产出建议。在没有权限门控的情况下，
发送者只需对一个受权限限制的映射的参数按下 `TAB`，就可能在他们真正尝试执行该命令之前触发那个
方法被调用。已废弃的 `abstracts.AbstractCommandExecutor.suggest` 在其首 token 与参数槽位分支上
已经带有等价的守卫，但在其多 token 同级字面量分支上没有——因此把一条命令迁移到当时的
`BaseCommandExecutor` 世代上，实际上是静默地扩大了暴露面，而不是缩小它。

作为把两代命令执行器的 Tab 补全统一到同一个分发实现的这次 WIRE-01 重写（见上一条记录）的
一部分，6.3.0 关闭了这个口子：`abstracts.command.CommandTabCompletionDispatch.isVisible(CommandSender,
Method)`——先检查映射声明的权限，再检查其 `requireOp()`——在一个映射能贡献任何内容之前，
为三条分发路径（首 token、`<param>` 槽位、以及多 token 同级字面量扫描）中的每一条设防，两代
命令执行器均如此。由 plan 05-05 交付，`BaseCommandExecutorTabCompletionTest$PermissionFilterTests`
（6 个测试），加上 `$ArgumentPositionResolutionTests#permissionFilterHoldsOnMultiTokenPath` 与
`$ShellAndParityTests`（两代之间的一致性，包括对受权限限制的映射保持隐藏）。

**模块作者应该检查什么。** 一个携带了玩家不持有的 `permission` 的 `@CmdMapping`，现在将不再
出现在这些玩家的 Tab 补全结果中——这是预期的修复，不是缺陷，但它是一次可见的行为变化：此前能
看到（并可能触发其建议方法被反射调用的）每一个子命令的发送者，现在只能看到他们被允许执行的
那些。

**归类。** 与下文的 GUI 点击派发条目出于相同的理由，这一条被记录为一次**安全修复**，而不是一次
中性的行为整理：此前能够看到受权限限制映射的补全建议、或触发其建议方法反射调用的发送者，利用
的是一个缺失的检查，而不是在依赖某项被文档记载的契约。依据上面的
[无需迁移期的行为变更](#哪些行为变更可以在-minor-直接做)，安全修复可以不经事先通知直接落地；
这里同样不设警告期。

`suggest(Player, Command, String[])` 的公开签名未变，所以 `japicmp` 无法检测到这一点——这条记录
正是为了写下签名差异写不出来的东西而存在。

### 已记录的实例：`@AsyncCommand.timeout()` 现在真正生效，默认路径上的双重异步派发被移除（WIRE-12，6.3.0）

6.3.0 之前，`timeout() > 0`——由于 `timeout()` 默认值为 `30`，这是默认路径——会把命令的可运行体
包进**第二个**可运行体，而这个外层可运行体的全部内容就是再把**原始**可运行体异步调度一次：一次
没有强制任何截止时间、也从不向发送者报告任何信息的双重派发。这比彻底不生效更糟，而且它正是每个
`@AsyncCommand` 的默认路径。javadoc 却声称超时后异步操作会被"取消"；实际上什么都没有被取消，
甚至没有被计时（#322）。

6.3.0 让命令体无条件地、恰好一次被异步调度。当 `timeout() > 0` 时，一个完全独立的监视任务通过
`runTaskLaterAsynchronously` 被排入；如果截止时间到达而命令体仍在运行，发送者会收到恰好一条
超时消息，通过主线程送达。命令体**永远不会**被中断——它总会自行运行至完成；超时报告与命令体
自身最终产生的效果彼此独立，通过一个共享的 `AtomicBoolean` CAS 守卫做到无竞争。`timeout() = 0`
不受影响（此前与此后都是单次派发、无监视器）；`@RunAsync` 不受影响（走的是另一条未被触及的
分支）。javadoc 被重写为精确陈述这一点——这是框架"等待多久"的截止时间，绝非对命令体的取消——
与 Phase 2 D-10 已为结构相同的 `@Transactional#timeout()` 建立的措辞口径保持一致。由 plan 05-07
交付，`BaseCommandExecutorTest$AsyncCommandTimeoutTests`（6 个测试）。

**归类。** 这是「从静默降级变为失败」，外加返回语义的变化：此前在任何情况下都不会收到超时相关
消息的发送者，现在可能会收到一条。基于本文档自己的"已证明不可用"理由跳过了两步警告期，且这个
理由是直接复现出来的，而非论证出来的：在修复前的 HEAD 上阅读 `BaseCommandExecutor.java:385-409`
证实了外层可运行体的全部内容就是再一次调用 `runTaskAsynchronously`，没有任何地方在追踪截止
时间。**实测影响面为零**——`Modules/`、`Plugins/`、`Libraries/`、`Tooling/` 中 `@AsyncCommand`
下游使用点为零（对照组：`@CmdMapping`，71 个文件）；工作区中六个真实的异步命令使用点
（`UltiBackup` 3 处、`UltiWorlds` 2 处、`UltiEssentials` 1 处）全部使用更老的 `@RunAsync` 注解，
走的是 `asyncCommand == null` 分支，修复前后都从未进入过双重派发路径。这是一次工作区测量，不是
对 Maven Central 使用者的断言——他们在这里是不可观测的。

`japicmp` 无法检测到这一点——不仅是推断，而是实测确认：`japicmp` 自己的 `clean verify` 运行把
`AsyncCommand` 报告为 `UNCHANGED ANNOTATION`，把 `BaseCommandExecutor` 的公开构造器报告为
`UNCHANGED CONSTRUCTOR`。

### 已记录的实例：`.filter(x).build().register()` 现在真正生效过滤，四个易混淆的 `SimpleTempListener` 构造器被标记废弃（SILENT-12 / SILENT-13，6.3.0）

6.3.0 之前，`TempListener.DefaultTempListenerBuilder.build()` 调用的是
`(Class, TempEventHandler, EventPriority)` 这个 `SimpleTempListener` 构造器——三个三参数重载中
**唯一没有** filter 参数的那个——所以通过 `.filter(...)` 设置的过滤器被静默丢弃，`build()` 
生成的监听器永远放行所有事件，不论是否设置了过滤器（#313）。仅三行之后的姊妹方法 `listen()`
早已正确调用了四参数重载。根本设计缺陷在于：已发布 jar 的两个三参数重载仅在**最后一个**参数是
`EventPriority` 还是 `Function<E, Boolean>` 上不同——同样的参数个数，编译期没有任何信号能提醒
选错了哪一个。

6.3.0 让 `build()` 调用与 `listen()` 相同的四参数构造器
`(Class, EventPriority, TempEventHandler, Function)`。`.filter(p).build().register()` 现在会
真正丢弃被拒绝的事件、放行被接受的事件。由于 `TempListener` 接口本就同时具备 `register()` 与
`unregister()`，这一处改动同时以派生方式（而非独立工作）关闭了另一个相关问题（#324）：一次
`.filter(p).build()` 调用现在就能得到一个既能过滤、又能被注销的实例，不需要任何额外机制。四个
易混淆的构造器——无参、`(Class, TempEventHandler)`、`(Class, TempEventHandler, Function)`、
`(Class, TempEventHandler, EventPriority)`——被标记为 `@Deprecated(since = "6.3.0", forRemoval =
true)`；保留的是那个四参数的全参构造器，未做改动。这四个构造器会自动登记进上面的
[移除清单](#630-的移除清单)——按本文档自己的策略由该标注生成——计划在 Phase 7 中删除；它们不会
被手工列出。由 plan 05-08 交付，`TempListenerTest$DefaultTempListenerBuilderTests` 与
`TempListenerTest$UnregisterTests`。

**归类。** 走安全修复 /「纠正与文档明显矛盾的行为」通道——`build()` 自己的契约（一个专门为了
被应用而存在 `.filter(...)` 方法的构建器）被静默违反了。值得明确指出：`interfaces.impl.PlayerTempListener`
本身已 `@Deprecated`，其 javadoc 推荐用按玩家过滤的 `TempListener#common(Class)` 作为替代——也
就是说，框架自己文档记载的迁移路径，此前一直在把调用方引向这个被静默失效的过滤器。不设警告期：
整个仓库中实测**零**个 `TempListener` 下游使用点（对照组：`extends UltiToolsPlugin` 20 处、
`registerSelf` 30 处、`@EventListener` 46 处——确认搜索机制本身有效），因此没有已知调用方依赖
这个静默失效的过滤器。

`japicmp` 无法检测到 `build()` 的行为变化——没有任何签名改变。四个构造器的废弃标注确实会被
`japicmp` 自己的 `"Annotation deprecated added"` 分类捕捉到，本 plan 自己的 `mvn clean verify`
运行已确认这不构成破坏性变更。
### 已记录的实例：声明式 GUI 的重绘管线现在真正会重绘，`GuiRenderer.initialize` 的签名改为接受一个 `Supplier<Widget>`（D-09 第 1-3 项 / WIRE-02，6.3.0）

6.3.0 之前，声明式 GUI 层在第一帧之后就再也不会重绘。`GuiRenderer` 只在 `initialize(Widget,
BuildContext)` 时构建一次 `Widget` 树，此后从不重新推导；`Element.update()` 从不把自己标记为
脏；而 `collectRenderNodesRecursive` 在每一帧都把同一个被缓存的、存活的
`RenderObjectElement.getRenderNode()` 引用原样存进 `lastRenderNodes`，导致
`RenderNodeDiffer.diff()` 每次都在拿一个节点和它自己比较。仅改变 lore、仅改变展示名、或仅改变
物品类型的 widget 变化完全不会产生任何库存更新——这个子系统自己宣称的论点 `UI = f(state)` 并不
成立。

6.3.0 修复了这一点。`GuiRenderer.initialize(Supplier<Widget> widgetSupplier, BuildContext
context)` 现在会在每一次 `performBuild()` 时（包括第一帧）从当前状态恰好重新推导一次 `Widget`
树；`Element.update()` 在基类中无条件地把元素标记为脏，三个子类均如此；
`collectRenderNodesRecursive` 在存入之前会通过 `.copy()` 对每个 `RenderNode` 做快照，从而打破
自比较。同一个 plan 中还发现并修复了第二个、此前从未被记录的缺口：嵌套的 `StatefulWidget` 上的
`State.setState()` 会向已挂载的根节点冒泡，但此前没有任何机制把这个冒泡与
`GuiRenderer.scheduleBuild()` 连接起来——根目录 `CLAUDE.md` 自己记载的 `CounterButton` 示例，
在这次修复之前实际上并不成立（修复方式：`Element.setRootBuildScheduler(Runnable)`，由
`GuiRenderer.mountRoot()` 注册）。由 plan 05-11 交付，`GuiRendererRepaintTest`（14 个测试）。

**与本文档「行为变更」小节中的其它每一条不同，这一条对 `japicmp` 并非不可见。**
`GuiRenderer.initialize` 的参数类型从 `Widget` 改为 `Supplier<Widget>`——这是一次真正的 JVM
方法描述符变化——`japicmp` 自己的报告（`target/japicmp/japicmp.diff`，基线为已发布的 6.2.5
jar，依据本仓库的 japicmp 配置）直接点名了它：

```
***! MODIFIED CLASS: PUBLIC com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer  (not serializable)
---! REMOVED METHOD: PUBLIC(-) void initialize(com.ultikits...core.Widget, com.ultikits...core.BuildContext)
+++  NEW METHOD: PUBLIC(+) void initialize(java.util.function.Supplier<com.ultikits...core.Widget>, com.ultikits...core.BuildContext)
```

产生这条记录的 plan 的早期草稿曾断言「没有一处行为变更改动了签名，所以 `japicmp` 无法发现任何
一处」，这对整个 GUI 分支而言并不成立——至少这一个成员就是反例，本文档不重复这个错误断言；
更正说明见 plan 05-15 自己的 SUMMARY。本文档中这一条以下的每一条 GUI 分支记录都是没有签名差异
的纯行为变更；这一条是唯一的例外，上面直接引用的是 `japicmp` 自己的 diff，而非一句断言。

**归类。** `DeclarativeGui.onOpen()` 是 `initialize(...)` 在仓库内唯一的调用方，已在同一个
plan 中更新——因此对任何外部调用方而言，这是源码级和二进制级都不兼容的变更。整个
`abstracts.gui.declarative` 包树都带有 `@ApiStatus.Experimental`，且这个方法被实测在整个
monorepo 范围内有**零**个下游调用点（对照组：更老、更稳定的命令式 `abstracts.gui` 一代有 8 个
——确认了搜索机制本身有效；见 `05-CONTEXT.md` D-12）。此次变更之前没有
`@Deprecated(forRemoval = true)` 警告期，这里也不提供警告期，依据的是本文档已经用于上面 AOP
移除项的同一套推理（见
[同一发布内移除的例外条款](#例外在宣布的同一个发布里移除)）：第 2 款（「已发布但从未被任何调用方
接入」）在上面的零采用实测下成立；第 1 款（「在当前已发布版本上被证明不可用」）也独立成立——
6.3.0 之前的这个方法在已发布的 6.2.5 jar 中本身就不可用：`Widget` 只在 `initialize()` 时提供
一次、此后从不重新推导，意味着此后每一帧都会静默地无法重绘；已发布 jar 自己的行为就是复现，
而不是一个需要论证的说法。这个方法没有出现在上面的[移除清单](#630-的移除清单)中，因为它从未
带过 `@Deprecated`——按本文档自己的生成规则它不可能被列入那张表，这正是这里需要手工记录它的
原因。

### 已记录的实例：点击玩家自己的物品栏不再能触发声明式 GUI 的处理器（D-09 第 4 项 / WIRE-02，6.3.0）

6.3.0 之前，`GuiRenderer.handleClick` 是按 `event.getSlot()`——一个相对于点击落在哪个物品栏的
槽位号——去查 `clickHandlers` 表的，而这个槽位号在 GUI 自己的顶部物品栏与同一个 `InventoryView`
下方显示的玩家自己的物品栏之间会发生数值冲突。点击**玩家自己的物品栏**中某个槽位，只要该槽位
数值恰好等于 GUI 已注册处理器的某个槽位，就会触发该处理器——这是一次非预期的跨物品栏派发，
不是一项被文档记载的能力。

6.3.0 在任何处理器查找之前，先用 `gui.getSize()` 对 `event.getRawSlot()` 做边界检查，并在
`RenderNode.getSlotIndex()` 已经用来填充 `clickHandlers` 的同一个槽位空间里做查找。这会拒绝
每一个玩家物品栏的原始槽位（在同一个组合 `InventoryView` 中，它们总是排在 GUI 自己的槽位之后）
以及 Bukkit 对「点击在窗口之外」报告的 `-999` 哨兵值。`clickHandlers` 仍然是槽位归属的唯一记录
——只有查找用的 key 变了，从会冲突的相对槽位改为经过边界检查的原始槽位。由 plan 05-12 交付，
`GuiClickDispatchTest`（7 个测试），覆盖了整个会冲突的槽位范围、shift 点击与数字键点击，以及
一次重绘把处理器移动到新槽位后，其点击目标也随之移动的情形。

**归类。** 这一条被记录为一次**安全修复**，而不是一次中性的行为整理：此前能够通过点击自己
物品栏中数值冲突的槽位来触发 GUI 处理器的调用方，利用的是一个缺陷，而不是在依赖某项被文档记载
的契约。依据上面的
[无需迁移期的行为变更](#哪些行为变更可以在-minor-直接做)，安全修复可以不经事先通知直接
落地；这里同样不设警告期。

`handleClick` 的公开签名未变（仍是 `public void handleClick(InventoryClickEvent event)`），所以
`japicmp` 无法检测到这一点——这条记录正是为了写下签名差异写不出来的东西而存在。

### 已记录的实例：`GridView` 现在会把任意 widget 类型都作为渲染时写入的父数据来定位（D-11 / WIRE-03，6.3.0）

6.3.0 之前，`GridView.Builder.items(items, itemBuilder)` 对 `ItemDisplay` 做了特殊处理——用一次
六字段的手工拷贝计算每个子项的槽位并直接构造 `ItemDisplay`。任何通过 `.child(Widget)` /
`.children(...)` 加入 `GridView` 的其它 widget 类型——一个 `TextButton`，或一个混合类型的网格
——从来不会被自动定位；它只会停在自己声明（或默认）的槽位上，而网格从不触碰这个槽位。

6.3.0 会在渲染时把每个子项的网格计算槽位写到该子项自己子树产生的 `RenderNode` 上
（`GridViewElement.applyGridPositions()`，在 `mount()` 与 `performRebuild()` 中都会调用，发生
在 `GuiRenderer` 收集快照之前）——这与
[Flutter 的 `ParentDataWidget`](https://api.flutter.dev/flutter/widgets/ParentDataWidget-class.html)
/ `Stack`+`Positioned` 一致，也正是这个子系统自己的文档所声称移植的模式：由布局父组件附加数据，
子组件永远看不到这份数据，而不是子组件自己计算自己的位置。`GridView.Builder.items()` 中
`ItemDisplay` 专属的特殊分支被删除；现在任意 widget 类型都能在 `GridView` 内正确定位。
`Widget` 自身的 API 没有新增任何与槽位相关的方法，因此没有任何下游自定义 widget 需要改动。
由 plan 05-13 交付，`GridViewTest`（15 个测试）与 `GridViewElementTest`（13 个测试——D-09 第 5
项的键控回收敛，这次定位工作建立在它之上）。

**冲突规则（D-11）。** `GridView` 总是优先于子项显式声明的槽位，**并且**会发出一条点名该子项的
`WARNING`——这与 Flutter 对错放的 `Positioned` 直接硬失败、而不是静默忽略保持一致。子项若没有
显式槽位，或合法地声明在槽位 `0`（`ItemDisplay`/`TextButton` 构建器自身原有的默认值，被当作
「未设置」的信号，因为两个构建器都没有单独的哨兵值——在 `GridView` 内显式调用 `.slot(0)` 的
widget，因此与从未调用过 `.slot()` 的 widget 无法区分，二者都不会触发警告；见
05-13-SUMMARY.md 中明确记录的这一取舍），则不会产生警告。

`GridView.Builder.items()` 的签名未变——被删除的是它内部的 `ItemDisplay` 专属分支，而不是这个
方法本身——所以 `japicmp` 无法检测到这一行为变化。

### 已记录的实例：两对声明式 GUI 构建器方法被直接删除，而不是给出一个猜测出来的实现（D-09，6.3.0）

`Container.Builder.background(IconWrapper)`/`Container.getBackground()` 与
`GridView.Builder.rows(int)`/`GridView.getMaxRows()` 被删除，而不是被实现。这两对都是流式构建器
方法，它们存下的值从未被任何下游代码读取过：

- `Container` 自己不带任何空间边界——不像 `GridView` 那样有 `startSlot`/`columns`/`rows`——所以
  「填充未占用的槽位」在 `ContainerElement` 内部根本没有一个能给出答案的作用域，若强行实现，就
  有可能静默覆盖某个兄弟子树的 `RenderNode`，而这是 `Container` 自己的子树唯一能看到的区域。一
  个错误的实现在单个根级 `Container` 的常见情形下会显得正确，却会在它与其它内容并列嵌套的那一
  刻悄悄破坏显示。
- `GridView.Builder.rows(int)` 的 `maxRows` 字段虽然被赋值，却从未被 `calculateSlotForChild`
  读取过（已用 grep 在 `src/main` 与 `src/test` 中确认）；用它实现一条溢出封顶规则，将意味着
  发明本 plan 自身范围之外的新布局语义。

根目录 `CLAUDE.md` 的第 14 条注意事项此前——在 plan 05-13 之前——曾正确地把
`Container.background(...)` 描述为「静默什么都不做」的死代码。从本 plan 起，调用任一被删除的
方法会是一次**编译错误**，而不再是静默的空操作；该条注意事项已被更正为如实陈述这一点。
`IconWrapper.java` 被保留，尽管 `Container.background(...)` 是它在 `src/main` 中唯一的生产
调用点，因此它现在在生产代码中已完全无人使用——这一点被标记留给未来的一个清理 plan，本 plan
不解决它。由 plan 05-13 交付，`GridViewTest#containerNoLongerExposesBackground` /
`#gridViewNoLongerExposesRows`（基于反射、断言 `NoSuchMethodException` 的回归防护测试）。

**依据本阶段自身要求读取的 `japicmp` 结果——而非假设得出。** 这两个类都带有
`@ApiStatus.Experimental`；两对方法都从未带过 `@Deprecated(forRemoval = true)`，因此按本文档
自己的生成规则，它们都不会被列入上面的[移除清单](#630-的移除清单)——那张表是从该注解生成的，
而这一条分支什么都没有标记废弃，是直接删除的。`japicmp` 自己的报告
（`target/japicmp/japicmp.diff`，`mvn -B clean verify` 相对已发布 6.2.5 jar 基线运行）被直接
读取，确认了恰好这两对方法，别无其它：

```
---! REMOVED METHOD: PUBLIC(-) com.ultikits.ultitools.abstracts.gui.declarative.widgets.IconWrapper getBackground()
---! REMOVED METHOD: PUBLIC(-) com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container$Builder background(com.ultikits.ultitools.abstracts.gui.declarative.widgets.IconWrapper)
---! REMOVED METHOD: PUBLIC(-) int getMaxRows()
---! REMOVED METHOD: PUBLIC(-) com.ultikits.ultitools.abstracts.gui.declarative.widgets.GridView$Builder<T> rows(int)
```

该关卡是**仅报告**模式——`pom.xml` 没有为 `japicmp-maven-plugin` 设置任何 `breakBuildOn*` 标志
——所以这次 `mvn verify` 通过本身并不构成兼容性的证据；只有直接读取的 diff 才算数。这也是
Phase 7 预期要逐条搬进它自己的 japicmp 排除清单、与本条一一对应的清单，等到那个阶段真正激活
这道关卡的时候。

**归类。** 与上面 `initialize(...)` 条目相同的推理：
[同一发布内移除的例外情形](#例外在宣布的同一个发布里移除)的第 2 款（「已发布但从未被任何调用方
接入」），依据上面对两个字段的 grep 结果成立。不设警告期，因为根本没有任何真实工作行为需要
警告期去保护——一个存下的值从未被任何人读取的流式方法，本来就没有任何可观察的效果需要保留。

### 已记录的实例：声明式 GUI 包上的 `@ApiStatus.Experimental` 被保留，只改了措辞（D-12，6.3.0）

没有移除，没有签名变化，也没有行为变化——记录在这里只是为了不让上面三条被误读成「这个界面
现在已经稳定了」。`abstracts.gui.declarative` 下全部六个 `package-info.java` 文件（根包、
`core`、`engine`、`widgets`、`util`、`widgets.navigation`）都保留了 `@ApiStatus.Experimental`。
只有随附的 javadoc——以及文档站点上与之对应的 `::: warning`——发生了变化：从描述「v6.2.5 中的
已知缺口」，改为点名本阶段修复的三处渲染接缝（重绘重新推导、点击派发的边界检查、`GridView`
对任意 widget 类型的定位），并说明为什么这个标记仍被保留：四个核心机制一次性被重写，却还没有
任何真实服务器环境的下游反馈。由 plan 05-14 交付。

## 移除清单覆盖不到的二进制不兼容

上面的移除清单只覆盖得了**有人知道自己在改 API** 的那些变更——它的两个前提
（带 `@Deprecated(forRemoval = true)`、跨过一个 MINOR）都要求改动者先意识到这是一次
API 变更。有一类改动不满足这个前提：它在作者眼里根本不是 API 变更，却改掉了公开方法的
**JVM 方法描述符**——于是它必定打破二进制兼容，却可能完全不打破源码兼容，从而绕过所有
以「有人会注意到」为前提的流程。

这已经发生过两次，都记在这里。第一次在 MINOR，第二次在 PATCH——**没有哪个版本级别是豁免的**：

### 第一次：6.1.1 → 6.2.0，一个 MINOR

移除 Spring 时，`UltiToolsPlugin` 里 context 字段的类型从
`AnnotationConfigApplicationContext` 换成了 `SimpleContainer`。该字段带 `@Getter`，
所以 Lombok 生成的 `getContext()` 的**返回类型**跟着变了：

```
针对 6.0.6 编译的模块，字节码里记的是
  getContext:()Lorg/springframework/context/annotation/AnnotationConfigApplicationContext;
6.2.0 及以后的框架提供的是
  getContext:()Lcom/ultikits/ultitools/context/SimpleContainer;
```

（起算点是 **6.2.0**，不是 6.2.1。6.2.0 发布到了 Maven Central 但仓库里没有对应的 git
tag——**发布列表看 `maven-metadata.xml`，不要看 `git tag`**。在 6.2.0 上排查同样会撞到
这个异常。）

返回类型是方法描述符的一部分，对 JVM 而言这是两个不同的方法，老 JAR 在 `registerSelf()`
里拿到 `NoSuchMethodError`。

**源码这一侧则取决于调用写法**，这正是它容易被漏掉的原因。UltiEconomy 那种
`getContext().getBean(X.class)` 没有写出返回类型的名字，同一份源码在两个版本上都编得过；
但只要源码写成 `AnnotationConfigApplicationContext ctx = plugin.getContext();`、把返回值
传给接收旧类型的方法、或者覆写 `getContext()`，重新编译就会失败——`SimpleContainer` 与
旧类型没有继承关系。所以这类改动的准确说法是「**必定破坏二进制兼容，是否破坏源码兼容
取决于调用方**」，不是「一律只破坏二进制兼容」。

三道防线因此同时失效：

- 没有任何东西被「移除」，移除清单里放不进这一条；
- 没有可以标 `@Deprecated` 的目标，`-Xlint:removal` 对下游一次都没响过；
- `PluginManager` 的版本门禁也拦不住——它只判 `api-version > 当前框架版本`，即
  「模块要求的框架比装的还新」这一个方向。老模块声明的下限确实被满足了，照样炸。

### 第二次：6.2.0 → 6.2.1，一个 PATCH

上面那次是 MINOR。第二次发生在 **PATCH** 里，所以「盯着 MINOR 就行」是不成立的。

`43f55ea refactor!: replace AbstractDataEntity with BaseDataEntity<String>` 把实体类型
换掉了，凡是签名里出现该类型的公开成员，描述符都跟着变：

```
6.2.0  DataOperator.insert   (Lcom/ultikits/ultitools/abstracts/AbstractDataEntity;)V
6.2.1  DataOperator.insert   (Lcom/ultikits/ultitools/abstracts/data/BaseDataEntity;)V
```

**完整清单是逐符号算出来的，不是手工列的**（手工列过三版，每版都漏）。做法：把两个框架
JAR 都解开，对 `com/ultikits/ultitools/**` 跑 `javap -s`，按 `(类, 成员名) → 描述符集合`
建表再比——**必须按集合，否则重载会互相覆盖**，`exist(T)` 就是这么被 `exist(WhereCondition[])`
盖掉、漏了一轮的。

结果是 **14 个公开成员、5 个类型**，且这次改动**零移除、零新增**——变的全部是描述符：

| 类型 | 受影响成员 |
|---|---|
| `interfaces.DataOperator` | `exist(T)` · `getById` · `insert(T)` · `update(T)` |
| `interfaces.Query` | `first()` |
| `…impl.data.AbstractRelationalDataOperator` | 同 `DataOperator` 四项 |
| `…impl.data.json.SimpleJsonDataOperator` | 同 `DataOperator` 四项 |
| `…impl.data.QueryImpl` | `first()` |

下游真正会静态调用的是前两行那 **5 个接口成员**，后 9 个是实现类上的同名镜像。
注意 `Query.first()` 是独立的一条：只走 `.query()….first()` 的模块，一个 `DataOperator`
方法都没调，照样中招。

不含实体类型的重载（`update(String, Object, Object)`、`exist(WhereCondition[])`）描述符未变。
`AbstractDataEntity` 本身没被删，所以这一条同样进不了移除清单。

**描述符变更本质上都是双向的**，两个实例都是——一个符号在两版里名字相同、描述符不同，
那么无论从哪一侧编译，另一侧都没有它。老 JAR 在新框架上炸（找 `(AbstractDataEntity)`，
已不存在），新 JAR 在老框架上也炸（找 `(BaseDataEntity)`，尚不存在）；同一份源码只改
pin 重编，两个产物各自只能跑在自己那一侧。第一个实例（`getContext()`）同理，只是当时
只有「老 JAR 撞新框架」这个方向被真实触发了。

**而第二个方向还多带一层：它是被放行之后才炸的。** 15 个官方模块统一把 `pom.xml` 的
pin 调到了 6.2.1，`plugin.yml` 的 `api-version` 却一个都没动，仍是 `620`。产物记的是
6.2.1 的描述符，声明的地板却是 6.2.0，而框架只看得到后者。结果：装了 6.2.0 的服务器
**加载成功**，然后在第一次数据读写时 `NoSuchMethodError`。11 个模块中招（剩下 4 个不碰
ORM，负向对照成立）。

Java 是惰性解析的，所以「装上去能起来」不构成证据——不碰数据路径的服主可以一直看不出问题。

**同一次提交里还有一个恰好相反的对照，值得一并记住。** 它把 `UltiToolsPlugin.getDataOperator`
的泛型上界从 `AbstractDataEntity` 换成了 `BaseDataEntity<String>`，但两版描述符
**完全相同**——上界被擦除，`T` 在描述符里早就是 `Class` / `DataOperator`：

```
6.2.0  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
6.2.1  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
```

这是前面那种情况的镜像：**改泛型上界破坏源码兼容而不破坏二进制兼容；改返回类型或参数
类型破坏二进制兼容而不一定破坏源码兼容。** 两者都不涉及移除，所以两者都绕过移除清单。

**但这条只对 `getDataOperator` 这个调用点成立，不要推广到整个模块。** 拿到
`DataOperator` 之后你几乎一定会调 `insert` / `update` / `exist` / `getById`，而那四个
的描述符是变了的。所以一个用了 ORM 的模块，**两个方向都会断**：

| 构建时 pin | 跑在 6.2.0 | 跑在 6.2.1+ |
|---|---|---|
| 6.2.0 | ✅ | ❌ `NoSuchMethodError`（找 `(AbstractDataEntity)`，已不存在） |
| 6.2.1 | ❌ `NoSuchMethodError`（找 `(BaseDataEntity)`，尚不存在） | ✅ |

实测取的是同一个模块的同一份源码，只改 pin 重编：针对 6.2.0 编译的产物在 6.2.1 上缺 3 个
符号、在 6.2.0 上缺 0 个；针对 6.2.1 编译的产物反过来，在 6.2.0 上缺 3 个、在 6.2.1 上缺
0 个。**对称的，两侧都不通。** 「跑得动但编不过」只描述了 `getDataOperator` 那一行。

### 这对你意味着什么

**pin 得低不等于安全。** [模块版本规范](https://dev.ultikits.com/zh/guide/advanced/module-versioning)
里说「编译 against 旧 API 不会因为够到了更新的东西而 `NoSuchMethodError`」——那句话仍然
成立，但它只排除掉了**一个方向的原因**。反方向的原因（框架自己改了描述符）会给你同一个
异常。

**而且这一类没有免费的修法。** 光说「重新编译并重新发布」是不够的，甚至是错的：如果
`pom.xml` 里的 pin 还停在 6.0.6，重新跑一遍构建仍然照着 6.0.6 的 class 文件生成**旧的**
描述符，产物在新框架上照炸。要真正修好，必须**把编译依赖抬到含新描述符的那个版本再重编**。

**但抬 pin 只做完了一半，而且是不被检查的那一半。** 这里有两个互相独立的数字，别把它们
当成一个：

| 数字 | 决定什么 | 谁在检查 |
|---|---|---|
| `pom.xml` 里的 `UltiTools-API` 版本 | 你的字节码记录**哪一版的描述符** | 没有人。它是 `provided`，不进 JAR，框架运行时看不到它 |
| `plugin.yml` 的 `api-version` | 声明的运行时**下限** | `PluginManager.isUltiToolsVersionCompatible`，这是唯一被检查的值 |

所以「pin 就是地板」是错的：抬高 pin 不会抬高地板。一个针对新框架编译、却仍然声明旧
`api-version` 的构件，会被老服务器**放行**，然后在第一次调用新描述符时炸掉——同一个
`NoSuchMethodError`，方向反过来。**两个数字必须一起动。**

**这不是假想。** 上面第二个实例就是这么发生的：15 个官方模块把 pin 调到 6.2.1，
`api-version` 全部留在 `620`，其中 11 个的产物因此声明了一个比自己真实需求更低的地板
（2026-08-16 已全部修正为 `621`）。**没有任何工具报过警**——构建是绿的，插件在 6.2.1 以上
的服务器上一切正常，只有恰好停在 6.2.0 的服务器会先加载成功、再在第一次数据读写时炸。

想自查的话，判据是「**产物实际引用了哪些符号**」，不是「pom 里写了什么」。把你的模块 JAR
和你在 `api-version` 里声明的那一版框架 JAR 都解开，用 `javap -p -c` 导出模块引用的
`com/ultikits/ultitools/**` 方法与描述符，再逐条对照框架 JAR 里 `javap -p -s` 的输出。
现成的脚本见 [issue #284](https://github.com/UltiKits/UltiTools-Reborn/issues/284)。

**但对不上不等于「`api-version` 低了」，有两个成因，修法相反。** 看那条对不上的符号里写的
是哪一版的类型：

| 缺失符号引用的类型 | 说明什么 | 怎么修 |
|---|---|---|
| **新**的（如 `BaseDataEntity`） | 产物比声明的地板新 | **抬 `api-version`**，pin 不用动 |
| **旧**的（如 `AbstractDataEntity`） | 产物比声明的地板旧，pin 停在老版本没跟上 | **抬 pin 并重编**，抬 `api-version` 没用，反而更错 |

第二种正是本节这个实例的另一侧：一个 pin 停在 6.2.0、却声明 `api-version: 621` 的产物，
引用的是 `insert(AbstractDataEntity)`，而 6.2.1 里没有这个描述符——地板再抬也不会让它
出现。**先看缺的是哪一代符号，再决定动哪个数字。**

于是完整的修法是：抬 pin、重编、**把 `api-version` 一并抬到对应的 API 级别**，并接受
由此带来的后果。

不能横跨两侧的，准确说是**那个直接静态调用了该方法的构件**——描述符是编译期写进调用点
的，所以一个调用点只能对上一侧。据此有三条路，成本递增：

1. **接受地板抬高**（默认选这条）。老服务器继续留在旧 JAR 上，新 JAR 只服务新框架。
2. **按框架区间出不同构件**。要维护两条发布线。
3. **写一层兼容垫片**：用反射调用（`getMethod("getContext").invoke(plugin)` 拿到
   `Object`，再反射调 `getBean`），或者按框架版本惰性加载不同的适配器实现。反射调用点
   不静态链接任何一版的返回类型，所以**同一个构件确实能同时跑在两侧**。代价是这段路径
   失去编译期检查、出错要到运行时才知道，且以后框架再改它你不会收到任何编译警告。

第 3 条真实可行，别因为前两条写在前面就以为它不存在；但它把一个编译期就能发现的问题
换成了一个运行期才暴露的问题。只有在**必须继续支持旧服务器**时才值得。

换句话说，本文别处说的「pin 落后是正常状态、不必因为落后本身去动它」在这一种情况下**不
适用**：那条讲的是没有理由时不要动 pin，而描述符变更正是一个理由。

至于多久要检查一次，取决于下面那条门禁有没有接线——**现在还没有，所以答案是「框架版本
号一变就重新验证，PATCH 也算」**：

- 本文上面写着 PATCH「不移除公开 API」。那句承诺覆盖的是**有意为之**的移除，因为它靠的
  是人先意识到自己在改 API。无意的描述符变更按定义不在任何排期里，**所以它同样可能出现
  在一个 PATCH 里**。这句话初写时只是推论，本节的第二个实例（6.2.0 → 6.2.1）已经证实了
  它：那是一个 PATCH。**「盯着 MINOR 就够了」是不成立的。**
- japicmp 门禁接线之后，PATCH 的二进制兼容才是被机器逐方法验证过的。到那时才可以只在
  跨 MINOR 时操心这件事。

### 这对我们意味着什么

人工流程挡不住这一类——它要求作者在改一个字段类型时就想到「这会改掉一个 public 方法的
描述符」。挡得住的只有机器逐方法比对描述符，也就是 japicmp 门禁（issue #216）。在它接线
之前，本文件对二进制兼容的承诺**仅限于有意为之的移除**；无意的描述符变更我们只能事后
记录，不能保证事前发现。

## 支持范围

| 项 | 值 |
|---|---|
| 服务端 | Paper（不支持 plain Spigot——代码全面使用 Adventure `Component`） |
| 构建 JDK | 21 |
| 字节码目标 | Java 8（`-source`/`-target`，非 `--release`） |
| `plugin.yml` 的 `api-version` | `1.19`（Bukkit API 层级，与上面两项无关） |
| 模块 `plugin.yml` 的 `api-version` | `620`（UltiTools API 层级，与 Bukkit 的同名字段无关） |

### 运行时依赖来自哪里

框架 JAR 里只打包两个库：obliviate-invs（GUI）、UniversalScheduler（调度）。
其余依赖一个都不在 JAR 里，而是走下面两条路之一：

| 投送方式 | 版本由谁决定 | 例子 |
|---|---|---|
| `plugin.yml` 的 `libraries:` 块，Paper 按坐标下载 | **本仓库** | Gson、MySQL Connector/J、HikariCP、Java-WebSocket、ByteBuddy、XSeries |
| Paper 服务端自身携带 | **服主所装的 Paper 版本** | log4j、Paper 内部实现 `libraries:` 用的 Maven resolver 及其依赖 |

**XSeries 在 6.3.0 里被移出了打包的 JAR。** 到 6.2.5 为止它还是被 shade 进去的——上面这句话
原来写的是「三个库」，XSeries 是其中之一。从 6.3.0 起它改为 `provided` 作用域，
走上表里的 `libraries:` 那条路投送，和 Gson、HikariCP 一样。如果你把本框架的 jar shade 进
自己的 uber-jar，XSeries 不会再跟着一起进去：如果你的模块用到它，需要自己声明
（`com.github.cryptomorin:XSeries:13.0.0`，或你自己钉的版本）。

这条分界决定了第三方安全告警该由谁修。命中第一类的，在本仓库钉版本是有效的修复；
命中第二类的，唯一的修法是**升级 Paper** —— 在 `pom.xml` 里怎么写都不会改变服务器上
实际加载的那个 jar，只会制造「已经修了」的错觉。

注意 Maven 的 `provided` 作用域**不是**这条分界：上表两类依赖在 `pom.xml` 里都声明为
`provided`。判断依据是「有没有出现在 `libraries:` 块里」，不是作用域。

建议服主跟进 Paper 的构建更新，安全构建尤其如此。

## 反馈

对本策略有异议，或你的模块受到上述移除影响，
请在 [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues) 提出。
