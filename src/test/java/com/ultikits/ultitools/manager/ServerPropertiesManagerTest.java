package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

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

    /** 装一个只会发消息的 socket，并把发出去的那条抓回来。 */
    private UltiPanelWebSocketClient attachSocket() {
        UltiPanelWebSocketClient socket = mock(UltiPanelWebSocketClient.class);
        lenient().when(socket.getServerId()).thenReturn("srv-1");
        manager.setWebSocketClient(socket);
        return socket;
    }

    private static JsonObject captureMessage(UltiPanelWebSocketClient socket) {
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(socket).sendMessage(captor.capture());
        return captor.getValue();
    }

    private static List<String> stringsOf(JsonObject message, String field) {
        List<String> values = new ArrayList<>();
        message.getAsJsonArray(field).forEach(element -> values.add(element.getAsString()));
        return values;
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
     */
    @Nested
    @DisplayName("set_all 的结果必须是真的（#281）")
    class SetAllReportsTheTruth {

        @Test
        @DisplayName("全部键都在白名单外：success 为 false，一个都没写进去")
        void allKeysRejected() {
            UltiPanelWebSocketClient socket = attachSocket();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("rcon.password", "hacked");
            request.put("server-port", "1337");

            manager.handleServerProperties(setAllRequest(request));

            JsonObject message = captureMessage(socket);
            assertThat(message.get("success").getAsBoolean()).isFalse();
            assertThat(message.get("updated").getAsInt()).isZero();
            assertThat(stringsOf(message, "rejected")).containsExactly("rcon.password", "server-port");

            // 负向对照：被拒不只是「没报成功」，磁盘上确实没被改。
            assertThat(manager.getSafeProperties()).doesNotContainKey("rcon.password");
        }

        @Test
        @DisplayName("部分命中：success 为 false，写进去的和被拒的分别列出来")
        void partiallyRejected() {
            UltiPanelWebSocketClient socket = attachSocket();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Half applied");
            request.put("rcon.password", "hacked");

            manager.handleServerProperties(setAllRequest(request));

            JsonObject message = captureMessage(socket);
            assertThat(message.get("success").getAsBoolean()).isFalse();
            assertThat(message.get("updated").getAsInt()).isEqualTo(1);
            assertThat(stringsOf(message, "rejected")).containsExactly("rcon.password");
            // 命中的那个确实写进去了——部分失败不等于整批回滚，响应说的就是这个。
            assertThat(manager.getSafeProperties().get("motd")).isEqualTo("Half applied");
        }

        @Test
        @DisplayName("全部命中：success 为 true，rejected 与 failed 都是空的")
        void allKeysApplied() {
            UltiPanelWebSocketClient socket = attachSocket();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Fully applied");
            request.put("max-players", "64");

            manager.handleServerProperties(setAllRequest(request));

            JsonObject message = captureMessage(socket);
            assertThat(message.get("success").getAsBoolean()).isTrue();
            assertThat(message.get("updated").getAsInt()).isEqualTo(2);
            assertThat(stringsOf(message, "rejected")).isEmpty();
            assertThat(stringsOf(message, "failed")).isEmpty();
            assertThat(manager.getSafeProperties().get("max-players")).isEqualTo("64");
        }

        @Test
        @DisplayName("值是 JSON null 的键记进 skipped，但不让整批失败")
        void explicitNullsAreVisibleButHarmless() {
            UltiPanelWebSocketClient socket = attachSocket();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Applied");
            request.put("pvp", null);

            manager.handleServerProperties(setAllRequest(request));

            JsonObject message = captureMessage(socket);
            // null 读作「这个别动」，所以它不算失败；但它此前既不计数也不上报，
            // 是第四类隐形跳过，现在至少看得见。
            assertThat(message.get("success").getAsBoolean()).isTrue();
            assertThat(stringsOf(message, "skipped")).containsExactly("pvp");
            assertThat(message.get("updated").getAsInt()).isEqualTo(1);
            assertThat(manager.getSafeProperties().get("pvp")).isEqualTo("true");
        }

        @Test
        @DisplayName("白名单里的键写不进去时算 failed，与「被白名单拒了」分开报")
        void writeFailuresAreDistinctFromRejections() {
            // server.properties 不存在 = 写不进去，但键本身是合法的。
            // 这两种情况过去都只是 setProperty 返回 false，调用方分不出来，而处置完全不同。
            assertThat(propsFile.delete()).isTrue();
            UltiPanelWebSocketClient socket = attachSocket();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("motd", "Never lands");
            request.put("rcon.password", "hacked");

            manager.handleServerProperties(setAllRequest(request));

            JsonObject message = captureMessage(socket);
            assertThat(message.get("success").getAsBoolean()).isFalse();
            assertThat(stringsOf(message, "failed")).containsExactly("motd");
            assertThat(stringsOf(message, "rejected")).containsExactly("rcon.password");
        }

        @Test
        @DisplayName("没提供 values 时既不写也不回消息")
        void missingValuesIsNotARequest() {
            UltiPanelWebSocketClient socket = attachSocket();
            JsonObject data = new JsonObject();
            data.addProperty("action", "set_all");

            manager.handleServerProperties(data);

            verify(socket, never()).sendMessage(org.mockito.ArgumentMatchers.any(JsonObject.class));
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
            attachSocket();

            manager.handleServerProperties(setAllRequest(Collections.singletonMap("rcon.password", "hacked")));

            ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
            verify(logger).log(eq(Level.WARNING), line.capture());
            assertThat(line.getValue()).contains("rcon.password");
        }

        @Test
        @DisplayName("全部成功时不记 WARNING")
        void nothingIsLoggedWhenEverythingApplies() {
            Logger logger = mock(Logger.class);
            TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));
            attachSocket();

            manager.handleServerProperties(setAllRequest(Collections.singletonMap("motd", "Fine")));

            verify(logger, never()).log(eq(Level.WARNING), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("没有全局单例时不抛异常——这个类的文件逻辑不该依赖它")
        void missingSingletonDoesNotBreakTheBatch() {
            // 负向对照。生产环境下 UltiTools.getInstance() 一定就绪（管理器在 onEnable 里构造），
            // 但纯文件逻辑的用例不该被迫先装一个全局单例才能跑。
            UltiPanelWebSocketClient socket = attachSocket();

            manager.handleServerProperties(setAllRequest(Collections.singletonMap("rcon.password", "hacked")));

            assertThat(captureMessage(socket).get("success").getAsBoolean()).isFalse();
        }
    }
}
