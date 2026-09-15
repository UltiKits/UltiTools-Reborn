package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.Command;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Real-machine UAT regression test for {@code ultitools.upm.uninstall.neg-command-gone}
 * (16-02, PR #457).
 *
 * <p>Measured on a real Paper 1.21.11 server: after {@code /upm uninstall}, the module's
 * own command still resolved and executed. Root cause, confirmed by reading
 * {@code org.bukkit.command.Command#unregister(CommandMap)} bytecode (only nulls the
 * command's own {@code commandMap}/{@code label}/{@code activeAliases} fields) and
 * {@code SimpleCommandMap#register(String, Command, boolean, String)} source (puts the
 * command instance under FOUR keys: bare label, bare alias(es), {@code prefix:label},
 * {@code prefix:alias}): {@link CommandManager#unregister(String)} calls
 * {@code Command.unregister(CommandMap)} but never removes the corresponding entries from
 * {@link CommandMap#getKnownCommands()}, so the label/alias/namespaced keys keep resolving
 * to the unloaded module's executor.
 *
 * <p>MockBukkit's {@code ServerMock} does not use {@link SimplePluginManager} (confirmed by
 * the existing {@code GetCommandMapSimplePluginManagerTests} in {@link CommandManagerTest}),
 * so {@link CommandManager#unregister(String)}'s {@code Bukkit.getPluginManager() instanceof
 * SimplePluginManager} branch is unreachable under a bare MockBukkit server. This test wires
 * a <em>real</em> {@link SimplePluginManager} backed by a real {@link SimpleCommandMap} and
 * substitutes it via {@code Bukkit.getPluginManager()} so the actual production code path
 * (register through the real Bukkit command map, then unregister) runs end to end.
 */
@DisplayName("CommandManager known-commands cleanup on unregister (16-02)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection into private register()/registerAll() primitives, matching CommandManagerTest's established pattern
class CommandManagerKnownCommandsCleanupTest {

    private static final String FALLBACK_PREFIX = "ultitools";

    private CommandManager commandManager;
    private UltiToolsPlugin mockPlugin;
    private SimpleCommandMap realCommandMap;
    private SimplePluginManager realPluginManager;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            PluginDescriptionFile description =
                    new PluginDescriptionFile(FALLBACK_PREFIX, "1.0", "com.ultikits.ultitools.UltiTools");
            when(ultiTools.getDescription()).thenReturn(description);
        });

        mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getPluginName()).thenReturn("TestModule");
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        // A real SimpleCommandMap/SimplePluginManager pair -- see class javadoc for why a
        // bare MockBukkit server cannot exercise CommandManager's real cleanup path.
        realCommandMap = new SimpleCommandMap(server, new HashMap<>());
        realPluginManager = new SimplePluginManager(server, realCommandMap);

        commandManager = new CommandManager();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    /**
     * Command executor whose sole alias is {@code redcmd} -- the base single-alias case.
     */
    @CmdExecutor(alias = {"redcmd"}, permission = "test.red", description = "Red command")
    static class SingleAliasCommandExecutor extends BaseCommandExecutor {
        @CmdMapping(format = "")
        public void execute(@CmdSender CommandSender sender) {
            sender.sendMessage("red executed");
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            execute(sender);
            return true;
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("help");
        }
    }

    /**
     * Command executor with a primary label plus two aliases, to prove every key -- not
     * just the bare primary label -- gets scrubbed.
     */
    @CmdExecutor(alias = {"multicmd", "mcmd", "m"}, permission = "test.multi", description = "Multi command")
    static class MultiAliasCommandExecutor extends BaseCommandExecutor {
        @CmdMapping(format = "")
        public void execute(@CmdSender CommandSender sender) {
            sender.sendMessage("multi executed");
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            execute(sender);
            return true;
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("help");
        }
    }

    /**
     * Command executor for the "shared label with another owner" over-deletion guard.
     */
    @CmdExecutor(alias = {"shared"}, permission = "test.shared", description = "Shared command")
    static class SharedLabelCommandExecutor extends BaseCommandExecutor {
        @CmdMapping(format = "")
        public void execute(@CmdSender CommandSender sender) {
            sender.sendMessage("shared executed");
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            execute(sender);
            return true;
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("help");
        }
    }

    /**
     * Registers {@code executor} for {@code mockPlugin} through the exact same private
     * primitive the framework itself uses ({@code CommandManager#register(UltiToolsPlugin,
     * CommandExecutor)}), reached via reflection the same way {@code CommandManagerTest}'s
     * {@code PrivateMethodReflectionTests} already does.
     */
    private void registerViaFramework(CommandExecutor executor) throws Exception {
        registerViaFramework(mockPlugin, executor);
    }

    /**
     * Same as {@link #registerViaFramework(CommandExecutor)} but for an explicit owning
     * plugin, so a test can register two different modules under the same primary label.
     */
    private void registerViaFramework(UltiToolsPlugin plugin, CommandExecutor executor) throws Exception {
        Method registerMethod = CommandManager.class.getDeclaredMethod(
                "register", UltiToolsPlugin.class, CommandExecutor.class);
        registerMethod.setAccessible(true);
        registerMethod.invoke(commandManager, plugin, executor);
    }

    @Test
    @DisplayName("unregisterAll removes the primary label from Bukkit's known-commands map")
    void unregisterAllRemovesPrimaryLabelFromKnownCommands() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("CommandManagerKnownCommandsCleanupTest"));

            registerViaFramework(new SingleAliasCommandExecutor());

            // Precondition: the command really is live in Bukkit's command map.
            assertThat(realCommandMap.getKnownCommands()).containsKey("redcmd");
            assertThat(realCommandMap.getCommand("redcmd")).isNotNull();

            commandManager.unregisterAll(mockPlugin);

            // A second /redcmd from console must now resolve to nothing -- Unknown command.
            assertThat(realCommandMap.getCommand("redcmd"))
                    .as("command must no longer resolve after unregisterAll")
                    .isNull();
            assertThat(realCommandMap.getKnownCommands())
                    .as("bare label entry must be removed from the known-commands map, not merely marked unregistered")
                    .doesNotContainKey("redcmd");
        }
    }

    @Test
    @DisplayName("unregisterAll removes the namespaced (fallbackPrefix:label) key too")
    void unregisterAllRemovesNamespacedKey() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("CommandManagerKnownCommandsCleanupTest"));

            registerViaFramework(new SingleAliasCommandExecutor());

            String namespacedKey = FALLBACK_PREFIX + ":redcmd";
            assertThat(realCommandMap.getKnownCommands()).containsKey(namespacedKey);

            commandManager.unregisterAll(mockPlugin);

            assertThat(realCommandMap.getKnownCommands())
                    .as("namespaced key (prefix:label) must also be scrubbed -- a player typing " +
                            "'/ultitools:redcmd' after uninstall must also get Unknown command")
                    .doesNotContainKey(namespacedKey);
        }
    }

    @Test
    @DisplayName("unregisterAll removes every alias key, bare and namespaced")
    void unregisterAllRemovesAllAliasKeys() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("CommandManagerKnownCommandsCleanupTest"));

            registerViaFramework(new MultiAliasCommandExecutor());

            // Precondition: every alias, bare and namespaced, resolves.
            assertThat(realCommandMap.getKnownCommands())
                    .containsKeys("multicmd", "mcmd", "m",
                            FALLBACK_PREFIX + ":multicmd", FALLBACK_PREFIX + ":mcmd", FALLBACK_PREFIX + ":m");

            commandManager.unregisterAll(mockPlugin);

            assertThat(realCommandMap.getKnownCommands())
                    .as("no alias -- bare or namespaced -- may keep resolving to the unloaded module")
                    .doesNotContainKeys("multicmd", "mcmd", "m",
                            FALLBACK_PREFIX + ":multicmd", FALLBACK_PREFIX + ":mcmd", FALLBACK_PREFIX + ":m");
        }
    }

    @Test
    @DisplayName("over-deletion guard: a different owner's command under the same bare label survives")
    void unregisterAllDoesNotRemoveADifferentOwnersCommandUnderTheSameLabel() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("CommandManagerKnownCommandsCleanupTest"));

            registerViaFramework(new SharedLabelCommandExecutor());

            Command ownCommandInstance = realCommandMap.getCommand(FALLBACK_PREFIX + ":shared");
            assertThat(ownCommandInstance).isNotNull();

            // Simulate a second, unrelated plugin later winning the bare "shared" slot --
            // e.g. Bukkit's own conflict-resolution letting a later-registered overridable
            // command take the bare label while our namespaced key stays ours. This is the
            // exact scenario a naive "remove every entry whose KEY starts with our label"
            // fix would get wrong: it would also strip the other owner's live command.
            Command foreignCommand = mock(Command.class);
            when(foreignCommand.getName()).thenReturn("shared");
            realCommandMap.getKnownCommands().put("shared", foreignCommand);

            commandManager.unregisterAll(mockPlugin);

            assertThat(realCommandMap.getKnownCommands().get("shared"))
                    .as("a different owner's live command instance under the same bare label must survive")
                    .isSameAs(foreignCommand);
            assertThat(realCommandMap.getKnownCommands().get(FALLBACK_PREFIX + ":shared"))
                    .as("our own namespaced entry must still be removed")
                    .isNull();
        }
    }

    @Test
    @DisplayName("Codex #457 P2: unloading an earlier module must not remove a later module's "
            + "live command sharing the same primary label under the shared UltiTools prefix")
    void unregisterAllDoesNotRemoveALaterModulesCommandUnderTheSameSharedPrefixLabel() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("CommandManagerKnownCommandsCleanupTest"));

            UltiToolsPlugin earlierPlugin = mock(UltiToolsPlugin.class);
            when(earlierPlugin.getPluginName()).thenReturn("EarlierModule");
            when(earlierPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

            UltiToolsPlugin laterPlugin = mock(UltiToolsPlugin.class);
            when(laterPlugin.getPluginName()).thenReturn("LaterModule");
            when(laterPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

            // Two different modules, both registered through this same CommandManager, so
            // both use the identical shared UltiTools fallback prefix -- and both declare
            // the same primary alias. SimpleCommandMap#register's namespaced-key put is
            // unconditional (knownCommands.put(fallbackPrefix + ":" + label, command), no
            // conflict check), so the later registration silently overwrites the earlier
            // module's namespaced entry to point at its own command.
            registerViaFramework(earlierPlugin, new SharedLabelCommandExecutor());
            registerViaFramework(laterPlugin, new SharedLabelCommandExecutor());

            Command laterCommand = realCommandMap.getCommand(FALLBACK_PREFIX + ":shared");
            assertThat(laterCommand).isNotNull();

            commandManager.unregisterAll(earlierPlugin);

            assertThat(realCommandMap.getCommand(FALLBACK_PREFIX + ":shared"))
                    .as("unloading the EARLIER module must not resolve/remove the LATER module's "
                            + "live command just because they share a namespaced key")
                    .isSameAs(laterCommand);
            assertThat(realCommandMap.getCommand("shared"))
                    .as("the later module's command must still be dispatchable by its bare label too")
                    .isSameAs(laterCommand);
        }
    }

    @Test
    @DisplayName("unregisterAllExternal removes the external module's known-commands entries too")
    void unregisterAllExternalRemovesKnownCommandsEntries() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("CommandManagerKnownCommandsCleanupTest"));

            CmdExecutor annotation = SingleAliasCommandExecutor.class.getAnnotation(CmdExecutor.class);
            Method registerExternal = CommandManager.class.getDeclaredMethod(
                    "registerExternalCommand", String.class, CommandExecutor.class, CmdExecutor.class);
            registerExternal.setAccessible(true);
            registerExternal.invoke(commandManager, "ExternalPlugin", new SingleAliasCommandExecutor(), annotation);

            assertThat(realCommandMap.getKnownCommands()).containsKey("redcmd");

            commandManager.unregisterAllExternal("ExternalPlugin");

            assertThat(realCommandMap.getCommand("redcmd"))
                    .as("external module command must no longer resolve after unregisterAllExternal")
                    .isNull();
            assertThat(realCommandMap.getKnownCommands())
                    .as("external module's known-commands entries must be scrubbed too")
                    .doesNotContainKey("redcmd");
        }
    }
}
