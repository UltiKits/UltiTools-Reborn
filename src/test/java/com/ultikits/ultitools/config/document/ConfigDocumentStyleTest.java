package com.ultikits.ultitools.config.document;

import static com.ultikits.ultitools.config.document.ConfigDocumentWriteTest.path;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 17-56 Task 2: a write keeps the file's own style - line terminator, list indicator indentation,
 * mapping indentation, unicode escaping (and the case of its hex digits), line width, byte order mark and
 * final line break - so new and changed lines look like the lines around them.
 */
@DisplayName("ConfigDocument - per-document style")
class ConfigDocumentStyleTest {

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> scalarLineBreakInventory() {
        return java.util.stream.Stream.of("LF", "CR", "CRLF", "NEL", "LS", "PS").flatMap(character ->
                java.util.stream.Stream.of(true, false).flatMap(finalNewline ->
                        java.util.stream.Stream.of("anchor-unrelated", "new-set", "new-key", "plain-noop")
                                .map(action -> org.junit.jupiter.params.provider.Arguments.of(character, finalNewline, action))));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "linebreak {0}, EOF {1}, action {2}")
    @org.junit.jupiter.params.provider.MethodSource("scalarLineBreakInventory")
    void everyScalarLineBreakPreservesContent(String character, boolean finalNewline, String action) throws Exception {
        String separator;
        String escape;
        switch (character) {
            case "LF": separator = "\n"; escape = "\\n"; break;
            case "CR": separator = "\r"; escape = "\\r"; break;
            case "CRLF": separator = "\r\n"; escape = "\\r\\n"; break;
            case "NEL": separator = "\u0085"; escape = "\\N"; break;
            case "LS": separator = " "; escape = "\\L"; break;
            case "PS": separator = " "; escape = "\\P"; break;
            default: throw new IllegalArgumentException(character);
        }
        String value = "hello" + separator + "world";
        String prefix = "anchor-unrelated".equals(action) ? "base: &base [x]\ncopy: *base\n" : "";
        String text = "# header\n\n" + prefix + "message: \"hello" + escape
                + "world\" # message comment\nnext: 2 # next comment" + (finalNewline ? "\n" : "");
        ConfigDocument document = ConfigDocument.parse(text);
        java.util.Map<String, Object> expected = document.toPlain();
        if ("anchor-unrelated".equals(action)) {
            document.set(path("next"), 3); expected.put("next", 3);
        } else if ("new-set".equals(action)) {
            document.set(path("added"), value); expected.put("added", value);
        } else if ("new-key".equals(action)) {
            document.set(path(value), 3); expected.put(value, 3);
        }
        String rendered = document.render();
        GoldenCorpus.assertContent(rendered, expected);
        assertThat(GoldenCorpus.comments(rendered)).containsExactlyElementsOf(GoldenCorpus.comments(text));
        assertThat(rendered.endsWith("\n")).isEqualTo(finalNewline);
        assertThat(document.render()).isEqualTo(rendered);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> commentShapeInventory() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("shared-alias-positions", "# key block\nx: &shared\n  a: 1 # inline\n  # end\nreference: *shared\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("shared-alias-distinct-notes", "x: &shared\n  a: 1\n  # note\nreference: *shared\nother:\n  a: 1\n  # note\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-root-inline", "{} # inline\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-root-all", "# block\n{} # inline\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("comments-only", "# bare\n\n# tail\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-map", "# block\nx: {} # inline\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-sequence", "# block\nx: [] # inline\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("flow-map", "x: {a: 1} # inline\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("flow-sequence", "x: [a, b] # inline\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("block-map", "x:\n  # block\n  a: 1 # inline\n  # section end\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("block-sequence", "x:\n  # block\n  - a # inline\n  # sequence end\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("key-inline-map", "x: # key inline\n  a: 1\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("key-inline-sequence", "x: # key inline\n  - a\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("null-scalar", "x: # null inline\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("literal-scalar", "x: | # scalar inline\n  a\n# end\n"),
                org.junit.jupiter.params.provider.Arguments.of("plain-scalar", "# scalar block\nx: a # scalar inline\n# scalar end\n"),
                org.junit.jupiter.params.provider.Arguments.of("value-block-empty", "x:\n  # value block\n  [] # value inline\ny: 2\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-map-block-node", "x: {}\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-map-flow-node", "x: {}\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-sequence-block-node", "x: []\n"),
                org.junit.jupiter.params.provider.Arguments.of("empty-sequence-flow-node", "x: []\n"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "comment shape: {0}")
    @org.junit.jupiter.params.provider.MethodSource("commentShapeInventory")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void everyLegitimateCommentPositionSurvivesRendering(String shape, String text) throws Exception {
        ConfigDocument document = ConfigDocument.parse(text);
        java.util.List<String> expected = GoldenCorpus.comments(text);
        if (shape.endsWith("-node")) {
            java.lang.reflect.Field field = ConfigDocument.class.getDeclaredField("root");
            field.setAccessible(true);
            org.yaml.snakeyaml.nodes.MappingNode root = (org.yaml.snakeyaml.nodes.MappingNode) field.get(document);
            org.yaml.snakeyaml.nodes.Node value = root.getValue().get(0).getValueNode();
            ((org.yaml.snakeyaml.nodes.CollectionNode<?>) value).setFlowStyle(shape.contains("-flow-")
                    ? org.yaml.snakeyaml.DumperOptions.FlowStyle.FLOW : org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
            value.setBlockComments(Collections.singletonList(new org.yaml.snakeyaml.comments.CommentLine(null, null,
                    " block", org.yaml.snakeyaml.comments.CommentType.BLOCK)));
            value.setInLineComments(Collections.singletonList(new org.yaml.snakeyaml.comments.CommentLine(null, null,
                    " inline", org.yaml.snakeyaml.comments.CommentType.IN_LINE)));
            value.setEndComments(Collections.singletonList(new org.yaml.snakeyaml.comments.CommentLine(null, null,
                    " end", org.yaml.snakeyaml.comments.CommentType.BLOCK)));
            expected.addAll(Arrays.asList(" block", " inline", " end"));
        }
        String noop = document.render();
        assertThat(GoldenCorpus.comments(noop)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ConfigDocument.parse(noop).toPlain()).isEqualTo(document.toPlain());
        assertThat(document.render()).isEqualTo(noop);
        document.set(path("unrelated"), 3);
        String written = document.render();
        assertThat(GoldenCorpus.comments(written)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ConfigDocument.parse(written).toPlain()).isEqualTo(document.toPlain());
        assertThat(document.render()).isEqualTo(written);
        if (!shape.endsWith("-node") && !shape.startsWith("empty-root") && !"comments-only".equals(shape)) {
            ConfigDocument anchored = ConfigDocument.parse("base: &base {a: 1}\n" + text);
            anchored.set(path("unrelated"), 3);
            String expanded = anchored.render();
            assertThat(GoldenCorpus.comments(expanded)).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(ConfigDocument.parse(expanded).toPlain()).isEqualTo(anchored.toPlain());
            assertThat(anchored.render()).isEqualTo(expanded);
        }
    }

    @Test
    void emitterIndentClampMatchesStrictBytecodeBounds() throws Exception {
        for (int indent : new int[]{1, 10}) {
            String text = "a:\n" + String.join("", Collections.nCopies(indent, " ")) + "b: 1\n";
            org.yaml.snakeyaml.nodes.MappingNode root = (org.yaml.snakeyaml.nodes.MappingNode)
                    new org.yaml.snakeyaml.Yaml(ConfigDocument.loaderOptions()).compose(new java.io.StringReader(text));
            assertThat(DocumentStyle.detect(text, root).dumperOptions().getIndent()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a CRLF file stays CRLF, for changed and added lines alike")
    void crlfStaysCrlf() throws Exception {
        String text = "# Economy\r\n\r\neconomy:\r\n  start: 100.0 # dollars\r\n  banks:\r\n    - central\r\n";
        ConfigDocument document = ConfigDocument.parse(text);

        document.set(path("economy", "start"), 250.0);
        document.set(path("economy", "currency"), "$");
        document.setFrameworkComment(path("economy", "currency"), Collections.singletonList("Currency symbol"));

        assertThat(document.render()).isEqualTo("# Economy\r\n\r\neconomy:\r\n  start: 250.0 # dollars\r\n  banks:\r\n"
                + "    - central\r\n  # Currency symbol\r\n  currency: $\r\n");
    }

    @Test
    @DisplayName("indented list items stay indented, also in a new list; 6.2's unindented items stay unindented")
    void listIndicatorIndentation() throws Exception {
        ConfigDocument indented = ConfigDocument.parse("worlds:\n  list:\n    - world\n");
        ConfigDocument unindented = ConfigDocument.parse("worlds:\n  list:\n  - world\n");

        indented.set(path("worlds", "other"), Arrays.asList("a", "b"));
        unindented.set(path("worlds", "other"), Arrays.asList("a", "b"));

        assertThat(indented.render()).isEqualTo("worlds:\n  list:\n    - world\n  other:\n    - a\n    - b\n");
        assertThat(unindented.render()).isEqualTo("worlds:\n  list:\n  - world\n  other:\n  - a\n  - b\n");
    }

    @Test
    @DisplayName("a four-space file stays four-space")
    void fourSpaceIndentation() throws Exception {
        ConfigDocument document = ConfigDocument.parse("settings:\n    general:\n        enabled: true\n");

        document.set(path("settings", "general", "name"), "x");
        document.set(path("settings", "other", "key"), 1);

        assertThat(document.render())
                .isEqualTo("settings:\n    general:\n        enabled: true\n        name: x\n    other:\n        key: 1\n");
    }

    @Test
    @DisplayName("a pure-ASCII file with \\u escapes keeps escapes and their hex case; non-ASCII values still read back")
    void unicodeEscapesAreKept() throws Exception {
        String upper = "mappings:\n  \":smile:\": \"\\u263A\"\n";
        String lower = "mappings:\n  accent: \"caf\\u00e9\"\n";
        ConfigDocument upperDocument = ConfigDocument.parse(upper);
        ConfigDocument lowerDocument = ConfigDocument.parse(lower);

        upperDocument.set(path("mappings", ":dark:"), "\u263B");
        lowerDocument.set(path("mappings", "word"), "na\u00efve");
        lowerDocument.setFrameworkComment(path("mappings", "word"), Collections.singletonList("\u8868\u60c5"));

        assertThat(upperDocument.render()).isEqualTo(upper + "  ':dark:': \"\\u263B\"\n");
        String lowerRendered = lowerDocument.render();
        assertThat(lowerRendered).startsWith(lower).contains("  word: \"na\\u00efve\"\n");
        ConfigDocument reread = ConfigDocument.parse(lowerRendered);
        assertThat(reread.get(path("mappings", "word"))).isEqualTo("na\u00efve");
        assertThat(reread.blockComment(path("mappings", "word"))).containsExactly("\u8868\u60c5");
    }

    @Test
    @DisplayName("a file holding raw non-ASCII text keeps writing it raw")
    void rawUnicodeStaysRaw() throws Exception {
        ConfigDocument document = ConfigDocument.parse("symbols:\n  heart: \u2764\n");

        document.set(path("symbols", "star"), "\u2605");

        assertThat(document.render()).isEqualTo("symbols:\n  heart: \u2764\n  star: \u2605\n");
    }

    @Test
    @DisplayName("a 6.2 file with folded long strings keeps the folding; a hand-written long line stays one line")
    void lineWidth() throws Exception {
        String folded = "chat:\n  format: x\n  long: '&eWelcome to the server, {player}! Please read the rules at /rules before\n"
                + "    you start building anything here.'\n";
        String longLine = "messages:\n  first: x\n  motd: This is a long plain line that the operator wrote by hand on one single"
                + " line and it goes well past eighty columns.\n";
        ConfigDocument foldedDocument = ConfigDocument.parse(folded);
        ConfigDocument longLineDocument = ConfigDocument.parse(longLine);

        foldedDocument.set(path("chat", "format"), "y");
        longLineDocument.set(path("messages", "first"), "y");

        assertThat(foldedDocument.render()).isEqualTo(folded.replace("format: x", "format: y"));
        assertThat(longLineDocument.render()).isEqualTo(longLine.replace("first: x", "first: y"));
    }

    @Test
    @DisplayName("a byte order mark and a missing final line break are kept")
    void byteOrderMarkAndFinalLineBreak() throws Exception {
        ConfigDocument withBom = ConfigDocument.parse("\uFEFFkey: value\n");
        ConfigDocument unterminated = ConfigDocument.parse("key: value\nother: 2");

        withBom.set(path("key"), "changed");
        unterminated.set(path("key"), "changed");

        assertThat(withBom.render()).isEqualTo("\uFEFFkey: changed\n");
        assertThat(unterminated.render()).isEqualTo("key: changed\nother: 2");
    }

    @Test
    @DisplayName("an indentation SnakeYAML cannot emit (more than 10 columns) falls back to the default instead of failing")
    void unsupportedIndentationFallsBack() throws Exception {
        ConfigDocument document = ConfigDocument.parse("a:\n            b: 1\nlist:\n            - x\n");

        document.set(path("a", "c"), 2);

        assertThat(ConfigDocument.parse(document.render()).get(path("a", "c"))).isEqualTo(2);
        assertThat(ConfigDocument.parse(document.render()).get(path("list"))).isEqualTo(Collections.singletonList("x"));
    }

    @Test
    void noFinalNewlinePreservesLiteralAndFoldedStringContent() throws Exception {
        for (String scalarStyle : new String[]{"|-", ">-"}) {
            for (String value : new String[]{"hello\n", "hello\n\n", "hello\r\n", "hello\r"}) {
                ConfigDocument document = ConfigDocument.parse("x: " + scalarStyle + "\n  a");
                document.set(path("x"), value);
                String rendered = document.render();
                assertThat(ConfigDocument.parse(rendered).get(path("x"))).isEqualTo(value);
                assertThat(rendered).doesNotEndWith("\n").doesNotEndWith("\r");
                assertThat(document.render()).isEqualTo(rendered);
            }
        }
    }

    @Test
    void noFinalNewlinePreservesNestedListAndSharedAnchorStringsAndComments() throws Exception {
        String source = "# Header\n\nshared: &text |+\n  hello\n\n"
                + "# Alias\nmirror: *text\nnested:\n  # List\n  values:\n  - *text\nlast: x";
        ConfigDocument document = ConfigDocument.parse(source);
        String rendered = document.render();
        assertThat(ConfigDocument.parse(rendered).toPlain()).isEqualTo(document.toPlain());
        assertThat(GoldenCorpus.comments(rendered)).containsExactlyInAnyOrderElementsOf(GoldenCorpus.comments(source));
        assertThat(rendered).contains("&text", "*text").doesNotEndWith("\n");
        assertThat(document.render()).isEqualTo(rendered);
        document.set(path("last"), "changed\n\n");
        rendered = document.render();
        assertThat(ConfigDocument.parse(rendered).toPlain()).isEqualTo(document.toPlain());
        assertThat(GoldenCorpus.comments(rendered)).containsExactlyInAnyOrderElementsOf(GoldenCorpus.comments(source));
    }

    @Test
    void bomAndCrlfWithoutFinalNewlinePreserveNewMultilineListAndScalarValues() throws Exception {
        ConfigDocument document = ConfigDocument.parse("﻿nested:\r\n  value: old\r\nlast: x");
        document.set(path("nested", "value"), "hello\r\n\n");
        document.set(path("nested", "list"), Arrays.asList("first\n", "second\r\n"));
        String rendered = document.render();
        assertThat(rendered).startsWith("﻿").doesNotEndWith("\n").doesNotEndWith("\r");
        assertThat(rendered.replace("\r\n", "")).doesNotContain("\n", "\r");
        assertThat(ConfigDocument.parse(rendered).toPlain()).isEqualTo(document.toPlain());
        assertThat(document.render()).isEqualTo(rendered);
    }

    @Test
    @DisplayName("a blank line inside a nested mapping is written empty, not as indentation")
    void blankLinesCarryNoIndentation() throws Exception {
        String text = "menu:\n  items:\n    a: 1\n\n    b: 2\n";
        ConfigDocument document = ConfigDocument.parse(text);

        document.set(path("menu", "items", "a"), 3);

        assertThat(document.render()).isEqualTo(text.replace("a: 1", "a: 3"));
    }
}
