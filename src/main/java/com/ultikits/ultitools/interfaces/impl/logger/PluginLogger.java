package com.ultikits.ultitools.interfaces.impl.logger;

import cn.hutool.log.Log;

public class PluginLogger {
    private final Log log;
    private final String pluginName;

    public PluginLogger(String pluginName, Log log){
        this.log = log;
        this.pluginName = pluginName;
    }

    public void info(String message){
        log.info("[" + pluginName + "] " + message);
    }

    public void warn(String message){
        log.warn("[" + pluginName + "] " + message);
    }

    public void error(String message){
        log.error("[" + pluginName + "] " + message);
    }

    public void debug(String message){
        log.debug("[" + pluginName + "] " + message);
    }

    public void trace(String message){
        log.trace("[" + pluginName + "] " + message);
    }

    public void info(String message, Object... params){
        log.info("[" + pluginName + "] " + message, params);
    }

    public void warn(String message, Object... params){
        log.warn("[" + pluginName + "] " + message, params);
    }

    public void error(String message, Object... params){
        log.error("[" + pluginName + "] " + message, params);
    }

    public void debug(String message, Object... params){
        log.debug("[" + pluginName + "] " + message, params);
    }

    public void trace(String message, Object... params){
        log.trace("[" + pluginName + "] " + message, params);
    }

    public void info(Throwable throwable){
        log.info("[" + pluginName + "] ", throwable);
    }

    public void warn(Throwable throwable){
        log.warn("[" + pluginName + "] ", throwable);
    }

    public void error(Throwable throwable){
        log.error("[" + pluginName + "] ", throwable);
    }

    public void debug(Throwable throwable){
        log.debug("[" + pluginName + "] ", throwable);
    }

    public void trace(Throwable throwable){
        log.trace("[" + pluginName + "] ", throwable);
    }

    public void info(Throwable throwable, String message){
        log.info("[" + pluginName + "] " + message, throwable);
    }

    public void warn(Throwable throwable, String message){
        log.warn("[" + pluginName + "] " + message, throwable);
    }

    public void error(Throwable throwable, String message){
        log.error("[" + pluginName + "] " + message, throwable);
    }

    public void debug(Throwable throwable, String message){
        log.debug("[" + pluginName + "] " + message, throwable);
    }

    public void trace(Throwable throwable, String message){
        log.trace("[" + pluginName + "] " + message, throwable);
    }

    public void info(Throwable throwable, String message, Object... params){
        log.info("[" + pluginName + "] " + message, params, throwable);
    }

    public void warn(Throwable throwable, String message, Object... params){
        log.warn("[" + pluginName + "] " + message, params, throwable);
    }

    public void error(Throwable throwable, String message, Object... params){
        log.error("[" + pluginName + "] " + message, params, throwable);
    }

    public void debug(Throwable throwable, String message, Object... params){
        log.debug("[" + pluginName + "] " + message, params, throwable);
    }

    public void trace(Throwable throwable, String message, Object... params){
        log.trace("[" + pluginName + "] " + message, params, throwable);
    }
}
