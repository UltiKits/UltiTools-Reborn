package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * ConfigEditorUtils 测试类
 * 由于 ConfigEditorUtils 依赖 UltiTools 实例，这里主要测试方法签名和可访问性
 */
@DisplayName("ConfigEditorUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfigEditorUtilsTest {

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getConfigMapString方法应该存在")
        void getConfigMapStringMethodShouldExist() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getCommentMapString方法应该存在")
        void getCommentMapStringMethodShouldExist() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getCommentMapString");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("updateConfigMap方法应该存在")
        void updateConfigMapMethodShouldExist() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }

    @Nested
    @DisplayName("方法可见性测试")
    class MethodVisibilityTests {

        @Test
        @DisplayName("getConfigMapString方法应该是受保护的静态方法")
        void getConfigMapStringShouldBeProtectedStatic() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getCommentMapString方法应该是受保护的静态方法")
        void getCommentMapStringShouldBeProtectedStatic() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getCommentMapString");
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("updateConfigMap方法应该是受保护的静态方法")
        void updateConfigMapShouldBeProtectedStatic() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("ConfigEditorUtils类应该存在")
        void classShouldExist() {
            assertThat(ConfigEditorUtils.class).isNotNull();
        }

        @Test
        @DisplayName("ConfigEditorUtils类应该是公开的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(ConfigEditorUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("ConfigEditorUtils类不应该是抽象的")
        void classShouldNotBeAbstract() {
            assertThat(Modifier.isAbstract(ConfigEditorUtils.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("ConfigEditorUtils类不应该是final的")
        void classShouldNotBeFinal() {
            assertThat(Modifier.isFinal(ConfigEditorUtils.class.getModifiers())).isFalse();
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("updateConfigMap方法应该声明IOException")
        void updateConfigMapShouldDeclareIOException() throws Exception {
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            
            assertThat(exceptionTypes).hasSize(1);
            assertThat(exceptionTypes[0]).isEqualTo(java.io.IOException.class);
        }
    }

    @Nested
    @DisplayName("功能测试 - 使用 Mock")
    class FunctionalTestsWithMock {

        private MockedStatic<UltiTools> mockedUltiTools;
        private UltiTools mockUltiToolsInstance;
        private ConfigManager mockConfigManager;

        @BeforeEach
        void setUp() {
            mockUltiToolsInstance = mock(UltiTools.class);
            mockConfigManager = mock(ConfigManager.class);
            
            mockedUltiTools = mockStatic(UltiTools.class);
            mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiToolsInstance);
            when(mockUltiToolsInstance.getConfigManager()).thenReturn(mockConfigManager);
        }

        @AfterEach
        void tearDown() {
            if (mockedUltiTools != null) {
                mockedUltiTools.close();
            }
        }

        @Test
        @DisplayName("getConfigMapString 应该调用 ConfigManager.toJson()")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void getConfigMapStringShouldCallToJson() throws Exception {
            String expectedJson = "{\"plugin\":{\"config.yml\":{\"key\":\"value\"}}}";
            when(mockConfigManager.toJson()).thenReturn(expectedJson);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isEqualTo(expectedJson);
            verify(mockConfigManager, times(1)).toJson();
        }

        @Test
        @DisplayName("getConfigMapString 应该返回空 JSON 对象字符串当没有配置时")
        void getConfigMapStringShouldReturnEmptyJsonWhenNoConfigs() throws Exception {
            when(mockConfigManager.toJson()).thenReturn("{}");
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isEqualTo("{}");
        }

        @Test
        @DisplayName("getCommentMapString 应该调用 ConfigManager.getComments()")
        void getCommentMapStringShouldCallGetComments() throws Exception {
            String expectedComments = "{\"plugin\":{\"config.yml\":{\"key\":\"This is a comment\"}}}";
            when(mockConfigManager.getComments()).thenReturn(expectedComments);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getCommentMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isEqualTo(expectedComments);
            verify(mockConfigManager, times(1)).getComments();
        }

        @Test
        @DisplayName("getCommentMapString 应该返回空 JSON 对象字符串当没有注释时")
        void getCommentMapStringShouldReturnEmptyJsonWhenNoComments() throws Exception {
            when(mockConfigManager.getComments()).thenReturn("{}");
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getCommentMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isEqualTo("{}");
        }

        @Test
        @DisplayName("updateConfigMap 应该调用 ConfigManager.loadFromJson()")
        void updateConfigMapShouldCallLoadFromJson() throws Exception {
            String configJson = "{\"plugin\":{\"config.yml\":{\"key\":\"newValue\"}}}";
            doNothing().when(mockConfigManager).loadFromJson(configJson);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            method.setAccessible(true);
            method.invoke(null, configJson);
            
            verify(mockConfigManager, times(1)).loadFromJson(configJson);
        }

        @Test
        @DisplayName("updateConfigMap 应该传递空字符串")
        void updateConfigMapShouldPassEmptyString() throws Exception {
            doNothing().when(mockConfigManager).loadFromJson("");
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            method.setAccessible(true);
            method.invoke(null, "");
            
            verify(mockConfigManager, times(1)).loadFromJson("");
        }

        @Test
        @DisplayName("updateConfigMap 当 ConfigManager 抛出 IOException 时应该传播异常")
        void updateConfigMapShouldPropagateIOException() throws Exception {
            String configJson = "{\"invalid\":\"json\"}";
            doThrow(new IOException("Failed to load config")).when(mockConfigManager).loadFromJson(anyString());
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            method.setAccessible(true);
            
            try {
                method.invoke(null, configJson);
            } catch (Exception e) {
                // 反射调用会将异常包装在 InvocationTargetException 中
                assertThat(e.getCause()).isInstanceOf(IOException.class);
                assertThat(e.getCause().getMessage()).contains("Failed to load config");
                return;
            }
            // 如果没有抛出异常，测试失败
            throw new AssertionError("Expected IOException to be thrown");
        }

        @Test
        @DisplayName("getConfigMapString 应该返回复杂的嵌套 JSON")
        void getConfigMapStringShouldReturnComplexNestedJson() throws Exception {
            String complexJson = "{\"MyPlugin\":{\"config.yml\":{\"settings\":{\"enabled\":true,\"maxPlayers\":100},\"messages\":{\"welcome\":\"Hello!\"}}}}";
            when(mockConfigManager.toJson()).thenReturn(complexJson);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isEqualTo(complexJson);
            assertThat(result).contains("MyPlugin");
            assertThat(result).contains("config.yml");
            assertThat(result).contains("enabled");
        }

        @Test
        @DisplayName("getCommentMapString 应该返回多个插件的注释")
        void getCommentMapStringShouldReturnMultiplePluginComments() throws Exception {
            String multiPluginComments = "{\"Plugin1\":{\"config.yml\":{\"key1\":\"comment1\"}},\"Plugin2\":{\"settings.yml\":{\"key2\":\"comment2\"}}}";
            when(mockConfigManager.getComments()).thenReturn(multiPluginComments);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getCommentMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).contains("Plugin1");
            assertThat(result).contains("Plugin2");
        }

        @Test
        @DisplayName("updateConfigMap 应该处理多个配置文件的更新")
        void updateConfigMapShouldHandleMultipleConfigFiles() throws Exception {
            String multiConfigJson = "{\"Plugin1\":{\"config1.yml\":{},\"config2.yml\":{}}}";
            doNothing().when(mockConfigManager).loadFromJson(multiConfigJson);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            method.setAccessible(true);
            method.invoke(null, multiConfigJson);
            
            verify(mockConfigManager).loadFromJson(multiConfigJson);
        }

        @Test
        @DisplayName("ConfigManager.toJson 返回 null 时 getConfigMapString 应该返回 null")
        void getConfigMapStringShouldReturnNullWhenToJsonReturnsNull() throws Exception {
            when(mockConfigManager.toJson()).thenReturn(null);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("ConfigManager.getComments 返回 null 时 getCommentMapString 应该返回 null")
        void getCommentMapStringShouldReturnNullWhenGetCommentsReturnsNull() throws Exception {
            when(mockConfigManager.getComments()).thenReturn(null);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getCommentMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryConditionTests {

        private MockedStatic<UltiTools> mockedUltiTools;
        private UltiTools mockUltiToolsInstance;
        private ConfigManager mockConfigManager;

        @BeforeEach
        void setUp() {
            mockUltiToolsInstance = mock(UltiTools.class);
            mockConfigManager = mock(ConfigManager.class);
            
            mockedUltiTools = mockStatic(UltiTools.class);
            mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiToolsInstance);
            when(mockUltiToolsInstance.getConfigManager()).thenReturn(mockConfigManager);
        }

        @AfterEach
        void tearDown() {
            if (mockedUltiTools != null) {
                mockedUltiTools.close();
            }
        }

        @Test
        @DisplayName("getConfigMapString 应该处理包含特殊字符的 JSON")
        void getConfigMapStringShouldHandleSpecialCharacters() throws Exception {
            String jsonWithSpecialChars = "{\"plugin\":{\"config.yml\":{\"message\":\"Hello\\nWorld\\t!\"}}}";
            when(mockConfigManager.toJson()).thenReturn(jsonWithSpecialChars);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).isEqualTo(jsonWithSpecialChars);
        }

        @Test
        @DisplayName("getConfigMapString 应该处理包含 Unicode 字符的 JSON")
        void getConfigMapStringShouldHandleUnicodeCharacters() throws Exception {
            String jsonWithUnicode = "{\"plugin\":{\"config.yml\":{\"message\":\"你好世界\"}}}";
            when(mockConfigManager.toJson()).thenReturn(jsonWithUnicode);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("getConfigMapString");
            method.setAccessible(true);
            String result = (String) method.invoke(null);
            
            assertThat(result).contains("你好世界");
        }

        @Test
        @DisplayName("updateConfigMap 应该处理非常大的 JSON 字符串")
        void updateConfigMapShouldHandleLargeJsonString() throws Exception {
            StringBuilder largeJson = new StringBuilder("{\"plugin\":{\"config.yml\":{\"data\":\"");
            for (int i = 0; i < 10000; i++) {
                largeJson.append("x");
            }
            largeJson.append("\"}}}");
            String json = largeJson.toString();
            
            doNothing().when(mockConfigManager).loadFromJson(json);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            method.setAccessible(true);
            method.invoke(null, json);
            
            verify(mockConfigManager).loadFromJson(json);
        }

        @Test
        @DisplayName("updateConfigMap 应该处理 null 参数")
        void updateConfigMapShouldHandleNullParameter() throws Exception {
            doNothing().when(mockConfigManager).loadFromJson(null);
            
            Method method = ConfigEditorUtils.class.getDeclaredMethod("updateConfigMap", String.class);
            method.setAccessible(true);
            method.invoke(null, (String) null);
            
            verify(mockConfigManager).loadFromJson(null);
        }
    }
}
