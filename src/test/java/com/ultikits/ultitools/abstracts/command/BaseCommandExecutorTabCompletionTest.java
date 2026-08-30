package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Makes {@code commands/tabcomplete/} the live tab-completion path, entered through the
 * executor's own {@code onTabComplete}, and proves the mapping-level permission filter a
 * documentation audit could not have found (WIRE-01 / D-06 / T-05-20).
 * <p>
 * One test class carries all three plan-05-05 tasks' assertions -- the permission filter (Task
 * 1), the entry-point + argument-position-resolution dispatch (Task 2), and the old-vs-new
 * parity condition (Task 3) -- so the shared fixture command classes are declared once.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BaseCommandExecutorTabCompletionTest {

    private ServerMock server;
    private PlayerMock player;
    private Command mockCommand;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin(); // dummy plugin for permission attachments
        player = server.addPlayer("tabPlayer");
        player.setOp(false);
        mockCommand = mock(Command.class);
        when(mockCommand.getName()).thenReturn("fixture");

        CommandManager mockCommandManager = mock(CommandManager.class);
        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        TestHelper.mockUltiToolsInstance(ultiTools ->
                when(ultiTools.getCommandManager()).thenReturn(mockCommandManager));
        when(mockCommandManager.getPluginByCommand(any())).thenReturn(mockPlugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ========================================================================================
    // Task 1: the permission filter, ported before anything else
    // ========================================================================================

    @Nested
    @DisplayName("Task 1: mapping-level permission filter ported into BaseCommandExecutor.suggest")
    class PermissionFilterTests {

        @Test
        @DisplayName("unprivileged player does not receive a permission-gated mapping's first token")
        void unprivilegedPlayerExcludedFromPermissionGatedMapping() {
            PermissionAwareExecutor executor = new PermissionAwareExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).doesNotContain("secret");
        }

        @Test
        @DisplayName("privileged player receives a permission-gated mapping's first token")
        void privilegedPlayerSeesPermissionGatedMapping() {
            grant("fixture.secret");
            PermissionAwareExecutor executor = new PermissionAwareExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).contains("secret");
        }

        @Test
        @DisplayName("op-gated mapping withheld from non-op, returned to op")
        void opGatedMappingWithheldFromNonOpReturnedToOp() {
            PermissionAwareExecutor executor = new PermissionAwareExecutor();

            List<String> nonOpCompletions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});
            assertThat(nonOpCompletions).doesNotContain("adminonly");

            player.setOp(true);
            List<String> opCompletions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});
            assertThat(opCompletions).contains("adminonly");
        }

        @Test
        @DisplayName("mapping with no permission and no op requirement is returned to everybody")
        void ungatedMappingAlwaysReturned() {
            PermissionAwareExecutor executor = new PermissionAwareExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).contains("open");
        }

        @Test
        @DisplayName("a withheld mapping contributes no entry of any kind -- not a placeholder")
        void withheldMappingContributesNoPlaceholder() {
            OnlySecretExecutor executor = new OnlySecretExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).isEmpty();
        }

        @Test
        @DisplayName("the guard is format-shape-agnostic -- a multi-token-format mapping is filtered identically")
        void guardAppliesRegardlessOfFormatTokenCount() {
            // "secretparam <value>" is a two-token format; its first token must be gated exactly
            // like a single-token format's ("secret" above). The argument-VECTOR multi-token
            // path (args.length > 1) does not exist until Task 2 gives suggest() a body for it --
            // Task 2's own acceptance criteria re-assert permission holds on that populated path.
            PermissionAwareExecutor executor = new PermissionAwareExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});
            assertThat(completions).doesNotContain("secretparam");

            grant("fixture.secretparam");
            completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});
            assertThat(completions).contains("secretparam");
        }
    }

    private void grant(String permission) {
        Plugin bukkitPlugin = server.getPluginManager().getPlugins()[0];
        player.addAttachment(bukkitPlugin, permission, true);
    }

    // ========================================================================================
    // Shared fixture command classes
    // ========================================================================================

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class PermissionAwareExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub -- help handler not exercised by these tests
        }

        @CmdMapping(format = "open")
        public void openCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "secret", permission = "fixture.secret")
        public void secretCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "adminonly", requireOp = true)
        public void adminCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "secretparam <value>", permission = "fixture.secretparam")
        public void secretParamCommand(@CmdSender CommandSender sender, @CmdParam("value") String value) {
            // Test stub
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class OnlySecretExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "secret", permission = "fixture.secret")
        public void secretCommand(@CmdSender CommandSender sender) {
            // Test stub
        }
    }
}
