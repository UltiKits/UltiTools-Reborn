package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

import com.ultikits.ultitools.UltiTools;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import lombok.SneakyThrows;

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

    /**
     * get UltiTools UUID
     * <br>
     * 获取UltiTools UUID
     *
     * @return UUID
     */
    @SneakyThrows
    public static String getUltiToolsUUID() {
        File dataFile = new File(UltiTools.getInstance().getDataFolder(), "data.json");
        JSON json = new cn.hutool.json.JSONObject();
        if (dataFile.exists()) {
            json = JSONUtil.readJSON(dataFile, StandardCharsets.UTF_8);
        } else {
            json.putByPath("uuid", IdUtil.simpleUUID());
            json.write(new FileWriter(dataFile));
        }
        return json.getByPath("uuid").toString();
    }
}
