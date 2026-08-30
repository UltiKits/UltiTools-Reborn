package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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

import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
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
    private UltiToolsPlugin mockPlugin;

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
        mockPlugin = mock(UltiToolsPlugin.class);
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
    // Task 2: one dispatch -- argument-position resolution into commands/tabcomplete/
    // ========================================================================================

    @Nested
    @DisplayName("Task 2: entry-point dispatch + argument-position resolution")
    class ArgumentPositionResolutionTests {

        @Test
        @DisplayName("entry-point assertion: onTabComplete resolves a @CmdParam(suggest=) slot through commands/tabcomplete/")
        void entryPointResolvesParamSuggestMethod() {
            MultiTokenExecutor executor = new MultiTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"give", ""});

            // Entered through the executor's OWN onTabComplete -- not TabCompletionManager
            // directly. Seven pre-existing test classes have passed against TabCompletionManager
            // for the entire period this package had zero callers; that is explicitly not
            // acceptable evidence for this assertion (05-05 plan, Task 2).
            assertThat(completions).containsExactlyInAnyOrder("alice", "bob", "charlie");
        }

        @Test
        @DisplayName("argument-position resolution: matched method and parameter name resolved at a later position")
        void resolvesMatchedMethodAndParameterNameAtLaterPosition() {
            MultiTokenExecutor executor = new MultiTokenExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture",
                    new String[]{"teleport", "steve", ""});

            // At HEAD, TabCompletionManager.createContext leaves matchedMethod/parameterName
            // null -- this only resolves because BaseCommandExecutor.suggest now performs the
            // index -> method -> parameter-name resolution before delegating.
            assertThat(completions).containsExactlyInAnyOrder("overworld", "nether", "the_end");
        }

        @Test
        @DisplayName("AOP-proxy-safe: a suggestion method declared on a superclass is found via a subclass instance")
        void findsSuggestionMethodOnProxiedSubclass() {
            // Simulates the shape an AOP subclass proxy produces: executorInstance is a subclass
            // instance, and the suggestion method is declared on its superclass. Exercises
            // MethodInvocationCompleter's hierarchy walk (issue #190), which the deprecated
            // class's own reflection helpers did not perform.
            ProxiedMultiTokenExecutor executor = new ProxiedMultiTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"give", ""});

            assertThat(completions).containsExactlyInAnyOrder("alice", "bob", "charlie");
        }

        @Test
        @DisplayName("suggest= naming a non-existent method still falls back to the i18n hint text")
        void fallsBackToI18nHintWhenSuggestMethodMissing() {
            when(mockPlugin.i18n("aHintOnlyKey")).thenReturn("Pick a mode");
            MultiTokenExecutor executor = new MultiTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"mode", ""});

            assertThat(completions).containsExactly("Pick a mode");
        }

        @Test
        @DisplayName("first-token completion is sorted and de-duplicated")
        void firstTokenCompletionSortedAndDeduplicated() {
            // "give <target>" and "give <target> silently" share the first literal token "give".
            // The deprecated class's own first-token branch would list it twice, unsorted; the
            // package's suggestFirstArgs sorts and dedupes -- adopted here as the new generation's
            // behaviour (deliberate, named change; see the plan's SUMMARY).
            MultiTokenExecutor executor = new MultiTokenExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).containsExactly("give", "mode", "teleport");
        }

        @Test
        @DisplayName("an argument vector longer than any mapping's format returns an empty list, not a throw")
        void overLongArgumentVectorReturnsEmptyListWithoutThrowing() {
            MultiTokenExecutor executor = new MultiTokenExecutor();

            List<String> completions = assertDoesNotThrow(() -> executor.onTabComplete(
                    player, mockCommand, "fixture", new String[]{"give", "alice", "extra", "waytoolong"}));

            assertThat(completions).isEmpty();
        }

        @Test
        @DisplayName("Task 1's permission assertions still hold on the newly-populated multi-token path")
        void permissionFilterHoldsOnMultiTokenPath() {
            PermissionAwareExecutor executor = new PermissionAwareExecutor();

            List<String> denied =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"secretgive", ""});
            assertThat(denied).isEmpty();

            grant("fixture.secretgive");
            List<String> allowed =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"secretgive", ""});
            assertThat(allowed).containsExactly("hiddenTarget");
        }
    }

    // ========================================================================================
    // Task 3: the old base class becomes a shell -- the parity condition is pinned
    // ========================================================================================

    @Nested
    @DisplayName("Task 3: AbstractCommandExecutor becomes a shell; D-06's parity condition is pinned")
    class ShellAndParityTests {

        @Test
        @DisplayName("D-06 parity, first-token case: both generations agree for the same fixture and player")
        void parityHoldsForFirstTokenCase() {
            OldParityExecutor oldExecutor = new OldParityExecutor();
            NewParityExecutor newExecutor = new NewParityExecutor();

            List<String> oldCompletions =
                    oldExecutor.onTabComplete(player, mockCommand, "parity", new String[]{""});
            List<String> newCompletions =
                    newExecutor.onTabComplete(player, mockCommand, "parity", new String[]{""});

            // Ordering is a deliberate, named difference -- Task 2 adopts the package's sort+dedup
            // for the new generation's first-token branch (see the plan's SUMMARY). This fixture's
            // mappings all have distinct first tokens, so set equality is the correct parity
            // contract; a fixture with a duplicated first token would additionally need to account
            // for the dedup difference, which is out of scope for this assertion.
            assertThat(newCompletions).containsExactlyInAnyOrderElementsOf(oldCompletions);
        }

        @Test
        @DisplayName("D-06 parity, multi-token literal-continuation case")
        void parityHoldsForMultiTokenLiteralCase() {
            OldParityExecutor oldExecutor = new OldParityExecutor();
            NewParityExecutor newExecutor = new NewParityExecutor();

            List<String> oldCompletions =
                    oldExecutor.onTabComplete(player, mockCommand, "parity", new String[]{"mode", ""});
            List<String> newCompletions =
                    newExecutor.onTabComplete(player, mockCommand, "parity", new String[]{"mode", ""});

            assertThat(newCompletions).containsExactlyInAnyOrderElementsOf(oldCompletions);
            assertThat(newCompletions).containsExactly("direct");
        }

        @Test
        @DisplayName("D-06 parity, @CmdParam(suggest=) reflective case")
        void parityHoldsForReflectiveSuggestCase() {
            OldParityExecutor oldExecutor = new OldParityExecutor();
            NewParityExecutor newExecutor = new NewParityExecutor();

            List<String> oldCompletions =
                    oldExecutor.onTabComplete(player, mockCommand, "parity", new String[]{"give", ""});
            List<String> newCompletions =
                    newExecutor.onTabComplete(player, mockCommand, "parity", new String[]{"give", ""});

            assertThat(newCompletions).containsExactlyInAnyOrderElementsOf(oldCompletions);
            assertThat(newCompletions).containsExactlyInAnyOrder("alice", "bob", "charlie");
        }

        @Test
        @DisplayName("D-06 parity, permission-gated case: an unprivileged player is withheld identically")
        void parityHoldsForPermissionGatedCase() {
            OldParityExecutor oldExecutor = new OldParityExecutor();
            NewParityExecutor newExecutor = new NewParityExecutor();

            List<String> oldCompletions =
                    oldExecutor.onTabComplete(player, mockCommand, "parity", new String[]{""});
            List<String> newCompletions =
                    newExecutor.onTabComplete(player, mockCommand, "parity", new String[]{""});

            assertThat(oldCompletions).doesNotContain("secret");
            assertThat(newCompletions).doesNotContain("secret");
        }

        @Test
        @DisplayName("the deprecated class's private reflection helpers are gone")
        void deprecatedReflectionHelpersAreDeleted() {
            Set<String> remaining = Arrays.stream(AbstractCommandExecutor.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            assertThat(remaining).doesNotContain("getArgSuggestion", "getFormatByMethod", "getArgAt",
                    "getSuggestName", "getSuggestMethodByName", "getMethod", "invokeSuggestMethod",
                    "getMethodsByArg");
        }

        @Test
        @DisplayName("the deprecated class's completion entry point still returns results after the helpers are deleted")
        void deprecatedShellStillReturnsResults() {
            OldParityExecutor executor = new OldParityExecutor();

            List<String> completions = executor.onTabComplete(player, mockCommand, "parity", new String[]{""});

            assertThat(completions).isNotEmpty();
        }
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

        @CmdMapping(format = "secretgive <target>", permission = "fixture.secretgive")
        public void secretGiveCommand(@CmdSender CommandSender sender,
                                       @CmdParam(value = "target", suggest = "suggestSecretTargets") String target) {
            // Test stub
        }

        public List<String> suggestSecretTargets(Player player, Command command, String[] args) {
            return Arrays.asList("hiddenTarget");
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

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class MultiTokenExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "target", suggest = "suggestTargets") String target) {
            // Test stub
        }

        @CmdMapping(format = "give <target> silently")
        public void giveSilentlyCommand(@CmdSender CommandSender sender, @CmdParam("target") String target) {
            // Test stub -- shares the first literal token "give" with giveCommand's mapping, to
            // exercise dedup on the first-token path.
        }

        @CmdMapping(format = "mode <choice>")
        public void modeCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "choice", suggest = "aHintOnlyKey") String choice) {
            // Test stub -- "aHintOnlyKey" names no method on this class, exercising the i18n hint
            // fallback (D-07 leaves this fallback unchanged; pinned here so plan 05-06 cannot
            // break it accidentally).
        }

        @CmdMapping(format = "teleport <player> <world>")
        public void teleportCommand(@CmdSender CommandSender sender,
                                     @CmdParam("player") String targetPlayer,
                                     @CmdParam(value = "world", suggest = "suggestWorlds") String world) {
            // Test stub
        }

        public List<String> suggestTargets(Player player, Command command, String[] args) {
            return Arrays.asList("alice", "bob", "charlie");
        }

        public List<String> suggestWorlds(Player player, Command command, String[] args) {
            return Arrays.asList("overworld", "nether", "the_end");
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class ProxiedMultiTokenExecutor extends MultiTokenExecutor {
        // Deliberately empty: simulates the shape an AOP subclass proxy produces (a subclass
        // instance whose suggestion method is declared on the superclass), without depending on
        // ByteBuddy proxy generation in this test.
    }

    /**
     * Shared suggestion content for the Task 3 parity fixtures below, so the two generations'
     * {@code suggestTargets} methods are guaranteed to return identical data -- the fixtures
     * themselves stay two separate classes (Java has no multiple inheritance across the two
     * unrelated base-class hierarchies), but what they return cannot drift out of sync.
     */
    static final class ParitySuggestions {
        private ParitySuggestions() {
        }

        static List<String> targets() {
            return Arrays.asList("alice", "bob", "charlie");
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class OldParityExecutor extends AbstractCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "open")
        public void openCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "secret", permission = "parity.secret")
        public void secretCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "target", suggest = "suggestTargets") String target) {
            // Test stub
        }

        @CmdMapping(format = "mode direct")
        public void modeDirectCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        public List<String> suggestTargets(Player player, Command command, String[] args) {
            return ParitySuggestions.targets();
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class NewParityExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "open")
        public void openCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "secret", permission = "parity.secret")
        public void secretCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "target", suggest = "suggestTargets") String target) {
            // Test stub
        }

        @CmdMapping(format = "mode direct")
        public void modeDirectCommand(@CmdSender CommandSender sender) {
            // Test stub
        }

        public List<String> suggestTargets(Player player, Command command, String[] args) {
            return ParitySuggestions.targets();
        }
    }
}
