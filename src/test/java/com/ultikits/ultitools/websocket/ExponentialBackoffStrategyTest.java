package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ExponentialBackoffStrategy 测试类
 */
@DisplayName("ExponentialBackoffStrategy 测试")
class ExponentialBackoffStrategyTest {

    @Nested
    @DisplayName("默认构造函数测试")
    class DefaultConstructorTests {

        @Test
        @DisplayName("默认构造函数应该使用默认值")
        void shouldUseDefaultValues() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            assertThat(strategy.getAttemptCount()).isZero();
            assertThat(strategy.shouldContinue()).isTrue(); // 默认无限重试
        }

        @Test
        @DisplayName("第一次延迟应该是初始延迟")
        void firstDelayShouldBeInitialDelay() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            long firstDelay = strategy.getNextDelay();

            assertThat(firstDelay).isEqualTo(ExponentialBackoffStrategy.DEFAULT_INITIAL_DELAY);
            assertThat(strategy.getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("延迟应该指数增长")
        void delayShouldGrowExponentially() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            long delay1 = strategy.getNextDelay(); // 5000
            long delay2 = strategy.getNextDelay(); // 10000
            long delay3 = strategy.getNextDelay(); // 20000
            long delay4 = strategy.getNextDelay(); // 40000

            assertThat(delay1).isEqualTo(5000L);
            assertThat(delay2).isEqualTo(10000L);
            assertThat(delay3).isEqualTo(20000L);
            assertThat(delay4).isEqualTo(40000L);
        }

        @Test
        @DisplayName("延迟不应该超过最大值")
        void delayShouldNotExceedMaxDelay() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            // 多次调用直到超过最大延迟
            long delay = 0;
            for (int i = 0; i < 20; i++) {
                delay = strategy.getNextDelay();
            }

