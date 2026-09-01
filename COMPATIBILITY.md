# Compatibility and Versioning Policy

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
can be backfilled; the starting point cannot. The registry (`compatibility/DEPRECATIONS.md`) names that version for every entry.

These two conditions are the complete basis for removal. Downstream usage counts are no longer part
of it. The usage figures recorded for each removal (in `compatibility/records/6.3.0.md`) are
informational: they tell you which removals carried real migration cost, but "zero references"
only proves that nobody in the repositories we can see uses it, not that no third party outside
the organization does.

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

Six removals in the 6.3.0 cycle used this exception: `aop.CglibProxyFactory` under clause 1
(`--add-opens java.base/java.lang=ALL-UNNAMED` is not a flag a Paper server sets, so the class
throws `ExceptionInInitializerError` on first use — the constructor throwing on every call is
itself the reproduction); `aop.ProxyFactory.createProxy(T)`/`createProxy(Class<T>, T)` and
`aop.AopProxyBeanPostProcessor` under clause 2 (neither ever reached a tagged release, or shipped
but had zero callers in `src/main`); `annotations.Propagation.NESTED` under clause 2 for a
different reason, controllability rather than impossibility; `PluginManager`'s seven-argument
`register(...)` overload under clause 1 (proven non-functional on every release since 6.2.0); and
`ListenerManager.registerAll(UltiToolsPlugin, String)` under clause 2 (zero callers anywhere in
`src/main` at removal time). Full evidence for each is in
["Same-release exceptions applied in 6.3.0"](#same-release-exceptions-applied-in-630) below and in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

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
([Chinese translation](https://dev.ultikits.com/zh/guide/advanced/module-versioning.html)).

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

The removal list is generated at `mvn verify` from the `@Deprecated(forRemoval = true)`
annotations in the source, cross-checked against the japicmp binary-compatibility report so a
disagreement between the two fails the build (`DeprecationRegistryGenerator` produces
`compatibility/DEPRECATIONS.md`; see `compatibility/deprecations.json` for the machine-readable
form). This document no longer carries the table itself — three artifacts now split the job:

- **This document** — policy: what removal means, when an API becomes eligible, the two
  same-release exceptions, and permanent lessons.
- [`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md) — the generated, cumulative
  registry: every deprecated/announced/removed member, its `since`, its replacement, and its
  status.
- [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md) — the 6.3.0 cycle's
  behavioural record: what each removal actually deletes, its replacement, and — per this
  document's own downstream-diagnostic design — exactly what an un-recompiled downstream JAR
  sees.

Packages and types marked `@ApiStatus.Internal` are not part of the removal list even when
japicmp's exclusion list carries an entry for them — japicmp reads bytecode, not `@ApiStatus`,
so an internal-only removal still needs an exclusion entry to keep the binary-compatibility gate
green, but it was never public API and its removal is not a compatibility event. See
`compatibility/records/6.3.0.md`'s WIRE-17 entry for a worked example.

### Same-release exceptions applied in 6.3.0

Six removals used the [same-release exception](#exception-removal-in-the-same-release-that-announces-it)
above instead of waiting a full MINOR:

- `aop.CglibProxyFactory` — clause 1, proven non-functional (issue #188).
- `aop.ProxyFactory.createProxy(T)` / `createProxy(Class<T>, T)` and
  `aop.AopProxyBeanPostProcessor` — clause 2, never reached a tagged release or shipped with zero
  callers (issue #190).
- `annotations.Propagation.NESTED` — clause 2, on controllability rather than impossibility:
  savepoint behaviour depends on whichever `sqlite-jdbc` version the server's own Paper build
  happens to ship, which this project can neither pin nor test across.
- `PluginManager`'s seven-argument `register(Class, String, String, List, List, int, String)` —
  clause 1, proven non-functional on every release since 6.2.0 (Phase 1 D-15).
- `ListenerManager.registerAll(UltiToolsPlugin, String)` — clause 2, zero callers anywhere in
  `src/main` at removal time.

Full reasoning and evidence for all six live in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

### Measurement notes carried forward from the 6.3.0 survey

How reference counts were measured (informing which removals were low-risk, though never the
basis for removal itself): on 2026-08-14, across 17 module repositories, 4 tooling projects and
Libraries under the UltiKits organization, covering 310 Java files, excluding test directories
and build output, counting only imports and `extends`.

The `6.2.1` starting point used throughout the 6.3.0 removal cycle is a deliberately conservative
choice, not a consequence of 6.2.0 being unverifiable. 6.2.0 was published to Maven Central; it
simply has no corresponding git tag, though it does have a release commit in the repository
history (`0286e26 release: UltiTools-API v6.2.0`) that can be checked. Setting the start at the
later 6.2.1 only lengthens the deprecation period and favours downstream, so it stays.

One verification note worth recording: the release list for this project is Maven Central's
`maven-metadata.xml`, **not `git tag`** — there is no `v6.2.0` among the tags. Inferring "this
version was never released" from the tag list produces a wrong conclusion in this repository.

**If your module references any removed API, please open a
[GitHub issue](https://github.com/UltiKits/UltiTools-Reborn/issues).** The registry's reference
counts are not the basis for removal, and do not support a conclusion that nobody is using
something.

## Migrating off `AbstractCommandExecutor`

`abstracts.AbstractCommandExecutor` was the only removed API in 6.3.0 with real downstream users
(15 files across 6 repositories, measured). It has been removed, ahead of those repositories' own
publication — see [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md) for the full
reasoning and the maintainer's decision to proceed.

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
`exist(WhereCondition[])`) kept their descriptors. `AbstractDataEntity` itself was not deleted at
the time this section was written, so this case could not go on the removal list yet — that has
since changed: `AbstractDataEntity` was deleted in 6.3.0 by plan 07-13 (GEN-04), and the descriptor
history recorded above (6.2.0 → 6.2.1) remains accurate for servers running those earlier versions.
See [the 6.3.0 removal record](records/6.3.0.md#recorded-instance-abstractdataentity-is-gone-basedataentity-now-owns-its-id-field-directly-gen-04-630)
for what an un-recompiled JAR referencing `AbstractDataEntity` now sees.

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
