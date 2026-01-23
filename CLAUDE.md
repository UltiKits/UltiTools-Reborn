# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

UltiTools-API is a Minecraft Spigot plugin framework (Java 8) providing annotation-driven development for Bukkit plugins. It features a custom Spring-like IoC container, ORM for data persistence, and WebSocket integration with UltiPanel for remote server management. Supports Minecraft 1.8-1.21.

## Build Commands

```bash
mvn clean package              # Build shaded JAR → target/UltiTools-API-*.jar
mvn test                       # Run JUnit 5 tests (excludes @Tag("isolated") by default)
mvn test -Dtest=ClassName      # Run a single test class
mvn test -DexcludedGroups=     # Run all tests including isolated ones
mvn javadoc:javadoc            # Generate API docs → target/apidocs/
```

Coverage reports are generated automatically during `mvn test` → `target/site/jacoco/index.html`

## Architecture

### Core Components
- **UltiTools.java** - Main Bukkit plugin entry, initializes all managers
- **UltiToolsPlugin** - Base class for plugin modules (`abstracts/UltiToolsPlugin.java`)
- **SimpleContainer** - Spring-like IoC container (`context/SimpleContainer.java`)
- **DataOperator/DataStore** - Storage abstraction for MySQL, SQLite, JSON

### Directory Structure
```
src/main/java/com/ultikits/ultitools/
├── abstracts/      # Base classes (UltiToolsPlugin, AbstractCommandExecutor, AbstractDataEntity)
├── annotations/    # Framework annotations (@Service, @Autowired, @CmdExecutor, @Table, etc.)
├── context/        # IoC container (SimpleContainer)
├── manager/        # Core managers (PluginManager, CommandManager, ListenerManager, ConfigManager)
├── interfaces/     # Core interfaces (DataOperator, DataStore, VersionWrapper) + impl/
├── websocket/      # UltiPanel WebSocket client for remote management
└── utils/          # Utility classes

plugins/            # 11 plugin modules (UltiEssentials, UltiWorlds, UltiTrade, etc.)
docs/wiki/          # Complete project documentation
```

## Key Patterns

### Plugin Module Setup
```java
@UltiToolsModule(scanBasePackages = {"com.example.plugin"})
public class MyPlugin extends UltiToolsPlugin {
    @Override public boolean registerSelf() { return true; }
    @Override public void unregisterSelf() { }
}
```

### Command System
```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"mycmd"}, permission = "myplugin.cmd")
public class MyCommand extends AbstractCommandExecutor {
    @CmdMapping(format = "action <param>")
    public void doAction(@CmdSender Player player, @CmdParam("param") String param) { }
}
```

### Dependency Injection
```java
@Service
public class MyService {
    @Autowired
    private AnotherService dependency;  // Auto-injected
}

// Or manual retrieval:
MyService service = getContext().getBean(MyService.class);
```

### Data Entities (ORM)
```java
@Table("my_data")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MyEntity extends AbstractDataEntity {
    @Column("name") private String name;
    @Column(value = "balance", type = "FLOAT") private double balance;
}

// Usage:
DataOperator<MyEntity> op = plugin.getDataOperator(MyEntity.class);
op.insert(entity);
List<MyEntity> results = op.getAll(WhereCondition.builder().column("name").value("test").build());
```

### Configuration Objects
```java
@Getter @Setter
@ConfigEntity(path = "config/myconfig.yml")
public class MyConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "setting", comment = "Description")
    private boolean enabled = true;
}

// Usage:
MyConfig config = plugin.getConfig(MyConfig.class);
```

## Testing

- **Framework**: JUnit 5 with `@DisplayName` annotations
- **Mocking**: Mockito 5.5.0 + MockBukkit-v1.19 for Bukkit API
- **Assertions**: AssertJ
- **Database**: H2 in-memory for integration tests
- Tests tagged with `@Tag("isolated")` are excluded by default (run separately to avoid conflicts)

## Code Style & Constraints

- **Java 8 compatibility** - No `var`, no records, use diamond operator carefully
- **Lombok** for boilerplate (`@Data`, `@Getter`, `@Setter`, `@Builder`)
- Snake_case for SQL columns, camelCase for Java
- Bilingual comments (English + Chinese)

## Common Gotchas

1. **Bukkit thread safety** - Use `Bukkit.getScheduler()` for async operations
2. **Event listeners** require `@EventListener` annotation (not just Bukkit's `Listener`)
3. **Commands** with `manualRegister = true` need explicit registration via `getCommandManager().register()`
4. **i18n** - Use `i18n("key")` method for translations, language files in `lang/`
5. **Storage backends** - Configurable in `config.yml`: `json`, `sqlite` (default), `mysql`
