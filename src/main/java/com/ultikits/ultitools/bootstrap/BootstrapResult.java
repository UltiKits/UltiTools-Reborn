package com.ultikits.ultitools.bootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

/**
 * Internal handover from Paper bootstrap to plugin enable; not an API for modules.
 * References and the failure-map snapshot never change. Transactions and admission retain the same
 * identity across both stages so load observation decides exactly the updates applied before load.
 */
@ApiStatus.Internal
public final class BootstrapResult {
    private final ModuleFileTransactions transactions;
    private final ModuleJarAdmission admission;
    private final ModuleClassLoader classLoader;
    private final @Nullable IOException classPathFailure;
    private final Map<Path, Throwable> bootstrapFailures;
    private final int bootstrapsRun;

    BootstrapResult(ModuleFileTransactions transactions, ModuleJarAdmission admission, ModuleClassLoader classLoader,
                    @Nullable IOException classPathFailure, Map<Path, Throwable> failures, int bootstrapsRun) {
        this.transactions = transactions;
        this.admission = admission;
        this.classLoader = classLoader;
        this.classPathFailure = classPathFailure;
        this.bootstrapFailures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
        this.bootstrapsRun = bootstrapsRun;
    }

    static BootstrapResult empty(File dataFolder, @Nullable IOException failure) {
        return new BootstrapResult(new ModuleFileTransactions(dataFolder), new ModuleJarAdmission(),
                ModuleClassLoader.create(new java.net.URL[0], BootstrapResult.class.getClassLoader()),
                failure, Collections.emptyMap(), 0);
    }

    /** @return the original transactions, for post-load observation and delayed translated reports */
    public ModuleFileTransactions transactions() { return transactions; }

    /** @return this start's admission decisions, shared with discovery */
    public ModuleJarAdmission admission() { return admission; }

    /** @return this start's shared module class loader */
    public ModuleClassLoader classLoader() { return classLoader; }

    /** @return a class-path failure to report during enable, or {@code null} */
    public @Nullable IOException classPathFailure() { return classPathFailure; }

    /** @return an immutable, filename-ordered snapshot of individual early-hook failures */
    public Map<Path, Throwable> bootstrapFailures() { return bootstrapFailures; }

    /** @return the number of declared hooks attempted, including failed hooks */
    public int bootstrapsRun() { return bootstrapsRun; }
}
