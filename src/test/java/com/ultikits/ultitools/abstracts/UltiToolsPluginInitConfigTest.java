package com.ultikits.ultitools.abstracts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * End-to-end proof that {@code @UltiToolsModule}'s {@code config} {@code @AliasFor} switch
 * actually reaches {@link UltiToolsPlugin#initConfig()}'s registration decision, now that it
 * resolves {@code @EnableAutoRegister} through
 * {@link com.ultikits.ultitools.context.MergedAnnotationResolver} instead of
 * {@link com.ultikits.ultitools.utils.AnnotationUtils#findAnnotation} (WIRE-08).
 * <p>
 * Companion to {@code PluginManagerAutoRegisterAliasTest} (03-01), which proves the same wiring
 * for {@code eventListener}/{@code cmdExecutor} through {@code PluginManager.registerBukkit}.
 * Before this migration, {@code @UltiToolsModule(config = false)} was silently ignored: the
 * legacy lookup read the raw, un-merged {@code @EnableAutoRegister} declared on
 * {@code @UltiToolsModule} itself (default {@code config() == true}), never the module's own
 * override.
 */
@DisplayName("UltiToolsPlugin.initConfig @AliasFor wiring (WIRE-08)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of the private initConfig
class UltiToolsPluginInitConfigTest {

    // Abstract fixtures extending UltiToolsPlugin, mocked rather than constructed -- Mockito
    // bypasses the constructor entirely (Objenesis) and, with the default inline mock maker,
    // mock.getClass() returns the real fixture class, so its annotations are read correctly.
    // Same idiom as PluginManagerAutoRegisterAliasTest (03-01).

    @UltiToolsModule
    abstract static class DefaultModuleFixture extends UltiToolsPlugin {
    }

    @UltiToolsModule(config = false)
    abstract static class ConfigDisabledFixture extends UltiToolsPlugin {
    }

    private ConfigManager mockConfigManager;

    @BeforeEach
    void setUp() {
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(mockConfigManager));
    }

    private void invokeInitConfig(UltiToolsPlugin plugin) throws Exception {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("initConfig");
        method.setAccessible(true);
        method.invoke(plugin);
    }

    @Test
    @DisplayName("Default @UltiToolsModule reaches ConfigManager.registerAll")
    void defaultModuleRegistersConfig() throws Exception {
        UltiToolsPlugin plugin = mock(DefaultModuleFixture.class);

        invokeInitConfig(plugin);

        verify(mockConfigManager, times(1)).registerAll(org.mockito.ArgumentMatchers.eq(plugin), anyString(), any());
    }

    @Test
    @DisplayName("@UltiToolsModule(config = false) suppresses ConfigManager.registerAll entirely")
    void configDisabledModuleNeverReachesConfigManager() throws Exception {
        UltiToolsPlugin plugin = mock(ConfigDisabledFixture.class);

        invokeInitConfig(plugin);

        verify(mockConfigManager, never()).registerAll(any(), anyString(), any());
        verify(mockConfigManager, never()).register(any(), any());
    }

    @Test
    @DisplayName("Inert-case guard: the default and disabled fixtures must not produce equal registerAll counts")
    void defaultAndDisabledFixturesMustNotProduceEqualCounts() throws Exception {
        // If the resolver silently returns the un-merged @EnableAutoRegister (the exact defect
        // this plan closes), both fixtures would skip registration in the same way and the two
        // counts below would be equal (0 and 0) instead of (1 and 0) -- this assertion fails
        // loudly on that equality rather than merely leaving it unasserted.
        UltiToolsPlugin defaultPlugin = mock(DefaultModuleFixture.class);
        UltiToolsPlugin disabledPlugin = mock(ConfigDisabledFixture.class);

        invokeInitConfig(defaultPlugin);
        invokeInitConfig(disabledPlugin);

        long defaultCount = org.mockito.Mockito.mockingDetails(mockConfigManager).getInvocations().stream()
                .filter(invocation -> "registerAll".equals(invocation.getMethod().getName())
                        && invocation.getArgument(0) == defaultPlugin)
                .count();
        long disabledCount = org.mockito.Mockito.mockingDetails(mockConfigManager).getInvocations().stream()
                .filter(invocation -> "registerAll".equals(invocation.getMethod().getName())
                        && invocation.getArgument(0) == disabledPlugin)
                .count();

        Assertions.assertNotEquals(defaultCount, disabledCount,
                "a resolver that fails to merge the @AliasFor override would skip both fixtures identically");
        Assertions.assertEquals(1L, defaultCount);
        Assertions.assertEquals(0L, disabledCount);
    }
}
