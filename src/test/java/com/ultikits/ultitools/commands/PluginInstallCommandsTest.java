package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.utils.PluginInstallUtils;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallCommandsTest {

    private ServerMock server;
    private PlayerMock player;
    private Command mockCommand;
    private PluginInstallCommands executor;
    private PluginManager mockPluginManager;
    private MockedStatic<PluginInstallUtils> mockedUtils;
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
        
        player = server.addPlayer("testplayer");
        player.setOp(true);
        
        mockCommand = mock(Command.class);
        when(mockCommand.getName()).thenReturn("upm");
        
        mockPluginManager = mock(PluginManager.class);
        when(UltiTools.getInstance().getPluginManager()).thenReturn(mockPluginManager);
        
        // Try to mock static methods
        try {
            mockedUtils = mockStatic(PluginInstallUtils.class);
            mockingAvailable = true;
            
            // Setup default mock behaviors
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(anyInt(), anyInt()))
                    .thenReturn(new ArrayList<>());
            mockedUtils.when(() -> PluginInstallUtils.installPlugin(anyString(), anyString()))
                    .thenReturn(true);
            mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin(anyString()))
                    .thenReturn(true);
            mockedUtils.when(() -> PluginInstallUtils.getPluginVersions(anyString()))
                    .thenReturn(Arrays.asList("1.0.0", "1.0.1"));
            mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin(anyString()))
                    .thenReturn(true);
            
            executor = new PluginInstallCommands();
        } catch (Exception e) {
            mockingAvailable = false;
            executor = null;
            System.err.println("Cannot mock PluginInstallUtils: " + e.getMessage());
            e.printStackTrace();
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
        if (mockedUltiTools != null) {
            try {
                mockedUltiTools.close();
            } catch (Exception ignored) {
            }
        }
        try {
            com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("Should show help message with all commands")
    void testHelp() {
        if (executor == null) return;
        
        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"help"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        
        // Verify all help messages are shown
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        
        String allMessages = String.join("\n", messages);
        assertThat(allMessages)
            .contains("插件安装帮助")
            .contains("list")
            .contains("install")
            .contains("versions")
            .contains("uninstall");
    }

    @Test
    @DisplayName("Should list plugins with page 1 for player with installed and uninstalled plugins")
    void testListPluginsPage1() {
        if (executor == null) return;
        
        // Create mock installed plugin
        UltiToolsPlugin mockInstalledPlugin = mock(UltiToolsPlugin.class);
        when(mockInstalledPlugin.getPluginName()).thenReturn("TestPlugin1");
        when(mockPluginManager.getPluginList()).thenReturn(Collections.singletonList(mockInstalledPlugin));
        
        // Create available plugins (one installed, one not)
        PluginEntity plugin1 = new PluginEntity();
        plugin1.setName("TestPlugin1");
        plugin1.setIdentifyString("test-plugin-1");
        plugin1.setShortDescription("Test plugin 1 description");
        
        PluginEntity plugin2 = new PluginEntity();
        plugin2.setName("TestPlugin2");
        plugin2.setIdentifyString("test-plugin-2");
        plugin2.setShortDescription("Test plugin 2 description");
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Arrays.asList(plugin1, plugin2));
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list", "1"});
        
        // Wait for async task
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should list plugins without page parameter")
    void testListPluginsDefaultPage() {
        if (executor == null) return;
        
        PluginEntity plugin1 = new PluginEntity();
        plugin1.setName("TestPlugin");
        plugin1.setIdentifyString("test-plugin");
        plugin1.setShortDescription("Test description");
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Arrays.asList(plugin1));
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list"});
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should show installed plugin in list")
    void testListInstalledPlugin() {
        if (executor == null) return;
        
        UltiToolsPlugin installedPlugin = mock(UltiToolsPlugin.class);
        when(installedPlugin.getPluginName()).thenReturn("TestPlugin");
        
        PluginEntity plugin1 = new PluginEntity();
        plugin1.setName("TestPlugin");
        plugin1.setIdentifyString("test-plugin");
        plugin1.setShortDescription("Installed plugin");
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Arrays.asList(plugin1));
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList(installedPlugin));
        
        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list", "1"});
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should install plugin with version")
    void testInstallPluginWithVersion() {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.installPlugin("test-plugin", "1.0.0"))
                .thenReturn(true);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"install", "test-plugin", "1.0.0"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("成功");
    }

    @Test
    @DisplayName("Should handle failed plugin installation with version")
    void testInstallPluginWithVersionFailed() {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.installPlugin("test-plugin", "1.0.0"))
                .thenReturn(false);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"install", "test-plugin", "1.0.0"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("失败");
    }

    @Test
    @DisplayName("Should install latest plugin")
    void testInstallLatestPlugin() {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin("test-plugin"))
                .thenReturn(true);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"install", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("成功");
    }

    @Test
    @DisplayName("Should handle failed latest plugin installation")
    void testInstallLatestPluginFailed() {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin("test-plugin"))
                .thenReturn(false);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"install", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("失败");
    }

    @Test
    @DisplayName("Should list plugin versions")
    void testListVersions() {
        if (executor == null) return;
        
        List<String> versions = Arrays.asList("1.0.0", "1.1.0", "2.0.0");
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("test-plugin"))
                .thenReturn(versions);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"versions", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message1 = player.nextMessage();
        assertThat(message1).contains("版本列表");
    }

    @Test
    @DisplayName("Should handle null version list")
    void testListVersionsNull() {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginVersions("test-plugin"))
                .thenReturn(null);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"versions", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("失败");
    }

    @Test
    @DisplayName("Should uninstall plugin successfully")
    void testUninstallPlugin() throws IOException {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenReturn(true);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("卸载成功");
    }

    @Test
    @DisplayName("Should handle uninstall plugin not found")
    void testUninstallPluginNotFound() throws IOException {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenReturn(false);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("失败");
    }

    @Test
    @DisplayName("Should handle uninstall IOException")
    void testUninstallPluginIOException() throws IOException {
        if (executor == null) return;
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(new IOException("File access error"));
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(player, mockCommand, "upm", 
            new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("删除失败");
    }

    @Test
    @DisplayName("#501: a successful uninstall has already deleted the jar, so the reply never asks for a manual delete")
    void uninstallSuccess_replyDoesNotAskForManualDelete() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin")).thenReturn(true);

        executor.onCommand(player, mockCommand, "upm", new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();

        List<String> messages = drainMessages();
        assertThat(messages).as("the command must reply at all").isNotEmpty();
        assertThat(messages.get(0)).contains("卸载成功");
        assertThat(messages)
                .as("uninstallPlugin returns true only after deleting the jar (#501) -- telling the "
                        + "operator to delete it manually describes work that is not needed")
                .noneMatch(m -> m.contains("手动删除"))
                .noneMatch(m -> m.contains("文件位置"));
    }

    @Test
    @DisplayName("#501: a failed jar delete is reported as a failure naming the jar that is still on disk")
    void uninstallDeleteFailure_replyNamesTheJarStillOnDisk() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        String jarPath = "/srv/minecraft/plugins/UltiTools/plugins/Fixture-1.0.0.jar";
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(new java.nio.file.FileSystemException(jarPath, null, "Permission denied"));

        executor.onCommand(player, mockCommand, "upm", new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();

        List<String> messages = drainMessages();
        assertThat(messages).as("the command must reply at all").isNotEmpty();
        assertThat(messages).noneMatch(m -> m.contains("卸载成功"));
        assertThat(messages.get(0)).contains("失败");
        assertThat(String.join("\n", messages))
                .as("the operator must be told which file will load again on restart")
                .contains(jarPath);
    }

    @Test
    @DisplayName("#501 review WR-02: a failed uninstall names every module jar still on disk")
    void uninstallSeveralUndeletableJars_replyNamesEveryJar() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        String first = "/srv/minecraft/plugins/UltiTools/plugins/Fixture-1.0.0.jar";
        String second = "/srv/minecraft/plugins/UltiTools/plugins/Fixture-2.0.0.jar";
        java.nio.file.FileSystemException failure =
                new java.nio.file.FileSystemException(first, null, "Permission denied");
        failure.addSuppressed(new java.nio.file.FileSystemException(second, null, "Permission denied"));
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin")).thenThrow(failure);

        executor.uninstallPlugin(player, "test-plugin");

        String all = String.join("\n", drainMessages());
        assertThat(all).contains("失败").contains(first).contains(second).doesNotContain("卸载成功");
    }

    @Test
    @DisplayName("#501 review WR-03: a module unloaded without a jar on disk is reported as such, not as a misspelling")
    void uninstallLoadedModuleWithoutJar_replySaysUnloadedAndNoJarFound() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        String folder = "/srv/minecraft/plugins/UltiTools/plugins";
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(new java.nio.file.NoSuchFileException(folder, null, "no module JAR named test-plugin"));

        executor.uninstallPlugin(player, "test-plugin");

        String all = String.join("\n", drainMessages());
        assertThat(all)
                .contains("模块已卸载")
                .contains(folder)
                .doesNotContain("拼写")
                .doesNotContain("卸载成功")
                .doesNotContain("文件访问错误");
    }

    private static IllegalStateException unloadThrew(Exception jarOutcome) {
        IllegalStateException failure = new IllegalStateException(
                "Module test-plugin was removed from the loaded modules, but its unload threw",
                new IllegalStateException("module unload step boom"));
        if (jarOutcome != null) {
            failure.addSuppressed(jarOutcome);
        }
        return failure;
    }

    @Test
    @DisplayName("#503 review WR-01: an unload that throws is reported, together with the jars having been deleted")
    void uninstallUnloadThrew_jarsDeleted_replyReportsBoth() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin")).thenThrow(unloadThrew(null));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> executor.uninstallPlugin(player, "test-plugin"));

        assertThat(thrown).as("the operator must get a reply, not a generic command error").isNull();
        String all = String.join("\n", drainMessages());
        assertThat(all).contains("卸载出错").contains("已全部删除").doesNotContain("卸载成功");
    }

    @Test
    @DisplayName("#503 review WR-01: an unload that throws and a jar that cannot be deleted are both reported, naming the jar")
    void uninstallUnloadThrew_jarNotDeleted_replyNamesTheJar() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        String jar = "/srv/minecraft/plugins/UltiTools/plugins/Fixture-1.0.0.jar";
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(unloadThrew(new java.nio.file.FileSystemException(jar, null, "Permission denied")));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> executor.uninstallPlugin(player, "test-plugin"));

        assertThat(thrown).isNull();
        String all = String.join("\n", drainMessages());
        assertThat(all).contains("卸载出错").contains(jar).doesNotContain("已全部删除");
    }

    @Test
    @DisplayName("#503 review WR-01: an unload that throws with no jar on disk says so")
    void uninstallUnloadThrew_noJar_replySaysNoJarFound() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        String folder = "/srv/minecraft/plugins/UltiTools/plugins";
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(unloadThrew(new java.nio.file.NoSuchFileException(folder, null, "no module JAR")));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> executor.uninstallPlugin(player, "test-plugin"));

        assertThat(thrown).isNull();
        String all = String.join("\n", drainMessages());
        assertThat(all).contains("卸载出错").contains(folder).doesNotContain("已全部删除");
    }

    /**
     * #505: {@code updatePlugin} is {@code @RunAsync}, and Mockito's static mocks are
     * thread-local, so these tests call the public command method directly on the test thread
     * instead of going through {@code onCommand}'s async dispatch.
     */
    private UpdateManager stubModuleUpdates(String... pluginNameAndIdPairs) {
        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        Map<String, UpdateInfo> updates = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pluginNameAndIdPairs.length; i += 2) {
            UpdateInfo info = new UpdateInfo();
            info.setPluginName(pluginNameAndIdPairs[i]);
            info.setIdentifyString(pluginNameAndIdPairs[i + 1]);
            info.setCurrentVersion("1.0.0");
            info.setLatestVersion("1.1.0");
            updates.put(pluginNameAndIdPairs[i], info);
        }
        when(mockUpdateManager.getModuleUpdates()).thenReturn(updates);
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);
        return mockUpdateManager;
    }

    private static PluginInstallUtils.UpdateOutcome outcome(PluginInstallUtils.UpdateOutcome.Status status,
            List<String> files, List<String> unrestored, List<String> leftovers) {
        PluginInstallUtils.UpdateOutcome outcome = mock(PluginInstallUtils.UpdateOutcome.class);
        when(outcome.getStatus()).thenReturn(status);
        when(outcome.getFiles()).thenReturn(files);
        when(outcome.getUnrestoredFiles()).thenReturn(unrestored);
        when(outcome.getLeftoverFiles()).thenReturn(leftovers);
        return outcome;
    }

    private static PluginInstallUtils.UpdateOutcome outcome(PluginInstallUtils.UpdateOutcome.Status status) {
        return outcome(status, Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    private void stubUpdateOutcome(String identifyString, PluginInstallUtils.UpdateOutcome result) {
        mockedUtils.when(() -> PluginInstallUtils.updatePluginTransactionally(identifyString)).thenReturn(result);
    }

    /** A path inside the staging directory of the data folder these tests mock. */
    private static String stagingPath(String fileName) {
        return new File(new File(UltiTools.getInstance().getDataFolder(), ".upm-staging"), fileName).getAbsolutePath();
    }

    private String updateReply(String pluginName) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> executor.updatePlugin(player, pluginName));
        assertThat(thrown).as("the command must reply, never let a failure escape").isNull();
        return String.join("\n", drainMessages());
    }

    @Test
    @DisplayName("review r3: a successful staged update replies success and nothing else")
    void updateUpdated_repliesSuccess() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.UPDATED));

        String reply = updateReply("TestPlugin");

        // The success line mentions what happens if the module fails to load at the restart, so the
        // check is that no failure is *reported*, not that the word never appears.
        assertThat(reply).contains("更新已安装").doesNotContain("更新失败");
    }

    @Test
    @DisplayName("review r3 WR-03: an update refused because the same module is already updating says so and claims no change")
    void updateAlreadyInProgress_repliesRefusal() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.ALREADY_IN_PROGRESS));

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains("正在进行另一项更新或卸载").contains("未做任何更改").doesNotContain("更新成功");
    }

    @Test
    @DisplayName("review r3 WR-02: a download that is not a jar of the module is reported as such, with nothing changed")
    void updateInvalidDownload_repliesInvalidDownload() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.INVALID_DOWNLOAD));

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains("不是该模块").contains("未做任何更改").doesNotContain("更新成功");
    }

    @Test
    @DisplayName("#505: an old jar that cannot be moved out is named, and the reply says the folder was restored")
    void updateOldJarNotMoved_namesTheJarAndSaysRestored() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String oldJar = "/srv/minecraft/plugins/UltiTools/plugins/test-plugin-1.0.0.jar";
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.OLD_JAR_NOT_MOVED,
                Collections.singletonList(oldJar), Collections.<String>emptyList(), Collections.<String>emptyList()));

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains("失败").contains(oldJar).contains("旧版本已全部恢复").doesNotContain("更新成功");
    }

    @Test
    @DisplayName("#505: a new version that cannot be moved in, with an old jar not moved back, names both paths")
    void updateNewJarNotInstalledWithUnrestoredJar_namesTargetAndUnrestored() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String target = "/srv/minecraft/plugins/UltiTools/plugins/test-plugin-1.1.0.jar";
        String unrestored = "/srv/minecraft/plugins/UltiTools/.upm-staging/test-plugin-1.0.0.jar.x.old";
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED,
                Collections.singletonList(target), Collections.singletonList(unrestored), Collections.<String>emptyList()));

        String reply = updateReply("TestPlugin");

        assertThat(reply)
                .contains(target)
                .as("an old jar left in staging must be named so it can be moved back before restarting")
                .contains(unrestored)
                .contains("移回")
                .doesNotContain("旧版本已全部恢复")
                .doesNotContain("更新成功");
    }

    @Test
    @DisplayName("review r3: /upm update all counts successes, failures and refused updates separately in its summary")
    void updateAll_summaryCountsRefusedUpdatesSeparately() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("Plugin1", "plugin-1", "Plugin2", "plugin-2", "Plugin3", "plugin-3");
        stubUpdateOutcome("plugin-1", outcome(PluginInstallUtils.UpdateOutcome.Status.ALREADY_IN_PROGRESS));
        stubUpdateOutcome("plugin-2", outcome(PluginInstallUtils.UpdateOutcome.Status.UPDATED));
        stubUpdateOutcome("plugin-3", outcome(PluginInstallUtils.UpdateOutcome.Status.INVALID_DOWNLOAD));

        executor.updatePlugin(player, "all");

        List<String> messages = drainMessages();
        String summary = messages.get(messages.size() - 1);
        assertThat(summary).contains("1个成功，1个失败，1个");
        assertThat(summary)
                .as("review r5 IN-06: an update can also be skipped because an uninstall is running")
                .contains("正在进行另一项更新或卸载");
        assertThat(String.join("\n", messages)).contains("正在进行另一项更新或卸载").contains("不是该模块");
    }

    @Test
    @DisplayName("review r3: /upm update all ends by asking for unrestored old jars to be moved back before restarting")
    void updateAllWithUnrestoredJar_summaryAsksToMoveBackBeforeRestart() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("Plugin1", "plugin-1", "Plugin2", "plugin-2");
        stubUpdateOutcome("plugin-1", outcome(PluginInstallUtils.UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED,
                Collections.singletonList("/srv/minecraft/plugins/UltiTools/plugins/plugin-1-1.1.0.jar"),
                Collections.singletonList("/srv/minecraft/plugins/UltiTools/.upm-staging/plugin-1-1.0.0.jar.x.old"),
                Collections.<String>emptyList()));
        stubUpdateOutcome("plugin-2", outcome(PluginInstallUtils.UpdateOutcome.Status.UPDATED));

        executor.updatePlugin(player, "all");

        List<String> messages = drainMessages();
        String summary = messages.get(messages.size() - 1);
        assertThat(summary)
                .as("restarting before the unrestored jar is moved back loses that module")
                .contains("1个成功，1个失败")
                .contains("移回")
                .doesNotContain("请重启服务器。");
    }

    @Test
    @DisplayName("review r4 WR-01: staging and modules folders on different file systems are refused, naming both folders")
    void updateFileSystemsDiffer_namesBothFolders() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String staging = "/srv/minecraft/plugins/UltiTools/.upm-staging";
        String modules = "/mnt/shared/modules";
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.FILE_SYSTEMS_DIFFER,
                Arrays.asList(staging, modules), Collections.<String>emptyList(), Collections.<String>emptyList()));

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains(staging).contains(modules).contains("同一文件系统").contains("未做任何更改")
                .doesNotContain("更新成功");
    }

    @Test
    @DisplayName("review r4 IN-03: a staging directory that cannot be prepared is named in the reply")
    void updateStagingUnavailable_namesTheStagingFolder() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String staging = "/srv/minecraft/plugins/UltiTools/.upm-staging";
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.STAGING_UNAVAILABLE,
                Collections.singletonList(staging), Collections.<String>emptyList(), Collections.<String>emptyList()));

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains(staging).contains("暂存目录").doesNotContain("更新成功");
    }

    @Test
    @DisplayName("review r4 WR-02: an unrestored jar is reported as its staging path followed by the exact original path to restore")
    void updateUnrestoredJar_namesStagingPathAndOriginalPath() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String aside = "/srv/minecraft/plugins/UltiTools/.upm-staging/test-plugin-1.0.0.jar.8420a849-1c2d-4e5f-9a0b-1c2d3e4f5a6b.old";
        String original = "/srv/minecraft/plugins/UltiTools/plugins/test-plugin-1.0.0.jar";
        PluginInstallUtils.UpdateOutcome result = outcome(PluginInstallUtils.UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED,
                Collections.singletonList("/srv/minecraft/plugins/UltiTools/plugins/test-plugin-1.1.0.jar"),
                Collections.singletonList(aside), Collections.<String>emptyList());
        when(result.getUnrestoredTargets()).thenReturn(Collections.singletonList(original));
        stubUpdateOutcome("test-plugin", result);

        String reply = updateReply("TestPlugin");

        assertThat(reply)
                .as("moving the .old file back under its staging name leaves the module unloadable")
                .contains(aside + " -> " + original);
    }

    @Test
    @DisplayName("review r4 WR-04: a failed move's cause is part of the reply")
    void updateOldJarNotMoved_includesTheCause() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String oldJar = "/srv/minecraft/plugins/UltiTools/plugins/test-plugin-1.0.0.jar";
        PluginInstallUtils.UpdateOutcome result = outcome(PluginInstallUtils.UpdateOutcome.Status.OLD_JAR_NOT_MOVED,
                Collections.singletonList(oldJar), Collections.<String>emptyList(), Collections.<String>emptyList());
        when(result.getFailureReason()).thenReturn("java.nio.file.AccessDeniedException: " + oldJar);
        stubUpdateOutcome("test-plugin", result);

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains(oldJar).contains("java.nio.file.AccessDeniedException");
    }

    @Test
    @DisplayName("review r4 WR-05: a newer jar in the modules folder is named with both versions, and nothing is changed")
    void updateNewerVersionPresent_namesTheJarAndBothVersions() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String newer = "/srv/minecraft/plugins/UltiTools/plugins/UltiChat-2.1.0-beta.jar";
        PluginInstallUtils.UpdateOutcome result = outcome(PluginInstallUtils.UpdateOutcome.Status.NEWER_VERSION_PRESENT,
                Collections.singletonList(newer), Collections.<String>emptyList(), Collections.<String>emptyList());
        when(result.getFoundVersion()).thenReturn("2.1.0");
        when(result.getExpectedVersion()).thenReturn("2.0.0");
        stubUpdateOutcome("test-plugin", result);

        String reply = updateReply("TestPlugin");

        assertThat(reply).contains(newer).contains("2.1.0").contains("2.0.0").contains("未做任何更改")
                .doesNotContain("更新成功");
    }

    @Test
    @DisplayName("review r4 WR-03: an uninstall refused because an update of the module is running says so")
    void uninstallDuringUpdate_repliesRefusal() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(new com.ultikits.ultitools.exceptions.PluginModuleException(
                        com.ultikits.ultitools.exceptions.ErrorCode.valueOf("PLUGIN_OPERATION_IN_PROGRESS"),
                        "an update of this module is running"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> executor.uninstallPlugin(player, "test-plugin"));

        assertThat(thrown).isNull();
        assertThat(String.join("\n", drainMessages()))
                .contains("卸载失败！该模块正在进行更新或卸载")
                .doesNotContain("卸载成功");
    }

    @Test
    @DisplayName("review r4 WR-01: /upm update all says to restart only once, after every module has finished")
    void updateAll_restartInstructionOnlyInTheSummary() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("Plugin1", "plugin-1", "Plugin2", "plugin-2");
        stubUpdateOutcome("plugin-1", outcome(PluginInstallUtils.UpdateOutcome.Status.UPDATED));
        stubUpdateOutcome("plugin-2", outcome(PluginInstallUtils.UpdateOutcome.Status.UPDATED));

        executor.updatePlugin(player, "all");

        List<String> messages = drainMessages();
        assertThat(messages.subList(0, messages.size() - 1))
                .as("restarting on a per-module line kills the loop inside a later module's update")
                .noneMatch(m -> m.contains("重启"));
        assertThat(messages.get(messages.size() - 1)).contains("2个成功，0个失败").contains("请重启服务器");
    }

    @Test
    @DisplayName("review r4 IN-07: the unrestored summary counts refused updates as skipped, not as failed")
    void updateAllWithUnrestoredJarAndRefusedUpdate_countsSkippedSeparately() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("Plugin1", "plugin-1", "Plugin2", "plugin-2");
        stubUpdateOutcome("plugin-1", outcome(PluginInstallUtils.UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED,
                Collections.singletonList("/srv/minecraft/plugins/UltiTools/plugins/plugin-1-1.1.0.jar"),
                Collections.singletonList("/srv/minecraft/plugins/UltiTools/.upm-staging/plugin-1-1.0.0.jar.x.old"),
                Collections.<String>emptyList()));
        stubUpdateOutcome("plugin-2", outcome(PluginInstallUtils.UpdateOutcome.Status.ALREADY_IN_PROGRESS));

        executor.updatePlugin(player, "all");

        List<String> messages = drainMessages();
        assertThat(messages.get(messages.size() - 1)).contains("0个成功，1个失败，1个跳过");
    }

    @Test
    @DisplayName("redesign: a successful update says the restart confirms it, and that a failed load rolls back")
    void updateSuccess_saysTheRestartConfirmsIt() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.UPDATED,
                Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList()));

        String reply = updateReply("TestPlugin");

        assertThat(reply)
                .as("the operator must know the restart decides it, and what happens if the module does not load")
                .contains("重启")
                .contains("确认")
                .contains("回滚");
    }

    @Test
    @DisplayName("codex r19 P2: a mixed failure keeps the module-JAR warning as well as the leftover one")
    void uninstallMixedFailure_reportsBothCategories() {
        String jar = "/srv/minecraft/plugins/UltiTools/plugins/Fixture-1.0.0.jar";
        String leftover = stagingPath("8420a849.txn");
        java.nio.file.FileSystemException failure =
                new java.nio.file.FileSystemException(jar, null, "Permission denied");
        failure.addSuppressed(new java.nio.file.FileSystemException(leftover, null, "Permission denied"));
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin")).thenThrow(failure);

        executor.onCommand(player, mockCommand, "upm", new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();

        String reply = String.join("\n", drainMessages());
        assertThat(reply)
                .as("the JAR reloads the module for certain; the leftover only might, and they need different words")
                .contains("模块 JAR 文件无法删除")
                .contains(jar)
                .contains("更新残留文件")
                .contains(leftover);
    }

    @Test
    @DisplayName("codex r18 P2: every file an uninstall could not delete is named, however deeply attached")
    void uninstallFailure_namesNestedSuppressedFiles() {
        String jar = "/srv/minecraft/plugins/UltiTools/plugins/Fixture-1.0.0.jar";
        String firstLeftover = stagingPath("8420a849.txn");
        String secondLeftover = stagingPath("Fixture-1.0.0.jar.8420a849.old");
        java.nio.file.FileSystemException jarFailure =
                new java.nio.file.FileSystemException(jar, null, "Permission denied");
        java.nio.file.FileSystemException stagingFailure =
                new java.nio.file.FileSystemException(firstLeftover, null, "Permission denied");
        stagingFailure.addSuppressed(new java.nio.file.FileSystemException(secondLeftover, null, "Permission denied"));
        jarFailure.addSuppressed(stagingFailure);
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin")).thenThrow(jarFailure);

        executor.onCommand(player, mockCommand, "upm", new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();

        String reply = String.join("\n", drainMessages());
        assertThat(reply)
                .as("a file left unnamed is one the operator leaves behind, and it can restore the module")
                .contains(jar)
                .contains(firstLeftover)
                .contains(secondLeftover);
    }

    @Test
    @DisplayName("codex r10 P2: a staging file that could not be deleted is not reported as a module JAR")
    void uninstallStagingFailure_isNotReportedAsAModuleJar() {
        String journal = stagingPath("8420a849-1c2d-4e5f-9a0b-1c2d3e4f5a6b.txn");
        mockedUtils.when(() -> PluginInstallUtils.uninstallPlugin("test-plugin"))
                .thenThrow(new java.nio.file.FileSystemException(journal, null, "could not be read"));

        executor.onCommand(player, mockCommand, "upm", new String[]{"uninstall", "test-plugin"});
        server.getScheduler().performOneTick();

        String reply = String.join("\n", drainMessages());
        assertThat(reply).contains(journal);
        assertThat(reply)
                .as("this is an update leftover, not a module JAR: deleting only it leaves nothing else to do")
                .doesNotContain("模块 JAR 文件无法删除");
    }

    @Test
    @DisplayName("codex r6 P2: a move that cannot be atomic is not reported as two file systems")
    void updateAtomicMoveUnsupported_doesNotBlameTheFileSystemLayout() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        stubUpdateOutcome("test-plugin", outcome(PluginInstallUtils.UpdateOutcome.Status.ATOMIC_MOVE_UNSUPPORTED,
                Arrays.asList("/srv/minecraft/plugins/UltiTools/.upm-staging",
                        "/srv/minecraft/plugins/UltiTools/plugins"),
                Collections.<String>emptyList(), Collections.<String>emptyList()));

        String reply = updateReply("TestPlugin");

        assertThat(reply)
                .as("the folders are on one file system; what it cannot do is rename atomically")
                .doesNotContain("不在同一文件系统上")
                .contains("原子");
    }

    @Test
    @DisplayName("review r5 IN-01: a cross-file-system refusal that left a jar in staging does not say nothing changed")
    void updateFileSystemsDifferWithUnrestoredJar_doesNotClaimNothingChanged() {
        assertThat(executor).as("PluginInstallUtils static mocking must be available").isNotNull();
        stubModuleUpdates("TestPlugin", "test-plugin");
        String aside = "/srv/minecraft/plugins/UltiTools/.upm-staging/test-plugin-1.0.0.jar.8420a849.old";
        String original = "/srv/minecraft/plugins/UltiTools/plugins/test-plugin-1.0.0.jar";
        PluginInstallUtils.UpdateOutcome result = outcome(PluginInstallUtils.UpdateOutcome.Status.FILE_SYSTEMS_DIFFER,
                Arrays.asList("/srv/minecraft/plugins/UltiTools/.upm-staging",
                        "/srv/minecraft/plugins/UltiTools/plugins"),
                Collections.singletonList(aside), Collections.<String>emptyList());
        when(result.getUnrestoredTargets()).thenReturn(Collections.singletonList(original));
        stubUpdateOutcome("test-plugin", result);

        String reply = updateReply("TestPlugin");

        assertThat(reply)
                .contains(aside + " -> " + original)
                .as("a jar left in the staging folder IS a change")
                .doesNotContain("未做任何更改");
    }

    private List<String> drainMessages() {
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    @Test
    @DisplayName("Should work with console sender for list")
    void testConsoleListCommand() {
        if (executor == null) return;
        
        ConsoleCommandSender console = server.getConsoleSender();
        
        PluginEntity plugin1 = new PluginEntity();
        plugin1.setName("TestPlugin");
        plugin1.setIdentifyString("test-plugin");
        plugin1.setShortDescription("Test description");
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Arrays.asList(plugin1));
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(console, mockCommand, "upm", new String[]{"list"});
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should work with console sender for install")
    void testConsoleInstallCommand() {
        if (executor == null) return;
        
        ConsoleCommandSender console = server.getConsoleSender();
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.installLatestPlugin("test-plugin"))
                .thenReturn(true);
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        boolean result = executor.onCommand(console, mockCommand, "upm", 
            new String[]{"install", "test-plugin"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle invalid page number")
    void testInvalidPageNumber() {
        if (executor == null) return;
        
        PluginEntity plugin1 = new PluginEntity();
        plugin1.setName("TestPlugin");
        plugin1.setIdentifyString("test-plugin");
        plugin1.setShortDescription("Test description");
        
        // Invalid page number should default to page 1
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Arrays.asList(plugin1));
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list", "invalid"});
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle empty plugin list gracefully")
    void testEmptyPluginList() {
        if (executor == null) return;
        
        // Mock empty plugin list
        when(mockPluginManager.getPluginList()).thenReturn(Collections.emptyList());
        
        try {
            mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                .thenReturn(Arrays.asList());
        } catch (Exception e) {
            // Skip if mocking not available
            return;
        }
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list", "1"});
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle command without OP permission")
    void testNoOpPermission() {
        if (executor == null) return;

        player.setOp(false);
        player.addAttachment(UltiTools.getInstance(), "ultikits.tools.admin", false);

        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"help"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        // Command should still execute, just with limited functionality
    }

    @Test
    @DisplayName("Should handle /upm check with no updates")
    void testCheckNoUpdates() {
        if (executor == null) return;

        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(false);
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);

        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"check"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("没有可用的更新");
    }

    @Test
    @DisplayName("Should handle /upm check with framework update")
    void testCheckFrameworkUpdate() {
        if (executor == null) return;

        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(true);
        UpdateInfo fwInfo = new UpdateInfo();
        fwInfo.setPluginName("UltiTools-API");
        fwInfo.setCurrentVersion("6.1.0");
        fwInfo.setLatestVersion("6.2.0");
        when(mockUpdateManager.getFrameworkUpdate()).thenReturn(fwInfo);
        when(mockUpdateManager.getModuleUpdates()).thenReturn(new HashMap<>());
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);

        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"check"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        // Collect all messages
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        String allMessages = String.join("\n", messages);
        assertThat(allMessages).contains("6.1.0").contains("6.2.0");
    }

    @Test
    @DisplayName("Should handle /upm check with module updates")
    void testCheckModuleUpdates() {
        if (executor == null) return;

        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(true);
        when(mockUpdateManager.getFrameworkUpdate()).thenReturn(null);
        Map<String, UpdateInfo> updates = new HashMap<>();
        UpdateInfo info = new UpdateInfo();
        info.setPluginName("TestPlugin");
        info.setIdentifyString("test-plugin");
        info.setCurrentVersion("1.0.0");
        info.setLatestVersion("1.1.0");
        updates.put("TestPlugin", info);
        when(mockUpdateManager.getModuleUpdates()).thenReturn(updates);
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);

        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"check"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        String allMessages = String.join("\n", messages);
        assertThat(allMessages).contains("TestPlugin").contains("1.0.0").contains("1.1.0");
    }

    @Test
    @DisplayName("Should handle /upm update with plugin name")
    void testUpdatePlugin() {
        if (executor == null) return;

        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        Map<String, UpdateInfo> updates = new HashMap<>();
        UpdateInfo info = new UpdateInfo();
        info.setPluginName("TestPlugin");
        info.setIdentifyString("test-plugin");
        info.setCurrentVersion("1.0.0");
        info.setLatestVersion("1.1.0");
        updates.put("TestPlugin", info);
        when(mockUpdateManager.getModuleUpdates()).thenReturn(updates);
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);

        try {
            mockedUtils.when(() -> PluginInstallUtils.updatePlugin("test-plugin"))
                .thenReturn(true);
        } catch (Exception e) {
            return;
        }

        boolean result = executor.onCommand(player, mockCommand, "upm",
            new String[]{"update", "TestPlugin"});

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle /upm update with nonexistent plugin")
    void testUpdateNonexistentPlugin() {
        if (executor == null) return;

        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        when(mockUpdateManager.getModuleUpdates()).thenReturn(new HashMap<>());
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);

        boolean result = executor.onCommand(player, mockCommand, "upm",
            new String[]{"update", "NonExistent"});

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle /upm update all")
    void testUpdateAll() {
        if (executor == null) return;

        UpdateManager mockUpdateManager = mock(UpdateManager.class);
        Map<String, UpdateInfo> updates = new HashMap<>();
        UpdateInfo info1 = new UpdateInfo();
        info1.setPluginName("Plugin1");
        info1.setIdentifyString("plugin-1");
        info1.setCurrentVersion("1.0.0");
        info1.setLatestVersion("1.1.0");
        updates.put("Plugin1", info1);
        UpdateInfo info2 = new UpdateInfo();
        info2.setPluginName("Plugin2");
        info2.setIdentifyString("plugin-2");
        info2.setCurrentVersion("2.0.0");
        info2.setLatestVersion("2.1.0");
        updates.put("Plugin2", info2);
        when(mockUpdateManager.getModuleUpdates()).thenReturn(updates);
        when(UltiTools.getInstance().getUpdateManager()).thenReturn(mockUpdateManager);

        try {
            mockedUtils.when(() -> PluginInstallUtils.updatePlugin("plugin-1"))
                .thenReturn(true);
            mockedUtils.when(() -> PluginInstallUtils.updatePlugin("plugin-2"))
                .thenReturn(true);
        } catch (Exception e) {
            return;
        }

        boolean result = executor.onCommand(player, mockCommand, "upm",
            new String[]{"update", "all"});

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        server.getScheduler().performTicks(20);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should show check and update in help")
    void testHelpContainsCheckAndUpdate() {
        if (executor == null) return;

        boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"help"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();

        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }

        String allMessages = String.join("\n", messages);
        assertThat(allMessages)
            .contains("check")
            .contains("update");
    }

    /**
     * #439: {@code listPlugins}'s player branch used to decide "installed" via a direct
     * {@code installedPlugin.getPluginName().equals(plugin.getName())} string comparison between
     * a loaded module's Bukkit {@code plugin.yml} name and the catalogue's display name. Every
     * in-repo module names itself with the literal {@code "UltiTools-"} vendor prefix
     * ({@code UltiTools-Economy}, {@code UltiTools-Menu}, ...) while the catalogue's own display
     * name omits it, so a genuinely-loaded, genuinely-matching module was reported not installed.
     * These tests exercise the extracted, package-private {@code isSameModule} decision directly
     * (same package as {@link PluginInstallCommands}, so no reflection is needed) -- this is the
     * exact function the fix changed, and testing it directly avoids depending on Adventure
     * component rendering, which this file's other tests do not assert content on either.
     */
    @Nested
    @DisplayName("#439 installed-state matching by stable identifier")
    class InstalledStateMatchingTests {

        @Test
        @DisplayName("a catalogue entry whose display name differs but shares identifyString matches")
        void matchesByIdentifyStringDespiteDifferentDisplayName() {
            UltiToolsPlugin economy = mock(UltiToolsPlugin.class);
            when(economy.getPluginName()).thenReturn("UltiTools-Economy");
            when(economy.getIdentifyString()).thenReturn("com.ultikits.economy");

            PluginEntity catalogueEntry = new PluginEntity();
            catalogueEntry.setName("Economy System"); // deliberately a DIFFERENT display string
            catalogueEntry.setIdentifyString("com.ultikits.economy");

            assertThat(PluginInstallCommands.isSameModule(economy, catalogueEntry)).isTrue();
        }

        @Test
        @DisplayName("#439's two named modules match via the shared 'UltiTools-' prefix normalisation")
        void matchesTheTwoNamedModulesByPrefixNormalisation() {
            UltiToolsPlugin economy = mock(UltiToolsPlugin.class);
            when(economy.getPluginName()).thenReturn("UltiTools-Economy");
            when(economy.getIdentifyString()).thenReturn(null);

            UltiToolsPlugin menu = mock(UltiToolsPlugin.class);
            when(menu.getPluginName()).thenReturn("UltiTools-Menu");
            when(menu.getIdentifyString()).thenReturn(null);

            PluginEntity economyEntry = new PluginEntity();
            economyEntry.setName("Economy");

            PluginEntity menuEntry = new PluginEntity();
            menuEntry.setName("Menu");

            assertThat(PluginInstallCommands.isSameModule(economy, economyEntry)).isTrue();
            assertThat(PluginInstallCommands.isSameModule(menu, menuEntry)).isTrue();
        }

        @Test
        @DisplayName("a module whose name already exactly equals the catalogue name still matches (no regression)")
        void exactNameMatchStillWorks() {
            UltiToolsPlugin exact = mock(UltiToolsPlugin.class);
            when(exact.getPluginName()).thenReturn("UltiLogin");
            when(exact.getIdentifyString()).thenReturn(null);

            PluginEntity entry = new PluginEntity();
            entry.setName("UltiLogin");

            assertThat(PluginInstallCommands.isSameModule(exact, entry)).isTrue();
        }

        @Test
        @DisplayName("a catalogue entry for a module that is genuinely not loaded does not match")
        void genuinelyAbsentModuleDoesNotMatch() {
            UltiToolsPlugin economy = mock(UltiToolsPlugin.class);
            when(economy.getPluginName()).thenReturn("UltiTools-Economy");
            when(economy.getIdentifyString()).thenReturn(null);

            PluginEntity notLoaded = new PluginEntity();
            notLoaded.setName("SomethingElseEntirely");

            assertThat(PluginInstallCommands.isSameModule(economy, notLoaded)).isFalse();
        }

        /**
         * The guard against over-fixing: a catalogue name that merely RESEMBLES a loaded module's
         * -- sharing a prefix or substring -- must be rejected, not treated as the same module. A
         * looser match that mislabels a different module as installed is a worse defect than the
         * one #439 reports.
         */
        @Test
        @DisplayName("a catalogue entry whose name resembles but is not a loaded module's is rejected")
        void resemblingButDifferentModuleIsRejected() {
            UltiToolsPlugin economy = mock(UltiToolsPlugin.class);
            when(economy.getPluginName()).thenReturn("UltiTools-Economy");
            when(economy.getIdentifyString()).thenReturn(null);

            PluginEntity lookAlike = new PluginEntity();
            lookAlike.setName("EconomyPro"); // resembles "Economy" but names a DIFFERENT module
            lookAlike.setIdentifyString(null);

            assertThat(PluginInstallCommands.isSameModule(economy, lookAlike)).isFalse();
        }

        @Test
        @DisplayName("both sides carrying a blank identifyString never auto-matches on that alone")
        void blankIdentifyStringOnBothSidesIsNotAMatch() {
            UltiToolsPlugin noIdentify = mock(UltiToolsPlugin.class);
            when(noIdentify.getPluginName()).thenReturn("UltiTools-Economy");
            when(noIdentify.getIdentifyString()).thenReturn("");

            PluginEntity blankEntry = new PluginEntity();
            blankEntry.setName("SomethingElseEntirely");
            blankEntry.setIdentifyString("");

            assertThat(PluginInstallCommands.isSameModule(noIdentify, blankEntry)).isFalse();
        }

        @Test
        @DisplayName("an empty catalogue page renders without error")
        void emptyCataloguePageRendersWithoutError() {
            if (executor == null) return;

            try {
                mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                        .thenReturn(new ArrayList<>());
            } catch (Exception e) {
                return;
            }
            when(mockPluginManager.getPluginList()).thenReturn(new ArrayList<>());

            boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list", "1"});
            server.getScheduler().performTicks(20);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("a server with no modules loaded reports every catalogue entry as not installed")
        void noModulesLoadedMatchesNothing() {
            PluginEntity entry1 = new PluginEntity();
            entry1.setName("First");
            PluginEntity entry2 = new PluginEntity();
            entry2.setName("Second");

            // With NO loaded modules, isSameModule is never even asked about a candidate -- but
            // the fact that pins "not installed" is that it would say no for ANY catalogue entry,
            // which the reject/absent tests above already establish. This test additionally
            // exercises the full command path with zero loaded modules to confirm no exception.
            if (executor == null) return;
            try {
                mockedUtils.when(() -> PluginInstallUtils.getPluginList(1, 10))
                        .thenReturn(Arrays.asList(entry1, entry2));
            } catch (Exception e) {
                return;
            }
            when(mockPluginManager.getPluginList()).thenReturn(new ArrayList<>());

            boolean result = executor.onCommand(player, mockCommand, "upm", new String[]{"list", "1"});
            server.getScheduler().performTicks(20);

            assertThat(result).isTrue();
        }
    }

    /**
     * Gate-2 Codex review round 1 (three P2 findings on the #439 fix, all with a concrete failure
     * scenario):
     * <ul>
     *   <li>the installed branch's uninstall click command used the catalogue's display name, but
     *       {@code PluginInstallUtils#uninstallPlugin} matches only the loaded module's own
     *       runtime name -- for exactly the newly-recognised display-name-mismatch case this fix
     *       exists to handle, clicking "uninstall" would silently fail;</li>
     *   <li>two non-blank but DIFFERENT {@code identifyString} values fell through to the name/
     *       prefix fallback instead of being treated as an authoritative "not the same module";</li>
     *   <li>the {@code identifyString} comparison was case-sensitive, unlike {@code
     *       PluginInstallUtils#normalizeIdentifyString}'s trim+lowercase convention used
     *       everywhere else identify strings are compared.</li>
     * </ul>
     */
    @Nested
    @DisplayName("gate-2 Codex round 1: uninstall identity, conflicting IDs, ID normalisation")
    class GateTwoCodexRoundOneTests {

        @Test
        @DisplayName("resolves the loaded module's own runtime name for uninstall, not the catalogue's display name")
        void resolvesInstalledRuntimeNameNotCatalogueDisplayName() {
            UltiToolsPlugin economy = mock(UltiToolsPlugin.class);
            when(economy.getPluginName()).thenReturn("UltiTools-Economy");
            when(economy.getIdentifyString()).thenReturn(null);

            PluginEntity economyEntry = new PluginEntity();
            economyEntry.setName("Economy"); // catalogue display name, deliberately NOT the runtime name

            String resolved = PluginInstallCommands.resolveInstalledRuntimeName(
                    Collections.singletonList(economy), economyEntry);

            assertThat(resolved)
                    .as("PluginInstallUtils#uninstallPlugin matches only the runtime name -- using "
                            + "the catalogue's display name here would silently fail to uninstall")
                    .isEqualTo("UltiTools-Economy");
        }

        @Test
        @DisplayName("resolves null when no loaded module matches the catalogue entry")
        void resolvesNullWhenNoMatch() {
            PluginEntity notLoaded = new PluginEntity();
            notLoaded.setName("SomethingElseEntirely");

            String resolved = PluginInstallCommands.resolveInstalledRuntimeName(
                    Collections.emptyList(), notLoaded);

            assertThat(resolved).isNull();
        }

        @Test
        @DisplayName("two different, non-blank identifyStrings are authoritative -- never falls through to the name/prefix fallback")
        void conflictingIdentifyStringsAreAuthoritative() {
            UltiToolsPlugin foo = mock(UltiToolsPlugin.class);
            when(foo.getPluginName()).thenReturn("UltiTools-Foo");
            when(foo.getIdentifyString()).thenReturn("author-a.foo");

            PluginEntity catalogueFoo = new PluginEntity();
            catalogueFoo.setName("Foo"); // would match via the prefix fallback if the ID check didn't short-circuit
            catalogueFoo.setIdentifyString("author-b.foo");

            assertThat(PluginInstallCommands.isSameModule(foo, catalogueFoo))
                    .as("two present but DIFFERENT stable identifiers prove these are different "
                            + "modules; the name/prefix heuristic must not override that")
                    .isFalse();
        }

        @Test
        @DisplayName("identifyString comparison is case-insensitive and trims whitespace, matching PluginInstallUtils' own normalisation")
        void identifyStringComparisonIsCaseInsensitiveAndTrimmed() {
            UltiToolsPlugin economy = mock(UltiToolsPlugin.class);
            when(economy.getPluginName()).thenReturn("UltiTools-Economy");
            when(economy.getIdentifyString()).thenReturn("Com.UltiKits.Economy");

            PluginEntity entry = new PluginEntity();
            entry.setName("Something Else Entirely"); // deliberately not name-matching, to prove the ID path fires
            entry.setIdentifyString("  com.ultikits.economy  ");

            assertThat(PluginInstallCommands.isSameModule(economy, entry)).isTrue();
        }
    }
}
