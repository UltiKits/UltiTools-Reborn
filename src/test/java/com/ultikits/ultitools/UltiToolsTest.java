package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.ServerMonitorManager;

/**
 * Tests for the {@link UltiTools} main plugin class.
 */
@DisplayName("UltiTools Main Class Tests")
class UltiToolsTest {

    @Nested
    @DisplayName("版本解析测试")
    class VersionParsingTests {
        @Test
        @DisplayName("应解析数字核心并忽略 SNAPSHOT 限定符")
        void parsesNumericCoreAndSnapshotQualifiers() {
            assertThat(UltiTools.parsePluginVersion("6.2.5")).isEqualTo(625);
            assertThat(UltiTools.parsePluginVersion("6.2.5-SNAPSHOT")).isEqualTo(625);
            assertThat(UltiTools.parsePluginVersion("6.2.5-SNAPSHOT-abcdefg")).isEqualTo(625);
            assertThat(UltiTools.parsePluginVersion("6.2.0")).isEqualTo(620);
        }

        @Test
        @DisplayName("应拒绝缺失、格式错误或溢出的版本")
        void rejectsMissingMalformedAndOverflowVersions() {
            assertThatThrownBy(() -> UltiTools.parsePluginVersion(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UltiTools.parsePluginVersion(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UltiTools.parsePluginVersion("6.2-SNAPSHOT")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UltiTools.parsePluginVersion("6.2.x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UltiTools.parsePluginVersion("2147483648.1.1")).isInstanceOf(IllegalArgumentException.class);
        }
    }

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
    }

    @Nested
    @DisplayName("GATE-05 group two (08-21): getEnv() failure routing")
    class GetEnvFailureTests {

        // getTextResource(String) is `protected final` on org.bukkit.plugin.java.JavaPlugin, in
        // a different package this test class does not extend -- javac refuses a direct
        // `when(ultiTools.getTextResource(...))` call at the source level even though Mockito's
        // inline mock maker can stub it at the bytecode level. Invoking it reflectively still
        // dispatches through the mock's interceptor, so Mockito's `when(...)` still picks up the
        // recorded invocation; only the *call syntax*, not the mocking itself, needs to route
        // around the access check.
        private java.lang.reflect.Method getTextResourceMethod;

        @org.junit.jupiter.api.BeforeEach
        void resolveProtectedMethod() throws NoSuchMethodException {
            getTextResourceMethod = org.bukkit.plugin.java.JavaPlugin.class
                    .getDeclaredMethod("getTextResource", String.class);
            getTextResourceMethod.setAccessible(true);
        }

        @org.junit.jupiter.api.AfterEach
        void resetInstance() throws Exception {
            java.lang.reflect.Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        }

        private java.io.Reader invokeGetTextResource(UltiTools ultiTools, String name) throws Exception {
            return (java.io.Reader) getTextResourceMethod.invoke(ultiTools, name);
        }

        @Test
        @DisplayName("Should throw ConfigurationException when env.yml is absent from resources")
        void shouldThrowConfigurationExceptionWhenEnvYmlMissing() {
            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
                try {
                    when(invokeGetTextResource(ultiTools, "env.yml")).thenReturn(null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThatThrownBy(UltiTools::getEnv)
                    .isInstanceOf(com.ultikits.ultitools.exceptions.ConfigurationException.class)
                    .hasMessageContaining("env.yml not found")
                    .extracting(t -> ((com.ultikits.ultitools.exceptions.ConfigurationException) t).getErrorCode())
                    .isEqualTo(com.ultikits.ultitools.exceptions.ErrorCode.CONFIG_LOAD_FAILED);
        }

        @Test
        @DisplayName("Should throw ConfigurationException when env.yml content is not valid YAML")
        void shouldThrowConfigurationExceptionWhenEnvYmlMalformed() {
            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
                try {
                    when(invokeGetTextResource(ultiTools, "env.yml"))
                            .thenReturn(new java.io.StringReader("api-url: [unterminated"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThatThrownBy(UltiTools::getEnv)
                    .isInstanceOf(com.ultikits.ultitools.exceptions.ConfigurationException.class)
                    .hasMessageContaining("env.yml")
                    .extracting(t -> ((com.ultikits.ultitools.exceptions.ConfigurationException) t).getErrorCode())
                    .isEqualTo(com.ultikits.ultitools.exceptions.ErrorCode.CONFIG_LOAD_FAILED);
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
        @DisplayName("Should have dependenceManagers field")
        void shouldHaveDependenceManagersField() throws NoSuchFieldException {
            Field field = UltiTools.class.getDeclaredField("dependenceManagers");
            assertThat(field.getType()).isEqualTo(DependenceManagers.class);
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
            assertThat(method.getReturnType()).isEqualTo(PluginManager.class);
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
        @DisplayName("Should have getEconomy method")
        void shouldHaveGetEconomyMethod() throws NoSuchMethodException {
            Method method = UltiTools.class.getMethod("getEconomy");
            assertThat(method.getReturnType()).isEqualTo(net.milkbowl.vault.economy.Economy.class);
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
    @DisplayName("Singleton Pattern Tests")
    class SingletonPatternTests {

        @Test
        @DisplayName("getInstance should return null before initialization")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void getInstanceShouldReturnNullBeforeInitialization() {
            try {
                Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
                ultiToolsField.setAccessible(true);
                Object originalValue = ultiToolsField.get(null);
                ultiToolsField.set(null, null);
                
                assertThat(UltiTools.getInstance()).isNull();
                
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
                
                ultiToolsField.set(null, originalValue);
            } catch (Exception e) {
                // Test may fail in some environments, which is acceptable
            }
        }
    }
}
