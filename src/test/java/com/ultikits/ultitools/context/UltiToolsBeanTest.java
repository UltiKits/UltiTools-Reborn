package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Unit tests for UltiToolsBean configuration class.
 * <br>
 * UltiToolsBean配置类的单元测试。
 */
@DisplayName("UltiToolsBean Tests")
class UltiToolsBeanTest {

    private UltiToolsBean ultiToolsBean;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private DataStore mockDataStore;

    @Mock
    private Language mockLanguage;

    @Mock
    private ConfigManager mockConfigManager;

    @Mock
    private PluginManager mockPluginManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ultiToolsBean = new UltiToolsBean();
    }

    @Test
    @DisplayName("Should be annotated with @Configuration")
    void testConfigurationAnnotation() {
        // Then
        assertTrue(UltiToolsBean.class.isAnnotationPresent(Configuration.class));
    }

    @Test
    @DisplayName("Should have @Bean annotated method for UltiTools")
    void testGetUltiToolsBeanAnnotation() throws Exception {
        // Given
        Method method = UltiToolsBean.class.getMethod("getUltiTools");

        // Then
        assertTrue(method.isAnnotationPresent(Bean.class));
    }

    @Test
    @DisplayName("Should have @Bean annotated method for DataStore")
    void testGetDataStoreBeanAnnotation() throws Exception {
        // Given
        Method method = UltiToolsBean.class.getMethod("getDataStore");

        // Then
        assertTrue(method.isAnnotationPresent(Bean.class));
    }

    @Test
    @DisplayName("Should have @Bean annotated method for Language")
    void testGetLanguageBeanAnnotation() throws Exception {
        // Given
        Method method = UltiToolsBean.class.getMethod("getLanguage");

        // Then
        assertTrue(method.isAnnotationPresent(Bean.class));
    }

    @Test
    @DisplayName("Should have @Bean annotated method for ConfigManager")
    void testGetConfigManagerBeanAnnotation() throws Exception {
        // Given
        Method method = UltiToolsBean.class.getMethod("getConfigManager");

        // Then
        assertTrue(method.isAnnotationPresent(Bean.class));
    }

    @Test
    @DisplayName("Should have @Bean annotated method for PluginManager")
    void testPluginManagerBeanAnnotation() throws Exception {
        // Given
        Method method = UltiToolsBean.class.getMethod("pluginManager");

        // Then
        assertTrue(method.isAnnotationPresent(Bean.class));
    }

    @Test
    @DisplayName("Should return UltiTools instance")
    void testGetUltiTools() {
        try (MockedStatic<UltiTools> mockedStatic = mockStatic(UltiTools.class)) {
            // Given
            mockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);

            // When
            UltiTools result = ultiToolsBean.getUltiTools();

            // Then
            assertNotNull(result);
            assertEquals(mockUltiTools, result);
            mockedStatic.verify(UltiTools::getInstance, times(1));
        }
    }

    @Test
    @DisplayName("Should return DataStore instance")
    void testGetDataStore() {
        try (MockedStatic<UltiTools> mockedStatic = mockStatic(UltiTools.class)) {
            // Given
            mockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            when(mockUltiTools.getDataStore()).thenReturn(mockDataStore);

            // When
            DataStore result = ultiToolsBean.getDataStore();

            // Then
            assertNotNull(result);
            assertEquals(mockDataStore, result);
            verify(mockUltiTools, times(1)).getDataStore();
        }
    }

    @Test
    @DisplayName("Should return Language instance")
    void testGetLanguage() {
        try (MockedStatic<UltiTools> mockedStatic = mockStatic(UltiTools.class)) {
            // Given
            mockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            when(mockUltiTools.getLanguage()).thenReturn(mockLanguage);

            // When
            Language result = ultiToolsBean.getLanguage();

            // Then
            assertNotNull(result);
            assertEquals(mockLanguage, result);
            verify(mockUltiTools, times(1)).getLanguage();
        }
    }

    @Test
    @DisplayName("Should return ConfigManager instance")
    void testGetConfigManager() {
        try (MockedStatic<UltiTools> mockedStatic = mockStatic(UltiTools.class)) {
            // Given
            mockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            when(mockUltiTools.getConfigManager()).thenReturn(mockConfigManager);

            // When
            ConfigManager result = ultiToolsBean.getConfigManager();

            // Then
            assertNotNull(result);
            assertEquals(mockConfigManager, result);
            verify(mockUltiTools, times(1)).getConfigManager();
        }
    }

    @Test
    @DisplayName("Should return PluginManager instance")
    void testPluginManager() {
        try (MockedStatic<UltiTools> mockedStatic = mockStatic(UltiTools.class)) {
            // Given
            mockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);

            // When
            PluginManager result = ultiToolsBean.pluginManager();

            // Then
            assertNotNull(result);
            assertEquals(mockPluginManager, result);
            verify(mockUltiTools, times(1)).getPluginManager();
        }
    }

    @Test
    @DisplayName("Should have all expected @Bean methods")
    void testAllBeanMethods() {
        // Given
        Method[] methods = UltiToolsBean.class.getDeclaredMethods();
        int beanMethodCount = 0;

        // When
        for (Method method : methods) {
            if (method.isAnnotationPresent(Bean.class)) {
                beanMethodCount++;
            }
        }

        // Then
        assertEquals(5, beanMethodCount, "Should have 5 @Bean annotated methods");
    }

    @Test
    @DisplayName("Should have correct method signatures")
    void testMethodSignatures() throws Exception {
        // Test getUltiTools
        Method getUltiTools = UltiToolsBean.class.getMethod("getUltiTools");
        assertEquals(UltiTools.class, getUltiTools.getReturnType());
        assertEquals(0, getUltiTools.getParameterCount());

        // Test getDataStore
        Method getDataStore = UltiToolsBean.class.getMethod("getDataStore");
        assertEquals(DataStore.class, getDataStore.getReturnType());
        assertEquals(0, getDataStore.getParameterCount());

        // Test getLanguage
        Method getLanguage = UltiToolsBean.class.getMethod("getLanguage");
        assertEquals(Language.class, getLanguage.getReturnType());
        assertEquals(0, getLanguage.getParameterCount());

        // Test getConfigManager
        Method getConfigManager = UltiToolsBean.class.getMethod("getConfigManager");
        assertEquals(ConfigManager.class, getConfigManager.getReturnType());
        assertEquals(0, getConfigManager.getParameterCount());

        // Test pluginManager
        Method pluginManager = UltiToolsBean.class.getMethod("pluginManager");
        assertEquals(PluginManager.class, pluginManager.getReturnType());
        assertEquals(0, pluginManager.getParameterCount());
    }

    @Test
    @DisplayName("Should be instantiable without dependencies")
    void testInstantiation() {
        // When
        UltiToolsBean bean = new UltiToolsBean();

        // Then
        assertNotNull(bean);
    }

    @Test
    @DisplayName("Bean methods should return consistent instances from UltiTools singleton")
    void testConsistentInstances() {
        try (MockedStatic<UltiTools> mockedStatic = mockStatic(UltiTools.class)) {
            // Given
            mockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            when(mockUltiTools.getDataStore()).thenReturn(mockDataStore);

            // When - call multiple times
            DataStore result1 = ultiToolsBean.getDataStore();
            DataStore result2 = ultiToolsBean.getDataStore();

            // Then - should return same instance (from singleton)
            assertSame(result1, result2);
            verify(mockUltiTools, times(2)).getDataStore();
        }
    }
}
