package com.ultikits.ultitools.entities;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Language {
    private final Map<String, String> dictionary;

    public Language(File file) {
        JSON json = JSONUtil.readJSON(file, StandardCharsets.UTF_8);
        dictionary = json.toBean(new TypeReference<Map<String, String>>() {
        });
    }

    public Language(Map<String, String> dictionary) {
        this.dictionary = dictionary;
    }

    public Language(String json) {
        dictionary = JSONUtil.toBean(json, new TypeReference<Map<String, String>>() {
        }, true);
    }

    public String getLocalizedText(String str) {
        return dictionary.getOrDefault(str, str);
    }
}
