package com.ultikits.ultitools.utils;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonObject;

/**
 * Captures player joins and leaves into the current UltiCloud session's reliable-delivery outbox
 * (D-14, D-17, S-16), whether or not the connection is open: the outbox exists precisely to carry
 * an event across a disconnect (contract answer 15).
 * <p>
 * <b>Public only because Bukkit needs it to be.</b> Bukkit registers listeners reflectively and
 * dispatches to public handler methods, so a package-private class could not be registered at all
 * (S-03). Everything else about this class -- its handler bodies, its payload builder -- is an
 * implementation detail; the class is marked {@link ApiStatus.Internal} so no module treats it as
 * API.
 * <p>
 * <b>Registered once, decided per event.</b> {@code UltiTools#onEnable()} registers this listener a
 * single time, beside {@code PlayerJoinListener}, and never unregisters it while the plugin is
 * enabled. Whether an event is captured is decided at event time by
 * {@link CloudSession#offerReliableEvent(String, JsonObject)}, which returns silently when the
 * server holds no credential or {@code ultipanel.capabilities.player-events} is off (S-16). The
 * listener is therefore never registered or unregistered from an asynchronous thread, and an
 * unlinked server writes no state file at all.
 * <p>
 * <b>Payload.</b> {@link #payloadFor(Player)} is the single builder of a {@code player.join} /
 * {@code player.leave} payload, and it emits exactly two keys: {@code playerUuid} (the UUID string)
 * and {@code playerName} (the name the server reports, a Floodgate prefix such as {@code .Steve}
 * included). No IP address, display name, location or online-mode flag ever reaches the outbox
 * (T-22-29, contract answer 15).
 * <p>
 * <b>Known limitation.</b> A quit fired after the plugin has already been disabled during a server
 * stop is not captured: Bukkit stops dispatching to a disabled plugin's listeners. The plugin's own
 * shutdown flush still persists everything captured before that point; whether the CraftBukkit stop
 * sequence disables plugins before it removes players is not verified here, and the real-server
 * observation is recorded by the UAT row {@code ultitools.cloud.outbox.persist-restart} and in
 * {@code COMPATIBILITY.md} (plan 22-15).
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class CloudOutboxListener implements Listener {

    /**
     * Adds one {@code player.join} entry to the current session's outbox.
     *
     * @param event the join event, on the main thread
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        offer("player.join", event.getPlayer());
    }

    /**
     * Adds one {@code player.leave} entry to the current session's outbox. A kick ends in a quit, so
     * a kicked player produces exactly this one entry.
     *
     * @param event the quit event, on the main thread
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        offer("player.leave", event.getPlayer());
    }

    /**
     * Hands one event to the session that is current <b>now</b>, not the one captured at
     * registration time: a login replaces the session, and the replacement owns the outbox the
     * event belongs in.
     *
     * @param type   the envelope type
     * @param player the player the event is about
     */
    private static void offer(String type, Player player) {
        CloudSession.current().offerReliableEvent(type, payloadFor(player));
    }

    /**
     * The one builder of a join/leave payload (T-22-29). Kept package-private so a test can assert
     * the exact key set without a live server.
     *
     * @param player the player the event is about
     * @return a fresh payload object the outbox copies
     */
    static JsonObject payloadFor(Player player) {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerUuid", player.getUniqueId().toString());
        payload.addProperty("playerName", player.getName());
        return payload;
    }
}
