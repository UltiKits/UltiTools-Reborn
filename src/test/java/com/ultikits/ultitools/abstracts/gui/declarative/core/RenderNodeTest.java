package com.ultikits.ultitools.abstracts.gui.declarative.core;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.GridAction;
import com.ultikits.ultitools.ui.GridItem;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.SlotContainer;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(30)
public class RenderNodeTest {
    private static GridItem item(String material) { return GridItem.of(ItemSpec.builder(Key.key("minecraft", material)).build()); }
    private static SlotBinding binding() {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(9);
        return new SlotBinding(container, 2, BindingMode.VIEW);
    }
    @Test void testRenderNodeCreation() {
        SlotKey key = SlotKey.of("test");
        RenderNode node = new RenderNode(key, 10);
        assertThat(node.getKey()).isEqualTo(key);
        assertThat(node.getSlotIndex()).isEqualTo(10);
        assertThat(node.getItem()).isNull();
        assertThat(node.getBinding()).isNull();
        assertThat(node.isLeaf()).isTrue();
    }
    @Test void testRenderNodeWithIcon() {
        GridItem icon = item("diamond");
        RenderNode node = new RenderNode(null, 5, icon);
        assertThat(node.getSlotIndex()).isEqualTo(5);
        assertThat(node.getItem()).isSameAs(icon);
    }
    @Test void testChildManagement() {
        RenderNode parent = new RenderNode(SlotKey.of("parent"), -1);
        RenderNode a = new RenderNode(SlotKey.of("a"), 1);
        RenderNode b = new RenderNode(SlotKey.of("b"), 2);
        assertThat(parent.isLeaf()).isTrue();
        assertThat(parent.isContainer()).isFalse();
        parent.addChild(a); parent.addChild(b);
        assertThat(parent.isLeaf()).isFalse();
        assertThat(parent.isContainer()).isTrue();
        assertThat(parent.getChildren()).containsExactly(a, b);
        assertThat(a.getParent()).isSameAs(parent);
        assertThat(b.getParent()).isSameAs(parent);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> parent.getChildren().clear());
    }
    @Test void testRemoveChild() {
        RenderNode parent = new RenderNode(null, -1);
        RenderNode child = new RenderNode(null, 1);
        parent.addChild(child); parent.removeChild(child);
        assertThat(parent.getChildren()).isEmpty();
        assertThat(child.getParent()).isNull();
    }
    @Test void testClearChildren() {
        RenderNode parent = new RenderNode(null, -1);
        RenderNode a = new RenderNode(null, 1), b = new RenderNode(null, 2);
        parent.addChild(a); parent.addChild(b); parent.clearChildren();
        assertThat(parent.getChildren()).isEmpty();
        assertThat(a.getParent()).isNull();
        assertThat(b.getParent()).isNull();
    }
    @Test void testGetAllLeaves() {
        RenderNode root = new RenderNode(null, -1), branch = new RenderNode(null, -1);
        RenderNode a = new RenderNode(SlotKey.of("a"), 1), b = new RenderNode(SlotKey.of("b"), 2);
        root.addChild(a); root.addChild(branch); branch.addChild(b);
        assertThat(root.getAllLeaves()).containsExactly(a, b);
    }
    @Test void testCalculateSlotCount() {
        RenderNode root = new RenderNode(null, -1), bound = new RenderNode(null, 2);
        bound.setBinding(binding());
        root.addChild(new RenderNode(null, 0, item("stone")));
        root.addChild(new RenderNode(null, 1)); root.addChild(bound);
        assertThat(root.calculateSlotCount()).isEqualTo(2);
        assertThat(new RenderNode(null, 1).calculateSlotCount()).isZero();
    }
    @Test void testMetadata() {
        RenderNode node = new RenderNode(null, 0);
        node.setMetadata("key", "value");
        assertThat(node.getMetadata("key")).isEqualTo("value");
        node.setMetadata("key", null);
        assertThat(node.getMetadata("key")).isNull();
    }
    @Test void testCopy() {
        GridAction action = click -> { /* action identity must survive a node snapshot */ };
        RenderNode original = new RenderNode(SlotKey.of("original"), 10,
                GridItem.of(ItemSpec.builder(Key.key("minecraft:gold_ingot")).build(), action));
        original.setMetadata("meta", "value");
        original.addChild(new RenderNode(null, 1));
        RenderNode copy = original.copy();
        assertThat(copy.getKey()).isEqualTo(original.getKey());
        assertThat(copy.getSlotIndex()).isEqualTo(10);
        assertThat(copy.getItem().action()).contains(action);
        assertThat(copy.getMetadata("meta")).isEqualTo("value");
        assertThat(copy.getChildren()).isEmpty();
        assertThat(copy.getParent()).isNull();
    }
    @Test void testCopyIsIndependentOfLaterMutationOnTheOriginal() {
        RenderNode original = new RenderNode(SlotKey.of("original"), 0, item("diamond"));
        original.setMetadata("meta", "before");
        RenderNode copy = original.copy();
        original.setItem(item("gold_ingot")); original.setSlotIndex(5); original.setMetadata("meta", "after");
        assertThat(copy.getItem()).isEqualTo(item("diamond"));
        assertThat(copy.getSlotIndex()).isZero();
        assertThat(copy.getMetadata("meta")).isEqualTo("before");
        original.setBinding(binding());
        RenderNode boundCopy = original.copy();
        original.setBinding(null);
        assertThat(boundCopy.getBinding()).isNotNull();
        assertThat(boundCopy.getItem()).isNull();
    }
    @Test void itemAndBindingSettersAreMutuallyExclusive() {
        RenderNode node = new RenderNode(null, 0, item("stone"));
        SlotBinding binding = binding();
        node.setBinding(binding);
        assertThat(node.getItem()).isNull(); assertThat(node.getBinding()).isSameAs(binding);
        node.setItem(item("diamond"));
        assertThat(node.getBinding()).isNull(); assertThat(node.getItem()).isEqualTo(item("diamond"));
        node.setItem(null);
        assertThat(node.calculateSlotCount()).isZero();
    }
    @Test void testEquality() {
        SlotKey key = SlotKey.of("same");
        RenderNode a = new RenderNode(key, 1), b = new RenderNode(key, 2), c = new RenderNode(null, 1);
        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(a).isNotEqualTo(null).isNotEqualTo("node");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(c.hashCode()).isEqualTo(1);
        assertThat(a).isNotEqualTo(new RenderNode(SlotKey.of("different"), 1));
        assertThat(c).isNotEqualTo(new RenderNode(null, 2));
        assertThat(a.toString()).contains("same", "slot=1");
        assertThat(c.toString()).contains("key=null");
    }
}
