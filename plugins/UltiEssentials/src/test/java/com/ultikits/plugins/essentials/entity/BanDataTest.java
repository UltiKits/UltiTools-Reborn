package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BanData entity.
 * <p>
 * 测试封禁记录实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("BanData Entity Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class BanDataTest {

    private BanData banData;
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

            banData = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("测试封禁")
                .bannedBy(operatorUuid.toString())
                .bannedByName("Admin")
                .banTime(now)
                .expireTime(expireTime)
                .active(true)
                .ipAddress("127.0.0.1")
                .build();

            assertThat(banData.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(banData.getPlayerName()).isEqualTo("TestPlayer");
            assertThat(banData.getReason()).isEqualTo("测试封禁");
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
            banData = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("永久封禁")
                .bannedBy(operatorUuid.toString())
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            assertThat(banData.getExpireTime()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("Permanent Ban Tests")
    class PermanentBanTests {

        @Test
        @DisplayName("Should identify permanent ban")
        void shouldIdentifyPermanentBan() {
            banData = BanData.builder()
                .expireTime(-1)
                .build();

            assertThat(banData.isPermanent()).isTrue();
        }

        @Test
        @DisplayName("Should identify temporary ban")
        void shouldIdentifyTemporaryBan() {
            banData = BanData.builder()
                .expireTime(System.currentTimeMillis() + 10000)
                .build();

            assertThat(banData.isPermanent()).isFalse();
        }

        @Test
        @DisplayName("Permanent ban should never expire")
        void permanentBanShouldNeverExpire() {
            banData = BanData.builder()
                .expireTime(-1)
                .build();

            assertThat(banData.hasExpired()).isFalse();
        }
    }

    @Nested
    @DisplayName("Expiration Tests")
    class ExpirationTests {

        @Test
        @DisplayName("Should detect expired ban")
        void shouldDetectExpiredBan() {
            banData = BanData.builder()
                .expireTime(System.currentTimeMillis() - 1000)
                .build();

            assertThat(banData.hasExpired()).isTrue();
        }

        @Test
        @DisplayName("Should detect non-expired ban")
        void shouldDetectNonExpiredBan() {
            banData = BanData.builder()
                .expireTime(System.currentTimeMillis() + 10000)
                .build();

            assertThat(banData.hasExpired()).isFalse();
        }

        @Test
        @DisplayName("Should calculate remaining time correctly")
        void shouldCalculateRemainingTime() {
            long duration = TimeUnit.HOURS.toMillis(2);
            banData = BanData.builder()
                .expireTime(System.currentTimeMillis() + duration)
                .build();

            long remaining = banData.getRemainingTime();
            assertThat(remaining).isGreaterThan(0);
            assertThat(remaining).isLessThanOrEqualTo(duration);
        }

        @Test
        @DisplayName("Should return -1 for permanent ban remaining time")
        void shouldReturnNegativeOneForPermanentBan() {
            banData = BanData.builder()
                .expireTime(-1)
                .build();

            assertThat(banData.getRemainingTime()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return 0 for expired ban remaining time")
        void shouldReturnZeroForExpiredBan() {
            banData = BanData.builder()
                .expireTime(System.currentTimeMillis() - 1000)
                .build();

            assertThat(banData.getRemainingTime()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("IP Ban Tests")
    class IpBanTests {

        @Test
        @DisplayName("Should support IP address field")
        void shouldSupportIpAddress() {
            banData = BanData.builder()
                .ipAddress("192.168.1.1")
                .build();

            assertThat(banData.getIpAddress()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("Should support null IP address")
        void shouldSupportNullIpAddress() {
            banData = BanData.builder()
                .ipAddress(null)
                .build();

            assertThat(banData.getIpAddress()).isNull();
        }
    }

    @Nested
    @DisplayName("Active Flag Tests")
    class ActiveFlagTests {

        @Test
        @DisplayName("Should support active flag")
        void shouldSupportActiveFlag() {
            banData = BanData.builder()
                .active(true)
                .build();

            assertThat(banData.isActive()).isTrue();
        }

        @Test
        @DisplayName("Should support inactive flag")
        void shouldSupportInactiveFlag() {
            banData = BanData.builder()
                .active(false)
                .build();

            assertThat(banData.isActive()).isFalse();
        }
    }
}
