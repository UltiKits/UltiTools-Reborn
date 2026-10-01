package com.ultikits.ultitools.config.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
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
 * {@code yes:} is the key {@code "true"}, {@code 1:} the key {@code "1"}. Unlike Bukkit, a key is never
 * split at {@code .}: {@code "o.O"} and {@code wave.} are single keys. A key's original node is kept, so
 * it is written back with its original text.
 * <p>
 * <b>Writing.</b> {@link #set(List, Object)} accepts plain data only (see {@link PlainData}) and changes
 * only the nodes it addresses; {@link #render()} serializes the node tree, so every other node keeps its
 * text and comments.
 * <p>
 * Instances are not thread-safe; the entity that owns a document confines it.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConfigDocument {

    /** Bukkit's {@code YamlConfiguration} nesting limit, kept for compatibility. */
    static final int NESTING_DEPTH_LIMIT = 100;

    private MappingNode root;
    private final Map<String, Object> plain;
    private final NodeConstructor keyConstructor = new NodeConstructor();

    private ConfigDocument(MappingNode root, Map<String, Object> plain) {
        this.root = root;
        this.plain = plain;
    }

    /**
     * Returns an empty document, as for a file that does not exist yet.
     *
     * @return a document without keys
     */
    public static ConfigDocument empty() {
        return new ConfigDocument(null, new LinkedHashMap<String, Object>());
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
        if (written == null) {
            return new ConfigDocument(null, new LinkedHashMap<String, Object>());
        }
        if (!(written instanceof MappingNode)) {
            throw new ConfigParseException("Top level is not a map (found a " + written.getNodeId() + ")", null);
        }
        // The plain view is built from a second, independent tree: reading flattens merge keys and drops
        // duplicate keys in place (as Bukkit does), which must not touch the tree that is written back.
        Node read = compose(text);
        try {
            Map<String, Object> plain = new PlainReader().readMapping((MappingNode) read);
            return new ConfigDocument((MappingNode) written, plain);
        } catch (YAMLException e) {
            throw new ConfigParseException(e.getMessage(), e);
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
     * Stores a plain value at {@code path}, creating missing mappings on the way.
     *
     * @param path  the key path, at least one key
     * @param value plain data
     * @throws IllegalArgumentException if {@code value} is not plain data (the message names the key path
     *                                  and the class) or the path is empty
     */
    public void set(List<String> path, Object value) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("A config key path needs at least one key");
        }
        PlainData.requirePlain(path, value);
        Object copy = PlainData.copy(value);
        if (root == null) {
            root = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
        }
        MappingNode mapping = root;
        Map<String, Object> plainMapping = plain;
        for (int i = 0; i < path.size() - 1; i++) {
            String key = path.get(i);
            mapping = childMapping(mapping, key);
            plainMapping = childPlainMapping(plainMapping, key);
        }
        String last = path.get(path.size() - 1);
        int index = indexOf(mapping, last);
        Node valueNode = representer().represent(copy);
        if (index < 0) {
            mapping.getValue().add(new NodeTuple(representer().represent(last), valueNode));
        } else {
            NodeTuple old = mapping.getValue().get(index);
            carryComments(old.getValueNode(), valueNode);
            mapping.getValue().set(index, new NodeTuple(old.getKeyNode(), valueNode));
        }
        plainMapping.put(last, copy);
    }

    /**
     * Serializes the document.
     *
     * @return the file text
     */
    public String render() {
        if (root == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        dumper().serialize(root, writer);
        return writer.toString();
    }

    private MappingNode childMapping(MappingNode mapping, String key) {
        int index = indexOf(mapping, key);
        if (index >= 0) {
            NodeTuple tuple = mapping.getValue().get(index);
            if (tuple.getValueNode() instanceof MappingNode) {
                return (MappingNode) tuple.getValueNode();
            }
            MappingNode created = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
            carryComments(tuple.getValueNode(), created);
            mapping.getValue().set(index, new NodeTuple(tuple.getKeyNode(), created));
            return created;
        }
        MappingNode created = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
        mapping.getValue().add(new NodeTuple(representer().represent(key), created));
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

    /** Index of the last tuple whose key identity equals {@code key} (the last duplicate wins, as on read). */
    private int indexOf(MappingNode mapping, String key) {
        List<NodeTuple> tuples = mapping.getValue();
        for (int i = tuples.size() - 1; i >= 0; i--) {
            Node keyNode = tuples.get(i).getKeyNode();
            if (!Tag.MERGE.equals(keyNode.getTag()) && key.equals(String.valueOf(keyConstructor.construct(keyNode)))) {
                return i;
            }
        }
        return -1;
    }

    private static void carryComments(Node from, Node to) {
        to.setBlockComments(from.getBlockComments());
        to.setInLineComments(from.getInLineComments());
        to.setEndComments(from.getEndComments());
    }

    private static Node compose(String text) throws ConfigParseException {
        try (Reader reader = new UnicodeReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))) {
            return new Yaml(new NodeConstructor(), representer(), dumperOptions(), loaderOptions()).compose(reader);
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

    private static DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setProcessComments(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(Integer.MAX_VALUE);
        options.setNonPrintableStyle(DumperOptions.NonPrintableStyle.ESCAPE);
        return options;
    }

    private static Representer representer() {
        return new PlainRepresenter(dumperOptions());
    }

    private static Yaml dumper() {
        return new Yaml(new NodeConstructor(), representer(), dumperOptions(), loaderOptions());
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
     * Builds new nodes for plain values. A {@link Representer} rather than Bukkit's {@code YamlRepresenter}:
     * plain data needs no {@code ConfigurationSerializable} handling, and the plain-data boundary has already
     * refused every other type, so a Java-bean representation can never be reached.
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
            if (node instanceof ScalarNode || node instanceof MappingNode || node instanceof SequenceNode) {
                return constructor.construct(node);
            }
            throw new YAMLException("Unsupported node " + node.getNodeId() + " at " + node.getStartMark());
        }

        private void enter(Node node) {
            if (!path.add(node)) {
                throw new YAMLException("A recursive alias refers to its own collection " + node.getStartMark());
            }
        }
    }
}
