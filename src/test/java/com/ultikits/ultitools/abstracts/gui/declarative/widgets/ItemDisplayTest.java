package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.GridAction;
import com.ultikits.ultitools.ui.GridClick;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

@Timeout(30)
public class ItemDisplayTest {
    private static final ItemSpec ICON = ItemSpec.builder(Key.key("minecraft:diamond")).build();

    @Test void testItemDisplayCreation() {
        ItemSpec icon = ICON.toBuilder().name(Text.plain("Test Item"))
                .lore(List.of(Text.plain("Line 1"), Text.plain("Line 2"))).build();
        ItemDisplay display = ItemDisplay.builder(icon).slot(10).build();
        assertThat(display.getSlot()).isEqualTo(10);
        assertThat(display.getIcon()).isEqualTo(icon);
        assertThat(display.getIcon().name()).isEqualTo(Text.plain("Test Item"));
        assertThat(display.getIcon().lore()).containsExactly(Text.plain("Line 1"), Text.plain("Line 2"));
    }

    @Test void testItemDisplayWithKey() {
        assertThat(ItemDisplay.builder(ICON).key("my-item").build().getKey()).isEqualTo(SlotKey.of("my-item"));
        SlotKey key = SlotKey.of("typed");
        assertThat(ItemDisplay.builder(ICON).key(key).build().getKey()).isSameAs(key);
    }

    @Test void testItemDisplayWithClickHandler() {
        AtomicBoolean clicked = new AtomicBoolean();
        ItemDisplay display = ItemDisplay.builder(ICON).onClick(() -> clicked.set(true)).build();
        display.getClickHandler().onClick(mock(GridClick.class));
        assertThat(clicked).isTrue();
        GridAction action = click -> clicked.set(false);
        assertThat(ItemDisplay.builder(ICON).onClick(action).build().getClickHandler()).isSameAs(action);
    }

    @Test void testItemDisplayElementCreatesRenderNode() {
        RenderObjectElement element = ItemDisplay.builder(ICON).slot(5).build().createElement();
        RenderNode node = element.getRenderNode();
        assertThat(node.getSlotIndex()).isEqualTo(5);
        assertThat(node.getItem().icon()).isEqualTo(ICON);
        assertThat(node.getItem().action()).isEmpty();
    }

    @Test void testItemDisplayBuilderDefaults() {
        ItemDisplay display = ItemDisplay.builder(ICON).build();
        assertThat(display.getSlot()).isZero();
        assertThat(display.getKey()).isNull();
        assertThat(display.getClickHandler()).isNull();
        assertThat(display.getIcon().name()).isNull();
        assertThat(display.getIcon().lore()).isEmpty();
    }

    @Test void updateRemovesOldActionAndReplacesDescriptionAndPosition() {
        GridAction action = click -> { /* the presence of this action is under test */ };
        RenderObjectElement element = ItemDisplay.builder(ICON).onClick(action).build().createElement();
        element.assignContext(BuildContext.root(null, "item", 1));
        element.mount(null);
        RenderNode node = element.getRenderNode();
        assertThat(node.getItem().action()).contains(action);
        ItemSpec next = ICON.toBuilder().amount(2).build();
        element.update(ItemDisplay.builder(next).slot(3).onClick((Runnable) null).build());
        element.performRebuild();
        assertThat(element.getRenderNode()).isSameAs(node);
        assertThat(node.getItem().action()).isEmpty();
        assertThat(node.getItem().icon()).isEqualTo(next);
        assertThat(node.getSlotIndex()).isEqualTo(3);
        element.unmount();
    }

    @Test void nullDescriptionIsRejectedAndTypedNullActionClears() {
        assertThatNullPointerException().isThrownBy(() -> ItemDisplay.builder(null));
        assertThat(ItemDisplay.builder(ICON).key((SlotKey) null).onClick((GridAction) null).build().getClickHandler()).isNull();
    }
}
