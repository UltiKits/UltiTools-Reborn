package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.Test;

class NumericConverterRulesTest {
    private final ConverterRegistry registry = ConverterRegistry.framework();

    @Test
    void integralConversionUsesExactValueAndRangeWithoutDoubleRounding() throws Exception {
        assertThat(read(7, long.class)).isEqualTo(7L);
        assertThat(read(7L, int.class)).isEqualTo(7);
        assertThat(read(1.0D, int.class)).isEqualTo(1);
        assertThat(read("5", int.class)).isEqualTo(5);
        assertThat(read(Long.MAX_VALUE, long.class)).isEqualTo(Long.MAX_VALUE);
        assertThat(read(new BigInteger("9007199254740993"), long.class)).isEqualTo(9007199254740993L);
        for (Object invalid : Arrays.asList(1.5D, 3000000000L, "2147483648", Double.NaN)) {
            assertThatThrownBy(() -> read(invalid, int.class)).isInstanceOf(ConversionException.class);
        }
        assertThatThrownBy(() -> read(128, byte.class)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> read(-32769, short.class)).isInstanceOf(ConversionException.class);
    }

    @Test
    void floatAcceptsOnlyDecimalsThatPrintBackIdentically() throws Exception {
        for (Object valid : Arrays.asList(0.1D, 0.3D, 1.5D, "0.3", "0.3000")) {
            assertThat(read(valid, float.class)).isEqualTo(Float.valueOf(valid.toString()));
        }
        assertThatThrownBy(() -> read(0.123456789D, Float.class)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> read("1e100", float.class)).isInstanceOf(ConversionException.class);
        for (float value : new float[]{0.1F, 0.3F, 1.0E-8F, Float.MIN_VALUE, Float.MAX_VALUE}) {
            Object plain = registry.toPlain(value, float.class, "config/numeric.yml", Collections.singletonList("ratio"));
            assertThat(plain).isEqualTo(Double.valueOf(Float.toString(value)));
            assertThat(read(plain, float.class)).isEqualTo(value);
        }
    }

    @Test
    void nonfiniteNumbersAndNegativeZeroRoundTrip() throws Exception {
        for (Object value : Arrays.asList(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0F,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0D)) {
            Type type = value instanceof Float ? Float.class : Double.class;
            Object plain = registry.toPlain(value, type, "config/numeric.yml", Collections.singletonList("value"));
            assertThat(read(plain, type)).isEqualTo(value);
        }
    }

    @Test
    void scalarRepresentationsPreserveTypesAndRejectBadText() throws Exception {
        assertThat(registry.toPlain((byte) 4, byte.class, "f", Collections.emptyList())).isEqualTo(4);
        assertThat(registry.toPlain((short) 5, short.class, "f", Collections.emptyList())).isEqualTo(5);
        assertThat(registry.toPlain('x', char.class, "f", Collections.emptyList())).isEqualTo("x");
        assertThat(read("x", char.class)).isEqualTo('x');
        assertThatThrownBy(() -> read("xx", Character.class)).isInstanceOf(ConversionException.class);
        assertThat(read(42, String.class)).isEqualTo("42");
        assertThat(read("false", boolean.class)).isEqualTo(false);
        assertThatThrownBy(() -> read("maybe", Boolean.class)).isInstanceOf(ConversionException.class);
        BigDecimal decimal = new BigDecimal("1.2300E+20");
        assertThat(registry.toPlain(decimal, BigDecimal.class, "f", Collections.emptyList())).isEqualTo(decimal.toString());
        assertThat(read(decimal.toString(), BigDecimal.class)).isEqualTo(decimal);
        UUID uuid = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
        assertThat(read(uuid.toString(), UUID.class)).isEqualTo(uuid);
        assertThatThrownBy(() -> read("not-a-uuid", UUID.class)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> read(null, int.class)).isInstanceOf(ConversionException.class);
        assertThat(read(null, Integer.class)).isNull();
    }

