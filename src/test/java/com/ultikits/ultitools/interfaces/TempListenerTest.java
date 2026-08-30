package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

import com.ultikits.ultitools.interfaces.TempListener.DefaultTempListenerBuilder;
import com.ultikits.ultitools.interfaces.impl.SimpleTempListener;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * TempListener 接口测试 - 使用 MockBukkit
 */
@DisplayName("TempListener 接口测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TempListenerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("DefaultTempListenerBuilder 测试")
    class DefaultTempListenerBuilderTests {

        @Test
        @DisplayName("应该创建默认优先级的监听器")
        void shouldCreateListenerWithDefaultPriority() {
            // Arrange
            DefaultTempListenerBuilder<PlayerJoinEvent> builder = TempListener.common(PlayerJoinEvent.class);

            // Act
            TempListener listener = builder
                    .eventHandler(event -> false)
                    .build();

            // Assert
            assertThat(listener).isNotNull();
            assertThat(listener).isInstanceOf(SimpleTempListener.class);
        }

        @Test
        @DisplayName("应该设置自定义优先级")
        void shouldSetCustomPriority() {
            // Arrange & Act
            DefaultTempListenerBuilder<PlayerJoinEvent> builder = TempListener.common(PlayerJoinEvent.class)
                    .priority(EventPriority.HIGH)
                    .eventHandler(event -> false);

            TempListener listener = builder.build();

            // Assert
            assertThat(listener).isNotNull();
            SimpleTempListener<?> simpleListener = (SimpleTempListener<?>) listener;
            assertThat(simpleListener.getPriority()).isEqualTo(EventPriority.HIGH);
        }

        @Test
        @DisplayName("应该设置过滤器 (SILENT-12: build() 必须传递过滤器)")
        void shouldSetFilter() {
            // Arrange
            AtomicBoolean filterCalled = new AtomicBoolean(false);
            AtomicBoolean eventHandled = new AtomicBoolean(false);

            // Act - the filter rejects every event
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> {
                        filterCalled.set(true);
                        return false;
                    })
                    .eventHandler(event -> {
                        eventHandled.set(true);
                        return false;
                    })
                    .build();
            listener.register();

            server.addPlayer("RejectedByFilter");

            // Assert - the filter set via .build() was actually consulted, and a rejected
            // event never reaches the handler. On the pre-fix build() (which drops the
            // filter to its (ignored) -> true default) this assertion fails: filterCalled
            // stays false and eventHandled becomes true.
            assertThat(filterCalled.get()).isTrue();
            assertThat(eventHandled.get()).isFalse();
        }

        @Test
        @DisplayName("build() 的监听器应该恰好处理一次被过滤器接受的事件")
        void shouldHandleAcceptedEventExactlyOnceViaBuild() {
            // Arrange
            AtomicInteger handledCount = new AtomicInteger(0);

            // Act
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> true)
                    .eventHandler(event -> {
                        handledCount.incrementAndGet();
                        return false;
                    })
                    .build();
            listener.register();

            server.addPlayer("AcceptedByFilter");

            // Assert
            assertThat(handledCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("未调用 .filter(...) 时 build() 的监听器应该处理所有事件")
        void shouldHandleAllEventsWhenNoFilterSetViaBuild() {
            // Arrange
            AtomicInteger handledCount = new AtomicInteger(0);

            // Act - no .filter(...) call: the field default (ignored) -> true must still apply
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .eventHandler(event -> {
                        handledCount.incrementAndGet();
                        return false;
                    })
                    .build();
            listener.register();

            server.addPlayer("Player1");
            server.addPlayer("Player2");

            // Assert
            assertThat(handledCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("装箱 Boolean 过滤结果应该与基本类型行为一致")
        void shouldTreatBoxedBooleanFilterResultsLikePrimitivesViaBuild() {
            // Arrange
            AtomicInteger handledCount = new AtomicInteger(0);

            // Act - filter explicitly returns boxed Boolean.TRUE / Boolean.FALSE
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> event.getPlayer().getName().equals("BoxedAccepted")
                            ? Boolean.TRUE : Boolean.FALSE)
                    .eventHandler(event -> {
                        handledCount.incrementAndGet();
                        return false;
                    })
                    .build();
            listener.register();

            server.addPlayer("BoxedRejected");
            server.addPlayer("BoxedAccepted");

            // Assert
            assertThat(handledCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("过滤器返回 null 时应该丢弃事件且不向外传播拆箱异常")
        void shouldDropEventWhenFilterReturnsNullViaBuild() {
            // Arrange
            AtomicInteger handledCount = new AtomicInteger(0);

            // Act - Function<E, Boolean> permits a null return
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> null)
                    .eventHandler(event -> {
                        handledCount.incrementAndGet();
                        return false;
                    })
                    .build();
            listener.register();

            // Assert - firing the event must not throw an unboxing NullPointerException,
            // and a null filter result is treated as non-matching (event dropped)
            assertThatCode(() -> server.addPlayer("NullFilterResult")).doesNotThrowAnyException();
            assertThat(handledCount.get()).isZero();
        }

        @Test
        @DisplayName("同优先级的多个监听器都应该收到匹配事件")
        void shouldDeliverToAllListenersAtSamePriorityViaBuild() {
            // Arrange
            AtomicInteger firstHandled = new AtomicInteger(0);
            AtomicInteger secondHandled = new AtomicInteger(0);

            // Act - two independently built listeners, same event class, same (default) priority
            TempListener first = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> true)
                    .eventHandler(event -> {
                        firstHandled.incrementAndGet();
                        return false;
                    })
                    .build();
            TempListener second = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> true)
                    .eventHandler(event -> {
                        secondHandled.incrementAndGet();
                        return false;
                    })
                    .build();
            first.register();
            second.register();

            server.addPlayer("SharedPriorityPlayer");

            // Assert - both receive the matching event; relative order is Bukkit's own and
            // is not asserted here.
            assertThat(firstHandled.get()).isEqualTo(1);
            assertThat(secondHandled.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("重复调用 register() 不应该重复处理同一事件")
        void shouldNotDeliverTwiceWhenRegisteredTwiceViaBuild() {
            // Arrange
            AtomicInteger handledCount = new AtomicInteger(0);

            // Act
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> true)
                    .eventHandler(event -> {
                        handledCount.incrementAndGet();
                        return false;
                    })
                    .build();
            listener.register();
            listener.register();

            server.addPlayer("DoubleRegisteredPlayer");

            // Assert
            assertThat(handledCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("listen 方法应该注册并触发事件")
        void listenShouldRegisterAndTrigger() {
            // Arrange
            AtomicBoolean eventHandled = new AtomicBoolean(false);

            // Act
            TempListener.common(PlayerJoinEvent.class)
                    .listen(event -> {
                        eventHandled.set(true);
                        return true; // 返回 true 会自动注销
                    });

            // 触发事件
            server.addPlayer("TestPlayer");

            // Assert
            assertThat(eventHandled.get()).isTrue();
        }

        @Test
        @DisplayName("过滤器返回 false 时不应该处理事件")
        void shouldNotHandleWhenFilterReturnsFalse() {
            // Arrange
            AtomicBoolean eventHandled = new AtomicBoolean(false);

            // Act
            TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> event.getPlayer().getName().equals("SpecificPlayer"))
                    .listen(event -> {
                        eventHandled.set(true);
                        return true;
                    });

            // 触发事件 - 玩家名不匹配
            server.addPlayer("OtherPlayer");

            // Assert
            assertThat(eventHandled.get()).isFalse();
        }

        @Test
        @DisplayName("过滤器返回 true 时应该处理事件")
        void shouldHandleWhenFilterReturnsTrue() {
            // Arrange
            AtomicBoolean eventHandled = new AtomicBoolean(false);

            // Act
            TempListener.common(PlayerJoinEvent.class)
                    .filter(event -> event.getPlayer().getName().equals("TargetPlayer"))
                    .listen(event -> {
                        eventHandled.set(true);
                        return true;
                    });

            // 触发事件 - 玩家名匹配
            server.addPlayer("TargetPlayer");

            // Assert
            assertThat(eventHandled.get()).isTrue();
        }

        @Test
        @DisplayName("handler 返回 false 时监听器应该保持注册")
        void shouldKeepListenerWhenHandlerReturnsFalse() {
            // Arrange
            AtomicInteger eventCount = new AtomicInteger(0);

            // Act
            TempListener.common(PlayerJoinEvent.class)
                    .listen(event -> {
                        eventCount.incrementAndGet();
                        return false; // 不注销
                    });

            // 触发多个事件
            server.addPlayer("Player1");
            server.addPlayer("Player2");

            // Assert
            assertThat(eventCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("handler 返回 true 时监听器应该自动注销")
        void shouldUnregisterWhenHandlerReturnsTrue() {
            // Arrange
            AtomicInteger eventCount = new AtomicInteger(0);

            // Act
            TempListener.common(PlayerJoinEvent.class)
                    .listen(event -> {
                        eventCount.incrementAndGet();
                        return true; // 注销
                    });

            // 触发多个事件
            server.addPlayer("Player1");
            server.addPlayer("Player2");

            // Assert - 只处理第一个事件
            assertThat(eventCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("TempListener.unregister 测试")
    class UnregisterTests {

        @Test
        @DisplayName("手动注销监听器后不应该再处理事件")
        void shouldNotHandleAfterUnregister() {
            // Arrange
            AtomicInteger eventCount = new AtomicInteger(0);
            TempListener listener = TempListener.common(PlayerJoinEvent.class)
                    .eventHandler(event -> {
                        eventCount.incrementAndGet();
                        return false;
                    })
                    .build();

            // Act
            listener.register();
            server.addPlayer("Player1");
            listener.unregister();
            server.addPlayer("Player2");

            // Assert
            assertThat(eventCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("SimpleTempListener 直接测试")
    class SimpleTempListenerTests {

        @Test
        @DisplayName("无参构造函数应该创建空实例")
        void noArgsConstructorShouldCreateEmptyInstance() {
            // Act
            SimpleTempListener<PlayerJoinEvent> listener = new SimpleTempListener<>();

            // Assert
            assertThat(listener.getEventClass()).isNull();
            assertThat(listener.getPriority()).isEqualTo(EventPriority.NORMAL);
            assertThat(listener.getEventHandler()).isNull();
        }

        @Test
        @DisplayName("两参数构造函数应该正确设置")
        void twoArgsConstructorShouldSetCorrectly() {
            // Arrange
            TempEventHandler<PlayerJoinEvent> handler = event -> false;

            // Act
            SimpleTempListener<PlayerJoinEvent> listener = new SimpleTempListener<>(
                    PlayerJoinEvent.class, handler);

            // Assert
            assertThat(listener.getEventClass()).isEqualTo(PlayerJoinEvent.class);
            assertThat(listener.getEventHandler()).isEqualTo(handler);
            assertThat(listener.getPriority()).isEqualTo(EventPriority.NORMAL);
        }

        @Test
        @DisplayName("三参数构造函数（带 filter）应该正确设置")
        void threeArgsConstructorWithFilterShouldSetCorrectly() {
            // Arrange
            TempEventHandler<PlayerJoinEvent> handler = event -> false;

            // Act
            SimpleTempListener<PlayerJoinEvent> listener = new SimpleTempListener<>(
                    PlayerJoinEvent.class, handler, event -> true);

            // Assert
            assertThat(listener.getEventClass()).isEqualTo(PlayerJoinEvent.class);
            assertThat(listener.getEventHandler()).isEqualTo(handler);
            assertThat(listener.getFilter()).isNotNull();
        }

        @Test
        @DisplayName("三参数构造函数（带 priority）应该正确设置")
        void threeArgsConstructorWithPriorityShouldSetCorrectly() {
            // Arrange
            TempEventHandler<PlayerJoinEvent> handler = event -> false;

            // Act
            SimpleTempListener<PlayerJoinEvent> listener = new SimpleTempListener<>(
                    PlayerJoinEvent.class, handler, EventPriority.HIGHEST);

            // Assert
            assertThat(listener.getEventClass()).isEqualTo(PlayerJoinEvent.class);
            assertThat(listener.getEventHandler()).isEqualTo(handler);
            assertThat(listener.getPriority()).isEqualTo(EventPriority.HIGHEST);
        }

        @Test
        @DisplayName("setter 方法应该正确工作")
        void settersShouldWork() {
            // Arrange
            SimpleTempListener<PlayerJoinEvent> listener = new SimpleTempListener<>();
            TempEventHandler<PlayerJoinEvent> handler = event -> false;

            // Act
            listener.setEventClass(PlayerJoinEvent.class);
            listener.setEventHandler(handler);
            listener.setPriority(EventPriority.LOW);
            listener.setFilter(event -> true);

            // Assert
            assertThat(listener.getEventClass()).isEqualTo(PlayerJoinEvent.class);
            assertThat(listener.getEventHandler()).isEqualTo(handler);
            assertThat(listener.getPriority()).isEqualTo(EventPriority.LOW);
            assertThat(listener.getFilter()).isNotNull();
        }
    }

    @Nested
    @DisplayName("PlayerTempListenerBuilder 测试 (已弃用)")
    @SuppressWarnings("deprecation")
    class PlayerTempListenerBuilderTests {

        @Test
        @DisplayName("应该创建玩家特定的监听器")
        void shouldCreatePlayerSpecificListener() {
            // Arrange
            PlayerMock player = server.addPlayer("TargetPlayer");

            // Act
            TempListener listener = TempListener.player(PlayerQuitEvent.class)
                    .player(player)
                    .eventHandler(event -> false)
                    .priority(EventPriority.NORMAL)
                    .build();

            // Assert
            assertThat(listener).isNotNull();
        }

        @Test
        @DisplayName("listen 方法应该注册监听器")
        void listenShouldRegister() {
            // Arrange
            PlayerMock player = server.addPlayer("TargetPlayer");
            AtomicBoolean handled = new AtomicBoolean(false);

            // Act
            TempListener.player(PlayerQuitEvent.class)
                    .player(player)
                    .listen(event -> {
                        handled.set(true);
                        return true;
                    });

            // 触发事件
            player.disconnect();

            // Assert
            assertThat(handled.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("多事件类型测试")
    class MultipleEventTypesTests {

        @Test
        @DisplayName("应该支持不同事件类型的监听器")
        void shouldSupportDifferentEventTypes() {
            // Arrange
            AtomicBoolean joinHandled = new AtomicBoolean(false);
            AtomicBoolean quitHandled = new AtomicBoolean(false);

            // Act - 注册两种事件监听器
            TempListener.common(PlayerJoinEvent.class)
                    .listen(event -> {
                        joinHandled.set(true);
                        return true;
                    });

            TempListener.common(PlayerQuitEvent.class)
                    .listen(event -> {
                        quitHandled.set(true);
                        return true;
                    });

            // 触发事件
            PlayerMock player = server.addPlayer("TestPlayer");
            player.disconnect();

            // Assert
            assertThat(joinHandled.get()).isTrue();
            assertThat(quitHandled.get()).isTrue();
        }
    }
}
