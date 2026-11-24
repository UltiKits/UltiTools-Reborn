package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.ui.CommitOutcome;
import com.ultikits.ultitools.ui.AbstractUiServiceContractTest.ProbeBackend;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.ViewerItems;
import com.ultikits.ultitools.ui.runtime.ContainerHost;
import com.ultikits.ultitools.ui.runtime.UiOwner;
import com.ultikits.ultitools.text.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrameworkSlotContainerTest extends FrameworkContainerTestSupport {
    @Test void sameNameReplacementDoesNotShareOwnerLifecycle() {
        UiService first = fixture.anotherService("same");
        UiService second = fixture.anotherService("same");
        SlotContainer old = first.container(returning(1).build());
        SlotContainer replacement = second.container(returning(1).build());
        fixture.unload(first);
        assertThat(old.state()).isEqualTo(ContainerState.ENDED);
        assertThat(replacement.state()).isEqualTo(ContainerState.EDITABLE);
        ViewerItems side = side(); side.cursor(stone(3));
        assertThat(containers.engine().apply(grid(replacement), viewer, side, new Gesture.Place(FIRST, false))).isTrue();
        fixture.unload(second);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(3));
    }
    @Test void observerCanDetachAfterTheContainersOwnerHasUnloaded() {
        fixture.register(new ProbeBackend("observer", 0, Grid.class));
        SlotContainer container = service.container(returning(1).baseline(List.of(stone(3))).build());
        UiService observer = fixture.anotherService("observer");
        GridSession session = observer.show(viewer, grid(container));
        fixture.advanceTicks(3);
        fixture.unload(service);
        fixture.advanceTicks(2);
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(fixture.shown(session, FIRST).isEmpty()).isTrue();
        session.close();
        fixture.advanceTicks(3);
        assertThat(session.result().toCompletableFuture()).isDone();
        assertThat(fixture.warnings()).isEmpty();
    }
    @Test void readOnlyRefusalRestoresStateAndPreservesIndependentBaseline() {
        SlotContainer container = contributed(snapshot -> CompletableFuture.completedFuture(StoreAnswer.refused(List.of(stone(2)))));
        container.setReadOnly(true);
        assertThat(container.commit().toCompletableFuture()).isNotDone();
        fixture.advanceTicks(3);
        assertThat(container.state()).isEqualTo(ContainerState.READ_ONLY);
        assertThat(container.contents()).containsExactly(stone(2));
        assertThat(container.changes().isEmpty()).isTrue();
        assertThat(completed(container.commit())).isEqualTo(CommitOutcome.UNCHANGED);
        assertThat(container.drain()).containsExactly(stone(2));
    }
    @Test void nullStoreStageAndNullAnswerAreUnknownWithoutRefunds() {
        SlotContainer noStage = contributed(snapshot -> null);
        SlotContainer noAnswer = contributed(snapshot -> CompletableFuture.completedFuture(null));
        assertThat(completed(noStage.commit())).isEqualTo(CommitOutcome.UNKNOWN);
        assertThat(completed(noAnswer.commit())).isEqualTo(CommitOutcome.UNKNOWN);
        assertThat(completed(noStage.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(completed(noAnswer.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(fixture.warnings()).hasSize(2);
        assertThat(undelivered).isEmpty();
    }
    @Test void policyReturningNullIsRefusedAndLogged() {
        SlotContainer container = service.container(returning(1).policy(request -> null).build());
        ViewerItems side = side(); side.cursor(stone(2));
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.Place(FIRST, false))).isFalse();
        assertThat(side.cursor()).isEqualTo(stone(2));
        assertThat(fixture.warnings()).singleElement().satisfies(record ->
                assertThat(record.getThrown()).isInstanceOf(NullPointerException.class));
    }
    @Test void undeliveredHandlerFailureDoesNotLeaveDispositionPending() {
        SlotContainer container = service.container(ContainerSpec.storage(1).disposition(Disposition.RETURN_TO_CONTRIBUTORS)
                .baseline(List.of(stone(3))).undelivered((player, items) -> { throw new IllegalStateException("handler"); }).build());
        assertThat(completed(container.end())).isEqualTo(EndOutcome.RETURNED);
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(fixture.warnings()).singleElement().satisfies(record -> assertThat(record.getMessage()).contains(service.owner()));
    }
    @Test void successfulAnswersCancelTimeoutAndDuplicateDetachDoesNotEndNeverViewedContainer() {
        SlotContainer container = contributed(snapshot -> CompletableFuture.completedFuture(StoreAnswer.written()));
        assertThat(completed(container.commit())).isEqualTo(CommitOutcome.WRITTEN);
        containers.detach(container, mock(UiSession.class));
        fixture.advanceTicks(601);
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(fixture.warnings()).isEmpty();
        assertThat(completed(container.end())).isEqualTo(EndOutcome.UNCHANGED);
    }
    @Test void shortInventoryAndUnsupportedTargetsMoveNothing() {
        SlotContainer container = service.container(returning(1).build());
        ViewerItems side = new StackMathTest.TestItems(1);
        side.cursor(stone(3));
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.HotbarSwap(FIRST, 8))).isFalse();
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.QuickMoveIn(-1))).isFalse();
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.QuickMoveIn(1))).isFalse();
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.Spread(Set.of(), true))).isFalse();
        assertThat(side.cursor()).isEqualTo(stone(3));
    }
    @Test void preparedContainersFreezeMutationsAndPartialAttachCleanupUsesUnloadDeadline() {
        UiOwner token = mock(UiOwner.class);
        when(token.name()).thenReturn("direct");
        ContainerHost host = directHost(new ArrayList<>());
        FrameworkSlotContainer container = (FrameworkSlotContainer) containers.create(token,
                committed(1, snapshot -> CompletableFuture.completedFuture(StoreAnswer.written()))
                        .initial(viewer.getUniqueId(), List.of(stone(8))).build(), host);
        containers.prepareUnload(token, System.nanoTime() + Duration.ofSeconds(1).toNanos());
        assertThatThrownBy(() -> container.setReadOnly(false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> container.load(List.of(stone(3)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(container::drain).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(container::commit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> containers.create(token, ContainerSpec.template(1).build(), host)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> containers.attach(container, mock(UiSession.class))).isInstanceOf(IllegalStateException.class);
        containers.startUnloadCommits(token);
        containers.detach(container, mock(UiSession.class));
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.COMMITTED);
        containers.finishUnload(token, System.nanoTime() + Duration.ofSeconds(1).toNanos());
    }
    @Test void directRuntimePreservesEarlierDeadlineAndRejectsForeignContainers() {
        UiOwner token = mock(UiOwner.class);
        when(token.name()).thenReturn("direct");
        List<LogRecord> warnings = new ArrayList<>();
        ContainerHost host = directHost(warnings);
        containers.unloadWaitOverride(Duration.ofSeconds(1));
        SlotContainer container = containers.create(token, committed(1, snapshot -> new CompletableFuture<>())
                .initial(viewer.getUniqueId(), List.of(stone(8))).build(), host);
        containers.prepareUnload(token, System.nanoTime());
        containers.startUnloadCommits(token);
        containers.finishUnload(token, System.nanoTime() + Duration.ofSeconds(1).toNanos());
        assertThat(completed(container.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(warnings).hasSize(1);
        SlotContainer foreign = mock(SlotContainer.class);
        assertThatThrownBy(() -> containers.attach(foreign, mock(UiSession.class))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> containers.detach(foreign, mock(UiSession.class))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidTestBudgetIsRejectedAndLateAttachToEndedContainersIsHarmless() {
        assertThatThrownBy(() -> containers.unloadWaitOverride(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> containers.unloadWaitOverride(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
        SlotContainer container = service.container(ContainerSpec.template(1).build());
        container.end();
        assertThatCode(() -> containers.attach(container, mock(UiSession.class))).doesNotThrowAnyException();
        assertThat(container.isViewed()).isFalse();
        assertThat(completed(container.end())).isEqualTo(EndOutcome.DISCARDED_TEMPLATE);
    }
    @Test void gestureRejectsForeignThreadsEvenOnEmptyGrids() throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { containers.engine().apply(Grid.builder(Text.plain("empty"), 1).build(),
                    viewer, side(), new Gesture.Place(FIRST, false)); }
            catch (IllegalStateException thrown) { failure.set(thrown); }
        }, "foreign-gesture");
        caller.start(); caller.join(2000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }
    @Test void failedAndThrowingDeliveryHostsSendOnlyTheUndeliveredBatchToHandler() {
        UiOwner token = mock(UiOwner.class);
        when(token.name()).thenReturn("delivery-failure");
        List<LogRecord> warnings = new ArrayList<>();
        ContainerHost throwsHost = directHost(warnings);
        doThrow(new IllegalStateException("delivery")).when(throwsHost).returnTo(any(), any());
        SlotContainer first = containers.create(token, returning(1).initial(viewer.getUniqueId(), List.of(stone(3))).build(), throwsHost);
        assertThat(completed(first.end())).isEqualTo(EndOutcome.RETURNED);
        ContainerHost failedHost = directHost(warnings);
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("delivery"))).when(failedHost).returnTo(any(), any());
        SlotContainer second = containers.create(token, returning(1).initial(viewer.getUniqueId(), List.of(stone(5))).build(), failedHost);
        assertThat(completed(second.end())).isEqualTo(EndOutcome.RETURNED);
        assertThat(undelivered.get(viewer.getUniqueId())).containsExactly(stone(3), stone(5));
        assertThat(warnings).hasSize(2);
    }
    @Test void differentRuntimesCannotApplyGesturesToEachOthersContainers() {
        FrameworkContainerRuntime otherRuntime = new FrameworkContainerRuntime(() -> true,
                (player, text) -> { /* No refusal in this test. */ });
        UiOwner token = mock(UiOwner.class);
        when(token.name()).thenReturn("foreign");
        SlotContainer foreign = otherRuntime.create(token, ContainerSpec.template(1).build(), directHost(new ArrayList<>()));
        ViewerItems side = side(); side.cursor(stone(2));
        assertThat(containers.engine().apply(grid(foreign), viewer, side, new Gesture.Place(FIRST, false))).isFalse();
        assertThat(foreign.get(0).isEmpty()).isTrue();
        foreign.end();
        containers.prepareUnload(token, System.nanoTime());
        containers.startUnloadCommits(token);
        containers.finishUnload(token, System.nanoTime());
        assertThat(fixture.warnings()).isEmpty();
    }
    private ContainerHost directHost(List<LogRecord> warnings) {
        ContainerHost host = mock(ContainerHost.class);
        when(host.scheduler()).thenReturn(fixture.scheduler());
        doAnswer(call -> { warnings.add(call.getArgument(0)); return null; }).when(host).warn(any());
        when(host.returnTo(any(), any())).thenAnswer(call -> CompletableFuture.completedFuture(
                new Delivery(List.of(), List.of(), call.getArgument(1))));
        return host;
    }
}
