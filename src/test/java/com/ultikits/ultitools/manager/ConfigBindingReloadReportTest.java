package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.ReloadReport;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.testutil.BindingTimingConfig;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #595: when a reload keeps a config-bound {@code @Scheduled} or {@code @CmdCD} value because the
 * new one is refused, or the binding step itself fails, the module's {@link ReloadReport} says so.
 * Before the fix only a console WARNING was written and {@code reloadWithReport()} returned an empty
 * report, so {@code /ul reload <module>} and a module's own reload command replied plain success.
 */
@DisplayName("Refused config-bound values are reported in the reload report (#595)")
class ConfigBindingReloadReportTest {

    private BindingTimingConfig config;
    private PluginManager pluginManager;
    private ModuleFixture module;
    private JavaPlugin host;

    /** Bare fixture. */
    abstract static class ModuleFixture extends UltiToolsPlugin {
    }

    /** Bound period. */
    public static class BoundPeriodBean {
        int runs;

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period")
        public void tick() {
            runs++;
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"reportwild"})
    static class BoundCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @BeforeEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same reload fixture idiom as UltiToolsPluginReloadBindingStepTest
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        host = MockBukkit.createMockPlugin();
        config = new BindingTimingConfig();
        ConfigManager configManager = mock(ConfigManager.class);
        pluginManager = new PluginManager();
        YamlConfiguration running = new YamlConfiguration();
        running.set("language", "en");
        module = mock(ModuleFixture.class);
        lenient().when(module.getPluginName()).thenReturn("TimingModule");
        lenient().when(module.getMinUltiToolsVersion()).thenReturn(630);
        lenient().when(module.getLogger()).thenReturn(mock(PluginLogger.class));
        lenient().when(configManager.getConfigEntities(module, BindingTimingConfig.class))
                .thenReturn(Collections.singletonList(config));
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getConfigManager()).thenReturn(configManager);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
            when(ultiTools.getConfig()).thenReturn(running);
        });
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(module, System.getProperty("java.io.tmpdir"));
        doCallRealMethod().when(module).reloadWithReport();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the plugin manager's task manager is set by its init(), not here
    private void useTaskManager(TaskManager taskManager) throws Exception {
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(pluginManager, taskManager);
    }

    private void loadCooldownExecutor() {
        config.setWildCooldown(60);
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("executor", new BoundCooldownExecutor());
        lenient().when(module.getContext()).thenReturn(container);
        PluginManager.validateConfigBindings(module, container);
    }

    private void loadScheduledTask() throws Exception {
        lenient().when(module.getContext()).thenReturn(new SimpleContainer());
        TaskManager taskManager = new TaskManager(host);
        useTaskManager(taskManager);
        config.setPeriodSeconds(5);
        taskManager.registerScheduledMethods(module, new BoundPeriodBean());
    }

    @Test
    @DisplayName("a refused @CmdCD value makes the reload partial, naming the key, the value and the value kept")
    void refusedCooldownIsReported() {
        loadCooldownExecutor();

        config.setWildCooldown(-1);
        ReloadReport report = module.reloadWithReport();

        assertThat(report.getPartialReasons()).hasSize(1).allSatisfy(reason -> assertThat(reason)
                .contains("cooldown.wild").contains("-1").contains("60").doesNotContain("%s").doesNotContain("%d"));
    }

    @Test
    @DisplayName("a refused @Scheduled value makes the reload partial, naming the key, the value and the value kept")
    void refusedScheduledValueIsReported() throws Exception {
        loadScheduledTask();

        config.setPeriodSeconds(0);
        ReloadReport report = module.reloadWithReport();

        assertThat(report.getPartialReasons()).hasSize(1).allSatisfy(reason -> assertThat(reason)
                .contains("timer.period").contains("0").contains("5").doesNotContain("%s").doesNotContain("%d"));
    }

    @Test
    @DisplayName("a binding step that throws makes the reload partial instead of being swallowed")
    void failedBindingStepIsReported() throws Exception {
        lenient().when(module.getContext()).thenReturn(new SimpleContainer());
        useTaskManager(mock(TaskManager.class, invocation -> {
            if ("rescheduleBound".equals(invocation.getMethod().getName())) {
                throw new IllegalStateException("task registry unavailable");
            }
            return null;
        }));

        ReloadReport report = module.reloadWithReport();

        assertThat(report.getPartialReasons()).hasSize(1).allSatisfy(reason -> assertThat(reason)
                .contains("@Scheduled").contains("task registry unavailable"));
    }

    @Test
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes") // inject one ordinary scheduler failure after the first update
    void aLaterTaskFailureDoesNotPretendEarlierUpdatesWereRolledBack() throws Exception {
        TaskManager tasks = new TaskManager(host);
        useTaskManager(tasks);
        BoundPeriodBean first = new BoundPeriodBean();
        BoundPeriodBean second = new BoundPeriodBean();
        config.setPeriodSeconds(5);
        tasks.registerScheduledMethods(module, first);
        tasks.registerScheduledMethods(module, second);
        config.setPeriodSeconds(10);
        BukkitScheduler real = Bukkit.getScheduler();
        BukkitScheduler failing = mock(BukkitScheduler.class, AdditionalAnswers.delegatesTo(real));
        AtomicInteger arms = new AtomicInteger();
        Mockito.doAnswer(call -> {
            if (arms.incrementAndGet() == 2) {
                throw new IllegalStateException("scheduler unavailable");
            }
            return real.runTaskTimer(call.getArgument(0), (Runnable) call.getArgument(1),
                    (Long) call.getArgument(2), (Long) call.getArgument(3));
        }).when(failing).runTaskTimer(Mockito.any(Plugin.class), Mockito.any(Runnable.class),
                Mockito.anyLong(), Mockito.anyLong());
        ReloadReport report;
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(failing);
            report = module.reloadWithReport();
        }
        MockBukkit.getMock().getScheduler().performTicks(201);
        assertThat(first.runs).as("the first task retains its new 200-tick period, not the old 100").isEqualTo(2);
        assertThat(second.runs).as("the later task's replacement could not be armed").isZero();
        assertThat(arms.get()).isEqualTo(2);
        assertThat(report.getPartialReasons()).singleElement().asString()
                .contains("updates applied before the failure remain in effect")
                .contains("remaining updates did not complete")
                .doesNotContain("the running values are kept");
    }

    /** Real shipped catalogue content, with only the singleton wiring mocked. */
    private void useCatalogue(String language) throws Exception {
        JsonObject catalogue;
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/lang/" + language + ".json"),
                StandardCharsets.UTF_8)) {
            catalogue = JsonParser.parseReader(reader).getAsJsonObject();
        }
        ConfigManager configs = UltiTools.getInstance().getConfigManager();
        org.bukkit.configuration.file.FileConfiguration running = UltiTools.getInstance().getConfig();
        TestHelper.mockUltiToolsInstance(framework -> {
            when(framework.getConfigManager()).thenReturn(configs);
            when(framework.getPluginManager()).thenReturn(pluginManager);
            when(framework.getConfig()).thenReturn(running);
            when(framework.i18n(Mockito.anyString())).thenAnswer(call -> {
                String key = call.getArgument(0);
                return catalogue.has(key) ? catalogue.get(key).getAsString() : key;
            });
        });
    }

    @Test
    void chineseTimerRefusalTranslatesTheOwnedRule() throws Exception {
        useCatalogue("zh");
        loadScheduledTask();
        config.setPeriodSeconds(0);
        assertThat(module.reloadWithReport().getPartialReasons()).singleElement().asString()
                .contains("整数秒").contains("1").contains(String.valueOf(ConfigBindings.MAX_TICK_SECONDS))
                .doesNotContain("a bound").doesNotContain("whole number").doesNotContain("does not mean off");
    }

    @Test
    void chineseCooldownRefusalTranslatesTheOwnedReasonAndRule() throws Exception {
        useCatalogue("zh");
        loadCooldownExecutor();
        config.setWildCooldown(-1);
        assertThat(module.reloadWithReport().getPartialReasons()).singleElement().asString()
                .contains("整数秒").contains("-1").contains(String.valueOf(Integer.MAX_VALUE))
                .doesNotContain("it has value").doesNotContain("after the reload").doesNotContain("a bound");
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // exercise the real source's incomplete-load signal
    void chineseCooldownRefusalTranslatesTheIncompleteConfigSource() throws Exception {
        useCatalogue("zh");
        loadCooldownExecutor();
        Field incomplete = AbstractConfigEntity.class.getDeclaredField("lastInitIncomplete");
        incomplete.setAccessible(true);
        incomplete.setBoolean(config, true);
        assertThat(module.reloadWithReport().getPartialReasons()).singleElement().asString()
                .contains("config/timing.yml").contains("60").contains("未完成")
                .doesNotContain("the reload of").doesNotContain("did not complete")
                .doesNotContain("were not validated");
    }

    @Test
    @DisplayName("valid reloaded values leave the report empty")
    void validValuesAreNotPartial() throws Exception {
        loadCooldownExecutor();
        TaskManager taskManager = new TaskManager(host);
        useTaskManager(taskManager);
        config.setPeriodSeconds(5);
        taskManager.registerScheduledMethods(module, new BoundPeriodBean());

        config.setWildCooldown(30);
        config.setPeriodSeconds(7);
        ReloadReport report = module.reloadWithReport();

        assertThat(report.isPartial()).isFalse();
    }
}
