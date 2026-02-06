package com.ultikits.plugins.essentials.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.service.TpaService.TpaResult;
import com.ultikits.plugins.essentials.service.TpaService.TpaRequest;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.plugins.essentials.utils.TestHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TpaService.
 * <p>
 * 测试TPA传送请求服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("TpaService Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class TpaServiceTest {

    private ServerMock server;
    private World world;
    private PlayerMock sender;
    private PlayerMock target;
    private TpaService tpaService;

    @Mock
    private EssentialsConfig config;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        MockitoAnnotations.openMocks(this);

        world = server.addSimpleWorld("world");
        sender = server.addPlayer("Sender");
        target = server.addPlayer("Target");
        sender.setLocation(new Location(world, 0, 64, 0));
        target.setLocation(new Location(world, 100, 64, 100));

        when(config.isTpaEnabled()).thenReturn(true);
        when(config.getTpaTimeout()).thenReturn(30);
        when(config.getTpaCooldown()).thenReturn(10);
        when(config.isTpaAllowCrossWorld()).thenReturn(true);

        tpaService = new TpaService();
        try {
            java.lang.reflect.Field configField = TpaService.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(tpaService, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Send TPA Request Tests")
    class SendTpaRequestTests {

        @Test
        @DisplayName("Should send TPA request successfully")
        void shouldSendTpaRequestSuccessfully() {
            TpaResult result = tpaService.sendTpaRequest(sender, target);

            assertThat(result).isEqualTo(TpaResult.SENT);
            assertThat(tpaService.getRequest(target.getUniqueId())).isNotNull();
        }

        @Test
        @DisplayName("Should reject self request")
        void shouldRejectSelfRequest() {
            TpaResult result = tpaService.sendTpaRequest(sender, sender);

            assertThat(result).isEqualTo(TpaResult.SELF_REQUEST);
        }

        @Test
        @DisplayName("Should reject when target has pending request")
        void shouldRejectWhenTargetHasPendingRequest() {
            tpaService.sendTpaRequest(sender, target);

            PlayerMock anotherSender = server.addPlayer("Another");
            TpaResult result = tpaService.sendTpaRequest(anotherSender, target);

            assertThat(result).isEqualTo(TpaResult.TARGET_BUSY);
        }

        @Test
        @DisplayName("Should return disabled when feature disabled")
        void shouldReturnDisabledWhenFeatureDisabled() {
            when(config.isTpaEnabled()).thenReturn(false);

            TpaResult result = tpaService.sendTpaRequest(sender, target);

            assertThat(result).isEqualTo(TpaResult.DISABLED);
        }
    }

    @Nested
    @DisplayName("Send TPA Here Request Tests")
    class SendTpaHereRequestTests {

        @Test
        @DisplayName("Should send TPA here request successfully")
        void shouldSendTpaHereRequestSuccessfully() {
            TpaResult result = tpaService.sendTpaHereRequest(sender, target);

            assertThat(result).isEqualTo(TpaResult.SENT);
            TpaRequest request = tpaService.getRequest(target.getUniqueId());
            assertThat(request).isNotNull();
            assertThat(request.getType()).isEqualTo(TpaService.TpaType.TPA_HERE);
        }
    }

    @Nested
    @DisplayName("Accept Request Tests")
    class AcceptRequestTests {

        @Test
        @DisplayName("Should accept TPA request")
        void shouldAcceptTpaRequest() {
            tpaService.sendTpaRequest(sender, target);

            TpaResult result = tpaService.acceptRequest(target);

            assertThat(result).isEqualTo(TpaResult.ACCEPTED);
            assertThat(tpaService.getRequest(target.getUniqueId())).isNull();
        }

        @Test
        @DisplayName("Should return no request when no pending request")
        void shouldReturnNoRequestWhenNoPendingRequest() {
            TpaResult result = tpaService.acceptRequest(target);

            assertThat(result).isEqualTo(TpaResult.NO_REQUEST);
        }
    }

    @Nested
    @DisplayName("Deny Request Tests")
    class DenyRequestTests {

        @Test
        @DisplayName("Should deny TPA request")
        void shouldDenyTpaRequest() {
            tpaService.sendTpaRequest(sender, target);

            TpaResult result = tpaService.denyRequest(target);

            assertThat(result).isEqualTo(TpaResult.DENIED);
            assertThat(tpaService.getRequest(target.getUniqueId())).isNull();
        }

        @Test
        @DisplayName("Should return no request when no pending request")
        void shouldReturnNoRequestWhenNoPendingRequest() {
            TpaResult result = tpaService.denyRequest(target);

            assertThat(result).isEqualTo(TpaResult.NO_REQUEST);
        }
    }

    @Nested
    @DisplayName("Cancel Request Tests")
    class CancelRequestTests {

        @Test
        @DisplayName("Should cancel pending request")
        void shouldCancelPendingRequest() {
            tpaService.sendTpaRequest(sender, target);

            tpaService.cancelRequest(target.getUniqueId());

            assertThat(tpaService.getRequest(target.getUniqueId())).isNull();
        }

        @Test
        @DisplayName("Should not error when cancelling non-existent request")
        void shouldNotErrorWhenCancellingNonExistentRequest() {
            Assertions.assertDoesNotThrow(() -> {
                tpaService.cancelRequest(target.getUniqueId());
            });
        }
    }

    @Nested
    @DisplayName("Cooldown Tests")
    class CooldownTests {

        @Test
        @DisplayName("Should not be on cooldown initially")
        void shouldNotBeOnCooldownInitially() {
            boolean onCooldown = tpaService.isOnCooldown(sender.getUniqueId());

            assertThat(onCooldown).isFalse();
        }

        @Test
        @DisplayName("Should be on cooldown after sending request")
        void shouldBeOnCooldownAfterSendingRequest() {
            tpaService.sendTpaRequest(sender, target);

            boolean onCooldown = tpaService.isOnCooldown(sender.getUniqueId());

            assertThat(onCooldown).isTrue();
        }

        @Test
        @DisplayName("Should calculate remaining cooldown")
        void shouldCalculateRemainingCooldown() {
            tpaService.sendTpaRequest(sender, target);

            int remaining = tpaService.getRemainingCooldown(sender.getUniqueId());

            assertThat(remaining).isGreaterThan(0);
            assertThat(remaining).isLessThanOrEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Player Quit Tests")
    class PlayerQuitTests {

        @Test
        @DisplayName("Should clean up when target quits")
        void shouldCleanUpWhenTargetQuits() {
            tpaService.sendTpaRequest(sender, target);

            tpaService.onPlayerQuit(target.getUniqueId());

            assertThat(tpaService.getRequest(target.getUniqueId())).isNull();
        }

        @Test
        @DisplayName("Should clean up when sender quits")
        void shouldCleanUpWhenSenderQuits() {
            tpaService.sendTpaRequest(sender, target);

            tpaService.onPlayerQuit(sender.getUniqueId());

            assertThat(tpaService.getRequest(target.getUniqueId())).isNull();
        }
    }
}
