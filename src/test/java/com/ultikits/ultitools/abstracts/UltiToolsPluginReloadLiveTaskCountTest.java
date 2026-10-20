package com.ultikits.ultitools.abstracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;
import com.ultikits.ultitools.testutil.BindingTimingConfig;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #531, threat "a bound task runs twice after reload": after one and after two {@code /ul reload}
 * -- through the real {@code final} {@link UltiToolsPlugin#reloadSelf()}, the real
 * {@link PluginManager#applyReloadedConfigBindings} and a real {@link TaskManager} on MockBukkit's
 * real scheduler -- each config-bound {@code @Scheduled} method has exactly ONE live task. Live
 * tasks are counted in the scheduler itself (pending, not cancelled, owned by the host plugin),
 * not inferred from how often the method ran, and this holds both when the bound value changed
 * (the reschedule path) and when it did not (the no-op path). Run counts are asserted as well, as
 * a second, independent symptom of a duplicated timer.
 */
@DisplayName("reloadSelf() keeps exactly one live task per config-bound @Scheduled method (#531)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // wires a real TaskManager into PluginManager and sets resourceFolderPath
class UltiToolsPluginReloadLiveTaskCountTest {

    private ServerMock server;
    private JavaPlugin host;
    private BindingTimingConfig config;
    private FixturePlugin module;
    private TaskManager taskManager;

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    /** Bound period, default delay. */
    public static class BoundPeriodBean {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period")
        public void tick() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    /** The interest pattern: delay and period bound to the same key. */
    public static class BoundInterestBean {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", delayKey = "timer.period")
        public void pay() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        host = MockBukkit.createMockPlugin();
        config = new BindingTimingConfig();

        module = mock(FixturePlugin.class);
        lenient().when(module.getPluginName()).thenReturn("TimingModule");
        lenient().when(module.getLogger()).thenReturn(mock(PluginLogger.class));
        lenient().when(module.getContext()).thenReturn(new SimpleContainer());
        Field resourceFolderPath = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPath.setAccessible(true);
        resourceFolderPath.set(module, System.getProperty("java.io.tmpdir"));
        doCallRealMethod().when(module).reloadSelf();

        ConfigManager configManager = mock(ConfigManager.class);
        lenient().when(configManager.getConfigEntities(module, BindingTimingConfig.class))
                .thenReturn(Collections.singletonList(config));

        taskManager = new TaskManager(host);
        PluginManager pluginManager = new PluginManager();
        Field taskManagerField = PluginManager.class.getDeclaredField("taskManager");
        taskManagerField.setAccessible(true);
        taskManagerField.set(pluginManager, taskManager);

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getConfigManager()).thenReturn(configManager);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    /** Pending, not-cancelled tasks the host plugin owns -- every task TaskManager schedules. */
    private int liveTasks() {
        int live = 0;
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (!task.isCancelled() && task.getOwner() == host) {
                live++;
            }
        }
        return live;
    }

    private void advanceTo(int tick) {
        server.getScheduler().performTicks(tick - Bukkit.getCurrentTick());
        assertEquals(tick, Bukkit.getCurrentTick());
    }

    @Test
    @DisplayName("changed value: one live task after one reload and after two, and the runs match a single timer")
    void changedValueKeepsOneLiveTaskAcrossTwoReloads() {
        config.setPeriodSeconds(5);
        BoundPeriodBean bean = new BoundPeriodBean();
        taskManager.registerScheduledMethods(module, bean);
        assertEquals(1, liveTasks());
        advanceTo(140);

        config.setPeriodSeconds(10);
        module.reloadSelf();
        assertEquals(1, liveTasks(), "after the first reload");

        advanceTo(320);
        config.setPeriodSeconds(4);
        module.reloadSelf();
        assertEquals(1, liveTasks(), "after the second reload");

        advanceTo(700);
        assertEquals(1, liveTasks());
        // 1, 101 at 5 s; 301 at last + 10 s; then 4 s: 381, 461, 541, 621 -- one timer's runs only.
        assertEquals(Arrays.asList(1, 101, 301, 381, 461, 541, 621), bean.fireTicks);
    }

    @Test
    @DisplayName("unchanged value: one live task after one reload and after two, and the runs match a single timer")
    void unchangedValueKeepsOneLiveTaskAcrossTwoReloads() {
        config.setPeriodSeconds(5);
        BoundPeriodBean bean = new BoundPeriodBean();
        taskManager.registerScheduledMethods(module, bean);
        advanceTo(140);

        module.reloadSelf();
        assertEquals(1, liveTasks(), "after the first reload");
        advanceTo(250);
        module.reloadSelf();
        assertEquals(1, liveTasks(), "after the second reload");

        advanceTo(405);
        assertEquals(Arrays.asList(1, 101, 201, 301, 401), bean.fireTicks);
    }

    @Test
    @DisplayName("interest pattern: one live task across two changed reloads, one before and one after the first payment")
    void interestPatternKeepsOneLiveTaskAcrossTwoReloads() {
        config.setPeriodSeconds(10);
        BoundInterestBean bean = new BoundInterestBean();
        taskManager.registerScheduledMethods(module, bean);
        advanceTo(100);

        config.setPeriodSeconds(15);
        module.reloadSelf();
        assertEquals(1, liveTasks(), "after the reload before the first payment");

        advanceTo(350);
        assertEquals(Collections.singletonList(300), bean.fireTicks, "arm tick 0 + 15 s, paid once");
        config.setPeriodSeconds(5);
        module.reloadSelf();
        assertEquals(1, liveTasks(), "after the reload after the first payment");

        advanceTo(600);
        assertEquals(1, liveTasks());
        // 300 + 5 s = 400, then 500, 600 -- never two payments on the same tick.
        assertEquals(Arrays.asList(300, 400, 500, 600), bean.fireTicks);
    }
}
