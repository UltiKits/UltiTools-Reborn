package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.utils.ModuleContent;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.VersionLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class UpmCatalogueRepliesTest {
    private static final List<String> HELP_EN = List.of(
            "========|Plugin Install Help|========",
            "/upm list [page] - View available plugin list",
            "/upm install [plugin] - Install latest plugin",
            "/upm install [plugin] [version] - Install certain version plugin",
            "/upm versions [plugin] - View plugin version list",
            "/upm uninstall [plugin] - Uninstall plugin",
            "/upm check - View available updates",
            "/upm update [plugin] - Update a plugin",
            "/upm update all - Update all plugins",
            "/upm pending - List module changes waiting for a restart",
            "/upm revoke <module> - Undo one of them");
    private static final List<String> HELP_ZH = List.of(
            "========|插件安装帮助|========",
            "/upm list [页数] - 查看可用插件列表",
            "/upm install [插件] - 安装最新插件",
            "/upm install [插件] [版本] - 安装某版本插件",
            "/upm versions [插件] - 查看插件版本列表",
            "/upm uninstall [插件] - 删除插件",
            "/upm check - 查看可用更新",
            "/upm update [插件] - 更新插件",
            "/upm update all - 更新所有插件",
            "/upm pending - 查看等待重启生效的模块变更",
            "/upm revoke <模块> - 撤销其中一项");
    private static final List<String> PLAYER_LIST_EN = List.of(
            "========|Available Plugin List|========", "1.  Name: Demo", "    Status:  installed",
            "     | Uninstall |     ", "   Intro:Summary", "---------------------", "2.  Name: Other",
            "    Status:  not installed", "     | Install |     ", "   Intro:Details", "======== Page 1 ========");
    private static final List<String> PLAYER_LIST_ZH = List.of(
            "========|可用插件列表|========", "1.  名字：Demo", "    安装状态： 已安装", "     | 卸载 |     ",
            "   简介：Summary", "---------------------", "2.  名字：Other", "    安装状态： 未安装",
            "     | 安装 |     ", "   简介：Details", "======== 第1页 ========");
    private static final List<String> CONSOLE_LIST_EN = List.of(
            "========|Available Plugin List|========", "1.  Name: Demo", "   Install Command: /upm install demo",
            "   Intro:Summary", "---------------------", "2.  Name: Other", "   Install Command: /upm install other",
            "   Intro:Details", "======== Page 1 ========");
    private static final List<String> CONSOLE_LIST_ZH = List.of(
            "========|可用插件列表|========", "1.  名字：Demo", "   安装命令：/upm install demo", "   简介：Summary",
            "---------------------", "2.  名字：Other", "   安装命令：/upm install other", "   简介：Details",
            "======== 第1页 ========");

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void helpHasElevenLinesInOrder(String language) {
        try (var fixture = new Fixture(language, true)) {
            fixture.commands.handleHelp(fixture.sender);
            assertThat(fixture.lines()).containsExactlyElementsOf(en(language) ? HELP_EN : HELP_ZH);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void playerListSendsOneLineAtATimeAndPreservesBothButtons(String language) {
        try (var fixture = new Fixture(language, true); var catalogue = mockStatic(PluginInstallUtils.class)) {
            stubCatalogue(fixture, catalogue);
            fixture.commands.listPlugins(fixture.sender);

            assertThat(fixture.lines()).containsExactlyElementsOf(en(language) ? PLAYER_LIST_EN : PLAYER_LIST_ZH);
            assertButton(fixture.replies.get(3), 0xff0000, "/upm uninstall UltiTools-Demo",
                    en(language) ? "Click to uninstall module" : "点击卸载模块");
            assertButton(fixture.replies.get(8), 0x00ff00, "/upm install other",
                    en(language) ? "Click to install module" : "点击安装模块");
            assertThat(fixture.replies.get(5).color()).isEqualTo(TextColor.color(0x00ffff));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void consoleListUsesFallbackAndKeepsTheSeparatorUncoloured(String language) {
        try (var fixture = new Fixture(language, false); var catalogue = mockStatic(PluginInstallUtils.class)) {
            stubCatalogue(fixture, catalogue);
            fixture.commands.listPlugins(fixture.sender);

            assertThat(fixture.lines()).containsExactlyElementsOf(en(language) ? CONSOLE_LIST_EN : CONSOLE_LIST_ZH);
            assertThat(fixture.replies.getFirst().color()).isEqualTo(TextColor.color(0x00ffff));
            assertThat(fixture.replies.get(4).color()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void invalidPageUsesTheSharedArgumentError(String language) {
        try (var fixture = new Fixture(language, true)) {
            fixture.commands.listPlugins(fixture.sender, "x");
            assertThat(fixture.lines()).containsExactly("Failed to parse 'x' as Integer");
            assertThat(fixture.replies.getFirst().color()).isEqualTo(NamedTextColor.RED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void versionsDistinguishesMissingFailedAndIncompatible(String language) {
        try (var fixture = new Fixture(language, true); var catalogue = mockStatic(PluginInstallUtils.class)) {
            fixture.ulti.when(UltiTools::getPluginVersion).thenReturn(701);
            fixture.assertVersionLine(catalogue, VersionLookup.none(), en(language)
                    ? "Module demo was not found on UltiCloud." : "UltiCloud 上没有找到模块 demo。");
            fixture.assertVersionLine(catalogue, VersionLookup.failed("unavailable"), en(language)
                    ? "Could not fetch the version list!" : "获取版本列表失败！");
            fixture.assertVersionLine(catalogue, VersionLookup.found(null), en(language)
                    ? "No version of demo can be installed on this server. This UltiTools-API is at API level 701 and installs only modules declaring api-version 700 to 701."
                    : "demo 没有可以安装在本服务器上的版本。本 UltiTools-API 的 API 级别是 701，只安装声明 api-version 700 到 701 的模块。");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void versionsShowsTheListAndOneInstallHint(String language) {
        try (var fixture = new Fixture(language, true); var catalogue = mockStatic(PluginInstallUtils.class)) {
            catalogue.when(() -> PluginInstallUtils.lookUpPluginVersions(eq("demo"), any())).thenAnswer(call -> {
                List<String> versions = call.getArgument(1);
                versions.addAll(List.of("2.0", "1.0"));
                return VersionLookup.found("2.0");
            });
            fixture.commands.listVersions(fixture.sender, "demo");
            var english = List.of("========|Available Version List|========", "1. Version: 2.0", "2. Version: 1.0",
                    "   Install Command: /upm install demo [version]", "---------------------");
            var chinese = List.of("========|可用版本列表|========", "1. 版本：2.0", "2. 版本：1.0",
                    "   安装命令：/upm install demo [version]", "---------------------");
            assertThat(fixture.lines()).containsExactlyElementsOf(en(language) ? english : chinese);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void checkDistinguishesRunningFailedEmptyAndIncomplete(String language) {
        try (var fixture = new Fixture(language, true)) {
            when(fixture.updates.isCheckComplete()).thenReturn(false);
            fixture.assertCheckLine(en(language) ? "Update check still running; try again in a few seconds."
                    : "更新检查仍在进行，请稍后重试。", NamedTextColor.YELLOW);
            when(fixture.updates.isCheckFailed()).thenReturn(true);
            fixture.assertCheckLine(en(language)
                    ? "Update check failed; see the console. It runs again at the next server start."
                    : "更新检查失败，详情见控制台；重启服务器后会再次检查。", NamedTextColor.YELLOW);
            when(fixture.updates.isCheckComplete()).thenReturn(true);
            fixture.assertCheckLine(en(language) ? "No updates available." : "没有可用的更新。", NamedTextColor.GREEN);
            when(fixture.updates.getUnconfirmed()).thenReturn(List.of("Demo", "Other"));
            fixture.assertCheckLine(en(language) ? "Update check incomplete: could not confirm the latest version of Demo, Other."
                    : "更新检查未完成：无法确认 Demo, Other 的最新版本。", NamedTextColor.YELLOW);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void checkListsFrameworkDownloadAndModuleLines(String language) {
        try (var fixture = new Fixture(language, true)) {
            when(fixture.updates.hasAnyUpdates()).thenReturn(true);
            when(fixture.updates.getFrameworkUpdate()).thenReturn(update("UltiTools-API", "7.0.0", "7.1.0"));
            when(fixture.updates.getModuleUpdates()).thenReturn(Map.of("Demo", update("Demo", "1.0", "2.0")));
            fixture.commands.checkUpdates(fixture.sender);
            var english = List.of("Available updates:", "  UltiTools-API 7.0.0 → 7.1.0",
                    "  Download URL: https://github.com/UltiKits/UltiTools-Reborn/releases/latest", "  Demo 1.0 → 2.0");
            var chinese = List.of("可用更新列表：", "  UltiTools-API 7.0.0 → 7.1.0",
                    "  下载地址：https://github.com/UltiKits/UltiTools-Reborn/releases/latest", "  Demo 1.0 → 2.0");
            assertThat(fixture.lines()).containsExactlyElementsOf(en(language) ? english : chinese);
            assertThat(fixture.replies.stream().map(Component::color)).containsExactly(
                    NamedTextColor.GREEN, NamedTextColor.YELLOW, NamedTextColor.GRAY, NamedTextColor.YELLOW);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void updateOneAndAllKeepTheirDistinctColours(String language) {
        try (var fixture = new Fixture(language, true); var catalogue = mockStatic(PluginInstallUtils.class)) {
            String none = en(language) ? "No updates available." : "没有可用的更新。";
            fixture.commands.updatePlugin(fixture.sender, "Demo");
            assertThat(fixture.lines()).containsExactly(none);
            assertThat(fixture.replies.removeFirst().color()).isEqualTo(NamedTextColor.RED);
            fixture.commands.updatePlugin(fixture.sender, "all");
            assertThat(fixture.lines()).containsExactly(none);
            assertThat(fixture.replies.removeFirst().color()).isEqualTo(NamedTextColor.GREEN);
            when(fixture.updates.getModuleUpdates()).thenReturn(Map.of("Demo", update("Demo", "1.0", "2.0")));
            catalogue.when(() -> PluginInstallUtils.updateResult(eq("Demo"), any()))
                    .thenReturn(new PluginInstallUtils.PendingResult(false, List.of(), null, ModuleContent.NONE));
            fixture.commands.updatePlugin(fixture.sender, "Demo");
            assertThat(fixture.lines()).containsExactly(en(language) ? "Updating Demo..." : "正在更新 Demo...");
            assertThat(fixture.replies.getFirst().color()).isEqualTo(NamedTextColor.YELLOW);
            catalogue.verify(() -> PluginInstallUtils.stageUpdate("demo", fixture.sender));
        }
    }

    private static boolean en(String language) {
        return "en".equals(language);
    }

    private static void assertButton(Component button, int rgb, String command, String hover) {
        assertThat(button.color()).isEqualTo(TextColor.color(rgb));
        assertThat(button.clickEvent()).isEqualTo(ClickEvent.runCommand(command));
        assertThat(button.hoverEvent()).isEqualTo(HoverEvent.showText(Component.text(hover)));
    }

    private static void stubCatalogue(Fixture fixture, MockedStatic<PluginInstallUtils> catalogue) {
        PluginManager manager = mock(PluginManager.class);
        when(fixture.instance.getPluginManager()).thenReturn(manager);
        UltiToolsPlugin installed = mock(UltiToolsPlugin.class);
        when(installed.getPluginName()).thenReturn("UltiTools-Demo");
        when(installed.getIdentifyString()).thenReturn("demo");
        when(manager.getPluginList()).thenReturn(List.of(installed));
        catalogue.when(() -> PluginInstallUtils.getPluginList(1, 10)).thenReturn(List.of(
                entry("Demo", "demo", "Summary"), entry("Other", "other", "Details")));
    }

    private static PluginEntity entry(String name, String id, String description) {
        PluginEntity plugin = new PluginEntity();
        plugin.setName(name);
        plugin.setIdentifyString(id);
        plugin.setShortDescription(description);
        return plugin;
    }

    private static UpdateInfo update(String name, String current, String latest) {
        UpdateInfo info = new UpdateInfo();
        info.setPluginName(name);
        info.setIdentifyString(name.toLowerCase(Locale.ROOT));
        info.setCurrentVersion(current);
        info.setLatestVersion(latest);
        return info;
    }

    private static final class Fixture implements AutoCloseable {
        private final List<Component> replies = new ArrayList<>();
        private final PluginInstallCommands commands = new PluginInstallCommands();
        private final UltiTools instance = mock(UltiTools.class);
        private final UpdateManager updates = mock(UpdateManager.class);
        private final MockedStatic<UltiTools> ulti = mockStatic(UltiTools.class);
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final MockedStatic<CommandRuntime> runtime = mockStatic(CommandRuntime.class);
        private final CommandMessageTestSupport text;
        private final CommandSender sender;

        private Fixture(String language, boolean usePlayer) {
            text = new CommandMessageTestSupport(usePlayer ? (en(language) ? "zh" : "en") : language);
            sender = usePlayer ? mockPlayer(language) : mock(ConsoleCommandSender.class);
            doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(any(Component.class));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ulti.when(UltiTools::getInstance).thenReturn(instance);
            runtime.when(CommandRuntime::messages).thenReturn(text.messages());
            when(instance.getUpdateManager()).thenReturn(updates);
            when(updates.isCheckComplete()).thenReturn(true);
            when(updates.getModuleUpdates()).thenReturn(Map.of());
            when(updates.getUnconfirmed()).thenReturn(List.of());
        }

        private static Player mockPlayer(String language) {
            Player player = mock(Player.class);
            when(player.locale()).thenReturn(en(language) ? Locale.US : Locale.SIMPLIFIED_CHINESE);
            return player;
        }

        private List<String> lines() {
            return replies.stream().map(CommandMessageTestSupport::plain).toList();
        }

        private void assertVersionLine(MockedStatic<PluginInstallUtils> catalogue, VersionLookup lookup, String expected) {
            catalogue.when(() -> PluginInstallUtils.lookUpPluginVersions(eq("demo"), any())).thenReturn(lookup);
            commands.listVersions(sender, "demo");
            assertThat(lines()).containsExactly(expected);
            assertThat(replies.removeFirst().color()).isEqualTo(NamedTextColor.RED);
        }

        private void assertCheckLine(String expected, NamedTextColor colour) {
            commands.checkUpdates(sender);
            assertThat(lines()).containsExactly(expected);
            assertThat(replies.removeFirst().color()).isEqualTo(colour);
        }

        @Override public void close() {
            runtime.close();
            bukkit.close();
            ulti.close();
            text.close();
        }
    }
}
