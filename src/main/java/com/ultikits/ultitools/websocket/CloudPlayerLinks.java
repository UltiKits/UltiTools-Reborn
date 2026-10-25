package com.ultikits.ultitools.websocket;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.jetbrains.annotations.ApiStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creates and tracks UltiCloud player links on a module's behalf; the framework's player-link API.
 *
 * <p>A player link proves that the person using the web panel is the owner of a game identity. A
 * module creates one for a player (in practice when the player runs {@code /panel}); the gateway
 * answers with a one-time URL the player opens in a browser; the gateway then reports how that web
 * user proved the identity. A module never sees the server credential and never talks to UltiCloud
 * over HTTP.</p>
 *
 * <p><b>Link lifetime.</b> A link lives {@code playerLinkTtlSeconds} = 600 seconds from issue, so
 * {@link LinkCreation#expiresAt()} is the issue time plus 600 seconds, and it is single use. Running
 * {@code /panel} again for the same player cancels the previous link, which is reported as an
 * {@link ExpiryReason#CANCELLED} expiry. A link is not extended by using it.</p>
 *
 * <p><b>Expiry reasons.</b> {@link ExpiryReason#EXPIRED} means nobody completed the link before it
 * lapsed; {@link ExpiryReason#CANCELLED} means it was replaced by a newer link or cancelled by the
 * plugin or the player; {@link ExpiryReason#DENIED} means the web user explicitly refused the
 * confirmation page. The framework never synthesizes an expiry event: a link whose
 * {@link LinkCreation#expiresAt()} has passed without any event is the module's to treat as expired.</p>
 *
 * <p><b>Creation failures.</b> {@link Failure#LINK_NOT_PERMITTED} means the gateway refused to issue
 * a link because the server is suspended, the owner disabled player links, or the game identity is
 * banned. {@link Failure#RATE_LIMITED} means the gateway's rate limit was hit and the module may
 * offer a retry. {@link Failure#TIMED_OUT} and {@link Failure#NOT_CONNECTED} mean the request never
 * produced an answer.</p>
 *
 * <p><b>Threading.</b> Every call is non-blocking, including {@link #create} from the server's
 * primary thread. The registered {@link LinkEventHandler} is always called on a framework worker
 * thread, never on the socket thread or the primary thread; it may do blocking work and must
 * complete its future from wherever the module's own work finishes. A handler that does not answer
 * within 30 seconds, or that fails, produces no result, so the gateway redelivers the same completion
 * later (the module is asked again).</p>
 *
 * <p><b>Ownership.</b> Exactly one module owns the handler at a time. It should set the handler when
 * it is enabled and remove it on unload; while another module owns it, {@link #setHandler} throws
 * {@link IllegalStateException} rather than taking events away from its owner.</p>
 *
 * @since 6.3.1
 */
public final class CloudPlayerLinks {

    private final Transport transport;

    /**
     * Creates the API over a transport.
     *
     * @param transport the transport that frames and tracks the protocol messages; never null
     */
    public CloudPlayerLinks(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Asks the gateway for a one-time link for one player, without blocking.
     *
     * <p>The returned future completes normally with a {@link LinkCreation} that carries the URL or
     * with a failure; it never completes exceptionally for a protocol-level outcome. The framework
     * fills {@code serverOnlineMode} itself. For one link, the creation result is delivered before
     * any completion or expiry of that link.</p>
     *
     * @param requester the calling module, used for ownership and diagnostics
     * @param playerUuid the player's Minecraft UUID
     * @param playerName the player's Minecraft name as the module knows it
     * @param gameAuth what this server knows about the player's game authentication
     * @param offeredProofs the identity proofs this server can support, one to three of them
     * @return a future that completes with the answer, never null
     * @throws NullPointerException if {@code requester}, {@code playerUuid} or {@code gameAuth} is
     *         null, or if {@code offeredProofs} holds a null proof
     * @throws IllegalArgumentException if the name is null or empty, or the offered proofs are null,
     *         empty or hold more than three entries
     * @since 6.3.1
     */
    public CompletableFuture<LinkCreation> create(UltiToolsPlugin requester, UUID playerUuid, String playerName,
                                                  GameAuth gameAuth, Set<LinkProof> offeredProofs) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(gameAuth, "gameAuth");
        if (playerName == null || playerName.isEmpty()) {
            throw new IllegalArgumentException("playerName must not be empty");
        }
        if (offeredProofs == null || offeredProofs.isEmpty() || offeredProofs.size() > 3) {
            throw new IllegalArgumentException("offeredProofs must hold one to three proofs");
        }
        for (LinkProof proof : offeredProofs) {
            Objects.requireNonNull(proof, "offeredProofs must not contain null");
        }
        return transport.create(requester, playerUuid, playerName, gameAuth, offeredProofs);
    }

    /**
     * Registers the handler for completions and expiries of this server's links.
     *
     * <p>One owner at a time: the same owner may replace its own handler, another owner while one is
     * set is refused. The handler is called off the primary thread.</p>
     *
     * @param owner the module that owns the handler
     * @param handler the handler to install
     * @throws IllegalStateException if a different module already owns the handler
     * @since 6.3.1
     */
    public void setHandler(UltiToolsPlugin owner, LinkEventHandler handler) {
        transport.setHandler(Objects.requireNonNull(owner, "owner"), Objects.requireNonNull(handler, "handler"));
    }

    /**
     * Removes the handler when {@code owner} is the module that set it; other owners are ignored.
     * After removal, completions are answered as if no handler were registered.
     *
     * @param owner the module that set the handler
     * @since 6.3.1
     */
    public void removeHandler(UltiToolsPlugin owner) {
        transport.removeHandler(Objects.requireNonNull(owner, "owner"));
    }

    /**
     * The framework's transport for link messages; internal, not for module use.
     *
     * <p>Modules pass an implementation to {@link CloudPlayerLinks} only through the framework's
     * accessor; a module test may implement or mock it to exercise its own code.</p>
     *
     * @since 6.3.1
     */
    @ApiStatus.Internal
    public interface Transport {
        /**
         * Sends one {@code player.link.create} and tracks its answer.
         *
         * @param requester the calling module
         * @param playerUuid the player's UUID
         * @param playerName the player's name
         * @param gameAuth the game-authentication facts
         * @param offeredProofs the supported proofs
         * @return the answer future, never null
         */
        CompletableFuture<LinkCreation> create(UltiToolsPlugin requester, UUID playerUuid, String playerName,
                                               GameAuth gameAuth, Set<LinkProof> offeredProofs);

        /**
         * Installs the single-owner handler.
         * @param owner the owning module
         * @param handler the handler
         * @throws IllegalStateException if another module owns the handler
         */
        void setHandler(UltiToolsPlugin owner, LinkEventHandler handler);

        /**
         * Removes the handler if {@code owner} set it.
         * @param owner the owning module
         */
        void removeHandler(UltiToolsPlugin owner);
    }

    /**
     * An identity proof the server side can support for one link; the gateway decides which of the
     * offered ones it actually accepts.
     *
     * @since 6.3.1
     */
    public enum LinkProof {
        /** The player is connected to this server right now and proved the session in game. */
        IN_GAME_SESSION,
        /** The web user is already bound to this game identity on the panel. */
        LINKED_WEB_ACCOUNT,
        /** The web user registered a new UltiKits account and confirmed it for this name. */
        WEB_REGISTRATION
    }

    /**
     * Why a link request failed.
     *
     * @since 6.3.1
     */
    public enum Failure {
        /** This server holds no usable UltiCloud connection; no request was sent. */
        NOT_CONNECTED,
        /** The gateway rate-limited the request; a later attempt may succeed. */
        RATE_LIMITED,
        /** The gateway refuses to issue links: server suspended, links disabled, or identity banned. */
        LINK_NOT_PERMITTED,
        /** The gateway did not answer within 30 seconds. */
        TIMED_OUT,
        /** The gateway answered with an unexpected error. */
        INTERNAL_ERROR
    }

    /**
     * Why a link stopped being usable.
     *
     * @since 6.3.1
     */
    public enum ExpiryReason {
        /** Nobody completed the link within its 600-second lifetime. */
        EXPIRED,
        /** A newer {@code /panel} replaced the link, or the plugin or player cancelled it. */
        CANCELLED,
        /** The web user explicitly refused the confirmation page. */
        DENIED
    }

    /**
     * What the handler reports about provisioning a game account for the linked player.
     *
     * @since 6.3.1
     */
    public enum GameAccountResult {
        /** The module created the game account and password. */
        CREATED,
        /** No game account was needed or requested. */
        NOT_REQUESTED,
        /** The player already has a game account on this server. */
        ACCOUNT_EXISTS,
        /** This server's own rules refused to create the account, for example an IP registration cap. */
        REGISTRATION_REJECTED,
        /** No usable in-game login plugin is available, so no account can be created. */
        AUTH_PLUGIN_UNAVAILABLE
    }

    /**
     * What this server knows about the player's game authentication when the link is created.
     *
     * @since 6.3.1
     */
    public static final class GameAuth {
        private final boolean authPlugin;
        private final boolean authenticated;
        private final boolean accountExists;

        private GameAuth(boolean authPlugin, boolean authenticated, boolean accountExists) {
            this.authPlugin = authPlugin;
            this.authenticated = authenticated;
            this.accountExists = accountExists;
        }

        /**
         * Reports that no login plugin is involved in this server's authentication.
         * @return the {@code provider: none} facts
         */
        public static GameAuth none() {
            return new GameAuth(false, false, false);
        }

        /**
         * Reports that a login plugin authenticates players on this server.
         * @param authenticated whether the player is currently authenticated by that plugin
         * @param accountExists whether that plugin already holds an account for this name
         * @return the {@code provider: auth_plugin} facts
         */
        public static GameAuth authPlugin(boolean authenticated, boolean accountExists) {
            return new GameAuth(true, authenticated, accountExists);
        }

        /** @return true when a login plugin is involved */
        public boolean isAuthPlugin() {
            return authPlugin;
        }

        /** @return whether the login plugin has authenticated the player; false for {@link #none()} */
        public boolean authenticated() {
            return authenticated;
        }

        /** @return whether the login plugin already holds an account; false for {@link #none()} */
        public boolean accountExists() {
            return accountExists;
        }

        @Override
        public String toString() {
            return "GameAuth{authPlugin=" + authPlugin + ", authenticated=" + authenticated
                    + ", accountExists=" + accountExists + "}";
        }
    }

    /**
     * The answer to one {@link #create} call: either a link, or a failure with a safe message.
     *
     * @since 6.3.1
     */
    public static final class LinkCreation {
        private final String linkId;
        private final String url;
        private final Instant expiresAt;
        private final List<LinkProof> acceptedProofs;
        private final Failure failure;
        private final String message;

        private LinkCreation(String linkId, String url, Instant expiresAt, Collection<LinkProof> acceptedProofs,
                             Failure failure, String message) {
            this.linkId = linkId;
            this.url = url;
            this.expiresAt = expiresAt;
            this.acceptedProofs = acceptedProofs == null ? Collections.<LinkProof>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(acceptedProofs));
            this.failure = failure;
            this.message = message;
        }

        /**
         * Creates a successful answer.
         * @param linkId the id of the {@code player.link.create} this link answers
         * @param url the one-time https URL to show the player
         * @param expiresAt the issue time plus 600 seconds, on the framework's clock
         * @param acceptedProofs the proofs the gateway accepted, never empty
         * @return the answer
         */
        public static LinkCreation created(String linkId, String url, Instant expiresAt,
                                           Collection<LinkProof> acceptedProofs) {
            return new LinkCreation(Objects.requireNonNull(linkId, "linkId"), Objects.requireNonNull(url, "url"),
                    Objects.requireNonNull(expiresAt, "expiresAt"), Objects.requireNonNull(acceptedProofs, "acceptedProofs"),
                    null, null);
        }

        /**
         * Creates a failed answer.
         * @param failure why it failed
         * @param message a safe diagnostic for the module or operator, never a credential
         * @return the answer
         */
        public static LinkCreation failed(Failure failure, String message) {
            return new LinkCreation(null, null, null, null, Objects.requireNonNull(failure, "failure"), message);
        }

        /** @return the link id, or null for a failed answer */
        public String linkId() {
            return linkId;
        }

        /** @return the one-time URL, or null for a failed answer */
        public String url() {
            return url;
        }

        /** @return the expiry on the framework's clock, or null for a failed answer */
        public Instant expiresAt() {
            return expiresAt;
        }

        /** @return the accepted proofs, empty for a failed answer */
        public List<LinkProof> acceptedProofs() {
            return acceptedProofs;
        }

        /** @return why the request failed, or null for a successful answer */
        public Failure failure() {
            return failure;
        }

        /** @return the safe failure message, or null for a successful answer */
        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return failure == null
                    ? "LinkCreation{linkId=" + linkId + ", url=" + url + ", expiresAt=" + expiresAt
                            + ", acceptedProofs=" + acceptedProofs + "}"
                    : "LinkCreation{failure=" + failure + ", message=" + message + "}";
        }
    }

    /**
     * One completed link: how the web user proved the game identity, and what to provision.
     *
     * @since 6.3.1
     */
    public static final class LinkCompletion {
        private final String completionId;
        private final String linkId;
        private final LinkProof proof;
        private final UUID playerUuid;
        private final String playerName;
        private final boolean provisionGameAccount;
        private final String webUsername;

        /**
         * Creates a completion; a module test may build one directly.
         *
         * @param completionId the completion message's envelope id
         * @param linkId the id of the link that completed
         * @param proof the proof the web user used
         * @param playerUuid the player's UUID
         * @param playerName the player's name
         * @param provisionGameAccount whether the module must create a game account
         * @param webUsername the UltiKits web username for {@link LinkProof#WEB_REGISTRATION}, else null
         */
        public LinkCompletion(String completionId, String linkId, LinkProof proof, UUID playerUuid, String playerName,
                              boolean provisionGameAccount, String webUsername) {
            this.completionId = Objects.requireNonNull(completionId, "completionId");
            this.linkId = Objects.requireNonNull(linkId, "linkId");
            this.proof = Objects.requireNonNull(proof, "proof");
            this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
            this.playerName = Objects.requireNonNull(playerName, "playerName");
            this.provisionGameAccount = provisionGameAccount;
            this.webUsername = webUsername;
        }

        /** @return the completion message's envelope id, used for the persisted receipt */
        public String completionId() {
            return completionId;
        }

        /** @return the id of the link that completed */
        public String linkId() {
            return linkId;
        }

        /** @return the proof the web user used */
        public LinkProof proof() {
            return proof;
        }

        /** @return the player's UUID */
        public UUID playerUuid() {
            return playerUuid;
        }

        /** @return the player's name */
        public String playerName() {
            return playerName;
        }

        /** @return whether the module must create a game account for this player */
        public boolean provisionGameAccount() {
            return provisionGameAccount;
        }

        /** @return the UltiKits web username for {@link LinkProof#WEB_REGISTRATION}, otherwise null */
        public String webUsername() {
            return webUsername;
        }

        @Override
        public String toString() {
            return "LinkCompletion{completionId=" + completionId + ", linkId=" + linkId + ", proof=" + proof
                    + ", playerUuid=" + playerUuid + ", playerName=" + playerName
                    + ", provisionGameAccount=" + provisionGameAccount + ", webUsername=" + webUsername + "}";
        }
    }

    /**
     * One link that stopped being usable.
     *
     * @since 6.3.1
     */
    public static final class LinkExpiry {
        private final String linkId;
        private final UUID playerUuid;
        private final String playerName;
        private final ExpiryReason reason;

        /**
         * Creates an expiry; a module test may build one directly.
         *
         * @param linkId the id of the link that expired
         * @param playerUuid the player's UUID
         * @param playerName the player's name
         * @param reason why it stopped being usable
         */
        public LinkExpiry(String linkId, UUID playerUuid, String playerName, ExpiryReason reason) {
            this.linkId = Objects.requireNonNull(linkId, "linkId");
            this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
            this.playerName = Objects.requireNonNull(playerName, "playerName");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        /** @return the id of the link that expired */
        public String linkId() {
            return linkId;
        }

        /** @return the player's UUID */
        public UUID playerUuid() {
            return playerUuid;
        }

        /** @return the player's name */
        public String playerName() {
            return playerName;
        }

        /** @return why the link stopped being usable */
        public ExpiryReason reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "LinkExpiry{linkId=" + linkId + ", playerUuid=" + playerUuid + ", playerName=" + playerName
                    + ", reason=" + reason + "}";
        }
    }

    /**
     * Receives completions and expiries of this server's links.
     *
     * <p>Methods run on a framework worker thread, never on the primary thread. The framework calls
     * {@link #onCompleted} for every completion whose result it has not recorded yet, including ones
     * redelivered after a restart; the result must be persisted by the framework before the gateway
     * is told, so a module never needs its own persistence. Answering with a future that never
     * completes, or that fails, sends no result, and the gateway asks again later.</p>
     *
     * @since 6.3.1
     */
    public interface LinkEventHandler {
        /**
         * Provisions (or declines to provision) a game account for a completed link.
         *
         * @param completion what the gateway reported
         * @return a future that completes with what the module did, never null
         */
        CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion);

        /**
         * Notifies that a link stopped being usable. No reply is sent for an expiry, and the
         * framework sends no synthetic expiry for a link whose expiry time simply passed.
         *
         * @param expiry the link and the reason
         */
        default void onExpired(LinkExpiry expiry) {
            // Modules that do not care, such as one only creating links, need not override this.
        }
    }
}
