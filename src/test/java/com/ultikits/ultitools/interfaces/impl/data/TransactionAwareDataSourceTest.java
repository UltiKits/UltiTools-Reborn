package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.interfaces.TransactionManager;

@DisplayName("TransactionAwareDataSource Tests")
class TransactionAwareDataSourceTest {

    private DataSource delegate;
    private TransactionManager txManager;
    private TransactionAwareDataSource txDataSource;

    @BeforeEach
    void setUp() {
        delegate = mock(DataSource.class);
        txManager = mock(TransactionManager.class);
        txDataSource = new TransactionAwareDataSource(delegate, () -> txManager);
    }

    @Nested
    @DisplayName("Connection Routing")
    class ConnectionRoutingTests {

        @Test
        @DisplayName("Should return delegate connection when no active transaction")
        void noTransactionReturnsDelegateConnection() throws SQLException {
            Connection delegateConn = mock(Connection.class);
            when(txManager.hasActiveTransaction()).thenReturn(false);
            when(delegate.getConnection()).thenReturn(delegateConn);

            Connection conn = txDataSource.getConnection();

            assertThat(conn).isSameAs(delegateConn);
            verify(delegate).getConnection();
        }

        @Test
        @DisplayName("Should return wrapped transaction connection when transaction active")
        void activeTransactionReturnsWrappedConnection() throws SQLException {
            Connection txConn = mock(Connection.class);
            when(txManager.hasActiveTransaction()).thenReturn(true);
            when(txManager.getConnection()).thenReturn(txConn);

            Connection conn = txDataSource.getConnection();

            assertThat(conn).isInstanceOf(NonClosingConnectionProxy.class);
            NonClosingConnectionProxy proxy = (NonClosingConnectionProxy) conn;
            assertThat(proxy.getRealConnection()).isSameAs(txConn);
        }

        @Test
        @DisplayName("Should return delegate connection when TransactionManager is null")
        void nullTransactionManagerReturnsDelegateConnection() throws SQLException {
            TransactionAwareDataSource nullTxDs = new TransactionAwareDataSource(delegate, () -> null);
            Connection delegateConn = mock(Connection.class);
            when(delegate.getConnection()).thenReturn(delegateConn);

            Connection conn = nullTxDs.getConnection();

            assertThat(conn).isSameAs(delegateConn);
        }

        @Test
        @DisplayName("getConnection(user, password) should route through transaction when active")
        void getConnectionWithCredentialsShouldRoute() throws SQLException {
            Connection txConn = mock(Connection.class);
            when(txManager.hasActiveTransaction()).thenReturn(true);
            when(txManager.getConnection()).thenReturn(txConn);

            Connection conn = txDataSource.getConnection("user", "pass");

            assertThat(conn).isInstanceOf(NonClosingConnectionProxy.class);
        }

        @Test
        @DisplayName("getConnection(user, password) should delegate when no transaction")
        void getConnectionWithCredentialsShouldDelegateNoTx() throws SQLException {
            Connection delegateConn = mock(Connection.class);
            when(txManager.hasActiveTransaction()).thenReturn(false);
            when(delegate.getConnection("user", "pass")).thenReturn(delegateConn);

            Connection conn = txDataSource.getConnection("user", "pass");

            assertThat(conn).isSameAs(delegateConn);
        }
    }

    @Nested
    @DisplayName("NonClosingConnectionProxy behavior during transaction")
    class TransactionConnectionBehavior {

        @Test
        @DisplayName("close() on transaction connection should be suppressed")
        void closeSuppressedDuringTransaction() throws SQLException {
            Connection txConn = mock(Connection.class);
            when(txManager.hasActiveTransaction()).thenReturn(true);
            when(txManager.getConnection()).thenReturn(txConn);

            Connection conn = txDataSource.getConnection();
            conn.close(); // Should be no-op

            verify(txConn, never()).close();
        }

        @Test
        @DisplayName("close() on non-transaction connection should work normally")
        void closeWorksWithoutTransaction() throws SQLException {
            Connection delegateConn = mock(Connection.class);
            when(txManager.hasActiveTransaction()).thenReturn(false);
            when(delegate.getConnection()).thenReturn(delegateConn);

            Connection conn = txDataSource.getConnection();
            conn.close(); // Should actually close

            verify(delegateConn).close();
        }
    }

    @Nested
    @DisplayName("DataSource Method Delegation")
    class DataSourceDelegation {

        @Test
        @DisplayName("getLoginTimeout() should delegate")
        void getLoginTimeoutShouldDelegate() throws SQLException {
            when(delegate.getLoginTimeout()).thenReturn(30);
            assertThat(txDataSource.getLoginTimeout()).isEqualTo(30);
        }

        @Test
        @DisplayName("setLoginTimeout() should delegate")
        void setLoginTimeoutShouldDelegate() throws SQLException {
            txDataSource.setLoginTimeout(60);
            verify(delegate).setLoginTimeout(60);
        }

        @Test
        @DisplayName("isWrapperFor() should return true for own type")
        void isWrapperForOwnType() throws SQLException {
            assertThat(txDataSource.isWrapperFor(TransactionAwareDataSource.class)).isTrue();
        }
    }
}
