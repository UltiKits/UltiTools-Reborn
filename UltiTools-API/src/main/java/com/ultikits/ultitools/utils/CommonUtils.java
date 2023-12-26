package com.ultikits.ultitools.utils;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.ultikits.ultitools.UltiTools;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CommonUtils {

    /**
     * get UltiTools UUID
     *
     * @return UUID
     * @throws IOException if an I/O error occurs
     */
    public static String getUltiToolsUUID() throws IOException {
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
