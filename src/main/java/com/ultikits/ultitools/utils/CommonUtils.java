package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ultikits.ultitools.UltiTools;

/**
 * Common utility class providing general-purpose helper methods.
 * This class contains utility methods used throughout the UltiTools plugin.
 * <br>
 * 通用工具类，提供通用的辅助方法。
 * 此类包含在整个UltiTools插件中使用的实用方法。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class CommonUtils {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * get UltiTools UUID
     * <br>
     * 获取UltiTools UUID
     *
     * @return UUID
     * @throws IOException if an I/O error occurs
     */
    @SuppressWarnings("unchecked")
    public static synchronized String getUltiToolsUUID() throws IOException {
        File dataFile = new File(UltiTools.getInstance().getDataFolder(), "data.json");
        Map<String, Object> json;
        
        if (dataFile.exists()) {
            try (Reader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
                json = GSON.fromJson(reader, Map.class);
                if (json == null) {
                    json = new LinkedHashMap<>();
                }
            }
        } else {
            json = new LinkedHashMap<>();
        }
        
        if (!json.containsKey("uuid")) {
            json.put("uuid", UUID.randomUUID().toString().replace("-", ""));
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            try (Writer writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        }
        
        return json.get("uuid").toString();
    }
}
