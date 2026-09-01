package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * SecurityPolicy 测试类
 *
 * <p><b>GEN-07 (6.3.0):</b> {@code isSafeClassName}, {@code isSafeParameterType},
 * {@code addTrustedPackage} and {@code addDangerousClass} became unconditional no-ops (D-12) --
 * the three name-based classload filter layers they enforced never protected anything (they
 * evaluated already-trusted, already-loaded classes, not arbitrary untrusted input). This class
 * INVERTS every assertion that used to assert "refused" to now assert "allowed", rather than
 * deleting the coverage -- an inverted assertion is evidence the new no-op contract holds; a
 * deleted test would just be silence. {@code isSafeFileStructure} and {@code isValidModuleJar}
 * (the retained structural JAR guard, ROADMAP criterion 4) are untouched below.
 */
@DisplayName("SecurityPolicy 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SecurityPolicyTest {

    @BeforeEach
    void setUp() {
        // 重置状态
    }

    @Nested
    @DisplayName("isSafeClassName 方法测试 -- GEN-07: 现在无条件返回 true")
    class IsSafeClassNameTests {

        @Test
        @DisplayName("GEN-07 空输入边界: null类名现在应该返回true（此前为false）")
        void nullClassNameShouldNowReturnTrue() {
            assertThat(SecurityPolicy.isSafeClassName(null)).isTrue();
        }

        @Test
        @DisplayName("GEN-07 空输入边界: 空字符串类名现在应该返回true（此前为false）")
        void emptyClassNameShouldNowReturnTrue() {
            assertThat(SecurityPolicy.isSafeClassName("")).isTrue();
            assertThat(SecurityPolicy.isSafeClassName("   ")).isTrue();
        }

        @Test
        @DisplayName("原危险类现在应该返回true -- 该层已被移除")
        void dangerousClassesShouldNowReturnTrue() {
            String[] dangerousClasses = {
                "java.lang.ProcessBuilder",
                "java.lang.Runtime",
                "java.lang.System",
                "sun.misc.Unsafe",
                "javax.script.ScriptEngine"
            };

            for (String className : dangerousClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("exact-name blacklist layer is removed: " + className)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("原危险包前缀的类现在应该返回true -- 该层已被移除")
        void dangerousPackagePrefixShouldNowReturnTrue() {
            String[] dangerousPackageClasses = {
                "java.lang.reflect.Method",
                "java.security.AccessController",
                "sun.misc.Something",
                "jdk.internal.misc.Unsafe",
                "com.sun.something.Class",
                "javax.script.ScriptEngineManager",
                "java.rmi.Remote"
            };

            for (String className : dangerousPackageClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("dangerous package-prefix layer is removed: " + className)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("原不在信任列表中的类现在应该返回true -- 白名单层已被移除，这正是 #207 论点的量级")
        void untrustedClassesShouldNowReturnTrue() {
            String[] untrustedClasses = {
                "com.evil.malware.Payload",
                "hacker.tools.Exploit",
                "random.package.SomeClass"
            };

            for (String className : untrustedClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("trusted-package whitelist layer is removed: " + className)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("信任的包前缀类仍然应该返回true")
        void trustedClassesShouldReturnTrue() {
            String[] trustedClasses = {
                "com.ultikits.ultitools.SomeClass",
                "org.bukkit.entity.Player",
                "net.kyori.adventure.text.Component"
            };

            for (String className : trustedClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("Should allow trusted class: " + className)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("包含可疑关键词的类现在应该返回true -- 关键词层已被移除，CLAUDE.md gotcha 9 的 FileManager 场景")
        void suspiciousKeywordsShouldNowReturnTrue() {
            // 这些类在信任包中且包含可疑关键词 -- 此前会被第4层拒绝（记录在 CLAUDE.md gotcha 9 中的
            // 真实缺陷：一个信任包内、命名合法的类会被子串匹配误伤）。GEN-07 移除该层修复了这个缺陷。
            String[] suspiciousClasses = {
                "com.ultikits.ultitools.ProcessHelper",
                "com.ultikits.ultitools.RuntimeManager",
                "com.ultikits.ultitools.ScriptExecutor",
                "com.ultikits.plugins.foo.FileManager"
            };

            for (String className : suspiciousClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("suspicious-keyword layer is removed: " + className)
                    .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("isSafeParameterType 方法测试 -- GEN-07: 现在无条件返回 true")
    class IsSafeParameterTypeTests {

        @Test
        @DisplayName("GEN-07 空输入边界: null参数现在应该返回true（此前为false）")
        void nullParameterShouldNowReturnTrue() {
            assertThat(SecurityPolicy.isSafeParameterType(null)).isTrue();
        }

        @Test
        @DisplayName("基本数据类型应该返回true")
        void primitiveTypesShouldReturnTrue() {
            assertThat(SecurityPolicy.isSafeParameterType(int.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(long.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(double.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(float.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(boolean.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(char.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(byte.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(short.class)).isTrue();
        }

        @Test
        @DisplayName("包装类型应该返回true")
        void wrapperTypesShouldReturnTrue() {
            assertThat(SecurityPolicy.isSafeParameterType(Integer.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Long.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Double.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Float.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Boolean.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Character.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Byte.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(Short.class)).isTrue();
        }

        @Test
        @DisplayName("String类型应该返回true")
        void stringTypeShouldReturnTrue() {
            assertThat(SecurityPolicy.isSafeParameterType(String.class)).isTrue();
        }

        @Test
        @DisplayName("常用集合类型应该返回true")
        void collectionTypesShouldReturnTrue() {
            assertThat(SecurityPolicy.isSafeParameterType(java.util.List.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(java.util.Map.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(java.util.Set.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(java.util.ArrayList.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(java.util.HashMap.class)).isTrue();
            assertThat(SecurityPolicy.isSafeParameterType(java.util.HashSet.class)).isTrue();
        }

        @Test
        @DisplayName("原不安全的参数类型现在也应该返回true -- 白名单层已被移除")
        void formerlyUnsafeTypeShouldNowReturnTrue() {
            // java.lang.ProcessBuilder matches none of the primitive/wrapper/collection checks and
            // is not under any TRUSTED_PACKAGE_PREFIXES entry -- the pre-GEN-07 implementation
            // returned false for it via the trailing logSecurityViolation branch.
            assertThat(SecurityPolicy.isSafeParameterType(ProcessBuilder.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("isSafeFileStructure 方法测试 -- ROADMAP 标准4：结构性防护保留不变")
    class IsSafeFileStructureTests {

        @Test
        @DisplayName("正常文件大小和条目数应该返回true")
        void normalFileShouldReturnTrue() {
            assertThat(SecurityPolicy.isSafeFileStructure(1024 * 1024, 100)).isTrue();
            assertThat(SecurityPolicy.isSafeFileStructure(50 * 1024 * 1024, 5000)).isTrue();
        }

        @Test
        @DisplayName("超过100MB的文件应该返回false")
        void tooLargeFileShouldReturnFalse() {
            long maxSize = 100 * 1024 * 1024;
            assertThat(SecurityPolicy.isSafeFileStructure(maxSize + 1, 100)).isFalse();
            assertThat(SecurityPolicy.isSafeFileStructure(200 * 1024 * 1024, 100)).isFalse();
        }

        @Test
        @DisplayName("超过10000个条目应该返回false")
        void tooManyEntriesShouldReturnFalse() {
            assertThat(SecurityPolicy.isSafeFileStructure(1024 * 1024, 10001)).isFalse();
            assertThat(SecurityPolicy.isSafeFileStructure(1024 * 1024, 20000)).isFalse();
        }

        @Test
        @DisplayName("边界条件应该正确处理")
        void boundaryConditionsShouldBeHandled() {
            // 刚好在限制内
            assertThat(SecurityPolicy.isSafeFileStructure(100 * 1024 * 1024, 10000)).isTrue();
            // 零值
            assertThat(SecurityPolicy.isSafeFileStructure(0, 0)).isTrue();
        }
    }

    @Nested
    @DisplayName("addTrustedPackage 方法测试 -- GEN-07: 现在是空操作")
    class AddTrustedPackageTests {

        @Test
        @DisplayName("调用后不应该修改 ClassloadFilterAudit 的信任包列表 -- 这是空操作")
        void addTrustedPackageIsNowANoOp() {
            String newPackage = "com.example.gen07-noop-probe";
            boolean containedBefore = ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES.contains(newPackage);

            SecurityPolicy.addTrustedPackage(newPackage);

            assertThat(containedBefore).isFalse();
            assertThat(ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES)
                    .as("addTrustedPackage must not mutate the shipped list any more (D-12)")
                    .doesNotContain(newPackage);
            // isSafeClassName is unconditionally true regardless, so this remains true whether or
            // not the (removed) mutation happened -- the doesNotContain assertion above is what
            // actually proves the no-op contract.
            assertThat(SecurityPolicy.isSafeClassName(newPackage + ".SomeClass")).isTrue();
        }

        @Test
        @DisplayName("null包名不应该导致异常")
        void nullPackageShouldNotThrow() {
            SecurityPolicy.addTrustedPackage(null);
            // 不应该抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("空字符串包名不应该导致异常")
        void emptyPackageShouldNotThrow() {
            SecurityPolicy.addTrustedPackage("");
            SecurityPolicy.addTrustedPackage("   ");
            // 不应该抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("addDangerousClass 方法测试 -- GEN-07: 现在是空操作")
    class AddDangerousClassTests {

        @Test
        @DisplayName("调用后不应该修改 ClassloadFilterAudit 的危险类列表 -- 这是空操作")
        void addDangerousClassIsNowANoOp() {
            String dangerousClass = "com.example.gen07-noop-probe.BadClass";
            boolean containedBefore = ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES.contains(dangerousClass);

            SecurityPolicy.addDangerousClass(dangerousClass);

            assertThat(containedBefore).isFalse();
            assertThat(ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES)
                    .as("addDangerousClass must not mutate the shipped list any more (D-12)")
                    .doesNotContain(dangerousClass);
            assertThat(SecurityPolicy.isSafeClassName(dangerousClass)).isTrue();
        }

        @Test
        @DisplayName("null类名不应该导致异常")
        void nullClassNameShouldNotThrow() {
            SecurityPolicy.addDangerousClass(null);
            // 不应该抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("getSecurityPolicySummary 方法测试")
    class GetSecurityPolicySummaryTests {

        @Test
        @DisplayName("应该返回非空摘要")
        void shouldReturnNonEmptySummary() {
            String summary = SecurityPolicy.getSecurityPolicySummary();
            assertThat(summary).isNotNull();
            assertThat(summary).isNotEmpty();
        }

        @Test
        @DisplayName("摘要应该包含关键信息")
        void summaryShouldContainKeyInfo() {
            String summary = SecurityPolicy.getSecurityPolicySummary();
            assertThat(summary).contains("Security Policy");
            assertThat(summary).contains("Trusted packages");
            assertThat(summary).contains("Dangerous classes");
        }
    }

    @Nested
    @DisplayName("GEN-07 一次性弃用警告 -- D-13: 每个JVM最多记录一次")
    class DeprecationWarningTests {

        private final List<LogRecord> capturedLogs = new ArrayList<>();
        private Handler captureHandler;
        private Logger securityPolicyLogger;

        @BeforeEach
        void captureLogs() {
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
            securityPolicyLogger = Logger.getLogger(SecurityPolicy.class.getName());
            securityPolicyLogger.addHandler(captureHandler);
        }

        @AfterEach
        void releaseLogs() {
            securityPolicyLogger.removeHandler(captureHandler);
        }

        @Test
        @DisplayName("数百次调用后，弃用警告最多只记录一次")
        void deprecationWarningFiresAtMostOnce() {
            // The guard is a process-wide AtomicBoolean, so an earlier test class in the same JVM
            // run may already have tripped it -- this test only asserts an upper bound, not that
            // it fires at all in this specific run (that would make test order load-bearing).
            for (int i = 0; i < 500; i++) {
                SecurityPolicy.isSafeClassName("com.ultikits.ultitools.SomeClass");
                SecurityPolicy.isSafeParameterType(String.class);
                SecurityPolicy.addTrustedPackage("com.example.warning-probe");
                SecurityPolicy.addDangerousClass("com.example.warning-probe.Bad");
            }

            long warningCount = capturedLogs.stream()
                    .filter(record -> Level.WARNING.equals(record.getLevel()))
                    .filter(record -> record.getMessage() != null
                            && record.getMessage().contains("GEN-07"))
                    .count();

            assertThat(warningCount)
                    .as("the GEN-07 deprecation warning must log at most once per JVM")
                    .isLessThanOrEqualTo(1);
        }
    }
}
