package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class PayloadReader {
    private final String id;
    private final JsonObject payload;

    PayloadReader(String id, JsonObject payload) {
        this.id = id;
        if (payload == null) { throw invalid(); }
        this.payload = payload.deepCopy();
    }

    PayloadReader keys(String[] required, String... optional) {
        Set<String> allowed = new HashSet<>(Arrays.asList(required));
        allowed.addAll(Arrays.asList(optional));
        if (!allowed.containsAll(payload.keySet()) || !payload.keySet().containsAll(Arrays.asList(required))) {
            throw invalid();
        }
        return this;
    }

    JsonElement required(String key) {
        if (!payload.has(key)) { throw invalid(); }
        return payload.get(key).deepCopy();
    }

    JsonElement optional(String key) {
        return payload.has(key) ? payload.get(key).deepCopy() : null;
    }

    String string(String key, int min, int max, Pattern pattern) {
        JsonElement value = required(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) { throw invalid(); }
        String text = value.getAsString();
        int count = text.codePointCount(0, text.length());
        if (count < min || count > max || (pattern != null && !pattern.matcher(text).matches())) { throw invalid(); }
        return text;
    }

    long integer(String key, long min, long max) {
        BigDecimal value = decimal(key);
        try {
            long number = value.longValueExact();
            if (number < min || number > max) { throw invalid(); }
            return number;
        } catch (ArithmeticException ex) {
            throw invalid();
        }
    }

    double number(String key, double min, double max) {
        double value = decimal(key).doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) { throw invalid(); }
        return value;
    }

    private BigDecimal decimal(String key) {
        JsonElement value = required(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { throw invalid(); }
        try {
            return value.getAsBigDecimal();
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    boolean bool(String key) {
        JsonElement value = required(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { throw invalid(); }
        return value.getAsBoolean();
    }

    String enumValue(String key, String... values) {
        String value = string(key, 0, Integer.MAX_VALUE, null);
        if (!Arrays.asList(values).contains(value)) { throw invalid(); }
        return value;
    }

    JsonArray array(String key, int min, int max) {
        JsonElement value = required(key);
        if (!value.isJsonArray() || value.getAsJsonArray().size() < min || value.getAsJsonArray().size() > max) {
            throw invalid();
        }
        return value.getAsJsonArray();
    }

    JsonObject object(String key) {
        JsonElement value = required(key);
        if (!value.isJsonObject()) { throw invalid(); }
        return value.getAsJsonObject();
    }

    private InvalidFrameException invalid() {
        return new InvalidFrameException("invalid_message", id, "Payload does not match the message schema.");
    }
}
