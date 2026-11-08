package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.utils.TestHelper;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection to clear the global singleton
class ServerPropertiesManagerTest {

    private File tempDir;
    private File propsFile;
    private ServerPropertiesManager manager;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = new File(System.getProperty("java.io.tmpdir"), "spm-test-" + System.currentTimeMillis());
        tempDir.mkdirs();
        propsFile = new File(tempDir, "server.properties");
        // Write a sample server.properties
        try (PrintWriter pw = new PrintWriter(new FileWriter(propsFile))) {
            pw.println("motd=A Minecraft Server");
            pw.println("max-players=20");
            pw.println("view-distance=10");
            pw.println("pvp=true");
            pw.println("difficulty=normal");
            pw.println("gamemode=survival");
            pw.println("allow-nether=true");
            pw.println("rcon.password=secret123");
            pw.println("server-port=25565");
        }
        manager = new ServerPropertiesManager(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        propsFile.delete();
        tempDir.delete();
        // UltiTools.ultiTools 是全局静态字段。只有部分用例会装它，但不还原就会漏给
        // 同一个 JVM 里后面的测试类。无条件清空，不去判断本用例装没装过。
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /** Applies a set_all batch the way the protocol v2 handler does (the v1 reply was removed in 6.4.0). */
    private ServerPropertiesManager.SetAllResult applySetAll(JsonObject data) {
        return manager.applySetAll(data.getAsJsonObject("values"));
    }

    private static JsonObject setAllRequest(Map<String, String> values) {
        JsonObject payload = new JsonObject();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                payload.add(entry.getKey(), com.google.gson.JsonNull.INSTANCE);
            } else {
                payload.addProperty(entry.getKey(), entry.getValue());
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("action", "set_all");
        data.add("values", payload);
        return data;
    }

    @Test
    @DisplayName("getSafeProperties should return only safe keys")
    void shouldReturnOnlySafeKeys() {
        Map<String, String> props = manager.getSafeProperties();
        assertThat(props).containsKey("motd");
        assertThat(props).containsKey("max-players");
        assertThat(props).containsKey("pvp");
        // Should NOT contain sensitive keys
        assertThat(props).doesNotContainKey("rcon.password");
        assertThat(props).doesNotContainKey("server-port");
    }

    @Test
    @DisplayName("setProperty should update safe property")
    void shouldUpdateSafeProperty() throws IOException {
        boolean result = manager.setProperty("motd", "New MOTD");
        assertThat(result).isTrue();

        Map<String, String> props = manager.getSafeProperties();
        assertThat(props.get("motd")).isEqualTo("New MOTD");
    }

    @Test
    @DisplayName("setProperty should reject unsafe property")
    void shouldRejectUnsafeProperty() {
        boolean result = manager.setProperty("rcon.password", "hacked");
        assertThat(result).isFalse();
    }

    /**
     * issue #281：{@code set_all} 的 {@code success} 曾经是写死的 {@code true}，与实际写进去几个键无关。
     *
     * <p>这里断言的对象刻意是**发回面板的那条消息**，而不是内部计数器。真相当时其实存在于
     * {@code updated} 字段里，缺陷是没有人保证调用方会去看它，而 {@code success} 这个名字
     * 明确在说另一件事。所以要证明修好了，就得证明那个名字现在说的是真话。
     * <p>As of 6.4.0 the protocol v1 reply is gone; these tests assert the {@link ServerPropertiesManager.SetAllResult}
     * the protocol v2 handler builds its answer from.
     */
    @Nested
    @DisplayName("set_all 的结果必须是真的（#281）")
    class SetAllReportsTheTruth {

        @Test
        @DisplayName("全部键都在白名单外：success 为 false，一个都没写进去")
        void allKeysRejected() {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("rcon.password", "hacked");
            request.put("server-port", "1337");

            ServerPropertiesManager.SetAllResult message = applySetAll(setAllRequest(request));

            assertThat(message.isSuccess()).isFalse();
            assertThat(message.getUpdated().size()).isZero();
            assertThat(message.getRejected()).containsExactly("rcon.password", "server-port");

            // 负向对照：被拒不只是「没报成功」，磁盘上确实没被改。
            assertThat(manager.getSafeProperties()).doesNotContainKey("rcon.password");
        }

        @Test
        @DisplayName("部分命中：success 为 false，写进去的和被拒的分别列出来")
        void partiallyRejected() {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Half applied");
            request.put("rcon.password", "hacked");

            ServerPropertiesManager.SetAllResult message = applySetAll(setAllRequest(request));

            assertThat(message.isSuccess()).isFalse();
            assertThat(message.getUpdated().size()).isEqualTo(1);
            assertThat(message.getRejected()).containsExactly("rcon.password");
            // 命中的那个确实写进去了——部分失败不等于整批回滚，响应说的就是这个。
            assertThat(manager.getSafeProperties().get("motd")).isEqualTo("Half applied");
        }

        @Test
        @DisplayName("全部命中：success 为 true，rejected 与 failed 都是空的")
        void allKeysApplied() {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Fully applied");
            request.put("max-players", "64");

            ServerPropertiesManager.SetAllResult message = applySetAll(setAllRequest(request));

            assertThat(message.isSuccess()).isTrue();
            assertThat(message.getUpdated().size()).isEqualTo(2);
            assertThat(message.getRejected()).isEmpty();
            assertThat(message.getFailed()).isEmpty();
            assertThat(manager.getSafeProperties().get("max-players")).isEqualTo("64");
        }

        @Test
        @DisplayName("值是 JSON null 的键记进 skipped，但不让整批失败")
        void explicitNullsAreVisibleButHarmless() {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Applied");
            request.put("pvp", null);

            ServerPropertiesManager.SetAllResult message = applySetAll(setAllRequest(request));

            // null 读作「这个别动」，所以它不算失败；但它此前既不计数也不上报，
            // 是第四类隐形跳过，现在至少看得见。
            assertThat(message.isSuccess()).isTrue();
            assertThat(message.getSkipped()).containsExactly("pvp");
            assertThat(message.getUpdated().size()).isEqualTo(1);
            assertThat(manager.getSafeProperties().get("pvp")).isEqualTo("true");
        }

        @Test
        @DisplayName("白名单里的键写不进去时算 failed，与「被白名单拒了」分开报")
        void writeFailuresAreDistinctFromRejections() {
            // server.properties 不存在 = 写不进去，但键本身是合法的。
            // 这两种情况过去都只是 setProperty 返回 false，调用方分不出来，而处置完全不同。
            assertThat(propsFile.delete()).isTrue();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Never lands");
            request.put("rcon.password", "hacked");

            ServerPropertiesManager.SetAllResult message = applySetAll(setAllRequest(request));

            assertThat(message.isSuccess()).isFalse();
            assertThat(message.getFailed()).containsExactly("motd");
            assertThat(message.getRejected()).containsExactly("rcon.password");
        }

        /**
         * 值不是 JSON 原始值时，`getAsString()` 在结果被构造之前就炸了，于是整批中断、
         * 一条响应都发不出去——面板永远等不到回复。而 Gson 的失败方式并不统一，
         * 所以三种载荷各测一次，实测（gson 2.8.9）：
         *
         * <pre>
         * {}            → UnsupportedOperationException
         * []            → IllegalStateException
         * ["Hello"]     → 不抛，静默拆包成 "Hello"
         * </pre>
         *
         * 最后一种最隐蔽：它不是崩溃，是畸形载荷被悄悄接受并写进磁盘。
         */
        @Test
        @DisplayName("值是 JSON 对象：算 malformed，不中断整批，也不写磁盘")
        void objectValuesAreMalformedNotFatal() {
            JsonObject payload = new JsonObject();
            payload.add("motd", new JsonObject());
            payload.addProperty("max-players", "64");
            JsonObject data = new JsonObject();
            data.addProperty("action", "set_all");
            data.add("values", payload);

            ServerPropertiesManager.SetAllResult message = applySetAll(data);

            assertThat(message.isSuccess()).isFalse();
            assertThat(message.getMalformed()).containsExactly("motd");
            // 后面的键仍然被处理了——一个畸形键不该毁掉整批。
            assertThat(message.getUpdated().size()).isEqualTo(1);
            assertThat(manager.getSafeProperties().get("motd")).isEqualTo("A Minecraft Server");
            assertThat(manager.getSafeProperties().get("max-players")).isEqualTo("64");
        }

        @Test
        @DisplayName("值是空数组：算 malformed（Gson 这里抛的是另一种异常）")
        void emptyArrayValuesAreMalformed() {
            JsonObject payload = new JsonObject();
            payload.add("motd", new com.google.gson.JsonArray());
            JsonObject data = new JsonObject();
            data.addProperty("action", "set_all");
            data.add("values", payload);

            ServerPropertiesManager.SetAllResult message = applySetAll(data);

            assertThat(message.getMalformed()).containsExactly("motd");
        }

        @Test
        @DisplayName("值是单元素数组：算 malformed，而不是被静默拆包写进去")
        void singleElementArraysAreNotSilentlyUnwrapped() {
            // 这一条是三种里唯一不抛异常的：Gson 的 JsonArray.getAsString() 在长度为 1 时
            // 返回该元素的字符串。也就是说没有守卫的话，{"motd": ["Hello"]} 会被当成
            // motd=Hello 写进磁盘——畸形载荷被悄悄接受，而不是被拒绝。
            com.google.gson.JsonArray wrapped = new com.google.gson.JsonArray();
            wrapped.add("Hello");
            JsonObject payload = new JsonObject();
            payload.add("motd", wrapped);
            JsonObject data = new JsonObject();
            data.addProperty("action", "set_all");
            data.add("values", payload);

            ServerPropertiesManager.SetAllResult message = applySetAll(data);

            assertThat(message.isSuccess()).isFalse();
            assertThat(message.getMalformed()).containsExactly("motd");
            assertThat(manager.getSafeProperties().get("motd")).isEqualTo("A Minecraft Server");
        }

        @Test
        @DisplayName("非字符串的原始值仍然可以写（数字、布尔）")
        void nonStringPrimitivesStillWork() {
            JsonObject payload = new JsonObject();
            payload.addProperty("max-players", 64);
            payload.addProperty("pvp", false);
            JsonObject data = new JsonObject();
            data.addProperty("action", "set_all");
            data.add("values", payload);

            ServerPropertiesManager.SetAllResult message = applySetAll(data);

            // 负向对照：守卫拦的是「不是原始值」，不是「不是字符串」。
            // 面板发数字和布尔是正常的，拦下它们会是一个新的回归。
            assertThat(message.isSuccess()).isTrue();
            assertThat(manager.getSafeProperties().get("max-players")).isEqualTo("64");
            assertThat(manager.getSafeProperties().get("pvp")).isEqualTo("false");
        }
    }

    @Nested
    @DisplayName("被挡下的键在服务器日志里也要看得见（#281）")
    class OperatorVisibleLogging {

        @Test
        @DisplayName("有键被挡下时记一条 WARNING，并点名是哪个键")
        void rejectedKeysAreLoggedAtWarning() {
            Logger logger = mock(Logger.class);
            // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
            TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));

            applySetAll(setAllRequest(Collections.singletonMap("rcon.password", "hacked")));

            ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
            verify(logger).log(eq(Level.WARNING), line.capture());
            assertThat(line.getValue()).contains("rcon.password");
        }

