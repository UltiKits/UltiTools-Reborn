package com.ultikits.ultitools.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * The 6.3.1 deprecations of the protocol v1 cloud surface, read from the generated ledger
 * {@code compatibility/deprecations.json} (plan 22-25, D-29, S-04, S-68).
 * <p>
 * The ledger is what {@code DeprecationRegistryGenerator} writes during {@code mvn verify} from the
 * {@code @Deprecated} annotations and the {@code @removeIn} javadoc tags, so this test proves the
 * generated artefact rather than the source text.
 * <p>
 * {@link #EXPECTED} is the confirmed inventory: the kept-but-unreachable v1 entry points 22-24
 * pinned, every public member of the classes only the v1 cloud used, the v1 senders and handlers
 * behind those entry points (S-67), and the token-era members 22-06 already deprecated. A key that
 * is missing from the ledger, or a 6.3.1 entry that is not in the list, fails the build; entries of
 * earlier milestones are told apart by their {@code since}. Each member's 6.3.1 behaviour is pinned
 * in its own package's tests (see the 22-25 summary for the table).
 */
@DisplayName("v1 cloud deprecations in the generated ledger (6.3.1)")
class V1CloudDeprecationTest {

    private static final String SINCE = "6.3.1";
    private static final String REMOVE_IN = "6.4.0";

    private static final String CLIENT = "com.ultikits.ultitools.websocket.UltiPanelWebSocketClient";
    private static final String JSON = "com.google.gson.JsonObject";
    private static final String STRING = "java.lang.String";
    private static final String SMM = "com.ultikits.ultitools.manager.ServerMonitorManager";
    private static final String CEM = "com.ultikits.ultitools.manager.CommandExecutionManager";
    private static final String FOM = "com.ultikits.ultitools.manager.FileOperationManager";
    private static final String SPM = "com.ultikits.ultitools.manager.ServerPropertiesManager";
    private static final String LSM = "com.ultikits.ultitools.manager.LogStreamManager";
    private static final String PEM = "com.ultikits.ultitools.manager.PlayerEventManager";
    private static final String HRU = "com.ultikits.ultitools.utils.HttpRequestUtils";
    private static final String TOKEN = "com.ultikits.ultitools.entities.TokenEntity";
    private static final String UCR = "com.ultikits.ultitools.utils.UltiCloudRequests";

    /** Every 6.3.1 deprecation key the ledger must hold, and nothing else with {@code since} 6.3.1. */
    private static final List<String> EXPECTED = Arrays.asList(
        // Token era, deprecated by 22-06.
        TOKEN,
        "com.ultikits.ultitools.utils.CloudAuthManager#login(java.lang.Runnable,java.util.function.Consumer,"
            + "java.lang.Runnable,java.util.function.Consumer,java.util.function.Consumer)",
        "com.ultikits.ultitools.utils.CloudAuthManager$CloudStatus#getExpirationDate()",
        "com.ultikits.ultitools.utils.CloudAuthManager$CloudStatus#getUserName()",
        "com.ultikits.ultitools.utils.PluginInitiationUtils#startTokenRefreshScheduler()",
        // Whole v1 types.
        CLIENT,
        "com.ultikits.ultitools.websocket.ExponentialBackoffStrategy",
        "com.ultikits.ultitools.websocket.ExponentialBackoffStrategy$Builder",
        "com.ultikits.ultitools.websocket.ReconnectStrategy",
        "com.ultikits.ultitools.utils.SimpleHttpClient",
        "com.ultikits.ultitools.utils.SimpleHttpClient$Response",
        UCR,
        UCR + "$Result",
        UCR + "$Outcome",
        UCR + "#post(java.lang.String,java.lang.String)",
        UCR + "#get(java.lang.String,java.util.Map)",
        "com.ultikits.ultitools.entities.vo.ServerEntityVO",
        "com.ultikits.ultitools.entities.ApiResponse",
        // The protected v1 REST calls (the class stays: its base-URL helper serves protocol v2).
        HRU + "#getServerByUUID(java.lang.String," + TOKEN + ")",
        HRU + "#registerServer(java.lang.String,java.lang.String,int,java.lang.String,boolean," + TOKEN + ")",
        HRU + "#updateServer(java.lang.String,int,java.lang.String,boolean," + TOKEN + ")",
        // The entry points that accept a v1 client (22-24 inventory).
        SMM + "#setWebSocketClient(" + CLIENT + ")",
        CEM + "#setWebSocketClient(" + CLIENT + ")",
        FOM + "#setWebSocketClient(" + CLIENT + ")",
        SPM + "#setWebSocketClient(" + CLIENT + ")",
        LSM + "#initialize(" + CLIENT + ")",
        PEM + "#initialize(" + CLIENT + ")",
        "com.ultikits.ultitools.manager.UltiPanelLogTransmitter#UltiPanelLogTransmitter(" + CLIENT + "," + STRING + ")",
        // The v1 senders and handlers behind them (S-67).
        SMM + "#startMonitoring()",
        SMM + "#stopMonitoring()",
        SMM + "#isMonitoring()",
        SMM + "#sendServerStatus()",
        SMM + "#sendServerStatusWithRequestId(" + STRING + ")",
        SMM + "#sendMetricsData()",
        SMM + "#sendMetricsDataWithRequestId(" + STRING + ")",
        SMM + "#sendPlayerEvent(" + STRING + ",org.bukkit.entity.Player," + JSON + ")",
        CEM + "#executeCommand(" + JSON + ")",
        CEM + "$CommandResult",
        FOM + "#handleFileOperation(" + JSON + ")",
        SPM + "#handleServerProperties(" + JSON + ")",
        LSM + "#handleLogStreamMessage(" + JSON + ")",
        LSM + "#sendCustomLog(" + STRING + "," + STRING + "," + STRING + ")",
        LSM + "#sendPlayerEventLog(" + STRING + "," + STRING + "," + STRING + ")",
        LSM + "#sendPluginActionLog(" + STRING + "," + STRING + ")",
        LSM + "#getLogTransmitter()",
        LSM + "#onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent)",
        LSM + "#onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent)",
        LSM + "#onServerLoad(org.bukkit.event.server.ServerLoadEvent)",
        PEM + "#onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent)",
        PEM + "#onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent)",
        PEM + "#onPlayerChat(org.bukkit.event.player.PlayerChatEvent)",
        PEM + "#onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent)",
        PEM + "#onPlayerKick(org.bukkit.event.player.PlayerKickEvent)",
        PEM + "#onPlayerCommandPreprocess(org.bukkit.event.player.PlayerCommandPreprocessEvent)",
        PEM + "#onPlayerChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent)");

    private static Map<String, JsonObject> ledger;

    @BeforeAll
    static void readLedger() throws IOException {
        Path file = Paths.get(System.getProperty("basedir", "."), "compatibility", "deprecations.json");
        ledger = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray entries = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                ledger.put(entry.get("key").getAsString(), entry);
            }
        }
    }

    @Test
    @DisplayName("UltiCloudRequests#post is announced for removal in 6.4.0 and names CloudPlayerLinks")
    void postIsAnnouncedForRemoval() {
        JsonObject entry = ledger.get("com.ultikits.ultitools.utils.UltiCloudRequests#post(java.lang.String,java.lang.String)");

        assertThat(entry).as("ledger entry for UltiCloudRequests#post").isNotNull();
        assertThat(entry.get("since").getAsString()).isEqualTo(SINCE);
        assertThat(entry.get("forRemoval").getAsBoolean()).isTrue();
        assertThat(entry.get("removeIn").getAsString()).isEqualTo(REMOVE_IN);
        assertThat(entry.get("status").getAsString()).isEqualTo("ANNOUNCED");
        assertThat(entry.get("replacement").getAsString()).contains("CloudPlayerLinks");
    }

    @Test
    @DisplayName("the 6.3.1 entries are exactly the confirmed v1 cloud inventory")
    void sixThreeOneEntriesAreExactlyTheInventory() {
        Set<String> actual = new TreeSet<>();
        for (JsonObject entry : ledger.values()) {
            if (entry.get("since").getAsString().startsWith(SINCE)) {
                actual.add(entry.get("key").getAsString());
            }
        }
        assertThat(EXPECTED).as("no duplicate in the expected list").doesNotHaveDuplicates();
        assertThat(actual).as("6.3.1 keys in compatibility/deprecations.json")
            .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    @DisplayName("every 6.3.1 entry is announced for removal in 6.4.0 with a replacement sentence")
    void everyEntryIsAnnouncedForRemovalWithAReplacement() {
        for (String key : EXPECTED) {
            JsonObject entry = ledger.get(key);
            assertThat(entry).as(key).isNotNull();
            assertThat(entry.get("forRemoval").getAsBoolean()).as(key + " forRemoval").isTrue();
            assertThat(entry.get("removeIn").getAsString()).as(key + " removeIn").isEqualTo(REMOVE_IN);
            assertThat(entry.get("status").getAsString()).as(key + " status").isEqualTo("ANNOUNCED");
            assertThat(entry.get("replacement").getAsString()).as(key + " replacement").isNotBlank();
        }
    }

    /**
     * The 22-06 no-op is still a no-op: its compiled body is a single {@code RETURN} (22-24
     * verification noted that nothing pinned it).
     */
    @Test
    @DisplayName("PluginInitiationUtils#startTokenRefreshScheduler compiles to a bare return")
    void tokenRefreshSchedulerIsABareReturn() throws IOException {
        List<Integer> opcodes = new ArrayList<>();
        String resource = PluginInitiationUtils.class.getName().replace('.', '/') + ".class";
        try (InputStream in = PluginInitiationUtils.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    if (!"startTokenRefreshScheduler".equals(name) || !"()V".equals(descriptor)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int opcode) {
                            opcodes.add(opcode);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String member, String desc,
                                boolean isInterface) {
                            opcodes.add(opcode);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String member, String desc) {
                            opcodes.add(opcode);
                        }

                        @Override
                        public void visitVarInsn(int opcode, int index) {
                            opcodes.add(opcode);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        assertThat(opcodes).containsExactly(Opcodes.RETURN);
    }
}
