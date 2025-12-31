package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

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
 * VersionUtils 测试类
 * 使用 Mock 测试 HTTP 请求和外部依赖
 */
@DisplayName("VersionUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VersionUtilsTest {

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getUltiToolsNewestVersion方法应该存在")
        void getUltiToolsNewestVersionMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = VersionUtils.class.getMethod("getUltiToolsNewestVersion");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("pluginHasUpdate方法应该存在")
        void pluginHasUpdateMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = VersionUtils.class.getMethod(
                "pluginHasUpdate", String.class, String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }
    }

    @Nested
    @DisplayName("版本比较逻辑测试")
    class VersionComparisonTests {

        @Test
        @DisplayName("版本比较工具类应该可用")
        void versionComparatorShouldBeAvailable() {
            // 验证 Hutool 的版本比较器可用
            cn.hutool.core.comparator.VersionComparator comparator = 
                new cn.hutool.core.comparator.VersionComparator();
            
            // 测试基本比较
            assertThat(comparator.compare("1.0.0", "2.0.0")).isLessThan(0);
            assertThat(comparator.compare("2.0.0", "1.0.0")).isGreaterThan(0);
            assertThat(comparator.compare("1.0.0", "1.0.0")).isEqualTo(0);
        }

        @Test
        @DisplayName("语义版本比较应该正确")
        void semanticVersionComparisonShouldBeCorrect() {
            cn.hutool.core.comparator.VersionComparator comparator = 
                new cn.hutool.core.comparator.VersionComparator();
            
            // 主版本号
            assertThat(comparator.compare("1.0.0", "2.0.0")).isLessThan(0);
            
            // 次版本号
            assertThat(comparator.compare("1.0.0", "1.1.0")).isLessThan(0);
            
            // 修订号
            assertThat(comparator.compare("1.0.0", "1.0.1")).isLessThan(0);
            
            // 复杂版本
            assertThat(comparator.compare("1.9.9", "1.10.0")).isLessThan(0);
        }

        @Test
        @DisplayName("更新检查逻辑应该正确")
        void updateCheckLogicShouldBeCorrect() {
            cn.hutool.core.comparator.VersionComparator comparator = 
                new cn.hutool.core.comparator.VersionComparator();
            
            // 模拟 pluginHasUpdate 的逻辑: currentVersion < latestVersion 时返回 true
            String currentVersion = "1.0.0";
            String latestVersion = "2.0.0";
            
            boolean hasUpdate = comparator.compare(currentVersion, latestVersion) < 0;
            assertThat(hasUpdate).isTrue();
            
            // 当前版本更新时不应该有更新
            currentVersion = "3.0.0";
            hasUpdate = comparator.compare(currentVersion, latestVersion) < 0;
            assertThat(hasUpdate).isFalse();
            
            // 版本相同时不应该有更新
            currentVersion = "2.0.0";
            hasUpdate = comparator.compare(currentVersion, latestVersion) < 0;
            assertThat(hasUpdate).isFalse();
        }
    }
    
    // ========== Mock 测试：getUltiToolsNewestVersion ==========
    
    @Nested
    @DisplayName("getUltiToolsNewestVersion Mock测试")
    class GetUltiToolsNewestVersionMockTests {
        
        @Test
        @DisplayName("成功获取最新版本号")
        void shouldReturnNewestVersionWhenSuccessful() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                // 准备 Mock
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet("https://api.ultikits.com/plugin/ultitools/newest"))
                    .thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn("6.2.1");
                
                // 执行方法
                String version = VersionUtils.getUltiToolsNewestVersion();
                
                // 验证
                assertThat(version).isEqualTo("6.2.1");
                verify(mockResponse).close();
            }
        }
        
        @Test
        @DisplayName("获取预发布版本号")
        void shouldReturnPreReleaseVersion() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn("7.0.0-SNAPSHOT");
                
                String version = VersionUtils.getUltiToolsNewestVersion();
                
                assertThat(version).isEqualTo("7.0.0-SNAPSHOT");
                verify(mockResponse).close();
            }
        }
        
        @Test
        @DisplayName("获取空版本号")
        void shouldHandleEmptyVersion() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn("");
                
                String version = VersionUtils.getUltiToolsNewestVersion();
                
                assertThat(version).isEmpty();
                verify(mockResponse).close();
            }
        }
        
        @Test
        @DisplayName("获取包含空白字符的版本号")
        void shouldReturnVersionWithWhitespace() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn("  6.1.0  ");
                
                String version = VersionUtils.getUltiToolsNewestVersion();
                
                // 原方法不会 trim，所以保持原样
                assertThat(version).isEqualTo("  6.1.0  ");
                verify(mockResponse).close();
            }
        }
        
        @Test
        @DisplayName("获取null版本号")
        void shouldHandleNullVersion() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn(null);
                
                String version = VersionUtils.getUltiToolsNewestVersion();
                
                assertThat(version).isNull();
                verify(mockResponse).close();
            }
        }
        
        @Test
        @DisplayName("验证请求正确的API端点")
        void shouldRequestCorrectApiEndpoint() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn("6.0.0");
                
                VersionUtils.getUltiToolsNewestVersion();
                
                httpUtilMock.verify(() -> 
                    HttpUtil.createGet("https://api.ultikits.com/plugin/ultitools/newest"));
                verify(mockResponse).close();
            }
        }
    }
    
    // ========== Mock 测试：pluginHasUpdate ==========
    
    @Nested
    @DisplayName("pluginHasUpdate Mock测试")
    class PluginHasUpdateMockTests {
        
        @Test
        @DisplayName("插件有更新时返回true")
        void shouldReturnTrueWhenPluginHasUpdate() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("2.0.0");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "1.0.0");
                
                assertThat(hasUpdate).isTrue();
            }
        }
        
        @Test
        @DisplayName("插件版本相同时返回false")
        void shouldReturnFalseWhenVersionsSame() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("1.0.0");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "1.0.0");
                
                assertThat(hasUpdate).isFalse();
            }
        }
        
        @Test
        @DisplayName("当前版本更新时返回false")
        void shouldReturnFalseWhenCurrentVersionIsNewer() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("1.0.0");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "2.0.0");
                
                assertThat(hasUpdate).isFalse();
            }
        }
        
        @Test
        @DisplayName("插件不存在时返回false")
        void shouldReturnFalseWhenPluginNotFound() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("non-existent-plugin"))
                    .thenReturn(null);
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("non-existent-plugin", "1.0.0");
                
                assertThat(hasUpdate).isFalse();
                // 确认不调用获取版本方法
                pluginUtilsMock.verify(() -> 
                    PluginInstallUtils.getPluginLatestVersion(anyString()), never());
            }
        }
        
        @Test
        @DisplayName("处理语义版本号比较 - 次版本更新")
        void shouldHandleMinorVersionUpdate() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("1.2.0");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "1.1.0");
                
                assertThat(hasUpdate).isTrue();
            }
        }
        
        @Test
        @DisplayName("处理语义版本号比较 - 修订号更新")
        void shouldHandlePatchVersionUpdate() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("1.0.1");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "1.0.0");
                
                assertThat(hasUpdate).isTrue();
            }
        }
        
        @Test
        @DisplayName("处理复杂版本号 - 1.9.9 vs 1.10.0")
        void shouldHandleComplexVersionComparison() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("1.10.0");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "1.9.9");
                
                assertThat(hasUpdate).isTrue();
            }
        }
        
        @Test
        @DisplayName("处理预发布版本号")
        void shouldHandlePreReleaseVersions() {
            // 首先验证 VersionComparator 的行为
            cn.hutool.core.comparator.VersionComparator comparator = 
                new cn.hutool.core.comparator.VersionComparator();
            // Hutool 的 VersionComparator 按字母顺序比较后缀
            // "2.0.0-SNAPSHOT" 与 "2.0.0" 比较时，"-SNAPSHOT" 会被考虑
            int result = comparator.compare("2.0.0-SNAPSHOT", "2.0.0");
            
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("2.1.0");
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("test-plugin", "2.0.0-SNAPSHOT");
                
                // 2.0.0-SNAPSHOT < 2.1.0 - 应该有更新
                assertThat(hasUpdate).isTrue();
            }
        }
    }
    
    // ========== 边界条件测试 ==========
    
    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("pluginHasUpdate - 空插件ID")
        void shouldHandleEmptyPluginId() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin(""))
                    .thenReturn(null);
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate("", "1.0.0");
                
                assertThat(hasUpdate).isFalse();
            }
        }
        
        @Test
        @DisplayName("pluginHasUpdate - null插件ID返回")
        void shouldHandleNullFromGetPlugin() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin(null))
                    .thenReturn(null);
                
                boolean hasUpdate = VersionUtils.pluginHasUpdate(null, "1.0.0");
                
                assertThat(hasUpdate).isFalse();
            }
        }
        
        @Test
        @DisplayName("验证方法调用顺序")
        void shouldCallMethodsInCorrectOrder() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("test-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"))
                    .thenReturn("2.0.0");
                
                VersionUtils.pluginHasUpdate("test-plugin", "1.0.0");
                
                // 验证先检查插件是否存在
                pluginUtilsMock.verify(() -> PluginInstallUtils.getPlugin("test-plugin"));
                // 然后获取最新版本
                pluginUtilsMock.verify(() -> PluginInstallUtils.getPluginLatestVersion("test-plugin"));
            }
        }
    }
    
    // ========== 集成场景测试 ==========
    
    @Nested
    @DisplayName("集成场景测试")
    class IntegrationScenarioTests {
        
        @Test
        @DisplayName("UltiTools版本获取完整流程")
        void shouldCompleteUltiToolsVersionCheckFlow() {
            try (MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {
                HttpRequest mockRequest = mock(HttpRequest.class);
                HttpResponse mockResponse = mock(HttpResponse.class);
                
                when(HttpUtil.createGet(anyString())).thenReturn(mockRequest);
                when(mockRequest.execute()).thenReturn(mockResponse);
                when(mockResponse.body()).thenReturn("6.2.0");
                
                // 获取版本
                String newestVersion = VersionUtils.getUltiToolsNewestVersion();
                
                // 使用版本比较逻辑
                cn.hutool.core.comparator.VersionComparator comparator = 
                    new cn.hutool.core.comparator.VersionComparator();
                boolean hasUpdate = comparator.compare("6.1.0", newestVersion) < 0;
                
                assertThat(hasUpdate).isTrue();
                verify(mockResponse).close();
            }
        }
        
        @Test
        @DisplayName("插件更新检查完整流程")
        void shouldCompletePluginUpdateCheckFlow() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity mockPlugin = mock(PluginEntity.class);
                when(mockPlugin.getName()).thenReturn("ExamplePlugin");
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("example-plugin"))
                    .thenReturn(mockPlugin);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("example-plugin"))
                    .thenReturn("3.0.0");
                
                // 模拟完整的更新检查流程
                String currentVersion = "2.5.0";
                boolean needsUpdate = VersionUtils.pluginHasUpdate("example-plugin", currentVersion);
                
                assertThat(needsUpdate).isTrue();
            }
        }
        
        @Test
        @DisplayName("多个插件批量检查更新")
        void shouldCheckMultiplePluginsForUpdates() {
            try (MockedStatic<PluginInstallUtils> pluginUtilsMock = mockStatic(PluginInstallUtils.class)) {
                PluginEntity plugin1 = mock(PluginEntity.class);
                PluginEntity plugin2 = mock(PluginEntity.class);
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("plugin-1"))
                    .thenReturn(plugin1);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("plugin-1"))
                    .thenReturn("2.0.0");
                
                pluginUtilsMock.when(() -> PluginInstallUtils.getPlugin("plugin-2"))
                    .thenReturn(plugin2);
                pluginUtilsMock.when(() -> PluginInstallUtils.getPluginLatestVersion("plugin-2"))
                    .thenReturn("1.0.0");
                
                boolean plugin1NeedsUpdate = VersionUtils.pluginHasUpdate("plugin-1", "1.0.0");
                boolean plugin2NeedsUpdate = VersionUtils.pluginHasUpdate("plugin-2", "1.0.0");
                
                assertThat(plugin1NeedsUpdate).isTrue();
                assertThat(plugin2NeedsUpdate).isFalse();
            }
        }
    }
}
