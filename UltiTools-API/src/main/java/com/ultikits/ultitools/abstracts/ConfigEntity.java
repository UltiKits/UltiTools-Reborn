package com.ultikits.ultitools.abstracts;

import cn.hutool.core.annotation.AnnotationUtil;
import com.ultikits.ultitools.annotations.Config;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.rmi.RemoteException;

@Getter
public abstract class ConfigEntity {
    private UltiToolsPlugin ultiToolsPlugin;
    private YamlConfiguration config;
    private String configFilePath;
    private boolean initiated = false;

    public void save() throws IOException {
        if (!isInitiated()){
            throw new RemoteException("Config entity save before initiation!");
        }
        config.save(new File(configFilePath));
    }

    @SneakyThrows
    public final void init(UltiToolsPlugin ultiToolsPlugin){
        this.ultiToolsPlugin = ultiToolsPlugin;
        if (!this.getClass().isAnnotationPresent(Config.class)) {
            throw new RuntimeException("No Config Annotation present in the class \""+ this.getClass().getName() + "\"");
        }
        Config configClass = this.getClass().getAnnotation(Config.class);
        this.configFilePath = configClass.filePath();
        config = YamlConfiguration.loadConfiguration(ultiToolsPlugin.getConfigFile(configFilePath));
        boolean upToDate = true;
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = AnnotationUtil.getAnnotation(field, ConfigEntry.class);
                Object configValue = config.get(annotation.path());
                if (configValue != null) {
                    field.set(this, configValue);
                }else {
                    upToDate = false;
                    config.set(annotation.path(), field.get(this));
                }
            }
        }
        if (!upToDate){
            config.save(ultiToolsPlugin.getConfigFile(configFilePath));
        }
        initiated = true;
    }

}
