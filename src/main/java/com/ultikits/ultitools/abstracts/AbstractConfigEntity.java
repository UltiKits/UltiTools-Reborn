package com.ultikits.ultitools.abstracts;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.ConfigChangeListener;
import com.ultikits.ultitools.utils.ReflectionUtil;

import lombok.Getter;

/**
 * Abstract class representing a configuration entity.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Config binder writes/reads private @ConfigEntry fields -- see 08-GATE05-TRIAGE.md
@Getter
public abstract class AbstractConfigEntity {
    private static final Logger LOGGER = Logger.getLogger(AbstractConfigEntity.class.getName());
    
    private final String configFilePath;
    private final List<ConfigChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private UltiToolsPlugin ultiToolsPlugin;
    private YamlConfiguration config;

    /**
     * Constructor for AbstractConfigEntity.
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml
     */
    public AbstractConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * Saves the configuration to the file.
     *
     * @throws IOException if an I/O error occurs
     */
    @SuppressWarnings("unchecked")
    public void save() throws IOException {
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (!field.isAnnotationPresent(ConfigEntry.class)) {
                continue;
            }
            field.setAccessible(true);
            ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            String path = annotation.path();
            if (path.isEmpty()) {
                path = field.getName();
            }
            Object fieldValue = ReflectionUtil.getFieldValue(this, field);
            if (fieldValue == null) {
                continue;
            }
            Object serialized = ReflectionUtil.newInstance(annotation.parser()).serialize(fieldValue);
            config.set(path, serialized);
        }
        config.save(new File(ultiToolsPlugin.getConfigFolder() + File.separator + configFilePath));
    }

    /**
     * Initializes the configuration entity.
     *
     * @param ultiToolsPlugin the plugin instance
     * @throws IOException if an I/O error occurs
     */
    public final void init(UltiToolsPlugin ultiToolsPlugin) throws IOException {
        this.ultiToolsPlugin = ultiToolsPlugin;
        File file = ultiToolsPlugin.getConfigFile(configFilePath);
        config = new YamlConfiguration();
        // D-08: options().parseComments(true) must be set on THIS instance before load() runs -
        // load() reads the option itself (verified via javap against paper-api), so setting it
        // afterward only affects a later save(), not this read. Under
        // -DPaper.parseYamlCommentsByDefault=false an operator's existing comments would
        // otherwise be dropped right here at parse time, and the missing-key branch below would
        // then write them out of their own file - the exact D-01 violation this lane exists to
        // prevent. Explicit, not inherited from the system-property default.
        config.options().parseComments(true);
        try {
            config.load(file);
        } catch (FileNotFoundException ignored) {
            // Mirrors YamlConfiguration.loadConfiguration(File)'s own behaviour: a missing file
            // is the normal "first run" case, not an error - config stays empty and every field
            // below takes the missing-key branch.
        } catch (InvalidConfigurationException e) {
            LOGGER.log(Level.SEVERE, "Cannot load " + file, e);
        }
        boolean upToDate = true;
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                Object configValue = config.get(path);
                if (configValue != null) {
                    Object parse = ReflectionUtil.newInstance(annotation.parser()).parse(configValue);
                    ReflectionUtil.setFieldValue(this, field, parse);
                } else {
                    upToDate = false;
                    config.set(path, ReflectionUtil.getFieldValue(this, field));
                    // D-07/D-09: the key never existed in the operator's file, so writing its
                    // @ConfigEntry comment alongside the value discloses nothing of theirs - this
                    // is D-01's sole sanctioned exception, widened from "silently add a value" to
                    // "silently add a value and its explanation". Never reached on the
                    // already-has-the-key path above, and this is the only comment write in the
                    // whole class.
                    List<String> commentLines = splitComment(annotation.comment());
                    if (!commentLines.isEmpty()) {
                        config.setComments(path, commentLines);
                    }
                }
            }
        }
        if (!upToDate) {
            config.save(file);
        }

        // Validate fields and reset invalid values to defaults
        validateFields();

        // Notify listeners after initialization
        notifyChangeListeners();
    }

    /**
     * Splits a {@code @ConfigEntry.comment()} value into one {@link List} element per line, in
     * declaration order, ready for {@link org.bukkit.configuration.ConfigurationSection}'s
     * comment-writing API. No blank leading element is added (Claude's Discretion, D-07) - it
     * would produce a diff on every regenerated file for a purely cosmetic gain, contrary to
     * D-01's touch-as-little-as-possible posture.
     *
     * @param comment the raw {@code comment()} attribute value, possibly empty
     * @return one element per line, or an empty list if {@code comment} is blank
     */
    private static List<String> splitComment(String comment) {
        if (comment == null || comment.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(comment.split("\n"));
    }

    /**
     * Updates the properties of the configuration entity.
     * <p>
     * The field traversal and path-derivation must match {@code init()} / {@code save()} /
     * {@code reload()} exactly, or a write can silently no-op: all four methods walk
     * {@code @ConfigEntry} fields, and this method previously diverged from them in two ways --
     * using {@code getDeclaredFields()} instead of {@link ReflectionUtil#getFields(Class)}
     * (missing inherited fields), and not normalizing an empty {@code path} to the field name.
     * The second divergence was the more hidden one: omitting {@code path} on
     * {@code @ConfigEntry} is a supported style, {@code init}/{@code save} read and write it by
     * field name, and {@link #toJsonObject()} also sends it to the panel by field name -- but
     * this method was looking for an empty-string key in the JSON, which never exists, so the
     * field was silently skipped while {@code config.save} still ran and the caller still
     * received success.
     * <p>
     * Since 6.3.0 (SILENT-14, closing CR-01) this method validates the full post-update field
     * state - the same {@link #validateFields()} {@link #init(UltiToolsPlugin)}/{@link
     * #reload()} already use - before either {@code config.set(...)} or {@code config.save(...)}
     * runs. A violating value refuses with {@link ConfigurationException} instead of being
     * written: the operator's file is left byte-identical, and every field this call touched is
     * restored to the value it held before the call, so memory never disagrees with disk (D-01,
     * D-04). Unlike {@link #reload()}, this entity keeps running after a refusal, so its
     * in-memory state must not be left holding a rejected value.
     *
     * @param jsonObject the JSON object containing the new properties
     * @throws IOException            if an I/O error occurs
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if the post-update field state violates a {@code @Range}/
     *                                 {@code @NotEmpty}/{@code @Size}/{@code @Pattern} constraint
     *                                 - the file is not written and touched fields are restored
     */
    public void updateProperties(JsonObject jsonObject) throws IOException {
        Gson gson = new Gson();
        // Phase one: apply to fields only. Remember each touched field's pre-call value first
        // so a refusal in phase two can restore it - nothing reaches `config` or disk here.
        List<Field> touchedFields = new ArrayList<>();
        List<Object> previousValues = new ArrayList<>();
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                if (jsonObject.has(path)) {
                    Object configValue = gson.fromJson(jsonObject.get(path), field.getType());
                    if (configValue != null) {
                        touchedFields.add(field);
                        previousValues.add(ReflectionUtil.getFieldValue(this, field));
                        ReflectionUtil.setFieldValue(this, field, configValue);
                    }
                }
            }
        }

        // Phase two: validate the full post-update state, restoring on refusal. Must run
        // before the first field write below, not merely before the final save call -
        // otherwise a refusal would still leave the in-memory YamlConfiguration holding
        // rejected values for a later, unrelated save() to flush. The original exception is
        // rethrown unchanged - never wrapped, never converted to IOException, never swallowed.
        try {
            validateFields();
        } catch (RuntimeException e) {
            for (int i = 0; i < touchedFields.size(); i++) {
                ReflectionUtil.setFieldValue(this, touchedFields.get(i), previousValues.get(i));
            }
            throw e;
        }

        // Phase three: persist. Only reached once validation has passed. Writes the same
        // Gson-deserialized value the method has always written - not the @ConfigEntry.parser()
        // serialized form save() uses; that asymmetry is pre-existing and out of scope here.
        for (Field field : touchedFields) {
            ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
            String path = annotation.path();
            if (path.isEmpty()) {
                path = field.getName();
            }
            config.set(path, ReflectionUtil.getFieldValue(this, field));
        }
        config.save(ultiToolsPlugin.getConfigFile(configFilePath));
    }

    /**
     * Converts the configuration entity to a JSON object.
     *
     * @return the JSON object representation of the configuration entity
     */
    public JsonObject toJsonObject() {
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        Set<String> keys = config.getKeys(true);
        for (String key : keys) {
            if (!config.isConfigurationSection(key)) {
                Object value = config.get(key);
                jsonObject.add(key, gson.toJsonTree(value));
            }
        }
        return jsonObject;
    }

    /**
     * Gets the comments of the configuration entity.
     *
     * @return a JSON object containing the comments
     */
    public JsonObject getComments() {
        JsonObject jsonObject = new JsonObject();
        // Same two alignments as updateProperties: walk the full field tree, normalize an empty
        // path to the field name. Without normalizing, a field with no path would have its
        // comment filed under the "" key, while toJsonObject() sends values by field name - the
        // panel's two sides would never match up and that comment would never display.
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                jsonObject.addProperty(path, annotation.comment());
            }
        }
        return jsonObject;
    }
    
    // ==================== Configuration Validation ====================

    /**
     * Validates all fields annotated with validation annotations (@Range, @NotEmpty, @Size, @Pattern).
     * A violation refuses this config's module instead of rewriting the value - the operator's
     * file is never modified (D-01). Every violating field is collected and named in a single
     * refusal; the module author must fix the value(s) on disk and restart.
     *
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if any field violates its validation constraint, or if this
     *                                 config class cannot be constructed through either of the
     *                                 two framework-supported idioms (D-03)
     */
    protected void validateFields() {
        List<Field> configFields = new ArrayList<>();
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                configFields.add(field);
            }
        }
        if (configFields.isEmpty()) {
            return;
        }

        // Proves this class still supports one of the two framework idioms (D-02/D-03) - the
        // constructed instance itself is discarded, only its existence matters here.
        ensureConstructable();

        List<String> violations = new ArrayList<>();
        for (Field field : configFields) {
            field.setAccessible(true);
            try {
                String violation = validateSingleField(field);
                if (violation != null) {
                    violations.add(violation);
                }
            } catch (IllegalAccessException e) {
                LOGGER.log(Level.WARNING, "Failed to validate field: " + field.getName(), e);
            }
        }

        if (!violations.isEmpty()) {
            String moduleName = ultiToolsPlugin != null ? ultiToolsPlugin.getPluginName() : this.getClass().getSimpleName();
            throw ConfigurationException.validationFailed(moduleName, configFilePath, violations);
        }
    }

    /**
     * Constructs and discards an instance of this config class through the same two-step
     * fallback {@code ConfigManager.registerAll} uses at registration time - a {@code (String)}
     * constructor first, then an accessible no-arg constructor. Existence, not the constructed
     * value, is what this proves: the framework needs every registered config class to still be
     * buildable through one of its two documented idioms (D-02). Neither resolving is a genuine
     * config-class error (D-03).
     *
     * @throws ConfigurationException if neither constructor resolves
     */
    private void ensureConstructable() {
        try {
            try {
                this.getClass().getDeclaredConstructor(String.class).newInstance(configFilePath);
            } catch (NoSuchMethodException e) {
                // Try no-arg constructor (class may hardcode path via super() call)
                this.getClass().getDeclaredConstructor().newInstance();
            }
        } catch (InstantiationException | InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            throw ConfigurationException.unconstructable(this.getClass().getName(), e);
        }
    }

    /**
     * Describes the single validation constraint {@code field} violates, if any.
     *
     * @param field the field to check, already made accessible
     * @return a violation description naming the field, its actual value (redacted for
     *         {@code @Pattern} on a secret-shaped field name, T-04-04) and the broken constraint,
     *         or {@code null} if the field's value satisfies its annotations
     */
    private String validateSingleField(Field field) throws IllegalAccessException {
        Object value = field.get(this);

        if (isRangeViolation(field, value)) {
            Range range = field.getAnnotation(Range.class);
            return String.format("field '%s' value %s is out of range [%s, %s]",
                    field.getName(), value, range.min(), range.max());
        }
        if (isNotEmptyViolation(field, value)) {
            return String.format("field '%s' must not be empty", field.getName());
        }
        if (isSizeViolation(field, value)) {
            Size size = field.getAnnotation(Size.class);
            int len = getValueLength(value);
            return String.format("field '%s' size %d is out of bounds [%d, %d]",
                    field.getName(), len, size.min(), size.max());
        }
        if (isPatternViolation(field, value)) {
            Pattern pattern = field.getAnnotation(Pattern.class);
            String displayValue = isSecretShapedFieldName(field.getName()) ? "<redacted>" : "'" + value + "'";
            return String.format("field '%s' value %s does not match pattern '%s'",
                    field.getName(), displayValue, pattern.regex());
        }
        return null;
    }

    /**
     * Whether a field name looks like it stores a secret. Only {@code @Pattern} violations echo
     * an arbitrary string value; {@code @Range}/{@code @Size} violations always echo a number or
     * a length, and {@code @NotEmpty} violations are empty by definition, so neither can leak a
     * secret verbatim (T-04-04).
     * <p>
     * Widened in 6.3.0 (04-REVIEW.md WR-03) to also cover {@code key}/{@code auth}/
     * {@code private}/{@code cert}, accepting the resulting false positives (a field merely
     * named e.g. {@code publicKey} is redacted too) as the safer default, per Phase 2 D-15's
     * fail-closed preference. The reason for the widening is new, not cosmetic: before the
     * write-path refusal added by this same 6.3.0 change, a {@code @Pattern} refusal message
     * went only to the local console; now both remote config-write handlers forward it
     * verbatim to UltiPanel over the WebSocket (T-04-56), so a name-heuristic miss here leaks
     * the server, not just the console.
     */
    private boolean isSecretShapedFieldName(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.contains("credential")
                || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("key") || lower.contains("auth")
                || lower.contains("private") || lower.contains("cert");
    }

    private boolean isRangeViolation(Field field, Object value) {
        Range range = field.getAnnotation(Range.class);
        if (range == null || !(value instanceof Number)) return false;
        double num = ((Number) value).doubleValue();
        return num < range.min() || num > range.max();
    }

    private boolean isNotEmptyViolation(Field field, Object value) {
        return field.getAnnotation(NotEmpty.class) != null
                && (value == null || value.toString().trim().isEmpty());
    }

    private boolean isSizeViolation(Field field, Object value) {
        Size size = field.getAnnotation(Size.class);
        if (size == null || value == null) return false;
        int len = getValueLength(value);
        return len >= 0 && (len < size.min() || len > size.max());
    }

    private boolean isPatternViolation(Field field, Object value) {
        Pattern pattern = field.getAnnotation(Pattern.class);
        return pattern != null && value instanceof String && !((String) value).matches(pattern.regex());
    }

    private int getValueLength(Object value) {
        if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).size();
        if (value instanceof String) return ((String) value).length();
        return -1;
    }

    // ==================== Configuration Change Listener Support ====================
    
    /**
     * Adds a configuration change listener.
     * The listener will be notified when the configuration is reloaded.
     *
     * @param listener the listener to add
     */
    public void addChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }
    
    /**
     * Removes a configuration change listener.
     *
     * @param listener the listener to remove
     */
    public void removeChangeListener(ConfigChangeListener listener) {
        changeListeners.remove(listener);
    }
    
    /**
     * Removes all configuration change listeners.
     */
    public void clearChangeListeners() {
        changeListeners.clear();
    }
    
    /**
     * Gets the number of registered change listeners.
     *
     * @return the number of listeners
     */
    public int getChangeListenerCount() {
        return changeListeners.size();
    }
    
    /**
     * Notifies all registered listeners about the configuration change.
     * Individual listener exceptions do not affect other listeners.
     */
    protected void notifyChangeListeners() {
        for (ConfigChangeListener listener : changeListeners) {
            try {
                listener.onConfigReload(this);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, 
                    "Config change listener failed for " + this.getClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * Reloads the configuration from file and notifies all listeners.
     *
     * @throws IOException if an I/O error occurs
     */
    public void reload() throws IOException {
        if (ultiToolsPlugin == null) {
            throw new IllegalStateException("Config not initialized. Call init() first.");
        }
        
        // Reload from file
        config = YamlConfiguration.loadConfiguration(ultiToolsPlugin.getConfigFile(configFilePath));
        
        // Update field values
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                Object configValue = config.get(path);
                if (configValue != null) {
                    Object parse = ReflectionUtil.newInstance(annotation.parser()).parse(configValue);
                    ReflectionUtil.setFieldValue(this, field, parse);
                }
            }
        }

        // Validate fields and reset invalid values to defaults
        validateFields();

        // Notify listeners
        notifyChangeListeners();
    }
}
