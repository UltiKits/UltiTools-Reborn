package com.ultikits.plugins.recipe.commands;

import com.ultikits.plugins.recipe.service.RecipeService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for managing custom recipes.
 * <p>
 * Commands:
 * - /recipe list - List all registered recipes
 * - /recipe reload - Reload recipes from configuration
 * - /recipe count - Show the number of registered recipes
 * <p>
 * Only registered when recipes are enabled in configuration.
 * <p>
 * 仅在配置中启用配方功能时注册。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"recipe", "ultirecipe"},
    permission = "ultirecipe.admin",
    description = "管理自定义配方"
)
@ConditionalOnConfig(value = "config/recipes.yml", path = "enabled")
public class RecipeCommand extends BaseCommandExecutor {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private RecipeService recipeService;

    /**
     * Gets the localized string from the plugin's language file.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }

    /**
     * List all registered recipes.
     */
    @CmdMapping(format = "list")
    public void listRecipes(@CmdSender CommandSender sender) {
        List<String> recipes = recipeService.getRecipeList();

        if (recipes == null || recipes.isEmpty()) {
            sender.sendMessage(i18n("§7没有已注册的配方"));
            return;
        }

        sender.sendMessage(i18n("§6=== 已注册的配方 ==="));
        for (String recipe : recipes) {
            sender.sendMessage("§7- §f" + recipe);
        }
        sender.sendMessage(String.format(i18n("§7共 §f%d §7个配方"), recipes.size()));
    }

    /**
     * Reload recipes from configuration.
     */
    @CmdMapping(format = "reload")
    public void reloadRecipes(@CmdSender CommandSender sender) {
        int count = recipeService.reloadRecipes();
        sender.sendMessage(String.format(i18n("§a配方已重载！共 %d 个配方"), count));
    }

    /**
     * Show recipe count.
     */
    @CmdMapping(format = "count")
    public void showCount(@CmdSender CommandSender sender) {
        int count = recipeService.getRecipeCount();
        sender.sendMessage(String.format(i18n("§7当前已注册 §f%d §7个配方"), count));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("§6=== UltiRecipe 帮助 ==="));
        sender.sendMessage("§e/recipe list §7- 列出所有配方");
        sender.sendMessage("§e/recipe reload §7- 重载配方配置");
        sender.sendMessage("§e/recipe count §7- 显示配方数量");
    }

    @Override
    protected List<String> suggest(Player player, Command command, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list", "reload", "count").stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return super.suggest(player, command, args);
    }
}
