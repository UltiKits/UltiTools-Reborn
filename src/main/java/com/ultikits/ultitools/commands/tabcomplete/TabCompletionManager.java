package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdParam;

/**
 * Central manager for tab completion functionality.
 * Provides a unified API for both old and new command systems.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class TabCompletionManager {
    
    /**
     * Built-in completer prefix for players.
     */
    public static final String PLAYERS = "@players";
    
    /**
     * Built-in completer prefix for worlds.
     */
    public static final String WORLDS = "@worlds";
    
    /**
     * Built-in completer prefix for materials.
     */
    public static final String MATERIALS = "@materials";
    
    /**
     * Built-in completer prefix for blocks.
     */
    public static final String BLOCKS = "@blocks";
    
    /**
     * Built-in completer prefix for items.
     */
    public static final String ITEMS = "@items";
    
    /**
     * Built-in completer prefix for boolean values.
     */
    public static final String BOOLEAN = "@boolean";
    
    /**
     * Built-in completer prefix for toggle values.
     */
    public static final String TOGGLE = "@toggle";
    
    private static final Logger LOGGER = Logger.getLogger(TabCompletionManager.class.getName());

    private static volatile TabCompletionManager instance;
    
    /**
     * Key -&gt; its registration: the completer together with the owner name and owner instance the
     * registration was attributed to (05-06 / D-08, T-05-24; #506). One immutable entry per key,
     * replaced as a whole by {@link #register(String, TabCompleter)} and removed by the owner sweeps
     * only while it is still the exact entry they matched, so a key registered again meanwhile --
     * from another thread, or by anything the sweep triggers -- keeps its new completer (Codex review
     * of #564, round 4). The owner fields are recorded only while a {@link
     * #beginRegistrationScope(String)}/{@link #endRegistrationScope()} window is active; a completer
     * registered outside one is "unowned" and never swept. Keyed by the completer KEY (a {@code
     * String}), not by the completer's {@code Class} -- Phase 1 D-35/D-38 forbids static {@code
     * Class}-keyed maps because they pin a plugin's ClassLoader. An owner instance is held only
     * until that module is unloaded, exactly as the completer object itself is.
     */
    private final Map<String, Registration> completers = new ConcurrentHashMap<>();
    private final MethodInvocationCompleter methodCompleter = new MethodInvocationCompleter();

    /**
     * The owner currently attributed to registrations made via {@link #register(String,
     * TabCompleter)} -- set by {@link #beginRegistrationScope(String)}, cleared by {@link
     * #endRegistrationScope()}. {@code ThreadLocal} rather than a plain field because {@link
     * #getInstance()} is one process-wide singleton and PluginManager's own module loading,
     * while sequential today, should not silently corrupt ownership if that ever changes.
     */
    private final ThreadLocal<String> currentOwner = new ThreadLocal<>();

    /**
     * The module instance attributed to registrations in the current scope, if the scope names one
     * (#506). Two copies of one module share a name, so only the instance tells a superseded copy's
     * completers from its replacement's; {@link #unregisterByOwnerInstance(UltiToolsPlugin)}
     * releases by it.
     */
    private final ThreadLocal<UltiToolsPlugin> currentOwnerInstance = new ThreadLocal<>();

    /**
     * Private constructor - use getInstance().
     */
    private TabCompletionManager() {
        registerBuiltInCompleters();
    }
    
    /**
     * Gets the singleton instance.
     *
     * @return the tab completion manager instance
     */
    public static TabCompletionManager getInstance() {
        if (instance == null) {
            synchronized (TabCompletionManager.class) {
                if (instance == null) {
                    instance = new TabCompletionManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Registers all built-in completers.
     */
    private void registerBuiltInCompleters() {
        completers.put(PLAYERS, new Registration(new OnlinePlayersCompleter(), null, null));
        completers.put(WORLDS, new Registration(new WorldsCompleter(), null, null));
        completers.put(MATERIALS, new Registration(new MaterialsCompleter(), null, null));
        completers.put(BLOCKS, new Registration(MaterialsCompleter.blocksOnly(), null, null));
        completers.put(ITEMS, new Registration(MaterialsCompleter.itemsOnly(), null, null));
        completers.put(BOOLEAN, new Registration(StaticSuggestionsCompleter.forBoolean(), null, null));
        completers.put(TOGGLE, new Registration(StaticSuggestionsCompleter.forToggle(), null, null));
    }
    
    /**
     * Registers a custom completer with a key.
     *
     * @param key       the key to register with (e.g., "@custom")
     * @param completer the completer to register
     */
    public void register(String key, TabCompleter completer) {
        if (key == null || completer == null) {
            throw new IllegalArgumentException("Key and completer must not be null");
        }
        completers.put(key, new Registration(completer, currentOwner.get(), currentOwnerInstance.get()));
    }

    /**
     * Unregisters a completer.
     *
     * @param key the key to unregister
     */
    public void unregister(String key) {
        completers.remove(key);
    }

    /**
     * Marks the beginning of a registration scope attributed to {@code owner} -- every completer
     * key registered via {@link #register(String, TabCompleter)} while this scope is active is
     * recorded as owned by {@code owner}, so {@link #unregisterByOwner(String)} can sweep it
     * later without requiring caller code to pass an owner explicitly (05-06 / D-08's "explicit
     * owner parameter on an internal registration path" -- the parameter is ambient rather than a
     * new argument on the published {@link #register(String, TabCompleter)} signature, which
     * stays frozen).
     * <p>
     * <b>ClassLoader-derived ownership was considered and rejected for this codebase.</b> Every
     * internal {@code UltiToolsPlugin} module is loaded through ONE shared {@code
     * URLClassLoader} covering the whole {@code plugins/} folder (see {@code
     * PluginManager.init(ClassLoader)}) -- the identical structural finding already recorded at
     * {@code PluginManager.validateAdditionalEntity}'s javadoc for D-19 -- so a completer
     * instance's {@code getClass().getClassLoader()} cannot distinguish between two different
     * modules here. An explicit scope is the only mechanism reachable from a module's only
     * registration entry point (the public {@link #register(String, TabCompleter)}) that still
     * correctly separates two modules' completers.
     * <p>
     * Not part of the frozen 6.2.5 singleton shape ({@link #getInstance()}, {@link
     * #register(String, TabCompleter)}, {@link #unregister(String)}) -- new in 6.3.0, intended
     * for {@code PluginManager}'s own module load/unload sequence, not for module authors.
     * <p>
     * Scopes do not nest: a second {@code beginRegistrationScope} call while one is already
     * active simply replaces the current owner. {@code UltiTools}' own module loading is
     * sequential -- one module's container is fully assembled (including its {@code
     * pluginContext.refresh()}, where {@code @PostConstruct} runs) before the next module's load
     * begins -- so nesting has never been required; this is a stated assumption, not an enforced
     * invariant.
     *
     * @param owner the identifier (the framework uses the plugin's name) to attribute subsequent
     *              registrations to
     * @since 6.3.0
     */
    public void beginRegistrationScope(String owner) {
        beginRegistrationScope(owner, null);
    }

    /**
     * Same as {@link #beginRegistrationScope(String)}, and additionally attributes every key
     * registered in the scope to {@code ownerInstance}, so {@link
     * #unregisterByOwnerInstance(UltiToolsPlugin)} can release exactly that module instance's
     * completers (#506). {@code PluginManager} opens this form for every module it loads: when a
     * newer copy of a module replaces an older one, both copies share a name, and only the
     * instance tells their completers apart.
     *
     * @param owner         the name to attribute subsequent registrations to, as in {@link
     *                      #beginRegistrationScope(String)}
     * @param ownerInstance the module instance to attribute them to; {@code null} records none,
     *                      which is exactly {@link #beginRegistrationScope(String)}
     * @since 6.3.0
     */
    public void beginRegistrationScope(String owner, UltiToolsPlugin ownerInstance) {
        currentOwner.set(owner);
        if (ownerInstance != null) {
            currentOwnerInstance.set(ownerInstance);
        } else {
            currentOwnerInstance.remove();
        }
    }

    /**
     * Ends the current registration scope started by {@link #beginRegistrationScope(String)}.
     * Registrations made after this call (and before another scope begins) are unowned and never
     * swept by {@link #unregisterByOwner(String)} -- the same as every registration made before
     * this mechanism existed.
     *
     * @since 6.3.0
     */
    public void endRegistrationScope() {
        currentOwner.remove();
        currentOwnerInstance.remove();
    }

    /**
     * Bulk-unregisters every completer key currently attributed to {@code owner} by an earlier
     * {@link #beginRegistrationScope(String)}/{@link #endRegistrationScope()} window. Each key is
     * removed only while it still holds the registration the sweep matched, so a key registered
     * again meanwhile keeps its new completer (Codex review of #564, round 4). A key registered
     * outside any scope (core built-ins, or any caller that never calls {@link
     * #beginRegistrationScope(String)}) has no recorded owner and is never matched here, regardless
     * of {@code owner}'s value.
     *
     * @param owner the owner identifier to sweep; {@code null} matches nothing and is a no-op
     * @return the number of keys unregistered
     * @since 6.3.0
     */
    public int unregisterByOwner(String owner) {
        if (owner == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, Registration> entry : completers.entrySet()) {
            Registration registration = entry.getValue();
            if (owner.equals(registration.owner) && completers.remove(entry.getKey(), registration)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Unregisters every completer key registered in a scope that named {@code ownerInstance}
     * (#506), whatever name the scope carried. A key another module or another copy of the same
     * module registered afterwards belongs to that registrant and is not touched, and a key
     * registered without an instance is never matched here -- {@link #unregisterByOwner(String)}
     * still releases those by name.
     *
     * @param ownerInstance the module instance being unloaded; {@code null} matches nothing
     * @return the number of keys unregistered
     * @since 6.3.0
     */
    public int unregisterByOwnerInstance(UltiToolsPlugin ownerInstance) {
        if (ownerInstance == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, Registration> entry : completers.entrySet()) {
            Registration registration = entry.getValue();
            // remove(key, value) is atomic and compares the whole registration by identity: a key
            // registered again in the meantime holds a new registration and is left alone.
            if (registration.ownerInstance == ownerInstance && completers.remove(entry.getKey(), registration)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Releases every completer key registered in a scope that named {@code ownerInstance} and
     * returns the action that gives them back (#562). {@code PluginManager} calls this for a loaded
     * copy of a module just before a newer copy of the same module builds its container and runs
     * {@code registerSelf()}, so the newer copy registers its keys into a registry that holds none
     * of the older copy's. When the newer copy fails to load, the framework first releases what the
     * failed copy registered and then runs the returned action, which puts each released completer
     * back under its key, unchanged; when the newer copy loads, the action is dropped and the older
     * copy is unloaded. Running the action more than once restores nothing more.
     * <p>
     * As in the owner sweeps, a key is removed only while it still holds the registration this call
     * matched, and put back only while it is free: a key registered again in between keeps its new
     * completer, and one WARNING names the key -- a copy never takes over another copy's key.
     * Intended for {@code PluginManager}, not for module authors.
     *
     * @param ownerInstance the loaded copy being superseded; {@code null} releases nothing
     * @return the action that restores what this call released
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Runnable releaseForSupersede(UltiToolsPlugin ownerInstance) {
        Map<String, Registration> released = new LinkedHashMap<>();
        if (ownerInstance != null) {
            for (Map.Entry<String, Registration> entry : completers.entrySet()) {
                Registration registration = entry.getValue();
                if (registration.ownerInstance == ownerInstance && completers.remove(entry.getKey(), registration)) {
                    released.put(entry.getKey(), registration);
                }
            }
        }
        AtomicBoolean pending = new AtomicBoolean(true);
        return () -> {
            if (pending.compareAndSet(true, false)) {
                restore(released);
            }
        };
    }

    private void restore(Map<String, Registration> released) {
        for (Map.Entry<String, Registration> entry : released.entrySet()) {
            Registration existing = completers.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && existing != entry.getValue()) {
                LOGGER.warning(String.format("Tab-completion key '%s' was registered again while module '%s' was being"
                        + " replaced; the new completer stays.", entry.getKey(), entry.getValue().owner));
            }
        }
    }

    /**
     * Gets a registered completer by key.
     *
     * @param key the completer key
     * @return the completer or null if not found
     */
    public TabCompleter getCompleter(String key) {
        return completerFor(key);
    }

    private TabCompleter completerFor(String key) {
        Registration registration = completers.get(key);
        return registration == null ? null : registration.completer;
    }
    
    /**
     * Generates suggestions using the appropriate completer.
     *
     * @param player  the player requesting completion
     * @param command the command
     * @param args    the current arguments
     * @return list of suggestions
     */
    public List<String> suggest(Player player, Command command, String[] args) {
        TabCompletionContext context = TabCompletionContext.of(player, command, args);
        return suggest(context);
    }
    
    /**
     * Generates suggestions using the given context.
     *
     * @param context the completion context
     * @return list of suggestions
     */
    public List<String> suggest(TabCompletionContext context) {
        if (context == null) {
            return Collections.emptyList();
        }
        
        // Check for built-in completer by parameter name
        String paramName = context.getParameterName();
        if (paramName != null && paramName.startsWith("@")) {
            TabCompleter completer = completerFor(paramName);
            if (completer != null) {
                return completer.complete(context);
            }
        }
        
        // Try method invocation completer
        return methodCompleter.complete(context);
    }

    /**
     * Resolves suggestions using an explicitly-resolved {@code @CmdParam.suggest()} value,
     * rather than {@code context}'s {@link TabCompletionContext#parameterName} -- which
     * carries {@code @CmdParam.value()}, the parameter's DISPLAY NAME, not its suggestion
     * (05-06 / D-07 Pitfall 2, T-05-28). {@link #suggest(TabCompletionContext)} above is left
     * untouched for existing callers; this overload is the dual-notation entry point a caller
     * that has already resolved the real {@code suggest()} value (see {@link
     * #resolveSuggestValue(Method, String)}) should use instead.
     * <p>
     * A {@code resolvedSuggest} beginning with {@code @} resolves as a built-in or registered
     * completer key. Any other value -- including {@code null} or empty -- falls through to the
     * existing method-invocation completer (and its i18n hint-text fallback), unchanged.
     *
     * @param context         the completion context
     * @param resolvedSuggest the already-resolved {@code @CmdParam.suggest()} string for the
     *                        parameter being completed; may be {@code null} or empty
     * @return the suggestions; never null
     * @since 6.3.0
     */
    public List<String> suggest(TabCompletionContext context, String resolvedSuggest) {
        if (context == null) {
            return Collections.emptyList();
        }

        if (resolvedSuggest != null && resolvedSuggest.startsWith("@")) {
            TabCompleter completer = completerFor(resolvedSuggest);
            if (completer != null) {
                return completer.complete(context);
            }
            return Collections.emptyList();
        }

        return methodCompleter.complete(context);
    }

    /**
     * Resolves {@code matchedMethod}'s {@code @CmdParam.suggest()} value for the parameter whose
     * {@code @CmdParam.value()} equals {@code parameterName} -- i.e. it keys the lookup off the
     * parameter's DISPLAY NAME to find the right parameter, then returns that parameter's
     * suggestion, never the display name itself (05-06 / D-07). This mirrors {@code
     * MethodInvocationCompleter.getSuggestName}'s existing lookup shape so the two stay
     * consistent, without depending on that private method directly.
     *
     * @param matchedMethod the matched {@code @CmdMapping} method, or {@code null}
     * @param parameterName the parameter's display name ({@code @CmdParam.value()}) to look up
     * @return the resolved {@code suggest()} value, or {@code null} if {@code matchedMethod} is
     *         {@code null} or no parameter's {@code value()} matches {@code parameterName}
     * @since 6.3.0
     */
    public static String resolveSuggestValue(Method matchedMethod, String parameterName) {
        if (matchedMethod == null || parameterName == null) {
            return null;
        }
        for (Parameter parameter : matchedMethod.getParameters()) {
            CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
            if (cmdParam != null && parameterName.equals(cmdParam.value())) {
                return cmdParam.suggest();
            }
        }
        return null;
    }

    /**
     * Suggests completions for a specific parameter type.
     *
     * @param context   the completion context
     * @param completerKey the key of the completer to use
     * @return list of suggestions
     */
    public List<String> suggestWith(TabCompletionContext context, String completerKey) {
        TabCompleter completer = completerFor(completerKey);
        if (completer != null) {
            return completer.complete(context);
        }
        return Collections.emptyList();
    }
    
    /**
     * Creates a context for tab completion with common defaults.
     *
     * @param player  the player
     * @param command the command
     * @param args    the arguments
     * @return a tab completion context
     */
    public TabCompletionContext createContext(
            Player player, Command command, String[] args) {
        int currentIndex = args.length > 0 ? args.length - 1 : 0;
        String partial = args.length > 0 ? args[args.length - 1] : "";
        
        return TabCompletionContext.builder()
                .player(player)
                .command(command)
                .args(args)
                .currentArgIndex(currentIndex)
                .partialArg(partial)
                .build();
    }
    
    /**
     * Helper method to filter suggestions by current input.
     *
     * @param suggestions the suggestions to filter
     * @param input       the current input
     * @return filtered suggestions
     */
    public static List<String> filterByInput(List<String> suggestions, String input) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }
        
        if (input == null || input.isEmpty()) {
            return new ArrayList<>(suggestions);
        }
        
        String lowerInput = input.toLowerCase();
        List<String> filtered = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(lowerInput)) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }
    
    /**
     * Suggests command format first arguments from mappings.
     *
     * @param mappings the command mappings (format -> method)
     * @param context  the completion context
     * @return list of first argument suggestions
     */
    public List<String> suggestFirstArgs(Map<String, Method> mappings, TabCompletionContext context) {
        if (mappings == null || mappings.isEmpty()) {
            return Collections.emptyList();
        }
        
        String input = context.getCurrentInput().toLowerCase();
        List<String> suggestions = new ArrayList<>();
        
        for (String format : mappings.keySet()) {
            String[] parts = format.split(" ");
            if (parts.length > 0) {
                String firstArg = parts[0];
                // Skip parameter placeholders
                if (!firstArg.startsWith("<") && !firstArg.endsWith(">")) {
                    if (firstArg.toLowerCase().startsWith(input)) {
                        if (!suggestions.contains(firstArg)) {
                            suggestions.add(firstArg);
                        }
                    }
                }
            }
        }
        
        Collections.sort(suggestions);
        return suggestions;
    }

    /**
     * One key's registration: the completer and the owner it was attributed to, replaced and
     * removed as a unit. Compared by identity, so a sweep's conditional removal never matches a
     * newer registration of the same key.
     */
    private static final class Registration {
        private final TabCompleter completer;
        private final String owner;
        private final UltiToolsPlugin ownerInstance;

        private Registration(TabCompleter completer, String owner, UltiToolsPlugin ownerInstance) {
            this.completer = completer;
            this.owner = owner;
            this.ownerInstance = ownerInstance;
        }
    }
}
