package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link RegistryLedger}'s cumulative merge (D-07) and D-22's dual-source REMOVED
 * transition, plus GEN-04's deterministic JSON serialization ordering.
 */
@DisplayName("RegistryLedger tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RegistryLedgerTest {

    private static DeprecationEntry deprecatedEntry(String className, String member, String removeIn) {
        return DeprecationEntry.builder()
                .key(RegistryKey.forMember(className, member, Collections.emptyList()))
                .kind(DeprecationEntry.Kind.METHOD)
                .since("6.2.0")
                .forRemoval(true)
                .removeIn(removeIn)
                .replacement("Use something else.")
                .status(DeprecationEntry.Status.ANNOUNCED)
                .build();
    }

    @Nested
    @DisplayName("empty-input edge case (GEN-04)")
    class EmptyInputTests {

        @Test
        @DisplayName("Test 1: merging an empty prior ledger with a fresh scan does not throw")
        void mergingEmptyPriorLedgerDoesNotThrow() {
            RegistryLedger prior = RegistryLedger.empty();
            List<DeprecationEntry> freshScan = Collections.singletonList(
                    deprecatedEntry("com.ultikits.ultitools.Foo", "bar", "6.3.0"));

            RegistryLedger merged = RegistryLedger.merge(prior, freshScan, Collections.emptySet(), "6.3.0");

            assertThat(merged.entries()).hasSize(1);
            assertThat(merged.entries().get(0).getKey().toString())
                    .isEqualTo("com.ultikits.ultitools.Foo#bar()");
        }
    }

    @Nested
    @DisplayName("D-07 cumulative retention")
    class RetentionTests {

        @Test
        @DisplayName("Test 2: an entry gone from source but confirmed REMOVED by japicmp is retained, flipped")
        void goneFromSourceButJapicmpConfirmsIsRetainedAndFlipped() {
            DeprecationEntry priorEntry = deprecatedEntry("com.ultikits.ultitools.aop.CglibProxyFactory", "createProxy", "6.3.0");
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(priorEntry));
            List<DeprecationEntry> freshScan = Collections.emptyList();
            Set<RegistryKey> japicmpRemoved = new LinkedHashSet<>(Collections.singletonList(priorEntry.getKey()));

            RegistryLedger merged = RegistryLedger.merge(prior, freshScan, japicmpRemoved, "6.3.0");

            assertThat(merged.entries()).hasSize(1);
            DeprecationEntry retained = merged.entries().get(0);
            assertThat(retained.getStatus()).isEqualTo(DeprecationEntry.Status.REMOVED);
            assertThat(retained.getRemovedIn()).isEqualTo("6.3.0");
        }

        @Test
        @DisplayName("WR-05 (16-REVIEW-cloud.md): a same-release add-then-remove records removedIn as the version being built NOW, not the shim's own stale removeIn")
        void sameReleaseAddThenRemoveRecordsCurrentVersionNotTheEntrysOwnStaleRemoveIn() {
            // Mirrors plan 16-08's five CloudAuthManager/PluginInitiationUtils compatibility shims
            // exactly: added as @Deprecated(since = "6.3.0", forRemoval = true) with removeIn 6.4.0
            // (a full release out, the ordinary deprecation-window convention) by plan 16-08, then
            // deleted again by plan 16-09 -- before 6.3.0 itself ever shipped. The member never
            // existed in any released jar; recording removedIn as its own stale "6.4.0" describes a
            // member that was part of 6.3.0's shipped surface and only scheduled to disappear a
            // release later, which is false on both counts.
            DeprecationEntry priorEntry = DeprecationEntry.builder()
                    .key(RegistryKey.forMember(
                            "com.ultikits.ultitools.utils.CloudAuthManager", "commitTokenIfCurrent",
                            Collections.singletonList("com.ultikits.ultitools.entities.TokenEntity")))
                    .kind(DeprecationEntry.Kind.METHOD)
                    .since("6.3.0")
                    .forRemoval(true)
                    .removeIn("6.4.0")
                    .replacement("Compatibility shim only.")
                    .status(DeprecationEntry.Status.ANNOUNCED)
                    .build();
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(priorEntry));
            List<DeprecationEntry> freshScan = Collections.emptyList(); // the shim is gone from source
            Set<RegistryKey> japicmpRemoved = new LinkedHashSet<>(Collections.singletonList(priorEntry.getKey()));

            RegistryLedger merged = RegistryLedger.merge(prior, freshScan, japicmpRemoved, "6.3.0");

            DeprecationEntry retained = merged.entries().get(0);
            assertThat(retained.getStatus()).isEqualTo(DeprecationEntry.Status.REMOVED);
            assertThat(retained.getRemovedIn())
                    .as("the shim never survived to 6.4.0 -- it must be recorded as removed in THIS "
                            + "build (6.3.0), not the removeIn value it was originally scheduled for")
                    .isEqualTo("6.3.0")
                    .isNotEqualTo(priorEntry.getRemoveIn());
        }

        @Test
        @DisplayName("WR-05: the ordinary cross-release case is unaffected -- removedIn is still the version being built, which happens to equal the old removeIn")
        void ordinaryCrossReleaseRemovalStillRecordsTheBuildVersion() {
            // The common case this bug hid inside: deprecated in an earlier RELEASED version,
            // removed while building the very next one. removeIn and currentVersion coincide here
            // by construction (that member's whole point was to be removed in exactly this build),
            // so this must keep passing exactly as before the fix.
            DeprecationEntry priorEntry = deprecatedEntry("com.ultikits.ultitools.SomeClass", "oldMethod", "6.3.0");
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(priorEntry));
            Set<RegistryKey> japicmpRemoved = new LinkedHashSet<>(Collections.singletonList(priorEntry.getKey()));

            RegistryLedger merged = RegistryLedger.merge(prior, Collections.emptyList(), japicmpRemoved, "6.3.0");

            assertThat(merged.entries().get(0).getRemovedIn()).isEqualTo("6.3.0");
        }
    }

    @Nested
    @DisplayName("D-22 dual-source disagreement is fatal")
    class DisagreementTests {

        @Test
        @DisplayName("Test 3: source-gone but japicmp silent -> disagreement, merge fails")
        void sourceGoneButJapicmpSilentIsFatal() {
            DeprecationEntry priorEntry = deprecatedEntry("com.ultikits.ultitools.Foo", "bar", "6.3.0");
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(priorEntry));
            List<DeprecationEntry> freshScan = Collections.emptyList();

            assertThatThrownBy(() -> RegistryLedger.merge(prior, freshScan, Collections.emptySet(), "6.3.0"))
                    .isInstanceOf(LedgerMergeConflictException.class)
                    .hasMessageContaining("com.ultikits.ultitools.Foo#bar()");
        }

        @Test
        @DisplayName("Test 4: japicmp says REMOVED but source scan still finds the declaration -> disagreement, merge fails")
        void japicmpRemovedButSourceStillPresentIsFatal() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.Foo", "bar", Collections.emptyList());
            RegistryLedger prior = RegistryLedger.empty();
            List<DeprecationEntry> freshScan = Collections.singletonList(deprecatedEntry("com.ultikits.ultitools.Foo", "bar", "6.3.0"));
            Set<RegistryKey> japicmpRemoved = new LinkedHashSet<>(Collections.singletonList(key));

            assertThatThrownBy(() -> RegistryLedger.merge(prior, freshScan, japicmpRemoved, "6.3.0"))
                    .isInstanceOf(LedgerMergeConflictException.class)
                    .hasMessageContaining("com.ultikits.ultitools.Foo#bar()");
        }
    }

    @Nested
    @DisplayName("GEN-04 ordering: deterministic JSON serialization")
    class DeterminismTests {

        @Test
        @DisplayName("Test 5: identical content serializes to byte-identical JSON regardless of insertion order")
        void identicalContentSerializesByteIdentically() {
            DeprecationEntry a = deprecatedEntry("com.ultikits.ultitools.Alpha", "one", "6.3.0");
            DeprecationEntry b = deprecatedEntry("com.ultikits.ultitools.Beta", "two", "6.4.0");
            DeprecationEntry c = deprecatedEntry("com.ultikits.ultitools.Gamma", "three", "6.3.0");

            RegistryLedger ledgerA = RegistryLedger.of(Arrays.asList(a, b, c));
            RegistryLedger ledgerB = RegistryLedger.of(Arrays.asList(c, a, b));

            String jsonA1 = ledgerA.toJson();
            String jsonA2 = ledgerA.toJson();
            String jsonB = ledgerB.toJson();

            assertThat(jsonA1).isEqualTo(jsonA2);
            assertThat(jsonA1).isEqualTo(jsonB);
        }
    }
}
