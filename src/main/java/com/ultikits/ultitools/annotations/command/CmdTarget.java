package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command target annotation. Restricts which kind of sender - a player, the console, or both -
 * may invoke a command class or one of its {@code @CmdMapping} methods.
 * <p>
 * When both a class-level and a method-level {@code @CmdTarget} are present, the method-level
 * value may only <b>narrow</b> the class-level one (D-01: narrowing-only override, resolved by
 * {@code CmdTargetComposition}). BOTH narrowing to PLAYER or to CONSOLE is legal; an identical
 * pair is legal. Widening a restriction back to BOTH, and switching laterally between PLAYER and
 * CONSOLE, are both ambiguous and are refused at plugin load, naming the offending class and
 * method - never silently resolved to either reading. See
 * {@code .planning/phases/01-adjudication-foundations-compatibility-baseline/01-ADJUDICATION.md}.
 * <p>
 * 指令目标注解，限制哪种发送者——玩家、控制台或两者——可以执行某个指令类或其中的
 * {@code @CmdMapping} 方法。当类级与方法级同时存在 {@code @CmdTarget} 时，方法级取值只能
 * <b>收窄</b>类级取值；放宽或横向切换（PLAYER 与 CONSOLE 互换）都是歧义组合，会在插件加载期
 * 被拒绝并点名违规的类与方法。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#sender-limitation">@CmdTarget</a>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdTarget {
    CmdTargetType value();

    enum CmdTargetType {
        PLAYER,
        CONSOLE,
        BOTH
    }
}
