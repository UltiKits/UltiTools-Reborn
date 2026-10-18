package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.testfixtures.beanalias.BeanAliasFixtures;
import com.ultikits.testfixtures.beanalias.BeanAliasFixtures.AliasedLifecycleBean;
import com.ultikits.testfixtures.beanalias.BeanAliasFixtures.FixtureModuleEvent;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.ComponentScanner;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * C50 end to end: a module whose {@code @Configuration} binds one {@code @Bean} product under two
 * names, scanned by the real {@link ComponentScanner}, then handed to
 * {@code PluginManager.onPluginRegistered} -- the step that schedules {@code @Scheduled} methods
 * and registers {@code @ModuleEventHandler} methods for every singleton the module's container
 * holds. The aliased product is one object, so each must happen once.
 * <p>
 * {@code onPluginRegistered} is private and is driven reflectively, the same way
 * {@link PluginManagerRegistrationFailureTeardownTest} drives {@code attemptPluginRegistration}.
 */
@DisplayName("PluginManager registers an aliased @Bean's scheduled and event handlers once (C50)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // invokes the private onPluginRegistered via reflection
class PluginManagerAliasLifecycleTest {

    private static final String MODULE_NAME = "AliasModule";

    private PluginManager pluginManager;
    private TaskManager taskManager;
    private EventBus eventBus;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        AliasedLifecycleBean.resetCounters();

        eventBus = mock(EventBus.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getEventBus()).thenReturn(eventBus));

        pluginManager = new PluginManager();
        taskManager = mock(TaskManager.class);
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(pluginManager, taskManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private static void invokeOnPluginRegistered(PluginManager pm, UltiToolsPlugin plugin) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("onPluginRegistered", UltiToolsPlugin.class);
        method.setAccessible(true);
        method.invoke(pm, plugin);
    }

    @Test
    @DisplayName("an aliased bean is scheduled once and its @ModuleEventHandler is registered once")
    void aliasedBeanIsScheduledAndEventRegisteredOnce() throws Exception {
        SimpleContainer context = new SimpleContainer();
        context.setClassLoader(PluginManagerAliasLifecycleTest.class.getClassLoader());
        new ComponentScanner(context).scanPackage("com.ultikits.testfixtures.beanalias");
        Object aliased = context.getBean(BeanAliasFixtures.PRIMARY_NAME);
        assertSame(aliased, context.getBean(BeanAliasFixtures.ALIAS_NAME),
                "precondition: both names bind the same object");

        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getPluginName()).thenReturn(MODULE_NAME);
        when(plugin.getVersion()).thenReturn("1.0.0");

        invokeOnPluginRegistered(pluginManager, plugin);

        // assertAll so a failure reports both halves -- scheduling and event registration -- at once.
        assertAll(
                () -> verify(taskManager, times(1)).registerScheduledMethods(same(plugin), same(aliased)),
                () -> verify(eventBus, times(1)).register(eq(FixtureModuleEvent.class),
                        any(EventPriority.class), anyBoolean(), eq(MODULE_NAME), same(plugin),
                        any(Method.class), same(aliased)));
    }
}
