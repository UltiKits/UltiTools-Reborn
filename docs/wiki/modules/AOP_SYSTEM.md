# AOP 系统 - 面向切面编程

本文档详细介绍 UltiTools-API 的 AOP（面向切面编程）和事务管理系统。

---

## 目录

- [概述](#概述)
- [核心组件](#核心组件)
- [事务管理](#事务管理)
- [异常处理](#异常处理)
- [使用方式](#使用方式)
- [高级特性](#高级特性)
- [已知限制](#已知限制)

---

## 概述

UltiTools 框架实现了类似 Spring 的面向切面编程（AOP）系统，支持声明式事务管理和异常处理。系统使用 CGLIB 运行时代理来拦截方法调用并应用横切关注点。

**核心功能**:

- 声明式事务管理（`@Transactional`）
- 声明式异常处理（`@ExceptionCatch`）
- 方法拦截器链
- CGLIB 动态代理
- 与 IoC 容器无缝集成

---

## 核心组件

### MethodInterceptor

方法拦截器接口，用于实现 AOP 通知：

```java
@FunctionalInterface
public interface MethodInterceptor {
    Object invoke(MethodInvocation invocation) throws Throwable;
}
```

### MethodInvocation

方法调用上下文，提供对目标对象、方法和参数的访问：

```java
public interface MethodInvocation {
    Object getTarget();           // 获取目标对象
    Method getMethod();           // 获取被调用的方法
    Object[] getArguments();      // 获取方法参数
    Object proceed() throws Throwable; // 继续执行下一个拦截器或目标方法
}
```

### AopAdvisor

通知器接口，决定哪些方法应该被拦截：

```java
public interface AopAdvisor {
    // 判断是否匹配指定方法
    boolean matches(Method method, Class<?> targetClass);

    // 返回匹配方法的拦截器
    MethodInterceptor getInterceptor();

    // 优先级（值越小优先级越高）
    default int getOrder() { return 0; }
}
```

### CglibProxyFactory

使用 CGLIB 创建动态代理：

```java
CglibProxyFactory factory = new CglibProxyFactory(interceptors);
MyService proxy = factory.createProxy(myService);
```

### AopProxyBeanPostProcessor

与 IoC 容器集成的 Bean 后处理器：

```java
AopProxyBeanPostProcessor aopProcessor = new AopProxyBeanPostProcessor();

// 添加事务拦截器
aopProcessor.addAdvisor(
    AopAdvisor.forAnnotation(Transactional.class, txInterceptor, 100)
);

// 添加异常处理拦截器
aopProcessor.addAdvisor(
    AopAdvisor.forAnnotation(ExceptionCatch.class, exInterceptor, 200)
);

// 注册到容器
container.addBeanPostProcessor(aopProcessor);
```

---

## 事务管理

### @Transactional 注解

声明式事务管理注解：

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {
    Propagation propagation() default Propagation.REQUIRED;
    Isolation isolation() default Isolation.DEFAULT;
    int timeout() default -1;
    boolean readOnly() default false;
    Class<? extends Throwable>[] rollbackFor() default {};
    Class<? extends Throwable>[] noRollbackFor() default {};
}
```

### 传播行为 (Propagation)

| 传播类型 | 描述 |
|----------|------|
| `REQUIRED` | 加入现有事务，如果没有则创建新事务（默认） |
| `REQUIRES_NEW` | 总是创建新事务 |
| `SUPPORTS` | 如果存在事务则加入，否则非事务执行 |
| `NOT_SUPPORTED` | 总是非事务执行 |
| `MANDATORY` | 必须存在事务，否则抛出异常 |
| `NEVER` | 必须没有事务，否则抛出异常 |
| `NESTED` | 在现有事务中创建保存点 |

### 隔离级别 (Isolation)

| 隔离级别 | 描述 |
|----------|------|
| `DEFAULT` | 使用数据库默认隔离级别 |
| `READ_UNCOMMITTED` | 允许脏读 |
| `READ_COMMITTED` | 防止脏读 |
| `REPEATABLE_READ` | 防止不可重复读 |
| `SERIALIZABLE` | 最高隔离级别 |

### 使用示例

**基本用法**:

```java
@Service
public class UserService {

    @Transactional
    public void createUser(User user) {
        // 自动开启事务
        userDao.insert(user);
        auditLog.record("用户创建: " + user.getId());
        // 成功时自动提交，RuntimeException 时自动回滚
    }
}
```

**只读事务**:

```java
@Transactional(readOnly = true)
public List<User> getAllUsers() {
    return userDao.findAll();
}
```

**超时和隔离级别**:

```java
@Transactional(
    timeout = 30,
    isolation = Isolation.READ_COMMITTED
)
public void processOrder(Order order) {
    // 30 秒超时，读已提交隔离级别
}
```

**自定义回滚规则**:

```java
@Transactional(
    rollbackFor = {BusinessException.class},      // 这些异常触发回滚
    noRollbackFor = {OptimisticLockException.class} // 这些异常不回滚
)
public void updateInventory(Item item) throws BusinessException {
    // 自定义回滚规则
}
```

**独立事务**:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logAudit(String message) {
    // 总是在独立事务中执行
    // 即使父事务回滚，此事务也会提交
    auditDao.insert(message);
}
```

### 类级别注解

可以在类上标注 `@Transactional`，应用到所有方法：

```java
@Service
@Transactional
public class OrderService {

    public void createOrder(Order order) {
        // 继承类级别的事务配置
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrder(Order order) {
        // 方法级别注解覆盖类级别
    }
}
```

---

## 异常处理

### @ExceptionCatch 注解

声明式异常处理注解：

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ExceptionCatch {
    Class<? extends Throwable>[] value() default {Exception.class};
    boolean silent() default false;
    String handler() default "";
    String defaultValue() default "";
}
```

| 属性 | 描述 |
|------|------|
| `value` | 要捕获的异常类型（支持继承） |
| `silent` | 是否静默处理（不记录日志） |
| `handler` | 自定义异常处理器 Bean 名称 |
| `defaultValue` | 异常时的默认返回值表达式 |

### 默认值表达式

| 表达式 | 返回值 |
|--------|--------|
| `"null"` | `null` |
| `"true"` | `Boolean.TRUE` |
| `"false"` | `Boolean.FALSE` |
| `"empty"` | 空集合/数组/字符串 |
| `"42"` | 数值类型 |

### @ExceptionCatch 使用示例

**基本用法**:

```java
@Service
public class DataService {

    @ExceptionCatch({NullPointerException.class, IllegalArgumentException.class})
    public User findUser(String id) {
        // 捕获异常后返回 null
        return userRepo.findById(id);
    }
}
```

**返回空集合**:

```java
@ExceptionCatch(
    value = {RuntimeException.class},
    defaultValue = "empty"
)
public List<Order> getOrders(String userId) {
    // RuntimeException 时返回空列表
    return orderRepo.findByUserId(userId);
}
```

**静默处理**:

```java
@ExceptionCatch(
    value = {Exception.class},
    silent = true
)
public void logAction(String action) {
    // 静默吞掉任何异常，不记录日志
    logger.info("Action: " + action);
}
```

**自定义处理器**:

```java
@ExceptionCatch(
    value = {ValidationException.class},
    handler = "validationErrorHandler"
)
public Result validate(Data data) {
    // 使用自定义处理器 Bean
    return validator.validate(data);
}
```

### ExceptionHandler 接口

自定义异常处理器：

```java
public interface ExceptionHandler {
    Object handleException(Throwable exception, Object target,
                          Method method, Object[] args) throws Throwable;

    default boolean supports(Class<? extends Throwable> exceptionType) {
        return true;
    }

    default int getOrder() {
        return 0;
    }
}
```

**实现示例**:

```java
@Component("orderErrorHandler")
public class OrderErrorHandler implements ExceptionHandler {

    @Override
    public Object handleException(Throwable e, Object target,
                                  Method method, Object[] args) throws Throwable {
        // 记录到外部服务
        logger.error("订单处理失败: " + method.getName(), e);
        metrics.recordError("order_processing");

        // 返回恢复值
        if (method.getReturnType() == String.class) {
            return "ERROR: " + e.getMessage();
        }
        return null;
    }

    @Override
    public boolean supports(Class<? extends Throwable> type) {
        return OrderException.class.isAssignableFrom(type);
    }
}
```

---

## 使用方式

### 1. 完整示例

```java
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private AuditLogger auditLogger;

    /**
     * 处理订单 - 事务管理 + 异常处理
     */
    @Transactional(
        propagation = Propagation.REQUIRED,
        isolation = Isolation.READ_COMMITTED,
        timeout = 60,
        rollbackFor = {RuntimeException.class}
    )
    @ExceptionCatch(
        value = {ValidationException.class},
        handler = "orderErrorHandler"
    )
    public Order processOrder(Order order) throws ValidationException {
        // 验证订单
        validateOrder(order);

        // 保存订单（同一事务）
        Order saved = orderRepo.save(order);

        // 更新库存（同一事务）
        inventoryService.decrementStock(saved.getItems());

        // 异步记录日志（独立事务）
        logOrderProcessed(saved);

        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void logOrderProcessed(Order order) {
        auditLogger.log("订单处理完成: " + order.getId());
        // 即使父事务回滚，此日志也会提交
    }

    @Transactional(readOnly = true)
    @ExceptionCatch(defaultValue = "empty")
    public List<Order> getOrdersByUser(String userId) {
        return orderRepo.findByUserId(userId);
    }
}
```

### 2. 与 IoC 容器集成

AOP 通过 `BeanPostProcessor` 与 IoC 容器集成。当 Bean 初始化后，`AopProxyBeanPostProcessor` 检查是否需要创建代理：

```
Bean 创建
    │
    ▼
依赖注入
    │
    ▼
@PostConstruct
    │
    ▼
AopProxyBeanPostProcessor 检查
    │
    ├─→ 无匹配注解 → 返回原始 Bean
    │
    └─→ 有匹配注解 → 创建 CGLIB 代理
                          │
                          ▼
                    返回代理 Bean
```

---

## 高级特性

### 1. 拦截器链

多个拦截器按优先级顺序执行：

```java
// 优先级 100（先执行）
aopProcessor.addAdvisor(
    AopAdvisor.forAnnotation(Transactional.class, txInterceptor, 100)
);

// 优先级 200（后执行）
aopProcessor.addAdvisor(
    AopAdvisor.forAnnotation(ExceptionCatch.class, exInterceptor, 200)
);
```

执行顺序：
```
请求 → 事务拦截器 → 异常拦截器 → 目标方法 → 异常拦截器 → 事务拦截器 → 响应
```

### 2. 自定义拦截器

```java
public class LoggingInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        long start = System.currentTimeMillis();

        try {
            Object result = invocation.proceed();
            long duration = System.currentTimeMillis() - start;
            logger.info("方法 {} 执行成功，耗时 {}ms",
                invocation.getMethod().getName(), duration);
            return result;
        } catch (Throwable e) {
            logger.error("方法 {} 执行失败",
                invocation.getMethod().getName(), e);
            throw e;
        }
    }
}
```

### 3. 自定义通知器

```java
public class CustomAdvisor implements AopAdvisor {

    private final MethodInterceptor interceptor;

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        // 自定义匹配逻辑
        return method.getName().startsWith("process");
    }

    @Override
    public MethodInterceptor getInterceptor() {
        return interceptor;
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
```

---

## 已知限制

### 1. 自调用问题

在同一个类中调用 `@Transactional` 或 `@ExceptionCatch` 方法会绕过代理：

```java
@Service
public class MyService {

    @Transactional
    public void methodA() {
        methodB(); // 直接调用，绕过代理！methodB 的事务不生效
    }

    @Transactional
    public void methodB() {
        // 事务注解不会生效
    }
}
```

**解决方案**:

1. 将方法移到不同的 Bean
2. 通过容器获取代理对象调用

### 2. final 类和方法

CGLIB 无法代理 `final` 类或拦截 `final` 方法：

```java
// 无法代理
public final class FinalService {
    @Transactional
    public void doSomething() { }
}

// 无法拦截
public class MyService {
    @Transactional
    public final void finalMethod() { } // 注解不生效
}
```

### 3. 事务暂停

`REQUIRES_NEW` 和 `NOT_SUPPORTED` 目前不会实际暂停现有事务。

### 4. 保存点

`NESTED` 传播类型目前被视为 `REQUIRED`。

---

## 性能考虑

1. **代理创建开销**: 仅在 Bean 初始化时发生一次
2. **拦截器链**: 最小开销 - 只是方法调用链
3. **反射调用**: 仅在所有拦截器执行后调用目标方法时使用
4. **线程本地存储**: 事务上下文通过 ThreadLocal 管理

---

## 与 Spring AOP 的比较

| 特性 | UltiTools AOP | Spring AOP |
|------|---------------|------------|
| 代理方式 | CGLIB | JDK/CGLIB |
| 注解支持 | @Transactional, @ExceptionCatch | 更多注解 |
| 切点表达式 | 基于注解 | AspectJ 表达式 |
| 事务传播 | 7 种 | 7 种 |
| 事务暂停 | 有限支持 | 完整支持 |
| 保存点 | 有限支持 | 完整支持 |
| 体积 | 轻量 | 较重 |

---

> **下一步**: 阅读 [安全系统](./SECURITY.md) 了解框架的安全机制
