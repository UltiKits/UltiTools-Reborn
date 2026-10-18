package com.ultikits.ultitools.context.scan.cmdtargetinherited;

import com.ultikits.ultitools.annotations.command.CmdExecutor;

/**
 * Fixture: a command class with no class-level target that inherits the same
 * {@code @CmdTarget(BOTH)} mapping - legal, so it must still register beside the refused sibling.
 */
@CmdExecutor(alias = {"inheritedboth"})
public class BothInheritorCommand extends InheritedBothMappingBase {
}
