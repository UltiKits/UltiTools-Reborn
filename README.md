<br>
<br>

<div align="center">
<img src="https://github.com/UltiKits/UltiTools-Reborn/assets/62180110/f5e8e7d3-e97d-4d37-a9ab-ba3722dc6faa" width="96" height="96"/>
</div>


<h1 align="center">UltiTools-API</h1>

<div align="center"><strong>The Modern Paper Plugin Framework</strong></div>

<p align="center">Build Minecraft plugins the way you'd build a Spring Boot app — annotations, dependency injection, ORM, and scheduled tasks. Integrates with Bukkit plugins running on a supported Paper server. This branch documents the unreleased 7.0 line: Paper 26.3 (build 258) on Java 25. Each framework version targets one latest Paper version; older servers are not promised compatibility.</p>

<br>

<div align="center">
<img alt="GitHub License" src="https://img.shields.io/github/license/ultikits/ultitools-reborn?style=flat-square"/>
<img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/UltiKits/UltiTools-Reborn?style=flat-square"/>
<img alt="Latest published version on Maven Central" src="https://img.shields.io/maven-central/v/com.ultikits/UltiTools-API?style=flat-square"/>
<img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/UltiKits/UltiTools-Reborn?style=flat-square"/>
<img alt="Paper Version" src="https://img.shields.io/badge/Paper-26.3%20build%20258-blue?style=flat-square"/>
<img alt="Server Java Version" src="https://img.shields.io/badge/Server%20Java-25-orange?style=flat-square"/>
<img alt="bStats Players" src="https://img.shields.io/bstats/players/8652?style=flat-square"/>
<img alt="bStats Servers" src="https://img.shields.io/bstats/servers/8652?style=flat-square"/>
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

Writing Minecraft plugins often means pages of boilerplate — registering commands manually, wiring up listeners, building raw SQL, scheduling BukkitRunnables everywhere. UltiTools-API handles that infrastructure for you.

**What you get:**

| Feature | Traditional Bukkit | With UltiTools-API |
|---------|-------------------|-------------------|
| Commands | `onCommand()` switch/case | Annotated `@CmdMapping` methods compiled into Paper’s Brigadier command tree |
| Listeners | Manual registration in `onEnable()` | `@EventListener` auto-discovered |
| Dependency injection | Static singletons everywhere | `@Autowired` like Spring |
| Data storage | Raw JDBC or custom file I/O | `@Table` + `@Column` ORM, works with MySQL/SQLite/JSON |
| Config | `getConfig().getString(...)` | `@ConfigEntry` on typed fields with validation |
| Scheduled tasks | `new BukkitRunnable()` boilerplate | `@Scheduled(period = 6000)` on a no-argument `void` method in a managed bean |
| Feature toggles | Manual `if` checks | `@ConditionalOnConfig` skips entire components |
| Transactions | Manual try/catch/rollback | `@Transactional` AOP proxy |
| Inter-module events | Custom event buses or static coupling | `@ModuleEventHandler` pub/sub EventBus |

<br>

## 7.0

- Inject `com.ultikits.ultitools.scheduling.Scheduler` with `@Autowired` in module beans. Each module
  instance owns its scheduler; unloading it cancels pending and repeating work without affecting a
  same-name replacement. An already running one-shot task may finish.
- Do not submit tasks from `onUnregister()` or `@PreDestroy`: the module scheduler has already retired
  and rejects submissions with `RejectedExecutionException`. Perform unload cleanup directly.
- Literal `@Scheduled(async = true)` periods use wall-clock time (50 ms per configured tick). A lagging
  server no longer stretches those periods. Config-bound async tasks still use a global tick trigger.

A failed module load also retires that instance's injected scheduler and cancels pending and repeating
work submitted during bean construction, `@PostConstruct`, or `registerSelf()`. An already running
one-shot task may finish. This guarantee applies to the module-owned scheduler, not direct Bukkit tasks.

## Getting Started

### Requirements

