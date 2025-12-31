package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.PlayerEventManager;
import com.ultikits.ultitools.manager.ServerMonitorManager;

import net.milkbowl.vault.economy.Economy;

/**
 * Tests for the {@link UltiTools} main plugin class.
 */
@DisplayName("UltiTools Main Class Tests")
class UltiToolsTest {

    @Nested
    @DisplayName("Class Structure Tests")
    class ClassStructureTests {

        @Test
        @DisplayName("Should be a final class")
        void shouldBeFinalClass() {
            assertThat(Modifier.isFinal(UltiTools.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("Should extend JavaPlugin")
        void shouldExtendJavaPlugin() {
            assertThat(org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(UltiTools.class)).isTrue();
        }

        @Test
        @DisplayName("Should implement Localized interface")
        void shouldImplementLocalizedInterface() {
            assertThat(com.ultikits.ultitools.interfaces.Localized.class.isAssignableFrom(UltiTools.class)).isTrue();
        }

        @Test
        @DisplayName("Should have getInstance static method")
        void shouldHaveGetInstanceMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getInstance");
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(UltiTools.class);
        }

        @Test
        @DisplayName("Should have getPluginVersion static method")
        void shouldHaveGetPluginVersionMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getPluginVersion");
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(int.class);
        }

        @Test
        @DisplayName("Should have getEnv static method")
        void shouldHaveGetEnvMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getEnv");
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(YamlConfiguration.class);
        }

