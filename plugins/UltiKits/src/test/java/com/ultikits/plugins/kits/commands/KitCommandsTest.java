package com.ultikits.plugins.kits.commands;

import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.plugins.kits.service.KitService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("KitCommands")
@ExtendWith(MockitoExtension.class)
class KitCommandsTest {

    @Mock
    private UltiToolsPlugin plugin;

    @Mock
    private KitService kitService;

    @Mock
    private Player player;

    @Mock
    private CommandSender consoleSender;

    private KitCommands kitCommands;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        kitCommands = new KitCommands(plugin, kitService);
    }

    private KitDefinition createKit(String name, String displayName, double price, int level) {
        KitDefinition kit = new KitDefinition();
        kit.setName(name);
        kit.setDisplayName(displayName);
        kit.setPrice(price);
        kit.setLevelRequired(level);
        return kit;
    }

    @Nested
    @DisplayName("Claim Tests")
    class ClaimTests {

        @Test
        @DisplayName("SUCCESS sends success message with kit name")
        void successSendsMessage() {
            when(kitService.claimKit(player, "starter")).thenReturn(KitService.ClaimResult.SUCCESS);

            kitCommands.onClaim(player, "starter");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("成功领取礼包").contains("starter");
        }

        @Test
        @DisplayName("NOT_FOUND sends not found message")
        void notFoundSendsMessage() {
            when(kitService.claimKit(player, "missing")).thenReturn(KitService.ClaimResult.NOT_FOUND);

            kitCommands.onClaim(player, "missing");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("不存在").contains("missing");
        }

        @Test
        @DisplayName("NO_PERMISSION sends permission denied message")
        void noPermissionSendsMessage() {
            when(kitService.claimKit(player, "vip")).thenReturn(KitService.ClaimResult.NO_PERMISSION);

            kitCommands.onClaim(player, "vip");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有权限");
        }

        @Test
        @DisplayName("INSUFFICIENT_LEVEL sends level required message")
        void insufficientLevelSendsMessage() {
            KitDefinition kit = createKit("elite", "&cElite", 0, 30);
            when(kitService.claimKit(player, "elite")).thenReturn(KitService.ClaimResult.INSUFFICIENT_LEVEL);
            when(kitService.getKit("elite")).thenReturn(kit);

            kitCommands.onClaim(player, "elite");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("等级不足").contains("30");
        }

        @Test
        @DisplayName("INSUFFICIENT_LEVEL with null kit shows 0 level")
        void insufficientLevelNullKit() {
            when(kitService.claimKit(player, "gone")).thenReturn(KitService.ClaimResult.INSUFFICIENT_LEVEL);
            when(kitService.getKit("gone")).thenReturn(null);

            kitCommands.onClaim(player, "gone");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("等级不足").contains("0");
        }

        @Test
        @DisplayName("INSUFFICIENT_FUNDS sends balance required message")
        void insufficientFundsSendsMessage() {
            KitDefinition kit = createKit("premium", "&6Premium", 500.0, 0);
            when(kitService.claimKit(player, "premium")).thenReturn(KitService.ClaimResult.INSUFFICIENT_FUNDS);
            when(kitService.getKit("premium")).thenReturn(kit);

            kitCommands.onClaim(player, "premium");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("余额不足").contains("500");
        }

        @Test
        @DisplayName("INSUFFICIENT_FUNDS with null kit shows question mark")
        void insufficientFundsNullKit() {
            when(kitService.claimKit(player, "gone")).thenReturn(KitService.ClaimResult.INSUFFICIENT_FUNDS);
            when(kitService.getKit("gone")).thenReturn(null);

            kitCommands.onClaim(player, "gone");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("余额不足").contains("?");
        }

        @Test
        @DisplayName("ALREADY_CLAIMED sends already claimed message")
        void alreadyClaimedSendsMessage() {
            when(kitService.claimKit(player, "one-time")).thenReturn(KitService.ClaimResult.ALREADY_CLAIMED);

            kitCommands.onClaim(player, "one-time");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已经领取过");
        }

        @Test
        @DisplayName("ON_COOLDOWN sends cooldown message with remaining time")
        void onCooldownSendsMessage() {
            KitDefinition kit = createKit("daily", "&eDaily", 0, 0);
            when(kitService.claimKit(player, "daily")).thenReturn(KitService.ClaimResult.ON_COOLDOWN);
            when(kitService.getKit("daily")).thenReturn(kit);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(3600000L);
            when(kitService.formatCooldown(3600000L)).thenReturn("1h 0m");

            kitCommands.onClaim(player, "daily");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("冷却中").contains("1h 0m");
        }

        @Test
        @DisplayName("INVENTORY_FULL sends inventory full message")
        void inventoryFullSendsMessage() {
            when(kitService.claimKit(player, "big")).thenReturn(KitService.ClaimResult.INVENTORY_FULL);

            kitCommands.onClaim(player, "big");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("背包空间不足");
        }

        @Test
        @DisplayName("EMPTY_KIT sends empty kit message")
        void emptyKitSendsMessage() {
            when(kitService.claimKit(player, "empty")).thenReturn(KitService.ClaimResult.EMPTY_KIT);

            kitCommands.onClaim(player, "empty");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("礼包内容为空");
        }

        @Test
        @DisplayName("ERROR sends generic error message")
        void errorSendsMessage() {
            when(kitService.claimKit(player, "broken")).thenReturn(KitService.ClaimResult.ERROR);

            kitCommands.onClaim(player, "broken");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("领取礼包时发生错误");
        }
    }

    @Nested
    @DisplayName("List Tests")
    class ListTests {

        @Test
        @DisplayName("empty list sends no kits message for player")
        void emptyListPlayer() {
            when(kitService.getAvailableKits(player)).thenReturn(Collections.emptyList());

            kitCommands.onList(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有可用的礼包");
        }

        @Test
        @DisplayName("empty list sends no kits message for console")
        void emptyListConsole() {
            when(kitService.getKitNames()).thenReturn(Collections.emptyList());

            kitCommands.onList(consoleSender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有可用的礼包");
        }

        @Test
        @DisplayName("player sees available kits with header")
        void playerSeesKits() {
            KitDefinition kit1 = createKit("starter", "&aStarter Kit", 0, 0);
            KitDefinition kit2 = createKit("vip", "&6VIP Kit", 100.0, 0);
            when(kitService.getAvailableKits(player)).thenReturn(Arrays.asList(kit1, kit2));

            kitCommands.onList(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(3)).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            assertThat(messages.get(0)).contains("礼包列表");
            assertThat(messages.get(1)).contains("starter");
            assertThat(messages.get(2)).contains("vip").contains("$100");
        }

        @Test
        @DisplayName("free kit does not show price")
        void freeKitNoPrice() {
            KitDefinition kit = createKit("free", "&fFree Kit", 0, 0);
            when(kitService.getAvailableKits(player)).thenReturn(Collections.singletonList(kit));

            kitCommands.onList(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(2)).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            assertThat(messages.get(1)).doesNotContain("$");
        }

        @Test
        @DisplayName("console gets all kits via getKitNames")
        void consoleGetsAllKits() {
            KitDefinition kit = createKit("admin", "&cAdmin Kit", 0, 0);
            when(kitService.getKitNames()).thenReturn(Collections.singletonList("admin"));
            when(kitService.getKit("admin")).thenReturn(kit);

            kitCommands.onList(consoleSender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender, times(2)).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            assertThat(messages.get(0)).contains("礼包列表");
            assertThat(messages.get(1)).contains("admin");
        }
    }

    @Nested
    @DisplayName("Edit Tests")
    class EditTests {

        @Test
        @DisplayName("no admin permission sends denied message")
        void noPermission() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(false);

            kitCommands.onEdit(player, "starter");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有权限");
            verify(kitService, never()).getKit(anyString());
        }

        @Test
        @DisplayName("nonexistent kit sends not found message")
        void kitNotFound() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.getKit("missing")).thenReturn(null);

            kitCommands.onEdit(player, "missing");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("不存在").contains("missing");
        }
    }

    @Nested
    @DisplayName("Create Tests")
    class CreateTests {

        @Test
        @DisplayName("no admin permission sends denied message")
        void noPermission() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(false);

            kitCommands.onCreate(player, "newkit");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有权限");
            verify(kitService, never()).createKit(any(), anyString());
        }

        @Test
        @DisplayName("SUCCESS sends created message")
        void success() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.createKit(player, "newkit")).thenReturn(KitService.CreateResult.SUCCESS);

            kitCommands.onCreate(player, "newkit");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已创建礼包").contains("newkit");
        }

        @Test
        @DisplayName("ALREADY_EXISTS sends exists message")
        void alreadyExists() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.createKit(player, "dupe")).thenReturn(KitService.CreateResult.ALREADY_EXISTS);

            kitCommands.onCreate(player, "dupe");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("礼包名已存在").contains("dupe");
        }

        @Test
        @DisplayName("INVALID_NAME sends invalid name message")
        void invalidName() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.createKit(player, "!bad")).thenReturn(KitService.CreateResult.INVALID_NAME);

            kitCommands.onCreate(player, "!bad");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("无效的礼包名");
        }

        @Test
        @DisplayName("EMPTY_INVENTORY sends empty inventory message")
        void emptyInventory() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.createKit(player, "empty")).thenReturn(KitService.CreateResult.EMPTY_INVENTORY);

            kitCommands.onCreate(player, "empty");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("物品栏为空");
        }

        @Test
        @DisplayName("ERROR sends generic error message")
        void error() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.createKit(player, "broken")).thenReturn(KitService.CreateResult.ERROR);

            kitCommands.onCreate(player, "broken");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("领取礼包时发生错误");
        }
    }

    @Nested
    @DisplayName("Delete Tests")
    class DeleteTests {

        @Test
        @DisplayName("no admin permission sends denied message")
        void noPermission() {
            when(consoleSender.hasPermission("ultikits.kits.admin")).thenReturn(false);

            kitCommands.onDelete(consoleSender, "starter");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有权限");
            verify(kitService, never()).deleteKit(anyString());
        }

        @Test
        @DisplayName("successful delete sends deleted message")
        void successfulDelete() {
            when(consoleSender.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.deleteKit("old")).thenReturn(true);

            kitCommands.onDelete(consoleSender, "old");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已删除礼包").contains("old");
        }

        @Test
        @DisplayName("nonexistent kit sends not found message")
        void nonexistent() {
            when(consoleSender.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.deleteKit("missing")).thenReturn(false);

            kitCommands.onDelete(consoleSender, "missing");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("不存在").contains("missing");
        }

        @Test
        @DisplayName("player can also delete kits with permission")
        void playerDelete() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.deleteKit("test")).thenReturn(true);

            kitCommands.onDelete(player, "test");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已删除礼包");
        }
    }

    @Nested
    @DisplayName("Reload Tests")
    class ReloadTests {

        @Test
        @DisplayName("no admin permission sends denied message")
        void noPermission() {
            when(consoleSender.hasPermission("ultikits.kits.admin")).thenReturn(false);

            kitCommands.onReload(consoleSender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有权限");
            verify(kitService, never()).reload();
        }

        @Test
        @DisplayName("reload sends count message")
        void reloadSendsCount() {
            when(consoleSender.hasPermission("ultikits.kits.admin")).thenReturn(true);
            List<KitDefinition> kits = Arrays.asList(
                    createKit("a", "A", 0, 0),
                    createKit("b", "B", 0, 0),
                    createKit("c", "C", 0, 0));
            when(kitService.getAllKits()).thenReturn(kits);

            kitCommands.onReload(consoleSender);

            verify(kitService).reload();
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已重新加载").contains("3");
        }

        @Test
        @DisplayName("player can reload with admin permission")
        void playerReload() {
            when(player.hasPermission("ultikits.kits.admin")).thenReturn(true);
            when(kitService.getAllKits()).thenReturn(Collections.emptyList());

            kitCommands.onReload(player);

            verify(kitService).reload();
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已重新加载").contains("0");
        }
    }

    @Nested
    @DisplayName("Tab Completion Tests")
    class TabCompletionTests {

        @Test
        @DisplayName("suggestKitNames returns kit names from service")
        @SuppressWarnings("unchecked")
        void suggestKitNamesReturnsNames() throws Exception {
            List<String> names = Arrays.asList("starter", "vip", "daily");
            when(kitService.getKitNames()).thenReturn(names);

            Method method = KitCommands.class.getDeclaredMethod("suggestKitNames", Player.class);
            method.setAccessible(true); // NOPMD
            List<String> result = (List<String>) method.invoke(kitCommands, player);

            assertThat(result).containsExactlyElementsOf(names);
        }

        @Test
        @DisplayName("suggestKitNames returns empty list when no kits")
        @SuppressWarnings("unchecked")
        void suggestKitNamesEmpty() throws Exception {
            when(kitService.getKitNames()).thenReturn(Collections.emptyList());

            Method method = KitCommands.class.getDeclaredMethod("suggestKitNames", Player.class);
            method.setAccessible(true); // NOPMD
            List<String> result = (List<String>) method.invoke(kitCommands, player);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Help Tests")
    class HelpTests {

        @Test
        @DisplayName("handleHelp sends multiple help lines")
        void helpSendsLines() {
            kitCommands.handleHelp(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, atLeast(7)).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            assertThat(messages.get(0)).contains("UltiKits");
            assertThat(messages).anyMatch(m -> m.contains("/kits claim"));
            assertThat(messages).anyMatch(m -> m.contains("/kits list"));
            assertThat(messages).anyMatch(m -> m.contains("/kits edit"));
            assertThat(messages).anyMatch(m -> m.contains("/kits create"));
            assertThat(messages).anyMatch(m -> m.contains("/kits delete"));
            assertThat(messages).anyMatch(m -> m.contains("/kits reload"));
        }

        @Test
        @DisplayName("handleHelp works for console sender")
        void helpConsole() {
            kitCommands.handleHelp(consoleSender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(consoleSender, atLeast(7)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).isNotEmpty();
        }
    }
}
