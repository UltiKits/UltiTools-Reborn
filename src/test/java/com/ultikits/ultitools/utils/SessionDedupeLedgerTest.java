package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The shared ledger of plan 22-21 (D-17 as narrowed by S-52, S-59, S-65): ids and receipts live in the current
 * session's set, are bounded with it, and reach {@code cloud-outbox.json} synchronously.
 */
@DisplayName("SessionDedupeLedger: bounded receipts, synchronous persistence, the current session's state")
class SessionDedupeLedgerTest {

    private static final String SERVER = "01JTESTSERVER0000000000000";
    private static final long DAY = 86_400_000L;

    @TempDir
    Path temp;

    private final AtomicLong clock = new AtomicLong(1_760_000_000_000L);
    private final List<LogRecord> warnings = Collections.synchronizedList(new ArrayList<>());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private CommandDedupeSet dedupe;
    private CloudStateStore store;
    private SessionDedupeLedger ledger;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("SessionDedupeLedgerTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        TestHelper.mockUltiToolsInstance(plugin -> org.mockito.Mockito.lenient().when(plugin.getLogger())
                .thenReturn(logger));
        dedupe = new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX, CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS,
                clock::get);
        store = new CloudStateStore(temp.resolve(CloudStateStore.FILE_NAME),
                new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, clock::get), dedupe);
        store.setLoggerForTesting(Logger.getAnonymousLogger());
        store.load(SERVER);
        ledger = SessionDedupeLedger.fixed(dedupe, store, SERVER);
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // un-publishes the UltiTools singleton
    void tearDown() throws Exception {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static JsonObject receipt() {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("status", "ok");
        JsonObject data = new JsonObject();
        data.addProperty("gameAccount", "created");
        receipt.add("data", data);
        return receipt;
    }

    private static String id(int n) {
        return String.format("01ARYZ6S41TSV4RRFFQ6%06d", n);
    }

    @Test
    @DisplayName("persistNow writes the ids and their receipts to the state file at once")
    void persistNowWritesReceipts() throws IOException {
        synchronized (ledger) {
            assertThat(ledger.firstSeen(id(1))).isTrue();
            ledger.recordReceipt(id(2), receipt());
            ledger.persistNow();
        }
        JsonObject written = JsonParser.parseString(new String(
                Files.readAllBytes(temp.resolve(CloudStateStore.FILE_NAME)), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertThat(written.getAsJsonArray("commandIds")).hasSize(2);
        assertThat(written.getAsJsonArray("commandIds").get(1).getAsJsonObject().getAsJsonObject("receipt"))
                .isEqualTo(receipt());
        assertThat(ledger.receiptFor(id(2))).isEqualTo(receipt());
        assertThat(ledger.firstSeen(id(1))).isFalse();
    }

    @Test
    @DisplayName("a receipt over 4 KiB is not recorded and logs one WARNING")
    void oversizedReceiptWarnsOnce() {
        JsonObject big = new JsonObject();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= CommandDedupeSet.MAX_RECEIPT_BYTES; i++) {
            text.append('x');
        }
        big.addProperty("message", text.toString());
        ledger.recordReceipt(id(1), big);
        assertThat(ledger.receiptFor(id(1))).isNull();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains(id(1)).doesNotContain("xxxx");
    }

    @Test
    @DisplayName("the 1001st id evicts the oldest id and its receipt; an id older than 24 h is new again")
    void receiptsAreBoundedWithTheirIds() {
        ledger.recordReceipt(id(0), receipt());
        for (int i = 1; i <= 1000; i++) {
            ledger.firstSeen(id(i));
        }
        assertThat(ledger.receiptFor(id(0))).isNull();
        assertThat(ledger.firstSeen(id(0))).isTrue();

        ledger.recordReceipt(id(2000), receipt());
        clock.addAndGet(DAY + 1);
        assertThat(ledger.receiptFor(id(2000))).isNull();
        assertThat(ledger.firstSeen(id(2000))).isTrue();
    }

    @Test
    @DisplayName("each call follows the current session; with none, ids are new and persistence fails")
    void followsTheCurrentTarget() throws IOException {
        AtomicReference<SessionDedupeLedger.Target> current = new AtomicReference<>();
        SessionDedupeLedger following = new SessionDedupeLedger(current::get);
        assertThat(following.firstSeen(id(1))).isTrue();
        assertThat(following.firstSeen(id(1))).isTrue();
        assertThat(following.receiptFor(id(1))).isNull();
        following.recordReceipt(id(1), receipt());
        assertThatThrownBy(following::persistNow).isInstanceOf(IOException.class);

        current.set(new SessionDedupeLedger.Target(dedupe, store, SERVER));
        assertThat(following.firstSeen(id(1))).isTrue();
        assertThat(following.firstSeen(id(1))).isFalse();
        following.persistNow();
        assertThat(Files.exists(temp.resolve(CloudStateStore.FILE_NAME))).isTrue();
    }

    @Test
    @DisplayName("a detached store writes nothing; a failing write surfaces as IOException")
    void failingWriteIsAnIoException() throws IOException {
        Path blocked = temp.resolve("blocked");
        Files.createDirectories(blocked.resolve(CloudStateStore.FILE_NAME));
        CloudStateStore failing = new CloudStateStore(blocked.resolve(CloudStateStore.FILE_NAME),
                new CloudOutbox(10, clock::get), dedupe);
        failing.setLoggerForTesting(Logger.getAnonymousLogger());
        SessionDedupeLedger broken = SessionDedupeLedger.fixed(dedupe, failing, SERVER);
        assertThatThrownBy(broken::persistNow).isInstanceOf(IOException.class);
    }
}
