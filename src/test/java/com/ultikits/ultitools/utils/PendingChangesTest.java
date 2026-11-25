package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.ModuleFileTransactions.CrashPoints;
import com.ultikits.ultitools.utils.ModuleFileTransactions.FileOps;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StageResult;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome;
import com.ultikits.ultitools.utils.PendingChanges.Kind;
import com.ultikits.ultitools.utils.PendingChanges.RevokeResult.Outcome;
import com.ultikits.ultitools.utils.PendingChanges.State;

class PendingChangesTest {
    @TempDir File server;
    private File data;
    private File modules;
    private File records;
    private ModuleFileTransactions transactions;
    private PendingChanges pending;
    private File oldJar;
    private List<UltiToolsPlugin> loaded;

    @BeforeEach
    void setUp() {
        data = ModuleUpdateFixtures.dataFolderIn(server);
        modules = ModuleFileTransactions.modulesFolder(data);
        records = ModuleFileTransactions.transactionsFolder(data);
        transactions = new ModuleFileTransactions(data);
        pending = new PendingChanges(transactions);
        loaded = List.of();
    }

    @AfterEach
    void resetLedger() {
        ModuleContentLedgers.install(ModuleContentLedger.NONE);
    }

    @Test
    void installsOnlyAtStartupAndCommitsWhenThatJarLoads() throws IOException {
        assertThat(stage("1.1", true).getOutcome()).isEqualTo(StageResult.Outcome.QUEUED_INSTALL);
        var change = pending.list().getFirst();
        assertThat(change.kind()).isEqualTo(Kind.INSTALL);
        assertThat(change.state()).isEqualTo(State.PENDING);
        assertThat(change.requestedAt()).isPositive();
        assertThat(change.requestedBy()).isEqualTo(new PendingChanges.Requester("player", "Operator"));
        assertThat(change.fromVersion()).isNull();
        assertThat(change.id()).startsWith("install-");
        assertThat(change.recordFile()).exists();
        assertThat(treeOf(modules)).isEmpty();
        transactions.applyBeforeLoad();
        assertThat(Files.readString(change.recordFile().toPath())).contains("APPLIED");
        var target = new File(modules, "demo-1.1.jar");
        assertThat(target).exists();
        var module = loadedModule("Demo", "1.1", "demo");
        transactions.observeAfterLoad(List.of(module), ignored -> target);
        assertThat(transactions.startOutcomes()).containsExactly(
                new StartOutcome(Kind.INSTALL, "Demo", null, "1.1", StartOutcome.Result.APPLIED));
        assertThat(treeOf(records)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {CrashPoints.AFTER_INSTALL_MOVED, CrashPoints.AFTER_INSTALL_APPLIED_RECORDED})
    void restartAfterFirstMoveEitherFinishesApplyingOrRollsBackUnconfirmed(String point) throws IOException {
        stage("1.1", true);
        var interrupted = new ModuleFileTransactions(modules, records, FileOps.DEFAULT, reached -> {
            if (reached.equals(point)) {
                throw new AssertionError(point);
            }
        });
        assertThatThrownBy(interrupted::applyBeforeLoad).isInstanceOf(AssertionError.class).hasMessage(point);
        var restarted = new ModuleFileTransactions(data);
        restarted.applyBeforeLoad();
        if (CrashPoints.AFTER_INSTALL_MOVED.equals(point)) {
            var target = new File(modules, "demo-1.1.jar");
            assertThat(target).exists();
            restarted.observeAfterLoad(List.of(loadedModule("Demo", "1.1", "demo")), ignored -> target);
            assertThat(restarted.startOutcomes().getFirst().result()).isEqualTo(StartOutcome.Result.APPLIED);
        } else {
            assertThat(treeOf(modules)).isEmpty();
            assertThat(restarted.startOutcomes().getFirst().result()).isEqualTo(StartOutcome.Result.ROLLED_BACK);
            assertThat(restarted.pendingReports()).anyMatch(line -> line.getKey().equals(UpmKeys.START_INSTALL_UNCONFIRMED.translationKey()));
        }
        assertThat(treeOf(records)).isEmpty();
        assertThat(treeOf(modules)).noneMatch(name -> name.endsWith(".part"));
    }

    @Test
    void occupiedTargetCannotBeOverwrittenOrDeletedByRevokingTheFailure() throws IOException {
        stage("1.1", true);
        Files.createDirectories(modules.toPath());
        var target = modules.toPath().resolve("demo-1.1.jar");
        Files.writeString(target, "not the staged JAR");
        transactions.applyBeforeLoad();
        var failure = pending.list().getFirst();
        assertThat(failure.state()).isEqualTo(State.FAILED);
        assertThat(pending.revoke(failure.id(), failure.revision(), true).outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(Files.readString(target)).isEqualTo("not the staged JAR");
    }

    @ParameterizedTest
    @CsvSource({
        "none, false, install, 2, QUEUED_INSTALL, INSTALL, 2",
        "none, true, install, 1, ALREADY_CURRENT, none, none",
        "none, true, install, 2, STAGED, UPDATE, 2",
        "none, true, update, 2, STAGED, UPDATE, 2",
        "none, false, update, 2, FAILED, none, none",
        "install, false, install, 2, ALREADY_STAGED, INSTALL, 2",
        "install, false, install, 3, REPLACED_INSTALL, INSTALL, 3",
        "install, false, update, 2, ALREADY_STAGED, INSTALL, 2",
        "install, false, update, 3, REPLACED_INSTALL, INSTALL, 3",
        "update, true, install, 1, CANCELLED_UPDATE, none, none",
        "update, true, install, 2, ALREADY_STAGED, UPDATE, 2",
        "update, true, install, 3, REPLACED_UPDATE, UPDATE, 3",
        "update, true, update, 2, ALREADY_STAGED, UPDATE, 2",
        "update, true, update, 3, REPLACED_UPDATE, UPDATE, 3",
        "uninstall, true, install, 1, CANCELLED_UNINSTALL_SAME, none, none",
        "uninstall, true, install, 3, CANCELLED_UNINSTALL, UPDATE, 3",
        "uninstall, true, update, 3, PENDING_UNINSTALL, UNINSTALL, none"
    })
    void installAndUpdateKeepAtMostOneChange(String previous, boolean running, String action, String version,
                                            StageResult.Outcome expected, String kind, String to) throws IOException {
        prepare(previous, running);
        assertThat(stage(version, action.equals("install")).getOutcome()).isEqualTo(expected);
        if (kind.equals("none")) {
            assertThat(pending.list()).isEmpty();
        } else {
            assertThat(pending.list()).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo(Kind.valueOf(kind));
                assertThat(item.toVersion()).isEqualTo(to.equals("none") ? null : to);
            });
        }
    }

