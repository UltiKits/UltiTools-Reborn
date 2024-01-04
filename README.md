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
<img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/wisdommen/UltiTools?style=flat-square"/>
<img alt="Minecraft Version" src="https://img.shields.io/badge/Minecraft-1.8--1.20-blue?style=flat-square"/>
<img alt="Spigot Rating" src="https://img.shields.io/spiget/rating/85214?label=SpigotMC&amp;style=flat-square"/>
<img alt="bStats Players" src="https://img.shields.io/bstats/players/8652?style=flat-square"/>
<img alt="bStats Servers" src="https://img.shields.io/bstats/servers/8652?style=flat-square"/>
<img alt="wakatime" src="https://wakatime.com/badge/user/d4b748db-828d-4641-b87e-85def2b4fc94/project/2ed8f867-16e0-4fd6-a5af-b18d50e59469.svg?style=flat-square"/>
<img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.ultikits/UltiTools-API?style=flat-square"/>
<img alt="GitHub issues" src="https://img.shields.io/github/issues/wisdommen/UltiTools?style=flat-square"/>
</div>

---

<div align="center">

| [![discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/6TVRRF47) | 👈 点击左侧按钮加入官方 Discord 服务器！ | 点击右侧按钮加入官方 QQ 群！ 👉 | [![discord](https://img.shields.io/badge/Tencent_QQ-EB1923?style=for-the-badge&logo=TencentQQ&logoColor=white)](https://qm.qq.com/cgi-bin/qm/qr?k=UNq3LPCmpfH2aLum7V0GmMRFBusNxqxn&jump_from=webapi) |
|-----------------------------------------------------------------------------------------------------------------------------------------|----------------------------|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|

</div>



<br>
<br>

## 给各位服主的介绍

UltiTools是一个高层的基础插件，包含了很多GUI和高级的玩法，而非仅仅所谓的“基础”。

插件本身的详细介绍，请看mcbbs的帖子。

[\[综合\]UltiTools —— 远程背包\|GUI登陆\|GUI邮箱\|礼包\|头显\|侧边栏~\[1.8.x-1.20.x\]](https://www.mcbbs.net/thread-1062730-1-1.html)

用户使用文档

[UltiTools 文档](https://doc.ultitools.ultikits.com/)

## 给开发者的介绍

希望我的插件能够帮到你的插件开发！

UltiTools包含了不少实用的API，方便你快速的开发自己的插件。

数据存储方面，UltiTools提供了Mysql和Json的封装API，让你无需考虑用户会使用哪种数据存储方式。

例如
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
//检查玩家账户是否存在
public boolean playerHasAccount(UUID player, String name) {
    DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
    return dataOperator.exist(
            WhereCondition.builder().column("name").value(name).build(),
            WhereCondition.builder().column("owner").value(player.toString()).build()
        );
}
```

配置文件方面，UltiTools提供了优雅的单例模式的封装API，让你可以像操作对象一样操作配置文件。

例如
```java
@Getter
@Setter
public class EcoConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "useThirdPartEconomy", comment = "是否使用其他的经济插件作为基础（即仅使用本插件的银行功能）")
    private boolean useThirdPartEconomy = false;
    @ConfigEntry(path = "enableInterest", comment = "是否开启利息")
    private boolean enableInterest = true;
    @ConfigEntry(path = "interestRate", comment = "利率，利息 = 利率 × 本金")
    private double  interestRate = 0.0003;
    @ConfigEntry(path = "interestTime", comment = "利息发放间隔（分钟）")
    private int interestTime = 30;
    @ConfigEntry(path = "initial_money", comment = "玩家初始货币数量")
    private double initMoney = 1000;
    @ConfigEntry(path = "op_operate_money", comment = "服务器管理员是否能够增减玩家货币")
    private boolean opOperateMoney = false;
    @ConfigEntry(path = "currency_name", comment = "货币名称")
    private String currencyName = "金币";
    @ConfigEntry(path = "server_trade_log", comment = "是否开启服务器交易记录")
    private boolean enableTradeLog = false;
    public EcoConfig(String configFilePath) {
        super(configFilePath);
    }
}
```
```java
// 获取经济插件的配置文件，并且读取利息率
EcoConfig config = UltiEconomy.getInstance().getConfig(EcoConfig.class);
double intrestRate = config.getInterestRate();
```

UltiTools还封装了一系列的Spigot API，让你更加高效优雅的开发插件。

例如
```java
// 注册一个Test指令，权限为permission.test，指令为test
// 无需在Plugin.yml中注册指令
getCommandManager().register(new TestCommands(), "permission.test", "示例功能", "test");
```

GUI界面方面，UltiTools提供了obliviate-invs的API，方便你快速的开发GUI界面。

UltiTools也提供了Adventure的API。

更多内容请查看 [UltiTools API 文档](https://doc.dev.ultikits.com/)

<details>
<summary>快速开始</summary>

### 快速开始

首先将UltiTools-API依赖加入到你的项目

使用Maven

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>{VERSION}</version>
</dependency>
```

使用Gradle

```groovy
implementation 'com.ultikits:UltiTools-API:{VERSION}'
```

开始之前请在resources文件夹下新建一个plugin.yml文件，内容如下

```yaml
# 插件名称
name: TestPlugin
# 插件版本
version: '${project.version}'
# 插件主类
main: com.test.plugin.MyPlugin
# 插件用到的UltiTools-API版本，例如6.0.0就是600
api-version: 600
# 插件作者
authors: [ wisdomme ]
```
新建一个config文件夹，里面可以按照你的需求放入你的插件配置文件。这些配置文件会被原封不动的放入UltiTools插件的集体配置文件夹中展示给用户。

### 简单开发

新建一个主类继承UltiToolsPlugin，类似传统的Spigot插件，UltiTools插件也需要重写启动和关闭方法。
但是UltiToolsPlugin增加了一个可选的```UltiToolsPlugin#reloadSelf()```方法，用于插件重载时执行。

```java
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // 插件启动时执行
        return true;
    }

    @Override
    public void unregisterSelf() {
        // 插件关闭时执行
    }
    
    @Override
    public void reloadSelf() {
        // 插件重载时执行
    }
}
```
这样就已经完成了一个什么功能都没有的UltiTools插件。然后你可以在```UltiToolsPlugin#registerSelf()```方法中注册你的监听器和指令。

```java
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // 注册一个Test指令，权限为permission.test，指令为test
        // 无需在Plugin.yml中注册指令
        getCommandManager().register(new TestCommands(), "permission.test", "示例功能", "test");
        // 注册监听器
        getListenerManager().register(this, new TestListener());
        return true;
    }
}
```
然后你可以在主类中添加你的配置文件，UltiTools会自动加载配置文件。

```java
public class MyPlugin extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        // 注册一个Test指令，权限为permission.test，指令为test
        // 无需在Plugin.yml中注册指令
        getCommandManager().register(new TestCommands(), "permission.test", "示例功能", "test");
        // 注册监听器
        getListenerManager().register(this, new TestListener());
        // 注册配置文件
        getConfigManager().register(this, new TestConfig("config/config.yml"));
        return true;
    }
}
```
或者你可以重写```UltiToolsPlugin#getAllConfigs()```方法，将所有的配置文件注册放在这里。

```java
@Override
public List<AbstractConfigEntity> getAllConfigs() {
    return Arrays.asList(
            new TestConfig("config/config.yml")
    );
}
```

</details>

<br>
<br>

## WakaTime 统计

<details>
<h3>开发时间线统计</h3>
<summary>点击查看统计数据</summary>
<img alt="wakatime timeline" src="https://wakatime.com/share/@wisdomme/0a9b3a30-f210-4be9-91f2-1b2e94ff403b.svg"/>
<br>
<br>
<img alt="wakatime week" src="https://wakatime.com/share/@wisdomme/bf0d9440-52ee-41b6-9df9-03fae3ae86dc.svg"/>
</details>

<br>
<br>

## 主要贡献者
| 贡献者                                              | 描述                                      |
|--------------------------------------------------|-----------------------------------------|
| [@wisdommen](https://github.com/wisdommen)       | 创始人，UltiKits套件作者                        |
| [@qianmo2233](https://github.com/qianmo2233)     | UltiTools&UltiCore开发者，UltiKits开发文档主要维护者 |
| [@Shpries](https://github.com/Shpries)           | UltiTools开发者，UltiTools使用文档主要维护者         |
| [@DevilJueChen](https://github.com/DevilJueChen) | UltiKits问题&漏洞&建议反馈                      |
| 拾柒                                               | 美工                                      |

## 发现问题？想提建议？
[点击这里提交开启一个Issue！](https://github.com/wisdommen/UltiTools/issues/new/choose)


## 鸣谢

|                                                                                                                           |                        |
|---------------------------------------------------------------------------------------------------------------------------|------------------------|
| ![wakatime](https://img.shields.io/badge/WakaTime-000000?style=for-the-badge&amp;logo=WakaTime&amp;logoColor=white)       | 记录了我们开发途中的每一刻          |
| ![wakatime](https://img.shields.io/badge/IntelliJ_IDEA-000000.svg?style=for-the-badge&logo=intellij-idea&logoColor=white) | 地表最强 Java IDE 助力愉悦开发体验 |
| ![wakatime](https://img.shields.io/badge/ChatGPT-74aa9c?style=for-the-badge&logo=openai&logoColor=white)                  | 帮助解决了许多重复且枯燥的工作        |
| ![wakatime](https://img.shields.io/badge/Spring-6DB33F?style=for-the-badge&logo=spring&logoColor=white)                   | 为插件带来了许多黑科技            |
| ![wakatime](https://img.shields.io/badge/apache_maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)        | 官方构建工具                 |
| ![wakatime](https://img.shields.io/badge/Apache_Spark-FFFFFF?style=for-the-badge&logo=apachespark&logoColor=#E35A16)      | 官方内置 HTTP 服务端          |
