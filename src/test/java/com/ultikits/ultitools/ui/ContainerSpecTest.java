package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ContainerSpecTest {
    private static final UndeliveredHandler HANDLER = (player, items) -> { /* This builder test never delivers. */ };
    private static final ContainerStore STORE = snapshot -> CompletableFuture.completedFuture(StoreAnswer.written());
    @BeforeEach void setUp() { MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void storage_requiresDispositionHandlerAndCommitStore() {
        assertThatThrownBy(() -> ContainerSpec.storage(1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerSpec.storage(1).disposition(Disposition.COMMIT).undelivered(HANDLER).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerSpec.storage(1).disposition(Disposition.RETURN_TO_CONTRIBUTORS).build())
                .isInstanceOf(IllegalArgumentException.class);
        ContainerSpec spec = ContainerSpec.storage(1).disposition(Disposition.COMMIT).store(STORE).undelivered(HANDLER).build();
        assertThat(spec.store()).contains(STORE);
        assertThat(spec.disposition()).contains(Disposition.COMMIT);
        assertThat(spec.undelivered()).contains(HANDLER);
        assertThat(spec.kind()).isEqualTo(ContainerKind.STORAGE);
        assertThat(spec.endWhenUnviewed()).isTrue();
        assertThat(spec.commitTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
    @Test void template_forbidsStorageAttributes() {
        assertThatThrownBy(() -> ContainerSpec.template(1).disposition(Disposition.COMMIT).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerSpec.template(1).store(STORE).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerSpec.template(1).undelivered(HANDLER).build()).isInstanceOf(IllegalArgumentException.class);
        ContainerSpec spec = ContainerSpec.template(1).build();
        assertThat(spec.kind()).isEqualTo(ContainerKind.TEMPLATE);
        assertThat(spec.store()).isEmpty();
        assertThat(spec.disposition()).isEmpty();
        assertThat(spec.undelivered()).isEmpty();
        assertThat(spec.policy().decide(new TransferRequest(MockBukkit.getMock().addPlayer(), TransferKind.PUT_IN, 0,
                ItemStack.empty(), 10)).allowedAmount(10)).isEqualTo(10);
    }
    @Test void template_initialContributor_isRejected() {
        assertThatThrownBy(() -> ContainerSpec.template(1).initial(UUID.randomUUID(), List.of(UiTestItems.stone(5))).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bounds_validateSizeTimeoutAndContentsLength() {
        for (int size : new int[] {0, -1, 4097, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> ContainerSpec.storage(size)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContainerSpec.template(size)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ContainerSpec.template(4096).build().size()).isEqualTo(ContainerSpec.MAX_SIZE);
        for (Duration time : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofMinutes(10).plusNanos(1))) {
            assertThatThrownBy(() -> ContainerSpec.template(1).commitTimeout(time)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ContainerSpec.template(1).commitTimeout(Duration.ofMinutes(10)).build().commitTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThatThrownBy(() -> ContainerSpec.template(2).baseline(List.of(ItemStack.empty()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerSpec.template(2).initial(UUID.randomUUID(), List.of(ItemStack.empty())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contents_copyAtInputOutputAndBuilderReuse() {
        ItemStack item = UiTestItems.stone(7);
        UUID player = UUID.randomUUID();
        ContainerSpec.Builder builder = ContainerSpec.storage(1).disposition(Disposition.RETURN_TO_CONTRIBUTORS)
                .undelivered(HANDLER).initial(player, List.of(item));
        item.setAmount(2);
        ContainerSpec first = builder.build();
        first.initial().getFirst().setAmount(1);
        assertThat(first.initial().getFirst().getAmount()).isEqualTo(7);
        assertThat(first.contributor()).contains(player);
        assertThat(first.baseline().getFirst().isEmpty()).isTrue();
        ContainerSpec next = builder.baseline(List.of(UiTestItems.dirt(3))).endWhenUnviewed(false).build();
        assertThat(next.contributor()).isEmpty();
        assertThat(next.initial().getFirst().isEmpty()).isTrue();
        assertThat(next.baseline().getFirst().getAmount()).isEqualTo(3);
        assertThat(next.endWhenUnviewed()).isFalse();
        assertThatThrownBy(() -> first.initial().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void builder_rejectsNullReferences() {
        ContainerSpec.Builder builder = ContainerSpec.template(1);
        assertThatThrownBy(() -> builder.policy(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.disposition(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.store(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.undelivered(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.commitTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.onChange(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.initial(null, List.of(ItemStack.empty()))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.baseline(null)).isInstanceOf(NullPointerException.class);
    }
}
