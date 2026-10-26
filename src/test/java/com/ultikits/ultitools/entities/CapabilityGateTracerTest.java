package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.file.YamlConfiguration;
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
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.utils.V2StubSession;

/**
 * Proves the remote-surface policy spine end to end on the protocol v2 path: every operator switch refuses its v2
 * request types. Rewritten in plan 22-23 (checker B-W6): the v1 tracer drove the removed v1 dispatch table
 * ({@code execute_command}, {@code file_operation}, {@code upload_config}); its switch assertions now run on the
 * connection's router against the in-process stub, with each switch turned off after the handshake, as the operator
 * would (S-66: the router re-reads the switch for every request).
 * <p>
 * Fates of the v1-only cases: the {@code Capability.NONE} bypass for {@code ping} has no v2 counterpart (the text
 * {@code ping} is answered by the transport, below the router); the {@code file_operation} resolver's mapping is the
 * public {@code PluginInitiationUtils#resolveFileOperationCapability}, pinned by {@code V2OnlySessionTest}; the
 * {@code upload_config} acknowledgement shape no longer exists; a type-less frame is refused by the router's schema
 * check ({@code AgentMessageRouterTest}).
 */
@DisplayName("Capability 端到端网关 —— v2 开关追踪")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reset the UltiTools singleton
class CapabilityGateTracerTest {

    @Nested
    @DisplayName("Capability.isEnabled() 的出厂默认值")
    class NoConfigDefaults {

