package com.ultikits.ultitools.manager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assertions;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * End-to-end proof that {@code @UltiToolsModule}'s {@code cmdExecutor}/{@code eventListener}
 * {@code @AliasFor} switches actually reach {@link PluginManager#registerBukkit}'s registration
 * decision, now that it resolves {@code @EnableAutoRegister} through
 * {@link com.ultikits.ultitools.context.MergedAnnotationResolver} instead of
 * {@link com.ultikits.ultitools.utils.AnnotationUtils#findAnnotation} (WIRE-08).
 * <p>
 * Companion to {@code MergedAnnotationResolverTest}, which covers the resolver's own merge
 * semantics in isolation; this class exercises the same switches through the real caller.
 */
@DisplayName("PluginManager auto-register @AliasFor wiring (WIRE-08)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of the private registerBukkit
class PluginManagerAutoRegisterAliasTest {

    // Abstract fixtures extending UltiToolsPlugin, mocked rather than constructed -- Mockito
    // bypasses the constructor entirely (Objenesis) and, with the default inline mock maker,
    // mock.getClass() returns the real fixture class, so its annotations are read correctly.
    // Following the same idiom already established in DependencyUtilsTest.

    @UltiToolsModule
    abstract static class DefaultModuleFixture extends UltiToolsPlugin {
    }

    @UltiToolsModule(cmdExecutor = false)
    abstract static class CmdExecutorDisabledFixture extends UltiToolsPlugin {
    }

    private PluginManager pluginManager;
    private CommandManager mockCommandManager;
    private ListenerManager mockListenerManager;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        mockCommandManager = mock(CommandManager.class);
        mockListenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(mockCommandManager);
            when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
        });

        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private void invokeRegisterBukkit(UltiToolsPlugin plugin) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("registerBukkit", UltiToolsPlugin.class, boolean.class);
        method.setAccessible(true);
        method.invoke(pluginManager, plugin, true);
    }

    @Test
    @DisplayName("Default @UltiToolsModule reaches CommandManager.registerAll")
    void defaultModuleRegistersCommands() throws Exception {
        UltiToolsPlugin plugin = mock(DefaultModuleFixture.class);

        invokeRegisterBukkit(plugin);

        verify(mockCommandManager, times(1)).registerAll(plugin);
    }

    @Test
    @DisplayName("@UltiToolsModule(cmdExecutor = false) suppresses CommandManager.registerAll entirely")
    void cmdExecutorDisabledModuleNeverReachesCommandManager() throws Exception {
        UltiToolsPlugin plugin = mock(CmdExecutorDisabledFixture.class);

        invokeRegisterBukkit(plugin);

        verify(mockCommandManager, never()).registerAll(plugin);
    }

    @Test
    @DisplayName("Inert-case guard: the default and disabled fixtures must not produce equal registerAll counts")
    void defaultAndDisabledFixturesMustNotProduceEqualCounts() throws Exception {
        // If the resolver silently returns the un-merged @EnableAutoRegister (the exact defect
        // this plan closes), both fixtures would register and the two counts below would be
        // equal (1 and 1) instead of (1 and 0) -- this assertion fails loudly on that equality
        // rather than merely leaving it unasserted.
        UltiToolsPlugin defaultPlugin = mock(DefaultModuleFixture.class);
        UltiToolsPlugin disabledPlugin = mock(CmdExecutorDisabledFixture.class);

        invokeRegisterBukkit(defaultPlugin);
        invokeRegisterBukkit(disabledPlugin);

        long defaultCount = org.mockito.Mockito.mockingDetails(mockCommandManager).getInvocations().stream()
                .filter(invocation -> "registerAll".equals(invocation.getMethod().getName())
                        && invocation.getArgument(0) == defaultPlugin)
                .count();
        long disabledCount = org.mockito.Mockito.mockingDetails(mockCommandManager).getInvocations().stream()
                .filter(invocation -> "registerAll".equals(invocation.getMethod().getName())
                        && invocation.getArgument(0) == disabledPlugin)
                .count();

        Assertions.assertNotEquals(defaultCount, disabledCount,
                "a resolver that fails to merge the @AliasFor override would register both fixtures identically");
        Assertions.assertEquals(1L, defaultCount);
        Assertions.assertEquals(0L, disabledCount);
    }
}
