package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;

/**
 * WIRE-07 / D-19: proves at Bukkit's own boundary -- the command map -- that a false
 * {@code @ConditionalOnConfig} condition actually removes the registration, on the standard
 * bean-resolving command path.
 * <p>
 * Runs a true-condition and a false-condition fixture through the same real scan/registration
 * machinery in a single pass, so the true-condition sibling is the control proving the
 * false-condition sibling's absence is the gate working, not an unrelated scan/registration
 * failure (03-04-PLAN.md's "inert case" guard).
 * <p>
 * This class formerly carried a second half proving the same gate on
 * {@code ListenerManager.registerAll(UltiToolsPlugin, String)}'s reflective package-scan path
 * (D-17). Plan 07-14 (GEN-04) deleted that overload outright -- it was the only place in the
 * framework that ran {@link com.ultikits.ultitools.context.ConditionalRegistrationEvaluator}
 * against a manually reflection-scanned class outside the container; the surviving
 * {@code registerAll(UltiToolsPlugin)} resolves listeners from beans the container already
 * conditionally registered (or did not) via {@code ComponentScanner}, so there is no analogous
 * gate left on that path to prove separately -- it is covered by the container's own
 * {@code @ConditionalOnConfig} tests, not by this class.
 * <br>
 * WIRE-07 / D-19：在 Bukkit 自身的边界——命令表——上证明一个为 {@code false} 的
 * {@code @ConditionalOnConfig} 条件确实会移除注册，覆盖标准的按 bean 解析的命令路径。
 * <p>
 * 让 true 条件与 false 条件两个 fixture 在同一次真实扫描/注册流程中一起跑，因此 true 条件的
 * 那一个是对照组，用来证明 false 条件那一个的缺席是门控生效，而不是扫描/注册本身出了无关的
 * 问题（03-04-PLAN.md 的"inert case"防护）。
 * <p>
 * 这个类原先还有第二半，用来在 {@code ListenerManager.registerAll(UltiToolsPlugin, String)} 的
 * 反射式包扫描路径（D-17）上证明同一个门控。计划 07-14（GEN-04）整体删除了这个重载——它是框架
 * 中唯一一处会对一个手动反射扫描出来、不经过容器的类运行
 * {@link com.ultikits.ultitools.context.ConditionalRegistrationEvaluator} 的地方；存活下来的
 * {@code registerAll(UltiToolsPlugin)} 只会从容器已经（有条件地）注册好的 bean 中解析监听器，
 * 因此那条路径上已经没有需要单独证明的同类门控了——它由容器自身的
 * {@code @ConditionalOnConfig} 测试覆盖，不再由这个类覆盖。
 */
@DisplayName("@ConditionalOnConfig at the Bukkit boundary (WIRE-07)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConditionalOnConfigRegistrationIntegrationTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private void writeConfig(File tempDir, String content) throws Exception {
        File configFile = new File(tempDir, "config/config.yml");
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void publishUltiToolsInstance(UltiTools mock) throws Exception {
        java.lang.reflect.Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, mock);
    }

    /**
     * Command half (D-19): drives the same real machinery
     * {@code PluginManager.register(Class)} -> {@code registerBukkit(plugin, true)} ->
     * {@code CommandManager.registerAll(plugin)} relies on -- a real {@link SimpleContainer}
     * scanning a real fixture package with {@link ComponentScanner}, then the real, non-deprecated
     * {@code CommandManager.registerAll(UltiToolsPlugin)} bean-resolving overload -- without
     * needing a full plugin.yml/jar-backed {@code UltiToolsPlugin} construction, which
     * {@code register(Class)} does but which is orthogonal to the condition-gating claim under
     * test here.
     * <p>
     * {@code CommandManager}'s private {@code getCommandMap()} helper reflectively requires
     * {@code Bukkit.getPluginManager() instanceof SimplePluginManager} -- true on a real server,
     * false under MockBukkit's own {@code PluginManagerMock} (verified empirically). A real
     * {@link SimplePluginManager} wrapping a real {@link SimpleCommandMap} is substituted via a
     * narrow, single-method {@link MockedStatic} override of {@code Bukkit.getPluginManager()}
     * (all other {@code Bukkit.*} calls still delegate to the real MockBukkit server), so the
     * assertion below reads the actual {@link SimpleCommandMap} the framework's own registration
     * code wrote into.
     */
    @Nested
    @DisplayName("Command half (D-19): the standard bean-resolving path")
    class CommandHalf {

        @Test
        @DisplayName("false-condition command is absent from Bukkit's command map; true-condition control is present")
        void falseConditionCommandAbsentTrueConditionPresent(@TempDir File tempDir) throws Exception {
            writeConfig(tempDir, "enableFalseCommand: false\nenableTrueCommand: true\n");

            SimpleCommandMap realCommandMap = new SimpleCommandMap(server, new HashMap<>());
            SimplePluginManager realPluginManager = new SimplePluginManager(server, realCommandMap);

            UltiTools mockUltiTools = mock(UltiTools.class);
            CommandManager commandManager = new CommandManager();
            when(mockUltiTools.getCommandManager()).thenReturn(commandManager);
            when(mockUltiTools.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
                    "ConditionalOnConfigRegistrationIntegrationTest.CommandHalf"));
            when(mockUltiTools.getDescription())
                    .thenReturn(new PluginDescriptionFile("UltiTools", "1.0.0", "com.ultikits.ultitools.UltiTools"));
            publishUltiToolsInstance(mockUltiTools);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class, Answers.CALLS_REAL_METHODS)) {
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(realPluginManager);

                UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
                when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
                when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

                SimpleContainer container = new SimpleContainer();
                // Mirrors PluginManager.initializePlugin's own ordering: register the plugin by
                // type BEFORE scanning, so ConditionalRegistrationEvaluator's container lookup
                // resolves it during the scan (see 03-04-PLAN.md's flagged assumption).
                container.registerType(UltiToolsPlugin.class, mockPlugin);
                when(mockPlugin.getContext()).thenReturn(container);
                container.scanComponents("com.ultikits.testfixtures.conditionalcommand");
                container.refresh();

                // Act - the real, non-deprecated bean-resolving overload registerBukkit(plugin,
                // true) calls.
                commandManager.registerAll(mockPlugin);

                // Assert - both directions, so the false-condition absence means something.
                assertThat(realCommandMap.getCommand("conditionalfalsecmd"))
                        .as("a false @ConditionalOnConfig command must never reach Bukkit's command map")
                        .isNull();
                assertThat(realCommandMap.getCommand("conditionaltruecmd"))
                        .as("inert-case guard: the true-condition control must still register, or the "
                                + "false-condition absence above would not distinguish the gate working "
                                + "from the whole scan/registration path being broken")
                        .isNotNull();

                container.close();
            }
        }
    }
}
