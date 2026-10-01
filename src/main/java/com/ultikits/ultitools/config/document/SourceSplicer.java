package com.ultikits.ultitools.config.document;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Renders edits against immutable source spans, never regenerating untouched source text. */
final class SourceSplicer {

    private final String source;
    private final DocumentStyle style;
    private final Map<Node, Snapshot> originals = new IdentityHashMap<>();

    SourceSplicer(String source, MappingNode root, DocumentStyle style) {
        this.source = source;
        this.style = style;
        remember(root);
    }

    String render(MappingNode root) {
        List<Edit> edits = new ArrayList<>();
        Snapshot original = originals.get(root);
        if (original == null) {
            String added = emit(root, 0, true);
            return source + separator(source.length()) + added;
        }
        mapping(root, original, edits, true);
        edits.sort(Comparator.comparingInt((Edit edit) -> edit.start).thenComparingInt(edit -> edit.end));
        StringBuilder result = new StringBuilder();
        int copied = 0;
        for (Edit edit : edits) {
            if (edit.start < copied) {
                throw new IllegalStateException("Overlapping config source edits");
            }
            result.append(source, copied, edit.start).append(edit.text);
            copied = edit.end;
        }
        return result.append(source, copied, source.length()).toString();
    }

    private void remember(Node node) {
        if (node == null || originals.containsKey(node)) {
            return;
        }
        originals.put(node, new Snapshot(node));
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                remember(tuple.getKeyNode());
                remember(tuple.getValueNode());
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                remember(element);
            }
        }
    }

    private void mapping(MappingNode mapping, Snapshot original, List<Edit> edits, boolean top) {
        List<NodeTuple> current = mapping.getValue();
        if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW && !sameTuples(current, original.tuples)) {
            replace(mapping, mapping, 0, edits);
            return;
        }
        int column = original.tuples.isEmpty() ? 0 : original.tuples.get(0).getKeyNode().getStartMark().getColumn();
        for (NodeTuple old : original.tuples) {
            NodeTuple now = null;
            for (NodeTuple candidate : current) {
                if (candidate.getKeyNode() == old.getKeyNode()) {
                    now = candidate;
                    break;
                }
            }
            if (now == null) {
                int start = ownStart(old.getKeyNode());
                int end = tupleEnd(old);
                edits.add(new Edit(start, end, ""));
            } else {
                comments(old.getKeyNode(), edits);
                visit(old.getValueNode(), now.getValueNode(), old.getKeyNode().getStartMark().getColumn(), edits);
            }
        }
        if (!top && current.isEmpty() && !original.tuples.isEmpty()) {
            int first = lineStart(start(original.tuples.get(0).getKeyNode()));
            int line = first;
            while (line > 0 && (source.charAt(line - 1) == '\n' || source.charAt(line - 1) == '\r')) {
                line--;
            }
            edits.add(new Edit(line, line, " {}"));
        }
        List<NodeTuple> added = new ArrayList<>();
        for (NodeTuple tuple : current) {
            if (!originals.containsKey(tuple.getKeyNode())) {
                added.add(tuple);
            }
        }
        if (!added.isEmpty()) {
            int position = top ? source.length() : original.tuples.isEmpty() ? end(mapping)
                    : tupleEnd(original.tuples.get(original.tuples.size() - 1));
            MappingNode fragment = new MappingNode(Tag.MAP, added, DumperOptions.FlowStyle.BLOCK);
            edits.add(new Edit(position, position, separator(position) + emit(fragment, column, true)));
        }
    }

    private void visit(Node old, Node current, int keyColumn, List<Edit> edits) {
        if (old != current) {
            replace(old, current, keyColumn, edits);
        } else if (current instanceof MappingNode) {
            mapping((MappingNode) current, originals.get(current), edits, false);
        } else if (current instanceof SequenceNode) {
            Snapshot snapshot = originals.get(current);
            List<Node> elements = ((SequenceNode) current).getValue();
            if (elements.size() != snapshot.elements.size()) {
                replace(old, current, keyColumn, edits);
            } else {
                for (int i = 0; i < elements.size(); i++) {
                    visit(snapshot.elements.get(i), elements.get(i), keyColumn, edits);
                }
            }
        }
    }

    private void replace(Node old, Node current, int keyColumn, List<Edit> edits) {
        String text = emitValue(current, keyColumn);
        int start = start(old);
        int end = end(old);
        if (isBlockCollection(old)) {
            // Collection marks end at the following key, not at the last byte of their own value.
            end = contentEnd(old);
            if (isBlockCollection(current) && start > lineStart(start)
                    && !source.substring(lineStart(start), start).trim().isEmpty()) {
                while (start > 0 && source.charAt(start - 1) == ' ') {
                    start--;
                }
                text = style.lineBreak() + spaces(keyColumn + style.dumperOptions().getIndent()) + text;
            }
            if (!isBlockCollection(current)) {
                text += style.lineBreak();
            }
        } else if (isBlockCollection(current)) {
            while (start > 0 && source.charAt(start - 1) == ' ') {
                start--;
            }
            text = style.lineBreak() + spaces(keyColumn + style.dumperOptions().getIndent()) + text;
            if (end < source.length() && source.charAt(end) != '\r' && source.charAt(end) != '\n') {
                text += style.lineBreak();
            }
        }
        edits.add(new Edit(start, end, text));
    }

    private void comments(Node key, List<Edit> edits) {
        Snapshot snapshot = originals.get(key);
        List<CommentLine> now = key.getBlockComments();
        if (equalComments(snapshot.comments, now)) {
            return;
        }
        int position = lineStart(start(key));
        int first = position;
        int leading = 0;
        for (CommentLine line : snapshot.comments) {
            if (line.getCommentType() == CommentType.BLANK_LINE && leading == 0) {
                continue;
            }
            leading++;
            if (line.getStartMark() != null) {
                first = Math.min(first, lineStart(offset(line.getStartMark().getIndex())));
            }
        }
        StringBuilder text = new StringBuilder();
        boolean beforeComment = true;
        if (now != null) {
            for (CommentLine line : now) {
                if (beforeComment && line.getStartMark() != null && line.getCommentType() == CommentType.BLANK_LINE) {
                    continue;
                }
                beforeComment = false;
                if (line.getCommentType() != CommentType.BLANK_LINE) {
                    text.append(spaces(key.getStartMark().getColumn())).append('#').append(line.getValue());
                }
                text.append(style.lineBreak());
            }
        }
        edits.add(new Edit(first, position, text.toString()));
    }

    private String emitValue(Node node, int column) {
        Node clean = cloneNode(node, false);
        ScalarNode key = new ScalarNode(Tag.STR, "value", null, null, DumperOptions.ScalarStyle.PLAIN);
        MappingNode wrapper = new MappingNode(Tag.MAP,
                Collections.singletonList(new NodeTuple(key, clean)), DumperOptions.FlowStyle.BLOCK);
        String emitted = emit(wrapper, 0, false);
        Node parsed = new Yaml(ConfigDocument.loaderOptions()).compose(new StringReader(emitted));
        Node value = ((MappingNode) parsed).getValue().get(0).getValueNode();
        int a = emitted.offsetByCodePoints(0, value.getStartMark().getIndex());
        int b = emitted.offsetByCodePoints(0, value.getEndMark().getIndex());
        String fragment = emitted.substring(a, b);
        if (isBlockCollection(node)) {
            fragment = stripFinalBreak(fragment);
            return indentContinuation(fragment, column);
        }
        return indentContinuation(fragment, column);
    }

    private String emit(Node node, int column, boolean comments) {
        StringWriter writer = new StringWriter();
        DumperOptions options = style.dumperOptions();
        new Yaml(new ConfigDocument.NodeConstructor(), new org.yaml.snakeyaml.representer.Representer(options),
                options, ConfigDocument.loaderOptions()).serialize(cloneNode(node, comments), writer);
        String text = writer.toString().replaceAll("(?m)^ +$", "");
        if (style.upperCaseHex() || style.latin1AsUnicodeEscape()) {
            text = ConfigDocument.normalizeEscapes(text, style.upperCaseHex(), style.latin1AsUnicodeEscape());
        }
        text = indentContinuation(text, column);
        if (column > 0) {
            text = spaces(column) + text;
        }
        return text.replace("\n", style.lineBreak());
    }

    private static Node cloneNode(Node node, boolean comments) {
        Node copy;
        if (node instanceof MappingNode) {
            List<NodeTuple> tuples = new ArrayList<>();
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                tuples.add(new NodeTuple(cloneNode(tuple.getKeyNode(), comments), cloneNode(tuple.getValueNode(), comments)));
            }
            copy = new MappingNode(node.getTag(), tuples, ((MappingNode) node).getFlowStyle());
        } else if (node instanceof SequenceNode) {
            List<Node> elements = new ArrayList<>();
            for (Node element : ((SequenceNode) node).getValue()) {
                elements.add(cloneNode(element, comments));
            }
            copy = new SequenceNode(node.getTag(), elements, ((SequenceNode) node).getFlowStyle());
        } else {
            ScalarNode scalar = (ScalarNode) node;
            copy = new ScalarNode(node.getTag(), scalar.getValue(), null, null, scalar.getScalarStyle());
        }
        if (comments) {
            copy.setBlockComments(node.getBlockComments());
            copy.setInLineComments(node.getInLineComments());
            copy.setEndComments(node.getEndComments());
        }
        return copy;
    }

    private int tupleEnd(NodeTuple tuple) {
        return lineEnd(contentEnd(tuple.getValueNode()));
    }

    private int contentEnd(Node node) {
        if (node instanceof MappingNode && isBlockCollection(node)) {
            List<NodeTuple> tuples = originals.get(node).tuples;
            return tuples.isEmpty() ? end(node) : contentEnd(tuples.get(tuples.size() - 1).getValueNode());
        }
        if (node instanceof SequenceNode && isBlockCollection(node)) {
            List<Node> elements = originals.get(node).elements;
            return elements.isEmpty() ? end(node) : contentEnd(elements.get(elements.size() - 1));
        }
        return end(node);
    }

    private int ownStart(Node key) {
        int first = lineStart(start(key));
        for (CommentLine line : originals.get(key).comments) {
            if (line.getStartMark() != null) {
                first = Math.min(first, lineStart(offset(line.getStartMark().getIndex())));
            }
        }
        return first;
    }

    private int start(Node node) {
        return offset(node.getStartMark().getIndex());
    }

    private int end(Node node) {
        return offset(node.getEndMark().getIndex());
    }

    private int offset(int index) {
        int bom = source.startsWith("\uFEFF") ? 1 : 0;
        return source.offsetByCodePoints(bom, index);
    }

    private int lineStart(int position) {
        while (position > 0 && source.charAt(position - 1) != '\n' && source.charAt(position - 1) != '\r') {
            position--;
        }
        return position == 0 && source.startsWith("\uFEFF") ? 1 : position;
    }

    private int lineEnd(int position) {
        if (position > 0 && (source.charAt(position - 1) == '\n' || source.charAt(position - 1) == '\r')) {
            return position;
        }
        while (position < source.length() && source.charAt(position) != '\n' && source.charAt(position) != '\r') {
            position++;
        }
        if (position < source.length() && source.charAt(position++) == '\r' && position < source.length()
                && source.charAt(position) == '\n') {
            position++;
        }
        return position;
    }

    private String separator(int position) {
        return position > 0 && source.charAt(position - 1) != '\n' && source.charAt(position - 1) != '\r'
                && source.charAt(position - 1) != '\uFEFF' ? style.lineBreak() : "";
    }

    private static String spaces(int count) {
        return String.join("", Collections.nCopies(count, " "));
    }

    private static String indentContinuation(String text, int column) {
        return column == 0 ? text : text.replaceAll("\n(?=.)", "\n" + spaces(column));
    }

    private String stripFinalBreak(String text) {
        return text.endsWith(style.lineBreak()) ? text.substring(0, text.length() - style.lineBreak().length()) : text;
    }

    private static boolean isBlockCollection(Node node) {
        return node instanceof MappingNode && ((MappingNode) node).getFlowStyle() == DumperOptions.FlowStyle.BLOCK
                || node instanceof SequenceNode && ((SequenceNode) node).getFlowStyle() == DumperOptions.FlowStyle.BLOCK;
    }

    private static boolean sameTuples(List<NodeTuple> current, List<NodeTuple> original) {
        if (current.size() != original.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).getKeyNode() != original.get(i).getKeyNode()) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalComments(List<CommentLine> a, List<CommentLine> b) {
        List<CommentLine> right = b == null ? Collections.<CommentLine>emptyList() : b;
        if (a.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getCommentType() != right.get(i).getCommentType()
                    || !a.get(i).getValue().equals(right.get(i).getValue())) {
                return false;
            }
        }
        return true;
    }

    private static final class Snapshot {
        final List<NodeTuple> tuples;
        final List<Node> elements;
        final List<CommentLine> comments;

        Snapshot(Node node) {
            tuples = node instanceof MappingNode ? new ArrayList<>(((MappingNode) node).getValue()) : Collections.emptyList();
            elements = node instanceof SequenceNode ? new ArrayList<>(((SequenceNode) node).getValue()) : Collections.emptyList();
            comments = node.getBlockComments() == null ? Collections.emptyList() : new ArrayList<>(node.getBlockComments());
        }
    }

    private static final class Edit {
        final int start;
        final int end;
        final String text;

        Edit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }
}
