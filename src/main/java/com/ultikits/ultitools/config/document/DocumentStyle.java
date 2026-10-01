package com.ultikits.ultitools.config.document;

import java.util.ArrayDeque;
import java.util.Deque;

import org.jetbrains.annotations.ApiStatus;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * The layout facts of one config file that SnakeYAML's emitter needs to write the file the way it already
 * looks. Each fact is read from the file once, when it is parsed; a mixed file gets one style per fact.
 * <ul>
 *     <li><b>Line terminator</b> - the file's first line break ({@code \r\n}, {@code \r} or {@code \n});
 *     {@code \n} for a file without one.</li>
 *     <li><b>Mapping indentation</b> - the column offset of the first nested block mapping's keys from their
 *     parent key; 2 when there is none (Bukkit's default).</li>
 *     <li><b>List indicator indentation</b> - the column offset of the first block sequence's {@code -} from
 *     the key that holds it: 0 for 6.2-written files ({@code - x} under the key), the offset for bundled
 *     files that indent items ({@code    - x}).</li>
 *     <li><b>Unicode escaping</b> - a file whose scalars hold no raw non-ASCII character and at least one
 *     {@code \}{@code u}, {@code \}{@code U} or {@code \}{@code x} escape keeps writing non-ASCII text as
 *     escapes. Escapes follow the file's own form: the hex digits in the case the file uses, and a character
 *     up to U+00FF as a four-digit {@code \}{@code u} escape unless the file itself uses {@code \}{@code x}
 *     (SnakeYAML's emitter would write {@code \}{@code x}).</li>
 *     <li><b>Line width</b> - 80 (Bukkit's default, the width 6.2 folded long strings at) when the file holds
 *     a quoted or plain scalar spanning more than one line; unlimited otherwise, so a long line written by
 *     hand stays one line.</li>
 *     <li><b>Byte order mark</b> and <b>final line break</b> - kept as found.</li>
 * </ul>
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class DocumentStyle {

    /** Bukkit's {@code YamlConfigurationOptions} default width, which 6.2 wrote with. */
    static final int BUKKIT_WIDTH = 80;

    private static final int DEFAULT_INDENT = 2;
    private static final int MAX_INDENT = 10;

    private final String lineBreak;
    private final int indent;
    private final int indicatorIndent;
    private final boolean escapeUnicode;
    private final boolean upperCaseHex;
    private final boolean latin1AsUnicodeEscape;
    private final int width;
    private final boolean byteOrderMark;
    private final boolean finalLineBreak;

    private DocumentStyle(String lineBreak, int indent, int indicatorIndent, boolean escapeUnicode, boolean upperCaseHex,
                          boolean latin1AsUnicodeEscape, int width, boolean byteOrderMark, boolean finalLineBreak) {
        this.lineBreak = lineBreak;
        this.indent = indent;
        this.indicatorIndent = indicatorIndent;
        this.escapeUnicode = escapeUnicode;
        this.upperCaseHex = upperCaseHex;
        this.latin1AsUnicodeEscape = latin1AsUnicodeEscape;
        this.width = width;
        this.byteOrderMark = byteOrderMark;
        this.finalLineBreak = finalLineBreak;
    }

    /**
     * The style of a new file: what Bukkit writes, without a line width limit.
     *
     * @return the default style
     */
    public static DocumentStyle defaults() {
        return new DocumentStyle("\n", DEFAULT_INDENT, 0, false, false, false, Integer.MAX_VALUE, false, true);
    }

    /**
     * Reads the style of a parsed file.
     *
     * @param text the file's text as read
     * @param root the composed top-level mapping, or {@code null} for a file without content
     * @return the file's style
     */
    static DocumentStyle detect(String text, MappingNode root) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        Layout layout = new Layout(body);
        if (root != null) {
            layout.walk(root);
        }
        // SnakeYAML's emitter accepts an indentation of 1 to 10 and an indicator indentation of 0 to 9; a file
        // indented otherwise is written with the default rather than failing.
        int indent = layout.indent >= 1 && layout.indent <= MAX_INDENT ? layout.indent : DEFAULT_INDENT;
        int indicatorIndent = layout.indicatorIndent >= 0 && layout.indicatorIndent < MAX_INDENT ? layout.indicatorIndent : 0;
        return new DocumentStyle(detectLineBreak(body),
                indent,
                indicatorIndent,
                layout.escapes && !layout.rawNonAscii,
                layout.upperHex && !layout.lowerHex,
                layout.unicodeEscapes && !layout.latin1Escapes,
                layout.multiLineFlowScalar ? BUKKIT_WIDTH : Integer.MAX_VALUE,
                text.startsWith("\uFEFF"),
                body.isEmpty() || body.endsWith("\n") || body.endsWith("\r"));
    }

    String lineBreak() {
        return lineBreak;
    }

    boolean upperCaseHex() {
        return upperCaseHex;
    }

    boolean latin1AsUnicodeEscape() {
        return latin1AsUnicodeEscape;
    }

    boolean byteOrderMark() {
        return byteOrderMark;
    }

    boolean finalLineBreak() {
        return finalLineBreak;
    }

    /**
     * The emitter options for this style, with Bukkit's flow style and comment processing.
     *
     * @return fresh dumper options
     */
    DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setProcessComments(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(indent);
        if (indicatorIndent > 0) {
            options.setIndicatorIndent(indicatorIndent);
            options.setIndentWithIndicator(true);
        }
        options.setWidth(width);
        options.setAllowUnicode(!escapeUnicode);
        options.setNonPrintableStyle(DumperOptions.NonPrintableStyle.ESCAPE);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        return options;
    }

    private static String detectLineBreak(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return "\n";
            }
            if (c == '\r') {
                return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
            }
        }
        return "\n";
    }

    /** One pass over the composed tree, recording the first occurrence of each layout fact. */
    private static final class Layout {

        private final String text;
        private int indent = -1;
        private int indicatorIndent = -1;
        private boolean escapes;
        private boolean rawNonAscii;
        private boolean upperHex;
        private boolean lowerHex;
        private boolean unicodeEscapes;
        private boolean latin1Escapes;
        private boolean multiLineFlowScalar;

        Layout(String text) {
            this.text = text;
        }

        void walk(MappingNode root) {
            Deque<Node> visiting = new ArrayDeque<>();
            walkMapping(root, visiting);
        }

        private void walkMapping(MappingNode mapping, Deque<Node> visiting) {
            if (visiting.contains(mapping)) {
                return;
            }
            visiting.push(mapping);
            for (NodeTuple tuple : mapping.getValue()) {
                Node key = tuple.getKeyNode();
                Node value = tuple.getValueNode();
                scalar(key);
                int keyColumn = key.getStartMark() == null ? -1 : key.getStartMark().getColumn();
                if (value instanceof MappingNode && !isFlow(value) && indent < 0 && keyColumn >= 0
                        && !((MappingNode) value).getValue().isEmpty()) {
                    Node child = ((MappingNode) value).getValue().get(0).getKeyNode();
                    if (child.getStartMark() != null && child.getStartMark().getColumn() > keyColumn) {
                        indent = child.getStartMark().getColumn() - keyColumn;
                    }
                }
                if (value instanceof SequenceNode && !isFlow(value) && indicatorIndent < 0 && keyColumn >= 0) {
                    int dash = dashColumn((SequenceNode) value);
                    if (dash >= keyColumn) {
                        indicatorIndent = dash - keyColumn;
                    }
                }
                walkNode(value, visiting);
            }
            visiting.pop();
        }

        private void walkNode(Node node, Deque<Node> visiting) {
            if (node instanceof MappingNode) {
                walkMapping((MappingNode) node, visiting);
            } else if (node instanceof SequenceNode) {
                if (visiting.contains(node)) {
                    return;
                }
                visiting.push(node);
                for (Node element : ((SequenceNode) node).getValue()) {
                    walkNode(element, visiting);
                }
                visiting.pop();
            } else {
                scalar(node);
            }
        }

        private void scalar(Node node) {
            if (!(node instanceof ScalarNode) || node.getStartMark() == null || node.getEndMark() == null) {
                return;
            }
            ScalarNode scalar = (ScalarNode) node;
            String source = source(scalar);
            for (int i = 0; i < source.length(); i++) {
                if (source.charAt(i) > 0x7F) {
                    rawNonAscii = true;
                    break;
                }
            }
            if (scalar.getScalarStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED) {
                hexEscapes(source);
            }
            boolean flow = scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN
                    || scalar.getScalarStyle() == DumperOptions.ScalarStyle.SINGLE_QUOTED
                    || scalar.getScalarStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED;
            if (flow && scalar.getStartMark().getLine() != scalar.getEndMark().getLine()) {
                multiLineFlowScalar = true;
            }
        }

        private void hexEscapes(String source) {
            for (int i = 0; i + 1 < source.length(); i++) {
                if (source.charAt(i) != '\\') {
                    continue;
                }
                char kind = source.charAt(i + 1);
                int digits = kind == 'x' ? 2 : kind == 'u' ? 4 : kind == 'U' ? 8 : 0;
                if (digits > 0) {
                    escapes = true;
                    latin1Escapes |= kind == 'x';
                    unicodeEscapes |= kind != 'x';
                    for (int j = i + 2; j < Math.min(i + 2 + digits, source.length()); j++) {
                        char c = source.charAt(j);
                        upperHex |= c >= 'A' && c <= 'F';
                        lowerHex |= c >= 'a' && c <= 'f';
                    }
                }
                i++;
            }
        }

        /**
         * The column of the first item's {@code -}: the indentation of the line the first item starts on (the
         * sequence's own start mark may be an anchor on the key's line).
         */
        private int dashColumn(SequenceNode sequence) {
            if (sequence.getValue().isEmpty() || sequence.getValue().get(0).getStartMark() == null) {
                return -1;
            }
            int start = offset(sequence.getValue().get(0).getStartMark().getIndex());
            if (start < 0) {
                return -1;
            }
            int lineStart = start;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
                lineStart--;
            }
            int column = 0;
            while (lineStart + column < start && text.charAt(lineStart + column) == ' ') {
                column++;
            }
            return lineStart + column < text.length() && text.charAt(lineStart + column) == '-' ? column : -1;
        }

        private String source(ScalarNode scalar) {
            int start = offset(scalar.getStartMark().getIndex());
            int end = offset(scalar.getEndMark().getIndex());
            return start >= 0 && end >= start ? text.substring(start, end) : "";
        }

        /** Converts a SnakeYAML mark index (counted in code points) to a char offset in the text. */
        private int offset(int codePointIndex) {
            try {
                return text.offsetByCodePoints(0, codePointIndex);
            } catch (IndexOutOfBoundsException e) {
                return -1;
            }
        }

        private static boolean isFlow(Node node) {
            if (node instanceof MappingNode) {
                return ((MappingNode) node).getFlowStyle() == DumperOptions.FlowStyle.FLOW;
            }
            return node instanceof SequenceNode && ((SequenceNode) node).getFlowStyle() == DumperOptions.FlowStyle.FLOW;
        }
    }
}
