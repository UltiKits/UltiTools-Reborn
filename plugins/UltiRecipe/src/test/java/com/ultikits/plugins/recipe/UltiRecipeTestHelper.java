package com.ultikits.plugins.recipe;

import com.ultikits.plugins.recipe.config.RecipeConfig;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework singletons.
 * <p>
 * UltiTools is a {@code final class extends JavaPlugin} — it cannot be mocked.
 * This helper mocks only UltiRecipe (extends abstract UltiToolsPlugin) and
 * provides a mock Plugin for NamespacedKey creation in tests that need it.
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
public final class UltiRecipeTestHelper {

    private UltiRecipeTestHelper() {}

    private static UltiRecipe mockPlugin;
    private static PluginLogger mockLogger;
    private static Plugin mockJavaPlugin;

    /**
     * Set up UltiRecipe singleton mock. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock UltiRecipe singleton (abstract UltiToolsPlugin — mockable)
        mockPlugin = mock(UltiRecipe.class);
        setStaticField(UltiRecipe.class, "instance", mockPlugin);

        // Mock logger
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);

        // Mock i18n to return the key as-is
        lenient().when(mockPlugin.i18n(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock getDataOperator
        lenient().when(mockPlugin.getDataOperator(any()))
                .thenReturn(mock(DataOperator.class));

        // Create a mock Plugin for NamespacedKey creation
        // This is used when tests need UltiTools.getInstance() for creating NamespacedKeys
        mockJavaPlugin = mock(Plugin.class);
        lenient().when(mockJavaPlugin.getName()).thenReturn("UltiTools");
    }

    /**
     * Clean up singleton state.
     */
    public static void tearDown() throws Exception {
        setStaticField(UltiRecipe.class, "instance", null);
    }

    public static UltiRecipe getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    /**
     * Get a mock Plugin for NamespacedKey creation.
     * Use this when mocking UltiTools.getInstance() in tests.
     */
    public static Plugin getMockJavaPlugin() {
        return mockJavaPlugin;
    }

    /**
     * Create a default RecipeConfig mock with recipe system enabled.
     */
    public static RecipeConfig createDefaultConfig() {
        RecipeConfig config = mock(RecipeConfig.class);
        lenient().when(config.isEnabled()).thenReturn(true);
        lenient().when(config.getRecipes()).thenReturn(new HashMap<>());
        return config;
    }

    /**
     * Create a RecipeConfig with sample recipes.
     */
    public static RecipeConfig createConfigWithSampleRecipes() {
        RecipeConfig config = mock(RecipeConfig.class);
        lenient().when(config.isEnabled()).thenReturn(true);

        // Create sample recipe
        RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
        RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
        output.setMaterial("DIAMOND");
        output.setAmount(1);
        output.setName("&bCustom Diamond");
        List<String> lore = new ArrayList<>();
        lore.add("&7A custom diamond");
        output.setLore(lore);
        recipe.setOutput(output);

        List<String> shape = new ArrayList<>();
        shape.add("xxx");
        shape.add("xyx");
        shape.add("xxx");
        recipe.setShape(shape);

        HashMap<String, String> ingredients = new HashMap<>();
        ingredients.put("x", "COAL");
        ingredients.put("y", "STICK");
        recipe.setIngredients(ingredients);

        HashMap<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
        recipes.put("custom_diamond", recipe);

        lenient().when(config.getRecipes()).thenReturn(recipes);
        return config;
    }

    /**
     * Create a mock Player with basic properties.
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.hasPermission(anyString())).thenReturn(true);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);

        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        lenient().when(player.getInventory()).thenReturn(inventory);

        return player;
    }

    /**
     * Create a mock CommandSender.
     */
    public static CommandSender createMockSender(String name) {
        CommandSender sender = mock(CommandSender.class);
        lenient().when(sender.getName()).thenReturn(name);
        return sender;
    }

    // --- Reflection ---

    public static void setStaticField(Class<?> clazz, String fieldName, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
