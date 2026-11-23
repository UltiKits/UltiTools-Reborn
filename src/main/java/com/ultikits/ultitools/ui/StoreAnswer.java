package com.ultikits.ultitools.ui;

import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** The store's answer. The set of answers may grow; unknown answers are treated as Unknown. */
public sealed interface StoreAnswer permits StoreAnswer.Written, StoreAnswer.Refused, StoreAnswer.Unknown {
    /** Stored. */
    record Written() implements StoreAnswer { }
    /** Not stored, and nothing of it will be; {@code current} is what the store holds now. A {@code current} whose length is not the container's size counts as {@link Unknown}: nothing is refunded and the store stays responsible. */
    record Refused(List<ItemStack> current) implements StoreAnswer {
        /** Copies the current contents. */
        public Refused { current = ContainerSpec.copyItems(current, "current"); }
        /** Fresh copies of the current contents. */
        @Override public List<ItemStack> current() { return ContainerSpec.copyItems(current, "current"); }
    }
    /** Not known whether it was stored (error, timeout, stall). */
    record Unknown(@Nullable Throwable cause) implements StoreAnswer { }
    /** A successful write. */
    static StoreAnswer written() { return new Written(); }
    /** A refused write with the store's current contents. */
    static StoreAnswer refused(List<ItemStack> current) { return new Refused(current); }
    /** An indeterminate write; the cause may be absent. */
    static StoreAnswer unknown(@Nullable Throwable cause) { return new Unknown(cause); }
}
