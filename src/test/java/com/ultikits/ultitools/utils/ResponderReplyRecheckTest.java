package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;

/**
 * Case a0cca09d (UltiTools-Reborn#677) on the protocol v2 path (rewritten in plan 22-23; the v1 version drove the
 * removed v1 inbound dispatch): a module responder's result that completes after the request was admitted is sent
 * only on the connection that received the request, never on a newer one, and a result for a connection that has
 * closed is dropped without a WARNING. On v2 this holds by construction: the reply goes to the channel of the
 * connection the router received the request on, and a closed channel refuses every frame quietly.
 * <p>
 * Not carried from v1: "a result completing after disable starts is not sent". On v2 a reply that completes in the
 * moment between {@code UltiTools#onDisable}'s first step and its {@code stopWebsocket()} is still sent on the open
 * connection (no scheduler work, no WARNING); the logout and replaced-session cases reduce to the replaced-connection
 * case below, since a logout closes the connection.
 */
@DisplayName("#677 a responder reply goes only to the connection that admitted the request")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ResponderReplyRecheckTest {

    @TempDir
    Path temp;

    private V2StubSession session;
    private final BlockingQueue<CompletableFuture<JsonObject>> handedOut = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        session = new V2StubSession(temp, MockBukkit.mock());
        session.responders().registerResponder("ultimail.inbox.list", data -> {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            handedOut.add(future);
            return future;
        }, "UltiMail");
        session.start(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (CompletableFuture<JsonObject> future : handedOut) {
            future.complete(new JsonObject());
        }
        session.close();
        MockBukkitHelper.safeUnmock();
    }

    private static JsonObject result() {
        JsonObject result = new JsonObject();
        result.addProperty("answer", 42);
        return result;
    }

    private List<LogRecord> linesAtOrAbove(Level level) {
        List<LogRecord> found = new ArrayList<>();
        for (LogRecord record : session.pluginLog()) {
            if (record.getLevel().intValue() >= level.intValue()) {
                found.add(record);
            }
        }
        return found;
    }

    private CompletableFuture<JsonObject> admit() throws InterruptedException {
        session.send("module.request", "ultimail", null);
        CompletableFuture<JsonObject> future = handedOut.poll(10, TimeUnit.SECONDS);
        assertThat(future).as("the responder was called").isNotNull();
        return future;
    }

    @Test
    @DisplayName("a result completing after the connection was replaced is sent on neither connection, no WARNING")
    void replyCompletingAfterTheConnectionWasReplacedIsNotSent() throws Exception {
        CompletableFuture<JsonObject> pending = admit();
        int upgrades = session.stub().accessLog().count("-> 101");
        session.stub().closeLive(1001, "going away");
        session.await(() -> session.stub().accessLog().count("-> 101") > upgrades
                && session.stub().gateway().sent("welcome").size() >= 2
                && CloudAuthManager.status().isConnected(), "the replacement connection");

        pending.complete(result());
        session.pass(800L);

        assertThat(session.stub().gateway().received("module.response")).as("no reply on any connection").isEmpty();
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines")
                .extracting(LogRecord::getMessage).isEmpty();
    }

    @Test
    @DisplayName("control: on the admitting live connection the reply is sent exactly once, correlated to the request")
    void replyOnTheAdmittedLiveConnectionIsSentOnce() throws Exception {
        session.send("module.request", "ultimail", null);
        CompletableFuture<JsonObject> pending = handedOut.poll(10, TimeUnit.SECONDS);
        assertThat(pending).isNotNull();
        String id = session.stub().gateway().sent("module.request").get(0).get("id").getAsString();

        pending.complete(result());
        JsonObject reply = session.reply("module.response", 1);
        session.pass(300L);

        assertThat(session.stub().gateway().received("module.response")).hasSize(1);
        assertThat(reply.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(reply.getAsJsonObject("payload").getAsJsonObject("data").get("answer").getAsInt()).isEqualTo(42);
        assertThat(linesAtOrAbove(Level.WARNING)).extracting(LogRecord::getMessage).isEmpty();
    }

    @Test
    @DisplayName("two in-flight requests answered from one shared cached result each reply to their own request")
    void repliesFromOneSharedCachedResultKeepTheirOwnReplyTo() throws Exception {
        CompletableFuture<JsonObject> first = admit();
        CompletableFuture<JsonObject> second = admit();
        List<String> ids = new ArrayList<>();
        for (JsonObject sent : session.stub().gateway().sent("module.request")) {
            ids.add(sent.get("id").getAsString());
        }
        JsonObject cached = result();
        JsonObject cachedAsBuilt = cached.deepCopy();

        Thread firstCompleter = new Thread(() -> first.complete(cached), "shared-result-completer-1");
        Thread secondCompleter = new Thread(() -> second.complete(cached), "shared-result-completer-2");
        firstCompleter.start();
        secondCompleter.start();
        firstCompleter.join(TimeUnit.SECONDS.toMillis(10));
        secondCompleter.join(TimeUnit.SECONDS.toMillis(10));
        session.reply("module.response", 2);

        List<String> repliedTo = new ArrayList<>();
        for (JsonObject reply : session.stub().gateway().received("module.response")) {
            repliedTo.add(reply.get("replyTo").getAsString());
            assertThat(reply.getAsJsonObject("payload").getAsJsonObject("data").get("answer").getAsInt())
                    .isEqualTo(42);
        }
        assertThat(repliedTo).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(cached).as("the responder's cached result is never changed by a reply").isEqualTo(cachedAsBuilt);
        assertThat(linesAtOrAbove(Level.WARNING)).extracting(LogRecord::getMessage).isEmpty();
    }
}
