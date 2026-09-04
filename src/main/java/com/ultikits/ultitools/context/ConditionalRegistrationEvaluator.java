package com.ultikits.ultitools.context;

import java.io.File;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

/**
 * The single shared {@code @ConditionalOnConfig} registration decision (D-17).
 * <p>
 * {@link ComponentScanner#shouldRegister(Class)} is the only caller of this evaluator in
 * {@code src/main} -- recording the decision here therefore catches every path the annotation
 * is honoured on, without needing a second copy of the decision anywhere else.
 * <p>
 * <b>Reload drift reporting (issue #392, D-01).</b> {@code @ConditionalOnConfig} is evaluated
 * exactly once, during component scanning at plugin startup. {@code ul reload} re-reads the
 * config file but this framework deliberately does <b>not</b> rebuild the container or
 * re-register/unregister the component on reload -- see D-01 in the issue for the rejected
 * "unregister and re-register the module" alternative and why it was rejected (it would discard
 * the whole module's in-memory state, turning a config reload into a de-facto restart while
 * players are online). Instead, {@link #shouldRegister(Class, SimpleContainer)} records every
 * decision it makes, and {@link #reportDrift(UltiToolsPlugin)} -- called from
 * {@link UltiToolsPlugin#reloadSelf()} -- re-evaluates each recorded class and logs a
 * {@code Level.WARNING} for any class whose answer has changed, naming the class, the config
 * file, the key, the new direction, and that a restart is required. This turns a previously
 * silent no-op into a visible one without changing what reload actually does.
 * <p>
 * <b>Where the record lives, and why.</b> The record is a private static map keyed by the
 * owning {@link UltiToolsPlugin} <i>instance</i> (identity, not equality -- a plugin has no
 * value-based {@code equals()}), because: (a) the decision is inherently per-plugin, since the
 * same class annotated on two different plugins would resolve against two different resource
 * folders; (b) it is released by an explicit {@link #clear(UltiToolsPlugin)} call from
 * {@code PluginManager.unregister}, the same place this codebase already releases the other
 * module-scoped registries that would otherwise pin the module's ClassLoader after unload
 * ({@code TabCompletionManager}, {@code EventBus}, {@code PanelResponderRegistry}) -- the record
 * holds {@code Class<?>} objects, so leaving it behind would pin the loader exactly the same
 * way; (c) it is safe for the framework's own core context, which has no {@code UltiToolsPlugin}
 * bean at all, because evaluation exits down the existing fail-open branch before anything is
 * recorded, and a class carrying no {@code @ConditionalOnConfig} returns {@code true} before
 * that. Rejected alternative: storing the record as a field on {@code UltiToolsPlugin} would
 * make garbage collection automatic (no {@code clear()} call needed), but it would widen a
 * public abstract class's API surface for an {@link ApiStatus.Internal} bookkeeping concern that
 * has nothing to do with what a module author subclasses {@code UltiToolsPlugin} for.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConditionalRegistrationEvaluator {

    private static final Logger LOGGER =
            Logger.getLogger(ConditionalRegistrationEvaluator.class.getName());

    /**
     * Guards every read, write, and iteration of {@link #STARTUP_DECISIONS}.
     */
    private static final Object RECORD_LOCK = new Object();

    /**
     * Every scan-time {@code @ConditionalOnConfig} decision, keyed by owning plugin instance
     * (identity) then by the evaluated class. The inner map is a {@link LinkedHashMap} so
     * {@link #reportDrift(UltiToolsPlugin)} reports drift in a deterministic order.
     */
    private static final Map<UltiToolsPlugin, Map<Class<?>, Boolean>> STARTUP_DECISIONS =
            new IdentityHashMap<>();

    private ConditionalRegistrationEvaluator() {
    }

    /**
     * Check if a class should be registered based on {@code @ConditionalOnConfig}.
     * <p>
     * The two "the decision cannot be determined" branches stay fail-open -- a component
     * that should have been enabled but was not is harder to diagnose than the reverse --
     * but each now emits a {@code Level.WARNING} record naming the evaluated class and the
     * reason, rather than silently registering by default (D-20). The config-file-missing
     * branch is unaffected: it returns {@code condition.negate()}, i.e. missing means
     * disabled, which already matches the annotation's own javadoc.
     * <p>
     * The decision made here -- including a {@code false} (skipped) decision, which never
     * appears in any container -- is recorded against the resolved plugin so a later
     * {@link #reportDrift(UltiToolsPlugin)} call can detect drift (issue #392, D-01).
     *
     * @param clazz     the class to check
     * @param container the container used to resolve the owning {@link UltiToolsPlugin}
     * @return true if the class should be registered
     */
    public static boolean shouldRegister(Class<?> clazz, SimpleContainer container) {
        ConditionalOnConfig condition = clazz.getAnnotation(ConditionalOnConfig.class);
        if (condition == null) {
            return true;
        }

        // Retrieve the plugin from the container
        UltiToolsPlugin plugin = null;
        try {
            plugin = container.getBean(UltiToolsPlugin.class);
        } catch (Exception e) {
            // No plugin in container — skip conditional (register by default), but say so:
            // a component that should have been enabled and was silently skipped is harder to
            // diagnose than the reverse (D-20). Nothing is recorded: there is no plugin to key
            // the record against, and this is the framework's own core context, which never
            // reloads through UltiToolsPlugin.reloadSelf() anyway.
            LOGGER.log(Level.WARNING, "@ConditionalOnConfig on " + clazz.getName()
                    + " could not be evaluated: no UltiToolsPlugin could be resolved from the "
                    + "container (" + e.getMessage() + "). Registering by default (fail-open).");
            return true;
        }

        if (plugin == null || plugin.getResourceFolderPath() == null) {
            LOGGER.log(Level.WARNING, "@ConditionalOnConfig on " + clazz.getName()
                    + " could not be evaluated: " + (plugin == null
                    ? "no UltiToolsPlugin instance was resolved from the container"
                    : "the plugin's resource folder path is null")
                    + ". Registering by default (fail-open).");
            return true;
        }

        boolean decision = evaluate(plugin, condition);
        record(plugin, clazz, decision);
        return decision;
    }

    /**
     * The decision logic extracted verbatim from the pre-6.3.0 {@code shouldRegister} body --
     * no existing decision semantics changed by this extraction. Kept separate from
     * {@link #shouldRegister(Class, SimpleContainer)} so {@link #reportDrift(UltiToolsPlugin)}
     * can re-run exactly the same logic against the current file contents.
     *
     * @param plugin    the resolved, non-null owning plugin
     * @param condition the class's {@code @ConditionalOnConfig} annotation
     * @return true if the condition currently evaluates to "register"
     */
    private static boolean evaluate(UltiToolsPlugin plugin, ConditionalOnConfig condition) {
        // Load the referenced config file from the plugin's config folder
        File configFile = new File(plugin.getResourceFolderPath(), condition.value());
        if (!configFile.exists()) {
            // Config file doesn't exist yet — feature disabled
            return condition.negate();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        boolean value = yaml.getBoolean(condition.path(), false);
        return condition.negate() ? !value : value;
    }

    private static void record(UltiToolsPlugin plugin, Class<?> clazz, boolean decision) {
        synchronized (RECORD_LOCK) {
            STARTUP_DECISIONS
                    .computeIfAbsent(plugin, unused -> new LinkedHashMap<>())
                    .put(clazz, decision);
        }
    }

    /**
     * Re-evaluate every {@code @ConditionalOnConfig} decision recorded for {@code plugin} and
     * log (and return) one {@code Level.WARNING} message for each class whose answer has
     * changed since it was recorded (issue #392, D-01).
     * <p>
     * Per D-01, this method only reports -- it never registers, unregisters, or rebuilds
     * anything. Re-evaluation runs outside {@link #RECORD_LOCK} (it does disk I/O via
     * {@link YamlConfiguration#loadConfiguration(File)}, which must never run while holding a
     * lock other reload/scan threads might contend on); the plugin's recorded map is snapshotted
     * inside the lock first. Each class is re-evaluated in its own {@code try}/{@code catch} so
     * one unreadable config file cannot abort the reload or the remaining classes.
     *
     * @param plugin the plugin being reloaded
     * @return an unmodifiable list of the drift messages emitted, in recording order; empty if
     *         there was no drift, if {@code plugin} is {@code null}, or if nothing is recorded
     *         for {@code plugin}
     */
    public static List<String> reportDrift(UltiToolsPlugin plugin) {
        if (plugin == null) {
            return Collections.emptyList();
        }

        Map<Class<?>, Boolean> snapshot;
        synchronized (RECORD_LOCK) {
            Map<Class<?>, Boolean> recorded = STARTUP_DECISIONS.get(plugin);
            if (recorded == null || recorded.isEmpty()) {
                return Collections.emptyList();
            }
            snapshot = new LinkedHashMap<>(recorded);
        }

        List<String> messages = new ArrayList<>();
        for (Map.Entry<Class<?>, Boolean> entry : snapshot.entrySet()) {
            Class<?> clazz = entry.getKey();
            boolean recordedDecision = entry.getValue();
            try {
                ConditionalOnConfig condition = clazz.getAnnotation(ConditionalOnConfig.class);
                if (condition == null) {
                    // Annotation removed since scan time (should not happen in practice, but the
                    // record must not crash a reload over it) — nothing to compare against.
                    continue;
                }
                boolean currentDecision = evaluate(plugin, condition);
                if (currentDecision == recordedDecision) {
                    continue;
                }
                String message = driftMessage(clazz, condition, currentDecision);
                LOGGER.log(Level.WARNING, message);
                messages.add(message);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "@ConditionalOnConfig drift check failed for "
                        + clazz.getName() + ": " + e.getMessage()
                        + ". Skipping this class for this reload.");
            }
        }
        return Collections.unmodifiableList(messages);
    }

    private static String driftMessage(Class<?> clazz, ConditionalOnConfig condition, boolean currentDecision) {
        // Report the registration decision, not the raw YAML boolean -- negate=true would
        // otherwise make the direction word ambiguous relative to what actually happened.
        String directionWord = currentDecision ? "enabled" : "disabled";
        String stateClause = currentDecision
                ? "but the component was not registered at startup"
                : "but the component is already registered";
        return "[UltiTools-API] @ConditionalOnConfig drift after reload: " + clazz.getName()
                + " (" + condition.value() + " -> " + condition.path() + ") now evaluates to "
                + directionWord + ", " + stateClause + ". @ConditionalOnConfig is evaluated once "
                + "at component scan; a restart is required for this change to take effect.";
    }

    /**
     * Release every decision recorded for {@code plugin}.
     * <p>
     * Called from {@code PluginManager.unregister} when a module unloads -- the record holds
     * {@code Class<?>} references, and would otherwise pin the module's ClassLoader after
     * unload, exactly like the {@code TabCompletionManager} / {@code EventBus} /
     * {@code PanelResponderRegistry} releases it sits alongside there.
     *
     * @param plugin the plugin whose recorded decisions should be released; {@code null} is a
     *               no-op, as is a plugin with nothing recorded
     */
    public static void clear(UltiToolsPlugin plugin) {
        if (plugin == null) {
            return;
        }
        synchronized (RECORD_LOCK) {
            STARTUP_DECISIONS.remove(plugin);
        }
    }
}
