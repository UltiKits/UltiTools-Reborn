# WebSocket 集成

本文档详细介绍 UltiTools 与 UltiPanel 的 WebSocket 集成功能。

---

## 目录

- [概述](#概述)
- [架构设计](#架构设计)
- [消息类型](#消息类型)
- [管理器说明](#管理器说明)
- [配置说明](#配置说明)
- [开发扩展](#开发扩展)

---

## 概述

UltiTools 通过 WebSocket 与 UltiPanel 管理面板集成，提供：

| 功能 | 描述 |
|------|------|
| **服务器监控** | 实时 TPS、内存、玩家数据 |
| **远程命令** | 从面板执行服务器命令 |
| **日志流** | 实时查看服务器日志 |
| **文件操作** | 远程读写服务器文件 |
| **玩家事件** | 玩家上下线通知 |

---

## 架构设计

```
┌──────────────────────────────────────────────────────────────┐
│                     Minecraft Server                          │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                      UltiTools                          │  │
│  │                                                         │  │
│  │  ┌─────────────────┐  ┌─────────────────┐             │  │
│  │  │ServerMonitorMgr │  │ CommandExecMgr  │             │  │
│  │  │  · TPS          │  │  · 执行命令      │             │  │
│  │  │  · 内存         │  │  · 返回结果      │             │  │
│  │  │  · 玩家数       │  │                 │             │  │
│  │  └────────┬────────┘  └────────┬────────┘             │  │
│  │           │                    │                       │  │
│  │  ┌────────┴────────┐  ┌───────┴────────┐             │  │
│  │  │ LogStreamMgr    │  │FileOperationMgr │             │  │
│  │  │  · 日志捕获      │  │  · 读写文件      │             │  │
│  │  │  · 批量发送      │  │  · 列出目录      │             │  │
│  │  └────────┬────────┘  └────────┬────────┘             │  │
│  │           │                    │                       │  │
│  │           └───────────┬────────┘                       │  │
│  │                       │                                │  │
│  │           ┌───────────▼───────────┐                   │  │
│  │           │UltiPanelWebSocketClient│                   │  │
│  │           │  · 连接管理            │                   │  │
│  │           │  · 心跳维持            │                   │  │
│  │           │  · 消息收发            │                   │  │
│  │           └───────────┬───────────┘                   │  │
│  └───────────────────────┼────────────────────────────────┘  │
└──────────────────────────┼───────────────────────────────────┘
                           │
                      WebSocket (wss://)
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                  UltiPanel API Worker                         │
│                (Cloudflare Workers)                           │
│                                                               │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │  WebSocket 路由   │  │   KV 状态缓存    │                 │
│  └────────┬─────────┘  └─────────────────┘                 │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────────────────────────┐                   │
│  │          消息分发                      │                   │
│  │  · server_status → 状态缓存           │                   │
│  │  · log_stream → 日志存储              │                   │
│  │  · execute_command → 命令队列         │                   │
│  └──────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────┘
                           │
                      HTTP/WebSocket
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                  UltiPanel Frontend                           │
│                     (Vue 3)                                   │
│                                                               │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │  服务器状态    │  │   控制台       │  │   文件管理    │   │
│  │  仪表板       │  │   日志查看     │  │   配置编辑    │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

---

## 消息类型

### 系统消息

#### ping/pong (心跳)

```json
// 客户端发送
{
  "type": "ping",
  "timestamp": 1704067200000
}

// 服务器响应
{
  "type": "pong",
  "timestamp": 1704067200000
}
```

#### subscribe (订阅)

```json
{
  "type": "subscribe",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "timestamp": 1704067200000
}
```

### 监控消息

#### server_status (服务器状态)

```json
{
  "type": "server_status",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "timestamp": 1704067200000,
  "data": {
    "online": true,
    "players": {
      "online": 10,
      "max": 100,
      "list": ["Steve", "Alex"]
    },
    "tps": {
      "1m": 19.8,
      "5m": 19.5,
      "15m": 19.2
    },
    "memory": {
      "used": 2048,
      "max": 4096,
      "free": 2048
    },
    "cpu": {
      "usage": 45.5
    },
    "uptime": 86400000
  }
}
```

#### plugin_list (插件列表)

```json
{
  "type": "plugin_list",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "timestamp": 1704067200000,
  "data": {
    "plugins": [
      {
        "name": "UltiTools",
        "version": "6.2.0",
        "enabled": true,
        "description": "UltiTools API"
      },
      {
        "name": "Vault",
        "version": "1.7.3",
        "enabled": true,
        "description": "Economy API"
      }
    ]
  }
}
```

#### log_stream (日志流)

```json
{
  "type": "log_stream",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "timestamp": 1704067200000,
  "data": {
    "level": "INFO",
    "logger": "com.example.MyPlugin",
    "message": "Plugin enabled successfully",
    "timestamp": 1704067200000
  }
}

// 批量日志
{
  "type": "log_batch",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "timestamp": 1704067200000,
  "data": {
    "logs": [
      {"level": "INFO", "message": "Log 1", "timestamp": 1704067200000},
      {"level": "WARN", "message": "Log 2", "timestamp": 1704067200001}
    ]
  }
}
```

### 控制消息

#### execute_command (执行命令)

```json
// 请求
{
  "type": "execute_command",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "requestId": "req-123",
  "data": {
    "command": "say Hello World",
    "executor": "console",
    "async": false
  }
}

// 响应
{
  "type": "command_result",
  "serverId": "e502c24dd77c439791f75217d7243aab",
  "requestId": "req-123",
  "data": {
    "success": true,
    "output": "Command executed successfully",
    "executionTime": 15
  }
}
```

#### file_operation (文件操作)

```json
// 读取文件
{
  "type": "file_operation",
  "operation": "read",
  "path": "plugins/UltiTools/config.yml",
  "requestId": "req-456"
}

// 写入文件
{
  "type": "file_operation",
  "operation": "write",
  "path": "plugins/UltiTools/config.yml",
  "content": "yaml content...",
  "requestId": "req-789"
}

// 列出目录
{
  "type": "file_operation",
  "operation": "list",
  "path": "plugins/",
  "requestId": "req-101"
}
```

---

## 管理器说明

### ServerMonitorManager

负责收集和发送服务器状态信息：

```java
public class ServerMonitorManager {
    
    // 开始监控（自动定时发送状态）
    public void startMonitoring();
    
    // 停止监控
    public void stopMonitoring();
    
    // 手动发送状态
    public void sendServerStatus();
    
    // 发送插件列表
    public void sendPluginList();
}
```

**发送频率**:
- 服务器状态: 每 30 秒
- 插件列表: 每 60 秒
- 性能数据: 每 120 秒

### CommandExecutionManager

处理远程命令执行：

```java
public class CommandExecutionManager {
    
    // 处理命令执行请求
    public void handleCommandRequest(JSONObject message);
    
    // 执行命令并返回结果
    private CommandResult executeCommand(String command, String executor);
}
```

**安全限制**:
- 需要有效的认证 Token
- 命令白名单/黑名单机制
- 执行日志记录

### LogStreamManager

管理日志实时传输：

```java
public class LogStreamManager {
    
    // 获取单例
    public static LogStreamManager getInstance();
    
    // 初始化
    public void initialize(UltiPanelWebSocketClient client);
    
    // 开始日志流
    public void startLogStream(String clientId, String level);
    
    // 停止日志流
    public void stopLogStream(String clientId);
    
    // 配置批量发送
    public void setBatchEnabled(boolean enabled);
    public void setBatchSize(int size);
    public void setBatchInterval(long interval);
}
```

**特性**:
- 日志级别过滤
- 批量发送优化
- 排除特定 Logger

### FileOperationManager

处理远程文件操作：

```java
public class FileOperationManager {
    
    // 读取文件
    public String readFile(String path);
    
    // 写入文件
    public void writeFile(String path, String content);
    
    // 列出目录
    public List<FileInfo> listDirectory(String path);
    
    // 删除文件
    public void deleteFile(String path);
}
```

**安全限制**:
- 路径白名单
- 文件大小限制
- 敏感文件保护

---

## 配置说明

在 `config.yml` 中配置 WebSocket 连接：

```yaml
# UltiPanel 配置
ultipanel:
  # 服务器ID（从 UltiPanel 后台获取）
  server-id: "e502c24dd77c439791f75217d7243aab"
  
  # WebSocket 连接配置
  websocket:
    url: "wss://api.ultikits.com/ws"
    reconnect-interval: 30        # 重连间隔（秒）
    max-reconnect-attempts: 10    # 最大重连次数
  
  # 日志传输配置
  logging:
    enabled: true
    
    # 传输的日志级别
    levels:
      - "info"
      - "warning"
      - "error"
      # - "debug"  # 生产环境不建议启用
    
    # 排除的 Logger（减少日志量）
    excluded-loggers:
      - "com.mojang.authlib"
      - "net.minecraft.network"
      - "org.apache.http"
      - "com.zaxxer.hikari"
    
    # 批量发送配置
    batch:
      enabled: true
      size: 10              # 每批最大条数
      interval: 5000        # 发送间隔（毫秒）
  
  # 性能配置
  performance:
    enable-optimization: true
    max-queue-size: 1000    # 最大队列大小
    minimum-level: "INFO"   # 最小日志级别

# 账户配置（用于认证）
account:
  username: "your-username"
  password: "your-password"

# Web 编辑器（可选）
web-editor:
  enable: true
```

---

## 开发扩展

### 自定义消息处理

```java
public class CustomMessageHandler {
    
    private final UltiPanelWebSocketClient client;
    
    public CustomMessageHandler() {
        this.client = getWebSocketClient();
        
        // 设置消息处理器
        client.setMessageHandler(this::handleMessage);
    }
    
    private void handleMessage(JSONObject message) {
        String type = message.getString("type");
        
        switch (type) {
            case "custom_action":
                handleCustomAction(message);
                break;
            // 其他消息类型...
        }
    }
    
    private void handleCustomAction(JSONObject message) {
        // 处理自定义消息
        String action = message.getJSONObject("data").getString("action");
        
        // 发送响应
        JSONObject response = new JSONObject();
        response.put("type", "custom_action_result");
        response.put("data", createResultData(action));
        client.sendMessage(response);
    }
}
```

### 发送自定义消息

```java
// 获取 WebSocket 客户端
UltiPanelWebSocketClient client = UltiTools.getInstance()
    .getServerMonitorManager()
    .getWebSocketClient();

// 构建消息
JSONObject message = new JSONObject();
message.put("type", "custom_event");
message.put("serverId", serverId);
message.put("timestamp", System.currentTimeMillis());

JSONObject data = new JSONObject();
data.put("eventName", "player_achievement");
data.put("player", player.getName());
data.put("achievement", achievementName);
message.put("data", data);

// 发送
client.sendMessage(message);
```

### 监听连接事件

```java
client.setOnConnectHandler(() -> {
    // 连接建立时
    logger.info("WebSocket 已连接");
    
    // 订阅服务器
    client.subscribeToServer(serverId);
});

client.setOnDisconnectHandler(() -> {
    // 连接断开时
    logger.warning("WebSocket 已断开");
});

client.setOnErrorHandler(error -> {
    // 发生错误时
    logger.severe("WebSocket 错误: " + error);
});
```

---

## 安全注意事项

### 认证

1. **Token 认证**: 所有连接需要有效的 Bearer Token
2. **服务器验证**: 验证 serverId 归属
3. **Token 刷新**: 自动刷新过期 Token

### 权限控制

1. **命令白名单**: 限制可远程执行的命令
2. **文件访问**: 限制可访问的文件路径
3. **操作审计**: 记录所有远程操作

### 敏感数据

1. **配置加密**: 敏感配置项加密存储
2. **日志脱敏**: 过滤敏感信息
3. **传输加密**: 使用 WSS (WebSocket Secure)

---

> **下一步**: 阅读 [快速入门教程](../tutorials/QUICK_START.md) 开始开发
