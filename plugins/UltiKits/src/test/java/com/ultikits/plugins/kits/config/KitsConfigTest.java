package com.ultikits.plugins.kits.config;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KitsConfig")
class KitsConfigTest {

    private KitsConfig config;

    @BeforeEach
    void setUp() {
        config = new KitsConfig("config/config.yml");
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("enabled is true by default")
        void enabledDefault() {
            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("clickCooldownMs is 200 by default")
        void clickCooldownDefault() {
            assertThat(config.getClickCooldownMs()).isEqualTo(200);
        }

        @Test
        @DisplayName("kitsPerPage is 28 by default")
        void kitsPerPageDefault() {
            assertThat(config.getKitsPerPage()).isEqualTo(28);
        }

        @Test
        @DisplayName("configFilePath is set from constructor")
        void configFilePath() {
            assertThat(config.getConfigFilePath()).isEqualTo("config/config.yml");
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("setEnabled updates enabled")
        void setEnabled() {
            config.setEnabled(false);
            assertThat(config.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("setClickCooldownMs updates clickCooldownMs")
        void setClickCooldownMs() {
            config.setClickCooldownMs(500);
            assertThat(config.getClickCooldownMs()).isEqualTo(500);
        }

        @Test
        @DisplayName("setKitsPerPage updates kitsPerPage")
        void setKitsPerPage() {
            config.setKitsPerPage(14);
            assertThat(config.getKitsPerPage()).isEqualTo(14);
        }
    }

    @Nested
    @DisplayName("Annotations")
    class Annotations {

        @Test
        @DisplayName("class has @ConfigEntity with correct path")
        void classHasConfigEntity() {
            ConfigEntity annotation = KitsConfig.class.getAnnotation(ConfigEntity.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("config/config.yml");
        }

        @Test
        @DisplayName("enabled field has @ConfigEntry with correct path")
        void enabledConfigEntry() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("enabled");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry).isNotNull();
            assertThat(entry.path()).isEqualTo("enabled");
        }

        @Test
        @DisplayName("clickCooldownMs field has @ConfigEntry with correct path")
        void clickCooldownConfigEntry() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("clickCooldownMs");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry).isNotNull();
            assertThat(entry.path()).isEqualTo("click_cooldown_ms");
        }

        @Test
        @DisplayName("kitsPerPage field has @ConfigEntry with correct path")
        void kitsPerPageConfigEntry() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("kitsPerPage");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry).isNotNull();
            assertThat(entry.path()).isEqualTo("kits_per_page");
        }

        @Test
        @DisplayName("clickCooldownMs field has @Range(min=50, max=5000)")
        void clickCooldownRange() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("clickCooldownMs");
            Range range = field.getAnnotation(Range.class);
            assertThat(range).isNotNull();
            assertThat(range.min()).isEqualTo(50);
            assertThat(range.max()).isEqualTo(5000);
        }

        @Test
        @DisplayName("kitsPerPage field has @Range(min=7, max=28)")
        void kitsPerPageRange() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("kitsPerPage");
            Range range = field.getAnnotation(Range.class);
            assertThat(range).isNotNull();
            assertThat(range.min()).isEqualTo(7);
            assertThat(range.max()).isEqualTo(28);
        }

        @Test
        @DisplayName("enabled field has @ConfigEntry comment")
        void enabledComment() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("enabled");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry.comment()).isNotEmpty();
        }

        @Test
        @DisplayName("clickCooldownMs field has @ConfigEntry comment")
        void clickCooldownComment() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("clickCooldownMs");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry.comment()).isNotEmpty();
        }

        @Test
        @DisplayName("kitsPerPage field has @ConfigEntry comment")
        void kitsPerPageComment() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("kitsPerPage");
            ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
            assertThat(entry.comment()).isNotEmpty();
        }

        @Test
        @DisplayName("enabled field does not have @Range annotation")
        void enabledNoRange() throws NoSuchFieldException {
            Field field = KitsConfig.class.getDeclaredField("enabled");
            Range range = field.getAnnotation(Range.class);
            assertThat(range).isNull();
        }
    }
}
