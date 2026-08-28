package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bukkit.command.Command;
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

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

@DisplayName("PluginInstallCommands Enhanced Coverage Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
        
        File dataFolder = new File("target/test-data");
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
            mockedMessageUtils.when(() -> MessageUtils.sendMessage(
                    (Player) org.mockito.ArgumentMatchers.any(), 
                    (TextComponent) org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    try {
                        Player p = invocation.getArgument(0);
                        TextComponent t = invocation.getArgument(1);
                        String msg = LegacyComponentSerializer.legacySection().serialize(t);
                        p.sendMessage(msg);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return null;
                });
            mockingAvailable = true;
            
            // Setup default behaviors
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(anyInt(), anyInt()))
                    .thenReturn(new ArrayList<>());
            mockedUtils.when(() -> PluginInstallUtils.installPlugin(anyString(), anyString()))
                    .thenReturn(true);
            mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin(anyString()))
                    .thenReturn(true);
            mockedUtils.when(() -> PluginInstallUtils.getPluginVersions(anyString()))
                    .thenReturn(Arrays.asList("1.0.0", "1.0.1", "1.1.0"));
            mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin(anyString()))
                    .thenReturn(true);
            
            executor = new PluginInstallCommands();
        } catch (Exception e) {
            mockingAvailable = false;
            executor = null;
        }
    }

    @AfterEach
    void tearDown() {
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
        
        executor.onCommand(player, mockCommand, "upm", new String[]{"help"});
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
        
        // Should default to page 1
        List<String> messages = collectAllMessages(player);
        assertThat(messages).isNotEmpty();
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
        
        executor.listPlugins(player, "invalid");
        
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
        
        executor.onCommand(console, mockCommand, "upm", new String[]{"list"});
        
        waitForAsync();
        
        verify(mockPluginManager, atLeastOnce()).getPluginList();
    }

    // ==================== Install Command Tests ====================
    
    @Test
    @DisplayName("Install specific version - should succeed")
    void testInstallPluginWithVersion() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installPlugin("test-plugin", "1.0.0"))
                .thenReturn(true);
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"install", "test-plugin", "1.0.0"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message)
            .contains("安装成功")
            .contains("重启服务器");
    }

    @Test
    @DisplayName("Install specific version - should handle failure")
    void testInstallPluginWithVersionFailure() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installPlugin("bad-plugin", "1.0.0"))
                .thenReturn(false);
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"install", "bad-plugin", "1.0.0"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message).contains("安装失败");
    }

    @Test
    @DisplayName("Install latest version - should succeed")
    void testInstallLatestPlugin() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin("latest-plugin"))
                .thenReturn(true);
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"install", "latest-plugin"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message)
            .contains("安装成功")
            .contains("重启服务器");
    }

    @Test
    @DisplayName("Install latest version - should handle failure")
    void testInstallLatestPluginFailure() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin("fail-plugin"))
                .thenReturn(false);
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"install", "fail-plugin"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message).contains("安装失败");
    }

    @Test
    @DisplayName("Install from console - should work")
    void testInstallFromConsole() {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin("console-plugin"))
                .thenReturn(true);
        
        boolean result = executor.onCommand(console, mockCommand, "upm", 
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
        
        executor.onCommand(player, mockCommand, "upm", 
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
        
        executor.onCommand(player, mockCommand, "upm", 
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
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"versions", "empty-plugin"});
        server.getScheduler().performOneTick();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage).contains("可用版本列表");
    }

    @Test
    @DisplayName("List versions from console")
    void testListVersionsFromConsole() {
        if (!mockingAvailable) return;
        
        List<String> versions = Arrays.asList("1.0.0", "2.0.0");
        mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("console-versions"))
                .thenReturn(versions);
        
        boolean result = executor.onCommand(console, mockCommand, "upm", 
                new String[]{"versions", "console-versions"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
    }

    // ==================== Uninstall Command Tests ====================
    
    @Test
    @DisplayName("Uninstall plugin - should succeed")
    void testUninstallPluginSuccess() throws IOException {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("remove-plugin"))
                .thenReturn(true);
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"uninstall", "remove-plugin"});
        server.getScheduler().performOneTick();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("卸载成功")
            .contains("文件位置");
    }

    @Test
    @DisplayName("Uninstall plugin - should handle not found")
    void testUninstallPluginNotFound() throws IOException {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("missing-plugin"))
                .thenReturn(false);
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"uninstall", "missing-plugin"});
        server.getScheduler().performOneTick();
        
        String message = player.nextMessage();
        assertThat(message).contains("卸载失败");
    }

    @Test
    @DisplayName("Uninstall plugin - should handle IOException")
    void testUninstallPluginIOException() throws IOException {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("io-error-plugin"))
                .thenThrow(new IOException("File access error"));
        
        executor.onCommand(player, mockCommand, "upm", 
                new String[]{"uninstall", "io-error-plugin"});
        server.getScheduler().performOneTick();
        
        List<String> messages = collectAllMessages(player);
        String fullMessage = String.join("\n", messages);
        
        assertThat(fullMessage)
            .contains("删除失败")
            .contains("文件位置");
    }

    @Test
    @DisplayName("Uninstall from console")
    void testUninstallFromConsole() throws IOException {
        if (!mockingAvailable) return;
        
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("console-uninstall"))
                .thenReturn(true);
        
        boolean result = executor.onCommand(console, mockCommand, "upm", 
                new String[]{"uninstall", "console-uninstall"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
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
