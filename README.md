<br>
<br>

<div align="center">
<img src="https://github.com/UltiKits/UltiTools-Reborn/assets/62180110/f5e8e7d3-e97d-4d37-a9ab-ba3722dc6faa" width="96" height="96"/>
</div>


<h1 align="center">UltiTools 6</h1>

<div align="center"><strong>UltiTools' Reborn</strong></div>

<br>
<br>

<div align="center">
<img alt="GitHub License" src="https://img.shields.io/github/license/ultikits/ultitools-reborn?style=flat-square"/>
<img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/UltiKits/UltiTools-Reborn?style=flat-square"/>
<img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/UltiKits/UltiTools-Reborn?style=flat-square"/>
<img alt="Minecraft Version" src="https://img.shields.io/badge/Minecraft-1.8--1.21-blue?style=flat-square"/>
<img alt="Java Version" src="https://img.shields.io/badge/Java-8+-orange?style=flat-square"/>
<img alt="Spigot Rating" src="https://img.shields.io/spiget/rating/85214?label=SpigotMC&amp;style=flat-square"/>
<img alt="bStats Players" src="https://img.shields.io/bstats/players/8652?style=flat-square"/>
<img alt="bStats Servers" src="https://img.shields.io/bstats/servers/8652?style=flat-square"/>
<img alt="wakatime" src="https://wakatime.com/badge/user/d4b748db-828d-4641-b87e-85def2b4fc94/project/2ed8f867-16e0-4fd6-a5af-b18d50e59469.svg?style=flat-square"/>
<img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.ultikits/UltiTools-API?style=flat-square"/>
<img alt="GitHub issues" src="https://img.shields.io/github/issues/UltiKits/UltiTools-Reborn?style=flat-square"/>
<a href="https://www.codefactor.io/repository/github/ultikits/ultitools-reborn/overview/alpha"><img src="https://www.codefactor.io/repository/github/ultikits/ultitools-reborn/badge/alpha" alt="CodeFactor" /></a>
</div>

---

<div align="center">

