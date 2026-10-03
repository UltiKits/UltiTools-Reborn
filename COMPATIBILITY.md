# Compatibility and Versioning Policy

This document explains what the version numbers of `com.ultikits:UltiTools-API` mean, how
deprecation and removal work, and which removals are currently scheduled. It is written for
downstream module authors.

## Config layer (6.3.0)

This section is the configuration migration contract **as of v6.3.0**. The new public extension
point is `com.ultikits.ultitools.config.convert.ConfigConverter<T>`, discovered with
`@ConfigConverterFor`; the document tree and writer remain internal implementation details.
Third-party modules must rebuild if they used the removed accessor, and register converters for
unsupported declared field types before configuration initialization.

### Rendering and save fallback

The internal config storage layer renders the whole YAML document through SnakeYAML, preserving content, comment text and key
order. Its existing line-terminator, BOM, final-newline and supported indentation-style rules remain in effect. Operator layout
may be normalized: aligned inline comments, flow spacing, extra spaces after a colon, document markers, mixed indentation and
trailing spaces are not byte-preservation guarantees. Changed anchored documents expand aliases and merge keys from their plain
values while retaining comments on surviving keys. Comments on individual list items are kept only while the list keeps its length — the same as Bukkit, which keeps none. The storage API signatures are unchanged.

Saving first attempts a forced same-directory temporary file and atomic replacement. Only an unsupported atomic move, EBUSY,
EXDEV, or a permission/read-only refusal to create the temporary file allows the narrow fallback: exclusively create `<file>.bak`,
copy and force the existing target's bytes, then overwrite and force the existing target in place. If a backup already exists,
it is refreshed from the current target through a forced same-directory temporary and atomic replacement before the target is
opened. A backup creation/write/force or refresh rename failure refuses the save before the target is touched and preserves the
previous backup. No old-backup restoration or validation of the current target is implied by this refresh. Other staging or move
failures refuse the save. A fallback attempt logs one warning identifying the target, backup, cause and outcome. Symbolic links
remain links, with the backup beside the resolved target.

After an in-place write begins, a failure may leave a partial target, but its complete forced backup remains. That backup is
removed only after the next successful strict UTF-8 storage load; unreadable or unparseable files keep it. Backup cleanup is best
effort and cannot turn a successful load into a failure. No automatic restoration policy is introduced.

### Declared types, whole map keys and persistence

`AbstractConfigEntity` uses the document, converter registry and atomic writer for initial defaults,
explicit saves and panel updates. Typed collections resolve their full inherited generic types:
convertible values bind, invalid collection/map elements are skipped with a located warning, and an
invalid field value falls back to its initially declared default. Invalid reload values use that
default too; a missing reload key instead retains its live field and is not added to the file.
Warnings name the file, key and failed position/type; secret-shaped values and nested credentials
are redacted. Unsupported declared types fail preflight before the file is read or created; register
`@ConfigConverterFor` or declare a supported plain-data shape. The built-in Bukkit serialization
fallback requires a registered alias; registered custom converters keep ownership of their types.
For every value `x` of the declared type whose collections and arrays contain no null element,
`fromPlain(toPlain(x))` equals `x`. Typed collections (including `List<Object>`) and reference arrays
(including `Object[]`) omit null elements on write with one located warning per field. Reading keeps
its existing skip/warning behavior; plain data in a declared `Object` slot is unchanged.
Null map values and null whole fields still round-trip. For every canonical
plain value `p` emitted by the converter (`p = toPlain(x)`), `toPlain(fromPlain(p))` equals `p`.
A converter may accept noncanonical input `q`; `toPlain(fromPlain(q))` is its canonical form, and
normalization is stable: `fromPlain(toPlain(fromPlain(q)))` equals `fromPlain(q)`. Approved coercions
(number to String, numeric text to int/float, `"false"` to boolean and duplicate elements to a Set)
remain unchanged. 6.2's default parser stored every list element as text, so a 6.2-saved
`List<Integer>` reads `- '60'`; 6.3.0 binds it as the number 60 and writes `- 60` the next time
that file is saved by its module or a panel edit. Loading, reloading and the shutdown check never
rewrite such a file on their own. Equality is semantic value comparison, not object identity; numeric plain values
compare by value. Reload merges and panel leaf edits rely on forward equality. A converter that adds
a value during reading without undoing that change during writing violates this contract.

Whole map keys, including `g.m`, `o.O` and `wave.`, are supported as of 6.3.0.
`@ConfigEntry.path` still splits at every dot: `chat.aliases` selects nested settings, whereas a
key `o.O` inside the bound map is one whole key. These are different path conventions. Legacy 6.2 files in which Bukkit
split a dotted key into nested mappings are read as they are, never automatically merged or renamed.
For a `Map<String, String>` the wrongly shaped nested value is skipped with a warning.
Explicit `null` is stored and binds to reference fields (primitive null is a mismatch). UUIDs, enums,
sets and registered Bukkit values use converter plain output, not Java class tags. A Bukkit value
loaded into an `Object` slot remains a plain map. Unknown runtime objects refuse saves without
changing the file.

Panel JSON uses the same conversion path as file binding and saves: integral numbers within the long
range become plain `Long`; integers beyond the long range are kept exactly as `BigInteger`.
Fractional numbers keep the existing `Double` route, objects ordered maps and arrays lists before typed conversion.
A panel write persists only touched fields; unrelated unsaved code edits remain dirty. Validation
runs before any missing-key or panel persistence. Snapshots track successful effective values,
separately from the raw document and byte fingerprint. Reordering a map is dirty, but an order-only
save acknowledges its new effective order without changing operator file order, comments or bytes.

An explicit save compares its candidates with the current disk document, not only the saved
baseline, so it may replace an operator's changed value even when the entity was clean. Semantic
no-op saves invoke no writer and preserve bytes and modification time. Edited saves use the full
emitter described above, retaining untargeted data, key order and comment text subject to the list-item
length limit above, while allowing layout normalization. A failed write never acknowledges the pending effective values as saved.

Unreadable, unparseable and non-UTF-8 files are protected on every entity write path. Initial load
keeps declared defaults; failed reload keeps running fields. One SEVERE names the file and safe cause.
Explicit save does not clear protection; only a later successful load permits writes again.
Parser diagnostics expose only numeric line/column metadata, never source snippets or scalar values.

