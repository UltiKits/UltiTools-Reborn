package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.utils.ModuleContent;
import com.ultikits.ultitools.utils.ModuleContentKind;

class LedgerContentAnswersTest {
    @Test
    void malformedRowIsReportedOnceWithTheModuleAndReasonBeforeQueries() {
        Logger logger = mock(Logger.class);
        var broken = mock(ContributionLedger.Row.class);
        var failure = new IllegalStateException("record cannot be read");
        when(broken.entries()).thenThrow(failure);
        var answers = LedgerContentAnswers.build(Map.of("Broken", broken), Set.of(), false, logger);
        verify(logger).log(eq(Level.WARNING), contains("module 'Broken': java.lang.IllegalStateException: record cannot be read"),
                eq(failure));
        assertThat(answers.contentOf(null, "Broken")).isEqualTo(ModuleContent.unknown());
        assertThat(answers.contentOf("broken", "Missing")).isEqualTo(ModuleContent.unknown());
        verifyNoMoreInteractions(logger);
    }

    @Test
    void invalidCountsAreDroppedBeforeConstructingTheAnswer() {
        Logger logger = mock(Logger.class);
        var result = LedgerContentAnswers.bounded("Demo", Map.of("zero", 0, "negative", -1, "tag", 2), logger);
        assertThat(result.kinds()).containsExactly(new ModuleContentKind("tag", 2));
        verifyNoInteractions(logger);
    }
}
