package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.ui.runtime.ContainerHost;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import com.ultikits.ultitools.ui.runtime.UiOwner;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Adapts the unchanged reference containers to the production session runtime. */
final class InMemoryContainerRuntime implements ContainerRuntime {
    private final Map<InMemorySlotContainer, UiOwner> containers = new IdentityHashMap<>();
    private final Map<UiOwner, Long> unloading = new HashMap<>();
    private final InMemoryMessages messages = new InMemoryMessages("test",
            new FixedLocaleResolver(Locale.ENGLISH, Optional.empty()));
    private final GestureEngine engine = new InMemoryGestureEngine(
            (player, text) -> player.sendMessage(messages.render(text, player)));

    @Override public SlotContainer create(UiOwner owner, ContainerSpec spec, ContainerHost host) {
        InMemorySlotContainer container = new InMemorySlotContainer(owner.name(), spec,
                (RecordingScheduler) host.scheduler(), host::returnTo, host::warn, host::changed);
        containers.put(container, owner);
        return container;
    }
    @Override public void attach(SlotContainer container, UiSession<?> session) {
        ((InMemorySlotContainer) container).attach(session);
    }
    @Override public void detach(SlotContainer container, UiSession<?> session) {
        InMemorySlotContainer memory = (InMemorySlotContainer) container;
        memory.detach(session);
        Long deadline = unloading.get(containers.get(memory));
        if (deadline != null) { memory.unload(deadline); }
    }
    @Override public GestureEngine engine() { return engine; }
    @Override public void prepareUnload(UiOwner owner, long deadlineNanos) {
        unloading.put(owner, deadlineNanos);
        owned(owner).forEach(InMemorySlotContainer::prepareUnload);
    }
    @Override public void startUnloadCommits(UiOwner owner) {
        owned(owner).forEach(InMemorySlotContainer::startUnloadCommit);
    }
    @Override public void finishUnload(UiOwner owner, long deadlineNanos) {
        owned(owner).forEach(container -> container.unload(deadlineNanos));
        unloading.remove(owner);
    }
    private List<InMemorySlotContainer> owned(UiOwner owner) {
        return containers.entrySet().stream().filter(entry -> entry.getValue().equals(owner)).map(Map.Entry::getKey).toList();
    }
}
