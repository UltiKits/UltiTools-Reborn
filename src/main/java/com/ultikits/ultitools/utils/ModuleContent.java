package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The custom game content one module added, by kind, heaviest consequence first.
 *
 * @param kinds the kinds, at most {@link #MAX_KINDS}, each kind at most once, in canonical order
 * @since 7.0.0
 */
@ApiStatus.Internal
public record ModuleContent(List<ModuleContentKind> kinds) {

    /** The most kinds one module reports. */
    public static final int MAX_KINDS = 16;
    /** No custom content. */
    public static final ModuleContent NONE = new ModuleContent(List.of());

    /**
     * Copies and sorts the kinds, putting known consequences first and future kinds alphabetically.
     *
     * @param kinds the kinds, or {@code null} for no custom content
     * @throws NullPointerException if an element is {@code null}
     * @throws IllegalArgumentException if a kind is repeated or there are more than {@link #MAX_KINDS}
     */
    public ModuleContent {
        if (kinds == null) {
            kinds = List.of();
        }
        if (kinds.size() > MAX_KINDS) {
            throw new IllegalArgumentException("At most " + MAX_KINDS + " content kinds are allowed");
        }
        var seen = new HashSet<String>();
        var sorted = new ArrayList<ModuleContentKind>(kinds.size());
        for (var entry : kinds) {
            Objects.requireNonNull(entry, "content kind");
            if (!seen.add(entry.kind())) {
                throw new IllegalArgumentException("Duplicate content kind: " + entry.kind());
            }
            sorted.add(entry);
        }
        sorted.sort(Comparator.comparingInt((ModuleContentKind entry) -> switch (entry.kind()) {
            case ModuleContentKind.ENCHANTMENT -> 0;
            case ModuleContentKind.DATAPACK -> 1;
            case ModuleContentKind.TAG -> 2;
            case ModuleContentKind.UNKNOWN -> 3;
            default -> 4;
        }).thenComparing(ModuleContentKind::kind));
        kinds = List.copyOf(sorted);
    }

    /**
     * Returns content that says only "this module may have added custom content".
     *
     * @return one unknown kind, without a count
     */
    public static ModuleContent unknown() {
        return new ModuleContent(List.of(new ModuleContentKind(ModuleContentKind.UNKNOWN, null)));
    }

    /**
     * Returns whether this module added no custom content.
     *
     * @return whether there are no kinds
     */
    public boolean isEmpty() {
        return kinds.isEmpty();
    }

    /**
     * Returns the entry for the given kind, or {@code null}.
     *
     * @param kind the kind to find
     * @return the entry, or {@code null} if it is absent
     */
    public @Nullable ModuleContentKind find(String kind) {
        for (var entry : kinds) {
            if (entry.kind().equals(kind)) {
                return entry;
            }
        }
        return null;
    }
}
