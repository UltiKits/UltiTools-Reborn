package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StageResult;
import com.ultikits.ultitools.utils.PendingChanges.PendingChange;
import com.ultikits.ultitools.utils.PendingChanges.RevokeResult.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class PendingChangesHandlerEndToEndTest {
    private static final String CAPABILITY = "upm:pending";
    private static final String LIST = "upm.pending.list";
    private static final String REVOKE = "upm.pending.revoke";

    @TempDir Path temp;
    private V2StubSession session;
    private File dataFolder;
    private ModuleFileTransactions transactions;
    private PendingChanges pending;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        dataFolder = ModuleUpdateFixtures.dataFolderIn(temp.resolve("journal-server").toFile());
        transactions = new ModuleFileTransactions(dataFolder);
        pending = new PendingChanges(transactions);
        session = new V2StubSession(temp, MockBukkit.mock());
        session.start(plugin -> when(plugin.getModuleFileTransactions()).thenReturn(transactions));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (session != null) {
                session.close();
            }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }

    @ParameterizedTest
    @CsvSource({"en,Panel user #7 revoked the pending install of Demo.", "zh,面板用户 #7 撤销了 Demo 的待生效操作（安装）。"})
    void acceptedHandshakeListsAndRevokesTheRealJournalEntry(String locale, String auditLine) throws Exception {
        PendingChange staged = stage("1");
        assertThat(declaredCapabilities(1)).contains(CAPABILITY);
        assertThat(CloudSession.current().agentProtocol().acceptedCapabilities()).contains(CAPABILITY);
        JsonObject listReply = exchange(LIST, "all", null, LIST + ".result");
        JsonArray entries = okData(listReply).getAsJsonArray("entries");
        assertThat(entries).hasSize(1);
        JsonObject listed = entries.get(0).getAsJsonObject();
        assertThat(listed.get("id").getAsString()).isEqualTo(staged.id());
        assertThat(listed.get("revision").getAsString()).isEqualTo(staged.revision());
        try (var text = new CommandMessageTestSupport(locale);
             var scope = FrameworkMessages.overrideForTests(text.messages(), Locale.forLanguageTag(locale))) {
            JsonObject reply = revoke(staged.id(), staged.revision(), REVOKE + ".result");
            assertThat(okData(reply).getAsJsonObject("revoked")).isEqualTo(listed);
        }
        assertThat(pending.list()).isEmpty();
        assertThat(staged.recordFile()).doesNotExist();
        assertThat(session.pluginLog().stream().filter(record -> record.getLevel() == Level.INFO))
                .extracting(LogRecord::getMessage).containsOnlyOnce(auditLine);
    }

    @Test
    void rejectedCapabilityAfterReconnectNeverReachesEitherOperation() throws Exception {
        PendingChange staged = stage("1");
        var before = CloudSession.current().agentProtocol();
        session.stub().gateway().refuseCapabilities(Set.of(CAPABILITY));
        session.stub().closeLive(1001, "gateway capability negotiation test");
        assertThat(declaredCapabilities(2)).contains(CAPABILITY);
        session.await(() -> {
            var current = CloudSession.current().agentProtocol();
            return current != null && current != before && current.isEstablished();
        }, "the second welcome");
        assertThat(CloudSession.current().agentProtocol().acceptedCapabilities()).doesNotContain(CAPABILITY);
        assertError(exchange(LIST, "all", null, "error"), "capability_not_accepted");
        assertError(revoke(staged.id(), staged.revision(), "error"), "capability_not_accepted");
        assertThat(pending.list()).containsExactly(staged);
    }

    @Test
    void entryAlreadyRevokedByTheCommandIsNotFoundFromThePanel() throws Exception {
        PendingChange staged = stage("1");
        assertThat(pending.revokeByModule("Demo").outcome()).isEqualTo(Outcome.REVOKED);
        assertError(revoke(staged.id(), staged.revision(), REVOKE + ".result"), "pending_not_found");
    }

    @Test
    void commandAndPanelRevokingTheSameEntryHaveExactlyOneWinner() throws Exception {
        PendingChange staged = stage("1");
        var ready = new CountDownLatch(2);
        // Rendezvous in the actual panel worker before it enters the journal, not just before sending the frame.
        doAnswer(call -> {
            awaitConcurrentRevoke(ready);
            return transactions;
        }).when(session.plugin()).getModuleFileTransactions();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var command = executor.submit(() -> {
                awaitConcurrentRevoke(ready);
                return pending.revokeByModule("Demo").outcome();
            });
            JsonObject reply = revoke(staged.id(), staged.revision(), REVOKE + ".result");
            Outcome commandOutcome = command.get(10, TimeUnit.SECONDS);
            if (commandOutcome == Outcome.REVOKED) {
                assertError(reply, "pending_not_found");
            } else {
                assertThat(commandOutcome).isEqualTo(Outcome.NOT_FOUND);
                assertThat(okData(reply).getAsJsonObject("revoked").get("id").getAsString()).isEqualTo(staged.id());
            }
        }
        assertThat(pending.list()).isEmpty();
        assertThat(staged.recordFile()).doesNotExist();
    }

    private static void awaitConcurrentRevoke(CountDownLatch ready) throws InterruptedException {
        ready.countDown();
        assertThat(ready.await(10, TimeUnit.SECONDS)).as("both revoke paths reached the journal").isTrue();
    }

    @Test
    void supersededRevisionDoesNotUndoTheReplacement() throws Exception {
        PendingChange earlier = stage("1");
        PendingChange replacement = stage("2");
        assertThat(replacement.id()).isEqualTo(earlier.id());
        assertThat(replacement.revision()).isNotEqualTo(earlier.revision());
        assertError(revoke(earlier.id(), earlier.revision(), REVOKE + ".result"), "pending_changed");
        assertThat(pending.list()).containsExactly(replacement);
    }

    @Test
    void failedEntryUsesTheJournalNonRevocablePolicy() throws Exception {
        PendingChange staged = stage("1");
        File target = new File(ModuleFileTransactions.modulesFolder(dataFolder), staged.files().getFirst());
        moduleJar(target, "Other", "1", "other");
        transactions.applyBeforeLoad();
        PendingChange failed = pending.list().getFirst();
        assertThat(failed.state()).isEqualTo(PendingChanges.State.FAILED);
        assertError(revoke(failed.id(), failed.revision(), REVOKE + ".result"), "pending_not_revocable");
        assertThat(pending.list()).containsExactly(failed);
    }

    @Test
    void downloadingCanBeRevokedOnlyWithNullRevision() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var stagedJar = new AtomicReference<File>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var download = executor.submit(() -> transactions.stageChange("demo", "1", "Demo", null,
                    List::of, ignored -> null, catalogue("demo", "1"), (url, name, folder) -> {
                        File jar = new File(folder, name);
                        moduleJar(jar, "Demo", "1", "demo");
                        stagedJar.set(jar);
                        entered.countDown();
                        awaitDownloadRelease(released);
                    }, null));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(stagedJar.get()).isFile();
                PendingChange inFlight = pending.list().getFirst();
                assertThat(inFlight.state()).isEqualTo(PendingChanges.State.DOWNLOADING);
                assertThat(inFlight.revision()).isNull();
                assertError(revoke(inFlight.id(), "0123456789abcdef", REVOKE + ".result"), "pending_changed");
                JsonObject before = okData(revoke(inFlight.id(), null, REVOKE + ".result")).getAsJsonObject("revoked");
                assertThat(before.get("state").getAsString()).isEqualTo("downloading");
                assertThat(before.get("revision").isJsonNull()).isTrue();
            } finally {
                released.countDown();
            }
            assertThat(download.get(10, TimeUnit.SECONDS).getReasonKey())
                    .isEqualTo(UpmKeys.REASON_CANCELLED_WHILE_DOWNLOADING);
        }
        assertThat(stagedJar.get()).doesNotExist();
        assertThat(stagedJar.get().getParentFile().getParentFile()).doesNotExist();
        assertThat(pending.list()).isEmpty();
    }

    @Test
    void shutdownRefusesRevocationWithoutDeletingTheRecord() throws Exception {
        PendingChange staged = stage("1");
        PluginInitiationUtils.stopAcceptingPanelMessages();
        JsonObject reply = revoke(staged.id(), staged.revision(), REVOKE + ".result");
        assertError(reply, "internal_error");
        assertThat(reply.getAsJsonObject("payload").get("message").getAsString()).contains("shutting down");
        assertThat(pending.list()).containsExactly(staged);
        assertThat(staged.recordFile()).exists();
    }

    private static void awaitDownloadRelease(CountDownLatch released) throws IOException {
        try {
            if (!released.await(15, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for the test to release the download");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private PendingChange stage(String version) {
        var result = transactions.stageChange("demo", version, "Demo", null, List::of, ignored -> null,
                catalogue("demo", version), (url, name, folder) -> moduleJar(new File(folder, name), "Demo", version,
                        "demo"), null);
        assertThat(result.getOutcome()).isIn(StageResult.Outcome.QUEUED_INSTALL, StageResult.Outcome.REPLACED_INSTALL);
        return pending.list().getFirst();
    }

    private List<String> declaredCapabilities(int helloNumber) throws InterruptedException {
        return session.reply("hello", helloNumber).getAsJsonObject("payload").getAsJsonArray("capabilities")
                .asList().stream().map(value -> value.getAsString()).toList();
    }

    private JsonObject revoke(String id, String revision, String responseType) throws InterruptedException {
        JsonObject overrides = new JsonObject();
        overrides.addProperty("id", id);
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", 7);
        overrides.add("actor", actor);
        if (revision != null) {
            overrides.addProperty("revision", revision);
        }
        return exchange(REVOKE, revision == null ? "downloading-null-revision" : "with-revision", overrides,
                responseType);
    }

    private JsonObject exchange(String type, String fixture, JsonObject payload, String expectedType)
            throws InterruptedException {
        String id = session.send(type, fixture, payload).get("id").getAsString();
        session.await(() -> repliesTo(id, type).findFirst().isPresent(), type + " answer");
        JsonObject response = repliesTo(id, type).findFirst().orElseThrow();
        assertThat(response.get("type").getAsString()).as(response.toString()).isEqualTo(expectedType);
        assertThat(session.stub().accessLog().count("WS-INVALID")).isZero();
        return response;
    }

    private Stream<JsonObject> repliesTo(String id, String requestType) {
        var gateway = session.stub().gateway();
        return Stream.concat(gateway.received(requestType + ".result").stream(), gateway.received("error").stream())
                .filter(frame -> frame.has("replyTo") && frame.get("replyTo").getAsString().equals(id));
    }

    private static JsonObject okData(JsonObject frame) {
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        return payload.getAsJsonObject("data");
    }

    private static void assertError(JsonObject frame, String code) {
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo(code);
    }
}
