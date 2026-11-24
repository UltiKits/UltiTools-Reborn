package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.ui.ContainerChanges;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(10)
class ContributionLedgerTest {
    private final UUID first = new UUID(0, 1);
    private final UUID second = new UUID(0, 2);
    private final UUID third = new UUID(0, 3);
    @BeforeEach void setUp() { MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void takingConsumesOwnThenOldestOtherThenBaseline() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.put(first, stone(3));
        ledger.put(second, stone(4));
        ledger.put(first, stone(2));
        ledger.take(second, stone(1), 5);
        assertThat(ledger.snapshot().putInBy()).containsOnlyKeys(first);
        assertThat(ledger.snapshot().putInBy().get(first)).containsExactly(stone(4));
        ledger.take(third, stone(1), 6);
        assertThat(ledger.snapshot().putIn()).isEmpty();
        assertThat(ledger.snapshot().takenOut()).containsExactly(stone(2));
    }
    @Test void snapshotsAreIndependentImmutableAndMergedBySimilarity() {
        ContributionLedger ledger = new ContributionLedger();
        ItemStack contributed = stone(3);
        ledger.put(first, contributed);
        contributed.setAmount(1);
        ledger.put(first, stone(2));
        ledger.put(second, new ItemStack(Material.DIRT, 4));
        ledger.put(first, ItemStack.empty());
        ContainerChanges changes = ledger.snapshot();
        changes.putIn().getFirst().setAmount(1);
        changes.putInBy().get(first).getFirst().setAmount(1);
        assertThat(changes.isEmpty()).isFalse();
        assertThat(changes.putIn()).containsExactly(stone(5), new ItemStack(Material.DIRT, 4));
        assertThat(changes.putInBy().get(first)).containsExactly(stone(5));
        assertThatThrownBy(() -> changes.putInBy().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> changes.putIn().clear()).isInstanceOf(UnsupportedOperationException.class);
        ledger.clear();
        assertThat(ledger.snapshot().isEmpty()).isTrue();
        assertThat(changes.putIn()).hasSize(2);
    }
    @Test void takingBaselineAndReplacingItStillCountsAsAWrite() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.take(first, stone(8), 8);
        ledger.put(first, stone(8));
        assertThat(ledger.snapshot().putIn()).containsExactly(stone(8));
        assertThat(ledger.snapshot().takenOut()).containsExactly(stone(8));
        assertThat(ledger.snapshot().isEmpty()).isFalse();
        ledger.take(first, new ItemStack(Material.DIRT, 2), 2);
        assertThat(ledger.snapshot().takenOut()).isEqualTo(List.of(stone(8), new ItemStack(Material.DIRT, 2)));
    }
    private static ItemStack stone(int amount) { return new ItemStack(Material.STONE, amount); }
}
