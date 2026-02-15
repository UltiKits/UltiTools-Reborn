# UltiTools-API Copilot Instructions

## Project Overview

UltiTools-API is a **Minecraft Spigot plugin framework** (Java 8) that provides annotation-driven development for Bukkit plugins. It features a custom Spring-like IoC container, ORM for data persistence, and WebSocket integration with UltiPanel for remote management.

## Architecture

### Core Components

- **UltiTools.java** - Main plugin entry point, manages lifecycle and initializes managers
- **UltiToolsPlugin** - Base class for plugin modules (extends `abstracts/UltiToolsPlugin.java`)
- **SimpleContainer** - Custom IoC container (`context/SimpleContainer.java`) replacing Spring
- **DataOperator/DataStore** - Abstraction for MySQL, SQLite, and JSON storage

### Key Directories

```
src/main/java/com/ultikits/ultitools/
├── abstracts/      # Base classes (UltiToolsPlugin, AbstractCommandExecutor, AbstractDataEntity)
├── annotations/    # Framework annotations (@Service, @Autowired, @CmdExecutor, @Table, etc.)
├── context/        # IoC container implementation
├── manager/        # Core managers (PluginManager, CommandManager, ListenerManager)
├── websocket/      # UltiPanel WebSocket client for remote management
└── interfaces/     # Core interfaces (DataOperator, DataStore, VersionWrapper)
```

## Annotation Patterns

### Command Registration
Commands use `@CmdExecutor` + `@CmdMapping` pattern (not Bukkit's default):
```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"mycmd"}, permission = "myplugin.cmd")
public class MyCommand extends AbstractCommandExecutor {
    
    @CmdMapping(format = "action <param>")  // Maps to "/mycmd action <param>"
    public void doAction(@CmdSender Player player, @CmdParam("param") String param) {
        // handler code
    }
}
```

### Bean/Service Registration  
Use `@Service` or `@Component` (similar to Spring):
```java
@Service
public class MyService {
    @Autowired
    private AnotherService dependency;  // Auto-injected by SimpleContainer
}
```

### Data Entities

Use `@Table` and `@Column` for ORM mapping:
```java
@Table("my_data")
public class MyEntity extends AbstractDataEntity {
    @Column("name")
    private String name;
    @Column(value = "value", type = "FLOAT")
    private double value;
}
```

### Configuration
Use `@ConfigEntity` and `@ConfigEntry`:
```java
@ConfigEntity(path = "config/myconfig.yml")
public class MyConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "setting", comment = "Description")
    private boolean setting = true;
}
```

### Plugin Module Setup

Use `@UltiToolsModule` on main class (enables auto-registration):
```java
@UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() { return true; }
}
```

## Build & Test

```bash
mvn clean package              # Build JAR to target/
mvn test                       # Run JUnit 5 tests
mvn javadoc:javadoc            # Generate API docs to target/apidocs/
```

- Dependencies are copied to `target/UltiTools/lib/` on package
- Target compatibility: **Java 8** (source/target 1.8)
- Uses Lombok for boilerplate reduction

## Data Storage

Three storage backends, configured in `config.yml`:

- `json` - File-based, human-editable, lowest reliability
- `sqlite` - Local file, good performance (default)
- `mysql` - Requires external DB, highest reliability

Access data via `DataOperator`:
```java
DataOperator<MyEntity> op = plugin.getDataOperator(MyEntity.class);
op.insert(entity);
List<MyEntity> all = op.getAll(WhereCondition.builder().column("name").value("test").build());
```

## WebSocket Integration (UltiPanel)

Managers in `manager/` handle WebSocket message types:

- `ServerMonitorManager` - Server status, TPS, memory
- `CommandExecutionManager` - Remote command execution
- `FileOperationManager` - Remote file operations
- `LogStreamManager` - Real-time log streaming

## Testing Conventions

- Tests use **JUnit 5** with `@DisplayName` annotations
- Mockito for mocking Bukkit APIs
- Test classes mirror source structure in `src/test/java/`
- Container tests demonstrate IoC patterns (`context/*Test.java`)

## Common Gotchas

1. **Java 8 compatibility** - No var, no records, use diamond operator carefully
2. **Bukkit thread safety** - Use `Bukkit.getScheduler()` for async operations
3. **Event listeners** require `@EventListener` annotation (not just Bukkit's `Listener`)
4. **Commands** with `manualRegister = true` need explicit registration
5. **i18n** - Use `i18n("key")` method for translations, language files in `lang/`
