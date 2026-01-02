package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.entities.PluginEntity;

/**
 * PluginInstallUtils 测试类
 * 测试插件安装工具类的方法签名和结构
 */
@DisplayName("PluginInstallUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("类应该是public的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(PluginInstallUtils.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getPluginList方法应该存在")
        void getPluginListMethodShouldExist() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(List.class);
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getPlugin方法应该存在")
        void getPluginMethodShouldExist() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(PluginEntity.class);
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("参数验证测试")
    class ParameterValidationTests {

        @Test
        @DisplayName("getPluginList应该接受正确的参数类型")
        void getPluginListShouldAcceptCorrectParameterTypes() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            Class<?>[] paramTypes = method.getParameterTypes();
            assertThat(paramTypes).containsExactly(int.class, int.class);
        }

        @Test
        @DisplayName("getPlugin应该接受正确的参数类型")
        void getPluginShouldAcceptCorrectParameterTypes() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            Class<?>[] paramTypes = method.getParameterTypes();
            assertThat(paramTypes).containsExactly(String.class);
        }
    }

    @Nested
    @DisplayName("PluginEntity 结构测试")
    class PluginEntityTests {

        @Test
        @DisplayName("PluginEntity类应该存在")
        void pluginEntityClassShouldExist() {
            assertThat(PluginEntity.class).isNotNull();
        }

        @Test
        @DisplayName("PluginEntity应该有正确的字段")
        void pluginEntityShouldHaveCorrectFields() throws Exception {
            assertThat(PluginEntity.class.getDeclaredField("id")).isNotNull();
            assertThat(PluginEntity.class.getDeclaredField("name")).isNotNull();
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("方法不应该声明受检异常")
        void methodsShouldNotDeclareCheckedExceptions() throws Exception {
            Method getPluginList = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            Method getPlugin = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            
            assertThat(getPluginList.getExceptionTypes()).isEmpty();
            assertThat(getPlugin.getExceptionTypes()).isEmpty();
        }
    }
}
