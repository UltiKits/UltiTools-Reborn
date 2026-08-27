package com.ultikits.ultitools.testutil;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * Live HikariCP pool-state assertions, used in place of "no exception was thrown" as evidence
 * that a connection pool was actually released.
 * <p>
 * {@link HikariDataSource#getHikariPoolMXBean()} is only meaningful while the pool is running -
 * calling it on a closed pool is undefined, so every assertion here checks {@link
 * HikariDataSource#isClosed()} first rather than dereferencing the MXBean unconditionally.
 * <p>
 * 基于真实 HikariCP 连接池状态的断言助手，取代「没有抛出异常」作为连接池确实被释放的证据。
 * {@link HikariDataSource#getHikariPoolMXBean()} 只有在连接池运行中才有意义——在已关闭的连接池
 * 上调用它是未定义行为，所以这里的每个断言都先检查 {@link HikariDataSource#isClosed()}，
 * 而不是无条件地解引用 MXBean。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class PoolStateAssertions {

    private PoolStateAssertions() {
        // Static utility - not instantiable.
    }

    /**
     * Asserts that the given pool currently holds exactly {@code expected} connections
     * (active + idle), failing with a message naming the actual count when it differs.
     *
     * @param dataSource the live pool to inspect <br> 待检查的存活连接池
     * @param expected   the expected total connection count <br> 期望的连接总数
     */
    public static void assertTotalConnections(HikariDataSource dataSource, int expected) {
        assertThat(dataSource.isClosed())
                .as("pool must be open to inspect its connection count")
                .isFalse();
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        assertThat(pool.getTotalConnections())
                .as("expected %d total connections in pool, found %d", expected, pool.getTotalConnections())
                .isEqualTo(expected);
    }

    /**
     * Asserts that the given pool is closed. Passes only when {@link HikariDataSource#isClosed()}
     * is {@code true}; never dereferences the MXBean, since it is not meaningful once a pool is
     * closed.
     *
     * @param dataSource the pool to inspect <br> 待检查的连接池
     */
    public static void assertClosed(HikariDataSource dataSource) {
        assertThat(dataSource.isClosed())
                .as("expected pool to be closed, but it is still open")
                .isTrue();
    }

    /**
     * Sums {@link HikariPoolMXBean#getTotalConnections()} across every still-open pool in the
     * given collection and asserts the sum is zero. Pools that report closed are skipped rather
     * than causing the assertion helper itself to throw.
     *
     * @param dataSources the pools to inspect <br> 待检查的连接池集合
     */
    public static void assertNoOpenConnections(Iterable<HikariDataSource> dataSources) {
        int total = 0;
        for (HikariDataSource dataSource : dataSources) {
            if (dataSource == null || dataSource.isClosed()) {
                continue;
            }
            total += dataSource.getHikariPoolMXBean().getTotalConnections();
        }
        assertThat(total)
                .as("expected zero open connections across all pools, found %d", total)
                .isZero();
    }
}
