package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads a module's own {@code plugin.yml} straight from its class's code source, given only a
 * bare {@code Class<?>} - before any {@code UltiToolsPlugin} instance exists (D-12). This is the
 * third place in the codebase that would otherwise open a JAR to read {@code plugin.yml};
 * extracting it once here is the point.
 * <p>
 * Every failure path - null code source, a directory code source with no {@code plugin.yml}
 * (the common case for test/dev classpaths), a missing JAR entry, an unreadable archive, or
 * malformed YAML - returns {@link PluginYmlInfo#EMPTY} rather than throwing, logging at most one
 * WARNING. This class runs once per plugin class during dependency-graph construction, and a
 * single bad archive must not take the whole graph down (T-04-20).
 * <br>
 * 直接从一个类的代码源读取该模块自身的 {@code plugin.yml}，只需要一个裸的
 * {@code Class<?>}——在任何 {@code UltiToolsPlugin} 实例存在之前（D-12）。这是代码库中
 * 第三处原本会各自打开 JAR 读取 {@code plugin.yml} 的地方；在这里把它抽取一次就是这个类
 * 存在的意义。
 * <p>
 * 每一条失败路径——代码源为 null、代码源是目录且没有 {@code plugin.yml}
 * （测试/开发 classpath 下的常见情况）、JAR 条目缺失、归档不可读、或 YAML 格式错误——
 * 都返回 {@link PluginYmlInfo#EMPTY} 而不是抛出异常，最多记一条 WARNING。这个类在依赖图
 * 构建期间对每个插件类都会运行一次，单个损坏的归档不能拖垮整张图（T-04-20）。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class PluginYmlReader {

    private static final Logger LOGGER = Logger.getLogger(PluginYmlReader.class.getName());
    private static final String PLUGIN_YML_ENTRY = "plugin.yml";

    private PluginYmlReader() {
        // Utility class - not instantiable.
    }

    /**
     * Reads {@code plugin.yml} for the given class's own code source.
     *
     * @param pluginClass the class whose containing JAR (or classes directory) should be read;
     *                    may be {@code null}
     * @return the module's declared name and {@code loadAfter} list, or {@link PluginYmlInfo#EMPTY}
     *         on any failure path
     * @since 6.3.0
     */
    public static PluginYmlInfo read(Class<?> pluginClass) {
        if (pluginClass == null) {
            return PluginYmlInfo.EMPTY;
        }
        try {
            CodeSource src = pluginClass.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) {
                return PluginYmlInfo.EMPTY;
            }
            URL location = src.getLocation();
            File locationFile = toFile(location);
            if (locationFile != null && locationFile.isDirectory()) {
                return readFromDirectory(locationFile);
            }
            return readFromJar(location);
        } catch (Exception e) {
            // Best-effort only: never let a bad code source take the caller down.
            LOGGER.log(Level.WARNING, "[UltiTools-API] Failed to read plugin.yml for "
                + pluginClass.getName() + ": " + e.getMessage());
            return PluginYmlInfo.EMPTY;
        }
    }

    private static File toFile(URL location) {
        try {
            return new File(location.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new File(location.getPath());
        }
    }

    private static PluginYmlInfo readFromDirectory(File directory) {
        File ymlFile = new File(directory, PLUGIN_YML_ENTRY);
        if (!ymlFile.isFile()) {
            // The common case for test/dev classes directories - silently inert, not a failure.
            return PluginYmlInfo.EMPTY;
        }
        try (InputStream inputStream = Files.newInputStream(ymlFile.toPath())) {
            return parse(inputStream);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[UltiTools-API] Unreadable plugin.yml at "
                + ymlFile.getAbsolutePath() + ": " + e.getMessage());
            return PluginYmlInfo.EMPTY;
        }
    }

    private static PluginYmlInfo readFromJar(URL jarLocation) {
        String path = jarLocation.getPath().startsWith("/")
            ? jarLocation.getPath() : "/" + jarLocation.getPath();
        try {
            URL entryUrl = new java.net.URI("jar:file:" + path + "!/" + PLUGIN_YML_ENTRY).toURL();
            JarURLConnection jarConnection = (JarURLConnection) entryUrl.openConnection();
            try (InputStream inputStream = jarConnection.getInputStream()) {
                return parse(inputStream);
            }
        } catch (Exception e) {
            // Missing entry, unreadable archive, or any other JAR-level failure - inert, not
            // fatal. Paper itself treats a module with no plugin.yml the same way here would.
            LOGGER.log(Level.WARNING, "[UltiTools-API] No readable plugin.yml in "
                + jarLocation + ": " + e.getMessage());
            return PluginYmlInfo.EMPTY;
        }
    }

    private static PluginYmlInfo parse(InputStream inputStream) {
        YamlConfiguration config = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            // Deliberately the instance method, not the static YamlConfiguration.loadConfiguration
            // factory - that swallows IOException/InvalidConfigurationException internally via
            // Bukkit.getLogger(), which requires a live Bukkit server. load(Reader) throws them
            // directly to this catch instead, so malformed input is deterministic to detect
            // regardless of whether a Bukkit server happens to be mocked in the calling test.
            config.load(reader);
        } catch (IOException | InvalidConfigurationException e) {
            LOGGER.log(Level.WARNING, "[UltiTools-API] Malformed plugin.yml: " + e.getMessage());
            return PluginYmlInfo.EMPTY;
        }
        String name = config.getString("name");
        List<String> loadAfter = config.getStringList("loadAfter");
        return new PluginYmlInfo(name, loadAfter);
    }

    /**
     * The subset of a module's {@code plugin.yml} this framework's dependency graph needs: its
     * declared {@code name:} (nullable - absent when the archive has none or reading failed) and
     * its {@code loadAfter:} list (never null; empty when absent or reading failed).
     *
     * @since 6.3.0
     */
    public static final class PluginYmlInfo {

        /** The empty result every failure path returns. */
        public static final PluginYmlInfo EMPTY = new PluginYmlInfo(null, Collections.emptyList());

        private final String name;
        private final List<String> loadAfter;

        PluginYmlInfo(String name, List<String> loadAfter) {
            this.name = name;
            this.loadAfter = Collections.unmodifiableList(
                loadAfter == null ? Collections.emptyList() : loadAfter);
        }

        /**
         * The module's declared {@code plugin.yml} {@code name:} value.
         *
         * @return the declared name, or {@code null} if absent or unreadable
         * @since 6.3.0
         */
        public String getName() {
            return name;
        }

        /**
         * The module's declared {@code plugin.yml} {@code loadAfter:} list.
         *
         * @return the loadAfter list, never {@code null}; empty if absent or unreadable
         * @since 6.3.0
         */
        public List<String> getLoadAfter() {
            return loadAfter;
        }
    }
}
