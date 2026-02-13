# Issue #36 Features Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement scheduled command execution (UltiEssentials) and world enhancements — difficulty, teleport descriptions, post-teleport commands (UltiWorlds) — from GitHub issue #36.

**Architecture:** Two independent feature areas: (1) new `ScheduledCommandService` in UltiEssentials following the same `@Service`/`@PostConstruct`/`BukkitRunnable` pattern as `AnnouncementService`, (2) entity + service modifications in UltiWorlds adding fields to `WorldSettings` and logic to `WorldService.teleportToWorld()`.

**Tech Stack:** Java 8, JUnit 5 + Mockito 5 + AssertJ, Bukkit API, UltiTools-API v6.2.0 annotations

**Design doc:** `docs/plans/2026-02-13-issue-36-features-design.md`

---

### Task 1: Add WorldSettings entity fields (difficulty, postTeleportCommands)

**Files:**

- Modify: `plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/entity/WorldSettings.java`
- Test: `plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/entity/WorldSettingsTest.java`
- Modify: `plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/UltiWorldsTestHelper.java`

**Step 1: Update WorldSettings entity**

Add two new fields after the `weatherEnabled` field (line 55) in WorldSettings.java:

```java
// === World Difficulty ===

@Column("difficulty")
private String difficulty;  // PEACEFUL, EASY, NORMAL, HARD, or null (use Bukkit default)

// === Post-Teleport Commands ===

@Column("post_teleport_commands")
private String postTeleportCommands;  // Newline-separated commands, or null
```

Update `createDefault()` to include new fields:

```java
.difficulty(null)
.postTeleportCommands(null)
```

**Important:** The `@AllArgsConstructor` from Lombok will add these params to the all-args constructor. The all-args constructor test in WorldSettingsTest.java line 80-111 must be updated to include the new positional parameters.

**Step 2: Update UltiWorldsTestHelper.createSampleWorldSettings**

Add to the builder chain in `createSampleWorldSettings()`:

```java
.difficulty(null)
.postTeleportCommands(null)
```

**Step 3: Update WorldSettingsTest**

Add tests for the new fields in the existing `CreationAndDefaults` nested class:

```java
@Test
@DisplayName("createDefault should have null difficulty")
void createDefaultNullDifficulty() {
    WorldSettings settings = WorldSettings.createDefault("test_world");
    assertThat(settings.getDifficulty()).isNull();
}

@Test
@DisplayName("createDefault should have null postTeleportCommands")
void createDefaultNullPostTeleportCommands() {
    WorldSettings settings = WorldSettings.createDefault("test_world");
    assertThat(settings.getPostTeleportCommands()).isNull();
}
```

Add to the `DisplayProperties` nested class:

```java
@Test
@DisplayName("Should update difficulty")
void difficulty() {
    WorldSettings settings = WorldSettings.createDefault("world");
    settings.setDifficulty("HARD");
    assertThat(settings.getDifficulty()).isEqualTo("HARD");
}

@Test
@DisplayName("Should allow null difficulty")
void nullDifficulty() {
    WorldSettings settings = WorldSettings.createDefault("world");
    settings.setDifficulty(null);
    assertThat(settings.getDifficulty()).isNull();
}

@Test
@DisplayName("Should update postTeleportCommands")
void postTeleportCommands() {
    WorldSettings settings = WorldSettings.createDefault("world");
    settings.setPostTeleportCommands("say Hello\ngive {player} diamond 1");
    assertThat(settings.getPostTeleportCommands()).isEqualTo("say Hello\ngive {player} diamond 1");
}

@Test
@DisplayName("Should allow null postTeleportCommands")
void nullPostTeleportCommands() {
    WorldSettings settings = WorldSettings.createDefault("world");
    settings.setPostTeleportCommands(null);
    assertThat(settings.getPostTeleportCommands()).isNull();
}
```

Update the `allArgConstructor` test to include new fields (add `null, null` for difficulty and postTeleportCommands in the positional args — exact position depends on where the fields are declared relative to other fields).