    @Test
    void listFailuresAreCollectedIndividuallyWithImmutablePathsAndRawSnapshots() throws Exception {
        Type type = Shapes.class.getDeclaredField("integers").getGenericType();
        Map<String, Object> bad = new java.util.LinkedHashMap<>();
        bad.put("o.O", new java.util.ArrayList<>(Collections.singletonList("bad")));
        ConversionResult<List<Integer>> result = registry.fromPlainResult(
                Arrays.asList(1, "bad", 2, bad, null), type, "config/list.yml", Collections.singletonList("o.O"));
        assertThat(result.value()).containsExactly(1, 2);
        assertThat(result.failures()).hasSize(3);
        assertThat(result.failures().get(0).path()).containsExactly("o.O", "1");
        assertThat(result.failures().get(0).raw()).isEqualTo("bad");
        assertThat(result.failures().get(1).path()).containsExactly("o.O", "3");
        bad.put("changed", true);
        assertThat(((Map<?, ?>) result.failures().get(1).raw()).containsKey("changed")).isFalse();
        assertThatThrownBy(() -> result.failures().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.failures().get(0).path().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapAndArrayFailuresSkipOnlyBadEntriesAndWrongRootShapesFail() throws Exception {
        Type mapType = Shapes.class.getDeclaredField("byMode").getGenericType();
        Map<String, Object> plain = new java.util.LinkedHashMap<>();
        plain.put("FIRST", 1); plain.put("INVALID", 2); plain.put("SECOND", "bad");
        ConversionResult<Map<Mode, Integer>> map = registry.fromPlainResult(plain, mapType, "f", Collections.singletonList("map"));
        assertThat(map.value()).containsOnlyKeys(Mode.FIRST);
        assertThat(map.failures()).hasSize(2);
        ConversionResult<int[]> array = registry.fromPlainResult(Arrays.asList(1, "bad", 3), int[].class, "f", Collections.emptyList());
        assertThat(array.value()).containsExactly(1, 3);
        assertThat(array.failures()).hasSize(1);
        assertThatThrownBy(() -> read("wrong", Shapes.class.getDeclaredField("integers").getGenericType()))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void nestedDiagnosticsShareCollectorAndNullConcurrentEntriesAreSkipped() throws Exception {
        Type nested = Shapes.class.getDeclaredField("nested").getGenericType();
        ConversionResult<Map<String, List<Integer>>> result = registry.fromPlainResult(
                Collections.singletonMap("o.O", Arrays.asList("bad", 2)), nested, "f", Collections.singletonList("root"));
        assertThat(result.value().get("o.O")).containsExactly(2);
        assertThat(result.failures().get(0).path()).containsExactly("root", "o.O", "0");
        Type concurrent = Shapes.class.getDeclaredField("concurrent").getGenericType();
        ConversionResult<ConcurrentMap<String, String>> nulls = registry.fromPlainResult(
                Collections.singletonMap("empty", null), concurrent, "f", Collections.emptyList());
        assertThat(nulls.value()).isEmpty();
        assertThat(nulls.failures()).hasSize(1);
    }

    @Test
    void objectSlotsStayPlainOnReadAndDispatchRuntimeConvertersOnWrite() throws Exception {
        Map<String, Object> serialized = new java.util.LinkedHashMap<>();
        serialized.put("==", "NotInstantiated"); serialized.put("value", 2);
        assertThat(read(serialized, Object.class)).isEqualTo(serialized).isNotSameAs(serialized);
        UUID uuid = UUID.randomUUID();
        assertThat(registry.toPlain(uuid, Object.class, "f", Collections.emptyList())).isEqualTo(uuid.toString());
        assertThatThrownBy(() -> registry.toPlain(new Object(), Object.class, "f", Collections.emptyList()))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void runtimeContainerWritesUseOperationAwareFactoriesAndRespectCustomConverters() throws Exception {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
        for (Object value : Arrays.asList(Collections.singletonMap("id", id),
                Collections.unmodifiableMap(Collections.singletonMap("id", id)),
                Collections.singletonList(id), Collections.unmodifiableList(Collections.singletonList(id)))) {
            Object plain = registry.toPlain(value, Object.class, "f", Collections.emptyList());
            assertThat(com.ultikits.ultitools.config.document.PlainData.isPlain(plain)).isTrue();
            assertThat(plain.toString()).contains(id.toString());
        }
        ConverterRegistry child = new ConverterRegistry(registry);
        child.register(Map.class, new ConfigConverter<Map<?, ?>>() {
            @Override public Object toPlain(Map<?, ?> value, ConversionContext ctx) { return "custom-map"; }
            @Override public Map<?, ?> fromPlain(Object plain, ConversionContext ctx) { return Collections.emptyMap(); }
        }, false);
        assertThat(child.toPlain(Collections.singletonMap("id", id), Object.class, "f", Collections.emptyList()))
                .isEqualTo("custom-map");
        assertThat(registry.resolve(Collections.singletonMap("id", id).getClass())).isNull();
    }

    @Test
    void concreteInheritedTypesAndModuleConvertersRemainTypedInsideContainers() throws Exception {
        ConversionResult<IntegerList> converted = registry.fromPlainResult(Arrays.asList("1", "bad", 3),
                IntegerList.class, "f", Collections.emptyList());
        assertThat(converted.value()).containsExactly(1, 3);
        assertThat(converted.failures()).hasSize(1);
        ConverterRegistry child = new ConverterRegistry(registry);
        child.register(UUID.class, new ConfigConverter<UUID>() {
            @Override public Object toPlain(UUID value, ConversionContext ctx) { return "custom-id"; }
            @Override public UUID fromPlain(Object plain, ConversionContext ctx) { return new UUID(0, 1); }
        }, false);
        Type ids = Shapes.class.getDeclaredField("ids").getGenericType();
        assertThat(child.toPlain(Collections.singletonList(new UUID(0, 1)), ids, "f", Collections.emptyList()))
                .isEqualTo(Collections.singletonList("custom-id"));
        assertThat(registry.resolve(UnsupportedList.class)).isNull();
    }

    @Test
    void integralNarrowingAndElementFailuresAreIndependentlyAsserted() throws Exception {
        assertThatThrownBy(() -> read(3000000000L, int.class)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> read(1.5D, int.class)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> read(0.123456789D, float.class)).isInstanceOf(ConversionException.class);
        Type type = Shapes.class.getDeclaredField("integers").getGenericType();
        ConversionResult<List<Integer>> converted = registry.fromPlainResult(Arrays.asList("bad", 2), type, "f", Collections.emptyList());
        assertThat(converted.value()).containsExactly(2);
        assertThat(converted.failures()).hasSize(1);
    }

    @Test
    void enumConstantBodiesUseNamesAndCustomHierarchyStillWins() throws Exception {
        assertThat(registry.toPlain(BodyEnum.FIRST, Object.class, "f", Collections.emptyList())).isEqualTo("FIRST");
        ConverterRegistry child = new ConverterRegistry(registry);
        child.register(BodyEnum.class, new ConfigConverter<BodyEnum>() {
            @Override public Object toPlain(BodyEnum value, ConversionContext ctx) { return "custom-enum"; }
            @Override public BodyEnum fromPlain(Object plain, ConversionContext ctx) { return BodyEnum.FIRST; }
        }, false);
        assertThat(child.toPlain(BodyEnum.FIRST, Object.class, "f", Collections.emptyList())).isEqualTo("custom-enum");
    }

    @Test
    void mapKeyFailuresKeepRawKeyAndConvertedCollisionsKeepFirstValue() throws Exception {
        ConverterRegistry child = new ConverterRegistry(registry);
        child.register(Mode.class, new ConfigConverter<Mode>() {
            @Override public Object toPlain(Mode value, ConversionContext ctx) { return value.name(); }
            @Override public Mode fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
                if ("bad".equals(plain)) { throw new ConversionException("bad key", ctx.file(), ctx.path(), ctx.declaredType()); }
                return Mode.FIRST;
            }
        }, false);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("first", 1); input.put("second", 2); input.put("bad", 3);
        ConversionResult<Map<Mode, Integer>> result = child.fromPlainResult(input,
                Shapes.class.getDeclaredField("byMode").getGenericType(), "f", Collections.singletonList("root"));
        assertThat(result.value()).containsEntry(Mode.FIRST, 1).hasSize(1);
        assertThat(result.failures()).hasSize(2);
        assertThat(result.failures().get(1).raw()).isEqualTo("bad");
        assertThat(result.failures().get(1).declaredType()).isEqualTo(Mode.class);
        assertThat(result.failures().get(1).path()).containsExactly("root", "bad");
    }

    @Test
    void mapKeyRefusalRecordsKeyRatherThanValue() throws Exception {
        Type type = Shapes.class.getDeclaredField("byMode").getGenericType();
        ConversionResult<Map<Mode, Integer>> result = registry.fromPlainResult(
                Collections.singletonMap("bad", 3), type, "f", Collections.singletonList("root"));
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).raw()).isEqualTo("bad");
        assertThat(result.failures().get(0).declaredType()).isEqualTo(Mode.class);
        assertThat(result.failures().get(0).path()).containsExactly("root", "bad");
    }

    @Test
    void mapWriteKeyRefusalIncludesWholeEntryPath() {
        assertThatThrownBy(() -> registry.toPlain(Collections.singletonMap("not-a-number", 1),
                Shapes.class.getDeclaredField("integerKeys").getGenericType(), "f", Collections.singletonList("root")))
                .isInstanceOfSatisfying(ConversionException.class,
                        failure -> assertThat(failure.path()).containsExactly("root", "not-a-number"));
    }

    @Test
    void ownerRawAndRecursiveDeclarationsResolveWithoutCustomTypeWrappers() throws Exception {
        Type owner = Shapes.class.getDeclaredField("owned").getGenericType();
        assertThat(com.ultikits.ultitools.config.convert.builtin.ConversionTypes.argument(
                owner, java.util.Collection.class, 0)).isEqualTo(UUID.class);
        assertThat(com.ultikits.ultitools.config.convert.builtin.ConversionTypes.raw(
                com.ultikits.ultitools.config.convert.builtin.ConversionTypes.argument(
                        List.class, java.util.Collection.class, 0))).isEqualTo(Object.class);
        Type recursive = com.ultikits.ultitools.config.convert.builtin.ConversionTypes.argument(
                RecursiveList.class, java.util.Collection.class, 0);
        assertThat(com.ultikits.ultitools.config.convert.builtin.ConversionTypes.raw(recursive)).isEqualTo(Comparable.class);
    }

    @Test
    void nestedGenericArrayAndWildcardArgumentsSubstituteConcreteAncestors() throws Exception {
        UUID uuid = new UUID(0, 1);
        Type nested = com.ultikits.ultitools.config.convert.builtin.ConversionTypes.argument(
                UuidNested.class, java.util.Collection.class, 0);
        Type component = ((java.lang.reflect.ParameterizedType) nested).getActualTypeArguments()[0];
        assertThat(com.ultikits.ultitools.config.convert.builtin.ConversionTypes.raw(component)).isEqualTo(UUID[].class);
        ConversionResult<UuidNested> result = registry.fromPlainResult(
                Collections.singletonList(Collections.singletonList(Collections.singletonList(uuid.toString()))),
                UuidNested.class, "f", Collections.emptyList());
        assertThat(result.failures()).isEmpty();
        assertThat(result.value().get(0).get(0)).containsExactly(uuid);
    }

    @Test
    void wildcardArgumentsSubstituteConcreteAncestors() {
        Type wildcard = com.ultikits.ultitools.config.convert.builtin.ConversionTypes.argument(
                UuidWildcard.class, java.util.Collection.class, 0);
        Type inner = ((java.lang.reflect.ParameterizedType) wildcard).getActualTypeArguments()[0];
        assertThat(com.ultikits.ultitools.config.convert.builtin.ConversionTypes.raw(inner)).isEqualTo(UUID.class);
    }

    public static class Owner<T> {
        public class Owned extends java.util.ArrayList<T> { private static final long serialVersionUID = 1L; }
    }
    public static class RecursiveList<T extends Comparable<T>> extends java.util.ArrayList<T> {
        private static final long serialVersionUID = 1L;
    }

    enum BodyEnum { FIRST { @Override public String toString() { return "not-name"; } }, SECOND }
    public static class NestedParent<T> extends java.util.ArrayList<List<T[]>> {
        private static final long serialVersionUID = 1L;
    }
    public static class UuidNested extends NestedParent<UUID> { private static final long serialVersionUID = 1L; }
    public static class WildcardParent<T> extends java.util.ArrayList<List<? extends T>> {
        private static final long serialVersionUID = 1L;
    }
    public static class UuidWildcard extends WildcardParent<UUID> { private static final long serialVersionUID = 1L; }

    public static class IntegerList extends java.util.ArrayList<Integer> {
        private static final long serialVersionUID = 1L;
    }
    interface UnsupportedList extends List<String> { }

    private Object read(Object plain, Type type) throws ConversionException {
        return registry.fromPlain(plain, type, "config/numeric.yml", Collections.singletonList("value"));
    }
    enum Mode { FIRST, SECOND }
    static class Shapes {
        Owner<UUID>.Owned owned;
        Map<Integer, Integer> integerKeys;
        List<Integer> integers;
        List<UUID> ids;
        Map<Mode, Integer> byMode;
        Map<String, List<Integer>> nested;
        ConcurrentMap<String, String> concurrent;
    }
}