| [![discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/6TVRRF47) | 👈 Click to join Discord server! | 点击右侧按钮加入官方 QQ 群！ 👉 | [![discord](https://img.shields.io/badge/Tencent_QQ-EB1923?style=for-the-badge&logo=TencentQQ&logoColor=white)](https://qm.qq.com/q/i5GeDH6a7C) |
|-----------------------------------------------------------------------------------------------------------------------------------------|----------------------------|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|

</div>

<br>
<br>

## UltiTools-API Introduction

[中文文档](https://github.com/UltiKits/UltiTools-Reborn/wiki/%E4%B8%AD%E6%96%87%E4%BB%8B%E7%BB%8D) | [项目 Wiki](docs/wiki/README.md)

I hope my plugin can help with your plugin development! XD

[Detailed Dev Documents](https://dev.ultikits.com/en/)

### Key Features

- 🎯 **Annotation-Driven Development** - Commands, listeners, configs auto-registered via annotations
- 🔧 **IoC Container** - Spring-like dependency injection with `@Service`, `@Autowired`, three-level cache for circular deps
- 🔀 **AOP Proxies** - `@Transactional` and `@ExceptionCatch` via CGLIB runtime proxies
- 💾 **Unified Data Storage** - Seamless MySQL/SQLite/JSON support with ORM-style API
- ⚙️ **Config as Objects** - Type-safe configuration with auto-reload
- 🖥️ **GUI Framework** - Easy inventory GUI development with pagination support
- 🌐 **WebSocket Integration** - Remote server management via UltiPanel with security hardening
- 🔒 **Plugin Sandbox** - Class blacklisting, path traversal protection, command blocklists
- 🌍 **i18n Support** - Built-in internationalization
- 📦 **Hot Module Loading** - Load/unload modules without server restart


### Annotation-driven

UltiTools-API has changed the way plugin development is done. By introducing advanced syntax like annotations, it makes your plugin development much more efficient.

With UltiTools-API, you no longer need to manually register commands and listeners. Simply add annotations to your command classes and listener classes, and UltiTools-API will automatically register them for you.

You can also write your commands like a controller. You no longer need to make tedious judgments for a command. Just add annotations to your command methods, and UltiTools-API will automatically match the commands to the corresponding methods.

```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"lore"}, manualRegister = true, permission = "ultikits.tools.command.lore", description = "Lore edit function")
public class LoreCommands extends AbstractCommandExecutor {

    @CmdMapping(format = "add <lore...>")
    public void addLore(@CmdSender Player player, @CmdParam("lore...") String[] lore) {
        ...
    }

    @CmdMapping(format = "delete <position>")
    public void deleteLore(@CmdSender Player player, @CmdParam("position") int position) {
        ...
    }

    @CmdMapping(format = "edit <position> <lore...>")
    public void editLore(@CmdSender Player player, @CmdParam("position") int position, @CmdParam("lore...") String[] lore) {
        ...
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "lore add <content>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("Add Lore"));
        sender.sendMessage(ChatColor.RED + "lore delete <lineNum>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("Delete Lore"));
        sender.sendMessage(ChatColor.RED + "lore edit <linNum> <content>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("Edit Lore"));
    }
}
```

In terms of data storage, UltiTools provides wrapped APIs for both MySQL and JSON, allowing you not to worry about which data storage method the users will choose.

For example

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("economy_accounts")
public class AccountEntity extends AbstractDataEntity {
    @Column("name")
    private String name;
    @Column(value = "balance", type = "FLOAT")
    private double balance;
    @Column("owner")
    private String owner;
}
```

```java
// check if the player account exists
public boolean playerHasAccount(UUID player, String name) {
    DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
    return dataOperator.exist(
            WhereCondition.builder().column("name").value(name).build(),
            WhereCondition.builder().column("owner").value(player.toString()).build()
        );
}
```

Regarding configuration files, UltiTools allows you to read the configuration files as if you were manipulating objects.

For example

```java
@Getter
@Setter
@ConfigEntity(path = "config/config.yml")
public class EcoConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "useThirdPartEconomy", comment = "Whether to use another economy plugin as a base (i.e., only use the bank function of this plugin)")
    private boolean useThirdPartEconomy = false;
    @ConfigEntry(path = "enableInterest", comment = "Whether to enable interest")
    private boolean enableInterest = true;
    @ConfigEntry(path = "interestRate", comment = "Interest rate, interest = interest rate × principal")
    private double  interestRate = 0.0003;
    @ConfigEntry(path = "interestTime", comment = "Interval for interest distribution (minutes)")
    private int interestTime = 30;
    @ConfigEntry(path = "initial_money", comment = "Initial amount of currency for players")
    private double initMoney = 1000;
    @ConfigEntry(path = "op_operate_money", comment = "Whether the server administrator can increase or decrease player currency")
    private boolean opOperateMoney = false;
    @ConfigEntry(path = "currency_name", comment = "Name of the currency")
    private String currencyName = "Gold Coin";
    @ConfigEntry(path = "server_trade_log", comment = "Whether to enable server trade log")
    private boolean enableTradeLog = false;
    public EcoConfig(String configFilePath) {
        super(configFilePath);
    }
}
```
```java
// Get the configuration file of the economy plugin and read the interest rate
EcoConfig config = UltiEconomy.getInstance().getConfig(EcoConfig.class);
double interestRate = config.getInterestRate();
```

### IoC Container

UltiTools-API provides a built-in IoC container with Spring-like dependency injection, three-level cache for circular dependency resolution, and AOP support via CGLIB proxies.

```java
// @Service marks the type as a Bean, and UltiTools-API will automatically scan and register it
@Service
public class BanPlayerService {
    
    ...

    public void unBanPlayer(OfflinePlayer player) {
        DataOperator<BanedUserData> dataOperator = BasicFunctions.getInstance().getDataOperator(BanedUserData.class);
        dataOperator.delById(player.getUniqueId().toString());
    }
}
```

```java
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(permission = "ultikits.ban.command.all", description = "Ban function", alias = {"uban"}, manualRegister = true)
public class BanCommands extends AbstractCommandExecutor {
    
    // Using the @Autowired annotation, UltiTools-API will automatically inject the dependency
    @Autowired
    private BanPlayerService banPlayerService;

    @CmdMapping(format = "unban <player>")
    public void unBanPlayer(@CmdSender CommandSender sender, @CmdParam("player") String player) {
        banPlayerService.unBanPlayer(Bukkit.getOfflinePlayer(player));
        sender.sendMessage(BasicFunctions.getInstance().i18n("§aUnban successful"));
    }
    
    ...
}
```

If you don't like automatic injection, or can't use automatic injection, you can also manually obtain the Bean.

```java
BanPlayerService banPlayerService = getContext().getBean(BanPlayerService.class);
```

### Providing Numerous Modern Dependency Libraries

UltiTools-API offers some functionalities of Hutool, including a large number of utility classes.

[Hutool Documentation](https://hutoolkit.com/docs/)

In terms of GUI interfaces, UltiTools provides the obliviate-invs API, facilitating rapid GUI development.

[ObliviateInvs — Highly efficient modular GUI library](https://www.spigotmc.org/resources/obliviateinvs-%E2%80%94-highly-efficient-modular-gui-library.103572/)

UltiTools also offers the Adventure API.

[Adventure Documentation](https://docs.adventure.kyori.net/)

### WebSocket & UltiPanel Integration

UltiTools-API includes built-in WebSocket support for remote server management through UltiPanel. Authentication is token-only via `/ulticloud login` (magic-link flow — no passwords stored on the server).

- **Batch Updates** - Single `batch_update` message every 5s with status, metrics, plugins (every 60s), and logs
- **Real-time Monitoring** - Server TPS, memory, online players
- **Remote Commands** - Execute commands from web dashboard with blocklist protection (`op`, `stop`, `reload`, etc. blocked; namespace prefix bypass prevention)
- **Log Streaming** - Real-time server log viewing via batch updates
- **File Management** - Remote config editing with path traversal protection, sensitive file blocklist, and executable extension blocking
- **Plugin Control** - Enable/disable plugins remotely
- **Magic Link Auth** - Admins run `ulticloud login` from the server console; the plugin auto-detects completion and connects to UltiPanel (this command cannot be used in-game)
- **Auto-Reconnect** - Exponential backoff reconnection (up to 5 attempts) with Bearer token authentication

## Quick Start

For more detailed documentation, please refer to [UltiTools API Documentation](https://dev.ultikits.com/en/) or the [Project Wiki](docs/wiki/README.md)

Below is a simple quick start guide.
<br>

### Requirements

- **Java**: 8 or higher
- **Minecraft**: 1.8 - 1.21 (Spigot/Paper)
- **UltiTools Plugin**: Install on your server first

### Installing Dependencies

First, add the UltiTools-API dependency to your project.

Using Maven

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>{VERSION}</version>
</dependency>
```

Using Gradle

```groovy
implementation 'com.ultikits:UltiTools-API:{VERSION}'
```

Before starting, please create a plugin.yml file in the resources folder with the following content:

```yaml
# Plugin name
name: TestPlugin
# Plugin version
version: '${project.version}'
# Plugin main class
main: com.test.plugin.MyPlugin
# UltiTools-API version used by the plugin, for example, 6.0.0 is 600
api-version: 600
# Plugin authors
authors: [ wisdomme ]
```
Create a config folder, where you can put your plugin configuration files according to your needs. These configuration files will be placed unmodified in the collective configuration folder of the UltiTools plugin for display to users.

### Simple Guide

Create a main class extending `UltiToolsPlugin`. Similar to traditional Spigot plugins, UltiTools plugins also need to override the start and stop methods. However, `UltiToolsPlugin` adds an optional `UltiToolsPlugin#reloadSelf()` method for use during plugin reload.

```java
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // Executes when the plugin starts
        return true;
    }

    @Override
    public void unregisterSelf() {
        // Executes when the plugin shuts down
    }
    
    @Override
    public void reloadSelf() {
        // Executes when the plugin is reloaded
    }
}
```
With this, you've completed an UltiTools plugin that does nothing. Then, you can register your listeners and commands in the `UltiToolsPlugin#registerSelf()` method.

```java
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // Register a Test command, with permission 'permission.test', and command 'test'
        // No need to register the command in Plugin.yml
        getCommandManager().register(new TestCommands(), "permission.test", "Sample Function", "test");
        // Register listeners
        getListenerManager().register(this, new TestListener());
        return true;
    }
}
```
Then, you can add your configuration file in the main class, and UltiTools will automatically load the configuration file.

```java
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // Register a Test command, with permission 'permission.test', and command 'test'
        // No need to register the command in Plugin.yml
        getCommandManager().register(new TestCommands(), "permission.test", "Sample Function", "test");
        // Register listeners
        getListenerManager().register(this, new TestListener());
        // Register configuration file
        getConfigManager().register(this, new TestConfig("config/config.yml"));
        return true;
    }
}
```
Alternatively, you can override the `UltiToolsPlugin#getAllConfigs()` method to register all configuration files here.

```java
@Override
public List<AbstractConfigEntity> getAllConfigs() {
    return Arrays.asList(
            new TestConfig("config/config.yml")
    );
}
```

### Auto-Registration with Annotations

For a more streamlined approach, use `@UltiToolsModule` to enable automatic scanning and registration:

```java
@UltiToolsModule(scanBasePackages = {"com.test.plugin"})
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // Commands, listeners, and services are auto-registered!
        return true;
    }
    
    @Override
    public void unregisterSelf() { }
}
```

With this annotation, any class marked with `@CmdExecutor`, `@EventListener`, or `@Service` in the specified packages will be automatically discovered and registered.

## Project Structure

```
UltiTools-Reborn/
├── src/main/java/com/ultikits/ultitools/
│   ├── UltiTools.java           # Main plugin entry
│   ├── abstracts/               # Base classes (UltiToolsPlugin, AbstractCommandExecutor, etc.)
│   ├── annotations/             # Framework annotations (@Service, @CmdExecutor, @Table, etc.)
│   ├── aop/                     # AOP system (CglibProxyFactory, TransactionInterceptor)
│   ├── context/                 # IoC container (SimpleContainer, ComponentScanner)
│   ├── manager/                 # Core managers (Command, Listener, Config, Plugin) +
│   │                            #   WebSocket managers (CommandExecution, FileOperation,
│   │                            #   ServerMonitor, LogStream)
│   ├── interfaces/              # Core interfaces (DataOperator, DataStore) + impl/
│   ├── websocket/               # UltiPanel WebSocket client + message handler registry
│   │   └── handlers/            # Message handlers (Command, FileOperation, LogStream, Pong)
│   └── utils/                   # SecurityPolicy, CloudAuthManager, ApiRateLimiter
├── plugins/                     # Built-in plugin modules (11 modules)
├── docs/wiki/                   # Project documentation
└── pom.xml                      # Maven build configuration
```

<br>
<br>

## Documentation

| Document | Description |
|----------|-------------|
| [Project Wiki](docs/wiki/README.md) | Complete project documentation |
| [Architecture Guide](docs/wiki/ARCHITECTURE.md) | System architecture overview |
| [IoC Container](docs/wiki/modules/IOC_CONTAINER.md) | Dependency injection guide |
| [Command System](docs/wiki/modules/COMMAND_SYSTEM.md) | Command development guide |
| [Data Storage](docs/wiki/modules/DATA_STORAGE.md) | Database operations guide |
| [Quick Start Tutorial](docs/wiki/tutorials/QUICK_START.md) | First module tutorial |
| [API Reference](docs/wiki/api/INDEX.md) | API quick reference |

<br>
<br>

## WakaTime Statistics

<details>
<h3>Development Timeline Statistics</h3>
<summary>Click to view statistics</summary>
<img alt="wakatime timeline" src="https://wakatime.com/share/@wisdomme/0a9b3a30-f210-4be9-91f2-1b2e94ff403b.svg"/>
<br>
<br>
<img alt="wakatime week" src="https://wakatime.com/share/@wisdomme/bf0d9440-52ee-41b6-9df9-03fae3ae86dc.svg"/>
</details>
<br>
<br>


## Main Contributors
| Contributor                                              | Description                                      |
|--------------------------------------------------|-----------------------------------------|
| [@wisdommen](https://github.com/wisdommen)       | Founder, UltiKits Author                        |
| [@qianmo2233](https://github.com/qianmo2233)     | UltiTools Developer, Main Maintainer of UltiKits Development Documentation |
| [@Shpries](https://github.com/Shpries)           | UltiTools Developer |
| [@JueChenChen](https://github.com/JueChenChen) | Feedback on UltiKits Issues, Bugs & Suggestions                      |
| 拾柒                                               | Graphic Designer                                      |

## Found an Issue? Want to Make a Suggestion?
[Click here to submit an Issue!](https://github.com/UltiKits/UltiTools-Reborn/issues/new/choose)


## Acknowledgments

|                                                                                                                           |                        |
|---------------------------------------------------------------------------------------------------------------------------|------------------------|
| ![Dependabot](https://img.shields.io/badge/dependabot-025E8C?style=for-the-badge&logo=dependabot&logoColor=white)         | Dep checker |
| ![GitHub Actions](https://img.shields.io/badge/github%20actions-%232671E5.svg?style=for-the-badge&logo=githubactions&logoColor=white) | Offical CI Tool
| ![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)                 | Development language |
| ![wakatime](https://img.shields.io/badge/WakaTime-000000?style=for-the-badge&amp;logo=WakaTime&amp;logoColor=white)       | Recorded every moment of our development journey          |
| ![wakatime](https://img.shields.io/badge/IntelliJ_IDEA-000000.svg?style=for-the-badge&logo=intellij-idea&logoColor=white) | The strongest Java IDE for a pleasant development experience |
| ![Claude](https://img.shields.io/badge/Claude-191919?style=for-the-badge&logo=anthropic&logoColor=white)                   | AI-assisted development, testing, and security hardening |
| ![wakatime](https://img.shields.io/badge/apache_maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)        | Official build tool                 |

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Related Projects

- [UltiPanel Worker](https://github.com/UltiKits/UltiPanel-Worker) - Cloudflare Workers API backend
- [UltiPanel Frontend](https://github.com/UltiKits/UltiPanelFrontend) - Vue 3 web dashboard
- [UltiKits Plugins](https://www.spigotmc.org/resources/authors/wisdomme.505795/) - Official UltiTools modules
