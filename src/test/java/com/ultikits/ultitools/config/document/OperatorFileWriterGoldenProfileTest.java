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
        independentlyCheck(beforeText, afterText, check);
        return "WRITTEN";
    }

    /**
     * A check that does not use the gate's own span logic: the differing lines, by a line diff, are only what
     * the write could own - the added key (and the separator after an old last line without a line break),
     * one contiguous changed block for the value (a multi-line value may grow or shrink), or comment lines.
     */
    private static void independentlyCheck(String before, String after, Check check) {
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
        } else {
            for (String line : diff.removed) {
                assertThat(line.trim()).as("removed comment line").startsWith("#");
            }
            for (String line : diff.added) {
                assertThat(line.trim()).as("added comment line").startsWith("#");
            }
            assertThat(diff.added).anySatisfy(line -> assertThat(line).contains(COMMENT));
        }
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
