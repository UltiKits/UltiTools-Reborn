package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for KitClaimData entity.
 * <p>
 * 纯单元测试礼包领取记录实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("KitClaimData Entity Tests (Mockito)")
class KitClaimDataMockitoTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            UUID uuid = UUID.randomUUID();
            UUID playerUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            KitClaimData claim = KitClaimData.builder()
                .uuid(uuid)
                .playerUuid(playerUuid.toString())
                .kitName("starter")
                .lastClaim(now)
                .claimCount(5)
                .build();

            assertThat(claim.getUuid()).isEqualTo(uuid);
            assertThat(claim.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(claim.getKitName()).isEqualTo("starter");
            assertThat(claim.getLastClaim()).isEqualTo(now);
            assertThat(claim.getClaimCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should build with no-args constructor")
        void shouldBuildWithNoArgsConstructor() {
            KitClaimData claim = new KitClaimData();
            assertThat(claim.getPlayerUuid()).isNull();
            assertThat(claim.getClaimCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update all fields via setters")
        void shouldUpdateAllFieldsViaSetters() {
            KitClaimData claim = new KitClaimData();

            UUID uuid = UUID.randomUUID();
            claim.setUuid(uuid);
            claim.setPlayerUuid("player-uuid-str");
            claim.setKitName("vip");
            claim.setLastClaim(99999L);
            claim.setClaimCount(10);

            assertThat(claim.getUuid()).isEqualTo(uuid);
            assertThat(claim.getPlayerUuid()).isEqualTo("player-uuid-str");
            assertThat(claim.getKitName()).isEqualTo("vip");
            assertThat(claim.getLastClaim()).isEqualTo(99999L);
            assertThat(claim.getClaimCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should increment claim count")
        void shouldIncrementClaimCount() {
            KitClaimData claim = KitClaimData.builder().claimCount(3).build();
            claim.setClaimCount(claim.getClaimCount() + 1);
            assertThat(claim.getClaimCount()).isEqualTo(4);
        }
    }
}