        @Test
        @DisplayName("全部成功时不记 WARNING")
        void nothingIsLoggedWhenEverythingApplies() {
            Logger logger = mock(Logger.class);
            TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));

            applySetAll(setAllRequest(Collections.singletonMap("motd", "Fine")));

            verify(logger, never()).log(eq(Level.WARNING), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("没有全局单例时不抛异常——这个类的文件逻辑不该依赖它")
        void missingSingletonDoesNotBreakTheBatch() {
            // 负向对照。生产环境下 UltiTools.getInstance() 一定就绪（管理器在 onEnable 里构造），
            // 但纯文件逻辑的用例不该被迫先装一个全局单例才能跑。

            assertThat(applySetAll(setAllRequest(Collections.singletonMap("rcon.password", "hacked"))).isSuccess())
                    .isFalse();
        }
    }

    @Nested
    class LegacyWriteFailureLogging {
        private final Logger capturedLogger = Logger.getLogger(ServerPropertiesManager.class.getName());
        private final List<LogRecord> warnings = new ArrayList<>();
        private final Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
                // In-memory capture.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };

        @BeforeEach
        void captureBothSingleKeyAndBatchWarnings() {
            capturedLogger.addHandler(capture);
            TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(capturedLogger));
        }

        @AfterEach
        void removeCapture() {
            capturedLogger.removeHandler(capture);
        }

        @Test
        void anIoFailureIsLoggedOnceButRemainsInTheBatchResult() throws IOException {
            byte[] original = Files.readAllBytes(propsFile.toPath());
            JsonObject values = new JsonObject();
            values.addProperty("motd", "unwritten-value-sentinel");
            ServerPropertiesManager.SetAllResult result;

            try (MockedStatic<AtomicConfigWriter> writer = failStaging()) {
                result = manager.applySetAll(values);
            }

            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getMessage()).contains("motd", "IOException")
                    .doesNotContain("unwritten-value-sentinel");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailed()).containsExactly("motd");
            assertThat(result.getFailureReasons()).isEmpty();
            assertThat(result.describeFailure()).contains("motd");
            assertThat(Files.readAllBytes(propsFile.toPath())).isEqualTo(original);
        }

        @Test
        void aSingleKeyIoFailureStillLogsItsDiagnostic() throws IOException {
            try (MockedStatic<AtomicConfigWriter> writer = failStaging()) {
                assertThat(manager.setProperty("motd", "unwritten-value-sentinel")).isFalse();
            }

            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getMessage()).contains("motd", "IOException")
                    .doesNotContain("unwritten-value-sentinel");
        }

        @Test
        void anUnreportedMissingFileFailureStillAppearsInTheBatchWarning() {
            assertThat(propsFile.delete()).isTrue();
            JsonObject values = new JsonObject();
            values.addProperty("motd", "unwritten-value-sentinel");

            ServerPropertiesManager.SetAllResult result = manager.applySetAll(values);

            assertThat(result.getFailed()).containsExactly("motd");
            assertThat(result.isSuccess()).isFalse();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getMessage()).contains("motd").doesNotContain("unwritten-value-sentinel");
        }

        @Test
        void anUnreportedReadFailureStillAppearsInTheBatchWarning() {
            assertThat(propsFile.delete()).isTrue();
            assertThat(propsFile.mkdir()).isTrue();
            JsonObject values = new JsonObject();
            values.addProperty("motd", "unwritten-value-sentinel");

            ServerPropertiesManager.SetAllResult result = manager.applySetAll(values);

            assertThat(result.getFailed()).containsExactly("motd");
            assertThat(result.isSuccess()).isFalse();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getMessage()).contains("motd").doesNotContain("unwritten-value-sentinel");
        }

        @Test
        void aMixedBatchSummarizesOnlyTheFailuresNotAlreadyReportedPerKey() throws IOException {
            Files.write(propsFile.toPath(), "pvp=false\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            byte[] original = Files.readAllBytes(propsFile.toPath());
            JsonObject values = new JsonObject();
            values.addProperty("motd", "unwritten-value-sentinel");
            values.addProperty("pvp", "false");
            values.addProperty("max-players", "20");
            values.addProperty("simulation-distance", "8");
            values.addProperty("rcon.password", "unwritten-value-sentinel");
            values.add("view-distance", new JsonObject());
            ServerPropertiesManager.SetAllResult result;

            try (MockedStatic<AtomicConfigWriter> writer = failStaging()) {
                result = manager.applySetAll(values);
            }

            assertThat(result.getUpdated()).containsExactly("max-players");
            assertThat(result.getFailed()).containsExactly("motd", "pvp");
            assertThat(result.getFailureReasons()).containsOnlyKeys("pvp");
            assertThat(result.getNotPresentOnServer()).containsExactly("simulation-distance");
            assertThat(result.getRejected()).containsExactly("rcon.password");
            assertThat(result.getMalformed()).containsExactly("view-distance");
            assertThat(result.describeFailure()).contains("motd", "pvp", "simulation-distance", "rcon.password", "view-distance");
            assertThat(warnings).hasSize(3);
            assertThat(warnings.get(0).getMessage()).contains("motd", "IOException");
            assertThat(warnings.get(1).getMessage()).contains("pvp", "more than one line");
            assertThat(warnings.get(2).getMessage()).contains("simulation-distance", "rcon.password", "view-distance")
                    .doesNotContain("motd", "pvp", "unwritten-value-sentinel");
            assertThat(Files.readAllBytes(propsFile.toPath())).isEqualTo(original);
        }

        private MockedStatic<AtomicConfigWriter> failStaging() throws IOException {
            MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class);
            writer.when(() -> AtomicConfigWriter.stage(eq(propsFile.toPath()), Mockito.any(byte[].class)))
                    .thenThrow(new IOException("unwritten-value-sentinel"));
            return writer;
        }
    }

    /**
     * A key on {@link ServerPropertiesManager}'s allowlist that the RUNNING server's own
     * {@code server.properties} does not have -- D-15, the SAFE_KEYS issue.
     *
     * <p>{@code SAFE_KEYS} is a ceiling across every Paper version this framework supports
     * (14 declared; only a subset exists on any one Paper version), not a per-server promise.
     * Writing a key Paper does not read on this server's version is silently ignored by the
     * platform -- the property lands in the operator's own file (Java's {@code Properties} does
     * not validate against Paper's schema) but Paper never reads it back, so the change appears
     * to succeed and never takes effect. This must be refused before it happens, with a reason
     * distinguishable from "not allowlisted" and "no properties file".
     */
    @Nested
    @DisplayName("白名单里但当前服务器版本没有的键 -- D-15, SAFE_KEYS issue")
    class AbsentAllowlistedKeyRejection {

        /** A single-key panel edit; the protocol v1 {@code set} message was removed in 6.4.0. */
        private ServerPropertiesManager.SetAllResult set(String key, String value) {
            return applySetAll(setAllRequest(Collections.singletonMap(key, value)));
        }

        @Test
        @DisplayName("写一个在白名单但文件里没有的键：被拒绝，且文件字节不变")
        void writingAnAbsentAllowlistedKeyIsRejectedAndFileStaysByteIdentical() throws IOException {
            // simulation-distance is on SAFE_KEYS but not written by setUp()'s fixture file.
            byte[] before = Files.readAllBytes(propsFile.toPath());

            ServerPropertiesManager.SetAllResult result = set("simulation-distance", "8");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getNotPresentOnServer()).containsExactly("simulation-distance");

            byte[] after = Files.readAllBytes(propsFile.toPath());
            assertThat(after).isEqualTo(before);
            assertThat(manager.getSafeProperties()).doesNotContainKey("simulation-distance");
        }

        @Test
        @DisplayName("不在白名单的键仍被拒，且归为 rejected 而不是「文件里没有」")
        void keyNotOnTheAllowlistIsStillRejectedWithTheExistingReason() {
            ServerPropertiesManager.SetAllResult result = set("rcon.password", "hacked");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getRejected()).containsExactly("rcon.password");
            assertThat(result.getNotPresentOnServer()).isEmpty();
        }

        @Test
        @DisplayName("三种拒绝互不相同：不在白名单 / 白名单里但文件没有 / 没有配置文件")
        void theThreeRefusalReasonsAreDistinguishableFromOneAnother() {
            assertThat(set("rcon.password", "hacked").getRejected()).containsExactly("rcon.password");
            assertThat(set("simulation-distance", "8").getNotPresentOnServer()).containsExactly("simulation-distance");

            assertThat(propsFile.delete()).isTrue();
            ServerPropertiesManager.SetAllResult noFile = set("motd", "New MOTD");
            assertThat(noFile.getFailed()).containsExactly("motd");
            assertThat(noFile.getRejected()).isEmpty();
            assertThat(noFile.getNotPresentOnServer()).isEmpty();
        }

        @Test
        @DisplayName("读结果只包含文件里实际存在的键——今天已经是对的，这里只是钉住不让后续改动悄悄破坏它")
        void readResultOnlyContainsKeysActuallyPresentInTheFile() {
            Map<String, String> props = manager.getSafeProperties();

            assertThat(props).containsKey("motd");
            // simulation-distance is on SAFE_KEYS but is not one of the keys setUp() writes.
            assertThat(props).doesNotContainKey("simulation-distance");
        }

        @Test
        @DisplayName("白名单本身的内容没有被删减")
        void allowlistContentIsUnchanged() throws Exception {
            Field field = ServerPropertiesManager.class.getDeclaredField("SAFE_KEYS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> safeKeys = (Set<String>) field.get(null);

            assertThat(safeKeys).containsExactlyInAnyOrder(
                    "motd", "max-players", "view-distance", "simulation-distance",
                    "spawn-protection", "difficulty", "gamemode", "pvp",
                    "allow-nether", "allow-flight", "spawn-animals", "spawn-monsters",
                    "spawn-npcs", "enable-command-block");
        }

        @Test
        @DisplayName("Gate-2 finding: set_all 里白名单里但本服务器没有的键，走 notPresentOnServer 而不是 failed")
        void setAllRoutesAbsentAllowlistedKeysSeparatelyFromFailed() {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "New MOTD");              // present, allowlisted -- updated
            request.put("simulation-distance", "8");      // allowlisted, absent from this server
            request.put("rcon.password", "hacked");       // not allowlisted at all -- rejected

            ServerPropertiesManager.SetAllResult result = manager.applySetAll(setAllRequest(request).getAsJsonObject("values"));

            assertThat(result.getUpdated()).containsExactly("motd");
            assertThat(result.getNotPresentOnServer()).containsExactly("simulation-distance");
            assertThat(result.getFailed())
                    .as("the absent-allowlisted key must NOT be indistinguishable from a real I/O failure")
                    .doesNotContain("simulation-distance")
                    .isEmpty();
            assertThat(result.getRejected()).containsExactly("rcon.password");
            assertThat(result.isSuccess()).isFalse();
        }
    }
}
