package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextEngineLocaleConfigurationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Shanghai", "UTC"})
    void existingStartupAndReloadSeam_refreshesTheSharedResolverAndReportsItsReason(String initialZone) throws Exception {
        String original = "# operator configuration\nlanguage: auto\nfallback-language: auto\n";
        Path file = temporary.resolve("config.yml");
        Files.writeString(file, original);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        AtomicReference<ZoneId> zone = new AtomicReference<>(ZoneId.of(initialZone));
        AtomicInteger reads = new AtomicInteger();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = logger(records);
        var resolver = ConfiguredLocaleResolver.forConfig(() -> config, logger, () -> {
            reads.incrementAndGet();
            return zone.get();
        });
        try (TextEngine engine = frameworkEngine(resolver, logger)) {
            SimpleContainer root = new SimpleContainer();
            root.registerSingleton(TextEngine.class.getName(), engine);
            try {
                UltiTools core = core(config, logger, root);
                initialize(core);
                boolean chinese = initialZone.equals("Asia/Shanghai");
                Locale first = chinese ? Locale.CHINESE : Locale.ENGLISH;
                Locale next = chinese ? Locale.ENGLISH : Locale.CHINESE;
                String nextZone = chinese ? "UTC" : "Asia/Shanghai";
                assertThat(engine.fallback()).isEqualTo(first);
                assertThat(core.i18n("Module '%s' reloaded.")).isEqualTo(legacyReloadMessage(first));
                assertThat(info(records)).containsExactly(selectionMessage(first, initialZone));
                zone.set(ZoneId.of(nextZone));
                try (var singleton = mockStatic(UltiTools.class)) {
                    singleton.when(UltiTools::getInstance).thenReturn(core);
                    UltiToolsPlugin module = mock(UltiToolsPlugin.class, CALLS_REAL_METHODS);
                    assertThat(module.getConfiguredLanguage()).isEqualTo(first.getLanguage());
                    assertThat(reads).hasValue(1);
                    initialize(core);
                    assertThat(module.getConfiguredLanguage()).isEqualTo(next.getLanguage());
                }
                assertThat(engine.fallback()).isEqualTo(next);
                assertThat(info(records)).hasSize(2);
                assertThat(info(records).getLast()).isEqualTo(selectionMessage(next, nextZone));
                assertThat(reads).hasValue(2);
                // nosemgrep: java.lang.security.audit.path-traversal.path-traversal
                assertThat(Files.readString(file)).isEqualTo(original); // Test-owned fixed config file.
            } finally {
                root.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "en"})
    void existingSixXConfig_doesNotInheritTheNewShippedFallbackOrChangeItsBytes(String code) throws Exception {
        String original = "# keep this operator comment\nlanguage: " + code + "\n";
        Path file = temporary.resolve("config.yml");
        Files.writeString(file, original);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertThat(input).isNotNull();
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8)));
        }
        assertThat(config.getString("fallback-language")).as("Bukkit defaults really are attached").isEqualTo("auto");
        assertThat(config.getString("fallback-language", null)).isNull();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = logger(records);
        var resolver = ConfiguredLocaleResolver.forConfig(() -> config, logger,
                () -> ZoneId.of(code.equals("zh") ? "UTC" : "Asia/Shanghai"));
        try (TextEngine engine = frameworkEngine(resolver, logger)) {
            SimpleContainer root = new SimpleContainer();
            root.registerSingleton(TextEngine.class.getName(), engine);
            try {
                UltiTools core = core(config, logger, root);
                initialize(core);
                assertThat(engine.fallback()).isEqualTo(Locale.forLanguageTag(code));
                assertThat(resolver.forced()).contains(Locale.forLanguageTag(code));
                assertThat(core.i18n("Module '%s' reloaded."))
                        .isEqualTo(code.equals("zh") ? "模块 '%s' 已重载。" : "Module '%s' reloaded.");
                assertThat(info(records)).hasSize(1);
                // nosemgrep: java.lang.security.audit.path-traversal.path-traversal
                assertThat(Files.readString(file)).isEqualTo(original); // Test-owned fixed config file.
            } finally {
                root.close();
            }
        }
    }

    @Test
    void shippedConfig_hasBothAutomaticDefaults() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertThat(input).isNotNull();
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            assertThat(defaults.getString("language")).isEqualTo("auto");
            assertThat(defaults.getString("fallback-language")).isEqualTo("auto");
        }
    }

    private static String selectionMessage(Locale locale, String zone) {
        return locale.equals(Locale.CHINESE)
                ? "后备语言：zh（系统时区 " + zone + "；可在 config.yml 中设置 fallback-language 覆盖）"
                : "Fallback language: en (system time zone " + zone + "; set fallback-language in config.yml to override)";
    }

    private static String legacyReloadMessage(Locale locale) {
        return locale.equals(Locale.CHINESE) ? "模块 '%s' 已重载。" : "Module '%s' reloaded.";
    }

    private TextEngine frameworkEngine(ConfiguredLocaleResolver resolver, Logger logger) {
        return new TextEngine(resolver, logger, TextEngine.class.getProtectionDomain().getCodeSource().getLocation(),
                temporary.resolve("messages"));
    }

    private UltiTools core(YamlConfiguration config, Logger logger, SimpleContainer root) {
        UltiTools core = mock(UltiTools.class, CALLS_REAL_METHODS);
        DependenceManagers managers = mock(DependenceManagers.class);
        doReturn(root).when(managers).getContext();
        doReturn(managers).when(core).getDependenceManagers();
        doReturn(config).when(core).getConfig();
        doReturn(temporary.toFile()).when(core).getDataFolder();
        doReturn(logger).when(core).getLogger();
        return core;
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void initialize(UltiTools core) throws Exception {
        Method method = UltiTools.class.getDeclaredMethod("initLanguage");
        method.setAccessible(true); // NOPMD - Exercise the existing private startup/full-reload seam.
        method.invoke(core);
    }

    private static List<String> info(List<LogRecord> records) {
        return records.stream().filter(record -> Level.INFO.equals(record.getLevel())).map(LogRecord::getMessage).toList();
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* In-memory capture only. */ }
            @Override public void close() { /* No external resources. */ }
        });
        return logger;
    }
}
