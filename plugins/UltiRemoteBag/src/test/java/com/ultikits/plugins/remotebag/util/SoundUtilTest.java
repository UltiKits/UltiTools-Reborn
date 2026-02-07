package com.ultikits.plugins.remotebag.util;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SoundUtil Tests")
class SoundUtilTest {

    private Player player;
    private RemoteBagConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
        config = UltiRemoteBagTestHelper.createDefaultConfig();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== playSound(Player, String, float, float) ====================

    @Nested
    @DisplayName("playSound with explicit volume and pitch")
    class PlaySoundExplicit {

        @Test
        @DisplayName("Should play sound with valid sound name")
        void playsSoundWithValidName() {
            SoundUtil.playSound(player, "BLOCK_CHEST_OPEN", 1.0f, 1.0f);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.BLOCK_CHEST_OPEN),
                    eq(1.0f),
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should handle lowercase sound name")
        void handlesLowercaseSoundName() {
            SoundUtil.playSound(player, "block_chest_open", 1.0f, 1.0f);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.BLOCK_CHEST_OPEN),
                    eq(1.0f),
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should silently ignore invalid sound name")
        void silentlyIgnoresInvalidSoundName() {
            assertThatCode(() -> SoundUtil.playSound(player, "INVALID_SOUND_NAME", 1.0f, 1.0f))
                    .doesNotThrowAnyException();

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("Should do nothing when player is null")
        void doesNothingWhenPlayerNull() {
            assertThatCode(() -> SoundUtil.playSound(null, "BLOCK_CHEST_OPEN", 1.0f, 1.0f))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should do nothing when sound name is null")
        void doesNothingWhenSoundNameNull() {
            assertThatCode(() -> SoundUtil.playSound(player, null, 1.0f, 1.0f))
                    .doesNotThrowAnyException();

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("Should do nothing when sound name is empty")
        void doesNothingWhenSoundNameEmpty() {
            assertThatCode(() -> SoundUtil.playSound(player, "", 1.0f, 1.0f))
                    .doesNotThrowAnyException();

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("Should use specified volume and pitch")
        void usesSpecifiedVolumeAndPitch() {
            SoundUtil.playSound(player, "BLOCK_CHEST_OPEN", 0.5f, 1.5f);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.BLOCK_CHEST_OPEN),
                    eq(0.5f),
                    eq(1.5f)
            );
        }
    }

    // ==================== playSound(Player, String, RemoteBagConfig) ====================

    @Nested
    @DisplayName("playSound with config")
    class PlaySoundWithConfig {

        @Test
        @DisplayName("Should play sound using config volume and pitch")
        void playsSoundWithConfig() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getSoundVolume()).thenReturn(0.8);
            when(config.getSoundPitch()).thenReturn(1.2);

            SoundUtil.playSound(player, "BLOCK_CHEST_OPEN", config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.BLOCK_CHEST_OPEN),
                    eq(0.8f),
                    eq(1.2f)
            );
        }

