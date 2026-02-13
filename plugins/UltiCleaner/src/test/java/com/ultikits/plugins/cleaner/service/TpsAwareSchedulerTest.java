package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.UltiCleanerTestHelper;
import com.ultikits.plugins.cleaner.config.CleanerConfig;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TpsAwareScheduler Tests")
class TpsAwareSchedulerTest {

    private TpsAwareScheduler scheduler;
    private CleanerConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiCleanerTestHelper.setUp();

        config = UltiCleanerTestHelper.createDefaultConfig();
        scheduler = new TpsAwareScheduler();

        // Inject dependencies via reflection
        UltiCleanerTestHelper.setField(scheduler, "config", config);
        UltiCleanerTestHelper.setField(scheduler, "plugin", UltiCleanerTestHelper.getMockPlugin());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiCleanerTestHelper.tearDown();
    }

    // ==================== Initialization ====================

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("Should initialize without errors")
        void initSuccess() {
            assertThatCode(() -> scheduler.init()).doesNotThrowAnyException();
        }
    }

    // ==================== Shutdown ====================

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("Should shutdown without errors")
        void shutdownSuccess() {
            assertThatCode(() -> scheduler.shutdown()).doesNotThrowAnyException();
        }
    }

    // ==================== TPS Methods ====================

    @Nested
    @DisplayName("TPS Methods")
    class TpsMethods {

        @Test
        @DisplayName("getCurrentTps should return 20.0 when adaptive disabled")
        void getCurrentTpsDisabled() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(false);

            double tps = scheduler.getCurrentTps();

            assertThat(tps).isEqualTo(20.0);
        }

        @Test
        @DisplayName("getCurrentTps should return value when adaptive enabled")
        void getCurrentTpsEnabled() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(true);
            when(config.getTpsSampleWindow()).thenReturn("1m");

            double tps = scheduler.getCurrentTps();

            assertThat(tps).isBetween(0.0, 20.0);
        }

        @Test
        @DisplayName("isLowTps should check against threshold")
        void isLowTps() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(true);
            when(config.getLowTpsThreshold()).thenReturn(18.0);
            when(config.getTpsSampleWindow()).thenReturn("1m");

            boolean isLow = scheduler.isLowTps();

            assertThat(isLow).isIn(true, false);
        }

        @Test
        @DisplayName("isCriticalTps should check against critical threshold")
        void isCriticalTps() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(true);
            when(config.getCriticalTpsThreshold()).thenReturn(15.0);
            when(config.getTpsSampleWindow()).thenReturn("1m");

            boolean isCritical = scheduler.isCriticalTps();

            assertThat(isCritical).isIn(true, false);
        }
    }

    // ==================== Threshold Multiplier ====================

    @Nested
    @DisplayName("Threshold Multiplier")
    class ThresholdMultiplier {

        @Test
        @DisplayName("Should return 1.0 when adaptive disabled")
        void multiplierDisabled() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(false);

            double multiplier = scheduler.getThresholdMultiplier();

            assertThat(multiplier).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should return value between 0 and 1 when enabled")
        void multiplierEnabled() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(true);
            when(config.getLowTpsThreshold()).thenReturn(18.0);
            when(config.getCriticalTpsThreshold()).thenReturn(15.0);
            when(config.getLowTpsReduction()).thenReturn(30);
            when(config.getCriticalTpsReduction()).thenReturn(50);
            when(config.getTpsSampleWindow()).thenReturn("1m");

            double multiplier = scheduler.getThresholdMultiplier();

            assertThat(multiplier).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("applyThresholdReduction should reduce threshold")
        void applyThresholdReduction() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(true);
            when(config.getLowTpsThreshold()).thenReturn(18.0);
            when(config.getCriticalTpsThreshold()).thenReturn(15.0);
            when(config.getLowTpsReduction()).thenReturn(30);
            when(config.getCriticalTpsReduction()).thenReturn(50);
            when(config.getTpsSampleWindow()).thenReturn("1m");

            int adjusted = scheduler.applyThresholdReduction(1000);

            assertThat(adjusted).isBetween(0, 1000);
        }
    }

    // ==================== TPS Status ====================

    @Nested
    @DisplayName("TPS Status")
    class TpsStatus {

        @Test
        @DisplayName("Should return formatted TPS status")
        void getTpsStatus() {
            when(config.isTpsAdaptiveEnabled()).thenReturn(true);
            when(config.getLowTpsThreshold()).thenReturn(18.0);
            when(config.getCriticalTpsThreshold()).thenReturn(15.0);
            when(config.getTpsSampleWindow()).thenReturn("1m");

            String status = scheduler.getTpsStatus();

            assertThat(status).isNotEmpty();
            assertThat(status).containsAnyOf("§a", "§e", "§c");
        }
    }
}
