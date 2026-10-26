package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.RemoteActionLog;

/**
 * 22-23 shutdown-window round: no remote state change happens after {@code UltiTools#onDisable} closes the action log.
 * The shutdown signal engages at {@code onDisable}'s first step, and that step waits for every change that entered
 * earlier to return its permit, so each one and its action-log entry is done before the action log closes.
 */
@DisplayName("Shutdown window: onDisable waits for in-flight remote changes before the action log closes")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PanelChangeBarrierTest {

    @BeforeEach
    void setUp() {
        PluginInitiationUtils.startAcceptingPanelMessages();
    }

    @AfterEach
    void tearDown() {
        PluginInitiationUtils.startAcceptingPanelMessages();
    }

    @Test
    @DisplayName("stopAcceptingPanelMessages returns only after an in-flight change returned its permit")
    void stopWaitsForAnInFlightChange() throws Exception {
        assertThat(PluginInitiationUtils.enterPanelChange()).isTrue();
        final CountDownLatch stopped = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            PluginInitiationUtils.stopAcceptingPanelMessages();
            stopped.countDown();
        }, "PanelChangeBarrierTest-stop");
        stopper.start();
        try {
            assertThat(stopped.await(500, TimeUnit.MILLISECONDS)).as("still waiting for the change").isFalse();
            assertThat(PluginInitiationUtils.isAcceptingPanelMessages()).as("the signal is already off").isFalse();
        } finally {
            PluginInitiationUtils.exitPanelChange();
        }
        assertThat(stopped.await(4, TimeUnit.SECONDS)).as("returns once the permit is back").isTrue();
        stopper.join(4000L);
    }

    @Test
    @DisplayName("after the first step no change enters; onEnable opens the signal again")
    void noChangeEntersAfterTheFirstStep() {
        PluginInitiationUtils.stopAcceptingPanelMessages();
        assertThat(PluginInitiationUtils.enterPanelChange()).isFalse();
        PluginInitiationUtils.startAcceptingPanelMessages();
        assertThat(PluginInitiationUtils.enterPanelChange()).isTrue();
        PluginInitiationUtils.exitPanelChange();
    }

    @Test
    @DisplayName("control: with nothing in flight the first step returns at once")
    void stopWithNothingInFlightReturnsAtOnce() {
        long started = System.nanoTime();
        PluginInitiationUtils.stopAcceptingPanelMessages();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000L);
    }

    @Test
    @DisplayName("onDisable: a change in flight when the disable starts finishes, and is audited, before the action "
            + "log closes; none starts afterwards")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // sets onDisable's private fields on the mocked plugin
    void onDisableClosesTheActionLogAfterInFlightChanges() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        try {
            final List<String> order = new CopyOnWriteArrayList<>();
            UltiTools plugin = mock(UltiTools.class);
            doCallRealMethod().when(plugin).onDisable();
            when(plugin.getLogger()).thenReturn(Logger.getLogger("PanelChangeBarrierTest"));
            when(plugin.getCommandManager()).thenReturn(mock(CommandManager.class));
            RemoteActionLog actionLog = mock(RemoteActionLog.class);
            final AtomicBoolean enteredAfterClose = new AtomicBoolean();
            doAnswer(invocation -> {
                order.add("action log closed");
                if (PluginInitiationUtils.enterPanelChange()) {
                    enteredAfterClose.set(true);
                    PluginInitiationUtils.exitPanelChange();
                }
                return null;
            }).when(actionLog).shutdown();
            Field field = UltiTools.class.getDeclaredField("remoteActionLog");
            field.setAccessible(true);
            field.set(plugin, actionLog);

            final CountDownLatch inFlight = new CountDownLatch(1);
            Thread change = new Thread(() -> {
                if (!PluginInitiationUtils.enterPanelChange()) {
                    order.add("change refused");
                    inFlight.countDown();
                    return;
                }
                try {
                    inFlight.countDown();
                    Thread.sleep(400L);
                    order.add("change written and audited");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    PluginInitiationUtils.exitPanelChange();
                }
            }, "PanelChangeBarrierTest-change");
            change.start();
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            plugin.onDisable();
            change.join(5000L);

            assertThat(order).containsExactly("change written and audited", "action log closed");
            assertThat(enteredAfterClose.get()).as("no change enters once the action log closes").isFalse();
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }
}
