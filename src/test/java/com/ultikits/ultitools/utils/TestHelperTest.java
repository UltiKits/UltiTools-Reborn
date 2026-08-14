package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.UltiTools;

/**
 * TestHelper 自身的回归测试。
 *
 * <p>守的是 issue #250：{@code mockUltiToolsInstance()} 曾经先把 mock 写进
 * {@code UltiTools.ultiTools} 静态字段、之后才打桩，于是任何还活着的后台线程都能在
 * 打桩进行到一半时调到这个 mock。Mockito 的
 * {@code InvocationContainerImpl.invocationForStubbing} 是 per-mock 的共享可变字段，
 * 被插一脚就会把 answer 绑到那次调用的方法上——{@code isEnabled()} 的 {@code true}
 * 因此挂到了 {@code getLogger()} 上，之后再调 {@code getLogger()} 抛
 * {@code ClassCastException: Boolean cannot be cast to Logger}。
 */
@DisplayName("TestHelper 测试")
class TestHelperTest {

    /**
     * 回归前这个 bug 极易触发：本地用一条热循环线程施压时，头几轮就必炸。
     * 300 轮留了很大余量，同时保证这个用例本身跑得够快。
     */
    private static final int ROUNDS = 300;

    @Test
    @DisplayName("发布出去的 mock 必须已经打完桩：并发调用不得污染 stubbing")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldOnlyPublishFullyStubbedMock() throws Exception {
        Logger stubbedLogger = mock(Logger.class);

        // 先占住静态字段，免得下面那条线程在第一轮之前碰到别的测试类留下的 mock。
        TestHelper.mockUltiToolsInstance(
                ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(stubbedLogger));

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> strayFailure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        // 模拟现实里的污染源：泄漏的 UltiPanel-LogTransmitter 调度线程、挂在 root logger
        // 上的 SystemLogHandler，都会在任意时刻通过 UltiTools.getInstance() 调进来。
        Thread stray = new Thread(() -> {
            started.countDown();
            while (!stop.get()) {
                try {
                    UltiTools instance = UltiTools.getInstance();
                    if (instance != null) {
                        instance.getLogger();
                        instance.isEnabled();
                    }
                } catch (Throwable t) {
                    strayFailure.compareAndSet(null, t);
                }
            }
        }, "issue-250-stray-thread");
        stray.setDaemon(true);
        stray.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            for (int i = 0; i < ROUNDS; i++) {
                TestHelper.mockUltiToolsInstance(
                        ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(stubbedLogger));

                // 修复前这里就是 LogStreamManagerTest:75 的现场：getLogger() 返回的
                // 可能是 isEnabled() 的 Boolean，inline mock maker 织入的 checkcast
                // 会抛 ClassCastException，堆栈指在 JavaPlugin.getLogger 上。
                assertThat(UltiTools.getInstance().getLogger()).isSameAs(stubbedLogger);
                assertThat(UltiTools.getInstance().isEnabled()).isTrue();
            }
        } finally {
            stop.set(true);
            stray.join(TimeUnit.SECONDS.toMillis(5));
        }

        // 这个用例自己不许泄漏线程——那正是它在防的病。
        assertThat(stray.isAlive()).isFalse();
        assertThat(strayFailure.get()).isNull();
    }
}
