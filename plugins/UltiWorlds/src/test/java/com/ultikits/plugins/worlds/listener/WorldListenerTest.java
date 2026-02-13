package com.ultikits.plugins.worlds.listener;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.worlds.UltiWorldsTestHelper;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.service.InventoryIsolationService;
import com.ultikits.plugins.worlds.service.WorldService;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WorldListener event handling.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("WorldListener Tests")
class WorldListenerTest {

    private WorldListener listener;
    private WorldService mockWorldService;
    private InventoryIsolationService mockInventoryService;
    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws Exception {
        UltiWorldsTestHelper.setUp();
        mockPlugin = UltiWorldsTestHelper.getMockPlugin();

        listener = new WorldListener();
        mockWorldService = mock(WorldService.class);
        mockInventoryService = mock(InventoryIsolationService.class);

        UltiWorldsTestHelper.setField(listener, "worldService", mockWorldService);
        UltiWorldsTestHelper.setField(listener, "inventoryService", mockInventoryService);
        UltiWorldsTestHelper.setField(listener, "plugin", mockPlugin);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiWorldsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Block Protection Events")
    class BlockProtectionEvents {

        @Test
        @DisplayName("onBlockBreak should cancel when protection enabled")
        void onBlockBreakProtected() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.protection")).thenReturn(false);

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setProtectBreak(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            BlockBreakEvent event = new BlockBreakEvent(block, player);

            listener.onBlockBreak(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("onBlockBreak should allow when protection disabled")
        void onBlockBreakNotProtected() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setProtectBreak(false);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            BlockBreakEvent event = new BlockBreakEvent(block, player);

            listener.onBlockBreak(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("onBlockBreak should allow with bypass permission")
        void onBlockBreakBypass() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.protection")).thenReturn(true);

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setProtectBreak(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            BlockBreakEvent event = new BlockBreakEvent(block, player);

            listener.onBlockBreak(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("onBlockPlace should cancel when protection enabled")
        void onBlockPlaceProtected() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.protection")).thenReturn(false);

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setProtectPlace(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            BlockPlaceEvent event = mock(BlockPlaceEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getBlock()).thenReturn(block);
            when(event.isCancelled()).thenReturn(false);

            listener.onBlockPlace(event);

            verify(event).setCancelled(true);
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("onPlayerInteract should cancel when protection enabled")
        void onPlayerInteractProtected() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.protection")).thenReturn(false);

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setProtectInteract(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getClickedBlock()).thenReturn(block);
            when(event.isCancelled()).thenReturn(false);

            listener.onPlayerInteract(event);

            verify(event).setCancelled(true);
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("onEntityExplode should clear blocks when protection enabled")
        void onEntityExplodeProtected() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Location location = new Location(world, 0, 64, 0);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setProtectExplosion(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            EntityExplodeEvent event = mock(EntityExplodeEvent.class);
            when(event.getLocation()).thenReturn(location);
            List<Block> blockList = new ArrayList<>();
            blockList.add(mock(Block.class));
            when(event.blockList()).thenReturn(blockList);

            listener.onEntityExplode(event);

            assertThat(blockList).isEmpty();
        }
    }

    @Nested
    @DisplayName("PVP Control")
    class PVPControl {

        @Test
        @DisplayName("onPlayerDamage should cancel when PVP disabled")
        void onPlayerDamagePvpDisabled() {
            Player victim = UltiWorldsTestHelper.createMockPlayer("Victim", UUID.randomUUID());
            Player attacker = UltiWorldsTestHelper.createMockPlayer("Attacker", UUID.randomUUID());

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            when(victim.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setPvpEnabled(false);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getEntity()).thenReturn(victim);
            when(event.getDamager()).thenReturn(attacker);
            when(event.isCancelled()).thenReturn(false);

            listener.onPlayerDamage(event);

            verify(event).setCancelled(true);
            verify(attacker).sendMessage(anyString());
        }

        @Test
        @DisplayName("onPlayerDamage should allow when PVP enabled")
        void onPlayerDamagePvpEnabled() {
            Player victim = UltiWorldsTestHelper.createMockPlayer("Victim", UUID.randomUUID());
            Player attacker = UltiWorldsTestHelper.createMockPlayer("Attacker", UUID.randomUUID());

            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            when(victim.getWorld()).thenReturn(world);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setPvpEnabled(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getEntity()).thenReturn(victim);
            when(event.getDamager()).thenReturn(attacker);
            when(event.isCancelled()).thenReturn(false);

            listener.onPlayerDamage(event);

            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Nested
    @DisplayName("Access Control")
    class AccessControl {

        @Test
        @DisplayName("onPlayerTeleport should cancel when cannot enter blocked world")
        void onPlayerTeleportBlocked() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(toWorld.getName()).thenReturn("blocked_world");

            Location from = mock(Location.class);
            Location to = mock(Location.class);
            when(from.getWorld()).thenReturn(fromWorld);
            when(to.getWorld()).thenReturn(toWorld);

            when(mockWorldService.canEnterWorld(player, "blocked_world")).thenReturn(false);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
            settings.setBlocked(true);
            when(mockWorldService.getOrCreateSettings("blocked_world")).thenReturn(settings);

            PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, to);

            listener.onPlayerTeleport(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("onPlayerTeleport should allow when can enter world")
        void onPlayerTeleportAllowed() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(toWorld.getName()).thenReturn("world");

            Location from = mock(Location.class);
            Location to = mock(Location.class);
            when(from.getWorld()).thenReturn(fromWorld);
            when(to.getWorld()).thenReturn(toWorld);

            when(mockWorldService.canEnterWorld(player, "world")).thenReturn(true);

            PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, to);

            listener.onPlayerTeleport(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("onPlayerTeleport should skip same world teleports")
        void onPlayerTeleportSameWorld() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World world = mock(World.class);
            Location from = mock(Location.class);
            Location to = mock(Location.class);
            when(from.getWorld()).thenReturn(world);
            when(to.getWorld()).thenReturn(world);

            PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, to);

            listener.onPlayerTeleport(event);

            assertThat(event.isCancelled()).isFalse();
            verify(mockWorldService, never()).canEnterWorld(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Creature Spawn Control")
    class CreatureSpawnControl {

        @Test
        @DisplayName("onCreatureSpawn should cancel monsters when disabled")
        void onCreatureSpawnMonstersDisabled() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Location location = new Location(world, 0, 64, 0);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setMonstersEnabled(false);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            CreatureSpawnEvent event = mock(CreatureSpawnEvent.class);
            when(event.getLocation()).thenReturn(location);
            when(event.getEntityType()).thenReturn(EntityType.ZOMBIE);

            listener.onCreatureSpawn(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onCreatureSpawn should cancel animals when disabled")
        void onCreatureSpawnAnimalsDisabled() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Location location = new Location(world, 0, 64, 0);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setAnimalsEnabled(false);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            CreatureSpawnEvent event = mock(CreatureSpawnEvent.class);
            when(event.getLocation()).thenReturn(location);
            when(event.getEntityType()).thenReturn(EntityType.COW);

            listener.onCreatureSpawn(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onCreatureSpawn should allow when enabled")
        void onCreatureSpawnEnabled() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Location location = new Location(world, 0, 64, 0);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setMonstersEnabled(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            CreatureSpawnEvent event = mock(CreatureSpawnEvent.class);
            when(event.getLocation()).thenReturn(location);
            when(event.getEntityType()).thenReturn(EntityType.ZOMBIE);

            listener.onCreatureSpawn(event);

            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Nested
    @DisplayName("Weather Control")
    class WeatherControl {

        @Test
        @DisplayName("onWeatherChange should cancel when weather disabled")
        void onWeatherChangeDisabled() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setWeatherEnabled(false);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            WeatherChangeEvent event = mock(WeatherChangeEvent.class);
            when(event.getWorld()).thenReturn(world);
            when(event.toWeatherState()).thenReturn(true);

            listener.onWeatherChange(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onWeatherChange should allow when weather enabled")
        void onWeatherChangeEnabled() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setWeatherEnabled(true);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            WeatherChangeEvent event = mock(WeatherChangeEvent.class);
            when(event.getWorld()).thenReturn(world);
            when(event.toWeatherState()).thenReturn(true);

            listener.onWeatherChange(event);

            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Nested
    @DisplayName("World Change Handling")
    class WorldChangeHandling {

        @Test
        @DisplayName("onPlayerChangeWorld should apply PVP settings")
        void onPlayerChangeWorldPvp() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(toWorld.getName()).thenReturn("pvp_world");

            when(player.getWorld()).thenReturn(toWorld);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("pvp_world");
            settings.setPvpEnabled(true);
            when(mockWorldService.getOrCreateSettings("pvp_world")).thenReturn(settings);

            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, fromWorld);

            listener.onPlayerChangeWorld(event);

            verify(toWorld).setPVP(true);
        }

        @Test
        @DisplayName("onPlayerChangeWorld should trigger inventory isolation")
        void onPlayerChangeWorldInventory() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(toWorld.getName()).thenReturn("world");

            when(player.getWorld()).thenReturn(toWorld);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, fromWorld);

            listener.onPlayerChangeWorld(event);

            verify(mockInventoryService).onWorldChange(player, fromWorld, toWorld);
        }

        @Test
        @DisplayName("onPlayerChangeWorld should handle null inventory service")
        void onPlayerChangeWorldNullInventory() throws Exception {
            UltiWorldsTestHelper.setField(listener, "inventoryService", null);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(toWorld.getName()).thenReturn("world");

            when(player.getWorld()).thenReturn(toWorld);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, fromWorld);

            listener.onPlayerChangeWorld(event);

            // Should not throw NPE
            verify(toWorld).setPVP(anyBoolean());
        }
    }
}
