package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.AutoReplyConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Service for matching chat messages against auto-reply rules.
 * <p>
 * Supports three match modes: contains, exact, and regex.
 * Regex patterns are compiled and cached for performance.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class AutoReplyService {

    @Autowired
    private AutoReplyConfig config;

    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /**
     * Find the first rule that matches the given message.
     *
     * @param message the chat message to match
     * @return the matching rule entry (name -> rule map), or null if no match
     */
    public Map.Entry<String, Map<String, Object>> findMatch(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        Map<String, Map<String, Object>> rules = config.getRules();
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, Map<String, Object>> entry : rules.entrySet()) {
            Map<String, Object> rule = entry.getValue();
            if (rule == null) {
                continue;
            }

            Object keywordObj = rule.get("keyword");
            if (keywordObj == null) {
                continue;
            }
            String keyword = keywordObj.toString();

            String mode = getMode(rule);
            boolean caseSensitive = isCaseSensitive(rule);

            if (matches(message, keyword, mode, caseSensitive)) {
                return entry;
            }
        }

        return null;
    }

    /**
     * Get the response from a rule. Can be a String or List of Strings.
     *
     * @param rule the rule map
     * @return the response object, or null
     */
    public Object getResponse(Map<String, Object> rule) {
        if (rule == null) {
            return null;
        }
        return rule.get("response");
    }

    /**
     * Get the commands list from a rule.
     *
     * @param rule the rule map
     * @return list of commands, or empty list if none
     */
    @SuppressWarnings("unchecked")
    public List<String> getCommands(Map<String, Object> rule) {
        if (rule == null) {
            return Collections.emptyList();
        }
        Object commands = rule.get("commands");
        if (commands instanceof List) {
            return (List<String>) commands;
        }
        return Collections.emptyList();
    }

    /**
     * Add a simple contains-mode rule to the config.
     *
     * @param name     the rule name (key)
     * @param keyword  the keyword to match
     * @param response the response text
     */
    public void addRule(String name, String keyword, String response) {
        Map<String, Map<String, Object>> rules = config.getRules();
        if (rules == null) {
            rules = new HashMap<>();
            config.setRules(rules);
        }

        Map<String, Object> rule = new HashMap<>();
        rule.put("keyword", keyword);
        rule.put("response", response);
        rule.put("mode", "contains");
        rule.put("case-sensitive", false);
        rules.put(name, rule);
    }

    /**
     * Remove a rule by name.
     *
     * @param name the rule name to remove
     */
    public void removeRule(String name) {
        Map<String, Map<String, Object>> rules = config.getRules();
        if (rules != null) {
            rules.remove(name);
            // Also remove cached pattern if any
            patternCache.remove(name);
        }
    }

    /**
     * Get all rules.
     *
     * @return the rules map, or empty map if null
     */
    public Map<String, Map<String, Object>> getRules() {
        Map<String, Map<String, Object>> rules = config.getRules();
        if (rules == null) {
            return Collections.emptyMap();
        }
        return rules;
    }

    private boolean matches(String message, String keyword, String mode, boolean caseSensitive) {
        switch (mode) {
            case "exact":
                return caseSensitive ? message.equals(keyword) : message.equalsIgnoreCase(keyword);
            case "regex":
                return matchesRegex(message, keyword, caseSensitive);
            case "contains":
            default:
                if (caseSensitive) {
                    return message.contains(keyword);
                }
                return message.toLowerCase().contains(keyword.toLowerCase());
        }
    }

    private boolean matchesRegex(String message, String keyword, boolean caseSensitive) {
        String cacheKey = (caseSensitive ? "s:" : "i:") + keyword;
        Pattern pattern = patternCache.get(cacheKey);
        if (pattern == null) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(keyword, flags);
                patternCache.put(cacheKey, pattern);
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        return pattern.matcher(message).find();
    }

    private String getMode(Map<String, Object> rule) {
        Object mode = rule.get("mode");
        if (mode == null) {
            return "contains";
        }
        return mode.toString().toLowerCase();
    }

    private boolean isCaseSensitive(Map<String, Object> rule) {
        Object cs = rule.get("case-sensitive");
        if (cs instanceof Boolean) {
            return (Boolean) cs;
        }
        if (cs != null) {
            return Boolean.parseBoolean(cs.toString());
        }
        return false;
    }
}
