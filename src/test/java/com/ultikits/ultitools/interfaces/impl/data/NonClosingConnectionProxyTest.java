package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NonClosingConnectionProxy Tests")
class NonClosingConnectionProxyTest {

    private Connection delegate;
    private NonClosingConnectionProxy proxy;

    @BeforeEach
    void setUp() {
        delegate = mock(Connection.class);
        proxy = new NonClosingConnectionProxy(delegate);
    }

    @Nested
    @DisplayName("Close Suppression")
    class CloseSuppressionTests {

        @Test
        @DisplayName("close() should be a no-op")
        void closeShouldBeNoOp() throws SQLException {
            proxy.close();
            verify(delegate, never()).close();
        }

        @Test
        @DisplayName("close() called multiple times should not affect delegate")
        void multipleClosesShouldBeNoOp() throws SQLException {
            proxy.close();
            proxy.close();
            proxy.close();
            verify(delegate, never()).close();
        }

        @Test
        @DisplayName("isClosed() should delegate to real connection")
        void isClosedShouldDelegate() throws SQLException {
            when(delegate.isClosed()).thenReturn(false);
            assertThat(proxy.isClosed()).isFalse();

            when(delegate.isClosed()).thenReturn(true);
            assertThat(proxy.isClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Method Delegation")
    class DelegationTests {

        @Test
        @DisplayName("createStatement() should delegate")
        void createStatementShouldDelegate() throws SQLException {
            Statement mockStmt = mock(Statement.class);
            when(delegate.createStatement()).thenReturn(mockStmt);

            assertThat(proxy.createStatement()).isSameAs(mockStmt);
            verify(delegate).createStatement();
        }

        @Test
        @DisplayName("prepareStatement() should delegate")
        void prepareStatementShouldDelegate() throws SQLException {
            PreparedStatement mockPstmt = mock(PreparedStatement.class);
            when(delegate.prepareStatement("SELECT 1")).thenReturn(mockPstmt);

            assertThat(proxy.prepareStatement("SELECT 1")).isSameAs(mockPstmt);
            verify(delegate).prepareStatement("SELECT 1");
        }

        @Test
        @DisplayName("setAutoCommit() should delegate")
        void setAutoCommitShouldDelegate() throws SQLException {
            proxy.setAutoCommit(false);
            verify(delegate).setAutoCommit(false);
        }

        @Test
        @DisplayName("getAutoCommit() should delegate")
        void getAutoCommitShouldDelegate() throws SQLException {
            when(delegate.getAutoCommit()).thenReturn(false);
            assertThat(proxy.getAutoCommit()).isFalse();
        }

        @Test
        @DisplayName("commit() should delegate")
        void commitShouldDelegate() throws SQLException {
            proxy.commit();
            verify(delegate).commit();
        }

        @Test
        @DisplayName("rollback() should delegate")
        void rollbackShouldDelegate() throws SQLException {
            proxy.rollback();
            verify(delegate).rollback();
        }

        @Test
        @DisplayName("getMetaData() should delegate")
        void getMetaDataShouldDelegate() throws SQLException {
            DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);
            when(delegate.getMetaData()).thenReturn(mockMeta);

            assertThat(proxy.getMetaData()).isSameAs(mockMeta);
        }

        @Test
        @DisplayName("setTransactionIsolation() should delegate")
        void setTransactionIsolationShouldDelegate() throws SQLException {
            proxy.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(delegate).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        }

        @Test
        @DisplayName("isValid() should delegate")
        void isValidShouldDelegate() throws SQLException {
            when(delegate.isValid(5)).thenReturn(true);
            assertThat(proxy.isValid(5)).isTrue();
        }
    }

    @Nested
    @DisplayName("getRealConnection()")
    class GetRealConnectionTests {

        @Test
        @DisplayName("should return the delegate connection")
        void shouldReturnDelegate() {
            assertThat(proxy.getRealConnection()).isSameAs(delegate);
        }
    }

    @Nested
    @DisplayName("Wrapper Support")
    class WrapperTests {

        @Test
        @DisplayName("isWrapperFor() should return true for own type")
        void isWrapperForOwnType() throws SQLException {
            assertThat(proxy.isWrapperFor(NonClosingConnectionProxy.class)).isTrue();
        }

        @Test
        @DisplayName("isWrapperFor() should delegate for other types")
        void isWrapperForDelegates() throws SQLException {
            when(delegate.isWrapperFor(String.class)).thenReturn(false);
            assertThat(proxy.isWrapperFor(String.class)).isFalse();
        }

        @Test
        @DisplayName("unwrap() should return self for own type")
        void unwrapSelf() throws SQLException {
            assertThat(proxy.unwrap(NonClosingConnectionProxy.class)).isSameAs(proxy);
        }

        @Test
        @DisplayName("unwrap() should return self for Connection type")
        void unwrapConnection() throws SQLException {
            assertThat(proxy.unwrap(Connection.class)).isSameAs(proxy);
        }
    }
}
