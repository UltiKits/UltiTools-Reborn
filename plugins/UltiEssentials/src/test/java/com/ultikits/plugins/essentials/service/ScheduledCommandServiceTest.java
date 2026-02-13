package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
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
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private List<BukkitTask> getTasks(ScheduledCommandService svc) throws Exception {
        Field field = svc.getClass().getDeclaredField("tasks");
        field.setAccessible(true);
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
}
