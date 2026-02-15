package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VersionUtils 测试类
 */
@DisplayName("VersionUtils 测试")
class VersionUtilsEnhancedTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("VersionUtils 类应该存在")
        void classShouldExist() {
            assertThat(VersionUtils.class).isNotNull();
        }

        @Test
        @DisplayName("VersionUtils 类应该是公开的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(VersionUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 getUltiToolsNewestVersion 方法")
        void shouldHaveGetNewestVersionMethod() throws Exception {
            Method method = VersionUtils.class.getMethod("getUltiToolsNewestVersion");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 pluginHasUpdate 方法")
        void shouldHavePluginHasUpdateMethod() throws Exception {
            Method method = VersionUtils.class.getMethod("pluginHasUpdate", String.class, String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getUltiToolsNewestVersion 应该是公开静态方法")
        void getNewestVersionShouldBePublicStatic() throws Exception {
            Method method = VersionUtils.class.getMethod("getUltiToolsNewestVersion");
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("pluginHasUpdate 应该是公开静态方法")
        void pluginHasUpdateShouldBePublicStatic() throws Exception {
            Method method = VersionUtils.class.getMethod("pluginHasUpdate", String.class, String.class);
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }
}
