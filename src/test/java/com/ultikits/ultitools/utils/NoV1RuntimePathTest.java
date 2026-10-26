package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.PlayerEventManager;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Plan 22-24 Task 1, the fence of CLOUD-11 ("no v1 cloud path remains in use"; D-01, D-29, S-04, S-67, checker B-W6).
 * <p>
 * The public managers keep their protocol v1 senders in 6.3.1 because the public setters that feed them a
 * {@link UltiPanelWebSocketClient} ({@code ServerMonitorManager}, {@code CommandExecutionManager},
 * {@code FileOperationManager} and {@code ServerPropertiesManager#setWebSocketClient}, {@code LogStreamManager} and
 * {@code PlayerEventManager#initialize}) are 6.3.0 API that a PATCH release may not remove. They are removed, with the
 * senders behind them, in <b>6.4.0</b>. This class proves that they are unreachable from the framework until then:
 * <ul>
 *   <li><b>Static part.</b> A bytecode scan of every class of the framework jar ({@code target/classes}) with
 *   ByteBuddy's bundled ASM finds no {@code NEW} of the v1 client, and no call that hands a v1 client to another
 *   class (a {@code setWebSocketClient} call, or any call whose parameters include the v1 client type) except from a
 *   method that was itself handed one. Calls inside one class are a manager's own sender steps, not a hand-off. A v1 client therefore only ever enters the framework through one of the kept
 *   public entry points, whose inventory is pinned, and never originates in framework code. A probe class compiled
 *   with this test is the negative control: the same scan reports its construction and its setter call.</li>
 *   <li><b>Runtime part.</b> After a whole protocol v2 session on the in-process stub (welcome, telemetry, a
 *   command, a file listing, the properties, a reconnect and the teardown), every manager the framework reaches
 *   through {@link UltiTools}'s getters holds no v1 client.</li>
 * </ul>
 */
@DisplayName("No framework path can reach a protocol v1 client")
class NoV1RuntimePathTest {

    private static final String CLIENT = "com/ultikits/ultitools/websocket/UltiPanelWebSocketClient";
    private static final String CLIENT_DESC = "L" + CLIENT + ";";
    private static final String ROOT_PACKAGE = "com/ultikits/ultitools/";

    /**
     * The methods of the framework jar that receive a v1 client as a parameter: the kept public entry points and the
     * private steps behind them. All of them leave in 6.4.0 with the public setters (S-67). A new entry here is a new
     * way into the v1 senders and needs a decision, so the list is exact.
     */
    private static final String[] KEPT_V1_ENTRY_POINTS = {
        "com/ultikits/ultitools/manager/CommandExecutionManager#setWebSocketClient(" + CLIENT_DESC + ")V",
        "com/ultikits/ultitools/manager/FileOperationManager#setWebSocketClient(" + CLIENT_DESC + ")V",
        "com/ultikits/ultitools/manager/LogStreamManager#initialize(" + CLIENT_DESC + ")V",
        "com/ultikits/ultitools/manager/PlayerEventManager#getServerId(" + CLIENT_DESC + ")Ljava/lang/String;",
        "com/ultikits/ultitools/manager/PlayerEventManager#initialize(" + CLIENT_DESC + ")V",
        "com/ultikits/ultitools/manager/PlayerEventManager#sendPlayerEvent(" + CLIENT_DESC
                + "Lcom/google/gson/JsonObject;)V",
        "com/ultikits/ultitools/manager/ServerMonitorManager#setWebSocketClient(" + CLIENT_DESC + ")V",
        "com/ultikits/ultitools/manager/ServerPropertiesManager#setWebSocketClient(" + CLIENT_DESC + ")V",
        "com/ultikits/ultitools/manager/UltiPanelLogTransmitter#<init>(" + CLIENT_DESC + "Ljava/lang/String;)V",
        "com/ultikits/ultitools/manager/UltiPanelLogTransmitter#<init>(" + CLIENT_DESC
                + "Ljava/lang/String;Lcom/ultikits/ultitools/manager/UltiPanelLogTransmitter$AgentLineSink;)V",
    };

    @Nested
    @DisplayName("static: the compiled framework")
    class StaticFence {

        @Test
        @DisplayName("no framework class constructs a v1 client or hands one to another method")
        void noFrameworkClassConstructsOrInjectsAV1Client() throws IOException {
            Scan scan = Scan.of(frameworkClasses());

            assertThat(scan.classCount).as("classes scanned (control: the scan reads the jar)").isGreaterThan(300);
            assertThat(scan.findings).as("v1 client construction or injection in framework code").isEmpty();
        }

        @Test
        @DisplayName("a v1 client enters the framework only through the kept 6.3.0 entry points")
        void onlyTheKeptEntryPointsReceiveAV1Client() throws IOException {
            Scan scan = Scan.of(frameworkClasses());

            assertThat(scan.entryPoints).containsExactly(KEPT_V1_ENTRY_POINTS);
        }

        @Test
        @DisplayName("negative control: the scan reports a probe that constructs a client and calls a setter")
        void theScanReportsAProbeThatInjectsAClient() throws IOException {
            Scan scan = Scan.of(classBytes(V1InjectionProbe.class));

            assertThat(scan.findings).anySatisfy(finding -> assertThat(finding)
                    .contains("V1InjectionProbe#inject").contains("constructs " + CLIENT));
            assertThat(scan.findings).anySatisfy(finding -> assertThat(finding)
                    .contains("V1InjectionProbe#inject").contains("ServerMonitorManager.setWebSocketClient"));
            assertThat(scan.findings).anySatisfy(finding -> assertThat(finding)
                    .contains("V1InjectionProbe#fromField").contains("ServerMonitorManager.setWebSocketClient"));
            assertThat(scan.findings).noneSatisfy(finding -> assertThat(finding).contains("#passAlong"));
            assertThat(scan.findings).hasSize(3);
        }
    }

    @Nested
    @DisplayName("runtime: a whole v2 session on the stub")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    class RuntimeFence {

        @TempDir
        Path temp;

        private V2StubSession session;
        private final AtomicReference<ServerMonitorManager> monitor = new AtomicReference<>();
        private final AtomicReference<FileOperationManager> files = new AtomicReference<>();
        private final AtomicReference<ServerPropertiesManager> properties = new AtomicReference<>();
        private final AtomicReference<PlayerEventManager> playerEvents = new AtomicReference<>();
        private Object previousLogStream;

        @BeforeEach
        void setUp() throws Exception {
            MockBukkitHelper.ensureCleanState();
            // The JVM-wide log stream singleton: this test builds its own and puts the previous one back.
            previousLogStream = swapLogStreamInstance(null);
        }

        @AfterEach
        void tearDown() throws Exception {
            if (session != null) {
                session.close();
            }
            ServerMonitorManager started = monitor.get();
            if (started != null) {
                started.stopMonitoring();
            }
            swapLogStreamInstance(previousLogStream);
            MockBukkitHelper.safeUnmock();
        }

        @Test
        @DisplayName("after welcome, requests, a reconnect and the teardown no manager holds a v1 client")
        void noManagerHoldsAV1ClientAfterAWholeSession() throws Exception {
            ServerMock server = MockBukkit.mock();
            session = new V2StubSession(temp, server).realCommands();
            for (Capability capability : new Capability[] {Capability.COMMANDS, Capability.FILE_READ,
                    Capability.SERVER_PROPERTIES, Capability.MONITORING, Capability.LOGS, Capability.PLAYER_EVENTS}) {
                session.config().set(capability.getConfigPath(), true);
            }
            final File serverRoot = session.serverRoot().toFile();
            session.start(plugin -> {
                // The managers every framework path reaches through UltiTools' getters are real instances here,
                // built on first use (after the plugin is published, as their constructors read it).
                lenient().when(plugin.getServerMonitorManager())
                        .thenAnswer(invocation -> lazily(monitor, ServerMonitorManager::new));
                lenient().when(plugin.getFileOperationManager())
                        .thenAnswer(invocation -> lazily(files, FileOperationManager::new));
                lenient().when(plugin.getServerPropertiesManager())
                        .thenAnswer(invocation -> lazily(properties, () -> new ServerPropertiesManager(serverRoot)));
                lenient().when(plugin.getPlayerEventManager())
                        .thenAnswer(invocation -> lazily(playerEvents, PlayerEventManager::new));
                lenient().when(plugin.getLogStreamManager()).thenAnswer(invocation -> LogStreamManager.getInstance());
            });
            // The welcome's established listeners have run once its status frame arrives (22-23 test hygiene).
            session.reply("status", 1);
            for (String snapshot : new String[] {"players.snapshot", "plugins.snapshot", "worlds.snapshot"}) {
                session.reply(snapshot, 1);
            }
            JsonObject blocked = new JsonObject();
            blocked.addProperty("command", "op");
            session.send("command.execute", "say", blocked);
            session.reply("command.result", 1);
            session.send("files.list", "root", null);
            session.reply("files.list.result", 1);
            session.send("properties.get", "all", null);
            session.reply("properties.get.result", 1);
            assertNoManagerHoldsAV1Client("after the requests");

            PluginInitiationUtils.reinitWebSocket();
            session.reply("status", 2);
            assertNoManagerHoldsAV1Client("after a reconnect");

            PluginInitiationUtils.disableCloud();
            PluginInitiationUtils.stopWebsocket();
            assertNoManagerHoldsAV1Client("after the teardown");
        }

        private void assertNoManagerHoldsAV1Client(String when) throws Exception {
            UltiTools plugin = UltiTools.getInstance();
            Object[] managers = {plugin.getServerMonitorManager(), plugin.getCommandExecutionManager(),
                plugin.getFileOperationManager(), plugin.getServerPropertiesManager(),
                plugin.getPlayerEventManager(), plugin.getLogStreamManager()};
            for (Object manager : managers) {
                assertThat(manager).as("a real manager behind each getter").isNotNull();
                assertThat(v1Client(manager)).as("%s: %s's v1 client", when, manager.getClass().getSimpleName())
                        .isNull();
            }
            assertThat(readField(plugin.getLogStreamManager(), "logTransmitter"))
                    .as("%s: LogStreamManager's v1 transmitter", when).isNull();
        }
    }

    // ------------------------------------------------------------------ the scan

    /** One scan over a set of class files: what it read, what it found, and who receives a v1 client. */
    static final class Scan {
        int classCount;
        final List<String> findings = new ArrayList<>();
        final TreeSet<String> entryPointSet = new TreeSet<>();
        List<String> entryPoints = new ArrayList<>();

        static Scan of(List<byte[]> classes) {
            Scan scan = new Scan();
            for (byte[] bytes : classes) {
                scan.read(bytes);
            }
            scan.entryPoints = new ArrayList<>(scan.entryPointSet);
            return scan;
        }

        private void read(byte[] bytes) {
            classCount++;
            final ClassReader reader = new ClassReader(bytes);
            final String className = reader.getClassName();
            if (className.equals(CLIENT) || className.startsWith(CLIENT + "$")) {
                // The v1 client's own nest is the sender itself, not a path into it.
                return;
            }
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, final String method, final String desc,
                        String signature, String[] exceptions) {
                    final String where = className + "#" + method;
                    final boolean receivesClient = takesClient(desc);
                    if (receivesClient) {
                        entryPointSet.add(className + "#" + method + desc);
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.NEW && CLIENT.equals(type)) {
                                findings.add(where + " constructs " + CLIENT);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callee, String calleeDesc,
                                boolean isInterface) {
                            if (receivesClient) {
                                // Passing along a client this method was handed: the only way a v1 client can
                                // travel, so every chain starts at a kept entry point, outside the framework.
                                return;
                            }
                            if ("setWebSocketClient".equals(callee) || takesClient(calleeDesc)) {
                                if (isClientConstructor(owner, callee) || sameTopLevelClass(owner, className)) {
                                    // The NEW above is reported already; a call inside one class (a manager's own
                                    // sender steps) does not hand the client to anyone else.
                                    return;
                                }
                                findings.add(where + " hands a v1 client to "
                                        + owner.substring(owner.lastIndexOf('/') + 1) + "." + callee);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        private static boolean isClientConstructor(String owner, String callee) {
            return CLIENT.equals(owner) && "<init>".equals(callee);
        }

        private static boolean sameTopLevelClass(String owner, String caller) {
            return topLevel(owner).equals(topLevel(caller));
        }

        private static String topLevel(String internalName) {
            int nested = internalName.indexOf('$');
            return nested < 0 ? internalName : internalName.substring(0, nested);
        }

        private static boolean takesClient(String desc) {
            for (Type argument : Type.getArgumentTypes(desc)) {
                if (CLIENT_DESC.equals(argument.getDescriptor())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Every class file of the framework jar: the directory the compiled {@link UltiTools} was loaded from. */
    static List<byte[]> frameworkClasses() throws IOException {
        Path root;
        try {
            root = Paths.get(UltiTools.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        assertThat(root).as("the compiled framework is a class directory").isDirectory();
        assertThat(root.resolve(ROOT_PACKAGE + "UltiTools.class")).exists();
        List<byte[]> classes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Object path : paths.filter(p -> p.toString().endsWith(".class")).toArray()) {
                classes.add(Files.readAllBytes((Path) path));
            }
        }
        return classes;
    }

    /** The class file of {@code type} alone, read from the test class path. */
    static List<byte[]> classBytes(Class<?> type) throws IOException {
        List<byte[]> classes = new ArrayList<>();
        try (InputStream in = type.getClassLoader()
                .getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            assertThat(in).as("the probe's class file").isNotNull();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            classes.add(out.toByteArray());
        }
        return classes;
    }

    private static synchronized <T> T lazily(AtomicReference<T> holder, Supplier<T> create) {
        if (holder.get() == null) {
            holder.set(create.get());
        }
        return holder.get();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the JVM-wide log stream singleton
    private static Object swapLogStreamInstance(Object replacement) throws Exception {
        Field instance = LogStreamManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, replacement);
        return previous;
    }

    private static Object v1Client(Object manager) throws Exception {
        return readField(manager, "webSocketClient");
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reads the managers' private v1 client fields
    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * The negative control of the static scan: never run, only compiled and scanned. {@link #inject} constructs a
     * client and hands it to a setter, {@link #fromField} hands a stored client to a setter (the shape the framework
     * wiring had), and {@link #passAlong} only forwards the client it was handed (the shape of a kept entry point).
     */
    @SuppressWarnings("unused")
    static final class V1InjectionProbe {
        private UltiPanelWebSocketClient stored;

        void inject(ServerMonitorManager monitor) throws URISyntaxException {
            monitor.setWebSocketClient(new UltiPanelWebSocketClient("ws://probe.invalid/ws", "probe", "probe"));
        }

        void fromField(ServerMonitorManager monitor) {
            monitor.setWebSocketClient(stored);
        }

        void passAlong(ServerMonitorManager monitor, UltiPanelWebSocketClient client) {
            monitor.setWebSocketClient(client);
        }
    }
}
