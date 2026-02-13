package com.ultikits.plugins.kits.gui;

import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.plugins.kits.service.KitService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.EconomyUtils;
import mc.obliviate.inventory.Icon;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("KitBrowserGui")
@ExtendWith(MockitoExtension.class)
class KitBrowserGuiTest {

    @Mock
    private UltiToolsPlugin plugin;

    @Mock
    private KitService kitService;

    @Mock
    private Player player;

    @Mock
    private Economy economy;

    private static ItemFactory mockItemFactory;

    private KitBrowserGui gui;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUpClass() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            mockItemFactory = mock(ItemFactory.class);
            when(mockServer.getItemFactory()).thenReturn(mockItemFactory);
            Bukkit.setServer(mockServer);
        } else {
            mockItemFactory = mock(ItemFactory.class);
            when(Bukkit.getServer().getItemFactory()).thenReturn(mockItemFactory);
        }
        // Configure ItemFactory to return working ItemMeta mocks
        when(mockItemFactory.getItemMeta(any(Material.class))).thenAnswer(inv -> createMockItemMeta());
        when(mockItemFactory.isApplicable(any(), any(Material.class))).thenReturn(true);
        when(mockItemFactory.asMetaFor(any(), any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mockItemFactory.equals(any(), any())).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private static ItemMeta createMockItemMeta() {
        ItemMeta meta = mock(ItemMeta.class);
        final String[] displayName = {null};
        final List<String>[] lore = new List[]{null};

        lenient().doAnswer(inv -> {
            displayName[0] = inv.getArgument(0);
            return null;
        }).when(meta).setDisplayName(anyString());
        lenient().when(meta.getDisplayName()).thenAnswer(inv -> displayName[0]);
        lenient().doAnswer(inv -> {
            lore[0] = new ArrayList<>((List<String>) inv.getArgument(0));
            return null;
        }).when(meta).setLore(anyList());
        lenient().when(meta.getLore()).thenAnswer(inv -> lore[0] != null ? new ArrayList<>(lore[0]) : null);
        lenient().when(meta.hasDisplayName()).thenAnswer(inv -> displayName[0] != null);
        lenient().when(meta.hasLore()).thenAnswer(inv -> lore[0] != null && !lore[0].isEmpty());
        lenient().when(meta.clone()).thenReturn(meta);

        return meta;
    }

    @BeforeEach
    void setUp() {
        lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        gui = new KitBrowserGui(player, plugin, kitService, 0);

        EconomyUtils.reset();
    }

    @AfterEach
    void tearDown() {
        EconomyUtils.reset();
    }

    private void setEconomyAvailable(boolean available) throws Exception {
        if (available) {
            Field economyField = EconomyUtils.class.getDeclaredField("economy");
            economyField.setAccessible(true); // NOPMD
            economyField.set(null, economy);

            Field setupField = EconomyUtils.class.getDeclaredField("setupAttempted");
            setupField.setAccessible(true); // NOPMD
            setupField.set(null, true);
        } else {
            EconomyUtils.reset();
            // Mark setup as attempted but with no economy provider
            Field setupField = EconomyUtils.class.getDeclaredField("setupAttempted");
            setupField.setAccessible(true); // NOPMD
            setupField.set(null, true);
        }
    }

    private void resetDebounce() throws Exception {
        Field lastClickField = KitBrowserGui.class.getDeclaredField("lastClickTime");
        lastClickField.setAccessible(true); // NOPMD
        lastClickField.set(gui, 0L);
    }

    private KitDefinition createKit(String name, String displayName, String icon, double price, int level) {
        KitDefinition kit = new KitDefinition();
        kit.setName(name);
        kit.setDisplayName(displayName);
        kit.setIcon(icon);
        kit.setPrice(price);
        kit.setLevelRequired(level);
        kit.setDescription(new ArrayList<>());
        return kit;
    }

    // -----------------------------------------------------------------------
    // Constructor Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("stores player reference")
        void storesPlayer() throws Exception {
            Field playerField = KitBrowserGui.class.getDeclaredField("player");
            playerField.setAccessible(true); // NOPMD
            assertThat(playerField.get(gui)).isSameAs(player);
        }

        @Test
        @DisplayName("stores plugin reference")
        void storesPlugin() throws Exception {
            Field pluginField = KitBrowserGui.class.getDeclaredField("plugin");
            pluginField.setAccessible(true); // NOPMD
            assertThat(pluginField.get(gui)).isSameAs(plugin);
        }

        @Test
        @DisplayName("stores kitService reference")
        void storesKitService() throws Exception {
            Field serviceField = KitBrowserGui.class.getDeclaredField("kitService");
            serviceField.setAccessible(true); // NOPMD
            assertThat(serviceField.get(gui)).isSameAs(kitService);
        }

        @Test
        @DisplayName("default page is 0")
        void defaultPage() throws Exception {
            Field pageField = KitBrowserGui.class.getDeclaredField("page");
            pageField.setAccessible(true); // NOPMD
            assertThat(pageField.getInt(gui)).isEqualTo(0);
        }

        @Test
        @DisplayName("kitsPerPage defaults to 28")
        void kitsPerPageDefault() throws Exception {
            Field kppField = KitBrowserGui.class.getDeclaredField("kitsPerPage");
            kppField.setAccessible(true); // NOPMD
            assertThat(kppField.getInt(gui)).isEqualTo(28);
        }

        @Test
        @DisplayName("lastClickTime defaults to 0")
        void lastClickTimeDefault() throws Exception {
            Field lctField = KitBrowserGui.class.getDeclaredField("lastClickTime");
            lctField.setAccessible(true); // NOPMD
            assertThat(lctField.getLong(gui)).isEqualTo(0L);
        }

        @Test
        @DisplayName("can construct with different page number")
        void differentPage() throws Exception {
            KitBrowserGui gui2 = new KitBrowserGui(player, plugin, kitService, 3);
            Field pageField = KitBrowserGui.class.getDeclaredField("page");
            pageField.setAccessible(true); // NOPMD
            assertThat(pageField.getInt(gui2)).isEqualTo(3);
        }

        @Test
        @DisplayName("CLICK_COOLDOWN_MS is 200")
        void clickCooldownMs() throws Exception {
            Field cooldownField = KitBrowserGui.class.getDeclaredField("CLICK_COOLDOWN_MS");
            cooldownField.setAccessible(true); // NOPMD
            assertThat(cooldownField.getLong(null)).isEqualTo(200L);
        }
    }

    // -----------------------------------------------------------------------
    // BuildKitIcon Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BuildKitIcon Tests")
    class BuildKitIconTests {

        @Test
        @DisplayName("valid material returns non-null icon")
        void validMaterial() {
            KitDefinition kit = createKit("sword", "&cSword Kit", "DIAMOND_SWORD", 0, 0);
            lenient().when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);

            assertThat(icon).isNotNull();
            assertThat(icon.getItem()).isNotNull();
        }

        @Test
        @DisplayName("invalid material falls back without throwing")
        void fallbackMaterial() {
            KitDefinition kit = createKit("bad", "&cBad Kit", "NOT_A_MATERIAL", 0, 0);
            lenient().when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);

            // Falls back to CHEST when material name is invalid
            assertThat(icon).isNotNull();
            assertThat(icon.getItem()).isNotNull();
        }

        @Test
        @DisplayName("handles lowercase icon name without throwing")
        void lowercaseIcon() {
            KitDefinition kit = createKit("lower", "&fKit", "gold_ingot", 0, 0);
            lenient().when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            // toUpperCase converts "gold_ingot" to "GOLD_INGOT" before Material.valueOf
            Icon icon = gui.buildKitIcon(kit);

            assertThat(icon).isNotNull();
            assertThat(icon.getItem()).isNotNull();
        }

        @Test
        @DisplayName("sets display name with color codes translated")
        void displayName() {
            KitDefinition kit = createKit("vip", "&6VIP Kit", "CHEST", 0, 0);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            assertThat(meta.getDisplayName()).isNotNull();
        }

        @Test
        @DisplayName("free kit shows free label in lore")
        void freeKitLore() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("free", "&aFree Kit", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            assertThat(meta.getLore()).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore).anyMatch(l -> l.contains("免费"));
        }

        @Test
        @DisplayName("paid kit shows price in lore without economy")
        void paidKitLoreNoEconomy() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("paid", "&6Paid Kit", "CHEST", 100.0, 0);
            lenient().when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore).anyMatch(l -> l.contains("100"));
        }

        @Test
        @DisplayName("paid kit shows formatted price with economy")
        void paidKitLoreWithEconomy() throws Exception {
            setEconomyAvailable(true);
            when(economy.format(250.0)).thenReturn("$250.00");
            when(economy.has(any(org.bukkit.OfflinePlayer.class), anyDouble())).thenReturn(true);
            KitDefinition kit = createKit("premium", "&6Premium", "GOLD_INGOT", 250.0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore).anyMatch(l -> l.contains("$250.00"));
        }

        @Test
        @DisplayName("kit with level requirement shows level in lore")
        void levelRequirementInLore() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("elite", "&cElite", "CHEST", 0, 20);
            when(player.getLevel()).thenReturn(25);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore).anyMatch(l -> l.contains("等级要求") && l.contains("20"));
        }

        @Test
        @DisplayName("kit without level requirement omits level line")
        void noLevelRequirement() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("basic", "&fBasic", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore).noneMatch(l -> l.contains("等级要求"));
        }

        @Test
        @DisplayName("kit with description lines shows them in lore")
        void descriptionInLore() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("desc", "&aKit", "CHEST", 0, 0);
            kit.setDescription(Arrays.asList("&7Line one", "&eLine two"));
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("kit with empty description has no blank separator before price")
        void emptyDescriptionNoSeparator() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("noDesc", "&aKit", "CHEST", 0, 0);
            kit.setDescription(new ArrayList<>());
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            // First lore line should be price info, not blank
            assertThat(lore.get(0)).isNotEmpty();
        }

        @Test
        @DisplayName("status text appears in lore")
        void statusInLore() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("avail", "&aKit", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            Icon icon = gui.buildKitIcon(kit);
            ItemMeta meta = icon.getItem().getItemMeta();

            assertThat(meta).isNotNull();
            List<String> lore = meta.getLore();
            assertThat(lore).anyMatch(l -> l.contains("状态"));
        }
    }

    // -----------------------------------------------------------------------
    // GetStatusText Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("GetStatusText Tests")
    class GetStatusTextTests {

        @Test
        @DisplayName("level insufficient shows level status")
        void levelInsufficient() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("elite", "&cElite", "CHEST", 0, 20);
            when(player.getLevel()).thenReturn(10);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("等级不足");
        }

        @Test
        @DisplayName("level exactly meets requirement passes level check")
        void levelExactlyMet() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("exact", "&fExact", "CHEST", 0, 15);
            when(player.getLevel()).thenReturn(15);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
        }

        @Test
        @DisplayName("level exceeds requirement passes level check")
        void levelExceeded() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("over", "&fOver", "CHEST", 0, 5);
            when(player.getLevel()).thenReturn(50);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
        }

        @Test
        @DisplayName("balance insufficient shows balance status")
        void balanceInsufficient() throws Exception {
            setEconomyAvailable(true);
            when(economy.has((org.bukkit.OfflinePlayer) player, 100.0)).thenReturn(false);
            KitDefinition kit = createKit("paid", "&6Paid", "CHEST", 100.0, 0);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("余额不足");
        }

        @Test
        @DisplayName("economy unavailable with paid kit shows balance insufficient")
        void economyUnavailable() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("paid", "&6Paid", "CHEST", 100.0, 0);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("余额不足");
        }

        @Test
        @DisplayName("sufficient balance passes economy check")
        void sufficientBalance() throws Exception {
            setEconomyAvailable(true);
            when(economy.has((org.bukkit.OfflinePlayer) player, 50.0)).thenReturn(true);
            KitDefinition kit = createKit("afford", "&6Afford", "CHEST", 50.0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
        }

        @Test
        @DisplayName("already claimed (remaining < 0) shows claimed status")
        void alreadyClaimed() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("once", "&fOnce", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(-1L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("已领取");
        }

        @Test
        @DisplayName("large negative cooldown still shows already claimed")
        void largeNegativeCooldown() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("neg", "&fNeg", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(-999999L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("已领取");
        }

        @Test
        @DisplayName("on cooldown (remaining > 0) shows cooldown status")
        void onCooldown() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("daily", "&eDaily", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(60000L);
            when(kitService.formatCooldown(60000L)).thenReturn("1m");

            String result = gui.getStatusText(kit);

            assertThat(result).contains("冷却中").contains("1m");
        }

        @Test
        @DisplayName("available (remaining == 0) shows available status")
        void available() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("free", "&aFree", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
        }

        @Test
        @DisplayName("level check is skipped when no level requirement")
        void noLevelReq() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("basic", "&fBasic", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
            verify(player, never()).getLevel();
        }

        @Test
        @DisplayName("free kit skips economy check")
        void freeSkipsEconomy() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("free", "&aFree", "CHEST", 0, 0);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
        }

        @Test
        @DisplayName("with sufficient level and balance, shows available")
        void allRequirementsMet() throws Exception {
            setEconomyAvailable(true);
            when(economy.has((org.bukkit.OfflinePlayer) player, 50.0)).thenReturn(true);
            KitDefinition kit = createKit("full", "&6Full", "CHEST", 50.0, 10);
            when(player.getLevel()).thenReturn(15);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(0L);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("可领取");
        }

        @Test
        @DisplayName("level check takes priority over economy check")
        void levelPriorityOverEconomy() throws Exception {
            setEconomyAvailable(true);
            KitDefinition kit = createKit("both", "&6Both", "CHEST", 100.0, 20);
            when(player.getLevel()).thenReturn(5);

            String result = gui.getStatusText(kit);

            // Level check comes first, so shows level insufficient
            assertThat(result).contains("等级不足");
            // Economy should never be checked since level failed first
            verify(economy, never()).has(any(org.bukkit.OfflinePlayer.class), anyDouble());
        }

        @Test
        @DisplayName("economy check takes priority over cooldown check")
        void economyPriorityOverCooldown() throws Exception {
            setEconomyAvailable(true);
            when(economy.has((org.bukkit.OfflinePlayer) player, 100.0)).thenReturn(false);
            KitDefinition kit = createKit("prio", "&6Prio", "CHEST", 100.0, 0);

            String result = gui.getStatusText(kit);

            assertThat(result).contains("余额不足");
            // Cooldown should never be checked since economy failed first
            verify(kitService, never()).getRemainingCooldown(player, kit);
        }
    }

    // -----------------------------------------------------------------------
    // HandleKitClick Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("HandleKitClick Tests")
    class HandleKitClickTests {

        @Test
        @DisplayName("SUCCESS sends success message and closes inventory")
        void successClaimsAndCloses() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("starter", "&aStarter", "CHEST", 0, 0);
            when(kitService.claimKit(player, "starter")).thenReturn(KitService.ClaimResult.SUCCESS);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("成功领取礼包");
            verify(player).closeInventory();
        }

        @Test
        @DisplayName("SUCCESS with paid kit and economy shows deduction message")
        void successPaidKit() throws Exception {
            setEconomyAvailable(true);
            when(economy.format(50.0)).thenReturn("$50.00");
            KitDefinition kit = createKit("paid", "&6Paid", "CHEST", 50.0, 0);
            when(kitService.claimKit(player, "paid")).thenReturn(KitService.ClaimResult.SUCCESS);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(2)).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            assertThat(messages.get(0)).contains("成功领取礼包");
            assertThat(messages.get(1)).contains("已扣除").contains("$50.00");
        }

        @Test
        @DisplayName("SUCCESS with paid kit but economy unavailable skips deduction message")
        void successPaidKitNoEconomy() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("paid", "&6Paid", "CHEST", 50.0, 0);
            when(kitService.claimKit(player, "paid")).thenReturn(KitService.ClaimResult.SUCCESS);

            gui.handleKitClick(kit);

            // Only success message, no deduction message
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(1)).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("成功领取礼包");
        }

        @Test
        @DisplayName("SUCCESS with free kit does not show deduction message")
        void successFreeKit() throws Exception {
            setEconomyAvailable(true);
            KitDefinition kit = createKit("free", "&aFree", "CHEST", 0, 0);
            when(kitService.claimKit(player, "free")).thenReturn(KitService.ClaimResult.SUCCESS);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(1)).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("成功领取礼包");
        }

        @Test
        @DisplayName("SUCCESS message includes kit display name")
        void successIncludesDisplayName() throws Exception {
            setEconomyAvailable(false);
            KitDefinition kit = createKit("vip", "&6VIP Deluxe", "CHEST", 0, 0);
            when(kitService.claimKit(player, "vip")).thenReturn(KitService.ClaimResult.SUCCESS);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("VIP Deluxe");
        }

        @Test
        @DisplayName("debounce prevents rapid clicks")
        void debounce() throws Exception {
            KitDefinition kit = createKit("test", "&fTest", "CHEST", 0, 0);
            when(kitService.claimKit(player, "test")).thenReturn(KitService.ClaimResult.SUCCESS);

            // First click succeeds
            gui.handleKitClick(kit);
            verify(kitService, times(1)).claimKit(player, "test");

            // Immediate second click is debounced
            gui.handleKitClick(kit);
            verify(kitService, times(1)).claimKit(player, "test");
        }

        @Test
        @DisplayName("click works again after debounce reset")
        void clickAfterDebounceReset() throws Exception {
            KitDefinition kit = createKit("test", "&fTest", "CHEST", 0, 0);
            when(kitService.claimKit(player, "test")).thenReturn(KitService.ClaimResult.SUCCESS);

            // First click
            gui.handleKitClick(kit);
            verify(kitService, times(1)).claimKit(player, "test");

            // Reset debounce via reflection
            resetDebounce();

            // Second click succeeds after reset
            gui.handleKitClick(kit);
            verify(kitService, times(2)).claimKit(player, "test");
        }

        @Test
        @DisplayName("debounce does not send any message on blocked click")
        void debounceNoMessage() throws Exception {
            KitDefinition kit = createKit("test", "&fTest", "CHEST", 0, 0);
            when(kitService.claimKit(player, "test")).thenReturn(KitService.ClaimResult.SUCCESS);

            gui.handleKitClick(kit);
            // Clear first interaction
            clearInvocations(player);

            // Debounced click sends nothing
            gui.handleKitClick(kit);
            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("NOT_FOUND sends not found message with kit name")
        void notFound() {
            KitDefinition kit = createKit("gone", "&fGone", "CHEST", 0, 0);
            when(kitService.claimKit(player, "gone")).thenReturn(KitService.ClaimResult.NOT_FOUND);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("不存在").contains("gone");
        }

        @Test
        @DisplayName("NOT_FOUND does not close inventory")
        void notFoundNoClose() {
            KitDefinition kit = createKit("gone", "&fGone", "CHEST", 0, 0);
            when(kitService.claimKit(player, "gone")).thenReturn(KitService.ClaimResult.NOT_FOUND);

            gui.handleKitClick(kit);

            verify(player, never()).closeInventory();
        }

        @Test
        @DisplayName("NO_PERMISSION sends permission message")
        void noPermission() {
            KitDefinition kit = createKit("vip", "&6VIP", "CHEST", 0, 0);
            when(kitService.claimKit(player, "vip")).thenReturn(KitService.ClaimResult.NO_PERMISSION);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("没有权限");
        }

        @Test
        @DisplayName("INSUFFICIENT_LEVEL sends level required message with level")
        void insufficientLevel() {
            KitDefinition kit = createKit("elite", "&cElite", "CHEST", 0, 30);
            when(kitService.claimKit(player, "elite")).thenReturn(KitService.ClaimResult.INSUFFICIENT_LEVEL);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("等级不足").contains("30");
        }

        @Test
        @DisplayName("INSUFFICIENT_FUNDS sends balance message")
        void insufficientFunds() {
            KitDefinition kit = createKit("premium", "&6Premium", "CHEST", 500.0, 0);
            when(kitService.claimKit(player, "premium")).thenReturn(KitService.ClaimResult.INSUFFICIENT_FUNDS);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("余额不足");
        }

        @Test
        @DisplayName("ALREADY_CLAIMED sends already claimed message")
        void alreadyClaimed() {
            KitDefinition kit = createKit("once", "&fOnce", "CHEST", 0, 0);
            when(kitService.claimKit(player, "once")).thenReturn(KitService.ClaimResult.ALREADY_CLAIMED);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已经领取过");
        }

        @Test
        @DisplayName("ON_COOLDOWN sends cooldown message with formatted time")
        void onCooldown() {
            KitDefinition kit = createKit("daily", "&eDaily", "CHEST", 0, 0);
            when(kitService.claimKit(player, "daily")).thenReturn(KitService.ClaimResult.ON_COOLDOWN);
            when(kitService.getRemainingCooldown(player, kit)).thenReturn(7200000L);
            when(kitService.formatCooldown(7200000L)).thenReturn("2h 0m");

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("冷却中").contains("2h 0m");
        }

        @Test
        @DisplayName("INVENTORY_FULL sends inventory full message")
        void inventoryFull() {
            KitDefinition kit = createKit("big", "&fBig", "CHEST", 0, 0);
            when(kitService.claimKit(player, "big")).thenReturn(KitService.ClaimResult.INVENTORY_FULL);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("背包空间不足");
        }

        @Test
        @DisplayName("EMPTY_KIT sends empty kit message")
        void emptyKit() {
            KitDefinition kit = createKit("empty", "&fEmpty", "CHEST", 0, 0);
            when(kitService.claimKit(player, "empty")).thenReturn(KitService.ClaimResult.EMPTY_KIT);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("礼包内容为空");
        }

        @Test
        @DisplayName("ERROR sends generic error message")
        void error() {
            KitDefinition kit = createKit("broken", "&fBroken", "CHEST", 0, 0);
            when(kitService.claimKit(player, "broken")).thenReturn(KitService.ClaimResult.ERROR);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("领取礼包时发生错误");
        }

        @Test
        @DisplayName("non-SUCCESS results do not close inventory")
        void nonSuccessNoClose() {
            KitDefinition kit = createKit("fail", "&fFail", "CHEST", 0, 0);
            when(kitService.claimKit(player, "fail")).thenReturn(KitService.ClaimResult.INSUFFICIENT_FUNDS);

            gui.handleKitClick(kit);

            verify(player, never()).closeInventory();
        }

        @Test
        @DisplayName("all non-SUCCESS results only send one message")
        void nonSuccessSingleMessage() {
            KitDefinition kit = createKit("once", "&fOnce", "CHEST", 0, 0);
            when(kitService.claimKit(player, "once")).thenReturn(KitService.ClaimResult.ALREADY_CLAIMED);

            gui.handleKitClick(kit);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(1)).sendMessage(captor.capture());
        }
    }
}
