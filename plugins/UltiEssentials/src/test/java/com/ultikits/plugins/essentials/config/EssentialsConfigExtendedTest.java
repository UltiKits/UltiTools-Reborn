package com.ultikits.plugins.essentials.config;

import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extended unit tests for EssentialsConfig - covering fields not tested in EssentialsConfigTest.
 * <p>
 * 扩展配置测试，覆盖 EssentialsConfigTest 未测试的字段。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("EssentialsConfig Extended Tests")
class EssentialsConfigExtendedTest {

    private EssentialsConfig config;

    @BeforeEach
    void setUp() {
        config = new EssentialsConfig();
    }

    @Nested
    @DisplayName("DeathPunish Defaults")
    class DeathPunishDefaults {

        @Test
        @DisplayName("Should have death punish disabled by default")
        void shouldHaveDeathPunishDisabled() {
            assertThat(config.isDeathPunishEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have money punish disabled by default")
        void shouldHaveMoneyPunishDisabled() {
            assertThat(config.isDeathPunishMoneyEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have correct money punish defaults")
        void shouldHaveMoneyPunishDefaults() {
            assertThat(config.getDeathPunishMoneyPercent()).isEqualTo(10.0);
            assertThat(config.getDeathPunishMoneyMax()).isEqualTo(1000.0);
        }

        @Test
        @DisplayName("Should have item drop disabled by default")
        void shouldHaveItemDropDisabled() {
            assertThat(config.isDeathPunishItemDropEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have correct item drop defaults")
        void shouldHaveItemDropDefaults() {
            assertThat(config.getDeathPunishItemDropChance()).isEqualTo(50.0);
            assertThat(config.isDeathPunishKeepOtherItems()).isTrue();
        }

        @Test
        @DisplayName("Should have item whitelist")
        void shouldHaveItemWhitelist() {
            assertThat(config.getDeathPunishItemWhitelist()).isNotNull();
            assertThat(config.getDeathPunishItemWhitelist()).contains("DIAMOND_SWORD");
            assertThat(config.getDeathPunishItemWhitelist()).contains("DIAMOND_PICKAXE");
        }

        @Test
        @DisplayName("Should have exp punish disabled by default")
        void shouldHaveExpPunishDisabled() {
            assertThat(config.isDeathPunishExpEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have correct exp punish defaults")
        void shouldHaveExpPunishDefaults() {
            assertThat(config.getDeathPunishExpPercent()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("Should have command punish disabled by default")
        void shouldHaveCommandPunishDisabled() {
            assertThat(config.isDeathPunishCommandEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have death punish commands")
        void shouldHaveDeathPunishCommands() {
            assertThat(config.getDeathPunishCommands()).isNotNull();
            assertThat(config.getDeathPunishCommands()).isNotEmpty();
        }

        @Test
        @DisplayName("Should have death punish world whitelist")
        void shouldHaveWorldWhitelist() {
            assertThat(config.getDeathPunishWorldWhitelist()).isNotNull();
            assertThat(config.getDeathPunishWorldWhitelist()).contains("world_creative");
        }
    }

    @Nested
    @DisplayName("DeathPunish Setter Tests")
    class DeathPunishSetterTests {

        @Test
        @DisplayName("Should update death punish enabled")
        void shouldUpdateDeathPunishEnabled() {
            config.setDeathPunishEnabled(true);
            assertThat(config.isDeathPunishEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update money punish settings")
        void shouldUpdateMoneyPunishSettings() {
            config.setDeathPunishMoneyEnabled(true);
            config.setDeathPunishMoneyPercent(25.0);
            config.setDeathPunishMoneyMax(5000.0);

            assertThat(config.isDeathPunishMoneyEnabled()).isTrue();
            assertThat(config.getDeathPunishMoneyPercent()).isEqualTo(25.0);
            assertThat(config.getDeathPunishMoneyMax()).isEqualTo(5000.0);
        }

        @Test
        @DisplayName("Should update item drop settings")
        void shouldUpdateItemDropSettings() {
            config.setDeathPunishItemDropEnabled(true);
            config.setDeathPunishItemDropChance(75.0);
            config.setDeathPunishKeepOtherItems(false);

            assertThat(config.isDeathPunishItemDropEnabled()).isTrue();
            assertThat(config.getDeathPunishItemDropChance()).isEqualTo(75.0);
            assertThat(config.isDeathPunishKeepOtherItems()).isFalse();
        }

        @Test
        @DisplayName("Should update item whitelist")
        void shouldUpdateItemWhitelist() {
            List<String> newWhitelist = Arrays.asList("NETHERITE_SWORD", "ELYTRA");
            config.setDeathPunishItemWhitelist(newWhitelist);
            assertThat(config.getDeathPunishItemWhitelist()).containsExactly("NETHERITE_SWORD", "ELYTRA");
        }

        @Test
        @DisplayName("Should update exp punish settings")
        void shouldUpdateExpPunishSettings() {
            config.setDeathPunishExpEnabled(true);
            config.setDeathPunishExpPercent(50.0);

            assertThat(config.isDeathPunishExpEnabled()).isTrue();
            assertThat(config.getDeathPunishExpPercent()).isEqualTo(50.0);
        }
    }

    @Nested
    @DisplayName("Chat Format Defaults")
    class ChatFormatDefaults {

        @Test
        @DisplayName("Should have chat format enabled by default")
        void shouldHaveChatFormatEnabled() {
            assertThat(config.isChatFormatEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default chat format")
        void shouldHaveDefaultChatFormat() {
            assertThat(config.getChatFormat()).isNotNull();
            assertThat(config.getChatFormat()).contains("{player}");
            assertThat(config.getChatFormat()).contains("{message}");
        }

        @Test
        @DisplayName("Should update chat format via setter")
        void shouldUpdateChatFormat() {
            config.setChatFormat("&e{player}: {message}");
            assertThat(config.getChatFormat()).isEqualTo("&e{player}: {message}");
        }
    }

    @Nested
    @DisplayName("Join/Quit Message Defaults")
    class JoinQuitDefaults {

        @Test
        @DisplayName("Should have join message enabled by default")
        void shouldHaveJoinMessageEnabled() {
            assertThat(config.isJoinMessageEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have quit message enabled by default")
        void shouldHaveQuitMessageEnabled() {
            assertThat(config.isQuitMessageEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default join message format")
        void shouldHaveDefaultJoinFormat() {
            assertThat(config.getJoinMessageFormat()).isNotNull();
            assertThat(config.getJoinMessageFormat()).contains("%player_name%");
        }

        @Test
        @DisplayName("Should have default quit message format")
        void shouldHaveDefaultQuitFormat() {
            assertThat(config.getQuitMessageFormat()).isNotNull();
            assertThat(config.getQuitMessageFormat()).contains("%player_name%");
        }

        @Test
        @DisplayName("Should have welcome message lines")
        void shouldHaveWelcomeMessageLines() {
            assertThat(config.getWelcomeMessageLines()).isNotNull();
            assertThat(config.getWelcomeMessageLines()).isNotEmpty();
        }

        @Test
        @DisplayName("Should update join/quit settings")
        void shouldUpdateJoinQuitSettings() {
            config.setJoinMessageEnabled(false);
            config.setQuitMessageEnabled(false);
            config.setJoinMessageFormat("Custom join");
            config.setQuitMessageFormat("Custom quit");

            assertThat(config.isJoinMessageEnabled()).isFalse();
            assertThat(config.isQuitMessageEnabled()).isFalse();
            assertThat(config.getJoinMessageFormat()).isEqualTo("Custom join");
            assertThat(config.getQuitMessageFormat()).isEqualTo("Custom quit");
        }
    }

    @Nested
    @DisplayName("ChestLock Defaults")
    class ChestLockDefaults {

        @Test
        @DisplayName("Should have chest lock enabled by default")
        void shouldHaveChestLockEnabled() {
            assertThat(config.isChestLockEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have admin bypass enabled by default")
        void shouldHaveAdminBypassEnabled() {
            assertThat(config.isChestLockAdminBypass()).isTrue();
        }

        @Test
        @DisplayName("Should update chest lock settings")
        void shouldUpdateChestLockSettings() {
            config.setChestLockEnabled(false);
            config.setChestLockAdminBypass(false);
            assertThat(config.isChestLockEnabled()).isFalse();
            assertThat(config.isChestLockAdminBypass()).isFalse();
        }
    }

    @Nested
    @DisplayName("CommandAlias Defaults")
    class CommandAliasDefaults {

        @Test
        @DisplayName("Should have command alias enabled by default")
        void shouldHaveCommandAliasEnabled() {
            assertThat(config.isCommandAliasEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default command aliases")
        void shouldHaveDefaultAliases() {
            assertThat(config.getCommandAliases()).isNotNull();
            assertThat(config.getCommandAliases()).isNotEmpty();
            assertThat(config.getCommandAliases()).containsKey("gmc");
            assertThat(config.getCommandAliases()).containsKey("gms");
        }

        @Test
        @DisplayName("Should update command aliases")
        void shouldUpdateCommandAliases() {
            Map<String, String> newAliases = new HashMap<>();
            newAliases.put("tp", "teleport");
            config.setCommandAliases(newAliases);

            assertThat(config.getCommandAliases()).containsKey("tp");
            assertThat(config.getCommandAliases()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Announcement Extended Setter Tests")
    class AnnouncementSetterTests {

        @Test
        @DisplayName("Should update chat announcement settings")
        void shouldUpdateChatAnnouncement() {
            config.setAnnouncementChatEnabled(false);
            config.setAnnouncementChatInterval(600);
            config.setAnnouncementChatPrefix("&b[Info] ");

            assertThat(config.isAnnouncementChatEnabled()).isFalse();
            assertThat(config.getAnnouncementChatInterval()).isEqualTo(600);
            assertThat(config.getAnnouncementChatPrefix()).isEqualTo("&b[Info] ");
        }

        @Test
        @DisplayName("Should update boss bar announcement settings")
        void shouldUpdateBossBarAnnouncement() {
            config.setAnnouncementBossBarEnabled(true);
            config.setAnnouncementBossBarInterval(120);
            config.setAnnouncementBossBarDuration(15);
            config.setAnnouncementBossBarColor("RED");

            assertThat(config.isAnnouncementBossBarEnabled()).isTrue();
            assertThat(config.getAnnouncementBossBarInterval()).isEqualTo(120);
            assertThat(config.getAnnouncementBossBarDuration()).isEqualTo(15);
            assertThat(config.getAnnouncementBossBarColor()).isEqualTo("RED");
        }

        @Test
        @DisplayName("Should update title announcement settings")
        void shouldUpdateTitleAnnouncement() {
            config.setAnnouncementTitleEnabled(true);
            config.setAnnouncementTitleInterval(300);
            config.setAnnouncementTitleFadeIn(20);
            config.setAnnouncementTitleStay(100);
            config.setAnnouncementTitleFadeOut(30);

            assertThat(config.isAnnouncementTitleEnabled()).isTrue();
            assertThat(config.getAnnouncementTitleInterval()).isEqualTo(300);
            assertThat(config.getAnnouncementTitleFadeIn()).isEqualTo(20);
            assertThat(config.getAnnouncementTitleStay()).isEqualTo(100);
            assertThat(config.getAnnouncementTitleFadeOut()).isEqualTo(30);
        }
    }
}
