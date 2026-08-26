package com.ultikits.ultitools.context.scan.cmdtargetambiguity;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: class-level PLAYER widened to BOTH by a method-level annotation. Ambiguous - must be
 * refused registration by {@code ComponentScanner}, and must not take
 * {@link LegalSiblingCommand} down with it.
 * <p>
 * 反例夹具：类级 PLAYER 被方法级注解放宽为 BOTH，属于歧义组合，应被拒绝注册，
 * 且不应牵连合法的同级类。
 */
@CmdExecutor(alias = {"widening"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class WideningCommand {

    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    public void widensToBoth() {
        // Fixture only - never instantiated by the scan being tested.
    }
}
