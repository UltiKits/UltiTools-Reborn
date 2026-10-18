package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Case 52 (issue #673, "Typed scalar map keys bind back to the original document keys"), converter level: given the
 * plain value the file holds at the converted path, a typed map key is written under the file's own key text that reads
 * back to it - a quoted {@code '01'}, a whole dotted {@code 1.0}, {@code 1.0E10}, an upper-case UUID - the first such
 * spelling in file order when several do, and under its verified canonical text when none does. The file value follows
 * map entries by key text and list elements by index. A file-key ambiguity scan reports, from the file's keys alone, every
 * typed key that two or more file keys read back to. Reading a file key to index it never adds a conversion failure.
 * All content is synthetic.
 */
@DisplayName("Typed map keys bind to the file's key spelling (case 52)")
class TypedMapKeySpellingTest {

    private static final String FILE = "config/spelling.yml";
    private static final List<String> ROOT = Collections.singletonList("limits");
    private static final UUID PLAYER = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
    private static final String PLAYER_UPPER = "12345678-1234-5678-9ABC-123456789ABC";

    private final ConverterRegistry registry = ConverterRegistry.framework();

    @Test
    @DisplayName("Map<Integer,String> {1: a} is written under the file's quoted '01'; without a file value under 1")
    void quotedSpellingIsKept() throws Exception {
        Map<Integer, String> value = Collections.singletonMap(1, "a");

        ConversionResult<Object> bound = write(value, "integers", plain("01", "a"));

        assertThat(bound.value()).isEqualTo(plain("01", "a"));
        assertThat(bound.failures()).isEmpty();
        assertThat(write(value, "integers", null).value()).isEqualTo(plain("1", "a"));
        assertThat(registry.toPlainResult(value, type("integers"), FILE, ROOT, null).value()).isEqualTo(plain("1", "a"));
    }

    @Test
    @DisplayName("whole dotted file keys 1.0 (Integer) and 1.0E10 (Long) are written as those exact keys")
    void wholeDottedSpellingIsKept() throws Exception {
        assertThat(write(Collections.singletonMap(1, "a"), "integers", plain("1.0", "a")).value())
                .isEqualTo(plain("1.0", "a"));
        assertThat(write(Collections.singletonMap(10_000_000_000L, "b"), "longs", plain("1.0E10", "b")).value())
                .isEqualTo(plain("1.0E10", "b"));
    }

    @Test
    @DisplayName("a Boolean key follows the file's identity text true, whatever the operator typed for it")
    void booleanSpellingFollowsTheIdentity() throws Exception {
        assertThat(write(Collections.singletonMap(true, "x"), "booleans", plain("true", "x")).value())
                .isEqualTo(plain("true", "x"));
        assertThat(write(Collections.singletonMap(true, "x"), "booleans", null).value()).isEqualTo(plain("true", "x"));
    }

    @Test
    @DisplayName("a Java key the file value does not hold is written as its verified canonical text")
    void newKeyUsesVerifiedCanonicalText() throws Exception {
        Map<Integer, String> value = new LinkedHashMap<>();
        value.put(1, "a");
        value.put(7, "b");

        ConversionResult<Object> bound = write(value, "integers", plain("01", "a"));

        assertThat(bound.value()).isEqualTo(plain("01", "a", "7", "b"));
        assertThat(bound.failures()).isEmpty();
    }

    @Test
    @DisplayName("when several file keys read back to one key, the first in file order is used")
    void firstFileSpellingWinsWhenSeveralReadBack() throws Exception {
        assertThat(write(Collections.singletonMap(1, "a"), "integers", plain("01", "a", "1", "b", "001", "c")).value())
                .isEqualTo(plain("01", "a"));
    }

    @Test
    @DisplayName("nested maps follow the outer key, then bind the inner key; list elements follow by index")
    void nestedMapsFollowTheFileValue() throws Exception {
        Map<String, Map<Integer, String>> nested = Collections.singletonMap("outer", Collections.singletonMap(1, "a"));
        assertThat(write(nested, "nested", plain("outer", plain("01", "a"))).value())
                .isEqualTo(plain("outer", plain("01", "a")));

        Map<Integer, Map<Integer, String>> both = Collections.singletonMap(2, Collections.singletonMap(1, "a"));
        assertThat(write(both, "typedOuter", plain("+2", plain("01", "a"))).value())
                .isEqualTo(plain("+2", plain("01", "a")));

        List<Map<Integer, String>> listed = Arrays.asList(Collections.singletonMap(1, "a"), Collections.singletonMap(2, "b"));
        assertThat(write(listed, "listed", Arrays.asList(plain("01", "a"), plain("002", "b"))).value())
                .isEqualTo(Arrays.asList(plain("01", "a"), plain("002", "b")));
    }

    @Test
    @DisplayName("file keys that do not read as the key type are skipped and add no failure")
    void probeReadsAddNothing() throws Exception {
        ConversionResult<Object> bound = write(Collections.singletonMap(1, "a"), "integers",
                plain("abc", "x", "1.5", "y", "01", "a"));

        assertThat(bound.value()).isEqualTo(plain("01", "a"));
        assertThat(bound.failures()).isEmpty();
    }

    @Test
    @DisplayName("a UUID key keeps the file's upper-case spelling")
    void uuidSpellingIsKept() throws Exception {
        assertThat(write(Collections.singletonMap(PLAYER, "steve"), "uuids", plain(PLAYER_UPPER, "steve")).value())
                .isEqualTo(plain(PLAYER_UPPER, "steve"));
        assertThat(write(Collections.singletonMap(PLAYER, "steve"), "uuids", null).value())
                .isEqualTo(plain(PLAYER.toString(), "steve"));
    }

    @Test
    @DisplayName("the file-key ambiguity scan reports every typed key two file keys read back to, with no Java value")
    void fileKeyAmbiguityScanNeedsNoJavaValue() {
        assertThat(registry.ambiguousFileKeys(type("integers"), plain("1", "a", "01", "b"), FILE, ROOT))
                .containsExactly(Arrays.asList("limits", "1"));
        assertThat(registry.ambiguousFileKeys(type("nested"),
                plain("other", plain("2", "c"), "outer", plain("1", "a", "01", "b")), FILE, ROOT))
                .containsExactly(Arrays.asList("limits", "outer", "1"));
        assertThat(registry.ambiguousFileKeys(type("listed"),
                Arrays.asList(plain("3", "c"), plain("+1", "a", "1", "b")), FILE, ROOT))
                .containsExactly(Arrays.asList("limits", "1", "+1"));
        assertThat(registry.ambiguousFileKeys(type("integers"), plain("1", "a", "abc", "b", "01", "c"), FILE, ROOT))
                .containsExactly(Arrays.asList("limits", "1"));

        assertThat(registry.ambiguousFileKeys(type("strings"), plain("1", "a", "01", "b"), FILE, ROOT))
                .as("a String-keyed map").isEmpty();
        assertThat(registry.ambiguousFileKeys(type("integers"), plain("1", "a", "2", "b"), FILE, ROOT))
                .as("one spelling per key").isEmpty();
        assertThat(registry.ambiguousFileKeys(type("integers"), plain("abc", "a", "xyz", "b"), FILE, ROOT))
                .as("keys that do not convert").isEmpty();
        assertThatCode(() -> registry.ambiguousFileKeys(type("integers"), "not a map", FILE, ROOT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("control: a String-keyed map is written the same with or without a file value")
    void stringKeyedMapIsUnchanged() throws Exception {
        Map<String, String> value = Collections.singletonMap("1", "a");
        assertThat(write(value, "strings", plain("01", "a")).value()).isEqualTo(plain("1", "a"));
        assertThat(write(value, "strings", null).value()).isEqualTo(plain("1", "a"));
    }

    @Test
    @DisplayName("control: a custom key whose plain number does not read back is still refused at its entry path")
    void customKeyThatDoesNotReadBackIsRefused() {
        ConverterRegistry custom = new ConverterRegistry(ConverterRegistry.framework());
        custom.register(Point.class, new ConfigConverter<Point>() {
            @Override public Object toPlain(Point value, ConversionContext ctx) { return value.number; }
            @Override public Point fromPlain(Object plain, ConversionContext ctx) { return new Point(-1); }
        }, true);
        Map<Point, String> value = Collections.singletonMap(new Point(7), "a");

        for (Object fileValue : Arrays.asList(null, plain("7", "a"))) {
            assertThatThrownBy(() -> custom.toPlainResult(value, type("points"), FILE, ROOT, null, fileValue))
                    .isInstanceOfSatisfying(ConversionException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo("Map keys must convert to text");
                        assertThat(failure.path()).containsExactly("limits", "Point(7)");
                    });
        }
    }

    @Test
    @DisplayName("control: an entry with a legacy parser ignores the file value")
    void legacyParserIgnoresTheFileValue() throws Exception {
        ConfigEntry entry = Shapes.class.getDeclaredField("legacy").getAnnotation(ConfigEntry.class);
        Map<String, Object> value = new LinkedHashMap<>(plain("1", "a"));

        Object withFile = registry.toPlainResult(value, Object.class, FILE, ROOT, entry, plain("01", "a")).value();

        assertThat(withFile).isEqualTo(registry.toPlainResult(value, Object.class, FILE, ROOT, entry).value());
        assertThat(withFile).isEqualTo(plain("1", "a"));
    }

    @Test
    @DisplayName("a map whose file spells every key differently is bound in visits linear in its size (PR #687 Codex run 2)")
    void nonCanonicalSpellingsBindInLinearVisits() throws Exception {
        int size = 2_000;
        Map<UUID, String> value = new LinkedHashMap<>();
        CountingMap file = new CountingMap();
        for (int index = 0; index < size; index++) {
            UUID player = new UUID(0x1234_5678_9ABC_DEF0L, index);
            value.put(player, "v" + index);
            file.put(player.toString().toUpperCase(java.util.Locale.ROOT), "v" + index);
        }
        file.visits = 0;

        ConversionResult<Object> bound = write(value, "uuids", file);
        long visits = file.visits;

        assertThat(visits).as("file entries visited to bind %d keys", size).isLessThanOrEqualTo(4L * size);
        assertThat(bound.failures()).isEmpty();
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) bound.value()).keySet())).as("every key keeps the file's upper-case spelling")
                .containsExactlyElementsOf(new java.util.ArrayList<Object>(file.keySet()));
    }

    private ConversionResult<Object> write(Object value, String field, Object fileValue) throws Exception {
        return registry.toPlainResult(value, type(field), FILE, ROOT, null, fileValue);
    }

    private static Type type(String field) {
        try {
            return Shapes.class.getDeclaredField(field).getGenericType();
        } catch (NoSuchFieldException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static Map<String, Object> plain(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    /** A file map that counts every entry or key its iteration views hand out; lookups by key are not counted. */
    static final class CountingMap extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        long visits;

        @Override
        public java.util.Set<Map.Entry<String, Object>> entrySet() { return counted(super.entrySet()); }

        @Override
        public java.util.Set<String> keySet() { return counted(super.keySet()); }

        private <E> java.util.Set<E> counted(java.util.Set<E> view) {
            return new java.util.AbstractSet<E>() {
                @Override public int size() { return view.size(); }
                @Override public java.util.Iterator<E> iterator() {
                    java.util.Iterator<E> inner = view.iterator();
                    return new java.util.Iterator<E>() {
                        @Override public boolean hasNext() { return inner.hasNext(); }
                        @Override public E next() { visits++; return inner.next(); }
                    };
                }
            };
        }
    }

    static final class Point {
        private final int number;
        Point(int number) { this.number = number; }
        @Override public boolean equals(Object other) { return other instanceof Point && ((Point) other).number == number; }
        @Override public int hashCode() { return Objects.hash(number); }
        @Override public String toString() { return "Point(" + number + ")"; }
    }

    // Deliberately exercises the deprecated legacy parser compatibility contract.
    @SuppressWarnings("removal")
    public static class Passthrough extends com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser { }

    @SuppressWarnings("unused") // declarations read reflectively for their generic types
    static class Shapes {
        Map<Integer, String> integers;
        Map<Long, String> longs;
        Map<Boolean, String> booleans;
        Map<UUID, String> uuids;
        Map<String, String> strings;
        Map<String, Map<Integer, String>> nested;
        Map<Integer, Map<Integer, String>> typedOuter;
        List<Map<Integer, String>> listed;
        Map<Point, String> points;
        // Deliberately selects a legacy parser to verify that it never sees the file value.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = Passthrough.class) Object legacy;
    }
}
