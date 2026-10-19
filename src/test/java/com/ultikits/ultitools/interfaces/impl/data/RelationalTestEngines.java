package com.ultikits.ultitools.interfaces.impl.data;

import java.io.File;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Connection pools for the relational test lanes, each built the way production builds the pool it
 * stands in for, so a test on a lane exercises the driver and pool settings a server uses.
 * <ul>
 *   <li>{@link #realSqlite(File)} mirrors {@code SQLiteDataStore#poolFor}: the same
 *       {@code jdbc:sqlite://} URL form over an absolute file path, the {@code org.sqlite.JDBC}
 *       driver Paper bundles (the same version is a test-scope dependency) and a pool of 10. H2
 *       cannot stand in for it: SQLite compares text columns and reports {@code COUNT(*)} in ways H2
 *       does not.</li>
 *   <li>{@link #h2MysqlMode(String)} is the in-memory H2 MySQL-mode pool the existing data tests
 *       use, kept as a regression lane for both relational operators.</li>
 *   <li>{@link #realMysql()} mirrors the {@code MysqlDataStore} Hikari block with the shipped
 *       {@code config.yml} defaults. It runs only when the system property
 *       {@value #MYSQL_URL_PROPERTY} names a throwaway local database (see
 *       {@link #isRealMysqlConfigured()}); CI sets no such property.</li>
 * </ul>
 */
public final class RelationalTestEngines {

    /** System property naming the real-MySQL JDBC URL; the lane is off when it is unset or empty. */
    public static final String MYSQL_URL_PROPERTY = "ultitools.test.mysql.url";

    /** System property naming the real-MySQL user. */
    public static final String MYSQL_USER_PROPERTY = "ultitools.test.mysql.user";

    /** Environment variable holding the real-MySQL password; never a system property or a literal. */
    public static final String MYSQL_PASSWORD_ENV = "ULTITOOLS_TEST_MYSQL_PASSWORD";

    /** The database name prefix the real-MySQL lane accepts, so it can only drop tables in a test database. */
    public static final String MYSQL_DATABASE_PREFIX = "ultitools_it";

    private static final Pattern TABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    private RelationalTestEngines() {
    }

    /**
     * A pool over a real SQLite file {@code test.db} in {@code directory}, built exactly as
     * {@code SQLiteDataStore#poolFor} builds a production pool.
     *
     * @param directory the directory that holds the database file
     * @return the pool; the caller closes it
     */
    public static HikariDataSource realSqlite(File directory) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite://" + new File(directory, "test.db").getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }

    /**
     * An in-memory H2 pool in MySQL mode, as the existing data tests build it. No password is set:
     * the in-memory database is created without one on first connection.
     *
     * @param uniqueName a database name no other test uses
     * @return the pool; the caller closes it
     */
    public static HikariDataSource h2MysqlMode(String uniqueName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + uniqueName + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        return new HikariDataSource(config);
    }

    /**
     * Whether the real-MySQL lane is configured for this run.
     *
     * @return {@code true} when {@value #MYSQL_URL_PROPERTY} is set and non-empty
     */
    public static boolean isRealMysqlConfigured() {
        String url = System.getProperty(MYSQL_URL_PROPERTY);
        return url != null && !url.isEmpty();
    }

    /**
     * A pool over the configured real MySQL database, with the data-source properties
     * {@code MysqlDataStore} sets, at the shipped {@code config.yml} defaults.
     *
     * @return the pool; the caller closes it
     * @throws IllegalStateException if the lane is not configured, or the URL's host is not
     *         {@code 127.0.0.1} or {@code localhost}, or its database name does not start with
     *         {@value #MYSQL_DATABASE_PREFIX}
     */
    public static HikariDataSource realMysql() {
        String url = System.getProperty(MYSQL_URL_PROPERTY);
        requireThrowawayDatabase(url);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getProperty(MYSQL_USER_PROPERTY));
        config.setPassword(System.getenv(MYSQL_PASSWORD_ENV));
        config.addDataSourceProperty("cachePrepStmts", true);
        config.addDataSourceProperty("prepStmtCacheSize", 250);
        config.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
        config.setConnectionTestQuery("select 1");
        config.setMaximumPoolSize(8);
        return new HikariDataSource(config);
    }

    /**
     * Drops {@code table} if it exists. The real-MySQL lane reuses one database across tests, so
     * each test drops its table first.
     *
     * @param dataSource the pool
     * @param table      a lower-case table name of letters, digits and underscores
     * @throws SQLException if the statement fails
     */
    public static void dropTableIfExists(DataSource dataSource, String table) throws SQLException {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Not a plain table name: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // The name was checked against a strict identifier pattern above; nothing else enters the SQL.
            statement.execute("DROP TABLE IF EXISTS " + table); // nosemgrep
        }
    }

    private static void requireThrowawayDatabase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("The real-MySQL lane needs " + MYSQL_URL_PROPERTY
                    + " set to a jdbc:mysql:// URL.");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String host = uri.getHost();
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (!loopback || !database.startsWith(MYSQL_DATABASE_PREFIX)) {
            throw new IllegalStateException("The real-MySQL lane only runs against a throwaway database on "
                    + "127.0.0.1 or localhost whose name starts with " + MYSQL_DATABASE_PREFIX
                    + "; refusing host '" + host + "' and database '" + database + "'.");
        }
    }
}
