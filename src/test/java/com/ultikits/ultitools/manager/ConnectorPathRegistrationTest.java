package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * GEN-03 (D-15): pins that the connector registration path -- {@code
 * PluginManager.registerBukkit(UltiToolsPlugin)} into the single-argument {@code
 * CommandManager.registerAll(UltiToolsPlugin)} -- accepts a {@link BaseCommandExecutor} subclass
 * with no uncaught {@link ClassCastException}, whether thrown directly or wrapped, AND that it
 * actually registers the command rather than merely completing silently.
 * <p>
 * GEN-03's acceptance is already satisfied by Phase 4's plan 04-08: {@code registerBukkit}'s
 * {@code boolean} fork is gone and both entry points bean-resolve without casting to the legacy
 * {@link AbstractCommandExecutor}. This class is therefore a REGRESSION GUARD, not evidence of a
 * change made in this plan -- 05-VALIDATION.md's GEN-03 row and CONTEXT.md D-15 both require the
 * assertion be taken at the connector path itself (never a reference count, never an
 * instantiation-only check), so that Phase 7's removal of {@code AbstractCommandExecutor} cannot
 * silently reintroduce the cast this class pins as absent.
 * <p>
 * Reuses {@link PluginManagerAutoRegisterAliasTest}'s exact {@code registerBukkit} reflective-
 * invoke plus real {@link CommandManager} plus real Bukkit {@link SimpleCommandMap} harness shape
 * -- that class already proved the connector path for {@code @ConditionalOnConfig} gating; this
 * class asserts the base-class-acceptance dimension GEN-03 names instead.
 * <br>
 * GEN-03（D-15）：钉住连接器注册路径——{@code PluginManager.registerBukkit(UltiToolsPlugin)}
 * 进入单参数的 {@code CommandManager.registerAll(UltiToolsPlugin)}——能接受一个
 * {@link BaseCommandExecutor} 子类而不抛出未捕获的 {@link ClassCastException}（无论是直接抛出还是被包
 * 装），并且确实注册了该命令，而不只是静默地跑完。
 */
