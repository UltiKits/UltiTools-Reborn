package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.AuditableDataEntity;
import com.ultikits.ultitools.exceptions.PluginModuleException;

/**
 * Which responder serves an admitted request, and which thread context its call sees
 * (UltiTools-Reborn#677). One worker, so successive requests share a thread and anything one
 * module's call leaves behind would reach the next module's call.
 * <br>
 * 已受理请求由哪个 responder 处理，以及调用看到哪些线程上下文；单一工作线程，使跨模块残留可被观察。
 */
@DisplayName("#677 panel responder ownership and worker context")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PanelResponderOwnershipContextTest {

    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final long LONG_DEADLINE_MILLIS = 5000L;

    private final List<PanelResponderRegistry> registries = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (CountDownLatch latch : latches) {
            latch.countDown();
        }
        for (PanelResponderRegistry registry : registries) {
            registry.shutdown();
        }
    }

    private PanelResponderRegistry registry(long deadlineMillis) {
        PanelResponderRegistry registry = new PanelResponderRegistry(deadlineMillis, 1, 8);
        registries.add(registry);
        return registry;
    }

    private CountDownLatch latch() {
        CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        return latch;
    }

    private static JsonObject result(String from) {
        JsonObject result = new JsonObject();
        result.addProperty("from", from);
        return result;
    }

    private static Function<JsonObject, CompletableFuture<JsonObject>> answering(String from, AtomicInteger calls) {
        return data -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(result(from));
        };
    }

    /** Occupies the only worker until {@code release} opens. */
    private static Function<JsonObject, CompletableFuture<JsonObject>> blocking(CountDownLatch started,
            CountDownLatch release) {
        return data -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(result("busy"));
        };
    }

    /** A loader distinct from the framework's own, standing in for a module's class loader. */
    private static ClassLoader testLoader() {
        return new ClassLoader(null) {
        };
    }

    // ---- RED after Task 2 ----

    @Test
    @DisplayName("the call sees the context class loader of the thread that admitted the request")
    void responderSeesTheAdmittingThreadsContextClassLoader() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        AtomicReference<ClassLoader> seen = new AtomicReference<>();
        registry.registerResponder("tccl", data -> {
            seen.set(Thread.currentThread().getContextClassLoader());
            return CompletableFuture.completedFuture(result("tccl"));
        }, "module-a");
        ClassLoader admitting = testLoader();
        assertThat(admitting).isNotSameAs(PanelResponderRegistry.class.getClassLoader());

        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        CompletableFuture<JsonElement> outcome;
        try {
            thread.setContextClassLoader(admitting);
            outcome = registry.dispatch("tccl", new JsonObject(), "req-1");
        } finally {
            thread.setContextClassLoader(saved);
        }

        assertThat(outcome).succeedsWithin(BOUND);
        assertThat(seen.get()).isSameAs(admitting);
    }

    @Test
    @DisplayName("a context class loader one responder leaves set does not reach the next call on the same worker")
    void contextClassLoaderChangedByOneResponderDoesNotReachTheNext() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        ClassLoader leaked = testLoader();
        registry.registerResponder("leaks-tccl", data -> {
            Thread.currentThread().setContextClassLoader(leaked);
            return CompletableFuture.completedFuture(result("module-a"));
        }, "module-a");
        AtomicReference<ClassLoader> seen = new AtomicReference<>();
        registry.registerResponder("reads-tccl", data -> {
            seen.set(Thread.currentThread().getContextClassLoader());
            return CompletableFuture.completedFuture(result("module-b"));
        }, "module-b");
        ClassLoader admitting = Thread.currentThread().getContextClassLoader();

        assertThat(registry.dispatch("leaks-tccl", new JsonObject(), "req-1")).succeedsWithin(BOUND);
        assertThat(registry.dispatch("reads-tccl", new JsonObject(), "req-2")).succeedsWithin(BOUND);

        assertThat(seen.get()).as("module B's call sees its own admitting thread's loader").isSameAs(admitting);
    }

    @Test
    @DisplayName("a registration scope one module's call leaves open does not attribute the next module's registration")
    void registrationScopeLeftOpenByOneModuleDoesNotAttributeTheNextModulesRegistration() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        UltiToolsPlugin instanceA = mock(UltiToolsPlugin.class);
        registry.registerResponder("opens-scope", data -> {
            registry.beginRegistrationScope(instanceA);
            return CompletableFuture.completedFuture(result("module-a"));
        }, "module-a");
        registry.registerResponder("registers-by-name", data -> {
            registry.registerResponder("b-registered", answering("module-b", new AtomicInteger()), "module-b");
            return CompletableFuture.completedFuture(result("module-b"));
        }, "module-b");

        assertThat(registry.dispatch("opens-scope", new JsonObject(), "req-1")).succeedsWithin(BOUND);
        assertThat(registry.dispatch("registers-by-name", new JsonObject(), "req-2")).succeedsWithin(BOUND);
        assertThat(registry.hasResponder("b-registered")).as("module B registered its type").isTrue();

        registry.unregisterByOwnerInstance(instanceA);

        assertThat(registry.hasResponder("b-registered"))
                .as("module B's registration was not attributed to module A's leftover scope")
                .isTrue();
    }

    @Test
    @DisplayName("the admitting thread's registration scope is carried into the call")
    void admittingThreadsRegistrationScopeIsCarried() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        UltiToolsPlugin instanceC = mock(UltiToolsPlugin.class);
        registry.registerResponder("registers-in-scope", data -> {
            registry.registerResponder("c-registered", answering("module-c", new AtomicInteger()), "module-c");
            return CompletableFuture.completedFuture(result("module-c"));
        }, "module-c");

        CompletableFuture<JsonElement> outcome;
        registry.beginRegistrationScope(instanceC);
        try {
            outcome = registry.dispatch("registers-in-scope", new JsonObject(), "req-1");
        } finally {
            registry.endRegistrationScope();
        }
        assertThat(outcome).succeedsWithin(BOUND);
        assertThat(registry.hasResponder("c-registered")).as("the call registered its type").isTrue();

        registry.unregisterByOwnerInstance(instanceC);

        assertThat(registry.hasResponder("c-registered"))
                .as("the name-only registration made in the call belongs to the admitting scope's module")
                .isFalse();
    }

    // ---- Characterization controls ----

    @Test
    @DisplayName("control: a queued request keeps the responder admitted with it when its type is reassigned")
    void queuedRequestKeepsTheAdmittedResponderWhenTheTypeIsReassigned() throws Exception {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        registry.registerResponder("busy", blocking(started, release), "module-busy");
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        registry.registerResponder("shared", answering("module-a", aCalls), "module-a");

        registry.dispatch("busy", new JsonObject(), "req-0");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the only worker is busy").isTrue();
        CompletableFuture<JsonElement> admittedUnderA = registry.dispatch("shared", new JsonObject(), "req-1");
        registry.unregisterAll("module-a");
        registry.registerResponder("shared", answering("module-b", bCalls), "module-b");
        release.countDown();

        assertThat(admittedUnderA).succeedsWithin(BOUND)
                .satisfies(reply -> assertThat(reply.getAsJsonObject().get("from").getAsString()).isEqualTo("module-a"));
        assertThat(aCalls).hasValue(1);
        assertThat(bCalls).as("module B's responder was not called for the earlier request").hasValue(0);

        assertThat(registry.dispatch("shared", new JsonObject(), "req-2")).succeedsWithin(BOUND)
                .satisfies(reply -> assertThat(reply.getAsJsonObject().get("from").getAsString()).isEqualTo("module-b"));
        assertThat(bCalls).hasValue(1);
    }

    @Test
    @DisplayName("control: a deadline failure names the owner admitted with the request")
    void deadlineNamesTheAdmittedOwner() throws Exception {
        PanelResponderRegistry registry = registry(300L);
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        registry.registerResponder("busy", blocking(started, release), "module-busy");
        registry.registerResponder("shared", answering("module-a", new AtomicInteger()), "module-a");

        registry.dispatch("busy", new JsonObject(), "req-0");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the only worker is busy").isTrue();
        CompletableFuture<JsonElement> admittedUnderA = registry.dispatch("shared", new JsonObject(), "req-1");
        registry.unregisterAll("module-a");
        registry.registerResponder("shared", answering("module-b", new AtomicInteger()), "module-b");

        assertThat(admittedUnderA).failsWithin(BOUND)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(PluginModuleException.class, e -> assertThat(e.getMessage())
                        .contains("module-a")
                        .doesNotContain("module-b"));
    }

    @Test
    @DisplayName("control: no other ThreadLocal of the admitting thread reaches the call")
    void arbitraryThreadLocalsAreNotCarried() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        ThreadLocal<String> local = new ThreadLocal<>();
        AtomicReference<String> seenLocal = new AtomicReference<>("not read");
        AtomicReference<UUID> seenUser = new AtomicReference<>(UUID.randomUUID());
        registry.registerResponder("reads-locals", data -> {
            seenLocal.set(local.get());
            UUID current = AuditableDataEntity.swapCurrentUser(null);
            AuditableDataEntity.swapCurrentUser(current);
            seenUser.set(current);
            return CompletableFuture.completedFuture(result("module-a"));
        }, "module-a");

        CompletableFuture<JsonElement> outcome;
        local.set("admitting thread value");
        AuditableDataEntity.setCurrentUser(UUID.randomUUID());
        try {
            outcome = registry.dispatch("reads-locals", new JsonObject(), "req-1");
        } finally {
            local.remove();
            AuditableDataEntity.clearCurrentUser();
        }

        assertThat(outcome).as("the call ran").succeedsWithin(BOUND);
        assertThat(seenLocal.get()).as("a test-local ThreadLocal").isNull();
        assertThat(seenUser.get()).as("AuditableDataEntity's current user").isNull();
    }

    @Test
    @DisplayName("control: no registration scope is made up from the responder's owner")
    void noScopeIsFabricatedFromTheOwner() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS);
        UltiToolsPlugin owner = mock(UltiToolsPlugin.class);
        registry.registerResponder("owned", data -> {
            registry.registerResponder("named-only", answering("module-x", new AtomicInteger()), "module-x");
            return CompletableFuture.completedFuture(result("module-x"));
        }, "module-x", owner);

        assertThat(registry.dispatch("owned", new JsonObject(), "req-1")).succeedsWithin(BOUND);
        assertThat(registry.hasResponder("named-only")).as("the call registered its type").isTrue();

        registry.unregisterByOwnerInstance(owner);

        assertThat(registry.hasResponder("owned")).as("the owner's own registration is released").isFalse();
        assertThat(registry.hasResponder("named-only"))
                .as("the name-only registration was not attributed to the responder's owner")
                .isTrue();
    }
}
