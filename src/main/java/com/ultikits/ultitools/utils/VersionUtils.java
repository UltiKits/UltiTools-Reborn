package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;

/**
 * Utility class for version checking and comparison operations.
 * Provides methods to check for plugin updates and compare semantic versions.
 *
 * @author wisdomme
 * @since 6.0.0
 * @see VersionComparatorUtil
 */
public class VersionUtils {

    /**
     * Get the newest version of UltiTools from the remote API.
     *
     * @return the newest UltiTools version string
     */
    public static String getUltiToolsNewestVersion() {
        try (Response httpResponse = SimpleHttpClient.get(PluginInstallUtils.getBaseUrl() + "/plugin/ultitools/newest")) {
            if (!httpResponse.isOk()) {
                return null;
            }
            String body = httpResponse.body();
            try {
                JsonObject wrapper = JsonParser.parseString(body).getAsJsonObject();
                String code = wrapper.has("code") ? wrapper.get("code").getAsString() : null;
                if (!"200".equals(code)) {
                    return null;
                }
                JsonElement data = wrapper.get("data");
                if (data == null || data.isJsonNull()) {
                    return null;
                }
                if (data.isJsonPrimitive()) {
                    return data.getAsString();
                }
                return data.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