@DisplayName("Connector-path command registration accepts BaseCommandExecutor (GEN-03/D-15)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of the private registerBukkit
class ConnectorPathRegistrationTest {

    private static final String BASE_COMMAND_ALIAS = "connectorpathbasecmd";
    private static final String LEGACY_COMMAND_ALIAS = "connectorpathlegacycmd";

    private PluginManager pluginManager;
    private ListenerManager mockListenerManager;
    private CommandManager realCommandManager;
    private SimpleCommandMap realCommandMap;
    private ServerMock server;

    /** A minimal module fixture -- default {@code @UltiToolsModule} enables command auto-register. */
    @UltiToolsModule
    abstract static class ModuleFixture extends UltiToolsPlugin {
    }

    /** The current generation: a command class extending {@link BaseCommandExecutor}. */
    @CmdExecutor(alias = {BASE_COMMAND_ALIAS}, permission = "test.connectorpath.base")
    static class BaseCommandFixture extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("base command help");
        }
    }

    /** The legacy generation: a command class extending {@link AbstractCommandExecutor}. */
    @CmdExecutor(alias = {LEGACY_COMMAND_ALIAS}, permission = "test.connectorpath.legacy")
    static class LegacyCommandFixture extends AbstractCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("legacy command help");
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        mockListenerManager = mock(ListenerManager.class);
        realCommandManager = new CommandManager();
        realCommandMap = new SimpleCommandMap(server, new HashMap<>());

        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    /** Invokes the private {@code PluginManager.registerBukkit(UltiToolsPlugin)} reflectively --
     * the same entry point {@link PluginManagerAutoRegisterAliasTest} uses, so this test enters
     * the connector path itself and not a single {@link CommandManager} method in isolation. */
    private void invokeRegisterBukkit(UltiToolsPlugin plugin) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("registerBukkit", UltiToolsPlugin.class);
        method.setAccessible(true);
        method.invoke(pluginManager, plugin);
    }

    /** A module whose container is fully under test control: no component scanning, the command
     * fixtures are registered directly as singletons so {@code CommandManager.registerAll}'s
     * {@code getBeanNamesForType(CommandExecutor.class)} lookup finds them. */
    private UltiToolsPlugin newModulePlugin(SimpleContainer container) {
        UltiToolsPlugin plugin = mock(ModuleFixture.class);
        when(plugin.getContext()).thenReturn(container);
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return plugin;
    }

    /** Wires a real Bukkit {@link SimpleCommandMap} behind {@code Bukkit.getPluginManager()} and a
     * real {@link CommandManager} behind {@code UltiTools.getInstance().getCommandManager()}, so
     * the assertion observes the actual Bukkit command-map boundary rather than a routing stub. */
    private void withRealBukkitCommandMap(Runnable action) {
        SimplePluginManager realBukkitPluginManager = new SimplePluginManager(server, realCommandMap);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class, Answers.CALLS_REAL_METHODS)) {
            bukkitMock.when(Bukkit::getPluginManager).thenReturn(realBukkitPluginManager);

            TestHelper.mockUltiToolsInstance(ultiTools -> {
                when(ultiTools.getCommandManager()).thenReturn(realCommandManager);
                when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
                when(ultiTools.getDescription()).thenReturn(
                        new PluginDescriptionFile("UltiTools", "1.0.0", "com.ultikits.ultitools.UltiTools"));
            });

            action.run();
        }
    }

    @Test
    @DisplayName("Test 1+2: a BaseCommandExecutor registers through the connector path with no "
            + "exception, and is actually registered -- not merely a silent no-throw")
    void baseCommandExecutor_registersThroughConnectorPath_withNoExceptionAndActuallyRegistered() {
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("baseCommandFixture", new BaseCommandFixture());
        UltiToolsPlugin plugin = newModulePlugin(container);

        withRealBukkitCommandMap(() -> {
            assertDoesNotThrow(() -> invokeRegisterBukkit(plugin),
                    "a BaseCommandExecutor auto-registered through the connector path "
                            + "(registerBukkit -> single-arg registerAll) must not throw an "
                            + "uncaught exception, whether thrown directly or wrapped");

            assertThat(realCommandMap.getCommand(BASE_COMMAND_ALIAS))
                    .as("the connector path must actually register the command in Bukkit's "
                            + "command map -- this is what distinguishes 'did not throw' from "
                            + "'did nothing', per 05-VALIDATION.md's GEN-03 row")
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("Test 3: a legacy AbstractCommandExecutor also registers through the same "
            + "connector path -- Phase 4's fix did not trade one generation for the other")
    void legacyAbstractCommandExecutor_alsoRegistersThroughConnectorPath() {
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("legacyCommandFixture", new LegacyCommandFixture());
        UltiToolsPlugin plugin = newModulePlugin(container);

        withRealBukkitCommandMap(() -> {
            assertDoesNotThrow(() -> invokeRegisterBukkit(plugin),
                    "a legacy AbstractCommandExecutor must still register through the connector "
                            + "path with no uncaught exception");

            assertThat(realCommandMap.getCommand(LEGACY_COMMAND_ALIAS))
                    .as("registration must actually happen for the legacy generation too")
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("Test 4: a module holding one BaseCommandExecutor and one AbstractCommandExecutor "
            + "registers both through the connector path")
    void moduleWithBothGenerations_registersBothThroughConnectorPath() {
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("baseCommandFixture", new BaseCommandFixture());
        container.registerSingleton("legacyCommandFixture", new LegacyCommandFixture());
        UltiToolsPlugin plugin = newModulePlugin(container);

        withRealBukkitCommandMap(() -> {
            assertDoesNotThrow(() -> invokeRegisterBukkit(plugin),
                    "a module mixing both command generations must register through the "
                            + "connector path with no uncaught exception");

            assertThat(realCommandMap.getCommand(BASE_COMMAND_ALIAS)).isNotNull();
            assertThat(realCommandMap.getCommand(LEGACY_COMMAND_ALIAS)).isNotNull();
        });
    }

    @Test
    @DisplayName("Test 5: the connector path with an empty container completes without throwing "
            + "and registers nothing")
    void emptyContainer_completesWithoutThrowing_registersNothing() {
        SimpleContainer container = new SimpleContainer();
        UltiToolsPlugin plugin = newModulePlugin(container);

        withRealBukkitCommandMap(() -> {
            assertDoesNotThrow(() -> invokeRegisterBukkit(plugin),
                    "an empty container must complete the connector path without throwing");

            assertThat(realCommandMap.getCommand(BASE_COMMAND_ALIAS)).isNull();
            assertThat(realCommandMap.getCommand(LEGACY_COMMAND_ALIAS)).isNull();
        });
    }
}
