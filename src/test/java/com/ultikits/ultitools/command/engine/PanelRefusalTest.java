package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.manager.CommandExecutionManager;

class PanelRefusalTest {
    @Test void checksBothPublicBlocklistOverloadsBeforeInvokingTheBody() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var panel = mockStatic(CommandExecutionManager.class)) {
            var sender = mock(CommandSender.class);
            var server = mock(Server.class);
            var map = mock(CommandMap.class);
            var command = mock(Command.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            when(server.getCommandMap()).thenReturn(map);
            when(map.getCommand("test")).thenReturn(command);
            panel.when(CommandExecutionManager::isPanelDispatch).thenReturn(true);
            panel.when(() -> CommandExecutionManager.refusePanelCommand(sender, "test count 5")).thenReturn(true);
            var executor = new CommandDispatchTest.Fixture();
            assertThat(CommandDispatchTest.dispatcher(executor).execute("test count 5",
                    CommandDispatchTest.source(sender))).isEqualTo(1);
            assertThat(executor.count).isNull();
            panel.verify(() -> CommandExecutionManager.refusePanelCommand(sender, command, "test count 5"));
            panel.verify(() -> CommandExecutionManager.refusePanelCommand(sender, "test count 5"));
        }
    }
}
