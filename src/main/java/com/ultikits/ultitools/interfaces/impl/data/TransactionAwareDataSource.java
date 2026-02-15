package com.ultikits.ultitools.interfaces.impl.data;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

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
            return new NonClosingConnectionProxy(tm.getConnection());
        }
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        TransactionManager tm = transactionManagerSupplier.get();
        if (tm != null && tm.hasActiveTransaction()) {
            return new NonClosingConnectionProxy(tm.getConnection());
        }
        return delegate.getConnection(username, password);
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
