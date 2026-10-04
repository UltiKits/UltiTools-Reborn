package com.ultikits.ultitools.config.document;

import static com.ultikits.ultitools.config.document.ConfigDocumentWriteTest.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 17-56 Task 2: comments in every position survive a value change, and the framework writes a comment
 * only through {@link ConfigDocument#setFrameworkComment}, which replaces only that key's own comment lines
 * (#542's token comments use it in plan 17-58).
 */
@DisplayName("ConfigDocument - comments")
class ConfigDocumentCommentTest {

    static final String COMMENTED = "# Server configuration\n"
            + "# edited by the operator\n"
            + "\n"
            + "# Chat settings\n"
            + "chat:\n"
            + "  # The chat format\n"
            + "  format: \"&7{player}: {message}\" # inline comment\n"
            + "  rate: 1.50\n"
            + "  worlds:\n"
            + "  - world # the overworld\n"
            + "  - world_nether\n"
            + "  # end of the list\n"
            + "  last: true\n"
            + "  # comment after the last key of chat\n"
            + "\n"
            + "# Economy settings\n"
            + "economy:\n"
            + "  start: 100\n"
            + "  nested:\n"
            + "    depth: 2\n"
            + "    # comment after the last key of nested\n"
            + "# end of file comment\n";

    @Test
    void removingLastSectionKeyKeepsTrailingComment() throws Exception {
        ConfigDocument document = ConfigDocument.parse("chat:\n  last: true\n  # operator note\neconomy: 1\n");
        document.remove(path("chat", "last"));
        assertCommentContract(document.render(), "chat: {}\n# operator note\neconomy: 1\n");
    }

    @Test
    void removingLastSectionKeyAtEofKeepsTrailingComment() throws Exception {
        ConfigDocument document = ConfigDocument.parse("chat:\n  last: true\n  # operator note\n");
        document.remove(path("chat", "last"));
        assertCommentContract(document.render(), "chat: {}\n# operator note\n");
    }

    @Test
    void removingLastOfTwoKeysTransfersTrailingCommentToRemainingValue() throws Exception {
        ConfigDocument document = ConfigDocument.parse(
                "chat:\n  first: 0\n  last: true\n  # operator note\neconomy: 1\n");
        document.remove(path("chat", "last"));
        assertCommentContract(document.render(), "chat:\n  first: 0\n  # operator note\neconomy: 1\n");
    }

    @Test
    void removingLastOfTwoKeysAtEofPreservesSectionAndFileCommentOrder() throws Exception {
        ConfigDocument document = ConfigDocument.parse(
                "chat:\n  first: 0\n  last: true\n  # section end\n# file end\n");
        document.remove(path("chat", "last"));
        assertCommentContract(document.render(), "chat:\n  first: 0\n  # section end\n# file end\n");
        assertThat(document.render().indexOf("# section end")).isLessThan(document.render().indexOf("# file end"));
    }

    @Test
    void anchoredFallbackKeepsScalarAndListEndComments() throws Exception {
        String text = "section: &s\n  list:\n  - first # item\n  # list end\n  scalar: value\n  # scalar end\nnext: 1\ncopy: *s\n";
        ConfigDocument document = ConfigDocument.parse(text);
        document.set(path("next"), 2);
        assertThat(document.render()).contains("# item", "# list end", "# scalar end");
        assertThat(ConfigDocument.parse(document.render()).get(path("next"))).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "list comments: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"leaf-equal", "leaf-append", "leaf-shrink",
            "map-equal", "map-append", "map-shrink", "anchored-leaf-equal", "anchored-leaf-append",
            "anchored-leaf-shrink", "anchored-map-equal", "anchored-map-append", "anchored-map-shrink"})
    void listItemCommentsRequireUnchangedLength(String action) throws Exception {
        String text = "section:\n  # list key\n  items:\n  - first # first inline\n"
                + "  # second block\n  - second # second inline\n  # list end\n"
                + "  # sibling key\n  sibling: true # sibling inline\n";
        if (action.startsWith("anchored-")) {
            text = "anchor: &base {value: 1}\nreference: *base\n" + text;
        }
        java.util.List<String> values = action.endsWith("append") ? Arrays.asList("first", "second", "third")
                : action.endsWith("shrink") ? Collections.singletonList("second") : Arrays.asList("first", "changed");
        ConfigDocument document = ConfigDocument.parse(text);
        if (action.contains("map-")) {
            java.util.Map<String, Object> section = new java.util.LinkedHashMap<>();
            section.put("items", values);
            section.put("sibling", true);
            document.set(path("section"), section);
        } else {
            document.set(path("section", "items"), values);
        }
        String rendered = document.render();
        assertThat(rendered).contains("# list key", "# sibling key", "# sibling inline", "# list end");
        if (action.endsWith("equal")) {
            assertThat(rendered).contains("# first inline", "# second block", "# second inline");
        } else {
            assertThat(rendered).doesNotContain("# first inline", "# second block", "# second inline");
        }
        assertThat(ConfigDocument.parse(rendered).get(path("section", "items"))).isEqualTo(values);
        assertThat(document.render()).isEqualTo(rendered);
    }

    @Test
    @DisplayName("header, block, inline, list-item, end-of-section and end-of-file comments survive value changes")
    void commentsSurviveValueChanges() throws Exception {
        ConfigDocument document = ConfigDocument.parse(COMMENTED);

        document.set(path("chat", "format"), "&a{player}: {message}");
        document.set(path("chat", "rate"), 2.0);
        document.set(path("chat", "last"), false);
        document.set(path("economy", "nested", "depth"), 3);

        assertCommentContract(document.render(), COMMENTED
                .replace("&7{player}", "&a{player}")
                .replace("rate: 1.50", "rate: 2.0")
                .replace("last: true", "last: false")
                .replace("depth: 2", "depth: 3"));
    }

    private static void assertCommentContract(String rendered, String expected) throws ConfigParseException {
        GoldenCorpus.assertContent(rendered, ConfigDocument.parse(expected).toPlain());
        GoldenCorpus.assertStyle(expected, rendered);
        assertThat(GoldenCorpus.comments(rendered)).containsExactlyInAnyOrderElementsOf(GoldenCorpus.comments(expected));
    }

    @Test
    @DisplayName("blockComment reads a key's own comment: text after '# ', null for a blank line; the header is the file's")
    void blockCommentReadsTheKeysComment() throws Exception {
        ConfigDocument document = ConfigDocument.parse(COMMENTED);

        assertThat(document.blockComment(path("chat"))).containsExactly("Chat settings");
        assertThat(document.blockComment(path("chat", "format"))).containsExactly("The chat format");
        assertThat(document.blockComment(path("economy"))).containsExactly(null, "Economy settings");
        assertThat(document.blockComment(path("chat", "rate"))).isEmpty();
        assertThat(document.blockComment(path("absent"))).isEmpty();
    }

    @Test
    @DisplayName("setFrameworkComment replaces only that key's comment lines, keeping a leading blank line")
    void setFrameworkCommentReplacesOnlyThatComment() throws Exception {
        ConfigDocument document = ConfigDocument.parse(COMMENTED);

        document.setFrameworkComment(path("chat", "format"), Collections.singletonList("Das Chatformat"));
        document.setFrameworkComment(path("economy"), Arrays.asList("Economy", "two lines"));
        document.setFrameworkComment(path("chat", "rate"), Collections.singletonList("New comment"));

        assertCommentContract(document.render(), COMMENTED
                .replace("  # The chat format\n", "  # Das Chatformat\n")
                .replace("# Economy settings\n", "# Economy\n# two lines\n")
                .replace("  rate: 1.50\n", "  # New comment\n  rate: 1.50\n"));
    }

    @Test
    @DisplayName("an empty comment list removes the comment lines; an absent key is refused")
    void emptyCommentAndAbsentKey() throws Exception {
        ConfigDocument document = ConfigDocument.parse(COMMENTED);

        document.setFrameworkComment(path("chat", "format"), Collections.<String>emptyList());

        assertCommentContract(document.render(), COMMENTED.replace("  # The chat format\n", ""));
        assertThatThrownBy(() -> document.setFrameworkComment(path("chat", "absent"), Collections.singletonList("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[chat, absent]");
    }

    @Test
    @DisplayName("comment text is split at every YAML line break and characters YAML forbids are dropped")
    void commentTextIsMadeSafe() throws Exception {
        ConfigDocument document = ConfigDocument.parse("a: 1\n");

        document.setFrameworkComment(path("a"), Collections.singletonList(
                "one\r\ntwo\rthree\nfour\u0085five\u2028six\u2029seven x\u0000y\u0007z\uFFFE"));

        ConfigDocument reread = ConfigDocument.parse(document.render());
        assertThat(reread.blockComment(path("a")))
                .containsExactly("one", "two", "three", "four", "five", "six", "seven xyz");
        assertThat(reread.get(path("a"))).isEqualTo(1);
    }

    /**
     * #604: the framework rewrites only the trailing run of a key's comment it identified as its own; every line
     * above that run - an operator's note, blank lines - is kept as it is (maintainer decision 2026-10-04).
     */
    @Test
    @DisplayName("replaceFrameworkComment replaces only the trailing run and keeps every line above it")
    void replaceFrameworkCommentKeepsTheLinesAboveTheRun() throws Exception {
        ConfigDocument document = ConfigDocument.parse("a: 1\n\n# operator\n\n# old one\n# old two\nkey: 2\n");

        document.replaceFrameworkComment(path("key"), 2, Collections.singletonList("new\nframework"));

        assertThat(document.render()).isEqualTo("a: 1\n\n# operator\n\n# new\n# framework\nkey: 2\n");
        assertThat(ConfigDocument.parse(document.render()).blockComment(path("key")))
                .containsExactly(null, "operator", null, "new", "framework");
    }

    @Test
    @DisplayName("replaceFrameworkComment with an empty run appends below a key that has no comment")
    void replaceFrameworkCommentWithAnEmptyRunAppends() throws Exception {
        ConfigDocument document = ConfigDocument.parse("a: 1\nkey: 2\n");

        document.replaceFrameworkComment(path("key"), 0, Collections.singletonList("framework"));

        assertThat(document.render()).isEqualTo("a: 1\n# framework\nkey: 2\n");
    }

    @Test
    @DisplayName("replaceFrameworkComment refuses a run longer than the comment or one that includes a blank line")
    void replaceFrameworkCommentRefusesAnImpossibleRun() throws Exception {
        ConfigDocument document = ConfigDocument.parse("a: 1\n# note\n\n# framework\nkey: 2\n");

        assertThatThrownBy(() -> document.replaceFrameworkComment(path("key"), 5, Collections.singletonList("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document.replaceFrameworkComment(path("key"), 2, Collections.singletonList("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document.replaceFrameworkComment(path("absent"), 0, Collections.singletonList("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.render()).isEqualTo("a: 1\n# note\n\n# framework\nkey: 2\n");
    }

    @Test
    @DisplayName("a framework comment written into a new document reads back")
    void frameworkCommentReadsBack() throws Exception {
        ConfigDocument document = ConfigDocument.empty();
        document.set(path("section", "key"), "v");

        document.setFrameworkComment(path("section", "key"), Arrays.asList("First", "Second"));

        String rendered = document.render();
        assertThat(rendered).isEqualTo("section:\n  # First\n  # Second\n  key: v\n");
        assertThat(ConfigDocument.parse(rendered).blockComment(path("section", "key"))).containsExactly("First", "Second");
    }
}
