package com.ultikits.plugins.worlds.conversation;

import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.conversations.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * World creation wizard using Bukkit Conversation API.
 * Features 60-second timeout and multi-step prompts.
 *
 * @author wisdomme
 * @version 2.0.0
 */
public class WorldCreateConversation implements ConversationAbandonedListener {
    
    private static final int TIMEOUT_SECONDS = 60;
    private static final Pattern WORLD_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");
    
    private static final String KEY_WORLD_NAME = "world_name";
    private static final String KEY_WORLD_TYPE = "world_type";
    private static final String KEY_ENVIRONMENT = "environment";
    private static final String KEY_GENERATE_STRUCTURES = "generate_structures";
    private static final String KEY_SEED = "seed";
    
    private final WorldService worldService;
    private final UltiToolsPlugin ultiPlugin;
    private final Plugin plugin;

    public WorldCreateConversation(WorldService worldService, UltiToolsPlugin ultiPlugin) {
        this.worldService = worldService;
        this.ultiPlugin = ultiPlugin;
        this.plugin = Bukkit.getPluginManager().getPlugin("UltiTools");
    }

    /**
     * Start the world creation wizard for a player.
     *
     * @param player The player to start the wizard for
     * @param worldService The world service
     * @param ultiPlugin The plugin instance for i18n
     */
    public static void start(Player player, WorldService worldService, UltiToolsPlugin ultiPlugin) {
        WorldCreateConversation wizard = new WorldCreateConversation(worldService, ultiPlugin);
        wizard.beginConversation(player);
    }
    
    /**
     * Begin the conversation with a player.
     */
    public void beginConversation(Player player) {
        Conversation conversation = new ConversationFactory(plugin)
                .withModality(true)
                .withFirstPrompt(new WorldNamePrompt())
                .withEscapeSequence("cancel")
                .withTimeout(TIMEOUT_SECONDS)
                .withLocalEcho(false)
                .addConversationAbandonedListener(this)
                .buildConversation(player);
        
        player.sendMessage("");
        player.sendMessage(i18n("wizard.header"));
        player.sendMessage(i18n("wizard.cancel_hint"));
        player.sendMessage("");
        
        conversation.begin();
    }
    
    @Override
    public void conversationAbandoned(ConversationAbandonedEvent event) {
        if (!event.gracefulExit()) {
            if (event.getCanceller() instanceof InactivityConversationCanceller) {
                event.getContext().getForWhom().sendRawMessage(i18n("wizard.timeout"));
            } else {
                event.getContext().getForWhom().sendRawMessage(i18n("wizard.cancelled"));
            }
        }
    }
    
    /**
     * Step 1: Ask for world name.
     */
    private class WorldNamePrompt extends ValidatingPrompt {
        
        @Override
        public String getPromptText(ConversationContext context) {
            return i18n("wizard.enter_name");
        }
        
        @Override
        protected boolean isInputValid(ConversationContext context, String input) {
            if (!WORLD_NAME_PATTERN.matcher(input).matches()) {
                return false;
            }
            
            // Check if world already exists
            return Bukkit.getWorld(input) == null;
        }
        
        @Override
        protected String getFailedValidationText(ConversationContext context, String invalidInput) {
            if (!WORLD_NAME_PATTERN.matcher(invalidInput).matches()) {
                return i18n("wizard.invalid_name");
            }
            return i18n("wizard.world_exists");
        }
        
        @Override
        protected Prompt acceptValidatedInput(ConversationContext context, String input) {
            context.setSessionData(KEY_WORLD_NAME, input);
            return new EnvironmentPrompt();
        }
    }
    
    /**
     * Step 2: Ask for world environment (dimension).
     */
    private class EnvironmentPrompt extends ValidatingPrompt {
        
        private final Map<String, World.Environment> environmentMap = new HashMap<>();
        
        public EnvironmentPrompt() {
            environmentMap.put("1", World.Environment.NORMAL);
            environmentMap.put("normal", World.Environment.NORMAL);
            environmentMap.put("2", World.Environment.NETHER);
            environmentMap.put("nether", World.Environment.NETHER);
            environmentMap.put("3", World.Environment.THE_END);
            environmentMap.put("end", World.Environment.THE_END);
            environmentMap.put("the_end", World.Environment.THE_END);
        }
        
