package com.ultikits.ultitools.utils;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.SoftAssertions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * {@link PluginInitiationUtils#handleConfigUpdate(com.google.gson.JsonObject)} 的载荷契约。
 *
 * <p>issue #236：面板下发的配置更新被静默丢弃。三处字段与面板对不上——内容在
 * {@code data.configData} 而这里读 {@code data.config}，文件在 {@code data.fileName}
 * 而这里除 {@code server_properties} 外从不读，面板不发 {@code requestId} 而这里拿
 * {@code requestId} 当「是不是一条请求」的判据。第一道就短路：进 else 分支，
 * 记一行 {@code Level.FINE} 然后返回。{@code FINE} 在默认日志配置下不打印，
 * 于是整条链路没有任何一处报错，用户看到「保存成功但没生效」。
 *
 * <p>这类缺陷读代码看不出来——两端各自都是自洽的，不一致只存在于两者之差。所以这里
 * 断言的是「给定面板真实发出的那种消息，究竟有没有落到写配置的调用上」，而不是
 * 某个分支内部的行为。
 */
@DisplayName("PluginInitiationUtils 配置更新载荷契约")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class PluginInitiationUtilsConfigUpdateTest {

    private Logger mockLogger;
    private ConfigManager mockConfigManager;
    private ServerPropertiesManager mockServerProperties;
    private UltiPanelWebSocketClient mockWebSocket;
    private Object previousPanelWs;
    private UltiTools ultiToolsMock;
    private RemoteActionLog mockRemoteActionLog;
    /** The live config the capability gate reads; rows set their switches on it, never re-stub. */
    private YamlConfiguration panelConfig;
    /** What getServerPropertiesManager() answers; a row may swap in a real manager (no re-stubbing, issue #250). */
    private ServerPropertiesManager serverPropertiesManager;

    private static final String NAMED_FILE = "config/lang.yml";
    private static final String NAMED_CONTENT = "{\"language\":\"zh\"}";
    private static final String WHOLE_CONTENT = "{\"UltiTools\":{\"config/lang.yml\":{\"language\":\"zh\"}}}";
    private static final String SERVER_PROPERTIES_CONTENT = "{\"motd\":\"synthetic\"}";

    private static final String PROPERTIES_HEADER = "#Minecraft server properties\n#synthetic fixture\n";
    private static final String MOTD_LINE = "motd=A Minecraft Server\n";
    private static final String RCON_LINE = "rcon.password=synthetic-secret\n";
    private static final String TRAILER = "max-players=20\n";

    @BeforeEach
    void setUp() throws Exception {
        mockLogger = mock(Logger.class);
        mockConfigManager = mock(ConfigManager.class);
        mockServerProperties = mock(ServerPropertiesManager.class);
        mockRemoteActionLog = mock(RemoteActionLog.class);
        panelConfig = new YamlConfiguration();
        serverPropertiesManager = mockServerProperties;

        // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
        ultiToolsMock = TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfigManager()).thenReturn(mockConfigManager);
            lenient().when(ultiTools.getServerPropertiesManager()).thenAnswer(invocation -> serverPropertiesManager);
            lenient().when(ultiTools.getConfig()).thenReturn(panelConfig);
            lenient().when(ultiTools.getRemoteActionLog()).thenReturn(mockRemoteActionLog);
        });

        mockWebSocket = mock(UltiPanelWebSocketClient.class);
        lenient().when(mockWebSocket.getServerId()).thenReturn("srv-1");
        previousPanelWs = setPanelWs(mockWebSocket);
    }

    @AfterEach
    void tearDown() throws Exception {
        // CloudSession.current 是 JVM 级静态（surefire 未配 forkCount，issue #250），不还原
        // 会漏给同一个 JVM 里后面的测试类。
        setPanelWs(previousPanelWs);
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /**
     * 如今 WebSocket 客户端是 {@link CloudSession} 的实例状态（16-08 Task 2 起，替代已删除的
     * {@code PluginInitiationUtils.panelWS} 静态字段）。本类与 {@code CloudSession} 同包
     * ({@code utils})，直接调用即可，不需要反射。
     */
    private Object setPanelWs(Object value) throws Exception {
        UltiPanelWebSocketClient previous = CloudSession.current().getWebSocketClient();
        CloudSession.current().setWebSocketClient((UltiPanelWebSocketClient) value);
        return previous;
    }

    /** 面板实际下发的那种消息。 */
    private static JsonObject panelMessage(String fileName, String configData, String requestId) {
        JsonObject data = new JsonObject();
        if (fileName != null) {
            data.addProperty("fileName", fileName);
        }
        if (configData != null) {
            data.addProperty("configData", configData);
        }
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return data;
    }

    /** 取出所有以指定级别记录的日志正文，两参与三参重载都算。 */
    private List<String> loggedAt(Level level) {
        List<String> lines = new ArrayList<>();
        ArgumentCaptor<String> twoArg = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, Mockito.atLeast(0)).log(eq(level), twoArg.capture());
        lines.addAll(twoArg.getAllValues());
        ArgumentCaptor<String> threeArg = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, Mockito.atLeast(0))
                .log(eq(level), threeArg.capture(), any(Throwable.class));
        lines.addAll(threeArg.getAllValues());
        return lines;
    }

    private JsonObject capturedResponse() {
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocket).sendMessage(captor.capture());
        return captor.getValue();
    }

    // ========== Inbound update_config capability (case 180e5fe3, D-04) ==========

    /** The four update_config target shapes the handler routes differently. */
    enum UpdateTarget {
        /** fileName server_properties, no content: the handler's get branch. */
        SERVER_PROPERTIES_GET(Capability.SERVER_PROPERTIES),
        /** fileName server_properties with content: the handler's applySetAll branch. */
        SERVER_PROPERTIES_WRITE(Capability.SERVER_PROPERTIES),
        /** A named config file with content. */
        NAMED_FILE(Capability.FILE_WRITE),
        /** No fileName, whole nested config as content. */
        WHOLE_CONFIG(Capability.FILE_WRITE);

        private final Capability governing;

        UpdateTarget(Capability governing) {
            this.governing = governing;
        }

        JsonObject data(String requestId) {
            switch (this) {
                case SERVER_PROPERTIES_GET:
                    return panelMessage("server_properties", null, requestId);
                case SERVER_PROPERTIES_WRITE:
                    return panelMessage("server_properties", SERVER_PROPERTIES_CONTENT, requestId);
                case NAMED_FILE:
                    return panelMessage(PluginInitiationUtilsConfigUpdateTest.NAMED_FILE, NAMED_CONTENT, requestId);
                default:
                    return panelMessage(null, WHOLE_CONTENT, requestId);
            }
        }

        /** Whether the governing switch, and only it, admits this target. */
        boolean allowedBy(boolean fileWrite, boolean serverProperties) {
            return governing == Capability.FILE_WRITE ? fileWrite : serverProperties;
        }

        /** Expected handleServerProperties(get) calls. */
        long expectedGets(boolean allowed) {
            return allowed && this == SERVER_PROPERTIES_GET ? 1L : 0L;
        }

        /** Expected applySetAll calls. */
        long expectedSetAlls(boolean allowed) {
            return allowed && this == SERVER_PROPERTIES_WRITE ? 1L : 0L;
        }

        /** Expected config-writer calls. */
        long expectedConfigWrites(boolean allowed) {
            return allowed && governing == Capability.FILE_WRITE ? 1L : 0L;
        }
    }

    private static RemoteActionLog.Verdict verdictFor(boolean allowed) {
        return allowed ? RemoteActionLog.Verdict.ALLOWED : RemoteActionLog.Verdict.DENIED;
    }

    private static int expectedDenials(boolean allowed) {
        return allowed ? 0 : 1;
    }

    /** The single refusal names {@code expected}, its config key and the request's requestId. */
    private static void assertRefusalNames(Capability expected, String requestId, List<JsonObject> denials) {
        JsonObject refusal = denials.get(0).getAsJsonObject("data");
        assertThat(refusal.get("capability").getAsString()).as("capability: capability_denied names it")
                .isEqualTo(expected.name());
        assertThat(refusal.get("configKey").getAsString()).as("capability: capability_denied config key")
                .isEqualTo(expected.getConfigPath());
        assertThat(refusal.get("requestId").getAsString()).as("refusal echoes requestId").isEqualTo(requestId);
    }

    static Stream<Arguments> switchesByTarget() {
        List<Arguments> rows = new ArrayList<>();
        for (boolean fileWrite : new boolean[] {true, false}) {
            for (boolean serverProperties : new boolean[] {true, false}) {
                for (UpdateTarget target : UpdateTarget.values()) {
                    rows.add(Arguments.of(fileWrite, serverProperties, target));
                }
            }
        }
        return rows.stream();
    }

    private void setSwitches(boolean fileWrite, boolean serverProperties) {
        panelConfig.set(Capability.FILE_WRITE.getConfigPath(), fileWrite);
        panelConfig.set(Capability.SERVER_PROPERTIES.getConfigPath(), serverProperties);
    }

    private static JsonObject updateConfigMessage(JsonObject data) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "update_config");
        if (data != null) {
            message.add("data", data);
        }
        return message;
    }

    /** Counts the invocations of {@code methodName} on {@code mock}, any overload. */
    private static long invocationsOf(Object mock, String methodName) {
        return Mockito.mockingDetails(mock).getInvocations().stream()
                .map(Invocation::getMethod)
                .filter(method -> method.getName().equals(methodName))
                .count();
    }

    /** Counts handleServerProperties calls whose data asks for action get. */
    private long serverPropertiesGets() {
        return Mockito.mockingDetails(mockServerProperties).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("handleServerProperties"))
                .map(invocation -> (JsonObject) invocation.getArgument(0))
                .filter(data -> data != null && data.has("action") && "get".equals(data.get("action").getAsString()))
                .count();
    }

    private List<RemoteActionLog.Entry> recordedVerdicts() {
        ArgumentCaptor<RemoteActionLog.Entry> captor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(mockRemoteActionLog, Mockito.atLeast(0)).record(captor.capture());
        return captor.getAllValues();
    }

    private List<JsonObject> sentOfType(String type) {
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocket, Mockito.atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .filter(sent -> sent.has("type") && type.equals(sent.get("type").getAsString()))
                .collect(Collectors.toList());
    }

    /**
     * Case 180e5fe3 (D-04): update_config's capability follows the target the handler actually
     * routes to. A server_properties get or write is governed by the server-properties switch
     * alone; a named-file or whole-config write by the file-write switch alone. Sent as a full
     * inbound message through the real dispatch table and gate, so the refusal, the verdict and
     * the publish decision are the ones panel traffic observes.
     *
     * <p>Assertions are grouped so a failure names its kind: routing and verdict (soft, reported
     * together) first, then capability naming.
     */
    @ParameterizedTest(name = "file-write={0}, server-properties={1}, target={2}")
    @MethodSource("switchesByTarget")
    @DisplayName("update_config capability follows the payload target")
    void updateConfigCapabilityFollowsPayloadTarget(boolean fileWrite, boolean serverProperties,
                                                    UpdateTarget target) {
        setSwitches(fileWrite, serverProperties);
        lenient().when(mockServerProperties.applySetAll(any(JsonObject.class)))
                .thenReturn(new ServerPropertiesManager.SetAllResult(singletonList("motd"), emptyList(),
                        emptyList(), emptyList(), emptyList(), emptyList()));
        String requestId = "req-matrix-" + target.name();
        Capability expected = target.governing;
        boolean allowed = target.allowedBy(fileWrite, serverProperties);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(target.data(requestId)));

        long gets = serverPropertiesGets();
        long setAlls = invocationsOf(mockServerProperties, "applySetAll");
        long configWrites = invocationsOf(mockConfigManager, "loadFromJson");
        long eventBusReads = invocationsOf(ultiToolsMock, "getEventBus");
        List<RemoteActionLog.Entry> verdicts = recordedVerdicts();
        List<JsonObject> denials = sentOfType("capability_denied");

        SoftAssertions routingAndVerdict = new SoftAssertions();
        routingAndVerdict.assertThat(gets).as("routing: server_properties get calls")
                .isEqualTo(target.expectedGets(allowed));
        routingAndVerdict.assertThat(setAlls).as("routing: applySetAll calls")
                .isEqualTo(target.expectedSetAlls(allowed));
        routingAndVerdict.assertThat(configWrites).as("routing: config writer calls")
                .isEqualTo(target.expectedConfigWrites(allowed));
        routingAndVerdict.assertThat(eventBusReads > 0).as("routing: PanelMessageEvent publish consulted")
                .isEqualTo(allowed);
        routingAndVerdict.assertThat(verdicts).as("verdict: gate-owned entries").hasSize(1);
        routingAndVerdict.assertThat(verdicts).extracting(RemoteActionLog.Entry::getVerdict)
                .as("verdict: kind")
                .containsExactly(verdictFor(allowed));
        routingAndVerdict.assertThat(denials).as("verdict: capability_denied messages")
                .hasSize(expectedDenials(allowed));
        routingAndVerdict.assertAll();

        assertThat(verdicts.get(0).getCapability()).as("capability: verdict entry names the governing switch")
                .isEqualTo(expected.name());
        if (!allowed) {
            assertRefusalNames(expected, requestId, denials);
        }
    }

    // ========== fileName-shape consistency, safe keys and the echo (D-04, T-20.2-10/12) ==========

    /** No manager, config writer or publish was reached. */
    private void assertNothingRouted() {
        assertThat(invocationsOf(mockServerProperties, "handleServerProperties")).as("server_properties get").isZero();
        assertThat(invocationsOf(mockServerProperties, "applySetAll")).as("applySetAll").isZero();
        assertThat(invocationsOf(mockConfigManager, "loadFromJson")).as("config writer").isZero();
        assertThat(invocationsOf(ultiToolsMock, "getEventBus")).as("PanelMessageEvent publish").isZero();
    }

    /** Exactly one capability_denied and one DENIED verdict, both naming {@code capability}. */
    private void assertRefusedUnder(Capability capability) {
        List<RemoteActionLog.Entry> verdicts = recordedVerdicts();
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(verdicts.get(0).getCapability()).isEqualTo(capability.name());
        List<JsonObject> denials = sentOfType("capability_denied");
        assertThat(denials).hasSize(1);
        assertThat(denials.get(0).getAsJsonObject("data").get("capability").getAsString())
                .isEqualTo(capability.name());
    }

    /** A JsonObject carrying {@code fileName} as an arbitrary element, plus optional content and a requestId. */
    private static JsonObject dataWithFileName(JsonElement fileName, String configData) {
        JsonObject data = new JsonObject();
        data.add("fileName", fileName);
        if (configData != null) {
            data.addProperty("configData", configData);
        }
        data.addProperty("requestId", "req-shape");
        return data;
    }

    private static JsonArray arrayOf(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** fileName shapes readString cannot read: an object, and an array whose size is not one. */
    static Stream<Arguments> unreadableFileNames() {
        JsonObject object = new JsonObject();
        object.addProperty("name", "server_properties");
        List<Arguments> rows = new ArrayList<>();
        for (String content : new String[] {null, SERVER_PROPERTIES_CONTENT}) {
            rows.add(Arguments.of("object", object, content));
            rows.add(Arguments.of("two-element array", arrayOf("server_properties", "server_properties"), content));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "content={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("fileName [\"server_properties\"] is classified and routed as the server-properties target")
    void singleElementArrayFileNameIsTheServerPropertiesTarget(boolean withContent) {
        setSwitches(false, true);
        lenient().when(mockServerProperties.applySetAll(any(JsonObject.class)))
                .thenReturn(new ServerPropertiesManager.SetAllResult(singletonList("motd"), emptyList(),
                        emptyList(), emptyList(), emptyList(), emptyList()));

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(
                dataWithFileName(arrayOf("server_properties"), withContent ? SERVER_PROPERTIES_CONTENT : null)));

        // readString unwraps a single-element array, so the handler routes it to the manager;
        // the gate must therefore have classified it SERVER_PROPERTIES (file-write is off).
        assertThat(serverPropertiesGets()).isEqualTo(withContent ? 0L : 1L);
        assertThat(invocationsOf(mockServerProperties, "applySetAll")).isEqualTo(withContent ? 1L : 0L);
        assertThat(invocationsOf(mockConfigManager, "loadFromJson")).isZero();
        assertThat(sentOfType("capability_denied")).isEmpty();
        List<RemoteActionLog.Entry> verdicts = recordedVerdicts();
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(verdicts.get(0).getCapability()).isEqualTo(Capability.SERVER_PROPERTIES.name());
    }

    @ParameterizedTest(name = "{0}, content={2}")
    @MethodSource("unreadableFileNames")
    @DisplayName("An unreadable fileName is classified FILE_WRITE and refused with file-write off")
    void unreadableFileNameIsRefusedUnderFileWrite(String shape, JsonElement fileName,
                                                   String content) {
        setSwitches(false, true);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(dataWithFileName(fileName, content)));

        assertRefusedUnder(Capability.FILE_WRITE);
        assertNothingRouted();
    }

    /**
     * With file-write on the handler runs and its own readString raises on the same value; the
     * existing exception path (caught and logged by handleInboundMessage, no gate verdict, no
     * publish) is unchanged by this plan. With server-properties off a SERVER_PROPERTIES
     * classification would have been refused, so the absence of any refusal pins FILE_WRITE.
     */
    @ParameterizedTest(name = "{0}, content={2}, server-properties off")
    @MethodSource("unreadableFileNames")
    @DisplayName("An unreadable fileName never reaches ServerPropertiesManager with file-write on")
    void unreadableFileNameNeverReachesServerProperties(String shape, JsonElement fileName,
                                                        String content) {
        setSwitches(true, false);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(dataWithFileName(fileName, content)));

        assertThat(sentOfType("capability_denied")).as("classified FILE_WRITE, not SERVER_PROPERTIES").isEmpty();
        assertThat(recordedVerdicts()).as("handler exception path records no gate verdict").isEmpty();
        assertNothingRouted();
    }

    @ParameterizedTest(name = "{0}, content={2}, server-properties on")
    @MethodSource("unreadableFileNames")
    @DisplayName("An unreadable fileName never reaches ServerPropertiesManager with both switches on")
    void unreadableFileNameNeverReachesServerPropertiesWithBothOn(String shape,
                                                                  JsonElement fileName,
                                                                  String content) {
        setSwitches(true, true);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(dataWithFileName(fileName, content)));

        assertThat(sentOfType("capability_denied")).isEmpty();
        assertThat(recordedVerdicts()).isEmpty();
        assertNothingRouted();
    }

    static Stream<Arguments> absentData() {
        return Stream.of(
                Arguments.of("absent", null),
                Arguments.of("JSON null", JsonNull.INSTANCE),
                Arguments.of("string primitive", new JsonPrimitive("server_properties")));
    }

    @ParameterizedTest(name = "data {0}")
    @MethodSource("absentData")
    @DisplayName("update_config without a data object is classified FILE_WRITE")
    void missingDataIsClassifiedFileWrite(String shape, JsonElement data) {
        setSwitches(false, true);
        JsonObject message = new JsonObject();
        message.addProperty("type", "update_config");
        if (data != null) {
            message.add("data", data);
        }

        PluginInitiationUtils.handleInboundMessage(message);

        assertRefusedUnder(Capability.FILE_WRITE);
        assertNothingRouted();
    }

    private Path realServerProperties(Path root) throws IOException {
        Path file = root.resolve("server.properties");
        Files.write(file, (PROPERTIES_HEADER + MOTD_LINE + RCON_LINE + TRAILER)
                .getBytes(StandardCharsets.ISO_8859_1));
        serverPropertiesManager = new ServerPropertiesManager(root.toFile());
        return file;
    }

    @Test
    @DisplayName("A safe-key write through the gate changes only that key's line")
    void safeKeyWriteChangesOnlyItsLine(@TempDir Path root)
            throws IOException {
        setSwitches(false, true);
        Path file = realServerProperties(root);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(
                panelMessage("server_properties", SERVER_PROPERTIES_CONTENT, "req-safe")));

        byte[] expected = (PROPERTIES_HEADER + "motd=synthetic\n" + RCON_LINE + TRAILER)
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThat(Files.readAllBytes(file)).isEqualTo(expected);
        List<JsonObject> responses = sentOfType("config_update_response");
        assertThat(responses).hasSize(1);
        JsonObject payload = responses.get(0).getAsJsonObject("data");
        assertThat(payload.get("requestId").getAsString()).isEqualTo("req-safe");
        assertThat(payload.get("status").getAsString()).isEqualTo("success");
    }

    @Test
    @DisplayName("A non-whitelisted key leaves server.properties byte-identical and answers an error naming it")
    void nonWhitelistedKeyLeavesFileByteIdentical(@TempDir Path root)
            throws IOException {
        setSwitches(false, true);
        Path file = realServerProperties(root);
        byte[] before = Files.readAllBytes(file);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(
                panelMessage("server_properties", "{\"rcon.password\":\"synthetic\"}", "req-unsafe")));

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        List<JsonObject> responses = sentOfType("config_update_response");
        assertThat(responses).hasSize(1);
        JsonObject payload = responses.get(0).getAsJsonObject("data");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("error").getAsString()).contains("rcon.password");
    }

    @ParameterizedTest(name = "file-write={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("A content-less named-file update_config stays under FILE_WRITE as an echo")
    void contentLessNamedFileEchoStaysUnderFileWrite(boolean fileWrite) {
        setSwitches(fileWrite, true);

        PluginInitiationUtils.handleInboundMessage(updateConfigMessage(panelMessage(NAMED_FILE, null, "req-echo")));

        if (!fileWrite) {
            assertRefusedUnder(Capability.FILE_WRITE);
            assertNothingRouted();
            return;
        }
        List<RemoteActionLog.Entry> verdicts = recordedVerdicts();
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(verdicts.get(0).getCapability()).isEqualTo(Capability.FILE_WRITE.name());
        assertThat(sentOfType("capability_denied")).isEmpty();
        assertThat(invocationsOf(mockServerProperties, "handleServerProperties")).isZero();
        assertThat(invocationsOf(mockConfigManager, "loadFromJson")).isZero();
        assertThat(sentOfType("config_update_response")).isEmpty();
        assertThat(loggedAt(Level.FINE)).anySatisfy(line -> assertThat(line).contains("update_config"));
    }

    @Nested
    @DisplayName("载荷字段")
    class PayloadFields {

        @Test
        @DisplayName("configData 被读取，并按 fileName 写到对应的配置文件")
        void configDataIsReadAndRoutedByFileName() throws Exception {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", "req-1"));

            // 这一句就是 #236 的全部：面板发的这条消息，过去一个写配置的调用都不会发生。
            verify(mockConfigManager).loadFromJson("config/lang.yml", "{\"language\":\"zh\"}");
        }

        @Test
        @DisplayName("没有 fileName 时按全量嵌套结构写，走单参入口")
        void missingFileNameFallsBackToTheWholeMap() throws Exception {
            String wholeMap = "{\"UltiTools\":{\"config/lang.yml\":{\"language\":\"zh\"}}}";
            PluginInitiationUtils.handleConfigUpdate(panelMessage(null, wholeMap, "req-2"));

            verify(mockConfigManager).loadFromJson(wholeMap);
            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
        }

        @Test
        @DisplayName("旧字段 data.config 仍可读，但记一条废弃 WARNING")
        void legacyConfigFieldIsStillReadAndWarnedAbout() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("config", "{\"UltiTools\":{}}");
            data.addProperty("requestId", "req-3");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager).loadFromJson("{\"UltiTools\":{}}");
            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("data.config"));
        }

        @Test
        @DisplayName("configData 与 config 同时存在时以 configData 为准，且不记废弃日志")
        void configDataWinsOverLegacyField() throws Exception {
            JsonObject data = panelMessage("config/lang.yml", "{\"new\":true}", "req-4");
            data.addProperty("config", "{\"old\":true}");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager).loadFromJson("config/lang.yml", "{\"new\":true}");
            assertThat(loggedAt(Level.WARNING))
                    .noneSatisfy(line -> assertThat(line).contains("data.config"));
        }
    }

    @Nested
    @DisplayName("requestId")
    class RequestIdHandling {

        @Test
        @DisplayName("缺 requestId 的请求仍然被应用，不再静默丢弃")
        void missingRequestIdNoLongerDiscardsTheUpdate() throws Exception {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", null));

            verify(mockConfigManager).loadFromJson("config/lang.yml", "{\"language\":\"zh\"}");
        }

        @Test
        @DisplayName("缺 requestId 时的日志级别至少是 WARNING")
        void missingRequestIdIsLoggedAtWarning() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", null));

            // 原来这里是 FINE —— 默认日志配置不打印，所以「丢弃」这件事在服务器上不可见。
            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("requestId"));
        }

        @Test
        @DisplayName("缺 requestId 时不回响应，因为无从关联")
        void missingRequestIdSendsNoResponse() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", null));

            verify(mockWebSocket, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("成功时回 config_update_response，载荷嵌在 data 里")
        void successSendsANestedResponse() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", "req-5"));

            JsonObject response = capturedResponse();
            assertThat(response.keySet()).containsExactlyInAnyOrder("type", "data", "serverId");
            assertThat(response.get("type").getAsString()).isEqualTo("config_update_response");
            assertThat(response.get("serverId").getAsString()).isEqualTo("srv-1");
            // 嵌套而非扁平：其余所有 插件→Worker 的消息都把载荷放在 data 里。
            JsonObject payload = response.getAsJsonObject("data");
            assertThat(payload.get("requestId").getAsString()).isEqualTo("req-5");
            assertThat(payload.keySet()).containsExactlyInAnyOrder("requestId", "status");
            assertThat(payload.get("status").getAsString()).isEqualTo("success");
        }

        @Test
        @DisplayName("应用失败时回 error 并带上原因")
        void failureSendsAnErrorResponse() throws Exception {
            doThrow(new IOException("No registered config matches path: nope.yml"))
                    .when(mockConfigManager).loadFromJson(anyString(), anyString());

            PluginInitiationUtils.handleConfigUpdate(panelMessage("nope.yml", "{}", "req-6"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.keySet()).containsExactlyInAnyOrder("requestId", "status", "error");
            assertThat(payload.get("error").getAsString()).contains("nope.yml");
        }

        @Test
        @DisplayName("configData 不是合法 JSON 时也回 error，而不是把异常抛给上层")
        void malformedConfigDataStillAnswers() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "not json at all", "req-7"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
        }
    }

    @Nested
    @DisplayName("回声与回执")
    class EchoesAndAcknowledgements {

        @Test
        @DisplayName("没有配置内容的消息不写任何配置，也不回响应")
        void messageWithoutContentIsTreatedAsAnEcho() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("message", "Configuration update command sent");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager, never()).loadFromJson(anyString());
            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
            verify(mockWebSocket, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("带 requestId 但不带内容的消息同样按回声处理")
        void requestIdAloneIsNotARequest() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("requestId", "req-8");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager, never()).loadFromJson(anyString());
            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
        }

        @Test
        @DisplayName("null data 不抛异常")
        void nullDataIsSafe() throws Exception {
            PluginInitiationUtils.handleConfigUpdate(null);

            verify(mockConfigManager, never()).loadFromJson(anyString());
        }
    }

    @Nested
    @DisplayName("server_properties")
    class ServerPropertiesRouting {

        @Test
        @DisplayName("带内容时转成 set_all 交给专用管理器，不走配置文件那条路")
        void contentIsForwardedAsSetAll() throws Exception {
            stubSetAllResult(singletonList("max-players"), emptyList(), emptyList());

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"max-players\":\"30\"}", "req-9"));

            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockServerProperties).applySetAll(captor.capture());
            assertThat(captor.getValue().get("max-players").getAsString()).isEqualTo("30");

            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
        }

        /**
         * issue #281：这条路径此前必报成功。{@code applyConfigUpdate} 调的是 {@code void} 的
         * {@code handleServerProperties}，真相在调用链上根本没有返回路径，于是面板拿到的
         * {@code status} 表达的是「消息处理完了」而不是「配置生效了」——而这两件事在
         * 白名单挡下某个键时就分岔了。
         */
        @Test
        @DisplayName("白名单挡下键时回 error，并点名是哪个键")
        void rejectedKeysMakeTheResponseAnError() throws Exception {
            stubSetAllResult(emptyList(), singletonList("rcon.password"), emptyList());

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"rcon.password\":\"hacked\"}", "req-10"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.get("error").getAsString()).contains("rcon.password");
        }

        @Test
        @DisplayName("全部生效时才回 success")
        void fullyAppliedBatchReportsSuccess() throws Exception {
            stubSetAllResult(singletonList("motd"), emptyList(), emptyList());

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"motd\":\"hi\"}", "req-11"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("success");
        }

        @Test
        @DisplayName("写入失败的键同样让响应变成 error")
        void writeFailuresMakeTheResponseAnError() throws Exception {
            stubSetAllResult(emptyList(), emptyList(), singletonList("motd"));

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"motd\":\"hi\"}", "req-12"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.get("error").getAsString()).contains("motd");
        }

        private void stubSetAllResult(List<String> updated, List<String> rejected, List<String> failed) {
            lenient().when(mockServerProperties.applySetAll(any(JsonObject.class)))
                    .thenReturn(new ServerPropertiesManager.SetAllResult(
                            updated, rejected, failed, emptyList(), emptyList(), emptyList()));
        }

        /**
         * 值不是 JSON 原始值时（对象、数组）同样让响应变成 error。这条与上面几条不同的地方在于
         * 它在**取值之前**就分岔了：`getAsString()` 对 JsonObject 抛
         * {@code UnsupportedOperationException}，对空数组和多元素数组抛
         * {@code IllegalStateException}，而单元素数组根本不抛、静默拆包。
         */
        @Test
        @DisplayName("值不是原始值的键让响应变成 error")
        void malformedValuesMakeTheResponseAnError() throws Exception {
            lenient().when(mockServerProperties.applySetAll(any(JsonObject.class)))
                    .thenReturn(new ServerPropertiesManager.SetAllResult(
                            emptyList(), emptyList(), emptyList(), emptyList(), singletonList("motd"), emptyList()));

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"motd\":{}}", "req-13"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.get("error").getAsString()).contains("motd");
        }

        @Test
        @DisplayName("不带内容时是一条 get 请求，不是回声")
        void withoutContentItIsAGetRequest() {
            PluginInitiationUtils.handleConfigUpdate(panelMessage("server_properties", null, null));

            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockServerProperties).handleServerProperties(captor.capture());
            assertThat(captor.getValue().get("action").getAsString()).isEqualTo("get");
        }
    }

    /**
     * {@link PluginInitiationUtils#handleConfigUpload(JsonObject)} -- issue #435/#359, D-13.
     *
     * <p>{@code upload_config} carries traffic in <b>both</b> directions on the very same message
     * type: {@code uploadConfig(client)} sends the server's own aggregated config up on every
     * reconnect ({@code PluginInitiationUtils.java:262}), and the Worker's own response handler
     * echoes that request's {@code type} straight back as its acknowledgement
     * ({@code websocket-server.ts:454}, {@code response.type = message.type}; the {@code
     * upload_config}/{@code update_config} case ends {@code return response;} at
     * {@code websocket-server.ts:1041}) -- so the acknowledgement lands right back in this same
     * inbound handler, distinguishable only by its {@code message} field (issue #359's own quoted
     * evidence: three candidate Worker write routes probed against production all returned 404,
     * and the production frontend bundle has zero occurrences of {@code upload_config}/{@code
     * uploadConfig}). The old {@code data.has("requestId")} gate never had a caller -- nothing
     * anywhere in the system ever populates that field on an {@code upload_config} payload -- so it
     * is deleted rather than fixed; the {@code message}-field check that already recognised the
     * acknowledgement is kept and promoted to run first, since it is the live path.
     */
    @Nested
    @DisplayName("handleConfigUpload -- upload_config 的写入分支 (issue #359/#435, D-13)")
    class ConfigUploadRouting {

        /**
         * The real write-request shape -- identical to what {@code uploadConfig(client)} itself
         * sends (configType/configName/configContent/format/backup) -- but deliberately without a
         * {@code message} field, which is the acknowledgement's own signature (see
         * {@link #workerAcknowledgementIsRecognizedAndIgnored()}).
         */
        private JsonObject uploadPayload(String configType, JsonObject configContent) {
            JsonObject data = new JsonObject();
            data.addProperty("configType", configType);
            data.addProperty("configName", "UltiTools.yml");
            if (configContent != null) {
                data.add("configContent", configContent);
            }
            data.addProperty("format", "yaml");
            data.addProperty("backup", true);
            return data;
        }

        /**
         * Invokes the real dispatch-table entry rather than the {@code private} method directly --
         * {@link PluginInitiationUtils#inboundDispatchTable()} is the same package-private seam
         * {@code PluginInitiationUtilsDispatchTableTest} already asserts the routing through, so
         * this proves the behaviour panel traffic would actually observe, not just the method body.
         */
        private void invoke(JsonObject data) {
            BiConsumer<JsonObject, JsonObject> handler =
                    PluginInitiationUtils.inboundDispatchTable().get("upload_config").getHandler();
            handler.accept(new JsonObject(), data);
        }

        private JsonObject capturedErrorResponse() {
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockWebSocket).sendMessage(captor.capture());
            JsonObject sent = captor.getValue();
            assertThat(sent.get("type").getAsString()).isEqualTo("error");
            return sent;
        }

        @Test
        @DisplayName("支持的 plugin_config 类型照常生效")
        void supportedTypeAppliesAsToday() throws Exception {
            JsonObject content = new JsonObject();
            content.addProperty("language", "zh");

            invoke(uploadPayload("plugin_config", content));

            verify(mockConfigManager).loadFromJson(new com.google.gson.Gson().toJson(content));
            JsonObject response = capturedResponse();
            assertThat(response.get("type").getAsString()).isEqualTo("upload_config_response");
            assertThat(response.get("status").getAsString()).isEqualTo("success");
        }

        @Test
        @DisplayName("server_properties 类型被拒绝，并点名该走 server_properties 消息")
        void serverPropertiesTypeIsRejectedNamingTheRightMessage() throws Exception {
            invoke(uploadPayload("server_properties", new JsonObject()));

            verify(mockConfigManager, never()).loadFromJson(anyString());
            JsonObject error = capturedErrorResponse();
            assertThat(error.getAsJsonObject("data").get("message").getAsString())
                    .contains("server_properties");
        }

        @Test
        @DisplayName("permissions 类型被拒绝，说明该类型不受支持")
        void permissionsTypeIsRejectedAsUnsupported() throws Exception {
            invoke(uploadPayload("permissions", new JsonObject()));

            verify(mockConfigManager, never()).loadFromJson(anyString());
            JsonObject error = capturedErrorResponse();
            assertThat(error.getAsJsonObject("data").get("message").getAsString())
                    .contains("permissions");
        }

        @Test
        @DisplayName("未知类型保持既有的失败关闭行为")
        void unknownTypeKeepsExistingFailClosedBehaviour() throws Exception {
            invoke(uploadPayload("definitely_not_a_real_type", new JsonObject()));

            verify(mockConfigManager, never()).loadFromJson(anyString());
            JsonObject error = capturedErrorResponse();
            assertThat(error.getAsJsonObject("data").get("message").getAsString())
                    .contains("Unsupported config type: definitely_not_a_real_type");
        }

        @Test
        @DisplayName("没有任何配置内容的上传被拒绝，而不是回 success")
        void noConfigurationContentAtAllIsRejectedNotAnsweredSuccess() throws Exception {
            invoke(uploadPayload("plugin_config", null));

            verify(mockConfigManager, never()).loadFromJson(anyString());
            JsonObject error = capturedErrorResponse();
            assertThat(error.getAsJsonObject("data").get("message").getAsString())
                    .contains("Configuration content is required");
        }

        @Test
        @DisplayName("成功响应里不再出现 requestId —— 没有任何代码路径再读它")
        void successResponseNeverIncludesARequestId() {
            JsonObject content = new JsonObject();
            content.addProperty("language", "zh");
            JsonObject payload = uploadPayload("plugin_config", content);
            payload.addProperty("requestId", "should-be-ignored");

            invoke(payload);

            JsonObject response = capturedResponse();
            assertThat(response.has("requestId")).isFalse();
        }

        @Test
        @DisplayName("Worker 回显的确认消息（只有 message 字段）被识别并忽略，不当成写请求 (issue #359)")
        void workerAcknowledgementIsRecognizedAndIgnored() throws Exception {
            JsonObject ack = new JsonObject();
            ack.addProperty("message", "Configuration upload_config processed successfully");
            ack.addProperty("serverId", "srv-1");
            ack.addProperty("configType", "plugin_config");

            invoke(ack);

            verify(mockConfigManager, never()).loadFromJson(anyString());
            verify(mockWebSocket, never()).sendMessage(any(JsonObject.class));
        }
    }
}
