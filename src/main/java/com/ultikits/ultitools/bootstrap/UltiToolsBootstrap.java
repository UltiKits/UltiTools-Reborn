package com.ultikits.ultitools.bootstrap;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;

/** Paper's early-start entry point. Internal; not an API for modules. */
@ApiStatus.Internal
public final class UltiToolsBootstrap implements PluginBootstrap {
    private static final Logger LOGGER = Logger.getLogger("UltiTools");
    private @Nullable BootstrapResult result;

    @Override
    public void bootstrap(BootstrapContext context) {
        File dataFolder = null;
        try {
            dataFolder = context.getDataDirectory().toFile();
            result = prepare(dataFolder);
        } catch (Throwable failure) { // NOPMD - a module failure must never abort the framework bootstrap
            LOGGER.log(Level.SEVERE, "[UltiTools] Failed to prepare module bootstrap; using an empty module set.", failure);
            if (dataFolder != null) {
                result = BootstrapResult.empty(dataFolder, new IOException("Failed to prepare module class path", failure));
            }
        }
    }

    private BootstrapResult prepare(File dataFolder) {
        ModuleFileTransactions transactions = new ModuleFileTransactions(dataFolder);
        ModuleJarAdmission admission = new ModuleJarAdmission();
        URL serverJar = ModuleClassPath.serverJarOf(LOGGER);
        IOException classPathFailure = null;
        URL[] urls;
        try {
            urls = classPath(transactions, dataFolder, serverJar, admission);
        } catch (IOException failure) {
            classPathFailure = failure;
            admission = new ModuleJarAdmission();
            urls = serverJar == null ? new URL[0] : new URL[] {serverJar};
        }
        ModuleClassLoader loader = ModuleClassLoader.create(urls, getClass().getClassLoader());
        return new BootstrapResult(transactions, admission, loader, classPathFailure, Collections.emptyMap(), 0);
    }

    // Package-private fault seam: transactions themselves collect and defer filesystem errors.
    URL[] classPath(ModuleFileTransactions transactions, File dataFolder, @Nullable URL serverJar,
                    ModuleJarAdmission admission) throws IOException {
        return ModuleClassPath.moduleClassPath(transactions, ModuleFileTransactions.modulesFolder(dataFolder),
                serverJar, admission);
    }

    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        BootstrapResult boot = result;
        if (boot == null) {
            boot = BootstrapResult.empty(context.getDataDirectory().toFile(), null);
        }
        return new UltiTools(boot);
    }

    @Nullable BootstrapResult result() { return result; }
}
