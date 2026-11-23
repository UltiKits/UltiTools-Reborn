package com.ultikits.ultitools.ui;

/** What happens to a storage container's items when it ends. Constants may be added in a 7.x minor release. */
public enum Disposition {
    /** Commit through the store (E14); on Refused, put-ins go back to their contributors. */ COMMIT,
    /** Every item goes back to whoever put it in (ledger), baseline items included only if contributed. */
    RETURN_TO_CONTRIBUTORS
}
