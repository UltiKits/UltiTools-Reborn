package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.text.Arg.number;
import static com.ultikits.ultitools.text.Arg.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith(CommandTestExtension.class)
class PendingChangesMessagesTest {
    @TempDir File root;

    @AfterEach
    void resetLedger() {
        ModuleContentLedgers.install(ModuleContentLedger.NONE);
    }

    @ParameterizedTest
    @MethodSource("warningCases")
    void warnsInOrderInTheUninstallReplyAndTheList(Locale locale, ModuleContent content, boolean throwsError,
                                                   List<String> warnings, int redWarnings) throws IOException {
        var data = ModuleUpdateFixtures.dataFolderIn(root);
        var transactions = new ModuleFileTransactions(data);
        var jar = ModuleUpdateFixtures.moduleJar(new File(ModuleFileTransactions.modulesFolder(data), "demo-1.jar"),
                "Demo", "1", "demo");
        ModuleContentLedgers.install((id, name) -> {
            assertThat(id).isEqualTo("demo");
            assertThat(name).isEqualTo("Demo");
            if (throwsError) {
                throw new IllegalStateException("ledger read failed");
            }
            return content;
        });
        var player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        var delivered = new ArrayList<Component>();
        doAnswer(call -> delivered.add(call.getArgument(0))).when(player).sendMessage(any(Component.class));
        var identity = new ModuleFileTransactions.RemovedModule(List.of("demo"), List.of("Demo"), List.of(jar.getName()));
        var request = ModuleFileTransactions.underLock(() -> transactions.queueRemoval("Demo", "demo", "1", identity,
                List.of(jar), List.of(), player, true));
        assertThat(request.queued()).isTrue();
        var framework = mock(UltiTools.class);
        when(framework.getModuleFileTransactions()).thenReturn(transactions);
        try (var tools = mockStatic(UltiTools.class); var installs = mockStatic(PluginInstallUtils.class);
             var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            tools.when(UltiTools::getInstance).thenReturn(framework);
            installs.when(() -> PluginInstallUtils.requestUninstall("demo", player)).thenReturn(request);
            installs.when(PluginInstallUtils::pendingChanges).thenReturn(new PendingChanges(transactions));
            var commands = new PluginInstallCommands();
            commands.uninstallPlugin(player, "demo");
            boolean zh = locale.getLanguage().equals("zh");
            var expected = new ArrayList<>(List.of(zh
                    ? "已加入待生效清单：Demo 将在下次重启时卸载，删除 demo-1.jar。重启前它照常运行。可用 /upm revoke Demo 撤销。"
                    : "Added to the pending changes: Demo will be uninstalled at the next restart, deleting demo-1.jar. It keeps running until then. Undo with /upm revoke Demo."));
            expected.addAll(warnings);
            if (!warnings.isEmpty()) {
                expected.add(zh ? "如果还需要这些内容，请在重启前运行 /upm revoke Demo 撤销卸载。"
                        : "If you still need this content, run /upm revoke Demo before restarting.");
            }
            assertThat(delivered).extracting(CommandMessageTestSupport::plain).containsExactlyElementsOf(expected);
            for (int index = 0; index < warnings.size(); index++) {
                assertThat(containsRed(delivered.get(index + 1))).isEqualTo(index < redWarnings);
            }
            delivered.clear();
            commands.pending(player);
            var plain = delivered.stream().map(CommandMessageTestSupport::plain).toList();
            // Header, item, requester, warning lines, footer. The footer replaces the standalone hint.
            assertThat(plain).hasSize(4 + warnings.size());
            assertThat(plain.get(1)).contains("Demo", "demo-1.jar");
            assertThat(plain.subList(3, plain.size() - 1)).containsExactlyElementsOf(warnings);
        }
    }

