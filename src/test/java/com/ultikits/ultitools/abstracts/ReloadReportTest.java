package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #529: {@link ReloadReport} collects the parts of a module's reload that did not reload, and
 * gives readers an immutable view of them.
 */
@DisplayName("ReloadReport collects partial-reload reasons behind an immutable view (#529)")
class ReloadReportTest {

    @Test
    @DisplayName("a fresh report is not partial and has no reasons")
    void freshReportIsNotPartial() {
        ReloadReport report = new ReloadReport();

        assertThat(report.isPartial()).isFalse();
        assertThat(report.getPartialReasons()).isEmpty();
    }

    @Test
    @DisplayName("each recorded reason is kept, in order")
    void recordedReasonsAreKeptInOrder() {
        ReloadReport report = new ReloadReport();

        report.partial("scoreboard service did not restart");
        report.partial("recipes kept from memory");

        assertThat(report.isPartial()).isTrue();
        assertThat(report.getPartialReasons())
                .containsExactly("scoreboard service did not restart", "recipes kept from memory");
    }

    @Test
    @DisplayName("readers get an immutable snapshot")
    void readersGetAnImmutableSnapshot() {
        ReloadReport report = new ReloadReport();
        report.partial("first");
        List<String> reasons = report.getPartialReasons();

        report.partial("second");

        assertThat(reasons).containsExactly("first");
        assertThatThrownBy(() -> reasons.add("tampered")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a missing or blank reason still marks the reload partial, with a placeholder text")
    void blankReasonStillMarksPartial() {
        ReloadReport report = new ReloadReport();

        report.partial(null);
        report.partial("   ");

        assertThat(report.isPartial()).isTrue();
        assertThat(report.getPartialReasons()).hasSize(2).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("the class is public and final")
    void classIsPublicAndFinal() {
        assertThat(Modifier.isPublic(ReloadReport.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(ReloadReport.class.getModifiers())).isTrue();
    }
}
