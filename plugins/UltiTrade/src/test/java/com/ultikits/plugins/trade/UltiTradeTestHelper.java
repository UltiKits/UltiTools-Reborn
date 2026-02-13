package com.ultikits.plugins.trade;

import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;

import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework dependencies.
 * <p>
 * UltiTools is a {@code final class extends JavaPlugin} — it cannot be mocked.
 * This helper mocks only UltiTrade (extends abstract UltiToolsPlugin) and
 * avoids any code paths that call {@code UltiTools.getInstance()}.
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
public final class UltiTradeTestHelper {

    private UltiTradeTestHelper() {}

    private static UltiTrade mockPlugin;
    private static PluginLogger mockLogger;

    /**
     * Set up UltiTrade mock. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Set up Bukkit server mock
        setupBukkitServer();

        // Mock UltiTrade (abstract UltiToolsPlugin — mockable)
        mockPlugin = mock(UltiTrade.class);

        // Mock logger
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);

        // Mock i18n to return the key as-is
        lenient().when(mockPlugin.i18n(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock getDataOperator
        lenient().when(mockPlugin.getDataOperator(any()))
                .thenReturn(mock(DataOperator.class));
    }

    /**
     * Set up a minimal Bukkit server mock so that Bukkit static methods work.
     */
    private static void setupBukkitServer() throws Exception {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        ItemFactory itemFactory = mock(ItemFactory.class);

        PluginManager pluginManager = mock(PluginManager.class);

        ServicesManager servicesManager = mock(ServicesManager.class);
        BossBar mockBossBar = mock(BossBar.class);

        BukkitTask mockBukkitTask = mock(BukkitTask.class);
        lenient().when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mockBukkitTask);
        lenient().when(scheduler.runTaskTimerAsynchronously(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mockBukkitTask);
        lenient().when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                .thenReturn(mockBukkitTask);

        lenient().when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(server.getItemFactory()).thenReturn(itemFactory);
        lenient().when(server.getPluginManager()).thenReturn(pluginManager);
        lenient().when(server.getServicesManager()).thenReturn(servicesManager);
        lenient().when(server.getLogger()).thenReturn(Logger.getLogger("MockBukkit"));
        lenient().when(server.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
                .thenReturn(mockBossBar);
        lenient().when(server.createInventory(any(), anyInt(), anyString()))
                .thenReturn(mock(Inventory.class));
        lenient().when(itemFactory.isApplicable(any(), any(ItemStack.class))).thenReturn(true);

        // Return a mock ItemMeta so that ItemStack.getItemMeta() is non-null
        ItemMeta mockMeta = mock(ItemMeta.class);
        lenient().when(itemFactory.getItemMeta(any())).thenReturn(mockMeta);

        setStaticField(Bukkit.class, "server", server);
    }

    /**
     * Clean up state.
     */
    public static void tearDown() throws Exception {
        setStaticField(Bukkit.class, "server", null);
    }

    public static UltiTrade getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    /**
     * Create a default TradeConfig mock with all features enabled.
     */
    public static TradeConfig createDefaultConfig() {
        TradeConfig config = mock(TradeConfig.class);
        lenient().when(config.getRequestTimeout()).thenReturn(30);
        lenient().when(config.getTradeTimeout()).thenReturn(120);
        lenient().when(config.getMaxDistance()).thenReturn(50);
        lenient().when(config.isAllowCrossWorld()).thenReturn(false);
        lenient().when(config.isEnableMoneyTrade()).thenReturn(true);
        lenient().when(config.isEnableExpTrade()).thenReturn(true);
        lenient().when(config.isEnableShiftClick()).thenReturn(true);
        lenient().when(config.getTradeTax()).thenReturn(0.0);
        lenient().when(config.getExpTaxRate()).thenReturn(0.0);
        lenient().when(config.getConfirmThreshold()).thenReturn(10000.0);
        lenient().when(config.isEnableTradeLog()).thenReturn(true);
        lenient().when(config.getLogRetentionDays()).thenReturn(30);
        lenient().when(config.getCleanupIntervalHours()).thenReturn(24);
        lenient().when(config.isEnableSounds()).thenReturn(true);
        lenient().when(config.isEnableParticles()).thenReturn(true);
        lenient().when(config.isEnableBossbar()).thenReturn(true);
        lenient().when(config.isEnableClickableButtons()).thenReturn(true);
        lenient().when(config.getGuiTitle()).thenReturn("&6与 {PLAYER} 交易");
        lenient().when(config.getRequestSentMessage()).thenReturn("&a已向 &f{PLAYER} &a发送交易请求！");
        lenient().when(config.getRequestReceivedMessage()).thenReturn("&e{PLAYER} &f请求与你交易！");
        lenient().when(config.getRequestTimeoutMessage()).thenReturn("&c交易请求已超时！");
        lenient().when(config.getTradeCompleteMessage()).thenReturn("&a交易完成！");
        lenient().when(config.getTradeCancelledMessage()).thenReturn("&c交易已取消！");
        lenient().when(config.getTradeDisabledMessage()).thenReturn("&c对方已关闭交易功能！");
        lenient().when(config.getPlayerBlockedMessage()).thenReturn("&c对方已将你加入黑名单！");
        return config;
    }

    /**
     * Create a mock Player with basic properties.
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getLevel()).thenReturn(30);
        lenient().when(player.getExp()).thenReturn(0.5f);
        lenient().when(player.hasPermission(anyString())).thenReturn(true);
        lenient().when(player.isOnline()).thenReturn(true);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);

        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(player.getInventory()).thenReturn(inventory);

        // Mock Player.Spigot for sendMessage(TextComponent)
        Player.Spigot spigot = mock(Player.Spigot.class);
        lenient().when(player.spigot()).thenReturn(spigot);

        return player;
    }

    /**
     * Create a mock Economy instance.
     */
    public static Economy createMockEconomy() {
        Economy economy = mock(Economy.class);
        EconomyResponse successResponse = new EconomyResponse(0, 0,
                EconomyResponse.ResponseType.SUCCESS, "");
        lenient().when(economy.getBalance(any(Player.class))).thenReturn(1000.0);
        lenient().when(economy.has(any(Player.class), anyDouble())).thenReturn(true);
        lenient().when(economy.withdrawPlayer(any(Player.class), anyDouble())).thenReturn(successResponse);
        lenient().when(economy.depositPlayer(any(Player.class), anyDouble())).thenReturn(successResponse);
        return economy;
    }

    // --- Reflection ---

    public static void setStaticField(Class<?> clazz, String fieldName, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