    static Stream<Arguments> warningCases() {
        String tag = "Note: the item categories Demo added will stop working.";
        String unknown = "This module may have added custom content; check before uninstalling it.";
        var mixed = new ModuleContent(List.of(new ModuleContentKind("tag", 1), new ModuleContentKind("datapack", 1),
                new ModuleContentKind("enchantment", 2)));
        return Stream.of(
                Arguments.of(Locale.ENGLISH, mixed, false, List.of(
                        "Warning: Demo added custom enchantments. After the restart that removes it, this enchantment is lost for good from every item that carries it (once the item's chunk is loaded and saved); items another plugin stored as raw bytes cannot be read while it is missing.",
                        "Warning: Demo brings its own data packs. After the restart that removes it, the recipes and advancements they bring disappear.", tag), 2),
                Arguments.of(Locale.CHINESE, mixed, false, List.of(
                        "警告：Demo 加过自定义附魔。重启卸载后，物品上的这条附魔会永久丢失（物品所在区块加载并保存时被去掉）；其他插件以字节形式存下的这类物品，在它缺席期间无法读取。",
                        "警告：Demo 自带数据包。重启卸载后，它带的配方与进度会消失。", "提示：Demo 加的物品分类将失效。"), 2),
                Arguments.of(Locale.ENGLISH, new ModuleContent(List.of(new ModuleContentKind("tag", 3))), false, List.of(tag), 0),
                Arguments.of(Locale.ENGLISH, ModuleContent.unknown(), false, List.of(unknown), 0),
                Arguments.of(Locale.ENGLISH, new ModuleContent(List.of(new ModuleContentKind("zz_future", 1))), false,
                        List.of("Note: Demo added custom content of another kind (zz_future)."), 0),
                Arguments.of(Locale.ENGLISH, ModuleContent.NONE, true, List.of(unknown), 0),
                Arguments.of(Locale.ENGLISH, ModuleContent.NONE, false, List.of(), 0));
    }

    @ParameterizedTest
    @MethodSource("localizedReplies")
    void repliesRenderExactlyInBothLocales(MessageKey key, Arg[] args, String en, String zh) {
        try (var text = new CommandMessageTestSupport("en")) {
            assertThat(CommandMessageTestSupport.plain(text.messages().render(key, Locale.ENGLISH, args))).isEqualTo(en);
            assertThat(CommandMessageTestSupport.plain(text.messages().render(key, Locale.CHINESE, args))).isEqualTo(zh);
        }
    }

    static Stream<Arguments> localizedReplies() {
        return Stream.of(
                Arguments.of(UpmKeys.INSTALL_QUEUED, new Arg[]{string("module", "Demo"), string("version", "2")},
                        "Added to the pending changes: Demo 2 will be installed at the next restart. Undo with /upm revoke Demo.",
                        "已加入待生效清单：Demo 2 将在下次重启时安装。可用 /upm revoke Demo 撤销。"),
                Arguments.of(UpmKeys.PENDING_TRUNCATED, new Arg[]{number("count", 1234)}, "… and 1234 more.", "……另有 1234 项。"),
                Arguments.of(UpmKeys.PENDING_EMPTY, new Arg[0], "No module changes are waiting for a restart.",
                        "没有等待重启生效的模块变更。"),
                Arguments.of(UpmKeys.REVOKE_DONE_INSTALL, new Arg[]{string("module", "Demo"), string("to", "2")},
                        "Undone: Demo 2 will not be installed.", "已撤销：不会安装 Demo 2。"),
                Arguments.of(UpmKeys.PENDING_FOOTER, new Arg[0],
                        "These take effect at the next restart. Undo one with /upm revoke <module name>.",
                        "以上变更在下次重启时生效。撤销其中一项：/upm revoke <模块名>。"),
                Arguments.of(UpmKeys.START_INSTALL_COMMITTED, new Arg[]{string("module", "Demo"), string("version", "2")},
                        "Module Demo 2 is installed: this start loaded it.", "模块 Demo 2 已安装：本次启动已加载。"));
    }

    private static boolean containsRed(Component component) {
        return NamedTextColor.RED.equals(component.color()) || component.children().stream().anyMatch(PendingChangesMessagesTest::containsRed);
    }
}
