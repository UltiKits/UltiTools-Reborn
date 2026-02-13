# Issue #36 Feature Design: Scheduled Commands & World Enhancements

**Date**: 2026-02-13
**Issue**: https://github.com/UltiKits/UltiTools-Reborn/issues/36
**Status**: Approved

## Overview

Four features requested in issue #36:

1. **Scheduled command execution** — periodically run console commands (UltiEssentials)
2. **World difficulty setting** — per-world difficulty configuration (UltiWorlds)
3. **Multi-line teleport descriptions** — display world description on teleport (UltiWorlds)
4. **Post-teleport commands** — execute commands after world teleport (UltiWorlds)

## Design Decisions

- Scheduled commands go in UltiEssentials alongside existing AnnouncementService
- Post-teleport commands are per-world only (not per-warp)
- All scheduled commands execute as console (no per-player mode)
- Config uses `List<String>` with `interval:command` format for scheduled commands (framework config doesn't support nested object lists)

---

## Feature 1: Scheduled Command Execution

**Plugin**: UltiEssentials

### Config (EssentialsConfig.java)

New fields:
```java
@ConfigEntry(path = "features.scheduled-commands.enabled", comment = "Enable scheduled command execution")
private boolean scheduledCommandsEnabled = false;

@ConfigEntry(path = "features.scheduled-commands.commands",
    comment = "Scheduled commands (format: interval_seconds:command)")
private List<String> scheduledCommands = Arrays.asList(
    "300:say Server is online!",
    "600:broadcast &cReminder: follow server rules!"
);
```

YAML output:
```yaml
features:
  scheduled-commands:
    enabled: false
    commands:
      - "300:say Server is online!"
      - "600:broadcast &cReminder: follow server rules!"
```

### Service (ScheduledCommandService.java — NEW)

```java
@Service
public class ScheduledCommandService {
    @Autowired private EssentialsConfig config;
    private Plugin bukkitPlugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @PostConstruct
    public void init() {
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        startTasks();
    }

    public void startTasks() {
        if (!config.isScheduledCommandsEnabled()) return;
        for (String entry : config.getScheduledCommands()) {
            int colonIndex = entry.indexOf(':');
            if (colonIndex <= 0) continue;
            int interval = Integer.parseInt(entry.substring(0, colonIndex));
            String command = entry.substring(colonIndex + 1);
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            }.runTaskTimer(bukkitPlugin, interval * 20L, interval * 20L);
            tasks.add(task);
        }
    }

    public void shutdown() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    public void reload() {
        shutdown();
        startTasks();
    }
}
```

### Files Changed

| File | Change |
|------|--------|
| `EssentialsConfig.java` | Add `scheduledCommandsEnabled`, `scheduledCommands` fields |
| `ScheduledCommandService.java` | **NEW** — timer + dispatch logic |

No i18n changes needed (no player-facing messages).

---

## Feature 2: World Difficulty Setting

**Plugin**: UltiWorlds

### Entity (WorldSettings.java)

New field:
```java
@Column("difficulty")
private String difficulty;  // PEACEFUL, EASY, NORMAL, HARD, or null (use Bukkit default)
```

Update `createDefault()`:
```java
.difficulty(null)  // Don't override Bukkit default
```

### Service (WorldService.java)

Apply difficulty when loading/creating worlds:
```java
// In getOrCreateSettings(), after loading settings:
if (settings.getDifficulty() != null) {
    World world = Bukkit.getWorld(worldName);
    if (world != null) {
        world.setDifficulty(Difficulty.valueOf(settings.getDifficulty()));
    }
}
```

Also apply in `loadWorld()` and `createWorld()` after world creation.

### Command

Add subcommand to world command class:
```
/world difficulty <worldName> <PEACEFUL|EASY|NORMAL|HARD>
```

### Files Changed

| File | Change |
|------|--------|
| `WorldSettings.java` | Add `difficulty` field |
| `WorldService.java` | Apply difficulty in `getOrCreateSettings()`, `loadWorld()`, `createWorld()` |
| World command class | Add `difficulty` subcommand |
| i18n files | Add `success.difficulty_set`, `error.invalid_difficulty` |

---

## Feature 3: Multi-line Teleport Description Display

**Plugin**: UltiWorlds

### Config (WorldConfig.java)

New field:
```java
@ConfigEntry(path = "tp_to_world.show_description", comment = "Show world description on teleport")
private boolean showDescriptionOnTeleport = true;
```

### Service (WorldService.java)

In `teleportToWorld()`, after successful teleport (after the existing `player.sendMessage` line):
```java
// Show description if configured
if (config.isShowDescriptionOnTeleport() && settings.getDescription() != null
        && !settings.getDescription().isEmpty()) {
    String[] lines = settings.getDescription().split("\\n");
    for (String line : lines) {
        String parsed = ChatColor.translateAlternateColorCodes('&',
            line.replace("{player}", player.getName())
                .replace("{world}", displayName));
        player.sendMessage(parsed);
    }
}
```

### Files Changed

| File | Change |
|------|--------|
| `WorldConfig.java` | Add `showDescriptionOnTeleport` field |
| `WorldService.java` | Display description after teleport |

No entity changes — `description` field already exists.

---

## Feature 4: Post-Teleport Commands

**Plugin**: UltiWorlds

### Entity (WorldSettings.java)

New field:
```java
@Column("post_teleport_commands")
private String postTeleportCommands;  // Newline-separated commands, or null
```

Update `createDefault()`:
```java
.postTeleportCommands(null)
```

### Service (WorldService.java)

In `teleportToWorld()`, after successful teleport and description display:
```java
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

### Command

Add subcommands to world command class:
```
/world post-cmd <worldName> add <command...>   — append a post-teleport command
/world post-cmd <worldName> list               — list current commands
/world post-cmd <worldName> clear              — remove all commands
```

### Files Changed

| File | Change |
|------|--------|
| `WorldSettings.java` | Add `postTeleportCommands` field |
| `WorldService.java` | Execute commands after teleport |
| World command class | Add `post-cmd` subcommands |
| i18n files | Add `success.post_cmd_added`, `success.post_cmd_cleared`, `info.post_cmd_list` |

---

## Summary of All Changes

| Plugin | File | Type |
|--------|------|------|
| UltiEssentials | `EssentialsConfig.java` | Modified |
| UltiEssentials | `ScheduledCommandService.java` | **New** |
| UltiWorlds | `WorldSettings.java` | Modified |
| UltiWorlds | `WorldConfig.java` | Modified |
| UltiWorlds | `WorldService.java` | Modified |
| UltiWorlds | World command class | Modified |
| UltiWorlds | i18n `en.json` / `zh.json` | Modified |

Estimated ~150-200 lines of new code. All changes follow existing framework patterns (`@Service`, `@Autowired`, `@PostConstruct`, `@ConfigEntry`, `@Column`, `BukkitRunnable`).

## Testing

- **ScheduledCommandService**: Unit test config parsing, task lifecycle (start/shutdown/reload)
- **WorldSettings**: Verify new fields serialize/deserialize correctly
- **WorldService.teleportToWorld()**: Test description display logic, command execution after teleport
- **WorldService difficulty**: Test difficulty application on world load/create
