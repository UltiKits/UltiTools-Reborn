package com.ultikits.ultitools.config.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
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
import org.yaml.snakeyaml.emitter.Emitter;
import org.yaml.snakeyaml.events.CommentEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.serializer.Serializer;

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
 * only what differs: an equal value keeps its node, a changed map is merged key by key, a
 * same-size list element by element, a changed string keeps its quote style, and every replaced node keeps
 * its comments. {@link #render()} serializes the whole node tree through SnakeYAML, preserving content,
 * comments and document style; the rendering itself may normalize operator spacing. Comments on individual
 * list items are kept only while the list keeps its length - the same as Bukkit, which keeps none.
 * A document holding an anchor, an alias
 * or a merge key is re-rendered from its plain data once changed (every value equal, anchors expanded, the
 * comments of keys that still exist carried over), as Bukkit renders every file.
 * <p>
 * No caller publishes a rendering as it is: every write to a configuration file goes through
 * {@link OperatorFileWriter}, which refuses a rendering that would change any byte outside the keys the write
 * owns (so normalized spacing or an expanded anchor is never written) and never writes an anchored file.
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

    private ConfigDocument(String source, MappingNode root, Map<String, Object> plain, DocumentStyle style, boolean anchored) {
        this.source = source;
        this.root = root;
        this.plain = plain;
        this.style = style;
        this.anchored = anchored;
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
        synchronized (AtomicConfigWriter.WRITE_LOAD_LOCK) {
            return loadLocked(file, files);
        }
    }

    private static ConfigLoadResult loadLocked(Path file, AtomicConfigWriter.FileOperations files) {
        AtomicConfigWriter.deleteStaleTemporaries(file);
        byte[] bytes;
        Path destination;
        try {
            destination = AtomicConfigWriter.resolve(file);
            bytes = files.read(destination);
        } catch (NoSuchFileException e) {
            return ConfigLoadResult.absent(file);
        } catch (IOException e) {
            return ConfigLoadResult.unreadable(file, e);
        } catch (SecurityException | UncheckedIOException e) {
            return ConfigLoadResult.unreadable(file, new IOException(e.getMessage(), e));
        }
        try {
            ConfigLoadResult loaded = ConfigLoadResult.loaded(file, parse(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()), sha256(bytes));
            AtomicConfigWriter.deleteBackupAfterLoad(destination, files);
            return loaded;
        } catch (CharacterCodingException e) {
            return ConfigLoadResult.unparseable(file, "Config file is not valid UTF-8: " + e.getMessage());
        } catch (ConfigParseException e) {
            return ConfigLoadResult.unparseable(file, e.getMessage());
        } catch (StackOverflowError e) {
            return ConfigLoadResult.unparseable(file, "Config nesting depth exceeds " + NESTING_DEPTH_LIMIT);
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
     * Returns every place this document holds a declared setting path: each way of splitting {@code dottedPath} at its
     * dots into keys the document holds, the last one present (an explicit {@code null} included). {@code a.b.c} is read
     * as {@code a -> b -> c}, {@code a.b -> c}, {@code a -> b.c} or the single key {@code a.b.c}, the way Bukkit's
     * {@code YamlConfiguration} - which splits every key at its dots - reads the same file, so a setting the operator
     * wrote as a flat dotted key is that setting (#612). Keys inside the setting's value are not split: this addresses
     * the setting itself, never an entry of a map it holds.
     *
     * @param dottedPath a declared setting path, such as {@code features.chat}
     * @return the readings found, nested forms first; empty when the document does not hold the setting, two or more
     *         when it holds it in several forms
     * @since 6.3.0
     */
    public List<List<String>> readings(String dottedPath) {
        return readings(plain, dottedPath);
    }

    /**
     * {@link #readings(String)} over a plain-data tree, such as a copy of a document taken at load.
     *
     * @param tree       the top-level mapping
     * @param dottedPath a declared setting path
     * @return the readings found, nested forms first
     * @since 6.3.0
     */
    public static List<List<String>> readings(Map<?, ?> tree, String dottedPath) {
        List<List<String>> found = new ArrayList<>();
        collectReadings(tree, dottedPath.split("\\.", -1), 0, new ArrayList<String>(), found);
        return found;
    }

    private static void collectReadings(Object node, String[] parts, int from, List<String> prefix, List<List<String>> found) {
        if (!(node instanceof Map)) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) node;
        StringBuilder key = new StringBuilder();
        for (int end = from; end < parts.length; end++) {
            if (end > from) {
                key.append('.');
            }
            key.append(parts[end]);
            String name = key.toString();
            if (!map.containsKey(name)) {
                continue;
            }
            List<String> path = new ArrayList<>(prefix);
            path.add(name);
            if (end == parts.length - 1) {
                found.add(path);
            } else {
                collectReadings(map.get(name), parts, end + 1, path, found);
            }
        }
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
        Object copy = PlainData.copy(value);
        PlainData.requirePlain(path, copy);
        if (path.size() + PlainData.depth(copy) > NESTING_DEPTH_LIMIT) {
            throw new IllegalArgumentException("Config value at key path " + PlainData.describePath(path) + " would nest"
                    + " deeper than " + NESTING_DEPTH_LIMIT + " levels, which no config file may (the next load refuses it)");
        }
        if (root == null) {
            root = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
        }
        MappingNode mapping = root;
        Map<String, Object> plainMapping = plain;
        for (int i = 0; i < path.size() - 1; i++) {
            mapping = childMapping(mapping, path.get(i), plainMapping.get(path.get(i)));
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
        if (plainMapping == null || !plainMapping.containsKey(last)) {
            return false;
        }
        if (mapping == null) {
            plainMapping.remove(last);
            modified = true;
            return true;
        }
        removeTuples(mapping, tuple -> !Tag.MERGE.equals(tuple.getKeyNode().getTag())
                && last.equals(keyIdentity(tuple.getKeyNode())));
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
     * Returns the comment lines directly above the key at {@code path} in the byte form they are written in: for a
     * line at the key's own column, {@code "#"} followed by everything after the {@code #} up to the line break
     * (a framework line reads {@code "# " + text}, or {@code "#"} for an empty line); {@code null} for a blank line.
     * A line at any other column is reported as {@code "@<offset>|#..."}, which never equals a line the framework
     * writes, since the framework writes a key's comment at the key's column. A line added in memory (it has no
     * position yet) is reported as it will be written. The file header is not part of the first key's comment.
     * <p>
     * The framework identifies its own comment lines on this form (17-64 review round 1 R1-02): a line holding the
     * framework's text in any other byte form was written by an operator.
     *
     * @param path the key path
     * @return the lines, empty when the key has no comment or does not exist
     */
    public List<String> blockCommentAsWritten(List<String> path) {
        requireKeys(path);
        Node key = findKey(path);
        List<String> result = new ArrayList<>();
        if (key == null || key.getBlockComments() == null) {
            return result;
        }
        int keyColumn = key.getStartMark() == null ? -1 : key.getStartMark().getColumn();
        for (CommentLine line : key.getBlockComments()) {
            if (line.getCommentType() == CommentType.BLANK_LINE) {
                result.add(null);
                continue;
            }
            int offset = keyColumn < 0 || line.getStartMark() == null ? 0 : line.getStartMark().getColumn() - keyColumn;
            result.add((offset == 0 ? "" : "@" + offset + "|") + "#" + line.getValue());
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
        result.addAll(frameworkLines(lines));
        if (!sameComments(result, key.getBlockComments())) {
            key.setBlockComments(result.isEmpty() ? null : result);
            modified = true;
        }
    }

    /**
     * Replaces only the last {@code owned} comment lines of the key at {@code path} - the run the framework
     * identified as its own comment - with {@code lines}, rendered and sanitized exactly as
     * {@link #setFrameworkComment(List, List)} renders them. With {@code owned} zero the lines are appended below
     * the key's existing comment.
     * <p>
     * <b>Why it cannot change an operator line.</b> Every comment line above the run - an operator's note, a blank
     * line, a framework comment the operator edited - is kept as the same comment object, in the same order, so it
     * renders byte for byte as before; only the run's own lines are dropped. The run may not include a blank line
     * and may not reach above the key's comment, so it can never take in a line the caller did not identify
     * (maintainer decision 2026-10-04, "only the framework's own comments are rewritten", #604).
     *
     * @param path  the key path of an existing key
     * @param owned how many of the key's last comment lines are the framework's (0 for none)
     * @param lines the new framework comment text, one element per line
     * @throws IllegalArgumentException if no key exists at {@code path}, or the run is negative, longer than the
     *                                  key's comment, or includes a blank line
     */
    public void replaceFrameworkComment(List<String> path, int owned, List<String> lines) {
        requireKeys(path);
        Node key = findKey(path);
        if (key == null) {
            throw new IllegalArgumentException("No config key at key path " + PlainData.describePath(path));
        }
        List<CommentLine> current = key.getBlockComments() == null
                ? Collections.<CommentLine>emptyList() : key.getBlockComments();
        int first = current.size() - owned;
        if (owned < 0 || first < 0) {
            throw new IllegalArgumentException("The framework comment run of key path " + PlainData.describePath(path)
                    + " is longer than the key's comment");
        }
        for (int i = first; i < current.size(); i++) {
            if (current.get(i).getCommentType() == CommentType.BLANK_LINE) {
                throw new IllegalArgumentException("The framework comment run of key path "
                        + PlainData.describePath(path) + " includes a blank line");
            }
        }
        List<CommentLine> result = new ArrayList<>(current.subList(0, first));
        result.addAll(frameworkLines(lines));
        if (!sameComments(result, key.getBlockComments())) {
            key.setBlockComments(result.isEmpty() ? null : result);
            modified = true;
        }
    }

    /** The comment lines the framework writes for {@code lines}: split at YAML line breaks, made printable. */
    private static List<CommentLine> frameworkLines(List<String> lines) {
        List<CommentLine> result = new ArrayList<>();
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
        return result;
    }

    /**
     * Serializes the document in the file's own style.
     *
     * @return the file text
     */
    @SuppressWarnings("PMD.NPathComplexity") // Presentation options are independent and retain the existing emitter configuration order.
    public String render() {
        MappingNode out = anchored && modified ? reRenderFromPlain() : root;
        if (out == null) {
            return modified ? "" : source;
        }
        normalizeMultilineStrings(out, new IdentityHashMap<Node, Node>(), false);
        String text = emit(out, style);
        if (!style.finalLineBreak() && changedByMissingFinalLineBreak(text)) {
            normalizeMultilineStrings(out, new IdentityHashMap<Node, Node>(), true);
            text = emit(out, style);
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

    /** The emitter's text for {@code out}, comments realigned and whitespace-only lines emptied. */
    private static String emit(Node out, DocumentStyle style) {
        StringWriter writer = new StringWriter();
        List<Event> events = serialize(out, writer, style);
        return WHITESPACE_ONLY_LINE.matcher(realignCommentsAfterBlankLines(writer.toString(), events)).replaceAll("");
    }

    /**
     * Whether dropping the emitted text's final line break (a file without one keeps that style) would change a value:
     * only a block scalar running to the end of the document can be changed, e.g. {@code |} ending a file whose last
     * value ends with a line break. Then every multi-line string is written double-quoted instead (the #592
     * measurement's adjacent finding: quoting every block scalar of such a file whenever it is written changed bytes no
     * write owned, so the write gate refused every write to it).
     */
    private static boolean changedByMissingFinalLineBreak(String text) {
        if (!text.endsWith("\n")) {
            return false;
        }
        try {
            return !PlainData.plainEquals(parse(text.substring(0, text.length() - 1)).toPlain(), parse(text).toPlain());
        } catch (ConfigParseException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Serializes {@code out} exactly as {@code Yaml#serialize(Node, Writer)} does (the same {@link Serializer},
     * {@link Emitter}, options and resolver), recording every event the emitter receives, so the comment lines it
     * writes can be located afterwards by position (17-64 route change (i)).
     *
     * @return the events, in the order the emitter wrote them
     */
    private static List<Event> serialize(Node out, Writer writer, DocumentStyle style) {
        DumperOptions options = dumperOptions(style);
        Emitter emitter = new Emitter(writer, options);
        List<Event> events = new ArrayList<>();
        Serializer serializer = new Serializer(event -> {
            events.add(event);
            emitter.emit(event);
        }, new Resolver(), options, null);
        try {
            serializer.open();
            serializer.serialize(out);
            serializer.close();
        } catch (IOException e) {
            throw new YAMLException(e);
        }
        return events;
    }

    /**
     * Puts back every comment line SnakeYAML's emitter misplaces after a blank line (17-64 review round 1 R1-01,
     * route change (i)). The emitter ({@code Emitter#writeCommentLines}, SnakeYAML 2.2) writes the first comment
     * line after a blank line of a comment run at its current indentation plus the run's own column again - doubled
     * indentation in a nested block - and every other line of the run at the run's column.
     * <p>
     * Nothing is injected into the document's text: the lines are located by position. The comment events the
     * emitter received (in order, each carrying its original line's position) are matched one to one with the comment
     * events of the emitted text read back; when the two sequences differ in length, type or text (a blank line
     * matches a blank line by type: in memory its text is empty, read back it is the line break), the emitted text is
     * returned unchanged. Each misplaced line - a comment line right after a blank line, with an earlier comment
     * line in the same run - is written at its own original column, or, for a line added in memory (it has no
     * position), at the column of the run's first comment line as emitted. Lines are located with the reader's own
     * line numbering ({@link #moveComments}), and every move is checked before any is applied, so a misalignment
     * leaves the output as emitted (review round 3).
     */
    @SuppressWarnings("PMD.NPathComplexity") // Each move is located and checked against the reader's line breaks before any is applied.
    private static String realignCommentsAfterBlankLines(String text, List<Event> events) {
        List<CommentEvent> written = new ArrayList<>();
        for (Event event : events) {
            if (event instanceof CommentEvent) {
                written.add((CommentEvent) event);
            }
        }
        if (written.isEmpty()) {
            return text;
        }
        List<CommentEvent> read = new ArrayList<>();
        try (Reader reader = new StringReader(text)) {
            for (Event event : new Yaml(loaderOptions()).parse(reader)) {
                if (event instanceof CommentEvent) {
                    read.add((CommentEvent) event);
                }
            }
        } catch (IOException | YAMLException e) {
            return text;
        }
        if (read.size() != written.size()) {
            return text;
        }
        for (int i = 0; i < read.size(); i++) {
            CommentType type = read.get(i).getCommentType();
            if (type != written.get(i).getCommentType()
                    || type != CommentType.BLANK_LINE && !read.get(i).getValue().equals(written.get(i).getValue())) {
                return text;
            }
        }
        List<CommentMove> moves = new ArrayList<>();
        int index = 0;
        int firstColumn = -1;
        boolean afterBlank = false;
        for (Event event : events) {
            if (!(event instanceof CommentEvent) || ((CommentEvent) event).getCommentType() == CommentType.IN_LINE) {
                index += event instanceof CommentEvent ? 1 : 0;
                firstColumn = -1;
                afterBlank = false;
                continue;
            }
            CommentEvent comment = (CommentEvent) event;
            CommentEvent back = read.get(index++);
            org.yaml.snakeyaml.error.Mark at = back.getStartMark();
            if (comment.getCommentType() == CommentType.BLANK_LINE) {
                afterBlank = firstColumn >= 0;
                continue;
            }
            if (firstColumn < 0) {
                firstColumn = at.getColumn();
            } else if (afterBlank) {
                int column = comment.getStartMark() != null ? comment.getStartMark().getColumn() : firstColumn;
                if (column != at.getColumn()) {
                    moves.add(new CommentMove(at.getLine(), at.getColumn(), column, "#" + back.getValue()));
                }
            }
            afterBlank = false;
        }
        return moves.isEmpty() ? text : moveComments(text, moves);
    }

    /** One comment line to re-indent: where the reader found it, and what must be there for the move to apply. */
    static final class CommentMove {
        private final int line;
        private final int column;
        private final int target;
        private final String expected;

        /**
         * @param line     the line, numbered as SnakeYAML's reader numbers it (0-based)
         * @param column   the column of its {@code #} in the emitted text
         * @param target   the column to write it at
         * @param expected the line's exact text from its {@code #} to the line break: {@code "#"} + the comment
         */
        CommentMove(int line, int column, int target, String expected) {
            this.line = line;
            this.column = column;
            this.target = target;
            this.expected = expected;
        }
    }

    /**
     * Re-indents comment lines of {@code text}; every other character is copied unchanged. Lines are counted exactly as
     * SnakeYAML's reader counts them - {@code \r\n}, {@code \r}, {@code \n}, U+0085, U+2028, U+2029 each end a line
     * (review round 3 R3-01: the emitter writes U+2028 and U+2029 raw inside a value). Before any line is changed every
     * move is checked: its line must hold, at the expected column, exactly {@code #} plus its comment, with only spaces
     * before it; when one move fails the check, {@code text} is returned as it is, so a misalignment can never move a
     * line it did not mean (review round 3 self-check).
     *
     * @param text  the emitted text
     * @param moves the lines to re-indent
     * @return the realigned text, or {@code text} unchanged
     */
    static String moveComments(String text, List<CommentMove> moves) {
        List<int[]> lines = readerLines(text);
        Map<Integer, CommentMove> byLine = new java.util.TreeMap<>();
        for (CommentMove move : moves) {
            if (move.line < 0 || move.line >= lines.size() || byLine.put(move.line, move) != null) {
                return text;
            }
            String content = text.substring(lines.get(move.line)[0], lines.get(move.line)[1]);
            if (move.column >= content.length() || !content.substring(move.column).equals(move.expected)
                    || !content.substring(0, move.column).replace(" ", "").isEmpty()) {
                return text;
            }
        }
        StringBuilder result = new StringBuilder(text.length());
        int copied = 0;
        for (CommentMove move : byLine.values()) {
            int start = lines.get(move.line)[0];
            result.append(text, copied, start);
            for (int i = 0; i < move.target; i++) {
                result.append(' ');
            }
            copied = start + move.column;
        }
        result.append(text, copied, text.length());
        return result.toString();
    }

    /** Each line's {start, end of content} in {@code text}, with SnakeYAML's reader's line breaks. */
    private static List<int[]> readerLines(String text) {
        List<int[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean crlf = c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n';
            if (crlf || c == '\r' || c == '\n' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                lines.add(new int[] {start, i});
                i += crlf ? 1 : 0;
                start = i + 1;
            }
        }
        lines.add(new int[] {start, text.length()});
        return lines;
    }

    /** Escapes preserve NEL (which the scanner normalizes) and no-EOF multiline string content. */
    private static Node normalizeMultilineStrings(Node node, Map<Node, Node> normalized, boolean preserveNoEof) {
        Node previous = normalized.get(node);
        if (previous != null) {
            return previous;
        }
        normalized.put(node, node);
        if (node instanceof ScalarNode && Tag.STR.equals(node.getTag())) {
            ScalarNode scalar = (ScalarNode) node;
            if ((scalar.getValue().indexOf('\u0085') >= 0 || preserveNoEof
                    && (scalar.getValue().indexOf('\r') >= 0 || scalar.getValue().indexOf('\n') >= 0))
                    && scalar.getScalarStyle() != DumperOptions.ScalarStyle.DOUBLE_QUOTED) {
                ScalarNode replacement = new ScalarNode(scalar.getTag(), scalar.getValue(), scalar.getStartMark(),
                        scalar.getEndMark(), DumperOptions.ScalarStyle.DOUBLE_QUOTED);
                carryPresentation(node, replacement);
                normalized.put(node, replacement);
                return replacement;
            }
        } else if (node instanceof MappingNode) {
            List<NodeTuple> tuples = ((MappingNode) node).getValue();
            for (int i = 0; i < tuples.size(); i++) {
                NodeTuple tuple = tuples.get(i);
                Node key = normalizeMultilineStrings(tuple.getKeyNode(), normalized, preserveNoEof);
                Node value = normalizeMultilineStrings(tuple.getValueNode(), normalized, preserveNoEof);
                if (key != tuple.getKeyNode() || value != tuple.getValueNode()) {
                    tuples.set(i, new NodeTuple(key, value));
                }
            }
        } else if (node instanceof SequenceNode) {
            List<Node> elements = ((SequenceNode) node).getValue();
            for (int i = 0; i < elements.size(); i++) {
                elements.set(i, normalizeMultilineStrings(elements.get(i), normalized, preserveNoEof));
            }
        } else if (node instanceof AnchorNode) {
            Node real = ((AnchorNode) node).getRealNode();
            Node replacement = normalizeMultilineStrings(real, normalized, preserveNoEof);
            if (replacement != real) {
                AnchorNode anchor = new AnchorNode(replacement);
                carryPresentation(node, anchor);
                normalized.put(node, anchor);
                return anchor;
            }
        }
        return node;
    }

    private static void carryPresentation(Node from, Node to) {
        to.setAnchor(from.getAnchor());
        to.setType(from.getType());
        to.setTwoStepsConstruction(from.isTwoStepsConstruction());
        carryComments(from, to);
    }

    @SuppressWarnings("PMD.NPathComplexity") // Node updates preserve type, whole-key order, aliases and comment ownership independently.
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
        retainDescendantEnds(old, created);
        return created;
    }

    private void merge(MappingNode old, Map<?, ?> oldPlain, Map<?, ?> value) {
        List<NodeTuple> tuples = old.getValue();
        removeTuples(old, tuple -> !Tag.MERGE.equals(tuple.getKeyNode().getTag())
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

    private MappingNode childMapping(MappingNode mapping, String key, Object child) {
        int index = indexOf(mapping, key);
        if (index >= 0) {
            NodeTuple tuple = mapping.getValue().get(index);
            if (tuple.getValueNode() instanceof MappingNode && Tag.MAP.equals(tuple.getValueNode().getTag())) {
                return (MappingNode) tuple.getValueNode();
            }
            MappingNode created = child instanceof Map ? (MappingNode) newNode(child)
                    : new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
            carryComments(tuple.getValueNode(), created);
            retainDescendantEnds(tuple.getValueNode(), created);
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
            transplantNode(root, fresh, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
            claimCommentOccurrences(fresh, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()),
                    Collections.newSetFromMap(new IdentityHashMap<CommentLine, Boolean>()));
        }
        return fresh;
    }

    /** Carries all comment positions and the collection presentation that makes them emit correctly. */
    private void transplantNode(Node from, Node to, Set<Node> retained) {
        carryComments(from, to);
        retained.add(from);
        if (from instanceof MappingNode && to instanceof MappingNode) {
            ((MappingNode) to).setFlowStyle(((MappingNode) from).getFlowStyle());
            transplantComments((MappingNode) from, (MappingNode) to, retained);
        } else if (from instanceof SequenceNode && to instanceof SequenceNode) {
            ((SequenceNode) to).setFlowStyle(((SequenceNode) from).getFlowStyle());
            List<Node> old = ((SequenceNode) from).getValue();
            List<Node> fresh = ((SequenceNode) to).getValue();
            for (int i = 0; i < Math.min(old.size(), fresh.size()); i++) {
                transplantNode(old.get(i), fresh.get(i), retained);
            }
            List<CommentLine> ends = new ArrayList<>();
            for (int i = fresh.size(); i < old.size(); i++) {
                collectEnds(old.get(i), ends, retained);
            }
            prependEnds(to, ends);
        } else {
            List<CommentLine> ends = new ArrayList<>();
            collectDescendantEnds(from, ends, retained);
            prependEnds(to, ends);
        }
    }

    private void transplantComments(MappingNode from, MappingNode to, Set<Node> retained) {
        Set<NodeTuple> matched = Collections.newSetFromMap(new IdentityHashMap<NodeTuple, Boolean>());
        for (NodeTuple tuple : to.getValue()) {
            int index = indexOf(from, keyIdentity(tuple.getKeyNode()));
            if (index < 0) {
                continue;
            }
            NodeTuple original = from.getValue().get(index);
            matched.add(original);
            transplantNode(original.getKeyNode(), tuple.getKeyNode(), retained);
            transplantNode(original.getValueNode(), tuple.getValueNode(), retained);
        }
        List<CommentLine> ends = new ArrayList<>();
        for (NodeTuple tuple : from.getValue()) {
            if (!matched.contains(tuple)) {
                collectEnds(tuple.getKeyNode(), ends, retained);
                collectEnds(tuple.getValueNode(), ends, retained);
            }
        }
        appendMappingEnds(to, ends);
    }

    /** Every tuple deletion uses the same post-order preservation of section end comments. */
    private static void removeTuples(MappingNode mapping, java.util.function.Predicate<NodeTuple> removed) {
        List<NodeTuple> dropped = new ArrayList<>();
        mapping.getValue().removeIf(tuple -> {
            if (!removed.test(tuple)) {
                return false;
            }
            dropped.add(tuple);
            return true;
        });
        List<CommentLine> ends = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        for (NodeTuple tuple : dropped) {
            collectEnds(tuple.getKeyNode(), ends, seen);
            collectEnds(tuple.getValueNode(), ends, seen);
        }
        appendMappingEnds(mapping, ends);
    }

    private static void retainDescendantEnds(Node from, Node to) {
        List<CommentLine> ends = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        seen.add(from);
        collectDescendantEnds(from, ends, seen);
        prependEnds(to, ends);
    }

    private static void collectDescendantEnds(Node node, List<CommentLine> ends, Set<Node> seen) {
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                collectEnds(tuple.getKeyNode(), ends, seen);
                collectEnds(tuple.getValueNode(), ends, seen);
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                collectEnds(element, ends, seen);
            }
        } else if (node instanceof AnchorNode) {
            collectEnds(((AnchorNode) node).getRealNode(), ends, seen);
        }
    }

    private static void collectEnds(Node node, List<CommentLine> ends, Set<Node> seen) {
        if (!seen.add(node)) {
            return;
        }
        collectDescendantEnds(node, ends, seen);
        if (node.getEndComments() != null) {
            ends.addAll(node.getEndComments());
        }
    }

    private static void appendMappingEnds(MappingNode mapping, List<CommentLine> ends) {
        if (ends.isEmpty()) {
            return;
        }
        if (mapping.getValue().isEmpty()) {
            prependEnds(mapping, ends);
        } else {
            Node recipient = mapping.getValue().get(mapping.getValue().size() - 1).getValueNode();
            List<CommentLine> comments = recipient.getEndComments() == null
                    ? new ArrayList<CommentLine>() : new ArrayList<>(recipient.getEndComments());
            comments.addAll(ends);
            recipient.setEndComments(comments);
        }
    }

    private static void prependEnds(Node recipient, List<CommentLine> ends) {
        if (!ends.isEmpty()) {
            List<CommentLine> comments = new ArrayList<>(ends);
            if (recipient.getEndComments() != null) {
                comments.addAll(recipient.getEndComments());
            }
            recipient.setEndComments(comments);
        }
    }

    /** An expanded alias may carry the same physical comment object to several fresh nodes. */
    private static void claimCommentOccurrences(Node node, Set<Node> seen, Set<CommentLine> claimed) {
        if (!seen.add(node)) {
            return;
        }
        node.setBlockComments(unclaimedComments(node.getBlockComments(), claimed));
        node.setInLineComments(unclaimedComments(node.getInLineComments(), claimed));
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                claimCommentOccurrences(tuple.getKeyNode(), seen, claimed);
                claimCommentOccurrences(tuple.getValueNode(), seen, claimed);
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                claimCommentOccurrences(element, seen, claimed);
            }
        } else if (node instanceof AnchorNode) {
            claimCommentOccurrences(((AnchorNode) node).getRealNode(), seen, claimed);
        }
        node.setEndComments(unclaimedComments(node.getEndComments(), claimed));
    }

    private static List<CommentLine> unclaimedComments(List<CommentLine> comments, Set<CommentLine> claimed) {
        if (comments == null) {
            return null;
        }
        List<CommentLine> result = new ArrayList<>();
        for (CommentLine line : comments) {
            if (claimed.add(line)) {
                result.add(line);
            }
        }
        return result.isEmpty() ? null : result;
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
    @SuppressWarnings("PMD.NPathComplexity") // Independent escape-style options are applied in the existing scan order.
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

    static String sha256(byte[] bytes) {
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

    private static DumperOptions dumperOptions(DocumentStyle style) {
        DumperOptions options = style.dumperOptions();
        // Keep the anchor names the file uses; only nodes shared by an alias are anchored on output.
        options.setAnchorGenerator(node -> node.getAnchor() != null ? node.getAnchor() : "id" + System.identityHashCode(node));
        return options;
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
            if (path.size() >= NESTING_DEPTH_LIMIT) {
                throw new YAMLException("Config nesting depth exceeds " + NESTING_DEPTH_LIMIT + " after alias expansion");
            }
            if (!path.add(node)) {
                throw new YAMLException("A recursive alias refers to its own collection " + node.getStartMark());
            }
        }
    }

    /** The two comment placements adjusted on read (see the class description). */
    private static final class CommentPlacement {

        @SuppressWarnings("PMD.UnnecessaryConstructor") // Static-only utility must not expose construction.
        private CommentPlacement() {
        }

        static void adjust(MappingNode root) {
            splitHeader(root);
            reclaimAfterBlockScalars(root, root, null, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
            relocate(root, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
        }

        /**
         * #592: SnakeYAML 2.2's scanner reads every comment line with a column above 0 that follows a multi-line block
         * scalar ({@code |} or {@code >}) as an in-line comment of that scalar, which the emitter then writes at column
         * 0 - and the key below reads as uncommented. Each such line (any in-line comment starting on a line after the
         * scalar's own first line; a comment on the indicator line, {@code |- # note}, is the key's and is untouched)
         * is given back, as a block comment with its original position, to the node that follows it in the file: the
         * next key or list item at the same level, or for the last value of a section the next one further out, or
         * for the last value of the document the document's end. That is exactly where SnakeYAML puts a comment after
         * a plain value, so {@link #relocate} then applies the same deeper-indentation rule to both.
         *
         * @param node      the node to walk
         * @param root      the document's top-level mapping (receives the lines after the document's last value)
         * @param following the node read right after {@code node}'s subtree, {@code null} at the end of the document
         * @param seen      nodes already walked (an alias is walked once)
         */
        private static void reclaimAfterBlockScalars(Node node, MappingNode root, Node following, Set<Node> seen) {
            if (!seen.add(node)) {
                return;
            }
            if (node instanceof ScalarNode) {
                reclaim((ScalarNode) node, root, following);
            } else if (node instanceof MappingNode) {
                List<NodeTuple> tuples = ((MappingNode) node).getValue();
                for (int i = 0; i < tuples.size(); i++) {
                    reclaimAfterBlockScalars(tuples.get(i).getValueNode(), root,
                            i + 1 < tuples.size() ? tuples.get(i + 1).getKeyNode() : following, seen);
                }
            } else if (node instanceof SequenceNode) {
                List<Node> elements = ((SequenceNode) node).getValue();
                for (int i = 0; i < elements.size(); i++) {
                    reclaimAfterBlockScalars(elements.get(i), root, i + 1 < elements.size() ? elements.get(i + 1) : following, seen);
                }
            }
        }

        private static void reclaim(ScalarNode scalar, MappingNode root, Node following) {
            List<CommentLine> inline = scalar.getInLineComments();
            if (inline == null || inline.isEmpty() || scalar.getStartMark() == null
                    || scalar.getScalarStyle() != DumperOptions.ScalarStyle.LITERAL
                    && scalar.getScalarStyle() != DumperOptions.ScalarStyle.FOLDED) {
                return;
            }
            List<CommentLine> keep = new ArrayList<>();
            List<CommentLine> moved = new ArrayList<>();
            for (CommentLine line : inline) {
                if (line.getStartMark() != null && line.getStartMark().getLine() > scalar.getStartMark().getLine()) {
                    moved.add(new CommentLine(line.getStartMark(), line.getEndMark(), line.getValue(), CommentType.BLOCK));
                } else {
                    keep.add(line);
                }
            }
            if (moved.isEmpty()) {
                return;
            }
            scalar.setInLineComments(keep.isEmpty() ? null : keep);
            if (following == null) {
                if (root.getEndComments() != null) {
                    moved.addAll(root.getEndComments());
                }
                root.setEndComments(moved);
            } else {
                if (following.getBlockComments() != null) {
                    moved.addAll(following.getBlockComments());
                }
                following.setBlockComments(moved);
            }
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
