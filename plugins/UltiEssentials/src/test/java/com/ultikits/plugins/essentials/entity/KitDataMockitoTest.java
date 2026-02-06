package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for KitData entity.
 * <p>
 * 纯单元测试礼包实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("KitData Entity Tests (Mockito)")
class KitDataMockitoTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            UUID uuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            KitData kit = KitData.builder()
                .uuid(uuid)
                .name("starter")
                .displayName("&aStarter Kit")
                .description("A kit for new players")
                .items("serialized-items-base64")
                .permission("kit.starter")
                .cooldown(3600)
                .enabled(true)
                .icon("DIAMOND_SWORD")
                .createdBy("admin-uuid")
                .createdAt(now)
                .build();

            assertThat(kit.getUuid()).isEqualTo(uuid);
            assertThat(kit.getName()).isEqualTo("starter");
            assertThat(kit.getDisplayName()).isEqualTo("&aStarter Kit");
            assertThat(kit.getDescription()).isEqualTo("A kit for new players");
            assertThat(kit.getItems()).isEqualTo("serialized-items-base64");
            assertThat(kit.getPermission()).isEqualTo("kit.starter");
            assertThat(kit.getCooldown()).isEqualTo(3600);
            assertThat(kit.isEnabled()).isTrue();
            assertThat(kit.getIcon()).isEqualTo("DIAMOND_SWORD");
            assertThat(kit.getCreatedBy()).isEqualTo("admin-uuid");
            assertThat(kit.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should build with no-args constructor")
        void shouldBuildWithNoArgsConstructor() {
            KitData kit = new KitData();
            assertThat(kit.getName()).isNull();
            assertThat(kit.isEnabled()).isFalse();
            assertThat(kit.getCooldown()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("isOneTime Tests")
    class OneTimeTests {

        @Test
        @DisplayName("Should identify one-time kit when cooldown is -1")
        void shouldIdentifyOneTimeKit() {
            KitData kit = KitData.builder().cooldown(-1).build();
            assertThat(kit.isOneTime()).isTrue();
        }

        @Test
        @DisplayName("Should not be one-time when cooldown is positive")
        void shouldNotBeOneTimeWhenCooldownPositive() {
            KitData kit = KitData.builder().cooldown(3600).build();
            assertThat(kit.isOneTime()).isFalse();
        }

        @Test
        @DisplayName("Should not be one-time when cooldown is 0")
        void shouldNotBeOneTimeWhenCooldownZero() {
            KitData kit = KitData.builder().cooldown(0).build();
            assertThat(kit.isOneTime()).isFalse();
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update fields via setters")
        void shouldUpdateFieldsViaSetters() {
            KitData kit = new KitData();

            kit.setName("vip");
            kit.setDisplayName("&6VIP Kit");
            kit.setDescription("VIP exclusive kit");
            kit.setEnabled(true);
            kit.setCooldown(7200);
            kit.setPermission("kit.vip");
            kit.setIcon("GOLD_INGOT");

            assertThat(kit.getName()).isEqualTo("vip");
            assertThat(kit.getDisplayName()).isEqualTo("&6VIP Kit");
            assertThat(kit.getDescription()).isEqualTo("VIP exclusive kit");
            assertThat(kit.isEnabled()).isTrue();
            assertThat(kit.getCooldown()).isEqualTo(7200);
            assertThat(kit.getPermission()).isEqualTo("kit.vip");
            assertThat(kit.getIcon()).isEqualTo("GOLD_INGOT");
        }

        @Test
        @DisplayName("Should toggle enabled via setter")
        void shouldToggleEnabled() {
            KitData kit = KitData.builder().enabled(true).build();
            assertThat(kit.isEnabled()).isTrue();

            kit.setEnabled(false);
            assertThat(kit.isEnabled()).isFalse();
        }
    }
}
