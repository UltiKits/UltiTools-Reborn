# UltiMail

[![UltiTools-API](https://img.shields.io/badge/UltiTools--API-6.2.0-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.java.com/)

UltiMail 是基于 UltiTools-API 框架开发的游戏内邮件系统插件模块，支持玩家之间发送邮件、附带物品，以及管理员群发功能。

## ✨ 功能特性

- 📬 **完整的邮件系统** - 发送、接收、阅读、删除邮件
- 📦 **物品附件支持** - 可以将物品作为附件发送给其他玩家
- 🖥️ **图形化界面** - 分页式收件箱/发件箱 GUI
- 📢 **群发邮件** - 管理员可向全服玩家群发邮件（支持多物品附件）
- 🔔 **登录通知** - 玩家上线时提示未读邮件数量（可点击打开收件箱）
- 🗑️ **批量删除** - 一键删除所有邮件或已读邮件
- ⚙️ **命令执行 API** - 支持邮件携带命令（领取时自动执行）
- 🌐 **多语言支持** - 内置中英文语言包

## 📋 命令

### 玩家命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/mail inbox` | `ultimail.use` | 查看收件箱列表 |
| `/mail read` | `ultimail.use` | 打开收件箱 GUI |
| `/mail read <编号>` | `ultimail.use` | 阅读指定邮件 |
| `/mail sent` | `ultimail.use` | 查看发件箱列表 |
| `/mail sentgui` | `ultimail.use` | 打开发件箱 GUI |
| `/mail claim <编号>` | `ultimail.use` | 领取邮件附件 |
| `/mail delete <编号>` | `ultimail.use` | 删除指定邮件 |
| `/mail delall` | `ultimail.use` | 删除所有邮件 |
| `/mail delread` | `ultimail.use` | 删除所有已读邮件 |
| `/sendmail <玩家> <标题>` | `ultimail.send` | 发送邮件（进入内容输入模式） |
| `/sendmail <玩家> <标题> attach` | `ultimail.send` | 发送带附件的邮件 |

### 管理员命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/mail sendall <内容>` | `ultimail.admin.sendall` | 向全服玩家群发邮件 |
| `/mail sendall <内容> items` | `ultimail.admin.sendall` | 群发带附件邮件（打开选择 GUI） |

## 🔐 权限节点

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultimail.use` | 使用邮件系统 | 玩家 |
| `ultimail.send` | 发送邮件 | 玩家 |
| `ultimail.admin.sendall` | 群发邮件 | OP |
| `ultimail.admin.multiattach` | 发送邮件时使用多物品附件 GUI | OP |

## 📦 安装

1. 确保服务器已安装 [UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn) 6.2.0+
2. 将 `UltiMail-1.1.0.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或使用 `/ul reload` 重载插件

**或通过 UPM 安装:**
```
/upm install UltiMail
```

## ⚙️ 配置文件

### 主配置 (`config.yml`)

```yaml
# 是否在玩家登录时通知未读邮件
notify_on_join: true

# 登录通知延迟（秒）
notify_delay: 3

# 新邮件通知消息模板
new_mail_message: "&e[邮件] &f你有 &a{COUNT} &f封未读邮件！"
```

## 🖼️ GUI 预览

### 收件箱 GUI

- 未读邮件显示为 **绿色羊毛** ✉️
- 已读邮件显示为 **灰色羊毛** 📭
- 有附件的邮件在 Lore 中显示物品数量
- 点击邮件可阅读并领取附件

### 发件箱 GUI

- 所有已发送邮件按时间排序
- 显示接收者、标题和发送时间

### 附件选择 GUI（管理员）

- 管理员发送邮件时可打开多物品选择界面
- 45 格物品槽位
- 确认/取消按钮

## 🔌 API 使用

UltiMail 提供了完整的 API 供其他插件调用。

### 获取 MailService

```java
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.UltiTools;

// 通过容器获取
MailService mailService = UltiTools.getInstance()
    .getContext()
    .getBean(MailService.class);
```

### 发送邮件

```java
// 发送纯文本邮件
boolean success = mailService.sendMail(
    senderPlayer,           // 发送者
    "targetPlayer",         // 接收者名称
    "邮件标题",             // 标题
    "邮件内容",             // 内容
    null                    // 无附件
);

// 发送带物品附件的邮件
ItemStack[] items = new ItemStack[] { itemStack1, itemStack2 };
mailService.sendMail(sender, "target", "标题", "内容", items);

// 发送带命令的邮件（领取时自动执行）
List<String> commands = Arrays.asList(
    "give {player} diamond 1",
    "eco give {player} 100"
);
mailService.sendMail(sender, "target", "标题", "内容", items, commands);
```

### 群发邮件

```java
// 向所有玩家群发（异步执行）
mailService.sendToAll(
    senderPlayer,
    "系统公告",
    "服务器将于今晚维护",
    null,   // 附件
    null    // 命令
);
```

### 查询邮件

```java
// 获取未读邮件数
int count = mailService.getUnreadCount(playerUUID);

// 获取收件箱
List<MailData> inbox = mailService.getInbox(playerUUID);

// 获取发件箱
List<MailData> sentbox = mailService.getSentMails(playerUUID);
```

### 命令执行模式

邮件支持携带命令，在玩家领取附件时自动执行：

```java
// 命令中的 {player} 会被替换为领取者名称
List<String> commands = Arrays.asList(
    "give {player} diamond 1",      // 控制台执行
    "eco give {player} 100"
);

// 通过 API 发送
mailService.sendMail(sender, receiver, subject, content, items, commands);

// 手动执行命令（如果需要）
mailService.executeMailCommands(mail, player);
```

### MailData 实体

```java
public class MailData extends AbstractDataEntity {
    private UUID sender;        // 发送者 UUID
    private UUID receiver;      // 接收者 UUID
    private String senderName;  // 发送者名称
    private String receiverName;// 接收者名称
    private String subject;     // 标题
    private String content;     // 内容
    private String items;       // Base64 编码的物品数组
    private String commands;    // JSON 格式的命令列表
    private boolean read;       // 是否已读
    private boolean claimed;    // 附件是否已领取
    private boolean commandsExecuted; // 命令是否已执行
    private Date createTime;    // 创建时间
    
    // 辅助方法
    boolean hasItems();         // 是否有附件
    boolean hasCommands();      // 是否有命令
}
```

## 🔄 与旧版 UltiTools 的区别

| 功能 | UltiTools 5.x | UltiMail 6.x |
|------|---------------|--------------|
| 命令系统 | 手动注册 | 注解驱动 |
| 数据存储 | 文件/MySQL 混合 | DataOperator ORM |
| GUI | InventoryManager | BasePaginationPage |
| 群发邮件 | ❌ | ✅ |
| 批量删除 | ❌ | ✅ |
| 命令执行 | ❌ | ✅ API |
| 可点击通知 | ❌ | ✅ Adventure API |
| 多物品附件 | ❌ | ✅ 管理员 |

## 📁 文件结构

```
UltiMail/
├── src/main/java/com/ultikits/plugins/mail/
│   ├── UltiMail.java              # 主类
│   ├── commands/
│   │   ├── MailCommand.java       # /mail 命令
│   │   └── SendMailCommand.java   # /sendmail 命令
│   ├── config/
│   │   └── MailConfig.java        # 配置实体
│   ├── entity/
│   │   └── MailData.java          # 邮件数据实体
│   ├── gui/
│   │   ├── MailboxGUI.java        # 收件箱 GUI
│   │   ├── SentboxGUI.java        # 发件箱 GUI
│   │   └── AttachmentSelectorPage.java # 附件选择 GUI
│   ├── listener/
│   │   ├── MailNotifyListener.java    # 登录通知
│   │   └── AttachmentGUIListener.java # GUI 监听
│   └── service/
│       └── MailService.java       # 邮件服务
└── src/main/resources/
    ├── config.yml                 # 默认配置
    └── lang/
        ├── zh.yml                 # 中文
        └── en.yml                 # 英文
```

## 🛠️ 开发依赖

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>6.2.0</version>
    <scope>provided</scope>
</dependency>
```

## 📝 更新日志

### v1.1.0
- ✅ 新增收件箱/发件箱 GUI
- ✅ 新增群发邮件功能
- ✅ 新增批量删除命令 (delall, delread)
- ✅ 新增可点击通知消息
- ✅ 新增命令执行 API
- ✅ 管理员支持多物品附件
- ✅ 完善 i18n 国际化

### v1.0.0
- 🎉 初始版本
- 基础邮件收发功能
- 物品附件支持
- 登录通知

## 🌍 多语言支持

UltiMail 支持多语言，语言文件位于 `lang/` 目录：
- `lang/zh.yml` - 简体中文
- `lang/en.yml` - English

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📞 支持

- GitHub Issues: [https://github.com/UltiKits/UltiTools-Reborn/issues](https://github.com/UltiKits/UltiTools-Reborn/issues)
- 文档: [https://doc.ultikits.com](https://doc.ultikits.com)
