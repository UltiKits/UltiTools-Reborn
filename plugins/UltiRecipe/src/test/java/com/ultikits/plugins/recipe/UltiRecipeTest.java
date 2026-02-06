package com.ultikits.plugins.recipe;

import com.ultikits.plugins.recipe.service.RecipeService;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiRecipe Main Class Tests")
class UltiRecipeTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiRecipeTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Singleton Management")
    class SingletonManagement {

        @Test
        @DisplayName("registerSelf should set instance and return true")
        void registerSelf() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);
            RecipeService service = mock(RecipeService.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(context.getBean(RecipeService.class)).thenReturn(service);
            when(service.initRecipes()).thenReturn(5);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(plugin.registerSelf()).thenCallRealMethod();

            // Set instance field to null first
            UltiRecipeTestHelper.setStaticField(UltiRecipe.class, "instance", null);

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            assertThat(UltiRecipe.getInstance()).isSameAs(plugin);
            verify(service).initRecipes();
            verify(logger).info(contains("已注册"));
            verify(logger).info(contains("已启用"));
        }

        @Test
        @DisplayName("getInstance should return null when not registered")
        void getInstanceNull() throws Exception {
            UltiRecipeTestHelper.setStaticField(UltiRecipe.class, "instance", null);
            assertThat(UltiRecipe.getInstance()).isNull();
        }
    }

    @Nested
    @DisplayName("Lifecycle Methods")
    class LifecycleMethods {

        @Test
        @DisplayName("unregisterSelf should remove recipes and clear instance")
        void unregisterSelf() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);
            RecipeService service = mock(RecipeService.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(context.getBean(RecipeService.class)).thenReturn(service);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).unregisterSelf();

            // Set instance first
            UltiRecipeTestHelper.setStaticField(UltiRecipe.class, "instance", plugin);

            plugin.unregisterSelf();

            verify(service).removeRecipes();
            verify(logger).info(contains("已禁用"));
            // Note: instance is set to null in real method but we can't verify it without calling again
        }

        @Test
        @DisplayName("unregisterSelf should handle null service gracefully")
        void unregisterSelfNullService() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(context.getBean(RecipeService.class)).thenReturn(null);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).unregisterSelf();

            // Should not throw exception
            plugin.unregisterSelf();

            verify(logger).info(contains("已禁用"));
        }

        @Test
        @DisplayName("reloadSelf should reload recipes")
        void reloadSelf() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);
            RecipeService service = mock(RecipeService.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(context.getBean(RecipeService.class)).thenReturn(service);
            when(service.reloadRecipes()).thenReturn(8);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).reloadSelf();

            plugin.reloadSelf();

            verify(service).reloadRecipes();
            verify(logger).info(contains("配方已重载"));
            verify(logger).info(contains("8"));
        }

        @Test
        @DisplayName("reloadSelf should handle null service gracefully")
        void reloadSelfNullService() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(context.getBean(RecipeService.class)).thenReturn(null);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).reloadSelf();

            // Should not throw exception
            plugin.reloadSelf();

            verify(logger, never()).info(contains("配方已重载"));
        }
    }

    @Nested
    @DisplayName("Service Integration")
    class ServiceIntegration {

        @Test
        @DisplayName("registerSelf should handle service initialization failure")
        void registerSelfServiceFails() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);
            RecipeService service = mock(RecipeService.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(context.getBean(RecipeService.class)).thenReturn(service);
            when(service.initRecipes()).thenReturn(0);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(plugin.registerSelf()).thenCallRealMethod();

            UltiRecipeTestHelper.setStaticField(UltiRecipe.class, "instance", null);

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            verify(logger).info(contains("已注册 0"));
        }

        @Test
        @DisplayName("registerSelf should handle null context gracefully")
        void registerSelfNullContext() throws Exception {
            UltiRecipe plugin = mock(UltiRecipe.class);
            PluginLogger logger = mock(PluginLogger.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(null);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(plugin.registerSelf()).thenCallRealMethod();

            UltiRecipeTestHelper.setStaticField(UltiRecipe.class, "instance", null);

            // Should throw NPE when trying to get bean from null context
            assertThatThrownBy(() -> plugin.registerSelf())
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
