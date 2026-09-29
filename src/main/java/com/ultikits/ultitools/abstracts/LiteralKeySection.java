package com.ultikits.ultitools.abstracts;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;

/**
 * A configuration section whose own keys are data, not paths (UltiKits/UltiTools-Reborn#553): a map
 * key such as {@code my.rule} stays one key instead of becoming {@code my} -> {@code rule}.
 * <p>
 * It is an ordinary child of the configuration it sits in - same root, same {@code '.'} separator,
 * its own path and parent - so {@code getKeys(true)}, {@code getValues(true)}, {@code
 * getCurrentPath()} and every path into its other entries behave exactly as in any section. Only a
 * key containing the separator differs: it is stored whole, {@link #get(String, Object)}, {@link
 * #getComments(String)} and {@link #getInlineComments(String)} find it by that whole key, and the YAML
 * writer writes it back as one key. Such an entry is created only by {@link #copyFrom}; the ordinary
 * {@code set} of a path with a separator in it navigates as usual and cannot reach it.
 * <p>
 * Framework-internal: built by {@link AbstractConfigEntity} when a map-valued entry is read or
 * written; a module never constructs one.
 */
final class LiteralKeySection extends MemorySection {

    /**
     * A key no configuration holds: an entry is created under it through the ordinary {@code set},
     * then moved to its whole key in this section's own map.
     */
    private static final String STAGING_KEY = "\u0000literal-key-staging";

    /** The data of every entry whose key contains the path separator, by that whole key. */
    private final Map<String, Object> wholeKeyValues = new HashMap<>();
    private final Map<String, List<String>> wholeKeyComments = new HashMap<>();
    private final Map<String, List<String>> wholeKeyInlineComments = new HashMap<>();

    /**
     * @param parent the section this one is a child of
     * @param path   this section's key in {@code parent}
     */
    LiteralKeySection(ConfigurationSection parent, String path) {
        super(parent, path);
    }

    /**
     * Copies every entry of {@code source} into this section, in order, keys kept whole, with each
     * entry's comments and inline comments; a nested section is copied as a nested {@code
     * LiteralKeySection}.
     *
     * @param source a section from any configuration, whatever its path separator
     */
    void copyFrom(ConfigurationSection source) {
        for (Map.Entry<String, Object> entry : source.getValues(false).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof ConfigurationSection) {
                LiteralKeySection child = new LiteralKeySection(this, key);
                child.copyFrom((ConfigurationSection) value);
                value = child;
            }
            putWhole(key, value, source.getComments(key), source.getInlineComments(key));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void putWhole(String key, Object value, List<String> comments, List<String> inlineComments) {
        super.set(STAGING_KEY, value);
        super.setComments(STAGING_KEY, comments);
        super.setInlineComments(STAGING_KEY, inlineComments);
        // The entry type is not visible outside Bukkit's package, so the move goes through the raw map.
        Map raw = map;
        raw.put(key, raw.remove(STAGING_KEY));
        if (key.indexOf(separator()) >= 0) {
            wholeKeyValues.put(key, value);
            wholeKeyComments.put(key, comments);
            wholeKeyInlineComments.put(key, inlineComments);
        }
    }

    private char separator() {
        Configuration root = getRoot();
        return root == null ? '.' : root.options().pathSeparator();
    }

    @Override
    public Object get(String path, Object def) {
        if (wholeKeyValues.containsKey(path)) {
            return wholeKeyValues.get(path);
        }
        return super.get(path, def);
    }

    @Override
    public List<String> getComments(String path) {
        if (wholeKeyComments.containsKey(path)) {
            List<String> comments = wholeKeyComments.get(path);
            return comments == null ? Collections.<String>emptyList() : comments;
        }
        return super.getComments(path);
    }

    @Override
    public List<String> getInlineComments(String path) {
        if (wholeKeyInlineComments.containsKey(path)) {
            List<String> comments = wholeKeyInlineComments.get(path);
            return comments == null ? Collections.<String>emptyList() : comments;
        }
        return super.getInlineComments(path);
    }
}
