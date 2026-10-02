package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.annotations.ConfigEntry;

class LegacyParserAdapterTest {
    private final ConverterRegistry registry = ConverterRegistry.framework();

    @Test
    void extendingPlainAndStringMapParsersReceiveDetachedLegacySections() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("o.O", "split"); input.put("nested", Collections.singletonMap("a.b", "inner"));
        for (String field : Arrays.asList("extending", "plain")) {
            Object converted = registry.fromPlainResult(input, Object.class, "f", Collections.emptyList(), entry(field)).value();
            assertThat(converted).isInstanceOf(Map.class);
            Map<?, ?> output = (Map<?, ?>) converted;
            assertThat(output).isNotNull();
            assertThat(output.get("o")).isEqualTo(Collections.singletonMap("O", "split"));
            assertThat(output.get("nested")).isEqualTo(Collections.singletonMap("a", Collections.singletonMap("b", "inner")));
        }
        Object mapped = registry.fromPlainResult(Collections.singletonMap("key", "value"), HashMap.class,
                "f", Collections.emptyList(), entry("stringMap")).value();
        assertThat(mapped).isEqualTo(Collections.singletonMap("key", "value"));
        assertThat(input).containsKey("o.O");
    }

    @Test
    void finalSerializeKeepsCustomSetShapeAndNormalizesUuidOutputLeaf() throws Exception {
        Object result = registry.toPlain(new LinkedHashSet<>(Arrays.asList("red", "blue")), Set.class,
                "f", Collections.emptyList(), entry("joined"));
        assertThat(result).isEqualTo(Collections.singletonMap("joined", "red,blue"));
        Object uuid = registry.toPlain(new Object(), Object.class, "f", Collections.singletonList("id"), entry("uuid"));
        assertThat(uuid).isEqualTo(Collections.singletonMap("id", new UUID(0, 1).toString()));
    }

    @Test
    void listSectionsAreExplicitlyNormalizedByAdapterAndObjectWrite() throws Exception {
        MemoryConfiguration section = new MemoryConfiguration(); section.set("a", 1);
        Object value = Collections.singletonList(section);
        assertThat(registry.toPlain(value, Object.class, "f", Collections.emptyList(), entry("extending")))
                .isEqualTo(Collections.singletonList(Collections.singletonMap("a", 1)));
        assertThat(registry.toPlain(value, Object.class, "f", Collections.emptyList()))
                .isEqualTo(Collections.singletonList(Collections.singletonMap("a", 1)));
    }

    @Test
    void unknownOutputAndConstructorFailureAreCheckedAndLocated() {
        assertThatThrownBy(() -> registry.toPlain(new Object(), Object.class, "file.yml", Collections.singletonList("key"), entry("unknown")))
                .isInstanceOf(ConversionException.class).hasMessageContaining("file.yml").hasMessageContaining("key").hasMessageContaining("UnsupportedLeaf");
        assertThatThrownBy(() -> registry.toPlain("value", String.class, "file.yml", Collections.singletonList("key"), entry("constructor")))
                .isInstanceOf(ConversionException.class).hasMessageContaining("file.yml").hasMessageContaining("key");
    }

    @Test
    void eachParallelConversionGetsFreshParserAndLegacySelectionPrecedesRegistry() throws Exception {
        ConverterRegistry custom = new ConverterRegistry(registry);
        custom.register(String.class, new ConfigConverter<String>() {
            @Override public Object toPlain(String value, ConversionContext ctx) { return "registry"; }
            @Override public String fromPlain(Object plain, ConversionContext ctx) { return "registry"; }
        }, true);
        CompletableFuture<?>[] calls = new CompletableFuture<?>[20];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = CompletableFuture.runAsync(() -> {
                try {
                    Object read = custom.fromPlainResult("value", String.class, "f", Collections.emptyList(), entry("stateful")).value();
                    assertThat(read).isEqualTo("first");
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
        CompletableFuture.allOf(calls).join();
    }

    @Test
    void rootListInputRemainsIntactAfterMutatingParserAndRetry() throws Exception {
        List<Object> input = new ArrayList<>(Arrays.asList("first", "second"));
        for (int attempt = 0; attempt < 3; attempt++) {
            Object size = registry.fromPlainResult(input, Object.class, "f", Collections.emptyList(), entry("mutating")).value();
            assertThat(size).isEqualTo(2);
            assertThat(input).containsExactly("first", "second");
        }
    }

    @Test
    void rootMapNestedListsAndMapsRemainDetached() throws Exception {
        List<Object> list = new ArrayList<>();
        Map<String, Object> nested = new LinkedHashMap<>(); nested.put("kept", "value");
        list.add(nested);
        Map<String, Object> input = new LinkedHashMap<>(); input.put("items", list);
        registry.fromPlainResult(input, Object.class, "f", Collections.emptyList(), entry("mutating"));
        assertThat(input).containsKey("items");
        assertThat(list).containsExactly(nested);
        assertThat(nested).containsEntry("kept", "value");
    }

    @Test
    void sharedInputIsStableAcrossParallelMutatingParsers() throws Exception {
        List<Object> input = new ArrayList<>(Arrays.asList("first", "second"));
        ConfigEntry annotation = entry("mutating");
        CompletableFuture<?>[] calls = new CompletableFuture<?>[20];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = CompletableFuture.runAsync(() -> {
                try {
                    Object size = registry.fromPlainResult(input, Object.class, "f", Collections.emptyList(), annotation).value();
                    assertThat(size).isEqualTo(2);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
        CompletableFuture.allOf(calls).join();
        assertThat(input).containsExactly("first", "second");
    }

    @Test
    void explicitLegacyResultsRetainEveryBoxedWideningPairWithoutNarrowing() throws Exception {
        Class<?>[] types = {Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class};
        Number[] numbers = {Byte.valueOf((byte) 7), Short.valueOf((short) 7), Integer.valueOf(7),
                Long.valueOf(7), Float.valueOf(7), Double.valueOf(7)};
        for (int from = 0; from < types.length; from++) {
            for (int to = from; to < types.length; to++) {
                Object value = registry.fromPlainResult(numbers[from], types[to], "legacy.yml",
                        Collections.singletonList("value"), entry("plain")).value();
                assertThat(value).as("%s to %s", types[from], types[to]).isInstanceOf(types[to]);
                assertThat(((Number) value).doubleValue()).isEqualTo(7.0);
            }
            for (int to = 0; to < from; to++) {
                final Number input = numbers[from]; final Class<?> target = types[to];
                assertThatThrownBy(() -> registry.fromPlainResult(input, target, "legacy.yml",
                        Collections.singletonList("value"), entry("plain")))
                        .isInstanceOf(ConversionException.class).hasMessageContaining("legacy.yml");
            }
        }
    }

    private static ConfigEntry entry(String name) throws NoSuchFieldException {
        return Shapes.class.getDeclaredField(name).getAnnotation(ConfigEntry.class);
    }
    // Deliberately exercises the deprecated legacy parser compatibility contract.
    @SuppressWarnings("removal")
    public static class Extending extends com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser { }
    // Deliberately exercises the deprecated legacy parser compatibility contract.
    @SuppressWarnings("removal")
    public static class Plain extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<Object> {
        @Override public Object parse(Object object) { return new com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser().parse(object); }
        @Override public MemorySection serializeToMemorySection(Object value) { return new com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser().serializeToMemorySection(value); }
    }
    // Deliberately exercises the deprecated legacy parser compatibility contract.
    @SuppressWarnings("removal")
    public static class Joined extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<Set<String>> {
        @Override public Set<String> parse(Object object) {
            return new LinkedHashSet<>(Arrays.asList(((ConfigurationSection) object).getString("joined").split(",")));
        }
        @Override public MemorySection serializeToMemorySection(Set<String> value) {
            MemoryConfiguration section = new MemoryConfiguration(); section.set("joined", String.join(",", value)); return section;
        }
    }
    public static class UuidOutput extends Plain {
        @Override public MemorySection serializeToMemorySection(Object value) {
            MemoryConfiguration section = new MemoryConfiguration(); section.set("id", new UUID(0, 1)); return section;
        }
    }
    public static class UnsupportedLeaf { }
    public static class UnknownOutput extends Plain {
        @Override public MemorySection serializeToMemorySection(Object value) {
            MemoryConfiguration section = new MemoryConfiguration(); section.set("bad", new UnsupportedLeaf()); return section;
        }
    }
    public static class BadConstructor extends Plain {
        public BadConstructor() { throw new IllegalArgumentException("constructor failed"); }
    }
    public static class Mutating extends Plain {
        @Override public Object parse(Object value) {
            if (value instanceof ConfigurationSection) {
                ConfigurationSection section = (ConfigurationSection) value;
                List<?> items = section.getList("items");
                if (items != null) { mutate(items); }
                section.set("items", null);
                return 1;
            }
            List<?> list = (List<?>) value;
            int size = list.size(); mutate(list); return size;
        }
        private static void mutate(List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?>) { ((Map<?, ?>) element).clear(); }
                if (element instanceof List<?>) { mutate((List<?>) element); }
            }
            list.clear();
        }
    }

    public static class Stateful extends Plain {
        private int calls;
        @Override public Object parse(Object value) { return ++calls == 1 ? "first" : "shared"; }
    }
    static class Shapes {
        // Deliberately selects a mutating legacy parser to verify input isolation.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = Mutating.class) Object mutating;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = Extending.class) Object extending;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = Plain.class) Object plain;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser.class) Object stringMap;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = Joined.class) Object joined;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = UuidOutput.class) Object uuid;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = UnknownOutput.class) Object unknown;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = BadConstructor.class) Object constructor;
        // Deliberately selects a legacy parser to verify compatibility dispatch.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = Stateful.class) Object stateful;
    }
}
