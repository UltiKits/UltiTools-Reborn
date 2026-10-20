package com.ultikits.ultitools.context.scan.cmdtargetinherited;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: a console-only command class that inherits a {@code @CmdTarget(BOTH)} mapping - a
 * widening. Must be refused registration, or a player could run the inherited mapping.
 */
@CmdExecutor(alias = {"inheritedconsole"})
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class ConsoleOnlyInheritorCommand extends InheritedBothMappingBase {
}
