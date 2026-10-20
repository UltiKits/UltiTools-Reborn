package com.ultikits.ultitools.config;

import java.io.IOException;

/**
 * Thrown when an explicit configuration write - an operator's change written through
 * {@code AbstractConfigEntity#saveOperatorChange}, {@code AbstractConfigEntity#saveOperatorMapEntry} or a panel edit - is
 * refused by the framework's configuration write gate, so that nothing was written.
 * <p>
 * The gate refuses rather than overwrite what an operator wrote (maintainer decision of 2026-10-04: operator-written
 * configuration is never overwritten automatically): a file it cannot read or parse, a file using YAML anchors, aliases
 * or merge keys, a layout the write could not keep byte for byte outside the keys it changes, or a file that changed
 * while the write was being prepared. The file keeps its bytes and the in-memory value the module set stays as it is;
 * {@link #getReason()} names why, as a phrase without any configuration value, so it can be shown to the operator.
 *
 * @since 6.3.0
 */
public class ConfigWriteRefusedException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String file;
    private final String reason;

    /**
     * Creates the exception for one refused write.
     *
     * @param file   the configuration file that was not written, as it should be named to the operator
     * @param reason why it was not written, as a phrase without any configuration value
     */
    public ConfigWriteRefusedException(String file, String reason) {
        super("Configuration file " + file + " was not written: " + reason);
        this.file = file;
        this.reason = reason;
    }

    /**
     * The configuration file that was not written.
     *
     * @return the file, as named in the message
     */
    public String getFile() {
        return file;
    }

    /**
     * Why the write was refused, as a phrase without any configuration value.
     *
     * @return the reason
     */
    public String getReason() {
        return reason;
    }
}
