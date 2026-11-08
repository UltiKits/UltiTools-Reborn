package com.ultikits.testfixtures.linkageerror.inherited;

import com.ultikits.ultitools.annotations.command.CmdExecutor;

/**
 * A command class that only inherits the base class's mapping.
 */
@CmdExecutor(alias = {"inheritedsignature"})
public class InheritedSignatureCommand extends OptionalDependencyCommandBase {
}
