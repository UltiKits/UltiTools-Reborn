package com.ultikits.ultitools.interfaces.impl.data;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;

/**
 * A {@link DataSource} wrapper that integrates with {@link TransactionManager}.
 * <p>
 * When a transaction is active, returns the transaction's connection wrapped in a
 * {@link NonClosingConnectionProxy} to prevent premature closing by {@code QueryRunner}.
 * When no transaction is active, delegates to the underlying DataSource for normal
 * auto-commit behavior.
 *
 * @since 6.2.0
 */
class TransactionAwareDataSource implements DataSource {

    private final DataSource delegate;
    private final Supplier<TransactionManager> transactionManagerSupplier;

    TransactionAwareDataSource(DataSource delegate, Supplier<TransactionManager> transactionManagerSupplier) {
        this.delegate = delegate;
        this.transactionManagerSupplier = transactionManagerSupplier;
    }

    @Override
    public Connection getConnection() throws SQLException {
        TransactionManager tm = transactionManagerSupplier.get();
        if (tm != null && tm.hasActiveTransaction()) {
            return new NonClosingConnectionProxy(transactionConnection(tm));
        }
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        TransactionManager tm = transactionManagerSupplier.get();
        if (tm != null && tm.hasActiveTransaction()) {
            return new NonClosingConnectionProxy(transactionConnection(tm));
        }
        return delegate.getConnection(username, password);
    }

    /**
     * The active transaction's connection. Only a {@link JdbcTransactionManager} has one; any other
     * manager is refused with the same {@link UnsupportedOperationException} the base interface's
     * deprecated {@code getConnection()} default threw before it was removed in 6.4.0.
     */
    private static Connection transactionConnection(TransactionManager tm) {
        if (tm instanceof JdbcTransactionManager) {
            return ((JdbcTransactionManager) tm).getConnection();
        }
        throw new UnsupportedOperationException(
                "getConnection() is JDBC-specific and not supported by this TransactionManager. "
                        + "Implement " + JdbcTransactionManager.class.getName() + " to provide it.");
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
