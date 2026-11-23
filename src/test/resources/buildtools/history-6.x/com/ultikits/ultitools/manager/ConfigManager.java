package com.ultikits.ultitools.manager;

/** Frozen declaration-only fixture for the archived 6.x ledger, not a runtime API. */
public class ConfigManager {
    /**
     * @deprecated as of 6.3.0 this writes nothing; persist changes with AbstractConfigEntity#save() or
     * AbstractConfigEntity#saveOperatorChange(String...) when they are made
     */
    @Deprecated(since = "6.3.0")
    public void saveAll() {
        // Declaration metadata only; never invoked.
    }
}
