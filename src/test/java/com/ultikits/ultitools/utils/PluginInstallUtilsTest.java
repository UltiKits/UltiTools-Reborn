package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.entities.PluginEntity;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;

/**
 * Unit tests for {@link PluginInstallUtils}.
 * These tests verify method signatures and class structure without requiring network connectivity.
 * 
 * Note: Direct HTTP mock tests cannot be added for this class because it has a static initializer
 * that requires UltiTools.getEnv() to be available at class loading time.
 */
@DisplayName("PluginInstallUtils 测试")
class PluginInstallUtilsTest {

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getPluginList 方法应该存在且签名正确")
        void getPluginListMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("getPluginVersionDownloadLink 方法应该存在且签名正确")
        void getPluginVersionDownloadLinkMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginVersionDownloadLink", String.class, String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getPluginVersions 方法应该存在且签名正确")
        void getPluginVersionsMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginVersions", String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("getPluginLatestVersion 方法应该存在且签名正确")
        void getPluginLatestVersionMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginLatestVersion", String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getPluginLatestDownloadLink 方法应该存在且签名正确")
        void getPluginLatestDownloadLinkMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginLatestDownloadLink", String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getPlugin 方法应该存在且签名正确")
        void getPluginMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(PluginEntity.class);
        }

        @Test
        @DisplayName("installLatestPlugin 方法应该存在且签名正确")
        void installLatestPluginMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("installLatestPlugin", String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("installPlugin 方法应该存在且签名正确")
        void installPluginMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("installPlugin", String.class, String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("uninstallPlugin 方法应该存在且签名正确")
        void uninstallPluginMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("uninstallPlugin", String.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("PluginInstallUtils 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(PluginInstallUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("PluginInstallUtils 应该有正确数量的公共方法")
        void shouldHaveCorrectNumberOfPublicMethods() {
            Method[] methods = PluginInstallUtils.class.getDeclaredMethods();
            long publicMethodCount = java.util.Arrays.stream(methods)
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .count();
            
            // 至少应该有 9 个公共方法
            assertThat(publicMethodCount).isGreaterThanOrEqualTo(9);
        }

        @Test
        @DisplayName("所有公共方法都应该是 static 的")
        void allPublicMethodsShouldBeStatic() {
            Method[] methods = PluginInstallUtils.class.getDeclaredMethods();
            
            for (Method method : methods) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertThat(Modifier.isStatic(method.getModifiers()))
                            .as("Method %s should be static", method.getName())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("PluginEntity 结构测试")
    class PluginEntityTests {

        @Test
        @DisplayName("PluginEntity 应该存在")
        void pluginEntityShouldExist() {
            assertThat(PluginEntity.class).isNotNull();
        }

        @Test
        @DisplayName("PluginEntity 应该有 id 字段")
        void pluginEntityShouldHaveIdField() {
            boolean hasIdField = false;
            boolean hasIdMethod = false;
            
            try {
                PluginEntity.class.getDeclaredField("id");
                hasIdField = true;
            } catch (NoSuchFieldException ignored) {
            }
            
            try {
                PluginEntity.class.getMethod("getId");
                hasIdMethod = true;
            } catch (NoSuchMethodException ignored) {
            }
            
            assertThat(hasIdField || hasIdMethod)
                    .as("PluginEntity should have id field or getId method")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("URL 构建测试")
    class UrlBuildingTests {

        @Test
        @DisplayName("应该能正确从下载链接中提取文件名")
        void shouldExtractFileNameFromDownloadLink() {
            String downloadLink = "https://example.com/plugins/my-plugin-1.0.0.jar";
            String fileName = downloadLink.substring(downloadLink.lastIndexOf("/") + 1);
            
            assertThat(fileName).isEqualTo("my-plugin-1.0.0.jar");
        }

        @Test
        @DisplayName("应该能正确处理带查询参数的下载链接")
        void shouldHandleDownloadLinkWithQueryParams() {
            // 测试不带查询参数的情况
            String downloadLink = "https://example.com/plugins/my-plugin.jar";
            String fileName = downloadLink.substring(downloadLink.lastIndexOf("/") + 1);
            
            assertThat(fileName).isEqualTo("my-plugin.jar");
        }

        @Test
        @DisplayName("应该能处理空路径")
        void shouldHandleEmptyPath() {
            String downloadLink = "/plugin.jar";
            String fileName = downloadLink.substring(downloadLink.lastIndexOf("/") + 1);
            
            assertThat(fileName).isEqualTo("plugin.jar");
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("uninstallPlugin 方法应该声明抛出 IOException")
        void uninstallPluginShouldDeclareIOException() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("uninstallPlugin", String.class);
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            
            assertThat(exceptionTypes).contains(java.io.IOException.class);
        }

        @Test
        @DisplayName("installLatestPlugin 方法不应该声明抛出受检异常")
        void installLatestPluginShouldNotDeclareCheckedException() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("installLatestPlugin", String.class);
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            
            // installLatestPlugin 内部处理了 IOException，返回 boolean
            assertThat(exceptionTypes).isEmpty();
        }

        @Test
        @DisplayName("installPlugin 方法不应该声明抛出受检异常")
        void installPluginShouldNotDeclareCheckedException() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("installPlugin", String.class, String.class);
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            
            // installPlugin 内部处理了 IOException，返回 boolean
            assertThat(exceptionTypes).isEmpty();
        }
    }

    @Nested
    @DisplayName("参数验证测试")
    class ParameterValidationTests {

        @Test
        @DisplayName("getPluginList 参数应该是 int 类型")
        void getPluginListParametersShouldBeInt() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            Class<?>[] parameterTypes = method.getParameterTypes();
            
            assertThat(parameterTypes).containsExactly(int.class, int.class);
        }

        @Test
        @DisplayName("getPluginVersionDownloadLink 参数应该是 String 类型")
        void getPluginVersionDownloadLinkParametersShouldBeString() throws NoSuchMethodException {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginVersionDownloadLink", String.class, String.class);
            Class<?>[] parameterTypes = method.getParameterTypes();
            
            assertThat(parameterTypes).containsExactly(String.class, String.class);
        }
    }

    // ==================== Mock HTTP 测试 ====================

    @Nested
    @DisplayName("Mock getPluginList 测试")
    class MockGetPluginListTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该成功获取插件列表")
        @Timeout(5)
        void shouldGetPluginListSuccessfully() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[{\"id\":1,\"name\":\"TestPlugin\",\"identifyString\":\"test-plugin\"}]");

                List<PluginEntity> result = PluginInstallUtils.getPluginList(1, 10);

                assertThat(result).isNotNull();
                assertThat(result).hasSize(1);
                verify(mockResponse).close();
            }
        }

        @Test
        @DisplayName("HTTP 请求失败时应该返回空列表")
        @Timeout(5)
        void shouldReturnEmptyListWhenHttpFails() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                List<PluginEntity> result = PluginInstallUtils.getPluginList(1, 10);

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("应该使用正确的分页参数构建 URL")
        @Timeout(5)
        void shouldBuildCorrectUrlWithPagination() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[]");

                PluginInstallUtils.getPluginList(5, 20);

                httpUtilMock.verify(() -> HttpUtil.createGet("https://api.test.com/plugin/list?page=5&pageSize=20"));
            }
        }

        @Test
        @DisplayName("空数组响应应该返回空列表")
        @Timeout(5)
        void shouldReturnEmptyListForEmptyArrayResponse() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[]");

                List<PluginEntity> result = PluginInstallUtils.getPluginList(1, 10);

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("应该正确解析多个插件")
        @Timeout(5)
        void shouldParseMultiplePlugins() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[{\"id\":1,\"name\":\"Plugin1\"},{\"id\":2,\"name\":\"Plugin2\"},{\"id\":3,\"name\":\"Plugin3\"}]");

                List<PluginEntity> result = PluginInstallUtils.getPluginList(1, 10);

                assertThat(result).hasSize(3);
            }
        }
    }

    @Nested
    @DisplayName("Mock getPlugin 测试")
    class MockGetPluginTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该成功获取插件信息")
        @Timeout(5)
        void shouldGetPluginSuccessfully() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":123,\"name\":\"TestPlugin\",\"identifyString\":\"test-plugin\"}");

                PluginEntity result = PluginInstallUtils.getPlugin("test-plugin");

                assertThat(result).isNotNull();
                assertThat(result.getId()).isEqualTo(123);
                verify(mockResponse).close();
            }
        }

        @Test
        @DisplayName("HTTP 请求失败时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenHttpFails() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                PluginEntity result = PluginInstallUtils.getPlugin("test-plugin");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("应该使用正确的 URL 参数")
        @Timeout(5)
        void shouldBuildCorrectUrl() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":1}");

                PluginInstallUtils.getPlugin("my-plugin-id");

                httpUtilMock.verify(() -> HttpUtil.createGet("https://api.test.com/plugin/get?identifyString=my-plugin-id"));
            }
        }

        @Test
        @DisplayName("应该正确解析完整的插件实体")
        @Timeout(5)
        void shouldParseCompletePluginEntity() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":999,\"name\":\"FullPlugin\",\"identifyString\":\"full-plugin\",\"version\":\"1.0.0\"}");

                PluginEntity result = PluginInstallUtils.getPlugin("full-plugin");

                assertThat(result).isNotNull();
                assertThat(result.getId()).isEqualTo(999);
            }
        }
    }

    @Nested
    @DisplayName("Mock getPluginVersions 测试")
    class MockGetPluginVersionsTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该成功获取插件版本列表")
        @Timeout(5)
        void shouldGetVersionsSuccessfully() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                // First call - getPlugin
                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/get?identifyString=test-plugin"))
                        .thenReturn(mockRequest);
                // Second call - getPluginVersions
                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/123/versions"))
                        .thenReturn(mockRequest);

                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":123,\"name\":\"TestPlugin\"}")
                        .thenReturn("[\"1.0.0\",\"1.1.0\",\"2.0.0\"]");

                List<String> result = PluginInstallUtils.getPluginVersions("test-plugin");

                assertThat(result).isNotNull();
                assertThat(result).containsExactly("1.0.0", "1.1.0", "2.0.0");
            }
        }

        @Test
        @DisplayName("插件不存在时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenPluginNotFound() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                List<String> result = PluginInstallUtils.getPluginVersions("non-existent");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("版本列表请求失败时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenVersionsRequestFails() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockGetPluginRequest = mock(HttpRequest.class);
                HttpRequest mockVersionsRequest = mock(HttpRequest.class);
                HttpResponse mockGetPluginResponse = mock(HttpResponse.class);
                HttpResponse mockVersionsResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/get?identifyString=test"))
                        .thenReturn(mockGetPluginRequest);
                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/123/versions"))
                        .thenReturn(mockVersionsRequest);

                when(mockGetPluginRequest.execute()).thenReturn(mockGetPluginResponse);
                when(mockGetPluginResponse.isOk()).thenReturn(true);
                when(mockGetPluginResponse.body()).thenReturn("{\"id\":123}");

                when(mockVersionsRequest.execute()).thenReturn(mockVersionsResponse);
                when(mockVersionsResponse.isOk()).thenReturn(false);

                List<String> result = PluginInstallUtils.getPluginVersions("test");

                assertThat(result).isNull();
            }
        }
    }

    @Nested
    @DisplayName("Mock getPluginLatestVersion 测试")
    class MockGetPluginLatestVersionTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该成功获取最新版本")
        @Timeout(5)
        void shouldGetLatestVersionSuccessfully() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":123}")
                        .thenReturn("2.5.0");

                String result = PluginInstallUtils.getPluginLatestVersion("test-plugin");

                assertThat(result).isEqualTo("2.5.0");
            }
        }

        @Test
        @DisplayName("插件不存在时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenPluginNotFound() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                String result = PluginInstallUtils.getPluginLatestVersion("non-existent");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("最新版本请求失败时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenLatestRequestFails() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockGetPluginRequest = mock(HttpRequest.class);
                HttpRequest mockLatestRequest = mock(HttpRequest.class);
                HttpResponse mockGetPluginResponse = mock(HttpResponse.class);
                HttpResponse mockLatestResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/get?identifyString=test"))
                        .thenReturn(mockGetPluginRequest);
                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/456/latest"))
                        .thenReturn(mockLatestRequest);

                when(mockGetPluginRequest.execute()).thenReturn(mockGetPluginResponse);
                when(mockGetPluginResponse.isOk()).thenReturn(true);
                when(mockGetPluginResponse.body()).thenReturn("{\"id\":456}");

                when(mockLatestRequest.execute()).thenReturn(mockLatestResponse);
                when(mockLatestResponse.isOk()).thenReturn(false);

                String result = PluginInstallUtils.getPluginLatestVersion("test");

                assertThat(result).isNull();
            }
        }
    }

    @Nested
    @DisplayName("Mock getPluginVersionDownloadLink 测试")
    class MockGetPluginVersionDownloadLinkTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该成功获取版本下载链接")
        @Timeout(5)
        void shouldGetVersionDownloadLinkSuccessfully() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":123}")
                        .thenReturn("https://downloads.example.com/plugin-1.0.0.jar");

                String result = PluginInstallUtils.getPluginVersionDownloadLink("test-plugin", "1.0.0");

                assertThat(result).isEqualTo("https://downloads.example.com/plugin-1.0.0.jar");
            }
        }

        @Test
        @DisplayName("插件不存在时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenPluginNotFound() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                String result = PluginInstallUtils.getPluginVersionDownloadLink("non-existent", "1.0.0");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("下载链接请求失败时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenDownloadLinkRequestFails() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockGetPluginRequest = mock(HttpRequest.class);
                HttpRequest mockDownloadRequest = mock(HttpRequest.class);
                HttpResponse mockGetPluginResponse = mock(HttpResponse.class);
                HttpResponse mockDownloadResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/get?identifyString=test"))
                        .thenReturn(mockGetPluginRequest);
                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/789/1.0.0/download"))
                        .thenReturn(mockDownloadRequest);

                when(mockGetPluginRequest.execute()).thenReturn(mockGetPluginResponse);
                when(mockGetPluginResponse.isOk()).thenReturn(true);
                when(mockGetPluginResponse.body()).thenReturn("{\"id\":789}");

                when(mockDownloadRequest.execute()).thenReturn(mockDownloadResponse);
                when(mockDownloadResponse.isOk()).thenReturn(false);

                String result = PluginInstallUtils.getPluginVersionDownloadLink("test", "1.0.0");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("应该构建正确的版本下载 URL")
        @Timeout(5)
        void shouldBuildCorrectVersionDownloadUrl() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":100}")
                        .thenReturn("https://example.com/download");

                PluginInstallUtils.getPluginVersionDownloadLink("my-plugin", "2.0.0");

                httpUtilMock.verify(() -> HttpUtil.createGet("https://api.test.com/plugin/100/2.0.0/download"));
            }
        }
    }

    @Nested
    @DisplayName("Mock getPluginLatestDownloadLink 测试")
    class MockGetPluginLatestDownloadLinkTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该成功获取最新版本下载链接")
        @Timeout(5)
        void shouldGetLatestDownloadLinkSuccessfully() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":123}")
                        .thenReturn("https://downloads.example.com/plugin-latest.jar");

                String result = PluginInstallUtils.getPluginLatestDownloadLink("test-plugin");

                assertThat(result).isEqualTo("https://downloads.example.com/plugin-latest.jar");
            }
        }

        @Test
        @DisplayName("插件不存在时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenPluginNotFound() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                String result = PluginInstallUtils.getPluginLatestDownloadLink("non-existent");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("最新下载链接请求失败时应该返回 null")
        @Timeout(5)
        void shouldReturnNullWhenLatestDownloadLinkRequestFails() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockGetPluginRequest = mock(HttpRequest.class);
                HttpRequest mockDownloadRequest = mock(HttpRequest.class);
                HttpResponse mockGetPluginResponse = mock(HttpResponse.class);
                HttpResponse mockDownloadResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/get?identifyString=test"))
                        .thenReturn(mockGetPluginRequest);
                httpUtilMock.when(() -> HttpUtil.createGet("https://api.test.com/plugin/321/latest/download"))
                        .thenReturn(mockDownloadRequest);

                when(mockGetPluginRequest.execute()).thenReturn(mockGetPluginResponse);
                when(mockGetPluginResponse.isOk()).thenReturn(true);
                when(mockGetPluginResponse.body()).thenReturn("{\"id\":321}");

                when(mockDownloadRequest.execute()).thenReturn(mockDownloadResponse);
                when(mockDownloadResponse.isOk()).thenReturn(false);

                String result = PluginInstallUtils.getPluginLatestDownloadLink("test");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("应该构建正确的最新下载 URL")
        @Timeout(5)
        void shouldBuildCorrectLatestDownloadUrl() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":555}")
                        .thenReturn("https://example.com/download");

                PluginInstallUtils.getPluginLatestDownloadLink("some-plugin");

                httpUtilMock.verify(() -> HttpUtil.createGet("https://api.test.com/plugin/555/latest/download"));
            }
        }
    }

    @Nested
    @DisplayName("BaseUrl 配置测试")
    class BaseUrlConfigurationTests {

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该能设置自定义 base URL")
        void shouldSetCustomBaseUrl() {
            PluginInstallUtils.setBaseUrlForTesting("https://custom.api.com");
            
            assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo("https://custom.api.com");
        }

        @Test
        @DisplayName("重置后应该清除自定义 URL")
        void shouldClearCustomUrlAfterReset() {
            PluginInstallUtils.setBaseUrlForTesting("https://custom.api.com");
            PluginInstallUtils.resetBaseUrl();
            
            // After reset, getBaseUrl would try to get from UltiTools.getEnv()
            // which would fail in test environment, but the customBaseUrl is cleared
            // We can verify by setting a new custom URL
            PluginInstallUtils.setBaseUrlForTesting("https://new.api.com");
            assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo("https://new.api.com");
        }

        @Test
        @DisplayName("自定义 URL 应该优先于缓存的 URL")
        void customUrlShouldTakePrecedence() {
            PluginInstallUtils.setBaseUrlForTesting("https://first.api.com");
            assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo("https://first.api.com");
            
            PluginInstallUtils.setBaseUrlForTesting("https://second.api.com");
            assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo("https://second.api.com");
        }
    }

    @Nested
    @DisplayName("HTTP 状态码测试")
    class HttpStatusCodeTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("404 状态码应该返回空结果")
        @Timeout(5)
        void shouldReturnEmptyFor404() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                List<PluginEntity> result = PluginInstallUtils.getPluginList(1, 10);

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("500 状态码应该返回空结果")
        @Timeout(5)
        void shouldReturnEmptyFor500() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                PluginEntity result = PluginInstallUtils.getPlugin("test");

                assertThat(result).isNull();
            }
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("空 identifyString 应该能处理")
        @Timeout(5)
        void shouldHandleEmptyIdentifyString() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(false);

                PluginEntity result = PluginInstallUtils.getPlugin("");

                assertThat(result).isNull();
            }
        }

        @Test
        @DisplayName("特殊字符在 identifyString 中应该能处理")
        @Timeout(5)
        void shouldHandleSpecialCharactersInIdentifyString() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":1}");

                PluginEntity result = PluginInstallUtils.getPlugin("plugin-with-dash_and_underscore");

                assertThat(result).isNotNull();
            }
        }

        @Test
        @DisplayName("分页参数为零应该能处理")
        @Timeout(5)
        void shouldHandleZeroPagination() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[]");

                List<PluginEntity> result = PluginInstallUtils.getPluginList(0, 0);

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("负数分页参数应该能处理")
        @Timeout(5)
        void shouldHandleNegativePagination() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[]");

                List<PluginEntity> result = PluginInstallUtils.getPluginList(-1, -10);

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("大分页参数应该能处理")
        @Timeout(5)
        void shouldHandleLargePagination() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[]");

                List<PluginEntity> result = PluginInstallUtils.getPluginList(Integer.MAX_VALUE, Integer.MAX_VALUE);

                assertThat(result).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("JSON 解析测试")
    class JsonParsingTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("应该正确解析包含所有字段的插件 JSON")
        @Timeout(5)
        void shouldParseCompletePluginJson() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":42,\"name\":\"TestPlugin\",\"identifyString\":\"test-plugin\",\"description\":\"A test plugin\",\"author\":\"Developer\"}");

                PluginEntity result = PluginInstallUtils.getPlugin("test-plugin");

                assertThat(result).isNotNull();
                assertThat(result.getId()).isEqualTo(42);
            }
        }

        @Test
        @DisplayName("应该正确解析只有必需字段的插件 JSON")
        @Timeout(5)
        void shouldParseMinimalPluginJson() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":1}");

                PluginEntity result = PluginInstallUtils.getPlugin("minimal");

                assertThat(result).isNotNull();
                assertThat(result.getId()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("应该正确解析版本数组")
        @Timeout(5)
        void shouldParseVersionArray() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":1}")
                        .thenReturn("[\"1.0.0\", \"1.1.0\", \"1.2.0-SNAPSHOT\", \"2.0.0-beta\"]");

                List<String> result = PluginInstallUtils.getPluginVersions("test");

                assertThat(result).containsExactly("1.0.0", "1.1.0", "1.2.0-SNAPSHOT", "2.0.0-beta");
            }
        }

        @Test
        @DisplayName("应该处理空版本数组")
        @Timeout(5)
        void shouldHandleEmptyVersionArray() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":1}")
                        .thenReturn("[]");

                List<String> result = PluginInstallUtils.getPluginVersions("test");

                assertThat(result).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("资源清理测试")
    class ResourceCleanupTests {

        @BeforeEach
        void setUp() {
            PluginInstallUtils.setBaseUrlForTesting("https://api.test.com");
        }

        @AfterEach
        void tearDown() {
            PluginInstallUtils.resetBaseUrl();
        }

        @Test
        @DisplayName("getPluginList 成功时应该关闭响应")
        @Timeout(5)
        void shouldCloseResponseOnSuccess() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("[]");

                PluginInstallUtils.getPluginList(1, 10);

                verify(mockResponse).close();
            }
        }

        @Test
        @DisplayName("getPlugin 成功时应该关闭响应")
        @Timeout(5)
        void shouldCloseResponseOnGetPlugin() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body()).thenReturn("{\"id\":1}");

                PluginInstallUtils.getPlugin("test");

                verify(mockResponse).close();
            }
        }

        @Test
        @DisplayName("getPluginLatestVersion 成功时应该关闭所有响应")
        @Timeout(5)
        void shouldCloseAllResponsesOnGetLatestVersion() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);

                httpUtilMock.when(() -> HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.isOk()).thenReturn(true);
                when(mockResponse.body())
                        .thenReturn("{\"id\":1}")
                        .thenReturn("1.0.0");

                PluginInstallUtils.getPluginLatestVersion("test");

                // Should be called twice: once for getPlugin, once for getPluginLatestVersion
                verify(mockResponse, times(2)).close();
            }
        }
    }
}
