package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Panel callbacks queue the entire operation without a reply or registry access on the socket thread. */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ConfigPanelThreadConfinementTest {
    private ConfigManager manager;
    private UltiPanelWebSocketClient client;
    private UltiPanelWebSocketClient previous;

    @BeforeEach void setup() {
        manager = mock(ConfigManager.class); client = mock(UltiPanelWebSocketClient.class);
        lenient().when(client.getServerId()).thenReturn("thread-test");
        lenient().when(manager.toJson()).thenReturn("{}");
        lenient().when(manager.getComments()).thenReturn("{}");
        TestHelper.mockUltiToolsInstance(core -> {
            lenient().when(core.getConfigManager()).thenReturn(manager);
            lenient().when(core.getLogger()).thenReturn(mock(Logger.class));
        });
        previous = CloudSession.current().getWebSocketClient();
        CloudSession.current().setWebSocketClient(client);
    }
    @AfterEach void cleanup() throws Exception {
        CloudSession.current().setWebSocketClient(previous);
        Field field = UltiTools.class.getDeclaredField("ultiTools"); field.setAccessible(true); field.set(null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "upload-write", "upload-read"})
    void callbackReturnsBeforeQueuedOperationAndRepliesOnlyAfterIt(String operation) throws Exception {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean primary = new AtomicBoolean(false);
        doAnswer(call -> { queued.set(call.getArgument(1)); return null; })
                .when(scheduler).runTask(any(Plugin.class), any(Runnable.class));
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenAnswer(call -> primary.get());
            when(Bukkit.getScheduler()).thenReturn(scheduler);
            invoke(operation);
            assertThat(queued.get()).as("one whole callback queued").isNotNull();
            verifyNoInteractions(manager);
            verify(client, never()).sendMessage(any(JsonObject.class));
            primary.set(true); queued.get().run();
            if (operation.equals("upload-read")) {
                verify(manager).toJson(); verify(manager).getComments();
            } else { verify(manager).loadFromJson("{}"); }
            verify(client).sendMessage(any(JsonObject.class));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"update", "upload-write"})
    void refusedProtectedEditReportsFailureWithoutSuccess(String operation) throws Exception {
        doThrow(new com.ultikits.ultitools.exceptions.ConfigurationException(
                "Protected configuration file values.yml")).when(manager).loadFromJson("{}");
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.isPrimaryThread()).thenReturn(true);
            invoke(operation);
            org.mockito.ArgumentCaptor<JsonObject> response = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            verify(client).sendMessage(response.capture());
            JsonObject reply = response.getValue();
            if (operation.equals("update")) {
                assertThat(reply.get("success").getAsBoolean()).isFalse();
            } else {
                assertThat(reply.get("type").getAsString()).isEqualTo("error");
            }
            assertThat(reply.toString()).contains("values.yml").doesNotContain("\"status\":\"success\"");
        }
    }

    private void invoke(String operation) throws Exception {
        if (operation.equals("update")) {
            JsonObject data = new JsonObject(); data.addProperty("requestId", "thread-request");
            data.addProperty("configData", "{}"); PluginInitiationUtils.handleConfigUpdate(data); return;
        }
        if (operation.equals("upload-read")) {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("uploadConfig", UltiPanelWebSocketClient.class);
            method.setAccessible(true); method.invoke(null, client); return;
        }
        JsonObject data = new JsonObject(); data.addProperty("configType", "plugin_config");
        data.addProperty("configName", "values.yml"); data.addProperty("format", "yaml");
        data.addProperty("backup", true); data.add("configContent", new JsonObject());
        Method method = PluginInitiationUtils.class.getDeclaredMethod("handleConfigUpload", JsonObject.class);
        method.setAccessible(true); method.invoke(null, data);
    }
}
