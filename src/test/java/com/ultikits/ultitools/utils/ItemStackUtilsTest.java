package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

@Timeout(30)
class ItemStackUtilsTest {
    private static final NamespacedKey TOKEN = new NamespacedKey("sample", "token");

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void serializeItems_newFormat_roundTripsComponentsMarkersAndNullSlots() {
        ItemStack original = namedItem();
        String encoded = ItemStackUtils.serializeItems(new ItemStack[] {original, null, ItemStack.empty()});
        assertThat(encoded).isNotNull().doesNotContain("\n");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        assertThat(bytes).isEqualTo(ItemStack.serializeItemsAsBytes(new ItemStack[] {original, null, ItemStack.empty()}));
        ItemStack[] restored = ItemStackUtils.deserializeItems(encoded);
        assertThat(restored).hasSize(3);
        assertNamedItem(restored[0]);
        assertThat(restored[1]).isNull();
        assertThat(restored[2]).isNull();
    }

    @Test
    void serializeItem_singleItem_roundTrips() {
        ItemStack restored = ItemStackUtils.deserializeItem(ItemStackUtils.serializeItem(namedItem()));
        assertNamedItem(restored);
    }

    @Test
    void serializeItems_nullInput_returnsNull() {
        assertThat(ItemStackUtils.serializeItems(null)).isNull();
        assertThat(ItemStackUtils.serializeItem(null)).isNull();
    }

    @Test
    void serializeItems_paperFailure_returnsNull() {
        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class)))
                    .thenThrow(new IllegalStateException("Paper write failed"));
            assertThat(ItemStackUtils.serializeItems(new ItemStack[0])).isNull();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "AQ==", "!!!!"})
    void deserializeItems_absentOrMalformedInput_returnsNull(String encoded) {
        assertThat(ItemStackUtils.deserializeItems(encoded)).isNull();
        assertThat(ItemStackUtils.deserializeItem(encoded)).isNull();
        assertThat(ItemStackUtils.isValidSerialized(encoded)).isFalse();
    }

    @Test
    void deserializeItems_nonItemBytes_returnsNull() {
        String encoded = Base64.getEncoder().encodeToString("bad".getBytes(StandardCharsets.UTF_8));
        assertThat(ItemStackUtils.deserializeItems(encoded)).isNull();
    }

    @Test
    void deserializeItems_mimeLineBreaks_areAccepted() {
        byte[] bytes = ItemStack.serializeItemsAsBytes(new ItemStack[] {namedItem()});
        String encoded = Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encodeToString(bytes);
        assertThat(encoded).contains("\r\n");
        assertNamedItem(ItemStackUtils.deserializeItem(encoded));
    }

    @Test
    void serializeItems_emptyArray_roundTripsAndHasNoFirstItem() {
        String encoded = ItemStackUtils.serializeItems(new ItemStack[0]);
        assertThat(ItemStackUtils.deserializeItems(encoded)).isEmpty();
        assertThat(ItemStackUtils.deserializeItem(encoded)).isNull();
        assertThat(ItemStackUtils.isValidSerialized(encoded)).isTrue();
    }

    @Test
    void isValidSerialized_itemArray_returnsTrue() {
        assertThat(ItemStackUtils.isValidSerialized(ItemStackUtils.serializeItem(namedItem()))).isTrue();
    }

    @Test
    void deserializeItems_legacyLineWrappedStream_roundTripsItemsAndNullSlots() throws IOException {
        String encoded = legacyString(new ItemStack[] {namedItem(), null});
        assertThat(encoded).contains("\n");
        assertThat(Base64.getMimeDecoder().decode(encoded)).startsWith((byte) 0xAC, (byte) 0xED);
        ItemStack[] restored = ItemStackUtils.deserializeItems(encoded);
        assertThat(restored).hasSize(2);
        assertNamedItem(restored[0]);
        assertThat(restored[1]).isNull();
        assertThat(ItemStackUtils.isValidSerialized(encoded)).isTrue();
    }

    @Test
    void deserializeItems_legacyWrongObjectType_returnsNull() throws IOException {
        assertThat(ItemStackUtils.deserializeItems(legacyString(new Object[] {"not an item"}))).isNull();
    }

    @Test
    void deserializeItems_legacyTruncatedStream_returnsNull() {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xAC, (byte) 0xED});
        assertThat(ItemStackUtils.deserializeItems(encoded)).isNull();
    }

    @SuppressWarnings("deprecation")
    private static String legacyString(Object[] items) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeInt(items.length);
            for (Object item : items) {
                output.writeObject(item);
            }
            output.flush();
            return Base64Coder.encodeLines(bytes.toByteArray());
        }
    }

    private static ItemStack namedItem() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 3);
        // MockBukkit's byte serializer reads ItemMeta, not its separate setData component map.
        stack.editMeta(meta -> {
            meta.itemName(Component.text("Saved diamond"));
            meta.lore(List.of(Component.text("Saved lore")));
        });
        stack.editPersistentDataContainer(data -> data.set(TOKEN, PersistentDataType.STRING, "saved-token"));
        return stack;
    }

    private static void assertNamedItem(ItemStack stack) {
        assertThat(stack).isNotNull();
        assertThat(stack.getType()).isEqualTo(Material.DIAMOND);
        assertThat(stack.getAmount()).isEqualTo(3);
        assertThat(stack.getItemMeta().itemName()).isEqualTo(Component.text("Saved diamond"));
        assertThat(stack.getItemMeta().lore()).containsExactly(Component.text("Saved lore"));
        assertThat(stack.getPersistentDataContainer().get(TOKEN, PersistentDataType.STRING)).isEqualTo("saved-token");
    }
}