Exactly one `{key}` annotation comment (surrounding whitespace ignored) is framework-owned. Every
load and write refreshes existing token comments from the module catalogue in the current language;
operator block comments on token entries are replaced, while literal-entry comments are kept.
Catalogue lookup failure keeps the literal token and warns once per entry per load. Comment text
uses the document's YAML line-break/control-character sanitation, including the panel payload.
A failed comment-only rewrite does not fail load or discard bound values: the entity stays dirty
until persistence succeeds. No-op comparison includes these authoritative comments.

A successful entity write that replaces an operator-edited value warns once, naming the file and
only the keys actually replaced, never values. Explicit saves, partial panel writes and shutdown
share this reporting. Comment/layout-only edits, equal candidate values and failed writes do not
claim an overwrite. The check and write run under the same entity monitor.

### Removed mutable configuration accessor

`AbstractConfigEntity#getConfig()` is removed under the **6.3.0 one-time carve-out**.
The accessor worked in 6.2.5: neither the non-functional nor the never-used same-release exception
applies. This is an explicit maintainer-authorized removal, not evidence that the accessor was
broken or that nobody used it.
Use `isPresentInFile(String)` for presence in the last successfully loaded document: undeclared keys
and explicit null count as present; unreadable/unparseable loads report false. Paths split at every
dot like `@ConfigEntry.path`, so a whole map key containing a dot is not addressable through this
method. Read or mutate declared fields and call `save()` instead of mutating Bukkit storage.
Official callers measured in UltiEssentials `RemovedConfigKeys.java:83` and UltiRemoteBag
`RemovedConfigKeys.java:91` migrate in the module batch; third-party usage is unknown.
An unrecompiled caller invoking the removed accessor sees `NoSuchMethodError`. See the removal
record in `compatibility/records/6.3.0.md`; all other public/protected entity signatures are retained.

### Registration batches

As of 6.3.0, package/directory configuration registration buffers initialization writes until every
entity binds and validates. A refused batch creates no file and changes no existing file, including
missing-key and language-token comment rewrites. Once accepted, files persist independently; an I/O
failure preserves earlier successful files and protects the failed entity until a successful reload.
Standalone registration still writes immediately. Framework-internal initialization bridges are not
a module transaction API.

### Superseded-copy configuration ordering

As of 6.3.0, before framework construction of an identifiable newer module copy, the framework
reads its own JAR plugin.yml using the constructor's main/version defaults and existing version
comparator, and saves the loaded old copy's dirty configurations in sorted file-path order.
A failed or protected old save refuses incoming construction and retains the old active copy.
The old copy is not unloaded before incoming activation succeeds. If identity is unavailable
before construction, or an already-constructed instance is supplied, there is no late old save:
successful supersede warns once with the dropped file/entry keys (never values), then releases
old configuration entities. Failed incoming construction, compatibility, assembly or activation
releases only refused incoming configuration owners; existing owners remain registered.
No constructor deferral, early unload or public storage/transaction API is introduced.

### Configuration registry server-thread confinement

As of 6.3.0, all ConfigManager registry operations are server-thread confined while a server
runs. Direct off-thread register/registerAll/saveAll/unregisterAll/reloadConfigs calls warn once
and do no work. Getters, toJson/getComments and both loadFromJson overloads warn once and throw
IllegalStateException, rather than returning a misleading empty result or successful write.
The no-server case remains supported. getAllConfigEntities preserves null for an unregistered
module and otherwise returns an unmodifiable detached map; its entities are not copied.
Panel update, upload-write and reconnect upload-read callbacks queue their whole operation and
return immediately off-thread, responding only after the queued operation runs. Registry guards
precede entity monitors. Multi-file panel transactions retain deterministically ordered touched-entity
monitors through validation, preparation, commit and acknowledgment or rollback; direct single-entity
persistence uses its existing monitor. No separate manager lock or blocking scheduler wait is added.
Async registry callers must schedule on the server thread. Existing public method signatures and
panel response fields/types are unchanged.

### Configuration release and shutdown save

As of 6.3.0, module unload releases that module instance's configuration registry entry even
when its unload hook or context close throws. Later shutdown saves neither retain nor write
unloaded entities. PluginManager.close saves all registered dirty configurations before unloading
any module. After each module's unload hook and container `@PreDestroy` callbacks, shutdown saves
that same owner's dirty configurations again before releasing the owner, even when cleanup throws.
The final save retains protected-file refusal and per-entity failure isolation. Normal runtime unload itself does
not save; superseded-copy preparation follows the separate preconstruction rule. Public existing
signatures are unchanged; no module migration is required.

### Configuration init and reload thread contract

As of 6.3.0, configuration init, reload, manager reloadConfigs and module reloadSelf refuse
calls off the server thread while a server runs. One warning names the module, entity path when
applicable and caller thread; no field/file/lifecycle action occurs. Checks precede entity monitors.
The no-server test harness case remains allowed. Public signatures, including final reloadSelf,
are unchanged; async third-party callers must schedule their reload on the server thread.

### Reload merge rule

As of 6.3.0, reload compares the last effective disk baseline, current serialized fields, and
incoming disk values. Memory-only changes survive and stay dirty; disk-only changes are adopted.
Maps merge recursively by whole keys; lists and scalars are atomic. Conflicts take the file's value
and warn with the located key and discarded value, redacting secret-shaped values. Absent map keys
and explicit null differ. Missing whole declared fields retain their live values with the inherited
declared-default baseline. This planner-selected file-wins policy can be overturned by the maintainer.
Unreadable/unparseable reloads keep live values and protect the file as before.

### Panel edits inside map entries

As of 6.3.0, a changed panel leaf inside a declared map setting is applied through that field's
full declared-type converter. Real whole keys containing dots remain whole. A path with multiple
readings refuses with every reading named; an unknown changed key is explicitly refused. Any refused
changed key refuses the whole payload, naming all refused paths. Unchanged displayed leaves are not
edits, including undeclared operator keys and ambiguous paths. Previously these map-entry edits were
silently ignored. Leaf edits preserve unrelated pending in-memory siblings and their dirty state,
and preserve independently edited disk siblings without acknowledging them. Full declared-field
conversion and validation still run: the live field is serialized, only targeted plain leaves are
patched, and the registry binds that candidate once. The round-trip contract preserves untouched
bound siblings without any type-specific map/object merging. Only targeted leaves are persisted.
The existing `config_update_response` shape is unchanged.

