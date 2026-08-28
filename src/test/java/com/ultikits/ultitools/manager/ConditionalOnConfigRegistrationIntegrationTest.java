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
import org.bukkit.plugin.java.JavaPluginLoader;
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

import com.ultikits.testfixtures.conditionallistenerdispatch.FalseConditionDispatchListener;
import com.ultikits.testfixtures.conditionallistenerdispatch.TrueConditionDispatchListener;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;

/**
 * WIRE-07 / D-17, D-19: proves at Bukkit's own boundary -- the command map and real event
 * dispatch -- that a false {@code @ConditionalOnConfig} condition actually removes the
 * registration, on both the standard command path and the listener package-scan path Task 1
 * gated.
 * <p>
 * Each half runs a true-condition and a false-condition fixture through the same real scan/
 * registration machinery in a single pass, so the true-condition sibling is the control proving
 * the false-condition sibling's absence is the gate working, not an unrelated scan/registration
 * failure (03-04-PLAN.md's "inert case" guard).
 * <br>
 * WIRE-07 / D-17、D-19：在 Bukkit 自身的边界——命令表与真实事件分发——上证明一个为
 * {@code false} 的 {@code @ConditionalOnConfig} 条件确实会移除注册，覆盖标准命令路径与 Task 1
 * 门控的监听器包扫描路径。
 * <p>
 * 两半测试都在同一次真实扫描/注册流程中，让 true 条件与 false 条件两个 fixture 一起跑，因此
 * true 条件的那一个是对照组，用来证明 false 条件那一个的缺席是门控生效，而不是扫描/注册本身
 * 出了无关的问题（03-04-PLAN.md 的"inert case"防护）。
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

    /**
     * Listener half: drives {@code ListenerManager.registerAll(plugin, packageName)} (the
     * overload Task 1 gated) over a real fixture package, then fires a real
     * {@link org.bukkit.event.player.PlayerJoinEvent} through MockBukkit's own plugin manager and
     * asserts on each fixture's own hit counter -- not a registration-list inspection, per
     * 03-04-PLAN.md's explicit instruction ("The listener case is asserted through the actual
     * event dispatch, not by inspecting a registration list").
     * <p>
     * {@code ListenerManager.register(plugin, listener)} calls
     * {@code Bukkit.getServer().getPluginManager().registerEvents(listener, UltiTools.getInstance())}.
     * That needs {@code UltiTools.getInstance().getPluginLoader()} to be a real, functioning
     * {@link JavaPluginLoader} -- the generic Mockito mock the shared {@code TestHelper} stubs
     * elsewhere in this test tree returns an empty {@code Map} by Mockito's own default answer
     * for unstubbed methods returning a {@code Map}, which makes {@code registerEvents} silently
     * attach zero real Bukkit handlers (proven empirically while building this test). A real
     * {@link JavaPluginLoader} is substituted here instead so a fired event genuinely reaches (or
     * does not reach) the fixture's {@code @EventHandler} method.
     */
    @Nested
    @DisplayName("Listener half: the package-scan path Task 1 gated")
    class ListenerHalf {

        @BeforeEach
        void resetHitCounters() {
            FalseConditionDispatchListener.HITS.set(0);
            TrueConditionDispatchListener.HITS.set(0);
        }

        @Test
        @DisplayName("false-condition listener receives no events; true-condition control does")
        void falseConditionListenerReceivesNoEventsTrueConditionDoes(@TempDir File tempDir) throws Exception {
            writeConfig(tempDir, "enableFalseListener: false\nenableTrueListener: true\n");

            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.isEnabled()).thenReturn(true);
            when(mockUltiTools.getPluginLoader()).thenReturn(new JavaPluginLoader(server));
            when(mockUltiTools.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
                    "ConditionalOnConfigRegistrationIntegrationTest.ListenerHalf"));
            publishUltiToolsInstance(mockUltiTools);

            UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
            when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());

            SimpleContainer container = new SimpleContainer();
            container.registerType(UltiToolsPlugin.class, mockPlugin);
            when(mockPlugin.getContext()).thenReturn(container);

            ListenerManager listenerManager = new ListenerManager();

            try {
                // Act - the overload Task 1 gated with ConditionalRegistrationEvaluator.
                listenerManager.registerAll(mockPlugin, "com.ultikits.testfixtures.conditionallistenerdispatch");

                // server.addPlayer() itself dispatches a real PlayerJoinEvent through Bukkit's
                // plugin manager (see PlayerEventManagerRegistrationTest's own fireJoinEvent()
                // for the same idiom) -- no separate manual callEvent needed.
                server.addPlayer();

                assertThat(FalseConditionDispatchListener.HITS.get())
                        .as("a listener whose @ConditionalOnConfig condition is false must receive "
                                + "no events -- ROADMAP Criterion 4")
                        .isZero();
                assertThat(TrueConditionDispatchListener.HITS.get())
                        .as("inert-case guard: the true-condition control must still receive the "
                                + "event, or the false-condition zero above would not distinguish the "
                                + "gate working from event dispatch itself being broken")
                        .isGreaterThan(0);
            } finally {
                container.close();
            }
        }
    }
}
