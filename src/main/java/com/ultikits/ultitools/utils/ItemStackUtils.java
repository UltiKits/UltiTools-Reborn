package com.ultikits.ultitools.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

/**
 * Utility class for ItemStack serialization and deserialization.
 * Uses Base64 encoding to convert ItemStack arrays to/from strings for storage.
 * <p>
 * ItemStack 序列化和反序列化工具类。
 * 使用 Base64 编码将 ItemStack 数组转换为字符串用于存储。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public final class ItemStackUtils {
    
    private static final Logger LOGGER = Logger.getLogger(ItemStackUtils.class.getName());
    
    private ItemStackUtils() {
        // Utility class, prevent instantiation
    }
    
    /**
     * Serializes an array of ItemStack to a Base64-encoded string.
     * <p>
     * 将 ItemStack 数组序列化为 Base64 编码的字符串。
     *
     * @param items the ItemStack array to serialize
     * @return the Base64-encoded string, or null if serialization fails
     */
    public static String serializeItems(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.flush();
            
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to serialize ItemStack array", e);
            return null;
        }
    }
    
    /**
     * Deserializes a Base64-encoded string back to an array of ItemStack.
     * <p>
     * 将 Base64 编码的字符串反序列化为 ItemStack 数组。
     *
     * @param data the Base64-encoded string
     * @return the ItemStack array, or null if deserialization fails
     */
    public static ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            
            return items;
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Failed to deserialize ItemStack array", e);
            return null;
        }
    }
    
    /**
     * Serializes a single ItemStack to a Base64-encoded string.
     * <p>
     * 将单个 ItemStack 序列化为 Base64 编码的字符串。
     *
     * @param item the ItemStack to serialize
     * @return the Base64-encoded string, or null if serialization fails
     */
    public static String serializeItem(ItemStack item) {
        if (item == null) {
            return null;
        }
        return serializeItems(new ItemStack[]{item});
    }
    
    /**
     * Deserializes a Base64-encoded string back to a single ItemStack.
     * <p>
     * 将 Base64 编码的字符串反序列化为单个 ItemStack。
     *
     * @param data the Base64-encoded string
     * @return the ItemStack, or null if deserialization fails or array is empty
     */
    public static ItemStack deserializeItem(String data) {
        ItemStack[] items = deserializeItems(data);
        if (items != null && items.length > 0) {
            return items[0];
        }
        return null;
    }
    
    /**
     * Checks if a serialized string is valid.
     * <p>
     * 检查序列化字符串是否有效。
     *
     * @param data the Base64-encoded string to check
     * @return true if the string can be deserialized, false otherwise
     */
    public static boolean isValidSerialized(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return deserializeItems(data) != null;
    }
}
