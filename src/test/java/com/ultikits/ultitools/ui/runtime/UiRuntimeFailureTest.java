package com.ultikits.ultitools.ui.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class UiRuntimeFailureTest {
    private static final Text TITLE = Text.plain("Failure paths");
    private ManualScheduler.Engine clock;
    private RecordingScheduler scheduler;
    private UiRuntime runtime;
    private UiService service;
    private PlayerMock viewer;
    private ContainerRuntime containers;
    private UiBackend backend;
    private Logger logger;
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        viewer = MockBukkit.mock().addPlayer();
        clock = new ManualScheduler.Engine();
        scheduler = new RecordingScheduler(clock.scheduler("UiRuntime"));
        containers = mock(ContainerRuntime.class);
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record); }
            @Override public void flush() { /* Synchronous capture. */ }
            @Override public void close() { /* No resources. */ }
        });
        startRuntime(scheduler);
    }
    private void startRuntime(Scheduler selected) {
        runtime = new UiRuntime(selected, containers, logger, Thread.currentThread());
        service = runtime.service("TestModule");
        backend = mock(UiBackend.class);
        when(backend.supports(any(UiIntent.class), any())).thenReturn(true);
        runtime.register(backend);
    }
    @AfterEach void tearDown() {
        try { runtime.close(); } finally {
            clock.close();
            MockBukkitHelper.safeUnmock();
        }
    }
    private void settle() { clock.advanceTicks(4); }
    private static void assertClosed(UiSession<?> session, CloseReason reason) {
        assertThat(session.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(reason));
    }
    private SlotContainer container() {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(1);
        when(container.state()).thenReturn(ContainerState.EDITABLE);
        when(container.get(0)).thenReturn(ItemStack.empty());
        return container;
    }
    private static Grid grid(SlotContainer... containers) {
        Grid.Builder builder = Grid.builder(TITLE, 1);
        for (int index = 0; index < containers.length; index++) {
            builder.bind(GridSlot.ofIndex(index), containers[index], 0, BindingMode.VIEW);
        }
        return builder.build();
    }
    @ParameterizedTest @ValueSource(strings = {"prepare", "start", "finish"})
    void unloadStepError_stillClosesEverySessionAndFinishes(String step) {
        UiOwner owner = ((RuntimeUiService) service).ownerIdentity;
        AssertionError failure = new AssertionError(step);
        switch (step) {
            case "prepare" -> doThrow(failure).when(containers).prepareUnload(eq(owner), anyLong());
            case "start" -> doThrow(failure).when(containers).startUnloadCommits(owner);
            case "finish" -> doThrow(failure).when(containers).finishUnload(eq(owner), anyLong());
            default -> throw new AssertionError(step);
        }
        UiSession<Boolean> first = service.show(viewer, Confirm.builder(TITLE).build());
        UiSession<Boolean> second = service.show(MockBukkit.getMock().addPlayer(), Confirm.builder(TITLE).build());
        settle();
        assertThatCode(() -> runtime.unload(service)).doesNotThrowAnyException();
        assertClosed(first, CloseReason.OWNER_UNLOADED); assertClosed(second, CloseReason.OWNER_UNLOADED);
        verify(containers).finishUnload(eq(owner), anyLong());
        assertThat(warnings).anySatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
    }
    @Test void runtimeCloseStepError_doesNotSkipAnotherOwner() {
        AssertionError failure = new AssertionError("container prepare");
        doThrow(failure).when(containers).prepareUnload(any(), anyLong());
        UiSession<Boolean> first = service.show(viewer, Confirm.builder(TITLE).build());
        UiService other = runtime.service("OtherModule");
        UiSession<Boolean> second = other.show(MockBukkit.getMock().addPlayer(), Confirm.builder(TITLE).build());
        settle();
        assertThatCode(() -> runtime.close()).doesNotThrowAnyException();
        assertClosed(first, CloseReason.OWNER_UNLOADED); assertClosed(second, CloseReason.OWNER_UNLOADED);
        assertThat(warnings).hasSize(2);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void openBindingError_completesAndDetachesEveryAttempt(boolean attachFailure) {
        SlotContainer first = container(); SlotContainer second = container();
        AssertionError failure = new AssertionError("binding open");
        if (attachFailure) { doThrow(failure).when(containers).attach(eq(second), any()); }
        else { when(second.get(0)).thenThrow(failure); }
        GridSession session = service.show(viewer, grid(first, second));
        settle();
        assertClosed(session, CloseReason.UNSUPPORTED);
        verify(containers).detach(second, session);
        assertThat(warnings).anySatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void updateBindingError_closesAndDetachesOldAndNewBindings(boolean attachFailure) {
        SlotContainer previous = container();
        SlotContainer first = container(); SlotContainer second = container();
        GridSession session = service.show(viewer, grid(previous)); settle();
        AssertionError failure = new AssertionError("binding update");
        List<SlotContainer> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            attempts.add(invocation.getArgument(0));
            if (attachFailure && attempts.size() == 2) { throw failure; }
            return null;
        }).when(containers).attach(any(), eq(session));
        if (!attachFailure) { when(second.get(0)).thenThrow(failure); }
        session.update(grid(first, second)); settle();
        assertClosed(session, CloseReason.UNSUPPORTED);
        verify(containers).detach(previous, session);
        for (SlotContainer attempted : attempts) { verify(containers).detach(attempted, session); }
        assertThat(warnings).anySatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
    }
    @Test void redrawReadError_closesAndDetachesSession() {
        SlotContainer container = container();
        RuntimeGridSession session = (RuntimeGridSession) service.show(viewer, grid(container)); settle();
        AssertionError failure = new AssertionError("redraw read");
        when(container.get(0)).thenThrow(failure);
        runtime.redraw(container); settle();
        assertClosed(session, CloseReason.UNSUPPORTED);
        verify(containers).detach(container, session);
    }
    @Test void detachError_doesNotSkipAnotherContainerOrResult() {
        SlotContainer first = container(); SlotContainer second = container();
        GridSession session = service.show(viewer, grid(first, second)); settle();
        doThrow(new AssertionError("detach")).when(containers).detach(first, session);
        session.close(); settle();
        assertClosed(session, CloseReason.CLOSED_BY_CODE);
        verify(containers).detach(second, session);
    }
    @Test void timeoutCancellationError_doesNotLoseChosenResult() {
        runtime.close();
        TaskContext global = spy(scheduler.global());
        TaskHandle timeout = mock(TaskHandle.class);
        when(timeout.completion()).thenReturn(new CompletableFuture<Void>().minimalCompletionStage());
        doReturn(timeout).when(global).runLater(any(Duration.class), any(Runnable.class));
        doThrow(new AssertionError("cancel timeout")).when(timeout).cancel();
        Scheduler unreliable = spy(scheduler);
        doReturn(global).when(unreliable).global();
        startRuntime(unreliable);
        UiSession<Boolean> session = service.show(viewer, Confirm.builder(TITLE).build()); settle();
        assertThatCode(session::close).doesNotThrowAnyException(); settle();
        assertClosed(session, CloseReason.CLOSED_BY_CODE);
    }
    @ParameterizedTest @ValueSource(strings = {"throw", "failed", "cancelled", "late"})
    void timeoutSchedulingFailure_hasAccurateWarningAndCompletes(String mode) {
        runtime.close();
        TaskContext global = spy(scheduler.global());
        Throwable failure = mode.equals("cancelled") ? new CancellationException("timeout cancelled")
                : new RejectedExecutionException("timeout unavailable");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (mode.equals("throw")) {
            doThrow(failure).when(global).runLater(any(Duration.class), any(Runnable.class));
        } else {
            TaskHandle timeout = mock(TaskHandle.class);
            when(timeout.completion()).thenReturn(completion.minimalCompletionStage());
            doReturn(timeout).when(global).runLater(any(Duration.class), any(Runnable.class));
            if (!mode.equals("late")) { completion.completeExceptionally(failure); }
        }
        Scheduler unreliable = spy(scheduler);
        doReturn(global).when(unreliable).global();
        startRuntime(unreliable);
        UiSession<Boolean> session = service.show(viewer, Confirm.builder(TITLE).build()); settle();
        if (mode.equals("late")) {
            assertThat(session.isOpen()).isTrue();
            completion.completeExceptionally(failure); settle();
        }
        assertClosed(session, CloseReason.UNSUPPORTED);
        verify(backend).open(eq(session), any());
        verify(backend).close(session);
        assertThat(warnings).singleElement().satisfies(record -> {
            assertThat(record.getMessage()).contains("timeout", "TestModule").doesNotContain("Backend open failed");
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }
    @Test void deliveryError_completesBeforeUnloadAndDoesNotSkipNextDelivery() {
        Player broken = mock(Player.class);
        when(broken.getUniqueId()).thenReturn(viewer.getUniqueId());
        when(broken.isOnline()).thenReturn(true);
        when(broken.getInventory()).thenThrow(new AssertionError("inventory linkage"));
        CompletionStage<Delivery> bad = service.deliver(broken, List.of(new ItemStack(Material.STONE, 3)));
        CompletionStage<Delivery> good = service.deliver(viewer, List.of(new ItemStack(Material.DIRT, 2)));
        runtime.unload(service);
        assertThat(bad.toCompletableFuture()).isDone();
        assertThat(bad.toCompletableFuture().getNow(null).undelivered()).containsExactly(new ItemStack(Material.STONE, 3));
        assertThat(good.toCompletableFuture()).isDone();
        assertThat(good.toCompletableFuture().getNow(null).given()).containsExactly(new ItemStack(Material.DIRT, 2));
    }
    @Test void backendVmErrorDuringClose_stillCompletesOtherOwnersBeforeRethrowing() {
        InternalError failure = new InternalError("simulated close VM failure");
        doThrow(failure).when(backend).close(any());
        UiSession<Boolean> first = service.show(viewer, Confirm.builder(TITLE).build());
        UiSession<Boolean> second = runtime.service("OtherModule").show(MockBukkit.getMock().addPlayer(),
                Confirm.builder(TITLE).build());
        settle();
        assertThatThrownBy(runtime::close).isSameAs(failure);
        assertClosed(first, CloseReason.OWNER_UNLOADED); assertClosed(second, CloseReason.OWNER_UNLOADED);
    }
    @Test void pumpCancellationError_stillReleasesViewerLanes() {
        runtime.close();
        TaskContext global = spy(scheduler.global());
        TaskHandle pump = mock(TaskHandle.class);
        doReturn(pump).when(global).runRepeating(any(Duration.class), any(Duration.class), any());
        doThrow(new AssertionError("pump cancel")).when(pump).cancel();
        Scheduler unreliable = spy(scheduler);
        doReturn(global).when(unreliable).global();
        startRuntime(unreliable);
        ViewerLane before = runtime.lane(viewer);
        runtime.close();
        assertThat(runtime.lane(viewer)).isNotSameAs(before);
    }
    @Test void rendererError_doesNotLoseSessionOrBlockLaterWork() {
        UiBackend renderer = mock(UiBackend.class, withSettings().extraInterfaces(BoundSlotRenderer.class));
        when(renderer.priority()).thenReturn(100);
        when(renderer.supports(any(UiIntent.class), any())).thenReturn(true);
        AssertionError failure = new AssertionError("renderer linkage");
        doThrow(failure).when((BoundSlotRenderer) renderer).redraw(any(), any());
        runtime.register(renderer);
        GridSession session = service.show(viewer, grid()); settle();
        assertThat(session.isOpen()).isTrue();
        session.close(); settle();
        assertClosed(session, CloseReason.CLOSED_BY_CODE);
        assertThat(warnings).anySatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
    }
    @Test void backendOpenError_completesUnsupported() {
        doThrow(new NoClassDefFoundError("backend linkage")).when(backend).open(any(), any());
        UiSession<Boolean> session = service.show(viewer, Confirm.builder(TITLE).build()); settle();
        assertClosed(session, CloseReason.UNSUPPORTED);
    }
    @Test void backendSupportsError_completesUnsupported() {
        when(backend.supports(any(UiIntent.class), any())).thenThrow(new ExceptionInInitializerError("backend init"));
        UiSession<Boolean> session = service.show(viewer, Confirm.builder(TITLE).build()); settle();
        assertClosed(session, CloseReason.UNSUPPORTED);
    }
    @Test void backendUpdateError_completesAndDetaches() {
        SlotContainer container = container();
        GridSession session = service.show(viewer, grid(container)); settle();
        doThrow(new AssertionError("backend update")).when(backend).update(any(), any(), any());
        session.update(grid()); settle();
        assertClosed(session, CloseReason.UNSUPPORTED);
        verify(containers).detach(container, session);
    }
}