- **Version**: [7.0.0](https://github.com/UltiKits/UltiTools-Reborn/releases/tag/v7.0.0) is the latest stable release. For 6.x server requirements, use the [6.x compatibility guide](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md).
- **Server**: Paper 26.3 (build 258), the baseline for 7.0.0. Each framework version targets one latest Paper version, not a range of older servers. Plain Spigot is not supported.
- **Server Java**: Java 25.
- **Building the framework**: JDK 25, compiling with `--release 25` (Java 25 bytecode, class major 69). The framework plugin declares `api-version: '26.3'`.
- **Modules**: use builds compiled for UltiTools-API 7.0 and the target Paper version. A native module declares an integer `api-version: 700` in its `plugin.yml`, not the framework plugin’s Paper API version `26.3`. Rebuild 6.x modules; changing the declaration alone will not make a 6.x JAR work. See the [support matrix](COMPATIBILITY.md#support-matrix).
- **UltiTools plugin**: install the framework JAR in the server’s `plugins/` directory. Install native modules under `plugins/UltiTools/plugins/` and restart the server.

### Languages and message catalogues (as of 7.0)

New installations use `language: auto` and `fallback-language: auto`. Players follow their current
Minecraft client language; a concrete `language`, such as `en` or `zh`, forces all players instead.
Console output and messages without a translation use the fallback. An automatic, blank or missing
fallback first uses the concrete `language` (including the official prefix of a 6.x custom name).
Only when `language` is also automatic, blank or missing does fallback use the system time-zone
**ID**, not its UTC offset: `Asia/Shanghai`, `Asia/Urumqi`, `Asia/Hong_Kong`,
`Asia/Macau`, `PRC`, `Asia/Chongqing`, `Asia/Chungking`, `Asia/Harbin`, `Asia/Kashgar`, `Hongkong`
and `Asia/Macao` select `zh`; every other ID selects `en`. The result is resolved at startup and
on a full `/ul reload`, not on each message. Startup reports the selected language and time-zone ID.

Cloud hosts and containers commonly use UTC, so a new installation there uses **English**, unlike
6.x's shipped Chinese default. To choose Chinese explicitly, set `fallback-language: zh` in
`plugins/UltiTools/config.yml` and run `/ul reload`. This does not change players whose client has
a translation. Explicit fallback codes always win. Existing files are not rewritten: an existing
`language: zh` or `language: en` without a fallback keeps that language, including console output;
automatic mode without a fallback uses the time-zone rule above.

Modules ship UTF-8 `messages/<locale>.yml` catalogues with stable dotted keys and MiniMessage values,
and may declare `source-language: en` in their `plugin.yml` (English if omitted). Scalar keys and
values are literal strings, including unquoted `Yes`, `null` and numbers; lists are not messages.
Only `.yml` is read; use language-only files such as `zh.yml` unless adding regional variants.
Inject `Messages`
to render or send text per recipient; use `shared()` for shared objects. Lookup tries the requested
language, configured fallback, module source language, then the key. Put only operator changes in
`plugins/UltiTools/pluginConfig/<module>/messages/<locale>.yml`; framework overrides belong in
`plugins/UltiTools/messages/<locale>.yml`. Generated `messages/reference/` files are framework-owned
examples, overwritten at startup; edit the parent override file, not the reference. A module reload
re-reads its overrides; a full `/ul reload` also re-reads the framework overrides. **7.0 removes
`i18n` and no longer reads, changes or imports old `lang/` files.** Convert changed translations
to MiniMessage rather than `§` / `&` colour codes. A configured name such as `zh-myserver` still
selects `zh` (an unmatched safe name selects `en`) without reading the old custom file. See the
[legacy text migration guide](COMPATIBILITY.md#legacy-text-system-removed-as-of-700).

中文补充：7.0 新装默认两个语言设置均为 `auto`；玩家按客户端语言显示。后备设置为 `auto`、空白
或缺失时，先沿用具体 `language`（自定义名称取官方前缀语言）；`language` 也为自动、空白或缺失时，
才按上列系统时区 ID 选择中文，其余使用英文（不是按 UTC 偏移判断）。云主机、容器常为 UTC，因而默认英文，与 6.x
出厂中文不同。想要中文，在 `plugins/UltiTools/config.yml` 写 `fallback-language: zh` 并执行完整
`/ul reload`；显式语言优先，已有 `language: zh/en` 且未写后备语言的配置不会被新默认覆盖，配置文件
也不会自动改写。时区选择只在启动和配置重载时解析，玩家运行中改客户端语言仍立即生效。
模块随包使用 `messages/<语言>.yml`，服主在上级覆盖文件里只写需要修改的条目；`reference/` 是每次
启动重写的只读参考，不要修改。单模块重载会重读该模块覆盖，完整重载也会重读框架覆盖。7.0 移除
`i18n`，不读取、修改或导入旧 `lang/` 文件；新目录的 `§`、`&` 不解释为颜色码。语言键和值的标量
均按原样读取，`Yes`、`null`、数字不必加引号，列表不能作为消息。仅读取 UTF-8 的 `.yml`；
提供 `zh_CN.yml` 等地区变体时也应提供 `zh.yml`。`zh-myserver` 仍归到 `zh`，无匹配前缀的安全名称用 `en`。

### Add the Dependency

The examples below target 7.0 and resolve `7.0.0` from Maven Central. For the earlier `6.5.0` release, use the [6.x branch’s API examples](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/README.md) instead.

**Maven**
```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>7.0.0</version>
    <scope>provided</scope>
</dependency>
```

**Gradle**
```groovy
compileOnly 'com.ultikits:UltiTools-API:7.0.0'
```

> Compile against UltiTools-API with `provided` or `compileOnly`, and declare the matching
> `paper-api` dependency separately for Paper types. The UltiTools plugin provides the framework
> at runtime; `compile`/`implementation` can cause a shade/shadow build to bundle a second copy.
> Use Paper’s native `Material`, `Sound` and `Enchantment` APIs.
> The framework no longer supplies XSeries or `XVersionUtils`. A module keeping XSeries must
> shade and relocate it itself; Paper `libraries:` is not available to UltiTools modules.

```java
// Inject ItemRenderer with @Autowired in the consuming bean.
ItemStack item = itemRenderer.render(ItemSpec.builder(Material.STONE).build(), player);
player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 0);
ItemStack enchanted = itemRenderer.render(ItemSpec.builder(Material.FISHING_ROD)
        .data(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.itemEnchantments()
                .add(Enchantment.LUCK_OF_THE_SEA, 1).build()).build(), player);
player.sendActionBar(Component.text("Ready"));
```

### Option A: Write an UltiTools Module (Recommended)

Building an UltiTools module is the best way to develop with UltiTools-API. Modules are lightweight plugins managed by the UltiTools framework, giving you everything you'd get from a standalone plugin plus:

- **Built-in marketplace** — Publish to [UltiCloud](https://panel.ultikits.com) and let users discover, install, and manage your module from a central catalog
- **Managed module lifecycle** — Install and update modules through UPM; changes take effect at the next server start
- **Shared infrastructure** — Database connections, config management, and per-recipient message rendering are handled by the framework; your module stays small and focused
- **Inter-module communication** — Publish and subscribe to events across modules with the built-in EventBus

Create the module’s `src/main/resources/plugin.yml` (its JAR-root descriptor):

```yaml
name: MyPlugin
version: 1.0.0
main: com.example.myplugin.MyPlugin
api-version: 700
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
}
```

Add `@CmdExecutor` on command classes, `@EventListener` on listener classes, and `@Service` on services. The framework discovers and wires these components under your module’s declared scan roots.

For detailed guides, see the [Developer Documentation](https://dev.ultikits.com/guide/introduction).

### Option B: Use from an Existing Bukkit Plugin on Paper

Already have a Bukkit plugin running on supported Paper? You can integrate UltiTools-API without rewriting it as a module. The connection gives you dependency injection, annotated commands and listeners, ORM, literal scheduled tasks, and EventBus without making your plugin a native module.

For a Bukkit-style plugin, add `depend: [UltiTools]` to your own `plugin.yml` and call `UltiToolsAPI.connect(this)` during enable:

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

After `connect()`, annotated components in your plugin's package are auto-discovered and managed by the UltiTools IoC container, while your plugin remains a standard Bukkit plugin on Paper. Module-only features, such as config-bound scheduled tasks, still require a native module.

An external plugin can bundle recognized `messages/<locale>.yml` files for a `Messages` instance bound to its own namespace. Its overrides belong in its own data folder; `/ul reload` does not refresh them. See [external plugin messages](COMPATIBILITY.md#external-plugin-messages-as-of-700) and the [External Plugin API guide](https://dev.ultikits.com/guide/advanced/external-plugin-api).

<br>

## Quick Look

The core pieces of a small UltiTools module (imports omitted):

```java
// Main class — just one annotation to enable auto-scanning
@UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() { return true; }
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
    public void setHome(@CmdSender Player player, @CmdParam("name") String name) throws IllegalAccessException {
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
            .where("player_id").eq(player.getUniqueId().toString())
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

    public void setHome(Player player, String name, Location location) throws IllegalAccessException {
        DataOperator<HomeEntity> homes = plugin.getDataOperator(HomeEntity.class);
        HomeEntity home = homes.query()
            .where("player_id").eq(player.getUniqueId().toString())
            .and("name").eq(name)
            .first();
        boolean isNew = home == null;
        if (isNew) {
            home = new HomeEntity();
            home.setPlayerId(player.getUniqueId().toString());
            home.setName(name);
        }
        home.setWorld(location.getWorld().getName());
        home.setX(location.getX());
        home.setY(location.getY());
        home.setZ(location.getZ());
        if (isNew) {
            homes.insert(home);
        } else {
            homes.update(home);
        }
    }

    public List<HomeEntity> getHomes(Player player) {
        return plugin.getDataOperator(HomeEntity.class)
            .query()
            .where("player_id").eq(player.getUniqueId().toString())
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
Paper registers an annotated Brigadier tree: argument parsing, usage and client completions come from that tree rather than a reflective Bukkit command map.

```java
@CmdMapping(format = "pay <player> <amount>")
public void pay(@CmdSender Player sender, @CmdParam("player") String target, @CmdParam("amount") double amount) {
    // 'amount' is already a double — no manual parsing
}
```

> Extend `BaseCommandExecutor` and implement `handleHelp(CommandSender)`. For removed base classes, argument syntax changes and upgrade steps, see the [7.0 command engine migration](COMPATIBILITY.md#command-engine-as-of-700).

### IoC Container with `@Autowired`
Spring-like dependency injection with three-level cache for circular dependency resolution.

```java
@Service
public class HomeAuditService {
    @Autowired
    private HomeService homes; // Discovered and injected by the container
}
```

### Fluent Query DSL
Chain conditions, ordering, and pagination — works across MySQL, SQLite, and JSON storage.

```java
List<HomeEntity> highHomes = plugin.getDataOperator(HomeEntity.class)
    .query()
    .where("y").gte(100)
    .orderByDesc("y")
    .limit(10)
    .list();

boolean exists = plugin.getDataOperator(HomeEntity.class)
    .query()
    .where("player_id").eq(player.getUniqueId().toString())
    .and("name").eq("base")
    .exists();
```

### `@Scheduled` Tasks
Declare work on a managed bean — the module-owned `Scheduler` handles dispatch and unload cleanup.

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
Invalid values that violate declared constraints refuse the module load or configuration reload, with one exception:
for a `@NotEmpty` list, set, map or array, an empty or null value at load or reload uses the declared default in memory
and logs one warning per setting per load or reload. The declared default must be nonempty and satisfy any declared
`@Size` constraint, or the module refuses to load. Empty text still refuses load or reload.
Operator-written configuration is never automatically reset or overwritten; the fallback does not change the file.

```java
@Getter @Setter
@ConfigEntity("config/config.yml")
public class EcoConfig extends AbstractConfigEntity {

    public EcoConfig(String configFilePath) {
        super(configFilePath);
    }

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
Choose which components register at startup — no `if` checks scattered through code. A restart is required after changing the condition; `/ul reload` only reports the drift.

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
    public void updateBalances() {
        // Put the related DataOperator writes here; unchecked exceptions end the transaction.
    }

    @ExceptionCatch(value = IOException.class, silent = true)
    public String readExternalData() throws IOException {
        // Returns null on IOException instead of propagating it
        return new String(Files.readAllBytes(Paths.get("plugins/MyPlugin/data.txt")),
            StandardCharsets.UTF_8);
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
        // Handle the event using event.getSourceModule(), getPlayer() and getAmount().
    }
}

// Publish using the injected EventBus.
eventBus.publish(new BalanceChangeEvent(player, 100.0));
```

### More Built-in

- **Message Catalogues** — Inject `Messages` to render MiniMessage text from `messages/<locale>.yml` for each recipient, with fallback and operator overrides.
- **UI Service** — Use `UiService` for notices, confirmations, forms, choices, and grids; declarative screens render through the service.
- **Bootstrap Content** — A module’s `bootstrapper:` hook can register enchantments, declare bundled data packs, and edit tags before worlds load.
- **External Plugin API** — Connect a separate plugin running on Paper with `UltiToolsAPI`; it can bundle locale files for its own message namespace.
- **Module Management** — Queue installs, updates and removals with `/upm`; changes take effect at the next server start.
- **UltiCloud** — Connect the server to the panel for remote commands, editable files, monitoring and logs.
- **Module JAR Validation** — The framework checks archive size and entry count (100 MB / 10,000 entries), not whether code is safe to run.
- **Remote Access Guards** — Capability switches, editable roots, path validation and command blocklists bound panel access.

<br>

## Startup and known limitations

UltiTools 7.0 is a Paper plugin: its JAR contains `paper-plugin.yml`, while modules continue to
use their own `plugin.yml`. Paper runs module bootstrap hooks before worlds load; normal module
loading happens during enable. An overall bootstrap failure disables the framework during enable
with the retained error, rather than silently running without modules.

- First start needs network access to download runtime libraries, unless they are already cached.
  If Paper reports `Error resolving libraries`, check connectivity or set
  `PAPER_DEFAULT_CENTRAL_REPOSITORY` to an accessible Maven mirror before starting the server.
- Use a full server restart after JAR changes. `/ul reload` reloads configuration and message overrides, not JARs. `/reload`,
  plugin-manager hot reload, and disabling then re-enabling UltiTools are unsupported.
- Pending module changes are applied before world loading and confirmed after modules load. Stopping or
  crashing in between triggers recovery on the next start; let startup finish first.
- A module whose bootstrap hook fails is not enabled, but its classes remain on the shared class
  path. Other modules that reference those classes can be affected. Do not initialize shared
  libraries that need Bukkit in an early hook.
- Declare every server plugin a module uses in `depend` or `softdepend`. Undeclared visibility is
  not guaranteed, even if a dependency such as Vault happens to expose its own dependencies.

See [COMPATIBILITY.md](COMPATIBILITY.md#two-stage-paper-startup-and-known-limitations-as-of-700)
for the startup and class-loading boundaries.

## Documentation

Architecture notes, module guides, tutorials and the API reference live on the
documentation site. For this unreleased line, start with the [7.0 compatibility and migration guide](COMPATIBILITY.md). For earlier versions, see the [6.x compatibility guide](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md).

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
│   ├── bootstrap/               # Paper loader, module bootstrap and content registration
│   ├── command/                 # Brigadier compiler and Paper lifecycle integration
│   ├── context/                 # IoC container (SimpleContainer, ComponentScanner)
│   ├── events/                  # Module EventBus (EventBus, ModuleEvent, EventPriority, Cancellable)
│   ├── manager/                 # Core managers (Command, Listener, Config, Plugin, Task)
│   ├── interfaces/              # Core interfaces (DataOperator, Query, DataStore) + impl/
│   ├── scheduling/              # Module-owned scheduler
│   ├── text/                    # Messages, locale selection and catalogues
│   ├── ui/                      # UI service and screen backends
│   └── websocket/               # UltiCloud WebSocket client and protocol support
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
