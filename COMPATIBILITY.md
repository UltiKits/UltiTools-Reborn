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
order. Its existing line-terminator, BOM, final-newline and supported indentation-style rules remain in effect.

As of 6.3.0 every automatic write to a module configuration file - creating a file that does not exist, inserting a declared
key the file lacks, rewriting the framework's own token comments at start-up or reload, and the registration batch flush - goes
through one write gate. Each write declares the keys it owns; after rendering, every line outside them must be byte-identical
to the file as read (line terminators, BOM and final line break included), and the file must still hold the bytes it was read
from. Otherwise nothing is written, one warning names the file, the keys and the reason (never a value), and the declared
defaults run in memory. These writes never normalize layout: on a hand-aligned file a missing key stays missing, with a warning
at each start, until the operator adds it. A file using anchors, aliases or merge keys is never written automatically and is
named once per server run. A file the framework creates is created exclusively and never replaces a file that appeared
meanwhile. The residual window between the gate's last read and the replacement cannot be closed between an editor and the JVM.

A module's explicit save, an operator change written through `saveOperatorChange`/`saveOperatorMapEntry`, and a panel edit
go through the same gate, each owning only the settings or map entries it writes (see "Declared types, whole map keys and
persistence", [#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599),
[#600](https://github.com/UltiKits/UltiTools-Reborn/issues/600)). No write normalizes layout or expands anchors any more: a
panel edit on a file the gate refuses replies with an error naming the reason, and the file keeps its bytes. Comments on
individual list items are kept only while the list keeps its length — the same as Bukkit, which keeps none. The storage API
signatures are unchanged.

Two layouts are refused by every gated write - a first-start insert, a comment rewrite, a save, an operator change or a
panel edit - whichever setting it changes, because the renderer cannot write them back byte for byte: a block scalar value (`|` or `>`) followed by a blank line,
and a comment that closes a section after a blank line, indented deeper than the key that follows it. The file keeps its bytes,
the warning names the keys and the layout reason, and the values run in memory; removing that blank line (or moving the comment
to the following key's column) lets the next write through.

A 0-byte, blank or comment-only file is an empty document to the gate: start-up inserts the declared keys (there is no other
byte to keep), and an operator change or a panel edit inserts the setting it names; a save never inserts. An operator change
or a panel edit of a file deleted since it was read creates the file, exclusively, holding only the named settings with their
comments. An unchecked failure inside the gate at start-up never refuses the module: one warning names the file and the
failure's class, and the declared defaults run in memory.

Saving first attempts a forced same-directory temporary file and atomic replacement. Only an unsupported atomic move, EBUSY,
EXDEV, or a permission/read-only refusal to create the temporary file allows the narrow fallback: exclusively create a backup
named `<file name>.ultitools-backup-<16 lower-case hex>`, copy and force the existing target's bytes, then overwrite and force the
existing target in place. If this server run already wrote such a backup for the same file and it still holds exactly the bytes
written, it is refreshed from the current target through a forced same-directory temporary and atomic replacement before the
target is opened. A backup creation/write/force or refresh rename failure refuses the save before the target is touched and
preserves the previous backup. No old-backup restoration or validation of the current target is implied by this refresh. Other
staging or move failures refuse the save. A fallback attempt logs one warning identifying the target, backup, cause and outcome.
Symbolic links remain links, with the backup beside the resolved target. The writer reads, writes or deletes no other name: an
operator's own `<file>.bak` (the name the fallback used before 6.3.0's #601) is never touched.

After an in-place write begins, a failure may leave a partial target, but its complete forced backup remains. That backup is
removed only after the next successful strict UTF-8 storage load, and only while its bytes still match what the writer recorded;
unreadable or unparseable files keep it. A file of the backup pattern that this server run did not write, or whose bytes changed
after it was written, is kept and named once at INFO; delete it yourself once it is no longer needed. Backup cleanup is best
effort and cannot turn a successful load into a failure. No automatic restoration policy is introduced.

### Declared types, whole map keys and persistence

`AbstractConfigEntity` uses the document, converter registry and atomic writer for initial defaults,
explicit saves and panel updates. Typed collections resolve their full inherited generic types:
convertible values bind, invalid collection/map elements are skipped with a located warning, and an
invalid field value falls back to its initially declared default. Invalid reload values use that
default too. A key missing on reload binds its declared default - with one warning naming the key when the previous load
still found it, so an operator who deletes a key to reset it is told - unless the module changed that setting since the
last load or save, which keeps the module's value; the file is not changed either way, and
no later save re-adds the key ([#596](https://github.com/UltiKits/UltiTools-Reborn/issues/596)).
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
its module changes that setting and saves it, or a panel edit sets it. Loading, reloading, a save that
did not change that setting and the shutdown check never rewrite such a file on their own. Equality is semantic value comparison, not object identity; numeric plain values
compare by value. Reload merges and panel leaf edits rely on forward equality. A converter that adds
a value during reading without undoing that change during writing violates this contract.

Whole map keys, including `g.m`, `o.O` and `wave.`, are supported as of 6.3.0.
`@ConfigEntry.path` still splits at every dot: `chat.aliases` selects nested settings, whereas a
key `o.O` inside the bound map is one whole key. These are different path conventions. Legacy 6.2 files in which Bukkit
split a dotted key into nested mappings are read as they are, never automatically merged or renamed, and a save never removes
such an entry: it stays until the operator removes it (maintainer decision of 2026-10-04).
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

As of 6.3.0 a module's `save()` writes only what the module changed in memory since the last load or save, and only
where the file still holds what it was read with ([#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599);
maintainer decision of 2026-10-04, "what code may write, by file type"). A setting is written when all three hold: the
module changed it; the file still holds at that key exactly the value the framework last read or wrote there; and that
value is the one the module started from - it converts, without a conversion warning, to the setting's value as last loaded
or saved. A setting declared as a `Map` is written entry by entry, following the declared map types into nested maps: only
the entries the module added, changed or removed are set or removed (a map that is empty, or that the module empties, is
written as a whole, because its key line changes with its first or last entry). Any other value - a list, a
`ConfigurationSerializable` such as a Bukkit `Location` or `Vector`, the value of a typed map entry that is not itself a map -
is one value, written whole or not at all, so the file never holds a value mixed from the module's and the operator's. A
setting declared as `Map<String, Object>` or as a raw `Map` is split one level only: a plain nested map below it is one value.
The same rule decides what is one value for the reload merge (a conflict over a composite value or a list takes the file's
whole value and names the key only), for a panel edit inside a composite value (the whole value is the unit written - the value the
framework last read with that field changed - and only while the file still holds exactly that whole value at write time;
otherwise the edit is refused naming the setting, "the file changed since it was read; reload first", and nothing is
written), and for `saveOperatorMapEntry` (map keys that reach inside an entry that is not a map throw `IllegalArgumentException`
and nothing is written). A save never inserts a key the file lacks, never rewrites a comment and never removes a
map entry the module did not remove. So a value the operator edited on disk since it was read, a key the operator deleted, a
value or list element the framework could not use (`interval: 3O0`, `[60, 30, 10, abc]`) and a 6.2 split map entry are
never written over by a save, whether or not the module changed that setting
([#596](https://github.com/UltiKits/UltiTools-Reborn/issues/596)). A change that is not written stays in memory, and one
warning per save names the file and those keys, never values; a key the write gate refuses is named in the gate's warning
instead, with its reason. The write goes through the write gate above, so every other line of the file stays byte for byte,
or nothing is written. With nothing to write the file is not touched (bytes and modification time stay). A failed write
never acknowledges the pending effective values as saved. Use `save()` for a change the operator asked for through the
module, or for shipped text the module re-renders after a language switch. Before this change an explicit save compared
its values with the file and replaced an operator's changed value with a warning (#527); that is removed in 6.3.0 without
a migration period, by the maintainer's rule that operator-written configuration is never overwritten automatically.

For a command the operator runs to change exactly one thing, two methods are new in 6.3.0 (additive):
`AbstractConfigEntity#saveOperatorChange(String...)` writes exactly the named settings (`/setspawn` names the six
`spawn.location.*` entries), and `#saveOperatorMapEntry(String, String...)` exactly one entry of a setting declared as a
map (`/autoreply` names one rule; the entry is inserted when the file lacks it and removed when the module's map no longer
holds it; each map key is one whole key, so `play.example` is a single key). The operator's request is their consent: the
module's value replaces what the file holds at the named keys, including a value the operator edited there by hand; a
named setting the file lacks is inserted with its comment. Nothing else is written - not even another setting the module
changed - and the write goes through the write gate owning only those keys. Naming a setting declared as a map with
`saveOperatorChange` writes the whole map and drops entries the operator added by hand; use `saveOperatorMapEntry` for a
command that changes one entry. A refusal (a file that cannot be read or
parsed, uses anchors, has a layout the write cannot keep byte for byte, or changed while the write was being prepared)
throws the new `com.ultikits.ultitools.config.ConfigWriteRefusedException`, an `IOException` whose message and
`getReason()` name the reason without any value; the file keeps its bytes and the in-memory value stays, unsaved. A path
that is not a declared entry, or a map-entry call on a setting not declared as a map, throws `IllegalArgumentException`
and writes nothing. Both run under the entity monitor and must be called on the server thread.

Unreadable, unparseable and non-UTF-8 files are protected on every entity write path. Initial load
keeps declared defaults; failed reload keeps running fields. One SEVERE names the file and safe cause.
Explicit save does not clear protection; only a later successful load permits writes again.
Parser diagnostics expose only numeric line/column metadata, never source snippets or scalar values.

An annotation comment that is exactly one `{key}` token (surrounding whitespace ignored) is resolved
from the module catalogue. Start-up and reload rewrite, in the current language, only the comment
lines above a token entry that the framework can identify as its own (a save, an operator change and a panel edit rewrite no
comment; a key one of them inserts gets its comment): the entry's comment, as a whole
or as its trailing run of lines, equal byte for byte to what the framework writes (the entry's
column, `# ` and the text) for the token's text in a catalogue the module's jar ships, for the text the
module resolves now, or for the bare token. Equality is the only test; the same text without the space
after `#` or at another column is the operator's. Every other comment line - a note an operator wrote above a token entry, a framework comment the
operator edited, a literal-entry comment - is kept byte for byte, permanently (maintainer decision of
2026-10-04, [#604](https://github.com/UltiKits/UltiTools-Reborn/issues/604); supersedes the earlier
6.3.0 rule that an operator's comment on a token entry is replaced). A framework comment whose text no
shipped catalogue holds any more - an older module version's wording - is recognised only when the module
lists that text in the new `@ConfigEntry(previousComments = {...})` attribute (additive, default empty):
a comment equal to a listed text, in the same exact written form, is replaced by the current catalogue
text and follows the language from then on (maintainer decision of 2026-10-04); otherwise it is kept as
it is. A token entry without any comment gets the framework's.
Catalogue lookup failure keeps the literal token and warns once per entry per load. Comment text
uses the document's YAML line-break/control-character sanitation, including the panel payload.
A failed comment-only rewrite does not fail load or discard bound values, logs one warning and marks
nothing for a later save. No-op comparison includes these comments.

A panel edit replaces an operator-edited value at exactly the keys it names - the operator's consent - and warns about
nothing; before 6.3.0's [#600](https://github.com/UltiKits/UltiTools-Reborn/issues/600) it warned once naming the replaced
keys (#527). A save never replaces an operator-edited value (above). Comment/layout-only edits, equal candidate values and
failed writes report nothing. The check and write run under the same entity monitor.

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
The flush writes through the write gate (see "Rendering and save fallback") and only while the file still
holds the bytes read at registration; an edit made in between is kept, with one warning.
Standalone registration still writes immediately. Framework-internal initialization bridges are not
a module transaction API.

### Superseded-copy configuration ordering

As of 6.3.0 no configuration of a loaded older module copy is saved before, during or after a newer copy replaces it
(maintainer decision of 2026-10-04, "what code may write, by file type": no replacement save of whole entities, because a
module update is not an operator's request to write). The newer copy is constructed and reads the files as they are; the old
copy's unsaved changes or a protected (unreadable or unparseable) old file no longer refuse the replacement. The old copy is
not unloaded before incoming activation succeeds; once it does, one warning names the dropped file/entry keys (never values)
and the old configuration entities are released. This replaces the earlier 6.3.0 behaviour of saving an identifiable old
copy's changed configurations before construction (and refusing construction when that save failed); its "no save, warn
naming the keys" fallback is now the only path. Failed incoming construction, compatibility, assembly or activation
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
when its unload hook or context close throws. **Nothing writes configuration at server stop, at
module unload or uninstall, or at module replacement** (maintainer decision of 2026-10-04: no
shutdown or replacement save of whole entities; a lifecycle event is not an operator's request to
write). At stop, and at a normal unload or uninstall, after each module's unload hook and container
`@PreDestroy` callbacks and just before its release, every configuration of that module holding module
changes that were never saved is named in one WARNING - the file and the changed keys, never values -
and the changes are dropped; the framework's final step at stop names, the same way, anything still
registered. A superseded copy is named once by the replacement's own drop warning instead. A configuration whose file
could not be read or parsed at its last load is named once instead, as before. An operator's edit made
while the server runs is never touched at stop. A module persists a change when it makes it, with
`save()` (only what the module changed, where the file still holds what was read) or
`saveOperatorChange`/`saveOperatorMapEntry` (exactly what an operator's command names).
`ConfigManager#saveAll()` keeps its signature, writes nothing, reports as above and is
`@Deprecated(since = "6.3.0")`. Public existing signatures are unchanged. This removes the earlier
6.3.0 shutdown saves (before unload and again per module after its callbacks) without a migration
period; see "Behavioral changes that need no migration period".

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
and warn with the located key and, for a scalar, the discarded value, redacting secret-shaped values; a conflict over a list
or a composite value names the key only. Absent map keys
and explicit null differ. A whole declared field missing from the file binds its declared default with one warning
when the module had not changed it, and keeps its live value when the module had (both with the declared-default
baseline); the file is not written. This planner-selected file-wins policy can be overturned by the maintainer.
Unreadable/unparseable reloads keep live values and protect the file as before.
After a module's language is rebuilt in the reload steps, and before its own reload hook, the framework rewrites
only its own comment lines of that module's configurations in the new language, through the write gate over a fresh
read; no value, key or other comment line is written ([#594](https://github.com/UltiKits/UltiTools-Reborn/issues/594)).

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
bound siblings without any type-specific map/object merging. Only targeted leaves are persisted, through the write gate
owning exactly those leaves (a map that is empty, or that the edit empties, is owned whole).
The existing `config_update_response` shape is unchanged.

A panel edit of one field inside a composite value (a Bukkit `Vector` or `Location`, also as a map entry) writes that whole
value exactly as the file held it with only the edited field changed, so the other fields keep the bytes the operator wrote
(`y: 64` and `pitch: 0` stay, never `64.0`). A whole number - sent by the panel, or already in another field of the value in
the file - is widened to a floating-point number for the conversion where the module's own value holds one (Bukkit's `Vector`
does not widen by itself), and the edited field is written in that type (`7` is written as `7.0`), so the file the edit leaves
behind loads ([#609](https://github.com/UltiKits/UltiTools-Reborn/issues/609)). A value the module could not load at all - a
`Vector` map entry holding whole numbers - is still refused. An edit below a map group the module removed in memory and has not
saved is refused, naming the key with "not found in memory".

### Multi-file panel persistence

As of 6.3.0, `ConfigManager#loadFromJson(String)` validates every touched configuration, stages
all changed files through the write gate, and only then replaces them. Each file is replaced only while it still holds
exactly the bytes it was staged against, checked again immediately before its move: a file an operator saved in between
refuses the whole batch with `ConfigWriteRefusedException` naming that file, the operator's save is kept, and the files
already replaced are restored to exactly the bytes they were verified against. Entity baselines and raw acknowledgments advance
after every commit succeeds. An in-process staging/replacement refusal restores attempted targets
and the complete prior entity state, removes staged temporaries, and rethrows the original error.
Recovery errors are attached as suppressed exceptions; persistently unavailable storage can prevent
restoration and is not falsely reported as a successful rollback. Semantic no-ops write nothing.
This is not a crash-safe multi-file transaction: a JVM crash between moves remains deferred to #545.
The panel message shape and public `loadFromJson` signatures are unchanged. Internal staged-entity
coordination bridges are not a module transaction API.

### Writing operator files a module manages

As of 6.3.0, `com.ultikits.ultitools.config.OperatorFiles` (additive) is the way a module writes a YAML file it
manages on an operator's behalf - a kit file, a menu file - on an explicit operator edit (maintainer decision of
2026-10-04: such a file gets only the edited part; comments and other keys are kept). `OperatorFiles.read(File)`
returns the file's text (strict UTF-8) and the SHA-256 of its bytes; `OperatorFiles.write(Snapshot, Map<List<String>,
Object>)` writes only the named whole keys (a key containing `.` is one key), through the configuration write gate,
only while the file still holds the snapshot's bytes, and returns `WRITTEN`, `UNCHANGED`, `FILE_CHANGED` or `REFUSED`
(a layout the gate cannot keep byte for byte, or anchors, with one WARNING naming the file, the keys and the reason).
Values must be plain data - serialize an `ItemStack` first; anything else throws `IllegalArgumentException` before
the file is touched. It never creates, deletes or renames a file, is not a general file API (UltiKits/UltiTools-Reborn#545
stays a later feature), and is not for `@ConfigEntity` files, which keep their own save rules.

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

Nothing is written at shutdown: an operator-only disk edit survives untouched, and a pending code change is named in
one warning and dropped (see "Configuration release and shutdown save"). A partial panel save cannot acknowledge an
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
- 注解 `path` 中的点仍表示嵌套路径；映射里的 `g.m`、`o.O`、`wave.` 从 6.3.0 起按完整键保存。6.2 已经拆开的文件原样读取，不会自动合并，保存也不会删除这些条目，留给服主自己删（维护者 2026-10-04 决定）。完整泛型参与绑定；无效集合元素跳过，无效字段用声明默认值；重载时文件里缺少的键改用声明默认值（上次加载时还在、即服主刚删掉的键警告一次）（模块自上次加载或保存后改过该设置时保留模块的值），两种情况都不改文件，之后的保存也不会补回该键（#596）。允许 null 的引用类型可往返；`Object` 中的 Bukkit 序列化对象读取后仍是普通映射。
- 保存内容、注释文字、键顺序和支持的文件风格。6.3.0 起，所有自动写入（创建不存在的文件、补写缺失的声明键、启动或重载时改写框架自己的令牌注释、注册批次落盘）都经过同一个写入闸门：每次写入声明自己拥有的键，渲染后其余每一行必须与读取时逐字节相同（含换行符、BOM 和末尾换行），且文件仍是读取时的内容；否则不写，警告一次（只列文件、键和原因，不列值），内存中使用声明默认值。这些写入不会规整排版：手工对齐的文件缺少的键会一直缺少，每次启动警告一次，直到服主自己补上。使用锚点、别名或合并键的文件不会被自动写入，每次运行只提示一次。框架新建文件时独占创建，绝不替换期间出现的文件。模块的显式保存同样经过写入闸门，只拥有它要写的设置（#599，见下）；服主操作写入和面板编辑同样经过写入闸门，只写它们改的设置或映射条目，不再规整排版或展开锚点；闸门拒绝时面板收到注明原因的错误，文件不变（#600）。有两种排版任何经闸门的写入（启动补键、改写注释、保存、服主操作写入、面板编辑）都会拒绝，无论改的是哪个设置，因为渲染器无法逐字节写回：块标量（`|` 或 `>`）后面跟空行；以及一节末尾、空行之后、缩进比下一个键更深的注释。文件保持原样，警告列出键和排版原因，内存中使用相应值；删掉那个空行（或把注释移到下一个键的缩进）后，下一次写入即可通过。0 字节、空白或只有注释的文件在闸门看来是空文档：启动时补入声明的键（没有其它字节需要保留），服主操作写入或面板编辑插入它指定的设置；保存从不插入。读取后被删除的文件，服主操作写入或面板编辑会独占地重新创建它，只含指定的设置及其注释。启动时闸门内部的非受检异常不会拒绝模块：一条警告列出文件和异常类名，内存中使用声明默认值。语义无变化不写文件，字节和修改时间不变。浮点数按最短可回读十进制判断，不要求二进制精确。
- 先临时文件、force、原子替换；仅已允许的原子替换/临时创建拒绝才走备份后原地写。备份文件名为 `<文件名>.ultitools-backup-<16 位小写十六进制>`，独占创建；本次运行已为同一文件写过、且内容未变的备份先从当前文件刷新并原子替换，之后才打开目标。写入器不读取、不写入、不删除任何其它文件，服主自己的 `<文件>.bak` 不受影响。失败保留备份，只有成功严格加载当前文件、且备份内容仍与记录一致时才清理；本次运行未写过或已被改动的同类备份保留，并以 INFO 提示一次。不自动还原，不保证多文件崩溃事务。
- 不能读取、不能解析、非 UTF-8 文件不会被任何实体写入路径覆盖；初次失败用默认值，重载失败保留运行值。成功加载才解除保护。单独 `{key}` 注释按模块当前语言目录更新，但只改框架能认出是自己写的注释行：该项注释整体或末尾连续几行，与框架对模块 jar 自带任一语言目录中的文字、模块当前解析出的文字或原样 `{key}` 写出的形式逐字节相同（该项的缩进、`# ` 加文字；只按相等判断，`#` 后缺空格或缩进不同即视为服主所写）；服主在令牌项上方手写的注释、改过的框架注释和字面注释逐字节永久保留（#604，维护者 2026-10-04 决定，取代此前“令牌项上的服主注释会被替换”）。已不在任何自带目录中的旧版措辞，只有模块在新增的 `@ConfigEntry(previousComments = {...})`（增量属性，默认为空）中登记了该文字时才算框架所写：逐字节相同即替换为当前目录文字并从此随语言切换（维护者 2026-10-04 决定）；未登记的原样保留。面板编辑以服主同意为准，只替换它指定的键，不再发警告（此前会警告一次列出被覆盖的键，#527）。
- 6.3.0 起模块 `save()` 只写模块自上次加载或保存以来改过的设置，并且只在文件该处仍是框架上次读到或写入的值、且该值正是模块的起始值（能无转换警告地转换为上次加载或保存时的设置值）时才写（#599，维护者 2026-10-04 决定）。声明为 `Map` 的设置按条目写（按声明的映射类型逐层进入嵌套映射），只设置或删除模块增、改、删的条目（空映射或被模块删空的映射整体写，因为它的键行随首条或末条一起变）；其他值——列表、Bukkit `Location`/`Vector` 等 `ConfigurationSerializable`、类型化映射中本身不是映射的条目值——都算一个值，要么整体写入要么不写，文件里不会出现模块与服主各占一部分的值。声明为 `Map<String, Object>` 或原始 `Map` 的设置只拆一层，其下的普通嵌套映射算一个值。重载合并（复合值或列表冲突时整体采用文件的值，只列键名）、面板编辑复合值内的字段（以框架上次读到的整个值加上该字段的改动为写入单位，且仅当写入时文件仍保存着该整个值；否则拒绝并注明设置名“文件在读取后已被改动，请先重载”，不写入）以及 `saveOperatorMapEntry`（键深入到非映射条目内部时抛 `IllegalArgumentException`，不写入）都按同一规则判断什么算一个值。保存从不补写文件缺少的键、从不改写注释、从不删除模块没删的映射条目。因此服主在磁盘上改过的值、删掉的键、框架无法使用的值或列表元素（`interval: 3O0`、`[60, 30, 10, abc]`）以及 6.2 拆开的映射条目，无论模块是否改了该设置，保存都不会覆盖（#596）。没写成的改动留在内存，每次保存一条警告列出文件和这些键，不列值；被写入闸门拒绝的键由闸门的警告连同原因列出。其余每一行逐字节不变，否则不写；没有可写内容时不碰文件。`save()` 用于服主通过模块要求的改动，或语言切换后重新渲染出厂文字。此前显式保存会把服主改过的值连同警告一起覆盖（#527），该行为在 6.3.0 依维护者“服主写的配置绝不被自动覆盖”的规则直接取消，没有过渡期。
- 6.3.0 新增两个服主操作写入方法（增量 API）：`saveOperatorChange(String...)` 只写指定的设置（如 `/setspawn` 指定六个 `spawn.location.*`），`saveOperatorMapEntry(String, String...)` 只写映射设置中的一个条目（如 `/autoreply` 的一条规则；文件缺少时插入，模块映射里已删除时从文件删除；每个映射键是完整键，`play.example` 是一个键）。服主的命令即同意：指定键处以模块的值为准，服主手改过的也替换；文件缺少的指定设置连同注释插入。其余一律不写（模块改过但未指定的设置也不写；用 `saveOperatorChange` 指定整个映射设置会整体写入并丢掉服主手加的条目，只改一个条目的命令应使用 `saveOperatorMapEntry`），写入经写入闸门且只拥有这些键。被拒绝时（文件不可读或无法解析、使用锚点、排版无法逐字节保留、准备写入期间文件被改）抛出新的 `com.ultikits.ultitools.config.ConfigWriteRefusedException`（`IOException` 子类），消息和 `getReason()` 说明原因、不含任何值；文件保持原样，内存中的值不变且仍未保存。路径不是已声明的配置项，或对非映射设置调用条目方法，抛 `IllegalArgumentException` 且不写。两者都在实体锁下执行，须在服务器主线程调用。
- `getConfig()` 在 6.2.5 确实可用，不能冒称符合两个同版删除例外；维护者通过 6.3.0 一次性 carve-out 删除它。改用 `isPresentInFile` 查询上次成功加载时的存在性，修改声明字段后 `save()`。两个已知官方调用在 UltiEssentials 与 UltiRemoteBag；第三方用量未知。
- 六个旧解析器相关声明在 6.3.0 首次带 `forRemoval`，公告 6.4.0 删除。显式非默认 parser 暂时保留冻结的旧行为；默认 parser 改走注册表。迁移示例见上方，转换器必须满足两条互逆等式，不能单向加值或悄悄丢字段。
- 注册批次验证完成才开始独立写文件；面板批次先验证并经写入闸门暂存全部文件，在进程内失败时回滚，持久存储故障可能阻止恢复；每个文件替换前再核对一次字节，期间被服主保存的文件整批拒绝（`ConfigWriteRefusedException` 注明文件）、服主的保存保留，已替换的文件恢复为核对时的原字节。面板唯一映射路径走整字段类型转换，歧义和未知变更拒绝整个请求；无关内存/磁盘兄弟项保留。
- 重载三方合并，内存独有改动保留且仍脏，磁盘独有采用，冲突磁盘胜。重载重建模块语言之后、模块自己的重载钩子之前，框架经写入闸门、按重新读取的文件，只把它能认出的自己的注释行改成新语言，不写任何值、键或其它注释行（#594）。仅磁盘该映射未变时保证内存顺序保留，不写文件。初始化、重载和注册表在服务器主线程执行；异步面板回调整体排队，不能阻塞等待。
- 框架自己的 `plugins/UltiTools/config.yml` 启动时补入缺少的面板键（能力开关、`ultipanel.commands.blocklist`、`ultipanel.files.editable-roots`、操作日志轮转键）同样经过写入闸门，只插入（#605）。此前启动迁移会整份重写文件：十六进制数变成十进制、锚点被展开、`o.O` 这样的含点键被拆成嵌套映射、显式 `null` 被替换。现在其余每个字节保持不变（已在每个已发布的 6.2.x `config.yml` 上实测）；闸门无法逐字节写回的文件（手工对齐、锚点）保持原样，警告一次列出这些键，由 jar 自带 `config.yml` 中的默认值（现在也列出命令黑名单和可编辑根目录）生效。
- UltiTools 只在 `plugins/bStats/config.yml` 不存在、或已含 `serverUuid` 时启动 bStats（#606）。该文件由所有使用 bStats 的插件共用；此前无法解析的文件被当作空文件，bStats 的默认值会写在它上面。现在无法读取、无法解析或缺少 `serverUuid` 的文件逐字节保持不变，记一行日志说明，本次运行 UltiTools 不发送统计。
- 6.3.0 新增 `com.ultikits.ultitools.config.OperatorFiles`（增量 API），供模块在服主明确编辑时写入它代为管理的 YAML 文件（礼包、菜单文件）：只写编辑的部分，注释和其它键保留（维护者 2026-10-04 决定）。`read(File)` 返回文件文本（严格 UTF-8）和字节的 SHA-256；`write(Snapshot, Map<List<String>, Object>)` 只写指定的完整键（含点的键是一个键），经写入闸门，且仅当文件仍是快照时的字节，返回 `WRITTEN`、`UNCHANGED`、`FILE_CHANGED` 或 `REFUSED`（排版无法逐字节保留或使用锚点，警告一次列出文件、键和原因）。值必须是普通数据（`ItemStack` 先序列化），否则在碰文件之前抛 `IllegalArgumentException`。它从不创建、删除或重命名文件，不是通用文件 API（#545 仍是以后的功能），也不用于 `@ConfigEntity` 文件。
- 面板编辑复合值（Bukkit `Vector`、`Location`，或映射条目中的此类值）内的一个字段时，写入的是文件中原样的整个值、只改该字段，其余字段保持服主写下的字节（`y: 64`、`pitch: 0` 不会变成 `64.0`）。面板发送的整数、或文件中该值其它字段里的整数，在模块自身的值为浮点数的位置按浮点数转换（Bukkit 的 `Vector` 自己不放宽），被编辑的字段按该类型写入（`7` 写成 `7.0`），因此编辑后的文件能被重新加载（#609）。模块根本无法加载的值（含整数的 `Vector` 映射条目）仍被拒绝。模块在内存中删除且未保存的映射分组，编辑其下的键会被拒绝，注明“not found in memory”。
- 服务器关闭、模块卸载或卸载删除、模块被新版本替换时，一律不写任何配置（维护者 2026-10-04 决定：生命周期事件不是服主的写入请求，不做整对象的关闭保存或替换保存）。关闭时以及正常卸载或卸载删除时，每个模块在卸载钩子和 `@PreDestroy` 之后、释放之前，凡有从未保存的模块改动的配置各警告一次（列文件和键，不列值），改动随之丢弃；框架最后一步对仍注册的配置同样处理。上次加载无法读取或解析的文件照旧只提示一次。新版本副本直接读取现有文件，旧副本的未保存改动或受保护文件不再阻止替换；新副本激活后警告一次丢弃的键。`ConfigManager#saveAll()` 签名不变、不再写入、改为报告并标记 `@Deprecated(since = "6.3.0")`。模块应在改动发生时用 `save()` 或 `saveOperatorChange`/`saveOperatorMapEntry` 保存。卸载照旧释放实体。已知限制 #578、#580 和多文件崩溃限制 #545 仍存在。

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
  with one INFO line naming the file; a file the operator has edited since it was recorded is left
  alone exactly as before, with only the individual keys whose placeholder count moved resolved
  from the jar instead (see `ultitools.language.file-refresh`/`ultitools.language.file-preserve` in
  `FEATURES.md`). A file with no record at all is handled by the next entry.
- **Upgrade note — language files without a provenance record are replaced on the first 6.3.0
  start, including files an operator edited (#459).** Every `lang/` file extracted before 6.3.0
  has no provenance record, so 6.3.0 cannot tell an operator's edit from a file that is merely an
  older jar's wording. The maintainer decided on 2026-09-29 that on the first start after upgrading,
  every such file whose bytes differ from the new module jar's copy is **replaced** by that copy and
  recorded, and accepted in writing that this replaces operator-edited files too. Nothing is
  deleted:
  - the previous file is kept beside the new one, in the same
    `plugins/UltiTools/pluginConfig/<module>/lang/` folder, as `<file>.bak` — for example
    `lang/en.json.bak`. If that name is taken, the backup is `<file>.1.bak`, then `<file>.2.bak`,
    and so on; an existing file is never overwritten. No backup name ends in `.json`, `.yml` or
    `.yaml`, so a backup is never loaded as a catalogue;
  - one WARNING per replaced file names the file and its backup;
  - **to restore your edit**, stop the server, delete or move the new `lang/<file>` and rename
    `<file>.bak` back to `<file>`, then start. The restored file now differs from the recorded
    hash, so it is treated as your customisation and kept on every later start (the
    `ultitools.language.file-preserve` rule);
  - a file byte-identical to the jar's copy is only recorded, with no backup and no log line; a
    file that already has a record keeps the rule in the previous entry; a second start changes
    nothing;
  - if the backup, the replacement or the record cannot be written (a read-only or symbolic-link
    file, a folder that is not writable), the original file stays in place, nothing is recorded,
    no backup is left behind, and the file is used as before, with the placeholder-count guard.
  The framework's own `lang/` catalogue is read from the framework jar and has never been
  extracted, so no framework file is affected. No per-release fingerprint list is used to spare
  edited files; that alternative was offered and not chosen. See
  `ultitools.language.unrecorded-replace` in `FEATURES.md`.

  中文补充：升级到 6.3.0 后第一次启动时，所有没有来源记录、且与新版模块 jar 自带版本不同的语言文件都会被替换为新版，
  服主改过的文件也会被替换（维护者于 2026-09-29 书面接受）。旧文件保留在同一个 `lang/` 目录下，名为 `<文件名>.bak`
  （名字已被占用时依次为 `<文件名>.1.bak`、`<文件名>.2.bak`……，绝不覆盖已有文件，也不会被当作语言文件读取），
  每替换一个文件，日志里有一行写明文件和备份。要恢复自己的修改：停服，把新文件移走，把 `.bak` 改回原名，再启动；
  恢复后的文件与记录不一致，会被当作服主的修改一直保留。
- Resolving a module's language only after its resources are extracted (#540). Before 6.3.0 the
  constructors resolved the language first and extracted the bundled resources second. With the
  multi-extension lookup added in 6.3.0 (#389), an operator who deleted `lang/<code>.json` to get a
  fresh copy, beside an older `lang/<code>.yml`, got the stale `.yml` at start-up and the fresh
  `.json` only at the next `/ul reload` — two catalogues from the same files, with nothing logged.
  As of 6.3.0 extraction runs first in both constructors, so the start-up catalogue is the one the
  next reload resolves (`ultitools.language.boot-resolve-after-extract`).
- Writing a module's language provenance only after the load gates accept it (#460). The
  refresh of an untouched `lang/` file, its provenance record and the #459 replacement used to run
  inside the module's constructor, before `PluginManager` decided whether to keep the candidate — so
  an older copy of a loaded module, or a module requiring a newer framework, rewrote the language
  file it shares with the accepted version and then was thrown away. As of 6.3.0 the constructor
  only computes the decision (and logs nothing about it); `PluginManager` commits it through the
  new `UltiToolsPlugin#commitLanguageProvenance()` right after the gates pass, on both `register`
  entry points. That method is `@ApiStatus.Internal` and public only because `PluginManager` is in
  another package, like `setContext`; a module never needs to call it. A module that registers
  through `PluginManager#register(UltiToolsPlugin)` gets this automatically. One thing is
  unchanged: a language file the candidate's jar ships and the disk lacks is still extracted, with
  its hash, while the candidate is constructed (`ultitools.language.rejected-candidate-untouched`).
- Unwinding a refused External Plugin API registration (#537). When
  `PluginManager#registerExternal` refused a plugin after recording its data-folder scope — the
  command-executor contract check, the config-binding refusal of #531, or a failed container
  `refresh()` — the scope, the entity ownership, the adapter's data scope and its context stayed
  behind, so a corrected connection of the same plugin in the same process was handed the stale
  scope. As of 6.3.0 the refusal closes the context and removes all four before rethrowing the same
  exception; nothing a caller observes changes except that the retry now works
  (`ultitools.boot.external-refusal-unwind`).
- Naming a missing required plugin instead of printing a class-not-found trace (#554). A module
  that cannot load because a plugin in its `plugin.yml` `depend:` list is not installed or not
  enabled used to log `Cannot initialize plugin for <main class>: <missing class>` with a
  `NoClassDefFoundError` trace. As of 6.3.0 that case logs one WARNING,
  `Module '<name>' requires <plugin>, which is not installed or not enabled; the module is not
  loaded.`, with no trace. Every other load failure keeps the old message and trace
  (`ultitools.boot.missing-required-plugin`). When the required plugin is not installed at all,
  the module is now refused before it is constructed: nothing of it runs, no resource is
  extracted and no class is scanned. One consequence: a module that lists an uninstalled plugin
  under `depend:` but never touched that plugin's classes while loading used to load anyway, and
  is now refused, as Bukkit itself refuses a plugin whose `depend:` is missing. A required
  plugin that is installed but not enabled yet is not refused early, because it may still be
  enabled after UltiTools; that case is refused only if loading fails, as before.
- Naming more callers in the economy unavailability warning (#462, #483, #489). The warning
  `Module '<name>' requested the economy service, but …` could name only a loaded module whose
  declared scan roots covered the calling class. As of 6.3.0 it also names a connected External
  Plugin API consumer (by plugin name), a module requesting the economy while it is still being
  registered (from its constructor, a `@PostConstruct` method or `registerSelf()`), and a module
  whose main class sits outside its declared roots (its own package counts as a root). A caller
  still unattributable is reported as `an unknown caller`, as before, but once per calling package
  rather than once for all of them, so a second one is no longer silenced
  (`ultitools.economy.attribute-caller`). Log wording and frequency only.
- Three internal methods added to published classes for the fixes above, each
  `@ApiStatus.Internal` and public only because the caller is in another package:
  `UltiToolsPlugin#commitLanguageProvenance()` (#460), `PluginManager#getConnectedExternalScanPackages()`
  (#462) and `PluginManager#getModuleBeingRegistered()` (#483). Additions only; no existing
  signature changed. A module never needs to call them.
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
  whatever its metadata says now (a file may have been replaced since the start, and a module
  registered from code has no `plugin.yml` the loader read). Every other entry of the modules folder is placed in exactly one of
  four states, each decided by reading the archive — with one exception, an entry the module loader
  itself would never load, judged by the same `.jar` test the start-up scan applies to this
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
- `/upm update` and `PluginInstallUtils.updatePlugin(String)` no longer replace a module's JAR while
  the server runs (#505, #513). Before 6.3.0 the update downloaded the new JAR into the modules
  folder, deleted the old one with `File#delete()` while ignoring its result, and returned `true` —
  reported as "Update successful" — whether or not the old JAR was gone, so a failed delete left two
  versions of the module to race at the next start. Now the update **takes effect at the next start
  and is committed only after that start shows the module loaded**: the command downloads the new JAR
  into `.ultikits/upm-transactions/` under the server root — beside the credential store and outside
  `plugins/`, where the panel's file interface cannot forge a record — and records it; the
  next start moves the old JAR aside (keeping it) and the new one in, before the module class loader
  is built; after the modules load, the update is kept only if the module is loaded from the new JAR
  at the new version, and otherwise the old JAR is restored and the new one removed, with one log
  line naming both versions. Nothing predicts before the restart whether a JAR will load. A move that
  fails leaves the modules folder as it was and is reported in the start-up log and again by the
  next `/upm update` of that module. The swap is an atomic rename, so when `plugins/` is on a
  different file system from the server root the start refuses it, changes nothing, and names both
  folders in one SEVERE line; there is no copy fallback. `updatePlugin(String)` keeps its signature; its `true` now
  means "staged", and `PluginInstallUtils.stageUpdate(String)` (`@ApiStatus.Internal`) returns what
  was staged or why nothing was. Nothing is staged, and `updatePlugin(String)` returns `false`, when
  another JAR in the modules folder declares the module's `plugin.yml` `main:` and sorts before the
  new JAR's file name: that copy would load instead at the next start, so the update could only be
  rolled back; the reply names it (maintainer follow-up 19). Likewise when two loaded modules in
  different JARs declare the same identify-string, since the update names a module only by that
  string. The update only ever moves, replaces or deletes a file whose SHA-256 matches its record;
  when another actor has changed one, it does nothing, keeps the record as `NEEDS_OPERATOR` with one
  SEVERE line, and refuses `/upm update` and `/upm uninstall` of that module until the record and its
  folder are deleted. Measured consumers: none of the fifteen module repositories or
  UltiTools-External-Example call either method (their `origin/master`, searched for
  `PluginInstallUtils`, `updatePlugin(` and `uninstallPlugin(`; the only hits are UAT documents
  naming the `/upm` commands). An uninstall that goes ahead also cancels an update of that module
  still waiting for the next start, and any download of one still running, on every outcome — its
  JARs deleted, recorded for deletion, not deletable, or a modules folder that could not be listed —
  matched on the identity it resolved (the unloaded instances' identify-strings and runtime names,
  the names it was given or their JARs declare, and their JARs), not only on the name typed.
  `PluginInstallUtils.uninstallPlugin(String)` does this too; the command reads the cancelled
  versions through `uninstallPluginReporting(String, List)` (`@ApiStatus.Internal`).
- `PluginInstallUtils.uninstallPlugin(String)` also deletes a JAR whose `plugin.yml` `main:` names
  the loaded module's main class, whatever `name:` it declares (#516). The module loader identifies
  a module JAR by that entry alone (since #548), and its start-up scan now records, per main class,
  every JAR that declares it; before 6.3.0 a second copy of a module whose `plugin.yml` declared a
  different `name:` survived the uninstall and loaded the module again at the next start. A main
  class another loaded module also has is never matched, a file is judged by what it declares at the
  time of the uninstall, and no class is read out of any archive.
- Module JARs are discovered in file-name order (#476). Before 6.3.0 the start-up scan and the module
  class loader took the modules folder in `File#listFiles()` order, which Java does not define (on
  ext4 it is hash order). The order decides which of several JARs carrying the same class supplies it,
  which copy of a duplicated module is read first, which module a `plugin.yml` `name:` shared by two
  modules resolves to, and the order of the opt-in legacy load (`-Dultitools.useLegacyPluginLoading`).
  Modules without a dependency between them already loaded in alphabetical order of their class names
  and still do. An install that relied on one copy winning by listing order may see the other one win
  after upgrading, once, and then the same one on every start and file system.
- Two or more JARs in the modules folder declaring the same `plugin.yml` `main:` class are reported by
  one start-up WARNING naming every one of them and the JAR the classes load from (UltiTools-Dev-Doc#96).
  The copies were never loaded side by side and still are not; before, each refused copy logged its
  own SEVERE line saying its main class belonged to another JAR. A JAR borrowing a class from a JAR that
  does not declare it keeps that SEVERE refusal.
- `PluginInstallUtils.uninstallPlugin(String)` no longer leaves a JAR it cannot delete for the
  operator to delete by hand (#518). On Windows the shared module class loader keeps every module
  JAR open while the server runs, so that instruction could not be followed. The uninstall now
  records such a JAR and the next start deletes it before any module loads, if it is still the
  recorded file (same SHA-256); the failure it raises is
  `PluginInstallUtils.RemovalDeferredException` (`@ApiStatus.Internal`), a
  `java.nio.file.FileSystemException` that names every recorded file as before. Only when the
  record cannot be written does the plain `FileSystemException` leave as it did.
- The modules folder is computed in one place, `<plugin data folder>/plugins` (#517). Before 6.3.0
  the start-up scan read `System.getProperty("user.dir")` + `/plugins/UltiTools/plugins` while the
  module class loader, install, update and uninstall read the data folder; on a server whose JVM was
  started from another working directory the scan found JARs the class loader did not hold, and
  `/upm` acted on a folder the scan did not read. The data folder follows Bukkit's own plugin
  directory, so a server started from its root is unaffected.

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

- `PluginManager#getPluginList()` returning an unmodifiable snapshot, and
  `PluginManager#unregister(UltiToolsPlugin)` delisting the module (#507). `getPluginList()` used to return the manager's live internal `ArrayList`, which callers had
  to mutate to delist a module `unregister` had unloaded, and which a reader on another thread (the
  asynchronous `/upm list`, the economy facade's module attribution) could see fail with a
  `ConcurrentModificationException` or a trailing `null` while the main thread unloaded a module.
  Mutating that list was never a documented contract; it was the mechanism of the defect. As of
  6.3.0 the method keeps its signature and return type and returns a copy taken at the moment of the
  call: it does not change afterwards, and `add`, `remove` and `clear` throw
  `UnsupportedOperationException`. Code that only reads or iterates it is unaffected — which, measured
  against the fifteen modules' `master` and UltiTools-External-Example, is every caller (two modules
  iterate it; none mutates it). `unregister` itself now removes the module from the loaded modules,
  by identity and whether or not the module's unload hook threw. It does not change the module's
  configuration entities: releasing them, so the shutdown save stops writing the files of a module
  unloaded while the server ran, is part of the configuration-layer rework in this release.
- Registrations released per module instance, and a superseded copy of a module unloaded through the
  full unload path (#506, #528). This path is reached only when code registers a newer instance of a
  loaded module through `PluginManager#register(...)`; two jars of one module in the modules folder
  never supersede each other. The older copy used to get only `unregisterSelf()`: its `@Scheduled`
  tasks kept running, its container stayed open and it stayed listed next to its replacement, and a
  failure thrown by its unload hook was reported as the **incoming** version failing to load and
  aborted that version's activation. As of 6.3.0 the older copy goes through `unregister` — tasks
  cancelled, container closed, registrations released, delisted — and a failure of its unload hook
  is logged against the older copy's name and version while the incoming copy goes on loading. As in
  `PluginManager#close()`, every `Exception` or `Error` the older copy throws counts as its own
  failure. Because both copies share a module name,
  `TabCompletionManager`, `EventBus` and `PanelResponderRegistry` can now also record the registering
  module instance, and `unregister` releases by instance first. The framework records it for
  everything a module registers in the three registries while it loads — during its container
  refresh, where `@PostConstruct` runs, and during `registerSelf()` — through the ordinary name-only
  methods, and for every `@ModuleEventHandler` method. A registration made later records it only
  through the new overloads that take the instance. A registration filed under the module's name
  only is released by name as before, except while another loaded copy shares that name, when it
  stays until the last copy of the name is unloaded. One visible consequence: a completer a module
  registers in `registerSelf()` is now released when the module unloads; before, only completers
  registered during the container refresh were. Added, all `@since 6.3.0`:
  `TabCompletionManager#beginRegistrationScope(String, UltiToolsPlugin)` and
  `#unregisterByOwnerInstance(UltiToolsPlugin)`; `EventBus#register(...)` and `EventBus#subscribe(...)`
  with an owner-instance parameter, `#beginRegistrationScope(UltiToolsPlugin)`,
  `#endRegistrationScope()` and `#unregisterByOwnerInstance(UltiToolsPlugin)`;
  `PanelResponderRegistry#registerResponder(...)` with an owner-instance parameter,
  `#beginRegistrationScope(UltiToolsPlugin)`, `#endRegistrationScope()` and
  `#unregisterByOwnerInstance(UltiToolsPlugin)`; two `HandlerEntry` constructors and a getter for
  the owner instance. Every name-keyed method keeps its signature and behaviour.
- `/ul reload` and a module's reload reporting what actually happened (#509, #529, #502). Before
  6.3.0 `reloadSelf()` logged `Module '<name>' reloaded.` before the module's `onReload()` ran and did
  not guard it, so a throwing hook printed the success line followed by a stack trace,
  `/ul reload <name>` answered only with the generic command-error line, and a bare `/ul reload`
  stopped at that module, leaving every module after it unreloaded. As of 6.3.0:
  - the per-module line is logged only after the hook returned; when any reload step or the hook
    throws, one SEVERE line names the module and the cause instead, and the failure is rethrown
    unchanged to the caller;
  - a bare `/ul reload` reloads every module in isolation — as in `PluginManager#close()`, every
    `Exception` or `Error` one module throws is that module's failure — and ends with a summary
    naming the modules that failed instead of `All plugins reloaded.`; the summary is also sent to
    the command's sender, which previously got no reply on success and the generic command-error
    line on failure;
  - `/ul reload <name>` replies failure, naming the module and the cause, when the reload threw;
  - a module can report a partial reload without throwing: the new public final class
    `ReloadReport`, the new hook `protected void onReload(ReloadReport report)` — whose default body
    calls `onReload()`, so a module overriding only `onReload()` behaves exactly as before — and the
    new `public final ReloadReport reloadWithReport()`, which runs the same reload as `reloadSelf()`
    and returns the report. `reloadSelf()` keeps its `public final void` signature. A partial
    reload logs a WARNING naming the parts instead of the success line, `/ul reload <name>` replies
    with those parts instead of the success reply, and the `/ul reload` summary lists the module
    with them;
  - a per-module reload whose framework `config.yml` on disk holds a different `language` than the
    one the framework runs with keeps the running language — one server-wide setting is not applied
    to one module — and reports the reload as partial, naming both values and that a full
    `/ul reload` applies it. A full `/ul reload` applies the new language to every module, as before.

  The new console lines and replies are in both shipped catalogues. A module that catches a failure
  in `onReload()` and only logs it keeps working unchanged; to make the operator see it, override
  `onReload(ReloadReport)` instead and record the failure with `report.partial(...)`.
- An existing credential file that cannot be read is preserved (#573). As of 6.3.0,
  `CommonUtils.getUltiToolsUUID()` fails with its declared `IOException`, naming the file, when the
  credential file exists but is empty, whitespace-only, the JSON literal `null` or not valid JSON,
  and it leaves the file untouched. 6.2.5 silently wrote a new server UUID over an empty `data.json`
  and let an unparseable one escape as an unchecked `JsonSyntaxException`. The same applies to an
  unreadable pre-6.3.0 `data.json` while no current credential file exists, and saving or clearing
  the UltiCloud token refuses instead of overwriting. The condition is logged once at `SEVERE`. A
  credential file that does not exist at all still starts a new identity. This corrects behaviour
  that contradicted the documentation: the method declares `IOException`, and silently replacing a
  torn credential file with a fresh identity is the defect the credential store exists to prevent.
  It follows the maintainer's rule for an unreadable configuration file (#470: treated like an
  unparseable one, never overwritten). A module that already handles the declared `IOException`
  needs no change. Separately, each credential write now forces the file and its directory to disk
  around the atomic rename, a performance change of a few milliseconds per write.
- Configuration writes follow the maintainer's rule that operator-written configuration is never
  overwritten automatically (2026-10-04, "what code may write, by file type";
  [#599](https://github.com/UltiKits/UltiTools-Reborn/issues/599)). Changed without a migration period,
  because a migration period would mean continuing to overwrite operators' files: a module's `save()`
  writes only what the module changed, where the file still holds what was read, and never writes over an
  operator's disk edit, deleted key or unusable value (it names the change as not written instead of
  overwriting with a warning); a panel edit writes only what it touches; and nothing writes configuration at
  server stop, module unload or module replacement - a never-saved module change is named at stop and
  dropped, and `ConfigManager#saveAll()` is deprecated and writes nothing. A module that relied on the stop
  to persist an in-memory change must call `save()` (or `saveOperatorChange`) when it makes the change; the
  stop warning names any configuration that still holds one.
- The framework's own `plugins/UltiTools/config.yml` gets its missing panel keys (the capability switches,
  `ultipanel.commands.blocklist`, `ultipanel.files.editable-roots` and the action-log rotation keys) through the
  same write gate, insert only ([#605](https://github.com/UltiKits/UltiTools-Reborn/issues/605)). Before 6.3.0
  the start-up migration re-emitted the whole file: hex numbers became decimal, anchors were expanded, a dotted
  key such as `o.O` was split into a nested map, and an explicit `null` was replaced. Now every other byte
  stays, measured on every released 6.2.x `config.yml`; a file the gate cannot write byte-identically (hand
  alignment, anchors) is left as it is with one WARNING naming the keys, and the defaults shipped in the jar's
  `config.yml` (which now also lists the blocklist and the editable roots) answer for them.
- UltiTools starts bStats only when `plugins/bStats/config.yml` is absent or already holds `serverUuid`
  ([#606](https://github.com/UltiKits/UltiTools-Reborn/issues/606)). That file is shared by every bStats plugin;
  before 6.3.0 an unparseable copy was read as empty and bStats' defaults were saved over it. A file that cannot
  be read or parsed, or one without `serverUuid`, is now left byte-identical, one line names it, and UltiTools'
  metrics stay off for that run (other plugins' own bStats copies are outside the framework).

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

### Command bodies run at dispatch (6.3.0) — changed without a migration period

This is a change "in the timing of a side effect", the last kind listed above, made in 6.3.0 without
the two-step period by the maintainer's decision of 2026-09-29 (#541), which accepted that it changes
command timing for **every module, third-party modules included**.

**What changed.** Before 6.3.0, `BaseCommandExecutor` ran every synchronous command body one tick after
the command was dispatched. As of 6.3.0 the body runs **at dispatch**, inside the call that dispatched
it, whenever that call is on the server's primary thread — which is where Bukkit dispatches everything
a player, the console, a command block, a command minecart, RCON or the panel sends. It is handed to
`runTask` only when `onCommand` is called from another thread, as Bukkit's own commands behave. An
`@AsyncCommand` or `@RunAsync` body is unchanged: it always runs asynchronously. The deferral had no
recorded reason: it came from sharing one `BukkitRunnable` with `@RunAsync` in 6.0.0.

**Why.** Paper 1.21.11 records a command block's output only while its dispatch is open, and RCON
reads its output as soon as the dispatch returns, so a deferred body's replies to those senders were
lost. (The panel's remote command runs as the server console, whose replies no sender can read; they
reach the panel through the log stream instead — see "The panel's log stream mirrors the server
console" below.) The deferral also let two dispatches in one tick both pass
`@CmdCD`, because the cooldown was recorded when the deferred body finished; the cooldown is now
recorded before the first dispatch returns.

**What a module author has to check.**

- Code that relied on the one-tick delay — for example a command body that expected the dispatching
  event to have finished first — must schedule that work itself with `runTask`.
- **Paper's rule for inventory clicks.** An `InventoryClickEvent` handler (including a GUI library's
  `onClick` callback) must not open or close an inventory directly, and must not call
  `performCommand` or `Bukkit.dispatchCommand` directly either: a module command dispatched there now
  runs its body — which may open or close an inventory — inside the click event. Defer the call with
  `Bukkit.getScheduler().runTask(...)`. The fifteen UltiKits modules were surveyed; the handlers that
  did this are tracked as UltiMenu#28, UltiSocial#27, UltiKits#41, UltiMail#43 and UltiWorlds#50.
- **A body that dispatches another command** runs the nested body on the same thread before its own
  has finished. The audit user (`AuditableDataEntity`'s current user, written into `created_by` /
  `updated_by`) is saved before each body and restored after it: the nested body sees its own sender
  (none for a sender that is not a player), the outer body sees its own sender again afterwards, and a
  thread that carried no user before the outermost command carries none after it. Before 6.3.0 the
  body cleared the user when it finished.
- **`@UsageLimit` and re-entry.** A body that dispatches its own command while holding its lock gets
  the nested dispatch refused with the ordinary lock message (`SENDER`: from the same sender; `ALL`:
  from any sender). Acquiring never waits, so nothing blocks; the outer lock is released when the
  outer body returns, normally or by throwing. Schedule the nested call with `runTask` if it must run.
  A body that dispatches its own command **without** `@UsageLimit` and without a stopping condition
  now recurses on the main thread until the stack overflows; before 6.3.0 it repeated once a tick.
- **Two dispatches in one tick meet the cooldown.** The second of two dispatches of a `@CmdCD` command
  by one player in the same tick is refused.

### Other command and task runtime changes (6.3.0) that need no migration period

Each corrects behaviour that contradicted the documentation or left state held by mistake.

- **Active `@CmdCD` cooldowns are kept per executor instance** as well as per mapping (#539). One
  `CooldownValidator` shared by two executors of one class — two modules passing one `ValidatorChain`
  — no longer refuses a player on executor B for a use through executor A. `clearCooldown(UUID, String)`
  and `getRemainingCooldown(UUID, String)` keep working unchanged for one executor per validator, which
  is the shape both `BaseCommandExecutor` constructors create; when a validator serves several
  executors they span all of them (the longest remaining time, and every executor's cooldown
  cleared). The new overloads `clearCooldown(UUID, Object, String)` and
  `getRemainingCooldown(UUID, Object, String)` address one executor. The executor is held weakly, so
  an active cooldown never keeps an unloaded module's executor reachable.
- **A `@UsageLimit` lock is released when the dispatch is refused after it was taken** (#568): by the
  cooldown (which validates after the lock), by the argument-count check, because a parameter did
  not parse, or by an exception from a later validator, a parameter parser or the scheduler. Before 6.3.0 the lock stayed held until the player quit, and every later call of the
  mapping was refused. The mechanism is a new default method, `CommandValidator#onRefused`, called for
  each validator that passed; its default does nothing, so existing validators are unaffected, and a
  refused dispatch runs no `onComplete`, so it applies no cooldown. Every `onRefused` and every
  `onComplete` hook now runs even when an earlier validator's hook throws; the first exception is
  rethrown afterwards.
- **`@Scheduled` methods declared on a superclass of a bean are scheduled** (#532), as the annotation's
  javadoc always said. An overridden method is scheduled once, with the most derived declaration's
  annotation; an override without `@Scheduled` is not scheduled. A method that previously never ran
  because an abstract base declared it now runs; none of the fifteen UltiKits modules declares one.

### Panel log stream and panel reply changes (6.3.0) that need no migration period

Each corrects a declared behaviour the stream did not deliver. The panel protocol is unchanged.

- **`ultipanel.logging.excluded-loggers` ships empty** (#485). The six defaults before 6.3.0
  (`com.mojang.authlib`, `net.minecraft.network`, `org.apache.http`, `com.zaxxer.hikari`,
  `org.eclipse.jetty`, `ErrorReportCollector`) could never match: the stream then received only
  `java.util.logging` records, whose logger names are `Minecraft` (everything logged through
  `Bukkit.getLogger()`), a plugin's own name, or `com.ultikits.ultitools.*`; those libraries log
  through Log4j or SLF4J, and `ErrorReportCollector` never logs through JUL. A configured list is now
  used as given. Nothing that reached the stream before is filtered differently. With the console
  mirror below, Log4j lines reach the stream too, under their Log4j logger names, and a configured
  entry applies to them as well; the default stays empty so the stream shows the whole console.
- **A log batch whose send fails is held and sent first** (#486), on the transmitter's own sender and
  on the `batch_update` drain; no newer record is drained while one is held. Delivery stays best
  effort: a batch whose connection drops just after it was written may arrive twice. Records the
  full queue (1000 records) discards are counted and reported by one WARNING at most once a minute,
  in the server log only. `UltiPanelLogTransmitter#holdUndelivered(JsonArray)` is added to that
  internal class.
- **Records logged before the stream starts reach it** (#487). From `onLoad` until the panel
  connection opens, records are kept in a start-up buffer (2000 records, an estimated 512 KiB, five
  minutes) and sent, oldest first, when the stream starts, in `log_batch` messages of at most
  64 KiB, the first at once and then about one per second, independent of the
  `ultipanel.logging.batch.*` keys: a full buffer drains in seconds and uses at most 10 of the panel's
  50 messages per 10 seconds. Each entry keeps the time its record was logged, a single entry too
  large for one message is shortened (stack trace first) rather than dropped, and a live record
  logged meanwhile can arrive before the last replay messages. The buffer applies the stream's
  filters as records arrive and is released without sending anything when there is no cloud login
  or when its time is up; with the `logs` capability off it is not attached and keeps nothing.
- **Lines about the panel connection are no longer sent to the panel.** The panel's `error` replies
  and notifications, inbound messages the framework cannot use, the WebSocket client's connect,
  disconnect, heartbeat and reconnect lines, and the warnings about a message that could not be sent
  are written to the server console as before, but the log stream drops them. With
  `ultipanel.logging.batch.enabled: false`, each logged `error` reply used to be streamed, rejected
  by the panel's quota and replied to again: 42,066 `[WebSocket error] Rate limit exceeded` lines in
  one measured run. A panel view that showed these lines no longer receives them.
- **The panel's log stream mirrors the server console** (maintainer decision, 2026-10-03). Paper prints
  its own output through Log4j — command feedback, a module's reply to the console sender, joins and
  quits, chat, vanilla warnings and errors, and player command lines — and before 6.3.0 none of it
  reached the panel, because the stream listened only to `java.util.logging`. The framework now
  installs an appender on Log4j's root logger at load (with the `logs` capability on) and removes it at
  disable; each line passes the same filters, batching and start-up replay as a plugin line, without
  ANSI colour codes. **The stream shows exactly what the console shows, including player command lines
  with their arguments** (`<player> issued server command: /login <password>` included): the panel is
  at the console's trust level, so whatever the console shows the panel may show. A plugin line
  arrives once, although Paper also copies it into Log4j; lines about the panel connection, the
  transmitter's own lines and the WebSocket library's (`org.java_websocket.*`) are never sent. If the
  server's Log4j configuration uses asynchronous loggers, the mirror is not installed, a console
  WARNING says so, and the stream carries plugin lines only. A Log4j `ERROR` line with an exception is
  now also reported to UltiPanel's error collection, once. **New `provided` dependency:**
  `org.apache.logging.log4j:log4j-core` (2.24.1, with `log4j-api` 2.24.1 declared alongside), which
  Paper supplies at runtime; it is not shaded, and a module needs nothing new.
- **The panel's remote command result no longer claims to carry the command's output.** A panel
  command is typed into the server console: it runs as the server's own console sender, unchanged for
  modules. Paper 1.21.11 replaces any console sender with the real console before running a command,
  so the framework's output capture never received a reply, and every `command_result` read the
  invented `Command executed successfully`. The result now reads `Command dispatched to the server
  console. Its output appears in the server log stream.`, or `The server console did not accept the
  command. Any message it printed appears in the server log stream.` when the dispatch returned
  false; blocklist refusals, an empty command and dispatch errors are unchanged. The replies themselves
  appear in the log stream (the item above). A panel or tool that showed `output` as the command's
  reply now shows this sentence.
- **The `server.properties` refusal for a key the file does not hold** now reads `This key is not in
  this server's server.properties` instead of `This server version has no such key` (#473): nothing
  tells a key the running version lacks from one the file omits. A panel or tool that matched on the
  old text must match the new one; the UltiPanel worker and frontend do not match on it.
- **A panel edit of `server.properties` changes only the line of the key it names** (#607). Before
  6.3.0 the file was read as ISO-8859-1 and re-emitted whole: every comment dropped, keys reordered,
  and UTF-8 values of other keys (a `motd` with `§` or Chinese text) turned into mojibake. The value
  text of that one line is now replaced - key, separator, comments, order and every other byte kept -
  decoded and encoded as the server reads the file (strict UTF-8, ISO-8859-1 when it is not UTF-8),
  checked, and written atomically. A key defined on more than one line, or continued onto the next
  line, is refused with a reason naming the lines (no value); `set_all` lists it under `failed`.
  The server itself still re-writes the whole file when it next starts, as every Paper version does.

### Framework text follows `language`; the class-load audit is quiet on a clean start (6.3.0) that need no migration period

Each corrects a declared behaviour the framework did not deliver. The panel protocol is unchanged.

- **The framework's own console, reply and panel-stream text follows `language`** (#556). Until 6.3.0 about 150
  lines of framework text were Chinese string literals that never went through `lang/*.json`, so an English
  server still printed and sent them in Chinese. They now resolve through the catalogue (`en.json` gives the
  English; under `language: zh` the text is unchanged): the reply every command sender gets when a module's
  command body throws (`Command execution failed: <reason>`), the default processing notice of an
  `@AsyncCommand` (`Processing...`), the framework's console lines (server status monitoring, log transmission,
  WebSocket message handling, remote command and file operation logging), the lines it streams to the panel
  (player join, quit and chat, plugin actions, the online-player count), and the `server.properties`
  batch-failure text returned to the panel. A tool that matched one of these lines by its Chinese text on a
  server running `language: en` must match the English text; the UltiPanel worker and frontend do not match on
  any of them (their sources were searched, and the only hits were comments and the panel's own strings).
  The verification e-mail stays bilingual on purpose, because its recipient's language is not the server's.
  The default teleport service's display name, `InMemeryTeleportService#getName()`, is now `TeleportService`
  (it was `传送服务`, the only Chinese service name); its `getResourceFolderName()` still returns `传送服务`, so
  an existing install keeps its folder. Nothing in the framework or the fifteen modules looks the service up by
  its name.
  English log messages that carried full-width punctuation (`Configuration save failed！File path：…`,
  `… load failed！`) now use ASCII punctuation; a tool that matched the full-width form must be updated.
  `FrameworkText` (`com.ultikits.ultitools.utils`, `@ApiStatus.Internal`) is added for this.
- **The class-load audit is quiet on a clean start** (#557). The audit that reports which classes the name-based
  filters removed in 6.3.0 would have refused printed one line per module, twice per module, as two `WARN` lines
  each (it wrote to the standard error stream, which Paper prints as `WARN`), naming an internal requirement
  code. It now reaches the server log through the plugin logger: a module for which nothing would have been
  refused is logged at `FINE` on the audit's own logger and is not forwarded to the console, and a module with at least one such class gets ONE `INFO`
  line naming the jar and the count. The module-scan diagnostics use the same route; their `SEVERE` summary for
  a skipped class is unchanged in level and content. `SecurityPolicy`'s one-time deprecation warning no longer
  carries the internal code either.

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
