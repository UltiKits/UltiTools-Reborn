/**
 * Framework-internal logging bridge.
 * <p>
 * 框架内部的日志桥接层。
 * <p>
 * {@code SystemLogHandler} is installed on the JUL root logger by the framework
 * itself during bootstrap. Module authors never construct, register or subclass
 * anything here — log records reach UltiPanel through this handler automatically.
 * <p>
 * {@code SystemLogHandler} 由框架在启动时挂到 JUL 根 logger 上。模块作者不需要
 * 也不应该自己构造、注册或继承这里的任何东西——日志会自动经由它送到 UltiPanel。
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.handler;

import org.jetbrains.annotations.ApiStatus;
