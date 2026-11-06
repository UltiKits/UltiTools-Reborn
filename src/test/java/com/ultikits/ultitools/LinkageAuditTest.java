package com.ultikits.ultitools;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LinkageAuditTest implements Opcodes {
    @TempDir Path temporary;

    @Test
    void reportsTypesFieldsMethodsAndHandlesWithTheirCallingMember() throws Exception {
        File module = jar("module.jar", references());
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(module, api, Collections.emptyList());
            assertEquals(5, findings.size());
            assertEquals(Arrays.asList("MISSING-CLASS", "MISSING-METHOD", "MISSING-FIELD",
                    "MISSING-METHOD", "MISSING-CLASS"), problemNames(findings));
            assertTrue(findings.stream().allMatch(f -> f.referringClass.equals("fixture/References")));
            assertEquals("call()V", findings.get(1).referringMember);
            assertEquals(42, findings.get(1).line);
            assertEquals("gone", findings.get(1).reference.getName());
        }
    }

    @Test
    void falseCoverageDoesNotHideMissingMembersAndTrueCoverageIsRespected() throws Exception {
        File module = jar("module.jar", references());
        ClassRewriteRule fake = new ClassRewriteRule() {
            public boolean wouldChange(String name, byte[] bytes) { return false; }
            public byte[] rewrite(String name, byte[] bytes) { return bytes; }
            public boolean covers(ServerApiReference ref) { return "gone".equals(ref.getName()); }
        };
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(module, api, Collections.singletonList(fake));
            assertEquals(4, findings.size());
            assertFalse(findings.stream().anyMatch(f -> "gone".equals(f.reference.getName())));
            assertTrue(findings.stream().anyMatch(f -> "absentField".equals(f.reference.getName())));
        }
    }

    @Test
    void missingOwnerProducesOneFindingNotOneForEachMissingMemberAndKind() throws Exception {
        ClassWriter writer = caller("fixture/MissingOwner");
        MethodVisitor method = method(writer, "call", "()V");
        method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/Missing", "gone", "()V", true);
        finish(method);
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("missing.jar", bytes(writer)), api,
                    Collections.emptyList());
            assertEquals(1, findings.size());
            assertEquals(Collections.singletonList("MISSING-CLASS"), findings.get(0).problems);
        }
    }

    @Test
    void inheritsExactDescriptorsButNotConstructorsAndDoesNotAssumeJdkMembersExist() throws Exception {
        ClassWriter writer = caller("fixture/Inherited");
        MethodVisitor method = method(writer, "call", "()V");
        method.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/Child", "exists", "()Ljava/lang/String;", false);
        method.visitFieldInsn(GETFIELD, "org/bukkit/Child", "exists", "I");
        method.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/Child", "toString", "()Ljava/lang/String;", false);
        method.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/Child", "exists", "()I", false);
        method.visitMethodInsn(INVOKESPECIAL, "org/bukkit/Child", "<init>", "(I)V", false);
        finish(method);
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("inherit.jar", bytes(writer)), api,
                    Collections.emptyList());
            assertEquals(2, findings.size());
            assertEquals("()I", findings.get(0).reference.getDescriptor());
            assertEquals("<init>", findings.get(1).reference.getName());
        }
    }

    @Test
    void noFindingsAgainstMatchingApiAndNoClassInitialization() throws Exception {
        ClassWriter writer = caller("fixture/Good");
        MethodVisitor method = method(writer, "call", "()V");
        method.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/Child", "exists", "()Ljava/lang/String;", false);
        finish(method);
        try (URLClassLoader api = api()) {
            assertTrue(LinkageAudit.scan(jar("good.jar", bytes(writer)), api, Collections.emptyList()).isEmpty());
        }
    }

    @Test
    void oneWarningPerReferenceIncludesModuleCallerAndMissingMember() throws Exception {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capture(records);
        try (URLClassLoader api = api()) {
            LinkageAudit.warn(jar("named-module.jar", references()), api, Collections.emptyList(), logger);
        }
        assertEquals(5, records.size());
        assertTrue(records.stream().allMatch(r -> r.getLevel().equals(java.util.logging.Level.WARNING)));
        assertTrue(records.stream().allMatch(r -> r.getMessage().contains("named-module.jar")
                && r.getMessage().contains("fixture.References")));
        assertTrue(records.get(1).getMessage().contains("call()V"));
        assertTrue(records.get(1).getMessage().contains("gone()V"));
    }

    @Test
    void offlineStatusIsNonzeroForUncoveredReferencesAndZeroForMatchingApi() throws Exception {
        File api = apiJar();
        File module = jar("module.jar", references());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(1, LinkageAudit.run(new String[]{"--api", api.getPath(), module.getPath()},
                new PrintStream(output)));
        assertTrue(output.toString("UTF-8").contains("gone()V"));
        ClassWriter writer = caller("fixture/Empty");
        assertEquals(0, LinkageAudit.run(new String[]{"--api", api.getPath(),
                jar("empty.jar", bytes(writer)).getPath()}, new PrintStream(output)));
        assertEquals(2, LinkageAudit.run(new String[0], new PrintStream(output)));
    }

    @Test
    void frameworkNamespaceIsAuditedButUnrelatedLibrariesAreNot() throws Exception {
        ClassWriter writer = caller("fixture/Framework");
        writer.visitField(ACC_PUBLIC, "framework", "Lcom/ultikits/ultitools/Removed;", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "library", "Lthird/party/Absent;", null, null).visitEnd();
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("framework.jar", bytes(writer)), api,
                    Collections.emptyList());
            assertEquals(1, findings.size());
            assertEquals("com/ultikits/ultitools/Removed", findings.get(0).reference.getOwner());
        }
    }

    @Test
    void realPaperInventoryHas26Kind2Methods9MissingClassProblemsAndCurrentHasNone() throws Exception {
        Path repository = new File(System.getProperty("user.home"), ".m2/repository").toPath();
        Path floor = repository.resolve("io/papermc/paper/paper-api/1.19.2-R0.1-SNAPSHOT/paper-api-1.19.2-R0.1-SNAPSHOT.jar");
        Path current = repository.resolve("io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(floor) && Files.isRegularFile(current),
                "Optional real API control; portable stub tests always run");
        File module = jar("inventory.jar", inventory());
        try (URLClassLoader oldApi = new URLClassLoader(new URL[]{floor.toUri().toURL()}, null);
                URLClassLoader newApi = new URLClassLoader(new URL[]{current.toUri().toURL()}, null)) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(module, oldApi, Collections.emptyList());
            List<String> problems = problemNames(findings);
            assertEquals(26, Collections.frequency(problems, "KIND"));
            assertEquals(2, Collections.frequency(problems, "MISSING-METHOD"));
            assertEquals(9, Collections.frequency(problems, "MISSING-CLASS"));
            assertEquals(36, findings.size(), "setTitle has two problems but is one reference");
            assertEquals(4, findings.stream().filter(f -> f.problems.contains("MISSING-CLASS"))
                    .map(f -> f.reference.getOwner()).distinct().count());
            assertTrue(LinkageAudit.scan(module, newApi, Collections.emptyList()).isEmpty());
        }
    }

    @Test
    void startupAuditsBeforeActualModuleLoadAndUsesTheLoadersRuleSnapshot() throws Exception {
        ClassWriter writer = caller("fixture/Loadable");
        MethodVisitor broken = method(writer, "unused", "()V");
        broken.visitMethodInsn(INVOKESTATIC, "org/bukkit/Absent", "missing", "()V", false);
        finish(broken);
        byte[] original = bytes(writer);
        File module = jar("loadable.jar", original);
        List<LogRecord> records = new ArrayList<>();
        List<byte[]> defined = new ArrayList<>();
        com.ultikits.ultitools.manager.PluginManager manager = org.mockito.Mockito.mock(
                com.ultikits.ultitools.manager.PluginManager.class);
        try (ModuleClassLoader loader = new ModuleClassLoader(new URL[]{module.toURI().toURL()},
                getClass().getClassLoader(), null, Collections.emptyList(), (name, value) -> defined.add(value))) {
            org.mockito.Mockito.doAnswer(invocation -> {
                assertEquals(1, records.size(), "startup warning must precede module loading");
                assertNotNull(loader.loadClass("fixture.Loadable"));
                return null;
            }).when(manager).init(loader);
            UltiTools.initModulesWithAudit(loader, manager, capture(records));
            org.mockito.Mockito.verify(manager).init(loader);
            assertArrayEquals(original, defined.get(0), "audit cannot reencode module bytes");
            loader.auditModules(capture(records));
            assertEquals(1, records.size(), "module JAR is audited once per loader");
        }
        ClassRewriteRule fake = new ClassRewriteRule() {
            public boolean wouldChange(String name, byte[] value) { return false; }
            public byte[] rewrite(String name, byte[] value) { return value; }
            public boolean covers(ServerApiReference ref) { return ref.getOwner().equals("org/bukkit/Absent"); }
        };
        List<ClassRewriteRule> rules = new ArrayList<>(Collections.singletonList(fake));
        try (ModuleClassLoader loader = new ModuleClassLoader(new URL[]{module.toURI().toURL()},
                getClass().getClassLoader(), null, rules, null)) {
            rules.clear();
            List<LogRecord> covered = new ArrayList<>();
            loader.auditModules(capture(covered));
            assertTrue(covered.isEmpty(), "audit must use the same immutable rules as class definition");
            assertNotNull(loader.loadClass("fixture.Loadable"));
        }
    }

    @Test
    void staticAndDynamicHandlesFieldHandlesAndSupertypeKindsAreAudited() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "fixture/Shapes", null, "java/lang/Object", new String[]{"org/bukkit/Api"});
        MethodVisitor method = method(writer, "call", "()V");
        method.visitMethodInsn(INVOKESTATIC, "org/bukkit/Api", "exists", "()Ljava/lang/String;", true);
        method.visitLdcInsn(new Handle(H_GETSTATIC, "org/bukkit/Api", "gone", "I", false));
        method.visitInvokeDynamicInsn("call", "()V",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                new Handle(H_INVOKEINTERFACE, "org/bukkit/Api", "exists", "()Ljava/lang/String;", true));
        finish(method);
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("shapes.jar", bytes(writer)), api,
                    Collections.emptyList());
            assertEquals(4, findings.size());
            assertEquals(Arrays.asList("KIND", "KIND", "MISSING-FIELD", "KIND"), problemNames(findings));
            assertEquals(ServerApiReference.Kind.FIELD_HANDLE, findings.get(2).reference.getKind());
            assertEquals(ServerApiReference.Kind.METHOD_HANDLE, findings.get(3).reference.getKind());
        }
    }

    @Test
    void methodArraysReturnTypesCatchTypesAndClassLiteralsAreNotLost() throws Exception {
        ClassWriter writer = caller("fixture/Types");
        MethodVisitor method = method(writer, "handler", "([[Lorg/bukkit/Missing;)Lorg/bukkit/MissingReturn;");
        method.visitLdcInsn(net.bytebuddy.jar.asm.Type.getType("[Lorg/bukkit/Literal;"));
        method.visitMultiANewArrayInsn("[[Lorg/bukkit/Array;", 2);
        net.bytebuddy.jar.asm.Label start = new net.bytebuddy.jar.asm.Label();
        net.bytebuddy.jar.asm.Label end = new net.bytebuddy.jar.asm.Label();
        method.visitTryCatchBlock(start, end, end, "org/bukkit/MissingException");
        finish(method);
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("types.jar", bytes(writer)), api,
                    Collections.emptyList());
            assertEquals(5, findings.size());
            assertTrue(findings.stream().allMatch(f -> f.problems.equals(Collections.singletonList("MISSING-CLASS"))));
        }
    }

    @Test
    void brokenApiMetadataIsNotMistakenForAnAbsentClass() throws Exception {
        File broken = jar("broken-api.jar", Collections.singletonMap("org/bukkit/Api", new byte[]{0, 1, 2}));
        try (URLClassLoader api = new URLClassLoader(new URL[]{broken.toURI().toURL()}, null)) {
            assertThrows(RuntimeException.class,
                    () -> LinkageAudit.scan(jar("module.jar", references()), api, Collections.emptyList()));
        }
    }

    @Test
    void auditFailureDoesNotPreventTheOriginalModuleLoadingStep() throws Exception {
        Path invalid = temporary.resolve("invalid.jar");
        Files.write(invalid, new byte[]{0, 1, 2});
        List<LogRecord> records = new ArrayList<>();
        com.ultikits.ultitools.manager.PluginManager manager = org.mockito.Mockito.mock(
                com.ultikits.ultitools.manager.PluginManager.class);
        try (ModuleClassLoader loader = new ModuleClassLoader(new URL[]{invalid.toUri().toURL()},
                getClass().getClassLoader(), null)) {
            UltiTools.initModulesWithAudit(loader, manager, capture(records));
            org.mockito.Mockito.verify(manager).init(loader);
            assertEquals(1, records.size());
            assertTrue(records.get(0).getMessage().contains("Could not audit module"));
            assertNotNull(records.get(0).getThrown());
        }
    }

    @Test
    void internalAuditClassesExposeNoPublicTypes() {
        for (Class<?> type : new Class<?>[]{LinkageAudit.class, LinkageAudit.Finding.class, LinkageApiIndex.class}) {
            assertFalse(java.lang.reflect.Modifier.isPublic(type.getModifiers()));
        }
    }

    @Test
    void auditsOnlyEffectiveMultiReleaseClassBytes() throws Exception {
        for (boolean multiRelease : new boolean[]{false, true}) {
            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Multi-Release", String.valueOf(multiRelease));
            Path path = temporary.resolve("multi-" + multiRelease + ".jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
                for (int version : new int[]{0, 9, 99}) {
                    ClassWriter writer = caller("fixture/Multi");
                    writer.visitField(ACC_PUBLIC, "type", "Lorg/bukkit/Version" + version + ";", null, null).visitEnd();
                    String prefix = version == 0 ? "" : "META-INF/versions/" + version + "/";
                    output.putNextEntry(new JarEntry(prefix + "fixture/Multi.class"));
                    output.write(bytes(writer));
                    output.closeEntry();
                }
            }
            try (URLClassLoader api = api()) {
                List<LinkageAudit.Finding> findings = LinkageAudit.scan(path.toFile(), api, Collections.emptyList());
                assertEquals(1, findings.size());
                assertEquals("org/bukkit/Version" + (multiRelease ? 9 : 0), findings.get(0).reference.getOwner());
            }
        }
    }

    private byte[] inventory() {
        ClassWriter writer = caller("fixture/Inventory");
        String[] events = {"org/bukkit/event/entity/EntityMountEvent",
                "io/papermc/paper/event/player/PlayerPickItemEvent",
                "io/papermc/paper/event/player/PlayerOpenSignEvent",
                "io/papermc/paper/event/player/PlayerSwapWithEquipmentSlotEvent"};
        for (int i = 0; i < events.length; i++) {
            MethodVisitor handler = method(writer, "handler" + i, "(L" + events[i] + ";)V");
            for (int n = 0; n < (i == 0 ? 2 : 1); n++) {
                handler.visitMethodInsn(INVOKEVIRTUAL, events[i], i == 0 ? "getEntity" : "getPlayer",
                        i == 0 ? "()Lorg/bukkit/entity/Entity;" : "()Lorg/bukkit/entity/Player;", false);
            }
            finish(handler);
        }
        MethodVisitor method = method(writer, "calls", "()V");
        for (int i = 0; i < 20; i++) {
            method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/inventory/InventoryView", "getTopInventory",
                    "()Lorg/bukkit/inventory/Inventory;", true);
        }
        method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/inventory/InventoryView", "getCursor",
                "()Lorg/bukkit/inventory/ItemStack;", true);
        for (int i = 0; i < 4; i++) {
            method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/inventory/InventoryView", "setCursor",
                    "(Lorg/bukkit/inventory/ItemStack;)V", true);
        }
        method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/inventory/InventoryView", "setTitle", "(Ljava/lang/String;)V", true);
        method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/OfflinePlayer", "getLocation", "()Lorg/bukkit/Location;", true);
        finish(method);
        return bytes(writer);
    }

    private byte[] references() {
        ClassWriter writer = caller("fixture/References");
        writer.visitField(ACC_PUBLIC, "missing", "[[Lorg/bukkit/Missing;", null, null).visitEnd();
        MethodVisitor method = method(writer, "call", "()V");
        method.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/Api", "gone", "()V", false);
        method.visitFieldInsn(GETSTATIC, "org/bukkit/Api", "absentField", "I");
        method.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, "org/bukkit/Api", "handle", "()V", false));
        method.visitTypeInsn(CHECKCAST, "[Lorg/bukkit/Missing;");
        finish(method);
        return bytes(writer);
    }

    private URLClassLoader api() throws Exception {
        return new URLClassLoader(new URL[]{apiJar().toURI().toURL()}, null);
    }

    private File apiJar() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter parent = caller("org/bukkit/Api");
        parent.visitField(ACC_PUBLIC, "exists", "I", null, null).visitEnd();
        finish(method(parent, "exists", "()Ljava/lang/String;"));
        finish(method(parent, "<init>", "(I)V"));
        classes.put("org/bukkit/Api", bytes(parent));
        ClassWriter child = new ClassWriter(0);
        child.visit(V1_8, ACC_PUBLIC, "org/bukkit/Child", null, "org/bukkit/Api", null);
        classes.put("org/bukkit/Child", bytes(child));
        return jar("api.jar", classes);
    }

    private static List<String> problemNames(List<LinkageAudit.Finding> findings) {
        List<String> names = new ArrayList<>();
        for (LinkageAudit.Finding finding : findings) names.addAll(finding.problems);
        return names;
    }

    static Logger capture(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() { }
            public void close() { }
        });
        return logger;
    }

    private File jar(String name, byte[] value) throws Exception {
        return jar(name, Collections.singletonMap(new net.bytebuddy.jar.asm.ClassReader(value).getClassName(), value));
    }

    private File jar(String name, Map<String, byte[]> entries) throws Exception {
        Path path = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path.toFile();
    }

    private static ClassWriter caller(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor method(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        net.bytebuddy.jar.asm.Label label = new net.bytebuddy.jar.asm.Label();
        method.visitLabel(label);
        method.visitLineNumber(42, label);
        return method;
    }

    private static void finish(MethodVisitor method) {
        method.visitInsn(RETURN);
        method.visitMaxs(8, 8);
        method.visitEnd();
    }

    private static byte[] bytes(ClassWriter writer) {
        writer.visitEnd();
        return writer.toByteArray();
    }
}
