package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * The module identifier rule (backend contract answer of 2026-10-27, 22-26 verification item 8): the
 * {@code <id>} of {@code modules:<id>@X.Y.Z} and the {@code module} a {@code module.request} names are
 * the module's catalogue identifier -- its identify-string, lower-case -- never its {@code plugin.yml}
 * name. Every case uses a module whose name differs from its identify-string, as four shipped modules'
 * do (UltiChat: name {@code UltiTools-Chat}, identify-string {@code ultichat}).
 *
 * <p>Compatibility: a responder registered the 6.3.0 way, under {@code <name lower-cased>.<action>}
 * with the module name as owner, keeps answering; it is now reached by the identifier.
 */
@DisplayName("module identifier = catalogue identifier (22-26 fix, item 8)")
class ModuleIdentifierRuleTest {
    private static final String ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";
    private static final String NAME = "UltiTools-Chat";
    private static final String IDENTIFY_STRING = "ultichat";

    private final PanelResponderRegistry registry = new PanelResponderRegistry();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void shutdownRegistry() {
        registry.shutdown();
    }

    @Test
    @DisplayName("hello declares the identify-string, not the name, for a responder registered the 6.3.0 way")
    void capabilityUsesTheIdentifyString() {
        registerCounting("ultitools-chat.history", module(NAME, IDENTIFY_STRING));

        assertThat(ModuleCapabilities.names(registry, name -> NAME.equals(name) ? "1.4.2" : null))
                .containsExactly("modules:ultichat@1.4.2");
    }

    @Test
    @DisplayName("a registration by name only maps to the listed module's identify-string")
    void nameOnlyRegistrationMapsToTheListedModule() {
        UltiToolsPlugin chat = module(NAME, IDENTIFY_STRING);
        try (MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class)) {
            listed(ultiTools, chat);
            registry.registerResponder("ultitools-chat.history", data -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(new JsonObject());
            }, NAME);

