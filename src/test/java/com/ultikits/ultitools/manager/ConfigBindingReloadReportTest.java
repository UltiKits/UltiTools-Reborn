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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
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
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period")
        public void tick() {
            // Scheduling is what is asserted.
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
