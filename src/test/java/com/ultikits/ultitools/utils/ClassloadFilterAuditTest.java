package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Coverage for {@link ClassloadFilterAudit} (D-14, GEN-07): the four name-based filter layers
 * {@code SecurityPolicy} used to enforce, relocated here as a package-private, observe-only
 * evaluator that records what the removed layers would have refused without refusing anything
 * itself.
 * <br>
 * {@link ClassloadFilterAudit}（D-14，GEN-07）的覆盖测试：{@code SecurityPolicy} 曾经用于强制执行的
 * 四个基于名称的过滤层，被迁移到这个包私有、只观察不裁决的评估器中——记录被移除的层原本会拒绝什么，
 * 但自身不拒绝任何东西。
 */
@DisplayName("ClassloadFilterAudit 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ClassloadFilterAuditTest {

    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Handler captureHandler;
    private Logger auditLogger;
    private Locale originalLocale;

    @BeforeEach
    void setUp() {
        capturedLogs.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedLogs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        captureHandler.setLevel(Level.ALL);
        auditLogger = Logger.getLogger(ClassloadFilterAudit.class.getName());
        auditLogger.setLevel(Level.ALL);
        auditLogger.addHandler(captureHandler);
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void tearDown() {
        auditLogger.removeHandler(captureHandler);
        Locale.setDefault(originalLocale);
    }

    @Nested
    @DisplayName("Test 1: exact-name blacklist layer")
    class BlacklistLayerTests {

        @Test
        @DisplayName("java.lang.ProcessBuilder is attributed to the exact-name blacklist layer")
        void blacklistedClassIsAttributed() {
            assertThat(ClassloadFilterAudit.classify("java.lang.ProcessBuilder"))
                    .isEqualTo(ClassloadFilterAudit.Layer.EXACT_BLACKLIST);
        }

        @Test
        @DisplayName("com.ultikits.ultitools.UltiTools is not attributed to any layer")
        void trustedFrameworkClassIsNotAttributed() {
            assertThat(ClassloadFilterAudit.classify("com.ultikits.ultitools.UltiTools")).isNull();
        }
    }

    @Nested
    @DisplayName("Test 2: dangerous package-prefix layer")
    class PackagePrefixLayerTests {

        @Test
        @DisplayName("java.security.AccessController is attributed to the package-prefix layer")
        void dangerousPrefixIsAttributed() {
            // Not also in the exact-name blacklist, unlike java.lang.reflect.Method, so this
            // isolates the package-prefix layer from the attribution-order case (Test 5).
            assertThat(ClassloadFilterAudit.classify("java.security.AccessController"))
                    .isEqualTo(ClassloadFilterAudit.Layer.PACKAGE_PREFIX);
        }

        @Test
        @DisplayName("org.bukkit.Bukkit is not attributed to any layer")
        void bukkitClassIsNotAttributed() {
            assertThat(ClassloadFilterAudit.classify("org.bukkit.Bukkit")).isNull();
        }
    }

    @Nested
    @DisplayName("Test 3: trusted-package whitelist layer -- the magnitude of #207's thesis")
    class WhitelistLayerTests {

        @Test
        @DisplayName("a third-party class is attributed to the whitelist layer")
        void thirdPartyClassIsAttributed() {
            assertThat(ClassloadFilterAudit.classify("com.example.thirdparty.Foo"))
                    .isEqualTo(ClassloadFilterAudit.Layer.WHITELIST);
        }

        @Test
        @DisplayName("com.ultikits.plugins.foo.Bar is not attributed to any layer")
        void trustedPluginClassIsNotAttributed() {
            assertThat(ClassloadFilterAudit.classify("com.ultikits.plugins.foo.Bar")).isNull();
        }
    }

    @Nested
    @DisplayName("Test 4: suspicious-keyword layer -- CLAUDE.md gotcha 9's FileManager case")
    class KeywordLayerTests {

        @Test
        @DisplayName("a trusted-package class whose name contains 'file' is attributed to the keyword layer")
        void fileManagerInTrustedPackageIsAttributed() {
            assertThat(ClassloadFilterAudit.classify("com.ultikits.plugins.foo.FileManager"))
                    .isEqualTo(ClassloadFilterAudit.Layer.KEYWORD);
        }
    }

    @Nested
    @DisplayName("Test 5: layer attribution order")
    class AttributionOrderTests {

        @Test
        @DisplayName("a name matching two layers is attributed to the first one evaluated")
        void firstMatchingLayerWins() {
            // java.lang.reflect.Method is both an exact SYSTEM_DANGEROUS_CLASSES entry AND
            // matches the java.lang.reflect DANGEROUS_PACKAGE_PREFIXES entry. isSafeClassName
            // evaluated the exact blacklist first, so that is what must win here too -- the
            // per-layer breakdown only sums correctly if attribution never double-counts.
            assertThat(ClassloadFilterAudit.classify("java.lang.reflect.Method"))
                    .isEqualTo(ClassloadFilterAudit.Layer.EXACT_BLACKLIST);
        }
    }

    @Nested
    @DisplayName("Test 6: GEN-07 encoding edge -- locale-stable keyword matching")
    class LocaleStabilityTests {

        @Test
        @DisplayName("a capital-I keyword match is identical under English and Turkish default locales")
        void localeDoesNotAffectAttribution() {
            // Capital "I" inside the "file" keyword: under a Turkish default locale,
            // String#toLowerCase() (no-arg, default-locale) maps 'I' to a dotless 'ı', not 'i' --
            // the exact latent defect the removed code's className.toLowerCase() carried. Using
            // Locale.ROOT explicitly must produce the same attribution regardless of the JVM's
            // default locale.
            String className = "com.ultikits.plugins.FIleManager";

            Locale.setDefault(Locale.ENGLISH);
            ClassloadFilterAudit.Layer underEnglish = ClassloadFilterAudit.classify(className);

            Locale.setDefault(Locale.forLanguageTag("tr"));
            ClassloadFilterAudit.Layer underTurkish = ClassloadFilterAudit.classify(className);

            assertThat(underEnglish).isEqualTo(ClassloadFilterAudit.Layer.KEYWORD);
            assertThat(underTurkish)
                    .as("attribution must not depend on the JVM default locale")
                    .isEqualTo(underEnglish);
        }
    }

    @Nested
    @DisplayName("Test 7: GEN-07 empty-input edge")
    class EmptyInputTests {

        @Test
        @DisplayName("null produces no attribution and does not throw")
        void nullProducesNoAttribution() {
            assertThat(ClassloadFilterAudit.classify(null)).isNull();
        }

        @Test
        @DisplayName("empty string produces no attribution and does not throw")
        void emptyStringProducesNoAttribution() {
            assertThat(ClassloadFilterAudit.classify("")).isNull();
        }

        @Test
        @DisplayName("record() is a no-op for a null or blank class name -- no FINE detail logged")
        void recordEmitsNoDetailForBlankInput() {
            ClassloadFilterAudit.record("empty-input-module", null);
            ClassloadFilterAudit.record("empty-input-module", "");
            ClassloadFilterAudit.emitSummary("empty-input-module");

            List<LogRecord> fineRecords = capturedLogs.stream()
                    .filter(r -> Level.FINE.equals(r.getLevel()))
                    .collect(Collectors.toList());
            assertThat(fineRecords).isEmpty();
        }
    }

    @Nested
    @DisplayName("Test 8/9: per-module INFO summary")
    class SummaryTests {

        @Test
        @DisplayName("Test 8: a module with no refusals still produces one INFO summary reporting zero")
        void emptyModuleProducesZeroSummary() {
            ClassloadFilterAudit.record("healthy-module", "com.ultikits.ultitools.UltiTools");
            ClassloadFilterAudit.emitSummary("healthy-module");

            List<LogRecord> infoRecords = capturedLogs.stream()
                    .filter(r -> Level.INFO.equals(r.getLevel()))
                    .collect(Collectors.toList());
            assertThat(infoRecords).hasSize(1);
            assertThat(infoRecords.get(0).getMessage())
                    .contains("healthy-module")
                    .contains("0 class(es)");

            List<LogRecord> fineRecords = capturedLogs.stream()
                    .filter(r -> Level.FINE.equals(r.getLevel()))
                    .collect(Collectors.toList());
            assertThat(fineRecords).isEmpty();
        }

        @Test
        @DisplayName("Test 9: a module with refusals across layers gets exactly one INFO summary, per-class detail only at FINE")
        void populatedModuleProducesOneSummaryWithBreakdown() {
            ClassloadFilterAudit.record("busy-module", "java.lang.ProcessBuilder"); // blacklist
            ClassloadFilterAudit.record("busy-module", "com.example.thirdparty.Foo"); // whitelist
            ClassloadFilterAudit.record("busy-module", "com.ultikits.plugins.foo.FileManager"); // keyword
            ClassloadFilterAudit.record("busy-module", "com.ultikits.ultitools.UltiTools"); // clean, no detail
            ClassloadFilterAudit.emitSummary("busy-module");

            List<LogRecord> infoRecords = capturedLogs.stream()
                    .filter(r -> Level.INFO.equals(r.getLevel()))
                    .collect(Collectors.toList());
            assertThat(infoRecords).hasSize(1);
            assertThat(infoRecords.get(0).getMessage())
                    .contains("busy-module")
                    .contains("3 class(es)");

            List<LogRecord> fineRecords = capturedLogs.stream()
                    .filter(r -> Level.FINE.equals(r.getLevel()))
                    .collect(Collectors.toList());
            assertThat(fineRecords).hasSize(3);
        }

        @Test
        @DisplayName("emitSummary resets the accumulator, so a second scan of the same module starts clean")
        void emitSummaryResetsAccumulator() {
            ClassloadFilterAudit.record("reused-module", "java.lang.ProcessBuilder");
            ClassloadFilterAudit.emitSummary("reused-module");
            capturedLogs.clear();

            ClassloadFilterAudit.emitSummary("reused-module");

            List<LogRecord> infoRecords = capturedLogs.stream()
                    .filter(r -> Level.INFO.equals(r.getLevel()))
                    .collect(Collectors.toList());
            assertThat(infoRecords).hasSize(1);
            assertThat(infoRecords.get(0).getMessage()).contains("0 class(es)");
        }
    }

    @Nested
    @DisplayName("Test 10: the evaluator never decides")
    class NeverDecidesTests {

        @Test
        @DisplayName("no declared method returns a boolean verdict")
        void noBooleanVerdictMethodExists() {
            for (Method method : ClassloadFilterAudit.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("method %s must not return boolean -- that would make it a verdict, "
                                + "not an observation", method.getName())
                        .isNotEqualTo(boolean.class)
                        .isNotEqualTo(Boolean.class);
            }
        }
    }
}
