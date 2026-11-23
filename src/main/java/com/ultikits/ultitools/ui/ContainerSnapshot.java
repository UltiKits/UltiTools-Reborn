package com.ultikits.ultitools.ui;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/** What was written over what. Copies; immutable; lists have the container's size. */
public record ContainerSnapshot(List<ItemStack> contents, List<ItemStack> baseline, long sequence) {
    /** Copies both lists. @throws IllegalArgumentException if their sizes differ */
    public ContainerSnapshot {
        contents = ContainerSpec.copyItems(contents, "contents");
        baseline = ContainerSpec.copyItems(baseline, "baseline");
        if (contents.size() != baseline.size()) {
            throw new IllegalArgumentException("snapshot lists must have equal size");
        }
    }
    /** Copies of the contents to write. */
    @Override public List<ItemStack> contents() { return ContainerSpec.copyItems(contents, "contents"); }
    /** Copies of the baseline being replaced. */
    @Override public List<ItemStack> baseline() { return ContainerSpec.copyItems(baseline, "baseline"); }
}