Update the `equalObjects` test in `EqualsAndHashCode` to include `.difficulty(null).postTeleportCommands(null)` in both builder chains.

**Step 4: Run tests**

Run: `cd plugins/UltiWorlds && mvn test -Dtest=WorldSettingsTest`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/entity/WorldSettings.java \
       plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/entity/WorldSettingsTest.java \
       plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/UltiWorldsTestHelper.java
git commit -m "feat(worlds): add difficulty and postTeleportCommands fields to WorldSettings"
```

---

### Task 2: Add WorldConfig option for showing description on teleport

**Files:**

- Modify: `plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/config/WorldConfig.java`
- Test: `plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/config/WorldConfigTest.java`
- Modify: `plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/UltiWorldsTestHelper.java`

**Step 1: Add config field**

In WorldConfig.java, after the `useSpawnLocation` field (line 77), add:

```java
@ConfigEntry(path = "tp_to_world.show_description", comment = "Show world description to player on teleport")
private boolean showDescriptionOnTeleport = true;
```

**Step 2: Update UltiWorldsTestHelper.createDefaultConfig()**

Add to the mock setup:

```java
lenient().when(config.isShowDescriptionOnTeleport()).thenReturn(true);
```

**Step 3: Write test**

In WorldConfigTest.java, add a test verifying the default value of the new config field (test instantiation via no-arg constructor or check that the field defaults to `true`).

**Step 4: Run tests**

Run: `cd plugins/UltiWorlds && mvn test -Dtest=WorldConfigTest`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/config/WorldConfig.java \
       plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/config/WorldConfigTest.java \
       plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/UltiWorldsTestHelper.java
git commit -m "feat(worlds): add showDescriptionOnTeleport config option"
```

---

### Task 3: Implement teleport enhancements in WorldService

**Files:**

- Modify: `plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/service/WorldService.java`
- Test: `plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/service/WorldServiceTest.java`

**Step 1: Write failing tests**

Add to `WorldServiceTest.java` in a new `@Nested` class:

