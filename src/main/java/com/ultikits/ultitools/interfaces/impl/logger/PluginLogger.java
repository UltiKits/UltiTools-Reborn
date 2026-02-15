package com.ultikits.ultitools.interfaces.impl.logger;

import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginLogger {
    private final Logger log;
    private final String pluginName;

    public PluginLogger(String pluginName, Logger log){
        this.log = log;
        this.pluginName = pluginName;
    }

    public void info(String message){
        log.info("[" + pluginName + "] " + message);
    }

    public void warn(String message){
        log.warning("[" + pluginName + "] " + message);
    }

    public void error(String message){
        log.severe("[" + pluginName + "] " + message);
    }

    public void debug(String message){
        log.fine("[" + pluginName + "] " + message);
    }

    public void trace(String message){
        log.finest("[" + pluginName + "] " + message);
    }

    public void info(String message, Object... params){
        log.log(Level.INFO, "[" + pluginName + "] " + message, params);
    }

    public void warn(String message, Object... params){
        log.log(Level.WARNING, "[" + pluginName + "] " + message, params);
    }

    public void error(String message, Object... params){
        log.log(Level.SEVERE, "[" + pluginName + "] " + message, params);
    }

    public void debug(String message, Object... params){
        log.log(Level.FINE, "[" + pluginName + "] " + message, params);
    }

    public void trace(String message, Object... params){
        log.log(Level.FINEST, "[" + pluginName + "] " + message, params);
    }

    public void info(Throwable throwable){
        log.log(Level.INFO, "[" + pluginName + "] ", throwable);
    }

    public void warn(Throwable throwable){
        log.log(Level.WARNING, "[" + pluginName + "] ", throwable);
    }

    public void error(Throwable throwable){
        log.log(Level.SEVERE, "[" + pluginName + "] ", throwable);
    }

    public void debug(Throwable throwable){
        log.log(Level.FINE, "[" + pluginName + "] ", throwable);
    }

    public void trace(Throwable throwable){
        log.log(Level.FINEST, "[" + pluginName + "] ", throwable);
    }

    public void info(Throwable throwable, String message){
        log.log(Level.INFO, "[" + pluginName + "] " + message, throwable);
    }

    public void warn(Throwable throwable, String message){
        log.log(Level.WARNING, "[" + pluginName + "] " + message, throwable);
    }

    public void error(Throwable throwable, String message){
        log.log(Level.SEVERE, "[" + pluginName + "] " + message, throwable);
    }

    public void debug(Throwable throwable, String message){
        log.log(Level.FINE, "[" + pluginName + "] " + message, throwable);
    }

    public void trace(Throwable throwable, String message){
        log.log(Level.FINEST, "[" + pluginName + "] " + message, throwable);
    }

    public void info(Throwable throwable, String message, Object... params){
        log.log(Level.INFO, "[" + pluginName + "] " + message, params);
        log.log(Level.INFO, "", throwable);
    }

    public void warn(Throwable throwable, String message, Object... params){
        log.log(Level.WARNING, "[" + pluginName + "] " + message, params);
        log.log(Level.WARNING, "", throwable);
    }

    public void error(Throwable throwable, String message, Object... params){
        log.log(Level.SEVERE, "[" + pluginName + "] " + message, params);
        log.log(Level.SEVERE, "", throwable);
    }

    public void debug(Throwable throwable, String message, Object... params){
        log.log(Level.FINE, "[" + pluginName + "] " + message, params);
        log.log(Level.FINE, "", throwable);
    }

    public void trace(Throwable throwable, String message, Object... params){
        log.log(Level.FINEST, "[" + pluginName + "] " + message, params);
        log.log(Level.FINEST, "", throwable);
    }
}
