package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failsafe runs after shade: inspect every class, including bundled dependency packages. */
class PackagedRuntimeLibraryIT {
    @TempDir
    Path temporary;

    @Test
    void finalShadedArtifactContainsNoRetiredPlatformReferences() throws Exception {
        String artifact = System.getProperty("runtimeGuardArtifact");
        assertThat(artifact).as("Maven must provide its primary artifact path").isNotBlank();
        Path jar = Paths.get(artifact);
        assertThat(jar).isRegularFile();
        Map<String, byte[]> classes = RuntimeApiGuard.jarClasses(jar);
        assertThat(classes).containsKey("com/ultikits/ultitools/UltiTools.class");
        assertThat(classes.keySet()).anyMatch(name -> name.startsWith("mc/obliviate/"))
                .anyMatch(name -> name.startsWith("com/github/Anon8281/universalScheduler/"));
        assertThat(RuntimeApiGuard.platformReferences(classes)).isEmpty();
        long bundled = classes.keySet().stream().filter(name -> !name.startsWith("com/ultikits/")).count();
        assertThat(bundled).isGreaterThan(0);
        System.out.println("Post-shade platform guard: all classes=" + classes.size()
                + ", non-framework classes=" + bundled + ", forbidden references=0");
    }

    @Test
    void packagedDependencyInjectionIsDetectedByTheSameScanner() throws Exception {
        Path jar = temporary.resolve("dependency-control.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("mc/obliviate/Injected.class"));
            output.write(RuntimeApiGuardTest.methodFixture(
                    "net/kyori/adventure/platform/bukkit/BukkitAudiences", "close", "()V"));
            output.closeEntry();
        }
        Map<String, byte[]> classes = RuntimeApiGuard.jarClasses(jar);
        assertThat(classes).hasSize(1);
        assertThat(RuntimeApiGuard.platformReferences(classes)).containsExactly("mc/obliviate/Injected.class");
    }
}
