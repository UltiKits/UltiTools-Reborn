package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class RuntimeOwnerIdentityTest {
    private ManualScheduler.Engine clock;
    private UiRuntime runtime;

    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        clock = new ManualScheduler.Engine();
        runtime = new UiRuntime(new RecordingScheduler(clock.scheduler("UiRuntime")), new InMemoryContainerRuntime(),
                Logger.getAnonymousLogger(), Thread.currentThread());
    }
    @AfterEach void tearDown() {
        try { runtime.close(); } finally {
            clock.close();
            MockBukkitHelper.safeUnmock();
        }
    }
    @Test void sameNameCopies_unloadOnlyTheirOwnContainers() {
        UiService oldCopy = runtime.service("SameModule");
        UiService newCopy = runtime.service("SameModule");
        SlotContainer oldContainer = oldCopy.container(ContainerSpec.template(1).build());
        SlotContainer newContainer = newCopy.container(ContainerSpec.template(1).build());
        ContainerState initial = newContainer.state();
        runtime.unload(oldCopy);
        assertThat(oldContainer.state()).isEqualTo(ContainerState.ENDED);
        assertThat(newContainer.state()).isEqualTo(initial).isNotEqualTo(ContainerState.ENDED);
        SlotContainer later = newCopy.container(ContainerSpec.template(1).build());
        runtime.unload(oldCopy);
        assertThat(later.state()).isEqualTo(initial);
        runtime.unload(newCopy);
        assertThat(newContainer.state()).isEqualTo(ContainerState.ENDED);
        assertThat(later.state()).isEqualTo(ContainerState.ENDED);
    }
}
