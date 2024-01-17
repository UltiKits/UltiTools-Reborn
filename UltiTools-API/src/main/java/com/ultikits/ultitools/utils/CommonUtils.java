package com.ultikits.ultitools.utils;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import org.bukkit.Bukkit;
import org.springframework.context.annotation.ComponentScan;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;

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

    public static URL getServerJar() {
        ProtectionDomain protectionDomain = Bukkit.class.getProtectionDomain();
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            return null;
        }
        if (codeSource.getLocation().toString().startsWith("union:")){
            String replace = codeSource.getLocation().toString().replace("union:", "file:").split("%")[0];
            try {
                return new URL(replace);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        return codeSource.getLocation();
    }
}