            assertThat(ModuleCapabilities.names(registry, name -> NAME.equals(name) ? "1.4.2" : null))
                    .containsExactly("modules:ultichat@1.4.2");
            Recorder channel = new Recorder();
            new ModuleRequestHandler(registry).handle(request("ultichat", "history"), channel);
            assertThat(await(channel::first, "the reply").get("status")).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("module.request for the identifier reaches a responder registered under the lower-cased name")
    void requestByIdentifierReachesTheLegacyRegistration() {
        registerCounting("ultitools-chat.history", module(NAME, IDENTIFY_STRING));
        Recorder channel = new Recorder();

        new ModuleRequestHandler(registry).handle(request("ultichat", "history"), channel);

        assertThat(await(channel::first, "the reply").get("status")).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("module.request for the identifier reaches a responder registered under the identifier")
    void requestByIdentifierReachesTheIdentifierRegistration() {
        registerCounting("ultichat.history", module(NAME, IDENTIFY_STRING));
        Recorder channel = new Recorder();

        new ModuleRequestHandler(registry).handle(request("ultichat", "history"), channel);

        assertThat(await(channel::first, "the reply").get("status")).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("module.request naming the lower-cased plugin name is not routed: the name is never an identifier")
    void requestByNameIsNotRouted() {
        registerCounting("ultitools-chat.history", module(NAME, IDENTIFY_STRING));
        Recorder channel = new Recorder();

        new ModuleRequestHandler(registry).handle(request("ultitools-chat", "history"), channel);

        assertThat(await(channel::first, "the reply").get("code")).isEqualTo("module_not_found");
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("name UltiChat with identify-string UltiTools-Chat: id ultitools-chat, and ultichat is not routed")
    void reversedPairFollowsTheIdentifyString() {
        UltiToolsPlugin module = module("UltiChat", "UltiTools-Chat");
        registerCounting("ultichat.history", module);

        assertThat(ModuleCapabilities.names(registry, name -> "UltiChat".equals(name) ? "2.0.0" : null))
                .containsExactly("modules:ultitools-chat@2.0.0");
        Recorder routed = new Recorder();
        new ModuleRequestHandler(registry).handle(request("ultitools-chat", "history"), routed);
        assertThat(await(routed::first, "the reply").get("status")).isEqualTo("ok");
        Recorder refused = new Recorder();
        new ModuleRequestHandler(registry).handle(request("ultichat", "history"), refused);
        assertThat(await(refused::first, "the reply").get("code")).isEqualTo("module_not_found");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("control: a module whose name lower-cased equals its identify-string declares and routes as before")
    void sameNameAndIdentifyStringIsUnchanged() {
        registerCounting("ultimail.inbox.list", module("UltiMail", "ultimail"));

        assertThat(ModuleCapabilities.names(registry, name -> "1.0.0"))
                .containsExactly("modules:ultimail@1.0.0");
        Recorder channel = new Recorder();
        new ModuleRequestHandler(registry).handle(request("ultimail", "inbox.list"), channel);
        assertThat(await(channel::first, "the reply").get("status")).isEqualTo("ok");
    }

    @Test
    @DisplayName("compatibility: a module with no identify-string keeps the 6.3.0 name-derived id")
    void noIdentifyStringKeepsTheNameDerivedId() {
        registerCounting("ultimail.inbox.list", module("UltiMail", null));

        assertThat(ModuleCapabilities.names(registry, name -> "1.0.0"))
                .containsExactly("modules:ultimail@1.0.0");
        Recorder channel = new Recorder();
        new ModuleRequestHandler(registry).handle(request("ultimail", "inbox.list"), channel);
        assertThat(await(channel::first, "the reply").get("status")).isEqualTo("ok");
    }

    @Test
    @DisplayName("ModuleIds: trimmed, lower-cased, ModuleIdentifier syntax, at most 64 characters, else null")
    void moduleIdsDerivation() {
        assertThat(ModuleIds.of("UltiTools-Chat")).isEqualTo("ultitools-chat");
        assertThat(ModuleIds.of(" ultikits.kits ")).isEqualTo("ultikits.kits");
        assertThat(ModuleIds.of("ULTICHAT")).isEqualTo("ultichat");
        assertThat(ModuleIds.of("ulti_chat")).isNull();
        assertThat(ModuleIds.of("1chat")).isNull();
        assertThat(ModuleIds.of("ulti..chat")).isNull();
        assertThat(ModuleIds.of("")).isNull();
        assertThat(ModuleIds.of((String) null)).isNull();
        assertThat(ModuleIds.of((UltiToolsPlugin) null)).isNull();
        StringBuilder sixtyFour = new StringBuilder("a");
        while (sixtyFour.length() < 64) {
            sixtyFour.append('b');
        }
        assertThat(ModuleIds.of(sixtyFour.toString())).hasSize(64);
        assertThat(ModuleIds.of(sixtyFour + "c")).isNull();
        assertThat(ModuleIds.of(module("UltiTools-Chat", "ultichat"))).isEqualTo("ultichat");
        assertThat(CatalogueClient.moduleRef("UltiTools-Chat")).isEqualTo(ModuleIds.of("UltiTools-Chat"));
    }

    private void registerCounting(String type, UltiToolsPlugin owner) {
        registry.registerResponder(type, data -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new JsonObject());
        }, owner.getPluginName(), owner);
    }

    private static UltiToolsPlugin module(String name, String identifyString) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn(name);
        when(plugin.getIdentifyString()).thenReturn(identifyString);
        when(plugin.getVersion()).thenReturn("1.4.2");
        return plugin;
    }

    private static void listed(MockedStatic<UltiTools> ultiTools, UltiToolsPlugin module) {
        UltiTools instance = mock(UltiTools.class);
        PluginManager modules = mock(PluginManager.class);
        when(modules.getPluginList()).thenReturn(Collections.singletonList(module));
        when(modules.findRegistrationOwner(module.getPluginName())).thenReturn(module);
        when(instance.getPluginManager()).thenReturn(modules);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
    }

    private static CloudRequest request(String module, String action) {
        JsonObject payload = new JsonObject();
        payload.addProperty("module", module);
        payload.addProperty("action", action);
        payload.add("params", new JsonObject());
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", 42);
        actor.add("minecraftUuid", com.google.gson.JsonNull.INSTANCE);
        payload.add("actor", actor);
        return new CloudRequest(ID, "module.request", 1761274800000L, null, payload);
    }

    /** Waits, bounded, for the first reply; the responder runs on the registry's worker. */
    private static <T> T await(Supplier<T> supplier, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, interrupted);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    /** Records each reply as {@code status} ({@code ok}) or {@code code} (the error code). */
    private static final class Recorder implements CloudChannel {
        private final List<java.util.Map<String, String>> replies = new ArrayList<>();

        private synchronized java.util.Map<String, String> first() {
            return replies.isEmpty() ? null : replies.get(0);
        }

        private synchronized void record(String key, String value) {
            replies.add(Collections.singletonMap(key, value));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public CloudLimits limits() {
            return CloudLimits.defaults();
        }

        @Override
        public boolean send(String type, JsonObject payload) {
            return true;
        }

        @Override
        public boolean reply(String replyTo, String type, JsonObject payload) {
            return true;
        }

        @Override
        public boolean replyOk(String replyTo, String type, JsonElement data) {
            record("status", "ok");
            return true;
        }

        @Override
        public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull,
                                    String messageOrNull) {
            record("status", "partial");
            return true;
        }

        @Override
        public boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull) {
            record("code", code);
            return true;
        }
    }
}
