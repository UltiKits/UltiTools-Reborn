package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plan 17-56 Task 2: a write changes only what it addresses (orchestrator note of 2026-09-30: the new storage
 * rewrites only changed values and comment lines), map keys stay whole and keep their original text
 * (Follow-up 16), and an equal value keeps the file's text.
 */
@DisplayName("ConfigDocument - diff-aware writes")
class ConfigDocumentWriteTest {

    /** Every fixture except the anchored one, whose writes fall back to a re-render (tested below). */
    static Stream<GoldenCorpus.Fixture> writableFixtures() throws IOException {
        List<GoldenCorpus.Fixture> result = new ArrayList<>();
        for (GoldenCorpus.Fixture fixture : GoldenCorpus.fixtures()) {
            if (!fixture.name.endsWith("/anchors.yml")) {
                result.add(fixture);
            }
        }
        return result.stream();
    }

    /** Fixtures whose text ends with a line break, so appending a key cannot touch the last line. */
    static Stream<GoldenCorpus.Fixture> terminatedFixtures() throws IOException {
        List<GoldenCorpus.Fixture> result = new ArrayList<>();
        for (GoldenCorpus.Fixture fixture : (Iterable<GoldenCorpus.Fixture>) writableFixtures()::iterator) {
            String text = fixture.text();
            if (text.endsWith("\n") || text.endsWith("\r")) {
                result.add(fixture);
            }
        }
        return result.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writableFixtures")
    @DisplayName("changing the first single-line scalar leaf changes only that value's line")
    void changingOneLeafChangesOneLine(GoldenCorpus.Fixture fixture) throws Exception {
        ConfigDocument document = ConfigDocument.parse(fixture.text());
        Leaf leaf = firstLeaf(document.toPlain(), new ArrayList<String>());
        assertThat(leaf).as("fixture has a single-line scalar leaf").isNotNull();

        document.set(leaf.keyPath, leaf.newValue);
        String rendered = document.render();

        LineDiff diff = LineDiff.of(fixture.text(), rendered);
        assertThat(diff.removed).as(diff.toString()).hasSize(1);
        assertThat(diff.added).as(diff.toString()).hasSize(1);
        Map<String, Object> expected = ConfigDocument.parse(fixture.text()).toPlain();
        put(expected, leaf.keyPath, leaf.newValue);
        assertThat(ConfigDocument.parse(rendered).toPlain()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("terminatedFixtures")
    @DisplayName("adding a key with a comment adds only its own lines")
    void addingAKeyAddsOnlyItsLines(GoldenCorpus.Fixture fixture) throws Exception {
        ConfigDocument document = ConfigDocument.parse(fixture.text());
        String lineBreak = fixture.text().contains("\r\n") ? "\r\n" : "\n";

        document.set(Collections.singletonList("zz-added-key"), "added value");
        document.setFrameworkComment(Collections.singletonList("zz-added-key"), Collections.singletonList("Added by the test"));

        LineDiff diff = LineDiff.of(fixture.text(), document.render());
        assertThat(diff.removed).as(diff.toString()).isEmpty();
        assertThat(diff.added).containsExactly("# Added by the test" + lineBreak, "zz-added-key: added value" + lineBreak);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writableFixtures")
    @DisplayName("removing a key removes only its lines and its own comment")
    void removingAKeyRemovesOnlyItsLines(GoldenCorpus.Fixture fixture) throws Exception {
        ConfigDocument document = ConfigDocument.parse(fixture.text());
        List<String> path = removableKey(document.toPlain());
        assertThat(path).as("fixture has a removable key").isNotNull();
        List<String> comment = document.blockComment(path);

        assertThat(document.remove(path)).isTrue();

        String rendered = document.render();
        LineDiff diff = LineDiff.of(fixture.text(), rendered);
        assertThat(diff.added).as(diff.toString()).isEmpty();
        assertThat(diff.removed).as(diff.toString()).isNotEmpty();
        assertThat(diff.removedIsContiguous()).as(diff.toString()).isTrue();
        for (String line : comment) {
            if (line != null) {
                assertThat(String.join("", diff.removed)).as(diff.toString()).contains("#" + (line.isEmpty() ? "" : " " + line));
            }
        }
        Map<String, Object> expected = ConfigDocument.parse(fixture.text()).toPlain();
        removePath(expected, path);
        assertThat(ConfigDocument.parse(rendered).toPlain()).isEqualTo(expected);
    }

    @Nested
    @DisplayName("value rules")
    class ValueRules {

        @Test
        @DisplayName("an equal value keeps the old text: 1.5 over 1.50, Long 30 over 30, true over yes, an equal map")
        void equalValueKeepsTheText() throws Exception {
            String text = "rate: 1.50\ncooldown: 30\nenabled: yes\nmap:\n  a: 1\n  b: x\n";
            ConfigDocument document = ConfigDocument.parse(text);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("b", "x");
            map.put("a", 1L);

            document.set(path("rate"), 1.5);
            document.set(path("cooldown"), 30L);
            document.set(path("enabled"), Boolean.TRUE);
            document.set(path("map"), map);

            assertThat(document.render()).isEqualTo(text);
        }

        @Test
        @DisplayName("a changed string keeps the old quote style; an inline comment stays")
        void changedStringKeepsQuoteStyle() throws Exception {
            String text = "format: \"&7{player}\" # the format\nsingle: 'a'\nplain: b\n";
            ConfigDocument document = ConfigDocument.parse(text);

            document.set(path("format"), "&a{player}");
            document.set(path("single"), "c");
            document.set(path("plain"), "d");

            assertThat(document.render()).isEqualTo("format: \"&a{player}\" # the format\nsingle: 'c'\nplain: d\n");
        }

        @Test
        @DisplayName("a value that changes type is written in the new type's form")
        void typeChangeUsesTheNewForm() throws Exception {
            ConfigDocument document = ConfigDocument.parse("count: '5'\nflag: \"yes\"\n");

            document.set(path("count"), 5);
            document.set(path("flag"), Boolean.TRUE);

            assertThat(document.render()).isEqualTo("count: 5\nflag: true\n");
        }

        @Test
        @DisplayName("a new string that would read as another type is quoted")
        void newAmbiguousStringIsQuoted() throws Exception {
            ConfigDocument document = ConfigDocument.parse("a: text\n");

            document.set(path("a"), "yes");
            document.set(path("b"), "1.50");
            document.set(path("c"), "a: b");

            assertThat(document.render()).isEqualTo("a: 'yes'\nb: '1.50'\nc: 'a: b'\n");
        }

        @Test
        @DisplayName("a changed map is merged key by key: sub-key comments stay, removed keys go, new keys are appended")
        void changedMapIsMerged() throws Exception {
            String text = "channels:\n  global:\n    # the prefix\n    prefix: '&7[G]'\n    radius: -1\n  local:\n    prefix: x\n";
            ConfigDocument document = ConfigDocument.parse(text);
            Map<String, Object> global = new LinkedHashMap<>();
            global.put("prefix", "&7[G]");
            global.put("radius", 5);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("prefix", "n");
            Map<String, Object> channels = new LinkedHashMap<>();
            channels.put("global", global);
            channels.put("extra", extra);

            document.set(path("channels"), channels);

            assertThat(document.render()).isEqualTo(
                    "channels:\n  global:\n    # the prefix\n    prefix: '&7[G]'\n    radius: 5\n  extra:\n    prefix: n\n");
        }

        @Test
        @DisplayName("a changed list keeps a flow style; a same-size block list keeps its item comments")
        void changedListKeepsStyle() throws Exception {
            String text = "flow: [a, b]\nblock:\n- x # first\n- y\n";
            ConfigDocument document = ConfigDocument.parse(text);

            document.set(path("flow"), Arrays.asList("a", "b", "c"));
            document.set(path("block"), Arrays.asList("x", "z"));

            assertThat(document.render()).isEqualTo("flow: [a, b, c]\nblock:\n- x # first\n- z\n");
        }

        @Test
        @DisplayName("null is stored as an explicit null; a scalar on the way becomes a mapping")
        void nullAndIntermediateMappings() throws Exception {
            ConfigDocument document = ConfigDocument.parse("a: 5\n");

            document.set(path("a", "b"), 1);
            document.set(path("c"), null);

            assertThat(document.render()).isEqualTo("a:\n  b: 1\nc: null\n");
            assertThat(document.contains(path("c"))).isTrue();
            assertThat(document.get(path("c"))).isNull();
        }

        @Test
        @DisplayName("removing an absent key changes nothing")
        void removingAnAbsentKey() throws Exception {
            String text = "a: 1\n";
            ConfigDocument document = ConfigDocument.parse(text);

            assertThat(document.remove(path("b"))).isFalse();
            assertThat(document.remove(path("a", "x"))).isFalse();
            assertThat(document.render()).isEqualTo(text);
        }

        @Test
        @DisplayName("a write that would nest deeper than the load limit (100) is refused, so no file the next load refuses is made")
        void nestingBeyondTheLoadLimitIsRefused() throws Exception {
            ConfigDocument document = ConfigDocument.parse("a: 1\n");
            List<String> deepPath = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                deepPath.add("k" + i);
            }
            Object nested = "leaf";
            for (int i = 0; i < 100; i++) {
                nested = Collections.singletonMap("k", nested);
            }
            Object deepValue = nested;

            assertThatThrownBy(() -> document.set(deepPath, Collections.singletonMap("x", 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
            assertThatThrownBy(() -> document.set(path("b"), deepValue))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
            document.set(deepPath, "leaf");

            assertThat(ConfigDocument.load(writeTemp(document.render())).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        }

        @Test
        @DisplayName("a file holding only comments keeps them above the keys set into it")
        void commentOnlyDocument() throws Exception {
            ConfigDocument document = ConfigDocument.parse("# only a comment\n\n# second\n");

            document.set(path("k"), "v");
            String withKey = document.render();
            document.remove(path("k"));

            assertThat(withKey).isEqualTo("# only a comment\n\n# second\nk: v\n");
            assertThat(document.render()).isEqualTo("# only a comment\n\n# second\n");
        }

        @Test
        @DisplayName("removing the last key of a file leaves its header")
        void removingTheLastKey() throws Exception {
            ConfigDocument document = ConfigDocument.parse("# header\n\na: 1\n");

            document.remove(path("a"));

            assertThat(document.render()).isEqualTo("# header\n\n");
        }

        @Test
        @DisplayName("a document parsed from empty text renders the keys set into it")
        void emptyDocument() throws Exception {
            ConfigDocument document = ConfigDocument.parse("");

            document.set(path("a", "b"), "c");

            assertThat(document.render()).isEqualTo("a:\n  b: c\n");
            assertThat(ConfigDocument.empty().render()).isEmpty();
        }
    }

    @Nested
    @DisplayName("key identity (Follow-up 16)")
    class KeyIdentity {

        private static final String KEYS = "keys:\n  yes: boolean key\n  1: integer key\n  1.0: float key\n  ~: null key\n"
                + "  on: on key\n  \"o.O\": quoted dotted key\n  g.m: plain dotted key\n  wave.: trailing dot key\n";

        @Test
        @DisplayName("keys resolve as 6.2 resolves them: yes and on are 'true', 1 is '1', 1.0 is '1.0', ~ is 'null'")
        void keysResolveAsBukkit() throws Exception {
            ConfigDocument document = ConfigDocument.parse(KEYS);

            assertThat(document.get(path("keys", "true"))).isEqualTo("on key");
            assertThat(document.get(path("keys", "1"))).isEqualTo("integer key");
            assertThat(document.get(path("keys", "1.0"))).isEqualTo("float key");
            assertThat(document.get(path("keys", "null"))).isEqualTo("null key");
            assertThat(document.contains(path("keys", "yes"))).isFalse();
        }

        @Test
        @DisplayName("a write through a resolved key keeps the key's original text")
        void writeKeepsOriginalKeyText() throws Exception {
            ConfigDocument document = ConfigDocument.parse(KEYS);

            document.set(path("keys", "1"), "changed");
            document.set(path("keys", "null"), "changed too");
            document.set(path("keys", "true"), "last wins");

            assertThat(document.render()).isEqualTo(KEYS.replace("1: integer key", "1: changed")
                    .replace("~: null key", "~: changed too").replace("on: on key", "on: last wins"));
        }

        @Test
        @DisplayName("dotted keys stay whole: o.O, g.m and wave. are single keys, read and written")
        void dottedKeysStayWhole() throws Exception {
            ConfigDocument document = ConfigDocument.parse(KEYS);

            document.set(path("keys", "o.O"), "x");
            document.set(path("keys", "new.key"), "n");

            assertThat(document.get(path("keys", "g.m"))).isEqualTo("plain dotted key");
            assertThat(document.get(path("keys", "wave."))).isEqualTo("trailing dot key");
            assertThat(document.contains(path("keys", "g"))).isFalse();
            assertThat(document.render()).isEqualTo(KEYS.replace("\"o.O\": quoted dotted key", "\"o.O\": x") + "  new.key: n\n");
        }
    }

    @Nested
    @DisplayName("anchors, aliases and merge keys")
    class Anchors {

        private static final String ANCHORED = "defaults: &defaults\n  radius: 100\n  enabled: true\nchannels:\n  local:\n"
                + "    <<: *defaults\n    radius: 50\nworlds: &w\n- a\nmirror: *w\n";

        @Test
        @DisplayName("read as Bukkit reads them: merge keys flattened, aliases resolved")
        void readAsBukkit() throws Exception {
            ConfigDocument document = ConfigDocument.parse(ANCHORED);

            assertThat(document.get(path("channels", "local", "radius"))).isEqualTo(50);
            assertThat(document.get(path("channels", "local", "enabled"))).isEqualTo(true);
            assertThat(document.get(path("mirror"))).isEqualTo(Collections.singletonList("a"));
        }

        @Test
        @DisplayName("a write re-renders from plain data with every value equal")
        void writeReRendersFromPlainData() throws Exception {
            ConfigDocument document = ConfigDocument.parse(ANCHORED);
            Map<String, Object> expected = document.toPlain();
            put(expected, path("channels", "local", "radius"), 75);

            document.set(path("channels", "local", "radius"), 75);

            String rendered = document.render();
            assertThat(rendered).doesNotContain("&").doesNotContain("*").doesNotContain("<<");
            assertThat(ConfigDocument.parse(rendered).toPlain()).isEqualTo(expected);
        }
    }

    private static java.nio.file.Path writeTemp(String text) throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("config-document", ".yml");
        file.toFile().deleteOnExit();
        java.nio.file.Files.write(file, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return file;
    }

    static List<String> path(String... keys) {
        return Arrays.asList(keys);
    }

    /** A key path and the value that changes the first single-line scalar under it. */
    static final class Leaf {
        final List<String> keyPath;
        final Object newValue;

        Leaf(List<String> keyPath, Object newValue) {
            this.keyPath = keyPath;
            this.newValue = newValue;
        }
    }

    /**
     * The first scalar leaf, depth first through mappings and lists, that is not a multi-line string. A leaf
     * inside a list is changed by setting the whole list (with only that element changed) at the nearest key.
     */
    static Leaf firstLeaf(Map<String, Object> mapping, List<String> prefix) {
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Leaf found = firstLeaf((Map<String, Object>) value, path);
                if (found != null) {
                    return found;
                }
            } else if (value instanceof List) {
                Object changed = changeFirstScalar(value, new boolean[1]);
                if (changed != null) {
                    return new Leaf(path, changed);
                }
            } else if (isSingleLineScalar(value)) {
                return new Leaf(path, changedValue(value));
            }
        }
        return null;
    }

    /** A copy of {@code value} with its first single-line scalar changed, or {@code null} if it has none. */
    private static Object changeFirstScalar(Object value, boolean[] done) {
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object element : (List<?>) value) {
                copy.add(done[0] ? element : changeFirstScalar(element, done));
            }
            return done[0] ? copy : null;
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object changed = done[0] ? null : changeFirstScalar(entry.getValue(), done);
                copy.put((String) entry.getKey(), changed != null ? changed : entry.getValue());
            }
            return done[0] ? copy : null;
        }
        if (isSingleLineScalar(value)) {
            done[0] = true;
            return changedValue(value);
        }
        return null;
    }

    private static boolean isSingleLineScalar(Object value) {
        return value == null || value instanceof Boolean || value instanceof Number
                || value instanceof String && !((String) value).matches("(?s).*[\r\n].*");
    }

    /**
     * The key to remove: the first scalar or list leaf whose mapping has another key; else the first key with a
     * sibling; else a top-level key that is the only one.
     */
    static List<String> removableKey(Map<String, Object> root) {
        List<String> leaf = keyWithSibling(root, new ArrayList<String>(), true);
        if (leaf == null) {
            leaf = keyWithSibling(root, new ArrayList<String>(), false);
        }
        if (leaf == null && root.size() == 1) {
            leaf = new ArrayList<>(root.keySet());
        }
        return leaf;
    }

    private static List<String> keyWithSibling(Map<String, Object> mapping, List<String> prefix, boolean leavesOnly) {
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(entry.getKey());
            boolean isMap = entry.getValue() instanceof Map;
            if (mapping.size() > 1 && (!leavesOnly || !isMap)) {
                return path;
            }
            if (isMap) {
                @SuppressWarnings("unchecked")
                List<String> found = keyWithSibling((Map<String, Object>) entry.getValue(), path, leavesOnly);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static void removePath(Map<String, Object> root, List<String> path) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            current = (Map<String, Object>) current.get(path.get(i));
        }
        current.remove(path.get(path.size() - 1));
    }

    static Object changedValue(Object value) {
        if (value instanceof String) {
            return value + " changed";
        }
        if (value instanceof Boolean) {
            return !((Boolean) value);
        }
        if (value instanceof Integer) {
            return ((Integer) value) + 1;
        }
        if (value instanceof Long) {
            return ((Long) value) + 1;
        }
        if (value instanceof BigInteger) {
            return ((BigInteger) value).add(BigInteger.ONE);
        }
        if (value instanceof Double && !((Double) value).isNaN() && !((Double) value).isInfinite()) {
            return ((Double) value) + 1.0;
        }
        return "changed";
    }

    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> root, List<String> path, Object value) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            current = (Map<String, Object>) current.get(path.get(i));
        }
        current.put(path.get(path.size() - 1), value);
    }
}
