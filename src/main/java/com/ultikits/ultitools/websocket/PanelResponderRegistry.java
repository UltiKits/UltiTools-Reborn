package com.ultikits.ultitools.websocket;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

/**
 * The single-owner registry a module uses to claim a request/response responder for a panel
 * message type the framework itself does not own (WIRE-16, D-26/D-27).
 * <p>
 * A module owns at most one responder per message type. Ownership is decided against exactly one
 * source of truth for the framework's own types — {@link PluginInitiationUtils#isFrameworkOwnedType}
 * — so a module can never silently take over {@code execute_command} or any of the framework's other
 * 23 inbound message types; that check runs before the module-owner check, and both refusals throw a
 * typed {@link PluginModuleException} naming the offender rather than a raw {@code RuntimeException}.
 * <p>
 * A module-owned type is served from the exact same lookup {@code PluginInitiationUtils
 * #handleInboundMessage} already uses for the framework's own 24 types — its unknown-type branch
 * consults this registry, not a second dispatch mechanism (01-CONTEXT D-10/D-11).
 * <p>
 * Deliberately does not require a {@code <module>:<type>} namespace prefix — D-26 rejected that as a
 * cross-repository protocol convention the panel would also have to honour, rather than an in-repo
 * check the framework can enforce alone.
 *
 * @since 6.3.0
 */
public class PanelResponderRegistry {

    /**
     * Pairs a responder function with the module name that registered it. Never exposed outside
     * this class — {@link #hasResponder(String)}/{@link #unregisterAll(String)} are the only
     * externally visible views of what this map holds.
     */
    private final Map<String, ResponderEntry> responders = new ConcurrentHashMap<>();

    /**
     * Registers {@code responder} as the single owner of {@code messageType}.
     * <p>
     * Validates all three arguments first. Then checks
     * {@link PluginInitiationUtils#isFrameworkOwnedType(String)} before checking module ownership —
     * deliberately in that order, so a module attempting {@code execute_command} is told the
     * framework owns it, not that some other module does. Finally uses an atomic
     * {@code putIfAbsent}-shaped check so a collision — with the framework, with another module, or
     * with the same module registering twice — always throws rather than silently displacing an
     * existing registration.
     *
     * @param messageType the exact message type string this responder will serve, matched by
     *                    {@code String.equals} — no case folding, no Unicode normalization
     * @param responder   the responder function, invoked with the inbound message's {@code data}
     *                    and returning a {@link CompletableFuture} that resolves the reply
     * @param ownerModule the name of the module registering this responder
     * @throws IllegalArgumentException if {@code messageType} or {@code ownerModule} is
     *                                   {@code null}/empty, or {@code responder} is {@code null}
     * @throws PluginModuleException    if the framework already owns {@code messageType}, or
     *                                   another registration (including the same module's own
     *                                   prior registration) already claims it
     */
    public void registerResponder(String messageType, Function<JsonObject, CompletableFuture<JsonObject>> responder,
            String ownerModule) {
        if (messageType == null || messageType.isEmpty()) {
            throw new IllegalArgumentException("messageType must not be null or empty");
        }
        if (responder == null) {
            throw new IllegalArgumentException("responder must not be null");
        }
        if (ownerModule == null || ownerModule.isEmpty()) {
            throw new IllegalArgumentException("ownerModule must not be null or empty");
        }
        if (PluginInitiationUtils.isFrameworkOwnedType(messageType)) {
            throw PluginModuleException.responderTypeOwnedByFramework(messageType);
        }
        ResponderEntry entry = new ResponderEntry(responder, ownerModule);
        ResponderEntry existing = responders.putIfAbsent(messageType, entry);
        if (existing != null) {
            throw PluginModuleException.responderTypeAlreadyOwned(messageType, existing.ownerModule);
        }
    }

    /**
     * Removes every responder {@code moduleName} owns — the mirror of
     * {@code EventBus.unregisterAll(String)}'s one-{@code String}-parameter void signature and
     * iterate-and-remove-by-owner shape, placed adjacent to the two existing
     * {@code eventBus.unregisterAll(...)} call sites in {@code PluginManager} rather than a new
     * lifecycle mechanism.
     * <p>
     * A module that registered nothing is a no-op: nothing is removed and nothing is thrown.
     * {@code moduleName == null} is also a no-op.
     *
     * @param moduleName the module whose responders should be removed, possibly {@code null}
     */
    public void unregisterAll(String moduleName) {
        if (moduleName == null) {
            return;
        }
        responders.values().removeIf(entry -> moduleName.equals(entry.ownerModule));
    }

    /**
     * Whether a responder is currently registered for the exact string {@code messageType}.
     *
     * @param messageType the message type to check, possibly {@code null}
     * @return {@code true} if a responder owns this exact type
     */
    public boolean hasResponder(String messageType) {
        return messageType != null && responders.containsKey(messageType);
    }

    /** An immutable pairing of a responder function and the module name that registered it. */
    private static final class ResponderEntry {
        private final Function<JsonObject, CompletableFuture<JsonObject>> responder;
        private final String ownerModule;

        private ResponderEntry(Function<JsonObject, CompletableFuture<JsonObject>> responder, String ownerModule) {
            this.responder = responder;
            this.ownerModule = ownerModule;
        }
    }
}
