package com.ultikits.ultitools.config.document;

import static com.ultikits.ultitools.config.document.ConfigDocumentWriteTest.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Plan 17-56 Task 2: the document accepts only plain data (Follow-up 11: only strings, numbers, booleans,
 * lists and maps reach the file), so no Java-class tag can be written (#560's defect class).
 */
@DisplayName("PlainData - the plain-data boundary")
class PlainDataBoundaryTest {

    static Stream<Arguments> refusedValues() {
        Map<Object, Object> integerKeys = new LinkedHashMap<>();
        integerKeys.put(1, "a");
        return Stream.of(
                Arguments.of("a UUID", UUID.fromString("00000000-0000-0000-0000-000000000001"), "[s, v]", "java.util.UUID"),
                Arguments.of("an enum", TimeUnit.SECONDS, "[s, v]", "java.util.concurrent.TimeUnit"),
                Arguments.of("a Set", new HashSet<>(Collections.singleton("a")), "[s, v]", "java.util.LinkedHashSet"),
                Arguments.of("an ItemStack", Mockito.mock(ItemStack.class), "[s, v]", "org.bukkit.inventory.ItemStack"),
                Arguments.of("a Location", new Location(null, 1, 2, 3), "[s, v]", "org.bukkit.Location"),
                Arguments.of("a Float", 1.5f, "[s, v]", "java.lang.Float"),
                Arguments.of("a Short", (short) 1, "[s, v]", "java.lang.Short"),
                Arguments.of("a Character", 'c', "[s, v]", "java.lang.Character"),
                Arguments.of("a map with a non-String key", integerKeys, "[s, v]", "java.lang.Integer"),
                Arguments.of("a list holding a UUID", Arrays.asList("a", UUID.randomUUID()), "[s, v, [1]]", "java.util.UUID"),
                Arguments.of("a map holding an enum", Collections.singletonMap("k", TimeUnit.DAYS), "[s, v, k]",
                        "java.util.concurrent.TimeUnit"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedValues")
    @DisplayName("set refuses a non-plain value, naming the key path and the class, and changes nothing")
    void nonPlainValuesAreRefused(String description, Object value, String path, String className) throws Exception {
        ConfigDocument document = ConfigDocument.parse("s:\n  v: 1\n");

        assertThatThrownBy(() -> document.set(path("s", "v"), value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(path)
                .hasMessageContaining(className);
        assertThat(document.render()).isEqualTo("s:\n  v: 1\n");
        assertThat(document.get(path("s", "v"))).isEqualTo(1);
    }

    static Stream<Arguments> readCopyInventory() {
        return Stream.of(
                Arguments.of("map", "v: {a: [1]}\n"),
                Arguments.of("list", "v: [1, {a: 2}]\n"),
                Arguments.of("set", "v: !!set {a: null}\n"),
                Arguments.of("date", "v: 2020-01-01\n"),
                Arguments.of("binary", "v: !!binary YWI=\n"),
                Arguments.of("pairs-array", "v: !!pairs [{a: [1]}]\n"),
                Arguments.of("omap-date-key", "v: !!omap [{2020-01-01: [1]}]\n"),
                Arguments.of("nested-set-date", "v: [!!set {2020-01-01: null}]\n"),
                Arguments.of("nested-pairs-binary", "v: {a: !!pairs [{b: !!binary YWI=}]}\n"),
                Arguments.of("map-key-list", "v: !!omap [{? [a, b] : [1]}]\n"),
                Arguments.of("alias", "v: [&s !!set {a: null}, *s]\n"),
                Arguments.of("immutable-types", "v: [null, true, 1, 2147483648, 9223372036854775808, 1.5, text]\n"),
                Arguments.of("cycle-map", ""), Arguments.of("cycle-list", ""),
                Arguments.of("cycle-set", ""), Arguments.of("cycle-array", ""));
    }

    @ParameterizedTest(name = "read copy: {0}")
    @MethodSource("readCopyInventory")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void everyConstructedMutableTypeIsIsolated(String shape, String yaml) throws Exception {
        if (shape.startsWith("cycle-")) {
            Object cycle;
            if ("cycle-map".equals(shape)) {
                Map<String, Object> map = new LinkedHashMap<>(); map.put("self", map); cycle = map;
            } else if ("cycle-list".equals(shape)) {
                List<Object> list = new ArrayList<>(); list.add(list); cycle = list;
            } else if ("cycle-set".equals(shape)) {
                java.util.Set<Object> set = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
                set.add(set); cycle = set;
            } else {
                Object[] array = new Object[1]; array[0] = array; cycle = array;
            }
            assertThatThrownBy(() -> PlainData.copy(cycle)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains itself");
            return;
        }
        ConfigDocument document = ConfigDocument.parse(yaml);
        java.lang.reflect.Field field = ConfigDocument.class.getDeclaredField("plain");
        field.setAccessible(true);
        Map<?, ?> internal = (Map<?, ?>) field.get(document);
        java.util.Set<Object> mutable = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        collectMutable(internal, mutable);
        assertIsolated(document.get(path("v")), mutable);
        assertIsolated(document.toPlain(), mutable);
        if (!PlainData.isPlain(internal.get("v"))) {
            assertThatThrownBy(() -> document.set(path("rejected"), document.get(path("v"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(document.contains(path("rejected"))).isFalse();
        }
    }

    private static void collectMutable(Object value, java.util.Set<Object> result) {
        if (value instanceof Map || value instanceof List || value instanceof java.util.Set
                || value instanceof java.util.Date || value instanceof byte[] || value instanceof Object[]) {
            if (!result.add(value)) { return; }
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                collectMutable(entry.getKey(), result); collectMutable(entry.getValue(), result);
            }
        } else if (value instanceof Iterable) {
            for (Object element : (Iterable<?>) value) { collectMutable(element, result); }
        } else if (value instanceof Object[]) {
            for (Object element : (Object[]) value) { collectMutable(element, result); }
        }
    }

    private static void assertIsolated(Object copy, java.util.Set<Object> internal) {
        java.util.Set<Object> copied = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        collectMutable(copy, copied);
        for (Object mutable : copied) {
            assertThat(internal.contains(mutable)).as("no shared mutable %s", mutable.getClass().getName()).isFalse();
        }
    }

    @Test
    void mutableYamlLeavesAreDefensivelyCopied() throws Exception {
        ConfigDocument document = ConfigDocument.parse("date: 2020-01-01\nbinary: !!binary YWI=\n");
        java.util.Date original = (java.util.Date) document.get(path("date"));
        long timestamp = original.getTime();
        original.setTime(0);
        byte[] binary = (byte[]) document.get(path("binary"));
        binary[0] = 0;
        assertThat(((java.util.Date) document.get(path("date"))).getTime()).isEqualTo(timestamp);
        assertThat((byte[]) document.get(path("binary"))).containsExactly((byte) 'a', (byte) 'b');
    }

    @Test
    void copiedSnapshotIsCheckedRatherThanEarlierIteration() throws Exception {
        ConfigDocument document = ConfigDocument.parse("a: 1\n");
        List<Object> shifting = new java.util.AbstractList<Object>() {
            private int reads;
            @Override public int size() { return 1; }
            @Override public Object get(int index) { return ++reads < 3 ? "safe" : UUID.randomUUID(); }
        };
        try {
            document.set(path("a"), shifting);
            assertThat(ConfigDocument.parse(document.render()).toPlain()).isEqualTo(document.toPlain());
            assertThat(PlainData.isPlain(document.get(path("a")))).isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("java.util.UUID");
            assertThat(document.render()).isEqualTo("a: 1\n");
        }
    }

    @Test
    @DisplayName("every plain type is accepted and reads back equal, null included")
    void plainValuesAreAccepted() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("text", "a");
        nested.put("flag", true);
        nested.put("int", 1);
        nested.put("long", 10000000000L);
        nested.put("big", new BigInteger("123456789012345678901234567890"));
        nested.put("double", 0.25);
        nested.put("none", null);
        nested.put("list", Arrays.asList(1, "b", null));
        ConfigDocument document = ConfigDocument.empty();

        document.set(path("root"), nested);

        assertThat(ConfigDocument.parse(document.render()).get(path("root"))).isEqualTo(nested);
    }

    @Test
    @DisplayName("a list that contains itself is refused, not followed forever")
    void cyclesAreRefused() {
        List<Object> list = new ArrayList<>();
        list.add(list);

        assertThatThrownBy(() -> PlainData.requirePlain(path("a"), list))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a, [0]]")
                .hasMessageContaining("contains itself");
    }

    @Test
    @DisplayName("value equality: Integer 1 equals Long 1, 1.50 equals 1.5, maps ignore order, types otherwise differ")
    void plainEquality() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2L);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1L);

        assertThat(PlainData.plainEquals(1, 1L)).isTrue();
        assertThat(PlainData.plainEquals(1L, BigInteger.ONE)).isTrue();
        assertThat(PlainData.plainEquals(1.5, Double.valueOf("1.50"))).isTrue();
        assertThat(PlainData.plainEquals(ab, ba)).isTrue();
        assertThat(PlainData.plainEquals(Arrays.asList(1, 2), Arrays.asList(1L, 2L))).isTrue();
        assertThat(PlainData.plainEquals(1, 1.0)).isFalse();
        assertThat(PlainData.plainEquals("1", 1)).isFalse();
        assertThat(PlainData.plainEquals(true, "true")).isFalse();
        assertThat(PlainData.plainEquals(Arrays.asList(1, 2), Arrays.asList(2, 1))).isFalse();
        assertThat(PlainData.plainEquals(null, "x")).isFalse();
        assertThat(PlainData.plainEquals(0.0, -0.0)).isFalse();
    }

    @Test
    @DisplayName("the document keeps its own copy: changing the caller's map afterwards changes nothing")
    void documentCopiesValues() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", 1);
        ConfigDocument document = ConfigDocument.empty();

        document.set(path("m"), value);
        value.put("b", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>) document.get(path("m"));
        read.put("c", 3);

        assertThat(document.get(path("m"))).isEqualTo(Collections.singletonMap("a", 1));
        assertThat(document.render()).isEqualTo("m:\n  a: 1\n");
    }
}
