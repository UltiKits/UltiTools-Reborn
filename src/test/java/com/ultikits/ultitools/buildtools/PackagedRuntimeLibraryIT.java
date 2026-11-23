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
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Failsafe runs after shade: inspect every class, including bundled dependency packages. */
// The *IT suffix intentionally follows Maven Failsafe discovery for execution after package/shade.
@SuppressWarnings("PMD.ClassNamingConventions")
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
    void finalArtifactHasNoRetiredCrossVersionLibraryEntriesOrLinkage() throws Exception {
        Path jar = Paths.get(System.getProperty("runtimeGuardArtifact"));
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
            java.util.Enumeration<JarEntry> entries = archive.entries();
            int inspectedClasses = 0;
            boolean descriptorFound = false;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                assertThat(entry.getName()).doesNotContain("cryptomorin", "XVersionUtils");
                if (entry.getName().endsWith(".class") || entry.getName().equals("plugin.yml")
                        || entry.getName().endsWith("/pom.xml")) {
                    try (java.io.InputStream input = archive.getInputStream(entry)) {
                        String contents = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                        assertThat(contents).as(entry.getName())
                                .doesNotContain("cryptomorin", "XSeries", "XVersionUtils");
                    }
                    if (entry.getName().endsWith(".class")) {
                        inspectedClasses++;
                    }
                    descriptorFound |= entry.getName().equals("plugin.yml");
                }
            }
            assertThat(inspectedClasses).isGreaterThan(0);
            assertThat(descriptorFound).isTrue();
            System.out.println("Post-shade cross-version guard: classes=" + inspectedClasses
                    + ", descriptor=true, forbidden entries/references=0");
        }
    }

    @Test
    void packagedDependencyInjectionIsDetectedByTheSameScanner() throws Exception {
        Path jar = temporary.resolve("dependency-control.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("mc/obliviate/Injected.class"));
            output.write(methodFixture(
                    "net/kyori/adventure/platform/bukkit/BukkitAudiences", "close", "()V"));
            output.closeEntry();
        }
        Map<String, byte[]> classes = RuntimeApiGuard.jarClasses(jar);
        assertThat(classes).hasSize(1);
        assertThat(RuntimeApiGuard.platformReferences(classes)).containsExactly("mc/obliviate/Injected.class");
    }

    private static byte[] methodFixture(String owner, String name, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "control/Probe", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
