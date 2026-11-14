package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Independent API fixtures, not the modern Paper/Gson on Surefire's ordinary classpath. */
class RuntimeApiGuardTest {
    private static Path target() {
        return Paths.get(System.getProperty("basedir", "."), "target");
    }

    static RuntimeApiGuard floorGuard() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.getBoolean("runtime.api.floor.skip"),
                "Runtime API floor fixture explicitly skipped with -Druntime.api.floor.skip=true");
        return new RuntimeApiGuard(target().resolve("runtime-api-floor/gson-2.8.9.jar"),
                target().resolve("runtime-api-floor/paper-api-1.19.2.jar"));
    }

    @Test
    void compiledFrameworkReferencesExistInTheMinimumGsonAndPaperApi() throws Exception {
        Map<String, byte[]> classes = RuntimeApiGuard.compiledClasses(target().resolve("classes"));
        assertThat(classes).containsKey("com/ultikits/ultitools/UltiTools.class");
        assertThat(classes.size()).isGreaterThan(100);
        assertThat(floorGuard().missingReferences(classes)).isEmpty();
        System.out.println("Floor API guard: framework classes=" + classes.size()
                + ", Gson=2.8.9, Paper API=1.19.2 (20221206.184705-189), missing references=0");
    }

    @Test
    void floorAllowsOldInheritedMembersButRejectsNewGsonAndPaperMembers() throws Exception {
        RuntimeApiGuard guard = floorGuard();
        assertThat(guard.missingReferences(Collections.singletonMap("OldApi.class",
                methodFixture("com/google/gson/JsonObject", "getAsString", "()Ljava/lang/String;"))))
                .as("JsonObject inherits this method from JsonElement on Gson 2.8.9").isEmpty();
        assertThat(guard.missingReferences(Collections.singletonMap("NewGson.class",
                methodFixture("com/google/gson/JsonArray", "asList", "()Ljava/util/List;"))))
                .anyMatch(reference -> reference.contains("JsonArray.asList"));
        assertThat(guard.missingReferences(Collections.singletonMap("NewPaper.class",
                methodFixture("org/bukkit/entity/Player", "getCurrentInput", "()Lorg/bukkit/Input;"))))
                .anyMatch(reference -> reference.contains("Player.getCurrentInput"))
                .anyMatch(reference -> reference.endsWith("org/bukkit/Input"));
        assertThat(guard.missingReferences(Collections.singletonMap("OldPaper.class",
                methodFixture("org/bukkit/entity/Player", "getName", "()Ljava/lang/String;"))))
                .as("Player's inherited Paper-floor API remains usable").isEmpty();
    }

    @Test
    void floorRejectsNewFieldsTypesAndMethodHandles() throws Exception {
        ClassWriter writer = fixtureWriter();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "org/bukkit/Material", "PALE_OAK_LOG", "Lorg/bukkit/Material;");
        method.visitLdcInsn(new net.bytebuddy.jar.asm.Handle(Opcodes.H_INVOKEVIRTUAL,
                "com/google/gson/JsonObject", "isEmpty", "()Z", false));
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        assertThat(floorGuard().missingReferences(Collections.singletonMap("NewSymbols.class", writer.toByteArray())))
                .anyMatch(reference -> reference.contains("Material.PALE_OAK_LOG"))
                .anyMatch(reference -> reference.contains("JsonObject.isEmpty"));
    }

    @Test
    void platformScanIncludesNonFrameworkClassesAndReflectiveNames() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("mc/obliviate/Clean.class", methodFixture("org/bukkit/entity/Player", "getName", "()Ljava/lang/String;"));
        assertThat(RuntimeApiGuard.platformReferences(classes)).isEmpty();
        classes.put("mc/obliviate/Injected.class", methodFixture(
                "net/kyori/adventure/platform/bukkit/BukkitAudiences", "close", "()V"));
        ClassWriter writer = fixtureWriter();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("net.kyori.adventure.platform.bukkit.BukkitAudiences");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        classes.put("thirdparty/Reflective.class", writer.toByteArray());
        assertThat(RuntimeApiGuard.platformReferences(classes))
                .containsExactly("mc/obliviate/Injected.class", "thirdparty/Reflective.class");
    }

    static byte[] methodFixture(String owner, String name, String descriptor) {
        ClassWriter writer = fixtureWriter();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter fixtureWriter() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "control/Probe", null, "java/lang/Object", null);
        return writer;
    }
}
