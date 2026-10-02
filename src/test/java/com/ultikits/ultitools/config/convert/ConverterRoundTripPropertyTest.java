package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.config.document.PlainData;

class ConverterRoundTripPropertyTest {
    private static final long SEED = 0x1757C0DEL;
    private static final int VALUES = 200;
    private static final ConverterRegistry REGISTRY = ConverterRegistry.framework();
    private static final Map<Class<?>, Sample> GENERATORS = generators();

    @org.junit.jupiter.api.BeforeAll
    static void startBukkit() {
        org.mockbukkit.mockbukkit.MockBukkit.mock().addSimpleWorld("world");
    }

    @org.junit.jupiter.api.AfterAll
    static void stopBukkit() { org.mockbukkit.mockbukkit.MockBukkit.unmock(); }

    @Test
    void everyFrameworkRegistrationAndFactoryHasAGenerator() {
        assertThat(GENERATORS.keySet()).containsAll(REGISTRY.registeredTypes());
    }

    @TestFactory
    Stream<DynamicTest> everyRegisteredTypeRoundTripsThroughRenderedDocument() {
        return REGISTRY.registeredTypes().stream().map(type -> DynamicTest.dynamicTest(type.getTypeName(), () -> {
            Sample sample = GENERATORS.get(type);
            assertThat(sample).as("Missing generator for %s", type).isNotNull();
            exercise(sample, type.getTypeName());
        }));
    }

