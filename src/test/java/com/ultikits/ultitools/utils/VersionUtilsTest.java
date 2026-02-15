package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * VersionUtils 测试类
 * 测试版本工具类的方法签名和结构
 */
@DisplayName("VersionUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VersionUtilsTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("类应该是public的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(VersionUtils.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getUltiToolsNewestVersion方法应该存在")
        void getUltiToolsNewestVersionMethodShouldExist() throws Exception {
            Method method = VersionUtils.class.getDeclaredMethod("getUltiToolsNewestVersion");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

    }
}
