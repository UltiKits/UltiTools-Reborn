package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.EmojiConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;

import java.util.Map;

/**
 * Replaces shortcode placeholders with Unicode emoji characters in chat messages.
 * 将聊天消息中的短代码替换为 Unicode 表情符号。
 */
@Service
public class EmojiService {

    @Autowired
    private EmojiConfig config;

    /**
     * Replace all emoji shortcodes in the message with their Unicode equivalents.
     * 将消息中的所有表情短代码替换为对应的 Unicode 字符。
     *
     * @param message the chat message
     * @return the message with shortcodes replaced, or the original message if disabled/null
     */
    public String replaceEmojis(String message) {
        if (message == null) {
            return null;
        }
        if (!config.isEnabled()) {
            return message;
        }
        Map<String, String> mappings = config.getMappings();
        if (mappings == null || mappings.isEmpty()) {
            return message;
        }
        String result = message;
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String shortcode = entry.getKey();
            String unicode = entry.getValue();
            if (shortcode != null && unicode != null) {
                result = result.replace(shortcode, unicode);
            }
        }
        return result;
    }
}
