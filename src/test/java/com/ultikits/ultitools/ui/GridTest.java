package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class GridTest {
    private static final Text TITLE = Text.plain("Grid");
    private static final ItemSpec ICON = ItemSpec.builder(Key.key("minecraft", "stone")).build();

    @Test
    void slots_validateCoordinatesAndRoundTripAllIndexes() {
        for (int index = 0; index < 54; index++) {
            GridSlot slot = GridSlot.ofIndex(index);
            assertThat(slot.index()).isEqualTo(index);
            assertThat(slot.row()).isEqualTo(index / 9);
            assertThat(slot.column()).isEqualTo(index % 9);
            assertThat(slot).isEqualTo(GridSlot.of(slot.row(), slot.column()));
        }
        for (int invalid : new int[] {-1, 54, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> GridSlot.ofIndex(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> GridSlot.of(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GridSlot.of(6, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GridSlot.of(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GridSlot.of(0, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Grid.COLUMNS).isEqualTo(9);
    }

    @Test
    void builder_validatesRowsAndSlotBounds() {
        for (int invalid : new int[] {0, -1, 7}) {
            assertThatThrownBy(() -> Grid.builder(TITLE, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Grid.builder(TITLE, 1).build().rows()).isOne();
        assertThat(Grid.builder(TITLE, 6).build().rows()).isEqualTo(6);
        assertThatThrownBy(() -> Grid.builder(TITLE, 1).set(GridSlot.of(1, 0), GridItem.of(ICON)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.builder(TITLE, 1).set(1, 0, ICON, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.builder(TITLE, 1).clear(GridSlot.of(1, 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshot_copiesIconsAndSupportsReplaceClearAndEquality() {
        GridSlot slot = GridSlot.of(0, 0);
        GridItem item = GridItem.of(ICON);
        Grid.Builder builder = Grid.builder(TITLE, 2).set(slot, item);
        Grid first = builder.build();
        builder.clear(slot).set(1, 8, ICON, null);
        Grid next = builder.build();
        assertThat(first.title()).isEqualTo(TITLE);
        assertThat(first.items()).containsOnlyKeys(slot);
        assertThat(first.item(slot)).contains(item);
        assertThat(next.item(slot)).isEmpty();
        assertThat(next.items()).containsOnlyKeys(GridSlot.of(1, 8));
        assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.items().entrySet().iterator().next().setValue(GridItem.of(ICON)))
                .isInstanceOf(UnsupportedOperationException.class);
        Grid equal = Grid.builder(TITLE, 2).set(slot, GridItem.of(ICON)).build();
        assertThat(first).isEqualTo(equal).isEqualTo(first).isNotEqualTo(next).isNotEqualTo(null).isNotEqualTo("foreign");
        assertThat(first.hashCode()).isEqualTo(equal.hashCode());
        assertThat(first.toString()).isEqualTo(equal.toString()).contains("rows=2");
        GridAction action = click -> { /* This action is compared, not invoked. */ };
        assertThat(builder.set(0, 0, ICON, action).build().item(slot).orElseThrow().action()).contains(action);
        assertThat(builder.set(slot, item).build().item(slot).orElseThrow().action()).isEmpty();
    }

    @Test
    void gridItem_comparesIconByValueAndActionByIdentity() {
        EqualAction action = new EqualAction();
        EqualAction other = new EqualAction();
        GridItem first = GridItem.of(ICON, action);
        GridItem equal = GridItem.of(ItemSpec.builder(Key.key("minecraft", "stone")).build(), action);
        assertThat(action).isEqualTo(other).isNotSameAs(other);
        assertThat(first).isEqualTo(first).isEqualTo(equal).isNotEqualTo(GridItem.of(ICON, other))
                .isNotEqualTo(GridItem.of(ICON)).isNotEqualTo(null).isNotEqualTo("foreign");
        assertThat(first.hashCode()).isEqualTo(equal.hashCode());
        assertThat(first.toString()).contains("GridItem[icon=");
        assertThat(first.icon()).isSameAs(ICON);
        assertThat(first.action()).containsSame(action);
        assertThat(GridItem.of(ICON).action()).isEmpty();
    }

    @Test
    void gridAndItems_rejectNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> GridItem.of(null)).withMessage("icon");
        assertThatNullPointerException().isThrownBy(() -> GridItem.of(ICON, null)).withMessage("action");
        assertThatNullPointerException().isThrownBy(() -> Grid.builder(TITLE, 1).set(null, GridItem.of(ICON))).withMessage("slot");
        assertThatNullPointerException().isThrownBy(() -> Grid.builder(TITLE, 1).set(GridSlot.of(0, 0), null)).withMessage("item");
        assertThatNullPointerException().isThrownBy(() -> Grid.builder(TITLE, 1).set(0, 0, null, null)).withMessage("icon");
        assertThatNullPointerException().isThrownBy(() -> Grid.builder(TITLE, 1).clear(null)).withMessage("slot");
        assertThatNullPointerException().isThrownBy(() -> Grid.builder(TITLE, 1).build().item(null)).withMessage("slot");
    }

    private static final class EqualAction implements GridAction {
        @Override public void onClick(GridClick click) {
            // Deliberately value-equal handlers verify that GridItem still uses identity.
        }
        @Override public boolean equals(Object other) { return other instanceof EqualAction; }
        @Override public int hashCode() { return 1; }
    }
}
