# UltiMail

[![UltiTools-API](https://img.shields.io/badge/UltiTools--API-6.2.0-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.java.com/)

UltiMail 是基于 UltiTools-API 框架开发的游戏内邮件系统插件模块，支持玩家之间发送邮件、附带物品，以及管理员召回离线玩家功能。

## ✨ 功能特性

### 📬 邮件系统
| 命令 | 描述 | 权限 |
|------|------|------|
| `/mail inbox` | 查看收件箱 | `ultimail.use` |
| `/mail send <玩家> <标题>` | 发送邮件 | `ultimail.send` |
| `/mail read <编号>` | 阅读邮件 | `ultimail.use` |
| `/mail delete <编号>` | 删除邮件 | `ultimail.use` |

### 📢 玩家召回功能
| 命令 | 描述 | 权限 |
|------|------|------|
| `/recall [自定义消息]` | 向所有注册玩家发送召回通知 | `ultimail.recall` |
| `/callback [自定义消息]` | 同上（别名） | `ultimail.recall` |

召回功能会：
1. 向所有离线玩家发送游戏内邮件通知
2. 如果启用邮件服务器并且玩家注册了邮箱，还会发送真实电子邮件

## 📦 安装

1. 确保服务器已安装 [UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn) 6.2.0+
2. 将 `UltiMail-1.0.0.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或使用 `/ul reload` 重载插件

**或通过 UPM 安装:**
```
/upm install UltiMail
```

## ⚙️ 配置文件

### 主配置 (`config/mail.yml`)

```yaml
# 基础邮件设置
max-items: 27              # 每封邮件最多附带物品数量
mail-expire-days: 30       # 邮件过期天数（0为永不过期）
notify-on-join: true       # 玩家登录时通知未读邮件
notify-delay: 3            # 登录通知延迟（秒）
max-subject-length: 50     # 邮件标题最大长度
max-content-length: 500    # 邮件内容最大长度
send-cooldown: 10          # 发送邮件冷却时间（秒）

messages:
  new-mail: "&e[邮件] &f你有 &a{COUNT} &f封未读邮件！使用 /mail inbox 查看"
  mail-sent: "&a邮件已发送给 {PLAYER}！"
  mail-received: "&e[邮件] &f你收到了来自 &a{SENDER} &f的新邮件！"

# ========== 召回玩家功能配置 ==========
recall:
  server-name: "Minecraft服务器"   # 服务器名称
  subject: "[{SERVER}] 回归召唤"   # 游戏内召回邮件标题
  content: |
    亲爱的玩家，{SERVER}想念你了！
    
    快回来看看吧，我们期待与你重逢！
    
    发送者: {SENDER}

# ========== 真实邮件发送配置 ==========
email:
  enabled: false            # 是否启用真实邮件发送功能
  smtp-host: "smtp.example.com"
  smtp-port: 587
  smtp-username: ""
  smtp-password: ""
  smtp-from-email: "noreply@example.com"
  smtp-ssl: false
  smtp-starttls: true
  recall-subject: "[{SERVER}] 我们想念你！"
  recall-content: |
    亲爱的 {PLAYER}，
    
    {SERVER} 服务器想念你了！快回来看看吧，我们期待与你重逢！
    
    发送者: {SENDER}
```

## 🔐 权限节点

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultimail.use` | 使用邮件系统 | 玩家 |
| `ultimail.send` | 发送邮件 | 玩家 |
| `ultimail.recall` | 发送召回通知 | OP |
| `ultimail.recall.admin` | 完整召回权限 | OP |

## 📧 真实邮件配置示例

### Gmail SMTP
```yaml
email:
  enabled: true
  smtp-host: "smtp.gmail.com"
  smtp-port: 587
  smtp-username: "your-email@gmail.com"
  smtp-password: "your-app-password"
  smtp-from-email: "your-email@gmail.com"
  smtp-ssl: false
  smtp-starttls: true
```

### QQ 邮箱 SMTP
```yaml
email:
  enabled: true
  smtp-host: "smtp.qq.com"
  smtp-port: 587
  smtp-username: "your-qq@qq.com"
  smtp-password: "your-authorization-code"
  smtp-from-email: "your-qq@qq.com"
  smtp-ssl: false
  smtp-starttls: true
```

> ⚠️ **注意**: Gmail 需要使用 App Password，QQ邮箱需要使用授权码

## 🌍 多语言支持

UltiMail 支持多语言，语言文件位于 `lang/` 目录：
- `lang/zh.json` - 简体中文
- `lang/en.json` - English

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📞 支持

- GitHub Issues: [https://github.com/UltiKits/UltiTools-Reborn/issues](https://github.com/UltiKits/UltiTools-Reborn/issues)
- 文档: [https://doc.ultikits.com](https://doc.ultikits.com)
