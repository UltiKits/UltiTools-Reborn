package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.config.document.ConfigLoadResult;
import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The three structured-configuration requests of protocol v2: browse the registered configuration
 * files, read one file's typed entries with the version of its bytes, and write exactly the keys the
 * panel names - only while the file still holds the bytes the panel read.
 *
 * <p><b>Why this cannot overwrite operator content.</b> Nothing here edits a file by rendering a new
 * whole document. A write names the paths it owns ({@link OwnedPaths}) and goes through
 * {@link OperatorFileWriter}, which re-reads the file at write time, applies only those settings,
 * renders the document from its own node tree and then refuses the write unless every line outside
 * the owned settings - comments, order, quoting, other values - comes out byte-identical. The panel's
 * {@code baseVersion} is the SHA-256 of the raw bytes it read; the same value is handed to the gate as
 * the expected fingerprint, so an operator's edit made after the read answers {@code file_changed} and
 * nothing is written. A file the operator marked read-only is refused before anything is staged.</p>
 *
 * <p>The only write path here is an explicit panel edit of the named keys, guarded by that version;
 * nothing in this class writes at start-up, at shutdown, on a reload or on a timer. The values are
 * checked per key before the write - the entry's declared type and its {@code @Range} bounds here, and
 * the entity's own validators ({@code @Range}, {@code @NotEmpty}, {@code @Size}, {@code @Pattern} and
 * its declared-type converters) through {@link AbstractConfigEntity#validateProposedProperties} - so a
 * refused key never reaches the file and the accepted ones are written in one verified replace.</p>
 *
 * <p>All file work runs on the thread the caller injects, which must be the server thread: the
 * configuration layer refuses registry access anywhere else
 * ({@link ConfigManager#permitsConfigThread}). Payload validation runs on the dispatching thread so a
 * malformed request still becomes the router's correlated {@code invalid_message}.</p>
 *
 * <p>Known limitation (niche case, documented rather than defended): a writer outside the framework
 * that replaces the file between the gate's last read and its atomic rename is not detected; the gate's
 * own re-read immediately before publishing is what narrows that window.</p>
 *
 * @since 6.3.1
 */
final class RemoteConfigHandler {

    private static final Logger LOGGER = Logger.getLogger(RemoteConfigHandler.class.getName());

    /** One monitor per configuration file, so a read-hash-compare-write of a file never interleaves with another. */
    private static final Map<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    /** {@code config.entries.result} carries at most this many entries. */
    private static final int ENTRIES_MAX = RemoteConfigEntries.ENTRIES_MAX;
    /** {@code config.tree.result} bounds. */
    private static final int TREE_PLUGINS_MAX = 1000;
    private static final int TREE_FILES_MAX = 200;
    /** {@code config.update} bounds. */
    private static final int CHANGES_MAX = 500;
    private static final int KEY_MAX = 256;
    private static final int TEXT_MAX = 8192;
    private static final int LIST_MAX = 500;
    private static final int LIST_ITEM_MAX = 1024;
    private static final int MESSAGE_MAX = 500;

    private static final String PATH_NOT_ALLOWED = "Only configuration files registered by a loaded module can be read or edited.";
    private static final String NOT_FOUND = "The configuration file does not exist.";
    private static final String CONFIG_INVALID = "The configuration file is not valid YAML.";
    private static final String UNREADABLE = "The configuration file cannot be read.";
    private static final String FILE_CHANGED = "The file changed since it was read; read its entries again.";
    private static final String WRITE_DENIED = "The server cannot write this configuration file.";
    private static final String NOT_RELOADED = "Saved to the file, but the module did not reload it (";
    private static final String INTERNAL = "The request could not be processed.";

    private final Supplier<List<UltiToolsPlugin>> modules;
    private final ConfigManager configs;
    private final Executor configThread;
    private final File serverRoot;

    /**
     * Creates the handler that publishes paths relative to the process working directory, the root the
     * remote file surface already uses.
     *
     * @param modules      the loaded modules whose registered configuration files may be shown
     * @param configs      the configuration registry, for listing registered files and reloading a module
     * @param configThread the executor that must run the server thread
     */
    RemoteConfigHandler(Supplier<List<UltiToolsPlugin>> modules, ConfigManager configs, Executor configThread) {
        this(modules, configs, configThread, new File(System.getProperty("user.dir")));
    }

    /**
     * Creates the handler with an explicit server root; tests only, since the panel's paths are the
     * paths a server operator would see under the working directory.
     *
     * @param modules      the loaded modules whose registered configuration files may be shown
     * @param configs      the configuration registry
     * @param configThread the executor that must run the server thread
     * @param serverRoot   the directory every published path is relative to
     */
    RemoteConfigHandler(Supplier<List<UltiToolsPlugin>> modules, ConfigManager configs, Executor configThread, File serverRoot) {
        this.modules = modules;
        this.configs = configs;
        this.configThread = configThread;
        this.serverRoot = serverRoot;
    }

    /**
     * Registers the three request types on the protocol router.
     *
     * @param router the router that dispatches inbound frames
     */
    void register(AgentMessageRouter router) {
        router.register("config.tree", this::handleTree);
        router.register("config.entries", this::handleEntries);
        router.register("config.update", this::handleUpdate);
    }

    /** Answers the configuration file tree: each loaded module with the files it registered. */
    private void handleTree(CloudRequest request, CloudChannel channel) {
        new PayloadReader(request.id(), request.payload()).keys(new String[0]);
        String replyTo = request.id();
        onConfigThread(channel, replyTo, "config.tree.result", () -> {
            try {
                channel.replyOk(replyTo, "config.tree.result", tree());
            } catch (RuntimeException failure) {
                failed(channel, replyTo, "config.tree.result", failure);
            }
        });
    }

    /** Answers one file's typed entries and the SHA-256 of its current bytes. */
    private void handleEntries(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        reader.keys(new String[] {"path"});
        String path = reader.string("path", 1, 512, ContractPrimitives.SERVER_PATH);
        String replyTo = request.id();
        onConfigThread(channel, replyTo, "config.entries.result", () -> {
            try {
                entries(channel, replyTo, path);
            } catch (RuntimeException failure) {
                failed(channel, replyTo, "config.entries.result", failure);
            }
        });
    }

    /** Writes exactly the keys a panel edit names, and only while the file still holds the bytes it read. */
    private void handleUpdate(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        reader.keys(new String[] {"path", "baseVersion", "changes"});
        String path = reader.string("path", 1, 512, ContractPrimitives.SERVER_PATH);
        String baseVersion = reader.string("baseVersion", 64, 64, ContractPrimitives.SHA256_HEX);
        List<Change> changes = changes(reader.array("changes", 1, CHANGES_MAX), request.id());
        String replyTo = request.id();
        onConfigThread(channel, replyTo, "config.update.result", () -> {
            try {
                update(channel, replyTo, path, baseVersion, changes);
            } catch (RuntimeException failure) {
                failed(channel, replyTo, "config.update.result", failure);
            }
        });
    }

    // ---------------------------------------------------------------- config.tree

    private JsonElement tree() {
        JsonArray plugins = new JsonArray();
        for (Map.Entry<String, UltiToolsPlugin> module : modulesByName().entrySet()) {
            UltiToolsPlugin plugin = module.getValue();
            Map<String, AbstractConfigEntity> registered = configs.getAllConfigEntities(plugin);
            if (registered == null || registered.isEmpty()) {
                continue;
            }
            JsonArray files = new JsonArray();
            for (String configPath : new TreeMap<>(registered).keySet()) {
                String published = publishedPath(plugin, configPath);
                if (published == null) {
                    continue;
                }
                JsonObject file = new JsonObject();
                file.addProperty("path", published);
                file.addProperty("name", published.substring(published.lastIndexOf('/') + 1));
                files.add(file);
                if (files.size() >= TREE_FILES_MAX) {
                    break;
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", module.getKey());
            entry.add("files", files);
            plugins.add(entry);
            if (plugins.size() >= TREE_PLUGINS_MAX) {
                break;
            }
        }
        JsonObject data = new JsonObject();
        data.add("plugins", plugins);
        return data;
    }

    private Map<String, UltiToolsPlugin> modulesByName() {
        Map<String, UltiToolsPlugin> byName = new TreeMap<>();
        List<UltiToolsPlugin> loaded = modules.get();
        if (loaded == null) {
            return byName;
        }
        for (UltiToolsPlugin plugin : loaded) {
            if (plugin == null || plugin.getPluginName() == null) {
                continue;
            }
            byName.put(plugin.getPluginName(), plugin);
        }
        return byName;
    }

    // ------------------------------------------------------------- config.entries

    private void entries(CloudChannel channel, String replyTo, String path) {
        Registered registered = lookup(path);
        if (registered == null) {
            channel.replyError(replyTo, "config.entries.result", "path_not_allowed", PATH_NOT_ALLOWED, null);
            return;
        }
        ConfigLoadResult loaded = ConfigDocument.load(registered.file.toPath());
        switch (loaded.state()) {
            case ABSENT:
                channel.replyError(replyTo, "config.entries.result", "not_found", NOT_FOUND, null);
                return;
            case UNREADABLE:
                channel.replyError(replyTo, "config.entries.result", "config_invalid", UNREADABLE, null);
                return;
            case UNPARSEABLE:
                channel.replyError(replyTo, "config.entries.result", "config_invalid", CONFIG_INVALID, null);
                return;
            case LOADED:
            default:
                break;
        }
        List<RemoteConfigEntries.Entry> entries = RemoteConfigEntries.describe(registered.entity, loaded.document());
        JsonArray wire = new JsonArray();
        for (RemoteConfigEntries.Entry entry : entries) {
            wire.add(entry.toWire());
            if (wire.size() >= ENTRIES_MAX) {
                break;
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.add("entries", wire);
        data.addProperty("version", loaded.fingerprint());
        channel.replyOk(replyTo, "config.entries.result", data);
    }

    // -------------------------------------------------------------- config.update

    private void update(CloudChannel channel, String replyTo, String path, String baseVersion, List<Change> changes) {
        Registered registered = lookup(path);
        if (registered == null) {
            channel.replyError(replyTo, "config.update.result", "path_not_allowed", PATH_NOT_ALLOWED, null);
            return;
        }
        Path file = registered.file.toPath();
        synchronized (lockFor(file)) {
            ConfigLoadResult loaded = ConfigDocument.load(file);
            switch (loaded.state()) {
                case ABSENT:
                    channel.replyError(replyTo, "config.update.result", "not_found", NOT_FOUND, null);
                    return;
                case UNREADABLE:
                    channel.replyError(replyTo, "config.update.result", "config_invalid", UNREADABLE, null);
                    return;
                case UNPARSEABLE:
                    channel.replyError(replyTo, "config.update.result", "config_invalid", CONFIG_INVALID, null);
                    return;
                case LOADED:
                default:
                    break;
            }
            if (!baseVersion.equals(loaded.fingerprint())) {
                channel.replyError(replyTo, "config.update.result", "file_changed", FILE_CHANGED, null);
                return;
            }
            if (!Files.isWritable(file)) {
                channel.replyError(replyTo, "config.update.result", "write_denied", WRITE_DENIED, null);
                return;
            }
            write(channel, replyTo, registered, loaded, changes);
        }
    }

    private void write(CloudChannel channel, String replyTo, Registered registered, ConfigLoadResult loaded,
            List<Change> changes) {
        List<RemoteConfigEntries.Entry> entries = RemoteConfigEntries.describe(registered.entity, loaded.document());
        List<KeyResult> results = new ArrayList<>();
        List<Accepted> accepted = new ArrayList<>();
        for (Change change : changes) {
            RemoteConfigEntries.Entry entry = RemoteConfigEntries.find(entries, change.key);
            String problem = RemoteConfigEntries.check(change.value, entry);
            if (problem == null) {
                problem = entityRefusal(registered.entity, change, entry);
            }
            if (problem == null) {
                accepted.add(new Accepted(entry, change.value));
                results.add(new KeyResult(change.key, true, null));
            } else {
                results.add(new KeyResult(change.key, false, bounded(problem)));
            }
        }
        String version = loaded.fingerprint();
        String notReloaded = null;
        if (!accepted.isEmpty()) {
            OperatorFileWriter.Result result;
            try {
                result = OperatorFileWriter.write(registered.file.toPath(), owned(accepted), loaded.fingerprint(),
                        document -> {
                            for (Accepted write : accepted) {
                                document.set(write.entry.path(),
                                        RemoteConfigEntries.plain(write.value, write.entry.kind()));
                            }
                        });
            } catch (IOException failure) {
                LOGGER.warning("UltiCloud: could not write configuration file " + registered.file.getName()
                        + ": " + failure.getClass().getSimpleName());
                channel.replyError(replyTo, "config.update.result", "write_denied", WRITE_DENIED, null);
                return;
            }
            if (result.outcome() == OperatorFileWriter.Outcome.FILE_CHANGED) {
                channel.replyError(replyTo, "config.update.result", "file_changed", FILE_CHANGED, null);
                return;
            }
            if (!result.applied()) {
                channel.replyError(replyTo, "config.update.result", "write_denied", writeDenied(result.reason()),
                        null);
                return;
            }
            version = result.fingerprint();
            notReloaded = reload(registered);
        }
        JsonObject data = keysData(results, version);
        boolean allOk = accepted.size() == results.size();
        if (allOk) {
            channel.replyOk(replyTo, "config.update.result", data, notReloaded);
        } else {
            channel.replyPartial(replyTo, "config.update.result", data, null, notReloaded);
        }
    }

    /**
     * The panel's explanation of a write the gate refused: the gate's own reason, which names the line an
     * operator has to fix and never a value. The fixed sentence it replaces read like a permission problem
     * and left the actionable part in the server log only.
     *
     * @param reason the gate's value-free reason, empty when it wrote or found nothing to write
     * @return the message for the {@code write_denied} error
     */
    private static String writeDenied(String reason) {
        return reason == null || reason.isEmpty() ? WRITE_DENIED : bounded("Nothing was written: " + reason + ".");
    }

    /**
     * The entity's own check of one change: it applies the value to the declared field in memory, runs
     * the same validators and converters a load runs, and restores every field before returning.
     *
     * <p>The text handed to the panel names the rule, never the value and never the load-time wording:
     * the configuration layer's own message echoes the offending value and speaks of a module that
     * refused to load, which is not what happened to a panel edit. Only the error code - the class of
     * rule that refused - is forwarded.</p>
     */
    private String entityRefusal(AbstractConfigEntity entity, Change change, RemoteConfigEntries.Entry entry) {
        JsonObject proposal = new JsonObject();
        proposal.add(change.key, change.value);
        try {
            entity.validateProposedProperties(proposal);
            return null;
        } catch (ConfigurationException refusal) {
            LOGGER.fine("UltiCloud: configuration value refused for " + entry.key() + ": "
                    + refusal.getErrorCode());
            return refusalText(refusal);
        } catch (RuntimeException failure) {
            LOGGER.fine("UltiCloud: configuration value check failed for " + entry.key() + ": "
                    + failure.getClass().getName());
            return "the value could not be used";
        }
    }

    private static String refusalText(ConfigurationException refusal) {
        ErrorCode code = refusal.getErrorCode();
        return code == null ? "the module refused this value"
                : "the module refused this value (" + code.getDefaultMessage().toLowerCase(Locale.ROOT) + ")";
    }

    private static OwnedPaths owned(List<Accepted> accepted) {
        OwnedPaths.Builder owned = OwnedPaths.builder();
        for (Accepted write : accepted) {
            owned.value(write.entry.path());
        }
        return owned.build();
    }

    /**
     * Makes the module take the values just written, by reloading the one entity that owns the written
     * file. Only that entity is reloaded: reloading every file of the module would let a broken file this
     * write never touched block the reload of the edited one
     * ({@link ConfigManager#reloadConfigs} stops at the first failure), and the panel would be told the
     * edit failed when the file it edited was written and readable.
     *
     * @param registered the file that was written and its owning entity
     * @return the panel's explanation when the module did not take the new values, or null when it did
     */
    private String reload(Registered registered) {
        try {
            registered.entity.reload();
            return null;
        } catch (IOException | RuntimeException failure) {
            // The file holds the accepted values; the module keeps its running ones. Never a value in the log.
            LOGGER.warning("UltiCloud: configuration saved but module " + registered.module.getPluginName()
                    + " did not reload it: " + registered.file.getName() + ": " + failure.getClass().getSimpleName());
            return bounded(NOT_RELOADED + registered.file.getName() + ": " + failure.getClass().getSimpleName()
                    + "); it keeps running its previous values until the file is fixed and reloaded.");
        }
    }

    private static JsonObject keysData(List<KeyResult> results, String version) {
        JsonArray keys = new JsonArray();
        for (KeyResult result : results) {
            JsonObject key = new JsonObject();
            key.addProperty("key", result.key);
            key.addProperty("ok", result.ok);
            if (result.message == null) {
                key.add("message", JsonNull.INSTANCE);
            } else {
                key.addProperty("message", result.message);
            }
            keys.add(key);
        }
        JsonObject data = new JsonObject();
        data.add("keys", keys);
        data.addProperty("version", version);
        return data;
    }

    // ------------------------------------------------------------------- shared

    private Registered lookup(String path) {
        Map<String, Registered> published = new LinkedHashMap<>();
        for (Map.Entry<String, UltiToolsPlugin> module : modulesByName().entrySet()) {
            UltiToolsPlugin plugin = module.getValue();
            Map<String, AbstractConfigEntity> registered = configs.getAllConfigEntities(plugin);
            if (registered == null) {
                continue;
            }
            for (Map.Entry<String, AbstractConfigEntity> entity : new TreeMap<>(registered).entrySet()) {
                String publishedPath = publishedPath(plugin, entity.getKey());
                if (publishedPath == null || published.containsKey(publishedPath)) {
                    continue;
                }
                published.put(publishedPath, new Registered(plugin, entity.getValue(),
                        new File(plugin.getResourceFolderPath(), entity.getKey())));
            }
        }
        return published.get(path);
    }

    /**
     * The panel's path of one registered configuration file: the file's location relative to the server
     * root, with {@code /} separators, as the contract's path primitive requires. A file outside the
     * root, or a name the primitive rejects, is not published at all - the panel never sees a path it
     * could not name back.
     */
    private String publishedPath(UltiToolsPlugin module, String configFilePath) {
        String folder = module.getResourceFolderPath();
        if (folder == null || configFilePath == null) {
            return null;
        }
        Path root = serverRoot.toPath().toAbsolutePath().normalize();
        Path file = new File(folder, configFilePath).toPath().toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        String published = root.relativize(file).toString().replace(File.separatorChar, '/');
        return published.isEmpty() || !ContractPrimitives.SERVER_PATH.matcher(published).matches() ? null : published;
    }

    private static Object lockFor(Path file) {
        String key;
        try {
            key = file.toRealPath().toString();
        } catch (IOException absent) {
            key = file.toAbsolutePath().normalize().toString();
        }
        Object monitor = FILE_LOCKS.get(key);
        if (monitor == null) {
            Object created = new Object();
            monitor = FILE_LOCKS.putIfAbsent(key, created);
            if (monitor == null) {
                monitor = created;
            }
        }
        return monitor;
    }

    private void onConfigThread(CloudChannel channel, String replyTo, String type, Runnable work) {
        try {
            configThread.execute(work);
        } catch (RejectedExecutionException rejected) {
            LOGGER.warning("UltiCloud: configuration request rejected: the server thread is not available.");
            channel.replyError(replyTo, type, "internal_error", INTERNAL, null);
        }
    }

    private void failed(CloudChannel channel, String replyTo, String type, RuntimeException failure) {
        LOGGER.warning("UltiCloud: configuration request failed (" + type + "), exception="
                + failure.getClass().getName());
        channel.replyError(replyTo, type, "internal_error", INTERNAL, null);
    }

    private static String bounded(String message) {
        if (message == null) {
            return null;
        }
        return message.codePointCount(0, message.length()) > MESSAGE_MAX
                ? message.substring(0, message.offsetByCodePoints(0, MESSAGE_MAX)) : message;
    }

    /**
     * Validates every change item against the frozen {@code config.update} schema shapes and copies it.
     * A malformed item is an {@link InvalidFrameException}, so the router answers {@code invalid_message}.
     */
    private static List<Change> changes(JsonArray items, String id) {
        List<Change> changes = new ArrayList<>();
        for (JsonElement item : items) {
            if (item == null || !item.isJsonObject()) {
                throw invalid(id);
            }
            JsonObject change = item.getAsJsonObject();
            if (!change.keySet().equals(new HashSet<>(Arrays.asList("key", "value")))) {
                throw invalid(id);
            }
            JsonElement key = change.get("key");
            if (key == null || !key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()) {
                throw invalid(id);
            }
            String text = key.getAsString();
            if (!RemoteConfigEntries.usableKey(text) || text.codePointCount(0, text.length()) > KEY_MAX) {
                throw invalid(id);
            }
            changes.add(new Change(text, value(change.get("value"), id)));
        }
        return changes;
    }

    private static JsonElement value(JsonElement value, String id) {
        if (value == null) {
            throw invalid(id);
        }
        if (value.isJsonNull()) {
            return value.deepCopy();
        }
        if (!value.isJsonPrimitive()) {
            if (!value.isJsonArray()) {
                throw invalid(id);
            }
            JsonArray array = value.getAsJsonArray();
            if (array.size() > LIST_MAX) {
                throw invalid(id);
            }
            JsonArray copy = new JsonArray();
            for (JsonElement item : array) {
                if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                        || item.getAsString().codePointCount(0, item.getAsString().length()) > LIST_ITEM_MAX) {
                    throw invalid(id);
                }
                copy.add(item.getAsString());
            }
            return copy;
        }
        if (value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (text.codePointCount(0, text.length()) > TEXT_MAX) {
                throw invalid(id);
            }
            return value.deepCopy();
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            try {
                new BigDecimal(value.getAsString());
            } catch (NumberFormatException notANumber) {
                throw invalid(id);
            }
            return value.deepCopy();
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.deepCopy();
        }
        throw invalid(id);
    }

    private static InvalidFrameException invalid(String id) {
        return new InvalidFrameException("invalid_message", id, "Payload does not match the message schema.");
    }

    /** One module-registered configuration file the panel may name. */
    private static final class Registered {
        private final UltiToolsPlugin module;
        private final AbstractConfigEntity entity;
        private final File file;

        private Registered(UltiToolsPlugin module, AbstractConfigEntity entity, File file) {
            this.module = module;
            this.entity = entity;
            this.file = file;
        }
    }

    /** One panel change, already shape-checked. */
    private static final class Change {
        private final String key;
        private final JsonElement value;

        private Change(String key, JsonElement value) {
            this.key = key;
            this.value = value;
        }
    }

    /** One change accepted for the write, with the entry that types it. */
    private static final class Accepted {
        private final RemoteConfigEntries.Entry entry;
        private final JsonElement value;

        private Accepted(RemoteConfigEntries.Entry entry, JsonElement value) {
            this.entry = entry;
            this.value = value;
        }
    }

    /** One row of {@code config.update.result}'s per-key array. */
    private static final class KeyResult {
        private final String key;
        private final boolean ok;
        private final String message;

        private KeyResult(String key, boolean ok, String message) {
            this.key = key;
            this.ok = ok;
            this.message = message;
        }
    }
}
