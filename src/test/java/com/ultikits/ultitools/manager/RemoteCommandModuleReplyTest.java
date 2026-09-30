package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * #541: the panel's remote command reads its output capture right after
 * {@code Bukkit.dispatchCommand} returns. A module command's body now runs inside that dispatch
 * on the primary thread, so its reply is in the captured output the panel receives.
 * <p>
 * Before the fix the body was deferred one tick, so the capture was read empty and the panel
 * received the generic "Command executed successfully" instead of the module's reply.
 */
@DisplayName("The panel's remote command returns a module command's reply (#541)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RemoteCommandModuleReplyTest {

    private ServerMock server;
    private CommandExecutionManager manager;
    private UltiPanelWebSocketClient webSocketClient;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"moduleprobe"})
    static class ModuleProbeExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "")
        public void reply(CommandSender sender) {
            sender.sendMessage("module reply 541");
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        Logger logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(logger);
            when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
        });
        webSocketClient = mock(UltiPanelWebSocketClient.class);
        when(webSocketClient.getServerId()).thenReturn("test-server-id");
        manager = new CommandExecutionManager();
        manager.setWebSocketClient(webSocketClient);

        ModuleProbeExecutor executor = new ModuleProbeExecutor();
        server.getCommandMap().register("moduleprobe", new Command("moduleprobe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return executor.onCommand(sender, this, label, args);
            }
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("the command_result output carries the module body's reply")
    void remoteCommandOutputCarriesTheModuleReply() {
        JsonObject commandData = new JsonObject();
        commandData.addProperty("command", "moduleprobe");
        commandData.addProperty("executor", "console");
        commandData.addProperty("commandId", "reply-541");

        manager.executeCommand(commandData);
        // One tick runs the manager's own hop to the main thread, which dispatches the command.
        server.getScheduler().performOneTick();

        ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
        verify(webSocketClient).sendMessage(sent.capture());
        JsonObject data = sent.getValue().getAsJsonObject("data");
        assertThat(data.get("output").getAsString()).contains("module reply 541");
    }

    @Test
    @DisplayName("control: a command that replies inside its own dispatch is captured the same way")
    void controlAnInlineReplyingCommandIsCaptured() {
        server.getCommandMap().register("controlprobe", new Command("controlprobe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                sender.sendMessage("control reply");
                return true;
            }
        });
        JsonObject commandData = new JsonObject();
        commandData.addProperty("command", "controlprobe");
        commandData.addProperty("executor", "console");
        commandData.addProperty("commandId", "control-541");

        manager.executeCommand(commandData);
        server.getScheduler().performOneTick();

        ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
        verify(webSocketClient).sendMessage(sent.capture());
        assertThat(sent.getValue().getAsJsonObject("data").get("output").getAsString()).contains("control reply");
        verify(webSocketClient).sendMessage(any(JsonObject.class));
    }
}
