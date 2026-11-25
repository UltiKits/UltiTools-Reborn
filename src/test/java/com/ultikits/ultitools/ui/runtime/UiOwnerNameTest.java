package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Timeout(15)
class UiOwnerNameTest {
    @Test
    void nameIsDiagnosticAndSurvivesSessionRemovalButRejectsForeignSessions() {
        MockBukkit.mock();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine();
             UiRuntime runtime = runtime(clock);
             UiRuntime other = runtime(clock)) {
            UiService first = runtime.service("alpha");
            UiService second = runtime.service("alpha");
            UiSession<Boolean> session = first.show(MockBukkit.getMock().addPlayer(),
                    Confirm.builder(Text.plain("Owner")).build());
            UiSession<Boolean> sameName = second.show(MockBukkit.getMock().addPlayer(),
                    Confirm.builder(Text.plain("Owner")).build());
            assertThat(runtime.ownerName(session)).isEqualTo("alpha");
            assertThat(runtime.ownerName(sameName)).isEqualTo("alpha");
            runtime.unload(first);
            assertThat(session.result().toCompletableFuture()).isDone();
            assertThat(runtime.ownerName(session)).isEqualTo("alpha");
            assertThatThrownBy(() -> other.ownerName(session)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.ownerName(mock(UiSession.class)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.ownerName(null)).isInstanceOf(NullPointerException.class);
        } finally {
            MockBukkit.unmock();
        }
    }

    private static UiRuntime runtime(ManualScheduler.Engine clock) {
        return new UiRuntime(clock.scheduler("OwnerTest"), new NoContainerRuntime(),
                Logger.getAnonymousLogger(), Thread.currentThread());
    }
}