        @Override
        public String getPromptText(ConversationContext context) {
            StringBuilder sb = new StringBuilder();
            sb.append(i18n("wizard.select_environment")).append("\n");
            sb.append("§7[1] §f").append(i18n("environment.normal")).append("\n");
            sb.append("§7[2] §f").append(i18n("environment.nether")).append("\n");
            sb.append("§7[3] §f").append(i18n("environment.the_end"));
            return sb.toString();
        }
        
        @Override
        protected boolean isInputValid(ConversationContext context, String input) {
            return environmentMap.containsKey(input.toLowerCase());
        }
        
        @Override
        protected String getFailedValidationText(ConversationContext context, String invalidInput) {
            return i18n("wizard.invalid_environment");
        }
        
        @Override
        protected Prompt acceptValidatedInput(ConversationContext context, String input) {
            context.setSessionData(KEY_ENVIRONMENT, environmentMap.get(input.toLowerCase()));
            return new WorldTypePrompt();
        }
    }
    
    /**
     * Step 3: Ask for world type (terrain generation).
     */
    private class WorldTypePrompt extends ValidatingPrompt {
        
        private final Map<String, WorldType> typeMap = new HashMap<>();
        
        public WorldTypePrompt() {
            typeMap.put("1", WorldType.NORMAL);
            typeMap.put("normal", WorldType.NORMAL);
            typeMap.put("2", WorldType.FLAT);
            typeMap.put("flat", WorldType.FLAT);
            typeMap.put("3", WorldType.AMPLIFIED);
            typeMap.put("amplified", WorldType.AMPLIFIED);
            typeMap.put("4", WorldType.LARGE_BIOMES);
            typeMap.put("large", WorldType.LARGE_BIOMES);
            typeMap.put("large_biomes", WorldType.LARGE_BIOMES);
        }
        
        @Override
        public String getPromptText(ConversationContext context) {
            StringBuilder sb = new StringBuilder();
            sb.append(i18n("wizard.select_type")).append("\n");
            sb.append("§7[1] §f").append(i18n("type.normal")).append("\n");
            sb.append("§7[2] §f").append(i18n("type.flat")).append("\n");
            sb.append("§7[3] §f").append(i18n("type.amplified")).append("\n");
            sb.append("§7[4] §f").append(i18n("type.large_biomes"));
            return sb.toString();
        }
        
        @Override
        protected boolean isInputValid(ConversationContext context, String input) {
            return typeMap.containsKey(input.toLowerCase());
        }
        
        @Override
        protected String getFailedValidationText(ConversationContext context, String invalidInput) {
            return i18n("wizard.invalid_type");
        }
        
        @Override
        protected Prompt acceptValidatedInput(ConversationContext context, String input) {
            context.setSessionData(KEY_WORLD_TYPE, typeMap.get(input.toLowerCase()));
            return new StructuresPrompt();
        }
    }
    
    /**
     * Step 4: Ask whether to generate structures.
     */
    private class StructuresPrompt extends ValidatingPrompt {
        
        private final List<String> yesValues = Arrays.asList("y", "yes", "true", "1", "是");
        private final List<String> noValues = Arrays.asList("n", "no", "false", "0", "否");
        
        @Override
        public String getPromptText(ConversationContext context) {
            return i18n("wizard.generate_structures") + " §7(y/n)";
        }
        
        @Override
        protected boolean isInputValid(ConversationContext context, String input) {
            String lower = input.toLowerCase();
            return yesValues.contains(lower) || noValues.contains(lower);
        }
        
        @Override
        protected String getFailedValidationText(ConversationContext context, String invalidInput) {
            return i18n("wizard.invalid_yn");
        }
        
        @Override
        protected Prompt acceptValidatedInput(ConversationContext context, String input) {
            context.setSessionData(KEY_GENERATE_STRUCTURES, yesValues.contains(input.toLowerCase()));
            return new SeedPrompt();
        }
    }
    
    /**
     * Step 5: Ask for world seed (optional).
     */
    private class SeedPrompt extends StringPrompt {
        
        @Override
        public String getPromptText(ConversationContext context) {
            return i18n("wizard.enter_seed");
        }
        
        @Override
        public Prompt acceptInput(ConversationContext context, String input) {
            if (input != null && !input.isEmpty() && !input.equalsIgnoreCase("random")) {
                context.setSessionData(KEY_SEED, input);
            }
            return new ConfirmPrompt();
        }
    }
    
    /**
     * Step 6: Confirm creation.
     */
    private class ConfirmPrompt extends ValidatingPrompt {
        
