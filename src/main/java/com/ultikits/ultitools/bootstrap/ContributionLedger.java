package com.ultikits.ultitools.bootstrap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.text.MessageKey;

/**
 * The framework-owned {@code bootstrap/contributions.json} record, read once and atomically replaced
 * before Paper's write events. It describes declarations, not a snapshot of world data. This is a
 * derived record, not operator configuration; neither the write nor quarantine targets other files.
 */
final class ContributionLedger {
    private static final String NAME = "contributions.json";
    private static final String UNREADABLE = NAME + ".unreadable-";
    private static final int SCHEMA = 1;
    private static final int KEEP_UNREADABLE = 3;
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    static final ContributionLedger EMPTY = new ContributionLedger(Map.of(), Map.of(), Map.of(), Set.of(), false);

    private final Map<String, Row> rows;
    private final Map<String, List<ContentEntry>> dropped;
    private final Map<String, List<ContentEntry>> removed;
    private final Set<String> successful;
    private final boolean unreadable;

    private ContributionLedger(Map<String, Row> rows, Map<String, List<ContentEntry>> dropped,
                               Map<String, List<ContentEntry>> removed, Set<String> successful, boolean unreadable) {
        this.rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
        this.dropped = Collections.unmodifiableMap(new LinkedHashMap<>(dropped));
        this.removed = Collections.unmodifiableMap(new LinkedHashMap<>(removed));
        this.successful = Set.copyOf(successful);
        this.unreadable = unreadable;
    }

    Map<String, Row> rows() { return rows; }
    Map<String, List<ContentEntry>> dropped() { return dropped; }
    Map<String, List<ContentEntry>> removed() { return removed; }
    Set<String> successful() { return successful; }
    boolean unreadable() { return unreadable; }

    static ContributionLedger update(Path dataFolder, ModuleJarAdmission admission, ContributionRegistry registry,
                                     Logger logger) {
        Set<String> installed = new LinkedHashSet<>();
        admission.entries().forEach((jar, entry) -> {
            if (entry.isApiAllowed()) {
                installed.add(ModuleBootstraps.moduleName(jar, entry.getMetadata()));
            }
        });
        Map<String, List<ContentEntry>> declared = new LinkedHashMap<>();
        for (var module : registry.modules()) {
            if (!registry.failed(module.jar())) {
                declared.merge(module.name(), module.ledgerEntries(), ContributionLedger::union);
            }
        }
        return update(dataFolder, new Start(declared, installed, registry.withoutHooks()), Instant.now(), logger,
                ContributionLedger::writeBytes);
    }

    private static List<ContentEntry> union(List<ContentEntry> first, List<ContentEntry> second) {
        Set<ContentEntry> entries = new LinkedHashSet<>(first);
        entries.addAll(second);
        return List.copyOf(entries);
    }

    static ContributionLedger update(Path dataFolder, Start start, Instant now, Logger logger, RecordWriter writer) {
        Path root = BootstrapFiles.root(dataFolder);
        Path file = root.resolve(NAME);
        ReadResult previous = read(file, now, logger);
        ContributionLedger merged = merge(previous, start, now);
        if (!merged.rows.equals(previous.rows())) {
            try {
                Files.createDirectories(dataFolder);
                BootstrapFiles.ensureDirectory(root);
                replace(file, merged.rows, writer);
            } catch (IOException | RuntimeException failure) {
                ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Could not save the custom content record ("
                        + file + "): " + ModuleBootstraps.describe(failure) + '.', failure);
            }
        }
        return merged;
    }

    private static ContributionLedger merge(ReadResult previous, Start start, Instant instant) {
        String now = instant.toString();
        Map<String, Row> rows = new LinkedHashMap<>(previous.rows());
        Map<String, List<ContentEntry>> missing = new LinkedHashMap<>();
        Map<String, List<ContentEntry>> dropped = new LinkedHashMap<>();
        rows.replaceAll((name, row) -> {
            if (start.installed().contains(name) || !row.present()) {
                return row;
            }
            if (!row.entries().isEmpty()) {
                missing.put(name, row.entries());
            }
            return new Row(row.namespace(), false, row.lastStart(), now, row.entries(), List.of());
        });
        start.withoutHooks().forEach(name -> {
            if (rows.containsKey(name) && !start.declared().containsKey(name)) {
                replaceRow(rows, dropped, name, List.of(), now);
            }
        });
        start.declared().forEach((name, entries) -> replaceRow(rows, dropped, name, entries, now));
        return new ContributionLedger(rows, dropped, missing, start.declared().keySet(), previous.unreadable());
    }

