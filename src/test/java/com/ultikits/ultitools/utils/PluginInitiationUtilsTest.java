package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * PluginInitiationUtils 测试类
 * 测试插件初始化工具类的结构
 */
@DisplayName("PluginInitiationUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInitiationUtilsTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("类应该是public的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(PluginInitiationUtils.class.getModifiers())).isTrue();
        }
    }
}
