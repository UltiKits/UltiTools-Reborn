package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;

/**
 * DataSourceTransactionManager 测试类
 */
@DisplayName("DataSourceTransactionManager 测试")
class DataSourceTransactionManagerTest {

    @Mock
    private DataSource mockDataSource;

    @Mock
    private Connection mockConnection;

    private DataSourceTransactionManager transactionManager;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        transactionManager = new DataSourceTransactionManager(mockDataSource);
        
        // 清除 ThreadLocal 状态
        clearThreadLocalContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearThreadLocalContext();
        mocks.close();
    }

    private void clearThreadLocalContext() {
        try {
            Field contextHolderField = DataSourceTransactionManager.class.getDeclaredField("contextHolder");
            contextHolderField.setAccessible(true);
            // Instance field now (FOUND-04) -- read off transactionManager, not off the class (null).
            @SuppressWarnings("unchecked")
            ThreadLocal<?> contextHolder = (ThreadLocal<?>) contextHolderField.get(transactionManager);
            contextHolder.remove();
        } catch (Exception ignored) {
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该正确初始化 DataSource")
        void shouldInitializeDataSource() {
            DataSourceTransactionManager manager = new DataSourceTransactionManager(mockDataSource);
            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("getConnection 测试")
    class GetConnectionTests {

        @Test
        @DisplayName("没有活动事务时应该从 DataSource 获取新连接")
        void shouldGetNewConnectionWhenNoActiveTransaction() throws Exception {
            Connection conn = transactionManager.getConnection();

            assertThat(conn).isEqualTo(mockConnection);
            verify(mockDataSource).getConnection();
        }

        @Test
        @DisplayName("有活动事务时应该返回事务连接")
        void shouldReturnTransactionConnectionWhenActive() throws Exception {
            // 开始事务
            transactionManager.begin();

            // 获取连接应该返回事务连接
            Connection conn = transactionManager.getConnection();

            assertThat(conn).isEqualTo(mockConnection);
        }

        @Test
        @DisplayName("获取连接失败时应该抛出 DataAccessException")
        void shouldThrowDataAccessExceptionWhenConnectionFails() throws Exception {
            when(mockDataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

            assertThatThrownBy(() -> transactionManager.getConnection())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Failed to get connection");
        }
    }

    @Nested
    @DisplayName("begin 测试")
    class BeginTests {

        @Test
        @DisplayName("应该开始新事务")
        void shouldBeginNewTransaction() throws Exception {
            transactionManager.begin();

            assertThat(transactionManager.hasActiveTransaction()).isTrue();
            verify(mockConnection).setAutoCommit(false);
        }

        @Test
        @DisplayName("嵌套事务应该增加深度")
        void shouldIncrementDepthForNestedTransaction() throws Exception {
            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(1);

            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(2);

            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(3);
        }

        @Test
        @DisplayName("开始事务失败时应该抛出 DataAccessException")
        void shouldThrowDataAccessExceptionWhenBeginFails() throws Exception {
            when(mockDataSource.getConnection()).thenThrow(new SQLException("Failed to connect"));

            assertThatThrownBy(() -> transactionManager.begin())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Failed to begin transaction");
        }
    }

    @Nested
    @DisplayName("commit 测试")
    class CommitTests {

        @Test
        @DisplayName("没有活动事务时 commit 不应该抛出异常")
        void shouldNotThrowWhenNoActiveTransaction() {
            // 不应该抛出异常
            transactionManager.commit();
            // If we reach here, test passes
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("应该提交事务")
        void shouldCommitTransaction() throws Exception {
            transactionManager.begin();
            
            transactionManager.commit();

            verify(mockConnection).commit();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("嵌套事务 commit 应该减少深度")
        void shouldDecrementDepthForNestedCommit() throws Exception {
            transactionManager.begin();
            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(2);

            transactionManager.commit();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(1);
            assertThat(transactionManager.hasActiveTransaction()).isTrue();

            // 最后一次 commit
            transactionManager.commit();
            assertThat(transactionManager.getTransactionDepth()).isZero();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("提交失败时应该抛出 DataAccessException")
        void shouldThrowDataAccessExceptionWhenCommitFails() throws Exception {
            transactionManager.begin();
            doThrow(new SQLException("Commit failed")).when(mockConnection).commit();

            assertThatThrownBy(() -> transactionManager.commit())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Failed to commit transaction");
        }
    }

    @Nested
    @DisplayName("rollback 测试")
    class RollbackTests {

        @Test
        @DisplayName("没有活动事务时 rollback 不应该抛出异常")
        void shouldNotThrowWhenNoActiveTransactionForRollback() {
            // 不应该抛出异常
            transactionManager.rollback();
            // If we reach here, test passes
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("应该回滚事务")
        void shouldRollbackTransaction() throws Exception {
            transactionManager.begin();
            
            transactionManager.rollback();

            verify(mockConnection).rollback();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("嵌套事务 rollback 只标记 rollback-only 并递减深度，不立即清除（D-08，修正自「完全回滚」旧断言）")
        void nestedRollbackShouldMarkRollbackOnlyInsteadOfTearingDownImmediately() throws Exception {
            // D-08 corrects this test's pre-6.3.0 assumption: rollback() used to call cleanup(ctx)
            // "regardless of depth" (the exact HEAD comment this fix removes), silently discarding
            // whatever the outer scope(s) still expected to do. A rollback() at depth > 1 now only
            // marks the context rollback-only and decrements depth -- see RollbackOnlyMarkerTests
            // below for the full depth-2 RED/GREEN pair and the outer commit()'s throw.
            transactionManager.begin();
            transactionManager.begin();
            transactionManager.begin();

            transactionManager.rollback();

            verify(mockConnection, never()).rollback();
            assertThat(transactionManager.hasActiveTransaction()).isTrue();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(2);
        }

        @Test
        @DisplayName("回滚失败时不应该抛出异常，但应该清理")
        void shouldCleanupEvenWhenRollbackFails() throws Exception {
            transactionManager.begin();
            doThrow(new SQLException("Rollback failed")).when(mockConnection).rollback();

            // 不应该抛出异常
            transactionManager.rollback();

            // 应该仍然清理
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }
    }

    @Nested
    @DisplayName("hasActiveTransaction 测试")
    class HasActiveTransactionTests {

        @Test
        @DisplayName("初始状态应该没有活动事务")
        void shouldNotHaveActiveTransactionInitially() {
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("开始事务后应该有活动事务")
        void shouldHaveActiveTransactionAfterBegin() {
            transactionManager.begin();
            assertThat(transactionManager.hasActiveTransaction()).isTrue();
        }

        @Test
        @DisplayName("提交后应该没有活动事务")
        void shouldNotHaveActiveTransactionAfterCommit() {
            transactionManager.begin();
            transactionManager.commit();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("回滚后应该没有活动事务")
        void shouldNotHaveActiveTransactionAfterRollback() {
            transactionManager.begin();
            transactionManager.rollback();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }
    }

    @Nested
    @DisplayName("setIsolationLevel 测试")
    class SetIsolationLevelTests {

        @Test
        @DisplayName("没有活动事务时不应该设置隔离级别")
        void shouldNotSetIsolationLevelWithoutTransaction() throws Exception {
            transactionManager.setIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);

            verify(mockConnection, never()).setTransactionIsolation(anyInt());
        }

        @Test
        @DisplayName("应该设置隔离级别")
        void shouldSetIsolationLevel() throws Exception {
            transactionManager.begin();
            
            transactionManager.setIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);

            verify(mockConnection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        }

        @Test
        @DisplayName("隔离级别为 -1 时不应该设置")
        void shouldNotSetIsolationLevelWhenMinusOne() throws Exception {
            transactionManager.begin();
            
            transactionManager.setIsolationLevel(-1);

            verify(mockConnection, never()).setTransactionIsolation(anyInt());
        }

        @Test
        @DisplayName("设置隔离级别失败时应该抛出 DataAccessException")
        void shouldThrowDataAccessExceptionWhenSetIsolationFails() throws Exception {
            transactionManager.begin();
            doThrow(new SQLException("Set isolation failed"))
                .when(mockConnection).setTransactionIsolation(anyInt());

            assertThatThrownBy(() -> transactionManager.setIsolationLevel(Connection.TRANSACTION_SERIALIZABLE))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Failed to set isolation level");
        }
    }

    @Nested
    @DisplayName("setReadOnly 测试")
    class SetReadOnlyTests {

        @Test
        @DisplayName("没有活动事务时不应该设置只读模式")
        void shouldNotSetReadOnlyWithoutTransaction() throws Exception {
            transactionManager.setReadOnly(true);

            verify(mockConnection, never()).setReadOnly(anyBoolean());
        }

        @Test
        @DisplayName("应该设置只读模式为 true")
        void shouldSetReadOnlyTrue() throws Exception {
            transactionManager.begin();
            
            transactionManager.setReadOnly(true);

            verify(mockConnection).setReadOnly(true);
        }

        @Test
        @DisplayName("应该设置只读模式为 false")
        void shouldSetReadOnlyFalse() throws Exception {
            transactionManager.begin();
            
            transactionManager.setReadOnly(false);

            verify(mockConnection).setReadOnly(false);
        }

        @Test
        @DisplayName("设置只读模式失败时不应该抛出异常")
        void shouldNotThrowWhenSetReadOnlyFails() throws Exception {
            transactionManager.begin();
            doThrow(new SQLException("Set read-only failed"))
                .when(mockConnection).setReadOnly(anyBoolean());

            // 不应该抛出异常
            transactionManager.setReadOnly(true);
            // If we reach here, test passes
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("setTimeout 测试")
    class SetTimeoutTests {

        @Test
        @DisplayName("设置超时应该不抛出异常")
        void shouldNotThrowWhenSettingTimeout() {
            // 不应该抛出异常
            transactionManager.setTimeout(30);
            // If we reach here, test passes
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("设置 0 超时应该不做任何事")
        void shouldDoNothingForZeroTimeout() {
            transactionManager.setTimeout(0);
            // 不应该有异常
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("设置负数超时应该不做任何事")
        void shouldDoNothingForNegativeTimeout() {
            transactionManager.setTimeout(-1);
            // 不应该有异常
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("getTransactionDepth 测试")
    class GetTransactionDepthTests {

        @Test
        @DisplayName("初始深度应该为 0")
        void shouldReturnZeroInitially() {
            assertThat(transactionManager.getTransactionDepth()).isZero();
        }

        @Test
        @DisplayName("开始事务后深度应该为 1")
        void shouldReturnOneAfterBegin() {
            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("嵌套事务应该正确递增深度")
        void shouldIncrementDepthCorrectly() {
            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(1);

            transactionManager.begin();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(2);

            transactionManager.commit();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(1);

            transactionManager.commit();
            assertThat(transactionManager.getTransactionDepth()).isZero();
        }
    }

    @Nested
    @DisplayName("cleanup 测试")
    class CleanupTests {

        @Test
        @DisplayName("清理应该关闭连接")
        void shouldCloseConnection() throws Exception {
            transactionManager.begin();
            transactionManager.commit();

            verify(mockConnection).setAutoCommit(true);
            verify(mockConnection).close();
        }

        @Test
        @DisplayName("清理失败时不应该抛出异常")
        void shouldNotThrowWhenCleanupFails() throws Exception {
            transactionManager.begin();
            doThrow(new SQLException("Close failed")).when(mockConnection).close();

            // 不应该抛出异常
            transactionManager.commit();
            // If we reach here, test passes
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("连接已关闭时不应该抛出异常")
        void shouldNotThrowWhenConnectionAlreadyClosed() throws Exception {
            transactionManager.begin();
            when(mockConnection.isClosed()).thenReturn(true);

            // 不应该抛出异常
            transactionManager.commit();
            // If we reach here, test passes
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("getCurrentContext 测试")
    class GetCurrentContextTests {

        @Test
        @DisplayName("没有事务时应该返回 null")
        void shouldReturnNullWhenNoTransaction() {
            DataSourceTransactionManager.TransactionContext ctx =
                transactionManager.getCurrentContext();
            assertThat(ctx).isNull();
        }

        @Test
        @DisplayName("有事务时应该返回上下文")
        void shouldReturnContextWhenTransactionActive() {
            transactionManager.begin();

            DataSourceTransactionManager.TransactionContext ctx =
                transactionManager.getCurrentContext();

            assertThat(ctx).isNotNull();
            assertThat(ctx.active).isTrue();
            assertThat(ctx.depth).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("TransactionContext 测试")
    class TransactionContextTests {

        @Test
        @DisplayName("TransactionContext 应该有正确的初始值")
        void shouldHaveCorrectInitialValues() {
            DataSourceTransactionManager.TransactionContext ctx = 
                new DataSourceTransactionManager.TransactionContext();

            assertThat(ctx.connection).isNull();
            assertThat(ctx.active).isFalse();
            assertThat(ctx.depth).isZero();
            assertThat(ctx.originalIsolation).isEqualTo(-1);
            assertThat(ctx.originalAutoCommit).isTrue();
            assertThat(ctx.rollbackOnly).isFalse();
        }
    }

    @Nested
    @DisplayName("线程安全测试")
    class ThreadSafetyTests {

        @Test
        @DisplayName("不同线程应该有独立的事务上下文")
        void shouldHaveIndependentTransactionContextPerThread() throws Exception {
            // 主线程开始事务
            transactionManager.begin();
            assertThat(transactionManager.hasActiveTransaction()).isTrue();

            // 另一个线程不应该看到主线程的事务
            Thread otherThread = new Thread(() -> {
                assertThat(transactionManager.hasActiveTransaction()).isFalse();
            });
            otherThread.start();
            otherThread.join();

            // 主线程的事务应该仍然活动
            assertThat(transactionManager.hasActiveTransaction()).isTrue();
        }
    }

    /**
     * FOUND-04: contextHolder is an instance field, not static, so two
     * {@code DataSourceTransactionManager} instances - each bound to a different
     * {@link DataSource} - never observe each other's {@link Connection} or transaction state.
     * These tests fail against the pre-fix {@code static} field: with a static contextHolder,
     * manager A and manager B would share the same ThreadLocal slot, so B's {@code begin()}
     * would silently overwrite A's context on the same thread (Test 1), and on two threads the
     * two managers would still resolve to independent ThreadLocal entries only by accident of
     * per-thread storage - Test 1 is the one that actually distinguishes "static" from
     * "instance", because same-thread reuse of one static field is exactly where the two
     * shapes diverge.
     */
    @Nested
    @DisplayName("跨 DataSource 隔离测试（FOUND-04）")
    class CrossDataSourceIsolationTests {

        @Test
        @DisplayName("同一线程上，两个 manager 各自持有独立的连接，互不可见")
        void shouldIsolateConnectionsAcrossManagersOnSameThread() throws Exception {
            DataSource dataSourceA = mock(DataSource.class);
            DataSource dataSourceB = mock(DataSource.class);
            Connection connectionA = mock(Connection.class);
            Connection connectionB = mock(Connection.class);
            when(dataSourceA.getConnection()).thenReturn(connectionA);
            when(dataSourceB.getConnection()).thenReturn(connectionB);

            DataSourceTransactionManager managerA = new DataSourceTransactionManager(dataSourceA);
            DataSourceTransactionManager managerB = new DataSourceTransactionManager(dataSourceB);

            managerA.begin();
            managerB.begin();

            assertThat(managerA.getConnection()).isSameAs(connectionA);
            assertThat(managerB.getConnection()).isSameAs(connectionB);
            assertThat(managerA.getConnection()).isNotSameAs(managerB.getConnection());

            // Rolling back manager A must never touch manager B's connection or transaction state.
            managerA.rollback();

            assertThat(managerA.hasActiveTransaction()).isFalse();
            assertThat(managerB.hasActiveTransaction()).isTrue();
            verify(connectionB, never()).rollback();
            verify(connectionB, never()).close();
        }

        @Test
        @DisplayName("两个线程上，两个 manager 的事务同时打开时互不可见")
        void shouldIsolateConnectionsAcrossManagersOnDifferentThreads() throws Exception {
            DataSource dataSourceA = mock(DataSource.class);
            DataSource dataSourceB = mock(DataSource.class);
            Connection connectionA = mock(Connection.class);
            Connection connectionB = mock(Connection.class);
            when(dataSourceA.getConnection()).thenReturn(connectionA);
            when(dataSourceB.getConnection()).thenReturn(connectionB);

            DataSourceTransactionManager managerA = new DataSourceTransactionManager(dataSourceA);
            DataSourceTransactionManager managerB = new DataSourceTransactionManager(dataSourceB);

            CountDownLatch bothBegun = new CountDownLatch(2);
            CountDownLatch releaseThreadA = new CountDownLatch(1);
            AtomicReference<Connection> seenByThreadA = new AtomicReference<>();
            AtomicReference<Connection> seenByThreadB = new AtomicReference<>();

            Thread threadA = new Thread(() -> {
                managerA.begin();
                bothBegun.countDown();
                try {
                    // Hold A's transaction open until B has begun too, so both are active
                    // simultaneously before either reads its connection back.
                    releaseThreadA.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                seenByThreadA.set(managerA.getConnection());
            });
            Thread threadB = new Thread(() -> {
                managerB.begin();
                bothBegun.countDown();
                seenByThreadB.set(managerB.getConnection());
                releaseThreadA.countDown();
            });

            threadA.start();
            threadB.start();
            threadA.join();
            threadB.join();

            assertThat(bothBegun.getCount()).isZero();
            assertThat(seenByThreadA.get()).isSameAs(connectionA);
            assertThat(seenByThreadB.get()).isSameAs(connectionB);
            assertThat(seenByThreadA.get()).isNotSameAs(seenByThreadB.get());
        }
    }

    /**
     * D-04: {@code TransactionManager} is split additively. A backend that only needs the
     * lifecycle half (begin/commit/rollback/hasActiveTransaction/setTimeout/getTransactionDepth)
     * implements {@link TransactionManager} directly and is never forced to answer the three
     * JDBC-only members - those now live only on {@link JdbcTransactionManager}, with a
     * {@code default} fallback on the base interface that refuses rather than lies.
     */
    @Nested
    @DisplayName("TransactionManager / JdbcTransactionManager split（D-04）")
    class JdbcSplitTests {

        /**
         * Test-only backend supplying only the six lifecycle methods - deliberately does not
         * implement {@link JdbcTransactionManager}, and does not override any of the three
         * JDBC-only default methods either.
         */
        private final class LifecycleOnlyTransactionManager implements TransactionManager {
            private boolean active;
            private int depth;

            @Override
            public void begin() {
                active = true;
                depth++;
            }

            @Override
            public void commit() {
                depth = Math.max(0, depth - 1);
                active = depth > 0;
            }

            @Override
            public void rollback() {
                depth = 0;
                active = false;
            }

            @Override
            public boolean hasActiveTransaction() {
                return active;
            }

            @Override
            public void setTimeout(int seconds) {
                // no-op: not exercised by this test
            }

            @Override
            public int getTransactionDepth() {
                return depth;
            }
        }

        @Test
        @DisplayName("A lifecycle-only TransactionManager implementation compiles and instantiates")
        void lifecycleOnlyImplementationCompilesAndInstantiates() {
            TransactionManager manager = new LifecycleOnlyTransactionManager();

            assertThat(manager).isNotNull();
            manager.begin();
            assertThat(manager.hasActiveTransaction()).isTrue();
        }

        @Test
        @DisplayName("getConnection() on a lifecycle-only manager throws UnsupportedOperationException naming JdbcTransactionManager")
        void getConnectionThrowsNamingJdbcTransactionManager() {
            TransactionManager manager = new LifecycleOnlyTransactionManager();

            assertThatThrownBy(manager::getConnection)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(JdbcTransactionManager.class.getName());
        }

        @Test
        @DisplayName("setIsolationLevel(int) on a lifecycle-only manager throws UnsupportedOperationException naming JdbcTransactionManager")
        void setIsolationLevelThrowsNamingJdbcTransactionManager() {
            TransactionManager manager = new LifecycleOnlyTransactionManager();

            assertThatThrownBy(() -> manager.setIsolationLevel(Connection.TRANSACTION_SERIALIZABLE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(JdbcTransactionManager.class.getName());
        }

        @Test
        @DisplayName("setReadOnly(boolean) on a lifecycle-only manager throws UnsupportedOperationException naming JdbcTransactionManager")
        void setReadOnlyThrowsNamingJdbcTransactionManager() {
            TransactionManager manager = new LifecycleOnlyTransactionManager();

            assertThatThrownBy(() -> manager.setReadOnly(true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(JdbcTransactionManager.class.getName());
        }

        @Test
        @DisplayName("DataSourceTransactionManager is assignable to both TransactionManager and JdbcTransactionManager")
        void concreteManagerIsAssignableToBothInterfaces() {
            assertThat(transactionManager).isInstanceOf(TransactionManager.class);
            assertThat(transactionManager).isInstanceOf(JdbcTransactionManager.class);
        }
    }

    /**
     * D-08: an inner {@code rollback()} at depth &gt; 1 marks the transaction rollback-only and
     * decrements depth instead of tearing the whole {@link DataSourceTransactionManager.TransactionContext}
     * down. The outer {@code commit()} then performs the real rollback, cleans up, and throws
     * {@link UnexpectedRollbackException} instead of silently returning after a WARNING log line -
     * observed HEAD behaviour before this fix (pinned by
     * {@code shouldFullyRollbackNestedTransactions} above, now rewritten to match the corrected
     * semantics).
     */
    @Nested
    @DisplayName("回滚只读标记 — 外层 commit() 不再静默丢弃工作（D-08）")
    class RollbackOnlyMarkerTests {

        @Test
        @DisplayName("深度 2：内层 rollback() 打标记；外层 commit() 执行真正回滚并抛出 UnexpectedRollbackException")
        void innerRollbackThenOuterCommitThrowsUnexpectedRollback() throws Exception {
            transactionManager.begin(); // depth 1
            transactionManager.begin(); // depth 2

            transactionManager.rollback(); // inner rollback at depth 2 -- must only mark, not tear down

            assertThat(transactionManager.hasActiveTransaction()).isTrue();
            assertThat(transactionManager.getTransactionDepth()).isEqualTo(1);
            verify(mockConnection, never()).rollback();
            verify(mockConnection, never()).close();

            assertThatThrownBy(() -> transactionManager.commit())
                .isInstanceOf(UnexpectedRollbackException.class)
                .hasMessageContaining("nested")
                .satisfies(e -> assertThat(((UnexpectedRollbackException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TRANSACTION_ROLLBACK_ONLY));

            // Outer commit must have performed the real rollback and cleaned up -- no leaked context.
            verify(mockConnection).rollback();
            verify(mockConnection, never()).commit();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("深度 2：内层 commit() 再外层 commit() 正常提交，不抛出任何异常（对照组）")
        void innerCommitThenOuterCommitCommitsNormally() throws Exception {
            transactionManager.begin();
            transactionManager.begin();

            transactionManager.commit(); // inner commit, depth 2 -> 1

            assertThatCode(() -> transactionManager.commit()).doesNotThrowAnyException();

            verify(mockConnection).commit();
            verify(mockConnection, never()).rollback();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("深度 1 的 rollback() 行为不变：真正回滚并清理")
        void depthOneRollbackUnchanged() throws Exception {
            transactionManager.begin();

            transactionManager.rollback();

            verify(mockConnection).rollback();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
            assertThat(transactionManager.getTransactionDepth()).isZero();
        }

        @Test
        @DisplayName("没有活动事务时 commit()/rollback() 仍然只记 WARNING 并正常返回")
        void noActiveTransactionStillReturnsQuietly() {
            assertThatCode(() -> transactionManager.commit()).doesNotThrowAnyException();
            assertThatCode(() -> transactionManager.rollback()).doesNotThrowAnyException();
        }
    }
}
