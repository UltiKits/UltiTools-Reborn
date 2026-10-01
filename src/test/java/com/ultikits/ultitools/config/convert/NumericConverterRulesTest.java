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
        assertThat((Map<?, ?>) result.failures().get(1).raw()).doesNotContainKey("changed");
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

    private Object read(Object plain, Type type) throws ConversionException {
        return registry.fromPlain(plain, type, "config/numeric.yml", Collections.singletonList("value"));
    }
    enum Mode { FIRST, SECOND }
    static class Shapes {
        List<Integer> integers;
        Map<Mode, Integer> byMode;
        Map<String, List<Integer>> nested;
        ConcurrentMap<String, String> concurrent;
    }
}
