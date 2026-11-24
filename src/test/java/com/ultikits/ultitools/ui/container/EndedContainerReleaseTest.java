package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import com.ultikits.ultitools.ui.runtime.ContainerHost;
import com.ultikits.ultitools.ui.runtime.UiOwner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

class EndedContainerReleaseTest extends FrameworkContainerTestSupport {
    @Test void ownerGroupRetainsNoEndedOrDrainedContainers() {
        UiOwner owner = mock(UiOwner.class);
        when(owner.name()).thenReturn("many-screens");
        ContainerHost host = mock(ContainerHost.class);
        for (int index = 0; index < 1000; index++) {
            SlotContainer container = containers.create(owner, ContainerSpec.template(1).baseline(List.of(stone(2))).build(), host);
            if (index % 2 == 0) {
                assertThat(completed(container.end())).isEqualTo(EndOutcome.DISCARDED_TEMPLATE);
            } else {
                assertThat(container.drain()).containsExactly(stone(2));
            }
        }
        assertThat(containers).extracting("owners").asInstanceOf(InstanceOfAssertFactories.MAP)
                .extractingByKey(owner).extracting("containers").asInstanceOf(InstanceOfAssertFactories.LIST).isEmpty();
    }

    @Test void endingReleasesTheBaselineAndPendingCommitSnapshot() {
        SlotContainer container = contributed(snapshot -> CompletableFuture.completedFuture(StoreAnswer.written()));
        assertThat(completed(container.end())).isEqualTo(EndOutcome.COMMITTED);
        assertThat(container).extracting("baseline").asInstanceOf(InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(container).extracting("pending").isNull();
    }

    @Test void containersEndedWhileUnloadingAreSettledExactlyOnce() {
        AtomicReference<SlotContainer> returning = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger();
        List<SlotContainer> all = new ArrayList<>();
        all.add(contributed(snapshot -> {
            writes.incrementAndGet();
            assertThat(completed(returning.get().end())).isEqualTo(EndOutcome.RETURNED);
            return CompletableFuture.completedFuture(StoreAnswer.written());
        }));
        for (int index = 0; index < 20; index++) {
            all.add(contributed(snapshot -> {
                writes.incrementAndGet();
                return CompletableFuture.completedFuture(StoreAnswer.written());
            }));
        }
        returning.set(service.container(returning(1).initial(viewer.getUniqueId(), List.of(stone(3))).build()));

        assertThatCode(() -> fixture.unload(service)).doesNotThrowAnyException();
        assertThat(writes).hasValue(21);
        assertThat(all).allSatisfy(container -> {
            assertThat(container.state()).isEqualTo(ContainerState.ENDED);
            assertThat(completed(container.end())).isEqualTo(EndOutcome.COMMITTED);
        });
        assertThat(completed(returning.get().end())).isEqualTo(EndOutcome.RETURNED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(3));
        fixture.unload(service);
        fixture.advanceTicks(3);
        assertThat(writes).hasValue(21);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(3));
        assertThat(undelivered).isEmpty();
        assertThat(fixture.warnings()).isEmpty();
    }
}
