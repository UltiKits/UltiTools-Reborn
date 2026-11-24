package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.chest.ChestBackend;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Local adapter; the contract suites and their fixtures remain unchanged. */
@Timeout(20)
abstract class ChestTestFixture {
    ServerMock server;
    SyncPlayer player;
    ManualScheduler.Engine clock;
    RecordingScheduler scheduler;
    UiRuntime runtime;
    UiService service;
    ChestBackend backend;
    Messages messages;

    @BeforeEach void prepareChest() {
        server = MockBukkit.mock();
        player = new SyncPlayer(server, "Alice");
        server.addPlayer(player);
        clock = new ManualScheduler.Engine();
        scheduler = new RecordingScheduler(clock.scheduler("UiRuntime"));
        messages = createMessages();
        runtime = new UiRuntime(scheduler, createContainerRuntime(), Logger.getAnonymousLogger(), Thread.currentThread());
        backend = new ChestBackend(runtime, new MinimalItemRenderer(messages), messages, scheduler);
        runtime.register(backend);
        service = runtime.service("chest-test");
    }

    @AfterEach void disposeChest() {
        try {
            if (runtime != null) { runtime.close(); }
            if (clock != null) { clock.close(); }
        } finally { MockBukkit.unmock(); }
    }

    Messages createMessages() {
        return new InMemoryMessages("test", new FixedLocaleResolver(Locale.ENGLISH, Optional.empty()));
    }

    ContainerRuntime createContainerRuntime() { return new InMemoryContainerRuntime(); }

    GridSession open(Grid grid) {
        GridSession session = service.show(player, grid);
        tick();
        return session;
    }
    void tick() { clock.advanceTicks(1); }
    Inventory top() { return player.getOpenInventory().getTopInventory(); }
    SlotContainer container(int size, List<ItemStack> contents) {
        return service.container(ContainerSpec.storage(size).disposition(Disposition.RETURN_TO_CONTRIBUTORS)
                .undelivered((contributor, items) -> { /* Explicitly discarded in this test fixture. */ })
                .endWhenUnviewed(false).baseline(contents).build());
    }
    Grid grid(SlotContainer container, BindingMode mode) {
        return Grid.builder(Text.plain("Chest"), 1)
                .bindRange(0, 0, Math.min(9, container.size()), container, 0, mode).build();
    }
    static ItemStack stack(Material material, int amount) { return new ItemStack(material, amount); }

    static final class SyncPlayer extends PlayerMock {
        final List<RecordingScheduler.Scope> syncScopes = new ArrayList<>();
        SyncPlayer(ServerMock server, String name) { super(server, name); }
        @Override public void updateInventory() {
            syncScopes.add(RecordingScheduler.current().orElseThrow());
        }
    }

    // The existing item-package renderer is package-private; this local minimal renderer uses the same seams.
    private record MinimalItemRenderer(Messages messages) implements ItemRenderer {
        @Override public ItemStack render(ItemSpec spec, Locale locale) {
            ItemStack item = new ItemStack(Material.matchMaterial(spec.itemType().asString()), spec.amount());
            if (spec.name() != null) { item.setData(DataComponentTypes.ITEM_NAME, messages.render(spec.name(), locale)); }
            return item;
        }
        @Override public ItemStack render(ItemSpec spec, Audience viewer) { return render(spec, Locale.ENGLISH); }
    }
}
