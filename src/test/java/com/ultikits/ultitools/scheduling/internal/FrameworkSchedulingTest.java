package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

@Timeout(30)
class FrameworkSchedulingTest {
    private ServerMock server;
    private Scheduler installed;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (installed != null) {
            FrameworkScheduling.uninstall(installed);
        }
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void rootRegistration_resolvesInterfaceAndRetiresOnContainerClose() throws Exception {
        UltiTools host = mock(UltiTools.class);
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        DependenceManagers managers = mock(DependenceManagers.class, CALLS_REAL_METHODS);
        SimpleContainer root = new SimpleContainer();
        Field field = DependenceManagers.class.getDeclaredField("context");
        field.setAccessible(true);
        field.set(managers, root);
        Method initialize = DependenceManagers.class.getDeclaredMethod("initScheduling", UltiTools.class);
        initialize.setAccessible(true);
        initialize.invoke(managers, host);
        installed = root.getBean(Scheduler.class);
        assertThat(installed.owner()).isEqualTo("UltiTools");
        assertThat(root.getBean(Scheduler.class.getName())).isSameAs(installed);
        assertThat(root.getBean("frameworkScheduler")).isInstanceOf(FrameworkSchedulerBean.class);
        assertThat(FrameworkScheduling.scheduler()).isSameAs(installed);
        AtomicInteger runs = new AtomicInteger();
        TaskHandle task = installed.global().run(runs::incrementAndGet);
        managers.closeContext();
        assertThat(task.state()).isEqualTo(TaskState.CANCELLED);
        server.getScheduler().performOneTick();
        assertThat(runs).hasValue(0);
        assertThatThrownBy(() -> installed.global().run(runs::incrementAndGet))
                .isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(FrameworkScheduling::scheduler)
                .isInstanceOf(IllegalStateException.class).hasMessage("Framework scheduler is not installed");
    }

    @Test
    void staleRootDestruction_doesNotClearReplacementBridge() {
        PaperSchedulerEngine engine = new PaperSchedulerEngine(MockBukkit.createMockPlugin(),
                Logger.getAnonymousLogger(), mock(PaperBackend.class));
        Scheduler old = engine.newOwner("UltiTools");
        installed = engine.newOwner("UltiTools");
        FrameworkScheduling.install(old);
        FrameworkScheduling.install(installed);
        new FrameworkSchedulerBean(engine, old).close();
        assertThat(FrameworkScheduling.scheduler()).isSameAs(installed);
        assertThatThrownBy(() -> old.global().run(() -> { /* Retired owners reject even empty tasks. */ }))
                .isInstanceOf(RejectedExecutionException.class);
    }
}
