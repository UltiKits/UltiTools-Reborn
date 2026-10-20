package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link RegistryKey}'s string form to japicmp's own {@code Class#method(paramTypes)} filter
 * syntax (D-22) — the single identifier space serving as registry primary key, japicmp
 * {@code <exclude>} entry, and D-01's report join key.
 */
@DisplayName("RegistryKey tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RegistryKeyTest {

    @Nested
    @DisplayName("string form")
    class StringFormTests {

        @Test
        @DisplayName("Test 10: class-level key round-trips as bare FQN, no # and no parens")
        void classLevelKeyIsBareFqn() {
            RegistryKey key = RegistryKey.forClass("com.ultikits.ultitools.aop.CglibProxyFactory");
            assertThat(key.toString()).isEqualTo("com.ultikits.ultitools.aop.CglibProxyFactory");
            assertThat(key.toString()).doesNotContain("#").doesNotContain("(").doesNotContain(")");
        }

        @Test
        @DisplayName("Test 11: member key renders with fully-qualified, comma-separated parameter types")
        void memberKeyRendersFullyQualifiedParams() {
            RegistryKey key = RegistryKey.forMember(
                    "com.ultikits.ultitools.manager.FileOperationManager",
                    "isPathAllowed",
                    Collections.singletonList("java.lang.String"));
            assertThat(key.toString())
                    .isEqualTo("com.ultikits.ultitools.manager.FileOperationManager#isPathAllowed(java.lang.String)");
        }

        @Test
        @DisplayName("Test 13: zero-argument member renders with empty parens, not bare")
        void zeroArgumentMemberRendersWithEmptyParens() {
            RegistryKey key = RegistryKey.forMember(
                    "com.ultikits.ultitools.manager.CommandManager",
                    "registerAll",
                    Collections.emptyList());
            assertThat(key.toString())
                    .isEqualTo("com.ultikits.ultitools.manager.CommandManager#registerAll()");
        }
    }

    @Nested
    @DisplayName("GEN-04 adjacency: collision resistance")
    class CollisionTests {

        @Test
        @DisplayName("Test 12: the two CommandManager.register overloads produce distinct keys")
        void overloadedMethodsProduceDistinctKeys() {
            RegistryKey fourArg = RegistryKey.forMember(
                    "com.ultikits.ultitools.manager.CommandManager",
                    "register",
                    Arrays.asList("org.bukkit.command.CommandExecutor", "java.lang.String", "java.lang.String", "java.lang.String[]"));
            RegistryKey oneArg = RegistryKey.forMember(
                    "com.ultikits.ultitools.manager.CommandManager",
                    "register",
                    Collections.singletonList("org.bukkit.command.CommandExecutor"));

            assertThat(fourArg).isNotEqualTo(oneArg);
            assertThat(fourArg.toString()).isNotEqualTo(oneArg.toString());
        }
    }

    @Nested
    @DisplayName("GEN-04 ordering: total order + equals/hashCode consistency")
    class OrderingTests {

        @Test
        @DisplayName("Test 14a: equals/hashCode consistent with the string form")
        void equalsAndHashCodeAreConsistent() {
            RegistryKey a = RegistryKey.forClass("com.ultikits.ultitools.aop.CglibProxyFactory");
            RegistryKey b = RegistryKey.forClass("com.ultikits.ultitools.aop.CglibProxyFactory");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Test 14b: compareTo imposes a total, deterministic order over a mixed key set")
        void compareToImposesDeterministicTotalOrder() {
            List<RegistryKey> keys = new ArrayList<>(Arrays.asList(
                    RegistryKey.forMember("com.ultikits.ultitools.manager.CommandManager", "register",
                            Collections.singletonList("org.bukkit.command.CommandExecutor")),
                    RegistryKey.forClass("com.ultikits.ultitools.aop.CglibProxyFactory"),
                    RegistryKey.forField("com.ultikits.ultitools.UltiTools", "versionWrapper"),
                    RegistryKey.forMember("com.ultikits.ultitools.manager.CommandManager", "register",
                            Arrays.asList("org.bukkit.command.CommandExecutor", "java.lang.String", "java.lang.String", "java.lang.String[]"))
            ));

            List<RegistryKey> sortedOnce = new ArrayList<>(keys);
            Collections.sort(sortedOnce);

            List<RegistryKey> shuffledInput = new ArrayList<>(Arrays.asList(
                    keys.get(3), keys.get(1), keys.get(2), keys.get(0)
            ));
            List<RegistryKey> sortedTwice = new ArrayList<>(shuffledInput);
            Collections.sort(sortedTwice);

            assertThat(sortedOnce).extracting(RegistryKey::toString)
                    .containsExactlyElementsOf(
                            sortedTwice.stream().map(RegistryKey::toString).collect(java.util.stream.Collectors.toList()));
        }
    }
}
