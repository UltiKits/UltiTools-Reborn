package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

class BootstrapLoggingTest {
    @TempDir Path server;

    @Test
    void summaryFailureAndModuleJulMessagesReachPapersBootstrapLogger() throws Exception {
        Path data = Files.createDirectories(server.resolve("plugins/UltiTools"));
        Path modules = ModuleFileTransactions.modulesFolder(data.toFile()).toPath();
        BootstrapFixtures.jar(modules.resolve("bad.jar"), BootstrapFixtures.descriptor("Bad", "fixture.Bad"),
                Collections.singletonMap("fixture.Bad", BootstrapFixtures.hook("fixture.Bad", "java/lang/NoClassDefFoundError")));
        BootstrapFixtures.jar(modules.resolve("good.jar"), BootstrapFixtures.descriptor("Good", "fixture.Good"),
                Collections.singletonMap("fixture.Good", BootstrapFixtures.hook("fixture.Good", null)));
        ComponentLogger paper = mock(ComponentLogger.class);
        BootstrapContext context = context(data, paper);
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context);
        try (ModuleClassLoader loader = bootstrap.result().classLoader()) {
            assertThat(bootstrap.result().bootstrapFailures()).hasSize(1);
            verify(paper).info(eq("[UltiTools] Bootstrap: processed pending module changes; 2 module JAR(s) on the module class path; "
                    + "2 module bootstrap(s) ran, 1 failed."), isNull(Throwable.class));
            verify(paper).error(startsWith("[UltiTools] Module 'bad.jar' failed in its bootstrap phase"),
                    any(NoClassDefFoundError.class));
            ModuleBootstrapContext module = (ModuleBootstrapContext) loader.loadClass("fixture.Good").getField("context").get(null);
            IllegalArgumentException failure = new IllegalArgumentException("module log cause");
            module.logger().log(Level.WARNING, "module {0}", "warning");
            module.logger().log(Level.SEVERE, "module error", failure);
            module.logger().fine("module debug");
            verify(paper).warn(eq("[UltiTools/Good] module warning"), isNull(Throwable.class));
            verify(paper).error("[UltiTools/Good] module error", failure);
            verify(paper).debug(eq("[UltiTools/Good] module debug"), isNull(Throwable.class));
        }
    }

    @Test
    void overallPreparationFailureUsesTheSamePaperLoggerAndIsRetained() throws Exception {
        ComponentLogger paper = mock(ComponentLogger.class);
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context(Path.of("/UltiTools"), paper));
        try (ModuleClassLoader loader = bootstrap.result().classLoader()) {
            assertThat(loader.getURLs()).isEmpty();
            assertThat(bootstrap.result().classPathFailure()).hasCauseInstanceOf(IllegalStateException.class);
            verify(paper).error(eq("[UltiTools] Failed to prepare module bootstrap; the framework will be disabled during enable."),
                    any(IllegalStateException.class));
        }
    }

    private static BootstrapContext context(Path data, ComponentLogger logger) {
        BootstrapContext context = mock(BootstrapContext.class);
        when(context.getDataDirectory()).thenReturn(data);
        when(context.getLogger()).thenReturn(logger);
        return context;
    }
}
