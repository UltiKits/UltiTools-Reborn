package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.interfaces.DataStore;

/**
 * {@link DataStoreManager#getDatastore(String)} never returns {@code null} for a registered store,
 * however it races the writers (#515).
 * <p>
 * The map used to be a plain {@code HashMap} that {@code register}/{@code unregister} mutated under
 * a lock while {@code getDatastore} read it without one, three times per call. A read during a
 * resize could miss a present key, and the second and third reads could see a different map than
 * the first, so a type that was registered when the call started could come back as {@code null}.
 * The first test pins the structural fix deterministically; the repeated stress tests exercise the
 * behaviour, which a race can only show intermittently.
 */
@DisplayName("DataStoreManager#getDatastore never returns null for a registered store (#515)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DataStoreManagerConcurrencyTest {

    private DataStore json;

    private static DataStore store(String type) {
        DataStore store = mock(DataStore.class);
        when(store.getStoreType()).thenReturn(type);
        return store;
    }

    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
    private static Map<String, DataStore> dataMap() throws Exception {
        Field field = DataStoreManager.class.getDeclaredField("dataMap");
        field.setAccessible(true);
        return (Map<String, DataStore>) field.get(null);
    }

    @BeforeEach
    void registerJsonFallback() throws Exception {
        dataMap().clear();
        // The json fallback is always registered here, so getDatastore never reaches the branch
        // that builds a JsonStore from UltiTools.getInstance().
        json = store("json");
        DataStoreManager.register(json);
    }

    @AfterEach
    void clear() throws Exception {
        dataMap().clear();
    }

    @Test
    @DisplayName("the registry is a concurrent map, so a lock-free read is safe against a writer")
    void registryIsAConcurrentMap() throws Exception {
        assertThat(dataMap())
                .as("getDatastore reads without the writers' lock, so the map itself must be safe for that")
                .isInstanceOf(ConcurrentMap.class);
    }

    @Test
    @DisplayName("a reader thread started after registration sees the registered store")
    void readerStartedAfterRegistrationSeesTheStore() throws Exception {
        DataStore sqlite = store("sqlite");
        DataStoreManager.register(sqlite);
        AtomicReference<DataStore> seen = new AtomicReference<>();

        Thread reader = new Thread(() -> seen.set(DataStoreManager.getDatastore("sqlite")));
        reader.start();
        reader.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(reader.isAlive()).isFalse();
        assertThat(seen.get()).isSameAs(sqlite);
    }

    @RepeatedTest(20)
    @DisplayName("reads of a registered type never return null while other types are registered and unregistered")
    void readsNeverReturnNullDuringChurn() throws Exception {
        DataStore stable = store("stable");
        DataStoreManager.register(stable);
        int pairs = 16;
        CyclicBarrier barrier = new CyclicBarrier(pairs * 2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<DataStore> reads = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String type = "churn-" + i;
            threads.add(new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int round = 0; round < 50; round++) {
                        DataStore churn = store(type);
                        DataStoreManager.register(churn);
                        DataStoreManager.unregister(churn);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
            threads.add(new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int round = 0; round < 200; round++) {
                        reads.add(DataStoreManager.getDatastore("stable"));
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(thread.isAlive()).as("a thread did not finish").isFalse();
        }

        assertThat(failures).isEmpty();
        assertThat(reads).hasSize(pairs * 200).allMatch(read -> read == stable);
    }

    @RepeatedTest(20)
    @DisplayName("a type being unregistered reads as itself or as the json fallback, never null")
    void typeBeingUnregisteredReadsAsItselfOrJson() throws Exception {
        int readers = 8;
        CyclicBarrier barrier = new CyclicBarrier(readers + 1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<DataStore> reads = new CopyOnWriteArrayList<>();
        List<DataStore> flapping = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                for (int round = 0; round < 500; round++) {
                    DataStore mysql = store("mysql");
                    flapping.add(mysql);
                    DataStoreManager.register(mysql);
                    DataStoreManager.unregister(mysql);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }));
        for (int i = 0; i < readers; i++) {
            threads.add(new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int round = 0; round < 500; round++) {
                        reads.add(DataStoreManager.getDatastore("mysql"));
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(thread.isAlive()).as("a thread did not finish").isFalse();
        }

        assertThat(failures).isEmpty();
        assertThat(reads).hasSize(readers * 500)
                .allMatch(read -> read == json || flapping.contains(read));
    }
}
