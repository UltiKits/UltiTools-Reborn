package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome.Result;
import com.ultikits.ultitools.utils.PendingChanges.Kind;
import com.ultikits.ultitools.utils.PendingChanges.PendingChange;
import com.ultikits.ultitools.utils.PendingChanges.Requester;
import com.ultikits.ultitools.utils.PendingChanges.RevokeResult;
import com.ultikits.ultitools.utils.PendingChanges.State;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PendingChangesHandlerTest {
    private static final String LIST = "upm.pending.list";
    private static final String REVOKE = "upm.pending.revoke";
    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final long FRAME_TIME = 8_640_000_000_000_000L;
    private static final String ID = "install-0123456789abcdef";
    private static final String REVISION = "fedcba9876543210";
    private static final UltiCloudStub.Contract CONTRACT = UltiCloudStub.Contract.load(null);

    private final PendingChanges pending = mock(PendingChanges.class);
    private final Logger logger = Logger.getAnonymousLogger();
    private final List<LogRecord> log = new ArrayList<>();
    private final List<String> wire = new ArrayList<>();
    private List<StartOutcome> outcomes = List.of();
    private CloudLimits limits = CloudLimits.defaults();
    private final CloudChannel channel = new FramedChannel(wire::add, () -> true, () -> limits, Ulid::next,
            () -> FRAME_TIME);
    private final PendingChangesHandler handler = new PendingChangesHandler(() -> pending, () -> outcomes,
            new ShutdownGate(ShutdownGate.Signal.OPEN, null), () -> logger);

    private JsonObject onlyReply(String type) {
        assertThat(wire).hasSize(1);
        JsonObject frame = JsonParser.parseString(wire.getFirst()).getAsJsonObject();
        assertThat(frame.get("type").getAsString()).isEqualTo(type);
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(REQUEST_ID);
        assertThat(CONTRACT.firstIssue(UltiCloudStub.Contract.frameSchema(type), frame)).isNull();
        return frame;
    }

    private void list(List<PendingChange> entries, int omitted) {
        when(pending.snapshot()).thenReturn(new PendingChanges.Snapshot(entries, omitted, 42L));
        handler.handle(request(LIST, new JsonObject()), channel);
    }

    private JsonObject listedData() {
        return data(onlyReply(LIST + ".result"));
    }

    private JsonObject listedEntry(PendingChange entry) {
        list(List.of(entry), 0);
        return listedData().getAsJsonArray("entries").get(0).getAsJsonObject();
    }

    @ParameterizedTest
    @CsvSource({"INSTALL,install", "UPDATE,update", "UNINSTALL,uninstall"})
    void operationNamesAreTheWireNames(Kind kind, String wireKind) {
        JsonObject entry = listedEntry(change(kind, State.PENDING, REVISION, null));
        assertThat(entry.get("kind").getAsString()).isEqualTo(wireKind);
        assertThat(entry.get("id").getAsString()).isEqualTo(ID);
        assertThat(entry.get("revision").getAsString()).isEqualTo(REVISION);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,pending,false", "DOWNLOADING,downloading,false", "FAILED,failed,true",
        "NEEDS_OPERATOR,needs_operator,true", "IN_PROGRESS,in_progress,false"})
    void stateControlsTheRevisionAndFailure(State state, String wireState, boolean hasFailure) {
        String revision = state == State.DOWNLOADING ? null : REVISION;
        JsonObject entry = listedEntry(change(Kind.UPDATE, state, revision, "Reason"));
        assertThat(entry.get("state").getAsString()).isEqualTo(wireState);
        assertThat(entry.get("revision").isJsonNull()).isEqualTo(state == State.DOWNLOADING);
        assertThat(entry.get("failure").isJsonNull()).isEqualTo(!hasFailure);
        if (hasFailure) {
            assertThat(entry.get("failure").getAsString()).isEqualTo("Reason");
        }
    }

    @Test
    void omitsAnUnrevisionedRecordAndWarnsWithoutDroppingHealthyEntries() {
        captureLogs();
        list(List.of(change(Kind.INSTALL, State.PENDING, null, null),
                change(Kind.UPDATE, State.DOWNLOADING, null, null)), 0);
        JsonObject data = listedData();
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
        assertThat(data.getAsJsonArray("entries")).hasSize(1);
        assertThat(data.getAsJsonArray("entries").get(0).getAsJsonObject().get("state").getAsString())
                .isEqualTo("downloading");
        assertThat(log).singleElement().satisfies(record -> assertThat(record.getLevel()).isEqualTo(Level.WARNING));
    }

    @Test
    void textBoundsDoNotSplitSurrogatePairs() {
        JsonObject entry = listedEntry(new PendingChange(ID, REVISION, Kind.UPDATE, withPair(127), withPair(63),
                "v".repeat(65), List.of(), 42L, new Requester("remote", withPair(63)), State.FAILED, withPair(499),
                null, ModuleContent.NONE));
        assertThat(entry.get("module").getAsString()).isEqualTo("x".repeat(127));
        assertThat(entry.get("fromVersion").getAsString()).isEqualTo("x".repeat(63));
        assertThat(entry.get("toVersion").getAsString()).isEqualTo("v".repeat(64));
        assertThat(entry.get("requestedAt").getAsLong()).isEqualTo(42L);
        assertThat(entry.getAsJsonObject("requestedBy").get("kind").getAsString()).isEqualTo("remote");
        assertThat(entry.getAsJsonObject("requestedBy").get("name").getAsString()).isEqualTo("x".repeat(63));
        assertThat(entry.get("failure").getAsString()).isEqualTo("x".repeat(499));
    }

    @Test
    void legacyFieldsAndEmptyVersionsRemainNull() {
        JsonObject entry = listedEntry(new PendingChange(ID, REVISION, Kind.INSTALL, "Demo", "", null, List.of(),
                null, null, State.PENDING, null, null, ModuleContent.NONE));
        for (String field : List.of("fromVersion", "toVersion", "requestedBy", "requestedAt", "failure")) {
            assertThat(entry.get(field)).as(field).isEqualTo(JsonNull.INSTANCE);
        }
        assertThat(entry.getAsJsonArray("warnings")).isEmpty();
    }

    @Test
    void consoleNameIsAlwaysNull() {
        JsonObject entry = listedEntry(new PendingChange(ID, REVISION, Kind.INSTALL, "Demo", null, "1", List.of(),
                null, new Requester("console", "Server"), State.PENDING, null, null, ModuleContent.NONE));
        assertThat(entry.getAsJsonObject("requestedBy").get("kind").getAsString()).isEqualTo("console");
        assertThat(entry.getAsJsonObject("requestedBy").get("name")).isEqualTo(JsonNull.INSTANCE);
    }

    @Test
    void playerNameIsKept() {
        JsonObject entry = listedEntry(new PendingChange(ID, REVISION, Kind.UPDATE, "Demo", "1", "2", List.of(),
                99L, new Requester("player", "Player"), State.PENDING, null, null, ModuleContent.NONE));
        assertThat(entry.getAsJsonObject("requestedBy").get("kind").getAsString()).isEqualTo("player");
        assertThat(entry.getAsJsonObject("requestedBy").get("name").getAsString()).isEqualTo("Player");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " \t\n"})
    void substitutesMissingOrBlankFailure(String failure) {
        JsonObject entry = listedEntry(change(Kind.UNINSTALL, State.NEEDS_OPERATOR, REVISION, failure));
        assertThat(entry.get("failure").getAsString()).isEqualTo("No reason was recorded.");
    }

    @Test
    void filtersFileNamesBeforeApplyingTheFileLimit() {
        List<String> files = new ArrayList<>(List.of(".jar", "/x.jar", "path/x.jar", "path\\x.jar", "Foo.JAR",
                "x".repeat(252) + ".jar", "a.jar", "x".repeat(251) + ".jar"));
        IntStream.range(0, 18).mapToObj(i -> i + ".jar").forEach(files::add);
        JsonObject entry = listedEntry(new PendingChange(ID, REVISION, Kind.UNINSTALL, "Demo", "1", null, files,
                null, null, State.PENDING, null, null, ModuleContent.NONE));
        List<String> expected = new ArrayList<>(List.of("a.jar", "x".repeat(251) + ".jar"));
        IntStream.range(0, 14).mapToObj(i -> i + ".jar").forEach(expected::add);
        assertThat(entry.getAsJsonArray("files")).extracting(JsonElement::getAsString).containsExactlyElementsOf(expected);
    }

    @Test
    void warnsOnceWithCanonicalContentOrderIncludingFutureKinds() {
        var content = new ModuleContent(List.of(new ModuleContentKind("tag", 2), new ModuleContentKind("z_kind", 10),
                new ModuleContentKind("datapack", 1), new ModuleContentKind("a_kind", 7),
                new ModuleContentKind("unknown", null), new ModuleContentKind("enchantment", 3)));
        JsonObject entry = listedEntry(new PendingChange(ID, REVISION, Kind.UNINSTALL, "Demo", "1", null, List.of(),
                null, null, State.PENDING, null, null, content));
        assertThat(entry.getAsJsonArray("warnings")).containsExactly(JsonParser.parseString("""
                {"warning":"custom_content","kinds":[{"kind":"enchantment","count":3},
                {"kind":"datapack","count":1},{"kind":"tag","count":2},{"kind":"unknown","count":null},
                {"kind":"a_kind","count":7},{"kind":"z_kind","count":10}]}
                """));
    }

    @ParameterizedTest
    @CsvSource({"APPLIED,applied", "ROLLED_BACK,rolled_back", "NOT_APPLIED,not_applied", "NEEDS_OPERATOR,needs_operator"})
    void mapsStartOutcomes(Result result, String wireResult) {
        outcomes = List.of(new StartOutcome(Kind.UPDATE, "x".repeat(129), "a".repeat(65), "", result));
        list(List.of(), 0);
        JsonObject data = listedData();
        assertThat(data.getAsJsonArray("lastStart")).containsExactly(JsonParser.parseString("""
                {"kind":"update","module":"%s","fromVersion":"%s","toVersion":null,"result":"%s"}
                """.formatted("x".repeat(128), "a".repeat(64), wireResult)));
        assertThat(data.get("truncated").getAsBoolean()).isFalse();
    }

    @Test
    void marksSnapshotOmissions() {
        list(List.of(change(Kind.INSTALL, State.PENDING, REVISION, null)), 1);
        JsonObject data = listedData();
        assertThat(data.getAsJsonArray("entries")).hasSize(1);
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
    }

    @Test
    void capsLastStartAtTwoHundred() {
        outcomes = IntStream.range(0, 201)
                .mapToObj(i -> new StartOutcome(Kind.INSTALL, "Module " + i, null, "1", Result.APPLIED)).toList();
        list(List.of(), 0);
        JsonObject data = listedData();
        assertThat(data.getAsJsonArray("lastStart")).hasSize(200);
        assertThat(data.getAsJsonArray("lastStart").get(199).getAsJsonObject().get("module").getAsString())
                .isEqualTo("Module 199");
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
    }

    @Test
    void largeEscapedMultibyteListKeepsTheLongestPrefixWithinTheWireBudget() {
        String file = "界".repeat(248) + "\" \u0001.jar";
        var kinds = new ModuleContent(IntStream.range(0, 16)
                .mapToObj(i -> new ModuleContentKind("kind_" + i, 1)).toList());
        List<PendingChange> entries = IntStream.range(0, 200).mapToObj(i -> new PendingChange(
                "remove-%016x".formatted(i), REVISION, Kind.UNINSTALL, "Module " + (100 + i), "1", null,
                Collections.nCopies(16, file), null, null, State.PENDING, null, null, kinds)).toList();
        outcomes = List.of(new StartOutcome(Kind.UPDATE, "Demo", "1", "2", Result.ROLLED_BACK));
        list(entries, 0);
        JsonObject frame = onlyReply(LIST + ".result");
        JsonObject data = data(frame);
        JsonArray included = data.getAsJsonArray("entries");
        assertThat(included.size()).isBetween(1, 199);
        assertThat(data.getAsJsonArray("lastStart")).hasSize(1);
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
        assertThat(included).extracting(item -> item.getAsJsonObject().get("id").getAsString())
                .containsExactlyElementsOf(entries.subList(0, included.size()).stream().map(PendingChange::id).toList());
        assertThat(wire.getFirst().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(limits.maxFrameBytes());
        included.add(included.get(0));
        assertThat(frame.toString().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(limits.maxFrameBytes());
    }

    @Test
    void negotiatedSmallBudgetAlsoCapsStartupResultsIfTheyAloneDoNotFit() {
        JsonObject welcome = ContractFixtures.validFrames("welcome").getFirst().frame()
                .getAsJsonObject("payload").getAsJsonObject("limits");
        welcome.addProperty("maxFrameBytes", 65_536);
        limits = CloudLimits.fromWelcome(welcome);
        outcomes = IntStream.range(0, 200).mapToObj(i -> new StartOutcome(Kind.UPDATE, "界".repeat(128),
                "界".repeat(64), "界".repeat(64), Result.ROLLED_BACK)).toList();
        list(List.of(change(Kind.INSTALL, State.PENDING, REVISION, null)), 0);
        JsonObject data = listedData();
        assertThat(data.getAsJsonArray("lastStart").size()).isBetween(1, 199);
        assertThat(data.getAsJsonArray("entries")).isEmpty();
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
        assertThat(wire.getFirst().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_536);
    }

    @Test
    void revokePassesTheRevisionAndReturnsTheBeforeImage() {
        var before = change(Kind.INSTALL, State.PENDING, REVISION, null);
        captureLogs();
        when(pending.revoke(ID, REVISION, true)).thenReturn(RevokeResult.revoked(before));
        handler.handle(request(REVOKE, revokePayload(REVISION)), channel);
        JsonObject revoked = data(onlyReply(REVOKE + ".result")).getAsJsonObject("revoked");
        assertThat(revoked.get("revision").getAsString()).isEqualTo(REVISION);
        assertThat(revoked.get("id").getAsString()).isEqualTo(ID);
        verify(pending).revoke(ID, REVISION, true);
        assertThat(log).singleElement().satisfies(record -> assertThat(record.getLevel()).isEqualTo(Level.INFO));
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,pending_not_found", "CHANGED,pending_changed", "NOT_REVOCABLE,pending_not_revocable",
        "FAILED,write_denied"})
    void mapsEveryRevokeRefusal(RevokeResult.Outcome outcome, String code) {
        captureLogs();
        var failedEntry = change(Kind.INSTALL, State.FAILED, REVISION, "Did not apply");
        when(pending.revoke(ID, REVISION, true)).thenReturn(new RevokeResult(outcome, failedEntry, withPair(499)));
        handler.handle(request(REVOKE, revokePayload(REVISION)), channel);
        JsonObject payload = onlyReply(REVOKE + ".result").getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo(code);
        if (outcome == RevokeResult.Outcome.FAILED) {
            assertThat(payload.get("message").getAsString()).isEqualTo("x".repeat(499));
        }
        assertThat(log).isEmpty();
    }

    @Test
    void constructionFailureAttemptsExactlyOneInternalError() {
        when(pending.snapshot()).thenReturn(new PendingChanges.Snapshot(List.of(), 0, 0));
        CloudChannel broken = mock(CloudChannel.class);
        when(broken.limits()).thenReturn(limits);
        JsonObject empty = JsonParser.parseString("{\"entries\":[],\"lastStart\":[],\"truncated\":false}").getAsJsonObject();
        doThrow(IllegalArgumentException.class).when(broken).replyOk(REQUEST_ID, LIST + ".result", empty);
        handler.handle(request(LIST, new JsonObject()), broken);
        verify(broken).limits();
        verify(broken).replyOk(REQUEST_ID, LIST + ".result", empty);
        verify(broken).replyError(REQUEST_ID, LIST + ".result", "internal_error",
                "The pending change reply could not be built.", null);
        verifyNoMoreInteractions(broken);
    }

    private void captureLogs() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { log.add(record); }
            @Override public void flush() { /* No buffering. */ }
            @Override public void close() { /* No resources. */ }
        });
    }

    private static JsonObject data(JsonObject frame) {
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        return payload.getAsJsonObject("data");
    }

    private static PendingChange change(Kind kind, State state, String revision, String failure) {
        return new PendingChange(ID, revision, kind, "Demo", "1", "2", List.of("demo.jar"), 1_750_000_000_000L,
                null, state, failure, null, ModuleContent.NONE);
    }

    private static String withPair(int prefixLength) {
        return "x".repeat(prefixLength) + "😀suffix";
    }

    private static JsonObject revokePayload(String revision) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", ID);
        payload.addProperty("revision", revision);
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", 7);
        payload.add("actor", actor);
        return payload;
    }

    private static CloudRequest request(String type, JsonObject payload) {
        return new CloudRequest(REQUEST_ID, type, FRAME_TIME, null, payload);
    }
}
