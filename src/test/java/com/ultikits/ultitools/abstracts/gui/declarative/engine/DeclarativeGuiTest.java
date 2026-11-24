package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.ShowOptions;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Safe icon actions and a final service entry point replace the old event-return-value convention. */
@Timeout(30)
class DeclarativeGuiTest {
    private Player viewer;
    private GuiTestHarness harness;
    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState(); viewer = MockBukkit.mock().addPlayer(); harness = new GuiTestHarness();
    }
    @AfterEach void tearDown() { harness.close(); MockBukkitHelper.safeUnmock(); }

    @Test void defaultScreenHasOnlyAnIconAndNoTakeableBinding() {
        Page page = new Page(harness.ui(), harness, viewer);
        GridSession session = page.open();
        assertThat(session.current().bindings()).isEmpty();
        assertThat(session.current().items()).hasSize(1);
        assertThat(session.current().items().values()).allSatisfy(item -> assertThat(item.action()).isEmpty());
        assertThat(page.viewer()).isSameAs(viewer);
    }
    @Test void noThirdPartyBaseOrEventReturnValueHookRemains() {
        assertThat(DeclarativeGui.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(Arrays.stream(DeclarativeGui.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("onClick", "onOpen", "onClose", "onGuiClick", "onGuiOpen", "getPlayer");
    }
    @Test void openIsFinal() throws Exception {
        assertThat(Modifier.isFinal(DeclarativeGui.class.getDeclaredMethod("open").getModifiers())).isTrue();
    }
    @Test void firstFrameIsSynchronousAndLaterFramesKeepTheSession() {
        Page page = new Page(harness.ui(), harness, viewer);
        GridSession session = page.open();
        assertThat(page.builds).hasValue(1);
        assertThat(page.isInitialized()).isTrue();
        assertThat(page.open()).isSameAs(session);
        page.setState(() -> page.label = "next");
        assertThat(page.builds).hasValue(1);
        harness.drain();
        assertThat(page.builds).hasValue(2);
        assertThat(session.current().title()).isEqualTo(Text.plain("Test"));
        assertThat(GuiTestHarness.label(session.current(), 0)).isEqualTo("next");
        assertThat(session.isOpen()).isTrue();
    }
    @Test void closeDisposesBeforeCallingTheHookAndCanReopen() {
        Page page = new Page(harness.ui(), harness, viewer);
        GridSession session = harness.open(page);
        page.markNeedsBuild();
        session.close(); harness.drain();
        int builds = page.builds.get();
        page.markNeedsBuild(); harness.drain();
        assertThat(page.builds).hasValue(builds);
        assertThat(page.closed).isEqualTo(new UiResult.Closed<Ack>(CloseReason.CLOSED_BY_CODE));
        assertThat(page.initializedInHook).isFalse();
        assertThat(page.lifecycle).containsExactly("dispose", "hook");
        assertThat(page.closes).hasValue(1);
        assertThat(page.open()).isNotSameAs(session);
    }
    @Test void failedFirstBuildShowsNothingAndCanRetry() {
        UiService ui = mock(UiService.class);
        Page page = new Page(ui, harness, viewer); page.fail = true;
        assertThatThrownBy(page::open).isInstanceOf(IllegalStateException.class).hasMessage("build failed");
        verifyNoInteractions(ui);
        assertThat(page.isInitialized()).isFalse();
        page.fail = false;
        GridSession session = mock(GridSession.class);
        when(session.result()).thenReturn(new CompletableFuture<UiResult<Ack>>().minimalCompletionStage());
        when(ui.show(eq(viewer), any(Grid.class), any(ShowOptions.class))).thenReturn(session);
        assertThat(page.open()).isSameAs(session);
    }
    @Test void failedShowDisposesTheRenderer() {
        UiService ui = mock(UiService.class);
        when(ui.show(eq(viewer), any(Grid.class), any(ShowOptions.class))).thenThrow(new IllegalStateException("show failed"));
        Page page = new Page(ui, harness, viewer);
        assertThatThrownBy(page::open).isInstanceOf(IllegalStateException.class).hasMessage("show failed");
        assertThat(page.isInitialized()).isFalse();
        page.markNeedsBuild(); harness.drain();
        assertThat(page.builds).hasValue(1);
    }
    @Test void alreadyCompletedServiceResultStillDisposesAndCallsHook() {
        UiService ui = mock(UiService.class); GridSession session = mock(GridSession.class);
        when(session.result()).thenReturn(CompletableFuture.completedFuture(
                (UiResult<Ack>) new UiResult.Closed<Ack>(CloseReason.UNSUPPORTED)).minimalCompletionStage());
        when(ui.show(eq(viewer), any(Grid.class), any(ShowOptions.class))).thenReturn(session);
        Page page = new Page(ui, harness, viewer);
        assertThat(page.open()).isSameAs(session);
        assertThat(page.isInitialized()).isFalse();
        assertThat(page.closes).hasValue(1);
    }
    @Test void openingOffTheOwningThreadIsRejected() throws Exception {
        Page page = new Page(harness.ui(), harness, viewer);
        java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Throwable failure = worker.submit(() -> {
                try { page.open(); return null; } catch (IllegalStateException expected) { return expected; }
            }).get(2, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("owning thread");
        } finally { worker.shutdownNow(); }
        assertThat(page.builds).hasValue(0);
    }
    @Test void defaultCloseHookNeedsNoOverride() {
        DeclarativeGui page = new DeclarativeGui(harness.ui(), harness.scheduler(), viewer, "default", Text.plain("Test"), 1) {
            @Override public Widget build(BuildContext context) { return TextButton.builder().build(); }
        };
        harness.open(page);
        assertThatCode(() -> harness.close(page)).doesNotThrowAnyException();
        assertThat(page.isInitialized()).isFalse();
    }

    @Test void throwingCloseHookLogsOneWarningWithOwnerAndScreen() {
        Page page = new Page(harness.ui(), harness, viewer);
        page.hookFailure = new IllegalStateException("hook");
        harness.open(page);
        try (GuiLogCapture log = new GuiLogCapture(DeclarativeGui.class)) {
            harness.close(page);
            assertThat(log.records()).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).contains("declarative", "DeclarativeTests", "onGuiClose");
                assertThat(record.getThrown()).isSameAs(page.hookFailure);
            });
        }
    }

    @Test void throwingDisposalLogsOneWarningAndStillCallsHook() {
        Page page = new Page(harness.ui(), harness, viewer);
        page.disposalFailure = new IllegalStateException("dispose");
        harness.open(page);
        try (GuiLogCapture log = new GuiLogCapture(DeclarativeGui.class)) {
            harness.close(page);
            assertThat(log.records()).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).contains("declarative", "DeclarativeTests", "dispose");
                assertThat(record.getThrown()).isSameAs(page.disposalFailure);
            });
            assertThat(page.lifecycle).containsExactly("dispose", "hook");
            assertThat(page.closes).hasValue(1);
        }
    }

    @Test void ownerUnloadCancelsPendingFramesBeforeReturning() {
        assertEndingStopsFrames(CloseReason.OWNER_UNLOADED);
    }
    @Test void viewerQuitCancelsPendingFrames() {
        assertEndingStopsFrames(CloseReason.DISCONNECTED);
    }
    @Test void replacementCancelsPendingFrames() {
        assertEndingStopsFrames(CloseReason.REPLACED);
    }

    private void assertEndingStopsFrames(CloseReason reason) {
        Scheduler scheduler = mock(Scheduler.class);
        TaskContext context = spy(harness.scheduler().entity(viewer));
        List<TaskHandle> handles = new ArrayList<>();
        when(scheduler.entity(viewer)).thenReturn(context);
        doAnswer(call -> {
            TaskHandle handle = spy((TaskHandle) call.callRealMethod());
            handles.add(handle);
            return handle;
        }).when(context).run(any(Runnable.class));
        Page page = new Page(harness.ui(), scheduler, viewer);
        GridSession session = page.open(); harness.drain();
        if (reason == CloseReason.REPLACED) {
            harness.ui().show(viewer, Grid.builder(Text.plain("Replacement"), 1).build());
        }
        page.markNeedsBuild();
        assertThat(handles).hasSize(1);
        if (reason == CloseReason.OWNER_UNLOADED) {
            harness.fixture().unload("DeclarativeTests");
            assertThat(page.lifecycle).containsExactly("dispose", "hook");
        } else if (reason == CloseReason.DISCONNECTED) {
            harness.fixture().disconnect((PlayerMock) viewer);
        }
        harness.drain();
        assertThat(session.result().toCompletableFuture().getNow(null)).isEqualTo(new UiResult.Closed<Ack>(reason));
        assertThat(page.lifecycle).containsExactly("dispose", "hook");
        assertThat(page.builds).hasValue(1);
        assertThat(handles.get(0).completion().toCompletableFuture()).isCompletedExceptionally();
        if (reason != CloseReason.DISCONNECTED) {
            verify(handles.get(0)).cancel();
        }
        page.markNeedsBuild(); harness.drain();
        assertThat(handles).hasSize(1);
        assertThat(page.builds).hasValue(1);
        assertThat(page.closes).hasValue(1);
    }

    private static final class DisposalProbe extends StatefulWidget {
        private final Page page;
        DisposalProbe(Page page) { this.page = page; }
        @Override public State<DisposalProbe> createState() { return new DisposalState(page); }
    }

    private static final class DisposalState extends State<DisposalProbe> {
        private final Page page;
        DisposalState(Page page) { this.page = page; }
        @Override public Widget build(BuildContext context) {
            return TextButton.builder().text(Text.plain(page.label)).build();
        }
        @Override protected void dispose() {
            super.dispose();
            page.lifecycle.add("dispose");
            if (page.disposalFailure != null) { throw page.disposalFailure; }
        }
    }

    private static final class Page extends DeclarativeGui {
        private final AtomicInteger builds = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private String label = "first";
        private boolean fail;
        private UiResult<Ack> closed;
        private boolean initializedInHook;
        private final List<String> lifecycle = new ArrayList<>();
        private IllegalStateException hookFailure;
        private IllegalStateException disposalFailure;
        Page(UiService ui, GuiTestHarness harness, Player viewer) {
            this(ui, harness.scheduler(), viewer);
        }
        Page(UiService ui, Scheduler scheduler, Player viewer) {
            super(ui, scheduler, viewer, "declarative", Text.plain("Test"), 1);
        }
        @Override public Widget build(BuildContext context) {
            builds.incrementAndGet();
            if (fail) { throw new IllegalStateException("build failed"); }
            return new DisposalProbe(this);
        }
        @Override protected void onGuiClose(UiResult<Ack> result) {
            lifecycle.add("hook");
            initializedInHook = isInitialized(); closed = result; closes.incrementAndGet();
            if (hookFailure != null) { throw hookFailure; }
        }
    }
}
