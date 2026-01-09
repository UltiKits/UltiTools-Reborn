# EmailService 使用指南

> UltiTools-API 6.2.0+ 邮件服务框架

---

## 概述

`EmailService` 是 UltiTools-API 提供的可选邮件发送服务，允许模块通过 SMTP 协议发送电子邮件。此服务为可选功能，仅在服务器管理员在 `config.yml` 中正确配置 SMTP 后才会启用。

## 配置

在 `plugins/UltiTools/config.yml` 中配置邮件服务：

```yaml
# 邮件服务配置 (Email Service Configuration)
email:
  enable: true  # 设为 true 启用
  smtp:
    host: "smtp.gmail.com"     # SMTP 服务器地址
    port: 587                   # SMTP 端口 (25/465/587)
    username: "your@gmail.com"  # SMTP 用户名
    password: "app-password"    # SMTP 密码或应用专用密码
    ssl: false                  # SSL 加密 (端口 465)
    starttls: true              # STARTTLS 加密 (端口 587)
    connectionTimeout: 10000    # 连接超时 (毫秒)
    readTimeout: 10000          # 读取超时 (毫秒)
  from:
    address: "noreply@example.com"  # 发件人地址
    name: "My Server"               # 发件人名称
  debug: false  # 调试模式
```

### 常用 SMTP 配置

| 服务商 | Host | Port | SSL | STARTTLS |
|--------|------|------|-----|----------|
| Gmail | smtp.gmail.com | 587 | false | true |
| QQ邮箱 | smtp.qq.com | 465 | true | false |
| 163邮箱 | smtp.163.com | 465 | true | false |
| Outlook | smtp.office365.com | 587 | false | true |
| 阿里云企业邮箱 | smtp.mxhichina.com | 465 | true | false |

> **注意**: Gmail 需要开启"应用专用密码"，QQ邮箱需要开启"授权码"。

---

## 在模块中使用

### 1. 获取服务实例

```java
import com.ultikits.ultitools.services.EmailService;

// 通过 IoC 容器获取
EmailService emailService = getContext().getBean(EmailService.class);

// 或从父容器获取 (推荐)
EmailService emailService = UltiTools.getInstance()
    .getDependenceManagers()
    .getContext()
    .getBean(EmailService.class);
```

### 2. 检查服务是否可用 (必须!)

```java
// ⚠️ 必须先检查 isEnabled()!
if (!emailService.isEnabled()) {
    player.sendMessage("邮件服务未配置，请联系服务器管理员");
    return;
}
```

### 3. 发送邮件

#### 发送纯文本邮件

```java
boolean success = emailService.sendEmail(
    "recipient@example.com",
    "邮件主题",
    "邮件内容"
);
```

#### 发送 HTML 邮件

```java
String html = "<h1>标题</h1><p>内容</p>";
boolean success = emailService.sendHtmlEmail(
    "recipient@example.com",
    "邮件主题",
    html
);
```

#### 异步发送 (推荐)

```java
emailService.sendEmailAsync("to@example.com", "主题", "内容")
    .thenAccept(success -> {
        if (success) {
            player.sendMessage("邮件发送成功！");
        } else {
            player.sendMessage("邮件发送失败: " + emailService.getLastError());
        }
    });
```

### 4. 发送验证码邮件

```java
// 生成验证码
String code = emailService.generateVerificationCode(6);  // "123456"

// 发送验证码邮件 (使用内置模板)
boolean success = emailService.sendVerificationCodeEmail(
    "user@example.com",
    code,
    "我的服务器",  // 服务器名称
    5              // 有效期 (分钟)
);

// 异步发送
emailService.sendVerificationCodeEmailAsync("user@example.com", code, "服务器", 5)
    .thenAccept(success -> { /* 处理结果 */ });
```

### 5. 测试 SMTP 连接

```java
if (emailService.testConnection()) {
    logger.info("SMTP 连接成功！");
} else {
    logger.warning("SMTP 连接失败: " + emailService.getLastError());
}
```

---

## 完整示例

### 邮箱验证功能

```java
@Service
public class EmailVerificationService {
    
    private final Map<UUID, String> pendingCodes = new ConcurrentHashMap<>();
    
    public boolean sendVerificationCode(Player player, String email) {
        EmailService emailService = UltiTools.getInstance()
            .getDependenceManagers().getContext()
            .getBean(EmailService.class);
        
        // 必须检查服务是否可用
        if (!emailService.isEnabled()) {
            player.sendMessage(ChatColor.RED + "邮件服务未配置！");
            return false;
        }
        
        // 生成验证码
        String code = emailService.generateVerificationCode(6);
        
        // 异步发送
        emailService.sendVerificationCodeEmailAsync(email, code, "MyServer", 10)
            .thenAccept(success -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (success) {
                        pendingCodes.put(player.getUniqueId(), code);
                        player.sendMessage(ChatColor.GREEN + "验证码已发送到 " + email);
                    } else {
                        player.sendMessage(ChatColor.RED + "发送失败: " + 
                            emailService.getLastError());
                    }
                });
            });
        
        return true;
    }
    
    public boolean verifyCode(Player player, String inputCode) {
        String expectedCode = pendingCodes.get(player.getUniqueId());
        if (expectedCode != null && expectedCode.equals(inputCode)) {
            pendingCodes.remove(player.getUniqueId());
            return true;
        }
        return false;
    }
}
```

---

## API 参考

### EmailService 接口

| 方法 | 描述 |
|------|------|
| `isEnabled()` | 检查服务是否已启用且配置正确 |
| `sendEmail(to, subject, content)` | 发送纯文本邮件 |
| `sendEmail(recipients, subject, content)` | 群发纯文本邮件 |
| `sendHtmlEmail(to, subject, html)` | 发送 HTML 邮件 |
| `sendHtmlEmail(recipients, subject, html)` | 群发 HTML 邮件 |
| `sendEmailAsync(...)` | 异步发送纯文本邮件 |
| `sendHtmlEmailAsync(...)` | 异步发送 HTML 邮件 |
| `generateVerificationCode(length)` | 生成纯数字验证码 |
| `generateAlphanumericCode(length)` | 生成字母数字混合验证码 |
| `sendVerificationCodeEmail(...)` | 使用模板发送验证码邮件 |
| `sendVerificationCodeEmailAsync(...)` | 异步发送验证码邮件 |
| `testConnection()` | 测试 SMTP 连接 |
| `getLastError()` | 获取上次操作的错误信息 |

---

## 注意事项

1. **始终检查 isEnabled()**: 模块必须在使用邮件功能前检查服务是否可用
2. **异步发送**: 推荐使用异步方法避免阻塞主线程
3. **错误处理**: 使用 `getLastError()` 获取详细错误信息
4. **授权码**: 部分邮件服务商需要使用授权码而非密码
5. **防滥用**: 建议实现发送频率限制，防止滥用

---

## 常见问题

### Q: 服务未启用怎么办？

检查 `config.yml` 中：
- `email.enable` 是否为 `true`
- SMTP 配置是否正确填写
- 用户名和密码是否为空

### Q: 发送失败如何排查？

1. 开启 `email.debug: true` 查看详细日志
2. 使用 `testConnection()` 测试连接
3. 检查 `getLastError()` 返回的错误信息

### Q: 如何支持多种邮件模板？

可以扩展 `EmailService` 接口或在模块内实现自定义模板：

```java
public String buildCustomTemplate(String title, String content) {
    return "<!DOCTYPE html><html>..."  + title + "..." + content + "...</html>";
}

emailService.sendHtmlEmail(to, subject, buildCustomTemplate("标题", "内容"));
```
