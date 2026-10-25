package com.ultikits.ultitools.websocket;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.ExpiryReason;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.Failure;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.GameAccountResult;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.GameAuth;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkCompletion;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkCreation;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkEventHandler;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkExpiry;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkProof;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The module-facing shape of {@link CloudPlayerLinks}: what a module can call, what it can build in a
 * test, and which arguments are refused before anything is sent.
 */
@DisplayName("CloudPlayerLinks: the module-facing player-link API")
class CloudPlayerLinksTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private RecordingTransport transport;
    private CloudPlayerLinks links;
    private UltiToolsPlugin plugin;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        links = new CloudPlayerLinks(transport);
        plugin = Mockito.mock(UltiToolsPlugin.class);
    }

    @Test
    @DisplayName("create delegates the validated call and returns the transport's future")
    void createDelegates() {
        CompletableFuture<LinkCreation> answer = CompletableFuture.completedFuture(
                LinkCreation.failed(Failure.NOT_CONNECTED, "offline"));
        transport.next = answer;
        Set<LinkProof> proofs = EnumSet.of(LinkProof.IN_GAME_SESSION, LinkProof.WEB_REGISTRATION);

        CompletableFuture<LinkCreation> returned = links.create(plugin, STEVE, "Steve", GameAuth.none(), proofs);

        assertThat(returned).isSameAs(answer);
        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.playerUuid).isEqualTo(STEVE);
        assertThat(transport.playerName).isEqualTo("Steve");
        assertThat(transport.proofs).isEqualTo(proofs);
    }

    @Test
    @DisplayName("setHandler and removeHandler reach the transport")
    void handlerCallsDelegate() {
        LinkEventHandler handler = new CompletingHandler();

        links.setHandler(plugin, handler);
        assertThat(transport.handler).isSameAs(handler);
        assertThat(transport.owner).isSameAs(plugin);

        links.removeHandler(plugin);
        assertThat(transport.removed).isSameAs(plugin);
    }

    @Test
    @DisplayName("unusable arguments are refused before anything is sent")
    void argumentsAreValidated() {
        assertThatThrownBy(() -> links.create(null, STEVE, "Steve", GameAuth.none(),
                EnumSet.of(LinkProof.IN_GAME_SESSION))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> links.create(plugin, null, "Steve", GameAuth.none(),
                EnumSet.of(LinkProof.IN_GAME_SESSION))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> links.create(plugin, STEVE, null, GameAuth.none(),
                EnumSet.of(LinkProof.IN_GAME_SESSION))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.create(plugin, STEVE, "", GameAuth.none(),
                EnumSet.of(LinkProof.IN_GAME_SESSION))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.create(plugin, STEVE, "Steve", GameAuth.none(),
                Collections.<LinkProof>emptySet())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.create(plugin, STEVE, "Steve", null,
                EnumSet.of(LinkProof.IN_GAME_SESSION))).isInstanceOf(NullPointerException.class);
        assertThat(transport.calls).isZero();
    }

    @Test
    @DisplayName("every public value type is constructible or creatable from a module test")
    void valueTypesAreConstructible() {
        LinkCreation created = LinkCreation.created("01JB2Z8Y3K4M5N6P7Q8R9S0TVX", "https://panel/link#ucl_x",
                Instant.ofEpochMilli(1761275400000L), Arrays.asList(LinkProof.IN_GAME_SESSION));
        assertThat(created.failure()).isNull();
        assertThat(created.acceptedProofs()).containsExactly(LinkProof.IN_GAME_SESSION);

        LinkCreation failed = LinkCreation.failed(Failure.RATE_LIMITED, "slow down");
        assertThat(failed.linkId()).isNull();
        assertThat(failed.acceptedProofs()).isEmpty();

        LinkCompletion completion = new LinkCompletion("01JB2Z8Y3K4M5N6P7Q8R9S0CC1", "01JB2Z8Y3K4M5N6P7Q8R9S0TVX",
                LinkProof.WEB_REGISTRATION, STEVE, "Steve", true, "steve_web");
        assertThat(completion.webUsername()).isEqualTo("steve_web");
        assertThat(completion.provisionGameAccount()).isTrue();

        LinkExpiry expiry = new LinkExpiry("01JB2Z8Y3K4M5N6P7Q8R9S0TVX", STEVE, "Steve", ExpiryReason.DENIED);
        assertThat(expiry.reason()).isEqualTo(ExpiryReason.DENIED);

        assertThat(GameAuth.authPlugin(true, true).isAuthPlugin()).isTrue();
        assertThat(GameAuth.none().isAuthPlugin()).isFalse();
        assertThat(GameAccountResult.values()).hasSize(5);
        assertThat(Failure.values()).hasSize(5);
        assertThat(LinkProof.values()).hasSize(3);
    }

    @Test
    @DisplayName("the handler's expiry hook is optional")
    void expiryHookIsOptional() {
        List<String> calls = new java.util.ArrayList<>();
        LinkEventHandler handler = new CompletingHandler();
        handler.onExpired(new LinkExpiry("01JB2Z8Y3K4M5N6P7Q8R9S0TVX", STEVE, "Steve", ExpiryReason.EXPIRED));
        calls.add("no-op default ran without an override");
        assertThat(calls).hasSize(1);
    }

    /** A transport that records what the API asked for. */
    private static final class RecordingTransport implements CloudPlayerLinks.Transport {
        private CompletableFuture<LinkCreation> next = new CompletableFuture<>();
        private int calls;
        private UUID playerUuid;
        private String playerName;
        private Set<LinkProof> proofs;
        private LinkEventHandler handler;
        private UltiToolsPlugin owner;
        private UltiToolsPlugin removed;

        @Override
        public CompletableFuture<LinkCreation> create(UltiToolsPlugin requester, UUID uuid, String name,
                                                      GameAuth gameAuth, Set<LinkProof> offeredProofs) {
            calls++;
            playerUuid = uuid;
            playerName = name;
            proofs = offeredProofs;
            return next;
        }

        @Override
        public void setHandler(UltiToolsPlugin declaringOwner, LinkEventHandler declaringHandler) {
            owner = declaringOwner;
            handler = declaringHandler;
        }

        @Override
        public void removeHandler(UltiToolsPlugin declaringOwner) {
            removed = declaringOwner;
        }
    }

    private static final class CompletingHandler implements LinkEventHandler {
        @Override
        public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
            return CompletableFuture.completedFuture(GameAccountResult.NOT_REQUESTED);
        }
    }
}
