package com.ultikits.plugins.worlds.config;

import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WorldConfig configuration entity.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("WorldConfig Tests")
class WorldConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have default world as 'world'")
        void defaultWorld() {
            WorldConfig config = createRealConfig();
            assertThat(config.getDefaultWorld()).isEqualTo("world");
        }

        @Test
        @DisplayName("Should have protected worlds list")
        void protectedWorlds() {
            WorldConfig config = createRealConfig();
            assertThat(config.getProtectedWorlds())
                    .containsExactly("world", "world_nether", "world_the_end");
        }

        @Test
        @DisplayName("Should have empty load worlds list by default")
        void loadWorldsOnStart() {
            WorldConfig config = createRealConfig();
            assertThat(config.getLoadWorldsOnStart()).isEmpty();
        }

        @Test
        @DisplayName("Should have auto-unload disabled by default")
        void autoUnloadDisabled() {
            WorldConfig config = createRealConfig();
            assertThat(config.isAutoUnloadEmptyWorlds()).isFalse();
        }

        @Test
        @DisplayName("Should have 60 second check interval")
        void checkInterval() {
            WorldConfig config = createRealConfig();
            assertThat(config.getEmptyWorldCheckInterval()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should unload after 300 seconds")
        void unloadAfter() {
            WorldConfig config = createRealConfig();
            assertThat(config.getEmptyWorldUnloadAfter()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should have teleport enabled by default")
        void tpEnabled() {
            WorldConfig config = createRealConfig();
            assertThat(config.isTpToWorldEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should not require permission per world by default")
        void permissionPerWorld() {
            WorldConfig config = createRealConfig();
            assertThat(config.isPermissionPerWorld()).isFalse();
        }

        @Test
        @DisplayName("Should have 10 second teleport cooldown")
        void tpCooldown() {
            WorldConfig config = createRealConfig();
            assertThat(config.getTpCooldown()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should use spawn location by default")
        void useSpawnLocation() {
            WorldConfig config = createRealConfig();
            assertThat(config.isUseSpawnLocation()).isTrue();
        }

        @Test
        @DisplayName("Should have inventory isolation disabled by default")
        void inventoryIsolation() {
            WorldConfig config = createRealConfig();
            assertThat(config.isInventoryIsolation()).isFalse();
        }

        @Test
        @DisplayName("Should separate inventory by default")
        void separateInventory() {
            WorldConfig config = createRealConfig();
            assertThat(config.isSeparateInventory()).isTrue();
        }

        @Test
        @DisplayName("Should separate ender chest by default")
        void separateEnderChest() {
            WorldConfig config = createRealConfig();
            assertThat(config.isSeparateEnderChest()).isTrue();
        }

        @Test
        @DisplayName("Should not separate experience by default")
        void separateExperience() {
            WorldConfig config = createRealConfig();
            assertThat(config.isSeparateExperience()).isFalse();
        }

        @Test
        @DisplayName("Should not separate health by default")
        void separateHealth() {
            WorldConfig config = createRealConfig();
            assertThat(config.isSeparateHealth()).isFalse();
        }

        @Test
        @DisplayName("Should not separate hunger by default")
        void separateHunger() {
            WorldConfig config = createRealConfig();
            assertThat(config.isSeparateHunger()).isFalse();
        }

        @Test
        @DisplayName("Should not separate effects by default")
        void separateEffects() {
            WorldConfig config = createRealConfig();
            assertThat(config.isSeparateEffects()).isFalse();
        }

        @Test
        @DisplayName("Should have shared world groups")
        void sharedWorldGroups() {
            WorldConfig config = createRealConfig();
            assertThat(config.getSharedWorldGroups())
                    .containsExactly("world,world_nether,world_the_end");
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update default world")
        void setDefaultWorld() {
            WorldConfig config = createRealConfig();
            config.setDefaultWorld("custom_world");
            assertThat(config.getDefaultWorld()).isEqualTo("custom_world");
        }

        @Test
        @DisplayName("Should update protected worlds")
        void setProtectedWorlds() {
            WorldConfig config = createRealConfig();
            List<String> newList = Arrays.asList("world");
            config.setProtectedWorlds(newList);
            assertThat(config.getProtectedWorlds()).isEqualTo(newList);
        }

        @Test
        @DisplayName("Should update auto-unload enabled")
        void setAutoUnloadEnabled() {
            WorldConfig config = createRealConfig();
            config.setAutoUnloadEmptyWorlds(true);
            assertThat(config.isAutoUnloadEmptyWorlds()).isTrue();
        }

        @Test
        @DisplayName("Should update check interval")
        void setCheckInterval() {
            WorldConfig config = createRealConfig();
            config.setEmptyWorldCheckInterval(120);
            assertThat(config.getEmptyWorldCheckInterval()).isEqualTo(120);
        }

        @Test
        @DisplayName("Should update unload after")
        void setUnloadAfter() {
            WorldConfig config = createRealConfig();
            config.setEmptyWorldUnloadAfter(600);
            assertThat(config.getEmptyWorldUnloadAfter()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should update teleport enabled")
        void setTpEnabled() {
            WorldConfig config = createRealConfig();
            config.setTpToWorldEnabled(false);
            assertThat(config.isTpToWorldEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update permission per world")
        void setPermissionPerWorld() {
            WorldConfig config = createRealConfig();
            config.setPermissionPerWorld(true);
            assertThat(config.isPermissionPerWorld()).isTrue();
        }

        @Test
        @DisplayName("Should update teleport cooldown")
        void setTpCooldown() {
            WorldConfig config = createRealConfig();
            config.setTpCooldown(30);
            assertThat(config.getTpCooldown()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should update use spawn location")
        void setUseSpawnLocation() {
            WorldConfig config = createRealConfig();
            config.setUseSpawnLocation(false);
            assertThat(config.isUseSpawnLocation()).isFalse();
        }

        @Test
        @DisplayName("Should update inventory isolation")
        void setInventoryIsolation() {
            WorldConfig config = createRealConfig();
            config.setInventoryIsolation(true);
            assertThat(config.isInventoryIsolation()).isTrue();
        }

        @Test
        @DisplayName("Should update separate inventory")
        void setSeparateInventory() {
            WorldConfig config = createRealConfig();
            config.setSeparateInventory(false);
            assertThat(config.isSeparateInventory()).isFalse();
        }

        @Test
        @DisplayName("Should update separate ender chest")
        void setSeparateEnderChest() {
            WorldConfig config = createRealConfig();
            config.setSeparateEnderChest(false);
            assertThat(config.isSeparateEnderChest()).isFalse();
        }

        @Test
        @DisplayName("Should update separate experience")
        void setSeparateExperience() {
            WorldConfig config = createRealConfig();
            config.setSeparateExperience(true);
            assertThat(config.isSeparateExperience()).isTrue();
        }

        @Test
        @DisplayName("Should update separate health")
        void setSeparateHealth() {
            WorldConfig config = createRealConfig();
            config.setSeparateHealth(true);
            assertThat(config.isSeparateHealth()).isTrue();
        }

        @Test
        @DisplayName("Should update separate hunger")
        void setSeparateHunger() {
            WorldConfig config = createRealConfig();
            config.setSeparateHunger(true);
            assertThat(config.isSeparateHunger()).isTrue();
        }

        @Test
        @DisplayName("Should update separate effects")
        void setSeparateEffects() {
            WorldConfig config = createRealConfig();
            config.setSeparateEffects(true);
            assertThat(config.isSeparateEffects()).isTrue();
        }

        @Test
        @DisplayName("Should update shared world groups")
        void setSharedWorldGroups() {
            WorldConfig config = createRealConfig();
            List<String> newGroups = Arrays.asList("pvp_world,arena", "creative,plot");
            config.setSharedWorldGroups(newGroups);
            assertThat(config.getSharedWorldGroups()).isEqualTo(newGroups);
        }
    }

    /**
     * Create a real WorldConfig using a mock path to avoid AbstractConfigEntity I/O.
     * We use Mockito spy to bypass the superclass constructor's file loading.
     */
    private WorldConfig createRealConfig() {
        WorldConfig config = mock(WorldConfig.class,
                withSettings().useConstructor("config/worlds.yml").defaultAnswer(CALLS_REAL_METHODS));
        return config;
    }
}
