package com.ultikits.ultitools;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ReflectionUtil;
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
    void startupDefersOutputUntilMainClassAndRealScanRootsAreKnown() throws Exception {
        MockBukkitHelper.ensureCleanState();
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            assertAll(() -> runtimeOrdering(true), () -> runtimeOrdering(false));
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }

    @SuppressWarnings("unchecked")
    private void runtimeOrdering(boolean annotated) throws Exception {
        String property = "ultitools.audit2.created";
        System.clearProperty(property);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter main = new ClassWriter(0);
        main.visit(V1_8, ACC_PUBLIC, "runtime/entry/Main", null,
                "com/ultikits/ultitools/abstracts/UltiToolsPlugin", null);
        if (annotated) {
            net.bytebuddy.jar.asm.AnnotationVisitor annotation = main.visitAnnotation(
                    "Lcom/ultikits/ultitools/annotations/UltiToolsModule;", true);
            net.bytebuddy.jar.asm.AnnotationVisitor roots = annotation.visitArray("scanBasePackages");
            roots.visit(null, "runtime.own");
            roots.visitEnd();
            net.bytebuddy.jar.asm.AnnotationVisitor markers = annotation.visitArray("scanBasePackageClasses");
            markers.visit(null, net.bytebuddy.jar.asm.Type.getObjectType("runtime/marker/Marker"));
            markers.visitEnd();
            annotation.visitEnd();
        }
        classes.put("runtime/entry/Main", bytes(main));
        classes.put("runtime/marker/Marker", bytes(caller("runtime/marker/Marker")));
        for (String name : Arrays.asList("runtime/own/Service", "runtime/ownExtra/Caller",
                "runtime/marker/Caller", "runtime/entry/Caller", "bundled/lib/Caller")) {
            ClassWriter writer = caller(name);
            if (name.endsWith("Service")) {
                writer.visitAnnotation("Lcom/ultikits/ultitools/annotations/Service;", true).visitEnd();
                MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
                constructor.visitCode();
                constructor.visitVarInsn(ALOAD, 0);
                constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                constructor.visitLdcInsn(property);
                constructor.visitLdcInsn("created");
                constructor.visitMethodInsn(INVOKESTATIC, "java/lang/System", "setProperty",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                constructor.visitInsn(POP);
                constructor.visitInsn(RETURN);
                constructor.visitMaxs(2, 1);
                constructor.visitEnd();
            }
            MethodVisitor unused = method(writer, "unused", "()V");
            unused.visitMethodInsn(INVOKESTATIC, "org/bukkit/Audit2Absent", "missing", "()V", false);
            finish(unused);
            classes.put(name, bytes(writer));
        }
        File module = jar("runtime-" + annotated + ".jar", classes);
        List<String> order = new ArrayList<>();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capture(records);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) {
                assertTrue(order.contains("main"));
                assertFalse(order.contains("scan"));
                assertNull(System.getProperty(property));
                order.add("audit");
            }
            public void flush() { /* No buffering. */ }
            public void close() { /* No resources. */ }
        });
        PluginManager manager = org.mockito.Mockito.spy(new PluginManager());
        SimpleContainer container =
                new SimpleContainer() {
                    @Override public void scanComponents(String... roots) {
                        assertFalse(records.isEmpty());
                        order.add("scan");
                        super.scanComponents(roots);
                    }
                };
        UltiTools framework = org.mockito.Mockito.mock(UltiTools.class);
        DependenceManagers dependencies = org.mockito.Mockito.mock(
                DependenceManagers.class);
        org.mockito.Mockito.when(dependencies.getContext()).thenReturn(new SimpleContainer());
        org.mockito.Mockito.when(framework.getDependenceManagers()).thenReturn(dependencies);
        org.mockito.Mockito.when(framework.getConfigManager()).thenReturn(org.mockito.Mockito.mock(
                ConfigManager.class));
        org.mockito.Mockito.when(framework.getDataStore()).thenReturn(org.mockito.Mockito.mock(
                DataStore.class, org.mockito.Mockito.CALLS_REAL_METHODS));
        java.lang.reflect.Method assemble = PluginManager.class.getDeclaredMethod("assemblePluginContainer",
                SimpleContainer.class,
                UltiToolsPlugin.class, Class.class, ClassLoader.class);
        try (org.mockito.MockedStatic<UltiTools> frameworkCalls = org.mockito.Mockito.mockStatic(
                    UltiTools.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                ModuleClassLoader loader = new ModuleClassLoader(new URL[]{module.toURI().toURL()},
                    getClass().getClassLoader(), null)) {
            frameworkCalls.when(UltiTools::getInstance).thenReturn(framework);
            java.lang.reflect.Field loaderField = PluginManager.class.getDeclaredField("classLoader");
            ReflectionUtil.setFieldValue(manager, loaderField, loader);
            org.mockito.Mockito.doAnswer(invocation -> {
                assertTrue(records.isEmpty(), "classification must wait for the normally loaded main class");
                Class<? extends UltiToolsPlugin> type = loader.loadClass(
                        "runtime.entry.Main").asSubclass(UltiToolsPlugin.class);
                order.add("main");
                UltiToolsPlugin plugin = org.mockito.Mockito.mock(type);
                org.mockito.Mockito.when(plugin.getPluginName()).thenReturn("runtime");
                ReflectionUtil.invoke(manager, assemble, container, plugin, type, loader);
                if (annotated) {
                    assertEquals("created", System.getProperty(property), "real scanned service must instantiate");
                    order.add("bean");
                }
                return null;
            }).when(manager).init(loader);
            UltiTools.initModulesWithAudit(loader, manager, logger);
            assertEquals("main", order.get(0));
            assertTrue(order.indexOf("audit") < order.indexOf("scan"));
            if (annotated) assertTrue(order.indexOf("scan") < order.indexOf("bean"));
            assertRuntimeScanRootDiagnostics(annotated, module, records);
            org.mockito.Mockito.verify(manager).getPluginScanPackages(
                    (Class<? extends UltiToolsPlugin>) loader.loadClass("runtime.entry.Main"));
        } finally {
            container.close();
            System.clearProperty(property);
        }
    }

    private static void assertRuntimeScanRootDiagnostics(boolean annotated, File module, List<LogRecord> records) {
        assertEquals(annotated ? 2 : 1, records.stream()
                .filter(r -> r.getLevel().equals(java.util.logging.Level.WARNING)).count());
        assertTrue(records.stream().anyMatch(r -> r.getLevel().equals(java.util.logging.Level.WARNING)
                && r.getMessage().contains(
                        annotated ? "runtime.own.Service.unused" : "runtime.entry.Caller.unused")));
        if (annotated) {
            assertTrue(records.stream().anyMatch(r -> r.getLevel().equals(java.util.logging.Level.WARNING)
                    && r.getMessage().contains("runtime.marker.Caller.unused")));
            assertFalse(records.stream().anyMatch(r -> r.getLevel().equals(java.util.logging.Level.WARNING)
                    && r.getMessage().contains("runtime.ownExtra.Caller.unused")));
            assertTrue(records.stream().anyMatch(r -> r.getLevel().equals(java.util.logging.Level.INFO)
                    && r.getMessage().equals(summary(module.getName(), 1, "runtime.ownExtra"))));
        }
    }

    @Test
    void startupPrescanUsesTheLoadersRuleSnapshotWithoutChangingDefinitionBytes() throws Exception {
        ClassWriter writer = caller("fixture/Loadable");
        MethodVisitor broken = method(writer, "unused", "()V");
        broken.visitMethodInsn(INVOKESTATIC, "org/bukkit/Absent", "missing", "()V", false);
        finish(broken);
        byte[] original = bytes(writer);
        File module = jar("loadable.jar", original);
        List<LogRecord> records = new ArrayList<>();
        List<byte[]> defined = new ArrayList<>();
        PluginManager manager = org.mockito.Mockito.mock(
                PluginManager.class);
        try (ModuleClassLoader loader = new ModuleClassLoader(new URL[]{module.toURI().toURL()},
                getClass().getClassLoader(), null, Collections.emptyList(), (name, value) -> defined.add(value))) {
            org.mockito.Mockito.doAnswer(invocation -> {
                assertTrue(records.isEmpty(), "pre-scan must not guess ownership");
                Class<?> mainClass = loader.loadClass("fixture.Loadable");
                loader.reportModule(mainClass, new String[]{"fixture"}, capture(records));
                return null;
            }).when(manager).init(loader);
            UltiTools.initModulesWithAudit(loader, manager, capture(records));
            org.mockito.Mockito.verify(manager).init(loader);
            assertArrayEquals(original, defined.get(0), "audit cannot reencode module bytes");
            loader.auditModules(capture(records));
            loader.reportModule(loader.loadClass("fixture.Loadable"), new String[]{"fixture"}, capture(records));
            assertEquals(1, records.size(), "module JAR is audited and reported once per loader");
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
            loader.reportModule(loader.loadClass("fixture.Loadable"), new String[]{"fixture"}, capture(covered));
            assertTrue(covered.isEmpty());
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
        PluginManager manager = org.mockito.Mockito.mock(
                PluginManager.class);
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

    @Test
    void offlineOwnRootsKeepWarningsAndSummarizeBundledPackages() throws Exception {
        File module = scopeJar();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(1, LinkageAudit.run(new String[]{"--api", apiJar().getPath(), "--own", "app/",
                module.getPath()}, new PrintStream(output)));
        List<String> lines = Arrays.asList(output.toString("UTF-8").trim().split("\\R"));
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).startsWith("Module 'scope.jar': app.Main."));
        assertTrue(lines.get(0).endsWith("Continuing module loading."));
        assertTrue(lines.contains(summary("scope.jar", 2, "com.github.alpha")));
        assertTrue(lines.contains(summary("scope.jar", 1, "com.github.beta")));
        assertTrue(lines.contains(summary("scope.jar", 1, "other")));
        assertFalse(output.toString("UTF-8").contains("com.github.alpha.deep.Caller."));
    }

    @Test
    void offlineMultipleOwnRootsAreAdditive() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(1, LinkageAudit.run(new String[]{"--api", apiJar().getPath(), "--own", "app/",
                "--own", "other/", scopeJar().getPath()}, new PrintStream(output)));
        String text = output.toString("UTF-8");
        assertEquals(2, Arrays.stream(text.trim().split("\\R")).filter(line -> line.startsWith("Module '")).count());
        assertTrue(text.contains("other.Main.<field> missing"));
        assertFalse(text.contains("bundled library other"));
    }

    @Test
    void offlineThirdPartyOnlyReturnsZeroAndStillPrintsSummaries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, LinkageAudit.run(new String[]{"--api", apiJar().getPath(), "--own", "absent/",
                scopeJar().getPath()}, new PrintStream(output)));
        String text = output.toString("UTF-8");
        assertFalse(text.contains("Continuing module loading."));
        assertTrue(text.contains(summary("scope.jar", 2, "com.github.alpha")));
        assertEquals(4, Arrays.stream(text.trim().split("\\R")).count());
    }

    @Test
    void offlineDefaultStillTreatsEveryClassAsOwn() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(1, LinkageAudit.run(new String[]{"--api", apiJar().getPath(), scopeJar().getPath()},
                new PrintStream(output)));
        assertEquals(5, output.toString("UTF-8").trim().split("\\R").length);
        assertFalse(output.toString("UTF-8").contains("bundled library"));
    }

    @Test
    void resolvesMembersThroughOwnersPresentOnlyInTheSubjectJar() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter owner = new ClassWriter(0);
        owner.visit(V1_8, ACC_PUBLIC, "org/bukkit/SubjectChild", null, "org/bukkit/Api", null);
        classes.put("org/bukkit/SubjectChild", bytes(owner));
        ClassWriter caller = caller("app/Caller");
        MethodVisitor call = method(caller, "call", "()V");
        call.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/SubjectChild", "exists", "()Ljava/lang/String;", false);
        call.visitFieldInsn(GETFIELD, "org/bukkit/SubjectChild", "exists", "I");
        call.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, "org/bukkit/SubjectChild", "exists", "()Ljava/lang/String;", false));
        finish(call);
        classes.put("app/Caller", bytes(caller));
        try (URLClassLoader api = api()) {
            assertTrue(LinkageAudit.scan(jar("subject-owner.jar", classes), api, Collections.emptyList()).isEmpty());
        }
    }

    @Test
    void subjectMetadataUsesParentFirstPrecedenceAndEffectiveMultiReleaseVariant() throws Exception {
        ClassWriter caller = caller("app/Caller");
        MethodVisitor call = method(caller, "call", "()V");
        call.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/SubjectChild", "exists", "()Ljava/lang/String;", false);
        call.visitFieldInsn(GETFIELD, "org/bukkit/SubjectChild", "exists", "I");
        finish(call);
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path path = temporary.resolve("subject-multi.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("app/Caller.class"));
            output.write(bytes(caller));
            output.closeEntry();
            output.putNextEntry(new JarEntry("org/bukkit/Api.class"));
            output.write(bytes(caller("org/bukkit/Api"))); // Must not shadow the supplied API's members.
            output.closeEntry();
            for (int version : new int[]{0, 9, 99}) {
                ClassWriter owner = new ClassWriter(0);
                owner.visit(V1_8, ACC_PUBLIC, "org/bukkit/SubjectChild", null,
                        version == 9 ? "org/bukkit/Api" : "org/bukkit/MissingParent", null);
                String prefix = version == 0 ? "" : "META-INF/versions/" + version + "/";
                output.putNextEntry(new JarEntry(prefix + "org/bukkit/SubjectChild.class"));
                output.write(bytes(owner));
                output.closeEntry();
            }
        }
        try (URLClassLoader api = api()) {
            assertTrue(LinkageAudit.scan(path.toFile(), api, Collections.emptyList()).isEmpty());
        }
    }

    @Test
    void javacSwitchMapMissingConstantIsGuardedInStartupAndOfflineAudits() throws Exception {
        Map<String, byte[]> compiled = compileFixture("switch", "return value == null ? 0 : switchValue(value);");
        File module = jar("switch.jar", compiled);
        File oldApi = enumApiJar();
        try (URLClassLoader api = new URLClassLoader(new URL[]{oldApi.toURI().toURL()}, null)) {
            assertTrue(LinkageAudit.scan(module, api, Collections.emptyList()).isEmpty());
            List<LogRecord> records = new ArrayList<>();
            try (ModuleClassLoader loader = new ModuleClassLoader(new URL[]{module.toURI().toURL()}, api,
                    null, Collections.emptyList(), null)) {
                loader.auditModules(capture(records));
                loader.reportModule(loader.loadClass("fixture.Guarded"), new String[]{"fixture"}, capture(records));
            }
            assertTrue(records.isEmpty(), "startup and offline must share the same guarded-field rule");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, LinkageAudit.run(new String[]{"--api", oldApi.getPath(), "--own", "fixture/",
                module.getPath()}, new PrintStream(output)));
        assertEquals("", output.toString("UTF-8"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NoSuchFieldError", "IncompatibleClassChangeError",
            "LinkageError", "Error", "Throwable"})
    void javacExplicitSupertypeCatchesGuardOnlyMissingGetstaticFields(String exception) throws Exception {
        Map<String, byte[]> compiled = compileFixture(exception,
                "try { return org.bukkit.FutureEnum.MISSING.ordinal(); } catch (" + exception
                        + " ignored) { return -1; }");
        try (URLClassLoader api = new URLClassLoader(new URL[]{enumApiJar().toURI().toURL()}, null)) {
            assertTrue(LinkageAudit.scan(jar(exception + ".jar", compiled), api, Collections.emptyList()).isEmpty());
        }
    }

    @Test
    void javacUnguardedAndFinallyOnlyReadsStillReportMissingFields() throws Exception {
        for (String body : Arrays.asList("return org.bukkit.FutureEnum.MISSING.ordinal();",
                "try { return org.bukkit.FutureEnum.MISSING.ordinal(); } finally { System.nanoTime(); }")) {
            Map<String, byte[]> compiled = compileFixture("control" + body.length(), body);
            if (body.contains("finally")) {
                List<String> catchTypes = new ArrayList<>();
                new net.bytebuddy.jar.asm.ClassReader(compiled.get("fixture/Guarded")).accept(
                        new net.bytebuddy.jar.asm.ClassVisitor(ASM9) {
                            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                    String signature, String[] exceptions) {
                                return new MethodVisitor(ASM9) {
                                    @Override public void visitTryCatchBlock(net.bytebuddy.jar.asm.Label start,
                                            net.bytebuddy.jar.asm.Label end, net.bytebuddy.jar.asm.Label handler,
                                            String type) { catchTypes.add(type); }
                                };
                            }
                        }, 0);
                assertTrue(catchTypes.contains(null), "javac finally must exercise an actual type-0 handler");
            }
            try (URLClassLoader api = new URLClassLoader(new URL[]{enumApiJar().toURI().toURL()}, null)) {
                List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("control.jar", compiled), api,
                        Collections.emptyList());
                assertEquals(1, findings.size());
                assertEquals(Collections.singletonList("MISSING-FIELD"), findings.get(0).problems);
                assertEquals("MISSING", findings.get(0).reference.getName());
            }
        }
    }

    @Test
    void guardRangesAreStartInclusiveEndExclusiveAndPreserveOtherReferences() throws Exception {
        ClassWriter writer = caller("fixture/Ranges");
        MethodVisitor call = method(writer, "call", "()V");
        net.bytebuddy.jar.asm.Label start = new net.bytebuddy.jar.asm.Label();
        net.bytebuddy.jar.asm.Label end = new net.bytebuddy.jar.asm.Label();
        call.visitTryCatchBlock(start, end, end, "java/lang/NoSuchFieldError");
        call.visitFieldInsn(GETSTATIC, "org/bukkit/Api", "sameField", "I");
        call.visitLabel(start);
        call.visitFieldInsn(GETSTATIC, "org/bukkit/Api", "sameField", "I");
        call.visitFieldInsn(GETFIELD, "org/bukkit/Api", "instance", "I");
        call.visitFieldInsn(PUTSTATIC, "org/bukkit/Api", "write", "I");
        call.visitFieldInsn(PUTFIELD, "org/bukkit/Api", "instanceWrite", "I");
        call.visitLdcInsn(new Handle(H_GETSTATIC, "org/bukkit/Api", "handle", "I", false));
        call.visitMethodInsn(INVOKESTATIC, "org/bukkit/Api", "missingMethod", "()V", false);
        call.visitFieldInsn(GETSTATIC, "org/bukkit/Missing", "owner", "I");
        call.visitFieldInsn(GETSTATIC, "org/bukkit/Api", "descriptor", "Lorg/bukkit/MissingType;");
        call.visitLabel(end);
        call.visitFieldInsn(GETSTATIC, "org/bukkit/Api", "sameField", "I");
        finish(call);
        // A different method must not inherit a guard from the previous method.
        MethodVisitor other = method(writer, "other", "()V");
        other.visitFieldInsn(GETSTATIC, "org/bukkit/Api", "other", "I");
        finish(other);
        try (URLClassLoader api = api()) {
            List<LinkageAudit.Finding> findings = LinkageAudit.scan(jar("ranges.jar", bytes(writer)), api,
                    Collections.emptyList());
            List<String> names = new ArrayList<>();
            for (LinkageAudit.Finding finding : findings) names.add(finding.reference.getName());
            assertEquals(Arrays.asList("sameField", "instance", "write", "instanceWrite", "handle", "missingMethod",
                    "owner", null, "sameField", "other"), names);
            assertEquals(2, Collections.frequency(problemNames(findings), "MISSING-CLASS"));
            assertEquals(1, Collections.frequency(problemNames(findings), "MISSING-METHOD"));
        }
    }

    @Test
    void unrelatedCatchTypeDoesNotGuardMissingGetstatic() throws Exception {
        Map<String, byte[]> compiled = compileFixture("unrelated",
                "try { return org.bukkit.FutureEnum.MISSING.ordinal(); } catch (RuntimeException ignored) { return -1; }");
        try (URLClassLoader api = new URLClassLoader(new URL[]{enumApiJar().toURI().toURL()}, null)) {
            assertEquals(1, LinkageAudit.scan(jar("unrelated.jar", compiled), api, Collections.emptyList()).size());
        }
    }

    private Map<String, byte[]> compileFixture(String directory, String body) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(directory));
        Path enumSource = root.resolve("org/bukkit/FutureEnum.java");
        Path callerSource = root.resolve("fixture/Guarded.java");
        Files.createDirectories(enumSource.getParent());
        Files.createDirectories(callerSource.getParent());
        Files.write(enumSource, "package org.bukkit; public enum FutureEnum { PRESENT, MISSING }"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(callerSource, ("package fixture; public class Guarded {"
                + " public static int read(org.bukkit.FutureEnum value) { " + body + " }"
                + (body.contains("switchValue") ? " static int switchValue(org.bukkit.FutureEnum value) { switch (value) {"
                        + " case PRESENT: return 1; case MISSING: return 2; default: return 0; } }" : "") + " }")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests require the configured JDK toolchain");
        assertEquals(0, compiler.run(null, null, null, "-source", "8", "-target", "8", "-g", "-proc:none",
                "-d", root.toString(), enumSource.toString(), callerSource.toString()));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("fixture/Guarded", Files.readAllBytes(root.resolve("fixture/Guarded.class")));
        if (body.contains("switchValue")) {
            classes.put("fixture/Guarded$1", Files.readAllBytes(root.resolve("fixture/Guarded$1.class")));
        }
        return classes;
    }

    private File enumApiJar() throws Exception {
        // Keep javac's real enum methods, but remove the future constant from the API index.
        Path enumClass = temporary.resolve("switch/org/bukkit/FutureEnum.class");
        if (!Files.exists(enumClass)) compileFixture("switch", "return switchValue(value);");
        net.bytebuddy.jar.asm.ClassReader reader = new net.bytebuddy.jar.asm.ClassReader(Files.readAllBytes(enumClass));
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new net.bytebuddy.jar.asm.ClassVisitor(ASM9, writer) {
            @Override public net.bytebuddy.jar.asm.FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                return "MISSING".equals(name) ? null : super.visitField(access, name, descriptor, signature, value);
            }
        }, 0);
        return jar("enum-api.jar", writer.toByteArray());
    }

    private File scopeJar() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("com/github/alpha/Root", bytes(caller("com/github/alpha/Root")));
        for (String name : Arrays.asList("app/Main", "other/Main", "com/github/alpha/deep/Caller",
                "com/github/alpha/second/Caller", "com/github/beta/Caller")) {
            ClassWriter writer = caller(name);
            writer.visitField(ACC_PUBLIC, "missing", "Lorg/bukkit/Absent;", null, null).visitEnd();
            classes.put(name, bytes(writer));
        }
        return jar("scope.jar", classes);
    }

    private static String summary(String module, int count, String library) {
        return module + ": " + count + " unresolved references in bundled library " + library
                + " (not checked per reference; such libraries usually guard platform-specific code at run time)";
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
        // API members are instance members built with legal flags: a static <init> is a
        // ClassFormatError as soon as any probe loads org/bukkit/Api.
        MethodVisitor exists = parent.visitMethod(ACC_PUBLIC, "exists", "()Ljava/lang/String;", null, null);
        exists.visitCode();
        exists.visitInsn(ACONST_NULL);
        exists.visitInsn(ARETURN);
        exists.visitMaxs(1, 1);
        exists.visitEnd();
        MethodVisitor constructor = parent.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 2);
        constructor.visitEnd();
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
            public void flush() {
                // Records are stored immediately; nothing is buffered.
            }
            public void close() {
                // The capture handler owns no resources.
            }
        });
        return logger;
    }

    private File jar(String name, byte[] value) throws Exception {
        return jar(name, Collections.singletonMap(new net.bytebuddy.jar.asm.ClassReader(value).getClassName(), value));
    }

    private File jar(String name, Map<String, byte[]> entries) throws Exception {
        Path path = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            java.util.Set<String> directories = new java.util.HashSet<>();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String key = entry.getKey();
                for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                    String directory = key.substring(0, slash + 1);
                    if (directories.add(directory)) {
                        output.putNextEntry(new JarEntry(directory));
                        output.closeEntry();
                    }
                }
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
