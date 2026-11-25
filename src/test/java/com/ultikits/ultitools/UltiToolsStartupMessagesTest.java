package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.EarlyLogCapture;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.utils.CommonUtils;
import com.ultikits.ultitools.utils.CloudAuthManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class UltiToolsStartupMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void startupRendersCompleteLinesInFallbackLanguage(String code) throws Exception {
        var plugin = mock(UltiTools.class);
        var scheduler = mock(Scheduler.class);
        var global = mock(TaskContext.class);
        when(scheduler.global()).thenReturn(global);
        var store = mock(DataStore.class);
        when(store.getStoreType()).thenReturn("json");
        Field field = UltiTools.class.getDeclaredField("dataStore");
        field.setAccessible(true);
        field.set(plugin, store);
        List<String> lines = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() { /* No buffered output. */ }
            @Override public void close() { /* No external resource. */ }
        });
        when(plugin.getLogger()).thenReturn(logger);
        var env = new YamlConfiguration();
        env.set("version", "7.0.0-SNAPSHOT");
        FrameworkScheduling.install(scheduler);
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var tools = mockStatic(UltiTools.class);
             var common = mockStatic(CommonUtils.class);
             var cloud = mockStatic(CloudAuthManager.class);
             var bukkit = mockStatic(Bukkit.class);
             var updates = mockConstruction(UpdateManager.class)) {
            tools.when(UltiTools::getEnv).thenReturn(env);
            cloud.when(CloudAuthManager::status).thenReturn(mock(CloudAuthManager.CloudStatus.class));
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            common.when(CommonUtils::getUltiToolsUUID).thenReturn("uuid");
            invoke(plugin, "scheduleStartupMessages");
            var task = ArgumentCaptor.forClass(Runnable.class);
            verify(global).run(task.capture());
            task.getValue().run();
            assertThat(lines).contains(code.equals("en") ? "Data Storage Method: json" : "数据存储方式：json",
                    code.equals("en") ? "UltiTools-API enabled, current version: 7.0.0-SNAPSHOT" : "UltiTools-API已启动，当前版本：7.0.0-SNAPSHOT",
                    code.equals("en") ? "Server UUID: uuid" : "服务器UUID: uuid");
            common.when(CommonUtils::getUltiToolsUUID).thenThrow(new IOException((String) null));
            task.getValue().run();
            assertThat(lines).contains(code.equals("en") ? "Could not obtain the server UUID!null" : "获取服务器UUID失败！null");
            bukkit.verify(Bukkit::getScheduler, never());
            assertThat(updates.constructed()).hasSize(2);
        } finally {
            FrameworkScheduling.uninstall(scheduler);
        }
    }

    @Test
    void earlyCaptureReleaseUsesFrameworkDuration() throws Exception {
        var scheduler = mock(Scheduler.class);
        var global = mock(TaskContext.class);
        when(scheduler.global()).thenReturn(global);
        FrameworkScheduling.install(scheduler);
        try (var bukkit = mockStatic(Bukkit.class)) {
            var method = UltiTools.class.getDeclaredMethod("releaseEarlyLogCapture", boolean.class);
            method.setAccessible(true);
            method.invoke(mock(UltiTools.class), true);
            verify(global).runLater(eq(Duration.ofMillis(EarlyLogCapture.RELEASE_AFTER_MS + 1000L)), any(Runnable.class));
            bukkit.verify(Bukkit::getScheduler, never());
        } finally {
            FrameworkScheduling.uninstall(scheduler);
        }
    }

    private static void invoke(UltiTools plugin, String name) throws Exception {
        var method = UltiTools.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(plugin);
    }
}
