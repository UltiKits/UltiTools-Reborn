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

### Plugin base class

| Type / member | Removal announced in | Replacement | Downstream references (informational) |
|---|---|---|---|
| The six-argument `abstracts.UltiToolsPlugin(String, String, List, List, int, String)` constructor | 6.2.5 | The seven-argument constructor, passing `resourceFolderPath` explicitly (the six-argument overload hard-codes it to `<dataFolder>/pluginConfig/<pluginName>`) | 0 |

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
| `aop.CglibProxyFactory` | 6.3.0 | `aop.ProxyFactory` — identical constructor and `createProxy` signatures | 0 |

This entry is removed in the same release that announces it, which the policy above normally
does not allow. The justification is that the type could not work on any supported server: it
requires `--add-opens java.base/java.lang=ALL-UNNAMED`, a flag a Paper server does not set and
a plugin cannot add. Keeping it through a deprecation cycle would preserve an API that throws
`ExceptionInInitializerError` on first use. It had no downstream references. See issue #188.

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

The framework JAR bundles exactly three libraries: obliviate-invs (GUI), XSeries (cross-version) and
UniversalScheduler (scheduling). Everything else is not in the JAR and arrives by one of two routes:

| Delivery route | Version decided by | Examples |
|---|---|---|
| The `libraries:` block in `plugin.yml`, downloaded by Paper from coordinates | **This repository** | Gson, MySQL Connector/J, HikariCP, Java-WebSocket, ByteBuddy |
| Shipped by the Paper server itself | **The Paper build the server owner installed** | log4j, the Maven resolver Paper uses internally for `libraries:` and its dependencies |

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
| `aop.CglibProxyFactory` | 6.3.0 | `aop.ProxyFactory` —— 构造器与 `createProxy` 签名完全相同 | 0 |

本条在宣布移除的同一个版本里就被移除，这不符合上面的常规策略。理由是该类型在任何受支持的
服务器上都无法工作：它需要 `--add-opens java.base/java.lang=ALL-UNNAMED`，而 Paper 服务器
不会设置这个参数，插件也无法自行添加。让它走完废弃周期，只会保留一个首次使用就抛
`ExceptionInInitializerError` 的 API。它没有任何下游引用。详见 issue #188。

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

框架 JAR 里只打包三个库：obliviate-invs（GUI）、XSeries（跨版本）、UniversalScheduler（调度）。
其余依赖一个都不在 JAR 里，而是走下面两条路之一：

| 投送方式 | 版本由谁决定 | 例子 |
|---|---|---|
| `plugin.yml` 的 `libraries:` 块，Paper 按坐标下载 | **本仓库** | Gson、MySQL Connector/J、HikariCP、Java-WebSocket、ByteBuddy |
| Paper 服务端自身携带 | **服主所装的 Paper 版本** | log4j、Paper 内部实现 `libraries:` 用的 Maven resolver 及其依赖 |

这条分界决定了第三方安全告警该由谁修。命中第一类的，在本仓库钉版本是有效的修复；
命中第二类的，唯一的修法是**升级 Paper** —— 在 `pom.xml` 里怎么写都不会改变服务器上
实际加载的那个 jar，只会制造「已经修了」的错觉。

注意 Maven 的 `provided` 作用域**不是**这条分界：上表两类依赖在 `pom.xml` 里都声明为
`provided`。判断依据是「有没有出现在 `libraries:` 块里」，不是作用域。

建议服主跟进 Paper 的构建更新，安全构建尤其如此。

## 反馈

对本策略有异议，或你的模块受到上述移除影响，
请在 [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues) 提出。
