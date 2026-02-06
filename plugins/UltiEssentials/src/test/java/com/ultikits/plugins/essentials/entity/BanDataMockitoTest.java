package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for BanData entity.
 * Does NOT use MockBukkit - pure unit tests.
 * <p>
 * 纯单元测试封禁实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("BanData Entity Tests (Mockito)")
class BanDataMockitoTest {

    private UUID playerUuid;
    private UUID operatorUuid;

    @BeforeEach
    void setUp() {
        playerUuid = UUID.randomUUID();
        operatorUuid = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build ban data with all fields")
        void shouldBuildWithAllFields() {
            long now = System.currentTimeMillis();
            long expireTime = now + TimeUnit.DAYS.toMillis(7);

            BanData banData = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Test ban")
                .bannedBy(operatorUuid.toString())
                .bannedByName("Admin")
                .banTime(now)
                .expireTime(expireTime)
                .active(true)
                .ipAddress("127.0.0.1")
                .build();

            assertThat(banData.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(banData.getPlayerName()).isEqualTo("TestPlayer");
            assertThat(banData.getReason()).isEqualTo("Test ban");
            assertThat(banData.getBannedBy()).isEqualTo(operatorUuid.toString());
            assertThat(banData.getBannedByName()).isEqualTo("Admin");
            assertThat(banData.getBanTime()).isEqualTo(now);
            assertThat(banData.getExpireTime()).isEqualTo(expireTime);
            assertThat(banData.isActive()).isTrue();
            assertThat(banData.getIpAddress()).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("Should build permanent ban data")
        void shouldBuildPermanentBan() {
            BanData banData = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Permanent")
                .bannedBy(operatorUuid.toString())
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            assertThat(banData.getExpireTime()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should build with no-args constructor")
        void shouldBuildWithNoArgsConstructor() {
            BanData banData = new BanData();
            assertThat(banData.getPlayerName()).isNull();
            assertThat(banData.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should build with null optional fields")
        void shouldBuildWithNullOptionalFields() {
            BanData banData = BanData.builder()
                .uuid(UUID.randomUUID())
                .bannedBy(null)
                .ipAddress(null)
                .build();

            assertThat(banData.getBannedBy()).isNull();
            assertThat(banData.getIpAddress()).isNull();
        }
    }

    @Nested
    @DisplayName("isPermanent Tests")
    class PermanentTests {

        @Test
        @DisplayName("Should identify permanent ban when expireTime is -1")
        void shouldIdentifyPermanentBan() {
            BanData banData = BanData.builder().expireTime(-1).build();
            assertThat(banData.isPermanent()).isTrue();
        }

        @Test
        @DisplayName("Should identify temporary ban when expireTime is positive")
        void shouldIdentifyTemporaryBan() {
            BanData banData = BanData.builder()
                .expireTime(System.currentTimeMillis() + 10000)
                .build();
            assertThat(banData.isPermanent()).isFalse();
        }

        @Test
        @DisplayName("Should not be permanent when expireTime is 0")
        void shouldNotBePermanentWhenZero() {
            BanData banData = BanData.builder().expireTime(0).build();
            assertThat(banData.isPermanent()).isFalse();
        }
    }

    @Nested
    @DisplayName("hasExpired Tests")
    class ExpirationTests {

        @Test
        @DisplayName("Permanent ban should never expire")
        void permanentBanShouldNeverExpire() {
            BanData banData = BanData.builder().expireTime(-1).build();
            assertThat(banData.hasExpired()).isFalse();
        }

        @Test
        @DisplayName("Should detect expired ban")
        void shouldDetectExpiredBan() {
            BanData banData = BanData.builder()
                .expireTime(System.currentTimeMillis() - 1000)
                .build();
            assertThat(banData.hasExpired()).isTrue();
        }

        @Test
        @DisplayName("Should detect non-expired ban")
        void shouldDetectNonExpiredBan() {
            BanData banData = BanData.builder()
                .expireTime(System.currentTimeMillis() + 100000)
                .build();
            assertThat(banData.hasExpired()).isFalse();
        }

        @Test
        @DisplayName("Should detect expired when expireTime is 0")
        void shouldDetectExpiredWhenZero() {
            BanData banData = BanData.builder().expireTime(0).build();
            assertThat(banData.hasExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("getRemainingTime Tests")
    class RemainingTimeTests {

        @Test
        @DisplayName("Should return -1 for permanent ban")
        void shouldReturnNegativeOneForPermanentBan() {
            BanData banData = BanData.builder().expireTime(-1).build();
            assertThat(banData.getRemainingTime()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return 0 for expired ban")
        void shouldReturnZeroForExpiredBan() {
            BanData banData = BanData.builder()
                .expireTime(System.currentTimeMillis() - 1000)
                .build();
            assertThat(banData.getRemainingTime()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should calculate remaining time correctly")
        void shouldCalculateRemainingTime() {
            long duration = TimeUnit.HOURS.toMillis(2);
            BanData banData = BanData.builder()
                .expireTime(System.currentTimeMillis() + duration)
                .build();

            long remaining = banData.getRemainingTime();
            assertThat(remaining).isGreaterThan(0);
            assertThat(remaining).isLessThanOrEqualTo(duration);
        }
    }

    @Nested
    @DisplayName("Active Flag Tests")
    class ActiveFlagTests {

        @Test
        @DisplayName("Should support active flag true")
        void shouldSupportActiveTrue() {
            BanData banData = BanData.builder().active(true).build();
            assertThat(banData.isActive()).isTrue();
        }

        @Test
        @DisplayName("Should support active flag false")
        void shouldSupportActiveFalse() {
            BanData banData = BanData.builder().active(false).build();
            assertThat(banData.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should toggle active flag via setter")
        void shouldToggleActiveFlag() {
            BanData banData = BanData.builder().active(true).build();
            assertThat(banData.isActive()).isTrue();

            banData.setActive(false);
            assertThat(banData.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("IP Address Tests")
    class IpAddressTests {

        @Test
        @DisplayName("Should support IP address")
        void shouldSupportIpAddress() {
            BanData banData = BanData.builder()
                .ipAddress("192.168.1.1")
                .build();
            assertThat(banData.getIpAddress()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("Should support null IP address")
        void shouldSupportNullIpAddress() {
            BanData banData = BanData.builder()
                .ipAddress(null)
                .build();
            assertThat(banData.getIpAddress()).isNull();
        }

        @Test
        @DisplayName("Should update IP address via setter")
        void shouldUpdateIpAddressViaSetter() {
            BanData banData = BanData.builder().build();
            banData.setIpAddress("10.0.0.1");
            assertThat(banData.getIpAddress()).isEqualTo("10.0.0.1");
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update all fields via setters")
        void shouldUpdateAllFieldsViaSetters() {
            BanData banData = new BanData();

            UUID uuid = UUID.randomUUID();
            banData.setUuid(uuid);
            banData.setPlayerUuid(playerUuid.toString());
            banData.setPlayerName("Updated");
            banData.setReason("Updated reason");
            banData.setBannedBy(operatorUuid.toString());
            banData.setBannedByName("UpdatedAdmin");
            banData.setBanTime(12345L);
            banData.setExpireTime(67890L);
            banData.setActive(true);
            banData.setIpAddress("1.2.3.4");

            assertThat(banData.getUuid()).isEqualTo(uuid);
            assertThat(banData.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(banData.getPlayerName()).isEqualTo("Updated");
            assertThat(banData.getReason()).isEqualTo("Updated reason");
            assertThat(banData.getBannedBy()).isEqualTo(operatorUuid.toString());
            assertThat(banData.getBannedByName()).isEqualTo("UpdatedAdmin");
            assertThat(banData.getBanTime()).isEqualTo(12345L);
            assertThat(banData.getExpireTime()).isEqualTo(67890L);
            assertThat(banData.isActive()).isTrue();
            assertThat(banData.getIpAddress()).isEqualTo("1.2.3.4");
        }
    }
}
