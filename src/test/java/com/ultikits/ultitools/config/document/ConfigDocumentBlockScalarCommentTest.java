package com.ultikits.ultitools.config.document;

import static com.ultikits.ultitools.config.document.ConfigDocumentWriteTest.path;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #592: a comment line after a multi-line block scalar ({@code |} or {@code >}) belongs to the key or list item that
 * follows it, exactly as it does after a plain value. SnakeYAML 2.2's scanner reads every such line with a column above
 * 0 as an in-line comment of the scalar; before the fix the renderer wrote it at column 0 (the file's bytes changed, so
 * the write gate refused every later write) and the following key read as uncommented (so the framework inserted its
 * comment a second time).
 */
@DisplayName("ConfigDocument - a comment after a block scalar (#592)")
class ConfigDocumentBlockScalarCommentTest {

    private static final String MAIL_RECALL = "recall:\n"
            + "  # Subject of the in-game recall mail\n"
            + "  subject: '[{SERVER}] Come back to us'\n"
            + "  # Content of the in-game recall mail\n"
            + "  content: |-\n"
            + "    Dear player, {SERVER} misses you!\n"
            + "\n"
            + "    Come back and take a look, we look forward to seeing you again!\n"
            + "\n"
            + "    Sender: {SENDER}\n"
            + "  # Server name shown in recall mails\n"
            + "  server-name: Minecraft Server\n"
            + "email:\n"
            + "  # Subject of the recall email\n"
            + "  recall-subject: '[{SERVER}] We miss you!'\n"
            + "  # Content of the recall email\n"
            + "  recall-content: |-\n"
            + "    Dear {PLAYER},\n"
            + "\n"
            + "    Sender: {SENDER}\n"
            + "  # Whether real email sending is enabled\n"
            + "  enabled: false\n";

