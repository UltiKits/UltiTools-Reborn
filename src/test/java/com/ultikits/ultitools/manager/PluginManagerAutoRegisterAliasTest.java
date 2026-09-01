package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * End-to-end proof that {@code @UltiToolsModule}'s {@code cmdExecutor}/{@code eventListener}
 * {@code @AliasFor} switches actually reach {@link PluginManager#registerBukkit}'s registration
 * decision, now that it resolves {@code @EnableAutoRegister} through
 * {@link com.ultikits.ultitools.context.MergedAnnotationResolver} instead of
 * {@link com.ultikits.ultitools.utils.AnnotationUtils#findAnnotation} (WIRE-08).
 * <p>
 * Companion to {@code MergedAnnotationResolverTest}, which covers the resolver's own merge
 * semantics in isolation; this class exercises the same switches through the real caller.
 * <p>
 * {@link ConnectorPathParityTests} additionally proves WIRE-05 differences #6-#9 (04-08):
 * {@code registerBukkit}'s boolean fork is gone, so {@code manualRegister()} and
 * {@code @ConditionalOnConfig} are honoured identically no matter which entry point loaded the
 * module.
 */
@DisplayName("PluginManager auto-register @AliasFor wiring (WIRE-08)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of the private registerBukkit
class PluginManagerAutoRegisterAliasTest {

    // Abstract fixtures extending UltiToolsPlugin, mocked rather than constructed -- Mockito
    // bypasses the constructor entirely (Objenesis) and, with the default inline mock maker,
    // mock.getClass() returns the real fixture class, so its annotations are read correctly.
    // Following the same idiom already established in DependencyUtilsTest.

    // Declared before the nested fixture classes below: PMD's
    // FieldDeclarationsShouldBeAtStartOfClass requires fields to precede any inner class.
    private PluginManager pluginManager;
    private CommandManager mockCommandManager;
    private ListenerManager mockListenerManager;
    private ServerMock server;

    @UltiToolsModule
    abstract static class DefaultModuleFixture extends UltiToolsPlugin {
    }

    @UltiToolsModule(cmdExecutor = false)
    abstract static class CmdExecutorDisabledFixture extends UltiToolsPlugin {
    }

    @ComponentScan(basePackages = "com.ultikits.testfixtures.manualregisterlistener")
    @EnableAutoRegister
    abstract static class ManualRegisterListenerModuleFixture extends UltiToolsPlugin {
    }

    @ComponentScan(basePackages = "com.ultikits.testfixtures.conditionalcommand")
    @EnableAutoRegister
    abstract static class ConditionalCommandModuleFixture extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        mockCommandManager = mock(CommandManager.class);
        mockListenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(mockCommandManager);
            when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
        });

        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private void invokeRegisterBukkit(UltiToolsPlugin plugin) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("registerBukkit", UltiToolsPlugin.class);
        method.setAccessible(true);
        method.invoke(pluginManager, plugin);
    }

    @Test
    @DisplayName("Default @UltiToolsModule reaches CommandManager.registerAll")
    void defaultModuleRegistersCommands() throws Exception {
        UltiToolsPlugin plugin = mock(DefaultModuleFixture.class);

        invokeRegisterBukkit(plugin);

        verify(mockCommandManager, times(1)).registerAll(plugin);
    }

    @Test
    @DisplayName("@UltiToolsModule(cmdExecutor = false) suppresses CommandManager.registerAll entirely")
    void cmdExecutorDisabledModuleNeverReachesCommandManager() throws Exception {
        UltiToolsPlugin plugin = mock(CmdExecutorDisabledFixture.class);

        invokeRegisterBukkit(plugin);

        verify(mockCommandManager, never()).registerAll(plugin);
    }

    @Test
    @DisplayName("Inert-case guard: the default and disabled fixtures must not produce equal registerAll counts")
    void defaultAndDisabledFixturesMustNotProduceEqualCounts() throws Exception {
        // If the resolver silently returns the un-merged @EnableAutoRegister (the exact defect
        // this plan closes), both fixtures would register and the two counts below would be
        // equal (1 and 1) instead of (1 and 0) -- this assertion fails loudly on that equality
        // rather than merely leaving it unasserted.
        UltiToolsPlugin defaultPlugin = mock(DefaultModuleFixture.class);
        UltiToolsPlugin disabledPlugin = mock(CmdExecutorDisabledFixture.class);

        invokeRegisterBukkit(defaultPlugin);
        invokeRegisterBukkit(disabledPlugin);

        long defaultCount = org.mockito.Mockito.mockingDetails(mockCommandManager).getInvocations().stream()
                .filter(invocation -> "registerAll".equals(invocation.getMethod().getName())
                        && invocation.getArgument(0) == defaultPlugin)
                .count();
        long disabledCount = org.mockito.Mockito.mockingDetails(mockCommandManager).getInvocations().stream()
                .filter(invocation -> "registerAll".equals(invocation.getMethod().getName())
                        && invocation.getArgument(0) == disabledPlugin)
                .count();

        Assertions.assertNotEquals(defaultCount, disabledCount,
                "a resolver that fails to merge the @AliasFor override would register both fixtures identically");
        Assertions.assertEquals(1L, defaultCount);
        Assertions.assertEquals(0L, disabledCount);
    }

    /**
     * WIRE-05 differences #6-#9 (04-08): before this plan, {@code registerBukkit}'s {@code
     * boolean} fork sent {@code register(UltiToolsPlugin)} (the "connector" entry point, flag
     * {@code false}) down the reflective package-scan overloads instead of the bean-resolution
     * ones the standard {@code register(Class)} path (flag {@code true}) already used. Those
     * package-scan overloads neither consult {@code manualRegister()} (listener side) nor
     * {@code @ConditionalOnConfig} (command side, because the container's own gating during
     * {@code scanComponents} never runs for a class the overload finds by raw reflection), and
     * the command side additionally casts to the legacy {@code AbstractCommandExecutor} (removed
     * in 6.3.0 by plan 07-15), which threw an uncaught {@code ClassCastException} for any class
     * extending the current {@code BaseCommandExecutor} (issue #272).
     * <p>
     * {@code registerBukkit} now has a single one-argument signature reached identically from
     * both {@code register(Class)} and {@code register(UltiToolsPlugin)} -- these tests reuse the
     * outer class's {@link #invokeRegisterBukkit(UltiToolsPlugin)} helper over a REAL {@link
     * SimpleContainer} and REAL {@link ListenerManager}/{@link CommandManager} (not mocks), so the
     * assertions exercise the actual gating logic rather than a routing stub.
     */
    @Nested
    @DisplayName("registerBukkit's connector path honours manualRegister/@ConditionalOnConfig identically (WIRE-05 differences #6-#9)")
    class ConnectorPathParityTests {

        @SuppressWarnings("unchecked")
        private List<Listener> registeredListenersFor(ListenerManager listenerManager, UltiToolsPlugin plugin)
                throws Exception {
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            Map<UltiToolsPlugin, List<Listener>> map =
                    (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);
            return map.get(plugin);
        }

        @Test
        @DisplayName("a manualRegister=true listener is skipped when the module is loaded through register(UltiToolsPlugin)")
        void manualRegisterListenerSkipped_onRegisterInstancePath() throws Exception {
            UltiToolsPlugin plugin = mock(ManualRegisterListenerModuleFixture.class);
            SimpleContainer container = new SimpleContainer();
            container.registerType(UltiToolsPlugin.class, plugin);
            when(plugin.getContext()).thenReturn(container);
            container.scanComponents("com.ultikits.testfixtures.manualregisterlistener");
            container.refresh();

            ListenerManager realListenerManager = new ListenerManager();
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                when(ultiTools.getListenerManager()).thenReturn(realListenerManager);
                when(ultiTools.getCommandManager()).thenReturn(mockCommandManager);
            });

            try {
                invokeRegisterBukkit(plugin);

                List<Listener> registered = registeredListenersFor(realListenerManager, plugin);
                assertThat(registered)
                        .as("register(UltiToolsPlugin)'s connector path must never register a "
                                + "manualRegister=true listener, exactly like register(Class) "
                                + "already doesn't")
                        .hasSize(1);
                assertThat(registered.get(0).getClass().getSimpleName())
                        .as("inert-case guard: the auto-register control must still register, or "
                                + "the manual one's absence would not distinguish the gate working "
                                + "from the whole scan/registration path being broken")
                        .isEqualTo("AutoRegisterListenerFixture");
            } finally {
                container.close();
            }
        }

        @Test
        @DisplayName("a @ConditionalOnConfig-disabled @CmdExecutor is skipped when the module is loaded through register(UltiToolsPlugin)")
        void conditionalOnConfigDisabledCommandSkipped_onRegisterInstancePath(@TempDir File tempDir) throws Exception {
            File configFile = new File(tempDir, "config/config.yml");
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write("enableFalseCommand: false\nenableTrueCommand: true\n");
            }

            SimpleCommandMap realCommandMap = new SimpleCommandMap(server, new HashMap<>());
            SimplePluginManager realBukkitPluginManager = new SimplePluginManager(server, realCommandMap);

            UltiToolsPlugin plugin = mock(ConditionalCommandModuleFixture.class);
            when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

            SimpleContainer container = new SimpleContainer();
            container.registerType(UltiToolsPlugin.class, plugin);
            when(plugin.getContext()).thenReturn(container);
            container.scanComponents("com.ultikits.testfixtures.conditionalcommand");
            container.refresh();

            CommandManager realCommandManager = new CommandManager();

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class, Answers.CALLS_REAL_METHODS)) {
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(realBukkitPluginManager);

                TestHelper.mockUltiToolsInstance(ultiTools -> {
                    when(ultiTools.getCommandManager()).thenReturn(realCommandManager);
                    when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
                    when(ultiTools.getDescription()).thenReturn(
                            new PluginDescriptionFile("UltiTools", "1.0.0", "com.ultikits.ultitools.UltiTools"));
                });

                Assertions.assertDoesNotThrow(() -> invokeRegisterBukkit(plugin),
                        "a @CmdExecutor extending BaseCommandExecutor must never reach the legacy "
                                + "AbstractCommandExecutor cast on the connector path (difference "
                                + "#9; AbstractCommandExecutor itself was removed in 6.3.0)");

                assertThat(realCommandMap.getCommand("conditionalfalsecmd"))
                        .as("a false @ConditionalOnConfig command must never reach Bukkit's "
                                + "command map, on the connector path either")
                        .isNull();
                assertThat(realCommandMap.getCommand("conditionaltruecmd"))
                        .as("inert-case guard: the true-condition control must still register, or "
                                + "the false-condition absence above would not distinguish the "
                                + "gate working from the whole scan/registration path being broken")
                        .isNotNull();
            } finally {
                container.close();
            }
        }
    }
}
