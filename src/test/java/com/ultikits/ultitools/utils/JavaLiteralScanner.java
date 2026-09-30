package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * A small Java-aware scanner that lists every string literal of a source file together with the
 * call it is the direct first argument of, if any. Used by the catalogue invariant (#556), which
 * must not be a same-line grep: a literal in a multi-line call or in a concatenation has to be seen
 * exactly as the compiler sees it, and text inside comments must be ignored.
 * <p>
 * It understands line comments, block comments, character literals, string literals with escapes
 * (including {@code \}{@code uXXXX}) and text blocks. It does not build a syntax tree; "direct
 * argument" means the literal is preceded, ignoring whitespace and comments, by {@code (} which is
 * preceded by an identifier.
 */
final class JavaLiteralScanner {

    private static final String TEXT_BLOCK_DELIMITER = "\"\"\"";

    private final String file;
    private final String source;
    private final List<Literal> found = new ArrayList<>();
    /** Significant tokens seen so far: identifiers, single punctuation characters, or a quote for a literal. */
    private final List<String> tokens = new ArrayList<>();
    private int pos;
    private int line = 1;

    private JavaLiteralScanner(String file, String source) {
        this.file = file;
        this.source = source;
    }

    /** Whether {@code text} holds Han (extensions included), CJK symbols/punctuation or full-width forms. */
    static boolean containsCjkOrFullWidth(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.HAN.equals(Character.UnicodeScript.of(cp))
                || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF));
    }

    static List<Literal> scan(String file, String source) {
        JavaLiteralScanner scanner = new JavaLiteralScanner(file, source);
        while (scanner.pos < source.length()) {
            scanner.step();
        }
        return scanner.found;
    }

    private void step() {
        char c = source.charAt(pos);
        if (c == '\n') {
            line++;
            pos++;
        } else if (Character.isWhitespace(c)) {
            pos++;
        } else if (source.startsWith("//", pos)) {
            skipLineComment();
        } else if (source.startsWith("/*", pos)) {
            skipBlockComment();
        } else if (c == '"') {
            readStringLiteral();
        } else if (c == '\'') {
            skipCharLiteral();
        } else if (Character.isJavaIdentifierStart(c)) {
            readIdentifier();
        } else {
            tokens.add(String.valueOf(c));
            pos++;
        }
    }

    private void skipLineComment() {
        while (pos < source.length() && source.charAt(pos) != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        pos += 2;
        while (pos + 1 < source.length() && !source.startsWith("*/", pos)) {
            if (source.charAt(pos) == '\n') {
                line++;
            }
            pos++;
        }
        pos += 2;
    }

    private void skipCharLiteral() {
        pos++;
        while (pos < source.length() && source.charAt(pos) != '\'') {
            pos += source.charAt(pos) == '\\' ? 2 : 1;
        }
        pos++;
        tokens.add("'");
    }

    private void readIdentifier() {
        int start = pos;
        while (pos < source.length() && Character.isJavaIdentifierPart(source.charAt(pos))) {
            pos++;
        }
        tokens.add(source.substring(start, pos));
    }

    private void readStringLiteral() {
        int startLine = line;
        boolean textBlock = source.startsWith(TEXT_BLOCK_DELIMITER, pos);
        pos += textBlock ? TEXT_BLOCK_DELIMITER.length() : 1;
        String value = readStringBody(textBlock);

        String callName = null;
        String receiver = null;
        int size = tokens.size();
        if (size >= 2 && "(".equals(tokens.get(size - 1)) && isIdentifier(tokens.get(size - 2))) {
            callName = tokens.get(size - 2);
            if (size >= 4 && ".".equals(tokens.get(size - 3)) && isIdentifier(tokens.get(size - 4))) {
                receiver = tokens.get(size - 4);
            }
        }
        found.add(new Literal(file, startLine, value, callName, receiver));
        tokens.add("\"");
    }

    /** Reads up to and including the closing delimiter; returns the decoded value. */
    private String readStringBody(boolean textBlock) {
        StringBuilder value = new StringBuilder();
        while (pos < source.length()) {
            if (textBlock ? source.startsWith(TEXT_BLOCK_DELIMITER, pos) : source.charAt(pos) == '"') {
                pos += textBlock ? TEXT_BLOCK_DELIMITER.length() : 1;
                break;
            }
            char d = source.charAt(pos);
            if (d == '\n') {
                line++;
            }
            if (d == '\\' && pos + 1 < source.length()) {
                readEscape(value);
            } else {
                value.append(d);
                pos++;
            }
        }
        return value.toString();
    }

    private static boolean isIdentifier(String token) {
        return !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0));
    }

    /** Decodes the escape at {@code pos} into {@code value} and moves past it. */
    private void readEscape(StringBuilder value) {
        char e = source.charAt(pos + 1);
        if (e == 'u') {
            int j = pos + 1;
            while (j < source.length() && source.charAt(j) == 'u') {
                j++;
            }
            value.append((char) Integer.parseInt(source.substring(j, j + 4), 16));
            pos = j + 4;
            return;
        }
        value.append(unescape(e));
        pos += 2;
    }

    private static char unescape(char e) {
        switch (e) {
            case 'n':
                return '\n';
            case 't':
                return '\t';
            case 'r':
                return '\r';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 's':
                return ' ';
            default:
                return e;
        }
    }

    /** One string literal of a source file. */
    static final class Literal {
        final String file;
        final int line;
        final String value;
        /** Name of the call this literal is the first argument of, or {@code null}. */
        final String callName;
        /** Identifier directly before the {@code .} of that call, or {@code null}. */
        final String receiver;

        Literal(String file, int line, String value, String callName, String receiver) {
            this.file = file;
            this.line = line;
            this.value = value;
            this.callName = callName;
            this.receiver = receiver;
        }

        @Override
        public String toString() {
            return file + ":" + line + " \"" + value.replace("\n", "\\n") + "\"";
        }
    }
}
