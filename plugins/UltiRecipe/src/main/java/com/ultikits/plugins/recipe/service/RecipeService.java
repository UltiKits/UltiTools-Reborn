package com.ultikits.plugins.recipe.service;

import com.ultikits.plugins.recipe.config.RecipeConfig;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing custom recipes.
 * <p>
 * Handles recipe registration, removal, and reloading.
 * Only active when enabled in recipes.yml configuration.
 * <p>
 * 仅在 recipes.yml 配置中启用时激活。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
@ConditionalOnConfig(value = "config/recipes.yml", path = "enabled")
public class RecipeService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private RecipeConfig config;

    /**
     * Set of registered recipe keys for cleanup.
     */
    private final Set<NamespacedKey> registeredRecipes = new HashSet<>();

    /**
     * Plugin instance for NamespacedKey creation.
     * Package-private for testing.
     */
    Plugin pluginInstance;

    private PluginLogger getLogger() {
        return plugin.getLogger();
    }

    /**
     * Get the plugin instance for NamespacedKey creation.
     * Uses Bukkit plugin manager lookup in production, can be overridden in tests.
     */
    Plugin getPluginInstance() {
        if (pluginInstance == null) {
            pluginInstance = Bukkit.getPluginManager().getPlugin("UltiTools");
        }
        return pluginInstance;
    }

    /**
     * Initializes all recipes from configuration.
     * Only called when the service is active (enabled in config).
     * <p>
     * 仅在服务激活时调用（配置中启用）。
     *
     * @return the number of recipes registered
     */
    public int initRecipes() {
        Map<String, RecipeConfig.RecipeDefinition> recipes = config.getRecipes();
        
        if (recipes == null || recipes.isEmpty()) {
            getLogger().info("No custom recipes configured");
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, RecipeConfig.RecipeDefinition> entry : recipes.entrySet()) {
            String recipeName = entry.getKey();
            RecipeConfig.RecipeDefinition definition = entry.getValue();
            
            try {
                if (registerRecipe(recipeName, definition)) {
                    count++;
                }
            } catch (Exception e) {
                getLogger().warn("Failed to register recipe: " + recipeName + " - " + e.getMessage());
            }
        }

        return count;
    }

    /**
     * Registers a single recipe.
     *
     * @param name       the recipe name
     * @param definition the recipe definition
     * @return true if registered successfully
     */
    private boolean registerRecipe(String name, RecipeConfig.RecipeDefinition definition) {
        // Validate definition
        if (definition.getOutput() == null || definition.getShape() == null || definition.getIngredients() == null) {
            getLogger().warn("Invalid recipe definition for: " + name);
            return false;
        }

        // Create output item
        ItemStack output = createOutputItem(definition.getOutput());
        if (output == null) {
            getLogger().warn("Invalid output material for recipe: " + name);
            return false;
        }

        // Create recipe - use plugin instance for NamespacedKey since UltiToolsPlugin doesn't extend JavaPlugin
        NamespacedKey key = new NamespacedKey(getPluginInstance(), "ultirecipe_" + name);
        ShapedRecipe recipe = new ShapedRecipe(key, output);

        // Set shape
        List<String> shape = definition.getShape();
        if (shape.size() != 3) {
            getLogger().warn("Recipe shape must have exactly 3 rows for: " + name);
            return false;
        }
        recipe.shape(shape.get(0), shape.get(1), shape.get(2));

        // Set ingredients
        Map<String, String> ingredients = definition.getIngredients();
        for (Map.Entry<String, String> ingredient : ingredients.entrySet()) {
            String charKey = ingredient.getKey();
            String materialName = ingredient.getValue();
            
            if (charKey.length() != 1) {
                getLogger().warn("Ingredient key must be a single character for recipe: " + name);
                continue;
            }
            
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                getLogger().warn("Unknown material '" + materialName + "' in recipe: " + name);
                continue;
            }
            
            recipe.setIngredient(charKey.charAt(0), material);
        }

        // Register recipe
        Bukkit.addRecipe(recipe);
        registeredRecipes.add(key);
        
        getLogger().info("Registered recipe: " + name);
        return true;
    }

    /**
     * Creates an output ItemStack from configuration.
     *
     * @param outputConfig the output configuration
     * @return the created ItemStack, or null if invalid
     */
    private ItemStack createOutputItem(RecipeConfig.OutputItem outputConfig) {
        Material material = Material.matchMaterial(outputConfig.getMaterial());
        if (material == null) {
            return null;
        }

        ItemStack item = new ItemStack(material, outputConfig.getAmount());
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            // Set custom name
            if (outputConfig.getName() != null && !outputConfig.getName().isEmpty()) {
                meta.setDisplayName(translateColorCodes(outputConfig.getName()));
            }
            
            // Set lore
            if (outputConfig.getLore() != null && !outputConfig.getLore().isEmpty()) {
                List<String> lore = outputConfig.getLore().stream()
                        .map(this::translateColorCodes)
                        .collect(Collectors.toList());
                meta.setLore(lore);
            }
            
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Translates color codes (&) to Minecraft color codes (§).
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateColorCodes(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Removes all registered custom recipes.
     */
    public void removeRecipes() {
        for (NamespacedKey key : registeredRecipes) {
            Bukkit.removeRecipe(key);
            getLogger().info("Removed recipe: " + key.getKey());
        }
        registeredRecipes.clear();
    }

    /**
     * Reloads all recipes (removes and re-registers).
     *
     * @return the number of recipes registered after reload
     */
    public int reloadRecipes() {
        removeRecipes();
        return initRecipes();
    }

    /**
     * Gets the list of registered recipe names.
     *
     * @return list of recipe names
     */
    public List<String> getRecipeList() {
        return registeredRecipes.stream()
                .map(key -> key.getKey().replace("ultirecipe_", ""))
                .collect(Collectors.toList());
    }

    /**
     * Gets the count of registered recipes.
     *
     * @return the number of registered recipes
     */
    public int getRecipeCount() {
        return registeredRecipes.size();
    }
}
