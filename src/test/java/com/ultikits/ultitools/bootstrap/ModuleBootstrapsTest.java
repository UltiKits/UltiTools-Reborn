package com.ultikits.ultitools.bootstrap;

import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.descriptor;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.hook;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.jar;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.manager.ModuleJarAdmission;

class ModuleBootstrapsTest {
    @TempDir Path temporary;
    private final Logger logger = Logger.getAnonymousLogger();

    @Test
    void successfulBootstrapper_receivesItsIdentityAndModuleContextLoader() throws Exception {
        Path module = jar(temporary.resolve("space + 模块/early.jar"), descriptor("Early", "fixture.Early"),
                Collections.singletonMap("fixture.Early", hook("fixture.Early", null)));
        ModuleJarAdmission admission = admission(module);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (ModuleClassLoader loader = loader(module)) {
            ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission, loader, logger);
            assertThat(run.attempted).isEqualTo(1);
            assertThat(run.failures).isEmpty();
            Class<?> early = loader.loadClass("fixture.Early");
            ModuleBootstrapContext context = (ModuleBootstrapContext) early.getField("context").get(null);
            assertThat(context.moduleName()).isEqualTo("Early");
            assertThat(context.moduleJar()).isEqualTo(module);
            assertThat(context.logger().getName()).isEqualTo("UltiTools/Early");
            assertThat(early.getField("threadLoader").get(null)).isSameAs(loader);
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
        }
    }

    @Test
    void throwingBootstrapper_excludesOnlyThatModule() throws Exception {
        Path bad = jar(temporary.resolve("a-bad.jar"), descriptor("Bad", "fixture.Bad"),
                Collections.singletonMap("fixture.Bad", hook("fixture.Bad", "java/lang/NoClassDefFoundError")));
        Path good = jar(temporary.resolve("z-good.jar"), descriptor("Good", "fixture.Good"),
                Collections.singletonMap("fixture.Good", hook("fixture.Good", null)));
        ModuleJarAdmission admission = admission(good, bad);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (ModuleClassLoader loader = loader(bad, good)) {
            ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission, loader, logger);
            assertThat(run.attempted).isEqualTo(2);
            assertThat(run.failures).containsOnlyKeys(bad);
            assertThat(run.failures.get(bad)).isInstanceOf(NoClassDefFoundError.class);
            assertThat(admission.inspect(bad.toFile()).getBootstrapFailure()).isSameAs(run.failures.get(bad));
            assertThat(admission.inspect(good.toFile()).getBootstrapFailure()).isNull();
            assertThat(loader.loadClass("fixture.Good").getField("context").get(null)).isNotNull();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
        }
    }

    @Test
    void bootstrapperFromAnotherJar_isRejected() throws Exception {
        Path owner = jar(temporary.resolve("a-owner.jar"), descriptor("Owner", "fixture.Shared"),
                Collections.singletonMap("fixture.Shared", hook("fixture.Shared", null)));
        Path borrower = jar(temporary.resolve("b-borrower.jar"), descriptor("Borrower", "fixture.Shared"), Collections.emptyMap());
        ModuleJarAdmission admission = admission(owner, borrower);
        try (ModuleClassLoader loader = loader(owner, borrower)) {
            ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission, loader, logger);
            assertThat(run.failures).containsOnlyKeys(borrower);
            assertThat(run.failures.get(borrower)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("bootstrapper class is not in this module's JAR");
            ModuleBootstrapContext context = (ModuleBootstrapContext) loader.loadClass("fixture.Shared").getField("context").get(null);
            assertThat(context.moduleName()).isEqualTo("Owner");
        }
    }

    @Test
    void invalidHooks_failIndividuallyAndKeepFilenameOrderedOutcomes() throws Exception {
        Path missing = jar(temporary.resolve("z-missing.jar"), descriptor("Missing", "fixture.Missing"), Collections.emptyMap());
        Path wrong = jar(temporary.resolve("a-wrong.jar"), descriptor("Wrong", "fixture.Wrong"),
                Collections.singletonMap("fixture.Wrong", BootstrapFixtures.plainClass("fixture.Wrong")));
        try (ModuleClassLoader loader = loader(missing, wrong)) {
            ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission(missing, wrong), loader, logger);
            assertThat(run.attempted).isEqualTo(2);
            assertThat(run.failures.keySet()).containsExactly(wrong, missing);
            assertThat(run.failures.get(wrong)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("bootstrapper does not implement ModuleBootstrap");
            assertThat(run.failures.get(missing)).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void noHookOrRefusedApi_isNotAttempted() throws Exception {
        Path ordinary = jar(temporary.resolve("ordinary.jar"), descriptor("Ordinary", null), Collections.emptyMap());
        Path refused = jar(temporary.resolve("refused.jar"), descriptor("Refused", "fixture.Refused").replace("700", "630"),
                Collections.singletonMap("fixture.Refused", hook("fixture.Refused", "java/lang/AssertionError")));
        try (ModuleClassLoader loader = loader(ordinary, refused)) {
            ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission(refused, ordinary), loader, logger);
            assertThat(run.attempted).isZero();
            assertThat(run.failures).isEmpty();
        }
    }

    @Test
    void parentClasspathBootstrapper_cannotMasqueradeAsTheModulesOwnHook() throws Exception {
        Path module = jar(temporary.resolve("parent.jar"), descriptor("Parent", "java.lang.String"), Collections.emptyMap());
        try (ModuleClassLoader loader = loader(module)) {
            ModuleBootstraps.Run run = ModuleBootstraps.runAll(admission(module), loader, logger);
            assertThat(run.failures.get(module)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("bootstrapper class is not in this module's JAR");
        }
    }

    private static ModuleJarAdmission admission(Path... jars) {
        ModuleJarAdmission admission = new ModuleJarAdmission();
        for (Path path : jars) { admission.inspect(path.toFile()); }
        return admission;
    }

    private ModuleClassLoader loader(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) { urls[i] = jars[i].toUri().toURL(); }
        return ModuleClassLoader.create(urls, getClass().getClassLoader());
    }
}
