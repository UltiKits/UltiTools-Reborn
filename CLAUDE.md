# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

UltiTools-API is a Minecraft Spigot plugin framework (Java 8) providing annotation-driven development for Bukkit plugins. It features a custom Spring-like IoC container, AOP with CGLIB proxies, ORM for data persistence, and WebSocket integration with UltiPanel for remote server management. Supports Minecraft 1.8-1.21.

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

### Bootstrap Sequence

`UltiTools.java` initializes in this order (critical for debugging startup issues):

1. **DependenceManagers** — classloader setup for external libs
2. **Language files** — i18n from `lang/*.json`
3. **XVersionUtils** — cross-version compatibility via XSeries (replaced old VersionWrapper)
4. **ConfigManager** → **DataStore** (MySQL/SQLite/JSON based on `config.yml`)
5. **PluginManager** — scans `plugins/` JARs, resolves dependencies via `PluginDependencyResolver` (Kahn's topological sort using `@PluginDependency`), then for each plugin:
   - Creates `SimpleContainer` with parent context (core UltiTools context)
   - Component scanning → bean creation → `@Autowired` injection → `@PostConstruct`
   - Auto-registers `@CmdExecutor` commands and `@EventListener` listeners
   - Calls `plugin.registerSelf()`
6. **WebSocket managers** — ServerMonitor, CommandExecution, FileOperation, LogStream
7. **bStats metrics** → account login → WebSocket client connection

Shutdown reverses this: `unregisterSelf()` → `@PreDestroy` → close contexts → close DataStore.

### Core Components

- **UltiTools.java** — Main Bukkit plugin entry, initializes all managers
- **UltiToolsPlugin** — Base class for plugin modules (`abstracts/UltiToolsPlugin.java`)
- **SimpleContainer** — Spring-like IoC container (`context/SimpleContainer.java`) with three-level cache for circular dependency resolution
- **DataOperator/DataStore** — Storage abstraction for MySQL, SQLite, JSON

### IoC Container Details

`SimpleContainer` implements a Spring-inspired DI system:
- **Three-level cache** (added 6.2.0): resolves circular dependencies between `@Autowired` beans
- **Parent-child hierarchy**: each plugin gets its own container with core UltiTools container as parent
- **BeanPostProcessor**: `AopProxyBeanPostProcessor` wraps beans with CGLIB proxies when AOP annotations are detected
- **Lifecycle hooks**: `@PostConstruct` (after injection) and `@PreDestroy` (on shutdown)
- Beans retrieved via `getContext().getBean(MyService.class)`

### AOP System

Uses CGLIB to create runtime proxies (no JDK dynamic proxies). Located in `aop/` package.

**Key annotations:**
- `@Transactional` — declarative transaction management with 7 propagation types (REQUIRED, REQUIRES_NEW, SUPPORTS, etc.) and 5 isolation levels
- `@ExceptionCatch` — catches specified exceptions with options for silent mode, default values, and custom handlers

**How it works:** `AopProxyBeanPostProcessor` intercepts bean initialization. If a bean has AOP annotations, a CGLIB subclass proxy is created. Interceptors execute in priority order: Transaction → Exception → Target Method.

**Limitations:** Cannot proxy `final` classes/methods. Self-invocation (`this.method()`) bypasses the proxy.

### Security Model

Multi-layer plugin sandboxing via `SecurityPolicy.java` — enforced at classloading time before any plugin code executes:

1. **Dangerous class blacklist** — blocks `ProcessBuilder`, `Runtime`, `Unsafe`, `ScriptEngine`, raw `FileOutputStream`, `Socket`, etc.
2. **Dangerous package prefixes** — blocks `java.lang.reflect`, `sun.misc`, `jdk.internal`, `javax.script`, etc.
3. **Trusted package whitelist** — allows `com.ultikits.ultitools`, `org.bukkit`, `net.md_5.bungee`, `io.papermc.paper`
4. **JAR validation** — max 100MB file size, max 10,000 entries (Zip Bomb prevention), max 1,000 classes scanned per JAR

Add trusted packages at runtime: `SecurityPolicy.addTrustedPackage("com.myplugin")`

### Paper Libraries Loader

Dependencies marked `<scope>provided</scope>` in pom.xml are auto-downloaded by Paper via `plugin.yml`'s `libraries:` section (Gson, MySQL connector, WebSocket, CGLIB, JavaMail, etc.). Spigot servers don't support this — they need shaded JARs.

### Directory Structure
```
src/main/java/com/ultikits/ultitools/
├── abstracts/      # Base classes (UltiToolsPlugin, AbstractCommandExecutor, AbstractDataEntity)
├── annotations/    # Framework annotations (@Service, @Autowired, @CmdExecutor, @Table, etc.)
├── aop/            # AOP system (CglibProxyFactory, TransactionInterceptor, ExceptionInterceptor)
├── context/        # IoC container (SimpleContainer, ComponentScanner)
├── manager/        # Core managers (PluginManager, CommandManager, ListenerManager, ConfigManager)
├── interfaces/     # Core interfaces (DataOperator, DataStore, VersionWrapper) + impl/
├── websocket/      # UltiPanel WebSocket client for remote management
└── utils/          # Utility classes (SecurityPolicy, XVersionUtils, ClassLoaderUtils)

docs/wiki/          # Complete project documentation (primarily Chinese with English technical terms)
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
```

### AOP Usage
```java
@Service
public class MyService {
    @Transactional(propagation = Propagation.REQUIRED)
    public void transferMoney(UUID from, UUID to, double amount) { /* ... */ }

    @ExceptionCatch(value = IOException.class, silent = true)
    public String readConfig() { /* ... */ }
}
```

## Testing

- **Framework**: JUnit 5 with `@DisplayName` annotations
- **Mocking**: Mockito 5.5.0 + MockBukkit-v1.19 for Bukkit API
- **Assertions**: AssertJ (fluent: `assertThat(x).isEqualTo(y)`)
- **Database**: H2 in-memory for integration tests
- **Structure**: Tests mirror source in `src/test/java/`, each test class is independent (no shared base class)
- Tests tagged with `@Tag("isolated")` are excluded by default — these modify global state (e.g., SecurityPolicy)
- `@Nested` classes used to group related tests within a file

## Code Style & Constraints

- **Java 8 compatibility** — No `var`, no records, use diamond operator carefully
- **Lombok** for boilerplate (`@Data`, `@Getter`, `@Setter`, `@Builder`)
- Snake_case for SQL columns, camelCase for Java
- Bilingual comments (English + Chinese)

## Common Gotchas

1. **Bukkit thread safety** — Use `Bukkit.getScheduler()` for async operations
2. **Event listeners** require `@EventListener` annotation (not just Bukkit's `Listener`)
3. **Commands** with `manualRegister = true` need explicit registration via `getCommandManager().register()`
4. **i18n** — Use `i18n("key")` method for translations, language files in `lang/`
5. **Storage backends** — Configurable in `config.yml`: `json`, `sqlite` (default), `mysql`
6. **AOP proxies** — `final` classes/methods can't be proxied; self-invocation bypasses AOP
7. **SecurityPolicy** — Plugin classes must pass security checks at load time; add trusted packages via `SecurityPolicy.addTrustedPackage()` if a legitimate class is blocked
8. **Paper vs Spigot** — Paper auto-downloads `libraries:` from `plugin.yml`; Spigot requires shaded dependencies
