package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class StoreAnswerTest {
    private PlayerMock viewer;
    @BeforeEach void setUp() { MockBukkitHelper.ensureCleanState(); viewer = MockBukkit.mock().addPlayer(); }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }
    @Test void factories_preserveThreeDistinctAnswersAndNullableCause() {
        IllegalStateException cause = new IllegalStateException("store");
        assertThat(StoreAnswer.written()).isEqualTo(new StoreAnswer.Written());
        assertThat(StoreAnswer.unknown(null)).isEqualTo(new StoreAnswer.Unknown(null));
        assertThat(((StoreAnswer.Unknown) StoreAnswer.unknown(cause)).cause()).isSameAs(cause);
        assertThat(StoreAnswer.refused(List.of(ItemStack.empty()))).isInstanceOf(StoreAnswer.Refused.class);
        assertThatThrownBy(() -> StoreAnswer.refused(null)).isInstanceOf(NullPointerException.class);
    }
    @Test void refusedAndSnapshot_copyAtEveryBoundary() {
        ItemStack item = UiTestItems.stone(8);
        StoreAnswer.Refused refused = (StoreAnswer.Refused) StoreAnswer.refused(List.of(item));
        ContainerSnapshot snapshot = new ContainerSnapshot(List.of(item), List.of(item), 7);
        item.setAmount(2);
        refused.current().getFirst().setAmount(1);
        snapshot.contents().getFirst().setAmount(1);
        snapshot.baseline().getFirst().setAmount(1);
        assertThat(refused.current().getFirst().getAmount()).isEqualTo(8);
        assertThat(snapshot.contents().getFirst().getAmount()).isEqualTo(8);
        assertThat(snapshot.baseline().getFirst().getAmount()).isEqualTo(8);
        assertThat(snapshot.sequence()).isEqualTo(7);
        assertThatThrownBy(() -> refused.current().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ContainerSnapshot(List.of(), List.of(item), 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void transfersAndDelivery_copyAtEveryBoundary() {
        ItemStack item = UiTestItems.stone(8);
        TransferRequest request = new TransferRequest(viewer, TransferKind.PUT_IN, 0, item, 8);
        ContainerChange change = new ContainerChange(viewer, TransferKind.PUT_IN, 0, item, 8);
        Delivery delivery = new Delivery(List.of(item), List.of(item), List.of(item));
        item.setAmount(2);
        request.item().setAmount(1);
        change.item().setAmount(1);
        delivery.given().getFirst().setAmount(1);
        delivery.dropped().getFirst().setAmount(1);
        delivery.undelivered().getFirst().setAmount(1);
        assertThat(request.item().getAmount()).isEqualTo(8);
        assertThat(change.item().getAmount()).isEqualTo(8);
        assertThat(delivery.given().getFirst().getAmount()).isEqualTo(8);
        assertThat(delivery.dropped().getFirst().getAmount()).isEqualTo(8);
        assertThat(delivery.undelivered().getFirst().getAmount()).isEqualTo(8);
        assertThatThrownBy(() -> delivery.given().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
