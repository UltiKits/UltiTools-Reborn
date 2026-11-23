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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.ListPersistentDataTypeProvider;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
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
        assertThatThrownBy(() -> ItemSpec.builder(Key.key("minecraft:air"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSpec.builder(Key.key("air"))).isInstanceOf(IllegalArgumentException.class);
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
                base.toBuilder().hiddenInTooltip(Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS)).build(),
                base.toBuilder().data(DataComponentTypes.MAX_STACK_SIZE, 16).build(),
                base.toBuilder().marker(MARKER, PersistentDataType.STRING, "value").build());
        for (ItemSpec spec : different) {
            assertThat(spec).isNotEqualTo(base).isEqualTo(spec.toBuilder().build());
            assertThat(spec.hashCode()).isNotEqualTo(base.hashCode());
            assertThat(spec.toBuilder().build()).hasSameHashCodeAs(spec);
        }
        assertThat(base.equals(base)).isTrue();
        assertThat(base).isNotEqualTo(null);
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
                .hiddenInTooltip(Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS))
                .data(DataComponentTypes.MAX_STACK_SIZE, 16).remove(DataComponentTypes.MAX_DAMAGE)
                .marker(MARKER, PersistentDataType.STRING, "value").build();
        assertThat(original.toBuilder().build()).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(original.name()).isEqualTo(Text.of(name, Arg.string("p", "value")));
        assertThat(original.lore()).containsExactly(Text.plain("first"), Text.of(lore));
        assertThat(original.model()).isEqualTo(Key.key("sample:model"));
        assertThat(original.glint()).isFalse();
        assertThat(original.tooltipHidden()).isTrue();
        assertThat(original.hiddenInTooltip()).containsExactly(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        ItemSpec cleared = original.toBuilder().name((Text) null).model(null).glint(null).lore(List.of())
                .hiddenInTooltip(Set.of()).build();
        assertThat(cleared.hiddenInTooltip()).isEmpty();
        assertThat(original.hiddenInTooltip()).containsExactly(DataComponentTypes.ATTRIBUTE_MODIFIERS);
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
        assertThatNullPointerException().isThrownBy(() -> builder.hiddenInTooltip(null)).withMessage("types");
        Set<DataComponentType> nullType = new LinkedHashSet<>();
        nullType.add(null);
        assertThatNullPointerException().isThrownBy(() -> builder.hiddenInTooltip(nullType)).withMessage("type");
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

    @Test
    void hiddenInTooltip_isCopiedAndComparedByKey() {
        DataComponentType first = new TestValued<Integer>(DataComponentTypes.ATTRIBUTE_MODIFIERS.getKey());
        DataComponentType duplicate = new TestFlag(first.getKey());
        Set<DataComponentType> input = new LinkedHashSet<>(List.of(first, duplicate));
        ItemSpec.Builder builder = ItemSpec.builder(DIAMOND).hiddenInTooltip(input);
        ItemSpec spec = builder.build();
        input.clear();
        builder.hiddenInTooltip(Set.of(DataComponentTypes.MAX_DAMAGE));
        assertThat(spec.hiddenInTooltip()).singleElement().isSameAs(first);
        assertThatThrownBy(() -> spec.hiddenInTooltip().clear()).isInstanceOf(UnsupportedOperationException.class);
        ItemSpec sameKey = ItemSpec.builder(DIAMOND).hiddenInTooltip(Set.of(duplicate)).build();
        assertThat(spec).isEqualTo(sameKey).hasSameHashCodeAs(sameKey);
        assertThat(spec.toBuilder().build()).isEqualTo(spec);
        assertThat(spec.toBuilder().hiddenInTooltip(Set.of()).build().hiddenInTooltip()).isEmpty();
        assertThat(spec.hiddenInTooltip()).containsExactly(first);
    }

    @Test
    void markers_equalArrayValues_specsAreEqual() {
        ItemSpec first = markerSpec(PersistentDataType.BYTE_ARRAY, new byte[] {1, 2});
        ItemSpec second = markerSpec(PersistentDataType.BYTE_ARRAY, new byte[] {1, 2});
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertEqualMarkerValues(PersistentDataType.INTEGER_ARRAY, new int[] {1, 2}, new int[] {1, 2});
        assertEqualMarkerValues(PersistentDataType.LONG_ARRAY, new long[] {1, 2}, new long[] {1, 2});
        assertEqualMarkerValues(PersistentDataType.LIST.byteArrays(),
                List.of(new byte[] {1, 2}), List.of(new byte[] {1, 2}));
    }

    @Test
    void markers_arrayValue_isCopiedInAndOut() {
        byte[] input = {1, 2};
        ItemSpec spec = ItemSpec.builder(DIAMOND).marker(MARKER, PersistentDataType.BYTE_ARRAY, input).build();
        ItemSpec expected = ItemSpec.builder(DIAMOND)
                .marker(MARKER, PersistentDataType.BYTE_ARRAY, new byte[] {1, 2}).build();
        input[0] = 9;
        assertThat((byte[]) spec.markers().getFirst().value()).containsExactly(1, 2);
        ((byte[]) spec.markers().getFirst().value())[1] = 9;
        assertThat(spec).isEqualTo(expected).hasSameHashCodeAs(expected);
        assertThat(spec.markers().getFirst().toString()).contains("value=[1, 2]");
    }

    @Test
    void markers_everyStandardPersistentDataType_isImmutableAndValueEqual() throws ReflectiveOperationException {
        Map<String, StandardSample> samples = standardSamples();
        Set<String> visited = new LinkedHashSet<>();
        for (Field field : PersistentDataType.class.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && PersistentDataType.class.isAssignableFrom(field.getType())) {
                assertStandardSample(field.getName(), (PersistentDataType<?, ?>) field.get(null), samples);
                visited.add(field.getName());
            }
        }
        for (Method method : ListPersistentDataTypeProvider.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && method.getParameterCount() == 0
                    && PersistentDataType.class.isAssignableFrom(method.getReturnType())) {
                String name = "LIST." + method.getName();
                assertStandardSample(name, (PersistentDataType<?, ?>) method.invoke(PersistentDataType.LIST), samples);
                visited.add(name);
            }
        }
        assertThat(visited).containsExactlyInAnyOrderElementsOf(samples.keySet());
    }

    private static void assertStandardSample(String name, PersistentDataType<?, ?> type,
            Map<String, StandardSample> samples) {
        assertThat(samples).as("Every standard type needs a sample: %s", name).containsKey(name);
        StandardSample sample = samples.get(name);
        assertThat(sample.first()).as(name).isNotSameAs(sample.second());
        if (sample.rejected()) {
            assertThatThrownBy(() -> markerSpec(type, sample.first())).as(name)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> markerSpec(type, sample.second())).as(name)
                    .isInstanceOf(IllegalArgumentException.class);
            return;
        }
        ItemSpec first = markerSpec(type, sample.first());
        ItemSpec second = markerSpec(type, sample.second());
        assertThat(first).as(name).isEqualTo(second).hasSameHashCodeAs(second);
        mutateInput(sample.first());
        assertThat(first).as("input is copied: %s", name).isEqualTo(second).hasSameHashCodeAs(second);
        Object exposed = first.markers().getFirst().value();
        if (exposed.getClass().isArray()) {
            mutateArray(exposed);
        } else if (exposed instanceof List<?> list) {
            for (Object element : list) {
                if (element.getClass().isArray()) {
                    mutateArray(element);
                }
            }
            assertThatThrownBy(list::clear).as(name).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(first).as("output is copied: %s", name).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.markers().getFirst().toString()).doesNotContain("[B@", "[I@", "[J@");
    }

    private static Map<String, StandardSample> standardSamples() throws ReflectiveOperationException {
        Map<String, StandardSample> samples = new LinkedHashMap<>();
        samples.put("BYTE", scalarSample((byte) 7));
        samples.put("SHORT", scalarSample((short) 7));
        samples.put("INTEGER", scalarSample(7));
        samples.put("LONG", scalarSample(7L));
        samples.put("FLOAT", scalarSample(7.5f));
        samples.put("DOUBLE", scalarSample(7.5));
        samples.put("BOOLEAN", scalarSample(true));
        samples.put("STRING", scalarSample("value"));
        samples.put("BYTE_ARRAY", new StandardSample(new byte[] {1, 2}, new byte[] {1, 2}, false));
        samples.put("INTEGER_ARRAY", new StandardSample(new int[] {1, 2}, new int[] {1, 2}, false));
        samples.put("LONG_ARRAY", new StandardSample(new long[] {1, 2}, new long[] {1, 2}, false));
        samples.put("LIST.bytes", listSample((byte) 7));
        samples.put("LIST.shorts", listSample((short) 7));
        samples.put("LIST.integers", listSample(7));
        samples.put("LIST.longs", listSample(7L));
        samples.put("LIST.floats", listSample(7.5f));
        samples.put("LIST.doubles", listSample(7.5));
        samples.put("LIST.booleans", listSample(true));
        samples.put("LIST.strings", listSample("value"));
        samples.put("LIST.byteArrays", new StandardSample(new ArrayList<>(List.of(new byte[] {1, 2})),
                new ArrayList<>(List.of(new byte[] {1, 2})), false));
        samples.put("LIST.integerArrays", new StandardSample(new ArrayList<>(List.of(new int[] {1, 2})),
                new ArrayList<>(List.of(new int[] {1, 2})), false));
        samples.put("LIST.longArrays", new StandardSample(new ArrayList<>(List.of(new long[] {1, 2})),
                new ArrayList<>(List.of(new long[] {1, 2})), false));
        PersistentDataContainer first = new ItemStack(Material.STONE).getPersistentDataContainer()
                .getAdapterContext().newPersistentDataContainer();
        PersistentDataContainer second = first.getAdapterContext().newPersistentDataContainer();
        samples.put("TAG_CONTAINER", new StandardSample(first, second, true));
        samples.put("TAG_CONTAINER_ARRAY", new StandardSample(new PersistentDataContainer[] {first},
                new PersistentDataContainer[] {second}, true));
        samples.put("LIST.dataContainers", new StandardSample(new ArrayList<>(List.of(first)),
                new ArrayList<>(List.of(second)), true));
        return samples;
    }

    private static StandardSample scalarSample(Object first) throws ReflectiveOperationException {
        Object second;
        if (first instanceof String string) {
            second = new String(string.toCharArray());
        } else {
            // Public wrapper constructors give distinct samples even for JVM-cached scalar values.
            Class<?> primitive = (Class<?>) first.getClass().getField("TYPE").get(null);
            second = first.getClass().getConstructor(primitive).newInstance(first);
        }
        return new StandardSample(first, second, false);
    }

    private static StandardSample listSample(Object value) throws ReflectiveOperationException {
        StandardSample scalar = scalarSample(value);
        return new StandardSample(new ArrayList<>(List.of(scalar.first())),
                new ArrayList<>(List.of(scalar.second())), false);
    }

    private static void mutateInput(Object value) {
        if (value.getClass().isArray()) {
            mutateArray(value);
        } else if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element.getClass().isArray()) {
                    mutateArray(element);
                }
            }
            list.clear();
        }
    }

    private static void mutateArray(Object value) {
        if (Array.getLength(value) > 0) {
            Array.set(value, 0, Array.get(Array.newInstance(value.getClass().getComponentType(), 1), 0));
        }
    }

    private static <P, C> ItemSpec markerSpec(PersistentDataType<P, C> type, Object value) {
        return ItemSpec.builder(DIAMOND).marker(MARKER, type, type.getComplexType().cast(value)).build();
    }

    @Test
    void markers_nestedAggregates_areDeeplyCopiedAndCompared() {
        TestObjectType<Object[]> type = new TestObjectType<>(Object[].class);
        Object[] input = {new byte[] {1, 2}, List.of(new int[] {3, 4}), null};
        ItemMarker<Object, Object[]> marker = new ItemMarker<>(MARKER, type, input);
        ItemMarker<Object, Object[]> expected = new ItemMarker<>(MARKER, type,
                new Object[] {new byte[] {1, 2}, List.of(new int[] {3, 4}), null});
        int originalHash = marker.hashCode();
        ((byte[]) input[0])[0] = 9;
        Object[] exposed = marker.value();
        ((byte[]) exposed[0])[1] = 9;
        ((int[]) ((List<?>) exposed[1]).getFirst())[0] = 9;
        assertThat(marker).isEqualTo(expected).hasSameHashCodeAs(expected);
        assertThat(marker.hashCode()).isEqualTo(originalHash);
        assertThat(marker.toString()).contains("value=[[1, 2], [[3, 4]], null]");
        assertThat(marker.equals(marker)).isTrue();
        assertThat(marker).isNotEqualTo(null);
        assertThat(marker.equals("not a marker")).isFalse();
        assertThat(marker).isNotEqualTo(new ItemMarker<>(MARKER, type, new Object[0]));
        assertThat(marker).isNotEqualTo(new ItemMarker<>(MARKER, type,
                new Object[] {new byte[] {9, 2}, List.of(new int[] {3, 4}), null}));
        assertThat(marker).isNotEqualTo(new ItemMarker<>(MARKER, type,
                new Object[] {new byte[] {1, 2}, List.of(), null}));
        assertThat(marker).isNotEqualTo(new ItemMarker<>(MARKER, type,
                new Object[] {new byte[] {1, 2}, List.of(new int[] {9, 4}), null}));
        assertEqualMarkerValues(new TestObjectType<>(boolean[].class), new boolean[] {true}, new boolean[] {true});
        assertEqualMarkerValues(new TestObjectType<>(short[].class), new short[] {2}, new short[] {2});
        assertEqualMarkerValues(new TestObjectType<>(char[].class), new char[] {'x'}, new char[] {'x'});
        assertEqualMarkerValues(new TestObjectType<>(float[].class), new float[] {Float.NaN}, new float[] {Float.NaN});
        assertEqualMarkerValues(new TestObjectType<>(double[].class), new double[] {-0.0}, new double[] {-0.0});
        PersistentDataContainer container = new ItemStack(Material.STONE).getPersistentDataContainer()
                .getAdapterContext().newPersistentDataContainer();
        assertThatThrownBy(() -> new ItemMarker<>(MARKER, type, new Object[] {List.of(container)}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemMarker<>(MARKER, type, new PersistentDataContainer[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemMarker<>(MARKER, type, new PersistentDataContainer[0][0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record TestObjectType<C>(Class<C> complexType) implements PersistentDataType<Object, C> {
        @Override public Class<Object> getPrimitiveType() { return Object.class; }
        @Override public Class<C> getComplexType() { return complexType; }
        @Override public Object toPrimitive(C value, PersistentDataAdapterContext context) { return value; }
        @Override public C fromPrimitive(Object value, PersistentDataAdapterContext context) { return complexType.cast(value); }
    }

    private record StandardSample(Object first, Object second, boolean rejected) { }

    private static <P, C> void assertEqualMarkerValues(PersistentDataType<P, C> type, C firstValue, C secondValue) {
        ItemSpec first = ItemSpec.builder(DIAMOND).marker(MARKER, type, firstValue).build();
        ItemSpec second = ItemSpec.builder(DIAMOND).marker(MARKER, type, secondValue).build();
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
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
