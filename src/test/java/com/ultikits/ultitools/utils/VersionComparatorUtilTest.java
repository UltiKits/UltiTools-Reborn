package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * VersionComparatorUtil 测试类
 */
@DisplayName("VersionComparatorUtil 测试")
class VersionComparatorUtilTest {

    @Nested
    @DisplayName("compare 方法测试")
    class CompareMethodTests {

        @ParameterizedTest
        @CsvSource({
            "1.0.0, 1.0.0, 0",
            "2.0.0, 1.0.0, 1",
            "1.0.0, 2.0.0, -1",
            "1.1.0, 1.0.0, 1",
            "1.0.0, 1.1.0, -1",
            "1.0.1, 1.0.0, 1",
            "1.0.0, 1.0.1, -1"
        })
        @DisplayName("应该正确比较简单版本号")
        void shouldCompareSimpleVersions(String v1, String v2, int expectedSign) {
            int result = VersionComparatorUtil.compare(v1, v2);
            assertThat(Integer.signum(result)).isEqualTo(expectedSign);
        }

        @ParameterizedTest
        @CsvSource({
            "v1.0.0, 1.0.0, 0",
            "V1.0.0, 1.0.0, 0",
            "v2.0.0, v1.0.0, 1",
            "V2.0.0, V1.0.0, 1"
        })
        @DisplayName("应该正确处理版本号前缀 v/V")
        void shouldHandleVersionPrefix(String v1, String v2, int expectedSign) {
            int result = VersionComparatorUtil.compare(v1, v2);
            assertThat(Integer.signum(result)).isEqualTo(expectedSign);
        }

        @ParameterizedTest
        @CsvSource({
            "1.0.0-alpha, 1.0.0-beta, -1",
            "1.0.0-beta, 1.0.0-rc, -1",
            "1.0.0-rc, 1.0.0-SNAPSHOT, -1",
            "1.0.0-SNAPSHOT, 1.0.0, -1",
            "1.0.0-alpha.1, 1.0.0-alpha.2, -1"
        })
        @DisplayName("应该正确比较预发布版本")
        void shouldComparePreReleaseVersions(String v1, String v2, int expectedSign) {
            int result = VersionComparatorUtil.compare(v1, v2);
            assertThat(Integer.signum(result)).isEqualTo(expectedSign);
        }

        @Test
        @DisplayName("应该正确处理 null 参数")
        void shouldHandleNullParameters() {
            assertThat(VersionComparatorUtil.compare(null, null)).isEqualTo(0);
            assertThat(VersionComparatorUtil.compare(null, "1.0.0")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("1.0.0", null)).isGreaterThan(0);
        }

        @Test
        @DisplayName("应该正确处理空字符串")
        void shouldHandleEmptyStrings() {
            assertThat(VersionComparatorUtil.compare("", "")).isEqualTo(0);
            assertThat(VersionComparatorUtil.compare("", "1.0.0")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("1.0.0", "")).isGreaterThan(0);
        }

        @ParameterizedTest
        @CsvSource({
            "1.0, 1.0.0, 0",
            "1, 1.0.0, 0",
            "2, 1.0.0, 1",
            "1.2, 1.2.0, 0"
        })
        @DisplayName("应该正确比较不同长度的版本号")
        void shouldCompareDifferentLengthVersions(String v1, String v2, int expectedSign) {
            int result = VersionComparatorUtil.compare(v1, v2);
            assertThat(Integer.signum(result)).isEqualTo(expectedSign);
        }

        @Test
        @DisplayName("应该正确处理复杂版本号")
        void shouldHandleComplexVersions() {
            assertThat(VersionComparatorUtil.compare("1.0.0-alpha.1", "1.0.0-alpha.2")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("1.0.0-beta", "1.0.0-beta.1")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("1.0.0-rc1", "1.0.0-rc2")).isLessThan(0);
        }
    }

    @Nested
    @DisplayName("isLessThan 方法测试")
    class IsLessThanMethodTests {

        @Test
        @DisplayName("应该正确判断小于关系")
        void shouldCorrectlyDetermineLessThan() {
            assertThat(VersionComparatorUtil.isLessThan("1.0.0", "2.0.0")).isTrue();
            assertThat(VersionComparatorUtil.isLessThan("2.0.0", "1.0.0")).isFalse();
            assertThat(VersionComparatorUtil.isLessThan("1.0.0", "1.0.0")).isFalse();
        }

        @Test
        @DisplayName("应该正确判断预发布版本的小于关系")
        void shouldCorrectlyDeterminePreReleaseLessThan() {
            assertThat(VersionComparatorUtil.isLessThan("1.0.0-alpha", "1.0.0")).isTrue();
            assertThat(VersionComparatorUtil.isLessThan("1.0.0-alpha", "1.0.0-beta")).isTrue();
            assertThat(VersionComparatorUtil.isLessThan("1.0.0-SNAPSHOT", "1.0.0")).isTrue();
        }
    }

    @Nested
    @DisplayName("isGreaterThan 方法测试")
    class IsGreaterThanMethodTests {

        @Test
        @DisplayName("应该正确判断大于关系")
        void shouldCorrectlyDetermineGreaterThan() {
            assertThat(VersionComparatorUtil.isGreaterThan("2.0.0", "1.0.0")).isTrue();
            assertThat(VersionComparatorUtil.isGreaterThan("1.0.0", "2.0.0")).isFalse();
            assertThat(VersionComparatorUtil.isGreaterThan("1.0.0", "1.0.0")).isFalse();
        }

