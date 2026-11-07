package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.sqlite.BusyHandler;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;
import org.sqlite.jdbc4.JDBC4Connection;

/** Physical SQLite connections whose busy waits use elapsed time, even under native profiling. */
final class SQLiteBusyDataSource extends SQLiteDataSource {
    private final String address;

    SQLiteBusyDataSource(String path) {
        // Retain the existing pool's SQLite URL and address, including its double slash.
        address = "//" + path;
        setUrl("jdbc:sqlite:" + address);
    }

    @Override
    public SQLiteConnection getConnection(String username, String password) throws SQLException {
        Properties properties = getConfig().toProperties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("pass", password);
        }
        return new BusyConnection(getUrl(), address, properties);
    }

    private static final class BusyConnection extends JDBC4Connection {
        private BusyConnection(String url, String address, Properties properties) throws SQLException {
            super(url, address, properties);
            try {
                installHandler(getBusyTimeout());
            } catch (SQLException failure) {
                try {
                    close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        @Override
        public void setBusyTimeout(int milliseconds) throws SQLException {
            super.setBusyTimeout(milliseconds);
            // JDBC query timeouts call this before execution AND when restoring the connection.
            // Reinstall here, not merely on checkout, so neither call restores the native handler.
            installHandler(milliseconds);
        }

        private void installHandler(int milliseconds) throws SQLException {
            if (milliseconds <= 0) {
                BusyHandler.clearHandler(this);
            } else {
                BusyHandler.setHandler(this, new ElapsedBusyHandler(milliseconds));
            }
        }
    }

    private static final class ElapsedBusyHandler extends BusyHandler {
        private final long budgetNanos;
        private long startedNanos;

        private ElapsedBusyHandler(int milliseconds) {
            budgetNanos = TimeUnit.MILLISECONDS.toNanos(milliseconds);
        }

        @Override
        protected int callback(int previousInvocations) {
            if (previousInvocations == 0) {
                startedNanos = System.nanoTime();
            }
            long remaining = budgetNanos - (System.nanoTime() - startedNanos);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                return 0;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
                return 1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
    }
}
