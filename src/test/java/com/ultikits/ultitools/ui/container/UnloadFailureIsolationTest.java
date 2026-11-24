package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.FrameworkRuntimeFixture;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import com.ultikits.ultitools.ui.runtime.ContainerHost;
import com.ultikits.ultitools.ui.runtime.UiOwner;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

class UnloadFailureIsolationTest extends FrameworkContainerTestSupport {
    @Test void rejectedTimeoutSubmissionDuringUnloadCannotLoseItemsOrSkipSibling() {
        fixture.close();
        fixture = new FrameworkRuntimeFixture(containers, UnloadFailureIsolationTest::rejectDelays);
        service = fixture.service("ContainerModule");
        containers.unloadWaitOverride(Duration.ofMillis(40));
        AtomicInteger firstWrites = new AtomicInteger();
        AtomicInteger secondWrites = new AtomicInteger();
        SlotContainer first = contributed(snapshot -> {
            firstWrites.incrementAndGet();
            assertThat(snapshot.contents()).containsExactly(stone(8));
            return CompletableFuture.completedFuture(StoreAnswer.written());
        });
        SlotContainer second = contributed(snapshot -> {
            secondWrites.incrementAndGet();
            assertThat(snapshot.contents()).containsExactly(stone(8));
            return CompletableFuture.completedFuture(StoreAnswer.written());
        });
        SlotContainer returning = service.container(returning(1)
                .initial(viewer.getUniqueId(), List.of(stone(3))).build());

        fixture.unload(service);

        assertThat(firstWrites).hasValue(1);
        assertThat(secondWrites).hasValue(1);
        assertThat(completed(first.end())).isEqualTo(EndOutcome.COMMITTED);
        assertThat(completed(second.end())).isEqualTo(EndOutcome.COMMITTED);
        assertThat(completed(returning.end())).isEqualTo(EndOutcome.RETURNED);
        assertThat(List.of(first, second, returning)).allSatisfy(container ->
                assertThat(container.state()).isEqualTo(ContainerState.ENDED));
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(3));
        assertThat(undelivered).isEmpty();
        assertThat(fixture.warnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedTimeoutBeforeWriteLeavesItemsAvailableForUnloadAndWarns(boolean ending) {
        fixture.close();
        fixture = new FrameworkRuntimeFixture(containers, UnloadFailureIsolationTest::rejectDelays);
        service = fixture.service("ContainerModule");
        containers.unloadWaitOverride(Duration.ofMillis(40));
        AtomicInteger writes = new AtomicInteger();
        SlotContainer container = contributed(snapshot -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(StoreAnswer.written());
        });

        if (ending) { assertThatThrownBy(container::end).isInstanceOf(RejectedExecutionException.class); }
        else { assertThatThrownBy(container::commit).isInstanceOf(RejectedExecutionException.class); }
        assertThat(writes).hasValue(0);
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(container.contents()).containsExactly(stone(8));
        assertThat(container.changes().putIn()).containsExactly(stone(8));
        assertThat(fixture.warnings()).singleElement().satisfies(record -> {
            assertThat(record.getMessage()).contains(service.owner(), "container");
            assertThat(record.getThrown()).isInstanceOf(RejectedExecutionException.class);
        });

        fixture.unload(service);
        assertThat(writes).hasValue(1);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.COMMITTED);
        assertThat(fixture.warnings()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(UnloadStep.class)
    void ordinaryFailureInOneContainerDoesNotSkipSibling(UnloadStep step) {
        UiOwner owner = mock(UiOwner.class);
        ContainerHost host = mock(ContainerHost.class);
        IllegalStateException failure = new IllegalStateException("one container failed");
        try (MockedConstruction<FrameworkSlotContainer> construction = mockConstruction(
                FrameworkSlotContainer.class, (container, context) -> {
                    if (context.getCount() == 1) { step.reject(container, failure); }
                })) {
            containers.create(owner, ContainerSpec.template(1).build(), host);
            containers.create(owner, ContainerSpec.template(1).build(), host);
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            assertThatCode(() -> runUnload(owner, deadline)).doesNotThrowAnyException();
            FrameworkSlotContainer first = construction.constructed().get(0);
            FrameworkSlotContainer second = construction.constructed().get(1);
            verify(first).warn(step.message, failure);
            verify(second).prepareUnload(deadline);
            verify(second).startUnloadCommit();
            verify(second).unload();
        }
    }

    @Test void virtualMachineFailureIsRethrownOnlyAfterSiblingsFinishTheStep() {
        UiOwner owner = mock(UiOwner.class);
        ContainerHost host = mock(ContainerHost.class);
        StackOverflowError fatal = new StackOverflowError("fatal container failure");
        try (MockedConstruction<FrameworkSlotContainer> construction = mockConstruction(
                FrameworkSlotContainer.class, (container, context) -> {
                    if (context.getCount() == 1) { doThrow(fatal).when(container).unload(); }
                })) {
            containers.create(owner, ContainerSpec.template(1).build(), host);
            containers.create(owner, ContainerSpec.template(1).build(), host);
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            containers.prepareUnload(owner, deadline);
            assertThatThrownBy(() -> containers.finishUnload(owner, deadline)).isSameAs(fatal);
            verify(construction.constructed().get(0)).warn("Container unload finish failed", fatal);
            verify(construction.constructed().get(1)).unload();
        }
    }

    private void runUnload(UiOwner owner, long deadline) {
        containers.prepareUnload(owner, deadline);
        containers.startUnloadCommits(owner);
        containers.finishUnload(owner, deadline);
    }

    private static Scheduler rejectDelays(Scheduler scheduler) {
        TaskContext global = spy(scheduler.global());
        doThrow(new RejectedExecutionException("framework scheduler closed")).when(global).runLater(any(), any());
        Scheduler decorated = spy(scheduler);
        doReturn(global).when(decorated).global();
        return decorated;
    }

    private enum UnloadStep {
        PREPARE("Container unload prepare failed"),
        START("Container unload start failed"),
        FINISH("Container unload finish failed");

        final String message;
        UnloadStep(String message) { this.message = message; }
        void reject(FrameworkSlotContainer container, IllegalStateException failure) {
            switch (this) {
                case PREPARE -> doThrow(failure).when(container).prepareUnload(anyLong());
                case START -> doThrow(failure).when(container).startUnloadCommit();
                case FINISH -> doThrow(failure).when(container).unload();
            }
        }
    }
}
