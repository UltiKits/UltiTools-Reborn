package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.ContractPrimitives;
import com.ultikits.ultitools.websocket.CloudChannel;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes and paces the UltiCloud protocol v2 telemetry frames; internal, not for module use.
 *
 * <p>The class is passive: the session wiring (plan 22-22) calls {@link #onWelcome(CloudChannel)}
 * when a connection is accepted, {@link #tick(CloudChannel)} roughly once a second, and
 * {@link #onClose()} when the connection ends. All timing goes through the injected clock, so the
 * cadence is testable without a socket and without sleeping.</p>
 *
 * <p>Cadence follows the frozen contract's SEMANTICS item 6: {@code status} every
 * {@value #STATUS_INTERVAL_MS} ms; {@code players.snapshot}, {@code plugins.snapshot} and
 * {@code worlds.snapshot} once after the welcome and afterwards only when their mapped payload
 * changed, at most once per {@value #SNAPSHOT_MIN_INTERVAL_MS} ms each. Change detection compares
 * what would be sent, so a change in a field the contract does not carry never triggers a frame.</p>
 *
 * <p>Every value is mapped into the contract's field set and bounds, and no field the contract
 * does not define is sent (no addresses, no plugin authors). Bukkit state is read only through
 * {@link ServerMonitorManager}'s main-thread sample.</p>
 *
 * @since 6.3.1
 */
final class AgentTelemetry {
    private static final Logger LOGGER = Logger.getLogger(AgentTelemetry.class.getName());

    /** Contract {@code timing.statusIntervalMs}; {@code status} is sent once per interval. */
    static final long STATUS_INTERVAL_MS = 10000L;

    /** Contract {@code timing.playersSnapshotMinIntervalMs}; also the coalescing window of the other snapshots. */
    static final long SNAPSHOT_MIN_INTERVAL_MS = 10000L;

    /** Status payload bound from the frozen {@code status} schema. */
    private static final int MAX_PLAYERS_COUNT = 100000;
    /** Status payload bound from the frozen {@code status} schema. */
    private static final long MAX_MEMORY_MB = 10000000L;
    /** Status payload bound from the frozen {@code status} schema. */
    private static final long MAX_UPTIME_SECONDS = 10000000000L;
    /** Status payload bound from the frozen {@code status} schema. */
    private static final int MAX_WORLD_COUNT = 1000;

    /** Snapshot array bounds from the frozen {@code players.snapshot} schema. */
    private static final int MAX_PLAYER_ENTRIES = 1000;
    /** Snapshot array bounds from the frozen {@code plugins.snapshot} schema. */
    private static final int MAX_PLUGIN_ENTRIES = 1000;
    /** Snapshot array bounds from the frozen {@code worlds.snapshot} schema. */
    private static final int MAX_WORLD_ENTRIES = 200;
    /** World payload bound from the frozen {@code worlds.snapshot} schema. */
    private static final int MAX_LOADED_CHUNKS = 10000000;

    private static final int MAX_SOFTWARE_CODE_POINTS = 128;
    private static final int MAX_VERSION_CODE_POINTS = 64;
    private static final int MAX_PLUGIN_NAME_CODE_POINTS = 128;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 500;
    private static final int MAX_WORLD_NAME_CODE_POINTS = 128;
    private static final double MAX_HEALTH = 10000.0;
    private static final int MAX_WORLD_COORDINATE = 30000000;

    private static final String STATUS = "status";
    private static final String PLAYERS_SNAPSHOT = "players.snapshot";
    private static final String PLUGINS_SNAPSHOT = "plugins.snapshot";
    private static final String WORLDS_SNAPSHOT = "worlds.snapshot";

    private static final String[] ENVIRONMENTS = {"normal", "nether", "the_end"};
    private static final String[] DIFFICULTIES = {"peaceful", "easy", "normal", "hard"};
    private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};

    /** The one field a version string must contain to satisfy the contract's Semver pattern. */
    private static final Pattern EMBEDDED_SEMVER =
            Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?");

    /** Used when neither the plugin description nor {@code env.yml} carries a usable version. */
    private static final String UNKNOWN_VERSION = "0.0.0";
    /** Used when the server reports neither a name nor a version. */
    private static final String UNKNOWN_SOFTWARE = "Unknown";
    /** Used for a plugin whose manifest carries no version; the schema requires a non-empty string. */
    private static final String UNKNOWN_PLUGIN_VERSION = "unknown";

    private final ServerMonitorManager monitor;
    private final BooleanSupplier telemetryEnabled;
    private final LongSupplier clock;

    private long lastStatusAt;
    private final Tracked playerTrack = new Tracked();
    private final Tracked pluginTrack = new Tracked();
    private final Tracked worldTrack = new Tracked();

    /**
     * Creates the encoder over the monitor's main-thread sample.
     *
     * @param monitor supplies the sampled server state, TPS and CPU load
     * @param telemetryEnabled the {@code telemetry} capability switch; false silences every frame
     * @param clock supplies epoch milliseconds for the cadence, never the wall clock directly
     */
    AgentTelemetry(ServerMonitorManager monitor, BooleanSupplier telemetryEnabled, LongSupplier clock) {
        this.monitor = monitor;
        this.telemetryEnabled = telemetryEnabled;
        this.clock = clock;
    }

    /**
     * Sends the four snapshots a fresh connection starts from: {@code status},
     * {@code players.snapshot}, {@code plugins.snapshot} and {@code worlds.snapshot}, in that
     * order. Nothing is sent while the telemetry capability is off or the channel is closed.
     *
     * @param channel the accepted connection
     */
    void onWelcome(CloudChannel channel) {
        if (!enabled(channel)) {
            return;
        }
        long now = clock.getAsLong();
        if (send(channel, STATUS, statusPayload())) {
            lastStatusAt = now;
        }
        sendForced(channel, PLAYERS_SNAPSHOT, this::playersPayload, playerTrack, now);
        sendForced(channel, PLUGINS_SNAPSHOT, this::pluginsPayload, pluginTrack, now);
        sendForced(channel, WORLDS_SNAPSHOT, this::worldsPayload, worldTrack, now);
    }

    /**
     * Forgets what was last sent, so the next welcome sends every snapshot again. The next
     * connection may well be a different gateway with a different view.
     */
    void onClose() {
        lastStatusAt = 0L;
        playerTrack.clear();
        pluginTrack.clear();
        worldTrack.clear();
    }

    /**
     * Advances the cadence; the session wiring calls this about once a second on the main thread.
     *
     * @param channel the accepted connection
     */
    void tick(CloudChannel channel) {
        if (!enabled(channel)) {
            return;
        }
        long now = clock.getAsLong();
        if (now - lastStatusAt >= STATUS_INTERVAL_MS && send(channel, STATUS, statusPayload())) {
            lastStatusAt = now;
        }
        sendChanged(channel, PLAYERS_SNAPSHOT, this::playersPayload, playerTrack, now);
        sendChanged(channel, PLUGINS_SNAPSHOT, this::pluginsPayload, pluginTrack, now);
        sendChanged(channel, WORLDS_SNAPSHOT, this::worldsPayload, worldTrack, now);
    }

    /**
     * Sends one snapshot regardless of the coalescing window, because the panel asked for it, and
     * restarts that snapshot's window from now.
     */
    private static void sendForced(CloudChannel channel, String type, Supplier<JsonObject> payload,
            Tracked track, long now) {
        JsonObject built = payload.get();
        if (send(channel, type, built)) {
            track.remember(built.toString(), now);
        }
    }

    /**
     * Sends one snapshot when its mapped payload differs from the last one sent and at least
     * {@value #SNAPSHOT_MIN_INTERVAL_MS} ms have passed since that send. Comparison is on the
     * payload that would go on the wire, so a value the contract does not carry cannot wake it.
     */
    private static void sendChanged(CloudChannel channel, String type, Supplier<JsonObject> payload,
            Tracked track, long now) {
        if (now - track.lastAt < SNAPSHOT_MIN_INTERVAL_MS) {
            return;
        }
        JsonObject built = payload.get();
        String json = built.toString();
        if (json.equals(track.lastPayload)) {
            return;
        }
        if (send(channel, type, built)) {
            track.remember(json, now);
        }
    }

    /** The last successful send of one snapshot type: how recently, and what went on the wire. */
    private static final class Tracked {
        private long lastAt;
        private String lastPayload;

        private void remember(String payload, long now) {
            lastPayload = payload;
            lastAt = now;
        }

        private void clear() {
            lastAt = 0L;
            lastPayload = null;
        }
    }

    private boolean enabled(CloudChannel channel) {
        return channel.isOpen() && telemetryEnabled.getAsBoolean();
    }

    private static boolean send(CloudChannel channel, String type, JsonObject payload) {
        return channel.isOpen() && channel.send(type, payload);
    }

    /**
     * Builds the contract {@code status} payload: exactly its ten fields, every value inside the
     * schema bounds. Bukkit state comes from the monitor's sample; memory, uptime, TPS and CPU are
     * JVM readings taken here (all four are safe off the main thread).
     */
    private JsonObject statusPayload() {
        ServerMonitorManager.ServerStateSnapshot snapshot = monitor.currentStateSnapshot();
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;

        JsonObject payload = new JsonObject();
        payload.addProperty("playerCount", clampInt(snapshot.playerCount, 0, MAX_PLAYERS_COUNT));
        payload.addProperty("maxPlayers", clampInt(snapshot.maxPlayers, 0, MAX_PLAYERS_COUNT));
        payload.addProperty("tps", round(clamp(monitor.getCurrentTPS(), 0.0, 100.0), 2));
        payload.addProperty("cpuPercent", round(clamp(monitor.sampledCpuPercent(), 0.0, 10000.0), 2));
        payload.addProperty("memUsedMb", clampLong(usedMb, 0L, MAX_MEMORY_MB));
        payload.addProperty("memMaxMb", clampLong(maxMb, 0L, MAX_MEMORY_MB));
        payload.addProperty("software", software(snapshot.serverVersion));
        payload.addProperty("ultitoolsVersion", ultitoolsVersion());
        payload.addProperty("uptimeSeconds", clampLong(uptimeSeconds, 0L, MAX_UPTIME_SECONDS));
        payload.addProperty("worldCount", clampInt(snapshot.worldCount, 0, MAX_WORLD_COUNT));
        return payload;
    }

    /**
     * Builds {@code players.snapshot} from the sampled player array, keeping only the contract's
     * seven fields. A player whose name, world or game mode cannot be represented is omitted and
     * logged at FINE; the name is never logged.
     */
    private JsonObject playersPayload() {
        JsonArray players = new JsonArray();
        for (JsonElement element : monitor.currentStateSnapshot().onlinePlayers) {
            if (players.size() >= MAX_PLAYER_ENTRIES) {
                break;
            }
            JsonObject player = playerEntry(element);
            if (player != null) {
                players.add(player);
            }
        }
        JsonObject payload = new JsonObject();
        payload.add("players", players);
        return payload;
    }

    private static JsonObject playerEntry(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return omitEntity("a player entry is not an object");
        }
        JsonObject source = element.getAsJsonObject();
        String uuid = text(source, "uuid");
        if (uuid == null || !ContractPrimitives.MC_UUID.matcher(uuid).matches()) {
            return omitEntity("a player has no representable uuid");
        }
        // Truncated first, so a merely too-long name stays reportable; only a name that is still
        // outside the contract's character class after truncation is dropped.
        String name = cutCodePoints(text(source, "name"), 32);
        if (name == null || !ContractPrimitives.MC_NAME.matcher(name).matches()) {
            return omitEntity("a player has no representable name");
        }
        String world = nonEmpty(cutCodePoints(text(source, "world"), MAX_WORLD_NAME_CODE_POINTS));
        if (world == null) {
            return omitEntity("a player has no representable world name");
        }
        String gameMode = lowerCase(text(source, "gameMode"));
        if (!contains(GAME_MODES, gameMode)) {
            return omitEntity("a player has no representable game mode");
        }

        JsonObject player = new JsonObject();
        player.addProperty("uuid", uuid);
        player.addProperty("name", name);
        player.addProperty("world", world);
        player.addProperty("health", round(clamp(number(source, "health"), 0.0, MAX_HEALTH), 2));
        player.addProperty("maxHealth", round(clamp(number(source, "maxHealth"), 0.0, MAX_HEALTH), 2));
        player.addProperty("gameMode", gameMode);
        player.addProperty("op", bool(source, "op"));
        return player;
    }

    /**
     * Builds {@code plugins.snapshot}: name, version, description (explicit JSON null when the
     * manifest has none) and enabled. Plugin authors are deliberately not carried over.
     */
    private JsonObject pluginsPayload() {
        JsonArray plugins = new JsonArray();
        for (JsonElement element : monitor.currentStateSnapshot().plugins) {
            if (plugins.size() >= MAX_PLUGIN_ENTRIES) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            String name = nonEmpty(cutCodePoints(text(source, "name"), MAX_PLUGIN_NAME_CODE_POINTS));
            if (name == null) {
                continue;
            }
            String version = nonEmpty(cutCodePoints(text(source, "version"), MAX_VERSION_CODE_POINTS));

            JsonObject plugin = new JsonObject();
            plugin.addProperty("name", name);
            plugin.addProperty("version", version == null ? UNKNOWN_PLUGIN_VERSION : version);
            JsonElement description = source.get("description");
            String text = description == null || description.isJsonNull() ? null : description.getAsString();
            String trimmed = nonEmpty(cutCodePoints(text, MAX_DESCRIPTION_CODE_POINTS));
            if (trimmed == null) {
                plugin.add("description", JsonNull.INSTANCE);
            } else {
                plugin.addProperty("description", trimmed);
            }
            plugin.addProperty("enabled", bool(source, "enabled"));
            plugins.add(plugin);
        }
        JsonObject payload = new JsonObject();
        payload.add("plugins", plugins);
        return payload;
    }

    /**
     * Builds {@code worlds.snapshot}: name, environment, difficulty, player count, loaded chunks,
     * PvP and the spawn point. An environment or difficulty the contract does not name maps to
     * {@code custom} or {@code normal} respectively, as the plan requires.
     */
    private JsonObject worldsPayload() {
        JsonArray worlds = new JsonArray();
        for (JsonElement element : monitor.currentStateSnapshot().worlds) {
            if (worlds.size() >= MAX_WORLD_ENTRIES) {
                break;
            }
            JsonObject world = worldEntry(element);
            if (world != null) {
                worlds.add(world);
            }
        }
        JsonObject payload = new JsonObject();
        payload.add("worlds", worlds);
        return payload;
    }

    private static JsonObject worldEntry(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject source = element.getAsJsonObject();
        String name = nonEmpty(cutCodePoints(text(source, "name"), MAX_WORLD_NAME_CODE_POINTS));
        if (name == null) {
            return null;
        }
        String environment = lowerCase(text(source, "environment"));
        String difficulty = lowerCase(text(source, "difficulty"));

        JsonObject world = new JsonObject();
        world.addProperty("name", name);
        world.addProperty("environment", contains(ENVIRONMENTS, environment) ? environment : "custom");
        world.addProperty("difficulty", contains(DIFFICULTIES, difficulty) ? difficulty : "normal");
        world.addProperty("playerCount", clampInt(integer(source, "playerCount"), 0, MAX_PLAYERS_COUNT));
        world.addProperty("loadedChunks", clampInt(integer(source, "loadedChunks"), 0, MAX_LOADED_CHUNKS));
        world.addProperty("pvp", bool(source, "pvpEnabled"));

        JsonElement spawn = source.get("spawnLocation");
        JsonObject point = new JsonObject();
        point.addProperty("x", clampInt(spawnCoordinate(spawn, "x"), -MAX_WORLD_COORDINATE, MAX_WORLD_COORDINATE));
        point.addProperty("y", clampInt(spawnCoordinate(spawn, "y"), -MAX_WORLD_COORDINATE, MAX_WORLD_COORDINATE));
        point.addProperty("z", clampInt(spawnCoordinate(spawn, "z"), -MAX_WORLD_COORDINATE, MAX_WORLD_COORDINATE));
        world.add("spawn", point);
        return world;
    }

    private static JsonObject omitEntity(String reason) {
        LOGGER.log(Level.FINE, "UltiCloud: omitted an entry from a telemetry snapshot ({0}).", reason);
        return null;
    }

    /** @return the server name and version, cut to the contract's 128 code points */
    private static String software(String serverVersion) {
        String name = Bukkit.getName();
        String version = nonEmpty(serverVersion);
        String text;
        if (nonEmpty(name) == null) {
            text = version == null ? UNKNOWN_SOFTWARE : version;
        } else {
            text = version == null ? name : name + " " + version;
        }
        return cutCodePoints(text, MAX_SOFTWARE_CODE_POINTS);
    }

    /**
     * @return the running framework version in the contract's Semver form: the plugin
     *         description's version when it already is Semver, else the first Semver-looking run
     *         inside it, else {@code env.yml}'s, else {@code 0.0.0} -- a frame is never allowed to
     *         carry a version the schema rejects
     */
    private static String ultitoolsVersion() {
        UltiTools plugin = UltiTools.getInstance();
        String version = null;
        if (plugin != null && plugin.getDescription() != null) {
            version = plugin.getDescription().getVersion();
        }
        String semver = semver(version);
        if (semver != null) {
            return semver;
        }
        try {
            return orDefault(semver(UltiTools.getEnv().getString("version")), UNKNOWN_VERSION);
        } catch (RuntimeException ex) {
            // env.yml is missing or unreadable on this server; the version is then simply unknown.
            return UNKNOWN_VERSION;
        }
    }

    private static String semver(String versionOrNull) {
        String trimmed = nonEmpty(versionOrNull);
        if (trimmed == null) {
            return null;
        }
        if (ContractPrimitives.SEMVER.matcher(trimmed).matches()
                && codePoints(trimmed) <= MAX_VERSION_CODE_POINTS) {
            return trimmed;
        }
        Matcher matcher = EMBEDDED_SEMVER.matcher(trimmed);
        return matcher.find() ? matcher.group() : null;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String text(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static boolean bool(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static double number(JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0.0;
        }
        return value.getAsDouble();
    }

    private static int integer(JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return value.getAsInt();
    }

    private static int spawnCoordinate(JsonElement spawn, String axis) {
        return spawn != null && spawn.isJsonObject() ? integer(spawn.getAsJsonObject(), axis) : 0;
    }

    private static String lowerCase(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String[] values, String value) {
        if (value == null) {
            return false;
        }
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String nonEmpty(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    /** Cuts a string to {@code max} Unicode code points, never splitting a surrogate pair. */
    private static String cutCodePoints(String value, int max) {
        if (value == null) {
            return null;
        }
        if (codePoints(value) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10.0, decimals);
        return Math.round(value * factor) / factor;
    }
}