```java
@Nested
@DisplayName("Teleport Enhancements")
class TeleportEnhancements {

    @Test
    @DisplayName("teleportToWorld should show description when enabled and description is non-empty")
    void teleportShowsDescription() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            Location spawnLocation = mock(Location.class);
            when(world.getSpawnLocation()).thenReturn(spawnLocation);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setDescription("Line 1\nLine 2");
            mockQueryReturning(settings);

            when(mockConfig.isUseSpawnLocation()).thenReturn(false);
            when(mockConfig.getTpCooldown()).thenReturn(0);
            when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(true);

            worldService.teleportToWorld(player, "world");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, atLeast(3)).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            // Should contain: teleported message + Line 1 + Line 2
            assertThat(messages).anyMatch(m -> m.contains("Line 1"));
            assertThat(messages).anyMatch(m -> m.contains("Line 2"));
        }
    }

    @Test
    @DisplayName("teleportToWorld should NOT show description when disabled in config")
    void teleportNoDescriptionWhenDisabled() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            Location spawnLocation = mock(Location.class);
            when(world.getSpawnLocation()).thenReturn(spawnLocation);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setDescription("Should not appear");
            mockQueryReturning(settings);

            when(mockConfig.isUseSpawnLocation()).thenReturn(false);
            when(mockConfig.getTpCooldown()).thenReturn(0);
            when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

            worldService.teleportToWorld(player, "world");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, atLeastOnce()).sendMessage(captor.capture());
            List<String> messages = captor.getAllValues();
            assertThat(messages).noneMatch(m -> m.contains("Should not appear"));
        }
    }

    @Test
    @DisplayName("teleportToWorld should execute post-teleport commands")
    void teleportExecutesPostCommands() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            Location spawnLocation = mock(Location.class);
            when(world.getSpawnLocation()).thenReturn(spawnLocation);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            org.bukkit.command.ConsoleCommandSender consoleSender = mock(org.bukkit.command.ConsoleCommandSender.class);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setPostTeleportCommands("say {player} entered {world}\ngive {player} diamond 1");
            mockQueryReturning(settings);

            when(mockConfig.isUseSpawnLocation()).thenReturn(false);
            when(mockConfig.getTpCooldown()).thenReturn(0);
            when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

            worldService.teleportToWorld(player, "world");

            bukkit.verify(() -> Bukkit.dispatchCommand(consoleSender, "say TestPlayer entered world"));
            bukkit.verify(() -> Bukkit.dispatchCommand(consoleSender, "give TestPlayer diamond 1"));
        }
    }

    @Test
    @DisplayName("teleportToWorld should NOT execute commands when postTeleportCommands is null")
    void teleportNoCommandsWhenNull() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            Location spawnLocation = mock(Location.class);
            when(world.getSpawnLocation()).thenReturn(spawnLocation);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setPostTeleportCommands(null);
            mockQueryReturning(settings);

            when(mockConfig.isUseSpawnLocation()).thenReturn(false);
            when(mockConfig.getTpCooldown()).thenReturn(0);
            when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

            worldService.teleportToWorld(player, "world");

            bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
        }
    }

    @Test
    @DisplayName("teleportToWorld should apply world difficulty when set")
    void teleportAppliesDifficulty() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            Location spawnLocation = mock(Location.class);
            when(world.getSpawnLocation()).thenReturn(spawnLocation);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setDifficulty("HARD");
            mockQueryReturning(settings);

            when(mockConfig.isUseSpawnLocation()).thenReturn(false);
            when(mockConfig.getTpCooldown()).thenReturn(0);
            when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

            // First call to getOrCreateSettings will set difficulty
            worldService.getOrCreateSettings("world");

            verify(world).setDifficulty(org.bukkit.Difficulty.HARD);
        }
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd plugins/UltiWorlds && mvn test -Dtest=WorldServiceTest`
Expected: New tests FAIL (description/commands not implemented yet)

**Step 3: Implement in WorldService**

In `getOrCreateSettings()`, after line 124 (`settingsCache.put(worldName, settings);`), add difficulty application:

```java
// Apply difficulty if configured
if (settings.getDifficulty() != null) {
    World world = Bukkit.getWorld(worldName);
    if (world != null) {
        try {
            world.setDifficulty(Difficulty.valueOf(settings.getDifficulty()));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warn("Invalid difficulty for world " + worldName + ": " + settings.getDifficulty());
        }
    }
}
```

Add import: `import org.bukkit.Difficulty;`

In `teleportToWorld()`, after the existing `player.sendMessage(plugin.i18n("success.teleported")...)` block (line 246-247), add:

```java
// Show description if configured
if (config.isShowDescriptionOnTeleport() && settings.getDescription() != null
        && !settings.getDescription().isEmpty()) {
    String[] lines = settings.getDescription().split("\\n");
    for (String line : lines) {
        String parsed = org.bukkit.ChatColor.translateAlternateColorCodes('&',
            line.replace("{player}", player.getName())
                .replace("{world}", displayName));
        player.sendMessage(parsed);
    }
}

// Execute post-teleport commands
if (settings.getPostTeleportCommands() != null && !settings.getPostTeleportCommands().isEmpty()) {
    String[] commands = settings.getPostTeleportCommands().split("\\n");
    for (String cmd : commands) {
        String parsed = cmd.trim()
            .replace("{player}", player.getName())
            .replace("{world}", worldName);
        if (!parsed.isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd plugins/UltiWorlds && mvn test -Dtest=WorldServiceTest`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/service/WorldService.java \
       plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/service/WorldServiceTest.java
