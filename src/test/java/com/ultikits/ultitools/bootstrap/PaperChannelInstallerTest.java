package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.inventory.ItemType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.TagEventTypeProvider;
import io.papermc.paper.registry.RegistryKey;
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

    interface BootstrapLifecycle extends LifecycleEventManager<BootstrapContext> { }
}
