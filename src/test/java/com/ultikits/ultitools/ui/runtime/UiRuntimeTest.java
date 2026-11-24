package com.ultikits.ultitools.ui.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridItem;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.ViewerItems;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class UiRuntimeTest {
    private static final Text TITLE = Text.plain("Runtime test");
    private ManualScheduler.Engine clock;
    private RecordingScheduler scheduler;
    private UiRuntime runtime;
    private UiService service;
    private PlayerMock viewer;
    private Logger logger;
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        viewer = MockBukkit.mock().addPlayer();
        clock = new ManualScheduler.Engine();
        scheduler = new RecordingScheduler(clock.scheduler("UiRuntime"));
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) { warnings.add(record); }
            }
            @Override public void flush() { /* Synchronous capture. */ }
            @Override public void close() { /* No resources. */ }
        });
        runtime = new UiRuntime(scheduler, new NoContainerRuntime(), logger, Thread.currentThread());
        service = runtime.service("TestModule");
    }
    @AfterEach void tearDown() {
        try {
            if (runtime != null) { runtime.close(); }
        } finally {
            if (clock != null) { clock.close(); }
            MockBukkitHelper.safeUnmock();
        }
    }

    private UiBackend backend() {
        UiBackend backend = mock(UiBackend.class);
        when(backend.supports(any(UiIntent.class), any(Player.class))).thenReturn(true);
        runtime.register(backend);
        return backend;
    }
    private UiSession<Boolean> show() { return service.show(viewer, Confirm.builder(TITLE).build()); }
    private void settle() { clock.advanceTicks(3); }
    private void warning(Throwable failure) {
        assertThat(warnings).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("TestModule");
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }
    private static <T> T completed(CompletionStage<T> result) {
        CompletableFuture<T> copy = result.toCompletableFuture();
        assertThat(copy).isDone();
        return copy.getNow(null);
    }

    @Test void supportsThrows_warnsAndCompletesUnsupportedExactlyOnce() {
        UiBackend backend = backend();
        IllegalStateException failure = new IllegalStateException("supports");
        when(backend.supports(any(UiIntent.class), any(Player.class))).thenThrow(failure);
        UiSession<Boolean> session = show();
        AtomicInteger completions = new AtomicInteger();
        session.result().thenAccept(ignored -> completions.incrementAndGet());
        settle(); session.close(); settle();
        assertThat(completed(session.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.UNSUPPORTED));
        assertThat(completions).hasValue(1);
        verify(backend, never()).open(any(), any());
        warning(failure);
    }
    @Test void supportsThrows_usesLowerPriorityBackend() {
        UiBackend broken = mock(UiBackend.class);
        when(broken.priority()).thenReturn(100);
        IllegalStateException failure = new IllegalStateException("supports");
        when(broken.supports(any(UiIntent.class), any(Player.class))).thenThrow(failure);
        runtime.register(broken);
        UiBackend fallback = backend();
        UiSession<Boolean> session = show(); settle();
        assertThat(session.isOpen()).isTrue();
        verify(fallback).open(any(), any());
        warning(failure);
    }
    @Test void openThrows_warnsAndCompletesUnsupportedExactlyOnce() {
        UiBackend backend = backend();
        IllegalStateException failure = new IllegalStateException("open");
        doThrow(failure).when(backend).open(any(), any());
        UiSession<Boolean> session = show();
        AtomicInteger completions = new AtomicInteger();
        session.result().thenAccept(ignored -> completions.incrementAndGet());
        settle(); session.close(); settle();
        assertThat(completed(session.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.UNSUPPORTED));
        assertThat(completions).hasValue(1);
        verify(backend).close(session);
        warning(failure);
    }
    @Test void closeThrows_warnsAndStillCompletesExactlyOnce() {
        UiBackend backend = backend();
        IllegalStateException failure = new IllegalStateException("close");
        doThrow(failure).when(backend).close(any());
        UiSession<Boolean> session = show(); settle();
        AtomicInteger completions = new AtomicInteger();
        session.result().thenAccept(ignored -> completions.incrementAndGet());
        session.close(); session.close(); settle(); runtime.unload(service);
        assertThat(completed(session.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(completions).hasValue(1);
        verify(backend).close(session);
        warning(failure);
    }
    @Test void closeError_completesExactlyOnceAndLaterShowStillOpens() {
        UiBackend backend = backend();
        AssertionError failure = new AssertionError("close linkage");
        UiSession<Boolean> session = show(); settle();
        doThrow(failure).when(backend).close(session);
        AtomicInteger completions = new AtomicInteger();
        session.result().thenAccept(ignored -> completions.incrementAndGet());
        session.close(); settle();
        UiSession<Boolean> later = show(); settle();
        assertThat(completed(session.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(completions).hasValue(1);
        assertThat(later.isOpen()).isTrue();
        verify(backend).open(eq(later), any());
        warning(failure);
    }
    @Test void actionError_doesNotWedgeOtherModulesForViewer() {
        UiBackend backend = backend();
        AssertionError failure = new AssertionError("module linkage");
        GridSlot slot = GridSlot.ofIndex(0);
        ItemSpec icon = ItemSpec.builder(
                Key.key("minecraft", "stone")).build();
        RuntimeGridSession first = (RuntimeGridSession) service.show(viewer, Grid.builder(TITLE, 1)
                .set(slot, GridItem.of(icon, click -> { throw failure; })).build());
        settle();
        first.clicked(slot, ClickKind.LEFT);
        settle();
        assertThat(first.isOpen()).isTrue();
        UiSession<Boolean> later = runtime.service("OtherModule").show(viewer, Confirm.builder(TITLE).build());
        settle(); later.close(); settle();
        assertThat(completed(first.result())).isEqualTo(new UiResult.Closed<>(CloseReason.REPLACED));
        assertThat(completed(later.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        verify(backend).open(eq(later), any());
        warning(failure);
    }
    @Test void backendReentryAndResultDependents_neverHoldRuntimeLock() {
        UiBackend backend = backend();
        AtomicReference<UiSession<?>> nested = new AtomicReference<>();
        AtomicInteger lockChecks = new AtomicInteger();
        when(backend.supports(any(UiIntent.class), any(Player.class))).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            lockChecks.incrementAndGet();
            return true;
        });
        doAnswer(invocation -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            UiSession<?> opening = invocation.getArgument(0);
            if (opening.intent() instanceof Confirm) {
                UiBackend.BackendCallbacks<Boolean> callbacks = invocation.getArgument(1);
                callbacks.completed(true);
                nested.set(CompletableFuture.supplyAsync(() -> service.show(viewer, Notice.builder(TITLE).build()))
                        .get(2, TimeUnit.SECONDS));
            }
            return null;
        }).when(backend).open(any(), any());
        doAnswer(invocation -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            lockChecks.incrementAndGet();
            return null;
        }).when(backend).close(any());
        UiSession<Boolean> first = show();
        first.result().thenAccept(ignored -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            lockChecks.incrementAndGet();
        });
        settle();
        assertThat(completed(first.result())).isEqualTo(new UiResult.Completed<>(true));
        assertThat(nested.get().isOpen()).isTrue();
        assertThat(lockChecks).hasValue(4);
        assertThat(warnings).isEmpty();
        verify(backend, times(2)).open(any(), any());
    }
    @Test void noContainerRuntime_refusesCreationAndOtherwiseDoesNothing() {
        assertThatThrownBy(() -> service.container(ContainerSpec.template(1).build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("No container runtime installed");
        NoContainerRuntime empty = new NoContainerRuntime();
        SlotContainer container = mock(SlotContainer.class);
        UiSession<?> session = mock(UiSession.class);
        empty.attach(container, session); empty.detach(container, session);
        UiOwner owner = new UiOwner("unused");
        empty.prepareUnload(owner, System.nanoTime()); empty.startUnloadCommits(owner); empty.finishUnload(owner, System.nanoTime());
        assertThat(empty.engine().apply(Grid.builder(TITLE, 1).build(), viewer, mock(ViewerItems.class),
                new Gesture.Pickup(GridSlot.ofIndex(0), false))).isFalse();
    }
    @Test void runtimeClose_unloadsAllOwnersAndRejectsNewWork() {
        UiBackend backend = backend();
        UiSession<Boolean> first = show();
        UiService other = runtime.service("OtherModule");
        UiSession<Boolean> second = other.show(MockBukkit.getMock().addPlayer(), Confirm.builder(TITLE).build());
        settle(); runtime.close(); runtime.close();
        assertThat(completed(first.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(completed(second.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(completed(runtime.service("TestModule").show(viewer, Confirm.builder(TITLE).build()).result()))
                .isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(completed(runtime.service("NewModule").show(viewer, Confirm.builder(TITLE).build()).result()))
                .isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThatThrownBy(() -> runtime.register(backend)).isInstanceOf(IllegalStateException.class);
    }
    @Test void sameNameCopies_unloadingOldCopyLeavesNewSessionsAndServiceLive() {
        UiBackend backend = backend();
        UiSession<Boolean> oldSession = show();
        UiService replacement = runtime.service("TestModule");
        PlayerMock otherViewer = MockBukkit.getMock().addPlayer();
        UiSession<Boolean> replacementSession = replacement.show(otherViewer, Confirm.builder(TITLE).build());
        settle();
        runtime.unload(service);
        assertThat(completed(oldSession.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(replacement).isNotSameAs(service);
        assertThat(replacementSession.isOpen()).isTrue();
        verify(backend, never()).close(replacementSession);
        UiSession<Boolean> later = replacement.show(viewer, Confirm.builder(TITLE).build());
        settle();
        assertThat(later.isOpen()).isTrue();
        verify(backend).open(eq(later), any());
    }
    @Test void reloadedModule_getsFreshUsableService() {
        UiBackend backend = backend();
        runtime.unload(service);
        UiService reloaded = runtime.service("TestModule");
        UiSession<Boolean> session = reloaded.show(viewer, Confirm.builder(TITLE).build());
        settle();
        assertThat(reloaded).isNotSameAs(service);
        assertThat(session.isOpen()).isTrue();
        verify(backend).open(eq(session), any());
        assertThat(completed(show().result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
    }
    @Test void containerCreationOffThread_failsBeforeContainerRuntime() throws Exception {
        Throwable observed = CompletableFuture.supplyAsync(() -> {
            try { service.container(ContainerSpec.template(1).build()); return null; }
            catch (IllegalStateException failure) { return failure; }
        }).get(2, TimeUnit.SECONDS);
        assertThat(observed).isInstanceOf(IllegalStateException.class).hasMessageContaining("server thread");
    }
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test-only inspection of registry resource retention.
    private Map<?, ?> viewerLanes() throws ReflectiveOperationException {
        Field field = UiRuntime.class.getDeclaredField("lanes");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(runtime);
    }
    @Test void quitAndRuntimeClose_releaseViewerLanes() throws ReflectiveOperationException {
        backend();
        UiSession<Boolean> first = show(); settle();
        assertThat(viewerLanes()).hasSize(1);
        viewer.disconnect(); runtime.quit(viewer); clock.retire(viewer);
        settle();
        assertThat(viewerLanes()).isEmpty();
        UiSession<Boolean> offline = show();
        assertThat(viewerLanes()).isEmpty();
        settle();
        assertThat(completed(first.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(completed(offline.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        service.show(MockBukkit.getMock().addPlayer(), Confirm.builder(TITLE).build());
        assertThat(viewerLanes()).hasSize(1);
        runtime.close();
        assertThat(viewerLanes()).isEmpty();
    }
    @Test void newConnectionWithSameUuid_usesFreshEntityLane() throws ReflectiveOperationException {
        UiBackend backend = backend();
        List<Boolean> opens = new ArrayList<>();
        doAnswer(invocation -> {
            UiSession<?> session = invocation.getArgument(0);
            opens.add(RecordingScheduler.inEntityTask(session.viewer()));
            return null;
        }).when(backend).open(any(), any());
        UiSession<Boolean> first = show(); settle();
        viewer.disconnect(); runtime.quit(viewer); clock.retire(viewer);
        Player rejoined = mock(Player.class);
        when(rejoined.getUniqueId()).thenReturn(viewer.getUniqueId());
        when(rejoined.isOnline()).thenReturn(true);
        UiSession<Boolean> second = service.show(rejoined, Confirm.builder(TITLE).build());
        settle();
        assertThat(completed(first.result())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(second.isOpen()).isTrue();
        assertThat(opens).containsExactly(true, true);
        assertThat(viewerLanes()).hasSize(1);
    }
    @Test void retiredEntityWhileStillOnline_deliversNothing() {
        clock.retire(viewer);
        CompletionStage<Delivery> pending = service.deliver(viewer, List.of(new ItemStack(Material.STONE, 5)));
        settle();
        assertThat(completed(pending).undelivered()).containsExactly(new ItemStack(Material.STONE, 5));
        assertThat(viewer.getInventory().getItem(0)).isNull();
    }
    @Test void deliveryFailure_completesNormallyAndLogsOriginalCause() {
        Player broken = mock(Player.class);
        when(broken.getUniqueId()).thenReturn(viewer.getUniqueId());
        when(broken.isOnline()).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("inventory");
        when(broken.getInventory()).thenThrow(failure);
        CompletionStage<Delivery> pending = service.deliver(broken, List.of(new ItemStack(Material.STONE, 5)));
        settle();
        assertThat(completed(pending).undelivered()).containsExactly(new ItemStack(Material.STONE, 5));
        warning(failure);
    }
    @Test void partialDeliveryFailure_reportsOnlyItemsNotYetGiven() {
        Player broken = mock(Player.class);
        when(broken.getUniqueId()).thenReturn(viewer.getUniqueId());
        when(broken.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(broken.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[] { new ItemStack(Material.STONE, 62), null });
        IllegalStateException failure = new IllegalStateException("second slot");
        doThrow(failure).when(inventory).setItem(eq(1), any(ItemStack.class));
        CompletionStage<Delivery> pending = service.deliver(broken, List.of(new ItemStack(Material.STONE, 8)));
        settle();
        Delivery delivery = completed(pending);
        assertThat(delivery.given()).containsExactly(new ItemStack(Material.STONE, 2));
        assertThat(delivery.dropped()).isEmpty();
        assertThat(delivery.undelivered()).containsExactly(new ItemStack(Material.STONE, 6));
        warning(failure);
    }
    @Test void unload_flushesOwedDeliveriesBeforeWritesAndRejectsForeignThreadDelivery() {
        runtime.close();
        ContainerRuntime containers = mock(ContainerRuntime.class);
        runtime = new UiRuntime(scheduler, containers, logger, Thread.currentThread());
        service = runtime.service("TestModule");
        UiBackend backend = backend();
        List<String> order = new ArrayList<>();
        UiOwner owner = ((RuntimeUiService) service).ownerIdentity;
        AtomicReference<Long> deadline = new AtomicReference<>();
        doAnswer(invocation -> {
            deadline.set(invocation.getArgument(1)); order.add("prepare"); return null;
        }).when(containers).prepareUnload(eq(owner), anyLong());
        doAnswer(invocation -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            assertThat(viewer.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.STONE, 3));
            Delivery foreign = completed(CompletableFuture.supplyAsync(() -> service.deliver(viewer,
                    List.of(new ItemStack(Material.DIRT, 5)))).get(2, TimeUnit.SECONDS));
            assertThat(foreign.undelivered()).containsExactly(new ItemStack(Material.DIRT, 5));
            order.add("write");
            return null;
        }).when(containers).startUnloadCommits(owner);
        doAnswer(invocation -> { order.add("close"); return null; }).when(backend).close(any());
        doAnswer(invocation -> {
            assertThat((long) invocation.getArgument(1)).isEqualTo(deadline.get());
            order.add("finish"); return null;
        }).when(containers).finishUnload(eq(owner), anyLong());
        UiSession<Boolean> session = show(); settle();
        CompletionStage<Delivery> owed = service.deliver(viewer, List.of(new ItemStack(Material.STONE, 3)));
        AtomicReference<Delivery> immediate = new AtomicReference<>();
        session.result().thenAccept(ignored -> immediate.set(completed(service.deliver(viewer,
                List.of(new ItemStack(Material.STONE, 4))))));
        runtime.unload(service);
        assertThat(completed(owed).given()).containsExactly(new ItemStack(Material.STONE, 3));
        assertThat(immediate.get().given()).containsExactly(new ItemStack(Material.STONE, 4));
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.STONE, 7));
        assertThat(order).containsExactly("prepare", "write", "close", "finish");
        settle();
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.STONE, 7));
        assertThat(warnings).isEmpty();
    }
    @Test void boundSlotRenderer_getsCopiesAndChangedNotificationOutsideLock() {
        runtime.close();
        ContainerRuntime containers = mock(ContainerRuntime.class);
        runtime = new UiRuntime(scheduler, containers, logger, Thread.currentThread());
        service = runtime.service("TestModule");
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(1);
        when(container.owner()).thenReturn("TestModule");
        when(container.state()).thenReturn(ContainerState.EDITABLE);
        AtomicReference<ItemStack> contents = new AtomicReference<>(new ItemStack(Material.STONE, 8));
        when(container.get(0)).thenAnswer(invocation -> contents.get().clone());
        AtomicReference<ContainerHost> host = new AtomicReference<>();
        when(containers.create(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            host.set(invocation.getArgument(2));
            return container;
        });
        SlotContainer created = service.container(ContainerSpec.template(1).build());
        UiBackend backend = mock(UiBackend.class, withSettings().extraInterfaces(BoundSlotRenderer.class));
        when(backend.supports(any(UiIntent.class), any(Player.class))).thenReturn(true);
        List<Map<GridSlot, ItemStack>> pictures = new ArrayList<>();
        doAnswer(invocation -> {
            assertThat(Thread.holdsLock(runtime.lock)).isFalse();
            Map<GridSlot, ItemStack> picture = invocation.getArgument(1);
            pictures.add(picture);
            return null;
        }).when((BoundSlotRenderer) backend).redraw(any(), any());
        runtime.register(backend);
        GridSession session = service.show(viewer, Grid.builder(TITLE, 1)
                .bind(GridSlot.ofIndex(0), created, 0, BindingMode.VIEW).build());
        settle();
        assertThat(pictures).hasSize(1);
        pictures.getFirst().get(GridSlot.ofIndex(0)).setAmount(1);
        assertThat(runtime.picture(session).get(GridSlot.ofIndex(0))).isEqualTo(new ItemStack(Material.STONE, 8));
        contents.set(new ItemStack(Material.DIRT, 3)); host.get().changed(container); clock.advanceTicks(1);
        assertThat(runtime.picture(session).get(GridSlot.ofIndex(0))).isEqualTo(new ItemStack(Material.DIRT, 3));
        assertThat(pictures).hasSize(2);
        session.close(); settle();
        verify(containers).detach(container, session);
    }
}