    @TestFactory
    Stream<DynamicTest> additionalArrayAndNestedFactoryShapesRoundTrip() {
        return Stream.of("primitiveArray", "referenceArray", "genericArray", "nested", "listEnum",
                "hashMap", "hashSet", "linkedList", "secondEnum", "item", "delegateItem", "location", "material")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    Sample sample;
                    switch (name) {
                        case "primitiveArray": sample = new Sample(field(name), random -> new int[]{random.nextInt(), random.nextInt()}); break;
                        case "referenceArray": sample = new Sample(field(name), random -> new String[]{"a" + random.nextInt(), "o.O"}); break;
                        case "genericArray": sample = new Sample(field(name), random -> new List[]{Arrays.asList(random.nextInt(), random.nextInt())}); break;
                        case "nested": sample = new Sample(field(name), random -> Collections.singletonMap("o.O",
                                Collections.singletonMap("inner", Arrays.asList(random.nextInt(), random.nextInt())))); break;
                        case "hashMap": sample = new Sample(field(name), random -> new java.util.HashMap<>(Collections.singletonMap("o.O", random.nextInt()))); break;
                        case "hashSet": sample = new Sample(field(name), random -> new java.util.HashSet<>(Collections.singletonList("x" + random.nextInt()))); break;
                        case "linkedList": sample = new Sample(field(name), random -> new java.util.LinkedList<>(Arrays.asList("x" + random.nextInt(), "y"))); break;
                        case "item": sample = new Sample(org.bukkit.inventory.ItemStack.class, ConverterRoundTripPropertyTest::randomItem); break;
                        case "delegateItem": sample = new Sample(org.bukkit.inventory.ItemStack.class,
                                random -> new DelegatingItemStackDouble(randomItem(random))); break;
                        case "location": sample = new Sample(org.bukkit.Location.class, random -> new org.bukkit.Location(
                                org.bukkit.Bukkit.getWorld("world"), random.nextDouble(), random.nextDouble(), random.nextDouble(),
                                random.nextFloat() * 360, random.nextFloat() * 90)); break;
                        case "material": sample = new Sample(org.bukkit.Material.class, random -> random.nextBoolean()
                                ? org.bukkit.Material.STONE : org.bukkit.Material.DIAMOND); break;
                        case "secondEnum": sample = new Sample(SecondMode.class, random -> SecondMode.values()[random.nextInt(2)]); break;
                        default: sample = new Sample(field(name), random -> Arrays.asList(Mode.FIRST, Mode.SECOND));
                    }
                    exercise(sample, name);
                }));
    }

    @TestFactory
    Stream<DynamicTest> inheritedConcreteContainersAndLegacyIsolationGeneratedCases() {
        return Stream.of("concreteList", "concreteMap", "legacyList", "legacyNested").map(name -> DynamicTest.dynamicTest(name, () -> {
            Random random = new Random(SEED);
            if (name.equals("concreteList")) {
                exercise(new Sample(ConverterLoadTimeCheckTest.StringList.class, source -> {
                    ConverterLoadTimeCheckTest.StringList values = new ConverterLoadTimeCheckTest.StringList();
                    values.add("value" + source.nextInt()); return values;
                }), name);
            } else if (name.equals("concreteMap")) {
                exercise(new Sample(ConverterLoadTimeCheckTest.UuidMap.class, source -> {
                    ConverterLoadTimeCheckTest.UuidMap values = new ConverterLoadTimeCheckTest.UuidMap();
                    values.put("o.O", new UUID(source.nextLong(), source.nextLong())); return values;
                }), name);
            } else {
                com.ultikits.ultitools.annotations.ConfigEntry entry = LegacyParserAdapterTest.Shapes.class
                        .getDeclaredField("mutating").getAnnotation(com.ultikits.ultitools.annotations.ConfigEntry.class);
                for (int iteration = 0; iteration < VALUES; iteration++) {
                    List<Object> values = new ArrayList<>();
                    values.add(new LinkedHashMap<>(Collections.singletonMap("key", "value" + random.nextInt())));
                    Object input = name.equals("legacyList") ? values : Collections.singletonMap("items", values);
                    Object snapshot = PlainData.copy(input);
                    REGISTRY.fromPlainResult(input, Object.class, "config/property.yml", Collections.singletonList("o.O"), entry);
                    assertThat(input).as("seed=%s shape=%s iteration=%s", SEED, name, iteration).isEqualTo(snapshot);
                }
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> acceptedNoncanonicalInputsNormalizeStablyPerRegisteredType() {
        return REGISTRY.registeredTypes().stream().map(type -> DynamicTest.dynamicTest(
                "noncanonical " + type.getTypeName(), () -> {
            Sample sample = GENERATORS.get(type);
            Random random = new Random(SEED);
            for (int iteration = 0; iteration < VALUES; iteration++) {
                Object canonical = REGISTRY.toPlain(sample.generate.apply(random), sample.type,
                        "config/property.yml", Collections.singletonList("o.O"));
                Object input = noncanonical(type, canonical, random);
                if (input == null) {
                    // No alternative acceptance shape is asserted for this generator; canonical coverage remains mandatory.
                    assertThat(PlainData.isPlain(canonical)).isTrue();
                    continue;
                }
                assertThat(PlainData.plainEquals(input, canonical)).as("distinct input for %s", type).isFalse();
                ConversionResult<Object> accepted = REGISTRY.fromPlainResult(input, sample.type,
                        "config/property.yml", Collections.singletonList("o.O"));
                assertThat(accepted.failures()).as("accepted, not a skipped invalid element").isEmpty();
                Object normalized = REGISTRY.toPlain(accepted.value(), sample.type,
                        "config/property.yml", Collections.singletonList("o.O"));
                ConversionResult<Object> rebound = REGISTRY.fromPlainResult(normalized, sample.type,
                        "config/property.yml", Collections.singletonList("o.O"));
                assertThat(rebound.failures()).isEmpty();
                assertThat(semantic(rebound.value())).isEqualTo(semantic(accepted.value()));
                assertThat(PlainData.plainEquals(REGISTRY.toPlain(rebound.value(), sample.type,
                        "config/property.yml", Collections.singletonList("o.O")), normalized)).isTrue();
            }
        }));
    }

    @Test
    void strictShapesHavePositiveAndNegativeAcceptanceControls() throws Exception {
        assertThat(REGISTRY.fromPlainResult("a", Character.class, "f", Collections.emptyList()).value()).isEqualTo('a');
        assertThat(REGISTRY.fromPlainResult("FIRST", Mode.class, "f", Collections.emptyList()).value()).isEqualTo(Mode.FIRST);
        assertThat(REGISTRY.fromPlainResult("world", org.bukkit.World.class, "f", Collections.emptyList()).value())
                .isEqualTo(org.bukkit.Bukkit.getWorld("world"));
        for (Class<?> type : Arrays.asList(Character.class, Mode.class, org.bukkit.World.class)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> REGISTRY.fromPlainResult(
                    "not-a-valid-name", type, "f", Collections.emptyList())).isInstanceOf(ConversionException.class);
        }
        Map<String, Object> plain = Collections.singletonMap("o.O", Arrays.asList(42, "false"));
        assertThat(REGISTRY.fromPlainResult(plain, Object.class, "f", Collections.emptyList()).value()).isEqualTo(plain);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> REGISTRY.fromPlainResult(
                null, int.class, "f", Collections.emptyList())).isInstanceOf(ConversionException.class);
        assertThat(REGISTRY.fromPlainResult(null, Integer.class, "f", Collections.emptyList()).value()).isNull();
    }

    private static Object noncanonical(Class<?> type, Object canonical, Random random) {
        if (type == String.class) { return random.nextInt(); }
        if (type == boolean.class || type == Boolean.class) { return canonical.toString(); }
        if (type == UUID.class) { return canonical.toString().toUpperCase(java.util.Locale.ROOT); }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != char.class) {
            return type == BigDecimal.class ? random.nextInt() : canonical.toString();
        }
        if (canonical instanceof List<?>) {
            List<Object> values = new ArrayList<>((List<?>) canonical);
            if (Set.class.isAssignableFrom(type)) { values.add(values.get(0)); return values; }
            if (type != List.class) { values.set(0, random.nextInt()); return values; }
        }
        if (canonical instanceof Map<?, ?> && Map.class.isAssignableFrom(type)) {
            Map<String, Object> values = new LinkedHashMap<>();
            ((Map<?, ?>) canonical).forEach((key, value) -> values.put(String.valueOf(key),
                    value instanceof Number ? value.toString() : value.toString().toUpperCase(java.util.Locale.ROOT)));
            return values;
        }
        if (type == org.bukkit.configuration.serialization.ConfigurationSerializable.class) {
            Map<String, Object> values = new LinkedHashMap<>();
            ((Map<?, ?>) canonical).forEach((key, value) -> values.put(String.valueOf(key), value));
            values.put("operator-extra", random.nextInt()); return values;
        }
        return null;
    }

    private static void exercise(Sample sample, String label) throws Exception {
        Random random = new Random(SEED);
        for (int iteration = 0; iteration < VALUES; iteration++) {
            Object input = sample.generate.apply(random);
            try {
                Object plain = REGISTRY.toPlain(input, sample.type, "config/property.yml", Collections.singletonList("o.O"));
                assertThat(PlainData.isPlain(plain)).isTrue();
                ConfigDocument document = ConfigDocument.empty();
                document.set(Collections.singletonList("o.O"), plain);
                String rendered = document.render();
                assertThat(rendered).doesNotContain("!!");
                Object reparsed = ConfigDocument.parse(rendered).get(Collections.singletonList("o.O"));
                ConversionResult<Object> result = REGISTRY.fromPlainResult(reparsed, sample.type,
                        "config/property.yml", Collections.singletonList("o.O"));
                assertThat(result.failures()).isEmpty();
                assertThat(semantic(result.value())).isEqualTo(semantic(input));
                Object canonical = REGISTRY.toPlain(result.value(), sample.type,
                        "config/property.yml", Collections.singletonList("o.O"));
                assertThat(PlainData.plainEquals(canonical, plain)).as("canonical reverse equality").isTrue();
                Object rebound = REGISTRY.fromPlainResult(canonical, sample.type,
                        "config/property.yml", Collections.singletonList("o.O")).value();
                assertThat(semantic(rebound)).as("normalization stability").isEqualTo(semantic(result.value()));
                Class<?> raw = sample.type instanceof Class<?> ? (Class<?>) sample.type
                        : sample.type instanceof java.lang.reflect.ParameterizedType
                        ? (Class<?>) ((java.lang.reflect.ParameterizedType) sample.type).getRawType() : Object[].class;
                if (!raw.isPrimitive()) { assertThat(result.value()).isInstanceOf(raw); }
            } catch (Exception | AssertionError failure) {
                throw new AssertionError("seed=" + SEED + ", type=" + label + ", iteration=" + iteration
                        + ", input=" + semantic(input), failure);
            }
        }
    }

    private static Object semantic(Object value) {
        if (value == null) { return null; }
        if (value instanceof org.bukkit.configuration.ConfigurationSection) {
            return semantic(((org.bukkit.configuration.ConfigurationSection) value).getValues(false));
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) { result.add(semantic(Array.get(value, i))); }
            return result;
        }
        if (value instanceof Set<?> && !(value instanceof LinkedHashSet<?>)
                && !(value instanceof SortedSet<?>) && !(value instanceof EnumSet<?>)) {
            Set<Object> result = new java.util.HashSet<>();
            for (Object item : (Set<?>) value) { result.add(semantic(item)); }
            return result;
        }
        if (value instanceof Collection<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Collection<?>) value) { result.add(semantic(item)); }
            return result;
        }
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, nested) -> result.put(key, semantic(nested)));
            return result;
        }
        return value;
    }

    private static Map<Class<?>, Sample> generators() {
        Map<Class<?>, Sample> result = new LinkedHashMap<>();
        scalar(result, String.class, random -> "text o.O " + random.nextLong());
        scalarPair(result, boolean.class, Boolean.class, Random::nextBoolean);
        scalarPair(result, byte.class, Byte.class, random -> (byte) random.nextInt());
        scalarPair(result, short.class, Short.class, random -> (short) random.nextInt());
        scalarPair(result, int.class, Integer.class, Random::nextInt);
        scalarPair(result, long.class, Long.class, Random::nextLong);
        scalarPair(result, float.class, Float.class, random -> Float.intBitsToFloat(random.nextInt()));
        scalarPair(result, double.class, Double.class, random -> Double.longBitsToDouble(random.nextLong()));
        scalarPair(result, char.class, Character.class, random -> (char) ('a' + random.nextInt(26)));
        scalar(result, BigInteger.class, random -> new BigInteger(160, random).subtract(BigInteger.ONE.shiftLeft(159)));
        scalar(result, BigDecimal.class, random -> new BigDecimal(new BigInteger(100, random), random.nextInt(20) - 10));
        scalar(result, UUID.class, random -> new UUID(random.nextLong(), random.nextLong()));
        result.put(Object.class, new Sample(Object.class, random -> Collections.singletonMap("==", "plain" + random.nextInt())));
        result.put(Enum.class, new Sample(Mode.class, random -> Mode.values()[random.nextInt(2)]));
        shape(result, Collection.class, "collection", random -> Arrays.asList("a" + random.nextInt(), "b"));
        shape(result, List.class, "listEnum", random -> Arrays.asList(Mode.FIRST, Mode.values()[random.nextInt(2)]));
        shape(result, ArrayList.class, "arrayList", random -> new ArrayList<>(Arrays.asList("a" + random.nextInt(), "b")));
        shape(result, Set.class, "set", random -> new LinkedHashSet<>(Arrays.asList("a" + random.nextInt(), "b")));
        shape(result, LinkedHashSet.class, "linkedSet", random -> new LinkedHashSet<>(Arrays.asList("a" + random.nextInt(), "b")));
        shape(result, SortedSet.class, "sortedSet", random -> new TreeSet<>(Arrays.asList("z" + random.nextInt(), "a")));
        shape(result, TreeSet.class, "treeSet", random -> new TreeSet<>(Arrays.asList("z" + random.nextInt(), "a")));
        shape(result, EnumSet.class, "enumSet", random -> random.nextBoolean() ? EnumSet.allOf(Mode.class) : EnumSet.of(Mode.FIRST));
        shape(result, Queue.class, "queue", random -> new ArrayDeque<>(Arrays.asList("a" + random.nextInt(), "b")));
        shape(result, Deque.class, "deque", random -> new ArrayDeque<>(Arrays.asList("a" + random.nextInt(), "b")));
        shape(result, ArrayDeque.class, "arrayDeque", random -> new ArrayDeque<>(Arrays.asList("a" + random.nextInt(), "b")));
        shape(result, Map.class, "uuidMap", random -> Collections.singletonMap("o.O", new UUID(random.nextLong(), random.nextLong())));
        shape(result, LinkedHashMap.class, "linkedMap", random -> new LinkedHashMap<>(Collections.singletonMap(Mode.SECOND, random.nextInt())));
        shape(result, ConcurrentMap.class, "concurrent", random -> new ConcurrentHashMap<>(Collections.singletonMap("key", random.nextInt())));
        shape(result, ConcurrentHashMap.class, "concreteConcurrent", random -> new ConcurrentHashMap<>(Collections.singletonMap("key", random.nextInt())));
        shape(result, EnumMap.class, "enumMap", random -> {
            EnumMap<Mode, Integer> map = new EnumMap<>(Mode.class); map.put(Mode.SECOND, random.nextInt()); return map;
        });
        shape(result, SortedMap.class, "sortedMap", random -> new TreeMap<>(Collections.singletonMap("key", random.nextInt())));
        shape(result, TreeMap.class, "treeMap", random -> new TreeMap<>(Collections.singletonMap("key", random.nextInt())));
        result.put(Object[].class, new Sample(field("referenceArray"), random -> new String[]{"a" + random.nextInt(), "b"}));
        result.put(org.bukkit.World.class, new Sample(org.bukkit.World.class, random -> org.bukkit.Bukkit.getWorld("world")));
        result.put(org.bukkit.configuration.serialization.ConfigurationSerializable.class,
                new Sample(org.bukkit.inventory.ItemStack.class, ConverterRoundTripPropertyTest::randomItem));
        result.put(org.bukkit.configuration.ConfigurationSection.class, new Sample(Object.class, random -> {
            org.bukkit.configuration.MemoryConfiguration section = new org.bukkit.configuration.MemoryConfiguration();
            section.set("number", random.nextInt());
            return section;
        }));
        return result;
    }

    private static org.bukkit.inventory.ItemStack randomItem(Random random) {
        org.bukkit.inventory.ItemStack item = BukkitConverterTest.item();
        item.setAmount(1 + random.nextInt(32));
        return item;
    }

    private static void scalar(Map<Class<?>, Sample> samples, Class<?> type, Function<Random, Object> generator) {
        samples.put(type, new Sample(type, generator));
    }
    private static void scalarPair(Map<Class<?>, Sample> samples, Class<?> primitive, Class<?> boxed,
                                   Function<Random, Object> generator) {
        scalar(samples, primitive, generator); scalar(samples, boxed, generator);
    }
    private static void shape(Map<Class<?>, Sample> samples, Class<?> factory, String name, Function<Random, Object> generator) {
        samples.put(factory, new Sample(field(name), generator));
    }
    private static Type field(String name) {
        try { Field field = Shapes.class.getDeclaredField(name); return field.getGenericType(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class Sample {
        private final Type type;
        private final Function<Random, Object> generate;
        private Sample(Type type, Function<Random, Object> generate) { this.type = type; this.generate = generate; }
    }
    enum Mode { FIRST, SECOND }
    enum SecondMode { LEFT, RIGHT }
    @SuppressWarnings("unused")
    static class Shapes {
        Collection<String> collection;
        List<Mode> listEnum;
        ArrayList<String> arrayList;
        Set<String> set;
        LinkedHashSet<String> linkedSet;
        SortedSet<String> sortedSet;
        TreeSet<String> treeSet;
        EnumSet<Mode> enumSet;
        Queue<String> queue;
        Deque<String> deque;
        ArrayDeque<String> arrayDeque;
        Map<String, UUID> uuidMap;
        LinkedHashMap<Mode, Integer> linkedMap;
        ConcurrentMap<String, Integer> concurrent;
        ConcurrentHashMap<String, Integer> concreteConcurrent;
        EnumMap<Mode, Integer> enumMap;
        SortedMap<String, Integer> sortedMap;
        TreeMap<String, Integer> treeMap;
        java.util.HashMap<String, Integer> hashMap;
        java.util.HashSet<String> hashSet;
        java.util.LinkedList<String> linkedList;
        int[] primitiveArray;
        String[] referenceArray;
        List<Integer>[] genericArray;
        Map<String, Map<String, List<Integer>>> nested;
    }
}