### Multi-file panel persistence

As of 6.3.0, `ConfigManager#loadFromJson(String)` validates every touched configuration, stages
all changed files, and only then replaces them. Entity baselines and raw acknowledgments advance
after every commit succeeds. An in-process staging/replacement refusal restores attempted targets
and the complete prior entity state, removes staged temporaries, and rethrows the original error.
Recovery errors are attached as suppressed exceptions; persistently unavailable storage can prevent
restoration and is not falsely reported as a successful rollback. Semantic no-ops write nothing.
This is not a crash-safe multi-file transaction: a JVM crash between moves remains deferred to #545.
The panel message shape and public `loadFromJson` signatures are unchanged. Internal staged-entity
coordination bridges are not a module transaction API.

### Migrating legacy parsers to converters

The six deprecated announcements are `ConfigEntry#parser()`, `interfaces.Parser`,
`interfaces.ObjectConfigSerializer`, and `interfaces.impl.pasers.ConfigParser`,
`DefaultConfigParser`, `StringHashMapParser` (the published package spelling `pasers` is retained).
Their first release carrying `@Deprecated(since = "6.3.0", forRemoval = true)` is 6.3.0; the next
MINOR, 6.4.0, is the announced removal version. They are retained in 6.3.0, not deleted now.
An explicit non-default `parser = X.class` still selects the frozen legacy adapter. Its detached
input is emitted under one key and loaded by a fresh Bukkit `YamlConfiguration#get`, retaining
6.2 section-based dotted-key splitting and Bukkit `==` alias deserialization at the root and inside
lists/maps, including explicitly parsed `Object` fields. Registry converters do not hydrate these
legacy inputs; legacy output still crosses the plain-data boundary and retains boxed widening.
Bukkit's own alias restrictions remain: integral Vector coordinates deserialize to null, whereas
fractional coordinates deserialize normally. Leaving `parser` at `DefaultConfigParser.class` selects the new registry,
not that legacy class. Third-party subclasses retain their old executable behavior, not the new
built-in collection semantics. The six announcements are indexed in
[`compatibility/DEPRECATIONS.md`](compatibility/DEPRECATIONS.md).

For a custom type, remove `parser = ...` from the field and place a public top-level converter
with a public no-argument constructor in the module's `@UltiToolsModule.scanBasePackages`.
The package scanner discovers top-level classes only, not nested converter classes. Converters are discovered before configuration
construction and are not IoC beans: do not depend on injected services or constructor side effects.
Two registrations for the same exact class refuse load naming both converters. Lookup uses an
explicit non-default legacy parser first; otherwise the module's exact registration, then its
non-exact superclass/interface registrations, then the framework's registrations in the same
order, generic collections/arrays/enums/Object, and registered Bukkit serialization fallback.
`exact = true` prevents a registration from serving subclasses.

Here is a map-shaped value migration. Put the value/parser/field members in a module class named
`MigrationExample` in package `example.config` (imports go before the outer class). Put the converter
in its own public top-level `TokenConverter.java` in the same scanned package, as shown separately. `Token`
must provide semantic `equals`/`hashCode` in production so round-trip comparisons mean value equality.
The converter deliberately accepts only the one-key shape it can reproduce; accepting extra keys
and dropping them would violate `toPlain(fromPlain(p)) == p`.

```java
import java.util.Map;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;

// Before: inherits the legacy reflective writer and section reader.
public static class Token {
    public String text;
    public Token(String text) { this.text = text; }
    @Override public boolean equals(Object other) {
        return other instanceof Token
                && java.util.Objects.equals(text, ((Token) other).text);
    }
    @Override public int hashCode() { return java.util.Objects.hashCode(text); }
}
public static class TokenParser extends DefaultConfigParser {
    @Override public Object parse(Object raw) {
        Map<?, ?> map = (Map<?, ?>) super.parse(raw);
        return new Token((String) map.get("text"));
    }
}
@ConfigEntry(path = "token", parser = TokenParser.class)
private Token oldToken = new Token("hello");

// After: delete the old field/parser and use this declaration/converter.
@ConfigEntry(path = "token")
private Token token = new Token("hello");

```

```java
// TokenConverter.java: a separate top-level source file.
package example.config;

import java.util.LinkedHashMap;
import java.util.Map;
import example.config.MigrationExample.Token;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConfigConverterFor;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;

@ConfigConverterFor(Token.class)
public class TokenConverter implements ConfigConverter<Token> {
    public TokenConverter() { }
    @Override public Object toPlain(Token value, ConversionContext ctx) {
        if (value == null) { return null; }
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("text", value.text);
        return plain;
    }
    @Override public Token fromPlain(Object plain, ConversionContext ctx)
            throws ConversionException {
        if (plain == null) { return null; }
        if (!(plain instanceof Map)) { throw failure(ctx); }
        Map<?, ?> map = (Map<?, ?>) plain;
        if (map.size() != 1 || !map.containsKey("text")
                || !(map.get("text") == null || map.get("text") instanceof String)) {
            throw failure(ctx);
        }
        return new Token((String) map.get("text"));
    }
    private ConversionException failure(ConversionContext ctx) {
        return new ConversionException("Expected only a text key containing text or null",
                ctx.file(), ctx.path(), ctx.declaredType());
    }
}
```

The plain boundary permits null, strings, booleans, `Integer`, `Long`, `BigInteger`, `Double`,
lists and string-keyed maps of those values. Return no Bukkit section, arbitrary Java bean or
Java class tag. Use `ctx.toPlain(nested)` / `ctx.fromPlain(nested, declaredType)` for recursive
conversion. A non-plain runtime value in an `Object` slot uses its runtime converter on write;
already-plain values stay plain. Integer narrowing is exact and range checked. Float accepts a
decimal only when `Float.toString(parsedFloat)` prints the same decimal value (scale does not
matter): `0.03` and `1.50` are accepted, `0.100000001` is not. Float output uses that printable
decimal as a plain `Double`. This is not a promise of exact binary representation for decimal floats.

