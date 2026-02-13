package com.ultikits.plugins.worlds.entity;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for WorldSettings entity.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("WorldSettings Entity Tests")
class WorldSettingsTest {

    @Nested
    @DisplayName("Creation and Defaults")
    class CreationAndDefaults {

        @Test
        @DisplayName("createDefault should create settings with default values")
        void createDefault() {
            WorldSettings settings = WorldSettings.createDefault("test_world");

            assertThat(settings.getWorldName()).isEqualTo("test_world");
            assertThat(settings.getDisplayName()).isEqualTo("test_world");
            assertThat(settings.getDescription()).isEmpty();
            assertThat(settings.getIcon()).isEqualTo("GRASS_BLOCK");
            assertThat(settings.isPvpEnabled()).isTrue();
            assertThat(settings.isMonstersEnabled()).isTrue();
            assertThat(settings.isAnimalsEnabled()).isTrue();
            assertThat(settings.isWeatherEnabled()).isTrue();
            assertThat(settings.isHidden()).isFalse();
            assertThat(settings.isLocked()).isFalse();
            assertThat(settings.isBlocked()).isFalse();
            assertThat(settings.isAutoUnload()).isTrue();
            assertThat(settings.isProtectBreak()).isFalse();
            assertThat(settings.isProtectPlace()).isFalse();
            assertThat(settings.isProtectInteract()).isFalse();
            assertThat(settings.isProtectExplosion()).isFalse();
        }

        @Test
        @DisplayName("Builder should create settings correctly")
        void builderCreation() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("custom_world")
                    .displayName("Custom World")
                    .description("A custom test world")
                    .icon("DIAMOND_BLOCK")
                    .pvpEnabled(false)
                    .hidden(true)
                    .build();

            assertThat(settings.getWorldName()).isEqualTo("custom_world");
            assertThat(settings.getDisplayName()).isEqualTo("Custom World");
            assertThat(settings.getDescription()).isEqualTo("A custom test world");
            assertThat(settings.getIcon()).isEqualTo("DIAMOND_BLOCK");
            assertThat(settings.isPvpEnabled()).isFalse();
            assertThat(settings.isHidden()).isTrue();
        }

        @Test
        @DisplayName("No-arg constructor should create settings with defaults")
        void noArgConstructor() {
            WorldSettings settings = new WorldSettings();

            assertThat(settings.getWorldName()).isNull();
            assertThat(settings.getDisplayName()).isNull();
            // boolean fields default to false in Java (no-arg constructor)
            assertThat(settings.isPvpEnabled()).isFalse();
            assertThat(settings.isMonstersEnabled()).isFalse();
        }

        @Test
        @DisplayName("All-arg constructor should set all fields")
        void allArgConstructor() {
            WorldSettings settings = new WorldSettings(
                    "world", "World", "desc", "STONE",
                    true, true, true, true,
                    null, null,
                    false, false, false, true,
                    true, true, true, true,
                    100.0, 64.0, -200.0, 90.0f, 45.0f,
                    1700000000000L
            );

            assertThat(settings.getWorldName()).isEqualTo("world");
            assertThat(settings.getDisplayName()).isEqualTo("World");
            assertThat(settings.getDescription()).isEqualTo("desc");
            assertThat(settings.getIcon()).isEqualTo("STONE");
            assertThat(settings.isPvpEnabled()).isTrue();
            assertThat(settings.isMonstersEnabled()).isTrue();
            assertThat(settings.isAnimalsEnabled()).isTrue();
            assertThat(settings.isWeatherEnabled()).isTrue();
            assertThat(settings.getDifficulty()).isNull();
            assertThat(settings.getPostTeleportCommands()).isNull();
            assertThat(settings.isHidden()).isFalse();
            assertThat(settings.isLocked()).isFalse();
            assertThat(settings.isBlocked()).isFalse();
            assertThat(settings.isAutoUnload()).isTrue();
            assertThat(settings.isProtectBreak()).isTrue();
            assertThat(settings.isProtectPlace()).isTrue();
            assertThat(settings.isProtectInteract()).isTrue();
            assertThat(settings.isProtectExplosion()).isTrue();
            assertThat(settings.getSpawnX()).isEqualTo(100.0);
            assertThat(settings.getSpawnY()).isEqualTo(64.0);
            assertThat(settings.getSpawnZ()).isEqualTo(-200.0);
            assertThat(settings.getSpawnYaw()).isEqualTo(90.0f);
            assertThat(settings.getSpawnPitch()).isEqualTo(45.0f);
            assertThat(settings.getCreatedAt()).isEqualTo(1700000000000L);
        }