        @Test
        @DisplayName("Should not play sound when sound disabled")
        void doesNotPlayWhenDisabled() {
            when(config.isSoundEnabled()).thenReturn(false);

            SoundUtil.playSound(player, "BLOCK_CHEST_OPEN", config);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    // ==================== playOpenSound ====================

    @Nested
    @DisplayName("playOpenSound")
    class PlayOpenSound {

        @Test
        @DisplayName("Should play configured open sound")
        void playsOpenSound() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getOpenSound()).thenReturn("BLOCK_CHEST_OPEN");
            when(config.getSoundVolume()).thenReturn(1.0);
            when(config.getSoundPitch()).thenReturn(1.0);

            SoundUtil.playOpenSound(player, config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.BLOCK_CHEST_OPEN),
                    eq(1.0f),
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should not play when sound disabled")
        void doesNotPlayWhenDisabled() {
            when(config.isSoundEnabled()).thenReturn(false);

            SoundUtil.playOpenSound(player, config);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    // ==================== playCloseSound ====================

    @Nested
    @DisplayName("playCloseSound")
    class PlayCloseSound {

        @Test
        @DisplayName("Should play configured close sound")
        void playsCloseSound() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getCloseSound()).thenReturn("BLOCK_CHEST_CLOSE");
            when(config.getSoundVolume()).thenReturn(1.0);
            when(config.getSoundPitch()).thenReturn(1.0);

            SoundUtil.playCloseSound(player, config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.BLOCK_CHEST_CLOSE),
                    eq(1.0f),
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should not play when sound disabled")
        void doesNotPlayWhenDisabled() {
            when(config.isSoundEnabled()).thenReturn(false);

            SoundUtil.playCloseSound(player, config);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    // ==================== playPurchaseSound ====================

    @Nested
    @DisplayName("playPurchaseSound")
    class PlayPurchaseSound {

        @Test
        @DisplayName("Should play configured purchase sound")
        void playsPurchaseSound() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getPurchaseSound()).thenReturn("ENTITY_PLAYER_LEVELUP");
            when(config.getSoundVolume()).thenReturn(1.0);
            when(config.getSoundPitch()).thenReturn(1.0);

            SoundUtil.playPurchaseSound(player, config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.ENTITY_PLAYER_LEVELUP),
                    eq(1.0f),
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should not play when sound disabled")
        void doesNotPlayWhenDisabled() {
            when(config.isSoundEnabled()).thenReturn(false);

            SoundUtil.playPurchaseSound(player, config);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    // ==================== playErrorSound ====================

    @Nested
    @DisplayName("playErrorSound")
    class PlayErrorSound {

        @Test
        @DisplayName("Should play configured error sound")
        void playsErrorSound() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getErrorSound()).thenReturn("ENTITY_VILLAGER_NO");
            when(config.getSoundVolume()).thenReturn(1.0);
            when(config.getSoundPitch()).thenReturn(1.0);

            SoundUtil.playErrorSound(player, config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.ENTITY_VILLAGER_NO),
                    eq(1.0f),
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should not play when sound disabled")
        void doesNotPlayWhenDisabled() {
            when(config.isSoundEnabled()).thenReturn(false);

            SoundUtil.playErrorSound(player, config);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    // ==================== playPageSound ====================

    @Nested
    @DisplayName("playPageSound")
    class PlayPageSound {

        @Test
        @DisplayName("Should play UI_BUTTON_CLICK at half volume")
        void playsPageSoundAtHalfVolume() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getSoundVolume()).thenReturn(1.0);
            when(config.getSoundPitch()).thenReturn(1.0);

            SoundUtil.playPageSound(player, config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.UI_BUTTON_CLICK),
                    eq(0.5f),  // half volume
                    eq(1.0f)
            );
        }

        @Test
        @DisplayName("Should scale volume from config")
        void scalesVolumeFromConfig() {
            when(config.isSoundEnabled()).thenReturn(true);
            when(config.getSoundVolume()).thenReturn(0.6);
            when(config.getSoundPitch()).thenReturn(1.5);

            SoundUtil.playPageSound(player, config);

            verify(player).playSound(
                    any(Location.class),
                    eq(Sound.UI_BUTTON_CLICK),
                    eq(0.3f),  // 0.6 * 0.5
                    eq(1.5f)
            );
        }

        @Test
        @DisplayName("Should not play when sound disabled")
        void doesNotPlayWhenDisabled() {
            when(config.isSoundEnabled()).thenReturn(false);

            SoundUtil.playPageSound(player, config);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    // ==================== Constructor ====================

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("SoundUtil should not be instantiable (utility class)")
        void notInstantiable() throws Exception {
            java.lang.reflect.Constructor<SoundUtil> constructor =
                    SoundUtil.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();

            constructor.setAccessible(true);
            // Verify the constructor can be called (no exception expected from the constructor itself)
            assertThatCode(constructor::newInstance).doesNotThrowAnyException();
        }
    }
}
