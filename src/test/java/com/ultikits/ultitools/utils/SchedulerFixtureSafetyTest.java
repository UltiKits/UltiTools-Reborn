package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import com.ultikits.ultitools.stub.UltiCloudStub;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

@Timeout(30)
class SchedulerFixtureSafetyTest {
    @TempDir
    Path temp;
    private ServerMock server;
    private Scheduler installed;
    private UltiTools previousPlugin;

    @BeforeEach
    void setUp() {
        previousPlugin = UltiTools.getInstance();
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() throws NoSuchFieldException {
        try {
            if (installed != null) {
                FrameworkScheduling.uninstall(installed);
            }
            CloudTestAccess.reset();
        } finally {
            try {
                MockBukkitHelper.safeUnmock();
            } finally {
                ReflectionUtil.setFieldValue(null, UltiTools.class.getDeclaredField("ultiTools"), previousPlugin);
            }
        }
    }

    @Test
    void failedStubStartUninstallsTheFrameworkScheduler() {
        V2StubSession session = new V2StubSession(temp, server);
        IOException failure = new IOException("synthetic stub start failure");
        try (MockedStatic<UltiCloudStub> stub = mockStatic(UltiCloudStub.class)) {
            stub.when(() -> UltiCloudStub.start(any())).thenAnswer(call -> {
                installed = FrameworkScheduling.scheduler();
                throw failure;
            });
            assertThatThrownBy(() -> session.start(null)).isSameAs(failure);
            assertNotInstalled();
        }
    }

    @Test
    void failedSessionShutdownStillUninstallsTheFrameworkScheduler() throws Exception {
        V2StubSession session = new V2StubSession(temp, server);
        installed = mock(Scheduler.class);
        FrameworkScheduling.install(installed);
        CloudFeatures features = mock(CloudFeatures.class);
        IllegalStateException failure = new IllegalStateException("synthetic shutdown failure");
        doThrow(failure).when(features).shutdown();
        ReflectionUtil.setFieldValue(session, "features", features);
        ReflectionUtil.setFieldValue(session, "scheduler", installed);
        ReflectionUtil.setFieldValue(session, "schedulerOwner", installed);
        ReflectionUtil.setFieldValue(session, "schedulerEngine", mock(PaperSchedulerEngine.class));
        assertThatThrownBy(session::close).isSameAs(failure);
        assertNotInstalled();
    }

    @Test
    void failedEngineRetirementStillUninstallsTheTestSession() throws Exception {
        FrameworkSchedulerTestSession session = new FrameworkSchedulerTestSession();
        installed = FrameworkScheduling.scheduler();
        PaperSchedulerEngine engine = mock(PaperSchedulerEngine.class);
        IllegalStateException failure = new IllegalStateException("synthetic retirement failure");
        doThrow(failure).when(engine).retire(installed);
        ReflectionUtil.setFieldValue(session, "engine", engine);
        assertThatThrownBy(session::close).isSameAs(failure);
        assertNotInstalled();
    }

    private static void assertNotInstalled() {
        assertThatThrownBy(FrameworkScheduling::scheduler).isInstanceOf(IllegalStateException.class)
                .hasMessage("Framework scheduler is not installed");
    }
}