        @BeforeEach
        void setUp() {
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration()));
        }

        @AfterEach
        void tearDown() throws Exception {
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        }

        @Test
        @DisplayName("没有 ultipanel.capabilities 块时，commands 为 false、monitoring 为 true")
        void splitDefaultsWithNoConfigBlock() {
            assertThat(Capability.COMMANDS.isEnabled())
                    .as("D-08：commands 出厂默认关闭")
                    .isFalse();
            assertThat(Capability.MONITORING.isEnabled())
                    .as("D-08：monitoring 出厂默认开启，否则升级后的服务器在面板上显示为离线")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("v2 开关网关（桩上的真实连接）")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    class SwitchGate {

        @TempDir
        Path temp;

        private V2StubSession session;

        @BeforeEach
        void setUp() {
            MockBukkitHelper.ensureCleanState();
        }

        @AfterEach
        void tearDown() throws Exception {
            if (session != null) {
                session.close();
            }
            MockBukkitHelper.safeUnmock();
        }

        private void start(boolean realCommands) throws Exception {
            ServerMock server = MockBukkit.mock();
            session = new V2StubSession(temp, server);
            if (realCommands) {
                session.realCommands();
            }
            for (Capability capability : switchedTypes().keySet()) {
                session.config().set(capability.getConfigPath(), true);
            }
            session.start(null);
            // Settle the handshake before a switch is turned off: "connected" is reported when the welcome is
            // accepted, before the connection's established listeners run, and a listener that already sees a
            // switch turned off reconnects so the next hello declares the new set (22-22 fix). A request sent on that
            // closing connection is refused but its reply cannot be delivered (22-23 fix, gap 5). The welcome's
            // status frame is sent by those listeners, after the reconnect check, so it marks the settled session.
            session.reply("status", 1);
        }

        /** Each operator switch and the gateway request types it governs (the contract's capability names). */
        private Map<Capability, String[]> switchedTypes() {
            Map<Capability, String[]> types = new LinkedHashMap<>();
            types.put(Capability.COMMANDS, new String[] {"command.execute"});
            types.put(Capability.PLAYER_ACTIONS, new String[] {"player.action"});
            types.put(Capability.FILE_READ, new String[] {"files.read", "files.list", "config.tree", "config.entries"});
            types.put(Capability.FILE_WRITE, new String[] {"files.write", "config.update"});
            types.put(Capability.FILE_DELETE, new String[] {"files.delete"});
            types.put(Capability.SERVER_PROPERTIES, new String[] {"properties.get", "properties.update"});
            types.put(Capability.MONITORING, new String[] {"snapshot.request"});
            return types;
        }

        private JsonObject errorFor(String id, int index) throws InterruptedException {
            JsonObject error = session.reply("error", index);
            assertThat(error.get("replyTo").getAsString()).isEqualTo(id);
            return error.getAsJsonObject("payload");
        }

        @Test
        @DisplayName("tracer: commands switched off refuses command.execute; switched on again it runs")
        void commandsSwitchRefusesAndAllows() throws Exception {
            start(false);
            session.config().set(Capability.COMMANDS.getConfigPath(), false);

            String id = session.send("command.execute", "say", null).get("id").getAsString();
            JsonObject refusal = errorFor(id, 1);
            assertThat(refusal.get("code").getAsString()).isEqualTo("capability_not_accepted");
            assertThat(refusal.getAsJsonObject("details").get("capability").getAsString()).isEqualTo("commands");
            assertThat(refusal.get("message").getAsString()).contains(Capability.COMMANDS.getConfigPath());
            session.pass(300L);
            assertThat(session.executions()).as("the refused command never ran").isZero();
            assertThat(session.stub().gateway().received("command.ack")).isEmpty();
            assertThat(session.actions()).as("the switch refusal writes exactly one DENIED entry").hasSize(1);
            RemoteActionLog.Entry denied = session.actions().get(0);
            assertThat(denied.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
            assertThat(denied.getCapability()).isEqualTo(Capability.COMMANDS.name());
            assertThat(denied.getAction()).isEqualTo("command.execute");
            assertThat(denied.getReason()).isEqualTo(Capability.COMMANDS.refusalMessage());

            session.config().set(Capability.COMMANDS.getConfigPath(), true);
            id = session.send("command.execute", "say", null).get("id").getAsString();
            assertThat(session.reply("command.result", 1).get("replyTo").getAsString()).isEqualTo(id);
            assertThat(session.executions()).isEqualTo(1);
        }

        @Test
        @DisplayName("sweep: every switch refuses each of its v2 request types, naming the capability and the key")
        void everySwitchRefusesItsTypes() throws Exception {
            start(false);
            int errors = 0;
            List<String> refused = new ArrayList<>();
            for (Map.Entry<Capability, String[]> entry : switchedTypes().entrySet()) {
                Capability capability = entry.getKey();
                session.config().set(capability.getConfigPath(), false);
                for (String type : entry.getValue()) {
                    String id = session.send(type, null, null).get("id").getAsString();
                    JsonObject refusal = errorFor(id, ++errors);
                    assertThat(refusal.get("code").getAsString()).as(type).isEqualTo("capability_not_accepted");
                    assertThat(refusal.getAsJsonObject("details").get("capability").getAsString()).as(type)
                            .isEqualTo(session.stub().contract().message(type).capability());
                    assertThat(refusal.get("message").getAsString()).as(type).contains(capability.getConfigPath());
                    refused.add(type);
                }
                session.config().set(capability.getConfigPath(), true);
            }
            assertThat(refused).hasSize(12);
            assertThat(session.executions()).isZero();
            // FEATURES.md ultitools.remote.file-refusal-audit and UAT-CHECKLIST.md
            // ultitools.capability.commands.neg-disabled: every switch refusal is exactly one DENIED entry naming the
            // switch, with the refusal sentence that names its configuration key as the reason.
            assertThat(session.actions()).as("one DENIED entry per refused request").hasSize(12);
            int index = 0;
            for (Map.Entry<Capability, String[]> entry : switchedTypes().entrySet()) {
                for (String type : entry.getValue()) {
                    RemoteActionLog.Entry denied = session.actions().get(index++);
                    assertThat(denied.getVerdict()).as(type).isEqualTo(RemoteActionLog.Verdict.DENIED);
                    assertThat(denied.getCapability()).as(type).isEqualTo(entry.getKey().name());
                    assertThat(denied.getAction()).as(type).isEqualTo(type);
                    assertThat(denied.getActor()).as(type).isEqualTo("panel");
                    assertThat(denied.getReason()).as(type).isEqualTo(entry.getKey().refusalMessage());
                }
            }
        }

        /**
         * CR-01 (06-REVIEW.md) on v2: with commands on, a blocklisted command is refused by the command manager's own
         * check, which writes exactly one DENIED entry; the router's gate passes the request and records nothing, so
         * no second, false ALLOWED entry can appear (the v1 gate used to add one).
         */
        @Test
        @DisplayName("CR-01: a blocklisted command with commands on records exactly one DENIED entry")
        void blocklistedCommandRecordsExactlyOneDeniedEntry() throws Exception {
            start(true);
            JsonObject payload = new JsonObject();
            payload.addProperty("command", "op");
            String id = session.send("command.execute", "say", payload).get("id").getAsString();

            JsonObject result = session.reply("command.result", 1);
            assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
            assertThat(result.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("command_blocked");
            session.pass(300L);
            assertThat(session.actions()).hasSize(1);
            RemoteActionLog.Entry entry = session.actions().get(0);
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
            assertThat(entry.getCapability()).isEqualTo(Capability.COMMANDS.name());
            assertThat(entry.getTarget()).isEqualTo("op");
        }
    }
}
