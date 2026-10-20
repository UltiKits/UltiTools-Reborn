package com.ultikits.ultitools.events;

import com.google.gson.JsonObject;

/**
 * Published once for every inbound panel message the framework has already handled — appended as
 * the very last statement of {@code PluginInitiationUtils#handleInboundMessage(JsonObject)}, after
 * the framework's own dispatch has completed. A module subscribes to this event on the existing
 * {@link EventBus} to observe panel traffic without the framework growing a second, module-visible
 * protocol surface.
 * <p>
 * This event deliberately does <b>not</b> implement {@link Cancellable}. Cancellation is only
 * meaningful before an outcome has been decided; this event is published after the framework has
 * already handled the message, so a cancel flag here would be a callable method with no observable
 * effect — exactly the "declared but does not do what it declares" defect class this milestone
 * (6.3.0) exists to remove. This is consistent with an existing in-repo contract rather than an
 * isolated exception: {@link EventBus#publishAsync(ModuleEvent)} already rejects any
 * {@code Cancellable} event outright, for the same underlying reasoning that cancellation requires
 * synchronous dispatch before the result is settled.
 * <p>
 * Handlers run on the main server thread — the publish site wraps {@link EventBus#publish} in
 * {@code Bukkit.getScheduler().runTask(...)} — so a handler may use Bukkit API freely. That same
 * hop means a slow handler occupies a server tick; the publish site logs one warning per slow
 * publish naming the elapsed time.
 * <p>
 * The {@code data} and raw envelope this event carries are defensive copies, taken both when the
 * event is constructed and again on every accessor call: this event crosses a thread hop and is
 * delivered to an unknown number of third-party handlers, and one handler mutating shared state
 * must not change what the next handler sees or what the framework already acted on.
 *
 * @since 6.3.0
 */
public final class PanelMessageEvent extends ModuleEvent {

    private final String type;
    private final JsonObject data;
    private final JsonObject rawMessage;

    /**
     * @param type       the panel message's {@code type} field; must not be {@code null} or empty
     * @param data       the message's {@code data} object; {@code null} becomes a non-null empty object
     * @param rawMessage the full inbound envelope; {@code null} becomes a non-null empty object
     * @throws IllegalArgumentException if {@code type} is {@code null} or empty
     */
    public PanelMessageEvent(String type, JsonObject data, JsonObject rawMessage) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("PanelMessageEvent requires a non-empty type");
        }
        this.type = type;
        this.data = data != null ? data.deepCopy() : new JsonObject();
        this.rawMessage = rawMessage != null ? rawMessage.deepCopy() : new JsonObject();
    }

    /** @return the panel message's {@code type} field; never {@code null} or empty */
    public String getType() {
        return type;
    }

    /** @return a defensive copy of the message's {@code data} object; never {@code null} */
    public JsonObject getData() {
        return data.deepCopy();
    }

    /** @return a defensive copy of the full inbound envelope; never {@code null} */
    public JsonObject getRawMessage() {
        return rawMessage.deepCopy();
    }
}
