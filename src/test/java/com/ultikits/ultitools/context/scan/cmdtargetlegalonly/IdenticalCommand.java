package com.ultikits.ultitools.context.scan.cmdtargetlegalonly;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: class-level CONSOLE, method-level CONSOLE - the SAME case, legal. Lives in a
 * dedicated package holding nothing but legal compositions, so a scan of this package alone
 * proves the check refuses nothing it should not.
 * <p>
 * 合法夹具：类级与方法级均为 CONSOLE，属于 SAME 情形，合法。
 */
@CmdExecutor(alias = {"identical"})
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class IdenticalCommand {

    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
    public void identical() {
        // Fixture only - never instantiated by the scan being tested.
    }
}