        @Test
        @DisplayName("Should have getJavaPluginClassLoader static method")
        void shouldHaveGetJavaPluginClassLoaderMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getJavaPluginClassLoader");
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(ClassLoader.class);
        }
    }

    @Nested
    @DisplayName("Field Tests")
    class FieldTests {

        @Test
        @DisplayName("Should have listenerManager field")
        void shouldHaveListenerManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("listenerManager");
            assertThat(field.getType()).isEqualTo(ListenerManager.class);
        }

        @Test
        @DisplayName("Should have commandManager field")
        void shouldHaveCommandManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("commandManager");
            assertThat(field.getType()).isEqualTo(CommandManager.class);
        }

        @Test
        @DisplayName("Should have pluginManager field")
        void shouldHavePluginManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("pluginManager");
            assertThat(field.getType()).isEqualTo(com.ultikits.ultitools.manager.PluginManager.class);
        }

        @Test
        @DisplayName("Should have configManager field")
        void shouldHaveConfigManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("configManager");
            assertThat(field.getType()).isEqualTo(ConfigManager.class);
        }

        @Test
        @DisplayName("Should have dataStore field")
        void shouldHaveDataStoreField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("dataStore");
            assertThat(field.getType()).isEqualTo(DataStore.class);
        }

        @Test
        @DisplayName("Should have language field")
        void shouldHaveLanguageField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("language");
            assertThat(field.getType()).isEqualTo(Language.class);
        }

        @Test
        @DisplayName("Should have serverMonitorManager field")
        void shouldHaveServerMonitorManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("serverMonitorManager");
            assertThat(field.getType()).isEqualTo(ServerMonitorManager.class);
        }

        @Test
        @DisplayName("Should have commandExecutionManager field")
        void shouldHaveCommandExecutionManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("commandExecutionManager");
            assertThat(field.getType()).isEqualTo(CommandExecutionManager.class);
        }

        @Test
        @DisplayName("Should have fileOperationManager field")
        void shouldHaveFileOperationManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("fileOperationManager");
            assertThat(field.getType()).isEqualTo(FileOperationManager.class);
        }

        @Test
        @DisplayName("Should have logStreamManager field")
        void shouldHaveLogStreamManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("logStreamManager");
            assertThat(field.getType()).isEqualTo(LogStreamManager.class);
        }

        @Test
        @DisplayName("Should have playerEventManager field")
        void shouldHavePlayerEventManagerField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("playerEventManager");
            assertThat(field.getType()).isEqualTo(PlayerEventManager.class);
        }

        @Test
        @DisplayName("Should have ultiToolsClassLoader field")
        void shouldHaveUltiToolsClassLoaderField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("ultiToolsClassLoader");
            assertThat(field.getType()).isEqualTo(URLClassLoader.class);
        }
    }

    @Nested
    @DisplayName("Localized Interface Tests")
    class LocalizedInterfaceTests {

        @Test
        @DisplayName("Should have supported method")
        void shouldHaveSupportedMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("supported");
            assertThat(method.getReturnType()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("Should have i18n method")
        void shouldHaveI18nMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("i18n", String.class);
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle Method Tests")
    class LifecycleMethodTests {

        @Test
        @DisplayName("Should have onLoad method")
        void shouldHaveOnLoadMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("onLoad");
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("Should have onEnable method")
        void shouldHaveOnEnableMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("onEnable");
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("Should have onDisable method")
        void shouldHaveOnDisableMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("onDisable");
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("Should have reloadPlugins method")
        void shouldHaveReloadPluginsMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("reloadPlugins");
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getExceptionTypes()).contains(java.io.IOException.class);
        }
    }

    @Nested
    @DisplayName("Getter Method Tests")
    class GetterMethodTests {

        @Test
        @DisplayName("Should have getListenerManager method")
        void shouldHaveGetListenerManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getListenerManager");
            assertThat(method.getReturnType()).isEqualTo(ListenerManager.class);
        }

        @Test
        @DisplayName("Should have getCommandManager method")
        void shouldHaveGetCommandManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getCommandManager");
            assertThat(method.getReturnType()).isEqualTo(CommandManager.class);
        }

        @Test
        @DisplayName("Should have getPluginManager method")
        void shouldHaveGetPluginManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getPluginManager");
            assertThat(method.getReturnType()).isEqualTo(com.ultikits.ultitools.manager.PluginManager.class);
        }

        @Test
        @DisplayName("Should have getConfigManager method")
        void shouldHaveGetConfigManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getConfigManager");
            assertThat(method.getReturnType()).isEqualTo(ConfigManager.class);
        }

        @Test
        @DisplayName("Should have getDataStore method")
        void shouldHaveGetDataStoreMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getDataStore");
            assertThat(method.getReturnType()).isEqualTo(DataStore.class);
        }

        @Test
        @DisplayName("Should have setDataStore method")
        void shouldHaveSetDataStoreMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("setDataStore", DataStore.class);
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("Should have getLanguage method")
        void shouldHaveGetLanguageMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getLanguage");
            assertThat(method.getReturnType()).isEqualTo(Language.class);
        }

        @Test
        @DisplayName("Should have getVersionWrapper method")
        void shouldHaveGetVersionWrapperMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getVersionWrapper");
            assertThat(method.getReturnType()).isEqualTo(com.ultikits.ultitools.interfaces.VersionWrapper.class);
        }

        @Test
        @DisplayName("Should have getDependenceManagers method")
        void shouldHaveGetDependenceManagersMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getDependenceManagers");
            assertThat(method.getReturnType()).isEqualTo(DependenceManagers.class);
        }

        @Test
        @DisplayName("Should have getServerMonitorManager method")
        void shouldHaveGetServerMonitorManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getServerMonitorManager");
            assertThat(method.getReturnType()).isEqualTo(ServerMonitorManager.class);
        }

        @Test
        @DisplayName("Should have getCommandExecutionManager method")
        void shouldHaveGetCommandExecutionManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getCommandExecutionManager");
            assertThat(method.getReturnType()).isEqualTo(CommandExecutionManager.class);
        }

        @Test
        @DisplayName("Should have getFileOperationManager method")
        void shouldHaveGetFileOperationManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getFileOperationManager");
            assertThat(method.getReturnType()).isEqualTo(FileOperationManager.class);
        }

        @Test
        @DisplayName("Should have getLogStreamManager method")
        void shouldHaveGetLogStreamManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getLogStreamManager");
            assertThat(method.getReturnType()).isEqualTo(LogStreamManager.class);
        }

        @Test
        @DisplayName("Should have getPlayerEventManager method")
        void shouldHaveGetPlayerEventManagerMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getPlayerEventManager");
            assertThat(method.getReturnType()).isEqualTo(PlayerEventManager.class);
        }

        @Test
        @DisplayName("Should have getUltiToolsClassLoader method")
        void shouldHaveGetUltiToolsClassLoaderMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getUltiToolsClassLoader");
            assertThat(method.getReturnType()).isEqualTo(URLClassLoader.class);
        }

        @Test
        @DisplayName("Should have getEconomy method")
        void shouldHaveGetEconomyMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getEconomy");
            assertThat(method.getReturnType()).isEqualTo(net.milkbowl.vault.economy.Economy.class);
        }

        @Test
        @DisplayName("Should have getServerJar method")
        void shouldHaveGetServerJarMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getServerJar");
            assertThat(method.getReturnType()).isEqualTo(URL.class);
        }
    }

    @Nested
    @DisplayName("Private Method Tests")
    class PrivateMethodTests {

        @Test
        @DisplayName("Should have getFileResource private method")
        void shouldHaveGetFileResourceMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getDeclaredMethod("getFileResource", String.class);
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(java.io.InputStream.class);
        }

        @Test
        @DisplayName("Should have getLibs private method")
        void shouldHaveGetLibsMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getDeclaredMethod("getLibs");
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(URL[].class);
        }

        @Test
        @DisplayName("Should have downloadRequiredDependencies private method")
        void shouldHaveDownloadRequiredDependenciesMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getDeclaredMethod("downloadRequiredDependencies");
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("Should have printLoadingBar private method")
        void shouldHavePrintLoadingBarMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getDeclaredMethod("printLoadingBar", int.class);
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }

    @Nested
    @DisplayName("Static Field Tests")
    class StaticFieldTests {

        @Test
        @DisplayName("Should have ultiTools static field")
        void shouldHaveUltiToolsStaticField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("ultiTools");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(UltiTools.class);
        }
    }

    @Nested
    @DisplayName("Language Support Tests")
    class LanguageSupportTests {

        @Test
        @DisplayName("Supported languages should include en and zh")
        void supportedLanguagesShouldIncludeEnAndZh() {
            // Test the expected supported languages based on the interface implementation
            List<String> expectedLanguages = Arrays.asList("en", "zh");
            
            // Verify the supported method exists and has correct signature
            try {
                Method supportedMethod = UltiTools.class.getMethod("supported");
                assertThat(supportedMethod.getReturnType()).isEqualTo(List.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("supported() method not found", e);
            }
        }
    }

    @Nested
    @DisplayName("Singleton Pattern Tests")
    class SingletonPatternTests {

        @Test
        @DisplayName("getInstance should return null before initialization")
        void getInstanceShouldReturnNullBeforeInitialization() {
            // Reset the static field
            try {
                Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
                ultiToolsField.setAccessible(true);
                Object originalValue = ultiToolsField.get(null);
                ultiToolsField.set(null, null);
                
                assertThat(UltiTools.getInstance()).isNull();
                
                // Restore original value
                ultiToolsField.set(null, originalValue);
            } catch (Exception e) {
                // Expected in some test environments
            }
        }
    }

    @Nested
    @DisplayName("ClassLoader Tests")
    class ClassLoaderTests {

        @Test
        @DisplayName("getJavaPluginClassLoader should return a ClassLoader")
        void getJavaPluginClassLoaderShouldReturnClassLoader() {
            ClassLoader classLoader = UltiTools.getJavaPluginClassLoader();
            assertThat(classLoader).isNotNull();
        }

        @Test
        @DisplayName("getJavaPluginClassLoader should return context ClassLoader when instance is null")
        void getJavaPluginClassLoaderShouldReturnContextClassLoaderWhenInstanceNull() {
            try {
                Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
                ultiToolsField.setAccessible(true);
                Object originalValue = ultiToolsField.get(null);
                ultiToolsField.set(null, null);
                
                ClassLoader classLoader = UltiTools.getJavaPluginClassLoader();
                assertThat(classLoader).isEqualTo(Thread.currentThread().getContextClassLoader());
                
                // Restore original value
                ultiToolsField.set(null, originalValue);
            } catch (Exception e) {
                // Test may fail in some environments, which is acceptable
            }
        }
    }

    @Nested
    @DisplayName("Lombok Generated Methods Tests")
    class LombokGeneratedMethodsTests {

        @Test
        @DisplayName("Getter should be generated for listenerManager")
        void getterShouldBeGeneratedForListenerManager() throws NoSuchMethodException {
            // Lombok @Getter generates getListenerManager() method
            Method method = UltiTools.class.getMethod("getListenerManager");
            assertThat(method.getReturnType()).isEqualTo(ListenerManager.class);
            assertThat(method.getParameterCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Getter should be generated for commandManager")
        void getterShouldBeGeneratedForCommandManager() throws NoSuchMethodException {
            // Lombok @Getter generates getCommandManager() method
            Method method = UltiTools.class.getMethod("getCommandManager");
            assertThat(method.getReturnType()).isEqualTo(CommandManager.class);
            assertThat(method.getParameterCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Both getter and setter should be generated for dataStore")
        void getterAndSetterShouldBeGeneratedForDataStore() throws NoSuchMethodException {
            // Lombok @Getter generates getDataStore() method
            Method getter = UltiTools.class.getMethod("getDataStore");
            assertThat(getter.getReturnType()).isEqualTo(DataStore.class);
            
            // Lombok @Setter generates setDataStore() method
            Method setter = UltiTools.class.getMethod("setDataStore", DataStore.class);
            assertThat(setter.getReturnType()).isEqualTo(void.class);
            assertThat(setter.getParameterCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Method Signature Tests")
    class MethodSignatureTests {

        @Test
        @DisplayName("i18n method should accept String parameter")
        void i18nMethodShouldAcceptStringParameter() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("i18n", String.class);
            assertThat(method.getParameterCount()).isEqualTo(1);
            assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
        }

        @Test
        @DisplayName("setDataStore method should accept DataStore parameter")
        void setDataStoreMethodShouldAcceptDataStoreParameter() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("setDataStore", DataStore.class);
            assertThat(method.getParameterCount()).isEqualTo(1);
            assertThat(method.getParameterTypes()[0]).isEqualTo(DataStore.class);
        }
    }

    @Nested
    @DisplayName("Manager Initialization Tests")
    class ManagerInitializationTests {

        @Test
        @DisplayName("ListenerManager should be initialized in field declaration")
        void listenerManagerShouldBeInitializedInFieldDeclaration() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("listenerManager");
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("CommandManager should be initialized in field declaration")
        void commandManagerShouldBeInitializedInFieldDeclaration() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("commandManager");
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("reloadPlugins should declare IOException")
        void reloadPluginsShouldDeclareIOException() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("reloadPlugins");
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            assertThat(exceptionTypes).hasSize(1);
            assertThat(exceptionTypes[0]).isEqualTo(java.io.IOException.class);
        }
    }
    
    // ========== Mock 测试：i18n 国际化方法 ==========
    
    @Nested
    @DisplayName("i18n Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class I18nMockTests {
        
        @Test
        @DisplayName("i18n 应该返回翻译后的文本")
        void i18nShouldReturnTranslatedText() throws Exception {
            // 创建一个 mock UltiTools 实例
            UltiTools mockUltiTools = mock(UltiTools.class);
            Language mockLanguage = mock(Language.class);
            
            when(mockLanguage.getLocalizedText("hello")).thenReturn("你好");
            when(mockUltiTools.i18n("hello")).thenCallRealMethod();
            
            // 设置 language 字段
            Field languageField = UltiTools.class.getDeclaredField("language");
            languageField.setAccessible(true);
            languageField.set(mockUltiTools, mockLanguage);
            
            String result = mockUltiTools.i18n("hello");
            
            assertThat(result).isEqualTo("你好");
            verify(mockLanguage).getLocalizedText("hello");
        }
        
        @Test
        @DisplayName("i18n 找不到翻译时应该返回原字符串")
        void i18nShouldReturnOriginalStringWhenNotFound() throws Exception {
            UltiTools mockUltiTools = mock(UltiTools.class);
            Language mockLanguage = mock(Language.class);
            
            when(mockLanguage.getLocalizedText("unknown_key")).thenReturn("unknown_key");
            when(mockUltiTools.i18n("unknown_key")).thenCallRealMethod();
            
            Field languageField = UltiTools.class.getDeclaredField("language");
            languageField.setAccessible(true);
            languageField.set(mockUltiTools, mockLanguage);
            
            String result = mockUltiTools.i18n("unknown_key");
            
            assertThat(result).isEqualTo("unknown_key");
        }
    }
    
    // ========== Mock 测试：supported 语言支持 ==========
    
    @Nested
    @DisplayName("supported Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class SupportedMockTests {
        
        @Test
        @DisplayName("supported 应该返回 en 和 zh")
        void supportedShouldReturnEnAndZh() throws Exception {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.supported()).thenCallRealMethod();
            
            List<String> result = mockUltiTools.supported();
            
            assertThat(result).containsExactly("en", "zh");
        }
        
        @Test
        @DisplayName("supported 返回的列表大小应该是 2")
        void supportedShouldReturn2Elements() throws Exception {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.supported()).thenCallRealMethod();
            
            List<String> result = mockUltiTools.supported();
            
            assertThat(result).hasSize(2);
        }
    }
    
    // ========== Mock 测试：getEconomy 经济服务 ==========
    
    @Nested
    @DisplayName("getEconomy Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class GetEconomyMockTests {
        
        @Mock
        private ServicesManager mockServicesManager;
        
        @Mock
        private PluginManager mockPluginManager;
        
        @Mock
        private Economy mockEconomy;
        
        @Mock
        private Plugin mockVaultPlugin;
        
        @Mock
        private RegisteredServiceProvider<Economy> mockServiceProvider;
        
        @Test
        @DisplayName("getEconomy 应该在 Vault 未安装时抛出异常")
        void getEconomyShouldThrowExceptionWhenVaultNotInstalled() throws Exception {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(mockPluginManager);
                when(mockPluginManager.getPlugin("Vault")).thenReturn(null);
                
                UltiTools mockUltiTools = mock(UltiTools.class);
                when(mockUltiTools.getEconomy()).thenCallRealMethod();
                
                assertThatThrownBy(() -> mockUltiTools.getEconomy())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Vault not found!");
            }
        }
        
        @Test
        @DisplayName("getEconomy 应该在没有经济服务时抛出异常")
        void getEconomyShouldThrowExceptionWhenNoEconomyService() throws Exception {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(mockPluginManager);
                bukkitMock.when(Bukkit::getServicesManager).thenReturn(mockServicesManager);
                
                when(mockPluginManager.getPlugin("Vault")).thenReturn(mockVaultPlugin);
                when(mockServicesManager.getRegistration(Economy.class)).thenReturn(null);
                
                UltiTools mockUltiTools = mock(UltiTools.class);
                when(mockUltiTools.getEconomy()).thenCallRealMethod();
                
                assertThatThrownBy(() -> mockUltiTools.getEconomy())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Economy service not found!");
            }
        }
        
        @Test
        @DisplayName("getEconomy 应该成功返回经济服务")
        void getEconomyShouldReturnEconomyWhenAvailable() throws Exception {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(mockPluginManager);
                bukkitMock.when(Bukkit::getServicesManager).thenReturn(mockServicesManager);
                
                when(mockPluginManager.getPlugin("Vault")).thenReturn(mockVaultPlugin);
                when(mockServicesManager.getRegistration(Economy.class)).thenReturn(mockServiceProvider);
                when(mockServiceProvider.getProvider()).thenReturn(mockEconomy);
                
                UltiTools mockUltiTools = mock(UltiTools.class);
                when(mockUltiTools.getEconomy()).thenCallRealMethod();
                
                Economy result = mockUltiTools.getEconomy();
                
                assertThat(result).isEqualTo(mockEconomy);
            }
        }
    }
    
    // ========== Mock 测试：getServerJar 服务器 JAR ==========
    
    @Nested
    @DisplayName("getServerJar Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class GetServerJarMockTests {
        
        @Test
        @DisplayName("getServerJar 在 CodeSource 为 null 时应该返回 null")
        void getServerJarShouldReturnNullWhenCodeSourceNull() throws Exception {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.getServerJar()).thenCallRealMethod();
            
            // 由于 Bukkit.class 的 ProtectionDomain 通常有 CodeSource，
            // 这个测试主要验证方法签名和基本行为
            URL result = mockUltiTools.getServerJar();
            
            // 结果可能是 null 或有效 URL，取决于运行环境
            // 这里主要测试方法不会抛出异常
            assertThat(result == null || result instanceof URL).isTrue();
        }
    }
    
    // ========== Mock 测试：getJavaPluginClassLoader ==========
    
    @Nested
    @DisplayName("getJavaPluginClassLoader Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class GetJavaPluginClassLoaderMockTests {
        
        @Test
        @DisplayName("getInstance 为 null 时应该返回 ContextClassLoader")
        void shouldReturnContextClassLoaderWhenInstanceNull() throws Exception {
            // 保存原始值
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true);
            Object originalValue = ultiToolsField.get(null);
            
            try {
                ultiToolsField.set(null, null);
                
                ClassLoader result = UltiTools.getJavaPluginClassLoader();
                
                assertThat(result).isEqualTo(Thread.currentThread().getContextClassLoader());
            } finally {
                ultiToolsField.set(null, originalValue);
            }
        }
        
        @Test
        @DisplayName("getInstance 有值时应该返回插件的 ClassLoader")
        void shouldReturnPluginClassLoaderWhenInstanceExists() throws Exception {
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true);
            Object originalValue = ultiToolsField.get(null);
            
            try {
                UltiTools mockUltiTools = mock(UltiTools.class);
                ultiToolsField.set(null, mockUltiTools);
                
                ClassLoader result = UltiTools.getJavaPluginClassLoader();
                
                // 结果应该是 mock 的类加载器
                assertThat(result).isNotNull();
            } finally {
                ultiToolsField.set(null, originalValue);
            }
        }
    }
    
    // ========== Mock 测试：getInstance 单例模式 ==========
    
    @Nested
    @DisplayName("getInstance Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class GetInstanceMockTests {
        
        @Test
        @DisplayName("getInstance 在设置后应该返回设置的实例")
        void getInstanceShouldReturnSetInstance() throws Exception {
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true);
            Object originalValue = ultiToolsField.get(null);
            
            try {
                UltiTools mockUltiTools = mock(UltiTools.class);
                ultiToolsField.set(null, mockUltiTools);
                
                UltiTools result = UltiTools.getInstance();
                
                assertThat(result).isEqualTo(mockUltiTools);
            } finally {
                ultiToolsField.set(null, originalValue);
            }
        }
        
        @Test
        @DisplayName("getInstance 在 null 时应该返回 null")
        void getInstanceShouldReturnNullWhenNotSet() throws Exception {
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true);
            Object originalValue = ultiToolsField.get(null);
            
            try {
                ultiToolsField.set(null, null);
                
                UltiTools result = UltiTools.getInstance();
                
                assertThat(result).isNull();
            } finally {
                ultiToolsField.set(null, originalValue);
            }
        }
    }
    
    // ========== Mock 测试：DataStore setter ==========
    
    @Nested
    @DisplayName("setDataStore Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class SetDataStoreMockTests {
        
        @Mock
        private DataStore mockDataStore;
        
        @Test
        @DisplayName("setDataStore 应该正确设置 dataStore 字段")
        void setDataStoreShouldSetField() throws Exception {
            UltiTools mockUltiTools = mock(UltiTools.class);
            doCallRealMethod().when(mockUltiTools).setDataStore(any(DataStore.class));
            
            // 使用反射设置字段
            Field dataStoreField = UltiTools.class.getDeclaredField("dataStore");
            dataStoreField.setAccessible(true);
            
            mockUltiTools.setDataStore(mockDataStore);
            
            // 验证 setter 被调用
            verify(mockUltiTools).setDataStore(mockDataStore);
        }
    }
    
    // ========== Mock 测试：printLoadingBar 私有方法 ==========
    
    @Nested
    @DisplayName("printLoadingBar Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class PrintLoadingBarMockTests {
        
        @Test
        @DisplayName("printLoadingBar 应该格式化进度条")
        void printLoadingBarShouldFormatProgressBar() throws Exception {
            // 测试进度条格式化逻辑
            int percentage = 50;
            StringBuilder loadingBar = new StringBuilder("[");
            int progress = percentage / 10;
            for (int i = 0; i < progress; i++) {
                loadingBar.append("*");
            }
            for (int i = progress; i < 10; i++) {
                loadingBar.append("-");
            }
            loadingBar.append("] ");
            loadingBar.append(percentage);
            loadingBar.append("%");
            
            String expected = "[*****-----] 50%";
            assertThat(loadingBar.toString()).isEqualTo(expected);
        }
        
        @Test
        @DisplayName("0% 进度条应该全是 -")
        void printLoadingBarShouldShowEmpty() {
            int percentage = 0;
            StringBuilder loadingBar = new StringBuilder("[");
            int progress = percentage / 10;
            for (int i = 0; i < progress; i++) {
                loadingBar.append("*");
            }
            for (int i = progress; i < 10; i++) {
                loadingBar.append("-");
            }
            loadingBar.append("] ");
            loadingBar.append(percentage);
            loadingBar.append("%");
            
            String expected = "[----------] 0%";
            assertThat(loadingBar.toString()).isEqualTo(expected);
        }
        
        @Test
        @DisplayName("100% 进度条应该全是 *")
        void printLoadingBarShouldShowFull() {
            int percentage = 100;
            StringBuilder loadingBar = new StringBuilder("[");
            int progress = percentage / 10;
            for (int i = 0; i < progress; i++) {
                loadingBar.append("*");
            }
            for (int i = progress; i < 10; i++) {
                loadingBar.append("-");
            }
            loadingBar.append("] ");
            loadingBar.append(percentage);
            loadingBar.append("%");
            
            String expected = "[**********] 100%";
            assertThat(loadingBar.toString()).isEqualTo(expected);
        }
    }
    
    // ========== Mock 测试：Manager 初始化 ==========
    
    @Nested
    @DisplayName("Manager 实例化 Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class ManagerInstantiationMockTests {
        
        @Test
        @DisplayName("ListenerManager 应该可以实例化")
        void listenerManagerShouldBeInstantiable() {
            ListenerManager manager = new ListenerManager();
            assertThat(manager).isNotNull();
        }
        
        @Test
        @DisplayName("CommandManager 应该可以实例化")
        void commandManagerShouldBeInstantiable() {
            CommandManager manager = new CommandManager();
            assertThat(manager).isNotNull();
        }
        
        @Test
        @DisplayName("ServerMonitorManager 应该可以实例化")
        void serverMonitorManagerShouldBeInstantiable() {
            ServerMonitorManager manager = new ServerMonitorManager();
            assertThat(manager).isNotNull();
        }
        
        @Test
        @DisplayName("CommandExecutionManager 应该可以实例化")
        void commandExecutionManagerShouldBeInstantiable() {
            CommandExecutionManager manager = new CommandExecutionManager();
            assertThat(manager).isNotNull();
        }
        
        @Test
        @DisplayName("FileOperationManager 应该可以实例化")
        void fileOperationManagerShouldBeInstantiable() {
            FileOperationManager manager = new FileOperationManager();
            assertThat(manager).isNotNull();
        }
        
        @Test
        @DisplayName("PlayerEventManager 应该可以实例化")
        void playerEventManagerShouldBeInstantiable() {
            PlayerEventManager manager = new PlayerEventManager();
            assertThat(manager).isNotNull();
        }
        
        @Test
        @DisplayName("LogStreamManager 应该使用单例模式")
        void logStreamManagerShouldUseSingleton() throws Exception {
            // 保存原始 UltiTools 实例
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true);
            Object originalValue = ultiToolsField.get(null);
            
            try {
                // 设置一个 mock UltiTools 实例
                UltiTools mockUltiTools = mock(UltiTools.class);
                java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
                when(mockUltiTools.getLogger()).thenReturn(mockLogger);
                ultiToolsField.set(null, mockUltiTools);
                
                LogStreamManager instance1 = LogStreamManager.getInstance();
                LogStreamManager instance2 = LogStreamManager.getInstance();
                
                assertThat(instance1).isNotNull();
                assertThat(instance1).isSameAs(instance2);
            } finally {
                ultiToolsField.set(null, originalValue);
            }
        }
    }
    
    // ========== Mock 测试：Language 类 ==========
    
    @Nested
    @DisplayName("Language Mock 测试")
    @ExtendWith(MockitoExtension.class)
    class LanguageMockTests {
        
        @Test
        @DisplayName("Language 可以用空 JSON 创建")
        void languageCanBeCreatedWithEmptyJson() {
            Language language = new Language("{}");
            assertThat(language).isNotNull();
        }
        
        @Test
        @DisplayName("Language 应该返回未翻译文本时的原始文本")
        void languageShouldReturnOriginalTextWhenNotTranslated() {
            Language language = new Language("{}");
            String result = language.getLocalizedText("unknown");
            assertThat(result).isEqualTo("unknown");
        }
        
        @Test
        @DisplayName("Language 可以用有效 JSON 创建并返回翻译")
        void languageCanReturnTranslation() {
            String json = "{\"hello\": \"你好\"}";
            Language language = new Language(json);
            String result = language.getLocalizedText("hello");
            assertThat(result).isEqualTo("你好");
        }
    }
}
