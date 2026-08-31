package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Pins {@link RemoteActionLog}'s durable, real-file behaviour — line format, root-logger
 * isolation, rotation, post-failure resilience and thread-safety — each read back from an actual
 * file on disk rather than asserted via a mocked method call. See
 * {@code 06-VALIDATION.md}'s "Manual-Only Verifications" for the content-level read-back this
 * class deliberately does not replace.
 * <p>
 * {@link RemoteActionLog#ACTION_LOG} is a {@code static} {@link Logger} shared by every instance
 * of this class in the same JVM — every test that calls {@link RemoteActionLog#init(File)} MUST
 * flush and detach the {@link Handler} it attached in an {@code @AfterEach}, or a leaked
 * {@link java.util.logging.FileHandler} keeps a temp directory open and later tests read stale
 * lines left over from an earlier test.
 */
@DisplayName("RemoteActionLog：落盘格式、根 logger 隔离、轮转与线程安全")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reach the shared static ACTION_LOG
class RemoteActionLogTest {

    @BeforeEach
    void setUp() {
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration()));
    }

    @AfterEach
    void tearDown() throws Exception {
        detachAllHandlers();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static Logger actionLogger() throws Exception {
        Field field = RemoteActionLog.class.getDeclaredField("ACTION_LOG");
        field.setAccessible(true);
        return (Logger) field.get(null);
    }

    /**
     * 刷盘并摘掉本次测试挂上的所有 handler —— 见类 javadoc：静态 logger 跨测试共享，
     * 不摘的话下一条测试会读到上一条测试留下的行，或者在 Windows 上锁住临时目录。
     */
    private static void detachAllHandlers() throws Exception {
        Logger logger = actionLogger();
        for (Handler handler : logger.getHandlers()) {
            handler.flush();
            handler.close();
            logger.removeHandler(handler);
        }
    }

    private static void flushHandlers() throws Exception {
        for (Handler handler : actionLogger().getHandlers()) {
            handler.flush();
        }
    }

    /** 读 {@code <dataFolder>/security/} 下所有 {@code action.log.*} 文件的全部行，按文件名排序。 */
    private static List<String> readAllLogLines(File dataFolder) throws IOException {
        File securityDir = new File(dataFolder, "security");
        File[] files = securityDir.listFiles();
        if (files == null) {
            return java.util.Collections.emptyList();
        }
        List<String> lines = new java.util.ArrayList<>();
        java.util.Arrays.sort(files);
        for (File file : files) {
            lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        }
        return lines.stream().filter(line -> !line.isEmpty()).collect(Collectors.toList());
    }

    @Nested
    @DisplayName("落盘格式：一行一个 JSON 对象")
    class LineFormatTests {

        @TempDir
        File dataFolder;

        @Test
        @DisplayName("DENIED 条目落盘为恰好一行，解析为携带全部七个字段的单一 JSON 对象")
        void deniedEntryIsExactlyOneLineWithAllSevenFields() throws Exception {
            RemoteActionLog log = new RemoteActionLog();
            log.init(dataFolder);

            log.record(RemoteActionLog.Entry.denied(
                    Capability.COMMANDS, "execute_command", "say hi", "panel", "blocked by policy"));
            flushHandlers();

            List<String> lines = readAllLogLines(dataFolder);
            assertThat(lines).hasSize(1);

            JsonObject json = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            assertThat(json.has("timestamp")).isTrue();
            assertThat(json.has("capability")).isTrue();
            assertThat(json.has("action")).isTrue();
            assertThat(json.has("target")).isTrue();
            assertThat(json.has("actor")).isTrue();
            assertThat(json.has("verdict")).isTrue();
            assertThat(json.has("reason")).isTrue();
            assertThat(json.get("capability").getAsString()).isEqualTo("COMMANDS");
            assertThat(json.get("action").getAsString()).isEqualTo("execute_command");
            assertThat(json.get("target").getAsString()).isEqualTo("say hi");
            assertThat(json.get("actor").getAsString()).isEqualTo("panel");
            assertThat(json.get("verdict").getAsString()).isEqualTo("DENIED");
            assertThat(json.get("reason").getAsString()).isEqualTo("blocked by policy");
        }

        @Test
        @DisplayName("ALLOWED 条目没有 reason 成员；DENIED 条目的 reason 非空")
        void allowedEntryHasNoReasonMemberDeniedEntryHasNonEmptyReason() throws Exception {
            RemoteActionLog log = new RemoteActionLog();
            log.init(dataFolder);

            log.record(RemoteActionLog.Entry.allowed(Capability.COMMANDS, "execute_command", "say hi", "panel"));
            log.record(RemoteActionLog.Entry.denied(
                    Capability.FILE_WRITE, "file_operation", "plugins/x.yml", "panel", "not editable"));
            flushHandlers();

            List<String> lines = readAllLogLines(dataFolder);
            assertThat(lines).hasSize(2);

            JsonObject allowed = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            assertThat(allowed.get("verdict").getAsString()).isEqualTo("ALLOWED");
            assertThat(allowed.has("reason")).as("ALLOWED 条目不应有 reason 成员").isFalse();

            JsonObject denied = JsonParser.parseString(lines.get(1)).getAsJsonObject();
            assertThat(denied.get("verdict").getAsString()).isEqualTo("DENIED");
            assertThat(denied.get("reason").getAsString()).as("DENIED 条目的 reason 必须非空").isNotBlank();
        }
    }

    @Nested
    @DisplayName("与根 logger 隔离")
    class RootLoggerIsolationTests {

        @TempDir
        File dataFolder;

        private Handler countingHandler;
        private Logger rootLogger;
        private int countBefore;

        @BeforeEach
        void attachCountingHandler() {
            rootLogger = Logger.getLogger("");
            countingHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    countBefore++;
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            countBefore = 0;
            rootLogger.addHandler(countingHandler);
        }

        @AfterEach
        void removeCountingHandler() {
            rootLogger.removeHandler(countingHandler);
        }

        @Test
        @DisplayName("logger 报告 parent handlers 已禁用；往根 logger 挂 handler 收不到 action-log 记录")
        void actionLogNeverReachesRootLogger() throws Exception {
            Logger actionLog = actionLogger();
            assertThat(actionLog.getUseParentHandlers())
                    .as("这是让本类不会打通到 SystemLogHandler 的唯一开关，见类 javadoc")
                    .isFalse();

            RemoteActionLog log = new RemoteActionLog();
            log.init(dataFolder);

            log.record(RemoteActionLog.Entry.allowed(Capability.COMMANDS, "execute_command", "say hi", "panel"));
            flushHandlers();

            assertThat(countBefore).as("action-log 记录不得传播到根 logger 的 handler").isZero();
        }
    }

    @Nested
    @DisplayName("轮转")
    class RotationTests {

        @TempDir
        File dataFolder;

        @Test
        @DisplayName("max-size-bytes 设小、max-files 设为 2 时，写入足够多条目后会滚动到第二个文件")
        void rotatesIntoSecondFileWhenSizeLimitExceeded() throws Exception {
            YamlConfiguration config = new YamlConfiguration();
            config.set("ultipanel.logging.action-log.max-size-bytes", 300);
            config.set("ultipanel.logging.action-log.max-files", 2);
            TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getConfig()).thenReturn(config));

            RemoteActionLog log = new RemoteActionLog();
            log.init(dataFolder);

            for (int i = 0; i < 40; i++) {
                log.record(RemoteActionLog.Entry.denied(
                        Capability.COMMANDS, "execute_command", "command number " + i, "panel", "blocked by policy"));
            }
            flushHandlers();

            File securityDir = new File(dataFolder, "security");
            File[] files = securityDir.listFiles((dir, name) -> name.startsWith("action.log."));
            assertThat(files)
                    .as("300 字节的限制加上 2 个文件配额，40 条记录必须把日志滚动到不止一个文件里")
                    .isNotNull();
            assertThat(files.length)
                    .as("security 目录里应当出现不止一个 action.log.* 文件")
                    .isGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("初始化失败后的韧性")
    class InitFailureResilienceTests {

        @Test
        @DisplayName("数据文件夹无法创建时：init 通过错误流报告失败，随后的 record 调用不抛异常")
        void recordDoesNotThrowAfterFailedInit() throws Exception {
            File notADirectory = File.createTempFile("ultitools-action-log-test", ".tmp");
            notADirectory.deleteOnExit();
            // security 子目录的父目录是个普通文件，mkdirs() 必然失败——不需要真的去踩权限系统。

            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            RemoteActionLog log = new RemoteActionLog();
            try {
                log.init(notADirectory);
            } finally {
                System.setErr(originalErr);
            }

            assertThat(captured.toString(StandardCharsets.UTF_8))
                    .as("init 失败必须通过 System.err 报告，而不是静默吞掉")
                    .contains("RemoteActionLog");

            assertThatCode(() -> log.record(
                    RemoteActionLog.Entry.allowed(Capability.COMMANDS, "execute_command", "say hi", "panel")))
                    .as("init 失败之后，record 仍然不能抛异常")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("record(null) 与线程安全")
    class NullAndThreadSafetyTests {

        @TempDir
        File dataFolder;

        @Test
        @DisplayName("record(null) 是空操作，不抛异常")
        void recordNullIsNoOp() {
            RemoteActionLog log = new RemoteActionLog();
            assertThatCode(() -> log.record(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("从非主线程记录的条目保留构造时的字段值")
        void entryRecordedFromNonMainThreadKeepsItsConstructedValues() throws Exception {
            RemoteActionLog log = new RemoteActionLog();
            log.init(dataFolder);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> log.record(RemoteActionLog.Entry.denied(
                                Capability.FILE_DELETE, "file_operation", "plugins/target.yml",
                                "panel", "not editable")))
                        .get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
            flushHandlers();

            List<String> lines = readAllLogLines(dataFolder);
            assertThat(lines).hasSize(1);
            JsonObject json = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            assertThat(json.get("capability").getAsString()).isEqualTo("FILE_DELETE");
            assertThat(json.get("action").getAsString()).isEqualTo("file_operation");
            assertThat(json.get("target").getAsString()).isEqualTo("plugins/target.yml");
            assertThat(json.get("actor").getAsString()).isEqualTo("panel");
            assertThat(json.get("verdict").getAsString()).isEqualTo("DENIED");
            assertThat(json.get("reason").getAsString()).isEqualTo("not editable");
        }
    }
}
