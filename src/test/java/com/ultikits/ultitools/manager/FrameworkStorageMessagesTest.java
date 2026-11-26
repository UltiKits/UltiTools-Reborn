package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataStore;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.utils.VersionUtils;
import com.ultikits.ultitools.utils.VersionLookup;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class FrameworkStorageMessagesTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void backendSelectionHasThreeDistinctReasons(String code) throws Exception {
        List<String> lines = new ArrayList<>();
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            var logger = logger(lines);
            DataStoreManager.reportBackendSelection(logger, "mysql", false, false, "json");
            DataStoreManager.reportBackendSelection(logger, "mysql", true, false, "json");
            DataStoreManager.reportBackendSelection(logger, "postgres", false, false, "json");
            assertThat(lines).containsExactly(code.equals("en")
                    ? "Data store downgraded: datasource.type in config.yml is 'mysql', but 'json' is actually in use. Reason: mysql.enable is false, so the MySQL data source was never initialized"
                    : "数据存储降级：config.yml 里 datasource.type 配置为 'mysql'，实际使用的是 'json'。原因：mysql.enable 为 false，MySQL 数据源从未初始化",
                    code.equals("en") ? "Player data will be read from and written to the 'json' store. Data is not shared between backends - if this is not what you want, stop the server, fix config.yml and restart." : "玩家数据将读写 'json' 存储；不同后端之间的数据并不互通，如果这不是你要的，请停服修正 config.yml 后重启",
                    code.equals("en") ? "Data store downgraded: datasource.type in config.yml is 'mysql', but 'json' is actually in use. Reason: MySQL connection failed, the data source is unavailable (see the connection error above)" : "数据存储降级：config.yml 里 datasource.type 配置为 'mysql'，实际使用的是 'json'。原因：MySQL 连接失败，数据源不可用（详见上方的连接错误）",
                    code.equals("en") ? "Player data will be read from and written to the 'json' store. Data is not shared between backends - if this is not what you want, stop the server, fix config.yml and restart." : "玩家数据将读写 'json' 存储；不同后端之间的数据并不互通，如果这不是你要的，请停服修正 config.yml 后重启",
                    code.equals("en") ? "Data store downgraded: datasource.type in config.yml is 'postgres', but 'json' is actually in use. Reason: no registered data store provides that type" : "数据存储降级：config.yml 里 datasource.type 配置为 'postgres'，实际使用的是 'json'。原因：没有已注册的数据存储提供该类型",
                    code.equals("en") ? "Player data will be read from and written to the 'json' store. Data is not shared between backends - if this is not what you want, stop the server, fix config.yml and restart." : "玩家数据将读写 'json' 存储；不同后端之间的数据并不互通，如果这不是你要的，请停服修正 config.yml 后重启");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void updateOutcomeLinesUseFallbackLanguage(String code) throws Exception {
        List<String> lines = new ArrayList<>();
        var plugin = mock(UltiTools.class);
        var plugins = mock(PluginManager.class);
        when(plugin.getPluginManager()).thenReturn(plugins);
        when(plugins.getPluginList()).thenReturn(List.of());
        var env = new YamlConfiguration();
        env.set("version", "7.0.0");
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var tools = mockStatic(UltiTools.class);
             var versions = mockStatic(VersionUtils.class)) {
            tools.when(UltiTools::getInstance).thenReturn(plugin);
            tools.when(UltiTools::getEnv).thenReturn(env);
            versions.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.none());
            var manager = new UpdateManager(logger(lines));
            manager.checkUpdatesSync();
            assertThat(lines).contains(code.equals("en") ? "[UltiTools-API] Checking for updates..." : "[UltiTools-API] 正在检查版本更新...",
                    code.equals("en") ? "[UltiTools-API] All plugins are up to date!" : "[UltiTools-API] 所有插件已是最新版本！");
            var update = new UpdateInfo();
            update.setPluginName("Probe");
            update.setCurrentVersion("1.0");
            update.setLatestVersion("2.0");
            field(manager, "frameworkUpdate", update);
            var mapField = UpdateManager.class.getDeclaredField("moduleUpdates");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked") var updates = (Map<String, UpdateInfo>) mapField.get(manager);
            updates.put("Probe", update);
            var results = UpdateManager.class.getDeclaredMethod("logResults", List.class);
            results.setAccessible(true);
            results.invoke(manager, List.of("Missing"));
            assertThat(lines).contains(code.equals("en") ? "[UltiTools-API] UltiTools-API update available: 2.0 (current: 1.0)" : "[UltiTools-API] UltiTools-API有新版本 2.0 可用（当前：1.0）",
                    code.equals("en") ? "[UltiTools-API] Download URL: https://github.com/UltiKits/UltiTools-Reborn/releases/latest" : "[UltiTools-API] 下载地址：https://github.com/UltiKits/UltiTools-Reborn/releases/latest",
                    code.equals("en") ? "[UltiTools-API] Module updates available (1):" : "[UltiTools-API] 模块更新可用（1个）：",
                    code.equals("en") ? "[UltiTools-API] Update check incomplete: could not confirm the latest version of Missing." : "[UltiTools-API] 更新检查未完成：无法确认 Missing 的最新版本。");
        }
    }

    public static class Row extends BaseDataEntity<String> { }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void corruptJsonAndMysqlFailureArePlainLogs(String code) throws Exception {
        List<String> lines = new ArrayList<>();
        var plugin = mock(UltiTools.class);
        when(plugin.getLogger()).thenReturn(logger(lines));
        var config = new YamlConfiguration();
        config.set("mysql.maximumPoolSize", 1);
        config.set("mysql.connectionTimeout", 30000);
        config.set("mysql.maxLifetime", 1800000);
        when(plugin.getConfig()).thenReturn(config);
        Path table = Files.createDirectories(temp.resolve("table"));
        Path broken = Files.writeString(table.resolve("broken.json"), "{invalid");
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var tools = mockStatic(UltiTools.class);
             var bukkit = mockStatic(Bukkit.class);
             var pools = mockConstruction(HikariDataSource.class, (pool, context) -> { throw new IllegalStateException("failed"); })) {
            tools.when(UltiTools::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::getLogger).thenReturn(logger(lines));
            var operator = new SimpleJsonDataOperator<>(table.toString(), Row.class);
            assertThat(operator.getById("broken")).isNull();
            var store = new MysqlDataStore();
            assertThat(store.getDataSource()).isNull();
            assertThat(pools.constructed()).isEmpty();
            assertThat(lines).containsExactly((code.equals("en") ? "Found corrupted data! Location: " : "发现一个数据损坏！位置：") + broken.toAbsolutePath(),
                    code.equals("en") ? "MySQL connection failed: Could not initialize mocked construction" : "Mysql数据库连接失败：Could not initialize mocked construction");
            assertThat(lines).noneMatch(line -> line.contains("§"));
        }
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Logger logger(List<String> lines) {
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() { /* Immediate capture. */ }
            @Override public void close() { /* No resource. */ }
        });
        return logger;
    }
}
