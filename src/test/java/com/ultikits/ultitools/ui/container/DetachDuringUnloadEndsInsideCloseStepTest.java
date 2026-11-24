package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.AbstractUiServiceContractTest.ProbeBackend;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DetachDuringUnloadEndsInsideCloseStepTest extends FrameworkContainerTestSupport {
    @Test void exclusiveContainerIsEndedBeforeSessionResultCompletes() {
        fixture.register(new ProbeBackend("container", 0, Grid.class));
        AtomicInteger writes = new AtomicInteger();
        SlotContainer container = contributed(snapshot -> {
            writes.incrementAndGet();
            return CompletableFuture.supplyAsync(StoreAnswer::written,
                    CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        });
        GridSession session = service.show(viewer, grid(container));
        fixture.advanceTicks(3);
        AtomicReference<ContainerState> atCompletion = new AtomicReference<>();
        AtomicReference<EndOutcome> endAtCompletion = new AtomicReference<>();
        session.result().thenAccept(ignored -> {
            atCompletion.set(container.state());
            endAtCompletion.set(completed(container.end()));
        });
        fixture.unload(service);
        assertThat(atCompletion).hasValue(ContainerState.ENDED);
        assertThat(endAtCompletion).hasValue(EndOutcome.COMMITTED);
        assertThat(writes).hasValue(1);
        assertThat(container.isViewed()).isFalse();
        assertThat(fixture.warnings()).isEmpty();
    }
    @Test void sharedContainerEndsInLastViewersCloseStepNotTheFirst() {
        fixture.register(new ProbeBackend("container", 0, Grid.class));
        CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        SlotContainer container = contributed(snapshot -> answer);
        GridSession first = service.show(viewer, grid(container));
        GridSession second = service.show(server.addPlayer("Second"), grid(container));
        fixture.advanceTicks(3);
        List<ContainerState> atCompletions = new ArrayList<>();
        first.result().thenAccept(ignored -> atCompletions.add(container.state()));
        second.result().thenAccept(ignored -> atCompletions.add(container.state()));
        CompletableFuture.runAsync(() -> answer.complete(StoreAnswer.written()),
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        fixture.unload(service);
        assertThat(atCompletions).containsExactly(ContainerState.COMMITTING, ContainerState.ENDED);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.COMMITTED);
        assertThat(first.result().toCompletableFuture()).isDone();
        assertThat(second.result().toCompletableFuture()).isDone();
    }
}
