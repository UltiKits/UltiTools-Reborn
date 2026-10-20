package com.ultikits.ultitools.config;

/**
 * Thrown when an operator's map-entry write made under an {@link EntryPresence} condition was not written because the
 * condition did not hold: the entry is already in the file under {@link EntryPresence#MUST_BE_ABSENT}, or it is no
 * longer in the file under {@link EntryPresence#MUST_BE_PRESENT}.
 * <p>
 * It is a {@link ConfigWriteRefusedException}, so a caller that handles refusals already handles it. Nothing was
 * written: the file keeps its bytes and its modification time, and the in-memory value the module set stays as it is.
 * The framework logs nothing above FINE for it, because the condition is the caller's to report.
 * {@link #getRequired()} tells the caller which condition failed, so it can tell the operator what happened, for
 * example "a rule of that name is already in the file; reload to see it". {@link #getReason()} names the entry's key
 * path and never a value.
 *
 * @since 6.3.0
 */
public class ConfigEntryPresenceException extends ConfigWriteRefusedException {

    private static final long serialVersionUID = 1L;

    private final EntryPresence required;

    /**
     * Creates the exception for one write whose condition did not hold.
     *
     * @param file     the configuration file that was not written, as it should be named to the operator
     * @param reason   why it was not written, as a phrase naming the entry's key path and no value
     * @param required the condition the write was made under, which did not hold
     */
    public ConfigEntryPresenceException(String file, String reason, EntryPresence required) {
        super(file, reason);
        this.required = required;
    }

    /**
     * The condition the write was made under, which did not hold.
     *
     * @return {@link EntryPresence#MUST_BE_ABSENT} when the entry was already in the file,
     *         {@link EntryPresence#MUST_BE_PRESENT} when it was not
     */
    public EntryPresence getRequired() {
        return required;
    }
}
