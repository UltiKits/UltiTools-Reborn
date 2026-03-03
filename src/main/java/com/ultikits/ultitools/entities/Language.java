package com.ultikits.ultitools.entities;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 语言实体类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class Language {
    private static final Logger LOGGER = Logger.getLogger(Language.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    
    private final Map<String, String> dictionary;

    /**
     * 使用json文件读取创建语言字典
     *
     * @param file 语言json文件
     */
    public Language(File file) {
        Map<String, String> tempDict = Collections.emptyMap();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            tempDict = GSON.fromJson(reader, MAP_TYPE);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load language file: " + file.getPath(), e);
        }
        this.dictionary = tempDict != null ? tempDict : Collections.emptyMap();
    }

    /**
     * 使用字典直接创建
     *
     * @param dictionary 语言字典
     */
    public Language(Map<String, String> dictionary) {
        this.dictionary = dictionary != null ? dictionary : Collections.emptyMap();
    }

    /**
     * 使用json创建语言字典
     *
     * @param json json字符串
     */
    public Language(String json) {
        Map<String, String> tempDict = GSON.fromJson(json, MAP_TYPE);
        this.dictionary = tempDict != null ? tempDict : Collections.emptyMap();
    }

    /**
     * 获取对应的语言翻译
     *
     * @param str 需要翻译的字符串
     * @return 翻译后的字符串，若字典中没有则返回原文
     */
    public String getLocalizedText(String str) {
        return dictionary.getOrDefault(str, str);
    }
}
