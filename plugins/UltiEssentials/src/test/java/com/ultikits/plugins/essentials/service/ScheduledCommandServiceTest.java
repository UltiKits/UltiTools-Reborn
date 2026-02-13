package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ScheduledCommandService Tests")
class ScheduledCommandServiceTest {

    private ScheduledCommandService service;
    private EssentialsConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        mockConfig = mock(EssentialsConfig.class);
        service = new ScheduledCommandService();
        setField(service, "config", mockConfig);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); // NOPMD - test reflection
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private List<BukkitTask> getTasks(ScheduledCommandService svc) throws Exception {
        Field field = svc.getClass().getDeclaredField("tasks");
        field.setAccessible(true); // NOPMD - test reflection
        return (List<BukkitTask>) field.get(svc);
    }

    @Nested
    @DisplayName("Config Parsing")
    class ConfigParsing {

        @Test
        @DisplayName("parseEntry should extract interval correctly")
        void parseEntryValid() {
            int[] result = ScheduledCommandService.parseEntry("300:say Hello World!");
            assertThat(result).isNotNull();
            assertThat(result[0]).isEqualTo(300);
        }

        @Test
        @DisplayName("parseEntry should return null for invalid format")
        void parseEntryInvalid() {
            int[] result = ScheduledCommandService.parseEntry("invalid");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("parseEntry should return null for negative interval")
        void parseEntryNegativeInterval() {
            int[] result = ScheduledCommandService.parseEntry("-5:say Hello");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("parseEntry should return null for zero interval")
        void parseEntryZeroInterval() {
            int[] result = ScheduledCommandService.parseEntry("0:say Hello");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("parseEntry should return null for non-numeric interval")
        void parseEntryNonNumeric() {
            int[] result = ScheduledCommandService.parseEntry("abc:say Hello");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("parseEntry should handle command with colons")
        void parseEntryCommandWithColons() {
            int[] result = ScheduledCommandService.parseEntry("60:say Hello: World: Test");
            assertThat(result).isNotNull();
            assertThat(result[0]).isEqualTo(60);
        }

        @Test
        @DisplayName("parseEntry should return null when entry starts with colon")
        void parseEntryStartsWithColon() {
            int[] result = ScheduledCommandService.parseEntry(":say Hello");
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Task Lifecycle")
    class TaskLifecycle {

        @Test
        @DisplayName("startTasks should not start when disabled")
        void startTasksDisabled() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                when(mockConfig.isScheduledCommandsEnabled()).thenReturn(false);

                service.startTasks();

                bukkit.verifyNoInteractions();
            }
        }

        @Test
        @DisplayName("shutdown should clear task list")
        void shutdownClearsTasks() throws Exception {
            service.shutdown();
            assertThat(getTasks(service)).isEmpty();
        }

        @Test
        @DisplayName("reload should stop and restart")
        void reloadStopsAndRestarts() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                when(mockConfig.isScheduledCommandsEnabled()).thenReturn(false);

                service.reload();

                // After reload with disabled config, tasks should be empty
                try {
                    assertThat(getTasks(service)).isEmpty();
                } catch (Exception e) {
                    fail("Could not access tasks field: " + e.getMessage());
                }
            }
        }
    }

    @Nested
    @DisplayName("Command Entry Parsing")
    class CommandEntryParsing {

        @Test
        @DisplayName("should parse simple entry")
        void parseSimple() {
            String entry = "300:say Hello";
            int colonIndex = entry.indexOf(':');
            int interval = Integer.parseInt(entry.substring(0, colonIndex));
            String command = entry.substring(colonIndex + 1);

            assertThat(interval).isEqualTo(300);
            assertThat(command).isEqualTo("say Hello");
        }

        @Test
        @DisplayName("should handle entry with no colon")
        void parseNoColon() {
            String entry = "invalid entry";
            int colonIndex = entry.indexOf(':');
            assertThat(colonIndex).isEqualTo(-1);
        }

        @Test
        @DisplayName("should handle entry starting with colon")
        void parseStartsWithColon() {
            String entry = ":say Hello";
            int colonIndex = entry.indexOf(':');
            assertThat(colonIndex).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Command Execution")
    class CommandExecution {

        @Test
        @DisplayName("startTasks should create tasks for valid entries")
        void startTasksCreatesTasksForValidEntries() throws Exception {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                // Mock plugin manager and plugin
                PluginManager pluginManager = mock(PluginManager.class);
                Plugin plugin = mock(Plugin.class);
                bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(plugin);

                // Mock scheduler
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                BukkitTask mockTask = mock(BukkitTask.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                when(scheduler.runTaskTimer(any(Plugin.class), any(BukkitRunnable.class), anyLong(), anyLong()))
                        .thenReturn(mockTask);

                // Mock console sender
                ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
                bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

                // Configure with 2 valid entries
                when(mockConfig.isScheduledCommandsEnabled()).thenReturn(true);
                when(mockConfig.getScheduledCommands()).thenReturn(Arrays.asList(
                        "300:say Hello World",
                        "600:broadcast Server restart in 10 minutes"
                ));

                service.startTasks();

                // Verify 2 tasks were created
                List<BukkitTask> tasks = getTasks(service);
                assertThat(tasks).hasSize(2);
            }
        }

        @Test
        @DisplayName("startTasks should skip invalid entries")
        void startTasksSkipsInvalidEntries() throws Exception {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                Plugin plugin = mock(Plugin.class);
                bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(plugin);

                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                BukkitTask mockTask = mock(BukkitTask.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                when(scheduler.runTaskTimer(any(Plugin.class), any(BukkitRunnable.class), anyLong(), anyLong()))
                        .thenReturn(mockTask);

                ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
                bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

                // Mix of valid and invalid entries
                when(mockConfig.isScheduledCommandsEnabled()).thenReturn(true);
                when(mockConfig.getScheduledCommands()).thenReturn(Arrays.asList(
                        "300:say Valid Command",
                        "invalid entry",
                        "-5:say Negative",
                        "abc:say NonNumeric",
                        "600:say Another Valid"
                ));

                service.startTasks();

                // Only 2 valid tasks should be created
                List<BukkitTask> tasks = getTasks(service);
                assertThat(tasks).hasSize(2);
            }
        }

        @Test
        @DisplayName("startTasks should skip empty command after colon")
        void startTasksSkipsEmptyCommandAfterColon() throws Exception {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                Plugin plugin = mock(Plugin.class);
                bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(plugin);

                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                BukkitTask mockTask = mock(BukkitTask.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                when(scheduler.runTaskTimer(any(Plugin.class), any(BukkitRunnable.class), anyLong(), anyLong()))
                        .thenReturn(mockTask);

                ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
                bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

                when(mockConfig.isScheduledCommandsEnabled()).thenReturn(true);
                when(mockConfig.getScheduledCommands()).thenReturn(Arrays.asList(
                        "300:",  // Empty command
                        "600:   ",  // Whitespace-only command
                        "900:say Valid Command"
                ));

                service.startTasks();

                // Only 1 valid task
                List<BukkitTask> tasks = getTasks(service);
                assertThat(tasks).hasSize(1);
            }
        }

        @Test
        @DisplayName("shutdown should cancel running tasks")
        void shutdownCancelsRunningTasks() throws Exception {
            // Manually add mock tasks to the tasks list
            BukkitTask task1 = mock(BukkitTask.class);
            BukkitTask task2 = mock(BukkitTask.class);
            List<BukkitTask> tasks = getTasks(service);
            tasks.add(task1);
            tasks.add(task2);

            service.shutdown();

            verify(task1).cancel();
            verify(task2).cancel();
            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("reload should cancel old tasks and restart")
        void reloadCancelsAndRestarts() throws Exception {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                // Add existing mock tasks
                BukkitTask oldTask1 = mock(BukkitTask.class);
                BukkitTask oldTask2 = mock(BukkitTask.class);
                List<BukkitTask> tasks = getTasks(service);
                tasks.add(oldTask1);
                tasks.add(oldTask2);

                // Mock plugin manager and plugin for restart
                PluginManager pluginManager = mock(PluginManager.class);
                Plugin plugin = mock(Plugin.class);
                bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(plugin);

                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                BukkitTask newTask = mock(BukkitTask.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                when(scheduler.runTaskTimer(any(Plugin.class), any(BukkitRunnable.class), anyLong(), anyLong()))
                        .thenReturn(newTask);

                ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
                bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

                // Configure with 1 valid entry for restart
                when(mockConfig.isScheduledCommandsEnabled()).thenReturn(true);
                when(mockConfig.getScheduledCommands()).thenReturn(Collections.singletonList(
                        "300:say Reloaded Command"
                ));

                service.reload();

                // Verify old tasks were cancelled
                verify(oldTask1).cancel();
                verify(oldTask2).cancel();

                // Verify new tasks were created
                List<BukkitTask> newTasks = getTasks(service);
                assertThat(newTasks).hasSize(1);
            }
        }
    }
}
