package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;

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
        @DisplayName("嵌套事务 rollback 应该完全回滚")
        void shouldFullyRollbackNestedTransactions() throws Exception {
            transactionManager.begin();
            transactionManager.begin();
            transactionManager.begin();

            // 回滚应该完全清除，不管嵌套深度
            transactionManager.rollback();

            verify(mockConnection).rollback();
            assertThat(transactionManager.hasActiveTransaction()).isFalse();
            assertThat(transactionManager.getTransactionDepth()).isZero();
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
}
