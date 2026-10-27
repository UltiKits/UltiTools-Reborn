package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;

/**
 * 22-23 shutdown-window round (maintainer decision of 2026-10-26), on the v2 stub with the production bindings and the
 * production server-thread hop: from the moment {@code UltiTools#onDisable} starts, every state-changing request is
 * answered with a failure saying the server is shutting down, in a shape the stub validates against the vendored
 * 2.0.0 schemas (a frame that does not validate is never recorded as received), and nothing changes: no file, no
 * {@code server.properties}, no player, no command, no responder, no credential, and no action-log entry. Read-only
 * requests served off the server thread keep working.
 */
@DisplayName("Shutdown window: state-changing requests refused with a failure, reads still served")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ShutdownWindowTest {

    @TempDir
    Path temp;

    private ShutdownPanelMessageTracerTest.ValidatingServer server;
    private V2StubSession session;
    private final AtomicInteger responderCalls = new AtomicInteger();
    private String userDir;
    private int actionsBefore;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        // FileOperationManager guards paths against user.dir; root it at the temporary server for the read control.
        userDir = System.getProperty("user.dir");
        Files.createDirectories(temp.resolve("server"));
        System.setProperty("user.dir", temp.resolve("server").toAbsolutePath().toString());
        server = MockBukkit.mock(new ShutdownPanelMessageTracerTest.ValidatingServer());
        session = new V2StubSession(temp, server).productionMainThread();
        for (Capability capability : new Capability[] {Capability.COMMANDS, Capability.PLAYER_ACTIONS,
                Capability.FILE_READ, Capability.FILE_WRITE, Capability.FILE_DELETE, Capability.SERVER_PROPERTIES,
                Capability.POWER_ACTIONS}) {
            session.config().set(capability.getConfigPath(), true);
        }
        session.responders().registerResponder("ultimail.inbox.list", data -> {
            responderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new JsonObject());
        }, "UltiMail");
        session.start(null);
        // Settle the welcome's listeners before a case turns disabling on (22-23 fix round test hygiene).
        session.reply("status", 1);
        session.await(() -> server.getScheduler().enabledCalls.contains("runTaskTimer"), "the welcome's main timer");
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        server.getScheduler().shutdown();
        MockBukkitHelper.safeUnmock();
        System.setProperty("user.dir", userDir);
    }

    /** onDisable's first step plus Bukkit's own isEnabled flip; the action-log baseline is taken here. */
    private void disabling() {
        PluginInitiationUtils.stopAcceptingPanelMessages();
        when(UltiTools.getInstance().isEnabled()).thenReturn(false);
        actionsBefore = session.actions().size();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /** Waits for the reply of {@code type} to the frame {@code sent} and checks it is the shutting-down failure. */
    private JsonObject assertRefused(JsonObject sent, String type) throws InterruptedException {
        String id = sent.get("id").getAsString();
        session.await(() -> session.stub().gateway().received(type).stream()
                .anyMatch(f -> id.equals(f.get("replyTo").getAsString())), type + " to " + id);
        JsonObject reply = session.stub().gateway().received(type).stream()
                .filter(f -> id.equals(f.get("replyTo").getAsString())).findFirst().get();
        JsonObject payload = reply.getAsJsonObject("payload");
        if (!"error".equals(type)) {
            assertThat(payload.get("status").getAsString()).as(reply.toString()).isEqualTo("error");
        }
        assertThat(payload.get("code").getAsString()).as(reply.toString()).isEqualTo("internal_error");
        assertThat(payload.get("message").getAsString()).as(reply.toString()).contains("shutting down");
        return reply;
    }

    private void assertNothingRecordedOrScheduled() {
        assertThat(session.actions().subList(actionsBefore, session.actions().size()))
                .as("no action-log entry after the disable started").isEmpty();
        assertThat(server.getScheduler().disabledCalls).as("nothing scheduled for the disabled plugin").isEmpty();
        List<LogRecord> loud = session.pluginLog().stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .collect(Collectors.toList());
        assertThat(loud).extracting(LogRecord::getMessage).isEmpty();
    }

    @Test
    @DisplayName("files: files.write and files.delete answered internal_error; no file changed, nothing audited")
    void fileWriteAndDeleteAreRefused() throws Exception {
        Path config = session.serverRoot().resolve("plugins/UltiChat/config.yml");
        Files.createDirectories(config.getParent());
        byte[] before = "enabled: true\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, before);
        Path victim = session.serverRoot().resolve("plugins/UltiChat/old.yml");
        Files.write(victim, "x: 1\n".getBytes(StandardCharsets.UTF_8));
        disabling();

        JsonObject write = new JsonObject();
        write.addProperty("path", "plugins/UltiChat/config.yml");
        write.addProperty("content", "enabled: false\n");
        write.addProperty("baseVersion", sha256(before));
        JsonObject delete = new JsonObject();
        delete.addProperty("path", "plugins/UltiChat/old.yml");
        assertRefused(session.send("files.write", "with-base-version", write), "files.write.result");
        assertRefused(session.send("files.delete", "file", delete), "files.delete.result");
        session.pass(300L);

        assertThat(Files.readAllBytes(config)).as("files.write changed nothing").isEqualTo(before);
        assertThat(victim).as("files.delete removed nothing").exists();
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("properties: properties.update answered internal_error; server.properties unchanged")
    void propertiesUpdateIsRefused() throws Exception {
        Path properties = session.serverRoot().resolve("server.properties");
        byte[] before = "motd=Old\nview-distance=10\npvp=true\n".getBytes(StandardCharsets.UTF_8);
        Files.write(properties, before);
        disabling();

        JsonObject update = new JsonObject();
        update.addProperty("baseVersion", sha256(before));
        assertRefused(session.send("properties.update", "motd-and-view-distance", update),
                "properties.update.result");
        session.pass(300L);

        assertThat(Files.readAllBytes(properties)).isEqualTo(before);
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("config: config.update answered internal_error, nothing scheduled")
    void configUpdateIsRefused() throws Exception {
        disabling();
        int scheduled = server.getScheduler().enabledCalls.size();

        assertRefused(session.send("config.update", "two-changes", null), "config.update.result");
        session.pass(300L);

        assertThat(server.getScheduler().enabledCalls).hasSize(scheduled);
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("players: player.action answered internal_error; the player stays online")
    void playerActionIsRefused() throws Exception {
        PlayerActionEndToEndTest.KickablePlayer player =
                new PlayerActionEndToEndTest.KickablePlayer(server, "Notch_fan");
        server.addPlayer(player);
        disabling();

        assertRefused(session.send("player.action", "kick-with-reason", null), "player.action.result");
        session.pass(300L);

        assertThat(player.isOnline()).isTrue();
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("commands: command.execute acknowledged, then a failed command.result; never executed")
    void commandIsAnsweredWithAFailedResult() throws Exception {
        disabling();

        JsonObject sent = session.send("command.execute", "say", null);
        assertThat(session.reply("command.ack", 1).get("replyTo").getAsString())
                .isEqualTo(sent.get("id").getAsString());
        assertRefused(sent, "command.result");
        session.pass(300L);

        assertThat(session.executions()).isZero();
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("power: server.power acknowledged, then server.power.result internal_error; no stop scheduled")
    void powerIsRefused() throws Exception {
        disabling();
        int scheduled = server.getScheduler().enabledCalls.size();

        JsonObject sent = session.send("server.power", "stop", null);
        session.reply("command.ack", 1);
        assertRefused(sent, "server.power.result");
        session.pass(1500L);

        assertThat(server.getScheduler().enabledCalls).as("no stop scheduled").hasSize(scheduled);
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("modules: module.request answered module.response internal_error; the responder never runs")
    void moduleRequestIsRefused() throws Exception {
        disabling();

        assertRefused(session.send("module.request", "ultimail", null), "module.response");
        session.pass(300L);

        assertThat(responderCalls.get()).isZero();
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("credentials: credential.rotate answered with the generic error; not acknowledged, nothing stored")
    void credentialRotationIsRefused() throws Exception {
        Path credentials = session.serverRoot().resolve(".ultikits");
        String before = snapshot(credentials);
        disabling();

        assertRefused(session.send("credential.rotate", null, null), "error");
        session.pass(300L);

        assertThat(session.stub().gateway().received("credential.rotate.ack")).isEmpty();
        assertThat(snapshot(credentials)).as("no credential written").isEqualTo(before);
        assertNothingRecordedOrScheduled();
    }

    @Test
    @DisplayName("control: read-only files.read, files.list and properties.get are still served while disabling")
    void readsKeepWorking() throws Exception {
        Path config = session.serverRoot().resolve("plugins/UltiChat/config.yml");
        Files.createDirectories(config.getParent());
        Files.write(config, "enabled: true\n".getBytes(StandardCharsets.UTF_8));
        Files.write(session.serverRoot().resolve("server.properties"),
                "motd=Old\n".getBytes(StandardCharsets.UTF_8));
        disabling();

        JsonObject read = new JsonObject();
        read.addProperty("path", "plugins/UltiChat/config.yml");
        session.send("files.read", "config", read);
        session.send("files.list", "root", null);
        session.send("properties.get", "all", null);

        assertThat(session.reply("files.read.result", 1).getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo("ok");
        assertThat(session.reply("files.list.result", 1).getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo("ok");
        assertThat(session.reply("properties.get.result", 1).getAsJsonObject("payload").get("status")
                .getAsString()).isEqualTo("ok");
    }

    @Test
    @DisplayName("control: while enabled the same file write runs and is audited once")
    void enabledWriteRunsAndIsAudited() throws Exception {
        Path config = session.serverRoot().resolve("plugins/UltiChat/config.yml");
        Files.createDirectories(config.getParent());
        byte[] before = "enabled: true\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, before);
        int audited = session.actions().size();

        JsonObject write = new JsonObject();
        write.addProperty("path", "plugins/UltiChat/config.yml");
        write.addProperty("content", "enabled: false\n");
        write.addProperty("baseVersion", sha256(before));
        session.send("files.write", "with-base-version", write);
        JsonObject result = session.reply("files.write.result", 1);

        assertThat(result.getAsJsonObject("payload").get("status").getAsString()).as(result.toString())
                .isEqualTo("ok");
        assertThat(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).isEqualTo("enabled: false\n");
        assertThat(session.actions()).hasSize(audited + 1);
    }

    private static String snapshot(Path directory) throws Exception {
        StringBuilder text = new StringBuilder();
        if (!Files.isDirectory(directory)) {
            return "";
        }
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                text.append(directory.relativize(file)).append('=').append(sha256(Files.readAllBytes(file)))
                        .append('\n');
            }
        }
        return text.toString();
    }
}
