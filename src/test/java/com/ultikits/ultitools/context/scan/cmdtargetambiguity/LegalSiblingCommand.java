package com.ultikits.ultitools.context.scan.cmdtargetambiguity;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: class-level PLAYER narrowed by nothing (method carries no {@code @CmdTarget}) plus a
 * second method that narrows BOTH to CONSOLE. Both are legal - this class must still register
 * even when {@link WideningCommand} and {@link LateralCommand} are refused in the same scan.
 * <p>
 * 合法夹具：类级 PLAYER，方法未携带 @CmdTarget（沿用类级），另一方法将 BOTH 收窄为
 * CONSOLE——两者均合法，即便同一次扫描中 WideningCommand 与 LateralCommand 被拒绝，
 * 本类也必须正常注册。
 */
@CmdExecutor(alias = {"legal"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class LegalSiblingCommand {

    @CmdMapping(format = "")
    public void inheritsClassLevel() {
        // Fixture only - never instantiated by the scan being tested.
    }
}
