package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.ChatColor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates and manages command usage locks to prevent concurrent execution.
 * Supports sender-specific and server-wide locks.
 * <p>
 * 验证和管理命令使用锁以防止并发执行。
 * 支持发送者特定锁和服务器范围锁。
 * <p>
 * <b>Acquire-then-execute (D-02, GEN-09, @since 6.3.0):</b> acquisition is no longer a
 * separate step called by field from {@code BaseCommandExecutor.executeCommand}. It happens
 * inside {@link #validate(CommandContext)} itself -- "acquire-as-you-validate" -- so that on
 * the synchronous dispatch path there is no scheduling point between deciding the lock is free
 * and taking it. A failed acquisition surfaces as an ordinary {@link ValidationResult#failure}
 * through the normal validation-rejection path; the mapped method is never invoked. Release
 * happens in {@link #onComplete(CommandContext, boolean)}, driven by the validator chain that
 * actually ran this validator (see {@link CommandValidator#onComplete(CommandContext, boolean)}) --
 * never called twice, and never called for an invocation whose acquisition failed.
 * <p>
 * <b>获取即验证（D-02, GEN-09, 自 6.3.0 起）：</b>获取锁不再是由 {@code BaseCommandExecutor.executeCommand}
 * 按字段调用的独立步骤，而是发生在 {@link #validate(CommandContext)} 内部——"验证即获取"——使得在同步分发
 * 路径上，从判定锁空闲到实际取得锁之间不存在调度点。获取失败会作为普通的
 * {@link ValidationResult#failure} 经由正常的验证拒绝路径呈现；映射方法永远不会被调用。释放发生在
 * {@link #onComplete(CommandContext, boolean)} 中，由实际运行了该验证器的责任链驱动——不会被调用两次，
 * 也不会为获取失败的调用而调用。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class UsageLockValidator implements CommandValidator, PlayerCacheSaver {

    private static final int ORDER = 250;

    /**
     * Locks per sender: player UUID -> set of locked method keys
     * <p>
     * {@code saveBeforeRemove = true} so {@link #savePlayerData(UUID)} -- which delegates to
     * the pre-existing {@link #clearPlayerLocks(UUID)}, clearing BOTH this field and {@link
     * #serverLocks} in one call -- fires exactly once per quit (GEN-08, D-03). {@link
     * #serverLocks} deliberately does NOT also carry {@code saveBeforeRemove = true}: doing so
     * would fire {@link #savePlayerData(UUID)} a second, redundant time per quit.
     */
    @PlayerCache(saveBeforeRemove = true)
    private final Map<UUID, Set<String>> senderLocks = new ConcurrentHashMap<>();

    /**
     * Server-wide locks: method key -> locking player UUID
     */
    @PlayerCache
    private final Map<String, UUID> serverLocks = new ConcurrentHashMap<>();

    /**
     * True once this instance has registered {@link #senderLocks}/{@link #serverLocks} with the
     * live {@link PlayerCacheManager} for quit-based sweeping (GEN-08, D-03). Set lazily from
     * {@link #validate(CommandContext)} rather than the constructor, mirroring {@code
     * CooldownValidator}'s identical field of the same name and rationale: a bare {@code new
     * UsageLockValidator()} must never attempt contact with a core plugin that may not exist
     * yet, and a failed attempt is retried on the next call rather than permanently abandoned.
     */
    private volatile boolean playerCacheRegistered = false;

    /**
     * Validates -- and, per the acquire-then-execute contract on this class, ATTEMPTS TO
     * ACQUIRE -- the lock for this invocation. Delegates entirely to {@link #acquireLock(CommandContext)}:
     * a successful acquisition is a successful validation; a failed acquisition is reported as
     * an ordinary validation failure carrying the scope-appropriate i18n key.
     * <p>
     * 验证——并按本类的"获取即验证"契约——尝试获取本次调用的锁。完全委托给
     * {@link #acquireLock(CommandContext)}：获取成功即验证成功；获取失败会以携带对应作用域 i18n 键的
     * 普通验证失败形式呈现。
     *
     * @param context the command context
     * @return the validation result
     */
    @Override
    public ValidationResult validate(CommandContext context) {
        ensurePlayerCacheRegistered();

        if (acquireLock(context)) {
            return ValidationResult.success();
        }

        // acquireLock() only returns false when the method (or its declaring class, per D-01
        // follow-up most-derived-wins resolution) carries @UsageLimit with a SENDER or ALL
        // scope, so this resolves non-null here.
        Method method = context.getMatchedMethod();
        UsageLimit limit = resolveLimit(method, context.getExecutorClass());
        if (limit.value() == UsageLimit.LimitType.SENDER) {
            return ValidationResult.failure(
                    ChatColor.RED + UltiTools.getInstance().i18n("请先等待上一条命令执行完毕！"),
                    "command.error.sender_locked"
            );
        }
        return ValidationResult.failure(
                ChatColor.RED + UltiTools.getInstance().i18n("请先等待其他玩家发送的命令执行完毕！"),
                "command.error.server_locked"
        );
    }

    /**
     * Post-action hook that releases the lock acquired by {@link #validate(CommandContext)} for
     * this invocation. Delegates to {@link #releaseLock(CommandContext)} and is invoked only by
     * a chain that actually ran this validator for the current dispatch -- see
     * {@link CommandValidator#onComplete(CommandContext, boolean)}. An invocation whose
     * acquisition failed never reaches this hook (it is absent from the chain's
     * passed-validator list), so there is nothing here to release on that path.
     * <p>
     * 释放由 {@link #validate(CommandContext)} 为本次调用获取的锁的后置钩子。委托给
     * {@link #releaseLock(CommandContext)}，仅由实际为本次分发运行了该验证器的责任链调用——参见
     * {@link CommandValidator#onComplete(CommandContext, boolean)}。获取失败的调用永远不会到达此钩子
     * （它不在责任链的通过验证器列表中），因此该路径上没有需要释放的内容。
     *
     * @param context          the command context
     * @param commandSucceeded ignored -- the lock releases whether the mapped method
     *                         succeeded or threw
     * @since 6.3.0
     */
    @Override
    public void onComplete(CommandContext context, boolean commandSucceeded) {
        releaseLock(context);
    }

    /**
     * Attempts lazy first-use registration of this instance with the live {@link
     * PlayerCacheManager} singleton. Safe to call unconditionally on every {@link
     * #validate(CommandContext)} invocation: a no-op once {@link #playerCacheRegistered} is
     * true, and a cheap, safely-no-op-on-failure retry otherwise (see that field's javadoc).
     */
    private void ensurePlayerCacheRegistered() {
        if (playerCacheRegistered) {
            return;
        }
        UltiTools instance = UltiTools.getInstance();
        // Checking getPluginManager() too, not just getInstance(), matters: a mock/test double
        // that stands up UltiTools.getInstance() without yet wiring getPluginManager() would
        // otherwise latch this flag true on a no-op attempt, permanently skipping the retry that
        // would have succeeded once the chain was genuinely live.
        if (instance == null || instance.getPluginManager() == null) {
            return;
        }
        PlayerCacheManager.tryRegister(this);
        playerCacheRegistered = true;
    }

    /**
     * {@link PlayerCacheSaver} hook: fired by {@link PlayerCacheManager#onPlayerQuit(UUID)}
     * ("the quit sweep") for the quitting player -- before the generic {@code @PlayerCache}
     * field sweep removes the (by then already-empty) {@link #senderLocks}/{@link #serverLocks}
     * entries. Delegates to the pre-existing {@link #clearPlayerLocks(UUID)} rather than
     * re-deriving its predicate -- the SAME predicate {@code PlayerCacheManager}'s own
     * value-side sweep branch was built from -- giving that previously zero-caller method a
     * real, quit-path-reached production call site (GEN-08). Only {@link #senderLocks} carries
     * {@code saveBeforeRemove = true}, so this fires exactly once per quit despite clearing both
     * maps.
     *
     * @param playerId the UUID of the player quitting
     * @since 6.3.0
     */
    @Override
    public void savePlayerData(UUID playerId) {
        clearPlayerLocks(playerId);
    }

    /**
     * Acquires a lock for command execution.
     * Should be called before executing the command. As of 6.3.0 this is also the method
     * {@link #validate(CommandContext)} itself calls -- see the class-level acquire-then-execute
     * note.
     * 获取命令执行的锁。
     * 应在执行命令之前调用。自 6.3.0 起，{@link #validate(CommandContext)} 本身也调用此方法——参见类级别的
     * "获取即验证"说明。
     *
     * @param context the command context
     * @return true if lock was acquired, false if already locked
     */
    public boolean acquireLock(CommandContext context) {
        Method method = context.getMatchedMethod();
        UsageLimit limit = resolveLimit(method, context.getExecutorClass());
        if (limit == null) {
            return true;
        }

        String methodKey = method.toString();
        
        if (!context.isPlayer() && !limit.ContainConsole()) {
            return true;
        }
        
        if (limit.value() == UsageLimit.LimitType.SENDER) {
            if (!context.isPlayer()) {
                // senderLocks is keyed by player UUID -- a non-player sender permitted through
                // by ContainConsole() can never OWN a SENDER-scope lock, so it acquires nothing.
                return true;
            }
            UUID playerId = context.getPlayer().getUniqueId();
            return senderLocks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                    .add(methodKey);
        } else if (limit.value() == UsageLimit.LimitType.ALL) {
            if (!context.isPlayer()) {
                // serverLocks' value is a player UUID -- a non-player sender permitted through
                // by ContainConsole() can never OWN an ALL-scope lock either, but it is still
                // gated by whatever player currently holds it.
                return !serverLocks.containsKey(methodKey);
            }
            return serverLocks.putIfAbsent(methodKey, context.getPlayer().getUniqueId()) == null;
        }
        
        return true;
    }
    
    /**
     * Releases a lock after command execution.
     * Should be called after command execution completes (success or failure). As of 6.3.0 this
     * is also the method {@link #onComplete(CommandContext, boolean)} calls -- see the
     * class-level acquire-then-execute note.
     * 在命令执行后释放锁。
     * 应在命令执行完成（成功或失败）后调用。自 6.3.0 起，{@link #onComplete(CommandContext, boolean)}
     * 本身也调用此方法——参见类级别的"获取即验证"说明。
     *
     * @param context the command context
     */
    public void releaseLock(CommandContext context) {
        Method method = context.getMatchedMethod();
        UsageLimit limit = resolveLimit(method, context.getExecutorClass());
        if (limit == null) {
            return;
        }

        String methodKey = method.toString();
        
        if (limit.value() == UsageLimit.LimitType.SENDER && context.isPlayer()) {
            UUID playerId = context.getPlayer().getUniqueId();
            Set<String> locks = senderLocks.get(playerId);
            if (locks != null) {
                locks.remove(methodKey);
                if (locks.isEmpty()) {
                    senderLocks.remove(playerId);
                }
            }
        } else if (limit.value() == UsageLimit.LimitType.ALL && context.isPlayer()) {
            // Ownership-gated, atomic conditional removal (Pitfall 5 / T-05-03): only the
            // sender recorded as the holder at acquisition time may release an ALL-scope lock.
            // A prior build removed unconditionally here, letting any sender free any other
            // sender's server-wide lock.
            serverLocks.remove(methodKey, context.getPlayer().getUniqueId());
        }
    }
    
    /**
     * Resolves the effective {@code @UsageLimit} for {@code method}: the method's own
     * declaration, falling back to a class-level one on the CONCRETE executor class dispatching
     * this command, falling back to a class-level one on the method's declaring class --
     * most-derived-wins, via {@link ReflectionUtil#resolveMethodOrClassAnnotation(Method, Class,
     * Class)}. This is the SAME resolution {@code PluginManager}'s load-time refusal treats as
     * satisfying the contract (SILENT-11 / D-01 follow-up, WR-02 / 05-REVIEW.md fix): a
     * class-level {@code @UsageLimit} that passes the load-time check -- whether declared on a
     * shared abstract base or on the concrete executor class itself -- now actually locks every
     * inherited mapping that does not declare its own.
     * <p>
     * 解析 {@code method} 生效的 {@code @UsageLimit}：方法自身的声明，若无则回退到分发本次命令的
     * 具体执行器类上的类级声明，再无则回退到方法声明类上的类级声明——方法级优先，经由
     * {@link ReflectionUtil#resolveMethodOrClassAnnotation(Method, Class, Class)} 解析。这与
     * {@code PluginManager} 加载时拒绝检查所采信的解析方式完全一致（SILENT-11 / D-01 追加任务，
     * WR-02 / 05-REVIEW.md 修复）：一个通过了加载时检查的类级 {@code @UsageLimit}——无论声明在
     * 共享的抽象基类上，还是声明在具体执行器类自身上——现在都会真正锁定每一个未声明自己
     * {@code @UsageLimit} 的继承映射。
     *
     * @param method        the matched command mapping method, or {@code null} <br> 已匹配的命令
     *                      映射方法，可能为 {@code null}
     * @param executorClass the concrete executor class dispatching this command (WR-02,
     *                      05-REVIEW.md), or {@code null} when unavailable -- falls back to the
     *                      pre-WR-02, declaring-class-only resolution in that case <br>
     *                      分发本次命令的具体执行器类（WR-02，05-REVIEW.md）；不可用时为
     *                      {@code null}，此时回退到 WR-02 之前的、仅声明类的解析
     * @return the resolved annotation, or {@code null} when {@code method} is {@code null} or
     *         neither the method, {@code executorClass}, nor its declaring class carries one
     *         <br> 解析出的注解；{@code method} 为 {@code null}，或方法、
     *         {@code executorClass} 与其声明类均未携带该注解时为 {@code null}
     * @since 6.3.0
     */
    private static UsageLimit resolveLimit(Method method, Class<?> executorClass) {
        if (method == null) {
            return null;
        }
        return ReflectionUtil.resolveMethodOrClassAnnotation(method, executorClass, UsageLimit.class);
    }

    /**
     * Clears all locks for a player.
     * Useful when a player disconnects.
     * 清除玩家的所有锁。
     * 当玩家断开连接时很有用。
     *
     * @param playerId the player's UUID
     */
    public void clearPlayerLocks(UUID playerId) {
        senderLocks.remove(playerId);
        serverLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }
    
    /**
     * Clears all locks.
     * 清除所有锁。
     */
    public void clearAllLocks() {
        senderLocks.clear();
        serverLocks.clear();
    }
    
    /**
     * Checks if a specific method is locked for a player.
     * 检查特定方法是否对玩家锁定。
     *
     * @param playerId  the player's UUID
     * @param methodKey the method key
     * @return true if locked
     */
    public boolean isLocked(UUID playerId, String methodKey) {
        Set<String> locks = senderLocks.get(playerId);
        if (locks != null && locks.contains(methodKey)) {
            return true;
        }
        return serverLocks.containsKey(methodKey);
    }
    
    @Override
    public int getOrder() {
        return ORDER;
    }
    
    @Override
    public String getName() {
        return "UsageLockValidator";
    }
}
