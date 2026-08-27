package com.ultikits.ultitools.manager;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.ultikits.ultitools.interfaces.TransactionManager;

/**
 * RED scaffold (02-05 Task 1) -- compiles and provides the vocabulary
 * {@code JsonTransactionTest$PerPluginTransactionScopeTests} needs, but every lifecycle method is
 * still a no-op stub. The real per-plugin snapshot/restore/rollback-only implementation lands in
 * the paired {@code feat(02-05)} commit.
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class JsonTransactionManager implements TransactionManager {

    public JsonTransactionManager(String identity) {
        // RED scaffold: no-op.
    }

    @Override
    public void begin() {
        // RED scaffold: no-op.
    }

    @Override
    public void commit() {
        // RED scaffold: no-op.
    }

    @Override
    public void rollback() {
        // RED scaffold: no-op.
    }

    @Override
    public boolean hasActiveTransaction() {
        return false;
    }

    @Override
    public int getTransactionDepth() {
        return 0;
    }

    @Override
    public void setTimeout(int seconds) {
        // RED scaffold: no-op.
    }

    public void captureIfAbsent(Object operatorKey, Supplier<Object> snapshotSupplier, Consumer<Object> restorer) {
        // RED scaffold: no-op -- nothing is ever captured, so rollback has nothing to restore.
    }
}
