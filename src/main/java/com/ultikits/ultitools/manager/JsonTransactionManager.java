package com.ultikits.ultitools.manager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.TransactionManager;

/**
 * Snapshot-based {@link TransactionManager} for the JSON backend (D-03), giving
 * {@code @Transactional} the same consistent behaviour on {@code datasource.type: json} that
 * {@link DataSourceTransactionManager} already gives the two JDBC backends.
 * <p>
 * Scoped to one requesting identity (a plugin name on the internal path, a canonical data-folder
 * path on the external one) and shared by every {@code SimpleJsonDataOperator} that
 * {@code JsonStore} hands out for that identity. {@link #begin()} opens (or, by depth, joins) a
 * transaction on the current thread. The first write reaching any one of that identity's JSON
 * operators captures a deep-copy snapshot of that operator's cache, lazily -- an operator never
 * written to during the transaction is neither snapshotted nor restored (see
 * {@link #captureIfAbsent}). {@link #rollback()} at depth 1 restores every captured snapshot; at
 * depth greater than 1 it marks the transaction rollback-only instead of tearing it down, exactly
 * mirroring {@link DataSourceTransactionManager}'s D-08 semantics so
 * {@code TransactionInterceptor} sees one behaviour regardless of which backend built this
 * manager. {@link #commit()} at depth 1 discards the captured snapshots, or -- when the marker is
 * set -- restores them and throws {@link UnexpectedRollbackException}, exactly as the JDBC
 * manager does.
 * <p>
 * <b>This is not a durable transaction.</b> It is an in-process rollback of cached state, not a
 * crash-safe one: a server killed mid-transaction has whatever the flush scheduler last wrote to
 * disk. Nothing here, and nothing this plan's javadoc, log output, or {@code COMPATIBILITY.md}
 * text produces, should read as a claim that a JSON transaction survives a crash the way a JDBC
 * transaction's commit/rollback does.
 * <p>
 * 面向 JSON 后端的快照式 {@link TransactionManager}（D-03），使 {@code @Transactional} 在
 * {@code datasource.type: json} 下拥有与两个 JDBC 后端一致的行为。按请求方身份分实例
 * （内部路径下为插件名，外部路径下为规范化的数据文件夹路径），同一身份下 {@code JsonStore}
 * 派发的每一个 {@code SimpleJsonDataOperator} 共享同一个管理器实例。事务范围内第一次写入某个
 * 操作器时才惰性捕获该操作器缓存的深拷贝快照——事务期间从未写入的操作器既不会被快照，
 * 也不会被回滚。<b>这不是持久化事务</b>：它只是对内存中缓存状态的进程内回滚，不具备崩溃安全性——
 * 服务器在事务执行中途被杀掉，磁盘上留下的是刷新调度器最后一次写入的内容。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class JsonTransactionManager implements TransactionManager {

    private static final Logger LOGGER = Logger.getLogger(JsonTransactionManager.class.getName());

    /**
     * The requesting identity this manager governs -- a plugin name or a canonical data-folder
     * path, matching whatever {@code JsonStore} used to key the operators it hands out for this
     * manager. Used only for diagnostic log/exception messages; never compared against anything.
     */
    private final String identity;

    /**
     * Instance-scoped {@link ThreadLocal}, matching the FOUND-04 reasoning already documented on
     * {@link DataSourceTransactionManager#contextHolder}: one manager per identity, built once by
     * {@code JsonStore}, so two identities can never cross transaction state.
     */
    private final ThreadLocal<Context> contextHolder = new ThreadLocal<>();

    /**
     * Constructed only by {@code JsonStore} (a different package, hence {@code public}) when it
     * first resolves a manager for a given identity.
     *
     * @param identity the requesting identity this manager governs, used only for diagnostics
     */
    public JsonTransactionManager(String identity) {
        this.identity = identity;
    }

    @Override
    public void begin() {
        Context ctx = contextHolder.get();
        if (ctx != null) {
            ctx.depth++;
            LOGGER.fine("Nested JSON transaction started for " + identity + ", depth: " + ctx.depth);
            return;
        }
        ctx = new Context();
        ctx.depth = 1;
        contextHolder.set(ctx);
        LOGGER.fine("JSON transaction started for " + identity);
    }

    @Override
    public void commit() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            LOGGER.warning("Commit called but no active JSON transaction for " + identity);
            return;
        }

        if (ctx.depth > 1) {
            ctx.depth--;
            LOGGER.fine("Nested JSON transaction committed for " + identity + ", depth: " + ctx.depth);
            return;
        }

        // D-08: an inner rollback() at a depth this commit() has now unwound to may have marked
        // the context rollback-only instead of tearing it down. At depth 1 that marker must be
        // honoured with a real restore, not silently overridden by a commit the caller never
        // asked to happen after all -- exactly the same reasoning as
        // DataSourceTransactionManager#commit().
        if (ctx.rollbackOnly) {
            try {
                restore(ctx);
                LOGGER.fine("JSON transaction rolled back for " + identity
                        + " (was marked rollback-only by a nested scope)");
            } finally {
                contextHolder.remove();
            }
            throw UnexpectedRollbackException.markedBy("a nested transaction scope in " + identity);
        }

        // Nothing further to do: the cache already reflects every write made during the
        // transaction. Discarding the captured snapshots (by simply dropping the context) is the
        // JSON equivalent of a JDBC connection.commit().
        LOGGER.fine("JSON transaction committed for " + identity);
        contextHolder.remove();
    }

    @Override
    public void rollback() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            LOGGER.warning("Rollback called but no active JSON transaction for " + identity);
            return;
        }

        // D-08: an inner rollback() must not tear down the whole context -- that would silently
        // discard whatever the outer scope(s) still have to do, and the outer commit() would then
        // proceed as if nothing had gone wrong. Mark rollback-only and decrement instead; only the
        // depth-1 rollback below actually restores anything.
        if (ctx.depth > 1) {
            ctx.rollbackOnly = true;
            ctx.depth--;
            LOGGER.fine("Nested JSON transaction marked rollback-only for " + identity + ", depth: " + ctx.depth);
            return;
        }

        try {
            restore(ctx);
            LOGGER.fine("JSON transaction rolled back for " + identity);
        } finally {
            contextHolder.remove();
        }
    }

    private void restore(Context ctx) {
        for (Runnable restorer : ctx.restorers.values()) {
            restorer.run();
        }
    }

    @Override
    public boolean hasActiveTransaction() {
        Context ctx = contextHolder.get();
        return ctx != null && ctx.depth > 0;
    }

    @Override
    public int getTransactionDepth() {
        Context ctx = contextHolder.get();
        return ctx != null ? ctx.depth : 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The JSON backend has no statement or connection layer for a timeout to apply to -- there is
     * nothing here that could time out. Rather than silently accept the value and do nothing (the
     * shape {@link DataSourceTransactionManager#setTimeout(int)} itself uses, since JDBC has no
     * portable timeout mechanism either, but at least genuinely has a connection the value could
     * theoretically apply to), this refuses outright: {@code @Transactional(timeout=)} is refused
     * rather than silently ignored on the JSON backend. {@code TransactionInterceptor} only calls
     * this when a positive timeout is actually configured on the annotation, so a
     * {@code @Transactional} method that never specifies one is unaffected.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void setTimeout(int seconds) {
        throw new UnsupportedOperationException(
                "JsonTransactionManager (identity: " + identity + ") has no statement to time out -- "
                        + "the JSON backend has no connection/statement layer for a timeout to apply to. "
                        + "@Transactional(timeout=) is not supported on datasource.type: json.");
    }

    /**
     * Called by a {@code SimpleJsonDataOperator} write path (through {@code JsonStore}, its own
     * package) the first time that operator is touched inside the active transaction on this
     * thread. A no-op when no transaction is active on this thread, or when {@code operatorKey}
     * was already captured earlier in this same transaction.
     * <p>
     * Public because the caller lives in a different package ({@code
     * interfaces.impl.data.json}) than this class. The actual deep-copy capture/restore logic
     * stays package-private on {@code SimpleJsonDataOperator} itself, reached only from inside
     * {@code JsonStore}: {@code snapshotSupplier} and {@code restorer} are lambdas built there,
     * closing over that package-private access, so this manager never touches an operator's cache
     * directly and never needs to.
     *
     * @param operatorKey      identifies the operator -- the operator instance itself, in
     *                         practice
     * @param snapshotSupplier produces the deep-copy snapshot; invoked at most once per operator
     *                         per transaction
     * @param restorer         restores {@code operatorKey}'s cache from the captured snapshot;
     *                         invoked by {@link #rollback()}, and by {@link #commit()} when the
     *                         transaction was marked rollback-only
     */
    public void captureIfAbsent(Object operatorKey, Supplier<Object> snapshotSupplier, Consumer<Object> restorer) {
        Context ctx = contextHolder.get();
        if (ctx == null || ctx.depth == 0) {
            return;
        }
        if (!ctx.restorers.containsKey(operatorKey)) {
            Object snapshot = snapshotSupplier.get();
            ctx.restorers.put(operatorKey, () -> restorer.accept(snapshot));
        }
    }

    /**
     * Internal class to hold JSON transaction state -- the JSON analogue of
     * {@link DataSourceTransactionManager.TransactionContext}, minus anything JDBC-specific.
     */
    private static final class Context {
        int depth;

        /**
         * D-08: set by an inner {@link #rollback()} at depth &gt; 1 instead of tearing the
         * context down. The outer {@link #commit()} at depth 1 checks this before committing --
         * if set, it restores every captured snapshot and throws
         * {@link UnexpectedRollbackException} rather than silently committing (or silently
         * returning) as if the inner rollback never happened.
         */
        boolean rollbackOnly;

        /**
         * One entry per operator captured so far this transaction, in first-touch order --
         * insertion order matters if two operators' restores could ever interact, so this is a
         * {@link LinkedHashMap} rather than a {@link java.util.HashMap}. Each value is a
         * zero-argument {@link Runnable} that restores exactly one operator from the snapshot
         * captured for it, built once by {@link #captureIfAbsent} and never mutated afterward.
         */
        final Map<Object, Runnable> restorers = new LinkedHashMap<>();
    }
}