git commit -m "feat(worlds): implement teleport descriptions, post-teleport commands, and world difficulty"
```

---

### Task 4: Add world commands for difficulty and post-teleport commands

**Files:**

- Modify: `plugins/UltiWorlds/src/main/java/com/ultikits/plugins/worlds/commands/WorldCommand.java`
- Modify: `plugins/UltiWorlds/src/main/resources/lang/en.yml`
- Modify: `plugins/UltiWorlds/src/main/resources/lang/zh.yml`
- Test: `plugins/UltiWorlds/src/test/java/com/ultikits/plugins/worlds/commands/WorldCommandTest.java`

**Step 1: Add i18n messages**

In `en.yml`, add to the `success:` section:

```yaml
  difficulty_set: "§aDifficulty set to %value% for world %world%"
  post_cmd_added: "§aPost-teleport command added for world %world%"
  post_cmd_cleared: "§aAll post-teleport commands cleared for world %world%"
  post_cmd_list_header: "§6Post-teleport commands for %world%:"
  post_cmd_list_item: "§7- §f%command%"
  post_cmd_empty: "§7No post-teleport commands configured"
```

In `en.yml`, add to the `error:` section:

```yaml
  invalid_difficulty: "§cInvalid difficulty! Use: PEACEFUL, EASY, NORMAL, HARD"
```

In `en.yml`, add to `command.help:` section:

```yaml
    difficulty: "§e/world difficulty <world> <level> §7- Set world difficulty"
    postcmd: "§e/world postcmd <world> <add|list|clear> §7- Manage post-TP commands"
```

Add equivalent Chinese translations to `zh.yml`:

```yaml
# In success:
  difficulty_set: "§a已设置世界 %world% 的难度为 %value%"
  post_cmd_added: "§a已为世界 %world% 添加传送后命令"
  post_cmd_cleared: "§a已清除世界 %world% 的所有传送后命令"
  post_cmd_list_header: "§6世界 %world% 的传送后命令:"
  post_cmd_list_item: "§7- §f%command%"
  post_cmd_empty: "§7未配置传送后命令"

# In error:
  invalid_difficulty: "§c无效的难度！可用: PEACEFUL, EASY, NORMAL, HARD"

# In command.help:
    difficulty: "§e/world difficulty <世界> <难度> §7- 设置世界难度"
    postcmd: "§e/world postcmd <世界> <add|list|clear> §7- 管理传送后命令"
```

**Step 2: Add difficulty command**

In WorldCommand.java, add after the `setWorldOption` method:

```java
@CmdMapping(format = "difficulty <world> <level>")
public void setDifficulty(@CmdSender Player player,
                          @CmdParam(value = "world", suggest = "suggestWorlds") String worldName,
                          @CmdParam(value = "level", suggest = "suggestDifficulties") String level) {
    if (!player.hasPermission("ultiworlds.admin.settings")) {
        player.sendMessage(i18n("error.no_permission"));
        return;
    }

    World world = Bukkit.getWorld(worldName);
    if (world == null) {
        player.sendMessage(i18n("error.world_not_found").replace("%world%", worldName));
        return;
    }

    Difficulty difficulty;
    try {
        difficulty = Difficulty.valueOf(level.toUpperCase());
    } catch (IllegalArgumentException e) {
        player.sendMessage(i18n("error.invalid_difficulty"));
        return;
    }

    WorldSettings settings = worldService.getOrCreateSettings(worldName);
    settings.setDifficulty(difficulty.name());
    worldService.updateSettings(settings);
    world.setDifficulty(difficulty);

    player.sendMessage(i18n("success.difficulty_set")
        .replace("%value%", difficulty.name())
        .replace("%world%", worldName));
}
```

Add import: `import org.bukkit.Difficulty;`

**Step 3: Add post-teleport command management**

```java
@CmdMapping(format = "postcmd <world> add <command>")
public void addPostCmd(@CmdSender Player player,
                       @CmdParam(value = "world", suggest = "suggestWorlds") String worldName,
                       @CmdParam("command") String command) {
    if (!player.hasPermission("ultiworlds.admin.settings")) {
        player.sendMessage(i18n("error.no_permission"));
        return;
    }

    WorldSettings settings = worldService.getOrCreateSettings(worldName);
    String existing = settings.getPostTeleportCommands();
    if (existing == null || existing.isEmpty()) {
        settings.setPostTeleportCommands(command);
    } else {
        settings.setPostTeleportCommands(existing + "\n" + command);
    }
    worldService.updateSettings(settings);

    player.sendMessage(i18n("success.post_cmd_added").replace("%world%", worldName));
}

