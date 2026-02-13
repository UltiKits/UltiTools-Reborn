package com.ultikits.plugins.login.listener;

import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("LoginProtectionListener Tests")
class LoginProtectionListenerTest {

    private LoginProtectionListener listener;
    private LoginService loginService;
    private LoginConfig config;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        loginService = mock(LoginService.class);

        // Mock config on loginService
        config = UltiLoginTestHelper.createDefaultConfig();
        lenient().when(loginService.getConfig()).thenReturn(config);

        // Mock Bukkit.server before LoginProtectionListener constructor (it calls Bukkit.getPluginManager())
        try {
            java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            if (serverField.get(null) == null) {
                Server mockServer = mock(Server.class);
                org.bukkit.plugin.PluginManager mockPm = mock(org.bukkit.plugin.PluginManager.class);
                lenient().when(mockServer.getPluginManager()).thenReturn(mockPm);
                lenient().when(mockPm.getPlugin("UltiTools")).thenReturn(mock(org.bukkit.plugin.Plugin.class));
                serverField.set(null, mockServer);
            }
        } catch (Exception ignored) {
            // Server may already be set
        }

        listener = new LoginProtectionListener(UltiLoginTestHelper.getMockPlugin(), loginService);

        playerUuid = UUID.randomUUID();
        player = UltiLoginTestHelper.createMockPlayer("TestPlayer", playerUuid);

