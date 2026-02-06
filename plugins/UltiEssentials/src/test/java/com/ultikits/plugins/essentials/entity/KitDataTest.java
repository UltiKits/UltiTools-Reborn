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
 * Unit tests for KitData entity.
 * <p>
 * 测试礼包实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("KitData Entity Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class KitDataTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build kit data with all fields")
        void shouldBuildWithAllFields() {
            UUID creatorUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            KitData kit = KitData.builder()
                .uuid(UUID.randomUUID())
                .name("starter")
                .displayName("&6新手礼包")
                .description("包含基础工具")
                .items("base64_encoded_items")
                .permission("ultiessentials.kit.starter")
                .cooldown(3600)
                .enabled(true)
                .icon("DIAMOND_SWORD")
                .createdBy(creatorUuid.toString())
                .createdAt(now)
                .build();

            assertThat(kit.getName()).isEqualTo("starter");
            assertThat(kit.getDisplayName()).isEqualTo("&6新手礼包");
            assertThat(kit.getDescription()).isEqualTo("包含基础工具");
            assertThat(kit.getItems()).isEqualTo("base64_encoded_items");
            assertThat(kit.getPermission()).isEqualTo("ultiessentials.kit.starter");
            assertThat(kit.getCooldown()).isEqualTo(3600);
            assertThat(kit.isEnabled()).isTrue();
            assertThat(kit.getIcon()).isEqualTo("DIAMOND_SWORD");
            assertThat(kit.getCreatedBy()).isEqualTo(creatorUuid.toString());
            assertThat(kit.getCreatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("One-Time Kit Tests")
    class OneTimeKitTests {

        @Test
        @DisplayName("Should identify one-time kit")
        void shouldIdentifyOneTimeKit() {
            KitData kit = KitData.builder()
                .cooldown(-1)
                .build();

            assertThat(kit.isOneTime()).isTrue();
        }

        @Test
        @DisplayName("Should identify repeatable kit")
        void shouldIdentifyRepeatableKit() {
            KitData kit = KitData.builder()
                .cooldown(3600)
                .build();

            assertThat(kit.isOneTime()).isFalse();
        }

        @Test
        @DisplayName("Should identify zero cooldown kit")
        void shouldIdentifyZeroCooldownKit() {
            KitData kit = KitData.builder()
                .cooldown(0)
                .build();

            assertThat(kit.isOneTime()).isFalse();
        }
    }

    @Nested
    @DisplayName("Enabled Flag Tests")
    class EnabledFlagTests {

        @Test
        @DisplayName("Should support enabled flag")
        void shouldSupportEnabledFlag() {
            KitData kit = KitData.builder()
                .enabled(true)
                .build();

            assertThat(kit.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should support disabled flag")
        void shouldSupportDisabledFlag() {
            KitData kit = KitData.builder()
                .enabled(false)
                .build();

            assertThat(kit.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Permission Tests")
    class PermissionTests {

        @Test
        @DisplayName("Should support permission field")
        void shouldSupportPermission() {
            KitData kit = KitData.builder()
                .permission("custom.permission")
                .build();

            assertThat(kit.getPermission()).isEqualTo("custom.permission");
        }

        @Test
        @DisplayName("Should support null permission")
        void shouldSupportNullPermission() {
            KitData kit = KitData.builder()
                .permission(null)
                .build();

            assertThat(kit.getPermission()).isNull();
        }
    }
}
