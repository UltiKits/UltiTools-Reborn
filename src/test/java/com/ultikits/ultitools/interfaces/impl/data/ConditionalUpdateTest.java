package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code DataOperator#updateIf}: a write that applies only while the stored row still matches the
 * caller's expected conditions, and reports whether it applied (#543). UltiEconomy's wallet merge
 * conditions each account write on the balance it read and re-reads when the write does not apply
 * (maintainer decision 2026-09-29), so the scenario below is that one, on the JSON, SQLite and MySQL
 * operators (H2 in MySQL mode standing in for both relational engines).
 */
@DisplayName("DataOperator#updateIf applies only while the expected values still hold (#543)")
class ConditionalUpdateTest {

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    @Table("conditional_account")
    public static class Account extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("owner")
        private String owner;

        @Column(value = "balance", type = "DOUBLE")
        private double balance;

        public Account() {
        }

        public Account(String owner, double balance) {
            this.owner = owner;
            this.balance = balance;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public double getBalance() {
            return balance;
        }

        public void setBalance(double balance) {
            this.balance = balance;
        }
    }

    private static final class Backend {
        private final String label;
        private final Supplier<DataOperator<Account>> factory;

        private Backend(String label, Supplier<DataOperator<Account>> factory) {
            this.label = label;
            this.factory = factory;
        }
    }