        // Mock player.getServer() for PlayerCommandPreprocessEvent
        Server playerServer = mock(Server.class);
        lenient().when(player.getServer()).thenReturn(playerServer);
        lenient().when(playerServer.getOnlinePlayers()).thenReturn(java.util.Collections.emptyList());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
    }

    @Nested
    @DisplayName("onPlayerJoin")
    class OnPlayerJoin {

        @Test
        @DisplayName("Should call loginService.onPlayerJoin")
        void callsOnPlayerJoin() {
            PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");

            listener.onPlayerJoin(event);

            verify(loginService).onPlayerJoin(player);
        }
    }

    @Nested
    @DisplayName("onPlayerQuit")
    class OnPlayerQuit {

        @Test
        @DisplayName("Should call loginService.onPlayerQuit")
        void callsOnPlayerQuit() {
            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");

            listener.onPlayerQuit(event);

            verify(loginService).onPlayerQuit(player);
        }
    }

    @Nested
    @DisplayName("onPlayerMove")
    class OnPlayerMove {

        private org.bukkit.World mockWorld;

        @BeforeEach
        void setUpWorld() {
            mockWorld = mock(org.bukkit.World.class);
        }

        private org.bukkit.Location createMockLocation(int x, int y, int z) {
            org.bukkit.Location loc = mock(org.bukkit.Location.class);
            when(loc.getBlockX()).thenReturn(x);
            when(loc.getBlockY()).thenReturn(y);
            when(loc.getBlockZ()).thenReturn(z);
            when(loc.getWorld()).thenReturn(mockWorld);
            return loc;
        }

        @Test
        @DisplayName("Should allow movement when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            org.bukkit.Location from = createMockLocation(0, 64, 0);
            org.bukkit.Location to = createMockLocation(1, 64, 0);

            PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

            listener.onPlayerMove(event);

            // Event should not cancel location change
            assertThat(event.getTo()).isEqualTo(to);
        }

        @Test
        @DisplayName("Should block movement when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.Location from = createMockLocation(0, 64, 0);
            org.bukkit.Location to = createMockLocation(1, 64, 0);

            PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

            listener.onPlayerMove(event);

            // Event should reset location to from
            assertThat(event.getTo()).isEqualTo(from);
        }

        @Test
        @DisplayName("Should allow looking around (no block change)")
        void allowLooking() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.Location from = createMockLocation(0, 64, 0);
            org.bukkit.Location to = createMockLocation(0, 64, 0);

            PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

            listener.onPlayerMove(event);

            // Should not reset location
            assertThat(event.getTo()).isEqualTo(to);
        }
    }

    @Nested
    @DisplayName("onPlayerChat")
    class OnPlayerChat {

        @Test
        @DisplayName("Should allow chat when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "message", java.util.Collections.emptySet());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should block chat when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "message", java.util.Collections.emptySet());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
        }
    }

    @Nested
    @DisplayName("onPlayerCommand")
    class OnPlayerCommand {

        @Test
        @DisplayName("Should allow all commands when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/help");

            listener.onPlayerCommand(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should allow login commands when not logged in")
        void allowLoginCommands() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isCommandAllowed("/login password")).thenReturn(true);

            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/login password");

            listener.onPlayerCommand(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should block other commands when not logged in")
        void blockOtherCommands() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isCommandAllowed("/help")).thenReturn(false);

            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/help");

            listener.onPlayerCommand(event);

            assertThat(event.isCancelled()).isTrue();
        }
    }

    @Nested
    @DisplayName("onBlockBreak")
    class OnBlockBreak {

        @Test
        @DisplayName("Should allow when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            BlockBreakEvent event = mock(BlockBreakEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onBlockBreak(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should block when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            BlockBreakEvent event = mock(BlockBreakEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onBlockBreak(event);

            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onBlockPlace")
    class OnBlockPlace {

        @Test
        @DisplayName("Should allow when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            BlockPlaceEvent event = mock(BlockPlaceEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onBlockPlace(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should block when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            BlockPlaceEvent event = mock(BlockPlaceEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onBlockPlace(event);

            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerInteract")
    class OnPlayerInteract {

        @Test
        @DisplayName("Should block when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerInteract(event);

            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerDropItem")
    class OnPlayerDropItem {

        @Test
        @DisplayName("Should block when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerDropItem(event);

            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerDamage")
    class OnPlayerDamage {

        @Test
        @DisplayName("Should block damage when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(player);

            listener.onPlayerDamage(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow damage when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(player);

            listener.onPlayerDamage(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onInventoryClick")
    class OnInventoryClick {

        @Test
        @DisplayName("Should allow login GUI interaction")
        void allowLoginGui() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("请输入密码");

            listener.onInventoryClick(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should block other inventory interaction")
        void blockOtherInventory() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("Chest");

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onInventoryOpen")
    class OnInventoryOpen {

        @Test
        @DisplayName("Should allow login GUI opening")
        void allowLoginGui() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryOpenEvent event = mock(org.bukkit.event.inventory.InventoryOpenEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("注册账号");

            listener.onInventoryOpen(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should block other inventory opening")
        void blockOtherInventory() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryOpenEvent event = mock(org.bukkit.event.inventory.InventoryOpenEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("Chest");

            listener.onInventoryOpen(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow inventory when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            org.bukkit.event.inventory.InventoryOpenEvent event = mock(org.bukkit.event.inventory.InventoryOpenEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("Chest");

            listener.onInventoryOpen(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should not cancel for non-Player entity")
        void nonPlayerEntity() {
            org.bukkit.event.inventory.InventoryOpenEvent event = mock(org.bukkit.event.inventory.InventoryOpenEvent.class);
            org.bukkit.entity.Entity nonPlayer = mock(org.bukkit.entity.Entity.class);
            when(event.getPlayer()).thenReturn(mock(org.bukkit.entity.HumanEntity.class));

            // HumanEntity that is not a Player
            org.bukkit.entity.HumanEntity humanEntity = mock(org.bukkit.entity.HumanEntity.class);
            when(event.getPlayer()).thenReturn(humanEntity);

            listener.onInventoryOpen(event);

            // Should not interact since the cast (Player) won't apply
            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow login GUI with title containing login keyword")
        void allowLoginKeyword() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryOpenEvent event = mock(org.bukkit.event.inventory.InventoryOpenEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("登录界面");

            listener.onInventoryOpen(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerInteractEntity")
    class OnPlayerInteractEntity {

        @Test
        @DisplayName("Should block interact entity when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.player.PlayerInteractEntityEvent event = mock(org.bukkit.event.player.PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerInteractEntity(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow interact entity when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            org.bukkit.event.player.PlayerInteractEntityEvent event = mock(org.bukkit.event.player.PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerInteractEntity(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerPickupItem")
    class OnPlayerPickupItem {

        @Test
        @DisplayName("Should block pickup when player not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.entity.EntityPickupItemEvent event = mock(org.bukkit.event.entity.EntityPickupItemEvent.class);
            when(event.getEntity()).thenReturn(player);

            listener.onPlayerPickupItem(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow pickup when player logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            org.bukkit.event.entity.EntityPickupItemEvent event = mock(org.bukkit.event.entity.EntityPickupItemEvent.class);
            when(event.getEntity()).thenReturn(player);

            listener.onPlayerPickupItem(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should ignore non-player entity pickup")
        void nonPlayerPickup() {
            org.bukkit.event.entity.EntityPickupItemEvent event = mock(org.bukkit.event.entity.EntityPickupItemEvent.class);
            org.bukkit.entity.LivingEntity nonPlayer = mock(org.bukkit.entity.LivingEntity.class);
            when(event.getEntity()).thenReturn(nonPlayer);

            listener.onPlayerPickupItem(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerDamageEntity")
    class OnPlayerDamageEntity {

        @Test
        @DisplayName("Should block damage by player when not logged in")
        void blockWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.entity.EntityDamageByEntityEvent event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(player);

            listener.onPlayerDamageEntity(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow damage by player when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            org.bukkit.event.entity.EntityDamageByEntityEvent event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(player);

            listener.onPlayerDamageEntity(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should ignore damage by non-player entity")
        void nonPlayerDamager() {
            org.bukkit.event.entity.EntityDamageByEntityEvent event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
            org.bukkit.entity.Entity nonPlayer = mock(org.bukkit.entity.Entity.class);
            when(event.getDamager()).thenReturn(nonPlayer);

            listener.onPlayerDamageEntity(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerDamage (extended)")
    class OnPlayerDamageExtended {

        @Test
        @DisplayName("Should ignore damage to non-player entity")
        void nonPlayerEntity() {
            org.bukkit.event.entity.EntityDamageEvent event = mock(org.bukkit.event.entity.EntityDamageEvent.class);
            org.bukkit.entity.Entity nonPlayer = mock(org.bukkit.entity.Entity.class);
            when(event.getEntity()).thenReturn(nonPlayer);

            listener.onPlayerDamage(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onInventoryClick (extended)")
    class OnInventoryClickExtended {

        @Test
        @DisplayName("Should allow inventory click when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("Chest");

            listener.onInventoryClick(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should ignore inventory click by non-player")
        void nonPlayerClick() {
            org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            org.bukkit.entity.HumanEntity humanEntity = mock(org.bukkit.entity.HumanEntity.class);
            when(event.getWhoClicked()).thenReturn(humanEntity);

            listener.onInventoryClick(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow register GUI interaction")
        void allowRegisterGui() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("注册");

            listener.onInventoryClick(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should allow login GUI with login keyword")
        void allowLoginKeyword() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(view);
            when(view.getTitle()).thenReturn("登录");

            listener.onInventoryClick(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerChat (extended)")
    class OnPlayerChatExtended {

        @Test
        @DisplayName("Should send prompt when chat blocked (non-GUI mode, registered)")
        void sendPromptRegistered() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(config.isGuiModeEnabled()).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "message", java.util.Collections.emptySet());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should send register prompt when chat blocked (non-GUI mode, not registered)")
        void sendPromptNotRegistered() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(config.isGuiModeEnabled()).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(false);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "message", java.util.Collections.emptySet());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("onPlayerInteract (extended)")
    class OnPlayerInteractExtended {

        @Test
        @DisplayName("Should allow interact when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerDropItem (extended)")
    class OnPlayerDropItemExtended {

        @Test
        @DisplayName("Should allow drop when logged in")
        void allowWhenLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerDropItem(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("onPlayerMove (extended)")
    class OnPlayerMoveExtended {

        private org.bukkit.World mockWorld;

        @BeforeEach
        void setUpWorld() {
            mockWorld = mock(org.bukkit.World.class);
        }

        private org.bukkit.Location createMockLocation(int x, int y, int z) {
            org.bukkit.Location loc = mock(org.bukkit.Location.class);
            when(loc.getBlockX()).thenReturn(x);
            when(loc.getBlockY()).thenReturn(y);
            when(loc.getBlockZ()).thenReturn(z);
            when(loc.getWorld()).thenReturn(mockWorld);
            return loc;
        }

        @Test
        @DisplayName("Should block Y movement when not logged in")
        void blockYMovement() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.Location from = createMockLocation(0, 64, 0);
            org.bukkit.Location to = createMockLocation(0, 65, 0);

            PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

            listener.onPlayerMove(event);

            assertThat(event.getTo()).isEqualTo(from);
        }

        @Test
        @DisplayName("Should block Z movement when not logged in")
        void blockZMovement() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            org.bukkit.Location from = createMockLocation(0, 64, 0);
            org.bukkit.Location to = createMockLocation(0, 64, 1);

            PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

            listener.onPlayerMove(event);

            assertThat(event.getTo()).isEqualTo(from);
        }
    }

    @Nested
    @DisplayName("onPlayerCommand (extended)")
    class OnPlayerCommandExtended {

        @Test
        @DisplayName("Should send login prompt when command blocked (non-GUI mode, registered)")
        void sendLoginPromptRegistered() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isCommandAllowed("/help")).thenReturn(false);
            when(config.isGuiModeEnabled()).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/help");

            listener.onPlayerCommand(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player).sendMessage(anyString());
        }
    }
}
