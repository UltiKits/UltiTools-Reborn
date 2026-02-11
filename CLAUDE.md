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
6. **WebSocket managers** — ServerMonitor (batch_update), CommandExecution, FileOperation, LogStream
7. **bStats metrics** → token-based UltiCloud auth → WebSocket client connection (Bearer token auth, exponential backoff reconnection)

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

### WebSocket Remote Management Security

The WebSocket subsystem provides remote server management through UltiPanel with multiple security layers:

**CommandExecutionManager** — Remote command execution with:
- **Command blocklist** — `op`, `deop`, `stop`, `restart`, `reload`, `ban-ip`, `pardon-ip`, `whitelist`, `save-off`, `save-all` blocked by default
- **Namespace stripping** — `bukkit:op`, `minecraft:stop` etc. are caught by stripping the prefix before blocklist check
- **Main thread dispatch** — All commands dispatched via `Bukkit.getScheduler().runTask()` to prevent Paper's AsyncCatcher rejection

**FileOperationManager** — Remote file CRUD with:
- **Path traversal protection** — Canonical path validation ensures files stay within server root
- **File blocklist** — `server.properties`, `ops.json`, `whitelist.json`, `banned-ips.json`, `eula.txt`, etc.
- **Extension blocklist** — `.jar`, `.sh`, `.bat`, `.exe`, `.class` blocked from read/write/delete
- **Directory listing filtering** — Protected files hidden from `list` operation responses

**WebSocket client** — `UltiPanelWebSocketClient` uses Bearer token auth in headers, 60s heartbeat interval, and exponential backoff reconnection (max 5 attempts).

### UltiCloud Authentication

Token-only authentication via `ulticloud login` (magic-link flow, **console-only** — cannot be run in-game). Password-based login (`loginAccount`/`getToken`) has been removed. On startup, the framework attempts to restore a saved token; if none exists, the server runs without cloud features until the admin authenticates from the server console.

- **CloudAuthManager** — Handles magic-link login flow, token persistence, and session management
- **Rate limiting** — All UltiCloud API calls are rate-limited via `ApiRateLimiter`
- **WebSocket always connects** when authenticated (no `web-editor.enable` config gate)

### WebSocket Batch Updates

`ServerMonitorManager` sends a single `batch_update` WebSocket message every 5 seconds containing:
- `status` — server status (always included)
- `metrics` — TPS, memory, player count (always included)
- `plugins` — plugin list (every 60 seconds / 12th tick)
- `logs` — drained from `UltiPanelLogTransmitter` in external drain mode

This replaces the previous approach of separate scheduled tasks for `server_status`, `plugin_list`, and `metrics_data`.

### Paper Libraries Loader

Dependencies marked `<scope>provided</scope>` in pom.xml are auto-downloaded by Paper via `plugin.yml`'s `libraries:` section (Gson, MySQL connector, WebSocket, CGLIB, JavaMail, etc.). Spigot servers don't support this — they need shaded JARs.

### Directory Structure
```
src/main/java/com/ultikits/ultitools/
├── abstracts/      # Base classes (UltiToolsPlugin, AbstractCommandExecutor, AbstractDataEntity)
├── annotations/    # Framework annotations (@Service, @Autowired, @CmdExecutor, @Table, etc.)
├── aop/            # AOP system (CglibProxyFactory, TransactionInterceptor, ExceptionInterceptor)
├── context/        # IoC container (SimpleContainer, ComponentScanner)
├── manager/        # Core managers + WebSocket managers:
│   │               #   PluginManager, CommandManager, ListenerManager, ConfigManager,
│   │               #   CommandExecutionManager, FileOperationManager, ServerMonitorManager,
│   │               #   LogStreamManager, UltiPanelLogTransmitter
├── interfaces/     # Core interfaces (DataOperator, DataStore, VersionWrapper) + impl/
├── websocket/      # UltiPanel WebSocket client + message handler registry
│   └── handlers/   # Message handlers: Command, FileOperation, LogStream, Pong, ServerStatus
└── utils/          # Utility classes (SecurityPolicy, XVersionUtils, CloudAuthManager, ApiRateLimiter)

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

1. **Bukkit thread safety** — Use `Bukkit.getScheduler()` for async operations. Remote commands MUST be dispatched on the main thread (`runTask()`) or Paper's AsyncCatcher will reject them.
2. **Event listeners** require `@EventListener` annotation (not just Bukkit's `Listener`)
3. **Commands** with `manualRegister = true` need explicit registration via `getCommandManager().register()`
4. **i18n** — Use `i18n("key")` method for translations, language files in `lang/`
5. **Storage backends** — Configurable in `config.yml`: `json`, `sqlite` (default), `mysql`
6. **No `web-editor` or `account` config** — These sections were removed; WebSocket connects automatically when token-authenticated via `ulticloud login`
7. **AOP proxies** — `final` classes/methods can't be proxied; self-invocation bypasses AOP
8. **SecurityPolicy** — Plugin classes must pass security checks at load time; add trusted packages via `SecurityPolicy.addTrustedPackage()` if a legitimate class is blocked
9. **Paper vs Spigot** — Paper auto-downloads `libraries:` from `plugin.yml`; Spigot requires shaded dependencies
10. **Remote command blocklist bypass** — Namespace prefixes (e.g. `bukkit:op`) are stripped before blocklist check; always test with both `cmd` and `namespace:cmd` forms
11. **File operation security** — `FileOperationManager` uses canonical path checks; never construct `File` objects from user input without `getSecureFile()` validation
