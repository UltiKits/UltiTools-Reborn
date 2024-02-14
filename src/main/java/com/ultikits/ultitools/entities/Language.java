package com.ultikits.ultitools.entities;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 语言实体类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class Language {
    private final Map<String, String> dictionary;

    /**
     * 使用json文件读取创建语言字典
     *
     * @param file 语言json文件
     */
    public Language(File file) {
        JSON json = JSONUtil.readJSON(file, StandardCharsets.UTF_8);
        dictionary = json.toBean(new TypeReference<Map<String, String>>() {
        });
    }

    /**
     * 使用字典直接创建
     *
     * @param dictionary 语言字典
     */
    public Language(Map<String, String> dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * 使用json创建语言字典
     *
     * @param json json字符串
     */
    public Language(String json) {
        dictionary = JSONUtil.toBean(json, new TypeReference<Map<String, String>>() {
        }, true);
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