            assertThat(delay).isLessThanOrEqualTo(ExponentialBackoffStrategy.DEFAULT_MAX_DELAY);
        }
    }

    @Nested
    @DisplayName("自定义构造函数测试")
    class CustomConstructorTests {

        @Test
        @DisplayName("应该使用自定义初始延迟")
        void shouldUseCustomInitialDelay() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, 0);

            long firstDelay = strategy.getNextDelay();

            assertThat(firstDelay).isEqualTo(1000L);
        }

        @Test
        @DisplayName("应该使用自定义乘数")
        void shouldUseCustomMultiplier() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(1000L, 60000L, 3.0, 0);

            long delay1 = strategy.getNextDelay(); // 1000
            long delay2 = strategy.getNextDelay(); // 3000
            long delay3 = strategy.getNextDelay(); // 9000

            assertThat(delay1).isEqualTo(1000L);
            assertThat(delay2).isEqualTo(3000L);
            assertThat(delay3).isEqualTo(9000L);
        }

        @Test
        @DisplayName("应该遵守最大尝试次数")
        void shouldRespectMaxAttempts() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, 3);

            assertThat(strategy.shouldContinue()).isTrue();
            strategy.getNextDelay();
            assertThat(strategy.shouldContinue()).isTrue();
            strategy.getNextDelay();
            assertThat(strategy.shouldContinue()).isTrue();
            strategy.getNextDelay();
            assertThat(strategy.shouldContinue()).isFalse();
        }

        @Test
        @DisplayName("初始延迟为0或负数时应该抛出异常")
        void shouldThrowExceptionForInvalidInitialDelay() {
            assertThatThrownBy(() -> new ExponentialBackoffStrategy(0, 60000L, 2.0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Initial delay must be positive");

            assertThatThrownBy(() -> new ExponentialBackoffStrategy(-1000L, 60000L, 2.0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Initial delay must be positive");
        }

        @Test
        @DisplayName("最大延迟小于初始延迟时应该抛出异常")
        void shouldThrowExceptionForInvalidMaxDelay() {
            assertThatThrownBy(() -> new ExponentialBackoffStrategy(5000L, 1000L, 2.0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max delay must be >= initial delay");
        }

        @Test
        @DisplayName("乘数小于1时应该抛出异常")
        void shouldThrowExceptionForInvalidMultiplier() {
            assertThatThrownBy(() -> new ExponentialBackoffStrategy(1000L, 60000L, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Multiplier must be >= 1.0");
        }
    }

    @Nested
    @DisplayName("reset 方法测试")
    class ResetTests {

        @Test
        @DisplayName("reset 应该将尝试计数归零")
        void resetShouldZeroAttemptCount() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            strategy.getNextDelay();
            strategy.getNextDelay();
            assertThat(strategy.getAttemptCount()).isEqualTo(2);

            strategy.reset();

            assertThat(strategy.getAttemptCount()).isZero();
        }

        @Test
        @DisplayName("reset 后第一次延迟应该是初始值")
        void resetShouldRestoreInitialDelay() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            strategy.getNextDelay();
            strategy.getNextDelay();
            strategy.getNextDelay();
            strategy.reset();

            long delay = strategy.getNextDelay();
            assertThat(delay).isEqualTo(ExponentialBackoffStrategy.DEFAULT_INITIAL_DELAY);
        }
    }

    @Nested
    @DisplayName("静态工厂方法测试")
    class FactoryMethodTests {

        @Test
        @DisplayName("unlimited() 应该创建无限重试策略")
        void unlimitedShouldCreateUnlimitedStrategy() {
            ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy.unlimited();

            // 即使多次尝试也应该继续
            for (int i = 0; i < 100; i++) {
                assertThat(strategy.shouldContinue()).isTrue();
                strategy.getNextDelay();
            }
            assertThat(strategy.shouldContinue()).isTrue();
        }

        @Test
        @DisplayName("withMaxAttempts() 应该创建有限重试策略")
        void withMaxAttemptsShouldCreateLimitedStrategy() {
            ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy.withMaxAttempts(5);

            for (int i = 0; i < 5; i++) {
                assertThat(strategy.shouldContinue()).isTrue();
                strategy.getNextDelay();
            }
            assertThat(strategy.shouldContinue()).isFalse();
        }
    }

    @Nested
    @DisplayName("Builder 测试")
    class BuilderTests {

        @Test
        @DisplayName("builder 应该创建自定义策略")
        void builderShouldCreateCustomStrategy() {
            ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy.builder()
                .initialDelay(2000L)
                .maxDelay(30000L)
                .multiplier(1.5)
                .maxAttempts(10)
                .build();

            long delay1 = strategy.getNextDelay();
            assertThat(delay1).isEqualTo(2000L);

            long delay2 = strategy.getNextDelay();
            assertThat(delay2).isEqualTo(3000L); // 2000 * 1.5 = 3000
        }

        @Test
        @DisplayName("builder 应该支持链式调用")
        void builderShouldSupportChaining() {
            ExponentialBackoffStrategy.Builder builder = ExponentialBackoffStrategy.builder();

            // 每个方法都应该返回 Builder 实例
            assertThat(builder.initialDelay(1000L)).isSameAs(builder);
            assertThat(builder.maxDelay(60000L)).isSameAs(builder);
            assertThat(builder.multiplier(2.0)).isSameAs(builder);
            assertThat(builder.maxAttempts(5)).isSameAs(builder);
        }

        @Test
        @DisplayName("builder 默认值应该和默认构造函数一致")
        void builderDefaultsShouldMatchDefaultConstructor() {
            ExponentialBackoffStrategy builderStrategy = ExponentialBackoffStrategy.builder().build();
            ExponentialBackoffStrategy defaultStrategy = new ExponentialBackoffStrategy();

            assertThat(builderStrategy.getNextDelay()).isEqualTo(defaultStrategy.getNextDelay());
        }
    }

    @Nested
    @DisplayName("常量值测试")
    class ConstantsTests {

        @Test
        @DisplayName("DEFAULT_INITIAL_DELAY 应该是 5000")
        void defaultInitialDelayShouldBe5000() {
            assertThat(ExponentialBackoffStrategy.DEFAULT_INITIAL_DELAY).isEqualTo(5000L);
        }

        @Test
        @DisplayName("DEFAULT_MAX_DELAY 应该是 300000 (5分钟)")
        void defaultMaxDelayShouldBe300000() {
            assertThat(ExponentialBackoffStrategy.DEFAULT_MAX_DELAY).isEqualTo(300000L);
        }

        @Test
        @DisplayName("DEFAULT_MULTIPLIER 应该是 2.0")
        void defaultMultiplierShouldBe2() {
            assertThat(ExponentialBackoffStrategy.DEFAULT_MULTIPLIER).isEqualTo(2.0);
        }

        @Test
        @DisplayName("DEFAULT_MAX_ATTEMPTS 应该是 0 (无限)")
        void defaultMaxAttemptsShouldBe0() {
            assertThat(ExponentialBackoffStrategy.DEFAULT_MAX_ATTEMPTS).isZero();
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("乘数为1.0时延迟应该保持不变")
        void multiplierOneShouldKeepConstantDelay() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(1000L, 60000L, 1.0, 0);

            for (int i = 0; i < 5; i++) {
                assertThat(strategy.getNextDelay()).isEqualTo(1000L);
            }
        }

        @Test
        @DisplayName("初始延迟等于最大延迟时应该正常工作")
        void initialEqualsMaxShouldWork() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(5000L, 5000L, 2.0, 0);

            for (int i = 0; i < 5; i++) {
                assertThat(strategy.getNextDelay()).isEqualTo(5000L);
            }
        }

        @Test
        @DisplayName("maxAttempts 为 1 时只能尝试一次")
        void maxAttemptsOneShouldAllowOnlyOneAttempt() {
            ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy.withMaxAttempts(1);

            assertThat(strategy.shouldContinue()).isTrue();
            strategy.getNextDelay();
            assertThat(strategy.shouldContinue()).isFalse();
        }
    }

    @Nested
    @DisplayName("ReconnectStrategy 接口实现测试")
    class InterfaceImplementationTests {

        @Test
        @DisplayName("应该实现 ReconnectStrategy 接口")
        void shouldImplementReconnectStrategy() {
            ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy();

            assertThat(strategy).isInstanceOf(ReconnectStrategy.class);
        }

        @Test
        @DisplayName("所有接口方法都应该正常工作")
        void allInterfaceMethodsShouldWork() {
            ReconnectStrategy strategy = new ExponentialBackoffStrategy(1000L, 10000L, 2.0, 5);

            // getNextDelay
            assertThat(strategy.getNextDelay()).isEqualTo(1000L);

            // getAttemptCount
            assertThat(strategy.getAttemptCount()).isEqualTo(1);

            // shouldContinue
            assertThat(strategy.shouldContinue()).isTrue();

            // reset
            strategy.reset();
            assertThat(strategy.getAttemptCount()).isZero();
        }
    }
}