    @ParameterizedTest
    @CsvSource({
        "none,false,not_found,false", "none,true,queued,true", "install,false,cancelled_install,false",
        "update,true,queued,true", "uninstall,true,already_queued,true"
    })
    void uninstallCancelsInstallsOrReplacesUpdatesWithoutTouchingTheRunningModules(
            String previous, boolean running, String suffix, boolean remains) throws IOException {
        prepare(previous, running);
        var result = remove();
        assertThat(result.replies().getFirst().key().path()).isEqualTo("upm.uninstall." + suffix);
        assertThat(pending.list()).hasSize(remains ? 1 : 0);
        if (previous.equals("update")) {
            assertThat(result.replies()).extracting(PluginInstallUtils.PendingReply::key)
                    .containsExactly(UpmKeys.UNINSTALL_QUEUED, UpmKeys.UNINSTALL_ALSO_CANCELLED_UPDATE);
        }
        if (running) {
            assertThat(oldJar).exists();
            assertThat(pending.list().getFirst().files()).containsExactly("demo-1.jar");
        }
    }

    @ParameterizedTest
    @CsvSource({"install,false", "update,true", "uninstall,true"})
    void revokeCancelsEveryPendingKindWithoutAdvancingGeneration(String kind, boolean running) throws IOException {
        prepare(kind, running);
        long generation = ModuleFileTransactions.pendingGeneration();
        var previous = pending.list().getFirst();
        assertThat(pending.revoke(previous.id(), "0".repeat(16), true).outcome()).isEqualTo(Outcome.CHANGED);
        assertThat(pending.list()).containsExactly(previous);
        assertThat(pending.revoke(previous.id(), previous.revision(), true).outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(pending.revoke(previous.id(), null, false).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(ModuleFileTransactions.pendingGeneration()).isEqualTo(generation);
    }

    @ParameterizedTest
    @CsvSource({"FAILED,REVOKED", "NEEDS_OPERATOR,NOT_REVOCABLE", "APPLIED,NOT_REVOCABLE",
        "COMMITTING,NOT_REVOCABLE", "ROLLING_BACK,NOT_REVOCABLE"})
    void revisionIsFromTheRecordBytesAndEachStateDecidesRevocability(String state, Outcome expected) throws IOException {
        stage("2", true);
        var first = pending.list().getFirst();
        assertThat(pending.list().getFirst().revision()).isEqualTo(first.revision());
        stage("3", true);
        var replacement = pending.list().getFirst();
        assertThat(replacement.revision()).isNotEqualTo(first.revision());
        var path = replacement.recordFile().toPath();
        byte[] bytes = Files.readAllBytes(path);
        Files.writeString(path, Files.readString(path) + " ");
        assertThat(Files.size(path)).isEqualTo(bytes.length + 1);
        assertThat(pending.list().getFirst().revision()).isNotEqualTo(replacement.revision());
        Files.writeString(path, Files.readString(path).replace("PENDING", state));
        var changed = pending.list().getFirst();
        assertThat(pending.revoke(changed.id(), changed.revision(), true).outcome()).isEqualTo(expected);
    }

    @Test
    void aDownloadRevokedBeforePublishingCannotCreateARecord() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var download = executor.submit(() -> transactions.stageChange("demo", "2", "Demo", null,
                    () -> loaded, ignored -> oldJar, catalogue("demo", "2"), (link, name, folder) -> {
                        entered.countDown();
                        try {
                            if (!released.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("release timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                        moduleJar(new File(folder, name), "Demo", "2", "demo");
                    }, null));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var item = pending.list().getFirst();
                assertThat(item.state()).isEqualTo(State.DOWNLOADING);
                assertThat(item.revision()).isNull();
                assertThat(pending.revoke(item.id(), null, true).outcome()).isEqualTo(Outcome.REVOKED);
            } finally {
                released.countDown();
            }
            assertThat(download.get(10, TimeUnit.SECONDS).getReasonKey()).isEqualTo(UpmKeys.REASON_CANCELLED_WHILE_DOWNLOADING);
        }
        assertThat(pending.list()).isEmpty();
    }

    @Test
    void onlyOneOfTwoConcurrentRevokesWins() throws Exception {
        stage("2", true);
        var entry = pending.list().getFirst();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Outcome> revoke = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                return pending.revoke(entry.id(), null, false).outcome();
            };
            var first = executor.submit(revoke);
            var second = executor.submit(revoke);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Outcome.REVOKED, Outcome.NOT_FOUND);
        }
    }

    @Test
    void requesterUsesServerSenderKindAndBoundsRemoteName() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("operator");
        var remote = mock(RemoteConsoleCommandSender.class);
        when(remote.getName()).thenReturn("r".repeat(90));
        assertThat(PendingChanges.Requester.from(player)).isEqualTo(new PendingChanges.Requester("player", "operator"));
        assertThat(PendingChanges.Requester.from(mock(ConsoleCommandSender.class)))
                .isEqualTo(new PendingChanges.Requester("console", null));
        assertThat(PendingChanges.Requester.from(remote)).isEqualTo(new PendingChanges.Requester("remote", "r".repeat(64)));
    }

    @Test
    void uninstallAndListQueryCustomContentWithThePluginDescriptorName() throws IOException {
        prepare("none", true);
        List<String> queried = new ArrayList<>();
        ModuleContent warning = new ModuleContent(List.of(new ModuleContentKind("enchantment", 2)));
        ModuleContentLedgers.install((identifier, name) -> {
            queried.add(name);
            return warning;
        });
        assertThat(remove().content()).isEqualTo(warning);
        assertThat(pending.findByModule("demo").content()).isEqualTo(warning);
        assertThat(remove().content()).isEqualTo(warning);
        assertThat(queried).containsExactly("Demo", "Demo", "Demo");
    }

    @Test
    void startReportsCanFallBackToKeyAndArgumentValues() throws IOException {
        stage("2", true);
        transactions.applyBeforeLoad();
        transactions.observeAfterLoad(List.of(), ignored -> null);
        var logs = new ArrayList<String>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord message) { logs.add(message.getMessage()); }
            @Override public void flush() { /* Untimed capture. */ }
            @Override public void close() { /* No resource. */ }
        });
        transactions.flushReports(logger, key -> key, null);
        assertThat(logs).singleElement().asString().contains(UpmKeys.START_INSTALL_ROLLED_BACK.translationKey(), "Demo", "2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void startupUsesTheConsoleFallbackLanguageAndPureText(String language) throws IOException {
        stage("2", true);
        transactions.applyBeforeLoad();
        transactions.observeAfterLoad(List.of(loadedModule("Demo", "2", "demo")), ignored -> new File(modules, "demo-2.jar"));
        var logs = new ArrayList<String>();
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord line) { logs.add(line.getMessage()); }
            @Override public void flush() { /* No buffering. */ }
            @Override public void close() { /* No resources. */ }
        });
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);
             var text = new com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport(language)) {
            bukkit.when(org.bukkit.Bukkit::getConsoleSender).thenReturn(mock(ConsoleCommandSender.class));
            transactions.flushReports(logger, key -> key, text.messages());
        }
        assertThat(logs).containsExactly("en".equals(language) ? "Module Demo 2 is installed: this start loaded it."
                : "模块 Demo 2 已安装：本次启动已加载。");
    }

    @Test
    void revisionCheckedRevokeDoesNotCancelAConcurrentReplacement() throws Exception {
        stage("1.0", true);
        var old = pending.list().getFirst();
        var downloading = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var staged = pool.submit(() -> transactions.stageChange("demo", "1.1", "Demo", null,
                    List::of, ignored -> null, catalogue("demo", "1.1"), (url, name, folder) -> {
                        downloading.countDown();
                        try {
                            if (!finish.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("test download timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                        moduleJar(new File(folder, name), "Demo", "1.1", "demo");
                    }, null));
            try {
                assertThat(downloading.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(pending.revoke(old.id(), old.revision(), true).outcome()).isEqualTo(Outcome.CHANGED);
            } finally {
                finish.countDown();
            }
            assertThat(staged.get(10, TimeUnit.SECONDS).getOutcome()).isEqualTo(StageResult.Outcome.REPLACED_INSTALL);
            assertThat(pending.list()).singleElement().satisfies(entry -> {
                assertThat(entry.toVersion()).isEqualTo("1.1");
                assertThat(entry.revision()).isNotEqualTo(old.revision());
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "REMOVE"})
    void legacyRecordsWithoutRequesterMetadataCanStillBeListedAndApplied(String kind) throws Exception {
        oldJar = moduleJar(new File(modules, "demo-1.jar"), "Demo", "1", "demo");
        loaded = List.of(loadedModule("Demo", "1", "demo"));
        if ("REMOVE".equals(kind)) {
            assertThat(remove().queued()).isTrue();
        } else {
            assertThat(stage("2", false).getOutcome()).isEqualTo(StageResult.Outcome.STAGED);
        }
        var record = pending.list().getFirst().recordFile().toPath();
        var json = com.google.gson.JsonParser.parseString(Files.readString(record)).getAsJsonObject();
        for (String key : List.of("requestedAt", "requestedBy", "fromVersion", "removalIdentifyString")) {
            json.remove(key);
        }
        if ("REMOVE".equals(kind)) {
            json.addProperty("key", "Demo");
            var bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("REMOVE:Demo".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            record = Files.move(record, record.resolveSibling("remove-" + java.util.HexFormat.of().formatHex(bytes, 0, 8) + ".json"));
        }
        Files.writeString(record, json.toString());
        assertThat(pending.list()).singleElement().satisfies(entry -> {
            assertThat(entry.module()).isEqualTo("Demo");
            assertThat(entry.requestedAt()).isNull();
            assertThat(entry.requestedBy()).isNull();
            assertThat(entry.fromVersion()).isNull();
        });
        transactions.applyBeforeLoad();
        assertThat(oldJar).doesNotExist();
        if ("UPDATE".equals(kind)) {
            assertThat(new File(modules, "demo-2.jar")).exists();
        } else {
            assertThat(record).doesNotExist();
        }
    }

    @Test
    void crashPointPropertyOnlyArmsKnownNames() {
        final String property = "ultitools.upm.testCrashAt";
        String original = System.getProperty(property);
        var logger = Logger.getLogger(ModuleFileTransactions.class.getName());
        var warnings = new ArrayList<LogRecord>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord line) { warnings.add(line); }
            @Override public void flush() { /* No buffering. */ }
            @Override public void close() { /* No resources. */ }
        };
        logger.addHandler(capture);
        try {
            System.clearProperty(property);
            assertThat(CrashPoints.fromSystemProperty()).isSameAs(CrashPoints.NONE);
            assertThat(warnings).isEmpty();
            System.setProperty(property, "not-a-crash-point");
            assertThat(CrashPoints.fromSystemProperty()).isSameAs(CrashPoints.NONE);
            assertThat(warnings).singleElement().satisfies(warning -> {
                assertThat(warning.getLevel()).isEqualTo(Level.WARNING);
                assertThat(warning.getMessage()).isEqualTo("Ignoring unknown ultitools.upm.testCrashAt test crash point.");
            });
            System.setProperty(property, CrashPoints.AFTER_INSTALL_MOVED);
            assertThat(CrashPoints.fromSystemProperty()).isNotSameAs(CrashPoints.NONE);
            assertThat(warnings).hasSize(1);
        } finally {
            logger.removeHandler(capture);
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
    }

    private void prepare(String kind, boolean running) throws IOException {
        if (running) {
            oldJar = moduleJar(new File(modules, "demo-1.jar"), "Demo", "1", "demo");
            loaded = List.of(loadedModule("Demo", "1", "demo"));
        }
        switch (kind) {
            case "install" -> assertThat(stage("2", true).getOutcome()).isEqualTo(StageResult.Outcome.QUEUED_INSTALL);
            case "update" -> assertThat(stage("2", false).getOutcome()).isEqualTo(StageResult.Outcome.STAGED);
            case "uninstall" -> assertThat(remove().queued()).isTrue();
            default -> { /* No earlier record. */ }
        }
    }

    private StageResult stage(String version, boolean install) {
        return transactions.stageChange("demo", install ? version : null, install ? "Demo" : null,
                new PendingChanges.Requester("player", "Operator"), () -> loaded, ignored -> oldJar,
                catalogue("demo", version), (link, name, folder) -> moduleJar(new File(folder, name), "Demo", version, "demo"), null);
    }

    private PluginInstallUtils.PendingResult remove() {
        var identity = new ModuleFileTransactions.RemovedModule(List.of("demo"), List.of("Demo"), List.of("demo-1.jar"));
        return ModuleFileTransactions.underLock(() -> transactions.queueRemoval("Demo", "demo", loaded.isEmpty() ? null : "1",
                identity, oldJar == null ? List.of() : List.of(oldJar), List.of(), mock(ConsoleCommandSender.class), !loaded.isEmpty()));
    }
}
