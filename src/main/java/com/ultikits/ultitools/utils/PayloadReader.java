package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict protocol payload reader shared by framework handlers; internal, not for module use.
 * Validation failures are unchecked and carry invalid_message plus the request id;
 * AgentMessageRouter converts them to correlated errors without exposing payload text.
 * JSON values are copied on input and access. Bounds are inclusive and strings are
 * measured in Unicode code points, as in the vendored JSON Schemas.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class PayloadReader {
    private final String id;
    private final JsonObject payload;

    /**
     * Creates a reader over a defensive payload snapshot.
     * @param id the request envelope ULID for validation errors
     * @param payload the non-null object to validate
     */
    public PayloadReader(String id, JsonObject payload) {
        this.id = id;
        if (payload == null) { throw invalid(); }
        this.payload = payload.deepCopy();
    }

    /**
     * Rejects missing required keys and every key outside the two sets.
     * @param required names that must be present, even when their value is JSON null
     * @param optional additional allowed names
     * @return this reader; validate each value separately
     */
    public PayloadReader keys(String[] required, String... optional) {
        Set<String> allowed = new HashSet<>(Arrays.asList(required));
        allowed.addAll(Arrays.asList(optional));
        if (!allowed.containsAll(payload.keySet()) || !payload.keySet().containsAll(Arrays.asList(required))) {
            throw invalid();
        }
        return this;
    }

    /**
     * Reads a required key without imposing a value type.
     * @param key the field name
     * @return a deep copy, including JSON null when explicitly present
     */
    public JsonElement required(String key) {
        if (!payload.has(key)) { throw invalid(); }
        return payload.get(key).deepCopy();
    }

    /**
     * Reads an optional key without imposing a value type.
     * @param key the field name
     * @return a deep copy, or Java null only when absent
     */
    public JsonElement optional(String key) {
        return payload.has(key) ? payload.get(key).deepCopy() : null;
    }

    /**
     * Reads a required string without coercion from other JSON types.
     * @param key the field name
     * @param min minimum code-point count
     * @param max maximum code-point count
     * @param pattern full-string pattern, or null to skip pattern validation
     * @return the validated string
     */
    public String string(String key, int min, int max, Pattern pattern) {
        JsonElement value = required(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) { throw invalid(); }
        String text = value.getAsString();
        int count = text.codePointCount(0, text.length());
        if (count < min || count > max || (pattern != null && !pattern.matcher(text).matches())) { throw invalid(); }
        return text;
    }

    /**
     * Reads a required integral JSON number, rejecting fractional or overflowing values.
     * @param key the field name
     * @param min minimum allowed value
     * @param max maximum allowed value
     * @return the validated integer
     */
    public long integer(String key, long min, long max) {
        BigDecimal value = decimal(key);
        try {
            long number = value.longValueExact();
            if (number < min || number > max) { throw invalid(); }
            return number;
        } catch (ArithmeticException ex) {
            throw invalid();
        }
    }

    /**
     * Reads a required finite JSON number without string coercion.
     * @param key the field name
     * @param min minimum allowed value
     * @param max maximum allowed value
     * @return the validated number
     */
    public double number(String key, double min, double max) {
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

    /**
     * Reads a required boolean without string or number coercion.
     * @param key the field name
     * @return the validated boolean
     */
    public boolean bool(String key) {
        JsonElement value = required(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { throw invalid(); }
        return value.getAsBoolean();
    }

    /**
     * Reads a required string matching exactly one allowed value.
     * @param key the field name
     * @param values case-sensitive allowed values
     * @return the validated enum value
     */
    public String enumValue(String key, String... values) {
        String value = string(key, 0, Integer.MAX_VALUE, null);
        if (!Arrays.asList(values).contains(value)) { throw invalid(); }
        return value;
    }

    /**
     * Reads a required array; element validation remains the caller's responsibility.
     * @param key the field name
     * @param min minimum item count
     * @param max maximum item count
     * @return a deep copy of the validated array
     */
    public JsonArray array(String key, int min, int max) {
        JsonElement value = required(key);
        if (!value.isJsonArray() || value.getAsJsonArray().size() < min || value.getAsJsonArray().size() > max) {
            throw invalid();
        }
        return value.getAsJsonArray();
    }

    /**
     * Reads a required object; its field validation remains the caller's responsibility.
     * @param key the field name
     * @return a deep copy of the object
     */
    public JsonObject object(String key) {
        JsonElement value = required(key);
        if (!value.isJsonObject()) { throw invalid(); }
        return value.getAsJsonObject();
    }

    private InvalidFrameException invalid() {
        return new InvalidFrameException("invalid_message", id, "Payload does not match the message schema.");
    }
}
