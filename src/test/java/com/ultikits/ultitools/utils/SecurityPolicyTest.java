package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * SecurityPolicy 测试类
 */
@DisplayName("SecurityPolicy 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SecurityPolicyTest {

    @BeforeEach
    void setUp() {
        // 重置状态
    }

    @Nested
    @DisplayName("isSafeClassName 方法测试")
    class IsSafeClassNameTests {

        @Test
        @DisplayName("null类名应该返回false")
        void nullClassNameShouldReturnFalse() {
            assertThat(SecurityPolicy.isSafeClassName(null)).isFalse();
        }

        @Test
        @DisplayName("空字符串类名应该返回false")
        void emptyClassNameShouldReturnFalse() {
            assertThat(SecurityPolicy.isSafeClassName("")).isFalse();
            assertThat(SecurityPolicy.isSafeClassName("   ")).isFalse();
        }

        @Test
        @DisplayName("危险类应该返回false")
        void dangerousClassesShouldReturnFalse() {
            String[] dangerousClasses = {
                "java.lang.ProcessBuilder",
                "java.lang.Runtime",
                "java.lang.System",
                "sun.misc.Unsafe",
                "javax.script.ScriptEngine"
            };
            
            for (String className : dangerousClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("Should block dangerous class: " + className)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("危险包前缀的类应该返回false")
        void dangerousPackagePrefixShouldReturnFalse() {
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
                    .as("Should block dangerous package: " + className)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("不在信任列表中的类应该返回false")
        void untrustedClassesShouldReturnFalse() {
            String[] untrustedClasses = {
                "com.evil.malware.Payload",
                "hacker.tools.Exploit",
                "random.package.SomeClass"
            };
            
            for (String className : untrustedClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("Should block untrusted class: " + className)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("信任的包前缀类应该返回true")
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
        @DisplayName("包含可疑关键词的类应该返回false")
        void suspiciousKeywordsShouldReturnFalse() {
            // 注意：这些类在信任包中但包含可疑关键词
            String[] suspiciousClasses = {
                "com.ultikits.ultitools.ProcessHelper",
                "com.ultikits.ultitools.RuntimeManager",
                "com.ultikits.ultitools.ScriptExecutor"
            };
            
            for (String className : suspiciousClasses) {
                assertThat(SecurityPolicy.isSafeClassName(className))
                    .as("Should block suspicious keyword in: " + className)
                    .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("isSafeParameterType 方法测试")
    class IsSafeParameterTypeTests {

        @Test
        @DisplayName("null参数应该返回false")
        void nullParameterShouldReturnFalse() {
            assertThat(SecurityPolicy.isSafeParameterType(null)).isFalse();
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
    }

    @Nested
    @DisplayName("isSafeFileStructure 方法测试")
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
    @DisplayName("addTrustedPackage 方法测试")
    class AddTrustedPackageTests {

        @Test
        @DisplayName("添加信任包后应该可以通过验证")
        void addedPackageShouldBeTrusted() {
            String newPackage = "com.example.trusted";
            SecurityPolicy.addTrustedPackage(newPackage);
            
            // 验证新包被添加
            assertThat(SecurityPolicy.isSafeClassName("com.example.trusted.SomeClass")).isTrue();
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
    @DisplayName("addDangerousClass 方法测试")
    class AddDangerousClassTests {

        @Test
        @DisplayName("添加危险类后应该被阻止")
        void addedDangerousClassShouldBeBlocked() {
            String dangerousClass = "com.example.dangerous.BadClass";
            SecurityPolicy.addDangerousClass(dangerousClass);
            
            // 验证新添加的危险类被阻止
            assertThat(SecurityPolicy.isSafeClassName(dangerousClass)).isFalse();
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
}