        @Test
        @DisplayName("createDefault should have null difficulty")
        void createDefaultNullDifficulty() {
            WorldSettings settings = WorldSettings.createDefault("test_world");
            assertThat(settings.getDifficulty()).isNull();
        }

        @Test
        @DisplayName("createDefault should have null postTeleportCommands")
        void createDefaultNullPostTeleportCommands() {
            WorldSettings settings = WorldSettings.createDefault("test_world");
            assertThat(settings.getPostTeleportCommands()).isNull();
        }

        @Test
        @DisplayName("Builder autoUnload should default to true")
        void builderAutoUnloadDefault() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .build();

            assertThat(settings.isAutoUnload()).isTrue();
        }

        @Test
        @DisplayName("Builder should set all protection fields")
        void builderProtectionFields() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .protectBreak(true)
                    .protectPlace(true)
                    .protectInteract(true)
                    .protectExplosion(true)
                    .build();

            assertThat(settings.isProtectBreak()).isTrue();
            assertThat(settings.isProtectPlace()).isTrue();
            assertThat(settings.isProtectInteract()).isTrue();
            assertThat(settings.isProtectExplosion()).isTrue();
        }

        @Test
        @DisplayName("Builder should set spawn coordinates")
        void builderSpawnCoords() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .spawnX(100.5)
                    .spawnY(64.0)
                    .spawnZ(-200.5)
                    .spawnYaw(90.0f)
                    .spawnPitch(45.0f)
                    .build();

            assertThat(settings.getSpawnX()).isEqualTo(100.5);
            assertThat(settings.getSpawnY()).isEqualTo(64.0);
            assertThat(settings.getSpawnZ()).isEqualTo(-200.5);
            assertThat(settings.getSpawnYaw()).isEqualTo(90.0f);
            assertThat(settings.getSpawnPitch()).isEqualTo(45.0f);
        }

        @Test
        @DisplayName("Builder should set createdAt")
        void builderCreatedAt() {
            long ts = 1700000000000L;
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .createdAt(ts)
                    .build();

            assertThat(settings.getCreatedAt()).isEqualTo(ts);
        }

        @Test
        @DisplayName("Builder should set access control fields")
        void builderAccessControl() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .hidden(true)
                    .locked(true)
                    .blocked(true)
                    .autoUnload(false)
                    .build();

            assertThat(settings.isHidden()).isTrue();
            assertThat(settings.isLocked()).isTrue();
            assertThat(settings.isBlocked()).isTrue();
            assertThat(settings.isAutoUnload()).isFalse();
        }

        @Test
        @DisplayName("Builder should set world rules")
        void builderWorldRules() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .pvpEnabled(false)
                    .monstersEnabled(false)
                    .animalsEnabled(false)
                    .weatherEnabled(false)
                    .build();

            assertThat(settings.isPvpEnabled()).isFalse();
            assertThat(settings.isMonstersEnabled()).isFalse();
            assertThat(settings.isAnimalsEnabled()).isFalse();
            assertThat(settings.isWeatherEnabled()).isFalse();
        }

        @Test
        @DisplayName("Builder toString should return non-null string")
        void builderToString() {
            String result = WorldSettings.builder().toString();

            assertThat(result).isNotNull();
            assertThat(result).contains("WorldSettings.WorldSettingsBuilder");
        }
    }

    @Nested
    @DisplayName("Protection Methods")
    class ProtectionMethods {

        @Test
        @DisplayName("hasProtection should return false when no protection enabled")
        void hasProtectionFalse() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings.hasProtection()).isFalse();
        }

        @Test
        @DisplayName("hasProtection should return true when protectBreak enabled")
        void hasProtectionBreak() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setProtectBreak(true);

            assertThat(settings.hasProtection()).isTrue();
        }

        @Test
        @DisplayName("hasProtection should return true when protectPlace enabled")
        void hasProtectionPlace() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setProtectPlace(true);

            assertThat(settings.hasProtection()).isTrue();
        }

        @Test
        @DisplayName("hasProtection should return true when protectInteract enabled")
        void hasProtectionInteract() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setProtectInteract(true);

            assertThat(settings.hasProtection()).isTrue();
        }

        @Test
        @DisplayName("hasProtection should return true when protectExplosion enabled")
        void hasProtectionExplosion() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setProtectExplosion(true);

            assertThat(settings.hasProtection()).isTrue();
        }

        @Test
        @DisplayName("hasProtection should return true when all protections enabled")
        void hasProtectionAll() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.enableFullProtection();

            assertThat(settings.hasProtection()).isTrue();
        }

        @Test
        @DisplayName("enableFullProtection should enable all protection settings")
        void enableFullProtection() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.enableFullProtection();

            assertThat(settings.isProtectBreak()).isTrue();
            assertThat(settings.isProtectPlace()).isTrue();
            assertThat(settings.isProtectInteract()).isTrue();
            assertThat(settings.isProtectExplosion()).isTrue();
            assertThat(settings.hasProtection()).isTrue();
        }

        @Test
        @DisplayName("disableAllProtection should disable all protection settings")
        void disableAllProtection() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.enableFullProtection();

            settings.disableAllProtection();

            assertThat(settings.isProtectBreak()).isFalse();
            assertThat(settings.isProtectPlace()).isFalse();
            assertThat(settings.isProtectInteract()).isFalse();
            assertThat(settings.isProtectExplosion()).isFalse();
            assertThat(settings.hasProtection()).isFalse();
        }

        @Test
        @DisplayName("disableAllProtection on already unprotected world should remain unprotected")
        void disableAllProtectionIdempotent() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.disableAllProtection();

            assertThat(settings.hasProtection()).isFalse();
        }
    }

    @Nested
    @DisplayName("Spawn Location")
    class SpawnLocation {

        @Test
        @DisplayName("Should store spawn coordinates correctly")
        void spawnCoordinates() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setSpawnX(100.5);
            settings.setSpawnY(64.0);
            settings.setSpawnZ(-200.5);
            settings.setSpawnYaw(90.0f);
            settings.setSpawnPitch(45.0f);

            assertThat(settings.getSpawnX()).isEqualTo(100.5);
            assertThat(settings.getSpawnY()).isEqualTo(64.0);
            assertThat(settings.getSpawnZ()).isEqualTo(-200.5);
            assertThat(settings.getSpawnYaw()).isEqualTo(90.0f);
            assertThat(settings.getSpawnPitch()).isEqualTo(45.0f);
        }

        @Test
        @DisplayName("Default spawn should be at origin")
        void defaultSpawn() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings.getSpawnX()).isEqualTo(0.0);
            assertThat(settings.getSpawnY()).isEqualTo(0.0);
            assertThat(settings.getSpawnZ()).isEqualTo(0.0);
            assertThat(settings.getSpawnYaw()).isEqualTo(0.0f);
            assertThat(settings.getSpawnPitch()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Should handle negative spawn coordinates")
        void negativeSpawnCoordinates() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setSpawnX(-500.25);
            settings.setSpawnY(-10.0);
            settings.setSpawnZ(-1000.75);

            assertThat(settings.getSpawnX()).isEqualTo(-500.25);
            assertThat(settings.getSpawnY()).isEqualTo(-10.0);
            assertThat(settings.getSpawnZ()).isEqualTo(-1000.75);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("validate should return true for valid settings")
        void validateTrue() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings.validate()).isTrue();
        }

        @Test
        @DisplayName("validate should return false for null world name")
        void validateNullName() {
            WorldSettings settings = WorldSettings.builder().build();

            assertThat(settings.validate()).isFalse();
        }

        @Test
        @DisplayName("validate should return false for empty world name")
        void validateEmptyName() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("")
                    .build();

            assertThat(settings.validate()).isFalse();
        }

        @Test
        @DisplayName("validate should return true for any non-empty world name")
        void validateNonEmptyName() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("a")
                    .build();

            assertThat(settings.validate()).isTrue();
        }

        @Test
        @DisplayName("getValidationErrors should return error for null name")
        void validationErrorsNullName() {
            WorldSettings settings = WorldSettings.builder().build();

            List<String> errors = settings.getValidationErrors();

            assertThat(errors).contains("World name cannot be empty");
        }

        @Test
        @DisplayName("getValidationErrors should return error for empty name")
        void validationErrorsEmptyName() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("")
                    .build();

            List<String> errors = settings.getValidationErrors();

            assertThat(errors).contains("World name cannot be empty");
        }

        @Test
        @DisplayName("getValidationErrors should return empty list for valid settings")
        void validationErrorsValid() {
            WorldSettings settings = WorldSettings.createDefault("world");

            List<String> errors = settings.getValidationErrors();

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("getValidationErrors should return exactly one error for null name")
        void validationErrorsSingleError() {
            WorldSettings settings = WorldSettings.builder().build();

            List<String> errors = settings.getValidationErrors();

            assertThat(errors).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Lifecycle Hooks")
    class LifecycleHooks {

        @Test
        @DisplayName("onCreate should set createdAt timestamp")
        void onCreate() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .build();

            long before = System.currentTimeMillis();
            settings.onCreate();
            long after = System.currentTimeMillis();

            assertThat(settings.getCreatedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("onCreate should overwrite previous createdAt")
        void onCreateOverwrite() {
            WorldSettings settings = WorldSettings.builder()
                    .worldName("world")
                    .createdAt(1000L)
                    .build();

            settings.onCreate();

            assertThat(settings.getCreatedAt()).isGreaterThan(1000L);
        }
    }

    @Nested
    @DisplayName("World Rules")
    class WorldRules {

        @Test
        @DisplayName("Should toggle PVP setting")
        void pvpToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setPvpEnabled(false);
            assertThat(settings.isPvpEnabled()).isFalse();

            settings.setPvpEnabled(true);
            assertThat(settings.isPvpEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should toggle monsters setting")
        void monstersToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setMonstersEnabled(false);
            assertThat(settings.isMonstersEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should toggle animals setting")
        void animalsToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setAnimalsEnabled(false);
            assertThat(settings.isAnimalsEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should toggle weather setting")
        void weatherToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setWeatherEnabled(false);
            assertThat(settings.isWeatherEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Access Control")
    class AccessControl {

        @Test
        @DisplayName("Should toggle hidden setting")
        void hiddenToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setHidden(true);
            assertThat(settings.isHidden()).isTrue();
        }

        @Test
        @DisplayName("Should toggle locked setting")
        void lockedToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setLocked(true);
            assertThat(settings.isLocked()).isTrue();
        }

        @Test
        @DisplayName("Should toggle blocked setting")
        void blockedToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setBlocked(true);
            assertThat(settings.isBlocked()).isTrue();
        }

        @Test
        @DisplayName("Should toggle auto-unload setting")
        void autoUnloadToggle() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setAutoUnload(false);
            assertThat(settings.isAutoUnload()).isFalse();
        }
    }

    @Nested
    @DisplayName("Display Properties")
    class DisplayProperties {

        @Test
        @DisplayName("Should update display name")
        void displayName() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setDisplayName("Beautiful World");

            assertThat(settings.getDisplayName()).isEqualTo("Beautiful World");
        }

        @Test
        @DisplayName("Should update description")
        void description() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setDescription("This is a test world");

            assertThat(settings.getDescription()).isEqualTo("This is a test world");
        }

        @Test
        @DisplayName("Should update icon")
        void icon() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setIcon("EMERALD_BLOCK");

            assertThat(settings.getIcon()).isEqualTo("EMERALD_BLOCK");
        }

        @Test
        @DisplayName("Should allow null display name")
        void nullDisplayName() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setDisplayName(null);

            assertThat(settings.getDisplayName()).isNull();
        }

        @Test
        @DisplayName("Should allow null description")
        void nullDescription() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setDescription(null);

            assertThat(settings.getDescription()).isNull();
        }

        @Test
        @DisplayName("Should update difficulty")
        void difficulty() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setDifficulty("HARD");
            assertThat(settings.getDifficulty()).isEqualTo("HARD");
        }

        @Test
        @DisplayName("Should allow null difficulty")
        void nullDifficulty() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setDifficulty(null);
            assertThat(settings.getDifficulty()).isNull();
        }

        @Test
        @DisplayName("Should update postTeleportCommands")
        void postTeleportCommands() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setPostTeleportCommands("say Hello\ngive {player} diamond 1");
            assertThat(settings.getPostTeleportCommands()).isEqualTo("say Hello\ngive {player} diamond 1");
        }

        @Test
        @DisplayName("Should allow null postTeleportCommands")
        void nullPostTeleportCommands() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setPostTeleportCommands(null);
            assertThat(settings.getPostTeleportCommands()).isNull();
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Two settings with same data should be equal")
        void equalObjects() {
            WorldSettings s1 = WorldSettings.builder()
                    .worldName("world")
                    .displayName("World")
                    .description("desc")
                    .icon("GRASS_BLOCK")
                    .pvpEnabled(true)
                    .monstersEnabled(true)
                    .animalsEnabled(true)
                    .weatherEnabled(true)
                    .difficulty(null)
                    .postTeleportCommands(null)
                    .hidden(false)
                    .locked(false)
                    .blocked(false)
                    .autoUnload(true)
                    .protectBreak(false)
                    .protectPlace(false)
                    .protectInteract(false)
                    .protectExplosion(false)
                    .spawnX(0).spawnY(0).spawnZ(0)
                    .spawnYaw(0f).spawnPitch(0f)
                    .createdAt(1000L)
                    .build();

            WorldSettings s2 = WorldSettings.builder()
                    .worldName("world")
                    .displayName("World")
                    .description("desc")
                    .icon("GRASS_BLOCK")
                    .pvpEnabled(true)
                    .monstersEnabled(true)
                    .animalsEnabled(true)
                    .weatherEnabled(true)
                    .difficulty(null)
                    .postTeleportCommands(null)
                    .hidden(false)
                    .locked(false)
                    .blocked(false)
                    .autoUnload(true)
                    .protectBreak(false)
                    .protectPlace(false)
                    .protectInteract(false)
                    .protectExplosion(false)
                    .spawnX(0).spawnY(0).spawnZ(0)
                    .spawnYaw(0f).spawnPitch(0f)
                    .createdAt(1000L)
                    .build();

            assertThat(s1).isEqualTo(s2);
            assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
        }

        @Test
        @DisplayName("Two settings with different world names should not be equal")
        void notEqualDifferentName() {
            WorldSettings s1 = WorldSettings.createDefault("world1");
            WorldSettings s2 = WorldSettings.createDefault("world2");

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings should not equal null")
        void notEqualNull() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Settings should not equal different type")
        void notEqualDifferentType() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings.equals("string")).isFalse();
        }

        @Test
        @DisplayName("Settings should equal itself")
        void equalSelf() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings).isEqualTo(settings);
        }

        @Test
        @DisplayName("Settings differing in displayName should not be equal")
        void notEqualDifferentDisplayName() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").displayName("A").build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").displayName("B").build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in description should not be equal")
        void notEqualDifferentDescription() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").description("A").build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").description("B").build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in icon should not be equal")
        void notEqualDifferentIcon() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").icon("STONE").build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").icon("DIRT").build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in pvpEnabled should not be equal")
        void notEqualDifferentPvp() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").pvpEnabled(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").pvpEnabled(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in monstersEnabled should not be equal")
        void notEqualDifferentMonsters() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").monstersEnabled(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").monstersEnabled(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in animalsEnabled should not be equal")
        void notEqualDifferentAnimals() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").animalsEnabled(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").animalsEnabled(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in weatherEnabled should not be equal")
        void notEqualDifferentWeather() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").weatherEnabled(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").weatherEnabled(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in hidden should not be equal")
        void notEqualDifferentHidden() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").hidden(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").hidden(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in locked should not be equal")
        void notEqualDifferentLocked() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").locked(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").locked(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in blocked should not be equal")
        void notEqualDifferentBlocked() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").blocked(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").blocked(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in protectBreak should not be equal")
        void notEqualDifferentProtectBreak() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").protectBreak(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").protectBreak(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in protectPlace should not be equal")
        void notEqualDifferentProtectPlace() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").protectPlace(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").protectPlace(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in protectInteract should not be equal")
        void notEqualDifferentProtectInteract() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").protectInteract(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").protectInteract(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in protectExplosion should not be equal")
        void notEqualDifferentProtectExplosion() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").protectExplosion(true).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").protectExplosion(false).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in spawnX should not be equal")
        void notEqualDifferentSpawnX() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").spawnX(100).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").spawnX(200).build();

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("Settings differing in createdAt should not be equal")
        void notEqualDifferentCreatedAt() {
            WorldSettings s1 = WorldSettings.builder().worldName("world").createdAt(1000L).build();
            WorldSettings s2 = WorldSettings.builder().worldName("world").createdAt(2000L).build();

            assertThat(s1).isNotEqualTo(s2);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain class name and fields")
        void toStringContainsFields() {
            WorldSettings settings = WorldSettings.createDefault("test_world");

            String result = settings.toString();

            assertThat(result).contains("WorldSettings");
            assertThat(result).contains("test_world");
        }

        @Test
        @DisplayName("toString should work on empty settings")
        void toStringEmpty() {
            WorldSettings settings = new WorldSettings();

            String result = settings.toString();

            assertThat(result).isNotNull();
            assertThat(result).contains("WorldSettings");
        }
    }

    @Nested
    @DisplayName("BaseDataEntity Methods")
    class BaseDataEntityMethods {

        @Test
        @DisplayName("isNew should return true when id is null")
        void isNewTrue() {
            WorldSettings settings = WorldSettings.createDefault("world");

            assertThat(settings.isNew()).isTrue();
        }

        @Test
        @DisplayName("isNew should return false when id is set")
        void isNewFalse() {
            WorldSettings settings = WorldSettings.createDefault("world");
            settings.setId(1);

            assertThat(settings.isNew()).isFalse();
        }

        @Test
        @DisplayName("getId and setId should work correctly")
        void getSetId() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setId(42);

            assertThat(settings.getId()).isEqualTo(42);
        }
    }

    @Nested
    @DisplayName("CanEqual")
    class CanEqualTests {

        @Test
        @DisplayName("canEqual should return true for same type")
        void canEqualSameType() {
            WorldSettings s1 = WorldSettings.createDefault("world");
            WorldSettings s2 = WorldSettings.createDefault("world");

            assertThat(s1.canEqual(s2)).isTrue();
        }

        @Test
        @DisplayName("canEqual should return false for different type")
        void canEqualDifferentType() {
            WorldSettings s = WorldSettings.createDefault("world");

            assertThat(s.canEqual("not a settings")).isFalse();
        }
    }

    @Nested
    @DisplayName("SetWorldName")
    class SetWorldName {

        @Test
        @DisplayName("Should update world name")
        void setWorldName() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setWorldName("new_world");

            assertThat(settings.getWorldName()).isEqualTo("new_world");
        }

        @Test
        @DisplayName("Should allow setting world name to null")
        void setWorldNameNull() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setWorldName(null);

            assertThat(settings.getWorldName()).isNull();
        }
    }

    @Nested
    @DisplayName("SetCreatedAt")
    class SetCreatedAt {

        @Test
        @DisplayName("Should update createdAt")
        void setCreatedAt() {
            WorldSettings settings = WorldSettings.createDefault("world");

            settings.setCreatedAt(1700000000000L);

            assertThat(settings.getCreatedAt()).isEqualTo(1700000000000L);
        }
    }
}
