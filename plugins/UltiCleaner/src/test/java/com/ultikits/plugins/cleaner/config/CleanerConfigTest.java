package com.ultikits.plugins.cleaner.config;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CleanerConfig Tests")
class CleanerConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have item cleanup enabled by default")
        void itemCleanEnabled() {
            CleanerConfig config = createRealConfig();
            assertThat(config.isItemCleanEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have 300 second default item interval")
        void itemCleanInterval() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getItemCleanInterval()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should have entity cleanup enabled by default")
        void entityCleanEnabled() {
            CleanerConfig config = createRealConfig();
            assertThat(config.isEntityCleanEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have 600 second default entity interval")
        void entityCleanInterval() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getEntityCleanInterval()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should have smart cleanup disabled by default")
        void smartCleanEnabled() {
            CleanerConfig config = createRealConfig();
            assertThat(config.isSmartCleanEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have item threshold of 2000")
        void itemMaxThreshold() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getItemMaxThreshold()).isEqualTo(2000);
        }

        @Test
        @DisplayName("Should have mob threshold of 1000")
        void mobMaxThreshold() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getMobMaxThreshold()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Should have batch size of 50")
        void cleanBatchSize() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getCleanBatchSize()).isEqualTo(50);
        }

        @Test
        @DisplayName("Should have TPS adaptive enabled by default")
        void tpsAdaptiveEnabled() {
            CleanerConfig config = createRealConfig();
            assertThat(config.isTpsAdaptiveEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have low TPS threshold of 18.0")
        void lowTpsThreshold() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getLowTpsThreshold()).isEqualTo(18.0);
        }

        @Test
        @DisplayName("Should have critical TPS threshold of 15.0")
        void criticalTpsThreshold() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getCriticalTpsThreshold()).isEqualTo(15.0);
        }

        @Test
        @DisplayName("Should have chunk unload disabled by default")
        void chunkUnloadEnabled() {
            CleanerConfig config = createRealConfig();
            assertThat(config.isChunkUnloadEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have max chunk distance of 20")
        void maxChunkDistance() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getMaxChunkDistance()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should have item whitelist with default values")
        void itemWhitelist() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getItemWhitelist()).isNotEmpty();
            assertThat(config.getItemWhitelist()).contains("DIAMOND", "EMERALD");
        }

        @Test
        @DisplayName("Should have entity types list with default values")
        void entityTypes() {
            CleanerConfig config = createRealConfig();
            assertThat(config.getEntityTypes()).isNotEmpty();
            assertThat(config.getEntityTypes()).contains("ZOMBIE", "SKELETON");
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update item clean enabled")
        void setItemCleanEnabled() {
            CleanerConfig config = createRealConfig();
            config.setItemCleanEnabled(false);
            assertThat(config.isItemCleanEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update item clean interval")
        void setItemCleanInterval() {
            CleanerConfig config = createRealConfig();
            config.setItemCleanInterval(600);
            assertThat(config.getItemCleanInterval()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should update entity clean enabled")
        void setEntityCleanEnabled() {
            CleanerConfig config = createRealConfig();
            config.setEntityCleanEnabled(false);
            assertThat(config.isEntityCleanEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update smart clean enabled")
        void setSmartCleanEnabled() {
            CleanerConfig config = createRealConfig();
            config.setSmartCleanEnabled(true);
            assertThat(config.isSmartCleanEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update item max threshold")
        void setItemMaxThreshold() {
            CleanerConfig config = createRealConfig();
            config.setItemMaxThreshold(3000);
            assertThat(config.getItemMaxThreshold()).isEqualTo(3000);
        }

        @Test
        @DisplayName("Should update TPS adaptive enabled")
        void setTpsAdaptiveEnabled() {
            CleanerConfig config = createRealConfig();
            config.setTpsAdaptiveEnabled(false);
            assertThat(config.isTpsAdaptiveEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update chunk unload enabled")
        void setChunkUnloadEnabled() {
            CleanerConfig config = createRealConfig();
            config.setChunkUnloadEnabled(true);
            assertThat(config.isChunkUnloadEnabled()).isTrue();
        }
    }

    /**
     * Create a real CleanerConfig instance.
     * The constructor sets default field values via field initializers.
     */
    private CleanerConfig createRealConfig() {
        return new CleanerConfig();
    }
}
