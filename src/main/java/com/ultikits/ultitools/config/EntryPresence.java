package com.ultikits.ultitools.config;

/**
 * The condition an operator's map-entry write is made under: whether the entry must already be in the configuration
 * file, or must not be, when the write happens.
 * <p>
 * Used with {@code AbstractConfigEntity#saveOperatorMapEntry(EntryPresence, String, String...)}. An operator command
 * that creates an entry ({@code /autoreply add <name>}) writes under {@link #MUST_BE_ABSENT}, so it never replaces an
 * entry of that name the operator added to the file by hand since it was read. A command that changes an existing entry
 * ({@code /autoreply setkeyword <name>}) writes under {@link #MUST_BE_PRESENT}, so it never writes back an entry the
 * operator deleted by hand.
 * <p>
 * The condition is decided on the same read of the file that the configuration write gate verifies the write against. An
 * edit saved after that read is never written over: the gate then refuses the write because the file changed.
 * <p>
 * <b>What "present" means.</b> The entry is present when its whole key path exists in the file. The path is where the
 * framework reads the setting (its nested keys, or the flat dotted key the operator wrote), followed by the map keys,
 * each of them one whole key. An entry holding {@code null} ({@code name: ~}) is present. An entry is absent when its
 * key is missing, or when a key above it is missing, {@code null}, an empty map ({@code {}}) or not a map. A file that
 * is missing, empty or holds only comments holds no entry.
 *
 * @since 6.3.0
 */
public enum EntryPresence {

    /** The entry must already be in the file; the write changes or removes it and never creates it. */
    MUST_BE_PRESENT,

    /** The entry must not be in the file yet; the write creates it and never replaces one the file holds. */
    MUST_BE_ABSENT
}
