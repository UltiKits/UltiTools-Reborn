package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.commands.UltiToolsCommands;

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
    private ServerMock server;
    private final Set<LogRecord> reloadLogs = new LinkedHashSet<>();
    private Handler captureHandler;
    private List<String> returnedSummary;

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
        server = MockBukkit.mock();
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
        if (captureHandler != null) {
            Logger.getLogger(UltiToolsPlugin.class.getName()).removeHandler(captureHandler);
            Bukkit.getLogger().removeHandler(captureHandler);
        }
        MockBukkitHelper.safeUnmock();
    }

    private void captureReloadLogs() {
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                reloadLogs.add(record);
            }

            @Override
            public void flush() {
                // Records are captured immediately.
            }

            @Override
            public void close() {
                // The test owns the records.
            }
        };
        Logger.getLogger(UltiToolsPlugin.class.getName()).addHandler(captureHandler);
        Bukkit.getLogger().addHandler(captureHandler);
    }

    private List<String> partialLogMessages() {
        List<String> lines = new ArrayList<>();
        for (LogRecord record : reloadLogs) {
            if (record.getMessage().contains("reloaded partially; not reloaded:")) {
                lines.add(record.getMessage());
            }
        }
        return lines;
    }

    private void prepareCommandReload() throws Exception {
        loadCooldownExecutor();
        config.setWildCooldown(-1);
        PluginListSeeding.add(pluginManager, module);
        ConfigManager configManager = UltiToolsPlugin.getConfigManager();
        TestHelper.mockUltiToolsInstance(tools -> {
            when(tools.getConfigManager()).thenReturn(configManager);
            when(tools.getPluginManager()).thenReturn(pluginManager);
            try {
                when(tools.reloadPluginsAndReport()).thenAnswer(invocation -> {
                    returnedSummary = pluginManager.reloadAllAndReport();
                    return returnedSummary;
                });
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
        });
        captureReloadLogs();
    }

    private void runReloadCommand(CommandSender sender, boolean all) {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("ul");
        new UltiToolsCommands().onCommand(sender, command, "ul",
                all ? new String[]{"reload"} : new String[]{"reload", "TimingModule"});
        server.getScheduler().performOneTick();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#665: named and full reload print one partial summary on the native console")
    void nativeConsoleGetsOnePartialSummary(boolean all) throws Exception {
        prepareCommandReload();

        runReloadCommand(server.getConsoleSender(), all);

        List<String> consoleReplies = new ArrayList<>();
        String reply;
        while ((reply = server.getConsoleSender().nextMessage()) != null) {
            consoleReplies.add(reply);
        }
        long replySummaries = consoleReplies.stream()
                .filter(line -> line.contains("reloaded partially; not reloaded:")).count();
        assertThat(partialLogMessages().size() + replySummaries)
                .as("one partial summary across module logger, manager logger and native console replies")
                .isEqualTo(1);
        assertThat(partialLogMessages()).singleElement().asString()
                .contains("Module TimingModule reloaded partially; not reloaded:", "cooldown.wild", "-1", "60");
        assertThat(reloadLogs).anySatisfy(record -> assertThat(record.getMessage())
                .contains("@CmdCD bound to", "keeping 60s"));
        if (all) {
            assertThat(returnedSummary).anySatisfy(line -> assertThat(line)
                    .contains("TimingModule", "cooldown.wild", "-1", "60"));
            assertThat(consoleReplies).contains(returnedSummary.get(0));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#665: a distinct console-permission sender keeps every partial reason")
    void independentConsoleSenderKeepsFeedback(boolean all) throws Exception {
        prepareCommandReload();
        ConsoleCommandSender independent = mock(ConsoleCommandSender.class);
        when(independent.isOp()).thenReturn(true);
        when(independent.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        List<String> replies = new ArrayList<>();
        doAnswer(invocation -> {
            replies.add(invocation.getArgument(0));
            return null;
        }).when(independent).sendMessage(org.mockito.ArgumentMatchers.anyString());

        runReloadCommand(independent, all);

        assertThat(replies).anySatisfy(line -> assertThat(line)
                .contains("Module TimingModule reloaded partially; not reloaded:", "cooldown.wild", "-1", "60"));
        assertThat(partialLogMessages()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#665: player feedback retains the refused value and the running value")
    void playerKeepsFeedback(boolean all) throws Exception {
        prepareCommandReload();
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer("operator");
        player.setOp(true);

        runReloadCommand(player, all);

        List<String> replies = new ArrayList<>();
        String reply;
        while ((reply = player.nextMessage()) != null) {
            replies.add(reply);
        }
        assertThat(replies).anySatisfy(line -> assertThat(line)
                .contains("Module TimingModule reloaded partially; not reloaded:", "cooldown.wild", "-1", "60"));
        assertThat(partialLogMessages()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#665: an ordinary sender never needs a Bukkit console lookup")
    void ordinarySenderDoesNotQueryConsoleWithoutServer(boolean all) throws Exception {
        ReloadReport report = new ReloadReport();
        report.partial("cooldown.wild was refused; the running 60s is kept");
        doReturn(report).when(module).reloadWithReport();
        PluginListSeeding.add(pluginManager, module);
        String line = "Module TimingModule reloaded partially; not reloaded: "
                + report.getPartialReasons().get(0);
        TestHelper.mockUltiToolsInstance(tools -> {
            when(tools.getPluginManager()).thenReturn(pluginManager);
            try {
                when(tools.reloadPluginsAndReport()).thenReturn(Collections.singletonList(line));
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
        });
        CommandSender sender = mock(CommandSender.class);
        try (org.mockito.MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertThat(Bukkit.getServer()).as("the ordinary sender has no Bukkit server").isNull();
            UltiToolsCommands commands = new UltiToolsCommands();
            if (all) {
                commands.reloadAll(sender);
            } else {
                commands.reloadPlugin(sender, "TimingModule");
            }
            verify(sender).sendMessage(line);
            bukkit.verify(Bukkit::getConsoleSender, never());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#665: native console success and failure replies are not swallowed")
    void nativeConsoleKeepsSuccessAndFailureReplies(boolean all) throws Exception {
        prepareCommandReload();
        config.setWildCooldown(30);
        runReloadCommand(server.getConsoleSender(), all);
        assertThat(server.getConsoleSender().nextMessage()).isNotBlank();
        while (server.getConsoleSender().nextMessage() != null) {
            // Drain the successful reload before checking a separate failure.
        }
        doThrow(new IllegalStateException("hook unavailable")).when(module).reloadWithReport();

        runReloadCommand(server.getConsoleSender(), all);

        assertThat(server.getConsoleSender().nextMessage()).contains("TimingModule");
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
        doAnswer(call -> {
            if (arms.incrementAndGet() == 2) {
                throw new IllegalStateException("scheduler unavailable");
            }
            return real.runTaskTimer(call.getArgument(0), (Runnable) call.getArgument(1),
                    (Long) call.getArgument(2), (Long) call.getArgument(3));
        }).when(failing).runTaskTimer(Mockito.any(Plugin.class), Mockito.any(Runnable.class),
                Mockito.anyLong(), Mockito.anyLong());
        ReloadReport report;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
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

    /** Collects every WARNING the framework writes to the Bukkit logger while {@code action} runs. */
    private static List<String> warningsDuring(Runnable action) {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (Level.WARNING.equals(logRecord.getLevel())) {
                    warnings.add(logRecord.getMessage());
                }
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Bukkit.getLogger().addHandler(handler);
        try {
            action.run();
        } finally {
            Bukkit.getLogger().removeHandler(handler);
        }
        return warnings;
    }

    @Test
    @DisplayName("Chinese: the per-key console WARNING for a refused @Scheduled value carries no English framework text")
    void chineseTimerRefusalWarningIsTranslated() throws Exception {
        useCatalogue("zh");
        loadScheduledTask();
        config.setPeriodSeconds(0);
        List<String> warnings = warningsDuring(module::reloadWithReport);
        assertThat(warnings).filteredOn(w -> w.contains("timer.period")).isNotEmpty()
                .allSatisfy(w -> assertThat(w).contains("整数秒").contains("5")
                        .doesNotContain("after the reload").doesNotContain("a bound @Scheduled period or delay")
                        .doesNotContain("is bound to").doesNotContain("keeping"));
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // exercise the real source's incomplete-load signal
    @DisplayName("Chinese: the per-key console WARNING for an unvalidated @Scheduled value carries no English framework text")
    void chineseTimerNotValidatedWarningIsTranslated() throws Exception {
        useCatalogue("zh");
        loadScheduledTask();
        Field incomplete = AbstractConfigEntity.class.getDeclaredField("lastInitIncomplete");
        incomplete.setAccessible(true);
        incomplete.setBoolean(config, true);
        List<String> warnings = warningsDuring(module::reloadWithReport);
        assertThat(warnings).filteredOn(w -> w.contains("timer.period")).isNotEmpty()
                .allSatisfy(w -> assertThat(w).contains("未完成")
                        .doesNotContain("did not complete").doesNotContain("were not validated")
                        .doesNotContain("is bound to").doesNotContain("keeping"));
    }

    @Test
    @DisplayName("Chinese: the per-key console WARNING for a refused @CmdCD value carries no English framework text")
    void chineseCooldownRefusalWarningIsTranslated() throws Exception {
        useCatalogue("zh");
        loadCooldownExecutor();
        config.setWildCooldown(-1);
        List<String> warnings = warningsDuring(module::reloadWithReport);
        assertThat(warnings).filteredOn(w -> w.contains("cooldown.wild")).isNotEmpty()
                .allSatisfy(w -> assertThat(w).contains("整数秒").contains("60")
                        .doesNotContain("it has value").doesNotContain("after the reload").doesNotContain("a bound")
                        .doesNotContain("is not updated").doesNotContain("keeping"));
    }

    @Test
    @DisplayName("English: the per-key console WARNINGs keep their existing wording")
    void englishPerKeyWarningsAreUnchanged() throws Exception {
        useCatalogue("en");
        loadScheduledTask();
        loadCooldownExecutor();
        config.setPeriodSeconds(0);
        config.setWildCooldown(-1);
        List<String> warnings = warningsDuring(module::reloadWithReport);
        assertThat(warnings).anySatisfy(w -> assertThat(w).startsWith("[UltiTools-API] TimingModule: ")
                .endsWith(" is bound to BindingTimingConfig key 'timer.period', which has value 0 after the reload; "
                        + ConfigBindings.TIMER_RULE + "; keeping 5s"));
        assertThat(warnings).contains("[UltiTools-API] TimingModule: @CmdCD bound to BindingTimingConfig key "
                + "'cooldown.wild' is not updated: it has value -1 after the reload; " + ConfigBindings.COOLDOWN_RULE
                + "; keeping 60s");
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
