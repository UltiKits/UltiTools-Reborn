package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;

/**
 * 22-23 second verification: a permit from {@link PluginInitiationUtils#enterPanelChange()} is held until the work it
 * covers ends, including the work a request hands to another thread. One case per hand-off site: a
 * {@code module.request} handed to the responder registry's worker, and a {@code player.link.completed} handed to the
 * link handler thread. Each was admitted before the disable; {@code stopAcceptingPanelMessages} (which
 * {@code UltiTools#onDisable} runs before it closes the action log) must not return while the handed-off change is
 * still pending, and the change must end before it returns. A handler that never ends still cannot hold the disable
 * longer than {@link PluginInitiationUtils#PANEL_CHANGE_DRAIN_MS}.
 */
@DisplayName("Shutdown window: work handed to another thread holds the disable until it ends")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ShutdownHandOffTest {

    private static final long STILL_WAITING_MS = 400L;

    @TempDir
    Path temp;

    private ShutdownPanelMessageTracerTest.ValidatingServer server;
    private V2StubSession session;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicLong changedAt = new AtomicLong();
    private String userDir;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        userDir = System.getProperty("user.dir");
        Files.createDirectories(temp.resolve("server"));
        System.setProperty("user.dir", temp.resolve("server").toAbsolutePath().toString());
        server = MockBukkit.mock(new ShutdownPanelMessageTracerTest.ValidatingServer());
        session = new V2StubSession(temp, server).productionMainThread();
        session.responders().registerResponder("ultimail.inbox.list", data -> {
            started.countDown();
            awaitRelease();
            changedAt.set(System.nanoTime()); // the module's state change
            return CompletableFuture.completedFuture(new JsonObject());
        }, "UltiMail");
        session.start(null);
        session.reply("status", 1);
        session.await(() -> server.getScheduler().enabledCalls.contains("runTaskTimer"), "main timer");
        CloudFeatures.installed().playerLinks().setHandler(mock(UltiToolsPlugin.class), completion -> {
            started.countDown();
            awaitRelease();
            changedAt.set(System.nanoTime()); // the module's account creation
            return CompletableFuture.completedFuture(CloudPlayerLinks.GameAccountResult.CREATED);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        PluginInitiationUtils.startAcceptingPanelMessages();
        session.close();
        server.getScheduler().shutdown();
        MockBukkitHelper.safeUnmock();
        System.setProperty("user.dir", userDir);
    }

    @Test
    @DisplayName("module.request: the disable waits for the responder the request was handed to")
    void moduleResponderHoldsTheDisable() throws Exception {
        session.send("module.request", "ultimail", null);
        assertWaitsForTheHandedOffChange();
    }

    @Test
    @DisplayName("player.link.completed: the disable waits for the link handler the completion was handed to")
    void linkHandlerHoldsTheDisable() throws Exception {
        session.stub().gateway().send("player.link.completed", null, null, null, "01JTESTSERVER0000000000009");
        assertWaitsForTheHandedOffChange();
        session.await(() -> session.stub().gateway().received("player.link.completed.result").size() == 1,
                "the receipt sent before the disable went on");
    }

    @Test
    @DisplayName("module.request: a responder that never ends holds the disable no longer than the bound")
    void stalledResponderIsBounded() throws Exception {
        session.send("module.request", "ultimail", null);
        assertBounded();
    }

    @Test
    @DisplayName("player.link.completed: a link handler that never ends holds the disable no longer than the bound")
    void stalledLinkHandlerIsBounded() throws Exception {
        session.stub().gateway().send("player.link.completed", null, null, null, "01JTESTSERVER0000000000009");
        assertBounded();
    }

    private void assertWaitsForTheHandedOffChange() throws Exception {
        assertThat(started.await(10, TimeUnit.SECONDS)).as("the handed-off work started").isTrue();
        final AtomicLong stoppedAt = new AtomicLong();
        Thread stopper = new Thread(() -> {
            PluginInitiationUtils.stopAcceptingPanelMessages();
            stoppedAt.set(System.nanoTime());
        }, "ShutdownHandOffTest-stop");
        stopper.start();
        Thread.sleep(STILL_WAITING_MS);
        assertThat(stoppedAt.get()).as("the disable still waits for the pending change").isZero();
        assertThat(PluginInitiationUtils.isAcceptingPanelMessages()).as("the signal is already off").isFalse();
        release.countDown();
        stopper.join(PluginInitiationUtils.PANEL_CHANGE_DRAIN_MS);
        assertThat(stoppedAt.get()).as("the disable went on once the change ended").isNotZero();
        assertThat(changedAt.get()).as("the change happened").isNotZero();
        assertThat(changedAt.get()).as("no change after the action log closes").isLessThan(stoppedAt.get());
    }

    private void assertBounded() throws Exception {
        assertThat(started.await(10, TimeUnit.SECONDS)).as("the handed-off work started").isTrue();
        long t0 = System.nanoTime();
        PluginInitiationUtils.stopAcceptingPanelMessages();
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(waited).as("waited for the stalled handler, then went on")
                .isBetween(PluginInitiationUtils.PANEL_CHANGE_DRAIN_MS - 100L,
                        PluginInitiationUtils.PANEL_CHANGE_DRAIN_MS + 1500L);
    }

    private void awaitRelease() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
