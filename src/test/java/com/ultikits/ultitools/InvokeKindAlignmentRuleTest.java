package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InvokeKindAlignmentRuleTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"virtual,true", "virtual,false", "static,true", "static,false", "handle,true", "handle,false",
            "static-handle,true", "static-handle,false", "lambda,true", "lambda,false", "bootstrap,true", "bootstrap,false", "condy,true", "condy,false"})
    void eachInstructionForm_hasAnIccErrorControlAndRunsThroughProductionLoader(String form, boolean compileInterface) throws Exception {
        String member = form.startsWith("static") ? "staticValue" : "value";
        InvokeKindTestFixture fixture = fixture("forms", !compileInterface, form, compileInterface, member);
        try (URLClassLoader parent = fixture.parent();
                URLClassLoader baseline = new URLClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent);
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent, null)) {
            assertThatThrownBy(() -> fixture.invoke(baseline)).satisfies(failure ->
                    assertThat(InvokeKindTestFixture.root(failure)).isInstanceOf(IncompatibleClassChangeError.class));
            assertThatCode(() -> assertThat(fixture.invoke(actual)).isEqualTo("rewritten")).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"org/bukkit/", "io/papermc/", "com/destroystokyo/", "net/kyori/", "co/aikar/"})
    void arbitraryTypesInEveryServerNamespace_needNoPerTypeEntry(String prefix) throws Exception {
        InvokeKindTestFixture fixture = new InvokeKindTestFixture(temp.resolve("namespace"), prefix + "compatfixture/Unlisted",
                false, "virtual", true, "value");
        try (URLClassLoader parent = fixture.parent();
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent, null)) {
            assertThatCode(() -> assertThat(fixture.invoke(actual)).isEqualTo("rewritten")).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @CsvSource({"virtual,true", "virtual,false", "static,true", "static,false", "handle,true", "handle,false", "lambda,true", "lambda,false"})
    void matchingKinds_areDefinedByteForByteWithoutReencoding(String form, boolean itf) throws Exception {
        InvokeKindTestFixture fixture = fixture("matching", itf, form, itf, form.equals("static") ? "staticValue" : "value");
        AtomicReference<byte[]> defined = new AtomicReference<>();
        try (URLClassLoader parent = fixture.parent();
                ModuleClassLoader actual = observed(fixture, parent, defined)) {
            assertThat(fixture.invoke(actual)).isEqualTo("rewritten");
            assertThat(defined.get()).isEqualTo(fixture.original);
        }
    }

    @ParameterizedTest
    @CsvSource({"special,true", "special,false", "special-handle,true", "special-handle,false"})
    void changedSpecialOwner_isRefusedBeforeDefinition(String form, boolean compileInterface) throws Exception {
        InvokeKindTestFixture fixture = fixture("special", !compileInterface, form, compileInterface, "value");
        try (URLClassLoader parent = fixture.parent();
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent, null)) {
            assertThatThrownBy(() -> actual.loadClass("fixture.Caller"))
                    .isInstanceOf(ModuleCompatibilityException.class).hasMessageContaining("fixture.Caller")
                    .satisfies(failure -> assertThat(InvokeKindTestFixture.root(failure).getMessage())
                            .contains(InvokeKindTestFixture.DEFAULT_OWNER).contains("special"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changedSupertype_refusesTheWholeClassWithoutDefiningHalfRewrittenBytes(boolean wasInterface) throws Exception {
        String owner = InvokeKindTestFixture.DEFAULT_OWNER;
        Path api = InvokeKindTestFixture.jar(temp.resolve("api.jar"), Collections.singletonMap(owner,
                InvokeKindTestFixture.api(owner, !wasInterface, false)), null);
        ClassWriter writer = InvokeKindTestFixture.header(InvokeKindTestFixture.CALLER, false,
                wasInterface ? "java/lang/Object" : owner, wasInterface ? new String[]{owner} : new String[0]);
        writer.visitEnd();
        Path module = InvokeKindTestFixture.jar(temp.resolve("supertype.jar"),
                Collections.singletonMap(InvokeKindTestFixture.CALLER, writer.toByteArray()), "Supertype");
        AtomicReference<byte[]> defined = new AtomicReference<>();
        try (URLClassLoader parent = new URLClassLoader(new URL[]{api.toUri().toURL()}, null);
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{module.toUri().toURL()}, parent, null,
                        Collections.singletonList(InvokeKindTestFixture.rule(InvokeKindTestFixture.probe(parent))),
                        (name, bytes) -> defined.set(bytes))) {
            assertThatThrownBy(() -> actual.loadClass("fixture.Caller"))
                    .isInstanceOf(ModuleCompatibilityException.class)
                    .satisfies(failure -> assertThat(InvokeKindTestFixture.root(failure).getMessage())
                            .contains(owner).contains(wasInterface ? "implements" : "extends"));
            assertThat(defined.get()).isNull();
        }
    }

    @Test
    void missingMember_isNotCoveredButKindAlignmentExposesTheNormalMissingMethodError() throws Exception {
        InvokeKindTestFixture fixture = fixture("missing", false, "virtual", true, "absent");
        try (URLClassLoader parent = fixture.parent();
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent, null)) {
            ClassRewriteRule rule = InvokeKindTestFixture.rule(InvokeKindTestFixture.probe(parent));
            assertThat(rule.covers(reference(ServerApiReference.Kind.METHOD, "absent", "()Ljava/lang/String;",
                    Opcodes.INVOKEINTERFACE, true))).isFalse();
            assertThatThrownBy(() -> fixture.invoke(actual)).satisfies(failure ->
                    assertThat(InvokeKindTestFixture.root(failure)).isExactlyInstanceOf(NoSuchMethodError.class));
        }
    }

    @ParameterizedTest
    @CsvSource({"METHOD,185,true", "METHOD,182,false", "METHOD,184,true", "METHOD,184,false",
            "METHOD_HANDLE,9,true", "METHOD_HANDLE,5,false", "METHOD_HANDLE,6,true", "METHOD_HANDLE,6,false"})
    void coversUsesTheExactMemberAndSupportedReferenceForm(ServerApiReference.Kind kind, int opcode, boolean itf) throws Exception {
        InvokeKindTestFixture fixture = fixture("covers", !itf, "virtual", itf, "value");
        try (URLClassLoader parent = fixture.parent()) {
            ClassRewriteRule rule = InvokeKindTestFixture.rule(InvokeKindTestFixture.probe(parent));
            String name = opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.H_INVOKESTATIC ? "staticValue" : "value";
            assertThat(rule.covers(reference(kind, name, "()Ljava/lang/String;", opcode, itf))).isTrue();
            assertThat(rule.covers(reference(kind, name, "()Ljava/lang/Object;", opcode, itf))).isFalse();
            assertThat(rule.covers(reference(kind, name, "()Ljava/lang/String;", opcode, !itf))).isFalse();
        }
    }

    @Test
    void unsupportedReferencesAndAbsentOwners_areNeverCoveredOrInvented() throws Exception {
        InvokeKindTestFixture fixture = fixture("unsupported", false, "virtual", true, "value");
        try (URLClassLoader parent = fixture.parent()) {
            ClassRewriteRule rule = InvokeKindTestFixture.rule(InvokeKindTestFixture.probe(parent));
            for (ServerApiReference.Kind kind : new ServerApiReference.Kind[]{ServerApiReference.Kind.TYPE,
                    ServerApiReference.Kind.SUPERCLASS, ServerApiReference.Kind.INTERFACE,
                    ServerApiReference.Kind.FIELD, ServerApiReference.Kind.FIELD_HANDLE}) {
                assertThat(rule.covers(reference(kind, "value", "()Ljava/lang/String;", Opcodes.INVOKEINTERFACE, true))).isFalse();
            }
            assertThat(rule.covers(reference(ServerApiReference.Kind.METHOD, "value", "()Ljava/lang/String;",
                    Opcodes.INVOKESPECIAL, true))).isFalse();
            ServerApiReference absent = new ServerApiReference(ServerApiReference.Kind.METHOD, "org/bukkit/compatfixture/Absent",
                    "value", "()Ljava/lang/String;", Opcodes.INVOKEINTERFACE, true);
            assertThat(rule.covers(absent)).isFalse();
            byte[] bytes = InvokeKindTestFixture.caller(absent.getOwner(), "virtual", true, "value");
            assertThat(rule.wouldChange("fixture.Caller", bytes)).isFalse();
            assertThat(rule.rewrite("fixture.Caller", bytes)).isEqualTo(bytes);
        }
    }

    @Test
    void namespaceBoundaryAndOrdinaryConstructor_areUntouched() throws Exception {
        ServerApiProbe hostile = new ServerApiProbe() {
            @Override public ServerApiTypeKind typeKind(String owner) { throw new AssertionError("outside namespace"); }
            @Override public boolean hasMethod(String owner, String name, String descriptor) { throw new AssertionError("outside namespace"); }
            @Override public boolean hasField(String owner, String name, String descriptor) { throw new AssertionError("outside namespace"); }
        };
        ClassRewriteRule rule = InvokeKindTestFixture.rule(hostile);
        for (String owner : new String[]{"org/bukkitish/Shape", "module/Shape", "java/lang/Object"}) {
            byte[] bytes = InvokeKindTestFixture.caller(owner, "virtual", true, "value");
            assertThat(rule.wouldChange("fixture.Caller", bytes)).isFalse();
            assertThat(rule.rewrite("fixture.Caller", bytes)).isEqualTo(bytes);
        }
    }

    @Test
    void rewrittenBytes_haveTheCorrectConstantPoolKindAndPreserveDebugResourcesAndDiskBytes() throws Exception {
        InvokeKindTestFixture fixture = fixture("fidelity", false, "virtual", true, "value");
        AtomicReference<byte[]> defined = new AtomicReference<>();
        byte[] diskBefore = Files.readAllBytes(fixture.module);
        try (URLClassLoader parent = fixture.parent(); ModuleClassLoader actual = observed(fixture, parent, defined)) {
            assertThatCode(() -> assertThat(fixture.invoke(actual)).isEqualTo("rewritten")).doesNotThrowAnyException();
            List<Integer> opcodes = new ArrayList<>();
            List<Boolean> interfaces = new ArrayList<>();
            List<Integer> lines = new ArrayList<>();
            new ClassReader(defined.get()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            if (owner.equals(fixture.owner)) { opcodes.add(opcode); interfaces.add(itf); }
                        }
                        @Override public void visitLineNumber(int line, net.bytebuddy.jar.asm.Label start) { lines.add(line); }
                    };
                }
            }, 0);
            assertThat(opcodes).containsExactly(Opcodes.INVOKEVIRTUAL);
            assertThat(interfaces).containsExactly(false);
            assertThat(lines).contains(73);
            try (InputStream resource = actual.getResourceAsStream("fixture/Caller.class")) {
                assertThat(read(resource)).isEqualTo(fixture.original);
            }
            assertThat(actual.loadClass("fixture.Caller").getProtectionDomain().getCodeSource().getLocation())
                    .isEqualTo(fixture.module.toUri().toURL());
        }
        assertThat(Files.readAllBytes(fixture.module)).isEqualTo(diskBefore);
    }

    @Test
    void startupCounts_arePerActualJarIncludeZeroAndDoNotRepeatAfterLazyLoading() throws Exception {
        InvokeKindTestFixture fixture = fixture("counts", false, "twice", true, "value");
        ClassWriter lazyWriter = new ClassWriter(0);
        new ClassReader(fixture.original).accept(new ClassVisitor(Opcodes.ASM9, lazyWriter) {
            @Override public void visit(int version, int access, String name, String signature, String superclass, String[] interfaces) {
                super.visit(version, access, "fixture/Lazy", signature, superclass, interfaces);
            }
        }, 0);
        Path zero = InvokeKindTestFixture.jar(temp.resolve("zero.jar"), Collections.singletonMap("fixture/Lazy", lazyWriter.toByteArray()), "ZeroModule");
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try (URLClassLoader parent = fixture.parent();
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL(), zero.toUri().toURL()}, parent, null)) {
            assertThatCode(() -> assertThat(fixture.invoke(actual)).isEqualTo("rewritten")).doesNotThrowAnyException();
            assertThat(fixture.invoke(actual)).isEqualTo("rewritten");
            report(actual, logger);
            Class<?> lazy = actual.loadClass("fixture.Lazy");
            Object receiver = parent.loadClass((fixture.owner + "Impl").replace('/', '.')).getConstructor().newInstance();
            assertThat(lazy.getMethod("run", Object.class).invoke(null, receiver)).isEqualTo("rewritten");
            report(actual, logger);
            assertThat(messages).hasSize(2).anySatisfy(message -> assertThat(message).contains("FixtureModule", "startup", "1 classes", "2 call sites", "R1: 2"))
                    .anySatisfy(message -> assertThat(message).contains("ZeroModule", "0 classes", "0 call sites"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void concurrentSameClass_rewritesAndCountsExactlyOnce() throws Exception {
        InvokeKindTestFixture fixture = fixture("concurrent", false, "virtual", true, "value");
        ExecutorService threads = Executors.newFixedThreadPool(4);
        CyclicBarrier start = new CyclicBarrier(4);
        try (URLClassLoader parent = fixture.parent();
                ModuleClassLoader actual = new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent, null)) {
            List<Future<Class<?>>> results = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                results.add(threads.submit(() -> { start.await(); return actual.loadClass("fixture.Caller"); }));
            }
            Class<?> first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<Class<?>> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(first);
            }
            assertThatCode(() -> assertThat(fixture.invoke(actual)).isEqualTo("rewritten")).doesNotThrowAnyException();
        } finally {
            threads.shutdownNow();
        }
    }

    private InvokeKindTestFixture fixture(String folder, boolean runtimeInterface, String form, boolean compileInterface, String member) throws Exception {
        return new InvokeKindTestFixture(temp.resolve(folder), InvokeKindTestFixture.DEFAULT_OWNER, runtimeInterface, form, compileInterface, member);
    }

    private ModuleClassLoader observed(InvokeKindTestFixture fixture, ClassLoader parent, AtomicReference<byte[]> bytes) throws Exception {
        return new ModuleClassLoader(new URL[]{fixture.module.toUri().toURL()}, parent, null,
                Collections.singletonList(InvokeKindTestFixture.rule(InvokeKindTestFixture.probe(parent))),
                (name, defined) -> bytes.set(defined));
    }

    private static ServerApiReference reference(ServerApiReference.Kind kind, String member, String descriptor, int opcode, boolean itf) {
        return new ServerApiReference(kind, InvokeKindTestFixture.DEFAULT_OWNER, member, descriptor, opcode, itf);
    }

    private static byte[] read(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) { output.write(buffer, 0, length); }
        return output.toByteArray();
    }

    private static void report(ModuleClassLoader loader, Logger logger) {
        loader.logRewriteCounts(logger);
    }
}
