/**
 * Framework-internal logging bridge.
 * <p>
 * {@code SystemLogHandler} is installed on the JUL root logger by the framework
 * itself during bootstrap. Module authors never construct, register or subclass
 * anything here — log records reach UltiPanel through this handler automatically.
 * As of 6.3.0, {@code ConsoleMirror} also feeds it the server's own Log4j console
 * output, so the panel's log stream mirrors the console.
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.handler;

import org.jetbrains.annotations.ApiStatus;
