<br>
<br>

<div align="center">
<img src="https://github.com/UltiKits/UltiTools-Reborn/assets/62180110/f5e8e7d3-e97d-4d37-a9ab-ba3722dc6faa" width="96" height="96"/>
</div>


<h1 align="center">UltiTools-API</h1>

<div align="center"><strong>The Modern Paper Plugin Framework</strong></div>

<p align="center">Build Minecraft plugins the way you'd build a Spring Boot app — annotations, dependency injection, ORM, and scheduled tasks. Works with any Bukkit/Paper plugin. Java 21+, Minecraft 1.21+.</p>

<br>

<div align="center">
<img alt="GitHub License" src="https://img.shields.io/github/license/ultikits/ultitools-reborn?style=flat-square"/>
<img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/UltiKits/UltiTools-Reborn?style=flat-square"/>
<img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.ultikits/UltiTools-API?style=flat-square"/>
<img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/UltiKits/UltiTools-Reborn?style=flat-square"/>
<img alt="Minecraft Version" src="https://img.shields.io/badge/Minecraft-1.21+-blue?style=flat-square"/>
<img alt="Java Version" src="https://img.shields.io/badge/Java-21+-orange?style=flat-square"/>
<img alt="Spigot Rating" src="https://img.shields.io/spiget/rating/85214?label=SpigotMC&amp;style=flat-square"/>
<img alt="bStats Players" src="https://img.shields.io/bstats/players/8652?style=flat-square"/>
<img alt="bStats Servers" src="https://img.shields.io/bstats/servers/8652?style=flat-square"/>
<a href="https://www.codefactor.io/repository/github/ultikits/ultitools-reborn/overview/alpha"><img src="https://www.codefactor.io/repository/github/ultikits/ultitools-reborn/badge/alpha" alt="CodeFactor" /></a>
</div>

---

<p align="center">
<strong>English</strong> | <a href="https://github.com/UltiKits/UltiTools-Reborn/wiki/%E4%B8%AD%E6%96%87%E4%BB%8B%E7%BB%8D">中文文档</a>
</p>

<div align="center">

