package com.ultikits.ultitools.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * <p>
 * It also records, for each literal and each significant token, the innermost named type whose body
 * encloses it (a brace opened right after {@code class}, {@code interface} or {@code enum} and a name),
 * and whether a literal is the whole initializer of a {@code static final String} field.
 */
final class JavaLiteralScanner {

    private static final String TEXT_BLOCK_DELIMITER = "\"\"\"";
    /** Scope entry for a brace outside every named type. */
    private static final String NO_TYPE = "";

    private final String file;
    private final String source;
    private final List<Literal> found = new ArrayList<>();
    /** Significant tokens seen so far: identifiers, single punctuation characters, or a quote for a literal. */
    private final List<String> tokens = new ArrayList<>();
    /** Every significant token with its line and enclosing type, in source order. */
    private final List<Token> tokenList = new ArrayList<>();
    /** Innermost enclosing type name per open brace; a non-type brace repeats its parent's entry. */
    private final Deque<String> scopes = new ArrayDeque<>();
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
        return run(file, source).found;
    }

    /** Every significant token of {@code source}; a string literal appears as a single {@code "}. */
    static List<Token> tokens(String file, String source) {
        return run(file, source).tokenList;
    }

    private static JavaLiteralScanner run(String file, String source) {
        JavaLiteralScanner scanner = new JavaLiteralScanner(file, source);
        while (scanner.pos < source.length()) {
            scanner.step();
        }
        return scanner;
    }

    private String currentScope() {
        return scopes.isEmpty() || NO_TYPE.equals(scopes.peek()) ? null : scopes.peek();
    }

    private void addToken(String text) {
        if ("{".equals(text)) {
            String type = typeNameOpenedHere();
            scopes.push(type != null ? type : scopes.isEmpty() ? NO_TYPE : scopes.peek());
        } else if ("}".equals(text) && !scopes.isEmpty()) {
            scopes.pop();
        }
        tokens.add(text);
        tokenList.add(new Token(text, line, currentScope()));
    }

    /** The name after {@code class}/{@code interface}/{@code enum} in the declaration this brace opens, or null. */
    private String typeNameOpenedHere() {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String token = tokens.get(i);
            if (";".equals(token) || "{".equals(token) || "}".equals(token)) {
                return null;
            }
            boolean keyword = "class".equals(token) || "interface".equals(token) || "enum".equals(token);
            if (keyword && i + 1 < tokens.size() && isIdentifier(tokens.get(i + 1))
                    && (i == 0 || !".".equals(tokens.get(i - 1)))) {
                return tokens.get(i + 1);
            }
        }
        return null;
    }

    /** The name of the {@code static final String} field whose whole initializer starts here, or null. */
    private String constantNameBeforeLiteral() {
        int size = tokens.size();
        if (size < 3 || !"=".equals(tokens.get(size - 1)) || !isIdentifier(tokens.get(size - 2))
                || !"String".equals(tokens.get(size - 3)) || nextSignificantChar() != ';') {
            return null;
        }
        boolean isStatic = false;
        boolean isFinal = false;
        for (int i = size - 4; i >= 0; i--) {
            String token = tokens.get(i);
            if (";".equals(token) || "{".equals(token) || "}".equals(token)) {
                break;
            }
            if ("static".equals(token)) {
                isStatic = true;
            } else if ("final".equals(token)) {
                isFinal = true;
            }
        }
        return isStatic && isFinal ? tokens.get(size - 2) : null;
    }

    /** The next character after {@code pos} that is not whitespace or inside a comment. */
    private char nextSignificantChar() {
        int i = pos;
        while (i < source.length()) {
            if (Character.isWhitespace(source.charAt(i))) {
                i++;
            } else if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else {
                return source.charAt(i);
            }
        }
        return 0;
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
            addToken(String.valueOf(c));
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
        addToken("'");
    }

    private void readIdentifier() {
        int start = pos;
        while (pos < source.length() && Character.isJavaIdentifierPart(source.charAt(pos))) {
            pos++;
        }
        addToken(source.substring(start, pos));
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
        found.add(new Literal(file, startLine, value, callName, receiver, constantNameBeforeLiteral(),
                currentScope()));
        addToken("\"");
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
        /** Name of the {@code static final String} field this literal is the whole initializer of, or null. */
        final String constantName;
        /** Innermost named type whose body encloses this literal, or {@code null} at top level. */
        final String enclosingType;

        Literal(String file, int line, String value, String callName, String receiver, String constantName,
                String enclosingType) {
            this.file = file;
            this.line = line;
            this.value = value;
            this.callName = callName;
            this.receiver = receiver;
            this.constantName = constantName;
            this.enclosingType = enclosingType;
        }

        @Override
        public String toString() {
            return file + ":" + line + " \"" + value.replace("\n", "\\n") + "\"";
        }
    }

    /** One significant token: an identifier, a punctuation character, or {@code "} for a string literal. */
    static final class Token {
        final String text;
        final int line;
        /** Innermost named type whose body encloses this token, or {@code null} at top level. */
        final String enclosingType;

        Token(String text, int line, String enclosingType) {
            this.text = text;
            this.line = line;
            this.enclosingType = enclosingType;
        }
    }
}
