package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Makes {@code commands/tabcomplete/} the live tab-completion path, entered through the
 * executor's own {@code onTabComplete}, and proves the mapping-level permission filter a
 * documentation audit could not have found (WIRE-01 / D-06 / T-05-20).
 * <p>
 * One test class carries plan-05-05's Task 1 and Task 2 assertions -- the permission filter
 * (Task 1) and the entry-point + argument-position-resolution dispatch (Task 2) -- so the shared
 * fixture command classes are declared once. Task 3 ("the old base class becomes a shell; the
 * old-vs-new parity condition is pinned") compared this class's dispatch against the deprecated
 * {@code AbstractCommandExecutor} generation; plan 07-15 deleted that generation entirely in
 * 6.3.0, so Task 3's entire subject -- the comparison itself -- no longer exists, and its nested
 * test class was removed rather than left comparing against nothing.
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
    @DisplayName("#398: a first-token <param> is completed, not skipped")
    class FirstTokenParameterTests {

        /**
         * The controlled comparison this defect was found by, reproduced as a test.
         * <p>
         * {@code FirstTokenExecutor} declares the SAME suggest method on the same parameter name
         * at two positions -- {@code "<name>"} and {@code "open <name>"} -- exactly as UltiMenu
         * does. Only the second worked: {@code suggestFirstArgs} collects literal subcommand names
         * and skips parameter placeholders by construction, and nothing else covered position 0.
         */
        @Test
        @DisplayName("position 0: a bare <param> format yields its suggester's output")
        void firstTokenParameterIsCompleted() {
            FirstTokenExecutor executor = new FirstTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).contains("example", "spawn");
        }

        @Test
        @DisplayName("control, position 1: the same suggester on the same parameter already worked")
        void laterPositionStillWorks() {
            FirstTokenExecutor executor = new FirstTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"open", ""});

            // If this ever fails, the position-0 assertion above proves nothing: the two would be
            // broken together rather than the first one alone.
            assertThat(completions).contains("example", "spawn");
        }

        @Test
        @DisplayName("literal subcommands are still offered alongside the parameter suggestions")
        void literalsAndParametersAreMerged() {
            FirstTokenExecutor executor = new FirstTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            // The literal branch must not be displaced by the new one -- both formats are valid
            // at position 0 and a user needs to see both.
            assertThat(completions).contains("open", "list");
        }

        @Test
        @DisplayName("a partial first token narrows the parameter suggestions")
        void partialFirstTokenNarrows() {
            FirstTokenExecutor executor = new FirstTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"exa"});

            assertThat(completions).contains("example");
            assertThat(completions).doesNotContain("spawn");
        }

        @Test
        @DisplayName("no duplicates when a suggestion also happens to be a literal subcommand")
        void noDuplicatesAcrossTheTwoSources() {
            FirstTokenExecutor executor = new FirstTokenExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{""});

            assertThat(completions).doesNotHaveDuplicates();
        }
    }

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
    // UAT Fix (05-fix): every downstream @CmdParam.suggest method-name signature shape is
    // actually invocable through the real onTabComplete entry point. Real-machine UAT on Paper
    // 1.21.11 caught this after phase 05 made commands/tabcomplete/ live:
    // MethodInvocationCompleter.invokeSuggestMethod fell into a final `else` that invoked ANY
    // unrecognized signature with ZERO arguments, throwing IllegalArgumentException at Tab-press
    // time for 16 of 24 real downstream call sites (every UltiWorlds `(Player, String)`-shaped
    // suggest method). 5233 unit tests missed it because MethodInvocationCompleterTest drove
    // invokeSuggestMethod in isolation -- exactly what this class exists to stop doing.
    // ========================================================================================

    @Nested
    @DisplayName("UAT Fix: every downstream suggest-method signature shape is invocable through onTabComplete")
    class SuggestMethodSignatureShapeTests {

        @Test
        @DisplayName("() zero-arg suggest method returns its suggestions")
        void zeroArgSignatureWorks() {
            SignatureShapeExecutor executor = new SignatureShapeExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"zero", ""});

            assertThat(completions).containsExactly("zeroResult");
        }

        @Test
        @DisplayName("(Player) single-arg suggest method receives the requesting player")
        void playerOnlySignatureWorks() {
            SignatureShapeExecutor executor = new SignatureShapeExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"playeronly", ""});

            assertThat(completions).containsExactly("playerOnlyResult:" + player.getName());
        }

        @Test
        @DisplayName("(String) single-arg suggest method receives the current input -- one of the 3 "
                + "downstream (String prefix) methods verified during this fix")
        void stringOnlySignatureWorks() {
            SignatureShapeExecutor executor = new SignatureShapeExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"stringonly", "ab"});

            assertThat(completions).containsExactly("abResult");
        }

        @Test
        @DisplayName("(Player, String) two-arg suggest method -- the shape 16 of 24 real downstream "
                + "call sites use -- receives both the player and the current input")
        void playerAndStringSignatureWorks() {
            SignatureShapeExecutor executor = new SignatureShapeExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"playerstring", "ab"});

            assertThat(completions).containsExactly("abResult:" + player.getName());
        }

        @Test
        @DisplayName("(Player, Command, String[]) three-arg suggest method still works unchanged")
        void playerCommandArgsSignatureStillWorks() {
            SignatureShapeExecutor executor = new SignatureShapeExecutor();

            List<String> completions = executor.onTabComplete(
                    player, mockCommand, "fixture", new String[]{"playercommandargs", ""});

            assertThat(completions).containsExactly("threeArgResult");
        }
    }

    // ========================================================================================
    // Task 3 ("the old base class becomes a shell -- the parity condition is pinned") was
    // removed by plan 07-15: it compared this generation's tab-completion dispatch against the
    // deprecated AbstractCommandExecutor generation, which was deleted entirely in 6.3.0. There
    // is no longer an "old" generation to hold parity with, so the comparison's entire subject
    // is gone -- the fixtures it used (OldParityExecutor, NewParityExecutor, ParitySuggestions)
    // were removed in the same change, not left behind unused.
    // ========================================================================================

    // ========================================================================================
    // 05-06 Task 1: dual notation on @CmdParam.suggest, entered through onTabComplete (D-07)
    // ========================================================================================

    @Nested
    @DisplayName("05-06 Task 1: dual notation on @CmdParam.suggest, entered through onTabComplete")
    class SuggestKeyNotationTests {

        @Test
        @DisplayName("@players resolves through OnlinePlayersCompleter")
        void atPlayersResolvesThroughOnlinePlayersCompleter() {
            server.addPlayer("otherOnline");
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"give", ""});

            assertThat(completions).contains("otherOnline", "tabPlayer");
        }

        @Test
        @DisplayName("@worlds resolves through WorldsCompleter")
        void atWorldsResolvesThroughWorldsCompleter() {
            server.addSimpleWorld("fixtureworld");
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"goto", ""});

            assertThat(completions).contains("fixtureworld");
        }

        @Test
        @DisplayName("@materials resolves through MaterialsCompleter")
        void atMaterialsResolvesThroughMaterialsCompleter() {
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"build", "sto"});

            assertThat(completions).contains("STONE");
        }

        @Test
        @DisplayName("@boolean resolves through StaticSuggestionsCompleter")
        void atBooleanResolvesThroughStaticSuggestionsCompleter() {
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"toggle", ""});

            assertThat(completions).containsExactlyInAnyOrder("true", "false");
        }

        @Test
        @DisplayName("a suggest value with no leading @ still resolves by method name -- the 24 downstream sites need zero change")
        void nonAtSuggestStillResolvesByMethodName() {
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"legacy", ""});

            assertThat(completions).containsExactlyInAnyOrder("alpha", "beta");
        }

        @Test
        @DisplayName("a suggest value naming a non-existent method still falls back to the i18n hint text, unchanged")
        void suggestNamingMissingMethodStillFallsBackToHint() {
            when(mockPlugin.i18n("aHintOnlyKeyForNotation")).thenReturn("Pick a notation");
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"hint", ""});

            assertThat(completions).containsExactly("Pick a notation");
        }

        @Test
        @DisplayName("wrong-attribute trap: a display name beginning with @ is not consulted as the key")
        void displayNameStartingWithAtIsNotConsultedAsKey() {
            // @CmdParam(value = "@trap", suggest = "suggestTrap") -- the DISPLAY NAME starts with
            // @, but suggest() names a real method. Resolution must be driven by suggest(), never
            // by the display name (D-07 Pitfall 2 / T-05-28) -- if it were, this would either
            // throw (no completer registered under "@trap") or silently return nothing.
            KeyNotationExecutor executor = new KeyNotationExecutor();

            List<String> completions =
                    executor.onTabComplete(player, mockCommand, "fixture", new String[]{"trap", ""});

            assertThat(completions).containsExactly("trapped");
        }

        @Test
        @DisplayName("a completer registered at runtime under a custom key is reachable through the same @key branch as a built-in")
        void runtimeRegisteredCustomKeyIsReachable() {
            TabCompletionManager.getInstance().register("@fixtureCustomKey",
                    ctx -> Arrays.asList("customOne", "customTwo"));
            try {
                KeyNotationExecutor executor = new KeyNotationExecutor();

                List<String> completions =
                        executor.onTabComplete(player, mockCommand, "fixture", new String[]{"custom", ""});

                assertThat(completions).containsExactlyInAnyOrder("customOne", "customTwo");
            } finally {
                TabCompletionManager.getInstance().unregister("@fixtureCustomKey");
            }
        }
    }

    // ========================================================================================
    // Shared fixture command classes
    // ========================================================================================

    /**
     * UAT Fix (05-fix) fixture: one mapping per suggest-method signature shape
     * {@code MethodInvocationCompleter.invokeSuggestMethod} supports. {@code suggestStringOnly}
     * and {@code suggestPlayerString} mirror the two real downstream shapes UAT found broken --
     * {@code (String)} (UltiEssentials' {@code BaseEssentialsCommand}) and {@code (Player,
     * String)} (16 of UltiWorlds' 24 downstream call sites).
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    /**
     * Declares one suggest method on the same parameter name at two token positions, so a test can
     * hold everything constant except the position. Modelled on UltiMenu's {@code MenuCommands},
     * where {@code /menu open e<TAB>} offered {@code example} and {@code /menu e<TAB>} offered
     * nothing (#398).
     */
    static class FirstTokenExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // not exercised
        }

        @CmdMapping(format = "<name>")
        public void quickOpen(@CmdSender CommandSender sender,
                              @CmdParam(value = "name", suggest = "suggestNames") String name) {
            // body not exercised
        }

        @CmdMapping(format = "open <name>")
        public void open(@CmdSender CommandSender sender,
                         @CmdParam(value = "name", suggest = "suggestNames") String name) {
            // body not exercised
        }

        @CmdMapping(format = "list")
        public void list(@CmdSender CommandSender sender) {
            // body not exercised
        }

        public List<String> suggestNames() {
            return Arrays.asList("example", "spawn");
        }
    }

    static class SignatureShapeExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "zero <target>")
        public void zeroCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "target", suggest = "suggestZero") String target) {
            // Test stub
        }

        public List<String> suggestZero() {
            return Arrays.asList("zeroResult");
        }

        @CmdMapping(format = "playeronly <target>")
        public void playerOnlyCommand(@CmdSender CommandSender sender,
                                       @CmdParam(value = "target", suggest = "suggestPlayerOnly") String target) {
            // Test stub
        }

        public List<String> suggestPlayerOnly(Player player) {
            return Arrays.asList("playerOnlyResult:" + player.getName());
        }

        @CmdMapping(format = "stringonly <target>")
        public void stringOnlyCommand(@CmdSender CommandSender sender,
                                       @CmdParam(value = "target", suggest = "suggestStringOnly") String target) {
            // Test stub
        }

        public List<String> suggestStringOnly(String input) {
            return Arrays.asList(input + "Result");
        }

        @CmdMapping(format = "playerstring <target>")
        public void playerStringCommand(@CmdSender CommandSender sender,
                                         @CmdParam(value = "target", suggest = "suggestPlayerString") String target) {
            // Test stub
        }

        public List<String> suggestPlayerString(Player player, String input) {
            return Arrays.asList(input + "Result:" + player.getName());
        }

        @CmdMapping(format = "playercommandargs <target>")
        public void playerCommandArgsCommand(@CmdSender CommandSender sender,
                @CmdParam(value = "target", suggest = "suggestPlayerCommandArgs") String target) {
            // Test stub
        }

        public List<String> suggestPlayerCommandArgs(Player player, Command command, String[] args) {
            return Arrays.asList("threeArgResult");
        }
    }

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
     * 05-06 Task 1 fixture: one mapping per built-in completer key, plus the method-name,
     * missing-method-fallback, wrong-attribute-trap and runtime-custom-key cases.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class KeyNotationExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "target", suggest = "@players") String target) {
            // Test stub
        }

        @CmdMapping(format = "goto <world>")
        public void gotoCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "world", suggest = "@worlds") String world) {
            // Test stub
        }

        @CmdMapping(format = "build <material>")
        public void buildCommand(@CmdSender CommandSender sender,
                                  @CmdParam(value = "material", suggest = "@materials") String material) {
            // Test stub
        }

        @CmdMapping(format = "toggle <state>")
        public void toggleCommand(@CmdSender CommandSender sender,
                                   @CmdParam(value = "state", suggest = "@boolean") String state) {
            // Test stub
        }

        @CmdMapping(format = "legacy <name>")
        public void legacyCommand(@CmdSender CommandSender sender,
                                   @CmdParam(value = "name", suggest = "suggestNames") String name) {
            // Test stub -- plain method-name notation, unaffected by the @key branch.
        }

        public List<String> suggestNames(Player player, Command command, String[] args) {
            return Arrays.asList("alpha", "beta");
        }

        @CmdMapping(format = "hint <choice>")
        public void hintCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "choice", suggest = "aHintOnlyKeyForNotation") String choice) {
            // Test stub -- "aHintOnlyKeyForNotation" names no method, exercising the unchanged
            // i18n hint fallback (D-07 leaves it deliberately unchanged).
        }

        // Wrong-attribute trap (D-07 Pitfall 2 / T-05-28): the parameter's DISPLAY NAME
        // (@CmdParam.value()) starts with "@", but suggest() names a real method. Resolution
        // must be driven by suggest(), never by value().
        @CmdMapping(format = "trap <@trap>")
        public void trapCommand(@CmdSender CommandSender sender,
                                 @CmdParam(value = "@trap", suggest = "suggestTrap") String value) {
            // Test stub
        }

        public List<String> suggestTrap(Player player, Command command, String[] args) {
            return Arrays.asList("trapped");
        }

        @CmdMapping(format = "custom <opt>")
        public void customCommand(@CmdSender CommandSender sender,
                                   @CmdParam(value = "opt", suggest = "@fixtureCustomKey") String opt) {
            // Test stub -- a runtime-registered custom key, not one of the four built-ins.
        }
    }

    // ParitySuggestions, OldParityExecutor and NewParityExecutor -- the Task 3 parity fixtures --
    // were removed by plan 07-15 along with the ShellAndParityTests nested class above; their
    // sole purpose was comparing against the now-deleted AbstractCommandExecutor generation.
}
