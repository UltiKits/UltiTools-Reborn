package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Safe server.properties manager for remote editing via WebSocket.
 * Only exposes a curated whitelist of non-sensitive keys.
 */
public class ServerPropertiesManager {
    private final File serverRoot;
    private UltiPanelWebSocketClient webSocketClient;

    private static final Set<String> SAFE_KEYS = new HashSet<>(Arrays.asList(
        "motd", "max-players", "view-distance", "simulation-distance",
        "spawn-protection", "difficulty", "gamemode", "pvp",
        "allow-nether", "allow-flight", "spawn-animals", "spawn-monsters",
        "spawn-npcs", "enable-command-block"
    ));

    public ServerPropertiesManager(File serverRoot) {
        this.serverRoot = serverRoot;
    }

    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }

    public Map<String, String> getSafeProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        File propsFile = new File(serverRoot, "server.properties");
        if (!propsFile.exists()) return result;

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile)) {
            props.load(fis);
        } catch (IOException e) {
            return result;
        }

        for (String key : SAFE_KEYS) {
            String value = props.getProperty(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    public boolean setProperty(String key, String value) {
        if (!SAFE_KEYS.contains(key)) return false;

        File propsFile = new File(serverRoot, "server.properties");
        if (!propsFile.exists()) return false;

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile)) {
            props.load(fis);
        } catch (IOException e) {
            return false;
        }

        props.setProperty(key, value);

        try (FileOutputStream fos = new FileOutputStream(propsFile)) {
            props.store(fos, null);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Handle WebSocket message for server properties operations.
     *
     * @param data the message data
     */
    public void handleServerProperties(JsonObject data) {
        if (data == null) return;

        String action = data.has("action") && !data.get("action").isJsonNull()
            ? data.get("action").getAsString() : "get";

        if ("get".equals(action)) {
            handleGet();
        } else if ("set".equals(action)) {
            handleSet(data);
        } else if ("set_all".equals(action)) {
            handleSetAll(data);
        }
    }

    private void handleGet() {
        Map<String, String> props = getSafeProperties();
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        JsonObject payload = new JsonObject();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            payload.addProperty(entry.getKey(), entry.getValue());
        }
        response.add("data", payload);
        sendResponse(response);
    }

    private void handleSet(JsonObject data) {
        String key = data.has("key") ? data.get("key").getAsString() : null;
        String value = data.has("value") ? data.get("value").getAsString() : null;
        if (key == null || value == null) return;

        boolean success = setProperty(key, value);
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set");
        response.addProperty("success", success);
        response.addProperty("key", key);
        sendResponse(response);
    }

    private void handleSetAll(JsonObject data) {
        JsonObject values = data.has("values") && data.get("values").isJsonObject()
            ? data.getAsJsonObject("values") : null;
        if (values == null) return;

        int updated = 0;
        for (String key : values.keySet()) {
            if (values.get(key).isJsonNull()) {
                continue;
            }
            if (setProperty(key, values.get(key).getAsString())) {
                updated++;
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set_all");
        response.addProperty("success", true);
        response.addProperty("updated", updated);
        sendResponse(response);
    }

    private void sendResponse(JsonObject response) {
        if (webSocketClient != null) {
            response.addProperty("serverId", webSocketClient.getServerId());
            webSocketClient.sendMessage(response);
        }
    }
}