        private final List<String> yesValues = Arrays.asList("y", "yes", "true", "1", "是", "confirm");
        private final List<String> noValues = Arrays.asList("n", "no", "false", "0", "否", "cancel");
        
        @Override
        public String getPromptText(ConversationContext context) {
            String worldName = (String) context.getSessionData(KEY_WORLD_NAME);
            World.Environment env = (World.Environment) context.getSessionData(KEY_ENVIRONMENT);
            WorldType type = (WorldType) context.getSessionData(KEY_WORLD_TYPE);
            Boolean structures = (Boolean) context.getSessionData(KEY_GENERATE_STRUCTURES);
            String seed = (String) context.getSessionData(KEY_SEED);
            
            StringBuilder sb = new StringBuilder();
            sb.append(i18n("wizard.confirm_header")).append("\n");
            sb.append("§7").append(i18n("wizard.confirm_name")).append(" §f").append(worldName).append("\n");
            sb.append("§7").append(i18n("wizard.confirm_env")).append(" §f").append(getEnvironmentName(env)).append("\n");
            sb.append("§7").append(i18n("wizard.confirm_type")).append(" §f").append(getTypeName(type)).append("\n");
            sb.append("§7").append(i18n("wizard.confirm_structures")).append(" §f")
                    .append(structures ? i18n("common.yes") : i18n("common.no")).append("\n");
            sb.append("§7").append(i18n("wizard.confirm_seed")).append(" §f")
                    .append(seed != null ? seed : i18n("common.random")).append("\n");
            sb.append("\n").append(i18n("wizard.confirm_question")).append(" §7(y/n)");
            
            return sb.toString();
        }
        
        @Override
        protected boolean isInputValid(ConversationContext context, String input) {
            String lower = input.toLowerCase();
            return yesValues.contains(lower) || noValues.contains(lower);
        }
        
        @Override
        protected String getFailedValidationText(ConversationContext context, String invalidInput) {
            return i18n("wizard.invalid_yn");
        }
        
        @Override
        protected Prompt acceptValidatedInput(ConversationContext context, String input) {
            if (yesValues.contains(input.toLowerCase())) {
                return new CreateWorldPrompt();
            }
            context.getForWhom().sendRawMessage(i18n("wizard.cancelled"));
            return Prompt.END_OF_CONVERSATION;
        }
    }
    
    /**
     * Final step: Create the world.
     */
    private class CreateWorldPrompt extends MessagePrompt {
        
        @Override
        public String getPromptText(ConversationContext context) {
            return i18n("wizard.creating");
        }
        
        @Override
        protected Prompt getNextPrompt(ConversationContext context) {
            String worldName = (String) context.getSessionData(KEY_WORLD_NAME);
            World.Environment env = (World.Environment) context.getSessionData(KEY_ENVIRONMENT);
            WorldType type = (WorldType) context.getSessionData(KEY_WORLD_TYPE);
            Boolean structures = (Boolean) context.getSessionData(KEY_GENERATE_STRUCTURES);
            String seed = (String) context.getSessionData(KEY_SEED);
            
            // Schedule creation on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    World world = worldService.createWorld(worldName, env, type, structures, seed);
                    if (world != null) {
                        context.getForWhom().sendRawMessage(i18n("wizard.success").replace("%world%", worldName));
                        
                        // Teleport player to new world
                        if (context.getForWhom() instanceof Player) {
                            Player player = (Player) context.getForWhom();
                            player.teleport(world.getSpawnLocation());
                        }
                    } else {
                        context.getForWhom().sendRawMessage(i18n("wizard.failed").replace("%world%", worldName));
                    }
                } catch (Exception e) {
                    context.getForWhom().sendRawMessage(i18n("wizard.error").replace("%error%", e.getMessage()));
                }
            });
            
            return Prompt.END_OF_CONVERSATION;
        }
    }
    
    private String getEnvironmentName(World.Environment env) {
        switch (env) {
            case NORMAL: return i18n("environment.normal");
            case NETHER: return i18n("environment.nether");
            case THE_END: return i18n("environment.the_end");
            default: return env.name();
        }
    }
    
    private String getTypeName(WorldType type) {
        switch (type) {
            case NORMAL: return i18n("type.normal");
            case FLAT: return i18n("type.flat");
            case AMPLIFIED: return i18n("type.amplified");
            case LARGE_BIOMES: return i18n("type.large_biomes");
            default: return type.name();
        }
    }
    
    private String i18n(String key) {
        return ultiPlugin.i18n(key);
    }
}
