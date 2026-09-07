package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.uat.ExtractorException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ScheduledRowScanner} converts {@code @Scheduled} tick values to seconds and
 * reports a default (-1) period as {@code one_shot} rather than a negative number (Phase 10,
 * D-10-04). Fixture classes are nested here rather than in a shared fixtures file since they
 * exist only to exercise this one scanner's tick-conversion arithmetic.
 */
@DisplayName("ScheduledRowScanner")
class ScheduledRowScannerTest {

    @Test
    @DisplayName("converts a 20-tick delay and a 1200-tick period to delay_seconds 1 and period_seconds 60")
    void convertsTicksToSeconds() throws ExtractorException {
        List<Map<String, Object>> rows = new ScheduledRowScanner().scan("Fixture",
                Arrays.asList(RepeatingTask.class));

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("kind")).isEqualTo("scheduled");
        assertThat(row.get("delay_seconds")).isEqualTo(1L);
        assertThat(row.get("period_seconds")).isEqualTo(60L);
        assertThat(row.get("one_shot")).isEqualTo(false);
        assertThat(row.get("async")).isEqualTo(true);
    }

    @Test
    @DisplayName("a default-period method reports one_shot true and carries no negative period_seconds")
    void defaultPeriodIsOneShot() throws ExtractorException {
        List<Map<String, Object>> rows = new ScheduledRowScanner().scan("Fixture",
                Arrays.asList(OneShotTask.class));

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("one_shot")).isEqualTo(true);
        assertThat(row).doesNotContainKey("period_seconds");
    }

    @Test
    @DisplayName("a period of exactly 0 is one_shot too, matching TaskManager.scanAndSchedule's own period() <= 0 branch")
    void zeroPeriodIsAlsoOneShot() throws ExtractorException {
        List<Map<String, Object>> rows = new ScheduledRowScanner().scan("Fixture",
                Arrays.asList(ZeroPeriodTask.class));

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("one_shot")).isEqualTo(true);
        assertThat(row).doesNotContainKey("period_seconds");
    }

    @Test
    @DisplayName("a @Scheduled method inherited unchanged from a superclass is not attributed to the subclass -- the runtime never schedules it there")
    void inheritedScheduledMethodIsNotAttributedToTheSubclass() throws ExtractorException {
        List<Map<String, Object>> rows = new ScheduledRowScanner().scan("Fixture",
                Arrays.asList(SubclassInheritingScheduledMethod.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a @Scheduled method with parameters is excluded -- TaskManager.scanAndSchedule logs and skips it, never schedules it")
    void parameterizedScheduledMethodIsExcluded() throws ExtractorException {
        List<Map<String, Object>> rows = new ScheduledRowScanner().scan("Fixture",
                Arrays.asList(ParameterizedScheduledTask.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a @Scheduled method returning non-void is excluded -- TaskManager.scanAndSchedule logs and skips it, never schedules it")
    void nonVoidReturningScheduledMethodIsExcluded() throws ExtractorException {
        List<Map<String, Object>> rows = new ScheduledRowScanner().scan("Fixture",
                Arrays.asList(NonVoidScheduledTask.class));

        assertThat(rows).isEmpty();
    }

    static class RepeatingTask {
        @Scheduled(delay = 20, period = 1200, async = true)
        public void run() {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    static class OneShotTask {
        @Scheduled(delay = 0)
        public void run() {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    static class ZeroPeriodTask {
        @Scheduled(delay = 0, period = 0)
        public void run() {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    static class BaseWithScheduledMethod {
        @Scheduled(delay = 0)
        public void run() {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    static class SubclassInheritingScheduledMethod extends BaseWithScheduledMethod {
        // Deliberately declares no methods of its own -- run() is inherited unchanged, so
        // TaskManager.scanAndSchedule's getDeclaredMethods() call never finds it here.
    }

    static class ParameterizedScheduledTask {
        @Scheduled(delay = 0)
        public void run(String unexpectedParameter) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    static class NonVoidScheduledTask {
        @Scheduled(delay = 0)
        public int run() {
            return 0;
        }
    }
}
