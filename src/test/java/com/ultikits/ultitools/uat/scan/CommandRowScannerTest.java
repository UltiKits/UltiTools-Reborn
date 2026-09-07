package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.SurfaceRow;
import com.ultikits.ultitools.uat.fixtures.Dup;
import com.ultikits.ultitools.uat.fixtures.DuplicateHolder;
import com.ultikits.ultitools.uat.fixtures.TracerCommands;
import com.ultikits.ultitools.uat.fixtures.classlevellimits.SubclassWithClassLevelLimits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
