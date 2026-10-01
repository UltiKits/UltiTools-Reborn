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
    @DisplayName("a blank line inside a nested mapping is written empty, not as indentation")
    void blankLinesCarryNoIndentation() throws Exception {
        String text = "menu:\n  items:\n    a: 1\n\n    b: 2\n";
        ConfigDocument document = ConfigDocument.parse(text);

        document.set(path("menu", "items", "a"), 3);

        assertThat(document.render()).isEqualTo(text.replace("a: 1", "a: 3"));
    }
}
