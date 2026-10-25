package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * {@link AgentOutboundFeeds} without a connection (plan 22-22 fix): the main-thread sampler of the v2 session.
 * MockBukkit treats the test thread as the server thread.
 */
@DisplayName("AgentOutboundFeeds: the v2 main-thread sampler")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test clears the UltiTools singleton
class AgentOutboundFeedsTest {

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        final Logger logger = Logger.getLogger("AgentOutboundFeedsTest");
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(logger));
    }

    @AfterEach
    void tearDown() throws Exception {
        MockBukkitHelper.safeUnmock();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static AgentOutboundFeeds feeds(final ServerMonitorManager monitor) {
        return new AgentOutboundFeeds(() -> monitor, () -> true, () -> false, () -> false, () -> null,
                System::currentTimeMillis, Runnable::run);
    }

    @Test
    @DisplayName("the first sample after a late first connection measures 20 ticks, not the time since enable")
    void firstSampleIgnoresTheIdleGapSinceTheMonitorWasCreated() throws Exception {
        ServerMonitorManager monitor = new ServerMonitorManager(); // created at plugin enable
        Thread.sleep(2000L); // the first connection comes later (on a real server: seconds to hours)
        AgentOutboundFeeds feeds = feeds(monitor);

        feeds.mainThreadSecond(); // the first 20-tick run on an idle server
        assertThat(monitor.getCurrentTPS()).as("an idle 20-TPS server").isGreaterThanOrEqualTo(19.9);

        // The sampler still measures: two seconds between two 20-tick runs is a slow second.
        Thread.sleep(2000L);
        feeds.mainThreadSecond();
        assertThat(monitor.getCurrentTPS()).as("a 2 s gap between runs").isLessThan(15.0);
    }
}
