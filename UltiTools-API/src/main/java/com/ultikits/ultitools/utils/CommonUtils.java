package com.ultikits.ultitools.utils;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import org.springframework.context.annotation.ComponentScan;

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

    public static String[] getPluginPackages(UltiToolsPlugin plugin) {
        Class<?> pluginClass = plugin.getClass();
        String[] packages;

        if (!pluginClass.isAnnotationPresent(ComponentScan.class)) {
            if (pluginClass.isAnnotationPresent(EnableAutoRegister.class)) {
                EnableAutoRegister enableAutoRegister = pluginClass.getAnnotation(EnableAutoRegister.class);
                packages = new String[]{enableAutoRegister.scanPackage()};
            } else {
                packages = new String[]{pluginClass.getPackage().getName()};
            }
        } else {
            ComponentScan componentScan = pluginClass.getAnnotation(ComponentScan.class);
            packages = (componentScan.value().length != 0) ? componentScan.value() :
                    (componentScan.basePackages().length != 0) ? componentScan.basePackages() :
                            new String[]{pluginClass.getPackage().getName()};
        }

        return packages;
    }

}
