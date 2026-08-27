package com.ultikits.ultitools.context.scan.cmdtargetlegalonly;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: class-level BOTH, method-level PLAYER - a NARROWING, legal. Lives beside
 * {@link IdenticalCommand} in a package holding nothing but legal compositions.
 * <p>
 * 合法夹具：类级 BOTH，方法级 PLAYER，属于 NARROWING 情形，合法。
 */
@CmdExecutor(alias = {"narrowing"})
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class NarrowingCommand {

    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void narrowsToPlayer() {
        // Fixture only - never instantiated by the scan being tested.
    }
}
