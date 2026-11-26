package com.ultikits.ultitools.commands;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskContext;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.utils.UpmKeys;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.MessageUtils;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.VersionLookup;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;

@DisplayName("PluginInstallCommands Enhanced Coverage Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(CommandTestExtension.class)
class PluginInstallCommandsEnhancedTest {

    private ServerMock server;
    private PlayerMock player;
    private ConsoleCommandSender console;
    private Command mockCommand;
    private PluginInstallCommands executor;
    private PluginManager mockPluginManager;
    private MockedStatic<PluginInstallUtils> mockedUtils;
    private MockedStatic<MessageUtils> mockedMessageUtils;
    private MockedStatic<UltiTools> mockedUltiTools;
    private MockedStatic<org.bukkit.Bukkit> mockedBukkit;
    private boolean mockingAvailable = false;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        UltiTools instance = UltiTools.getInstance();
        
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(instance);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("api-url", "http://localhost");
        mockedUltiTools.when(UltiTools::getEnv).thenReturn(config);
        
        // A server layout: the update records live under the data folder's grandparent (the server root).
        File dataFolder = new File("target/test-data/plugins/UltiTools");
        dataFolder.mkdirs();
        when(instance.getDataFolder()).thenReturn(dataFolder);
        when(instance.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("UltiTools");
        org.bukkit.plugin.PluginDescriptionFile pdf = new org.bukkit.plugin.PluginDescriptionFile("UltiTools", "1.0.0", "com.ultikits.ultitools.UltiTools");
        when(instance.getDescription()).thenReturn(pdf);
        when(instance.getLogger()).thenReturn(java.util.logging.Logger.getLogger("UltiTools"));
        
        registerPluginToMockBukkit(instance);
        
        player = server.addPlayer("testplayer");
        player.setOp(true);
        console = server.getConsoleSender();
        
        mockCommand = mock(Command.class);
        when(mockCommand.getName()).thenReturn("upm");
        
        mockPluginManager = mock(PluginManager.class);
        when(UltiTools.getInstance().getPluginManager()).thenReturn(mockPluginManager);
        
        try {
            mockedUtils = mockStatic(PluginInstallUtils.class);
            mockedMessageUtils = mockStatic(MessageUtils.class);
            mockedMessageUtils.when(() -> MessageUtils.sendMessage(any(Player.class), any(String.class)))
                .thenAnswer(invocation -> {
                    Player p = invocation.getArgument(0);
                    String msg = invocation.getArgument(1);
                    p.sendMessage(msg);
                    return null;
                });
            mockedMessageUtils.when(() -> MessageUtils.sendMessage(any(Player.class), any(TextComponent.class)))
                .thenAnswer(invocation -> {
                    Player p = invocation.getArgument(0);
                    TextComponent t = invocation.getArgument(1);
                    p.sendMessage(t);
                    return null;
                });
            mockingAvailable = true;
            
            // Setup default behaviors
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(anyInt(), anyInt()))
                    .thenReturn(new ArrayList<>());
            mockedUtils.when(() -> PluginInstallUtils.installReporting(anyString(), any(), any(CommandSender.class)))
                    .thenReturn(PendingCommandTestSupport.install("test-plugin", "1.0.0"));
            mockedUtils.when(() -> PluginInstallUtils.getPluginVersions(anyString()))
                    .thenReturn(Arrays.asList("1.0.0", "1.0.1", "1.1.0"));
            mockedUtils.when(() -> PluginInstallUtils.lookUpPluginVersions(anyString(), anyList()))
                    .thenAnswer(invocation -> {
                        List<String> versions = PluginInstallUtils.getPluginVersions(invocation.getArgument(0));
                        if (versions == null) {
                            return VersionLookup.failed("test failure");
                        }
                        invocation.<List<String>>getArgument(1).addAll(versions);
                        return VersionLookup.found(invocation.getArgument(0));
                    });
            mockedUtils.when(() -> PluginInstallUtils.requestUninstall(anyString(), any(CommandSender.class)))
                    .thenReturn(PendingCommandTestSupport.remove("test-plugin", "module.jar"));
            mockedUtils.when(() -> PluginInstallUtils.updateResult(anyString(), any())).thenCallRealMethod();
            var stage = PendingCommandTestSupport.staged();
            mockedUtils.when(() -> PluginInstallUtils.stageUpdate(anyString(), any(CommandSender.class)))
                    .thenReturn(stage);
            
            executor = new PluginInstallCommands();
        } catch (Exception e) {
            throw new AssertionError("Could not initialize command fixture", e);
        }
        // Output-only tests keep thread-local static stubs on this thread. Real worker execution
        // and main-thread delivery are covered by CatalogueClientPagingTest.
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class,
                org.mockito.AdditionalAnswers.delegatesTo(server.getScheduler()));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(org.bukkit.scheduler.BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class));
        mockedBukkit = mockStatic(org.bukkit.Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        mockedBukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
        TaskContext async = CommandRuntime.scheduler().async();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(async).run(any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
        if (mockedUtils != null) {
            try {
                mockedUtils.close();
            } catch (Exception ignored) {
            }
        }
        if (mockedMessageUtils != null) {
            try {
                mockedMessageUtils.close();
            } catch (Exception ignored) {
            }
        }
        if (mockedUltiTools != null) {
            try {
                mockedUltiTools.close();
            } catch (Exception ignored) {
            }
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // ==================== Help Command Tests ====================
    
    @Test
    @DisplayName("Should display all help commands")
    void testHelpDisplaysAllCommands() {
        if (!mockingAvailable) return;
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm", new String[]{"help"});
        server.getScheduler().performOneTick();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("插件安装帮助")
            .contains("list")
            .contains("install")
            .contains("versions")
            .contains("uninstall");
    }

    // ==================== List Command Tests (Player) ====================
    
    @Test
    @DisplayName("Player list - should show installed plugin with uninstall option")
    void testPlayerListShowsInstalledPlugin() {
        if (!mockingAvailable) return;
        
        // Setup installed plugin
        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getPluginName()).thenReturn("InstalledPlugin");
        when(mockPluginManager.getPluginList()).thenReturn(Collections.singletonList(mockPlugin));
        
        // Setup available plugin
        PluginEntity entity = createPlugin("InstalledPlugin", "installed-id", "Installed");
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.singletonList(entity));
        
        executor.listPlugins(player, "1");
        
        waitForAsync();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("可用插件列表")
            .contains("InstalledPlugin")
            .contains("已安装")
            .contains("卸载");
    }

    @Test
    @DisplayName("Player list - should show uninstalled plugin with install option")
    void testPlayerListShowsUninstalledPlugin() {
        if (!mockingAvailable) return;
        
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        
        PluginEntity entity = createPlugin("NewPlugin", "new-id", "New plugin");
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.singletonList(entity));
        
        // Call directly to avoid async execution issues with mockStatic
        executor.listPlugins(player, "1");
        
        waitForAsync();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("NewPlugin")
            .contains("未安装")
            .contains("安装");
    }

    @Test
    @DisplayName("Player list - should show multiple plugins with different statuses")
    void testPlayerListMultiplePlugins() {
        if (!mockingAvailable) return;
        
        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getPluginName()).thenReturn("Plugin1");
        when(mockPluginManager.getPluginList()).thenReturn(Collections.singletonList(mockPlugin));
        
        List<PluginEntity> entities = Arrays.asList(
            createPlugin("Plugin1", "p1", "First plugin"),
            createPlugin("Plugin2", "p2", "Second plugin"),
            createPlugin("Plugin3", "p3", "Third plugin")
        );
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(entities);
        
        executor.listPlugins(player, "1");
        
        waitForAsync();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("Plugin1").contains("已安装")
            .contains("Plugin2").contains("未安装")
            .contains("Plugin3").contains("未安装")
            .contains("第1页");
    }

    @Test
    @DisplayName("Player list - install and uninstall buttons keep their run-command click events")
    void testPlayerListButtonsKeepClickEvents() {
        assertThat(mockingAvailable).as("the component-delivery fixture must be available").isTrue();

        UltiToolsPlugin installed = mock(UltiToolsPlugin.class);
        when(installed.getPluginName()).thenReturn("UltiTools-Economy");
        when(installed.getIdentifyString()).thenReturn("com.ultikits.economy");
        when(mockPluginManager.getPluginList()).thenReturn(Collections.singletonList(installed));
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10)).thenReturn(Arrays.asList(
                createPlugin("UltiEconomy", "com.ultikits.economy", "Installed module"),
                createPlugin("UltiMail", "com.ultikits.mail", "Available module")));

        executor.listPlugins(player, "1");
        server.getScheduler().performTicks(2);

        Component received = player.nextComponentMessage();
        assertThat(received).as("the player must receive the list component").isNotNull();
        List<String> commands = new ArrayList<>();
        collectRunCommands(received, commands);
        assertThat(commands)
                .as("uninstall uses the loaded runtime name; install uses the catalogue identifier")
                .containsExactly("/upm uninstall UltiTools-Economy", "/upm install com.ultikits.mail");
        assertThat(player.nextComponentMessage()).as("one list component is one message").isNull();
    }

    @Test
    @DisplayName("Player list - should handle default page (page 1)")
    void testPlayerListDefaultPage() {
        if (!mockingAvailable) return;
        
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        
        PluginEntity entity = createPlugin("TestPlugin", "test-id", "Test");
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.singletonList(entity));
        
        executor.listPlugins(player);
        
        waitForAsync();
        
        List<String> messages = collectAllMessages(player);
        assertThat(messages).isNotEmpty();
        
        String fullMessage = String.join("\n", messages);
        assertThat(fullMessage)
            .contains("可用插件列表")
            .contains("第1页");
    }

    @Test
    @DisplayName("Player list - should handle different page numbers")
    void testPlayerListDifferentPages() {
        if (!mockingAvailable) return;
        
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        
        PluginEntity entity = createPlugin("PagePlugin", "page-id", "Page test");
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(3, 10))
                .thenReturn(Collections.singletonList(entity));
        
        executor.listPlugins(player, "3");
        
        waitForAsync();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage).contains("第3页");
    }

    @Test
    @DisplayName("Player list - should handle invalid page number gracefully")
    void testPlayerListInvalidPageNumber() {
        if (!mockingAvailable) return;

        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());

        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.emptyList());

        executor.listPlugins(player, "invalid");

        waitForAsync();

        // #380: this used to assert only that *some* message came back, which the removed
        // behaviour satisfied by silently serving page 1. The contract now is that a non-integer
        // page is reported rather than guessed at, so the assertion names the message.
        List<String> messages = collectAllMessages(player);
        assertThat(messages)
                .as("a non-integer page must be reported, not silently treated as page 1")
                .anyMatch(msg -> msg.contains("Failed to parse 'invalid' as Integer"));
    }

    @Test
    @DisplayName("Player list - should handle empty plugin list")
    void testPlayerListEmpty() {
        if (!mockingAvailable) return;
        
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.emptyList());
        
        executor.listPlugins(player, "1");
        
        waitForAsync();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage).contains("可用插件列表");
    }

    // ==================== List Command Tests (Console) ====================
    
    @Test
    @DisplayName("Console list - should use plain text format")
    void testConsoleListPlainText() {
        if (!mockingAvailable) return;
        
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        
        PluginEntity entity = createPlugin("ConsolePlugin", "console-id", "Console test");
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.singletonList(entity));
        
        // Uses a valid page deliberately. This test is about the console's plain-text rendering,
        // and it previously passed "invalid" only because that was silently treated as page 1
        // (#380). Now a bad page returns before any listing happens, which would make this test
        // pass for the wrong reason.
        executor.listPlugins(player, "1");
        
        waitForAsync();
        
        // Console sender receives messages differently
        verify(mockPluginManager, atLeastOnce()).getPluginList();
    }

    @Test
    @DisplayName("Console list - should show install commands")
    void testConsoleListShowsInstallCommands() {
        if (!mockingAvailable) return;
        
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        
        PluginEntity entity = createPlugin("CmdPlugin", "cmd-id", "Command test");
        mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Collections.singletonList(entity));
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, console, mockCommand, "upm", new String[]{"list"});
        
        waitForAsync();
        
        verify(mockPluginManager, atLeastOnce()).getPluginList();
    }

    // ==================== Install Command Tests ====================
    
    @Test
    @DisplayName("Install specific version - should succeed")
    void testInstallPluginWithVersion() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installReporting(eq("test-plugin"), eq("1.0.0"), any(CommandSender.class)))
                .thenReturn(PendingCommandTestSupport.install("test-plugin", "1.0.0"));
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"install", "test-plugin", "1.0.0"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message)
            .containsAnyOf("待生效清单", "pending changes")
            .containsAnyOf("重启", "restart");
    }

    @Test
    @DisplayName("Install specific version - should handle failure")
    void testInstallPluginWithVersionFailure() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installReporting(eq("bad-plugin"), eq("1.0.0"), any(CommandSender.class)))
                .thenReturn(PendingCommandTestSupport.reply(false, UpmKeys.INSTALL_FAILED));
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"install", "bad-plugin", "1.0.0"});
        server.getScheduler().performOneTick();
        
        assertThat(player.nextMessage()).isEqualTo(ChatColor.RED + "Install Failed!");
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("Install latest version - should succeed")
    void testInstallLatestPlugin() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installReporting(eq("latest-plugin"), isNull(), any(CommandSender.class)))
                .thenReturn(PendingCommandTestSupport.install("test-plugin", "1.0.0"));
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"install", "latest-plugin"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message)
            .containsAnyOf("待生效清单", "pending changes")
            .containsAnyOf("重启", "restart");
    }

    @Test
    @DisplayName("Install latest version - should handle failure")
    void testInstallLatestPluginFailure() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installReporting(eq("fail-plugin"), isNull(), any(CommandSender.class)))
                .thenReturn(PendingCommandTestSupport.reply(false, UpmKeys.INSTALL_FAILED));
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"install", "fail-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(player.nextMessage()).isEqualTo(ChatColor.RED + "Install Failed!");
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("Install from console - should work")
    void testInstallFromConsole() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installReporting(eq("console-plugin"), isNull(), any(CommandSender.class)))
                .thenReturn(PendingCommandTestSupport.install("test-plugin", "1.0.0"));
        
        boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, console, mockCommand, "upm",
                new String[]{"install", "console-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
    }

    // ==================== Versions Command Tests ====================
    
    @Test
    @DisplayName("List versions - should display all versions")
    void testListVersions() {
        if (!mockingAvailable) return;
        
        List<String> versions = Arrays.asList("1.0.0", "1.0.1", "1.1.0", "2.0.0");
        mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("versioned-plugin"))
                .thenReturn(versions);
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"versions", "versioned-plugin"});
        server.getScheduler().performOneTick();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("可用版本列表")
            .contains("1.0.0")
            .contains("1.0.1")
            .contains("1.1.0")
            .contains("2.0.0")
            .contains("安装命令");
    }

    @Test
    @DisplayName("List versions - should handle null response")
    void testListVersionsNull() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("null-plugin"))
                .thenReturn(null);
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"versions", "null-plugin"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message).contains("获取版本列表失败");
    }

    @Test
    @DisplayName("List versions - should handle empty list")
    void testListVersionsEmpty() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("empty-plugin"))
                .thenReturn(Collections.emptyList());
        
        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, player, mockCommand, "upm",
                new String[]{"versions", "empty-plugin"});
        server.getScheduler().performOneTick();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        // 18-40: the catalogue lists only versions this framework installs, so an empty list is
        // reported as "no version can be installed on this server", not as an empty table.
        assertThat(fullMessage).contains("没有可以安装在本服务器上的版本").doesNotContain("可用版本列表");
    }

    @Test
    @DisplayName("List versions from console")
    void testListVersionsFromConsole() {
        if (!mockingAvailable) return;
        
        List<String> versions = Arrays.asList("1.0.0", "2.0.0");
        mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("console-versions"))
                .thenReturn(versions);
        
        boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, console, mockCommand, "upm",
                new String[]{"versions", "console-versions"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
    }

    // ==================== Uninstall Command Tests ====================
    
    @Test
    @DisplayName("Uninstall plugin - should succeed")
    void testUninstallPluginSuccess() throws IOException {
        var sender = player;
        mockedUtils.when(() -> PluginInstallUtils.requestUninstall("remove-plugin", sender)).thenReturn(PendingCommandTestSupport.remove("remove-plugin", "module.jar"));
        executor.uninstallPlugin(sender, "remove-plugin");
        assertThat(player.nextMessage()).contains("module.jar");
    }

    @Test
    @DisplayName("Uninstall plugin - should handle not found")
    void testUninstallPluginNotFound() throws IOException {
        var sender = player;
        mockedUtils.when(() -> PluginInstallUtils.requestUninstall("missing-plugin", sender)).thenReturn(PendingCommandTestSupport.reply(false, UpmKeys.UNINSTALL_NOT_FOUND));
        executor.uninstallPlugin(sender, "missing-plugin");
        assertThat(player.nextMessage()).isEqualTo(ChatColor.RED + "Uninstall Failed! Please check if the spelling is correct!");
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("Uninstall plugin - should handle IOException")
    void testUninstallPluginIOException() throws IOException {
        var sender = player;
        mockedUtils.when(() -> PluginInstallUtils.requestUninstall("io-error-plugin", sender)).thenReturn(PendingCommandTestSupport.refusal(UpmKeys.UNINSTALL_NOT_RECORDED, "io-error-plugin", "error", "read-only journal"));
        executor.uninstallPlugin(sender, "io-error-plugin");
        assertThat(player.nextMessage()).contains("read-only journal");
    }

    @Test
    @DisplayName("Uninstall from console")
    void testUninstallFromConsole() throws IOException {
        var sender = server.getConsoleSender();
        mockedUtils.when(() -> PluginInstallUtils.requestUninstall("console-uninstall", sender)).thenReturn(PendingCommandTestSupport.remove("console-uninstall", "module.jar"));
        executor.uninstallPlugin(sender, "console-uninstall");
        assertThat(sender.nextMessage()).contains("module.jar");
    }

    // ==================== Helper Methods ====================
    
    private PluginEntity createPlugin(String name, String id, String description) {
        PluginEntity entity = new PluginEntity();
        entity.setName(name);
        entity.setIdentifyString(id);
        entity.setShortDescription(description);
        return entity;
    }

    private List<String> collectAllMessages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    private static void collectRunCommands(Component component, List<String> commands) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
            commands.add(((ClickEvent.Payload.Text) click.payload()).value());
        }
        for (Component child : component.children()) {
            collectRunCommands(child, commands);
        }
    }

    private void waitForAsync() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
    }
    
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void registerPluginToMockBukkit(org.bukkit.plugin.Plugin plugin) {
        try {
            org.bukkit.plugin.PluginManager pluginManager = server.getPluginManager();
            java.lang.reflect.Field pluginsField = null;
            try {
                pluginsField = pluginManager.getClass().getDeclaredField("plugins");
            } catch (NoSuchFieldException e) {
                if (pluginManager.getClass().getSuperclass() != null) {
                    try {
                        pluginsField = pluginManager.getClass().getSuperclass().getDeclaredField("plugins");
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            
            if (pluginsField != null) {
                pluginsField.setAccessible(true);
                List<org.bukkit.plugin.Plugin> plugins = (List<org.bukkit.plugin.Plugin>) pluginsField.get(pluginManager);
                if (!plugins.contains(plugin)) {
                    plugins.add(plugin);
                }
            }
            
            java.lang.reflect.Field lookupNamesField = null;
            try {
                lookupNamesField = pluginManager.getClass().getDeclaredField("lookupNames");
            } catch (NoSuchFieldException e) {
                if (pluginManager.getClass().getSuperclass() != null) {
                    try {
                        lookupNamesField = pluginManager.getClass().getSuperclass().getDeclaredField("lookupNames");
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            
            if (lookupNamesField != null) {
                lookupNamesField.setAccessible(true);
                java.util.Map<String, org.bukkit.plugin.Plugin> lookupNames = (java.util.Map<String, org.bukkit.plugin.Plugin>) lookupNamesField.get(pluginManager);
                lookupNames.put(plugin.getName(), plugin);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
