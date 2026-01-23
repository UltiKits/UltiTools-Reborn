# UltiLogin

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

UltiLogin 是 UltiTools 插件系统的登录验证模块，为 Minecraft 服务器提供安全的玩家认证系统。

## 功能特性

### 核心功能

- ✅ **玩家注册/登录** - 支持命令和 GUI 两种模式
- ✅ **会话保持** - 同一 IP 短期内免重复登录
- ✅ **密码安全** - SHA-256 + Salt 加密存储
- ✅ **登录保护** - 未登录时限制所有操作
- ✅ **超时踢出** - 登录超时自动踢出

### 安全特性

- ✅ **登录失败保护** - 失败次数限制 + IP/UUID 临时封禁
- ✅ **IP 注册限制** - 同一 IP 最大注册账户数限制
- ✅ **失明效果** - 未登录时添加失明效果防止偷窥

### 管理功能

- ✅ **管理员命令** - 重置密码、强制登录、删除账号
- ✅ **账号查询** - 查看玩家注册信息

### GUI 模式 (可选)

- ✅ **数字键盘界面** - 1-9 数字按钮输入密码
- ✅ **自动密码验证** - 输入完成自动提交
- ✅ **两步注册确认** - 设置密码后需再次确认

## 命令

### 玩家命令

| 命令 | 别名 | 权限 | 描述 |
|------|------|------|------|
| `/login <密码>` | `/l` | 无 | 登录账号 |
| `/register <密码> <确认>` | `/reg` | 无 | 注册账号 |
| `/changepassword <旧密码> <新密码> <确认>` | `/changepw`, `/cpw` | `ultilogin.changepassword` | 修改密码 |

### 管理员命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/logadmin reset <玩家> [密码]` | `ultilogin.admin` | 重置玩家密码 |
| `/logadmin forcelogin <玩家>` | `ultilogin.admin` | 强制登录玩家 |
| `/logadmin unregister <玩家>` | `ultilogin.admin` | 删除玩家账号 |
| `/logadmin info <玩家>` | `ultilogin.admin` | 查看玩家账号信息 |

## 配置文件

配置文件位于 `plugins/UltiTools/config/login.yml`

```yaml
# ==================== 基础设置 ====================
login-timeout: 60           # 登录超时时间（秒）
session-enabled: true       # 启用会话功能
session-timeout: 30         # 会话过期时间（分钟）
max-register-per-ip: 3      # 同一IP最大注册数（0为不限制）

# ==================== GUI 模式设置 ====================
gui-mode:
  enabled: false            # 启用GUI登录模式
  password-length: 4        # GUI模式密码位数（1-9数字）
  title-login: "&6请输入密码"
  title-register: "&6请设置密码"
  title-confirm: "&6请再次输入密码"

# ==================== 命令模式密码设置 ====================
password:
  min-length: 6             # 密码最小长度
  max-length: 32            # 密码最大长度

# ==================== 登录安全保护 ====================
security:
  max-login-attempts: 5     # 最大登录失败次数（0为不限制）
  lockout-duration: 900     # 封禁时长（秒）
  lockout-type: IP          # 封禁类型：IP / UUID / BOTH

# ==================== 位置设置 ====================
spawn-location:
  enabled: false            # 未登录时传送到指定位置
  world: world
  x: 0
  y: 64
  z: 0

# ==================== 其他设置 ====================
allowed-commands:           # 未登录时允许的命令
  - login
  - l
  - register
  - reg
blind-effect: true          # 未登录时失明效果
```

## 权限节点

| 权限 | 默认 | 描述 |
|------|------|------|
| `ultilogin.changepassword` | true | 允许修改密码 |
| `ultilogin.admin` | op | 管理员命令权限 |

## 数据存储

账号数据存储在 UltiTools 配置的数据存储中（JSON/SQLite/MySQL），包含以下字段：

| 字段 | 类型 | 描述 |
|------|------|------|
| `player_uuid` | VARCHAR | 玩家 UUID |
| `player_name` | VARCHAR | 玩家名称 |
| `password_hash` | VARCHAR(128) | 密码哈希值 |
| `salt` | VARCHAR(32) | 密码盐值 |
| `last_ip` | VARCHAR(45) | 最后登录 IP |
| `last_login` | BIGINT | 最后登录时间戳 |
| `register_time` | BIGINT | 注册时间戳 |
| `register_ip` | VARCHAR(45) | 注册 IP |
| `email` | VARCHAR(100) | 邮箱（预留） |
| `email_verified` | BOOLEAN | 邮箱验证状态 |
| `login_count` | INT | 登录次数 |
| `failed_attempts` | INT | 失败次数 |
| `last_failed_time` | BIGINT | 最后失败时间 |

## 登录模式说明

### 命令模式（默认）
- 玩家加入后显示文本提示
- 通过 `/login` 和 `/register` 命令操作
- 密码长度可配置（默认 6-32 字符）
- 支持任意字符作为密码

### GUI 模式（可选）

- 玩家加入后自动弹出 GUI 界面
- 通过点击数字按钮 (1-9) 输入密码
- 密码为固定位数的纯数字（默认 4 位）
- 输入完成后自动提交
- 关闭 GUI 后会自动重新打开

## 安全机制

### 密码加密
使用 SHA-256 算法 + 随机 Salt 进行密码哈希：
1. 每个账号生成唯一的 16 字节随机 Salt
2. 将密码与 Salt 拼接后进行 SHA-256 哈希
3. 哈希结果以 Base64 编码存储

### 登录失败保护

- 连续失败达到阈值后触发封禁
- 支持按 IP、UUID 或两者同时封禁
- 封禁时长可配置（默认 15 分钟）
- 成功登录后清除失败记录

### 会话保持

- 基于 IP + UUID 组合标识会话
- 会话有效期内无需重复登录
- 适用于短时间内重连的情况

## 依赖

- **UltiTools-API** >= 6.2.0

## 版本历史

### v1.1.0
- 新增 GUI 数字键盘登录模式
- 新增登录失败保护机制（IP/UUID 封禁）
- 新增管理员命令（reset/forcelogin/unregister/info）
- 优化密码验证逻辑（支持 GUI/命令模式切换）
- 扩展语言文件支持

### v1.0.0
- 基础登录/注册功能
- 会话保持功能
- SHA-256 + Salt 密码加密
- 登录超时踢出
- 登录前操作限制

## 未来计划

- [ ] 邮箱绑定与密码找回（依赖 UltiTools-API 提供的 EmailService）
- [ ] 二次验证 (2FA)
- [ ] 登录日志记录
- [ ] Web API 接口
