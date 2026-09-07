package com.ultikits.ultitools.uat.fixtures.classlevellimits;

import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.UsageLimit;

/**
 * Inherits {@link BaseWithMapping#go} unchanged and declares {@code @CmdCD}/{@code @UsageLimit}
 * at the CLASS level rather than on the mapping method itself -- exactly the shape
 * {@code CooldownValidator.getCooldownSeconds}/{@code UsageLockValidator}'s own javadoc says
 * {@code ReflectionUtil.resolveMethodOrClassAnnotation} resolves at runtime (WR-02,
 * 05-REVIEW.md): method, then the concrete executor class directly, then the mapping method's
 * declaring class directly. {@code CommandRowScanner} must resolve the same way, or the
 * generated row for {@code go} silently omits a cooldown/usage-limit the runtime actually
 * enforces.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"lim"})
@CmdCD(30)
@UsageLimit(UsageLimit.LimitType.SENDER)
public class SubclassWithClassLevelLimits extends BaseWithMapping {
}
