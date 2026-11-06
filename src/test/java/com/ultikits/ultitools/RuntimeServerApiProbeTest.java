package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;

class RuntimeServerApiProbeTest {
    @TempDir Path temp;

    @Test
    void kindsAndExactMembers_areResolvedWithoutInitializingTheApi() throws Exception {
        String owner = InvokeKindTestFixture.DEFAULT_OWNER;
        Path api = InvokeKindTestFixture.jar(temp.resolve("api.jar"),
                Collections.singletonMap(owner, InvokeKindTestFixture.api(owner, false, true)), null);
        try (URLClassLoader parent = new URLClassLoader(new URL[]{api.toUri().toURL()}, null)) {
            ServerApiProbe probe = InvokeKindTestFixture.probe(parent);
            assertThat(probe.typeKind(owner)).isEqualTo(ServerApiTypeKind.CLASS);
            assertThat(probe.hasMethod(owner, "value", "()Ljava/lang/String;")).isTrue();
            assertThat(probe.hasMethod(owner, "value", "()Ljava/lang/Object;")).isFalse();
            assertThat(probe.hasMethod(owner, "<init>", "()V")).isTrue();
            assertThat(probe.hasField(owner, "FIELD", "Ljava/lang/String;")).isTrue();
            assertThat(probe.hasField(owner, "FIELD", "Ljava/lang/Object;")).isFalse();
            assertThatThrownBy(() -> Class.forName("org.bukkit.compatfixture.Shape", true, parent))
                    .isInstanceOf(AssertionError.class).hasMessage("initialized");
        }
    }

    @Test
    void inheritedMembersIncludeExactReturnTypesButNotInheritedInterfaceStaticsOrConstructors() throws Exception {
        String base = "org/bukkit/compatfixture/Base";
        String child = "org/bukkit/compatfixture/Child";
        String parentInterface = "org/bukkit/compatfixture/ParentInterface";
        String childInterface = "org/bukkit/compatfixture/ChildInterface";
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(base, InvokeKindTestFixture.api(base, false, false));
        ClassWriter subclass = InvokeKindTestFixture.header(child, false, base);
        subclass.visitEnd();
        classes.put(child, subclass.toByteArray());
        classes.put(parentInterface, InvokeKindTestFixture.api(parentInterface, true, false));
        ClassWriter subinterface = InvokeKindTestFixture.header(childInterface, true, "java/lang/Object", parentInterface);
        subinterface.visitEnd();
        classes.put(childInterface, subinterface.toByteArray());
        Path api = InvokeKindTestFixture.jar(temp.resolve("inherited.jar"), classes, null);
        try (URLClassLoader parent = new URLClassLoader(new URL[]{api.toUri().toURL()}, null)) {
            ServerApiProbe probe = InvokeKindTestFixture.probe(parent);
            assertThat(probe.typeKind(childInterface)).isEqualTo(ServerApiTypeKind.INTERFACE);
            assertThat(probe.hasMethod(child, "value", "()Ljava/lang/String;")).isTrue();
            assertThat(probe.hasMethod(child, "value", "()Ljava/lang/Object;")).isFalse();
            assertThat(probe.hasMethod(child, "<init>", "()V")).isFalse();
            assertThat(probe.hasField(child, "FIELD", "Ljava/lang/String;")).isTrue();
            assertThat(probe.hasMethod(childInterface, "value", "()Ljava/lang/String;")).isTrue();
            assertThat(probe.hasMethod(childInterface, "toString", "()Ljava/lang/String;")).isTrue();
            assertThat(probe.hasMethod(childInterface, "staticValue", "()Ljava/lang/String;")).isFalse();
        }
    }

    @Test
    void absenceIsCachedAndIsDifferentFromAnApiLinkageFailure() {
        AtomicInteger queries = new AtomicInteger();
        ClassLoader parent = new ClassLoader(null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("org.bukkit.compatfixture.Absent")) {
                    queries.incrementAndGet();
                    throw new ClassNotFoundException(name);
                }
                if (name.equals("org.bukkit.compatfixture.Broken")) {
                    throw new NoClassDefFoundError("missing/ApiDependency");
                }
                return super.loadClass(name, resolve);
            }
        };
        ServerApiProbe probe = InvokeKindTestFixture.probe(parent);
        String absent = "org/bukkit/compatfixture/Absent";
        assertThat(probe.typeKind(absent)).isEqualTo(ServerApiTypeKind.ABSENT);
        assertThat(probe.typeKind(absent)).isEqualTo(ServerApiTypeKind.ABSENT);
        assertThat(probe.hasMethod(absent, "value", "()V")).isFalse();
        assertThat(probe.hasField(absent, "field", "I")).isFalse();
        assertThat(queries.get()).isEqualTo(1);
        assertThatThrownBy(() -> probe.typeKind("org/bukkit/compatfixture/Broken"))
                .isInstanceOf(NoClassDefFoundError.class).hasMessage("missing/ApiDependency");
    }

    @Test
    void aMissingSignatureDependencyInAnIsolatedApiJar_isNotSwallowedAsMissingMethod() throws Exception {
        String owner = InvokeKindTestFixture.DEFAULT_OWNER;
        ClassWriter apiType = InvokeKindTestFixture.header(owner, true, "java/lang/Object");
        apiType.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "missingDependency",
                "()Lmissing/SignatureDependency;", null, null).visitEnd();
        apiType.visitEnd();
        Path api = InvokeKindTestFixture.jar(temp.resolve("incomplete-api.jar"), Collections.singletonMap(owner, apiType.toByteArray()), null);
        try (URLClassLoader parent = new URLClassLoader(new URL[]{api.toUri().toURL()}, null)) {
            ServerApiProbe probe = InvokeKindTestFixture.probe(parent);
            assertThat(probe.typeKind(owner)).isEqualTo(ServerApiTypeKind.INTERFACE);
            assertThatThrownBy(() -> probe.hasMethod(owner, "missingDependency", "()Lmissing/SignatureDependency;"))
                    .isInstanceOf(NoClassDefFoundError.class);
        }
    }

    @Test
    void implementationAndRuleRemainPackagePrivate() {
        ServerApiProbe probe = InvokeKindTestFixture.probe(getClass().getClassLoader());
        ClassRewriteRule rule = InvokeKindTestFixture.rule(probe);
        for (Class<?> type : new Class<?>[]{probe.getClass(), rule.getClass()}) {
            assertThat(java.lang.reflect.Modifier.isPublic(type.getModifiers())).isFalse();
            for (Class<?> nested : type.getDeclaredClasses()) {
                assertThat(java.lang.reflect.Modifier.isPublic(nested.getModifiers())).isFalse();
            }
        }
    }
}
