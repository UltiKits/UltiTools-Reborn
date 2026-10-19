package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * The panel responder registry must exist before any module loads, and must never be replaced while
 * the framework is enabled (Phase 20.4, C42). A module may claim a panel responder from
 * {@code registerSelf()} or a {@code @PostConstruct} method; {@code PluginManager} attributes that
 * claim to the loading module only when {@link UltiTools#getPanelResponderRegistry()} is not
 * {@code null}, and a registry created after module loading would also drop every claim made
 * before it.
 *
 * <p>{@link UltiTools#onEnable()} cannot run in a unit test: it needs a live Bukkit server, a data
 * store, the module folder and the cloud login. What can be checked is the artefact that decides
 * the order — the compiled {@code UltiTools} class. This test reads it with the ASM copy ByteBuddy
 * ships (already on the test class path), walks {@code onEnable} in instruction order, and follows
 * each call to a method declared in {@code UltiTools} one level deep, which is where every start-up
 * step lives ({@code initDataStore}, {@code initPluginModules}, {@code initWebSocketManagers}, ...).
 * It then checks where the {@code PanelResponderRegistry} constructor runs relative to module
 * loading. Positive controls assert that the walk finds the module-loading and WebSocket-manager
 * calls at all, so a rename fails loudly instead of letting the order checks pass vacuously.
 */
@DisplayName("UltiTools start-up order: the panel responder registry exists before modules load (C42)")
class UltiToolsStartupOrderTest {

    private static final String ULTITOOLS = "com/ultikits/ultitools/UltiTools";
    private static final String REGISTRY = "com/ultikits/ultitools/websocket/PanelResponderRegistry";
    private static final String REGISTRY_FIELD = "panelResponderRegistry";

    /** Every method of the compiled UltiTools class, by name plus descriptor, with its events in order. */
    private static Map<String, List<Event>> methods;

    @BeforeAll
    static void readCompiledClass() throws IOException {
        methods = readMethods();
    }

    @Test
    @DisplayName("positive control: the walk over onEnable finds the module-loading and WebSocket-manager calls")
    void walkFindsModuleLoadingAndWebSocketManagerCalls() {
        List<Event> walk = onEnableWalk();

        assertThat(indexOfCall(walk, "initDependencies")).as("onEnable calls initDependencies").isNotNegative();
        assertThat(indexOfCall(walk, "initPluginModules")).as("onEnable calls initPluginModules").isNotNegative();
        assertThat(indexOfCall(walk, "initWebSocketManagers")).as("onEnable calls initWebSocketManagers")
                .isNotNegative();
        assertThat(findMethod("initWebSocketManagers")).as("UltiTools declares initWebSocketManagers").isNotEmpty();
    }

    @Test
    @DisplayName("the registry is constructed after the dependency check and before initPluginModules")
    void registryIsConstructedBeforeModulesLoad() {
        List<Event> walk = onEnableWalk();
        int dependencies = indexOfCall(walk, "initDependencies");
        int modules = indexOfCall(walk, "initPluginModules");
        int construction = indexOfConstruction(walk);

        assertThat(modules).as("onEnable calls initPluginModules").isNotNegative();
        assertThat(construction).as("onEnable (one call level deep) constructs the PanelResponderRegistry")
                .isNotNegative();
        assertThat(construction)
                .as("PanelResponderRegistry constructed at %s must run before initPluginModules at %s",
                        walk.get(construction), walk.get(modules))
                .isLessThan(modules);
        assertThat(construction)
                .as("PanelResponderRegistry constructed at %s must run after initDependencies at %s, so an"
                        + " early return creates nothing", walk.get(construction), walk.get(dependencies))
                .isGreaterThan(dependencies);
    }

    @Test
    @DisplayName("UltiTools constructs the registry at exactly one place and assigns the field at exactly one place")
    void registryIsConstructedAndAssignedAtExactlyOnePlace() {
        List<Event> constructions = new ArrayList<>();
        List<Event> assignments = new ArrayList<>();
        for (List<Event> events : methods.values()) {
            for (Event event : events) {
                if (event.isRegistryConstruction()) {
                    constructions.add(event);
                }
                if (event.isRegistryAssignment()) {
                    assignments.add(event);
                }
            }
        }

        assertThat(constructions).as("PanelResponderRegistry construction sites in UltiTools").hasSize(1);
        assertThat(assignments).as("assignments of UltiTools.panelResponderRegistry").hasSize(1);
        assertThat(assignments.get(0).method).as("the assignment sits in the constructing method")
                .isEqualTo(constructions.get(0).method);
    }

    @Test
    @DisplayName("initWebSocketManagers neither constructs the registry nor assigns the field")
    void initWebSocketManagersLeavesTheRegistryAlone() {
        List<Event> events = findMethod("initWebSocketManagers");

        assertThat(events).as("UltiTools declares initWebSocketManagers").isNotEmpty();
        assertThat(events).as("initWebSocketManagers must not construct a PanelResponderRegistry")
                .noneMatch(Event::isRegistryConstruction);
        assertThat(events).as("initWebSocketManagers must not assign panelResponderRegistry")
                .noneMatch(Event::isRegistryAssignment);
    }

    // ---- compiled-class walk ----

    /** onEnable's events with each call to a method declared in UltiTools expanded one level deep. */
    private static List<Event> onEnableWalk() {
        List<Event> onEnable = methods.get("onEnable()V");
        assertThat(onEnable).as("compiled UltiTools declares onEnable()V").isNotNull().isNotEmpty();
        List<Event> walk = new ArrayList<>();
        for (Event event : onEnable) {
            walk.add(event);
            if (event.isOwnCall()) {
                List<Event> callee = methods.get(event.name + event.desc);
                if (callee != null) {
                    walk.addAll(callee);
                }
            }
        }
        return walk;
    }

    private static List<Event> findMethod(String name) {
        for (Map.Entry<String, List<Event>> entry : methods.entrySet()) {
            if (entry.getKey().startsWith(name + "(")) {
                return entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    private static int indexOfCall(List<Event> walk, String name) {
        for (int i = 0; i < walk.size(); i++) {
            Event event = walk.get(i);
            if (event.isOwnCall() && event.name.equals(name) && "onEnable".equals(event.method)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfConstruction(List<Event> walk) {
        for (int i = 0; i < walk.size(); i++) {
            if (walk.get(i).isRegistryConstruction()) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, List<Event>> readMethods() throws IOException {
        final Map<String, List<Event>> result = new LinkedHashMap<>();
        ClassLoader loader = UltiToolsStartupOrderTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(ULTITOOLS + ".class")) {
            assertThat(in).as("compiled %s.class on the test class path", ULTITOOLS).isNotNull();
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, final String name, String desc, String signature,
                        String[] exceptions) {
                    final List<Event> events = new ArrayList<>();
                    result.put(name + desc, events);
                    return new MethodVisitor(Opcodes.ASM9) {
                        private int line = -1;

                        @Override
                        public void visitLineNumber(int lineNumber, Label start) {
                            line = lineNumber;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callee, String calleeDesc,
                                boolean isInterface) {
                            events.add(new Event(name, line, opcode, owner, callee, calleeDesc));
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                            events.add(new Event(name, line, opcode, owner, field, fieldDesc));
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return result;
    }

    /** One method call or field access inside a method of UltiTools, with its source line. */
    private static final class Event {
        private final String method;
        private final int line;
        private final int opcode;
        private final String owner;
        private final String name;
        private final String desc;

        Event(String method, int line, int opcode, String owner, String name, String desc) {
            this.method = method;
            this.line = line;
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        boolean isOwnCall() {
            return (opcode == Opcodes.INVOKESPECIAL || opcode == Opcodes.INVOKEVIRTUAL
                    || opcode == Opcodes.INVOKESTATIC) && ULTITOOLS.equals(owner) && !"<init>".equals(name);
        }

        boolean isRegistryConstruction() {
            return opcode == Opcodes.INVOKESPECIAL && REGISTRY.equals(owner) && "<init>".equals(name);
        }

        boolean isRegistryAssignment() {
            return opcode == Opcodes.PUTFIELD && ULTITOOLS.equals(owner) && REGISTRY_FIELD.equals(name);
        }

        @Override
        public String toString() {
            return "UltiTools." + method + " line " + line + " (" + owner.substring(owner.lastIndexOf('/') + 1)
                    + "." + name + ")";
        }
    }
}
