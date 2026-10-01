package com.ultikits.ultitools.config.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jetbrains.annotations.ApiStatus;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;

/**
 * One config file as a document: SnakeYAML's own node tree (comments, quoting and layout as they are in
 * the file) plus the plain-data view of its values, addressed by key arrays.
 * <p>
 * <b>Reading.</b> The text is composed with SnakeYAML's node API using the options Bukkit's
 * {@code YamlConfiguration} uses (aliases and code points unlimited, nesting depth 100) with comment
 * processing on. Values are constructed with a {@link SafeConstructor}, so a global tag such as
 * {@code !!java.util.UUID} is refused, and a map key's identity is {@code String.valueOf} of its
 * constructed value, exactly the rule Bukkit's {@code YamlConfiguration#fromNodeTree} applies:
 * {@code yes:} is the key {@code "true"}, {@code 1:} the key {@code "1"}; a duplicate key's last value
 * wins; merge keys ({@code <<}) are flattened. Unlike Bukkit, a key is never split at {@code .}:
 * {@code "o.O"} and {@code wave.} are single keys. A key's original node is kept, so it is written back with
 * its original text. Values YAML itself types outside plain data (a timestamp reads as a
 * {@code java.util.Date}, {@code !!binary} as a {@code byte[]}, {@code !!set} as a {@code Set}) are
 * returned as they are; the binding layer reports them.
 * <p>
 * <b>Writing.</b> {@link #set(List, Object)} accepts plain data only (see {@link PlainData}) and changes
 * only what differs: an equal value keeps its node and text, a changed map is merged key by key, a
 * same-size list element by element, a changed string keeps its quote style, and every replaced node keeps
 * its comments. {@link #render()} splices changed spans into the original source, so every
 * other line keeps its bytes. The only exception is a document holding an anchor, an alias or a merge key:
 * once it is changed it is re-rendered from its plain data (every value equal, anchors expanded, the
 * comments of keys that still exist carried over), as Bukkit renders every file.
 * <p>
 * <b>Comments.</b> SnakeYAML attaches a comment to the node after it. Two placements are adjusted when the
 * file is read, without changing the rendered text: the file header (the comment lines before the first
 * key, up to the last blank line among them, Bukkit's own rule) belongs to the document rather than to the
 * first key; and a comment indented deeper than the key it precedes is the trailing comment of the
 * previous section, and is kept there. The framework writes a comment only through
 * {@link #setFrameworkComment(List, List)}.
 * <p>
 * Instances are not thread-safe; the entity that owns a document confines it.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConfigDocument {

    /** Bukkit's {@code YamlConfiguration} nesting limit, kept for compatibility. */
    static final int NESTING_DEPTH_LIMIT = 100;

    private static final Pattern YAML_LINE_BREAK = Pattern.compile("\r\n|[\r\n\u0085\u2028\u2029]");
    private static final Pattern WHITESPACE_ONLY_LINE = Pattern.compile("(?m)^ +$");

    private final DocumentStyle style;
    private final boolean anchored;
    private final String source;
    private final Map<String, Object> plain;
    private final NodeConstructor keyConstructor = new NodeConstructor();
    private MappingNode root;
    private boolean modified;
    private final SourceSplicer splicer;

    private ConfigDocument(String source, MappingNode root, Map<String, Object> plain, DocumentStyle style, boolean anchored) {
        this.source = source;
        this.root = root;
        this.plain = plain;
        this.style = style;
        this.anchored = anchored;
        this.splicer = new SourceSplicer(source, root, style);
    }

    /**
     * Returns an empty document, as for a file that does not exist yet, in the default style.
     *
     * @return a document without keys
     */
    public static ConfigDocument empty() {
        return new ConfigDocument("", null, new LinkedHashMap<String, Object>(), DocumentStyle.defaults(), false);
    }

    /**
     * Parses a config file's text.
     *
     * @param text the file's content
     * @return the document
     * @throws ConfigParseException if the text is not valid YAML, holds a tag this layer does not construct,
     *                              is deeper than the nesting limit, or its top level is not a mapping
     */
    public static ConfigDocument parse(String text) throws ConfigParseException {
        Node written = compose(text);
        if (written != null && !(written instanceof MappingNode)) {
            throw new ConfigParseException("Top level is not a map (found a " + written.getNodeId() + ")", null);
        }
        MappingNode root = (MappingNode) written;
        if (root != null && (Tag.COMMENT.equals(root.getTag()) || !(root.getValue() instanceof ArrayList))) {
            // A file holding only comments composes to an immutable placeholder; keep its comments on a real,
            // empty top-level mapping so keys can be added below them.
            MappingNode mapping = new MappingNode(Tag.MAP, new ArrayList<>(root.getValue()), DumperOptions.FlowStyle.BLOCK);
            carryComments(root, mapping);
            root = mapping;
        }
        Map<String, Object> plain = new LinkedHashMap<>();
        if (root != null) {
            // The plain view is built from a second, independent tree: reading flattens merge keys and drops
            // duplicate keys in place (as Bukkit does), which must not touch the tree that is written back.
            Node read = compose(text);
            try {
                plain = new PlainReader().readMapping((MappingNode) read);
            } catch (YAMLException e) {
                throw new ConfigParseException(e.getMessage(), e);
            }
        }
        DocumentStyle style = DocumentStyle.detect(text, root);
        boolean anchored = root != null && hasAnchors(root, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
        if (root != null) {
            CommentPlacement.adjust(root);
        }
        return new ConfigDocument(text, root, plain, style, anchored);
    }

    /**
     * Reads a config file. Never throws: the result is {@link ConfigLoadResult.State#ABSENT ABSENT},
     * {@link ConfigLoadResult.State#LOADED LOADED} (with the document and the SHA-256 of the bytes read),
     * {@link ConfigLoadResult.State#UNREADABLE UNREADABLE} (with the I/O failure) or
     * {@link ConfigLoadResult.State#UNPARSEABLE UNPARSEABLE} (with the parser's message). Temporary files an
     * interrupted write left beside the file are deleted first (see {@link AtomicConfigWriter}).
     *
     * @param file the config file
     * @return what was found
     */
    public static ConfigLoadResult load(Path file) {
        return load(file, AtomicConfigWriter.FILES_FOR_LOAD);
    }

    static ConfigLoadResult load(Path file, AtomicConfigWriter.FileOperations files) {
        AtomicConfigWriter.deleteStaleTemporaries(file);
        byte[] bytes;
        try {
            bytes = files.read(file);
        } catch (NoSuchFileException e) {
            return ConfigLoadResult.absent(file);
        } catch (IOException e) {
            return ConfigLoadResult.unreadable(file, e);
        } catch (SecurityException | UncheckedIOException e) {
            return ConfigLoadResult.unreadable(file, new IOException(e.getMessage(), e));
        }
        try {
            return ConfigLoadResult.loaded(file, parse(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()), sha256(bytes));
        } catch (CharacterCodingException e) {
            return ConfigLoadResult.unparseable(file, "Config file is not valid UTF-8: " + e.getMessage());
        } catch (ConfigParseException e) {
            return ConfigLoadResult.unparseable(file, e.getMessage());
        } catch (RuntimeException e) {
            return ConfigLoadResult.unparseable(file, e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * Returns the plain value at {@code path}, or {@code null} when the path is absent (use
     * {@link #contains(List)} to tell an absent key from an explicit {@code null}).
     *
     * @param path the key path, one element per mapping level
     * @return a copy of the value
     */
    public Object get(List<String> path) {
        Object current = plain;
        for (String key : path) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(key)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return PlainData.copy(current);
    }

    /**
     * Returns whether the file holds a value (possibly {@code null}) at {@code path}.
     *
     * @param path the key path
     * @return whether the path is present
     */
    public boolean contains(List<String> path) {
        Object current = plain;
        for (String key : path) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(key)) {
                return false;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return true;
    }

    /**
     * Returns a copy of the whole document as plain data, in file order.
     *
     * @return the top-level mapping
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toPlain() {
        return (Map<String, Object>) PlainData.copy(plain);
    }

    /**
     * Stores a plain value at {@code path}, creating missing mappings on the way. A value equal to the
     * current one (see {@link PlainData#plainEquals}) changes nothing.
     *
     * @param path  the key path, at least one key
     * @param value plain data; {@code null} is stored as an explicit YAML {@code null}
     * @throws IllegalArgumentException if {@code value} is not plain data (the message names the key path
     *                                  and the class), the path is empty, or the path and value together nest
     *                                  deeper than the 100 levels a load accepts
     */
    public void set(List<String> path, Object value) {
        requireKeys(path);
        PlainData.requirePlain(path, value);
        if (path.size() + PlainData.depth(value) > NESTING_DEPTH_LIMIT) {
            throw new IllegalArgumentException("Config value at key path " + PlainData.describePath(path) + " would nest"
                    + " deeper than " + NESTING_DEPTH_LIMIT + " levels, which no config file may (the next load refuses it)");
        }
        Object copy = PlainData.copy(value);
        if (root == null) {
            root = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
        }
        MappingNode mapping = root;
        Map<String, Object> plainMapping = plain;
        for (int i = 0; i < path.size() - 1; i++) {
            mapping = childMapping(mapping, path.get(i));
            plainMapping = childPlainMapping(plainMapping, path.get(i));
        }
        String last = path.get(path.size() - 1);
        int index = indexOf(mapping, last);
        if (index < 0) {
            mapping.getValue().add(new NodeTuple(representer().represent(last), newNode(copy)));
            modified = true;
        } else if (!plainMapping.containsKey(last) || !PlainData.plainEquals(plainMapping.get(last), copy)) {
            NodeTuple old = mapping.getValue().get(index);
            Node updated = update(old.getValueNode(), plainMapping.get(last), copy);
            mapping.getValue().set(index, new NodeTuple(old.getKeyNode(), updated));
            modified = true;
        }
        plainMapping.put(last, copy);
    }

    /**
     * Removes the key at {@code path} together with its own comment lines.
     *
     * @param path the key path
     * @return whether a key was removed
     */
    public boolean remove(List<String> path) {
        requireKeys(path);
        MappingNode mapping = findMapping(path);
        Map<String, Object> plainMapping = findPlainMapping(path);
        String last = path.get(path.size() - 1);
        if (mapping == null || plainMapping == null || !plainMapping.containsKey(last)) {
            return false;
        }
        String keyText = last;
        mapping.getValue().removeIf(tuple -> !Tag.MERGE.equals(tuple.getKeyNode().getTag())
                && keyText.equals(keyIdentity(tuple.getKeyNode())));
        plainMapping.remove(last);
        modified = true;
        return true;
    }

    /**
     * Returns the comment lines directly above the key at {@code path}: each line's text after {@code #}
     * with one leading space removed, and {@code null} for a blank line. The file header is not part of the
     * first key's comment.
     *
     * @param path the key path
     * @return the lines, empty when the key has no comment or does not exist
     */
    public List<String> blockComment(List<String> path) {
        requireKeys(path);
        Node key = findKey(path);
        List<String> result = new ArrayList<>();
        if (key == null || key.getBlockComments() == null) {
            return result;
        }
        for (CommentLine line : key.getBlockComments()) {
            if (line.getCommentType() == CommentType.BLANK_LINE) {
                result.add(null);
            } else {
                String text = line.getValue();
                result.add(text.startsWith(" ") ? text.substring(1) : text);
            }
        }
        return result;
    }

    /**
     * Replaces the comment of the key at {@code path} - the only way the framework writes a comment. Blank
     * lines directly above the comment are kept; the comment lines themselves are replaced by {@code lines}.
     * Each line is split at every YAML line break ({@code \r\n}, {@code \r}, {@code \n}, U+0085, U+2028,
     * U+2029) and characters YAML does not allow in a stream are dropped; a {@code null} line is a blank line.
     *
     * @param path  the key path of an existing key
     * @param lines the new comment text, one element per line (an empty list removes the comment)
     * @throws IllegalArgumentException if no key exists at {@code path}
     */
    public void setFrameworkComment(List<String> path, List<String> lines) {
        requireKeys(path);
        Node key = findKey(path);
        if (key == null) {
            throw new IllegalArgumentException("No config key at key path " + PlainData.describePath(path));
        }
        List<CommentLine> result = new ArrayList<>();
        if (key.getBlockComments() != null) {
            for (CommentLine line : key.getBlockComments()) {
                if (line.getCommentType() != CommentType.BLANK_LINE) {
                    break;
                }
                result.add(line);
            }
        }
        for (String line : lines) {
            if (line == null) {
                result.add(new CommentLine(null, null, "", CommentType.BLANK_LINE));
                continue;
            }
            for (String part : YAML_LINE_BREAK.split(line, -1)) {
                String text = printable(part);
                result.add(new CommentLine(null, null, text.isEmpty() ? "" : " " + text, CommentType.BLOCK));
            }
        }
        if (!sameComments(result, key.getBlockComments())) {
            key.setBlockComments(result.isEmpty() ? null : result);
            modified = true;
        }
    }

    /**
     * Serializes the document in the file's own style.
     *
     * @return the file text
     */
    public String render() {
        if (!modified) {
            return source;
        }
        if (!anchored) {
            return splicer.render(root);
        }
        MappingNode out = reRenderFromPlain();
        if (out == null) {
            return modified ? "" : source;
        }
        String text;
        if (out.getValue().isEmpty()) {
            text = commentsOnly(out);
        } else {
            StringWriter writer = new StringWriter();
            dumper(style).serialize(out, writer);
            text = WHITESPACE_ONLY_LINE.matcher(writer.toString()).replaceAll("");
        }
        if (style.upperCaseHex() || style.latin1AsUnicodeEscape()) {
            text = normalizeEscapes(text, style.upperCaseHex(), style.latin1AsUnicodeEscape());
        }
        if (!style.finalLineBreak() && text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (!"\n".equals(style.lineBreak())) {
            text = text.replace("\n", style.lineBreak());
        }
        return style.byteOrderMark() ? "\uFEFF" + text : text;
    }

    private Node update(Node old, Object oldPlain, Object value) {
        if (value instanceof Map && oldPlain instanceof Map && old instanceof MappingNode && Tag.MAP.equals(old.getTag())) {
            merge((MappingNode) old, (Map<?, ?>) oldPlain, (Map<?, ?>) value);
            return old;
        }
        if (value instanceof List && oldPlain instanceof List && old instanceof SequenceNode && Tag.SEQ.equals(old.getTag())
                && ((List<?>) value).size() == ((List<?>) oldPlain).size()
                && ((SequenceNode) old).getValue().size() == ((List<?>) value).size()) {
            List<Node> elements = ((SequenceNode) old).getValue();
            List<?> oldList = (List<?>) oldPlain;
            List<?> newList = (List<?>) value;
            for (int i = 0; i < newList.size(); i++) {
                if (!PlainData.plainEquals(oldList.get(i), newList.get(i))) {
                    elements.set(i, update(elements.get(i), oldList.get(i), newList.get(i)));
                }
            }
            return old;
        }
        Node created = newNode(value);
        if (created instanceof SequenceNode && old instanceof SequenceNode) {
            ((SequenceNode) created).setFlowStyle(((SequenceNode) old).getFlowStyle());
        } else if (created instanceof MappingNode && old instanceof MappingNode) {
            ((MappingNode) created).setFlowStyle(((MappingNode) old).getFlowStyle());
        } else if (created instanceof ScalarNode && old instanceof ScalarNode && value instanceof String
                && oldPlain instanceof String) {
            ScalarNode scalar = (ScalarNode) created;
            created = new ScalarNode(scalar.getTag(), scalar.getValue(), null, null, ((ScalarNode) old).getScalarStyle());
        }
        carryComments(old, created);
        return created;
    }

    private void merge(MappingNode old, Map<?, ?> oldPlain, Map<?, ?> value) {
        List<NodeTuple> tuples = old.getValue();
        tuples.removeIf(tuple -> !Tag.MERGE.equals(tuple.getKeyNode().getTag())
                && !value.containsKey(keyIdentity(tuple.getKeyNode())));
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = (String) entry.getKey();
            int index = indexOf(old, key);
            if (index < 0) {
                tuples.add(new NodeTuple(representer().represent(key), newNode(entry.getValue())));
            } else if (!oldPlain.containsKey(key) || !PlainData.plainEquals(oldPlain.get(key), entry.getValue())) {
                NodeTuple tuple = tuples.get(index);
                tuples.set(index, new NodeTuple(tuple.getKeyNode(), update(tuple.getValueNode(), oldPlain.get(key), entry.getValue())));
            }
        }
    }

    private Node newNode(Object plainValue) {
        return representer().represent(PlainData.copy(plainValue));
    }

    private MappingNode childMapping(MappingNode mapping, String key) {
        int index = indexOf(mapping, key);
        if (index >= 0) {
            NodeTuple tuple = mapping.getValue().get(index);
            if (tuple.getValueNode() instanceof MappingNode && Tag.MAP.equals(tuple.getValueNode().getTag())) {
                return (MappingNode) tuple.getValueNode();
            }
            MappingNode created = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
            carryComments(tuple.getValueNode(), created);
            mapping.getValue().set(index, new NodeTuple(tuple.getKeyNode(), created));
            modified = true;
            return created;
        }
        MappingNode created = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
        mapping.getValue().add(new NodeTuple(representer().represent(key), created));
        modified = true;
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childPlainMapping(Map<String, Object> mapping, String key) {
        Object child = mapping.get(key);
        if (child instanceof Map) {
            return (Map<String, Object>) child;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        mapping.put(key, created);
        return created;
    }

    /** The mapping node holding the last key of {@code path}, or {@code null}. */
    private MappingNode findMapping(List<String> path) {
        MappingNode mapping = root;
        for (int i = 0; i < path.size() - 1 && mapping != null; i++) {
            int index = indexOf(mapping, path.get(i));
            Node value = index < 0 ? null : mapping.getValue().get(index).getValueNode();
            mapping = value instanceof MappingNode && Tag.MAP.equals(value.getTag()) ? (MappingNode) value : null;
        }
        return mapping;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPlainMapping(List<String> path) {
        Object current = plain;
        for (int i = 0; i < path.size() - 1; i++) {
            current = current instanceof Map ? ((Map<?, ?>) current).get(path.get(i)) : null;
        }
        return current instanceof Map ? (Map<String, Object>) current : null;
    }

    private Node findKey(List<String> path) {
        MappingNode mapping = findMapping(path);
        int index = mapping == null ? -1 : indexOf(mapping, path.get(path.size() - 1));
        return index < 0 ? null : mapping.getValue().get(index).getKeyNode();
    }

    /** Index of the last tuple whose key identity equals {@code key} (the last duplicate wins, as on read). */
    private int indexOf(MappingNode mapping, String key) {
        List<NodeTuple> tuples = mapping.getValue();
        for (int i = tuples.size() - 1; i >= 0; i--) {
            Node keyNode = tuples.get(i).getKeyNode();
            if (!Tag.MERGE.equals(keyNode.getTag()) && key.equals(keyIdentity(keyNode))) {
                return i;
            }
        }
        return -1;
    }

    private String keyIdentity(Node keyNode) {
        return String.valueOf(keyConstructor.construct(keyNode));
    }

    private MappingNode reRenderFromPlain() {
        MappingNode fresh = (MappingNode) newNode(plain);
        if (root != null) {
            carryComments(root, fresh);
            transplantComments(root, fresh);
        }
        return fresh;
    }

    /** Carries key and value comments from the original tree onto a re-rendered one, key by key. */
    private void transplantComments(MappingNode from, MappingNode to) {
        for (NodeTuple tuple : to.getValue()) {
            int index = indexOf(from, keyIdentity(tuple.getKeyNode()));
            if (index < 0) {
                continue;
            }
            NodeTuple original = from.getValue().get(index);
            tuple.getKeyNode().setBlockComments(original.getKeyNode().getBlockComments());
            tuple.getValueNode().setInLineComments(original.getValueNode().getInLineComments());
            if (original.getValueNode() instanceof MappingNode && tuple.getValueNode() instanceof MappingNode) {
                tuple.getValueNode().setEndComments(original.getValueNode().getEndComments());
                transplantComments((MappingNode) original.getValueNode(), (MappingNode) tuple.getValueNode());
            }
        }
    }

    private static String commentsOnly(MappingNode mapping) {
        StringBuilder text = new StringBuilder();
        appendComments(text, mapping.getBlockComments());
        appendComments(text, mapping.getEndComments());
        return text.toString();
    }

    private static void appendComments(StringBuilder text, List<CommentLine> lines) {
        if (lines == null) {
            return;
        }
        for (CommentLine line : lines) {
            if (line.getCommentType() != CommentType.BLANK_LINE) {
                text.append('#').append(line.getValue());
            }
            text.append('\n');
        }
    }

    private static boolean sameComments(List<CommentLine> a, List<CommentLine> b) {
        List<CommentLine> left = a == null ? Collections.<CommentLine>emptyList() : a;
        List<CommentLine> right = b == null ? Collections.<CommentLine>emptyList() : b;
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i).getCommentType() != right.get(i).getCommentType()
                    || !left.get(i).getValue().equals(right.get(i).getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void carryComments(Node from, Node to) {
        to.setBlockComments(from.getBlockComments());
        to.setInLineComments(from.getInLineComments());
        to.setEndComments(from.getEndComments());
    }

    private static void requireKeys(List<String> path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("A config key path needs at least one key");
        }
    }

    private static boolean hasAnchors(Node node, Set<Node> seen) {
        if (!seen.add(node)) {
            return true;
        }
        if (node.getAnchor() != null) {
            return true;
        }
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                if (Tag.MERGE.equals(tuple.getKeyNode().getTag()) || hasAnchors(tuple.getKeyNode(), seen)
                        || hasAnchors(tuple.getValueNode(), seen)) {
                    return true;
                }
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                if (hasAnchors(element, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Keeps the characters YAML allows in a stream (the YAML 1.1 printable set, tab included). */
    private static String printable(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (c == '\t' || c >= 0x20 && c <= 0x7E || c == 0x85 || c >= 0xA0 && c <= 0xD7FF
                    || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF) {
                result.appendCodePoint(c);
            }
            i += Character.charCount(c);
        }
        return result.toString();
    }

    /**
     * Rewrites the escapes inside every double-quoted scalar into the file's own form: {@code \}{@code xHH}
     * as {@code \}{@code u00HH} when {@code latin1AsU}, and hex digits in upper case when {@code upper}.
     */
    static String normalizeEscapes(String text, boolean upper, boolean latin1AsU) {
        StringBuilder result = new StringBuilder(text.length() + 16);
        int copied = 0;
        for (Event event : new Yaml(loaderOptions()).parse(new StringReader(text))) {
            if (!(event instanceof ScalarEvent)
                    || ((ScalarEvent) event).getScalarStyle() != DumperOptions.ScalarStyle.DOUBLE_QUOTED) {
                continue;
            }
            int start = text.offsetByCodePoints(0, event.getStartMark().getIndex());
            int end = text.offsetByCodePoints(0, event.getEndMark().getIndex());
            result.append(text, copied, start);
            for (int i = start; i < end; i++) {
                char c = text.charAt(i);
                if (c != '\\' || i + 1 >= end) {
                    result.append(c);
                    continue;
                }
                char kind = text.charAt(i + 1);
                int digits = kind == 'x' ? 2 : kind == 'u' ? 4 : kind == 'U' ? 8 : 0;
                String hex = text.substring(i + 2, Math.min(i + 2 + digits, end));
                if (upper) {
                    hex = hex.toUpperCase(Locale.ROOT);
                }
                if (kind == 'x' && latin1AsU) {
                    result.append("\\u00").append(hex);
                } else {
                    result.append(c).append(kind).append(hex);
                }
                i += 1 + hex.length();
            }
            copied = end;
        }
        return result.append(text, copied, text.length()).toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    private static Node compose(String text) throws ConfigParseException {
        try (Reader reader = new UnicodeReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))) {
            return new Yaml(new NodeConstructor(), representer(), DocumentStyle.defaults().dumperOptions(), loaderOptions())
                    .compose(reader);
        } catch (YAMLException e) {
            throw new ConfigParseException(e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigParseException(String.valueOf(e.getMessage()), e);
        }
    }

    static LoaderOptions loaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setNestingDepthLimit(NESTING_DEPTH_LIMIT);
        return options;
    }

    private static Representer representer() {
        return new PlainRepresenter(DocumentStyle.defaults().dumperOptions());
    }

    private static Yaml dumper(DocumentStyle style) {
        DumperOptions options = style.dumperOptions();
        // Keep the anchor names the file uses; only nodes shared by an alias are anchored on output.
        options.setAnchorGenerator(node -> node.getAnchor() != null ? node.getAnchor() : "id" + System.identityHashCode(node));
        return new Yaml(new NodeConstructor(), new PlainRepresenter(options), options, loaderOptions());
    }

    /** A {@link SafeConstructor} that constructs single nodes and flattens merge keys on request. */
    static final class NodeConstructor extends SafeConstructor {

        NodeConstructor() {
            super(loaderOptions());
        }

        Object construct(Node node) {
            return constructObject(node);
        }

        void flatten(MappingNode node) {
            flattenMapping(node);
        }
    }

    /**
     * Builds new nodes for plain values. The plain-data boundary has already refused every type a
     * {@link Representer} would write as a Java bean, so no class tag can be produced.
     */
    private static final class PlainRepresenter extends Representer {

        PlainRepresenter(DumperOptions options) {
            super(options);
            setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        }
    }

    /** Builds the plain-data view of a composed tree with Bukkit's key and value rules. */
    private static final class PlainReader {

        private final NodeConstructor constructor = new NodeConstructor();
        private final Set<Node> path = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());

        Map<String, Object> readMapping(MappingNode node) {
            enter(node);
            try {
                constructor.flatten(node);
                Map<String, Object> result = new LinkedHashMap<>();
                for (NodeTuple tuple : node.getValue()) {
                    result.put(String.valueOf(constructor.construct(tuple.getKeyNode())), read(tuple.getValueNode()));
                }
                return result;
            } finally {
                path.remove(node);
            }
        }

        private Object read(Node node) {
            if (node instanceof MappingNode && Tag.MAP.equals(node.getTag())) {
                return readMapping((MappingNode) node);
            }
            if (node instanceof SequenceNode && Tag.SEQ.equals(node.getTag())) {
                enter(node);
                try {
                    List<Object> result = new ArrayList<>();
                    for (Node element : ((SequenceNode) node).getValue()) {
                        result.add(read(element));
                    }
                    return result;
                } finally {
                    path.remove(node);
                }
            }
            return constructor.construct(node);
        }

        private void enter(Node node) {
            if (!path.add(node)) {
                throw new YAMLException("A recursive alias refers to its own collection " + node.getStartMark());
            }
        }
    }

    /** The two comment placements adjusted on read (see the class description). */
    private static final class CommentPlacement {

        private CommentPlacement() {
        }

        static void adjust(MappingNode root) {
            splitHeader(root);
            relocate(root, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
        }

        /** Bukkit's {@code adjustNodeComments}: the first key's comments up to the last blank line are the header. */
        private static void splitHeader(MappingNode root) {
            if ((root.getBlockComments() != null && !root.getBlockComments().isEmpty()) || root.getValue().isEmpty()) {
                return;
            }
            Node first = root.getValue().get(0).getKeyNode();
            List<CommentLine> comments = first.getBlockComments();
            if (comments == null) {
                return;
            }
            int lastBlank = -1;
            for (int i = 0; i < comments.size(); i++) {
                if (comments.get(i).getCommentType() == CommentType.BLANK_LINE) {
                    lastBlank = i;
                }
            }
            if (lastBlank >= 0) {
                root.setBlockComments(new ArrayList<>(comments.subList(0, lastBlank + 1)));
                first.setBlockComments(new ArrayList<>(comments.subList(lastBlank + 1, comments.size())));
            }
        }

        private static void relocate(Node node, Set<Node> seen) {
            if (!seen.add(node)) {
                return;
            }
            if (node instanceof MappingNode) {
                List<NodeTuple> tuples = ((MappingNode) node).getValue();
                for (int i = 0; i < tuples.size(); i++) {
                    if (i > 0) {
                        Node key = tuples.get(i).getKeyNode();
                        key.setBlockComments(moveTrailing(key.getBlockComments(), column(key), tuples.get(i - 1).getValueNode()));
                    }
                    relocate(tuples.get(i).getValueNode(), seen);
                }
                if (!tuples.isEmpty()) {
                    node.setEndComments(moveTrailing(node.getEndComments(), contentColumn(node), tuples.get(tuples.size() - 1).getValueNode()));
                }
            } else if (node instanceof SequenceNode) {
                for (Node element : ((SequenceNode) node).getValue()) {
                    relocate(element, seen);
                }
            }
        }

        /**
         * Moves the leading comment lines indented deeper than {@code ownColumn} to the end of the deepest
         * block mapping in {@code previous} whose keys start at the line's column, and returns the lines that
         * stay. A moved line is attached to the end of that mapping's last value: SnakeYAML's emitter writes
         * comments that follow a value at the indentation of the mapping holding it.
         */
        private static List<CommentLine> moveTrailing(List<CommentLine> comments, int ownColumn, Node previous) {
            if (comments == null || ownColumn < 0) {
                return comments;
            }
            int moved = 0;
            while (moved < comments.size()) {
                CommentLine line = comments.get(moved);
                if (line.getCommentType() != CommentType.BLOCK || line.getStartMark() == null
                        || line.getStartMark().getColumn() <= ownColumn) {
                    break;
                }
                MappingNode section = sectionAt(previous, line.getStartMark().getColumn());
                if (section == null) {
                    break;
                }
                Node target = section.getValue().get(section.getValue().size() - 1).getValueNode();
                List<CommentLine> end = target.getEndComments() == null
                        ? new ArrayList<CommentLine>() : new ArrayList<>(target.getEndComments());
                end.add(line);
                target.setEndComments(end);
                moved++;
            }
            return moved == 0 ? comments : new ArrayList<>(comments.subList(moved, comments.size()));
        }

        /** The block mapping along the last-child chain of {@code node} whose keys start at {@code column}. */
        private static MappingNode sectionAt(Node node, int column) {
            Node current = node;
            while (current instanceof MappingNode || current instanceof SequenceNode) {
                if (current instanceof MappingNode) {
                    int content = contentColumn(current);
                    if (content < 0 || content > column) {
                        return null;
                    }
                    if (content == column) {
                        return (MappingNode) current;
                    }
                }
                current = lastChild(current);
            }
            return null;
        }

        private static int contentColumn(Node node) {
            if (node instanceof MappingNode && ((MappingNode) node).getFlowStyle() != DumperOptions.FlowStyle.FLOW
                    && !((MappingNode) node).getValue().isEmpty()) {
                return column(((MappingNode) node).getValue().get(0).getKeyNode());
            }
            return -1;
        }

        private static Node lastChild(Node node) {
            if (node instanceof MappingNode) {
                List<NodeTuple> tuples = ((MappingNode) node).getValue();
                return tuples.isEmpty() ? null : tuples.get(tuples.size() - 1).getValueNode();
            }
            List<Node> elements = ((SequenceNode) node).getValue();
            return elements.isEmpty() || ((SequenceNode) node).getFlowStyle() == DumperOptions.FlowStyle.FLOW
                    ? null : elements.get(elements.size() - 1);
        }

        private static int column(Node node) {
            return node.getStartMark() == null ? -1 : node.getStartMark().getColumn();
        }
    }
}
