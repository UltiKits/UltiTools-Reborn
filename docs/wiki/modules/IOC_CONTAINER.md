# IoC 容器 - SimpleContainer

本文档详细介绍 UltiTools-API 的依赖注入容器 `SimpleContainer`。

---

## 目录

- [概述](#概述)
- [核心概念](#核心概念)
- [注解说明](#注解说明)
- [使用方式](#使用方式)
- [生命周期钩子](#生命周期钩子)
- [高级特性](#高级特性)

---

## 概述

`SimpleContainer` 是 UltiTools 框架的核心组件，提供类似 Spring 的依赖注入功能，但更加轻量和适配 Bukkit 环境。

**核心功能**:

- Bean 注册与管理
- 自动依赖注入（`@Autowired`）
- 组件扫描（`@ComponentScan`）
- 生命周期管理（`@PostConstruct` / `@PreDestroy`）
- 作用域支持（Singleton / Prototype）

---

## 核心概念

### Bean

Bean 是由容器管理的对象实例。通过以下方式定义：

1. **注解声明**: 使用 `@Service`、`@Component` 等注解
2. **手动注册**: 调用 `registerSingleton()` 方法

### 作用域

| 作用域 | 描述 |
|--------|------|
| `SINGLETON` | 单例模式（默认），整个容器生命周期内只有一个实例 |
| `PROTOTYPE` | 原型模式，每次 `getBean()` 创建新实例 |

### 依赖注入

容器自动解析和注入 Bean 之间的依赖关系：

```java
@Service
public class MyService {
    @Autowired
    private AnotherService anotherService; // 自动注入
}
```

---

## 注解说明

### @Service

标记服务类，被容器自动管理：

```java
@Service
public class PlayerService {
    public void doSomething() {
        // 业务逻辑
    }
}

// 带名称
@Service("myPlayerService")
public class PlayerService {
    // ...
}
```

### @Component

通用组件注解，与 `@Service` 功能相同：

```java
@Component
public class MyComponent {
    // ...
}
```

### @Autowired

自动注入依赖：

```java
@Service
public class GameService {
    
    // 字段注入
    @Autowired
    private PlayerService playerService;
    
    // 可选依赖
    @Autowired(required = false)
    private OptionalService optionalService;
}
```

**注入位置**:

- 字段（推荐）
- 构造函数
- Setter 方法

### @ComponentScan

指定组件扫描路径：

```java
@UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
public class MyPlugin extends UltiToolsPlugin {
    // 自动扫描 com.example.myplugin 包下的所有组件
}
```

### @PostConstruct

Bean 创建后调用的初始化方法：

```java
@Service
public class DatabaseService {
    
    @PostConstruct
    public void init() {
        // 初始化数据库连接
        connectToDatabase();
    }
}
```

### @PreDestroy

Bean 销毁前调用的清理方法：

```java
@Service
public class CacheService {
    
    @PreDestroy
    public void cleanup() {
        // 清理缓存
        cache.clear();
    }
}
```

---

## 使用方式

### 1. 获取 Bean

**通过类型获取**:

```java
// 在 UltiToolsPlugin 子类中
PlayerService service = getContext().getBean(PlayerService.class);

// 在任意位置（需要有 context 引用）
PlayerService service = context.getBean(PlayerService.class);
```

**通过名称获取**:

```java
Object bean = context.getBean("playerService");
PlayerService service = context.getBean("playerService", PlayerService.class);
```

### 2. 手动注册 Bean

```java
// 注册单例
context.registerSingleton("myBean", new MyBean());

// 注册类型映射
context.registerType(MyInterface.class, new MyImplementation());

// 注册供应商（懒加载）
context.registerSupplier("lazyBean", () -> new ExpensiveBean());
```

### 3. 在命令中使用

```java
@CmdExecutor(alias = {"test"})
public class TestCommand extends AbstractCommandExecutor {
    
    @Autowired
    private PlayerService playerService;
    
    @CmdMapping(format = "check <player>")
    public void checkPlayer(@CmdSender Player sender, @CmdParam("player") String name) {
        playerService.checkPlayer(name);
    }
}
```

### 4. 在监听器中使用

```java
@EventListener
public class MyListener implements Listener {
    
    @Autowired
    private GameService gameService;
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        gameService.handleJoin(event.getPlayer());
    }
}
```

---

## 生命周期钩子

### 执行顺序

```
Bean 类发现 (@Component/@Service)
         │
         ▼
    构造函数调用
         │
         ▼
   依赖注入 (@Autowired)
         │
         ▼
  @PostConstruct 方法调用
         │
         ▼
      Bean 可用
         │
         ▼
    容器关闭时
         │
         ▼
  @PreDestroy 方法调用
```

### 示例

```java
@Service
public class LifecycleDemo {
    
    @Autowired
    private ConfigService configService;
    
    private Connection connection;
    
    public LifecycleDemo() {
        System.out.println("1. 构造函数调用");
    }
    
    @PostConstruct
    public void init() {
        System.out.println("2. PostConstruct - 依赖已注入");
        // 此时 configService 已可用
        String url = configService.getDatabaseUrl();
        this.connection = createConnection(url);
    }
    
    @PreDestroy
    public void destroy() {
        System.out.println("3. PreDestroy - 清理资源");
        if (connection != null) {
            connection.close();
        }
    }
}
```

---

## 高级特性

### 1. 循环依赖检测

容器自动检测循环依赖并抛出异常：

```java
@Service
public class ServiceA {
    @Autowired
    private ServiceB serviceB; // A 依赖 B
}

@Service  
public class ServiceB {
    @Autowired
    private ServiceA serviceA; // B 依赖 A - 循环依赖！
}
// 抛出: RuntimeException("Circular dependency detected...")
```

**解决方案**:

1. 重构代码，消除循环依赖
2. 使用 Setter 注入而非字段注入
3. 引入中间服务

### 2. 按类型获取多个 Bean

```java
// 获取所有实现某接口的 Bean 名称
String[] names = context.getBeanNamesForType(MyInterface.class);

// 遍历处理
for (String name : names) {
    MyInterface bean = context.getBean(name, MyInterface.class);
    bean.doSomething();
}
```

### 3. 父子容器

支持容器层级，子容器可访问父容器的 Bean：

```java
SimpleContainer parent = new SimpleContainer();
parent.registerSingleton("sharedService", new SharedService());

SimpleContainer child = new SimpleContainer(parent);
// child 可以访问 parent 中的 sharedService
SharedService service = child.getBean(SharedService.class);
```

### 4. 自定义 ClassLoader

```java
SimpleContainer container = new SimpleContainer();
container.setClassLoader(customClassLoader);
```

### 5. BeanPostProcessor

实现 Bean 后处理器以自定义 Bean 初始化：

```java
public class LoggingPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        System.out.println("Before init: " + beanName);
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        System.out.println("After init: " + beanName);
        return bean;
    }
}

// 注册
container.addBeanPostProcessor(new LoggingPostProcessor());
```

### 6. AOP 代理集成

框架使用 `AopProxyBeanPostProcessor` 为标注了 `@Transactional` 或 `@ExceptionCatch` 的 Bean 创建 CGLIB 代理：

```java
// AOP 后处理器自动注册
AopProxyBeanPostProcessor aopProcessor = new AopProxyBeanPostProcessor();

// 添加事务拦截器
aopProcessor.addAdvisor(
    AopAdvisor.forAnnotation(Transactional.class, txInterceptor, 100)
);

// 添加异常处理拦截器
aopProcessor.addAdvisor(
    AopAdvisor.forAnnotation(ExceptionCatch.class, exInterceptor, 200)
);

container.addBeanPostProcessor(aopProcessor);
```

**代理创建流程**:
```
Bean 初始化完成
       │
       ▼
AopProxyBeanPostProcessor.postProcessAfterInitialization()
       │
       ├─→ 检查是否为 final 类
       │         │
       │         └─→ 是 → 跳过代理（记录警告）
       │
       ├─→ 扫描所有方法
       │
       ├─→ 匹配 AopAdvisor
       │         │
       │         └─→ 无匹配 → 返回原始 Bean
       │
       └─→ 有匹配 → 创建 CGLIB 代理 → 返回代理 Bean
```

> **详细信息**: 阅读 [AOP 系统](./AOP_SYSTEM.md) 了解完整的 AOP 和事务管理功能

---

## 最佳实践

### 推荐做法

1. **使用构造函数注入关键依赖**
   ```java
   @Service
   public class MyService {
       private final RequiredDependency dep;
       
       @Autowired
       public MyService(RequiredDependency dep) {
           this.dep = dep;
       }
   }
   ```

2. **合理使用作用域**
   - 无状态服务：`SINGLETON`
   - 有状态对象：`PROTOTYPE`

3. **避免在构造函数中使用注入的依赖**
   - 依赖在构造函数执行后才注入
   - 使用 `@PostConstruct` 进行初始化

### 避免做法

1. **避免循环依赖**
2. **避免过度使用字段注入**（不利于测试）
3. **避免在静态方法中访问容器**

---

## 与 Spring 的差异

| 特性 | SimpleContainer | Spring |
|------|-----------------|--------|
| 作用域 | Singleton/Prototype | 更多作用域 |
| AOP | ✅ 基于 CGLIB | ✅ JDK/CGLIB |
| 事务管理 | ✅ @Transactional | ✅ @Transactional |
| 异常处理 | ✅ @ExceptionCatch | ✅ @ExceptionHandler |
| 条件装配 | ❌ 不支持 | ✅ @Conditional |
| 配置类 | 有限支持 | 完整支持 |
| 自动配置 | ❌ | ✅ |
| 体积 | 轻量 (~50KB) | 较重 |

---

> **下一步**: 阅读 [命令系统](./COMMAND_SYSTEM.md) 了解如何创建命令
