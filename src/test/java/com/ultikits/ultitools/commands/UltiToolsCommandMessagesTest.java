package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertColour;
import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.ReloadReport;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginManager;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UltiToolsCommandMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void helpKeepsOneMessageAndFiveLiteralLines(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            CommandSender sender = mock(CommandSender.class);
            new UltiToolsCommands().help(sender);
            String expected = "zh".equals(language)
                    ? "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n/ul reload <模块名> 重载指定模块\n/ul list 查看已加载的模块列表\n================"
                    : "=== UltiTools Commands ===\n/ul reload Reload all modules\n/ul reload <name> Reload a specific module\n/ul list Check loaded module list\n================";
            assertLines(sender, expected);
            assertThat(expected.split("\n")).hasSize(5);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void namedReloadUsesInputNameAndLiteralArguments(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            var manager = mock(PluginManager.class);
            var module = mock(UltiToolsPlugin.class);
            when(fixture.plugin.getPluginManager()).thenReturn(manager);
            when(manager.getPluginList()).thenReturn(List.of(module));
            when(module.getPluginName()).thenReturn("<red>Module");
            when(module.reloadWithReport()).thenReturn(new ReloadReport());
            CommandSender sender = mock(CommandSender.class);
            new UltiToolsCommands().reloadPlugin(sender, "<RED>mODULE");
            var lines = assertLines(sender, "zh".equals(language) ? "模块 <RED>mODULE 已重载"
                    : "Module <RED>mODULE has been reloaded");
            assertColour(lines.getFirst(), NamedTextColor.GREEN);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void missingModuleHasOneRedReply(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            var manager = mock(PluginManager.class);
            when(fixture.plugin.getPluginManager()).thenReturn(manager);
            when(manager.getPluginList()).thenReturn(List.of());
            CommandSender sender = mock(CommandSender.class);
            new UltiToolsCommands().reloadPlugin(sender, "<red>missing");
            var lines = assertLines(sender, "zh".equals(language)
                    ? "模块 <red>missing 不是已加载的UltiTools模块，请使用 /ul list 查看已加载的模块"
                    : "<red>missing is not a loaded UltiTools module. Use /ul list to see loaded modules");
            assertColour(lines.getFirst(), NamedTextColor.RED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void failedAndPartialReloadKeepOneReplyAndTheCanonicalModuleName(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            var manager = mock(PluginManager.class);
            var module = mock(UltiToolsPlugin.class);
            when(fixture.plugin.getPluginManager()).thenReturn(manager);
            when(manager.getPluginList()).thenReturn(List.of(module));
            when(module.getPluginName()).thenReturn("Module");
            when(module.reloadWithReport()).thenThrow(new IllegalStateException("<red>boom"));
            CommandSender sender = mock(CommandSender.class);
            new UltiToolsCommands().reloadPlugin(sender, "module");
            assertColour(assertLines(sender, "zh".equals(language)
                    ? "模块 Module 重载失败：<red>boom。详情见控制台。"
                    : "Module Module failed to reload: <red>boom. See the console for details.").getFirst(), NamedTextColor.RED);
            clearInvocations(sender);
            ReloadReport partial = new ReloadReport();
            partial.partial("one");
            partial.partial("two");
            doReturn(partial).when(module).reloadWithReport();
            new UltiToolsCommands().reloadPlugin(sender, "module");
            assertLines(sender, "zh".equals(language)
                    ? "模块 Module 只重载了一部分；未重载：one; two"
                    : "Module Module reloaded partially; not reloaded: one; two");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void partialReloadDoesNotRepeatNativeConsoleLog(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language); var bukkit = mockStatic(Bukkit.class)) {
            var manager = mock(PluginManager.class);
            var module = mock(UltiToolsPlugin.class);
            when(fixture.plugin.getPluginManager()).thenReturn(manager);
            when(manager.getPluginList()).thenReturn(List.of(module));
            when(module.getPluginName()).thenReturn("Module");
            ReloadReport partial = new ReloadReport();
            partial.partial("one");
            doReturn(partial).when(module).reloadWithReport();
            ConsoleCommandSender console = mock(ConsoleCommandSender.class);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            new UltiToolsCommands().reloadPlugin(console, "Module");
            verify(console, never()).sendMessage(any(Component.class));
            verify(console, never()).sendMessage(anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void playerUsesTheirLocaleRatherThanTheServerFallback(String language) {
        try (var fixture = new CoreCommandMessageTestSupport("zh".equals(language) ? "en" : "zh")) {
            Player sender = mock(Player.class);
            when(sender.locale()).thenReturn(java.util.Locale.forLanguageTag(language));
            var manager = mock(PluginManager.class);
            when(fixture.plugin.getPluginManager()).thenReturn(manager);
            when(manager.getPluginList()).thenReturn(List.of());
            new UltiToolsCommands().reloadPlugin(sender, "missing");
            assertLines(sender, "zh".equals(language)
                    ? "模块 missing 不是已加载的UltiTools模块，请使用 /ul list 查看已加载的模块"
                    : "missing is not a loaded UltiTools module. Use /ul list to see loaded modules");
        }
    }
}
