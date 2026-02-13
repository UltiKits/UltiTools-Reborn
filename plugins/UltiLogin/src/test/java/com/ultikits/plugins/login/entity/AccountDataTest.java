package com.ultikits.plugins.login.entity;

import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import org.junit.jupiter.api.*;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccountData Entity Tests")
class AccountDataTest {

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should initialize with default values")
        void defaultValues() {
            AccountData account = new AccountData();

            assertThat(account.getLoginCount()).isEqualTo(0);
            assertThat(account.getFailedAttempts()).isEqualTo(0);
            assertThat(account.isEmailVerified()).isFalse();
            assertThat(account.getRegisterTime()).isGreaterThan(0);
            assertThat(account.getLastLogin()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should initialize null string fields as null")
        void nullStringFields() {
            AccountData account = new AccountData();

            assertThat(account.getPlayerUuid()).isNull();
            assertThat(account.getPlayerName()).isNull();
            assertThat(account.getPasswordHash()).isNull();
            assertThat(account.getSalt()).isNull();
            assertThat(account.getLastIp()).isNull();
            assertThat(account.getRegisterIp()).isNull();
            assertThat(account.getEmail()).isNull();
        }

        @Test
        @DisplayName("Should initialize lastFailedTime as 0")
        void lastFailedTimeDefault() {
            AccountData account = new AccountData();
            assertThat(account.getLastFailedTime()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should initialize id from parent as null")
        void idDefault() {
            AccountData account = new AccountData();
            assertThat(account.getId()).isNull();
        }

        @Test
        @DisplayName("Register time and last login should be close to current time")
        void timestampsCloseToNow() {
            long before = System.currentTimeMillis();
            AccountData account = new AccountData();
            long after = System.currentTimeMillis();

            assertThat(account.getRegisterTime()).isBetween(before, after);
            assertThat(account.getLastLogin()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSetters {

        @Test
        @DisplayName("Should get and set playerUuid")
        void playerUuid() {
            AccountData account = new AccountData();
            account.setPlayerUuid("uuid-123");
            assertThat(account.getPlayerUuid()).isEqualTo("uuid-123");
        }

        @Test
        @DisplayName("Should get and set playerName")
        void playerName() {
            AccountData account = new AccountData();
            account.setPlayerName("TestPlayer");
            assertThat(account.getPlayerName()).isEqualTo("TestPlayer");
        }

        @Test
        @DisplayName("Should get and set passwordHash")
        void passwordHash() {
            AccountData account = new AccountData();
            account.setPasswordHash("hash123");
            assertThat(account.getPasswordHash()).isEqualTo("hash123");
        }

        @Test
        @DisplayName("Should get and set salt")
        void salt() {
            AccountData account = new AccountData();
            account.setSalt("salt123");
            assertThat(account.getSalt()).isEqualTo("salt123");
        }

        @Test
        @DisplayName("Should get and set lastIp")
        void lastIp() {
            AccountData account = new AccountData();
            account.setLastIp("127.0.0.1");
            assertThat(account.getLastIp()).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("Should get and set registerIp")
        void registerIp() {
            AccountData account = new AccountData();
            account.setRegisterIp("192.168.1.1");
            assertThat(account.getRegisterIp()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("Should get and set email")
        void email() {
            AccountData account = new AccountData();
            account.setEmail("test@example.com");
            assertThat(account.getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should get and set emailVerified")
        void emailVerified() {
            AccountData account = new AccountData();
            account.setEmailVerified(true);
            assertThat(account.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("Should get and set loginCount")
        void loginCount() {
            AccountData account = new AccountData();
            account.setLoginCount(10);
            assertThat(account.getLoginCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should get and set failedAttempts")
        void failedAttempts() {
            AccountData account = new AccountData();
            account.setFailedAttempts(3);
            assertThat(account.getFailedAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should get and set lastFailedTime")
        void lastFailedTime() {
            AccountData account = new AccountData();
            long time = System.currentTimeMillis();
            account.setLastFailedTime(time);
            assertThat(account.getLastFailedTime()).isEqualTo(time);
        }

        @Test
        @DisplayName("Should get and set lastLogin")
        void lastLogin() {
            AccountData account = new AccountData();
            long time = System.currentTimeMillis();
            account.setLastLogin(time);
            assertThat(account.getLastLogin()).isEqualTo(time);
        }

        @Test
        @DisplayName("Should get and set registerTime")
        void registerTime() {
            AccountData account = new AccountData();
            long time = System.currentTimeMillis();
            account.setRegisterTime(time);
            assertThat(account.getRegisterTime()).isEqualTo(time);
        }

        @Test
        @DisplayName("Should get and set id from parent class")
        void id() {
            AccountData account = new AccountData();
            account.setId(123);
            assertThat(account.getId()).isEqualTo(123);
        }

        @Test
        @DisplayName("Should allow setting fields to null")
        void setToNull() {
            AccountData account = new AccountData();
            account.setPlayerUuid("uuid");
            account.setPlayerUuid(null);
            assertThat(account.getPlayerUuid()).isNull();

            account.setPlayerName("name");
            account.setPlayerName(null);
            assertThat(account.getPlayerName()).isNull();

            account.setPasswordHash("hash");
            account.setPasswordHash(null);
            assertThat(account.getPasswordHash()).isNull();

            account.setSalt("salt");
            account.setSalt(null);
            assertThat(account.getSalt()).isNull();

            account.setEmail("email");
            account.setEmail(null);
            assertThat(account.getEmail()).isNull();

            account.setLastIp("ip");
            account.setLastIp(null);
            assertThat(account.getLastIp()).isNull();

            account.setRegisterIp("ip");
            account.setRegisterIp(null);
            assertThat(account.getRegisterIp()).isNull();
        }

        @Test
        @DisplayName("Should handle boundary values for login count")
        void loginCountBoundary() {
            AccountData account = new AccountData();
            account.setLoginCount(Integer.MAX_VALUE);
            assertThat(account.getLoginCount()).isEqualTo(Integer.MAX_VALUE);

            account.setLoginCount(0);
            assertThat(account.getLoginCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle boundary values for failed attempts")
        void failedAttemptsBoundary() {
            AccountData account = new AccountData();
            account.setFailedAttempts(Integer.MAX_VALUE);
            assertThat(account.getFailedAttempts()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("equals")
    class Equals {

        private AccountData createFullAccount() {
            AccountData account = new AccountData();
            account.setId("id-1");
            account.setPlayerUuid("uuid-1");
            account.setPlayerName("Player1");
            account.setPasswordHash("hash1");
            account.setSalt("salt1");
            account.setLastIp("10.0.0.1");
            account.setLastLogin(1000L);
            account.setRegisterTime(2000L);
            account.setRegisterIp("10.0.0.2");
            account.setEmail("test@test.com");
            account.setEmailVerified(true);
            account.setLoginCount(5);
            account.setFailedAttempts(2);
            account.setLastFailedTime(3000L);
            return account;
        }

        @Test
        @DisplayName("Should be equal to itself (reflexive)")
        void equalToSelf() {
            AccountData account = createFullAccount();
            assertThat(account).isEqualTo(account);
        }

        @Test
        @DisplayName("Should be equal to identical copy (symmetric)")
        void equalToIdenticalCopy() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            assertThat(a).isEqualTo(b);
            assertThat(b).isEqualTo(a);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void notEqualToNull() {
            AccountData account = createFullAccount();
            assertThat(account).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void notEqualToDifferentType() {
            AccountData account = createFullAccount();
            assertThat(account).isNotEqualTo("not an AccountData");
            assertThat(account).isNotEqualTo(42);
            assertThat(account).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("Should not be equal when playerUuid differs")
        void differentPlayerUuid() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setPlayerUuid("uuid-different");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when playerName differs")
        void differentPlayerName() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setPlayerName("DifferentPlayer");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when passwordHash differs")
        void differentPasswordHash() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setPasswordHash("differentHash");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when salt differs")
        void differentSalt() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setSalt("differentSalt");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when lastIp differs")
        void differentLastIp() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setLastIp("192.168.0.1");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when lastLogin differs")
        void differentLastLogin() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setLastLogin(9999L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when registerTime differs")
        void differentRegisterTime() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setRegisterTime(9999L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when registerIp differs")
        void differentRegisterIp() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setRegisterIp("192.168.0.2");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when email differs")
        void differentEmail() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setEmail("other@example.com");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when emailVerified differs")
        void differentEmailVerified() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setEmailVerified(false);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when loginCount differs")
        void differentLoginCount() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setLoginCount(99);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when failedAttempts differs")
        void differentFailedAttempts() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setFailedAttempts(99);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when lastFailedTime differs")
        void differentLastFailedTime() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setLastFailedTime(9999L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when parent id differs")
        void differentId() {
            AccountData a = createFullAccount();
            AccountData b = createFullAccount();
            b.setId("id-different");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null fields in equals - one null one not")
        void nullFieldsInEquals() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setPlayerUuid("uuid");

            assertThat(a).isNotEqualTo(b);
            assertThat(b).isNotEqualTo(a);
        }

        @Test
        @DisplayName("Should be equal when both have null fields")
        void bothNullFields() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);

            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null playerName vs non-null")
        void nullPlayerName() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);
            a.setPlayerName(null);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setPlayerName("Player");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null passwordHash vs non-null")
        void nullPasswordHash() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setPasswordHash("hash");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null salt vs non-null")
        void nullSalt() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setSalt("salt");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null lastIp vs non-null")
        void nullLastIp() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setLastIp("10.0.0.1");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null registerIp vs non-null")
        void nullRegisterIp() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setRegisterIp("10.0.0.1");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null email vs non-null")
        void nullEmail() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setEmail("test@test.com");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null id (from parent) vs non-null")
        void nullId() {
            AccountData a = new AccountData();
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);
            b.setId("id-1");

            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("hashCode")
    class HashCode {

        @Test
        @DisplayName("Equal objects should have same hashCode")
        void equalObjectsSameHashCode() {
            AccountData a = new AccountData();
            a.setId("id");
            a.setPlayerUuid("uuid");
            a.setPlayerName("Player");
            a.setPasswordHash("hash");
            a.setSalt("salt");
            a.setLastIp("10.0.0.1");
            a.setLastLogin(1000L);
            a.setRegisterTime(2000L);
            a.setRegisterIp("10.0.0.2");
            a.setEmail("test@test.com");
            a.setEmailVerified(true);
            a.setLoginCount(5);
            a.setFailedAttempts(2);
            a.setLastFailedTime(3000L);

            AccountData b = new AccountData();
            b.setId("id");
            b.setPlayerUuid("uuid");
            b.setPlayerName("Player");
            b.setPasswordHash("hash");
            b.setSalt("salt");
            b.setLastIp("10.0.0.1");
            b.setLastLogin(1000L);
            b.setRegisterTime(2000L);
            b.setRegisterIp("10.0.0.2");
            b.setEmail("test@test.com");
            b.setEmailVerified(true);
            b.setLoginCount(5);
            b.setFailedAttempts(2);
            b.setLastFailedTime(3000L);

            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Different objects should likely have different hashCode")
        void differentObjectsDifferentHashCode() {
            AccountData a = new AccountData();
            a.setPlayerUuid("uuid-1");
            a.setRegisterTime(1000L);
            a.setLastLogin(1000L);

            AccountData b = new AccountData();
            b.setPlayerUuid("uuid-2");
            b.setRegisterTime(1000L);
            b.setLastLogin(1000L);

            assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("hashCode should be consistent for same object")
        void hashCodeConsistent() {
            AccountData account = new AccountData();
            account.setPlayerUuid("uuid");
            account.setRegisterTime(1000L);
            account.setLastLogin(1000L);

            int hash1 = account.hashCode();
            int hash2 = account.hashCode();
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("hashCode should handle all null fields")
        void hashCodeWithNulls() {
            AccountData a = new AccountData();
            a.setRegisterTime(0L);
            a.setLastLogin(0L);

            AccountData b = new AccountData();
            b.setRegisterTime(0L);
            b.setLastLogin(0L);

            // Should not throw and should be equal
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("hashCode changes when field changes")
        void hashCodeChangesWithField() {
            AccountData account = new AccountData();
            account.setRegisterTime(1000L);
            account.setLastLogin(1000L);
            int hash1 = account.hashCode();

            account.setPlayerUuid("new-uuid");
            int hash2 = account.hashCode();

            assertThat(hash1).isNotEqualTo(hash2);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("Should include class name")
        void includesClassName() {
            AccountData account = new AccountData();
            assertThat(account.toString()).contains("AccountData");
        }

        @Test
        @DisplayName("Should include field values")
        void includesFieldValues() {
            AccountData account = new AccountData();
            account.setPlayerUuid("uuid-test");
            account.setPlayerName("TestPlayer");
            account.setPasswordHash("hash-val");
            account.setSalt("salt-val");
            account.setEmail("user@domain.com");
            account.setLoginCount(42);

            String str = account.toString();
            assertThat(str).contains("uuid-test");
            assertThat(str).contains("TestPlayer");
            assertThat(str).contains("hash-val");
            assertThat(str).contains("salt-val");
            assertThat(str).contains("user@domain.com");
            assertThat(str).contains("42");
        }

        @Test
        @DisplayName("Should handle null fields in toString")
        void handlesNullFields() {
            AccountData account = new AccountData();
            // Should not throw
            String str = account.toString();
            assertThat(str).isNotNull();
            assertThat(str).contains("null");
        }

        @Test
        @DisplayName("Should include boolean field")
        void includesBooleanField() {
            AccountData account = new AccountData();
            account.setEmailVerified(true);
            String str = account.toString();
            assertThat(str).contains("true");
        }
    }

    @Nested
    @DisplayName("canEqual")
    class CanEqual {

        @Test
        @DisplayName("Should return true for same type")
        void sameType() {
            AccountData a = new AccountData();
            AccountData b = new AccountData();
            assertThat(a.canEqual(b)).isTrue();
        }

        @Test
        @DisplayName("Should return false for unrelated type")
        void unrelatedType() {
            AccountData account = new AccountData();
            assertThat(account.canEqual("string")).isFalse();
            assertThat(account.canEqual(42)).isFalse();
            assertThat(account.canEqual(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Table Annotation")
    class TableAnnotation {

        @Test
        @DisplayName("Should have table name login_accounts")
        void tableName() {
            AccountData account = new AccountData();
            Table table =
                account.getClass().getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.value()).isEqualTo("login_accounts");
        }
    }

    @Nested
    @DisplayName("Column Annotations")
    class ColumnAnnotations {

        @Test
        @DisplayName("Should have Column annotation on playerUuid")
        void playerUuidColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("playerUuid");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("player_uuid");
        }

        @Test
        @DisplayName("Should have Column annotation on playerName")
        void playerNameColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("playerName");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("player_name");
        }

        @Test
        @DisplayName("Should have Column annotation on passwordHash with VARCHAR(128)")
        void passwordHashColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("passwordHash");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("password_hash");
            assertThat(column.type()).isEqualTo("VARCHAR(128)");
        }

        @Test
        @DisplayName("Should have Column annotation on salt with VARCHAR(32)")
        void saltColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("salt");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("salt");
            assertThat(column.type()).isEqualTo("VARCHAR(32)");
        }

        @Test
        @DisplayName("Should have Column annotation on email with VARCHAR(100)")
        void emailColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("email");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("email");
            assertThat(column.type()).isEqualTo("VARCHAR(100)");
        }

        @Test
        @DisplayName("Should have Column annotation on lastIp with VARCHAR(45)")
        void lastIpColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("lastIp");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("last_ip");
            assertThat(column.type()).isEqualTo("VARCHAR(45)");
        }

        @Test
        @DisplayName("Should have Column annotation on registerIp with VARCHAR(45)")
        void registerIpColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("registerIp");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("register_ip");
            assertThat(column.type()).isEqualTo("VARCHAR(45)");
        }

        @Test
        @DisplayName("Should have Column annotation on emailVerified as BOOLEAN")
        void emailVerifiedColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("emailVerified");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("email_verified");
            assertThat(column.type()).isEqualTo("BOOLEAN");
        }

        @Test
        @DisplayName("Should have Column annotation on loginCount as INT")
        void loginCountColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("loginCount");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("login_count");
            assertThat(column.type()).isEqualTo("INT");
        }

        @Test
        @DisplayName("Should have Column annotation on failedAttempts as INT")
        void failedAttemptsColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("failedAttempts");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("failed_attempts");
            assertThat(column.type()).isEqualTo("INT");
        }

        @Test
        @DisplayName("Should have Column annotation on lastLogin as BIGINT")
        void lastLoginColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("lastLogin");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("last_login");
            assertThat(column.type()).isEqualTo("BIGINT");
        }

        @Test
        @DisplayName("Should have Column annotation on registerTime as BIGINT")
        void registerTimeColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("registerTime");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("register_time");
            assertThat(column.type()).isEqualTo("BIGINT");
        }

        @Test
        @DisplayName("Should have Column annotation on lastFailedTime as BIGINT")
        void lastFailedTimeColumn() throws NoSuchFieldException {
            Field field = AccountData.class.getDeclaredField("lastFailedTime");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("last_failed_time");
            assertThat(column.type()).isEqualTo("BIGINT");
        }
    }

    @Nested
    @DisplayName("Serializable")
    class Serializable {

        @Test
        @DisplayName("Should implement Serializable via parent")
        void isSerializable() {
            AccountData account = new AccountData();
            assertThat(account).isInstanceOf(java.io.Serializable.class);
        }
    }
}