    /** Layouts kept byte for byte; the comment is read as the following key's comment ({@code null}: no key to check). */
    static Stream<Arguments> keptLayouts() {
        return Stream.of(
                Arguments.of("column 2, the next key's own column", "a:\n  content: |-\n    one\n  # n\n  b: x\n",
                        path("a", "b"), Collections.singletonList("# n")),
                Arguments.of("a two-line comment", "a:\n  content: |-\n    one\n    two\n  # n1\n  # n2\n  b: x\n",
                        path("a", "b"), Arrays.asList("# n1", "# n2")),
                Arguments.of("folded >-", "a:\n  content: >-\n    one\n  # n\n  b: x\n",
                        path("a", "b"), Collections.singletonList("# n")),
                Arguments.of("keep |+ (its blank line is the value's)", "a:\n  content: |+\n    one\n\n  # n\n  b: x\n",
                        path("a", "b"), Collections.singletonList("# n")),
                Arguments.of("clip |", "a:\n  content: |\n    one\n  # n\n  b: x\n",
                        path("a", "b"), Collections.singletonList("# n")),
                Arguments.of("a header comment on the indicator line as well", "a:\n  content: |- # hdr\n    one\n  # n\n  b: x\n",
                        path("a", "b"), Collections.singletonList("# n")),
                Arguments.of("two levels deep", "a:\n  b:\n    content: |-\n      one\n    # n\n    d: x\n",
                        path("a", "b", "d"), Collections.singletonList("# n")),
                Arguments.of("last in its section, before a top-level key (closes the section)",
                        "a:\n  content: |-\n    one\n  # n\nc: y\n", path("c"), Collections.emptyList()),
                Arguments.of("last in a nested section, comment at the nested column (closes it)",
                        "a:\n  b:\n    content: |-\n      one\n    # n\n  d: x\n", path("a", "d"), Collections.emptyList()),
                Arguments.of("last in a nested section, comment at the next outer key's column",
                        "a:\n  b:\n    content: |-\n      one\n  # n\n  d: x\n", path("a", "d"), Collections.singletonList("# n")),
                Arguments.of("last value of the document", "a:\n  content: |-\n    one\n  # n\n", null, null),
                Arguments.of("last value of the document, two levels deep", "a:\n  b:\n    content: |-\n      one\n    # n\n",
                        null, null),
                Arguments.of("a mapping inside a list item", "a:\n  - k: |-\n      one\n    # n\n    m: x\n", null, null),
                Arguments.of("a comment, a blank line, then the key",
                        "a:\n  content: |-\n    one\n  # n\n\n  b: x\n", path("a", "b"), Arrays.asList("# n", null)),
                Arguments.of("column 0 (never read as the scalar's)", "a:\n  content: |-\n    one\n# n\nc: y\n",
                        path("c"), Collections.singletonList("# n")),
                Arguments.of("UltiMail's mail.yml shape", MAIL_RECALL, path("recall", "server-name"),
                        Collections.singletonList("# Server name shown in recall mails")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keptLayouts")
    void commentAfterABlockScalarIsKeptAndReadAsTheNextKeys(String name, String text, List<String> next, List<String> comment)
            throws Exception {
        ConfigDocument document = ConfigDocument.parse(text);

        assertThat(document.render()).isEqualTo(text);
        if (next != null) {
            assertThat(document.blockCommentAsWritten(next)).isEqualTo(comment);
        }
    }

    @Test
    @DisplayName("UltiMail's shape: the second block scalar's trailing comment belongs to the next key too")
    void mailShapeSecondSection() throws Exception {
        ConfigDocument document = ConfigDocument.parse(MAIL_RECALL);

        assertThat(document.blockCommentAsWritten(path("email", "enabled")))
                .containsExactly("# Whether real email sending is enabled");
        assertThat(document.get(path("email", "recall-content"))).isEqualTo("Dear {PLAYER},\n\nSender: {SENDER}");
    }

    /**
     * Columns other than the next key's own, and a comment before a list item, behave exactly as they do after a plain
     * value: the comment is the next node's and the rendering equals the plain value's rendering with the block put back.
     */
    static Stream<Arguments> plainLikeLayouts() {
        return Stream.of(
                Arguments.of("column 1", "a:\n  content: |-\n    one\n # n\n  b: x\n", "a:\n  content: one\n # n\n  b: x\n",
                        "content: one\n", "content: |-\n    one\n"),
                Arguments.of("column 3", "a:\n  content: |-\n    one\n   # n\n  b: x\n", "a:\n  content: one\n   # n\n  b: x\n",
                        "content: one\n", "content: |-\n    one\n"),
                Arguments.of("a comment, then a column-0 comment", "a:\n  content: |-\n    one\n  # n\n# m\n  b: x\n",
                        "a:\n  content: one\n  # n\n# m\n  b: x\n", "content: one\n", "content: |-\n    one\n"),
                Arguments.of("before a list item", "a:\n  - |-\n    one\n  # n\n  - two\n", "a:\n  - one\n  # n\n  - two\n",
                        "- one\n", "- |-\n    one\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plainLikeLayouts")
    void otherColumnsBehaveAsAfterAPlainValue(String name, String block, String plain, String plainLine, String blockLines)
            throws Exception {
        String plainRendering = ConfigDocument.parse(plain).render();

        assertThat(ConfigDocument.parse(block).render()).isEqualTo(plainRendering.replace(plainLine, blockLines));
    }

    @Test
    @DisplayName("a blank line after a block scalar is the value's (a documented refused layout); the comment stays the next key's")
    void blankLineAfterABlockScalar() throws Exception {
        String text = "a:\n  content: |-\n    one\n\n  # n\n  b: x\n";
        ConfigDocument document = ConfigDocument.parse(text);

        assertThat(document.render()).isEqualTo(text.replace("one\n\n", "one\n"));
        assertThat(document.blockCommentAsWritten(path("a", "b"))).containsExactly("# n");
    }

    /**
     * The #592 mechanism at document level: a key inserted after a block scalar with its framework comment renders
     * the comment at the key's column; read back, the comment is the key's, so the next start finds it and writes
     * nothing, and a language switch replaces it once.
     */
    @Test
    void keyInsertedAfterABlockScalarReadsBackWithItsComment() throws Exception {
        ConfigDocument document = ConfigDocument.parse("recall:\n  content: |-\n    one\n\n    two\nother: 1\n");
        document.set(path("recall", "server-name"), "Minecraft Server");
        document.setFrameworkComment(path("recall", "server-name"), Collections.singletonList("Server name"));
        String written = document.render();
        assertThat(written).isEqualTo("recall:\n  content: |-\n    one\n\n    two\n  # Server name\n  server-name: Minecraft Server\n"
                + "other: 1\n");

        ConfigDocument second = ConfigDocument.parse(written);
        assertThat(second.blockCommentAsWritten(path("recall", "server-name"))).containsExactly("# Server name");
        assertThat(second.render()).isEqualTo(written);

        second.replaceFrameworkComment(path("recall", "server-name"), 1, Collections.singletonList("Server name (zh)"));
        assertThat(second.render()).isEqualTo(written.replace("# Server name\n", "# Server name (zh)\n"));
    }

    /**
     * The adjacent finding of the #592 measurement: in a file without a final line break a block scalar was re-rendered
     * double-quoted, so every gated write to the file was refused. Only a value whose content the missing final line
     * break would change needs the quotes.
     */
    static Stream<Arguments> noFinalLineBreak() {
        return Stream.of(
                Arguments.of("last value", "interval: 300\nmotd: |-\n  first line\n  second line"),
                Arguments.of("last value, nested", "a:\n  interval: 300\n  motd: |-\n    first line\n    second line"),
                Arguments.of("before another key", "motd: |-\n  first line\n  second line\ninterval: 300"),
                Arguments.of("folded, then a comment", "motd: >-\n  first line\n\n  second line\n# note\ninterval: 300"),
                Arguments.of("keep |+ in the middle", "motd: |+\n  first line\n\ninterval: 300"),
                Arguments.of("with a following comment (#592)", "a:\n  motd: |-\n    first\n    second\n  # note\n  interval: 300"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noFinalLineBreak")
    void blockScalarInAFileWithoutAFinalLineBreakIsKept(String name, String text) throws Exception {
        ConfigDocument document = ConfigDocument.parse(text);
        assertThat(document.render()).isEqualTo(text);

        List<String> interval = text.startsWith("a:") ? path("a", "interval") : path("interval");
        document.set(interval, 400);

        assertThat(document.render()).isEqualTo(text.replace("interval: 300", "interval: 400"));
    }

    @Test
    @DisplayName("without a final line break, a last value the missing break would change is still quoted")
    void lastValueEndingWithALineBreakIsStillQuotedWithoutAFinalLineBreak() throws Exception {
        ConfigDocument document = ConfigDocument.parse("first: |-\n  a\n  b\nlast: x");

        document.set(path("last"), "changed\n");
        String rendered = document.render();

        assertThat(rendered).doesNotEndWith("\n");
        assertThat(ConfigDocument.parse(rendered).get(path("last"))).isEqualTo("changed\n");
        assertThat(ConfigDocument.parse(rendered).get(path("first"))).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("a value changed elsewhere keeps the comment after the block scalar")
    void valueChangeElsewhereKeepsTheComment() throws Exception {
        ConfigDocument document = ConfigDocument.parse(MAIL_RECALL);

        document.set(path("email", "enabled"), true);

        assertThat(document.render()).isEqualTo(MAIL_RECALL.replace("enabled: false", "enabled: true"));
    }
}
