package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.SurfaceRow;
import com.ultikits.ultitools.uat.fixtures.AnnotatedButNotACommandExecutor;
import com.ultikits.ultitools.uat.fixtures.BothPermissionLevelsCommand;
import com.ultikits.ultitools.uat.fixtures.Dup;
import com.ultikits.ultitools.uat.fixtures.DuplicateFormatCommands;
import com.ultikits.ultitools.uat.fixtures.DuplicateHolder;
import com.ultikits.ultitools.uat.fixtures.HelpFormatMapping;
import com.ultikits.ultitools.uat.fixtures.HelpFormatMappingWithOverriddenHelpCommand;
import com.ultikits.ultitools.uat.fixtures.TracerCommands;
import com.ultikits.ultitools.uat.fixtures.UsageLimitContainConsoleFixtures;
import com.ultikits.ultitools.uat.fixtures.classlevellimits.AmbiguousCmdTargetComposition;
import com.ultikits.ultitools.uat.fixtures.classlevellimits.RedeclaringSubclassWithoutTarget;
import com.ultikits.ultitools.uat.fixtures.classlevellimits.SubclassWithClassLevelLimits;
import com.ultikits.ultitools.uat.fixtures.classlevellimits.UnannotatedSubclass;
import com.ultikits.ultitools.utils.ReflectionUtil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link CommandRowScanner} emits one {@code command} row per {@code @CmdMapping} format
 * plus exactly one {@code help} row per {@code @CmdExecutor} class (Phase 10, D-10-04), and that
 * two rows computing the same id raise {@link ExtractorException} naming both fully qualified
 * classes rather than merging (D-10-08).
 */
@DisplayName("CommandRowScanner")
class CommandRowScannerTest {

