package com.ultikits.plugins.menu.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

@DisplayName("MenuConfig Tests")
class MenuConfigTest {

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have default cooldown of 200ms")
        void shouldHaveDefaultCooldown() {
            MenuConfig config = new MenuConfig("config/config.yml");
            assertThat(config.getClickCooldownMs()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should allow setting cooldown value")
        void shouldAllowSettingCooldown() {
            MenuConfig config = new MenuConfig("config/config.yml");
            config.setClickCooldownMs(500);
            assertThat(config.getClickCooldownMs()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Annotation Tests")
    class AnnotationTests {

        @Test
        @DisplayName("Should have @ConfigEntity annotation with correct path")
        void shouldHaveConfigEntityAnnotation() {
            ConfigEntity annotation = MenuConfig.class.getAnnotation(ConfigEntity.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("config/config.yml");
        }

        @Test
        @DisplayName("Should have @Range annotation on clickCooldownMs field")
        void shouldHaveRangeAnnotation() throws NoSuchFieldException {
            Field field = MenuConfig.class.getDeclaredField("clickCooldownMs");
            Range range = field.getAnnotation(Range.class);
            assertThat(range).isNotNull();
            assertThat(range.min()).isEqualTo(50);
            assertThat(range.max()).isEqualTo(5000);
        }

        @Test
        @DisplayName("Should have @ConfigEntry annotation on clickCooldownMs field")
        void shouldHaveConfigEntryAnnotation() throws NoSuchFieldException {
            Field field = MenuConfig.class.getDeclaredField("clickCooldownMs");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry).isNotNull();
            assertThat(entry.path()).isEqualTo("click_cooldown_ms");
        }
    }
}
