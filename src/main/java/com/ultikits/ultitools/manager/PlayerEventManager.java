package com.ultikits.ultitools.manager;

import java.time.Instant;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * Player event manager.
 * Handles WebSocket messages for player-related events.
 */
@SuppressWarnings("deprecation")
@ApiStatus.Internal
public class PlayerEventManager implements Listener {
    /**
     * Must be volatile. Written on the WebSocket's onOpen thread ({@link #initialize}), read on
     * the Bukkit main thread (the three event handlers). Adding {@code synchronized} only to the
     * writer does not establish publication -- the reader never acquires the same monitor, so
     * there is no happens-before edge between the two sides.
     * <p>
     * This was <b>accidentally masked</b> before the idempotency guard was added: back then
     * every {@code initialize} call also called {@code registerEvents}, and Bukkit's own
     * listener registration writes a volatile field on {@code HandlerList}, which event dispatch
     * then reads -- so this field's write got piggybacked out for free. Once the guard started
     * skipping registration, that piggyback chain broke: after a reconnect, the handler could
     * keep looking at the old, already-disconnected client, and player events would be silently
     * dropped. See the PR #264 review.
     */
    private volatile UltiPanelWebSocketClient webSocketClient;

    /**
     * Whether this instance has already hooked itself into the Bukkit event system.
     * <p>
     * {@link #initialize(UltiPanelWebSocketClient)} is called by {@code initializeManagers()},
     * which is itself hung on the WebSocket's {@code onConnectHandler} -- <b>it runs on every
     * single onOpen</b>. Without this guard, N disconnect-reconnect cycles would register N
     * copies of the listener, and the same player event would be sent out N times. See issue #180.
     */
    private volatile boolean listenerRegistered = false;

    /**
     * Initializes the player event manager.
     * <p>
     * The whole method is mutually exclusive with {@link #shutdown()}. Synchronizing only {@code
     * registerEvents} on its own is not enough: a window would be left between "assign the
     * client" and "register the listener", and if a logout happens to land exactly in that
     * window, the listener {@code shutdown()} just detached would get reinstalled by the {@code
     * registerEvents} that follows right behind it -- leaving {@code listenerRegistered} true
     * even after {@code disableCloud()}. See the PR #264 review.
     *
     * @param client the WebSocket client
     */
    public synchronized void initialize(UltiPanelWebSocketClient client) {
        // The client is a freshly constructed instance on every reconnect (see
        // PluginInitiationUtils.getPanelWebsocketClient), so the reference must be updated
        // unconditionally; only the registration action itself is idempotent.
        this.webSocketClient = client;
        registerEvents();
    }

