package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.ReloadReport;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class FrameworkManagerMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void reloadSummariesKeepAllFourOutcomes(String code) throws Exception {
        List<String> logs = new ArrayList<>();
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(logs));
            var manager = new PluginManager();
            var module = mock(UltiToolsPlugin.class);
            when(module.getPluginName()).thenReturn("Probe");
            when(module.reloadWithReport()).thenReturn(new ReloadReport());
            PluginListSeeding.add(manager, module);
            assertThat(manager.reloadAllAndReport()).containsExactly(code.equals("en")
                    ? "All 1 modules reloaded." : "已重载全部 1 个模块。");
            var partial = new ReloadReport();
            partial.partial("detail");
            when(module.reloadWithReport()).thenReturn(partial);
            assertThat(manager.reloadAllAndReport()).containsExactly(code.equals("en")
                    ? "Reloaded 1 modules; 1 only partially:" : "已重载 1 个模块，其中 1 个只重载了一部分：",
                    code.equals("en") ? "Module Probe reloaded partially; not reloaded: detail" : "模块 Probe 只重载了一部分；未重载：detail");
            var failed = mock(UltiToolsPlugin.class);
            when(failed.getPluginName()).thenReturn("Broken");
            when(failed.reloadWithReport()).thenThrow(new IllegalStateException("failed"));
            PluginListSeeding.add(manager, failed);
            assertThat(manager.reloadAllAndReport()).containsExactly(code.equals("en")
                    ? "Failed to reload 1 of 2 modules: Broken. See the console for each failure." : "重载失败 1 个（共 2 个模块）：Broken。每个失败的详情见控制台。",
                    code.equals("en") ? "1 of the others reloaded only partially:" : "其余模块中有 1 个只重载了一部分：",
                    code.equals("en") ? "Module Probe reloaded partially; not reloaded: detail" : "模块 Probe 只重载了一部分；未重载：detail");
            when(module.reloadWithReport()).thenReturn(new ReloadReport());
            assertThat(manager.reloadAllAndReport()).containsExactly(code.equals("en")
                    ? "Failed to reload 1 of 2 modules: Broken. See the console for each failure." : "重载失败 1 个（共 2 个模块）：Broken。每个失败的详情见控制台。");
            assertThat(logs).contains(code.equals("en") ? "[UltiTools-API] All 1 modules reloaded." : "[UltiTools-API] 已重载全部 1 个模块。");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void duplicateAndMissingDependencyLogCompleteSentences(String code) throws Exception {
        List<String> logs = new ArrayList<>();
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(logs));
            var manager = new PluginManager();
            var field = PluginManager.class.getDeclaredField("moduleJarIndex");
            field.setAccessible(true);
            var index = (ModuleJarIndex) field.get(manager);
            index.record("fixture.Main", new File("a.jar"));
            index.record("fixture.Main", new File("b.jar"));
            index.recordSupplier("fixture.Main", new File("a.jar"));
            var duplicate = PluginManager.class.getDeclaredMethod("warnOnDuplicateModuleJars");
            duplicate.setAccessible(true);
            duplicate.invoke(manager);
            var missing = PluginManager.class.getDeclaredMethod("logMissingRequiredPlugins", String.class, List.class);
            missing.setAccessible(true);
            missing.invoke(null, "Probe", List.of("Vault"));
            assertThat(logs).containsExactly(code.equals("en")
                    ? "[UltiTools-API] These JAR files all declare the same module main class fixture.Main: a.jar, b.jar. The module's classes load only from a.jar; the other copies are not loaded. Keep only one of them."
                    : "[UltiTools-API] 以下 JAR 文件都声明了同一个模块主类 fixture.Main：a.jar, b.jar。该模块的类只从 a.jar 加载，其余副本不会加载；请只保留其中一个。",
                    code.equals("en") ? "[UltiTools-API] Module 'Probe' requires Vault, which is not installed or not enabled; the module is not loaded."
                    : "[UltiTools-API] 模块 'Probe' 需要 Vault，但它没有安装或没有启用；该模块未加载。");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void missingRunningCooldownIsReportedAsZero(String code) throws Exception {
        List<String> logs = new ArrayList<>();
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(logs));
            var module = mock(UltiToolsPlugin.class);
            when(module.getPluginName()).thenReturn("Probe");
            var method = PluginManager.class.getDeclaredMethod("keepRunning", UltiToolsPlugin.class, ReloadReport.class,
                    String.class, Integer.class, Map.class, Set.class, String.class);
            method.setAccessible(true);
            var report = new ReloadReport();
            method.invoke(null, module, report, "fixture.Config#cooldown", null, new HashMap<>(), new HashSet<>(), "detail");
            assertThat(report.getPartialReasons()).containsExactly(code.equals("en")
                    ? "config-bound @CmdCD Config key 'cooldown' was not updated: detail; the running 0s is kept"
                    : "绑定配置的 @CmdCD Config key 'cooldown' 未更新：detail；继续使用运行中的 0 秒");
            assertThat(logs).containsExactly(code.equals("en")
                    ? "[UltiTools-API] Probe: @CmdCD bound to Config key 'cooldown' is not updated: detail; keeping 0s"
                    : "[UltiTools-API] Probe：绑定到 Config key 'cooldown' 的 @CmdCD 未更新：detail；继续使用 0 秒");
        }
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
