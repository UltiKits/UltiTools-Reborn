package com.ultikits.ultitools.interfaces.impl.pasers;

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

/**
 * The one place a dotted map key is refused (UltiKits/UltiTools-Reborn#553; maintainer decision of
 * 2026-09-30, "refuse, and say so plainly"; scope by the orchestrator's ruling of the same day). The
 * configuration file uses {@code '.'} as its path separator, so a map that Bukkit turns into a
 * configuration section - a map field, a map nested in such a map, a map inside an object - cannot
 * hold a key such as {@code my.rule}: the loader reads it back as {@code my} -> {@code rule}, and quoting
 * it does not help. A map that is a list element is plain data, which Bukkit keeps whole, so it is not
 * refused. The built-in serializers ({@link DefaultConfigParser}, {@link StringHashMapParser}) call
 * {@link #storable} for every key at the moment they build a section.
 * <p>
 * The configuration entity passes its warning sink to {@link #fileForm} as a parameter; the sink
 * receives the nested path of the map and the refused key. Across a serializer's overridable {@code
 * serializeToMemorySection} the pair travels in a thread-scoped context, never in the serializer's own
 * state, so a shared or concurrent serializer cannot misattribute a warning. Used without a sink, a
 * serializer reports to its own logger.
 * <p>
 * Framework-internal: {@code public} only because the configuration entity lives in another package.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public interface DottedMapKeyRefusal {

    /** Warning text for a key a write left out: the key, then the nested path of its map. */
    String REFUSED = "map key '%s' under key '%s' contains '.', which the configuration file reads as a path"
            + " separator, so it cannot be stored as one key; the entry was not written - rename the key (for"
            + " example with '-' or '_')";

    /** Warning text for a key a load found: the key, the nested path of its map, the split form. */
    String LOADED_SPLIT = "map key '%s' under key '%s' contains '.', which the configuration file reads as a path"
            + " separator, so it was loaded as '%s'; rename the key (for example with '-' or '_')";

    /**
     * The form in which the framework writes {@code value} for the entry at {@code path}, refusing every
     * dotted key of a map that becomes a section and reporting it to {@code refused}.
     *
     * @param value   the value, possibly {@code null}
     * @param path    the entry's path - the nested path of any map found in {@code value} is built on it
     * @param refused receives (nested path of the map, refused key); {@code null} means this serializer's
     *                own logger
     * @return the value to put into the configuration
     */
    Object fileForm(Object value, String path, BiConsumer<String, String> refused);

    /**
     * Whether {@code key} can be stored in a section, reporting it when it cannot.
     *
     * @param key      the key as it will be written
     * @param path     the nested path of the map the key belongs to
     * @param refused  where to report a refusal, or {@code null}
     * @param fallback the logger used without a sink
     * @return {@code true} if the key contains no {@code '.'}
     */
    static boolean storable(String key, String path, BiConsumer<String, String> refused, Logger fallback) {
        if (key.indexOf('.') < 0) {
            return true;
        }
        if (refused != null) {
            refused.accept(path, key);
        } else {
            fallback.warning(String.format(REFUSED, key, path));
        }
        return false;
    }

    /**
     * @param path  a nested path, possibly empty
     * @param child a key under it
     * @return the child's nested path
     */
    static String child(String path, String child) {
        return path == null || path.isEmpty() ? child : path + "." + child;
    }

    /** The nested path and sink of the {@link #fileForm} call running on this thread. */
    final class Context {
        private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

        final String path;
        final BiConsumer<String, String> refused;

        private Context(String path, BiConsumer<String, String> refused) {
            this.path = path;
            this.refused = refused;
        }

        /** The running call's context, or an empty one outside any call. */
        static Context current() {
            Context context = CURRENT.get();
            return context != null ? context : new Context("", null);
        }

        /** Runs {@code body} with {@code path} and {@code refused} as the current context. */
        static <T> T with(String path, BiConsumer<String, String> refused, Supplier<T> body) {
            Context previous = CURRENT.get();
            CURRENT.set(new Context(path, refused));
            try {
                return body.get();
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        }
    }
}
