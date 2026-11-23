package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests the retained file-size and archive-entry limits. */
@DisplayName("SecurityPolicy 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SecurityPolicyTest {

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

}
