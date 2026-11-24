package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TextEngineRecipientConcurrencyTest {
    @Test
    void forEachRecipient_whileMembersJoinAndQuit_neverThrows() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        Audience first = new Audience() {
            @Override public void sendMessage(Component message) { deliveries.incrementAndGet(); }
        };
        Audience second = new Audience() {
            @Override public void sendMessage(Component message) { deliveries.incrementAndGet(); }
        };
        List<Audience> members = new CopyOnWriteArrayList<>(List.of(first, second));
        ForwardingAudience group = () -> members;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch readersEntered = new CountDownLatch(8);
        CountDownLatch firstChange = new CountDownLatch(1);
        Logger logger = Logger.getAnonymousLogger();
        try (TextEngine engine = new TextEngine(
                new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger), logger)) {
            Messages messages = engine.messagesFor("sample");
            ExecutorService pool = Executors.newFixedThreadPool(9);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(pool.submit(() -> {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        AtomicBoolean firstReceipt = new AtomicBoolean(true);
                        for (int call = 0; call < 200; call++) {
                            messages.forEachRecipient(group, Text.plain("test"), (recipient, rendered) -> {
                                if (firstReceipt.compareAndSet(true, false)) {
                                    readersEntered.countDown();
                                    awaitChange(firstChange);
                                }
                                assertThat(recipient).isIn(first, second).isNotSameAs(group);
                                recipient.sendMessage(rendered);
                            });
                        }
                        return true;
                    }));
                }
                Future<Boolean> changes = pool.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(readersEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    members.remove(second);
                    members.add(second);
                    firstChange.countDown();
                    for (int change = 0; change < 2000; change++) {
                        members.remove(second);
                        members.add(second);
                    }
                    return true;
                });
                start.countDown();
                for (Future<Boolean> result : results) {
                    assertThat(result.get(15, TimeUnit.SECONDS)).isTrue();
                }
                assertThat(changes.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(deliveries.get()).isGreaterThanOrEqualTo(1600);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static void awaitChange(CountDownLatch changed) {
        try {
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void arrayListControl_detectsConcurrentMembershipChanges() throws Exception {
        List<Audience> members = new ArrayList<>();
        members.add(new Audience() { });
        members.add(new Audience() { });
        CountDownLatch iteratorEntered = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> writer = pool.submit(() -> {
                assertThat(iteratorEntered.await(5, TimeUnit.SECONDS)).isTrue();
                members.add(new Audience() { });
                changed.countDown();
                return true;
            });
            ForwardingAudience group = () -> members;
            assertThatThrownBy(() -> group.forEachAudience(recipient -> {
                iteratorEntered.countDown();
                try {
                    assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            })).isInstanceOf(ConcurrentModificationException.class);
            assertThat(writer.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
