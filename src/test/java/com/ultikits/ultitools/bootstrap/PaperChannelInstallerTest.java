package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

import io.papermc.paper.datapack.DatapackRegistrar;
import io.papermc.paper.datapack.DiscoveredDatapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.registrar.RegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.lifecycle.event.types.TagEventTypeProvider;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryComposeEvent;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.event.WritableRegistry;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.tag.PostFlattenTagRegistrar;
import net.kyori.adventure.key.Key;

class PaperChannelInstallerTest {
    private final Logger logger = Logger.getAnonymousLogger();
    private final ContributionRegistry registry = new ContributionRegistry(new ModuleJarAdmission(), logger);
    private final BootstrapLifecycle lifecycle = mock(BootstrapLifecycle.class);

    @Test
    void noContributionsInstallsNoHandler() {
        PaperChannelInstaller.install(lifecycle, registry, logger);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void itemTagsInstallOnlyOneItemHandler() {
        var module = new ModuleContributions("Tags", Path.of("tags.jar"));
        Key tag = Key.key("tags:demo");
        Key apple = Key.key("minecraft:apple");
        module.tags().setTag(RegistryKey.ITEM, tag, List.of(apple));
        module.seal();
        registry.accept(module);

        // MockBukkit's postFlatten provider is not implemented. Keep that substitute at the Paper boundary.
        TagEventTypeProvider provider = mock(TagEventTypeProvider.class);
        LifecycleEventType.Prioritizable<BootstrapContext, ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>>>
                type = mock();
        when(provider.postFlatten(RegistryKey.ITEM)).thenReturn(type);
        PaperChannelInstaller.install(lifecycle, registry, logger, () -> provider);
        ArgumentCaptor<LifecycleEventHandler<ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>>>> handler =
                ArgumentCaptor.captor();
        verify(lifecycle).registerEventHandler(eq(type), handler.capture());
        verifyNoMoreInteractions(lifecycle);
        verify(provider).postFlatten(RegistryKey.ITEM);
        verifyNoMoreInteractions(provider);

        ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>> event = mock();
        PostFlattenTagRegistrar<ItemType> registrar = mock();
        when(event.registrar()).thenReturn(registrar);
        handler.getValue().run(event);
        verify(registrar).setTag(RegistryKey.ITEM.tagKey(tag), List.of(RegistryKey.ITEM.typedKey(apple)));
        when(event.registrar()).thenThrow(new AssertionError("event unavailable"), new StackOverflowError("fatal"));
        assertThatCode(() -> handler.getValue().run(event)).doesNotThrowAnyException();
        assertThatThrownBy(() -> handler.getValue().run(event)).isInstanceOf(StackOverflowError.class);
    }

    @Test
    void failedInstallationLeavesTheEarlierHandlerInert() {
        var module = new ModuleContributions("Demo", Path.of("demo.jar"));
        module.tags().setTag(RegistryKey.ITEM, Key.key("demo:items"), List.of(Key.key("minecraft:apple")));
        module.seal();
        registry.accept(module);
        registry.preparedPacks(List.of(new EmbeddedDatapacks.PreparedPack(module, Path.of("demo.test.zip"), "demo.test")));

        TagEventTypeProvider tags = mock();
        LifecycleEventType.Prioritizable<BootstrapContext, ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>>>
                itemTags = mock();
        when(tags.postFlatten(RegistryKey.ITEM)).thenReturn(itemTags);
        var failure = new IllegalStateException("registration failed");
        doThrow(failure).when(lifecycle).registerEventHandler(eq(LifecycleEvents.DATAPACK_DISCOVERY), any());
        assertThatThrownBy(() -> PaperChannelInstaller.install(lifecycle, registry, logger, () -> tags))
                .isSameAs(failure);

        ArgumentCaptor<LifecycleEventHandler<ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>>>> handler =
                ArgumentCaptor.captor();
        verify(lifecycle).registerEventHandler(eq(itemTags), handler.capture());
        ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>> event = mock();
        handler.getValue().run(event);
        verifyNoInteractions(event);
    }

    @Test
    void enchantmentHandlerPassesTheTypedKeyAndAdaptsTheDefinition() throws Exception {
        var module = new ModuleContributions("Demo", Path.of("demo.jar"));
        Key key = Key.key("demo:edge");
        var itemTag = RegistryKey.ITEM.tagKey(Key.key("minecraft:enchantable/sword"));
        module.enchantments().register(key, (builder, tags) -> builder.supportedItems(tags.tag(itemTag)));
        module.seal();
        registry.accept(module);
        LifecycleEventType.Prioritizable<BootstrapContext, RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>>
                type = mock();
        // Paper's game server, not MockBukkit, supplies this package-private service. Replace only
        // its lookup on this thread; the framework's event registration and callback are real.
        Class<?> service = Class.forName("io.papermc.paper.registry.event.RegistryEventTypeProvider");
        Object provider = mock(service, invocation -> invocation.getMethod().getName().equals("registryCompose")
                && invocation.getArgument(0) == RegistryEvents.ENCHANTMENT ? type : null);
        try (var ignored = mockStatic(service, invocation -> provider)) {
            PaperChannelInstaller.install(lifecycle, registry, logger);
        }

        ArgumentCaptor<LifecycleEventHandler<RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>>>
                handler = ArgumentCaptor.captor();
        verify(lifecycle).registerEventHandler(eq(type), handler.capture());
        RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder> event = mock();
        WritableRegistry<Enchantment, EnchantmentRegistryEntry.Builder> writable = mock();
        Tag<ItemType> tag = mock();
        when(event.registry()).thenReturn(writable);
        when(event.getOrCreateTag(itemTag)).thenReturn(tag);
        handler.getValue().run(event);

        ArgumentCaptor<Consumer<? super EnchantmentRegistryEntry.Builder>> definition = ArgumentCaptor.captor();
        verify(writable).register(eq(RegistryKey.ENCHANTMENT.typedKey(key)), definition.capture());
        EnchantmentRegistryEntry.Builder builder = mock();
        definition.getValue().accept(builder);
        verify(builder).supportedItems(tag);
    }

    @Test
    void datapackHandlerPassesTheZipAndIdAndTreatsNullAsFailure(@TempDir Path data) throws Exception {
        var good = new ModuleContributions("Good", data.resolve("good.jar"));
        Path badJar = BootstrapFixtures.jar(data.resolve("bad.jar"), BootstrapFixtures.descriptor("Bad", null), Map.of());
        var bad = new ModuleContributions("Bad", badJar);
        Path goodZip = data.resolve("good.pack.zip");
        Path badZip = data.resolve("bad.pack.zip");
        registry.preparedPacks(List.of(new EmbeddedDatapacks.PreparedPack(good, goodZip, "good.pack"),
                new EmbeddedDatapacks.PreparedPack(bad, badZip, "bad.pack")));
        PaperChannelInstaller.install(lifecycle, registry, logger);

        ArgumentCaptor<LifecycleEventHandler<RegistrarEvent<DatapackRegistrar>>> handler = ArgumentCaptor.captor();
        verify(lifecycle).registerEventHandler(eq(LifecycleEvents.DATAPACK_DISCOVERY), handler.capture());
        RegistrarEvent<DatapackRegistrar> event = mock();
        DatapackRegistrar registrar = mock();
        when(event.registrar()).thenReturn(registrar);
        when(registrar.discoverPack(goodZip, "good.pack")).thenReturn(mock(DiscoveredDatapack.class));
        when(registrar.discoverPack(badZip, "bad.pack")).thenReturn(null);
        handler.getValue().run(event);

        verify(registrar).discoverPack(goodZip, "good.pack");
        verify(registrar).discoverPack(badZip, "bad.pack");
        assertThat(registry.failed(good.jar())).isFalse();
        assertThat(registry.failed(bad.jar())).isTrue();
    }

    interface BootstrapLifecycle extends LifecycleEventManager<BootstrapContext> { }
}
