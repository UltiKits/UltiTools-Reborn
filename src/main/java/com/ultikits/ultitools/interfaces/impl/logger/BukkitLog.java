package com.ultikits.ultitools.interfaces.impl.logger;

import org.bukkit.Bukkit;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.AbstractLog;
import cn.hutool.log.level.Level;

public class BukkitLog extends AbstractLog {

    private static final long serialVersionUID = -6843151523130063975L;

    private static final String logFormat = "{name}: {msg}";
    private static Level currentLevel = Level.INFO;

    private final String name;

    // -------------------------------------------------------------------------
    // Constructor

    /**
     * 构造
     *
     * @param clazz 类
     */
    public BukkitLog(Class<?> clazz) {
        this.name = (null == clazz) ? StrUtil.NULL : clazz.getName();
    }

    /**
     * 构造
     *
     * @param name 类名
     */
    public BukkitLog(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return this.name;
    }

    /**
     * 设置自定义的日志显示级别
     *
     * @param customLevel 自定义级别
     * @since 4.1.10
     */
    public static void setLevel(Level customLevel) {
        Assert.notNull(customLevel);
        currentLevel = customLevel;
    }

    // -------------------------------------------------------------------------
    // Trace
    @Override
    public boolean isTraceEnabled() {
        return isEnabled(Level.TRACE);
    }

    @Override
    public void trace(String fqcn, Throwable t, String format, Object... arguments) {
        log(fqcn, Level.TRACE, t, format, arguments);
    }

    // -------------------------------------------------------------------------
    // Debug
    @Override
    public boolean isDebugEnabled() {
        return isEnabled(Level.DEBUG);
    }

    @Override
    public void debug(String fqcn, Throwable t, String format, Object... arguments) {
        log(fqcn, Level.DEBUG, t, format, arguments);
    }

    // -------------------------------------------------------------------------
    // Info
    @Override
    public boolean isInfoEnabled() {
        return isEnabled(Level.INFO);
    }

    @Override
    public void info(String fqcn, Throwable t, String format, Object... arguments) {
        log(fqcn, Level.INFO, t, format, arguments);
    }

    // -------------------------------------------------------------------------
    // Warn
    @Override
    public boolean isWarnEnabled() {
        return isEnabled(Level.WARN);
    }

    @Override
    public void warn(String fqcn, Throwable t, String format, Object... arguments) {
        log(fqcn, Level.WARN, t, format, arguments);
    }

    // -------------------------------------------------------------------------
    // Error
    @Override
    public boolean isErrorEnabled() {
        return isEnabled(Level.ERROR);
    }

    @Override
    public void error(String fqcn, Throwable t, String format, Object... arguments) {
        log(fqcn, Level.ERROR, t, format, arguments);
    }

    // ------------------------------------------------------------------------- Log
    @Override
    public void log(String fqcn, Level level, Throwable t, String format, Object... arguments) {
        // fqcn 无效
        if (false == isEnabled(level)) {
            return;
        }

        final Dict dict = Dict.create()
                .set("name", this.name)
                .set("msg", StrUtil.format(format, arguments));

        final String logMsg = StrUtil.format(logFormat, dict);

        switch (level) {
            case FATAL:
            case ERROR:
                Bukkit.getLogger().severe(logMsg);
                break;
            case WARN:
                Bukkit.getLogger().warning(logMsg);
                break;
            case INFO:
                Bukkit.getLogger().info(logMsg);
                break;
            case DEBUG:
                Bukkit.getLogger().fine(logMsg);
                break;
            case TRACE:
                Bukkit.getLogger().finer(logMsg);
                break;
            case ALL:
                Bukkit.getLogger().finest(logMsg);
                break;
            case OFF:
            default:
                break;
        }
    }

    @Override
    public boolean isEnabled(Level level) {
        return currentLevel.compareTo(level) <= 0;
    }
}
