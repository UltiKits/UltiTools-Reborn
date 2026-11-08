package com.ultikits.ultitools.context;

import com.ultikits.ultitools.aop.AopProxyResolver;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SkippedBeanDiagnosticsTest implements Opcodes {
    private static final String BEAN = "fixture.skip.OptionalListener";
    private static final String BASE = "fixture.skip.BaseListener";
    private static final String MISSING = "io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent";
    @TempDir Path temporary;
    private final List<LogRecord> captured = new ArrayList<>();
    private Logger logger;
    private Level previousLevel;
    private Handler handler;

    public static class Survivor {
        public String ping() { return "pong"; }
    }

    @BeforeEach
    void capture() {
        logger = Logger.getLogger(SimpleContainer.class.getName());
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler = new Handler() {
            public void publish(LogRecord record) { captured.add(record); }
            public void flush() {
                // Records are stored immediately; nothing is buffered.
            }
            public void close() {
                // The capture handler owns no resources.
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void release() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }

    @Test
    void actualSkippedBeanNamesEveryLostHandlerWithoutReflectingItsBrokenMethods() throws Exception {
        writeFixture(MISSING);
        try (BlockingLoader loader = loader(MISSING)) {
            Class<?> bean = loader.loadClass(BEAN);
            assertThrows(NoClassDefFoundError.class, bean::getDeclaredMethods,
                    "reflection genuinely fails before diagnostics can enumerate handlers");
            SimpleContainer container = container(bean);
            assertDoesNotThrow(container::refresh);
            assertEquals("pong", ((Survivor) container.getBean("survivor")).ping());
            assertFalse(container.getSingletonValues().stream().anyMatch(value -> value.getClass() == bean));
            List<LogRecord> consequences = consequences();
            assertEquals(1, consequences.size());
            String message = consequences.get(0).getMessage();
            for (String required : Arrays.asList("skip-test-module", BEAN, MISSING,
                    "onMissing", "onJoin", "onMove", "onClick", "inheritedHandler")) {
                assertTrue(message.contains(required), "missing consequence detail: " + required + " in " + message);
            }
            assertFalse(message.contains("notAHandler"));
            assertTrue(Level.WARNING.equals(consequences.get(0).getLevel()));
            List<LogRecord> guidance = guidance();
            assertEquals(1, guidance.size());
            String advice = guidance.get(0).getMessage();
            for (String required : Arrays.asList("optional handlers", "separate class", "outside every",
                    "scanBasePackages", "string prefix", "Class.forName", "succeeds")) {
                assertTrue(advice.contains(required), "missing author guidance: " + required);
            }
            assertThrows(NoClassDefFoundError.class, () -> container.getBean("optionalListener"));
            assertEquals(1, consequences().size(), "memoized skip must not repeat consequences");
            assertEquals(1, guidance().size(), "memoized skip must not repeat author guidance");
            assertTrue(captured.stream().noneMatch(record -> record.getMessage().contains("Continuing module loading.")),
                    "actual skip diagnostics must not repeat pre-scan linkage findings");
        }
    }

    @Test
    void actualSkipHasAnIndependentActionableAuthorGuidanceWarning() throws Exception {
        writeFixture(MISSING);
        try (BlockingLoader loader = loader(MISSING)) {
            SimpleContainer container = container(loader.loadClass(BEAN));
            assertDoesNotThrow(container::refresh);
            assertEquals(1, guidance().size(), "an actual server-class skip needs author guidance");
            LogRecord record = guidance().get(0);
            assertEquals(Level.WARNING, record.getLevel());
            assertTrue(record.getMessage().contains("outside every configured scanBasePackages string prefix"));
            assertTrue(record.getMessage().contains("only after a Class.forName(name, false, loader) probe succeeds"));
        }
    }

    @Test
    void healthyBeanWithTheSameHandlersDoesNotEmitSkipConsequencesOrGuidance() throws Exception {
        writeFixture(MISSING);
        try (BlockingLoader loader = loader(null)) {
            Class<?> bean = loader.loadClass(BEAN);
            SimpleContainer container = container(bean);
            assertDoesNotThrow(container::refresh);
            assertTrue(container.getSingletonValues().stream().anyMatch(value -> value.getClass() == bean));
            assertTrue(consequences().isEmpty());
            assertTrue(guidance().isEmpty());
        }
    }

    @Test
    void absentFrameworkAndThirdPartyTypesKeepTheirOwnExistingSkipBehaviour() throws Exception {
        for (String absent : Arrays.asList("com.ultikits.ultitools.Removed", "third.party.Absent")) {
            writeFixture(absent);
            try (BlockingLoader loader = loader(absent)) {
                Class<?> bean = loader.loadClass(BEAN);
                SimpleContainer container = container(bean);
                assertDoesNotThrow(container::refresh);
                assertNotNull(container.getBean("survivor"));
                assertFalse(container.getSingletonValues().stream().anyMatch(value -> value.getClass() == bean));
                assertTrue(consequences().isEmpty());
                assertTrue(guidance().isEmpty());
            }
        }
    }

    private SimpleContainer container(Class<?> bean) {
        SimpleContainer container = new SimpleContainer();
        container.setAopProxyResolver(new AopProxyResolver());
        container.setDisplayName("skip-test-module");
        container.registerBean(bean);
        container.registerBean(Survivor.class);
        return container;
    }

    private List<LogRecord> consequences() {
        return captured.stream().filter(record -> record.getMessage().contains("unregistered @EventHandler methods"))
                .collect(Collectors.toList());
    }

    private List<LogRecord> guidance() {
        return captured.stream().filter(record -> record.getMessage().contains("optional handlers"))
                .collect(Collectors.toList());
    }

    private BlockingLoader loader(String blocked) throws Exception {
        return new BlockingLoader(new URL[]{temporary.toUri().toURL()}, getClass().getClassLoader(), blocked);
    }

    private void writeFixture(String missing) throws Exception {
        ClassWriter parent = type(BASE, "java.lang.Object");
        eventHandler(parent, "inheritedHandler", "org.bukkit.event.player.PlayerJoinEvent");
        write(BASE, parent);
        ClassWriter bean = type(BEAN, BASE);
        eventHandler(bean, "onMissing", missing);
        eventHandler(bean, "onJoin", "org.bukkit.event.player.PlayerJoinEvent");
        eventHandler(bean, "onMove", "org.bukkit.event.player.PlayerMoveEvent");
        eventHandler(bean, "onClick", "org.bukkit.event.inventory.InventoryClickEvent");
        MethodVisitor ordinary = bean.visitMethod(ACC_PUBLIC, "notAHandler", "()V", null, null);
        ordinary.visitCode();
        ordinary.visitInsn(RETURN);
        ordinary.visitMaxs(0, 1);
        ordinary.visitEnd();
        write(BEAN, bean);
    }

    private static ClassWriter type(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, name.replace('.', '/'), null, parent.replace('.', '/'),
                new String[]{"org/bukkit/event/Listener"});
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, parent.replace('.', '/'), "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return writer;
    }

    private static void eventHandler(ClassWriter writer, String name, String event) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, name, "(L" + event.replace('.', '/') + ";)V", null, null);
        method.visitAnnotation("Lorg/bukkit/event/EventHandler;", true).visitEnd();
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 2);
        method.visitEnd();
    }

    private void write(String name, ClassWriter writer) throws Exception {
        writer.visitEnd();
        Path file = temporary.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }

    private static final class BlockingLoader extends URLClassLoader {
        private final String blocked;

        BlockingLoader(URL[] urls, ClassLoader parent, String blocked) {
            super(urls, parent);
            this.blocked = blocked;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(blocked)) {
                throw new ClassNotFoundException(name);
            }
            if (!BEAN.equals(name) && !BASE.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        }
    }
}
