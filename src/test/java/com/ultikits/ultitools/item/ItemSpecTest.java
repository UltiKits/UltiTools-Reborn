package com.ultikits.ultitools.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemSpecTest {
    private static final Key DIAMOND = Key.key("minecraft:diamond");
    private static final NamespacedKey MARKER = new NamespacedKey("sample", "marker");

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void builder_repeatedBuilds_areEqualIndependentAndImmutable() {
        List<Text> input = new ArrayList<>(List.of(Text.plain("first")));
        ItemSpec.Builder builder = ItemSpec.builder(DIAMOND).lore(input)
                .data(DataComponentTypes.MAX_STACK_SIZE, 16)
                .marker(MARKER, PersistentDataType.STRING, "first");
        ItemSpec first = builder.build();
        ItemSpec second = builder.build();
        assertThat(first).isEqualTo(second).isNotSameAs(second).hasSameHashCodeAs(second);
        input.clear();
        builder.amount(2).addLore(Text.plain("second")).remove(DataComponentTypes.MAX_STACK_SIZE)
                .marker(MARKER, PersistentDataType.STRING, "changed");
        assertThat(first.amount()).isEqualTo(1);
        assertThat(first.lore()).containsExactly(Text.plain("first"));
        assertThat(first.patches()).hasSize(1);
        assertThat(first.markers()).containsExactly(new ItemMarker<>(MARKER, PersistentDataType.STRING, "first"));
        assertThatThrownBy(() -> first.lore().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.patches().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.markers().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void amountAndMaterial_invalidInputs_areRejected() {
        assertThatThrownBy(() -> ItemSpec.builder(DIAMOND).amount(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSpec.builder(DIAMOND).amount(100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSpec.builder(Material.AIR)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ItemSpec.builder(Material.DIAMOND).amount(99).build().itemType()).isEqualTo(DIAMOND);
    }

    @Test
    void equalsAndHashCode_includeEveryAttribute() {
        ItemSpec base = ItemSpec.builder(DIAMOND).build();
        List<ItemSpec> different = List.of(
                ItemSpec.builder(Key.key("minecraft:stone")).build(),
                base.toBuilder().amount(2).build(),
                base.toBuilder().name(Text.plain("name")).build(),
                base.toBuilder().addLore(Text.plain("lore")).build(),
                base.toBuilder().model(Key.key("sample:model")).build(),
                base.toBuilder().glint(true).build(),
                base.toBuilder().tooltipHidden(true).build(),
                base.toBuilder().data(DataComponentTypes.MAX_STACK_SIZE, 16).build(),
                base.toBuilder().marker(MARKER, PersistentDataType.STRING, "value").build());
        for (ItemSpec spec : different) {
            assertThat(spec).isNotEqualTo(base).isEqualTo(spec.toBuilder().build());
            assertThat(spec.hashCode()).isNotEqualTo(base.hashCode());
            assertThat(spec.toBuilder().build()).hasSameHashCodeAs(spec);
        }
        assertThat(base.equals(base)).isTrue();
        assertThat(base.equals(null)).isFalse();
        assertThat(base.equals("other")).isFalse();
        assertThat(base.toString()).contains("minecraft:diamond", "amount=1", "name=null", "lore=[]",
                "model=null", "glint=null", "tooltipHidden=false", "patches=[]", "markers=[]");
    }

    @Test
    void toBuilder_copiesEveryAttributeWithoutAliasingBuilderState() {
        MessageKey name = MessageKey.of("sample", "name");
        MessageKey lore = MessageKey.of("sample", "lore");
        ItemSpec original = ItemSpec.builder(DIAMOND).amount(3).name(name, Arg.string("p", "value"))
                .lore(List.of(Text.plain("first"))).addLore(lore)
                .model(Key.key("sample:model")).glint(false).tooltipHidden(true)
                .data(DataComponentTypes.MAX_STACK_SIZE, 16).remove(DataComponentTypes.MAX_DAMAGE)
                .marker(MARKER, PersistentDataType.STRING, "value").build();
        assertThat(original.toBuilder().build()).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(original.name()).isEqualTo(Text.of(name, Arg.string("p", "value")));
        assertThat(original.lore()).containsExactly(Text.plain("first"), Text.of(lore));
        assertThat(original.model()).isEqualTo(Key.key("sample:model"));
        assertThat(original.glint()).isFalse();
        assertThat(original.tooltipHidden()).isTrue();
        ItemSpec cleared = original.toBuilder().name((Text) null).model(null).glint(null).lore(List.of()).build();
        assertThat(cleared.name()).isNull();
        assertThat(cleared.model()).isNull();
        assertThat(cleared.glint()).isNull();
        assertThat(cleared.lore()).isEmpty();
        assertThat(original.name()).isNotNull();
    }

    @Test
    void typedComponents_cannotBeAssignedFlaggedOrRemovedEvenThroughAnotherTypeObject() {
        List<DataComponentType> typed = List.of(DataComponentTypes.ITEM_NAME, DataComponentTypes.LORE,
                DataComponentTypes.ITEM_MODEL, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
                DataComponentTypes.TOOLTIP_DISPLAY);
        for (DataComponentType component : typed) {
            DataComponentType.Valued<String> valued = new TestValued<>(component.getKey());
            DataComponentType.NonValued flag = new TestFlag(component.getKey());
            assertThatThrownBy(() -> ItemSpec.builder(DIAMOND).data(valued, "value"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ItemSpec.builder(DIAMOND).data(flag)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ItemSpec.builder(DIAMOND).remove(component))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ItemSpec.builder(DIAMOND).remove(valued))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void passThroughPatchesAndMarkers_preserveInsertionOrderAndReplaceMarkersByKey() {
        TestValued<Integer> valued = new TestValued<>(new NamespacedKey("sample", "valued"));
        TestFlag flag = new TestFlag(new NamespacedKey("sample", "flag"));
        NamespacedKey second = new NamespacedKey("sample", "second");
        ItemSpec spec = ItemSpec.builder(DIAMOND).data(valued, 5).data(flag).remove(valued)
                .marker(MARKER, PersistentDataType.STRING, "first")
                .marker(second, PersistentDataType.INTEGER, 42)
                .marker(MARKER, PersistentDataType.STRING, "replacement").build();
        assertThat(spec.patches()).containsExactly(new DataPatch.Assign<>(valued, 5),
                new DataPatch.Flag(flag), new DataPatch.Remove(valued));
        assertThat(spec.markers()).containsExactly(new ItemMarker<>(MARKER, PersistentDataType.STRING, "replacement"),
                new ItemMarker<>(second, PersistentDataType.INTEGER, 42));
        assertThat(spec.toBuilder().build()).isEqualTo(spec);
    }

    @Test
    void keyBuilder_needsNoRunningServer() {
        MockBukkitHelper.safeUnmock();
        ItemSpec spec = ItemSpec.builder(DIAMOND).name(Text.plain("constant")).amount(2).build();
        assertThat(spec.itemType()).isEqualTo(DIAMOND);
        assertThat(spec.toBuilder().build()).isEqualTo(spec);
    }

    @Test
    void nullArguments_areRejectedAtAllValueBoundaries() {
        DataComponentType.Valued<String> valued = new TestValued<>(new NamespacedKey("sample", "value"));
        assertThatNullPointerException().isThrownBy(() -> ItemSpec.builder((Key) null)).withMessage("itemType");
        assertThatNullPointerException().isThrownBy(() -> ItemSpec.builder((Material) null)).withMessage("material");
        ItemSpec.Builder builder = ItemSpec.builder(DIAMOND);
        assertThatNullPointerException().isThrownBy(() -> builder.lore(null)).withMessage("lines");
        assertThatNullPointerException().isThrownBy(() -> builder.addLore((Text) null)).withMessage("line");
        assertThatNullPointerException().isThrownBy(() -> builder.name(null, new Arg[0])).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> builder.name(MessageKey.of("a", "b"), (Arg[]) null))
                .withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> builder.addLore(null, new Arg[0])).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> builder.addLore(MessageKey.of("a", "b"), (Arg[]) null))
                .withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> builder.data(null, "value")).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> builder.data(valued, null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> builder.data((DataComponentType.NonValued) null))
                .withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> builder.remove(null)).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> builder.marker(null, PersistentDataType.STRING, "v"))
                .withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> builder.marker(MARKER, null, "v")).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> builder.marker(MARKER, PersistentDataType.STRING, null))
                .withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> new DataPatch.Assign<>(null, "v")).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> new DataPatch.Assign<>(valued, null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> new DataPatch.Flag(null)).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> new DataPatch.Remove(null)).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> new ItemMarker<>(null, PersistentDataType.STRING, "v"))
                .withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> new ItemMarker<>(MARKER, null, "v")).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> new ItemMarker<>(MARKER, PersistentDataType.STRING, null))
                .withMessage("value");
    }

    private record TestValued<T>(NamespacedKey key) implements DataComponentType.Valued<T> {
        @Override public NamespacedKey getKey() { return key; }
        @Override public boolean isPersistent() { return true; }
    }

    private record TestFlag(NamespacedKey key) implements DataComponentType.NonValued {
        @Override public NamespacedKey getKey() { return key; }
        @Override public boolean isPersistent() { return true; }
    }
}
