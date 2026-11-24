package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.GridAction;
import com.ultikits.ultitools.ui.GridClick;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

@Timeout(30)
public class TextButtonTest {
    @BeforeEach void setUp() { MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void testTextButtonCreation() {
        TextButton button = TextButton.builder().text(Text.plain("Click Me")).color("GREEN").slot(13)
                .lore(List.of(Text.plain("Line 1"), Text.plain("Line 2"))).build();
        assertThat(button.getText()).isEqualTo(Text.plain("Click Me"));
        assertThat(button.getColor()).isEqualTo("GREEN");
        assertThat(button.getSlot()).isEqualTo(13);
        assertThat(button.getLore()).containsExactly(Text.plain("Line 1"), Text.plain("Line 2"));
    }
    @Test void testTextButtonDefaults() {
        TextButton button = TextButton.builder().build();
        assertThat(button.getText()).isEqualTo(Text.plain(""));
        assertThat(button.getColor()).isEqualTo("WHITE");
        assertThat(button.getSlot()).isZero();
        assertThat(button.getLore()).isEmpty();
    }
    @Test void testTextButtonWithKey() {
        assertThat(TextButton.builder().key("confirm-btn").build().getKey()).isEqualTo(SlotKey.of("confirm-btn"));
    }
    @Test void testTextButtonWithClickHandler() {
        AtomicBoolean clicked = new AtomicBoolean();
        TextButton button = TextButton.builder().onClick(() -> clicked.set(true)).build();
        button.getClickHandler().onClick(mock(GridClick.class));
        assertThat(clicked).isTrue();
        GridAction action = click -> clicked.set(false);
        assertThat(TextButton.builder().onClick(action).build().getClickHandler()).isSameAs(action);
    }
    @ParameterizedTest @EnumSource(Colors.class)
    void everyColourKeepsItsNativeGlassMaterial(Colors color) {
        TextButtonElement element = new TextButtonElement(TextButton.builder()
                .color(color.name().toLowerCase(Locale.ROOT)).text(Text.plain("Button")).build());
        assertThat(element.createRenderNode().getItem().icon().itemType())
                .isEqualTo(Material.valueOf(color.name() + "_STAINED_GLASS_PANE").getKey());
    }
    @Test void invalidColourStillUsesPlainGlassOnCreationAndUpdate() {
        TextButtonElement element = new TextButtonElement(TextButton.builder().color("not-a-colour").build());
        RenderNode node = element.createRenderNode();
        assertThat(node.getItem().icon().itemType()).isEqualTo(Material.GLASS_PANE.getKey());
        element.updateRenderNode(node);
        assertThat(node.getItem().icon().itemType()).isEqualTo(Material.GLASS_PANE.getKey());
    }
    @Test void testTextButtonWithSlotKeyBuilder() {
        SlotKey key = SlotKey.of("typed");
        assertThat(TextButton.builder().key(key).build().getKey()).isSameAs(key);
        assertThat(TextButton.builder().key((SlotKey) null).build().getKey()).isNull();
    }
    @Test void loreIsCopiedAndUpdateClearsOldAction() {
        List<Text> lore = new ArrayList<>(List.of(Text.plain("before")));
        TextButton button = TextButton.builder().lore(lore).onClick(() -> lore.clear()).build();
        lore.clear();
        assertThat(button.getLore()).containsExactly(Text.plain("before"));
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> button.getLore().clear());
        TextButtonElement element = (TextButtonElement) button.createElement();
        element.assignContext(BuildContext.root(null, "text", 1));
        element.mount(null);
        RenderNode node = element.getRenderNode();
        assertThat(node.getItem().action()).isPresent();
        element.update(TextButton.builder().text(Text.plain("after")).slot(4).onClick((Runnable) null).build());
        element.performRebuild();
        assertThat(node.getItem().action()).isEmpty();
        assertThat(node.getItem().icon().name()).isEqualTo(Text.plain("after"));
        assertThat(node.getSlotIndex()).isEqualTo(4);
        assertThat(TextButton.builder().onClick((GridAction) null).build().getClickHandler()).isNull();
        element.unmount();
    }
}
