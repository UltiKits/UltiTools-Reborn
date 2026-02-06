package com.ultikits.plugins.essentials.commands;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.ultitools.annotations.*;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BaseEssentialsCommand.
 * <p>
 * Tests the helper methods provided by the base command class.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("BaseEssentialsCommand Tests")
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class BaseEssentialsCommandTest {

    private ServerMock server;
    private PlayerMock player;
    private TestCommand testCommand;

    @BeforeAll
    static void setUpMockBukkit() {
        MockBukkitHelper.ensureCleanState();
    }

    @AfterAll
    static void tearDownMockBukkit() {
        MockBukkitHelper.safeUnmock();
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("TestPlayer");
        testCommand = new TestCommand();
    }

    @Nested
    @DisplayName("checkFeatureEnabled Tests")
    class CheckFeatureEnabledTests {

        @Test
        @DisplayName("Should return true when feature is enabled")
        void shouldReturnTrueWhenEnabled() {
            boolean result = testCommand.testCheckFeatureEnabled(true, player);
            
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false and send message when feature is disabled")
        void shouldReturnFalseAndSendMessageWhenDisabled() {
            boolean result = testCommand.testCheckFeatureEnabled(false, player);
            
            assertThat(result).isFalse();
            // Message should be sent to player
            assertThat(player.nextMessage()).contains("feature_disabled");
        }
    }

    @Nested
    @DisplayName("sendTeleportResultMessage Tests")
    class SendTeleportResultMessageTests {

        @Test
        @DisplayName("Should send success message")
        void shouldSendSuccessMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.SUCCESS);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_success");
        }

        @Test
        @DisplayName("Should send warmup started message")
        void shouldSendWarmupMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.WARMUP_STARTED);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_warmup_started");
        }

        @Test
        @DisplayName("Should send not found message")
        void shouldSendNotFoundMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.NOT_FOUND);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_target_not_found");
        }

        @Test
        @DisplayName("Should send world not found message")
        void shouldSendWorldNotFoundMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.WORLD_NOT_FOUND);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_world_not_found");
        }

        @Test
        @DisplayName("Should send no permission message")
        void shouldSendNoPermissionMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.NO_PERMISSION);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_no_permission");
        }

        @Test
        @DisplayName("Should send already teleporting message")
        void shouldSendAlreadyTeleportingMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.ALREADY_TELEPORTING);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_already_in_progress");
        }

        @Test
        @DisplayName("Should send disabled message")
        void shouldSendDisabledMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.DISABLED);
            
            String message = player.nextMessage();
            assertThat(message).contains("feature_disabled");
        }

        @Test
        @DisplayName("Should send cancelled message")
        void shouldSendCancelledMessage() {
            testCommand.testSendTeleportResultMessage(player, TeleportResult.CANCELLED);
            
            String message = player.nextMessage();
            assertThat(message).contains("teleport_cancelled");
        }
    }

    /**
     * Test subclass that exposes protected methods for testing.
     */
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    @CmdExecutor(alias = {"test"}, permission = "test.test", description = "Test command")
    static class TestCommand extends BaseEssentialsCommand {

        /**
         * Override i18n to return keys directly for testing.
         */
        @Override
        protected String i18n(String key) {
            return key;
        }

        /**
         * Expose checkFeatureEnabled for testing.
         */
        public boolean testCheckFeatureEnabled(boolean enabled, CommandSender sender) {
            return checkFeatureEnabled(enabled, sender);
        }

        /**
         * Expose sendTeleportResultMessage for testing.
         */
        public void testSendTeleportResultMessage(PlayerMock player, TeleportResult result) {
            sendTeleportResultMessage(player, result);
        }
    }
}
