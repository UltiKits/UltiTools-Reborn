package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.text.MessageKey;

/** Text catalogue keys for the enable-time custom content report. */
@ApiStatus.Internal
public final class BootstrapContentKeys {
    /** Successful declarations at this start. */
    public static final MessageKey SUMMARY = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.summary");
    /** A module that had declared custom content has been removed. */
    public static final MessageKey REMOVED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.removed");
    /** An updated module no longer declares some entries. */
    public static final MessageKey DROPPED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.dropped");
    /** The previous record could not be read. */
    public static final MessageKey UNKNOWN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.unknown");
    /** The contents of the removed or dropped entry list. */
    public static final MessageKey LIST = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.list");
    /** The effect of removing a custom enchantment. */
    public static final MessageKey CONSEQUENCE_ENCHANTMENT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.consequence.enchantment");
    /** The effect of removing a data pack. */
    public static final MessageKey CONSEQUENCE_DATAPACK = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.consequence.datapack");
    /** The effect of removing a tag change. */
    public static final MessageKey CONSEQUENCE_TAG = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.consequence.tag");
    /** A module attempted to declare a world-generation data pack. */
    public static final MessageKey WORLDGEN_REFUSED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "bootstrap_content.worldgen_refused");

    private BootstrapContentKeys() {
        // Message keys only.
    }
}