    /**
     * Idempotently registers the event listener: returns immediately if already registered.
     * <p>
     * The caller must already hold this object's monitor lock (currently the only caller is
     * {@link #initialize}).
     */
    private synchronized void registerEvents() {
        if (listenerRegistered) {
            return;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        if (plugin == null) {
            UltiTools.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                "[PlayerEventManager] 找不到 UltiTools 插件实例，玩家事件监听器未注册");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listenerRegistered = true;
    }

    /**
     * Unregisters the event listener and detaches the association with the client.
     * <p>
     * Called by {@code PluginInitiationUtils.disableCloud()} -- i.e. after {@code /ulticloud
     * logout}. Distinct from a "disconnect-and-reconnect": in that case {@code initialize} gets
     * called back afterward, so unregistering would just mean re-registering again; this method
     * handles the case where the cloud feature was explicitly turned off, and the listener
     * should genuinely be detached.
     */
    public synchronized void shutdown() {
        HandlerList.unregisterAll(this);
        listenerRegistered = false;
        this.webSocketClient = null;
    }

    /** Lets tests assert the idempotency guard's state. */
    boolean isListenerRegistered() {
        return listenerRegistered;
    }

    /**
     * Handles the player-join event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Read once into a local variable and use that same reference throughout. The field is
        // volatile, and shutdown() could very well null it out from another thread between the
        // check and the send -- when a reconnect budget is exhausted, disableCloud() runs on the
        // WebSocket thread, while this method runs on the Bukkit main thread.
        // HandlerList.unregisterAll() only blocks future dispatches, not this one already on the stack.
        UltiPanelWebSocketClient client = webSocketClient;
        if (client == null || !client.isConnected()) {
            return;
        }

        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("event_type", "player_join");
        data.addProperty("player_name", player.getName());
        data.addProperty("player_uuid", player.getUniqueId().toString());
        data.addProperty("join_message", event.getJoinMessage());
        data.addProperty("online_count", Bukkit.getOnlinePlayers().size());

        sendPlayerEvent(client, data);

        // Also send a log-stream message
        UltiTools.getInstance().getLogStreamManager().sendPlayerEventLog(
            "玩家加入", player.getName(), 
            String.format("玩家加入服务器，当前在线: %d人", Bukkit.getOnlinePlayers().size())
        );
    }

    /**
     * Handles the player-quit event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Read once into a local variable and use that same reference throughout. The field is
        // volatile, and shutdown() could very well null it out from another thread between the
        // check and the send -- when a reconnect budget is exhausted, disableCloud() runs on the
        // WebSocket thread, while this method runs on the Bukkit main thread.
        // HandlerList.unregisterAll() only blocks future dispatches, not this one already on the stack.
        UltiPanelWebSocketClient client = webSocketClient;
        if (client == null || !client.isConnected()) {
            return;
        }

        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("event_type", "player_quit");
        data.addProperty("player_name", player.getName());
        data.addProperty("player_uuid", player.getUniqueId().toString());
        data.addProperty("quit_message", event.getQuitMessage());
        data.addProperty("online_count", Math.max(0, Bukkit.getOnlinePlayers().size() - 1));

        sendPlayerEvent(client, data);

        // Also send a log-stream message
        UltiTools.getInstance().getLogStreamManager().sendPlayerEventLog(
            "玩家退出", player.getName(),
            String.format("玩家离开服务器，当前在线: %d人", Math.max(0, Bukkit.getOnlinePlayers().size() - 1))
        );
    }

    /**
     * Handles the player-chat event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(PlayerChatEvent event) {
        // Read once into a local variable and use that same reference throughout. The field is
        // volatile, and shutdown() could very well null it out from another thread between the
        // check and the send -- when a reconnect budget is exhausted, disableCloud() runs on the
        // WebSocket thread, while this method runs on the Bukkit main thread.
        // HandlerList.unregisterAll() only blocks future dispatches, not this one already on the stack.
        UltiPanelWebSocketClient client = webSocketClient;
        if (client == null || !client.isConnected()) {
            return;
        }

        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("event_type", "player_chat");
        data.addProperty("player_name", player.getName());
        data.addProperty("player_uuid", player.getUniqueId().toString());
        data.addProperty("message", event.getMessage());
        data.addProperty("format", event.getFormat());

        sendPlayerEvent(client, data);

        // Also send a log-stream message (chat messages are usually frequent, use debug level)
        UltiTools.getInstance().getLogStreamManager().sendCustomLog(
            "debug",
            String.format("[聊天] <%s> %s", player.getName(), event.getMessage()),
            "plugin:UltiTools"
        );
    }

    /**
     * Sends a player event to UltiPanel.
     * <p>
     * The client is passed in by the caller rather than re-reading {@link #webSocketClient}
     * here: each of the three event handlers reads that volatile field into a local variable
     * once and threads it through from there. Otherwise "check the connection" and "actually
     * send" could read two different values -- if {@code shutdown()} happens to land in between,
     * this would be an NPE.
     *
     * @param client the client the event handler read on entry
     * @param data the event data
     */
    private void sendPlayerEvent(UltiPanelWebSocketClient client, JsonObject data) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "player_event");
        message.add("data", data);
        message.addProperty("timestamp", Instant.now().toEpochMilli());
        message.addProperty("serverId", getServerId(client));

        client.sendMessage(message);
    }

    /**
     * Gets the server ID.
     * <p>
     * Takes the UUID handed to the client at connect time (i.e. {@code
     * CommonUtils.getUltiToolsUUID()}, see {@code PluginInitiationUtils.getPanelWebsocketClient}),
     * consistent with every sibling manager. Before #180, this returned a hardcoded placeholder
     * string, meaning every server worldwide sent out its {@code player_event} claiming the same
     * identity, and the panel side had no way to route them.
     *
     * @return the server ID
     */
    private String getServerId(UltiPanelWebSocketClient client) {
        return client == null ? "unknown" : client.getServerId();
    }
}
