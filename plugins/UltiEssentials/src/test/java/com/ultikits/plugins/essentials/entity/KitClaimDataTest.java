package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for KitClaimData entity.
 * <p>
 * 测试礼包领取记录实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("KitClaimData Entity Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class KitClaimDataTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build kit claim data with all fields")
        void shouldBuildWithAllFields() {
            UUID playerUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            KitClaimData claim = KitClaimData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .kitName("starter")
                .lastClaim(now)
                .claimCount(5)
                .build();

            assertThat(claim.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(claim.getKitName()).isEqualTo("starter");
            assertThat(claim.getLastClaim()).isEqualTo(now);
            assertThat(claim.getClaimCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should build with initial claim count")
        void shouldBuildWithInitialClaimCount() {
            KitClaimData claim = KitClaimData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(UUID.randomUUID().toString())
                .kitName("starter")
                .lastClaim(System.currentTimeMillis())
                .claimCount(1)
                .build();

            assertThat(claim.getClaimCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Claim Count Tests")
    class ClaimCountTests {

        @Test
        @DisplayName("Should increment claim count")
        void shouldIncrementClaimCount() {
            KitClaimData claim = KitClaimData.builder()
                .claimCount(5)
                .build();

            claim.setClaimCount(claim.getClaimCount() + 1);

            assertThat(claim.getClaimCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("Should handle zero claim count")
        void shouldHandleZeroClaimCount() {
            KitClaimData claim = KitClaimData.builder()
                .claimCount(0)
                .build();

            assertThat(claim.getClaimCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Last Claim Tests")
    class LastClaimTests {

        @Test
        @DisplayName("Should update last claim time")
        void shouldUpdateLastClaimTime() {
            long oldTime = System.currentTimeMillis() - 10000;
            KitClaimData claim = KitClaimData.builder()
                .lastClaim(oldTime)
                .build();

            long newTime = System.currentTimeMillis();
            claim.setLastClaim(newTime);

            assertThat(claim.getLastClaim()).isEqualTo(newTime);
            assertThat(claim.getLastClaim()).isGreaterThan(oldTime);
        }
    }
}
