package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Platform scanning applies to every package and both bytecode and reflective class names. */
class RuntimeApiGuardPlatformScanTest {
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

    private static byte[] methodFixture(String owner, String name, String descriptor) {
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
