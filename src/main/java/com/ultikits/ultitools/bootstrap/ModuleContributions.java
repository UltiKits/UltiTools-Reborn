package com.ultikits.ultitools.bootstrap;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.bukkit.Keyed;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.utils.ModuleContentKind;

import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;

/** Module-local declarations: only a sealed buffer from a successful hook reaches Paper. */
final class ModuleContributions implements EnchantmentChannel, TagChannel, DatapackChannel {
    static final Pattern PACK_NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final String name;
    private final Path jar;
    private final Map<Key, EnchantmentDefinition> enchantments = new LinkedHashMap<>();
    private final List<TagChange> tags = new ArrayList<>();
    private final Set<String> packs = new LinkedHashSet<>();
    private boolean sealed;

    ModuleContributions(String name, Path jar) {
        this.name = name;
        this.jar = jar;
    }

    String name() { return name; }
    Path jar() { return jar; }
    String namespace() { return MessageKey.namespaceFor(name); }
    EnchantmentChannel enchantments() { return this; }
    TagChannel tags() { return this; }
    DatapackChannel datapacks() { return this; }

    synchronized void seal() { sealed = true; }
    boolean isSealed() { return sealed; }
    Map<Key, EnchantmentDefinition> enchantmentsByKey() { return Collections.unmodifiableMap(enchantments); }
    List<TagChange> tagChanges() { return Collections.unmodifiableList(tags); }
    Set<String> packNames() { return Collections.unmodifiableSet(packs); }
    String packId(String pack) { return namespace() + '.' + pack; }

    @Override
    public synchronized void register(Key key, EnchantmentDefinition definition) {
        requireOpen();
        Key entry = copyKey(key);
        requireNamespace(entry);
        if (enchantments.putIfAbsent(entry, Objects.requireNonNull(definition, "definition")) != null) {
            throw new IllegalArgumentException("Enchantment '" + entry.asString() + "' is already declared");
        }
    }

    @Override
    public synchronized <T extends Keyed> void addToTag(RegistryKey<T> registry, Key tag, Collection<Key> values) {
        declareTag(registry, tag, values, false);
    }

    @Override
    public synchronized <T extends Keyed> void setTag(RegistryKey<T> registry, Key tag, Collection<Key> values) {
        declareTag(registry, tag, values, true);
    }

    private void declareTag(RegistryKey<?> registry, Key tag, Collection<Key> values, boolean replace) {
        requireOpen();
        Objects.requireNonNull(registry, "registry");
        if (!SUPPORTED.contains(registry)) {
            throw new IllegalArgumentException("Unsupported tag registry '" + registry.key().asString()
                    + "'; use the item or enchantment registry");
        }
        Key tagKey = copyKey(tag);
        if (replace) {
            requireNamespace(tagKey);
        } else {
            namespace(); // Tag additions also have to be attributed to a valid module namespace.
        }
        tags.add(new TagChange(registry, tagKey, values.stream().map(ModuleContributions::copyKey).toList(), replace));
    }

    @Override
    public synchronized void embedded(String pack) {
        requireOpen();
        if (pack == null || !PACK_NAME.matcher(pack).matches()) {
            throw new IllegalArgumentException("Invalid data pack name '" + pack + "'; use [a-z0-9_-]{1,32}");
        }
        if (packs.contains(pack)) {
            throw new IllegalArgumentException("Data pack '" + pack + "' is already declared");
        }
        namespace(); // Validate the namespace before using it in the cache file name.
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            validatePack(archive, pack);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Data pack '" + pack + "' in module '" + name + "': "
                    + failure.getMessage(), failure);
        }
        packs.add(pack);
    }

    static void validatePack(ZipFile archive, String pack) throws IOException {
        String metadata = "datapacks/" + pack + "/pack.mcmeta";
        var entry = archive.getEntry(metadata);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("missing " + metadata);
        }
        try (var reader = new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
            BootstrapJson.readObject(reader);
        } catch (IOException | RuntimeException failure) {
            throw new IOException(metadata + " must contain a valid JSON object", failure);
        }
    }

    List<ContentEntry> ledgerEntries() {
        Set<ContentEntry> entries = new LinkedHashSet<>();
        enchantments.keySet().forEach(key -> entries.add(new ContentEntry(ModuleContentKind.ENCHANTMENT, key.asString())));
        tags.forEach(tag -> entries.add(tag.entry()));
        packs.forEach(pack -> entries.add(new ContentEntry(ModuleContentKind.DATAPACK, "UltiTools/" + packId(pack))));
        return List.copyOf(entries);
    }

    List<ContentEntry> exclusiveEntries() {
        Set<ContentEntry> entries = new LinkedHashSet<>(ledgerEntries());
        entries.removeIf(entry -> entry.kind().startsWith(ContentEntry.TAG_PREFIX));
        tags.stream().filter(TagChange::replace).forEach(tag -> entries.add(tag.entry()));
        return List.copyOf(entries);
    }

    private void requireNamespace(Key key) {
        String expected = namespace();
        if (!expected.equals(key.namespace())) {
            throw new IllegalArgumentException("Key '" + key.asString() + "' must use the module namespace '" + expected + "'");
        }
    }

    private void requireOpen() {
        if (sealed) {
            throw new IllegalStateException("bootstrap channels are closed after bootstrap() returns");
        }
    }

    private static Key copyKey(Key key) {
        return Key.key(key.namespace(), key.value());
    }

    record TagChange(RegistryKey<?> registry, Key tag, List<Key> values, boolean replace) {
        ContentEntry entry() {
            return new ContentEntry(ContentEntry.TAG_PREFIX + registry.key().asString(), tag.asString());
        }
    }
}
