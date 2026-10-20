package com.ultikits.ultitools.context.scan.cmdtargetambiguity;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: class-level PLAYER switched laterally to CONSOLE by a method-level annotation.
 * {@link CmdTarget.CmdTargetType} is not a total order, so this is neither a narrowing nor a
 * widening - it is its own ambiguous category, refused on the same path as
 * {@link WideningCommand}.
 * <p>
 * 反例夹具：类级 PLAYER 被方法级注解横向切换为 CONSOLE。三值枚举不是全序，
 * 这既不是收窄也不是放宽，是独立的歧义类别，与放宽走同一条拒绝路径。
 */
@CmdExecutor(alias = {"lateral"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class LateralCommand {

    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
    public void switchesToConsole() {
        // Fixture only - never instantiated by the scan being tested.
    }
}
