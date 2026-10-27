package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A responder may answer any JSON value the UltiCloud v2 {@code module.response} contract allows as
 * {@code data}: an object, an array, a primitive, or nothing. A future completing with Java
 * {@code null} or {@link JsonNull} settles the dispatch with {@link JsonNull#INSTANCE}; a responder
 * that returns no future at all is still a failure.
 */
@DisplayName("PanelResponderRegistry: every JSON result shape a responder may answer")
class PanelResponderResultShapeTest {

    private static final String TYPE = "ultimail.inbox.list";
    private static final String OWNER = "UltiMail";

    private final PanelResponderRegistry registry = new PanelResponderRegistry();

    @AfterEach
    void shutdown() {
        registry.shutdown();
    }

    private JsonElement dispatchAndGet() throws Exception {
        return registry.dispatch(TYPE, new JsonObject(), "req-1").get(10, TimeUnit.SECONDS);
    }

    @Test
    void futureCompletingWithJavaNullSettlesWithJsonNull() throws Exception {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(null), OWNER);

        assertThat(dispatchAndGet()).isSameAs(JsonNull.INSTANCE);
    }

    @Test
    void futureCompletingWithJsonNullSettlesWithJsonNull() throws Exception {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(JsonNull.INSTANCE), OWNER);

        assertThat(dispatchAndGet()).isSameAs(JsonNull.INSTANCE);
    }

    @Test
    void arrayResultIsRelayedAsIs() throws Exception {
        JsonArray items = new JsonArray();
        items.add("first");
        items.add(2);
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(items), OWNER);

        assertThat(dispatchAndGet()).isEqualTo(items);
    }

    @Test
    void primitiveResultIsRelayedAsIs() throws Exception {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(new JsonPrimitive(7)), OWNER);

        assertThat(dispatchAndGet()).isEqualTo(new JsonPrimitive(7));
    }

    @Test
    void objectResultIsUnchanged() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("items", 3);
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(result), OWNER);

        assertThat(dispatchAndGet()).isEqualTo(result);
    }

    @Test
    void responderReturningNoFutureIsStillAFailure() {
        registry.registerResponder(TYPE, data -> null, OWNER);

        assertThatThrownBy(this::dispatchAndGet)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null");
    }

    /**
     * Source compatibility with the 6.3.0 signature: the responder shapes module code already
     * writes keep compiling against the widened parameter, including a variable typed exactly as
     * the released {@code Function<JsonObject, CompletableFuture<JsonObject>>}.
     */
    @Test
    void sixThreeZeroResponderShapesStillCompile() throws Exception {
        Function<JsonObject, CompletableFuture<JsonObject>> released =
                data -> CompletableFuture.completedFuture(new JsonObject());
        registry.registerResponder("ultimail.a", released, OWNER);
        registry.registerResponder("ultimail.b", data -> {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            future.complete(new JsonObject());
            return future;
        }, OWNER);
        registry.registerResponder("ultimail.c", data -> new CompletableFuture<>(), OWNER, null);
        registry.registerResponder("ultimail.d", data -> CompletableFuture.supplyAsync(JsonObject::new), OWNER);
        registry.registerResponder("ultimail.e",
                data -> CompletableFuture.completedFuture(data).thenApply(in -> new JsonArray()), OWNER);

        assertThat(registry.dispatch("ultimail.a", null, null).get(10, TimeUnit.SECONDS))
                .isEqualTo(new JsonObject());
        assertThat(registry.dispatch("ultimail.e", null, null).get(10, TimeUnit.SECONDS))
                .isEqualTo(new JsonArray());
    }
}
