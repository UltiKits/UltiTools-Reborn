package com.ultikits.ultitools.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for ItemStack serialization and deserialization.
 * Uses Base64 encoding of Paper's item bytes for storage.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public final class ItemStackUtils {
    private static final Logger LOGGER = Logger.getLogger(ItemStackUtils.class.getName());

    private ItemStackUtils() {
        // Utility class, prevent instantiation.
    }

    /**
     * Serializes an array using Paper's item byte format and Base64 encoding.
     *
     * @param items the array to serialize, including null or empty slots
     * @return the Base64-encoded string, or null for null input or a serialization failure
     */
    public static @Nullable String serializeItems(@Nullable ItemStack @Nullable [] items) {
        if (items == null) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
        } catch (RuntimeException e) { // NOPMD - Paper throws unspecified unchecked exceptions for malformed item bytes
            LOGGER.log(Level.SEVERE, "Failed to serialize ItemStack array", e);
            return null;
        }
    }

    /**
     * Deserializes a Base64-encoded array of Paper item bytes, accepting MIME line breaks.
     * Empty items are returned as null slots. Legacy Bukkit object streams saved by 6.x are also accepted.
     *
     * @param data the Base64-encoded string
     * @return the item array, or null for absent input or a deserialization failure
     */
    public static @Nullable ItemStack @Nullable [] deserializeItems(@Nullable String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(data);
            ItemStack[] items = isLegacy(bytes) ? readLegacy(bytes) : ItemStack.deserializeItemsFromBytes(bytes);
            for (int index = 0; index < items.length; index++) {
                if (items[index] != null && items[index].isEmpty()) {
                    items[index] = null;
                }
            }
            return items;
        } catch (IOException | ClassNotFoundException | RuntimeException e) { // NOPMD - Paper throws unspecified unchecked exceptions for malformed item bytes
            LOGGER.log(Level.SEVERE, "Failed to deserialize ItemStack array", e);
            return null;
        }
    }

    private static boolean isLegacy(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == (byte) 0xAC && bytes[1] == (byte) 0xED;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack[] readLegacy(byte[] bytes) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            ItemStack[] items = new ItemStack[input.readInt()];
            for (int index = 0; index < items.length; index++) {
                items[index] = (ItemStack) input.readObject();
            }
            return items;
        }
    }

    /**
     * Serializes a single ItemStack to a Base64-encoded string.
     *
     * @param item the item to serialize
     * @return the Base64-encoded string, or null for null input or a serialization failure
     */
    public static @Nullable String serializeItem(@Nullable ItemStack item) {
        if (item == null) {
            return null;
        }
        return serializeItems(new ItemStack[] {item});
    }

    /**
     * Deserializes a Base64-encoded string back to a single ItemStack.
     *
     * @param data the Base64-encoded string
     * @return the first item, or null for a deserialization failure, empty array or empty slot
     */
    public static @Nullable ItemStack deserializeItem(@Nullable String data) {
        ItemStack[] items = deserializeItems(data);
        if (items != null && items.length > 0) {
            return items[0];
        }
        return null;
    }

    /**
     * Checks whether a string can be deserialized as an item array.
     *
     * @param data the Base64-encoded string to check
     * @return true if the string can be deserialized, false otherwise
     */
    public static boolean isValidSerialized(@Nullable String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return deserializeItems(data) != null;
    }
}