### Shutdown and known limits

Shutdown saves dirty registered entities before module release. An operator-only disk edit does
not make a clean live entity dirty and survives shutdown untouched; a pending code change is saved
and may replace operator values with the warning above. A partial panel save cannot acknowledge an
unrelated pending field. Async module field mutation itself is not protected by registry confinement:
module authors must arrange server-thread mutations. Initialization batches and panel transactions
are different: accepted initialization files persist independently, while the panel stages all
touched files with in-process rollback.

Known limits remain explicit, not guaranteed away: [#578](https://github.com/UltiKits/UltiTools-Reborn/issues/578)
tracks out-of-threat-model storage findings (special anchored containers, alias-comment ownership,
complex symlink paths and Unicode style-offset cost);
[#580](https://github.com/UltiKits/UltiTools-Reborn/issues/580) tracks refusal of a valid block anchor
with a comment before its first child key. Direct alias token comments can affect the anchor's
comment and cause repeated writes. No general alias-preservation guarantee or automatic backup
restoration is claimed. [#545](https://github.com/UltiKits/UltiTools-Reborn/issues/545) remains the
separate crash-safe multi-file transaction limit.

### 中文补充：6.3.0 配置层迁移

- 新扩展点是 `ConfigConverter<T>` 和 `@ConfigConverterFor`，转换器必须是扫描包内带 public 无参构造器的 public 顶层类；扫描器不发现嵌套转换器。它不是 IoC bean。未知声明类型在读取或创建文件前拒绝模块加载；官方 UltiRecipe 的配方对象需要模块自己的转换器。
- 注解 `path` 中的点仍表示嵌套路径；映射里的 `g.m`、`o.O`、`wave.` 从 6.3.0 起按完整键保存。6.2 已经拆开的文件原样读取，不会自动合并。完整泛型参与绑定；无效集合元素跳过，无效字段用声明默认值，缺失的重载字段保留运行值。允许 null 的引用类型可往返；`Object` 中的 Bukkit 序列化对象读取后仍是普通映射。
- 保存内容、注释文字、键顺序和支持的文件风格；有修改时整份经过 SnakeYAML 输出，运维排版可以规整。语义无变化不写文件，字节和修改时间不变。浮点数按最短可回读十进制判断，不要求二进制精确。
- 先临时文件、force、原子替换；仅已允许的原子替换/临时创建拒绝才走备份后原地写。已有 `.bak` 先从当前文件刷新并原子替换，之后才打开目标。失败保留备份，只有成功严格加载当前文件才清理；不自动还原，不保证多文件崩溃事务。
- 不能读取、不能解析、非 UTF-8 文件不会被任何实体写入路径覆盖；初次失败用默认值，重载失败保留运行值。成功加载才解除保护。单独 `{key}` 注释归框架所有，按模块当前语言目录更新；字面注释保持。成功覆盖运维值时一条警告只列文件和键，不列值。
- `getConfig()` 在 6.2.5 确实可用，不能冒称符合两个同版删除例外；维护者通过 6.3.0 一次性 carve-out 删除它。改用 `isPresentInFile` 查询上次成功加载时的存在性，修改声明字段后 `save()`。两个已知官方调用在 UltiEssentials 与 UltiRemoteBag；第三方用量未知。
- 六个旧解析器相关声明在 6.3.0 首次带 `forRemoval`，公告 6.4.0 删除。显式非默认 parser 暂时保留冻结的旧行为；默认 parser 改走注册表。迁移示例见上方，转换器必须满足两条互逆等式，不能单向加值或悄悄丢字段。
- 注册批次验证完成才开始独立写文件；面板批次先验证并暂存全部文件，在进程内失败时回滚，持久存储故障可能阻止恢复。面板唯一映射路径走整字段类型转换，歧义和未知变更拒绝整个请求；无关内存/磁盘兄弟项保留。
- 重载三方合并，内存独有改动保留且仍脏，磁盘独有采用，冲突磁盘胜。仅磁盘该映射未变时保证内存顺序保留，不写文件。初始化、重载和注册表在服务器主线程执行；异步面板回调整体排队，不能阻塞等待。
- 可以提前识别的新副本先保存旧副本配置再构造；失败保留旧副本。不能识别时成功替换后只警告丢弃的键，不事后保存。卸载释放实体，关闭先保存后释放。已知限制 #578、#580 和多文件崩溃限制 #545 仍存在。

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

**6.3.0 is a one-time carve-out.** 6.3.0 is this project's first stable release. For 6.3.0 only,
this exception was applied to other breaking changes as well as to removals, and none of the changes
recorded as uses of it required the changed API to have carried `@Deprecated(forRemoval = true)`.
The evidence recorded for those changes is kept as it was applied during the 6.3.0 cycle. It does
not always match the clauses' wording above, and clause 2 in particular was assessed in more than one
way. From the release after 6.3.0 onward, the exception, including both clauses' evidence
requirements, applies strictly as written above.

Each removal that relies on this exception names in its own entry which clause it used and the
evidence for it. For 6.3.0, every removal that relies on it is in
["Same-release exceptions applied in 6.3.0"](#same-release-exceptions-applied-in-630) below — read
that section through rather than reading a number off it, because it carries a list of removals and
then a further entry after that list — and each is recorded in full, with the measurement behind it,
in [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md). No total is stated here: a
count kept away from the list it counts has nothing holding it honest, which is how the count that
used to sit in this paragraph went stale.

That section lists removals only. The exception as written covers removals, but under the
one-time 6.3.0 carve-out above, changes that are not removals also cite its clauses in their own
entries, and they are not listed there: `UltiToolsPlugin#unregisterSelf()` and `#reloadSelf()`
becoming `final` (see the third
occurrence under
[Binary incompatibilities the removal list cannot cover](#binary-incompatibilities-the-removal-list-cannot-cover)),
and `GuiRenderer.initialize`'s first parameter changing from `Widget` to `Supplier<Widget>`. Their
cited clause and evidence are recorded in
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

- **This document** — policy: what removal means, when an API becomes eligible, the same-release
  exception, and permanent lessons.
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
`compatibility/records/6.3.0.md`'s WIRE-17 entry for a worked example; `manager.ServerPropertiesManager.SetAllResult`'s
public constructor widening from five `List<String>` parameters to six (Gate-2, 6.3.0) is the same
pattern — a same-release, internal-only signature change recorded there for japicmp traceability
only, not because it is itself a compatibility event.

### Same-release exceptions applied in 6.3.0

The removals in the nine entries below used the
[same-release exception](#exception-removal-in-the-same-release-that-announces-it) above, on the
terms of its one-time 6.3.0 carve-out, instead of waiting a full MINOR:

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
- `listeners.EnhancedPlayerEventListener` — clause 1, proven non-functional: it carried
  `@EventListener`, nothing registered it, and all seven of its handlers were measured never to
  fire on a real 1.21.4 server (issue #387).
- `manager.LogStreamManager.pauseLogStream(String)` / `resumeLogStream(String)` — clause 1, proven
  non-functional: `LogStreamManager` auto-subscribes a permanent, never-paused `"auto"` client on
  every WebSocket connect, so the delivery-suppression check these methods drove could never
  observe "no active subscriber" for any real panel session on any released version — reproduced
  by a test that pauses a distinctly-named client while `"auto"`'s entry survives untouched, which
  still delivers (issue #434, maintainer decision D-19). Removed rather than fixed because a true
  per-viewer pause needs Worker-side viewer identity this framework does not have and is not
  authorized to add here; the framework now rejects `pause`/`resume` outright instead.
  **Addendum (D-20, issue #468):** the same reasoning applies to `startLogStream(String, String)` /
  `startLogStream(String)` / `stopLogStream(String)` — measured end to end, the shipped frontend
  only ever toggles `start`/`stop` from its own button without gating rendering on the response,
  the Worker's REST log-stream endpoint is a stateless relay minting a disposable `clientId` per
  call with no per-browser stream state, and `stopLogStream` mutated only bookkeeping nothing on
  the delivery path ever consulted — `stop` never actually stopped delivery for any real client on
  any released version. `start`/`stop` are now rejected the same way as `pause`/`resume`; the
  now-inert `isStreaming()`/`getSubscriberCount()` accessors are removed alongside them, and the
  `status` action is answered with a redefined, genuinely-honest set of fields instead (no
  behavioural contract existed for `status`'s exact response shape, so this is not itself a
  removal).
- `UltiTools#getEconomy()` — clause 2, public and zero measured callers anywhere in this
  framework's own `src/main`, all sixteen product modules, and the external example (issue #451).
  It was also the sole cause of a total bootstrap crash on a server with no Vault plugin
  installed: reflecting over `UltiTools`'s declared methods (done the instant the core plugin
  bean is registered) eagerly resolved this accessor's `net.milkbowl.vault.economy.Economy`
  return type, which is absent from the classpath when Vault is absent. Replacement:
  `EconomyUtils.getEconomy()`, on the pre-existing `EconomyUtils` façade. No module calls that
  accessor: measured 2026-09-17 on the `origin/master` of every `Modules/` repository and of
  `Tooling/UltiTools-External-Example`, `EconomyUtils.getEconomy()` has **0** references, while the
  four modules that use `EconomyUtils` at all (`UltiEssentials`, `UltiKits`, `UltiMenu`,
  `UltiRemoteBag`) call its wrapper methods — `isAvailable`, `format`, `getBalance`, `withdraw`,
  `has` — instead.
- `abstracts.gui.declarative.widgets.Container.Builder.background(IconWrapper)` /
  `Container.getBackground()` and `GridView.Builder.rows(int)` / `GridView.getMaxRows()` — clause 2,
  zero callers at removal time (plan 05-13). All four were public in the released 6.2.5. Each pair
  did return through its getter the value its builder method stored, but no framework code consumed
  that value, and no downstream code called any of the four: measured 2026-09-17 on the
  `origin/master` of every `Modules/` repository and of `Tooling/UltiTools-External-Example`, their
  names have **0** hits and no file imports anything under `abstracts.gui.declarative`, against **9**
  imports of the imperative `abstracts.gui` pages in the same search. Their package carries
  `@ApiStatus.Experimental` and neither pair was ever `@Deprecated(forRemoval = true)`, which is why
  the generated removal list does not carry them. See
  [their records entry](compatibility/records/6.3.0.md#recorded-instance-two-declarative-gui-builder-method-pairs-are-removed-rather-than-given-a-guessed-implementation-d-09-630).

Full reasoning and evidence for every entry above live in
[`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md).

**One further exception, added by plan 16-09 (D-17, root-cause group "cloud session," part 2 of 3
for issue #298) after the list above:** `utils.CloudAuthManager`'s fine-grained public statics —
`loadSavedToken()`, `refreshToken(String)`, `saveToken(TokenEntity)`, `clearToken()`,
`currentCredentialGeneration()`, `invalidateCredentialOperations()`,
`commitTokenIfCurrent(TokenEntity, long)`, `getCurrentToken()`, `hasValidToken()`,
`requestMagicLink(Consumer<String>)`, both `startPolling(String, Consumer<TokenEntity>)` overloads,
`startTokenRefreshScheduler()`, `stopTokenRefreshScheduler()`, `stopPolling()`, and the class's own
implicit public no-arg constructor — plus `utils.PluginInitiationUtils#loginWithToken(TokenEntity)`
and `#activateCloudIfCurrent(long)` — clause 2, zero external callers. Measured 2026-09-14 across
every module repository under `Modules/` (the 15 active modules, the discontinued `UltiBot`, and
the non-product `ultikits-module-parent`) plus `Tooling/UltiTools-External-Example`: `grep -rlI
"CloudAuthManager" --include=*.java` and the same for `"PluginInitiationUtils"`,
`"loginWithToken"`, and `"activateCloudIfCurrent"` each return **0** files across all 18
repositories, against a control query for `"UltiToolsPlugin"` over the same roots returning **173**
files (proving the search itself works — a bare zero is not evidence on its own). Separately,
`strings`-scanning the one downstream consumer jar available locally
(`UltiTools-External-Example-1.0.0.jar`) for the literal string `CloudAuthManager` also returns
**0**, against a control string (`UltiToolsAPI`, the class that jar's `onEnable()` actually calls)
returning **1** — confirming the jar-level probe methodology works and the class name is genuinely
absent from that consumer's compiled bytecode, not merely absent from its source tree. The three
command-facing entry points `CloudAuthManager.login(...)`/`.logout()`/`.status()` are the
replacement surface; the class is now `@ApiStatus.Internal`. Full reasoning, the complete member
list with its own javap output against the 6.2.5 baseline, and the downstream-author paragraph are
in [`compatibility/records/6.3.0.md`](compatibility/records/6.3.0.md)'s own entry for this removal.

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
- Refreshing an extracted resource file nobody has customised. Before 6.3.0, `saveResources()`
  skipped an already-extracted `lang/` file unconditionally, so an operator who never touched it
  kept whatever an older jar first extracted, forever — a defect (#441), not a documented
  guarantee that the file would stay frozen. As of 6.3.0, a `lang/` file whose recorded extraction
  hash still matches its on-disk bytes is replaced by the current jar's copy on the next start,
  with one INFO line naming the file; a file the operator has edited is left alone exactly as
  before, with only the individual keys whose placeholder count moved resolved from the jar
  instead (see `ultitools.language.file-refresh`/`ultitools.language.file-preserve` in
  `FEATURES.md`). No operator who customised a file is affected either way.
- Saving at shutdown only the configuration that module code changed (#510). Operator-only disk
  edits survive a clean stop; pending code changes are saved before module release, with one
  warning naming replaced operator keys. The [config-layer contract](#config-layer-630) covers
  protected files, partial panel acknowledgments, semantic no-ops and server-thread confinement.
  Panel callbacks queue their complete operation on the server thread; they do not perform registry
  writes concurrently on the WebSocket thread. Explicit entity saves still compare against current
  disk content and can replace an operator edit even when the entity was clean.

- `PluginInstallUtils.uninstallPlugin(String)` unloading through the framework's one full unload
  path, and reporting the outcome it documents (#503, #501). It used to call
  `plugin.unregisterSelf()` directly, skipping everything `PluginManager#unregister` does first —
  cancelling the module's `@Scheduled` tasks, releasing its `@PlayerCache` beans, its
  tab-completion completers, its EventBus handlers and its conditional-bean records, then closing
  its context — so an "uninstalled" module kept running its repeating tasks until the next restart.
  It also ignored `File#delete()`'s result and stopped at the first matching jar, and returned
  `true` either way. As of 6.3.0 it deletes every jar whose `plugin.yml` `name` matches and
  **throws** where it used to report success: `java.nio.file.FileSystemException` naming every jar
  still on disk when one could not be deleted, `java.nio.file.NoSuchFileException` when a loaded
  module was unloaded but no jar of it was found (which is not a misspelling and must not be
  reported as one), and `IllegalStateException` when the module's own unload threw — the module is
  still removed from the loaded modules and its jars are still deleted in that case, with the jar
  outcome attached as suppressed, because `unregister` has closed its context by then and keeping
  the jar would bring the module back on the next restart. `false` now means only "no jar matched
  and nothing was unloaded either". A caller that checked the boolean alone now sees these as
  exceptions rather than a success it did not get. The method also no longer builds a `jar:file:`
  URL for every entry of the modules folder, so a stray file or a subdirectory there no longer
  fails the uninstall (#504). A loaded module is asked which JAR it came from — its own
  `getProtectionDomain().getCodeSource()`, read before it is unloaded — and that JAR is deleted
  whatever its metadata says, since an UltiTools module is identified by `@UltiToolsModule` and
  needs no `plugin.yml` at all. Every other entry of the modules folder is placed in exactly one of
  four states, each decided by reading the archive — with one exception, an entry the module loader
  itself would never load, judged by the same `.jar` test `PluginManager#init` applies to this
  folder, which is state B without being opened. The states: its `plugin.yml` declares this module,
  or it is a loaded instance's own code-source JAR (deleted); it opened and its `plugin.yml`
  declares another module, or it is a directory (ignored); nothing about it identifies a module,
  because the archive would not open, its `plugin.yml` is not valid YAML, **it carries no
  `plugin.yml` at all or one with no `name:` key** — which say nothing about whether it is a
  module — or the entry is named like a JAR and cannot be resolved at all, a link whose target is
  away (reported as undetermined — the uninstall still succeeds, and the operator is told how many
  entries could not be identified and that one of them, if it is a copy of this module, will load
  it again after a restart); or the modules folder exists but
  could not be listed, which is reported as `java.nio.file.AccessDeniedException` naming the folder
  rather than as "no JAR of this module is here", a claim nothing supports when the folder's
  contents are unknown. A second entry point,
  `PluginInstallUtils.uninstallPluginReporting(String)` (`@ApiStatus.Internal`), returns both what
  was deleted and the entries whose identity could not be determined; `uninstallPlugin(String)`
  keeps its signature and returns the first half. When the uninstall leaves by a failure instead —
  a module whose unload threw, a JAR that could not be deleted, or nothing identifiable found —
  those entries travel with it as a suppressed
  `PluginInstallUtils.UndeterminedEntriesException` (`@ApiStatus.Internal`), so no outcome
  discards what another established.

- The JSON storage backend no longer hands out the entities it caches (#522). Before 6.3.0,
  `SimpleJsonDataOperator`'s read paths (`getById`, `getAll`, `page`, `getLike`, and every
  `query()` terminal built on them) returned the very instances it kept in memory, and `insert`
  cached the instance it was given, so on `datasource.type: json` changing a loaded (or just
  inserted) entity **without** calling `update(...)` changed the store and was written to disk at
  the next flush. On SQLite and MySQL the same code never persisted anything, because every read
  materialises the row afresh. As of 6.3.0 every JSON read returns a detached copy produced by the
  same Gson form the store writes to disk, and `insert` caches a copy: a change reaches the store
  only through `update(...)` (or `update(column, value, id)`), on every backend alike. `update(T)`
  also fires `onUpdate()` on the entity passed in, before its fields are copied into the store,
  exactly as the relational backends do — so an `AuditableDataEntity`'s `updatedAt`/`updatedBy`
  now show on the caller's instance on the JSON backend too, and `exist(entity)` looks the entry
  up by the entity's id, as the relational backends do, instead of comparing it with the cached
  copy through `equals()`. A module that relied on the old
  aliasing — changing a loaded entity and counting on the next flush to save it — must now call
  `update(...)`; no module in this monorepo was found doing so (see the pull request's consumer
  impact list). The cost is one Gson round trip per entity returned, the same materialisation the
  relational backends already pay (see `ultitools.storage.detached-reads` in `FEATURES.md`).
- `Query#delete()` returns the number of rows actually removed, as its javadoc always said (#521).
  It used to return the number of rows the query *matched*, and it skipped a matched row whose id
  was `null` while still counting it, so a caller reading the `int` as "rows removed" could be told
  a delete succeeded when it removed nothing. As of 6.3.0 the count comes from the backend's own
  affected-row count (a row another writer removed between the read and the delete is not
  counted), and a matched row with a `null` id is refused with a `DataAccessException` naming the
  entity type **before** any row is deleted, since no delete can address it. This corrects
  behaviour that contradicted the documentation, so it takes no migration period. A third-party
  `DataOperator` implementation, which cannot report what its `delById` removed, is counted by
  checking that the row existed immediately before that call and is gone after it (see `ultitools.storage.query-delete-count` in `FEATURES.md`).
- Rows left without an id by UltiTools-API 6.2.0 are repaired, and addressing a row by a null id
  is refused (#546, maintainer decision of 2026-09-27). 6.2.0 did not assign an id in `insert`, and
  SQLite's generated DDL accepted a `NULL` primary key, so every row a module inserted without an
  id on that release was stored with none; such a row could be read, but every `update`/`delete` of
  it bound `WHERE id = NULL`, matched nothing and returned normally, so a change the module
  reported as saved was lost at the next restart. As of 6.3.0, when a SQLite-backed table is
  initialised every row whose `id` is `NULL` is given the id its entity reports through `getId()`,
  or a new UUID when the entity reports none, in either case only if the entity read back with that
  id reports it; a row that no written id would make addressable is left as it is and counted, by
  reason, in one WARNING line per table: a derived id that more than one row without an id reports
  (none of those rows is written — maintainer decision of 2026-09-29, the rule UltiEssentials' own
  repair applies), a derived id another row already holds, or a derived id that is `null` or a row
  that cannot be read as the entity — only the `id`
  column is written, all rows in one transaction, so the repair writes user data at startup, which
  is what the maintainer decided — and one INFO line names the table, the count and how many rows
  took the entity's own id; a second start finds nothing and logs nothing. The reported id comes
  first because an entity may derive `getId()` from another column (UltiEssentials'
  `UuidKeyedDataEntity` and UltiKits' `KitClaimData` derive it from a `uuid` column) and every
  lookup binds that value, so a random id would leave such a row exactly as unreachable as `NULL`
  did. For the same reason every write path (`insert`, `insertAll`, `update(T)`, `updateAll`,
  `updateIf`) now stores `getId()` in the `id` column rather than the inherited field: an entity
  that overrides `getId()` never sets that field, so on 6.3.0 before this change it still inserted
  a `NULL` id on SQLite, and on MySQL its insert failed outright. MySQL never
  accepted a `NULL` id and runs no backfill. Independently, `update(T)`, `update(column, value, id)`,
  `delById` and `updateAll` addressed by a `null` id now throw `DataAccessException` on every
  backend instead of silently matching nothing (the JSON backend used to throw a raw
  `NullPointerException`); `updateAll` checks every entity before it writes any. A call with a
  non-null id that matches no row is unchanged. See `ultitools.storage.null-id-backfill` and
  `ultitools.storage.null-id-refused` in `FEATURES.md`.
- `DataOperator` gains one method, `boolean updateIf(T entity, WhereCondition... expected)` (#543):
  a conditional write that applies only while the stored row still matches every expected
  condition, and reports whether it applied, on the JSON, SQLite and MySQL backends. No existing
  method's signature changes, and it is a `default` method, so a module compiled against 6.2.x
  still links. Its default body throws `UnsupportedOperationException` naming the implementing
  class rather than quietly performing an unconditional write — a third-party `DataOperator`
  implementation keeps working for every other method and must implement `updateIf` before a caller
  can rely on it. The framework's own operators implement it (see
  `ultitools.storage.conditional-update` in `FEATURES.md`).
- An update by a non-null id that matches no row writes nothing and says so (#558, maintainer
  decision of 2026-09-29). `update(T)`, `update(column, value, id)` and `updateAll` now log one
  WARNING naming the table and the id each time, on JSON, SQLite and MySQL, and return normally —
  as SQLite and MySQL already did, silently; the JSON backend used to throw a raw
  `NullPointerException`, which a module catching `RuntimeException` or `Exception` around the call
  saw as a failed write. The caller learns the outcome through a new method,
  `int updateCounted(T entity)` on `DataOperator`: `1` for a written row, `0` when no row has the id.
  It is a `default` method, so no existing signature changes and a module compiled against 6.2.x
  still links; a third-party implementation that does not override it is counted by whether the row
  exists before its `update` (the one remaining miscount: a delete by another writer during that
  call). See `ultitools.storage.missing-row-update` in `FEATURES.md`.

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

A second, narrower class also cannot go on the removal list, for a different reason: the method is
not removed at all, so there is no eventual deletion event to annotate with `@Deprecated(forRemoval =
true)` and no descriptor change either. Adding `final` to a public method is exactly this — the
method's name, return type and parameter types are all unchanged, so the removal list's two
preconditions have no target, yet japicmp still reports it (`METHOD_NOW_FINAL`) because a subclass
that overrode the method can no longer be loaded at all. Unlike the two descriptor cases below, this
one is not invisible to its author — it is deliberate and recorded here in full, with its own
japicmp exclude entry — but the removal-list mechanism still cannot express it, because nothing was
removed for the annotation to describe.

This has happened three times, and all three are recorded here — a JVM-descriptor change in a MINOR,
the same in a PATCH, and a `final` addition in a MINOR still under development — **no release level
is exempt, and no failure mode is exempt either**:

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

### Third occurrence: 6.2.5 → 6.3.0, a MINOR (still under development)

`UltiToolsPlugin.unregisterSelf()` and `UltiToolsPlugin.reloadSelf()` both gain `final`. Each keeps
its exact name, return type (`void`) and parameter list (none) — there is no descriptor change here,
unlike the first two occurrences — but japicmp still reports both as binary-incompatible
(`METHOD_NOW_FINAL`), because a subclass compiled against 6.2.5 that overrides either method can no
longer be loaded on 6.3.0 at all.

**Same-release exception, clause 1 (proven non-functional).** Both overrides were already the
declared extension point for module unload and reload work, and both were measured broken on every
released version: **15 of 15** module `unregisterSelf()` overrides never call `super.unregisterSelf()`,
so the framework's own `CommandManager.unregisterAll` / `ListenerManager.unregisterAll` step never
runs on module unload — the module's commands keep resolving into a closed container after the
module is gone. **9 of 11** module `reloadSelf()` overrides never call `super.reloadSelf()`, so the
framework's own config-reload / language-refresh / `@ConditionalOnConfig`-drift-report steps never
run on a `/ul reload` of that module. Real-machine instances: Phase 13's UltiLogin#13 and
UltiWorlds#10. Making both methods `final` — with `onUnregister()` and `onReload()` as the new,
unconditional-body-guaranteed hooks — is not a new restriction on working behaviour; it is the fix
for a declared contract that never ran.

**Why this could not simply be deprecated first.** `unregisterSelf()`/`reloadSelf()` are declared on
the public `IPlugin` interface and were never going to be removed — only sealed against override.
There is no future release where deprecation would mature into removal, so the ordinary one-MINOR
warning window has nothing to count down to; the `final` keyword is the entire change, applied once.

**What an un-recompiled downstream JAR sees.** A module JAR compiled against 6.2.5 that overrides
either method fails at class-verification time — before the module's `registerSelf()` ever runs —
with `IncompatibleClassChangeError: class <ModuleClass> overrides final method
com.ultikits.ultitools.abstracts.UltiToolsPlugin.unregisterSelf()V` (or `reloadSelf()V`). This is a
loud, named failure at load time, not a silent no-op and not a delayed `NoSuchMethodError` on first
use, unlike the first two occurrences in this section — the class naming its own offending method is
exactly what an `IncompatibleClassChangeError` for an overridden final method reports.

**Migration guide for module authors.** The fix is a rename or a deletion — never a rewrite — with at
most a `super` call or a now-dead `lang` key to drop alongside it:

| Your current override | What to do |
|---|---|
| `unregisterSelf()` doing real cleanup work | Rename the override to `onUnregister()`, keep the body verbatim |
| `unregisterSelf()` that is empty or log-only | Delete the override outright — the framework's own `onUnregister()` default body already does nothing, and the module's log line (if any) becomes dead weight once removed |
| `reloadSelf()` doing real reload work | Rename the override to `onReload()`, keep the body verbatim. Drop any `super.reloadSelf()` call inside it — it is no longer callable, and the framework's three reload steps and its own log line now always run before `onReload()`, whether or not the old override called `super` |
| `reloadSelf()` that only logs "reloaded" or similar | Delete the override outright, and delete the now-dead module `lang` key that message used — the framework logs its own per-module reload line (D-03) |
| `reloadSelf()` that already called `super.reloadSelf()` (2 of 11 modules) | Rename to `onReload()` and drop the `super` call; no other change needed |

Two member-level japicmp excludes cover this occurrence: `UltiToolsPlugin#unregisterSelf()` and
`UltiToolsPlugin#reloadSelf()`, both member-level (the class itself is unchanged and still present),
appended to the end of `pom.xml`'s existing `<excludes>` list. See
[the 6.3.0 removal record](compatibility/records/6.3.0.md) for the full evidence and the
downstream-author paragraph in that file's own format.

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

### A floor no linker enforces: config-bound `@Scheduled` and `@CmdCD` (6.3.0)

Everything above is about descriptors, where a missing symbol at least fails loudly with
`NoSuchMethodError`. New **annotation elements** fail silently instead. As of 6.3.0,
`@Scheduled(config = ..., periodKey = ..., delayKey = ...)` and `@CmdCD(config = ..., key = ...)`
read an interval or cooldown from a module config key (#531). A module compiled against 6.3.0 that
uses them still loads on an older framework. The JVM drops annotation elements that the running
annotation type does not declare. Measured: a class compiled with `@Sch(periodKey = "x")` and run
against an older `Sch` without `periodKey` reads back as `@Sch(period=-1L)`. Nothing is logged.
Against 6.2.x, a bound `@Scheduled` therefore runs **once** at load instead of on its interval,
and a bound `@CmdCD` enforces **no** cooldown.

So **a module that uses either binding must declare `api-version: 630`** in its `plugin.yml`. That
floor makes an older framework refuse the module at load, rather than run it with the wrong timing.
Raising the `pom.xml` pin alone does not do this, for the reason given above. So that the mistake
surfaces on the version you develop against, 6.3.0 itself refuses a module that uses a binding while
declaring a lower `api-version`, naming the module, the binding and the required floor. A bound
`@Scheduled` must also be synchronous: `async = true` together with a binding is refused at load,
because a reload can keep an async task's place in its cycle only by predicting when the server
dispatches async work (#535 tracks a design that observes it instead). The binding is
additive: existing literal usages (`@Scheduled(period = 6000)`, `@CmdCD(60)`) behave as before
and need no change.

**A bound field must not also carry a module `@Range`.** The binding's own range is the field's
range: 1 to `Integer.MAX_VALUE / 20` seconds for a period or delay, and 0 (no cooldown) to
`Integer.MAX_VALUE` for a cooldown. A panel write outside it is refused like a `@Range` violation.
An out-of-range value on `/ul reload` keeps the running value and logs a WARNING. A `@Range` on the
same field would instead throw from the config reload itself, and that aborts the rest of the
module's reload. That behaviour is tracked separately in #509 and is unchanged by 6.3.0. A module
that already had a `@Range` on a field it now binds should drop it; the binding's range takes over.

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
