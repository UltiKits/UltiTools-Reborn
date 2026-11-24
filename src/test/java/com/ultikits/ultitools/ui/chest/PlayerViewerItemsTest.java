package com.ultikits.ultitools.ui.chest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(15)
class PlayerViewerItemsTest {
    private PlayerMock player;
    private PlayerViewerItems items;

    @BeforeEach void prepare() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        player.openInventory(server.createInventory(null, 9));
        items = new PlayerViewerItems(player.getOpenInventory(), player);
    }
    @AfterEach void dispose() { MockBukkit.unmock(); }

    @Test void all36SlotsMatchStorageContentsAndExcludeArmourAndOffhand() {
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        for (int index = 0; index < 36; index++) {
            items.slot(index, new ItemStack(Material.STONE, index + 1));
            assertThat(items.slot(index)).isEqualTo(player.getInventory().getStorageContents()[index]);
        }
        assertThat(items.inventorySize()).isEqualTo(36);
        assertThat(player.getInventory().getHelmet().getType()).isEqualTo(Material.IRON_HELMET);
        assertThat(player.getInventory().getItemInOffHand().getType()).isEqualTo(Material.SHIELD);
        assertThatThrownBy(() -> items.slot(36)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> items.slot(-1, ItemStack.empty())).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test void validWritesRemainAvailableWithoutExceptionsAfterTheViewerDisconnects() {
        player.disconnect();
        assertThatCode(() -> {
            items.cursor(new ItemStack(Material.STONE, 9));
            items.slot(0, new ItemStack(Material.DIRT, 3));
            items.slot(35, ItemStack.empty());
        }).doesNotThrowAnyException();
        assertThat(player.isOnline()).isFalse();
        assertThat(items.cursor()).isEqualTo(new ItemStack(Material.STONE, 9));
        assertThat(items.slot(0)).isEqualTo(new ItemStack(Material.DIRT, 3));
        assertThat(items.slot(35).isEmpty()).isTrue();
    }

    @Test void viewCursorAndPlayerCursorAreTheSameLogicalStackWithDefensiveCopies() {
        assertThat(items.cursor().isEmpty()).isTrue();
        items.cursor(new ItemStack(Material.STONE, 9));
        assertThat(player.getItemOnCursor()).isEqualTo(new ItemStack(Material.STONE, 9));
        ItemStack copy = items.cursor();
        copy.setAmount(1);
        assertThat(items.cursor().getAmount()).isEqualTo(9);
        player.setItemOnCursor(new ItemStack(Material.DIRT, 3));
        assertThat(items.cursor()).isEqualTo(new ItemStack(Material.DIRT, 3));
        items.cursor(ItemStack.empty());
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
        assertThat(items.slot(0).isEmpty()).isTrue();
    }
}
