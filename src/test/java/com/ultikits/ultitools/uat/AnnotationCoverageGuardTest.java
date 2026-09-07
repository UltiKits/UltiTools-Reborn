package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdSuggest;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.annotations.command.UsageLimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link SurfaceAssembler#COVERED_ANNOTATION_TYPES} to the extractor's locked scope (Phase
 * 10, D-10-04): a hardcoded allowlist assertion, not a directory listing, because three
 * annotation types under {@code annotations/command/} are deliberately out of scope forever and a
 * directory-scan guard would either fail permanently on those three or silently accept a
 * genuinely missing scanner (D-10-04's own discretion note).
 * <p>
 * Set equality is asserted in BOTH directions: removing a covered type from the extractor's
 * allowlist without also removing its scanner fails this test (a scanner exists but is no longer
 * declared covered), and adding a type to the allowlist without adding a scanner for it also
 * fails (declared covered but nothing extracts it) — exactly the two failure modes D-10-03's own
 * "the extractor moves when the annotations move" guard exists to catch.
 */
@DisplayName("AnnotationCoverageGuard")
class AnnotationCoverageGuardTest {

    /**
     * The thirteen in-scope annotation types, named explicitly here per D-10-04's locked scope —
     * NOT derived from a directory listing (see this class's own javadoc for why).
     */
    private static final Set<Class<? extends Annotation>> EXPECTED_COVERED = new HashSet<>(Arrays.asList(
            CmdExecutor.class,
            CmdMapping.class,
            CmdParam.class,
            CmdSender.class,
            CmdCD.class,
            CmdTarget.class,
            UsageLimit.class,
            ConfigEntity.class,
            ConfigEntry.class,
            EventListener.class,
            Scheduled.class,
            ConditionalOnConfig.class,
            UltiToolsModule.class));

    @Test
    @DisplayName("the extractor's allowlist equals the thirteen locked in-scope annotation types, in both directions")
    void coveredAnnotationTypesMatchLockedScopeExactly() {
        assertThat(SurfaceAssembler.COVERED_ANNOTATION_TYPES)
                .as("extractor allowlist vs D-10-04's locked scope")
                .isEqualTo(EXPECTED_COVERED);
    }

    @Test
    @DisplayName("three annotations under annotations/command/ are deliberately excluded, each for a stated reason")
    void excludedTypesAreDeliberateNotOmitted() {
        // @AsyncCommand describes HOW a command runs (which thread), not WHAT the surface is --
        // the command row already exists independent of this attribute.
        assertThat(SurfaceAssembler.COVERED_ANNOTATION_TYPES).doesNotContain(AsyncCommand.class);

        // @RunAsync is the older sibling of @AsyncCommand with identical intent; same reasoning.
        assertThat(SurfaceAssembler.COVERED_ANNOTATION_TYPES).doesNotContain(RunAsync.class);

        // @CmdSuggest's tab-completion contribution is already folded into the command row's
        // params[].suggest field via @CmdParam -- a dedicated scanner would duplicate
        // information already on the row rather than add new surface.
        assertThat(SurfaceAssembler.COVERED_ANNOTATION_TYPES).doesNotContain(CmdSuggest.class);
    }
}
