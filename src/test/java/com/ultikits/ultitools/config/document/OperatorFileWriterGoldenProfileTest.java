package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The golden refusal profile of the config write gate (plan 17-63): for every fixture of the golden corpus,
 * the three routine automatic writes - (a) insert a new top-level key, (b) set the first scalar leaf to a
 * different value of the same type, (c) rewrite the first key's framework comment - and what the gate did.
 * Every {@code bundled/} and {@code written-by-6.2/} fixture must accept all three with its unowned lines
 * byte-identical (the files the project ships and the files 6.2 wrote); {@code hand-edited/} fixtures are
 * measured and printed, not asserted, because refusing operator layout is the gate's purpose.
 */
class OperatorFileWriterGoldenProfileTest {

    private static final String ADDED = "ultitools-golden-profile-added";
    private static final String COMMENT = "Golden profile framework comment";
    private static final List<String> TABLE = Collections.synchronizedList(new ArrayList<String>());

    @TempDir
    Path tempDir;

    static List<GoldenCorpus.Fixture> fixtures() throws IOException {
        return GoldenCorpus.fixtures();
    }

    @BeforeEach
    void freshRun() {
        OperatorFileWriter.resetAnchorWarnings();
    }

    @AfterAll
    static void printTable() {
        List<String> rows = new ArrayList<>(TABLE);
        Collections.sort(rows);
        StringBuilder table = new StringBuilder("GOLDEN-PROFILE | Fixture | Class | insert | set | comment |\n");
        for (String row : rows) {
            table.append("GOLDEN-PROFILE ").append(row).append('\n');
        }
        System.out.print(table);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void routineAutomaticWrites(GoldenCorpus.Fixture fixture) throws Exception {
        String original = fixture.text();
        ConfigDocument document = ConfigDocument.parse(original);
        Map<String, Object> plain = document.toPlain();
        String fixtureClass = fixture.name.substring(0, fixture.name.indexOf('/'));

        String insert = attempt(fixture, OwnedPaths.builder().value(Collections.singletonList(ADDED)).build(),
                d -> d.set(Collections.singletonList(ADDED), "added"), Check.INSERT);

        List<String> leaf = firstScalarLeaf(plain, new ArrayList<String>());
        String set = leaf == null ? "n/a (no scalar leaf)" : attempt(fixture, OwnedPaths.builder().value(leaf).build(),
                d -> d.set(leaf, differentValueOfSameType(d.get(leaf))), Check.SET);

        List<String> first = plain.isEmpty() ? null : Collections.singletonList(plain.keySet().iterator().next());
        String comment = first == null ? "n/a (no key)" : attempt(fixture, OwnedPaths.builder().comment(first).build(),
                d -> d.setFrameworkComment(first, Collections.singletonList(COMMENT)), Check.COMMENT);

        TABLE.add("| " + fixture.name + " | " + fixtureClass + " | " + insert + " | " + set + " | " + comment + " |");
        if (!"hand-edited".equals(fixtureClass)) {
            assertThat(insert).as("insert on " + fixture.name).isEqualTo("WRITTEN");
            assertThat(set).as("set on " + fixture.name).isIn("WRITTEN", "n/a (no scalar leaf)");
            assertThat(comment).as("comment on " + fixture.name).isEqualTo("WRITTEN");
        }
    }

    private enum Check { INSERT, SET, COMMENT }

    private String attempt(GoldenCorpus.Fixture fixture, OwnedPaths owned, Consumer<ConfigDocument> edit, Check check)
            throws IOException, ConfigParseException {
        Path file = Files.createTempDirectory(tempDir, "profile").resolve("profile.yml");
        byte[] before = fixture.bytes();
        Files.write(file, before);
        OperatorFileWriter.Result result = OperatorFileWriter.write(file, owned, null, edit);
        byte[] after = Files.readAllBytes(file);
        if (result.outcome() != OperatorFileWriter.Outcome.WRITTEN) {
            assertThat(after).as("a write that was not published leaves the bytes").isEqualTo(before);
            return result.outcome() + " (" + result.reason() + ")";
        }
        String beforeText = new String(before, StandardCharsets.UTF_8);
        String afterText = new String(after, StandardCharsets.UTF_8);
        ConfigDocument expected = ConfigDocument.parse(beforeText);
        edit.accept(expected);
        assertThat(PlainData.plainEquals(ConfigDocument.parse(afterText).toPlain(), expected.toPlain()))
                .as("the file holds the original values with only the edit applied").isTrue();
        independentlyCheck(beforeText, afterText, check, owned);
        return "WRITTEN";
    }

    /**
     * A check that does not use the gate's own span logic: the differing lines, by a line diff, are only what
     * the write could own - the added key (and the separator after an old last line without a line break),
     * one contiguous changed block for the value (a multi-line value may grow or shrink), or comment lines.
     */
    private static void independentlyCheck(String before, String after, Check check, OwnedPaths owned) {
        LineDiff diff = LineDiff.of(before, after);
        if (check == Check.INSERT) {
            for (String added : diff.added) {
                assertThat(added.contains(ADDED) || isSeparatorOf(diff.removed, added)).as("added line " + added).isTrue();
            }
            assertThat(diff.removed.size()).as("removed lines (only a separator)").isLessThanOrEqualTo(1);
        } else if (check == Check.SET) {
            assertThat(diff.removed).isNotEmpty();
            assertThat(diff.removedIsContiguous()).as("one changed block").isTrue();
            assertThat(diff.added).isNotEmpty();
            // Review round 1 IN-04: the changed block is the key's own lines - from its key line to the last line of
            // its value, never a blank or comment line between the value and the next key.
            int[] region = valueRegion(before, owned.values().get(0));
            for (int line : diff.removedAt) {
                assertThat(line).as("removed line inside the key's own lines " + region[0] + ".." + region[1])
                        .isBetween(region[0], region[1]);
            }
            // Review round 2 IN-R2-02: the added lines too, in the new text's coordinates - a renderer that also
            // inserted a line elsewhere is caught here, independently of the gate's own spans.
            int[] rendered = valueRegion(after, owned.values().get(0));
            for (int line : diff.addedAt) {
                assertThat(line).as("added line inside the key's own lines " + rendered[0] + ".." + rendered[1])
                        .isBetween(rendered[0], rendered[1]);
            }
        } else {
            for (String line : diff.removed) {
                assertThat(line.trim()).as("removed comment line").startsWith("#");
            }
            for (String line : diff.added) {
                assertThat(line.trim()).as("added comment line").startsWith("#");
            }
            assertThat(diff.added).anySatisfy(line -> assertThat(line).contains(COMMENT));
            // Review round 1 IN-04: the rewritten comment lines sit directly above the owned key.
            if (!diff.removedAt.isEmpty()) {
                int keyLine = valueRegion(before, owned.comments().get(0))[0];
                assertThat(diff.removedIsContiguous()).isTrue();
                assertThat(diff.removedAt.get(diff.removedAt.size() - 1)).as("directly above the owned key").isEqualTo(keyLine - 1);
            }
            // Review round 2 IN-R2-02: the added comment lines, also where none was removed, are one block ending
            // directly above the owned key in the new text.
            int renderedKeyLine = valueRegion(after, owned.comments().get(0))[0];
            assertThat(diff.addedIsContiguous()).as("one added comment block").isTrue();
            assertThat(diff.addedAt.get(diff.addedAt.size() - 1)).as("added directly above the owned key")
                    .isEqualTo(renderedKeyLine - 1);
        }
    }

    /**
     * The key line of {@code path} and the last line of its value, found with SnakeYAML's own composer (not the
     * gate's span logic): the value ends at the last line before the next key that is not blank and not a comment
     * at or left of the key's column (a block scalar's content is indented deeper than its key). A keep-chomped
     * block scalar ({@code |+}, {@code >+}) also owns the blank lines it keeps (review round 2 IN-R2-02): they are
     * part of its value, up to the composer's end mark.
     */
    private static int[] valueRegion(String text, List<String> path) {
        org.yaml.snakeyaml.nodes.Node node = new org.yaml.snakeyaml.Yaml(ConfigDocument.loaderOptions())
                .compose(new java.io.StringReader(text.startsWith("\uFEFF") ? text.substring(1) : text));
        ConfigDocument.NodeConstructor keys = new ConfigDocument.NodeConstructor();
        org.yaml.snakeyaml.nodes.Node key = null;
        for (String segment : path) {
            org.yaml.snakeyaml.nodes.Node found = null;
            for (org.yaml.snakeyaml.nodes.NodeTuple tuple : ((org.yaml.snakeyaml.nodes.MappingNode) node).getValue()) {
                if (segment.equals(String.valueOf(keys.construct(tuple.getKeyNode())))) {
                    key = tuple.getKeyNode();
                    found = tuple.getValueNode();
                }
            }
            node = found;
        }
        assertThat(key).as("key " + path).isNotNull();
        int keyLine = key.getStartMark().getLine();
        int keyColumn = key.getStartMark().getColumn();
        List<String> lines = LineDiff.lines(text.startsWith("\uFEFF") ? text.substring(1) : text);
        int next = lines.size();
        for (int i = keyLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int indent = line.length() - line.replaceAll("^[ \t]+", "").length();
            String content = line.trim();
            if (!content.isEmpty() && !content.startsWith("#") && indent <= keyColumn) {
                next = i;
                break;
            }
        }
        int last = keyLine;
        for (int i = keyLine + 1; i < next; i++) {
            String line = lines.get(i);
            int indent = line.length() - line.replaceAll("^[ \t]+", "").length();
            String content = line.trim();
            if (!content.isEmpty() && !(content.startsWith("#") && indent <= keyColumn)) {
                last = i;
            }
        }
        if (node instanceof org.yaml.snakeyaml.nodes.ScalarNode && ((org.yaml.snakeyaml.nodes.ScalarNode) node).getValue().endsWith("\n\n")) {
            org.yaml.snakeyaml.error.Mark end = node.getEndMark();
            last = Math.max(last, Math.min(next - 1, end.getColumn() == 0 ? end.getLine() - 1 : end.getLine()));
        }
        return new int[]{keyLine, last};
    }

    /**
     * Review round 2 IN-R2-02: the independent checks themselves - a set that also adds a line outside the key, and a
     * comment added away from its key, fail them; a keep-chomped value's blank lines count as the value.
     */
    @org.junit.jupiter.api.Test
    void independentChecksAreBoundToTheOwnedKeyOnBothSides() {
        OwnedPaths setA = OwnedPaths.builder().value(Collections.singletonList("a")).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> independentlyCheck(
                "a: 1\nb: 2\n", "a: 2\nb: 2\nstray: 3\n", Check.SET, setA)).isInstanceOf(AssertionError.class);
        OwnedPaths commentB = OwnedPaths.builder().comment(Collections.singletonList("b")).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> independentlyCheck(
                "a: 1\nb: 2\n", "# " + COMMENT + "\na: 1\nb: 2\n", Check.COMMENT, commentB)).isInstanceOf(AssertionError.class);
        independentlyCheck("a: 1\nb: 2\n", "a: 1\n# " + COMMENT + "\nb: 2\n", Check.COMMENT, commentB);
        independentlyCheck("a: |+\n  x\n\nb: 1\n", "a: |+\n  y\nb: 1\n", Check.SET, setA);
    }

    private static boolean isSeparatorOf(List<String> removed, String added) {
        for (String line : removed) {
            if (added.startsWith(line) && added.substring(line.length()).matches("\\r\\n|\\n|\\r")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> firstScalarLeaf(Map<?, ?> map, List<String> prefix) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(String.valueOf(entry.getKey()));
            Object value = entry.getValue();
            if (value instanceof Map) {
                List<String> nested = firstScalarLeaf((Map<?, ?>) value, path);
                if (nested != null) {
                    return nested;
                }
            } else if (value instanceof String || value instanceof Boolean || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger || value instanceof Double) {
                return path;
            }
        }
        return null;
    }

    private static Object differentValueOfSameType(Object value) {
        if (value instanceof String) {
            return value + "-profile";
        }
        if (value instanceof Boolean) {
            return !(Boolean) value;
        }
        if (value instanceof Integer) {
            return (Integer) value + 1;
        }
        if (value instanceof Long) {
            return (Long) value + 1;
        }
        if (value instanceof BigInteger) {
            return ((BigInteger) value).add(BigInteger.ONE);
        }
        return (Double) value + 1.5;
    }
}
