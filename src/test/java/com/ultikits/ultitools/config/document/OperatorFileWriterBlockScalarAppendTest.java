package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** #883: adding defaults never grants ownership of the operator's block scalar or its separators. */
class OperatorFileWriterBlockScalarAppendTest {

    private static final List<String> DEFAULT_KEY = Arrays.asList("expiry", "retention-days");

    @TempDir
    Path directory;

    static Stream<Arguments> blockScalars() {
        return Stream.of(
                Arguments.of("literal strip", "message: |-\n  first\n  second\n\n", "first\nsecond"),
                Arguments.of("literal clip", "message: |\n  first\n  second\n\n\n", "first\nsecond\n"),
                Arguments.of("literal keep", "message: |+\n  first\n  second\n\n\n", "first\nsecond\n\n\n"),
                Arguments.of("folded strip", "message: >-\n  first\n  second\n\n", "first second"),
                Arguments.of("folded clip", "message: >\n  first\n  second\n\n", "first second\n"),
                Arguments.of("folded keep", "message: >+\n  first\n  second\n\n", "first second\n\n"),
                Arguments.of("indent then strip", "message: |2-\n  first\n\n", "first"),
                Arguments.of("strip then indent", "message: |-2\n  first\n\n", "first"),
                Arguments.of("folded explicit indent", "message: >2-\n  first\n  second\n\n", "first second"),
                Arguments.of("explicit keep", "message: |+2\n  first\n\n", "first\n\n"),
                Arguments.of("one-column indent", "message: |1-\n first\n\n", "first"),
                Arguments.of("nine-column indent", "message: |9-\n         first\n\n", "first"),
                Arguments.of("leading blank lines", "message: |2-\n\n  first\n\n", "\nfirst"),
                Arguments.of("empty strip", "message: |-\n\n\n", ""),
                Arguments.of("empty clip", "message: |\n\n\n", ""),
                Arguments.of("empty keep", "message: |+\n\n\n", "\n\n"),
                Arguments.of("empty explicit indent", "message: |2-\n\n", ""),
                Arguments.of("spaces are scalar content", "message: |2-\n  first\n    \n\n", "first\n  "),
                Arguments.of("whitespace-only trailing line", "message: |-\n  first\n  \n\n", "first"),
                Arguments.of("folded more-indented content", "message: >-\n  first\n    indented\n\n  last\n\n",
                        "first\n  indented\n\nlast"),
                Arguments.of("indicator comment", "message: |- # operator note\n  first\n\n", "first"),
                Arguments.of("hash is content", "message: |-\n  # not a comment\n\n", "# not a comment"),
                Arguments.of("supplementary Unicode before and inside", "label: '😀'\nmessage: |-\n  😀 first\n\n", "😀 first"),
                Arguments.of("CRLF and BOM", "﻿message: |-\r\n  first\r\n\r\n", "first"),
                Arguments.of("CR throughout", "message: |-\r  first\r\r", "first"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blockScalars")
    void appendDefaultKeepsEveryOriginalByteAndScalarValue(String layout, String original, String value) throws Exception {
        Path file = write(original);
        ConfigDocument before = ConfigDocument.parse(original);
        assertThat(before.get(Collections.singletonList("message"))).as(layout).isEqualTo(value);

        OperatorFileWriter.Result result = append(file, DEFAULT_KEY);

        assertThat(result.outcome()).as("%s: %s", layout, result.reason()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        String lineBreak = original.contains("\r\n") ? "\r\n" : original.contains("\r") ? "\r" : "\n";
        String suffix = ("expiry:\n  # Retention in days\n  retention-days: 0\n").replace("\n", lineBreak);
        assertThat(Files.readAllBytes(file)).isEqualTo((original + suffix).getBytes(StandardCharsets.UTF_8));
        assertParsedValues(file, before, DEFAULT_KEY);
        assertThat(result.document().get(Collections.singletonList("message"))).isEqualTo(value);
    }

    static Stream<Arguments> nestedLayouts() {
        return Stream.of(
                Arguments.of("nested block at EOF", "recall:\n  message: |-\n    first\n\n", ""),
                Arguments.of("nested block before another section", "recall:\n  message: |-\n    first\n\n", "other: 1\n"),
                Arguments.of("comment at the next key's column", "recall:\n  message: |-\n    first\n\n  # keep this note\n  old: 2\n", "other: 1\n"),
                Arguments.of("block scalar inside a list", "recall:\n  messages:\n  - |-\n    first\n\n", "other: 1\n"),
                Arguments.of("keep chomping before another key", "recall:\n  message: |+\n    first\n\n  old: 2\n", "other: 1\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedLayouts")
    void insertIntoExistingSectionPreservesBothSidesOfTheInsertion(String layout, String prefix, String suffix) throws Exception {
        String original = prefix + suffix;
        Path file = write(original);
        ConfigDocument before = ConfigDocument.parse(original);
        List<String> key = Arrays.asList("recall", "retention-days");

        OperatorFileWriter.Result result = append(file, key);

        assertThat(result.outcome()).as("%s: %s", layout, result.reason()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(Files.readAllBytes(file)).isEqualTo((prefix + "  # Retention in days\n  retention-days: 0\n" + suffix)
                .getBytes(StandardCharsets.UTF_8));
        assertParsedValues(file, before, key);
    }

    @Test
    void twoBlockScalarsRetainTheirOwnChompingAndSeparators() throws Exception {
        String original = "first: |-\n  one\n\nsecond: >+\n  two\n\n\n";
        Path file = write(original);
        ConfigDocument before = ConfigDocument.parse(original);

        OperatorFileWriter.Result result = append(file, DEFAULT_KEY);

        assertThat(result.outcome()).as(result.reason()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).startsWith(original);
        assertParsedValues(file, before, DEFAULT_KEY);
    }

    @Test
    void changingTheScalarDoesNotReplayItsOldSource() throws Exception {
        ConfigDocument document = ConfigDocument.parse("message: |-\n  old text\n\nother: 1\n");
        assertThat(document.render()).contains("old text");

        document.set(Collections.singletonList("message"), "replacement\n");
        document.set(DEFAULT_KEY, 0);
        ConfigDocument reparsed = ConfigDocument.parse(document.render());

        assertThat(reparsed.get(Collections.singletonList("message"))).isEqualTo("replacement\n");
        assertThat(reparsed.get(DEFAULT_KEY)).isEqualTo(0);
    }

    @Test
    void appendingCannotHideAChangeOutsideTheOwnedPaths() throws Exception {
        String original = "message: |-\n  operator text\n\n";
        Path file = write(original);

        OperatorFileWriter.Result result = OperatorFileWriter.write(file,
                OwnedPaths.builder().value(DEFAULT_KEY).build(), null, document -> {
                    document.set(DEFAULT_KEY, 0);
                    document.set(Collections.singletonList("message"), "not authorized");
                });

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(result.reason()).contains("does not own");
        assertThat(Files.readAllBytes(file)).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anOperatorSaveWhileTheAppendIsStagedStillWins() throws Exception {
        String original = "message: |-\n  first\n\n";
        Path file = write(original);
        String operatorEdit = original.replace("first", "operator's new text");
        AtomicConfigWriter.FileOperations editWhileStaging = new AtomicConfigWriter.FileOperations() {
            @Override
            public java.nio.channels.FileChannel open(Path temporary, java.nio.file.attribute.FileAttribute<?>... attributes)
                    throws java.io.IOException {
                Files.write(file, operatorEdit.getBytes(StandardCharsets.UTF_8));
                return AtomicConfigWriter.FileOperations.super.open(temporary, attributes);
            }
        };

        OperatorFileWriter.Result result = OperatorFileWriter.write(file,
                OwnedPaths.builder().value(DEFAULT_KEY).build(), null, document -> document.set(DEFAULT_KEY, 0), editWhileStaging);

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.FILE_CHANGED);
        assertThat(Files.readAllBytes(file)).isEqualTo(operatorEdit.getBytes(StandardCharsets.UTF_8));
    }

    static Stream<Arguments> refusedLayouts() {
        return Stream.of(
                Arguments.of("invalid explicit indentation", "message: |0-\n  first\n\n"),
                Arguments.of("under-indented content", "message: |2-\n first\n\n"),
                Arguments.of("over-indented leading empty line", "message: |-\n    \n  first\n\n"),
                Arguments.of("anchors still refuse", "message: &shared |-\n  first\n\ncopy: *shared\n"),
                Arguments.of("unrelated operator alignment", "other:  1\nmessage: |-\n  first\n\n"),
                Arguments.of("mixed line endings", "other: 1\r\nmessage: |-\n  first\n\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedLayouts")
    void unsafeLayoutsStillRefuseWithoutTouchingBytesOrMtime(String layout, String original) throws Exception {
        Path file = write(original);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000000000000L));
        FileTime modified = Files.getLastModifiedTime(file);

        OperatorFileWriter.Result result = append(file, DEFAULT_KEY);

        assertThat(result.outcome()).as(layout).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(result.reason()).isNotEmpty();
        assertThat(Files.readAllBytes(file)).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }

    private Path write(String text) throws Exception {
        Path file = directory.resolve("operator.yml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static OperatorFileWriter.Result append(Path file, List<String> key) throws Exception {
        return OperatorFileWriter.write(file, OwnedPaths.builder().value(key).comment(key).build(), null, document -> {
            document.set(key, 0);
            document.setFrameworkComment(key, Collections.singletonList("Retention in days"));
        });
    }

    @SuppressWarnings("unchecked")
    private static void assertParsedValues(Path file, ConfigDocument before, List<String> key) {
        ConfigLoadResult loaded = ConfigDocument.load(file);
        assertThat(loaded.state()).isEqualTo(ConfigLoadResult.State.LOADED);
        ConfigDocument after = loaded.document();
        assertThat(after.get(key)).isEqualTo(0);
        assertThat(after.blockComment(key)).containsExactly("Retention in days");
        Map<String, Object> expected = before.toPlain();
        if (key.equals(DEFAULT_KEY)) {
            expected.put("expiry", Collections.singletonMap("retention-days", 0));
        } else {
            ((Map<String, Object>) expected.get("recall")).put("retention-days", 0);
        }
        assertThat(after.toPlain()).isEqualTo(expected);
    }
}
