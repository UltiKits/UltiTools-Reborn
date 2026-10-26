package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;

/**
 * Tracer for UltiTools-Reborn#621 on the protocol v2 path (rewritten in plan 22-23; the v1 tracer drove the removed v1
 * inbound dispatch): a remote request that arrives while UltiTools is disabling must not make the connection's
 * threads schedule work for the disabled plugin, and no new remote action may run. Bukkit flips {@code isEnabled()}
 * to {@code false} before it calls {@code onDisable()}, and Paper's {@code CraftScheduler} then rejects every task with
 * {@link IllegalPluginAccessException}; the scheduler here does exactly that, so a regression shows as a recorded
 * call for the disabled plugin instead of the WARNING with a stack trace a real server prints. The framework runs the
 * production server-thread hop ({@code scheduleUnlessDisabling} and the Bukkit scheduler) on the in-process stub.
 * <br>
 * #621 的示踪测试（v2）：UltiTools 正在禁用时到达的远程请求不得为已禁用的插件调度任务，也不得执行新的远程动作。
 */
@DisplayName("#621 remote requests while UltiTools is disabling")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ShutdownPanelMessageTracerTest {

    @TempDir
    Path temp;

    private ValidatingServer server;
    private V2StubSession session;
    private final AtomicInteger responderCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(new ValidatingServer());
        session = new V2StubSession(temp, server).productionMainThread();
        session.config().set(Capability.COMMANDS.getConfigPath(), true);
        session.config().set(Capability.PLAYER_ACTIONS.getConfigPath(), true);
        session.config().set(Capability.FILE_WRITE.getConfigPath(), true);
        session.config().set(Capability.SERVER_PROPERTIES.getConfigPath(), true);
        session.config().set(Capability.POWER_ACTIONS.getConfigPath(), true);
        session.responders().registerResponder("ultimail.inbox.list", data -> {
            responderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new JsonObject());
        }, "UltiMail");
        session.start(null);
        // Settle the handshake before a case starts: "connected" is reported when the welcome is accepted, before the
        // connection's established listeners run on the agent thread, and those listeners still hand the welcome's
        // status to the server thread and start the once-a-second main timer. A case that took its scheduler baseline
        // or turned disabling on in that gap saw those calls land afterwards (22-23 fix round, under CPU load). The
        // main timer is the listeners' last scheduler call.
        session.reply("status", 1);
        session.await(() -> server.getScheduler().enabledCalls.contains("runTaskTimer"), "the welcome's main timer");
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        server.getScheduler().shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        StringBuilder hex = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private void disabling() {
        when(UltiTools.getInstance().isEnabled()).thenReturn(false);
    }

    private List<LogRecord> at(Level level) {
        return session.pluginLog().stream().filter(r -> r.getLevel().equals(level)).collect(Collectors.toList());
    }

    private void assertQuiet(String what) {
        assertThat(at(Level.WARNING)).as("WARNING lines (%s)", what)
                .extracting(r -> r.getMessage() + " | thrown: " + r.getThrown()).isEmpty();
        assertThat(at(Level.SEVERE)).as("SEVERE lines (%s)", what).isEmpty();
        assertThat(server.getScheduler().disabledCalls).as("scheduler calls for the disabled plugin (%s)", what)
                .isEmpty();
    }

    @Nested
    @DisplayName("tracer: a remote command while disabling")
    class Tracer {

        @Test
        @DisplayName("a command.execute while disabling: acknowledged, never executed, nothing scheduled, no WARNING")
        void commandWhileDisablingIsDroppedQuietly() throws Exception {
            disabling();

            String id = session.send("command.execute", "say", null).get("id").getAsString();
            assertThat(session.reply("command.ack", 1).get("replyTo").getAsString()).isEqualTo(id);
            session.pass(500L);

            assertThat(session.executions()).isZero();
            assertThat(session.stub().gateway().received("command.result")).isEmpty();
            assertQuiet("command.execute");
        }

        @Test
        @DisplayName("the accepting flag alone, cleared first in onDisable, drops the command too")
        void clearedFlagDropsEvenBeforeBukkitReportsDisabled() throws Exception {
            PluginInitiationUtils.stopAcceptingPanelMessages();
            int scheduledBefore = server.getScheduler().enabledCalls.size();

            session.send("command.execute", "say", null);
            session.reply("command.ack", 1);
            session.pass(500L);

            assertThat(session.executions()).isZero();
            assertThat(session.stub().gateway().received("command.result")).isEmpty();
            assertThat(server.getScheduler().enabledCalls.subList(scheduledBefore,
                    server.getScheduler().enabledCalls.size())).as("nothing new scheduled at all").isEmpty();
            assertQuiet("command.execute");
        }

        @Test
        @DisplayName("control: while enabled the same command is executed on the server thread and answered")
        void enabledCommandIsHandledAsBefore() throws Exception {
            String id = session.send("command.execute", "say", null).get("id").getAsString();
            JsonObject result = session.reply("command.result", 1);

            assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
            assertThat(session.executions()).isEqualTo(1);
            assertThat(server.getScheduler().enabledCalls).contains("runTask");
            assertQuiet("control");
        }
    }

    @Nested
    @DisplayName("sweep: every remote action behind the same check")
    class Sweep {

        /**
         * The requests that need the server thread (power, player action, configuration, command) run nothing while
         * disabling: their hand-off goes through {@code scheduleUnlessDisabling}. The requests served off the server
         * thread -- {@code properties.*} and {@code files.*} on the connection's file worker, {@code module.request} on
         * the responder registry's workers -- are still served until {@code onDisable} closes the connection a moment
         * later; for them this case pins only the #621 guarantee (nothing is scheduled for the disabled plugin, no
         * WARNING). Recorded as an open question in 22-23-SUMMARY.md, not changed here.
         */
        @Test
        @DisplayName("every remote request while disabling: nothing scheduled for the disabled plugin, no WARNING; "
                + "server-thread actions do not run")
        void everyRemoteRequestWhileDisablingIsQuiet() throws Exception {
            PlayerActionEndToEndTest.KickablePlayer player =
                    new PlayerActionEndToEndTest.KickablePlayer(server, "Notch_fan");
            server.addPlayer(player);
            disabling();
            int scheduledBefore = server.getScheduler().enabledCalls.size();

            String[][] requests = {
                    {"server.power", "stop"},
                    {"player.action", "kick-with-reason"},
                    {"config.update", "two-changes"},
                    {"command.execute", "say"},
                    {"files.list", "root"},
                    {"properties.get", "all"},
                    {"module.request", "ultimail"},
                    {"snapshot.request", "all"},
            };
            for (String[] request : requests) {
                session.send(request[0], request[1], null);
            }
            session.pass(1500L);

            assertThat(session.executions()).as("command executed").isZero();
            assertThat(player.isOnline()).as("the player was not kicked").isTrue();
            assertThat(server.getScheduler().enabledCalls.subList(scheduledBefore,
                    server.getScheduler().enabledCalls.size())).as("no power stop or other task was scheduled")
                    .isEmpty();
            assertThat(session.stub().gateway().received("player.action.result")).isEmpty();
            assertThat(session.stub().gateway().received("config.update.result")).isEmpty();
            assertQuiet("sweep");
        }

        /**
         * The v2 form of the 6.3.0 case "a handshake completing while disabling wires nothing and schedules nothing"
         * (dropped in 22-23, restored in its fix round): onDisable's first step and Bukkit's own flag are set, the
         * connection is replaced, and a second welcome arrives. The established listeners' server-thread work (the
         * welcome's status and snapshots, the once-a-second timer) goes through {@code scheduleUnlessDisabling}, so
         * nothing is scheduled for the disabled plugin, nothing of it reaches the gateway, and no WARNING is printed.
         */
        @Test
        @DisplayName("a handshake completing while disabling: nothing scheduled for the disabled plugin, no welcome "
                + "telemetry sent, no WARNING")
        void handshakeCompletingWhileDisablingSchedulesNothing() throws Exception {
            session.reply("status", 1);
            int welcomes = session.stub().gateway().sent("welcome").size();
            int statuses = session.stub().gateway().received("status").size();
            int scheduledBefore = server.getScheduler().enabledCalls.size();
            PluginInitiationUtils.stopAcceptingPanelMessages();
            disabling();

            PluginInitiationUtils.reinitWebSocket();
            session.await(() -> session.stub().gateway().sent("welcome").size() > welcomes, "a second welcome");
            session.pass(2500L);

            assertThat(server.getScheduler().disabledCalls).as("nothing scheduled for the disabled plugin").isEmpty();
            assertThat(server.getScheduler().enabledCalls.subList(scheduledBefore,
                    server.getScheduler().enabledCalls.size())).as("nothing scheduled at all").isEmpty();
            assertThat(session.stub().gateway().received("status")).as("no welcome status while disabling")
                    .hasSize(statuses);
            // ConsoleMirror's once-per-JVM "log forwarder not found" WARNING is a MockBukkit artefact.
            assertThat(at(Level.WARNING)).extracting(LogRecord::getMessage)
                    .filteredOn(message -> message == null || !message.contains("log forwarder")).isEmpty();
            assertThat(at(Level.SEVERE)).isEmpty();
        }

        @Test
        @DisplayName("control: while enabled a properties update, a module request and a player kick run")
        void enabledRequestsRun() throws Exception {
            PlayerActionEndToEndTest.KickablePlayer player =
                    new PlayerActionEndToEndTest.KickablePlayer(server, "Notch_fan");
            server.addPlayer(player);
            Path properties = session.serverRoot().resolve("server.properties");
            byte[] before = "motd=Old\nview-distance=10\npvp=true\n".getBytes(StandardCharsets.UTF_8);
            Files.write(properties, before);
            JsonObject base = new JsonObject();
            base.addProperty("baseVersion", sha256(before));

            session.send("properties.update", "motd-and-view-distance", base);
            session.send("module.request", "ultimail", null);
            session.send("player.action", "kick-with-reason", null);
            JsonObject update = session.reply("properties.update.result", 1);
            session.reply("module.response", 1);
            session.reply("player.action.result", 1);

            assertThat(new String(Files.readAllBytes(properties), StandardCharsets.UTF_8)).as(update.toString())
                    .contains("motd=Welcome");
            assertThat(responderCalls.get()).isEqualTo(1);
            assertThat(player.isOnline()).as("the player was kicked").isFalse();
            assertQuiet("control");
        }

        @Test
        @DisplayName("the scheduler refuses after the check passed: dropped, null, one FINE line, no WARNING")
        void refusalBetweenCheckAndSchedulerIsDropped() {
            int before = at(Level.FINE).size();
            Object scheduled = PluginInitiationUtils.scheduleUnlessDisabling("race probe", () -> {
                throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
            });

            assertThat(scheduled).isNull();
            List<LogRecord> fine = at(Level.FINE);
            assertThat(fine).hasSize(before + 1);
            assertThat(fine.get(fine.size() - 1).getMessage()).contains("race probe");
            assertThat(fine.get(fine.size() - 1).getThrown()).isNull();
            assertQuiet("race probe");
        }

        @Test
        @DisplayName("control: while accepting, the scheduler call runs and its result is returned")
        void acceptedSchedulingReturnsTheResult() {
            Object scheduled = PluginInitiationUtils.scheduleUnlessDisabling("accepted probe", () -> "task");

            assertThat(scheduled).isEqualTo("task");
        }
    }

    /** A MockBukkit server whose scheduler refuses a disabled plugin's task the way Paper's does. */
    static final class ValidatingServer extends ServerMock {
        private ValidatingScheduler validating;

        @Override
        public ValidatingScheduler getScheduler() {
            if (validating == null) {
                validating = new ValidatingScheduler();
            }
            return validating;
        }
    }

    /** Records every scheduling call by whether its plugin was enabled; refuses a disabled one. */
    static final class ValidatingScheduler extends BukkitSchedulerMock {
        final List<String> enabledCalls = new CopyOnWriteArrayList<>();
        final List<String> disabledCalls = new CopyOnWriteArrayList<>();

        private void check(Plugin plugin, String call) {
            if (!plugin.isEnabled()) {
                disabledCalls.add(call);
                throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
            }
            enabledCalls.add(call);
        }

        @Override
        public BukkitTask runTask(Plugin plugin, Runnable task) {
            check(plugin, "runTask");
            return super.runTask(plugin, task);
        }

        @Override
        public BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
            check(plugin, "runTaskLater");
            return super.runTaskLater(plugin, task, delay);
        }

        @Override
        public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
            check(plugin, "runTaskTimer");
            return super.runTaskTimer(plugin, task, delay, period);
        }

        @Override
        public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
            check(plugin, "runTaskAsynchronously");
            return super.runTaskAsynchronously(plugin, task);
        }

        @Override
        public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
            check(plugin, "scheduleSyncDelayedTask");
            return super.scheduleSyncDelayedTask(plugin, task, delay);
        }

        @Override
        public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
            check(plugin, "scheduleSyncDelayedTask");
            return super.scheduleSyncDelayedTask(plugin, task);
        }
    }
}
