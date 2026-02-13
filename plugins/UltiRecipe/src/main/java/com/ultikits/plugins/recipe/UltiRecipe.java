package com.ultikits.plugins.recipe;

import com.ultikits.plugins.recipe.service.RecipeService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiRecipe - Custom recipe management plugin for Minecraft servers.
 * <p>
 * Features:
 * - Create custom shaped recipes via YAML configuration
 * - Support for all vanilla materials
 * - Hot reload recipes without server restart
 * - Simple and intuitive configuration format
 * </p>
 * <p>
 * This plugin is part of the UltiKits ecosystem and requires UltiTools-API.
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.recipe"})
public class UltiRecipe extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        // Initialize recipe service
        RecipeService recipeService = getContext().getBean(RecipeService.class);
        if (recipeService != null) {
            int count = recipeService.initRecipes();
            getLogger().info(String.format(i18n("已注册 %d 个自定义配方"), count));
        }

        getLogger().info(i18n("UltiRecipe 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        // Remove all custom recipes
        RecipeService recipeService = getContext().getBean(RecipeService.class);
        if (recipeService != null) {
            recipeService.removeRecipes();
        }

        getLogger().info(i18n("UltiRecipe 已禁用！"));
    }

    @Override
    public void reloadSelf() {
        // Reload recipes
        RecipeService recipeService = getContext().getBean(RecipeService.class);
        if (recipeService != null) {
            int count = recipeService.reloadRecipes();
            getLogger().info(String.format(i18n("配方已重载，共 %d 个配方"), count));
        }
    }
}
