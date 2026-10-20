package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * SlotKey uniquely identifies a Widget within the Widget tree.
 * <p>
 * In lists or dynamic content, SlotKey is essential to the diff algorithm. It lets the framework
 * recognize which Widgets are "the same one" (just with changed data) versus which were added or
 * removed, avoiding unnecessary rebuilds.
 * <p>
 * <b>When to use it:</b>
 * <ul>
 *   <li>List items need a stable key to preserve state (e.g. scroll position, selection state)</li>
 *   <li>Dynamic content needs a key to trigger the correct animation or transition</li>
 *   <li>Reusing an Element improves performance</li>
 * </ul>
 *
 * <p><strong>Best practice:</strong></p>
 * <pre>{@code
 * // Good: use a stable business identifier as the key
 * ListView.builder()
 *     .children(players.stream()
 *         .map(p -> PlayerWidget.builder()
 *             .key(SlotKey.of(p.getUniqueId().toString()))  // stable UUID
 *             .player(p)
 *             .build())
 *         .toList())
 *     .build();
 *
 * // Avoid: using the list index as the key (unless the list is static)
 * // This makes the key mismatch the actual content once the data updates
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see Widget#getKey()
 */
public final class SlotKey {

    @NotNull
    private final String value;

    private SlotKey(@NotNull String value) {
        this.value = value;
    }

    /**
     * Creates a SlotKey.
     *
     * @param value the key value, must not be empty
     * @return a new SlotKey instance
     * @throws IllegalArgumentException if value is empty
     */
    @NotNull
    public static SlotKey of(@NotNull String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("SlotKey value cannot be empty");
        }
        return new SlotKey(value);
    }

    /**
     * Creates a SlotKey with a prefix.
     *
     * @param prefix the prefix
     * @param value  the key value
     * @return a new SlotKey instance
     */
    @NotNull
    public static SlotKey of(@NotNull String prefix, @NotNull String value) {
        return new SlotKey(prefix + ":" + value);
    }

    /**
     * Gets the key's string value.
     *
     * @return the key value
     */
    @NotNull
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlotKey)) return false;
        SlotKey slotKey = (SlotKey) o;
        return value.equals(slotKey.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "SlotKey(" + value + ")";
    }
}
