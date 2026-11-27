package com.ultikits.ultitools.api;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.text.internal.TextEngine;

import lombok.Getter;
import lombok.Setter;

/**
 * Wraps a regular Bukkit {@link JavaPlugin} to provide the information
 * UltiTools framework needs for annotation scanning, IoC, and data storage.
 *
 * @since 6.2.2
 */
public class ExternalPluginAdapter {
    @Getter
    private final JavaPlugin javaPlugin;
    @Getter
    private final String pluginName;
    @Getter
    private final String version;
    @Getter
    private final List<String> authors;
    @Getter
    private final String mainClass;
    @Getter
    private final File dataFolder;
    @Getter
    private final String scanPackage;
    @Getter
    @Setter
    private SimpleContainer context;
    @Getter
    @Setter
    private volatile boolean connected;
    /**
     * The credential {@code PluginManager.registerExternal} mints for this adapter (D-17), set
     * immediately after minting and before {@code wireAop} runs. {@code null} only in the brief
     * window between adapter construction and that assignment.
     *
     * @since 6.3.0
     */
    @Getter
    @Setter
    private DataScope dataScope;
    private volatile @Nullable TextEngine.ExternalCatalogue messageCatalogue;

    public ExternalPluginAdapter(JavaPlugin javaPlugin) {
        this.javaPlugin = javaPlugin;
        this.pluginName = javaPlugin.getName();
        var description = javaPlugin.getDescription();
        PluginMeta metadata = description != null ? description : javaPlugin.getPluginMeta();
        this.version = metadata.getVersion();
        this.authors = metadata.getAuthors();
        this.mainClass = description != null ? description.getMain() : metadata.getMainClass();
        this.dataFolder = javaPlugin.getDataFolder();
        this.scanPackage = extractPackage(this.mainClass);
        this.connected = false;
    }

    /**
     * Get the classloader of the wrapped JavaPlugin.
     *
     * @return the plugin's classloader
     */
    public ClassLoader getPluginClassLoader() {
        return javaPlugin.getClass().getClassLoader();
    }

    /**
     * Get the logger of the wrapped JavaPlugin.
     *
     * @return the plugin's logger
     */
    public Logger getLogger() {
        return javaPlugin.getLogger();
    }

    /** Keeps the publication handle even if the child container fails to refresh. */
    @ApiStatus.Internal
    public void setMessageCatalogue(TextEngine.ExternalCatalogue catalogue) {
        messageCatalogue = catalogue;
    }

    /** Withdraws or rolls back this connection's publication, if any. */
    @ApiStatus.Internal
    public void withdrawMessages() {
        var catalogue = messageCatalogue;
        messageCatalogue = null;
        if (catalogue != null) {
            catalogue.withdraw();
        }
    }

    void reloadMessages() {
        var catalogue = messageCatalogue;
        if (catalogue != null) {
            catalogue.reload();
        }
    }

    private static String extractPackage(String mainClassName) {
        int lastDot = mainClassName.lastIndexOf('.');
        return lastDot > 0 ? mainClassName.substring(0, lastDot) : "";
    }
}
