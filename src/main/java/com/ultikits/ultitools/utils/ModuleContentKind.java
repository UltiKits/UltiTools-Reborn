package com.ultikits.ultitools.utils;

import java.util.Objects;
import java.util.regex.Pattern;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One kind of custom game content a module added, with how many entries of it when known.
 *
 * <p>Kinds are open lower-case identifiers so that later releases can add kinds without breaking
 * readers: code that meets a kind it does not know shows it with generic wording.
 *
 * @param kind  the kind, matching {@link #PATTERN}, for example {@link #ENCHANTMENT}
 * @param count how many entries of this kind, or {@code null} when unknown; at most {@link #MAX_COUNT}
 * @since 7.0.0
 */
@ApiStatus.Internal
public record ModuleContentKind(String kind, @Nullable Integer count) {

    /** Custom enchantments; removing them strips them from items for good. */
    public static final String ENCHANTMENT = "enchantment";
    /** Bundled data packs; removing them removes their recipes and advancements. */
    public static final String DATAPACK = "datapack";
    /** Tag additions or new tags; removing them only makes those item categories stop applying. */
    public static final String TAG = "tag";
    /** The record could not be read, so the module may have added custom content. */
    public static final String UNKNOWN = "unknown";
    /** The shape every kind must have. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    /** The largest count a kind carries; larger counts are capped to it. */
    public static final int MAX_COUNT = 100_000;

    /**
     * Validates the kind and count, capping large counts and clearing the count for {@link #UNKNOWN}.
     *
     * @param kind the open lower-case kind identifier
     * @param count the entry count, or {@code null} when unknown
     * @throws NullPointerException if {@code kind} is {@code null}
     * @throws IllegalArgumentException if the kind is invalid or the count is less than one
     */
    public ModuleContentKind {
        Objects.requireNonNull(kind, "kind");
        if (!PATTERN.matcher(kind).matches()) {
            throw new IllegalArgumentException("Content kind must match " + PATTERN.pattern() + ": " + kind);
        }
        if (count != null) {
            if (count < 1) {
                throw new IllegalArgumentException("Content count must be at least 1: " + count);
            }
            count = Math.min(count, MAX_COUNT);
        }
        if (UNKNOWN.equals(kind)) {
            count = null;
        }
    }
}