        @Test
        @DisplayName("应该正确判断预发布版本的大于关系")
        void shouldCorrectlyDeterminePreReleaseGreaterThan() {
            assertThat(VersionComparatorUtil.isGreaterThan("1.0.0", "1.0.0-alpha")).isTrue();
            assertThat(VersionComparatorUtil.isGreaterThan("1.0.0-beta", "1.0.0-alpha")).isTrue();
            assertThat(VersionComparatorUtil.isGreaterThan("1.0.0", "1.0.0-SNAPSHOT")).isTrue();
        }
    }

    @Nested
    @DisplayName("isEqual 方法测试")
    class IsEqualMethodTests {

        @Test
        @DisplayName("应该正确判断相等关系")
        void shouldCorrectlyDetermineEquality() {
            assertThat(VersionComparatorUtil.isEqual("1.0.0", "1.0.0")).isTrue();
            assertThat(VersionComparatorUtil.isEqual("v1.0.0", "1.0.0")).isTrue();
            assertThat(VersionComparatorUtil.isEqual("1.0", "1.0.0")).isTrue();
            assertThat(VersionComparatorUtil.isEqual("1.0.0", "2.0.0")).isFalse();
        }

        @Test
        @DisplayName("应该正确处理 null 和空字符串的相等")
        void shouldHandleNullAndEmptyEquality() {
            assertThat(VersionComparatorUtil.isEqual(null, null)).isTrue();
            assertThat(VersionComparatorUtil.isEqual("", "")).isTrue();
            assertThat(VersionComparatorUtil.isEqual(null, "")).isTrue();
        }
    }

    @Nested
    @DisplayName("COMPARATOR 测试")
    class ComparatorTests {

        @Test
        @DisplayName("COMPARATOR 应该能用于排序")
        void comparatorShouldWorkForSorting() {
            List<String> versions = Arrays.asList("2.0.0", "1.0.0", "1.5.0", "1.0.0-alpha", "3.0.0");
            versions.sort(VersionComparatorUtil.COMPARATOR);
            
            assertThat(versions).containsExactly("1.0.0-alpha", "1.0.0", "1.5.0", "2.0.0", "3.0.0");
        }

        @Test
        @DisplayName("COMPARATOR 不应该为 null")
        void comparatorShouldNotBeNull() {
            assertThat(VersionComparatorUtil.COMPARATOR).isNotNull();
        }

        @Test
        @DisplayName("COMPARATOR 应该是 Comparator 类型")
        void comparatorShouldBeComparatorType() {
            assertThat(VersionComparatorUtil.COMPARATOR).isInstanceOf(Comparator.class);
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("私有构造函数应该抛出 UnsupportedOperationException")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<VersionComparatorUtil> constructor = VersionComparatorUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            
            assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("预发布优先级测试")
    class PreReleasePriorityTests {

        @Test
        @DisplayName("alpha 应该小于 beta")
        void alphaShouldBeLessThanBeta() {
            assertThat(VersionComparatorUtil.compare("1.0.0-alpha", "1.0.0-beta")).isLessThan(0);
        }

        @Test
        @DisplayName("beta 应该小于 rc")
        void betaShouldBeLessThanRc() {
            assertThat(VersionComparatorUtil.compare("1.0.0-beta", "1.0.0-rc")).isLessThan(0);
        }

        @Test
        @DisplayName("rc 应该小于 SNAPSHOT")
        void rcShouldBeLessThanSnapshot() {
            assertThat(VersionComparatorUtil.compare("1.0.0-rc", "1.0.0-SNAPSHOT")).isLessThan(0);
        }

        @Test
        @DisplayName("SNAPSHOT 应该小于正式版")
        void snapshotShouldBeLessThanRelease() {
            assertThat(VersionComparatorUtil.compare("1.0.0-SNAPSHOT", "1.0.0")).isLessThan(0);
        }

        @Test
        @DisplayName("alpha 版本号应该正确比较")
        void alphaVersionsShouldCompareCorrectly() {
            assertThat(VersionComparatorUtil.compare("1.0.0-alpha1", "1.0.0-alpha2")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("1.0.0-alpha.1", "1.0.0-alpha.2")).isLessThan(0);
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("应该处理只有数字的版本")
        void shouldHandleNumericOnlyVersions() {
            assertThat(VersionComparatorUtil.compare("1", "2")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("10", "2")).isGreaterThan(0);
        }

        @Test
        @DisplayName("应该处理多级版本号")
        void shouldHandleMultiLevelVersions() {
            assertThat(VersionComparatorUtil.compare("1.2.3.4.5", "1.2.3.4.5")).isEqualTo(0);
            assertThat(VersionComparatorUtil.compare("1.2.3.4.5", "1.2.3.4.6")).isLessThan(0);
        }

        @Test
        @DisplayName("应该正确处理非数字段的字典序比较")
        void shouldHandleLexicographicComparison() {
            // 当非数字段不是预发布标识时，按字典序比较
            assertThat(VersionComparatorUtil.compare("1.0.0-a", "1.0.0-b")).isLessThan(0);
            assertThat(VersionComparatorUtil.compare("1.0.0-A", "1.0.0-b")).isLessThan(0); // 忽略大小写
        }
    }
}
