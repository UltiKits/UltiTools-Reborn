package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.ConsoleMirror;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.utils.FrameworkText;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Log stream manager.
 * Responsible for monitoring server logs in real time and transmitting them to UltiPanel via WebSocket.
 *
 * @author UltiKits
 * @version 2.0.0
 */
@ApiStatus.Internal
public class LogStreamManager implements Listener {

    private static LogStreamManager instance;

    private LogStreamManager() {
        // Register the event listener
        try {
            Bukkit.getPluginManager().registerEvents(this, UltiTools.getInstance());
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning(FrameworkText.format("注册事件监听器失败: %s", e.getMessage()));
        }
    }
    
    public static LogStreamManager getInstance() {
        if (instance == null) {
            instance = new LogStreamManager();
        }
        return instance;
    }
    
    /**
     * Shuts down the log stream manager.
     */
    public void shutdown() {
        // Remove the console mirror and the handler from Bukkit's Logger
        ConsoleMirror.uninstall();
        detachAllSystemLogHandlers();

        UltiTools.getInstance().getLogger().info("[UltiPanel] LogStreamManager shutdown");
    }

    /**
     * Detaches every one of this framework's {@link SystemLogHandler} instances from the JVM
     * root logger.
     * <p>
     * Deliberately scans by type and removes every instance, rather than only removing {@code
     * this.systemLogHandler}: before this method existed, every successful reconnect attached
     * one more to the root logger, the field could only ever hold onto the last one, and the
     * previously-leaked ones had no reference pointing at them any more and so could never be
     * detached. Only a type-based scan can clean up the whole historical backlog at once, and
     * that is also the only formulation under which the acceptance criterion "handler count is
     * always 1" can hold. See issue #181.
     */
    private void detachAllSystemLogHandlers() {
        try {
            Logger rootLogger = Logger.getLogger("");
            for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
                if (handler instanceof SystemLogHandler) {
                    rootLogger.removeHandler(handler);
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("Error removing log handler: " + e.getMessage());
        }
    }
}
