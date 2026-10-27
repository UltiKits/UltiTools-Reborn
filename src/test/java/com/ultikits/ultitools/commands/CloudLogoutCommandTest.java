package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.CloudAuthManager.LogoutOutcome;
import com.ultikits.ultitools.utils.TestHelper;

class CloudLogoutCommandTest {
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(logger));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @ParameterizedTest
    @EnumSource(LogoutOutcome.class)
    void exactOutcomeLinesAndRemoteLogging(LogoutOutcome outcome) {
        for (Class<? extends CommandSender> type : Arrays.asList(ConsoleCommandSender.class,
                RemoteConsoleCommandSender.class, CommandSender.class, Player.class)) {
            org.mockito.Mockito.clearInvocations(logger);
            CommandSender sender = mock(type);
            List<String> lines = new ArrayList<>();
            doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(() -> CloudAuthManager.logoutReporting(any())).thenAnswer(call -> {
                    assertThat(lines).containsExactly(ChatColor.AQUA + "Unlinking this server from UltiCloud...");
                    Consumer<LogoutOutcome> callback = call.getArgument(0);
                    callback.accept(outcome);
                    return null;
                });
                new CloudLoginCommand().logout(sender);
                auth.verify(() -> CloudAuthManager.logoutReporting(any()));
            }
            List<String> expected = new ArrayList<>();
            expected.add(ChatColor.AQUA + "Unlinking this server from UltiCloud...");
            switch (outcome) {
                case REVOKED:
                    expected.add(ChatColor.GREEN + "Unlinked this server from UltiCloud and revoked its credential. Cloud features are now stopped.");
                    expected.add(ChatColor.GRAY + "Use /ulticloud login to link it again.");
                    break;
                case DELETED_LOCALLY_REVOKE_FAILED:
                    expected.add(ChatColor.YELLOW + "Unlinked this server locally, but UltiCloud could not be reached to revoke its credential.");
                    expected.add(ChatColor.YELLOW + "The cloud credential may still be valid. Revoke it in the panel: server settings > Revoke credential.");
                    expected.add(ChatColor.GRAY + "Use /ulticloud login to link it again.");
                    break;
                case CANCELLED_PENDING:
                    expected.add(ChatColor.GREEN + "Cancelled the pending UltiCloud link request. Cloud features are stopped.");
                    break;
                default:
                    expected.add(ChatColor.YELLOW + "This server is not linked to UltiCloud.");
                    expected.add(ChatColor.GRAY + "Cloud features have been stopped regardless.");
            }
            assertThat(lines).containsExactlyElementsOf(expected);
            if (type == RemoteConsoleCommandSender.class || type == CommandSender.class) {
                for (String line : expected) { verify(logger).log(Level.INFO, ChatColor.stripColor(line)); }
            } else {
                verifyNoInteractions(logger);
            }
        }
    }
}
