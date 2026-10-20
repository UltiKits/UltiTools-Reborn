package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * BuildContext carries the contextual information available while building a Widget tree.
 * <p>
 * It holds the information the build process needs to access, such as:
 * <ul>
 *   <li>the current player</li>
 *   <li>GUI configuration (row count, title, etc.)</li>
 *   <li>properties inherited from the parent Widget</li>
 *   <li>custom context data</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * @Override
 * public Widget build(BuildContext context) {
 *     Player player = context.getPlayer();
 *     int rows = context.getRows();
 *
 *     return Container.builder()
 *         .title("Welcome, " + player.getName())
 *         .rows(rows)
 *         .child(buildContent(context))
 *         .build();
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class BuildContext {

    @NotNull
    private final Player player;

    @NotNull
    private final String guiId;

    private final int rows;

    @Nullable
    private final Element parentElement;

    @NotNull
    private final Map<String, Object> inheritedProperties;

    private BuildContext(@NotNull Builder builder) {
        this.player = builder.player;
        this.guiId = builder.guiId;
        this.rows = builder.rows;
        this.parentElement = builder.parentElement;
        this.inheritedProperties = new HashMap<>(builder.inheritedProperties);
    }

    /**
     * Creates the root BuildContext.
     *
     * @param player the player
     * @param guiId  the GUI ID
     * @param rows   the row count
     * @return a new BuildContext
     */
    @NotNull
    public static BuildContext root(@NotNull Player player, @NotNull String guiId, int rows) {
        return new Builder(player, guiId, rows).build();
    }

    /**
     * Creates a child Context for this Context.
     * Used to pass a parent Widget's properties down to a child Widget.
     *
     * @return the child BuildContext
     */
    @NotNull
    public BuildContext child(@NotNull Element parentElement) {
        return new Builder(this)
                .parentElement(parentElement)
                .build();
    }

    // Getters

    /**
     * Gets the current player.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the GUI ID.
     *
     * @return the GUI ID
     */
    @NotNull
    public String getGuiId() {
        return guiId;
    }

    /**
     * Gets the GUI row count.
     *
     * @return the row count
     */
    public int getRows() {
        return rows;
    }

    /**
     * Gets the total number of GUI slots.
     *
     * @return the total slot count
     */
    public int getSize() {
        return rows * 9;
    }

    /**
     * Gets the parent Element.
     *
     * @return the parent Element, or null at the root
     */
    @Nullable
    public Element getParentElement() {
        return parentElement;
    }

    /**
     * Gets an inherited property value.
     *
     * @param key the property name
     * @return the property value, or null if it does not exist
     */
    @Nullable
    public Object getInheritedProperty(@NotNull String key) {
        return inheritedProperties.get(key);
    }

    /**
     * Checks whether the given inherited property exists.
     *
     * @param key the property name
     * @return true if it exists
     */
    public boolean hasInheritedProperty(@NotNull String key) {
        return inheritedProperties.containsKey(key);
    }

    /**
     * Creates a new Builder that copies this Context's configuration.
     */
    @NotNull
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Builder for BuildContext.
     */
    public static class Builder {
        @NotNull
        private final Player player;
        @NotNull
        private final String guiId;
        private int rows;
        @Nullable
        private Element parentElement;
        @NotNull
        private final Map<String, Object> inheritedProperties = new HashMap<>();

        public Builder(@NotNull Player player, @NotNull String guiId, int rows) {
            this.player = player;
            this.guiId = guiId;
            this.rows = rows;
        }

        private Builder(@NotNull BuildContext context) {
            this.player = context.player;
            this.guiId = context.guiId;
            this.rows = context.rows;
            this.parentElement = context.parentElement;
            this.inheritedProperties.putAll(context.inheritedProperties);
        }

        public Builder rows(int rows) {
            this.rows = rows;
            return this;
        }

        public Builder parentElement(@Nullable Element parentElement) {
            this.parentElement = parentElement;
            return this;
        }

        public Builder inheritedProperty(@NotNull String key, @Nullable Object value) {
            if (value == null) {
                this.inheritedProperties.remove(key);
            } else {
                this.inheritedProperties.put(key, value);
            }
            return this;
        }

        public BuildContext build() {
            return new BuildContext(this);
        }
    }
}
