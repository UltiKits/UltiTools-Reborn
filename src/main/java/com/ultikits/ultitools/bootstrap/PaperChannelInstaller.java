package com.ultikits.ultitools.bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.lifecycle.event.types.TagEventTypeProvider;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.tag.PostFlattenTagRegistrar;

/** The sole adapter from Paper's content lifecycle events to framework-owned declarations. */
final class PaperChannelInstaller {
    private PaperChannelInstaller() {
        // The installer has no state outside one bootstrap.
    }

    static void install(LifecycleEventManager<BootstrapContext> lifecycle, ContributionRegistry registry, Logger logger) {
        install(lifecycle, registry, logger, () -> LifecycleEvents.TAGS);
    }

    // MockBukkit supplies lifecycle event types but does not implement postFlatten.
    static void install(LifecycleEventManager<BootstrapContext> lifecycle, ContributionRegistry registry, Logger logger,
                        Supplier<TagEventTypeProvider> tags) {
        // Paper has no registration rollback. A partial installation must not write anything.
        var installed = new AtomicBoolean();
        if (registry.hasEnchantments()) {
            lifecycle.registerEventHandler(RegistryEvents.ENCHANTMENT.compose(), event ->
                    guard(installed, logger, "enchantment", () -> {
                        var writable = event.registry();
                        registry.composeEnchantments((key, define) ->
                                writable.register(RegistryKey.ENCHANTMENT.typedKey(key), define), event::getOrCreateTag);
                    }));
        }
        for (RegistryKey<?> key : registry.tagRegistries()) {
            installTags(lifecycle, tags.get(), registry, logger, installed, key);
        }
        if (registry.hasDatapacks()) {
            lifecycle.registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event ->
                    guard(installed, logger, "data pack", () -> {
                        var registrar = event.registrar();
                        registry.discoverDatapacks((zip, id) -> registrar.discoverPack(zip, id) != null);
                    }));
        }
        installed.set(true);
    }

    private static <T> void installTags(LifecycleEventManager<BootstrapContext> lifecycle, TagEventTypeProvider tags,
                                        ContributionRegistry registry, Logger logger, AtomicBoolean installed,
                                        RegistryKey<T> key) {
        lifecycle.registerEventHandler(tags.postFlatten(key), event ->
                guard(installed, logger, "tag", () -> {
                    var registrar = event.registrar();
                    registry.applyTags(key, change -> applyTag(registrar, key, change));
                }));
    }

    private static <T> void applyTag(PostFlattenTagRegistrar<T> registrar, RegistryKey<T> key,
                                     ModuleContributions.TagChange change) {
        var tag = key.tagKey(change.tag());
        var values = change.values().stream().map(key::typedKey).toList();
        if (change.replace()) {
            registrar.setTag(tag, values);
        } else {
            registrar.addToTag(tag, values);
        }
    }

    private static void guard(AtomicBoolean installed, Logger logger, String channel, Runnable action) {
        if (!installed.get()) {
            return;
        }
        try {
            action.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) { // NOPMD - never let a non-VM event failure abort Paper's start or reload
            ModuleBootstraps.logFailure(logger, "[UltiTools] Could not run the custom " + channel + " handler: "
                    + ModuleBootstraps.describe(failure), failure);
        }
    }
}