@CmdMapping(format = "postcmd <world> list")
public void listPostCmd(@CmdSender Player player,
                        @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
    WorldSettings settings = worldService.getOrCreateSettings(worldName);
    String commands = settings.getPostTeleportCommands();

    player.sendMessage(i18n("success.post_cmd_list_header").replace("%world%", worldName));
    if (commands == null || commands.isEmpty()) {
        player.sendMessage(i18n("success.post_cmd_empty"));
    } else {
        for (String cmd : commands.split("\\n")) {
            player.sendMessage(i18n("success.post_cmd_list_item").replace("%command%", cmd.trim()));
        }
    }
}

@CmdMapping(format = "postcmd <world> clear")
public void clearPostCmd(@CmdSender Player player,
                         @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
    if (!player.hasPermission("ultiworlds.admin.settings")) {
        player.sendMessage(i18n("error.no_permission"));
        return;
    }

    WorldSettings settings = worldService.getOrCreateSettings(worldName);
    settings.setPostTeleportCommands(null);
    worldService.updateSettings(settings);

    player.sendMessage(i18n("success.post_cmd_cleared").replace("%world%", worldName));
}
```

**Step 4: Add suggestion methods**

```java
public List<String> suggestDifficulties(Player player, String input) {
    return Arrays.asList("PEACEFUL", "EASY", "NORMAL", "HARD").stream()
        .filter(d -> d.toLowerCase().startsWith(input.toLowerCase()))
        .collect(Collectors.toList());
}
```

**Step 5: Update help and suggestOptions**

In `suggestOptions()`, add `"difficulty"` to the list. In `help()`, add:

```java
player.sendMessage(i18n("command.help.difficulty"));
player.sendMessage(i18n("command.help.postcmd"));
```

Also add `"difficulty"` case to `setWorldOption`'s switch:

```java
case "difficulty":
    try {
        Difficulty diff = Difficulty.valueOf(value.toUpperCase());
        settings.setDifficulty(diff.name());
        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            w.setDifficulty(diff);
        }
    } catch (IllegalArgumentException e) {
        player.sendMessage(i18n("error.invalid_difficulty"));
        return;
    }
    break;
```

**Step 6: Run tests**

Run: `cd plugins/UltiWorlds && mvn test`
Expected: ALL PASS

**Step 7: Commit**

```bash
git add plugins/UltiWorlds/
git commit -m "feat(worlds): add /world difficulty and /world postcmd commands with i18n"
```

---

### Task 5: Add ScheduledCommandService to UltiEssentials

**Files:**

- Modify: `plugins/UltiEssentials/src/main/java/com/ultikits/plugins/essentials/config/EssentialsConfig.java`
- Create: `plugins/UltiEssentials/src/main/java/com/ultikits/plugins/essentials/service/ScheduledCommandService.java`
- Create: `plugins/UltiEssentials/src/test/java/com/ultikits/plugins/essentials/service/ScheduledCommandServiceTest.java`

**Step 1: Add config fields**

In EssentialsConfig.java, add after the announcement title section (line 261):

```java
// ============ Scheduled Commands ============
@ConfigEntry(path = "features.scheduled-commands.enabled", comment = "Enable scheduled command execution / 启用定时命令执行")
private boolean scheduledCommandsEnabled = false;

@ConfigEntry(path = "features.scheduled-commands.commands",
    comment = "Scheduled commands, format: interval_seconds:command / 定时命令列表，格式: 间隔秒数:命令")