    @Test
    @DisplayName("emits one command row per @CmdMapping format plus one help row, for the tracer fixture")
    void emitsCommandAndHelpRows() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(TracerCommands.class));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(SurfaceRow::getId).doesNotHaveDuplicates();

        Map<String, Object> echoRow = fieldMapOf(rows, "onEcho");
        assertThat(echoRow.get("kind")).isEqualTo("command");
        assertThat(echoRow.get("format")).isEqualTo("echo <message>");
        assertThat(echoRow.get("trigger")).isEqualTo("/tracer echo <message>");
        assertThat(echoRow.get("permission")).isEqualTo("uat.tracer");
        assertThat(echoRow.get("aliases")).asList().containsExactly("tracer");
        assertThat(echoRow.get("senders")).asList().containsExactly("CommandSender");
        assertThat(echoRow.get("params")).asList().hasSize(1);
        assertThat(echoRow).doesNotContainKey("cooldown_seconds");

        Map<String, Object> pingRow = fieldMapOf(rows, "onPing");
        assertThat(pingRow.get("format")).isEqualTo("ping");
        assertThat(pingRow.get("cooldown_seconds")).isEqualTo(5);

        Map<String, Object> helpRow = fieldMapOf(rows, "handleHelp");
        assertThat(helpRow.get("kind")).isEqualTo("help");
        assertThat(helpRow.get("trigger")).isEqualTo("/tracer help");
    }

    @Test
    @DisplayName("@CmdCD/@UsageLimit declared at the class level on an inherited mapping are resolved onto the row, matching CooldownValidator/UsageLockValidator's own three-step resolution")
    void classLevelLimitsAreResolvedOntoInheritedMappingRows() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(SubclassWithClassLevelLimits.class));

        Map<String, Object> goRow = fieldMapOf(rows, "go");
        assertThat(goRow.get("cooldown_seconds")).isEqualTo(30);
        assertThat(goRow.get("usage_limit")).isEqualTo("SENDER");
    }

    @Test
    @DisplayName("@UsageLimit's ContainConsole is captured only when it opts out of the default (Codex review)")
    void usageLimitContainConsoleIsCapturedOnlyWhenNonDefault() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(UsageLimitContainConsoleFixtures.class));

        Map<String, Object> defaultRow = fieldMapOf(rows, "defaultContainConsole");
        assertThat(defaultRow.get("usage_limit")).isEqualTo("ALL");
        assertThat(defaultRow).doesNotContainKey("usage_limit_contain_console");

        Map<String, Object> optedOutRow = fieldMapOf(rows, "optedOutContainConsole");
        assertThat(optedOutRow.get("usage_limit")).isEqualTo("ALL");
        assertThat(optedOutRow.get("usage_limit_contain_console")).isEqualTo(false);
    }

    @Test
    @DisplayName("two @CmdMapping methods on the same class sharing an identical format collapse to one row -- matching BaseCommandExecutor.scanCommandMappings' own putIfAbsent, under which the second method can never dispatch")
    void duplicateFormatOnTheSameClassCollapsesToOneRow() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(DuplicateFormatCommands.class));

        List<Map<String, Object>> reloadRows = rows.stream()
                .map(SurfaceRow::toFieldMap)
                .filter(row -> "reload".equals(row.get("format")))
                .collect(java.util.stream.Collectors.toList());
        assertThat(reloadRows).hasSize(1);

        // Independently re-derive which method BaseCommandExecutor's own
        // scanCommandMappings would actually keep -- the first one
        // ReflectionUtil.getAllMethods visits for this format -- so this assertion holds
        // regardless of what order the JVM's own reflection happens to return, since both
        // the scanner under test and this re-derivation call the identical utility.
        String expectedWinner = null;
        for (Method method : ReflectionUtil.getAllMethods(DuplicateFormatCommands.class)) {
            CmdMapping mapping = method.getAnnotation(CmdMapping.class);
            if (mapping != null && "reload".equals(mapping.format())) {
                expectedWinner = method.getName();
                break;
            }
        }
        assertThat(reloadRows.get(0).get("member")).isEqualTo(expectedWinner);
    }

    @Test
    @DisplayName("a subclass that does not redeclare @CmdExecutor produces no rows -- the runtime never registers a Bukkit command for it")
    void unannotatedSubclassOfAnAnnotatedAncestorProducesNoRows() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(UnannotatedSubclass.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a concrete executor that redeclares @CmdExecutor but not @CmdTarget reports no class-level sender restriction -- the runtime's own direct lookup does not inherit the ancestor's")
    void redeclaringSubclassWithoutOwnCmdTargetReportsNoRestriction() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(RedeclaringSubclassWithoutTarget.class));

        Map<String, Object> goRow = fieldMapOf(rows, "go");
        assertThat(goRow).doesNotContainKey("cmd_target");
    }

    @Test
    @DisplayName("a class annotated @CmdExecutor but not implementing org.bukkit.command.CommandExecutor produces no rows -- CommandManager.registerAll never discovers it")
    void annotatedButNotACommandExecutorProducesNoRows() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(AnnotatedButNotACommandExecutor.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a class with an ambiguous class-versus-method @CmdTarget composition (LATERAL/WIDENING) produces no rows -- ComponentScanner refuses to register the whole class as a bean")
    void ambiguousCmdTargetCompositionProducesNoRows() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(AmbiguousCmdTargetComposition.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a @CmdMapping(format = \"help\") is skipped when getHelpCommand() is not overridden -- BaseCommandExecutor.onCommand intercepts the single-token \"help\" argument before matchMethod ever runs")
    void helpFormatMappingIsSkippedWhenHelpCommandIsNotOverridden() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(HelpFormatMapping.class));

        Map<String, Object> goRow = fieldMapOf(rows, "go");
        assertThat(goRow.get("format")).isEqualTo("go");

        boolean hasShadowedHelpMappingRow = rows.stream()
                .anyMatch(row -> "help".equals(row.toFieldMap().get("member")));
        assertThat(hasShadowedHelpMappingRow).isFalse();

        // Exactly two rows: the "go" command and the synthesized help row -- not three.
        assertThat(rows).hasSize(2);

        // getHelpCommand() is NOT overridden here, so the synthesized row's literal
        // "/alias help" claim is trustworthy.
        Map<String, Object> helpRow = fieldMapOf(rows, "handleHelp");
        assertThat(helpRow.get("trigger")).isEqualTo("/helpshadowed help");
    }

    @Test
    @DisplayName("a @CmdMapping(format = \"help\") is NOT skipped when getHelpCommand() IS overridden -- the scanner cannot know what the override returns without executing it")
    void helpFormatMappingIsNotSkippedWhenHelpCommandIsOverridden() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(HelpFormatMappingWithOverriddenHelpCommand.class));

        Map<String, Object> helpMappingRow = fieldMapOf(rows, "help");
        assertThat(helpMappingRow.get("format")).isEqualTo("help");
    }

    @Test
    @DisplayName("the synthesized help row does not claim a literal \"help\" trigger when getHelpCommand() is overridden -- the real token cannot be statically resolved (Codex review of PR #427, discovered via the fixture above)")
    void synthesizedHelpRowDoesNotClaimALiteralTriggerWhenHelpCommandIsOverridden() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(HelpFormatMappingWithOverriddenHelpCommand.class));

        Map<String, Object> helpRow = fieldMapOf(rows, "handleHelp");
        assertThat(helpRow.get("trigger")).asString()
                .doesNotContain("/helpoverridden help")
                .contains("cannot be statically resolved");
    }

    @Test
    @DisplayName("a class-level AND a method-level permission are both preserved, not one overriding the other -- PermissionValidator checks them conjunctively")
    void bothPermissionLevelsAreRepresentedConjunctively() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(BothPermissionLevelsCommand.class));

        Map<String, Object> goRow = fieldMapOf(rows, "go");
        assertThat(goRow.get("permission")).isEqualTo("uat.base");
        assertThat(goRow.get("mapping_permission")).isEqualTo("uat.mapping");
    }

    @Test
    @DisplayName("a row with no method-level permission carries no mapping_permission field at all")
    void noMethodLevelPermissionMeansNoMappingPermissionField() throws ExtractorException {
        List<SurfaceRow> rows = new CommandRowScanner()
                .scan("Fixture", Arrays.asList(TracerCommands.class));

        Map<String, Object> echoRow = fieldMapOf(rows, "onEcho");
        assertThat(echoRow).doesNotContainKey("mapping_permission");
    }

    @Test
    @DisplayName("two distinct classes computing the same row id raise ExtractorException naming both")
    void collidingRowsRaiseNamingBothClasses() {
        assertThatThrownBy(() -> new CommandRowScanner()
                .scan("Fixture", Arrays.asList(DuplicateHolder.Dup.class, Dup.class)))
                .isInstanceOf(ExtractorException.class)
                .hasMessageContaining(DuplicateHolder.Dup.class.getName())
                .hasMessageContaining(Dup.class.getName());
    }

    private static Map<String, Object> fieldMapOf(List<SurfaceRow> rows, String member) {
        Optional<SurfaceRow> match = rows.stream()
                .filter(row -> member.equals(row.toFieldMap().get("member")))
                .findFirst();
        assertThat(match).as("row for member " + member).isPresent();
        return match.get().toFieldMap();
    }
}
