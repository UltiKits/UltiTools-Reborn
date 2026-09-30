package com.ultikits.ultitools.interfaces.impl.pasers;

import java.util.function.Consumer;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

/**
 * The one place a dotted map key is refused (UltiKits/UltiTools-Reborn#553; maintainer decision of
 * 2026-09-30, "refuse, and say so plainly"). The configuration file uses {@code '.'} as its path
 * separator, so a map key such as {@code my.rule} cannot be stored as one key: Bukkit's loader reads it
 * as {@code my} -> {@code rule}, and quoting it does not help. The two built-in serializers ({@link
 * DefaultConfigParser}, {@link StringHashMapParser}) call {@link #storable} for every key at the moment
 * they turn a map into a configuration section - which covers a map field, a map nested in a map, a map
 * inside an object and a map inside a list - and leave a refused key out.
 * <p>
 * A serializer implementing this interface is told where to report a refused key: the configuration
 * entity passes a sink that names the file, the entry and the module, or a silent one for its own
 * comparisons. A serializer used without a sink reports to its own logger.
 * <p>
 * Framework-internal: {@code public} only because the configuration entity lives in another package.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public interface DottedMapKeyRefusal {

    /**
     * @param sink receives each refused key, as written; {@code null} restores the default (this
     *             serializer's own logger)
     */
    void reportRefusedKeysTo(Consumer<String> sink);

    /**
     * Whether {@code key} can be stored as one map key, reporting it to {@code sink} (or, without one,
     * to {@code fallback}) when it cannot.
     *
     * @param key      the key as it will be written
     * @param sink     where to report a refusal, or {@code null}
     * @param fallback the logger used without a sink
     * @return {@code true} if the key contains no {@code '.'}
     */
    static boolean storable(String key, Consumer<String> sink, Logger fallback) {
        if (key.indexOf('.') < 0) {
            return true;
        }
        if (sink != null) {
            sink.accept(key);
        } else {
            fallback.warning("Map key '" + key + "' contains '.', which the configuration file reads as a path"
                    + " separator, so it cannot be stored as one key; the entry was not written - rename the key"
                    + " (for example with '-' or '_')");
        }
        return false;
    }
}
