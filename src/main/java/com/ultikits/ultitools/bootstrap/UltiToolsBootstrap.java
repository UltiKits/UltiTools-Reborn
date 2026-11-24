package com.ultikits.ultitools.bootstrap;

import java.io.File;
import java.io.IOException;
import java.net.URL;
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

/**
 * Paper's early-start entry point. Internal; not an API for modules.
 * An overall preparation failure is retained for enable, where the framework reports it and is
 * disabled. It is not a successful start with no modules. Virtual machine failures may propagate.
 */
@ApiStatus.Internal
public final class UltiToolsBootstrap implements PluginBootstrap {
    private static final Logger LOGGER = Logger.getLogger("UltiTools");
    private @Nullable BootstrapResult result;
    private @Nullable Throwable bootstrapFailure;

    @Override
    public void bootstrap(BootstrapContext context) {
        result = null;
        bootstrapFailure = null;
        try {
            result = prepare(context.getDataDirectory().toFile());
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable failure) { // NOPMD - a module failure must never abort the framework bootstrap
            bootstrapFailure = failure;
            // Do not construct transactions again here: their server-root resolution may have failed.
            ModuleBootstraps.logFailure(LOGGER,
                    "[UltiTools] Failed to prepare module bootstrap; the framework will be disabled during enable.", failure);
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
        ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission, loader, LOGGER);
        // Early output is English only: neither the language dictionary nor panel capture exists yet.
        int moduleJars = urls.length - (serverJar == null ? 0 : 1);
        LOGGER.info(String.format("[UltiTools] Bootstrap: processed pending module changes; %d module JAR(s) "
                + "on the module class path; %d module bootstrap(s) ran, %d failed.", moduleJars, run.attempted, run.failures.size()));
        return new BootstrapResult(transactions, admission, loader, classPathFailure, run.failures, run.attempted);
    }

    // Package-private fault seam: transactions themselves collect and defer filesystem errors.
    URL[] classPath(ModuleFileTransactions transactions, File dataFolder, @Nullable URL serverJar,
                    ModuleJarAdmission admission) throws IOException {
        return ModuleClassPath.moduleClassPath(transactions, ModuleFileTransactions.modulesFolder(dataFolder),
                serverJar, admission);
    }

    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new UltiTools(result());
    }

    BootstrapResult result() {
        if (result == null) {
            result = BootstrapResult.failed(new IOException("Failed to prepare module class path", bootstrapFailure));
        }
        return result;
    }
}
