package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void bind_sameIndexTwice_throws() {
        SlotContainer container = sizedContainer(3);
        assertThatThrownBy(() -> Grid.builder(TITLE, 1).bind(GridSlot.ofIndex(0), container, 1, BindingMode.EDITABLE)
                .bind(GridSlot.ofIndex(1), container, 1, BindingMode.VIEW).build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(Grid.builder(TITLE, 1).bind(GridSlot.ofIndex(0), container, 1, BindingMode.VIEW)
                .bind(GridSlot.ofIndex(1), sizedContainer(3), 1, BindingMode.VIEW).build().bindings()).hasSize(2);
    }

    @Test
    void bind_replacesIcon_andSetReplacesBinding() {
        SlotContainer container = sizedContainer(9);
        GridSlot slot = GridSlot.ofIndex(0);
        Grid.Builder builder = Grid.builder(TITLE, 1).set(slot, GridItem.of(ICON));
        Grid bound = builder.bind(slot, container, 2, BindingMode.EDITABLE).build();
        assertThat(bound.items()).isEmpty();
        assertThat(bound.binding(slot)).contains(new SlotBinding(container, 2, BindingMode.EDITABLE));
        assertThat(builder.set(slot, GridItem.of(ICON)).build().bindings()).isEmpty();
        assertThat(bound.bindings()).hasSize(1);
        assertThatThrownBy(() -> bound.bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(bound).isNotEqualTo(builder.build());
        assertThat(bound).isEqualTo(Grid.builder(TITLE, 1).bind(slot, container, 2, BindingMode.EDITABLE).build());
    }

    @Test
    void bind_indexOutsideContainer_throws() {
        SlotContainer container = sizedContainer(3);
        for (int index : new int[] {-1, 3, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> Grid.builder(TITLE, 1).bind(GridSlot.ofIndex(0), container, index, BindingMode.EDITABLE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Grid.builder(TITLE, 1).bind(GridSlot.ofIndex(9), container, 0, BindingMode.VIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.builder(TITLE, 1).bindRange(0, 8, 2, container, 0, BindingMode.VIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.builder(TITLE, 2).bindRange(0, 8, 3, container, 1, BindingMode.VIEW))
                .isInstanceOf(IllegalArgumentException.class);
        Grid range = Grid.builder(TITLE, 2).bindRange(0, 8, 3, container, 0, BindingMode.EDITABLE).build();
        assertThat(range.binding(GridSlot.ofIndex(10)).orElseThrow().index()).isEqualTo(2);
        assertThatThrownBy(() -> new SlotBinding(null, 0, BindingMode.VIEW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SlotBinding(container, 0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> range.binding(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void clear_removesBinding() {
        GridSlot slot = GridSlot.ofIndex(0);
        Grid grid = Grid.builder(TITLE, 1).bind(slot, sizedContainer(1), 0, BindingMode.VIEW).clear(slot).build();
        assertThat(grid.bindings()).isEmpty();
        assertThat(grid.item(slot)).isEmpty();
    }

    private static SlotContainer sizedContainer(int size) {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(size);
        return container;
    }

    private static final class EqualAction implements GridAction {
        @Override public void onClick(GridClick click) {
            // Deliberately value-equal handlers verify that GridItem still uses identity.
        }
        @Override public boolean equals(Object other) { return other instanceof EqualAction; }
        @Override public int hashCode() { return 1; }
    }
}
