package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for DeathPunishListener.
 * <p>
 * 测试死亡惩罚监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("DeathPunishListener Tests (Mockito)")
class DeathPunishListenerMockitoTest {

    private DeathPunishListener listener;
    private EssentialsConfig config;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new DeathPunishListener();
        config = new EssentialsConfig();
        EssentialsTestHelper.setField(listener, "config", config);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    private Player createDeathPlayer(String worldName) {
        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        World world = EssentialsTestHelper.createMockWorld(worldName);
        lenient().when(player.getWorld()).thenReturn(world);
        lenient().when(player.hasPermission("ultiessentials.deathpunish.bypass")).thenReturn(false);
        lenient().when(player.getTotalExperience()).thenReturn(1000);
        return player;
    }

    @Nested
    @DisplayName("Skip Conditions")
    class SkipConditionTests {

        @Test
        @DisplayName("Should skip when death punish is disabled")
        void shouldSkipWhenDisabled() {
            config.setDeathPunishEnabled(false);

            Player player = createDeathPlayer("world");
            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "Steve died");

            listener.onPlayerDeath(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should skip when player is in whitelisted world")
        void shouldSkipInWhitelistedWorld() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Arrays.asList("world_creative", "build_world"));

            Player player = createDeathPlayer("world_creative");
            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "Steve died");

            listener.onPlayerDeath(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should skip when player has bypass permission")
        void shouldSkipWithBypassPermission() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Collections.emptyList());

            Player player = createDeathPlayer("world");
            when(player.hasPermission("ultiessentials.deathpunish.bypass")).thenReturn(true);
            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "Steve died");

            listener.onPlayerDeath(event);

            verify(player, never()).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Experience Punish")
    class ExperiencePunishTests {

        @Test
        @DisplayName("Should reduce dropped experience when exp punish enabled")
        void shouldReduceDroppedExp() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Collections.emptyList());
            config.setDeathPunishMoneyEnabled(false);
            config.setDeathPunishItemDropEnabled(false);
            config.setDeathPunishExpEnabled(true);
            config.setDeathPunishExpPercent(50.0);
            config.setDeathPunishCommandEnabled(false);

            Player player = createDeathPlayer("world");
            when(player.getTotalExperience()).thenReturn(1000);

            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 100, "Steve died");

            listener.onPlayerDeath(event);

            // exp loss = 1000 * 50/100 = 500
            // dropped exp = max(0, 100 - 500) = 0
            assertThat(event.getDroppedExp()).isEqualTo(0);
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not go below 0 dropped experience")
        void shouldNotGoBelowZero() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Collections.emptyList());
            config.setDeathPunishMoneyEnabled(false);
            config.setDeathPunishItemDropEnabled(false);
            config.setDeathPunishExpEnabled(true);
            config.setDeathPunishExpPercent(100.0);
            config.setDeathPunishCommandEnabled(false);

            Player player = createDeathPlayer("world");
            when(player.getTotalExperience()).thenReturn(1000);

            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 50, "Steve died");

            listener.onPlayerDeath(event);

            assertThat(event.getDroppedExp()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Command Punish")
    class CommandPunishTests {

        @Test
        @DisplayName("Should execute punishment commands")
        void shouldExecuteCommands() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Collections.emptyList());
            config.setDeathPunishMoneyEnabled(false);
            config.setDeathPunishItemDropEnabled(false);
            config.setDeathPunishExpEnabled(false);
            config.setDeathPunishCommandEnabled(true);
            config.setDeathPunishCommands(Arrays.asList("say {PLAYER} died", "tell {PLAYER} punished"));

            Player player = createDeathPlayer("world");
            ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
            when(EssentialsTestHelper.getMockServer().getConsoleSender()).thenReturn(consoleSender);

            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "Steve died");

            listener.onPlayerDeath(event);

            verify(EssentialsTestHelper.getMockServer(), times(2)).dispatchCommand(
                    eq(consoleSender), anyString());
        }
    }

    @Nested
    @DisplayName("Money Punish")
    class MoneyPunishTests {

        @Test
        @DisplayName("Should skip money punish when economy not available")
        void shouldSkipWhenEconomyNotAvailable() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Collections.emptyList());
            config.setDeathPunishMoneyEnabled(true);
            config.setDeathPunishItemDropEnabled(false);
            config.setDeathPunishExpEnabled(false);
            config.setDeathPunishCommandEnabled(false);

            // EconomyUtils.isAvailable() returns false by default (no Vault)

            Player player = createDeathPlayer("world");
            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "Steve died");

            // Should not throw, just skip money loss
            listener.onPlayerDeath(event);
        }
    }

    @Nested
    @DisplayName("All Punish Disabled")
    class AllDisabledTests {

        @Test
        @DisplayName("Should not send message when all punishments are disabled")
        void shouldNotSendMessageWhenAllDisabled() {
            config.setDeathPunishEnabled(true);
            config.setDeathPunishWorldWhitelist(Collections.emptyList());
            config.setDeathPunishMoneyEnabled(false);
            config.setDeathPunishItemDropEnabled(false);
            config.setDeathPunishExpEnabled(false);
            config.setDeathPunishCommandEnabled(false);

            Player player = createDeathPlayer("world");
            List<ItemStack> drops = new ArrayList<>();
            PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "Steve died");

            listener.onPlayerDeath(event);

            // The initial StringBuilder has "死亡惩罚: " which is 10 chars with color codes
            // When no punishments are applied, message.length() should be <= 10
            // so sendMessage should NOT be called
            verify(player, never()).sendMessage(anyString());
        }
    }
}
