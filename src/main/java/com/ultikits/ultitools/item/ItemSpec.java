package com.ultikits.ultitools.item;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import io.papermc.paper.datacomponent.DataComponentType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * Deeply immutable and thread-safe provided pass-through values are immutable
 * (see {@link Builder#data(DataComponentType.Valued, Object)}); equals/hashCode compare every attribute,
 * including marker values by content, and the GUI diff relies on it.
 * An {@link ItemRenderer} renders its name and lore once the viewer's locale is known; Paper does not
 * translate item text per client.
 *
 * <p>Building from a Key with amount, text, model, glint, tooltip flags and markers needs no running
 * server; builder(Material) and any DataComponentType argument need Paper's registries
 * (a server or MockBukkit).
 */
public final class ItemSpec {
    private static final Set<String> TYPED_COMPONENTS = Set.of(
            "minecraft:item_name", "minecraft:lore", "minecraft:item_model",
            "minecraft:enchantment_glint_override", "minecraft:tooltip_display");
    private final Key itemType;
    private final int amount;
    private final @Nullable Text name;
    private final List<Text> lore;
    private final @Nullable Key model;
    private final @Nullable Boolean glint;
    private final boolean tooltipHidden;
    private final Set<DataComponentType> hiddenInTooltip;
    private final Set<NamespacedKey> hiddenTooltipKeys;
    private final List<DataPatch> patches;
    private final List<ItemMarker<?, ?>> markers;

    private ItemSpec(Builder builder) {
        itemType = builder.itemType;
        amount = builder.amount;
        name = builder.name;
        lore = List.copyOf(builder.lore);
        model = builder.model;
        glint = builder.glint;
        tooltipHidden = builder.tooltipHidden;
        hiddenInTooltip = Collections.unmodifiableSet(new LinkedHashSet<>(builder.hiddenInTooltip.values()));
        hiddenTooltipKeys = Set.copyOf(builder.hiddenInTooltip.keySet());
        patches = List.copyOf(builder.patches);
        markers = List.copyOf(builder.markers.values());
    }

    /**
     * Returns a builder for the item key, initially with amount 1.
     * @throws IllegalArgumentException for {@code minecraft:air}
     */
    public static Builder builder(Key itemType) {
        Objects.requireNonNull(itemType, "itemType");
        if ("minecraft:air".equals(itemType.asString())) {
            throw new IllegalArgumentException("Air is not a renderable item: " + itemType);
        }
        return new Builder(itemType);
    }

    /**
     * Shorthand for {@code builder(material.getKey())}.
     * @throws IllegalArgumentException if the material is AIR or is not an item
     */
    public static Builder builder(Material material) {
        Objects.requireNonNull(material, "material");
        if (material == Material.AIR || !material.isItem()) {
            throw new IllegalArgumentException("Material is not an item: " + material);
        }
        return builder(material.getKey());
    }

    /** Returns the registry key of the item to create. */
    public Key itemType() {
        return itemType;
    }

    /** Returns the amount, in 1..99. */
    public int amount() {
        return amount;
    }

    /** Returns text for ITEM_NAME (the non-italic default name), or absent. */
    public @Nullable Text name() {
        return name;
    }

    /** Returns unmodifiable lore; empty means no LORE component is set. */
    public List<Text> lore() {
        return lore;
    }

    /** Returns ITEM_MODEL, or absent. */
    public @Nullable Key model() {
        return model;
    }

    /** Returns ENCHANTMENT_GLINT_OVERRIDE, or absent for the item's default. */
    public @Nullable Boolean glint() {
        return glint;
    }

    /** Returns whether TOOLTIP_DISPLAY hides the entire tooltip. */
    public boolean tooltipHidden() {
        return tooltipHidden;
    }

    /** Components whose tooltip lines are hidden ({@code TOOLTIP_DISPLAY} {@code hiddenComponents}); unmodifiable, may be empty. */
    public Set<DataComponentType> hiddenInTooltip() {
        return hiddenInTooltip;
    }

    /** Returns unmodifiable pass-through changes in insertion order. */
    public List<DataPatch> patches() {
        return patches;
    }

    /** Returns unmodifiable persistent-data markers in insertion order. */
    public List<ItemMarker<?, ?>> markers() {
        return markers;
    }

    /** Returns a builder initialized with every attribute of this description. */
    public Builder toBuilder() {
        Builder builder = new Builder(itemType);
        builder.amount = amount;
        builder.name = name;
        builder.lore.addAll(lore);
        builder.model = model;
        builder.glint = glint;
        builder.tooltipHidden = tooltipHidden;
        for (DataComponentType type : hiddenInTooltip) {
            builder.hiddenInTooltip.put(type.getKey(), type);
        }
        builder.patches.addAll(patches);
        for (ItemMarker<?, ?> marker : markers) {
            builder.markers.put(marker.key(), marker);
        }
        return builder;
    }

    /** Compares all attributes, including the order of patches and markers. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemSpec other)) {
            return false;
        }
        return amount == other.amount && tooltipHidden == other.tooltipHidden
                && itemType.equals(other.itemType) && Objects.equals(name, other.name)
                && lore.equals(other.lore) && Objects.equals(model, other.model)
                && Objects.equals(glint, other.glint) && patches.equals(other.patches)
                && markers.equals(other.markers) && hiddenTooltipKeys.equals(other.hiddenTooltipKeys);
    }

    /** Returns a hash of every attribute. */
    @Override
    public int hashCode() {
        return Objects.hash(itemType, amount, name, lore, model, glint, tooltipHidden, hiddenTooltipKeys, patches, markers);
    }

    /** Returns every attribute for diagnostics. */
    @Override
    public String toString() {
        return "ItemSpec[itemType=" + itemType + ", amount=" + amount + ", name=" + name + ", lore=" + lore
                + ", model=" + model + ", glint=" + glint + ", tooltipHidden=" + tooltipHidden
                + ", hiddenInTooltip=" + hiddenInTooltip + ", patches=" + patches + ", markers=" + markers + ']';
    }

    /** A non-thread-safe builder; repeated builds produce independent immutable descriptions. */
    public static final class Builder {
        private final Key itemType;
        private int amount = 1;
        private @Nullable Text name;
        private final List<Text> lore = new ArrayList<>();
        private @Nullable Key model;
        private @Nullable Boolean glint;
        private boolean tooltipHidden;
        private final Map<NamespacedKey, DataComponentType> hiddenInTooltip = new LinkedHashMap<>();
        private final List<DataPatch> patches = new ArrayList<>();
        private final Map<NamespacedKey, ItemMarker<?, ?>> markers = new LinkedHashMap<>();

        private Builder(Key itemType) {
            this.itemType = itemType;
        }

        /** Sets the amount. @throws IllegalArgumentException outside 1..99 */
        public Builder amount(int amount) {
            if (amount < 1 || amount > 99) {
                throw new IllegalArgumentException("amount must be in 1..99");
            }
            this.amount = amount;
            return this;
        }

        /** Sets the deferred name, or clears it with null. */
        public Builder name(@Nullable Text name) {
            this.name = name;
            return this;
        }

        /** Sets a translated name with uniquely named arguments. */
        public Builder name(MessageKey key, Arg... args) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(args, "args");
            return name(Text.of(key, args));
        }

        /** Replaces lore with a defensive copy of the lines. */
        public Builder lore(List<? extends Text> lines) {
            Objects.requireNonNull(lines, "lines");
            List<Text> copy = List.copyOf(lines);
            lore.clear();
            lore.addAll(copy);
            return this;
        }

        /** Appends a deferred lore line. */
        public Builder addLore(Text line) {
            lore.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /** Appends a translated lore line with uniquely named arguments. */
        public Builder addLore(MessageKey key, Arg... args) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(args, "args");
            return addLore(Text.of(key, args));
        }

        /** Sets ITEM_MODEL, or clears the override with null. */
        public Builder model(@Nullable Key model) {
            this.model = model;
            return this;
        }

        /** Sets the glint override, or clears it with null. */
        public Builder glint(@Nullable Boolean glint) {
            this.glint = glint;
            return this;
        }

        /** Sets whether the whole tooltip is hidden. */
        public Builder tooltipHidden(boolean hidden) {
            tooltipHidden = hidden;
            return this;
        }

        /**
         * Replaces the set of components whose tooltip lines are hidden, for example
         * {@code DataComponentTypes.ATTRIBUTE_MODIFIERS} to hide "+7 Attack Damage". Any component type is
         * allowed. An empty set hides none. Types are compared by key; for two types with the same key the
         * first is kept. The renderer writes it together with {@link #tooltipHidden(boolean)} into one
         * {@code TOOLTIP_DISPLAY} value.
         */
        public Builder hiddenInTooltip(Set<? extends DataComponentType> types) {
            Objects.requireNonNull(types, "types");
            Map<NamespacedKey, DataComponentType> copy = new LinkedHashMap<>();
            for (DataComponentType type : types) {
                Objects.requireNonNull(type, "type");
                copy.putIfAbsent(type.getKey(), type);
            }
            hiddenInTooltip.clear();
            hiddenInTooltip.putAll(copy);
            return this;
        }

        /**
         * Appends a component value, applied unchanged and without localization.
         * The value must be immutable with value equals; Paper's own component values are.
         * @throws IllegalArgumentException for any of the five typed component keys
         */
        public <T> Builder data(DataComponentType.Valued<T> type, T value) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            validatePassThrough(type);
            patches.add(new DataPatch.Assign<>(type, value));
            return this;
        }

        /**
         * Appends a flag component.
         * @throws IllegalArgumentException for any of the five typed component keys
         */
        public Builder data(DataComponentType.NonValued type) {
            Objects.requireNonNull(type, "type");
            validatePassThrough(type);
            patches.add(new DataPatch.Flag(type));
            return this;
        }

        /**
         * Removes a prototype component.
         * @throws IllegalArgumentException for any of the five typed component keys
         */
        public Builder remove(DataComponentType type) {
            Objects.requireNonNull(type, "type");
            validatePassThrough(type);
            patches.add(new DataPatch.Remove(type));
            return this;
        }

        /**
         * Adds a marker, replacing a previous marker with the same key in its original position.
         * The value must be immutable with value equals; Paper's own component values are.
         */
        public <P, C> Builder marker(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            markers.put(key, new ItemMarker<>(key, type, value));
            return this;
        }

        /** Returns an independent description with all current builder attributes. */
        public ItemSpec build() {
            return new ItemSpec(this);
        }

        private static void validatePassThrough(DataComponentType type) {
            if (TYPED_COMPONENTS.contains(type.getKey().toString())) {
                throw new IllegalArgumentException("Component has a typed attribute: " + type.getKey());
            }
        }
    }
}
