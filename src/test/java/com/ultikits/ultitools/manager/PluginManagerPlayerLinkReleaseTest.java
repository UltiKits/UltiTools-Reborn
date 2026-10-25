package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.utils.CloudFeatures;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Plan 22-21 (S-60, UltiLogin R4): unloading a module through the real {@link PluginManager#unregister} path removes
 * the player-link handler it set, so no completion reaches code whose class loader is gone; unloading another module
 * leaves it in place, and a failing release step does not stop the remaining unload steps.
 */
@DisplayName("Unloading a module releases its player-link handler")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerPlayerLinkReleaseTest {

    private static final CloudPlayerLinks.LinkEventHandler HANDLER =
            completion -> CompletableFuture.completedFuture(CloudPlayerLinks.GameAccountResult.CREATED);

    private PluginManager pluginManager;
    private PanelResponderRegistry responders;
    private EventBus eventBus;
    private CloudFeatures features;
    private final AtomicReference<RuntimeException> linksFailure = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean noFeatures = new java.util.concurrent.atomic.AtomicBoolean();

    /** A module; mocked so its constructor is bypassed. */
    abstract static class LinkModule extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        responders = new PanelResponderRegistry();
        eventBus = new EventBus();
        AtomicReference<UltiTools> published = new AtomicReference<>();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getDescription()).thenReturn(
                    new PluginDescriptionFile("UltiTools", "6.3.1", "com.ultikits.ultitools.UltiTools"));
            lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(responders);
            lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
            lenient().when(ultiTools.getCloudPlayerLinks()).thenAnswer(invocation -> {
                RuntimeException failure = linksFailure.get();
                if (failure != null) {
                    throw failure;
                }
                return noFeatures.get() ? null : features.playerLinks();
            });
            published.set(ultiTools);
        });
        features = CloudFeatures.start(published.get());
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        features.shutdown();
        eventBus.shutdown();
        responders.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private LinkModule loadModule(String name) {
        LinkModule module = mock(LinkModule.class);
        when(module.getPluginName()).thenReturn(name);
        lenient().when(module.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(module.getContext()).thenReturn(mock(SimpleContainer.class));
        PluginListSeeding.add(pluginManager, module);
        return module;
    }

    @Test
    @DisplayName("unloading the owner removes its handler; another module can then set one")
    void unloadingTheOwnerRemovesItsHandler() {
        LinkModule owner = loadModule("UltiLogin");
        features.playerLinks().setHandler(owner, HANDLER);
        LinkModule next = loadModule("Other");
        assertThatThrownBy(() -> features.playerLinks().setHandler(next, HANDLER))
                .as("the owner holds the handler while loaded").isInstanceOf(IllegalStateException.class);

        pluginManager.unregister(owner);

        features.playerLinks().setHandler(next, HANDLER);
        assertThat(pluginManager.getPluginList()).doesNotContain(owner);
    }

    @Test
    @DisplayName("unloading another module leaves the owner's handler in place")
    void unloadingAnotherModuleKeepsTheHandler() {
        LinkModule owner = loadModule("UltiLogin");
        LinkModule other = loadModule("UltiMail");
        features.playerLinks().setHandler(owner, HANDLER);

        pluginManager.unregister(other);

        LinkModule third = loadModule("Third");
        assertThatThrownBy(() -> features.playerLinks().setHandler(third, HANDLER))
                .as("the owner still holds the handler").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a failing release step does not stop the remaining unload steps")
    void failingReleaseDoesNotStopTheUnload() {
        LinkModule owner = loadModule("UltiLogin");
        responders.registerResponder("ultilogin.probe",
                (JsonObject data) -> CompletableFuture.completedFuture(new JsonObject()), "UltiLogin", owner);
        SimpleContainer context = owner.getContext();
        linksFailure.set(new IllegalStateException("links unavailable"));

        pluginManager.unregister(owner);

        assertThat(responders.hasResponder("ultilogin.probe")).as("the responder step still ran").isFalse();
        verify(context).close();
        assertThat(pluginManager.getPluginList()).as("the module is delisted").doesNotContain(owner);
    }

    @Test
    @DisplayName("an unload before the features exist (early enable failure) is null-safe")
    void unloadWithoutFeaturesIsNullSafe() {
        LinkModule owner = loadModule("UltiLogin");
        noFeatures.set(true);

        pluginManager.unregister(owner);

        assertThat(pluginManager.getPluginList()).doesNotContain(owner);
    }
}