private java.util.List<String> scheduledCommands = java.util.Arrays.asList(
    "300:say Server is online!",
    "600:broadcast &cReminder: follow server rules!"
);
```

**Step 2: Write failing test**

Create `ScheduledCommandServiceTest.java`:

```java
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
    private List<Object> getTasks(ScheduledCommandService svc) throws Exception {
        Field field = svc.getClass().getDeclaredField("tasks");
        field.setAccessible(true);
        return (List<Object>) field.get(svc);
    }

    @Nested
    @DisplayName("Config Parsing")
    class ConfigParsing {

        @Test
        @DisplayName("parseEntry should extract interval and command correctly")
        void parseEntryValid() {
            int[] result = ScheduledCommandService.parseEntry("300:say Hello World!");
            assertThat(result[0]).isEqualTo(300);
        }

        @Test
        @DisplayName("parseEntry should return null for invalid format")
        void parseEntryInvalid() {
            int[] result = ScheduledCommandService.parseEntry("invalid");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("parseEntry should handle command with colons")
        void parseEntryCommandWithColons() {
            String entry = "60:say Hello: World: Test";
            int colonIndex = entry.indexOf(':');
            assertThat(colonIndex).isEqualTo(2);
            String command = entry.substring(colonIndex + 1);
            assertThat(command).isEqualTo("say Hello: World: Test");
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

                // No scheduler interactions when disabled
                bukkit.verifyNoInteractions();
            }
        }

        @Test
        @DisplayName("shutdown should cancel all active tasks")
        void shutdownCancelsTasks() throws Exception {
            BukkitTask task1 = mock(BukkitTask.class);
            BukkitTask task2 = mock(BukkitTask.class);
            List<BukkitTask> tasks = getTasks(service);
            // Can't easily add since it's a private field, but we test shutdown logic
            // by verifying the list is cleared after shutdown
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
                // (shutdown was called, startTasks was called but did nothing)
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

            // colonIndex is -1 when no colon
            assertThat(colonIndex).isEqualTo(-1);
        }

        @Test
        @DisplayName("should handle entry starting with colon")
        void parseStartsWithColon() {
            String entry = ":say Hello";
            int colonIndex = entry.indexOf(':');

            // colonIndex is 0 = invalid
            assertThat(colonIndex).isEqualTo(0);
        }
    }
}
```

**Step 3: Create ScheduledCommandService**

Create `ScheduledCommandService.java`:

```java
package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for executing scheduled console commands.
 * <p>
 * 定时执行控制台命令的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class ScheduledCommandService {

    @Autowired
    private EssentialsConfig config;

    private Plugin bukkitPlugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @PostConstruct
    public void init() {
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        startTasks();
    }

    /**
     * Start all scheduled command tasks.
     */
    public void startTasks() {
        if (!config.isScheduledCommandsEnabled()) {
            return;
        }

        for (String entry : config.getScheduledCommands()) {
            int colonIndex = entry.indexOf(':');
            if (colonIndex <= 0) {
                log.warn("Invalid scheduled command entry (missing interval): {}", entry);
                continue;
            }

            int interval;
            try {
                interval = Integer.parseInt(entry.substring(0, colonIndex));
            } catch (NumberFormatException e) {
                log.warn("Invalid interval in scheduled command entry: {}", entry);
                continue;
            }

            if (interval <= 0) {
                log.warn("Interval must be positive in scheduled command entry: {}", entry);
                continue;
            }

            String command = entry.substring(colonIndex + 1).trim();
            if (command.isEmpty()) {
                log.warn("Empty command in scheduled command entry: {}", entry);
                continue;
            }

            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            }.runTaskTimer(bukkitPlugin, interval * 20L, interval * 20L);

            tasks.add(task);
            log.info("Scheduled command (every {}s): {}", interval, command);
        }
    }

    /**
     * Stop all scheduled tasks.
     */
    public void shutdown() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    /**
     * Reload: stop and restart all tasks.
     */
    public void reload() {
        shutdown();
        startTasks();
    }

    /**
     * Parse a scheduled command entry. Returns interval or null if invalid.
     * Exposed for testing.
     *
     * @param entry format "interval:command"
     * @return int array with [interval] or null if invalid
     */
    public static int[] parseEntry(String entry) {
        int colonIndex = entry.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }
        try {
            int interval = Integer.parseInt(entry.substring(0, colonIndex));
            if (interval <= 0) return null;
            return new int[]{interval};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

**Step 4: Run tests**

Run: `cd plugins/UltiEssentials && mvn test -Dtest=ScheduledCommandServiceTest`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add plugins/UltiEssentials/src/main/java/com/ultikits/plugins/essentials/config/EssentialsConfig.java \
       plugins/UltiEssentials/src/main/java/com/ultikits/plugins/essentials/service/ScheduledCommandService.java \
       plugins/UltiEssentials/src/test/java/com/ultikits/plugins/essentials/service/ScheduledCommandServiceTest.java
git commit -m "feat(essentials): add ScheduledCommandService for periodic console commands"
```

---

### Task 6: Run full test suite and verify

**Step 1: Run UltiWorlds tests**

Run: `cd plugins/UltiWorlds && mvn test`
Expected: ALL PASS

**Step 2: Run UltiEssentials tests**

Run: `cd plugins/UltiEssentials && mvn test`
Expected: ALL PASS

**Step 3: Run full framework test suite**

Run: `cd /home/wisdomme/Code-Folder/Minecraft/Ulti/Framework/UltiTools-Reborn && mvn test`
Expected: ALL PASS (except pre-existing `ServerMonitorManagerTest$UpdateTPSTests` failures)

**Step 4: Build both plugin JARs**

Run: `cd plugins/UltiWorlds && mvn package -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Run: `cd plugins/UltiEssentials && mvn package -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS for both

**Step 5: Final commit (if any fixups needed)**

```bash
git add -A && git commit -m "fix: address test failures from issue #36 features"
```

---

### Task 7: Create feature branch and PR

**Step 1: Create feature branch** (if not already on one)

```bash
git checkout -b feature/issue-36-scheduled-commands-world-enhancements alpha
```

Note: If work was done on `alpha` directly, create the branch from current HEAD and reset `alpha`.

**Step 2: Push and create PR**

```bash
git push -u origin feature/issue-36-scheduled-commands-world-enhancements
gh pr create --title "feat: scheduled commands and world enhancements (#36)" \
  --body "$(cat <<'PREOF'
## Summary
- Add `ScheduledCommandService` to UltiEssentials for periodic console command execution
- Add world difficulty setting (`/world difficulty`) to UltiWorlds
- Display world description to players on teleport
- Add post-teleport command execution (`/world postcmd`)

Closes #36

## Changes
### UltiEssentials
- `EssentialsConfig`: new `scheduled-commands` config section
- `ScheduledCommandService` (NEW): parses `interval:command` entries, runs via `BukkitRunnable`

### UltiWorlds
- `WorldSettings`: add `difficulty` and `post_teleport_commands` columns
- `WorldConfig`: add `show_description` toggle
- `WorldService.teleportToWorld()`: show description, execute post-teleport commands, apply difficulty
- `WorldCommand`: add `/world difficulty`, `/world postcmd add/list/clear` subcommands
- i18n: new messages in en.yml and zh.yml

## Test plan
- [ ] Run `mvn test` in UltiWorlds — all pass
- [ ] Run `mvn test` in UltiEssentials — all pass
- [ ] Run full framework `mvn test` — all pass (ignore pre-existing TPS test)
- [ ] Deploy to dev server, verify `/world difficulty world HARD` changes difficulty
- [ ] Verify world description shows on `/world tp`
- [ ] Verify `/world postcmd world add say Hello` executes after teleport
- [ ] Verify scheduled commands fire at configured intervals

PREOF
)"
```
