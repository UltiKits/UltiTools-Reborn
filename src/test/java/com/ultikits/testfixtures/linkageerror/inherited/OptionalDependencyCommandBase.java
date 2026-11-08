package com.ultikits.testfixtures.linkageerror.inherited;

import com.ultikits.ultitools.annotations.command.CmdMapping;

/**
 * A command base class whose inherited command method names an optional dependency's type.
 */
public abstract class OptionalDependencyCommandBase {

    /**
     * An inherited command mapping whose signature names the hidden type.
     *
     * @param value the optional dependency's value
     */
    @CmdMapping(format = "use")
    public void use(MissingOptionalType value) {
        // never invoked: the scan skips the subclass
    }
}
