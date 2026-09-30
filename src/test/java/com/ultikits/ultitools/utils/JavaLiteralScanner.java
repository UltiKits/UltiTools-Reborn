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

    private JavaLiteralScanner() {
    }

    /** Whether {@code text} holds Han (extensions included), CJK symbols/punctuation or full-width forms. */
    static boolean containsCjkOrFullWidth(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF));
    }

    static List<Literal> scan(String file, String source) {
        List<Literal> out = new ArrayList<>();
        // Significant tokens seen so far (identifiers, punctuation, or "\"" for a literal).
        List<String> tokens = new ArrayList<>();
        int line = 1;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i += 2;
            } else if (c == '"') {
                int startLine = line;
                StringBuilder value = new StringBuilder();
                boolean textBlock = source.startsWith("\"\"\"", i);
                i += textBlock ? 3 : 1;
                while (i < n) {
                    char d = source.charAt(i);
                    if (textBlock && source.startsWith("\"\"\"", i)) {
                        i += 3;
                        break;
                    }
                    if (!textBlock && d == '"') {
                        i++;
                        break;
                    }
                    if (d == '\n') {
                        line++;
                    }
                    if (d == '\\' && i + 1 < n) {
                        i = readEscape(source, i, value);
                        continue;
                    }
                    value.append(d);
                    i++;
                }
                String callName = null;
                String receiver = null;
                int size = tokens.size();
                if (size >= 2 && "(".equals(tokens.get(size - 1)) && isIdentifier(tokens.get(size - 2))) {
                    callName = tokens.get(size - 2);
                    if (size >= 4 && ".".equals(tokens.get(size - 3)) && isIdentifier(tokens.get(size - 4))) {
                        receiver = tokens.get(size - 4);
                    }
                }
                out.add(new Literal(file, startLine, value.toString(), callName, receiver));
                tokens.add("\"");
            } else if (c == '\'') {
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                tokens.add("'");
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                tokens.add(source.substring(start, i));
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }

    private static boolean isIdentifier(String token) {
        return !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0));
    }

    /** Decodes the escape at {@code i} into {@code value}; returns the index after it. */
    private static int readEscape(String source, int i, StringBuilder value) {
        char e = source.charAt(i + 1);
        switch (e) {
            case 'n':
                value.append('\n');
                return i + 2;
            case 't':
                value.append('\t');
                return i + 2;
            case 'r':
                value.append('\r');
                return i + 2;
            case 'b':
                value.append('\b');
                return i + 2;
            case 'f':
                value.append('\f');
                return i + 2;
            case 's':
                value.append(' ');
                return i + 2;
            case 'u': {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) == 'u') {
                    j++;
                }
                value.append((char) Integer.parseInt(source.substring(j, j + 4), 16));
                return j + 4;
            }
            default:
                value.append(e);
                return i + 2;
        }
    }
}
