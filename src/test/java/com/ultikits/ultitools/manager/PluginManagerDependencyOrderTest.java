package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.PluginDependency;

/**
 * Caller-side coverage for {@code PluginManager.sortPluginsByDependencies} (SILENT-08): a
 * dependency cycle or a missing hard dependency must refuse only the affected module(s), not
 * degrade every module to filesystem order, and the degraded mode itself must survive only as an
 * explicit, cost-stating opt-in ({@code ultitools.useLegacyPluginLoading}).
 * <p>
 * SILENT-08 的调用方侧覆盖：一个依赖环或一个缺失的硬依赖，只应拒绝受影响的模块，
 * 而不是让所有模块退化为文件系统顺序；退化模式本身只能作为一个显式的、
 * 会说明代价的可选开关（{@code ultitools.useLegacyPluginLoading}）继续存在。
 */
@DisplayName("PluginManager 依赖排序拒绝范围测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerDependencyOrderTest {

    private PluginManager pluginManager;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();

        bukkitLogs.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bukkitLogs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Bukkit.getLogger().addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(captureHandler);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        // Backstop in case a test fails before its own try/finally clears the property.
        System.clearProperty("ultitools.useLegacyPluginLoading");
    }

    private List<String> severeMessagesInOrder() {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : bukkitLogs) {
            if (Level.SEVERE.equals(record.getLevel())) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    private String allSevereMessagesJoined() {
        StringBuilder joined = new StringBuilder();
        for (String message : severeMessagesInOrder()) {
            joined.append(message).append('\n');
        }
        return joined.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends UltiToolsPlugin>> invokeSort(
            List<Class<? extends UltiToolsPlugin>> plugins) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "sortPluginsByDependencies", List.class);
        method.setAccessible(true);
        return (List<Class<? extends UltiToolsPlugin>>) method.invoke(pluginManager, plugins);
    }

    // Fixtures - independent of PluginDependencyResolverTest's own fixtures so this test class
    // has no coupling to that class's test data.
    public static class OrderPluginA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { /* nothing to release: this fixture owns no state */ }
    }

    @PluginDependency(depends = {"OrderCircularB"})
    public static class OrderCircularA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { /* nothing to release: this fixture owns no state */ }
    }

    @PluginDependency(depends = {"OrderCircularA"})
    public static class OrderCircularB extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { /* nothing to release: this fixture owns no state */ }
    }

    @PluginDependency(depends = {"OrderMissingModule"})
    public static class OrderModuleWithMissingDep extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { /* nothing to release: this fixture owns no state */ }
    }

    @Test
    @DisplayName("一个依赖环只拒绝其成员，未受影响的模块仍出现在返回列表中")
    void cycleRefusesOnlyAffectedModulesWhileUnrelatedModuleSurvives() throws Exception {
        List<Class<? extends UltiToolsPlugin>> input = new ArrayList<>();
        input.add(OrderCircularA.class);
        input.add(OrderCircularB.class);
        input.add(OrderPluginA.class);

        List<Class<? extends UltiToolsPlugin>> result = invokeSort(input);

        assertThat(result).contains(OrderPluginA.class);
        assertThat(result).doesNotContain(OrderCircularA.class, OrderCircularB.class);
    }

    @Test
    @DisplayName("环检测的控制台输出按顺序命名检测行、环路径、作者提示与 opt-in 提示")
    void cycleConsoleMessageIsOrderedInFourParts() throws Exception {
        List<Class<? extends UltiToolsPlugin>> input = new ArrayList<>();
        input.add(OrderCircularA.class);
        input.add(OrderCircularB.class);

        invokeSort(input);

        List<String> severe = severeMessagesInOrder();
        assertThat(severe).hasSizeGreaterThanOrEqualTo(4);
        assertThat(severe.get(0)).containsIgnoringCase("circular dependency");
        assertThat(severe).anySatisfy(msg -> assertThat(msg).contains("->"));
        assertThat(severe).anySatisfy(msg -> assertThat(msg).containsIgnoringCase("author"));
        assertThat(severe.get(severe.size() - 1)).contains("ultitools.useLegacyPluginLoading");
    }

    @Test
    @DisplayName("缺失硬依赖只拒绝声明模块及其依赖者，控制台命名模块与缺失依赖")
    void missingHardDependencyRefusesOnlyDeclaringModuleAndDependents() throws Exception {
        List<Class<? extends UltiToolsPlugin>> input = new ArrayList<>();
        input.add(OrderModuleWithMissingDep.class);
        input.add(OrderPluginA.class);

        List<Class<? extends UltiToolsPlugin>> result = invokeSort(input);

        assertThat(result).contains(OrderPluginA.class);
        assertThat(result).doesNotContain(OrderModuleWithMissingDep.class);
        String severe = allSevereMessagesJoined();
        assertThat(severe).contains("OrderModuleWithMissingDep");
        assertThat(severe).contains("OrderMissingModule");
    }

    @Test
    @DisplayName("legacy 属性开启时返回原始列表且不咨询解析器")
    void legacyPropertyEnabledReturnsInputUnchangedWithoutConsultingResolver() throws Exception {
        System.setProperty("ultitools.useLegacyPluginLoading", "true");
        try {
            List<Class<? extends UltiToolsPlugin>> input = new ArrayList<>();
            input.add(OrderCircularA.class);
            input.add(OrderCircularB.class);

            List<Class<? extends UltiToolsPlugin>> result = invokeSort(input);

            // Unchanged input order, and no cycle-detection line was ever logged - proving the
            // resolver was never consulted, not merely that the caught exception was swallowed.
            assertThat(result).containsExactlyElementsOf(input);
            String severe = allSevereMessagesJoined();
            assertThat(severe).doesNotContain("Circular dependency detected");
            assertThat(severe).contains("ultitools.useLegacyPluginLoading");
        } finally {
            System.clearProperty("ultitools.useLegacyPluginLoading");
        }
    }

    @Test
    @DisplayName("无环无缺失依赖时返回完整排序列表")
    void noFailureReturnsFullSortedOrder() throws Exception {
        List<Class<? extends UltiToolsPlugin>> input = new ArrayList<>();
        input.add(OrderPluginA.class);

        List<Class<? extends UltiToolsPlugin>> result = invokeSort(input);

        assertThat(result).containsExactly(OrderPluginA.class);
    }
}
