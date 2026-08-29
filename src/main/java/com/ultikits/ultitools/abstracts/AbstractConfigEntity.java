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
 * <p>
 * 配置实体的抽象类。
 */
@Getter
public abstract class AbstractConfigEntity {
    private static final Logger LOGGER = Logger.getLogger(AbstractConfigEntity.class.getName());
    
    private final String configFilePath;
    private final List<ConfigChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private UltiToolsPlugin ultiToolsPlugin;
    private YamlConfiguration config;

    /**
     * Constructor for AbstractConfigEntity.
     * <p>
     * AbstractConfigEntity的构造函数。
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml <br> 配置文件在resource文件夹的路径，例如：config/config.yml
     */
    public AbstractConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * Saves the configuration to the file.
     * <p>
     * 将配置保存到文件。
     *
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
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
     * <p>
     * 初始化配置实体。
     *
     * @param ultiToolsPlugin the plugin instance <br> 插件实例
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
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
     * <p>
     * 把 {@code @ConfigEntry.comment()} 的值按行拆分成一个 {@link List}，每行一个元素，顺序不变，
     * 供 {@link org.bukkit.configuration.ConfigurationSection} 的注释写入 API 使用。不添加空白的
     * 首行元素（Claude 自行裁量，D-07）——那样会让每次重新生成的文件都产生一次纯粹为了排版的 diff，
     * 与 D-01"尽量少碰操作员文件"的立场相悖。
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
     * 更新配置实体的属性。
     *
     * <p>
     * 字段的遍历方式与路径推导必须与 {@code init()} / {@code save()} / {@code reload()} 完全一致，
     * 否则会出现「写进去了、其实没写」：这四个方法各自遍历 {@code @ConfigEntry} 字段，
     * 而本方法过去两处都跟它们不一样 ——
     * 用 {@code getDeclaredFields()} 而非 {@link ReflectionUtil#getFields(Class)}（漏掉父类字段），
     * 且不把空的 {@code path} 归一到字段名。后者的后果最隐蔽：{@code @ConfigEntry} 不写
     * {@code path} 是受支持的写法，{@code init}/{@code save} 会按字段名读写它，
     * {@link #toJsonObject()} 也按字段名把它发给面板，而这里去 JSON 里找空字符串键，
     * 永远找不到——字段被跳过，随后 {@code config.save} 照常执行，调用方收到成功。
     *
     * @param jsonObject the JSON object containing the new properties <br> 包含新属性的JSON对象
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    public void updateProperties(JsonObject jsonObject) throws IOException {
        Gson gson = new Gson();
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
                        ReflectionUtil.setFieldValue(this, field, configValue);
                        config.set(path, configValue);
                    }
                }
            }
        }
        config.save(ultiToolsPlugin.getConfigFile(configFilePath));
    }

    /**
     * Converts the configuration entity to a JSON object.
     * <p>
     * 将配置实体转换为JSON对象。
     *
     * @return the JSON object representation of the configuration entity <br> 配置实体的JSON对象表示
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
     * <p>
     * 获取配置实体的注释。
     *
     * @return a JSON object containing the comments <br> 包含注释的JSON对象
     */
    public JsonObject getComments() {
        JsonObject jsonObject = new JsonObject();
        // 与 updateProperties 同样的两处对齐：走完整字段树、空 path 归一到字段名。
        // 不归一的话，没写 path 的字段其注释会被塞在 "" 这个键下，而 toJsonObject()
        // 是按字段名发值的，面板两边对不上，那条注释永远显示不出来。
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
     * <p>
     * 验证所有带验证注解的字段。违反约束将拒绝加载该模块，而不是改写字段值——操作员的文件绝不会
     * 被修改（D-01）。所有违规字段会被收集进同一次拒绝里；需要由服务器操作员修正磁盘上的值后重启。
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
     * <p>
     * 通过与 {@code ConfigManager.registerAll} 注册期完全相同的两步回退方式构造并丢弃本类的一个
     * 实例——{@code (String)} 构造函数优先，其次是可访问的无参构造函数。这里证明的是"能否构造"
     * 而非构造出的值：框架需要确认每个已注册的配置类仍然可以通过两种受支持写法之一构建
     * （D-02）。两者都无法解析属于真正的配置类错误（D-03）。
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
     * <p>
     * 描述 {@code field} 违反的单个校验约束（如果有的话）。
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
     * 判断字段名是否形似存放密钥。只有 {@code @Pattern} 违规会回显任意字符串值；
     * {@code @Range}/{@code @Size} 违规始终只回显数字或长度，{@code @NotEmpty} 违规按定义就是
     * 空值，两者都不会泄露密钥原文（T-04-04）。
     */
    private boolean isSecretShapedFieldName(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.contains("credential")
                || lower.contains("apikey") || lower.contains("api_key");
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
     * <p>
     * 添加配置变更监听器。当配置重载时，监听器将被通知。
     *
     * @param listener the listener to add <br> 要添加的监听器
     */
    public void addChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }
    
    /**
     * Removes a configuration change listener.
     * <p>
     * 移除配置变更监听器。
     *
     * @param listener the listener to remove <br> 要移除的监听器
     */
    public void removeChangeListener(ConfigChangeListener listener) {
        changeListeners.remove(listener);
    }
    
    /**
     * Removes all configuration change listeners.
     * <p>
     * 移除所有配置变更监听器。
     */
    public void clearChangeListeners() {
        changeListeners.clear();
    }
    
    /**
     * Gets the number of registered change listeners.
     * <p>
     * 获取已注册的变更监听器数量。
     *
     * @return the number of listeners <br> 监听器数量
     */
    public int getChangeListenerCount() {
        return changeListeners.size();
    }
    
    /**
     * Notifies all registered listeners about the configuration change.
     * Individual listener exceptions do not affect other listeners.
     * <p>
     * 通知所有已注册的监听器配置已变更。单个监听器的异常不影响其他监听器。
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
     * <p>
     * 从文件重新加载配置并通知所有监听器。
     *
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
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
