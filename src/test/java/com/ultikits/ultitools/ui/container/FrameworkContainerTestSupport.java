package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerStore;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.FrameworkRuntimeFixture;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.ViewerItems;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(5)
abstract class FrameworkContainerTestSupport {
    static final GridSlot FIRST = GridSlot.ofIndex(0);
    FrameworkContainerRuntime containers;
    FrameworkRuntimeFixture fixture;
    UiService service;
    ServerMock server;
    PlayerMock viewer;
    final Map<UUID, List<ItemStack>> undelivered = new LinkedHashMap<>();

    @BeforeEach void setUpContainer() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        viewer = server.addPlayer("ContainerViewer");
        Thread thread = Thread.currentThread();
        containers = new FrameworkContainerRuntime(() -> thread.equals(Thread.currentThread()),
                (player, text) -> player.sendMessage("refused"));
        fixture = new FrameworkRuntimeFixture(containers);
        service = fixture.service("ContainerModule");
    }
    @AfterEach void tearDownContainer() {
        try { if (fixture != null) { fixture.close(); } }
        finally { MockBukkitHelper.safeUnmock(); }
    }
    ContainerSpec.Builder returning(int size) {
        return ContainerSpec.storage(size).disposition(Disposition.RETURN_TO_CONTRIBUTORS).endWhenUnviewed(false)
                .undelivered((player, items) -> undelivered.computeIfAbsent(player, ignored -> new ArrayList<>()).addAll(items));
    }
    ContainerSpec.Builder committed(int size, ContainerStore store) {
        return returning(size).disposition(Disposition.COMMIT).store(store);
    }
    SlotContainer contributed(ContainerStore store) {
        return service.container(committed(1, store).initial(viewer.getUniqueId(), List.of(stone(8))).build());
    }
    static Grid grid(SlotContainer container) {
        return Grid.builder(Text.plain("Container test"), 1).bindRange(0, 0, container.size(), container, 0, BindingMode.EDITABLE).build();
    }
    static ItemStack stone(int amount) { return new ItemStack(Material.STONE, amount); }
    static ViewerItems side() { return new StackMathTest.TestItems(36); }
    static <T> T completed(CompletionStage<T> result) {
        assertThat(result.toCompletableFuture()).isDone();
        return result.toCompletableFuture().getNow(null);
    }
}
