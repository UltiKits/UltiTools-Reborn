package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import java.util.function.BooleanSupplier;

/**
 * The operator settings the panel mirrors next to its remote controls: the {@code settings}
 * object of the opening {@code hello}, and a {@code settings.changed} payload only when the value
 * differs from the last one reported.
 * <p>
 * The contract keeps capabilities fixed for the life of a session but lets operator settings change
 * mid-session, so a switch that flips while the panel is connected is reported once through
 * {@code settings.changed} and the panel greys its control without a new handshake.
 * <p>
 * {@link #changedSinceLastReport()} is a pure comparison: the caller sends the payload and then
 * calls {@link #markReported(JsonObject)}, which is what makes the next call return null again.
 * Before the first {@code markReported} every call returns the current settings; the wiring calls
 * it right after handing {@code hello} to the socket, so an unchanged value is never repeated.
 * Package-private framework plumbing; not for module use.
 *
 * @since 6.3.1
 */
final class AgentSettings {

    private final BooleanSupplier powerEnabled;

    /**
     * The last settings object handed to the panel, or null before the first report.
     */
    private volatile JsonObject reported;

    /**
     * Creates the helper.
     *
     * @param powerEnabled the operator's {@code ultipanel.capabilities.power-actions} switch
     */
    AgentSettings(BooleanSupplier powerEnabled) {
        this.powerEnabled = powerEnabled;
    }

    /**
     * Builds the settings object the opening {@code hello} carries.
     *
     * @return {@code { powerActionsEnabled }} as the contract's hello settings schema requires
     */
    JsonObject helloSettings() {
        JsonObject settings = new JsonObject();
        settings.addProperty("powerActionsEnabled", powerEnabled.getAsBoolean());
        return settings;
    }

    /**
     * The settings payload to send as {@code settings.changed}, or null when nothing changed.
     *
     * @return a fresh payload object, or null when the current value equals the last reported one
     */
    JsonObject changedSinceLastReport() {
        JsonObject current = helloSettings();
        return current.equals(reported) ? null : current;
    }

    /**
     * Records what was just handed to the panel, so the same value is not reported again.
     *
     * @param settings the payload that was sent; a defensive copy is kept
     */
    void markReported(JsonObject settings) {
        reported = settings == null ? null : settings.deepCopy();
    }
}