    @BeforeAll
    static void initDataSource() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:conditionalupdate;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // No setPassword: the in-memory database is created without one on first connect.
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void dropTable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS conditional_account");
        }
    }

    private List<Backend> backends() {
        List<Backend> backends = new ArrayList<>();
        String jsonDir = tempDir.toFile().getAbsolutePath();
        backends.add(new Backend("json", () -> new SimpleJsonDataOperator<>(jsonDir, Account.class)));
        backends.add(new Backend("sqlite", () -> new SQLiteDataOperator<>(dataSource, Account.class)));
        backends.add(new Backend("mysql", () -> new MysqlDataOperator<>(dataSource, Account.class)));
        return backends;
    }

    private static WhereCondition balanceIs(double balance) {
        return WhereCondition.builder().column("balance").value(balance).build();
    }

    private void resetTable() throws Exception {
        dropTable();
    }

    @Nested
    @DisplayName("on every backend")
    class OnEveryBackend {

        @Test
        @DisplayName("applies and returns true while the stored balance still equals the one read")
        void appliesWhenExpectedHolds() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();
                Account account = new Account("alice", 100.0);
                operator.insert(account);

                Account read = operator.getById(account.getId());
                read.setBalance(read.getBalance() + 50.0);

                assertThat(operator.updateIf(read, balanceIs(100.0))).as(backend.label).isTrue();
                assertThat(operator.getById(account.getId()).getBalance()).as(backend.label).isEqualTo(150.0);
            }
        }

        @Test
        @DisplayName("returns false and writes nothing once the stored balance has changed")
        void refusesWhenExpectedNoLongerHolds() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();
                Account account = new Account("alice", 100.0);
                operator.insert(account);

                Account stale = operator.getById(account.getId());
                Account current = operator.getById(account.getId());
                current.setBalance(120.0);
                operator.update(current);

                stale.setBalance(stale.getBalance() + 50.0);
                stale.setOwner("overwritten");
                assertThat(operator.updateIf(stale, balanceIs(100.0))).as(backend.label).isFalse();

                Account stored = operator.getById(account.getId());
                assertThat(stored.getBalance()).as(backend.label).isEqualTo(120.0);
                assertThat(stored.getOwner()).as(backend.label).isEqualTo("alice");
            }
        }

        @Test
        @DisplayName("returns false for an id no row has")
        void falseForMissingRow() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();
                Account ghost = new Account("ghost", 1.0);
                ghost.setId("no-such-id");

                assertThat(operator.updateIf(ghost, balanceIs(1.0))).as(backend.label).isFalse();
                assertThat(operator.getById("no-such-id")).as(backend.label).isNull();
            }
        }

        @Test
        @DisplayName("throws DataAccessException for a null id")
        void throwsForNullId() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();

                assertThatThrownBy(() -> operator.updateIf(new Account("no-id", 1.0), balanceIs(1.0)))
                        .as(backend.label)
                        .isInstanceOf(DataAccessException.class);
            }
        }

        @Test
        @DisplayName("every expected condition must hold, compared with the same semantics as getAll")
        void everyConditionMustHold() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();
                Account account = new Account("O'Brien", 100.0);
                operator.insert(account);
                Account read = operator.getById(account.getId());
                read.setBalance(90.0);

                WhereCondition ownerMatches = WhereCondition.builder().column("owner").value("O'Brien").build();
                WhereCondition balanceAbove = WhereCondition.builder().column("balance").value(200.0)
                        .comparison(Comparison.GREATER).build();
                assertThat(operator.updateIf(read, ownerMatches, balanceAbove))
                        .as("%s: applied although one of two conditions failed", backend.label).isFalse();
                assertThat(operator.updateIf(read, ownerMatches, balanceIs(100.0)))
                        .as("%s: a value containing a quote did not match", backend.label).isTrue();
                assertThat(operator.getById(account.getId()).getBalance()).as(backend.label).isEqualTo(90.0);
            }
        }
    }

    @Nested
    @DisplayName("conditions it cannot evaluate are refused on every backend")
    class RefusedConditions {

        @Test
        @DisplayName("a column the entity does not map throws DataAccessException instead of returning false")
        void unknownColumnThrows() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();
                Account account = new Account("alice", 100.0);
                operator.insert(account);
                Account read = operator.getById(account.getId());

                assertThatThrownBy(() -> operator.updateIf(read,
                        WhereCondition.builder().column("balanc").value(100.0).build()))
                        .as("%s: a misspelt column would make a compare-and-set loop retry forever", backend.label)
                        .isInstanceOf(DataAccessException.class);
            }
        }

        /**
         * Expectation changed by the maintainer's rule of 2026-10-06 (#640): a null expected value
         * under the default EQUAL comparison means IS NULL (JSON: absent or JSON null), so it is no
         * longer refused. The method keeps its name so the history of this assertion stays readable.
         */
        @Test
        @DisplayName("a null expected value means IS NULL: on a NULL column the write applies (#640)")
        void nullExpectedValueThrows() throws Exception {
            for (Backend backend : backends()) {
                resetTable();
                DataOperator<Account> operator = backend.factory.get();
                Account account = new Account(null, 100.0);
                operator.insert(account);
                Account read = operator.getById(account.getId());
                read.setOwner("claimed");

                Throwable thrown = catchThrowable(() -> assertThat(operator.updateIf(read,
                        WhereCondition.builder().column("owner").value(null).build()))
                        .as(backend.label).isTrue());
                assertThat(thrown).as("%s: updateIf with a null expected value", backend.label).isNull();
                assertThat(operator.getById(account.getId()).getOwner()).as(backend.label).isEqualTo("claimed");
            }
        }
    }

    @Nested
    @DisplayName("two operators over one database")
    class TwoWriters {

        @Test
        @DisplayName("the second writer's update, conditioned on the balance it read before the first wrote, does not apply")
        void secondWriterLoses() {
            SQLiteDataOperator<Account> serverA = new SQLiteDataOperator<>(dataSource, Account.class);
            SQLiteDataOperator<Account> serverB = new SQLiteDataOperator<>(dataSource, Account.class);
            Account account = new Account("alice", 100.0);
            serverA.insert(account);

            Account readByA = serverA.getById(account.getId());
            Account readByB = serverB.getById(account.getId());

            readByA.setBalance(readByA.getBalance() + 50.0);
            assertThat(serverA.updateIf(readByA, balanceIs(100.0))).isTrue();

            readByB.setBalance(readByB.getBalance() + 30.0);
            assertThat(serverB.updateIf(readByB, balanceIs(100.0)))
                    .as("the stalled writer applied on top of the other server's write")
                    .isFalse();

            assertThat(serverA.getById(account.getId()).getBalance()).isEqualTo(150.0);
        }
    }

    @Nested
    @DisplayName("an implementation that does not provide it")
    class NotImplemented {

        @Test
        @DisplayName("the default throws UnsupportedOperationException naming the implementing class")
        void defaultThrowsNamingTheClass() {
            @SuppressWarnings("unchecked")
            DataOperator<Account> foreign = mock(DataOperator.class, CALLS_REAL_METHODS);
            Account account = new Account("alice", 1.0);
            account.setId("id-1");

            assertThatThrownBy(() -> foreign.updateIf(account, balanceIs(1.0)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining(foreign.getClass().getName());
        }
    }
}