| [![discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/6TVRRF47) | Join our Discord! | 加入官方 QQ 群！ | [![discord](https://img.shields.io/badge/Tencent_QQ-EB1923?style=for-the-badge&logo=TencentQQ&logoColor=white)](https://qm.qq.com/q/i5GeDH6a7C) |
|-----------------------------------------------------------------------------------------------------------------------------------------|----------------------------|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|

</div>

<br>

## Why UltiTools?

Writing Minecraft plugins often means pages of boilerplate — registering commands manually, wiring up listeners, building raw SQL, scheduling BukkitRunnables everywhere. UltiTools-API eliminates all of that.

**What you get:**

| Feature | Traditional Bukkit | With UltiTools-API |
|---------|-------------------|-------------------|
| Commands | `onCommand()` switch/case | `@CmdMapping(format = "pay <player> <amount>")` |
| Listeners | Manual registration in `onEnable()` | `@EventListener` auto-discovered |
| Dependency injection | Static singletons everywhere | `@Autowired` like Spring |
| Data storage | Raw JDBC or custom file I/O | `@Table` + `@Column` ORM, works with MySQL/SQLite/JSON |
| Config | `getConfig().getString(...)` | `@ConfigEntry` on typed fields with validation |
| Scheduled tasks | `new BukkitRunnable()` boilerplate | `@Scheduled(period = 6000)` on any method |
| Feature toggles | Manual `if` checks | `@ConditionalOnConfig` skips entire components |
| Transactions | Manual try/catch/rollback | `@Transactional` AOP proxy |
| Inter-module events | Custom event buses or static coupling | `@ModuleEventHandler` pub/sub EventBus |

<br>

## Getting Started

### Requirements

- **Java**: 21 or higher
- **Minecraft**: 1.21+ (Paper)
- **UltiTools Plugin**: Install on your server

### Add the Dependency

**Maven**
```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>6.2.4</version>
    <scope>provided</scope>
</dependency>
```

**Gradle**
```groovy
compileOnly 'com.ultikits:UltiTools-API:6.2.4'
```

> The scope matters. UltiTools-API is a shaded JAR published with a dependency-free POM, and the
> UltiTools plugin already provides it at runtime. Compile against it, but do not bundle it — a
> `compile`/`implementation` scope pulls the whole framework (including the un-relocated XSeries)
> into your own JAR.

### Option A: Write an UltiTools Module (Recommended)

Building an UltiTools module is the best way to develop with UltiTools-API. Modules are lightweight plugins managed by the UltiTools framework, giving you everything you'd get from a standalone plugin plus:

- **One-click updates** — Server admins can update your module directly from the UltiTools panel, no manual JAR swapping
- **Built-in marketplace** — Publish to [UltiCloud](https://panel.ultikits.com) and let users discover, install, and manage your module from a central catalog
- **Hot reload** — Modules can be loaded and unloaded at runtime without restarting the server
- **Shared infrastructure** — Database connections, config management, and i18n are handled by the framework; your module stays small and focused
- **Inter-module communication** — Publish and subscribe to events across modules with the built-in EventBus

**Quickest start — scaffold with the CLI:**

```bash
npm install -g @ultikits/cli
ultikits create
# Answer the prompts, then:
cd MyModule && mvn compile
```

This generates a complete, compilable project with `pom.xml`, main class, `plugin.yml`, language files, and tests directory. See the [CLI documentation](https://dev.ultikits.com/guide/advanced/ultikits-cli) for details.

**Or set up manually** — create `plugin.yml`:

```yaml
name: MyPlugin
version: '${project.version}'
main: com.example.myplugin.MyPlugin
api-version: 620
authors: [ YourName ]
```

Write your main class:

```java
@UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;  // Commands, listeners, services are all auto-registered!
    }

    @Override
    public void unregisterSelf() { }
}
```

Add `@CmdExecutor` on command classes, `@EventListener` on listener classes, and `@Service` on services. The framework discovers and wires everything automatically.

For detailed guides, see the [Developer Documentation](https://dev.ultikits.com/guide/introduction).

### Option B: Use from Any Bukkit Plugin

Already have a plugin? You can integrate UltiTools-API into any existing Bukkit/Paper plugin without rewriting it as a module. You get the full framework — DI, ORM, scheduled tasks, EventBus — with a single line of code.

Add `depend: [UltiTools]` to your `plugin.yml` and call `UltiToolsAPI.connect(this)`:

```java
public class MyBukkitPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // One line — UltiTools scans your plugin for @Service, @CmdExecutor, @EventListener, etc.
        UltiToolsAPI.connect(this);
    }

    @Override
    public void onDisable() {
        UltiToolsAPI.disconnect(this);
    }
}
```

After `connect()`, all annotated classes in your plugin's package are auto-discovered and managed by the UltiTools IoC container — everything a native module gets, while keeping your plugin as a standard Bukkit plugin.

See the [External Plugin API Guide](https://dev.ultikits.com/guide/advanced/external-plugin-api) for full details, or check out the [complete working example](https://github.com/UltiKits/UltiTools-External-Example).

<br>

## Quick Look

A complete UltiTools module in under 50 lines:

```java
// Main class — just one annotation to enable auto-scanning
@UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() { return true; }

    @Override
    public void unregisterSelf() { }
}
```

```java
// Define your data — works with MySQL, SQLite, and JSON automatically
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("homes")
public class HomeEntity extends BaseDataEntity<String> {
    @Column("player_id") private String playerId;
    @Column("name")      private String name;
    @Column("world")     private String world;
    @Column("x")         private double x;
    @Column("y")         private double y;
    @Column("z")         private double z;
}
```

```java
// Write commands like controllers — auto-registered, auto-completed
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"home"}, permission = "myplugin.home")
public class HomeCommand extends BaseCommandExecutor {

    @Autowired
    private HomeService homeService;

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("/home tp <name> | set <name> | list");
    }

    @CmdMapping(format = "tp <name>")
    public void teleport(@CmdSender Player player, @CmdParam("name") String name) {
        homeService.teleport(player, name);
    }

    @CmdMapping(format = "set <name>")
    public void setHome(@CmdSender Player player, @CmdParam("name") String name) {
        homeService.setHome(player, name, player.getLocation());
        player.sendMessage("Home set!");
    }

    @CmdMapping(format = "list")
    public void listHomes(@CmdSender Player player) {
        homeService.getHomes(player).forEach(h ->
            player.sendMessage(" - " + h.getName())
        );
    }
}
```

```java
// Service with DI, query DSL, and scheduled tasks
@Service
public class HomeService {

    @Autowired
    private UltiToolsPlugin plugin;

    public void teleport(Player player, String name) {
        HomeEntity home = plugin.getDataOperator(HomeEntity.class)
            .query()
            .where("playerId").eq(player.getUniqueId().toString())
            .and("name").eq(name)
            .first();

        if (home != null) {
            Location loc = new Location(
                Bukkit.getWorld(home.getWorld()),
                home.getX(), home.getY(), home.getZ()
            );
            player.teleport(loc);
        }
    }

    public List<HomeEntity> getHomes(Player player) {
        return plugin.getDataOperator(HomeEntity.class)
            .query()
            .where("playerId").eq(player.getUniqueId().toString())
            .orderBy("name")
            .list();
    }

    @Scheduled(period = 72000, async = true) // Cleanup every hour
    public void cleanupExpiredHomes() {
        // Automatically called by the framework — no BukkitRunnable needed
    }
}
```

That's it. No `onCommand()` switches, no manual listener registration, no `new BukkitRunnable()` boilerplate, no raw SQL.

<br>

## Features

### Annotation-Driven Commands
Map subcommands directly to methods. Parameters are parsed and type-converted automatically.

```java
@CmdMapping(format = "pay <player> <amount>")
public void pay(@CmdSender Player sender, @CmdParam("player") String target, @CmdParam("amount") double amount) {
    // 'amount' is already a double — no manual parsing
}
```

> **Migrating from `AbstractCommandExecutor`.** The older base class is deprecated
> (`forRemoval = true`, since 6.2.0) and is scheduled for removal in **6.3.0**. Extend
> `abstracts.command.BaseCommandExecutor` instead; the only mandatory addition is the abstract
> `handleHelp(CommandSender)`. Step-by-step instructions and the full list of what 6.3.0 removes
> are in [`COMPATIBILITY.md`](COMPATIBILITY.md).

### IoC Container with `@Autowired`
Spring-like dependency injection with three-level cache for circular dependency resolution.

```java
@Service
public class EconomyService {
    @Autowired
    private TransactionLogger logger;  // Auto-injected

    @Transactional
    public void transfer(UUID from, UUID to, double amount) {
        // Automatic rollback on exception
    }
}
```

### Fluent Query DSL
Chain conditions, ordering, and pagination — works across MySQL, SQLite, and JSON storage.

```java
List<AccountEntity> richPlayers = plugin.getDataOperator(AccountEntity.class)
    .query()
    .where("balance").gte(10000)
    .orderByDesc("balance")
    .limit(10)
    .list();

boolean exists = plugin.getDataOperator(AccountEntity.class)
    .query()
    .where("owner").eq(uuid)
    .and("name").eq("savings")
    .exists();
```

### `@Scheduled` Tasks
Declare scheduled methods — the framework handles BukkitRunnable creation, registration, and cleanup.

```java
@Service
public class InterestService {
    @Scheduled(delay = 100, period = 36000, async = true) // 30min cycle, async
    public void distributeInterest() {
        // Called automatically. Cancelled when plugin unloads.
    }

    @Scheduled(delay = 20) // One-shot: runs once after 1 second
    public void onStartup() {
        // Initialization logic
    }
}
```

### Config Validation
Invalid config values are automatically caught and reset to safe defaults on load and reload.

```java
@Getter @Setter
@ConfigEntity("config/config.yml")
public class EcoConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "interestRate", comment = "Interest rate per cycle")
    @Range(min = 0.0, max = 1.0)
    private double interestRate = 0.0003;

    @ConfigEntry(path = "currency_name", comment = "Currency display name")
    @NotEmpty
    private String currencyName = "Gold Coin";

    @ConfigEntry(path = "motd", comment = "Server message of the day")
    @Size(min = 1, max = 256)
    private String motd = "Welcome!";
}
```

### `@ConditionalOnConfig`
Toggle entire features on/off from config — no `if` checks scattered through code.

```java
@Service
@ConditionalOnConfig(value = "config/config.yml", path = "enableInterest")
public class InterestService {
    // This bean is never created if enableInterest: false
}

@CmdExecutor(alias = {"warp"}, permission = "myplugin.warp")
@ConditionalOnConfig(value = "config/config.yml", path = "enableWarp")
public class WarpCommands extends BaseCommandExecutor {
    // Command doesn't exist if enableWarp: false
    @Override
    protected void handleHelp(CommandSender sender) { }
}
```

### AOP Proxies
`@Transactional` and `@ExceptionCatch` via ByteBuddy runtime proxies — no boilerplate try/catch.

```java
@Service
public class BankService {
    @Transactional(propagation = Propagation.REQUIRED)
    public void transfer(UUID from, UUID to, double amount) {
        withdraw(from, amount);
        deposit(to, amount); // Rolls back both if this throws
    }

    @ExceptionCatch(value = IOException.class, silent = true)
    public String readExternalData() {
        // Returns null on IOException instead of crashing
    }
}
```

### Module EventBus
Decoupled pub/sub communication between modules — no direct dependencies needed.

```java
// Define a custom event
public class BalanceChangeEvent extends ModuleEvent {
    @Getter private final UUID player;
    @Getter private final double amount;
    public BalanceChangeEvent(UUID player, double amount) {
        this.player = player;
        this.amount = amount;
    }
}

// Subscribe via annotation — auto-discovered by the framework
@Service
public class AuditService {
    @ModuleEventHandler(priority = EventPriority.NORMAL)
    public void onBalanceChange(BalanceChangeEvent event) {
        log(event.getSourceModule(), event.getPlayer(), event.getAmount());
    }
}

// Publish from anywhere
eventBus.publish(new BalanceChangeEvent(player, 100.0));
```

### More Built-in

- **GUI Framework** — Inventory GUIs with pagination via ObliviateInvs, plus a declarative reactive GUI system
- **i18n** — Built-in internationalization with `i18n("key")`
- **Hot Module Loading** — Load/unload plugin modules without server restart
- **WebSocket + UltiPanel** — Remote server management (commands, files, monitoring, logs)
- **Plugin Sandbox** — Class blacklisting, path traversal protection, command blocklists

<br>

## Documentation

Architecture notes, module guides, tutorials, and the API reference all live on the
documentation site — it is the single source of truth and is kept in sync with each release.

| Document | Description |
|----------|-------------|
| [Developer Docs](https://dev.ultikits.com/guide/introduction) | Full documentation site |
| [External Plugin API](https://dev.ultikits.com/guide/advanced/external-plugin-api) | Integrate UltiTools into an existing Bukkit plugin |
| [UltiKits CLI](https://dev.ultikits.com/guide/advanced/ultikits-cli) | Scaffold and publish modules from the terminal |

<br>

## Project Structure

```
UltiTools-Reborn/
├── src/main/java/com/ultikits/ultitools/
│   ├── UltiTools.java           # Main plugin entry
│   ├── api/                     # External Plugin API (UltiToolsAPI, ExternalPluginAdapter)
│   ├── abstracts/               # Base classes (UltiToolsPlugin, BaseCommandExecutor, etc.)
│   ├── annotations/             # Framework annotations (@Service, @CmdExecutor, @Scheduled, etc.)
│   │   └── config/              # Validation annotations (@Range, @NotEmpty, @Size, @Pattern)
│   ├── aop/                     # AOP system (ProxyFactory, TransactionInterceptor)
│   ├── context/                 # IoC container (SimpleContainer, ComponentScanner)
│   ├── events/                  # Module EventBus (EventBus, ModuleEvent, EventPriority, Cancellable)
│   ├── manager/                 # Core managers (Command, Listener, Config, Plugin, Task)
│   ├── interfaces/              # Core interfaces (DataOperator, Query, DataStore) + impl/
│   ├── websocket/               # UltiPanel WebSocket client + handlers
│   └── utils/                   # SecurityPolicy, CloudAuthManager, ApiRateLimiter
└── pom.xml
```

<br>

## Contributing

[Submit an Issue](https://github.com/UltiKits/UltiTools-Reborn/issues/new/choose) for bugs and suggestions.

### Main Contributors
| Contributor | Role |
|-------------|------|
| [@wisdommen](https://github.com/wisdommen) | Founder, UltiKits Author |
| [@qianmo2233](https://github.com/qianmo2233) | Developer, Documentation Maintainer |
| [@Shpries](https://github.com/Shpries) | Developer |
| [@JueChenChen](https://github.com/JueChenChen) | Issue Feedback & Suggestions |
| 拾柒 | Graphic Designer |

<br>

## License

MIT License — see [LICENSE](LICENSE) for details.

<br>

## WakaTime Statistics

<details>
<summary>Development Timeline</summary>
<img alt="wakatime timeline" src="https://wakatime.com/share/@wisdomme/0a9b3a30-f210-4be9-91f2-1b2e94ff403b.svg"/>
<br>
<br>
<img alt="wakatime week" src="https://wakatime.com/share/@wisdomme/bf0d9440-52ee-41b6-9df9-03fae3ae86dc.svg"/>
</details>

<br>

## Acknowledgments

|                                                                                                                           |                        |
|---------------------------------------------------------------------------------------------------------------------------|------------------------|
| ![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)                 | Development language |
| ![GitHub Actions](https://img.shields.io/badge/github%20actions-%232671E5.svg?style=for-the-badge&logo=githubactions&logoColor=white) | CI/CD |
| ![wakatime](https://img.shields.io/badge/IntelliJ_IDEA-000000.svg?style=for-the-badge&logo=intellij-idea&logoColor=white) | IDE |
| ![Claude](https://img.shields.io/badge/Claude-191919?style=for-the-badge&logo=anthropic&logoColor=white)                   | AI-assisted development |
| ![wakatime](https://img.shields.io/badge/apache_maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)        | Build tool |
