package com.ultikits.ultitools.manager;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.SimplePluginManager;

/**
 * Manager for chat-based callback commands.
 * <p>
 * Enables clickable chat messages that execute callbacks when clicked.
 * Registers a hidden command /ultitools_callback that executes registered
 * callbacks by their UUID.
 * </p>
 * <p>
 * 聊天回调命令管理器。
 * 支持可点击的聊天消息，点击时执行回调。
 * 注册一个隐藏命令 /ultitools_callback，通过 UUID 执行已注册的回调。
 * </p>
 *
 * <p><strong>Usage Example / 使用示例:</strong></p>
 * <pre>{@code
 * VoidFunc0 callback = () -> player.sendMessage("Clicked!");
 * UUID callbackId = ChatCallbackManager.registerCallback(callback);
 * 
 * // Create clickable text that runs: /ultitools_callback <callbackId>
 * Component text = Component.text("[Click Me]")
 *     .clickEvent(ClickEvent.runCommand("/ultitools_callback " + callbackId));
 * }</pre>
 *
 * @author wisdomme
 * @see com.ultikits.ultitools.widgets.impl.ChatConfirm
 * @since 6.0.0
 */
public class ChatCallbackManager {
    /** Thread-safe storage for pending callbacks / 待处理回调的线程安全存储 */
    private static final Map<UUID, Runnable> callbacks = new ConcurrentHashMap<>();
    /** Initialization flag to prevent multiple command registrations / 防止多次命令注册的初始化标志 */
    private static boolean initialized = false;

    /**
     * Registers a callback and returns its unique identifier.
     * <p>
     * The callback will be executed once when a player runs the command
     * /ultitools_callback &lt;uuid&gt;. After execution, the callback is removed.
     * </p>
     * <p>
     * 注册一个回调并返回其唯一标识符。
     * 当玩家运行命令 /ultitools_callback &lt;uuid&gt; 时，回调将被执行一次，执行后移除。
     * </p>
     *
     * @param callback the callback to register / 要注册的回调
     * @return the UUID identifier for this callback / 此回调的 UUID 标识符
     */
    public static synchronized UUID registerCallback(Runnable callback) {
        if (!initialized) {
            initialize();
        }
        UUID uuid = UUID.randomUUID();
        callbacks.put(uuid, callback);
        return uuid;
    }

    /**
     * Get the CommandMap from Bukkit's PluginManager.
     * Returns null if the PluginManager is not a SimplePluginManager (e.g., in test environments).
     */
    private static CommandMap getCommandMap() {
        try {
            if (Bukkit.getPluginManager() instanceof SimplePluginManager) {
                Field commandMapField = SimplePluginManager.class.getDeclaredField("commandMap");
                commandMapField.setAccessible(true);
                return (CommandMap) commandMapField.get(Bukkit.getPluginManager());
            }
        } catch (Exception | Error e) {
            // Silently fail - this is expected in test environments
        }
        return null;
    }

    private static void initialize() {
        try {
            CommandMap commandMap = getCommandMap();
            if (commandMap == null) {
                // In test environments (MockBukkit), commandMap may be null
                // Mark as initialized to prevent repeated attempts
                initialized = true;
                return;
            }
            
            Command command = new Command("ultitools_callback") {
                @Override
                public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                    if (args.length != 1) return false;
                    try {
                        UUID uuid = UUID.fromString(args[0]);
                        Runnable callback = callbacks.remove(uuid);
                        if (callback != null) {
                            callback.run();
                        }
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            };
            commandMap.register("ultitools", command);
            initialized = true;
        } catch (Exception e) {
            // Mark as initialized even on failure to prevent repeated attempts
            initialized = true;
            Logger.getLogger(ChatCallbackManager.class.getName())
                    .log(Level.SEVERE, "Failed to initialize ChatCallbackManager", e);
        }
    }
}
