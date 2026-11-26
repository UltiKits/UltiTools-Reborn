package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.dataFolderIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.Messages;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpmStartReportTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void reportsUseTheConsoleLocaleAndKeepTheirLevels(String language) throws IOException {
        var committed = start("commit", false);
        var unwritable = start("write-failure", true);
        String file = unwritable.record().getAbsolutePath();
        String error = "IOException: " + file + ": access denied: SecurityException: injected write failure";
        String success = "en".equals(language)
                ? "Module Demo updated from 1.0 to 1.1: this start confirmed the new version loaded, and the old JAR has been deleted."
                : "模块 Demo 已从 1.0 更新到 1.1：本次启动确认新版本已加载，旧版本 JAR 已删除。";
        String failure = "en".equals(language) ? "Update record " + file + " could not be written: " + error
                : "更新记录 " + file + " 无法写入：" + error;
        try (var bukkit = mockStatic(Bukkit.class); var text = new CommandMessageTestSupport(language)) {
            bukkit.when(Bukkit::getConsoleSender).thenReturn(mock(ConsoleCommandSender.class));
            assertThat(flush(committed.transactions(), text.messages())).containsExactly(new Line(Level.INFO, success));
            assertThat(flush(unwritable.transactions(), text.messages())).containsExactly(new Line(Level.SEVERE, failure));
        }
    }

    @Test
    void earlyStartupWithoutMessagesKeepsTheKeyAndEveryValue() throws IOException {
        var committed = start("early", false);
        assertThat(flush(committed.transactions(), null)).containsExactly(
                new Line(Level.INFO, "ultitools:upm.start.update.committed Demo, 1.0, 1.1"));
        assertThat(committed.transactions().pendingReports()).isEmpty();
    }

    private Start start(String directory, boolean failCommitWrite) throws IOException {
        File dataFolder = dataFolderIn(root.resolve(directory).toFile());
        File modules = ModuleFileTransactions.modulesFolder(dataFolder);
        File records = ModuleFileTransactions.transactionsFolder(dataFolder);
        File installed = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        var old = loadedModule("Demo", "1.0", "demo");
        var result = new ModuleFileTransactions(dataFolder).stageUpdate("demo", List.of(old),
                new ModuleUpdateFixtures.CodeSources().with(old, installed), catalogue("demo", "1.1"),
                downloading("Demo", "1.1", "demo"));
        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        File[] files = records.listFiles((folder, name) -> name.endsWith(".json"));
        assertThat(files).hasSize(1);

        AtomicBoolean rejectWrites = new AtomicBoolean();
        var transactions = new ModuleFileTransactions(modules, records, ModuleFileTransactions.FileOps.DEFAULT, point -> {
            if (rejectWrites.get() && ModuleFileTransactions.CrashPoints.BEFORE_RECORD_MOVED.equals(point)) {
                throw new SecurityException("injected write failure");
            }
        });
        transactions.applyBeforeLoad();
        rejectWrites.set(failCommitWrite);
        var loaded = loadedModule("Demo", "1.1", "demo");
        transactions.observeAfterLoad(List.of(loaded), new ModuleUpdateFixtures.CodeSources()
                .with(loaded, new File(modules, "demo-1.1.jar")));
        return new Start(transactions, files[0]);
    }

    private static List<Line> flush(ModuleFileTransactions transactions, Messages messages) {
        List<Line> output = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                output.add(new Line(record.getLevel(), record.getMessage()));
            }
            @Override public void flush() { /* Captured synchronously. */ }
            @Override public void close() { /* No external resources. */ }
        });
        transactions.flushReports(logger, messages);
        return output;
    }

    private record Start(ModuleFileTransactions transactions, File record) { }

    private record Line(Level level, String message) { }
}
