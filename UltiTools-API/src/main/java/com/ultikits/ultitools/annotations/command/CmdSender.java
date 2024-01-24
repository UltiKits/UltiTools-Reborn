package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command sender annotation. <br> Support auto-cast to {@link org.bukkit.command.CommandSender} and {@link org.bukkit.entity.Player}.
 * <p>
 * 指令发送者注解。<br> 支持自动转换为 {@link org.bukkit.command.CommandSender} 和 {@link org.bukkit.entity.Player}。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#quick-start">Command Excutor</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdSender {
}
