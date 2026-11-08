package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pinning tests for the existing refused layouts documented in {@code COMPATIBILITY.md} "Layouts the write gate
 * cannot keep". A refusal names the offending line when layout comparison can locate it, or gives the parse-back
 * reason without a line number. The control cases are layouts the gate keeps byte for byte, so a write to another
 * key goes through. No renderer behaviour is changed by these tests.
 * <p>
 * Most cases write the setting {@code a} (1 to 2, or insert it). The list-comment cases write another key or own the
 * list itself, including the exception where changing its length drops the item comment.
 */
class OperatorFileWriterLayoutRefusalListTest {

    @TempDir
    Path directory;

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("a whitespace-only line", "b: 2\n  \nc: 3\na: 1\n", 2),
                Arguments.of("a trailing space after a value", "b: 2 \nc: 3\na: 1\n", 1),
                Arguments.of("a trailing space after a section key", "sec: \n  k: 1\na: 1\n", 1),
                Arguments.of("an inline comment aligned with several spaces", "b: 2    # note\nc: 3\na: 1\n", 1),
                Arguments.of("more than one space after a colon", "b:  2\nc: 3\na: 1\n", 1),
                Arguments.of("spaces inside flow brackets", "w: [ x, y ]\na: 1\n", 1),
                Arguments.of("a document start marker", "---\nb: 2\na: 1\n", 1),
                Arguments.of("a document end marker", "b: 2\na: 1\n...\n", 3),
                Arguments.of("a block scalar followed by a blank line", "s: |\n  x\n\nb: 2\na: 1\n", 3),
                Arguments.of("a section-closing comment after a blank line", "sec:\n  k: 1\n\n  # closing\nb: 2\na: 1\n", 4),
                Arguments.of("a comment indented deeper than the next key", "b: 2\n  # indented\nc: 3\na: 1\n", 2),
                Arguments.of("two indentation widths in one file", "sec:\n  k: 1\nsec2:\n    k: 1\na: 1\n", 4),
                Arguments.of("mixed line endings", "b: 2\r\nc: 3\na: 1\n", 2),
                Arguments.of("a plain value continued onto the next line", "b: one\n  two\na: 1\n", 1),
                Arguments.of("a tab inside a value", "b: x\ty\na: 1\n", 1),
                Arguments.of("an explicit key (? key)", "? b\n: 2\na: 1\n", 1),
                Arguments.of("an explicit tag (!!str)", "b: !!str 2\na: 1\n", 1),
                Arguments.of("a whitespace-only file", "   \n", 1),
                Arguments.of("a comment-only file with an indented comment", "# a\n  # indented\n", 2),
                Arguments.of("a last block scalar with | in a file without a final line break", "a: 1\ns: |\n  x\n  y", 2),
                Arguments.of("a last block scalar with |+ in a file without a final line break", "a: 1\ns: |+\n  x\n  y", 2),
                Arguments.of("a last block scalar with > in a file without a final line break", "a: 1\ns: >\n  x\n\n  y", 2),
                // #836: one Unicode spelling per document; the minority spelling would be re-rendered.
                Arguments.of("raw non-ASCII text, then a \\u escape", "b: \u4E2D\nc: \"\\u4E2D\"\na: 1\n", 2),
                Arguments.of("a \\u escape, then raw non-ASCII text", "c: \"\\u4E2D\"\nb: \u4E2D\na: 1\n", 1));
    }

    static Stream<Arguments> kept() {
        return Stream.of(
                Arguments.of("one space before an inline comment", "b: 2 # note\nc: 3\na: 1\n"),
                Arguments.of("a block scalar not followed by a blank line", "s: |\n  x\nb: 2\na: 1\n"),
                Arguments.of("a comment after a block scalar at the next key's column (#592)",
                        "sec:\n  s: |-\n    x\n  # note\n  k: 1\na: 1\n"),
                Arguments.of("a section-closing comment after a block scalar (#592)", "sec:\n  s: |-\n    x\n  # note\na: 1\n"),
                Arguments.of("a block scalar in a file without a final line break", "s: |-\n  x\n  y\nb: 2\na: 1"),
                Arguments.of("a last block scalar with |- in a file without a final line break", "a: 1\ns: |-\n  x\n  y"),
                Arguments.of("a flow map", "m: {x: 1, y: 2}\na: 1\n"),
                Arguments.of("a flow list without inner spaces", "w: [x, y]\na: 1\n"),
                Arguments.of("quoted values", "b: 'x'\nc: \"y\"\na: 1\n"),
                Arguments.of("four-space indentation throughout", "sec:\n    k: 1\na: 1\n"),
                Arguments.of("list items at the key's column", "l:\n- x\n- y\na: 1\n"),
                Arguments.of("CRLF throughout", "b: 2\r\nc: 3\r\na: 1\r\n"),
                Arguments.of("no final line break", "b: 2\nc: 3\na: 1"),
                Arguments.of("a byte-order mark", "\uFEFFb: 2\na: 1\n"),
                Arguments.of("a comment without a space after #", "#note\nb: 2\na: 1\n"),
                Arguments.of("several blank lines", "b: 2\n\n\nc: 3\na: 1\n"),
                Arguments.of("hex, ~ and an empty value", "b: 0x1F\nc: ~\nd:\na: 1\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void aLayoutTheRendererCannotKeepRefusesTheWriteNamingItsLine(String layout, String text, int line)
            throws Exception {
        Path file = write(text);

        OperatorFileWriter.Result result = setA(file);

        assertThat(result.outcome()).as(layout).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(result.reason()).as(layout).endsWith("(line " + line + ")");
        assertThat(read(file)).as("the file keeps its bytes").isEqualTo(text);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kept")
    void aLayoutTheRendererKeepsLetsTheWriteThroughWithEveryOtherByteUnchanged(String layout, String text)
            throws Exception {
        Path file = write(text);

        OperatorFileWriter.Result result = setA(file);

        assertThat(result.outcome()).as(layout).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(read(file)).isEqualTo(text.replace("a: 1", "a: 2"));
    }

    @org.junit.jupiter.api.Test
    void aFileHoldingOnlyAByteOrderMarkRefusesNamingTheMark() throws Exception {
        // An emptied file saved by an editor as UTF-8 with a BOM: the mark is the line to fix (R3-I1).
        Path file = write("\uFEFF");

        OperatorFileWriter.Result result = setA(file);

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(result.reason()).contains("byte-order mark");
        assertThat(read(file)).isEqualTo("\uFEFF");
    }

    @ParameterizedTest(name = "unowned plain key of {0} characters")
    @ValueSource(ints = {122, 123, 128, 129})
    void plainKeyLengthLimitRefusesOnlyWhenItsRewrittenLayoutIsOutsideTheOwnedPaths(int length) throws Exception {
        String key = String.join("", Collections.nCopies(length, "k"));
        String text = key + ": keep\na: 1\n";
        Path file = write(text);

        OperatorFileWriter.Result result = setA(file);

        // SnakeYAML counts the five-character !!str tag too and requires the total to be below 128.
        if (length >= 123) {
            assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
            assertThat(result.reason()).contains("layout").endsWith("(line 1)");
            assertThat(read(file)).isEqualTo(text);
        } else {
            assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
            assertThat(read(file)).isEqualTo(text.replace("a: 1", "a: 2"));
        }
    }

    @ParameterizedTest(name = "owned plain key of {0} characters")
    @ValueSource(ints = {122, 123, 128, 129})
    void owningTheLongKeyCanPermitItsExplicitKeyRendering(int length) throws Exception {
        String key = String.join("", Collections.nCopies(length, "k"));
        Path file = write(key + ": before\na: 1\n");

        OperatorFileWriter.Result result = OperatorFileWriter.write(file,
                OwnedPaths.builder().value(Collections.singletonList(key)).build(), null,
                document -> document.set(Collections.singletonList(key), "after"));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(ConfigDocument.parse(read(file)).toPlain()).containsEntry(key, "after").containsEntry("a", 1);
        assertThat(read(file)).endsWith("a: 1\n");
    }

    static Stream<Arguments> listItemComments() {
        String line2 = "the file's layout outside the keys this write owns would change (line 2)";
        String line3 = "the file's layout outside the keys this write owns would change (line 3)";
        String line4 = "the file's layout outside the keys this write owns would change (line 4)";
        String parseBack = "the rendered text would not parse back";
        return Stream.of(
                Arguments.of("before a plain item", "a:\n- x\n# n\n- y\nc: 1\n", line3),
                Arguments.of("before a mapping item", "a:\n- x\n# n\n- k: v\nc: 1\n", line3),
                Arguments.of("before the first plain item", "a:\n# n\n- x\nc: 1\n", line2),
                Arguments.of("after a block scalar, before a plain item", "a:\n- |-\n  x\n# n\n- y\nc: 1\n", line4),
                Arguments.of("before a flow sequence item", "a:\n- x\n# n\n- [1, 2]\nc: 1\n", parseBack),
                Arguments.of("before a flow mapping item", "a:\n- x\n# n\n- {k: v}\nc: 1\n", parseBack),
                Arguments.of("before the first flow item", "a:\n# n\n- [1, 2]\nc: 1\n", parseBack),
                Arguments.of("after a block scalar, before a flow item",
                        "a:\n- |-\n  x\n# n\n- [1, 2]\nc: 1\n", parseBack),
                Arguments.of("after a flow item", "a:\n- [0]\n# n\n- [1, 2]\nc: 1\n", parseBack),
                Arguments.of("nested flow item", "sec:\n  a:\n  - x\n  # n\n  - [1, 2]\nc: 1\n", parseBack));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listItemComments")
    void aCommentBeforeAListItemRefusesAnotherKeyWithBytesAndMtimeUnchanged(
            String layout, String text, String reason) throws Exception {
        Path file = write(text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000000000000L));
        FileTime modified = Files.getLastModifiedTime(file);
        byte[] bytes = Files.readAllBytes(file);

        OperatorFileWriter.Result result = OperatorFileWriter.write(file,
                OwnedPaths.builder().value(Collections.singletonList("c")).build(), null,
                document -> document.set(Collections.singletonList("c"), 2));

        assertThat(result.outcome()).as(layout).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(result.reason()).as(layout).isEqualTo(reason);
        assertThat(Files.readAllBytes(file)).as("the file keeps its bytes").isEqualTo(bytes);
        assertThat(Files.getLastModifiedTime(file)).as("the file keeps its mtime").isEqualTo(modified);
    }

    @org.junit.jupiter.api.Test
    void owningACommentedListAtTheSameLengthStillRefuses() throws Exception {
        String text = "a:\n- x\n# n\n- [1, 2]\nc: 1\n";
        Path file = write(text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000000000000L));
        FileTime modified = Files.getLastModifiedTime(file);
        byte[] bytes = Files.readAllBytes(file);

        OperatorFileWriter.Result result = OperatorFileWriter.write(file,
                OwnedPaths.builder().value(Collections.singletonList("a")).build(), null,
                document -> document.set(Collections.singletonList("a"),
                        Arrays.asList("z", Arrays.asList(1, 2))));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(result.reason()).isEqualTo("the rendered text would not parse back");
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }

    @org.junit.jupiter.api.Test
    void owningACommentedListAndChangingItsLengthWritesWithoutTheItemComment() throws Exception {
        Path file = write("a:\n- x\n# n\n- [1, 2]\nc: 1\n");

        OperatorFileWriter.Result result = OperatorFileWriter.write(file,
                OwnedPaths.builder().value(Collections.singletonList("a")).build(), null,
                document -> document.set(Collections.singletonList("a"), Collections.singletonList("z")));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(result.reason()).isEmpty();
        assertThat(read(file)).isEqualTo("a:\n- z\nc: 1\n");
    }

    private OperatorFileWriter.Result setA(Path file) throws Exception {
        return OperatorFileWriter.write(file, OwnedPaths.builder().value(Collections.singletonList("a")).build(), null,
                document -> document.set(Collections.singletonList("a"), 2));
    }

    private Path write(String text) throws Exception {
        Path file = directory.resolve("layout.yml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