    private static void replaceRow(Map<String, Row> rows, Map<String, List<ContentEntry>> dropped,
                                   String name, List<ContentEntry> entries, String now) {
        Row before = rows.get(name);
        Set<ContentEntry> kept = Set.copyOf(entries);
        List<ContentEntry> missing = before == null ? List.of() : before.entries().stream()
                .filter(entry -> !kept.contains(entry)).toList();
        if (entries.isEmpty() && missing.isEmpty()) {
            rows.remove(name);
            return;
        }
        String namespace = entries.isEmpty() && before != null ? before.namespace() : MessageKey.namespaceFor(name);
        rows.put(name, new Row(namespace, true, now, null, List.copyOf(entries), missing));
        if (!missing.isEmpty()) {
            dropped.put(name, missing);
        }
    }

    private static ReadResult read(Path file, Instant now, Logger logger) {
        try {
            if (Files.notExists(file.getParent(), NOFOLLOW_LINKS)) {
                return new ReadResult(Map.of(), false);
            }
            BootstrapFiles.requireDirectory(file.getParent());
            if (Files.notExists(file, NOFOLLOW_LINKS)) {
                return new ReadResult(Map.of(), false);
            }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                throw new IOException("not a framework-owned record file");
            }
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return new ReadResult(decode(BootstrapJson.readObject(reader)), false);
            }
        } catch (IOException | RuntimeException failure) {
            ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Could not read the custom content record ("
                    + file + "): " + ModuleBootstraps.describe(failure)
                    + "; removal warnings may be incomplete until every module has started once.", failure);
            quarantine(file, now, logger);
            return new ReadResult(Map.of(), true);
        }
    }

    private static Map<String, Row> decode(JsonObject json) throws IOException {
        JsonElement schema = json.get("schema");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                || !Integer.toString(SCHEMA).equals(schema.getAsString())) {
            throw new IOException("unrecognized custom content record schema: " + schema);
        }
        Map<String, Row> result = new LinkedHashMap<>();
        for (var entry : json.getAsJsonObject("modules").entrySet()) {
            try {
                if (!entry.getValue().getAsJsonObject().getAsJsonPrimitive("present").isBoolean()) {
                    throw new IllegalArgumentException("present must be a boolean");
                }
                result.put(entry.getKey(), GSON.fromJson(entry.getValue(), Row.class));
            } catch (RuntimeException failure) {
                throw new IOException("invalid custom content record for module '" + entry.getKey() + "'", failure);
            }
        }
        return result;
    }

    private static void quarantine(Path file, Instant now, Logger logger) {
        try {
            BootstrapFiles.requireDirectory(file.getParent());
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                return;
            }
            String suffix = String.format(Locale.ROOT, "%019d-%s", now.toEpochMilli(), UUID.randomUUID());
            Files.move(file, file.resolveSibling(UNREADABLE + suffix));
            try (var files = Files.list(file.getParent())) {
                var old = files.filter(path -> path.getFileName().toString().startsWith(UNREADABLE))
                        .filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS))
                        .sorted(Comparator.reverseOrder()).skip(KEEP_UNREADABLE).toList();
                for (Path path : old) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException | RuntimeException failure) {
            ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Could not preserve the unreadable content record ("
                    + file + "): " + ModuleBootstraps.describe(failure), failure);
        }
    }

    private static void replace(Path file, Map<String, Row> rows, RecordWriter writer) throws IOException {
        if (Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            throw new IOException("not a framework-owned record file: " + file);
        }
        byte[] bytes = (GSON.toJson(new Snapshot(SCHEMA, rows)) + '\n').getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), ".contributions-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, WRITE)) {
                writer.write(channel, bytes);
                channel.force(true);
            }
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void writeBytes(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    @FunctionalInterface
    interface RecordWriter {
        void write(FileChannel channel, byte[] bytes) throws IOException;
    }

    record Start(Map<String, List<ContentEntry>> declared, Set<String> installed, Set<String> withoutHooks) { }

    record Row(String namespace, boolean present, String lastStart, @Nullable String removedAt,
               List<ContentEntry> entries, List<ContentEntry> dropped) {
        Row {
            if (namespace.isBlank() || present == (removedAt != null)) {
                throw new IllegalArgumentException("inconsistent namespace or removal timestamp");
            }
            Instant.parse(lastStart);
            if (removedAt != null) {
                Instant.parse(removedAt);
            }
            entries = List.copyOf(entries);
            dropped = List.copyOf(dropped);
        }
    }

    private record Snapshot(int schema, Map<String, Row> modules) { }
    private record ReadResult(Map<String, Row> rows, boolean unreadable) { }
}
