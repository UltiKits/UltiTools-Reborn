package com.ultikits.ultitools.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.PluginDependency;
import com.ultikits.ultitools.utils.PluginYmlReader;
import org.jetbrains.annotations.ApiStatus;

/**
 * Plugin dependency resolver using Kahn's algorithm for topological sorting.
 * Ensures plugins are loaded in the correct order based on their dependencies.
 * <p>
 * A failed resolve no longer refuses everything (D-10): {@link #resolve(List)} throws
 * {@link CircularDependencyException} or {@link MissingDependencyException}, and both carry a
 * structured sortable prefix and a refused set alongside the message, so a caller can load the
 * unaffected modules instead of falling back to filesystem order.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@ApiStatus.Internal
public class PluginDependencyResolver {

    private final Logger logger;

    /**
     * Constructs a new PluginDependencyResolver.
     *
     * @param logger the logger used to warn about a duplicate {@code plugin.yml} {@code name:}
     *               collision (#361 / IN-02) - a pick still has to be made, so this is the
     *               operator's only signal that one was
     */
    public PluginDependencyResolver(Logger logger) {
        this.logger = logger;
    }

    /**
     * Represents a dependency graph node containing plugin class and its dependencies.
     */
    public static class PluginNode {
        private final Class<? extends UltiToolsPlugin> pluginClass;
        private final String pluginName;
        private final Set<String> hardDependencies;
        private final Set<String> softDependencies;
        private final Set<String> loadBefore;
        private final Set<String> loadAfter;
        private final String pluginYmlName;

        public PluginNode(Class<? extends UltiToolsPlugin> pluginClass) {
            this.pluginClass = pluginClass;
            this.pluginName = pluginClass.getSimpleName();
            // LinkedHashSet, not HashSet (#361 / WR-05): several missing hard dependencies are
            // now joined into one message (see buildMissingDependencyMessage below), and the
            // operator-facing order should match declaration order in @PluginDependency's
            // depends() array rather than an arbitrary hash-bucket order.
            this.hardDependencies = new LinkedHashSet<>();
            this.softDependencies = new HashSet<>();
            this.loadBefore = new HashSet<>();

            // Extract dependencies from annotation
            if (pluginClass.isAnnotationPresent(PluginDependency.class)) {
                PluginDependency dep = pluginClass.getAnnotation(PluginDependency.class);
                Collections.addAll(hardDependencies, dep.depends());
                Collections.addAll(softDependencies, dep.softDepends());
                Collections.addAll(loadBefore, dep.loadBefore());
            }

            // D-12: plugin.yml's loadAfter is a second, complementary ordering mechanism -
            // @PluginDependency has no loadAfter attribute and never will (D-11), and plugin.yml
            // has nothing else. Both this and the plugin.yml-declared name: (used to build the
            // alias map in resolve()) are read from the class's own JAR, before any instance
            // exists.
            PluginYmlReader.PluginYmlInfo ymlInfo = PluginYmlReader.read(pluginClass);
            this.loadAfter = new HashSet<>(ymlInfo.getLoadAfter());
            this.pluginYmlName = ymlInfo.getName();
        }

        public Class<? extends UltiToolsPlugin> getPluginClass() {
            return pluginClass;
        }

        public String getPluginName() {
            return pluginName;
        }

        public Set<String> getHardDependencies() {
            return hardDependencies;
        }

        public Set<String> getSoftDependencies() {
            return softDependencies;
        }

        public Set<String> getLoadBefore() {
            return loadBefore;
        }

        /**
         * The plugins this one should load after, as declared in its own {@code plugin.yml}
         * {@code loadAfter:} list. An entry naming a module that is not installed is inert
         * (Paper parity) - only {@code depends} treats an absent target as an error.
         *
         * @return the declared loadAfter targets, by raw declared name (not yet alias-resolved)
         * @since 6.3.0
         */
        public Set<String> getLoadAfter() {
            return loadAfter;
        }

        /**
         * This module's {@code plugin.yml} {@code name:} value, or {@code null} if its code
         * source has no readable {@code plugin.yml}. Used to build the alias map in
         * {@link #resolve(List)} so a dependency entry can name a module by either its simple
         * class name or its {@code plugin.yml} name.
         *
         * @return the declared plugin.yml name, or null
         * @since 6.3.0
         */
        public String getPluginYmlName() {
            return pluginYmlName;
        }

        /**
         * Gets all dependencies (hard + soft) that exist in the available plugins.
         *
         * @deprecated use {@link #getAllDependencies(Set, Map)}, which resolves each entry
         *             through the plugin.yml-name alias map (D-12) before checking availability
         * @removeIn 6.4.0
         */
        @Deprecated(since = "6.3.0", forRemoval = true)
        public Set<String> getAllDependencies(Set<String> availablePlugins) {
            return getAllDependencies(availablePlugins, Collections.emptyMap());
        }

        /**
         * Gets all dependencies (hard + soft) that exist in the available plugins, resolving each
         * soft-dependency entry through {@code aliasMap} before checking availability so it may
         * name either a simple class name or a {@code plugin.yml} name (D-12).
         *
         * @param availablePlugins the plugin names present in this resolve
         * @param aliasMap         maps a declared name (simple class name or plugin.yml name) to
         *                         the canonical node name (always a simple class name)
         * @return the combined hard + resolvable-soft dependency set, by raw declared name
         * @since 6.3.0
         */
        public Set<String> getAllDependencies(Set<String> availablePlugins, Map<String, String> aliasMap) {
            Set<String> allDeps = new HashSet<>(hardDependencies);
            for (String softDep : softDependencies) {
                String resolved = aliasMap.getOrDefault(softDep, softDep);
                if (availablePlugins.contains(resolved)) {
                    allDeps.add(softDep);
                }
            }
            return allDeps;
        }
    }

    /**
     * Resolves plugin load order using Kahn's algorithm for topological sorting.
     *
     * @param pluginClasses the list of plugin classes to sort
     * @return sorted list of plugin classes
     * @throws CircularDependencyException if a dependency cycle is detected; the exception itself
     *                                      carries the sortable prefix and the refused set
     * @throws MissingDependencyException  if a required hard dependency is missing; the exception
     *                                      itself carries the sortable prefix and the refused set
     */
    public List<Class<? extends UltiToolsPlugin>> resolve(
            List<Class<? extends UltiToolsPlugin>> pluginClasses)
            throws CircularDependencyException, MissingDependencyException {

        if (pluginClasses == null || pluginClasses.isEmpty()) {
            return new ArrayList<>();
        }

        // Build plugin nodes
        Map<String, PluginNode> nodes = new LinkedHashMap<>();
        Set<String> availablePlugins = new HashSet<>();

        for (Class<? extends UltiToolsPlugin> pluginClass : pluginClasses) {
            PluginNode node = new PluginNode(pluginClass);
            nodes.put(node.getPluginName(), node);
            availablePlugins.add(node.getPluginName());
        }

        // D-12: build the alias map before touching the adjacency list, so every depends/
        // softDepends/loadBefore/loadAfter entry resolves against both a node's simple class name
        // and its plugin.yml name: - the two naming conventions this framework already mixes.
        Map<String, String> aliasMap = buildAliasMap(nodes);

        // Build the adjacency list once, over the full node set. A missing hard dependency simply
        // produces no edge (its target is not a node), so this can run before the missing-dependency
        // check below and that check can then use the same edges to find every transitive dependent.
        Map<String, Set<String>> adjacencyList = buildAdjacencyList(nodes, availablePlugins, aliasMap);

        // Missing hard dependencies: collect-all instead of throw-on-first (D-10), and every
        // missing dependency PER NODE is collected too, not just the first (#361 / WR-05). A
        // node whose hard dependency does not exist as a node is unloadable; everything that
        // transitively depends on it (forward along the same edges Kahn's sort would use) is
        // unloadable with it.
        Map<String, List<String>> missingDependenciesByNode =
            collectMissingHardDependencies(nodes, availablePlugins, aliasMap);
        if (!missingDependenciesByNode.isEmpty()) {
            Set<String> refused = expandForward(missingDependenciesByNode.keySet(), adjacencyList);
            Set<String> survivorNames = new LinkedHashSet<>(nodes.keySet());
            survivorNames.removeAll(refused);

            Map<String, Set<String>> survivorAdjacency = restrictAdjacency(adjacencyList, survivorNames);
            Map<String, Integer> survivorInDegree = calculateInDegrees(survivorNames, survivorAdjacency);
            List<String> survivorSorted = kahnSort(survivorNames, survivorAdjacency, survivorInDegree);

            throw new MissingDependencyException(
                buildMissingDependencyMessage(missingDependenciesByNode),
                toClassList(survivorSorted, nodes),
                refused
            );
        }

        // Kahn's algorithm over the full graph.
        Map<String, Integer> inDegree = calculateInDegrees(nodes.keySet(), adjacencyList);
        List<String> sortedNames = kahnSort(nodes.keySet(), adjacencyList, inDegree);

        // Check for circular dependency
        if (sortedNames.size() != nodes.size()) {
            Set<String> remaining = new HashSet<>(nodes.keySet());
            remaining.removeAll(sortedNames);

            List<Class<? extends UltiToolsPlugin>> sortedPrefix = toClassList(sortedNames, nodes);
            List<List<String>> cyclePaths = findCyclePaths(remaining, adjacencyList);

            throw new CircularDependencyException(
                "Circular dependency detected among plugins: " + remaining,
                sortedPrefix,
                remaining,
                cyclePaths
            );
        }

        return toClassList(sortedNames, nodes);
    }

    /**
     * Converts a sorted list of plugin names back to their plugin classes.
     */
    private List<Class<? extends UltiToolsPlugin>> toClassList(List<String> names, Map<String, PluginNode> nodes) {
        List<Class<? extends UltiToolsPlugin>> result = new ArrayList<>();
        for (String name : names) {
            result.add(nodes.get(name).getPluginClass());
        }
        return result;
    }

    /**
     * Builds the alias map every dependency-graph edge resolves through (D-12): each node's own
     * simple class name maps to itself first (guaranteed unique, so it always takes priority),
     * then each node's {@code plugin.yml} {@code name:} - when present and not already claimed by
     * some other node's simple class name - also maps to that node's canonical (simple class)
     * name. A dependency entry that does not appear in this map at all resolves to itself
     * unchanged, exactly as it did before this map existed.
     */
    private Map<String, String> buildAliasMap(Map<String, PluginNode> nodes) {
        Map<String, String> aliasMap = new HashMap<>();
        for (String simpleName : nodes.keySet()) {
            aliasMap.put(simpleName, simpleName);
        }
        warnOnDuplicatePluginYmlNames(nodes);
        for (PluginNode node : nodes.values()) {
            String ymlName = node.getPluginYmlName();
            if (ymlName != null && !ymlName.isEmpty() && !aliasMap.containsKey(ymlName)) {
                aliasMap.put(ymlName, node.getPluginName());
            }
        }
        return aliasMap;
    }

    /**
     * Detects two or more nodes declaring the same {@code plugin.yml} {@code name:} and logs one
     * WARNING per colliding name, naming every module that declared it (#361).
     * <p>
     * This does not change which module wins the alias - that is still whichever comes first in
     * {@code nodes} iteration order (discovery order), exactly as before. It only makes the
     * operator aware that a pick happened, instead of the dependency graph silently depending on
     * filesystem directory-listing order with nothing logged.
     * <p>
     * <b>IN-01 (gate-1 review, 16-REVIEW-residue.md):</b> "discovery order" here is deterministic
     * GIVEN a deterministic {@code nodes} iteration order, but this class's only production
     * caller, {@code PluginManager.init(ClassLoader)}, builds its input list from a raw
     * {@code File.listFiles()} call with no sort - measured non-alphabetical and
     * non-creation-order on this environment's filesystem (see #476). This WARNING's named
     * "winner" is therefore only as reproducible as that upstream order; not a regression this
     * change introduces (resolution already picked "whichever came first" before this fix), and
     * not addressed here deliberately - sorting the upstream list would itself be a load-order
     * behaviour change on existing installs, requiring its own decision. Tracked as #476.
     */
    private void warnOnDuplicatePluginYmlNames(Map<String, PluginNode> nodes) {
        Map<String, List<String>> declaringModulesByYmlName = new LinkedHashMap<>();
        for (PluginNode node : nodes.values()) {
            String ymlName = node.getPluginYmlName();
            if (ymlName != null && !ymlName.isEmpty()) {
                declaringModulesByYmlName
                    .computeIfAbsent(ymlName, k -> new ArrayList<>())
                    .add(node.getPluginName());
            }
        }
        for (Map.Entry<String, List<String>> entry : declaringModulesByYmlName.entrySet()) {
            List<String> declaringModules = entry.getValue();
            if (declaringModules.size() > 1) {
                logger.warning(String.format(
                    "Multiple modules declare the same plugin.yml name '%s': %s. "
                        + "Dependency resolution will resolve this name to '%s' (the module "
                        + "discovered first); rename one module's plugin.yml name: to remove "
                        + "the ambiguity.",
                    entry.getKey(), declaringModules, declaringModules.get(0)));
            }
        }
    }

    private String resolveAlias(String rawName, Map<String, String> aliasMap) {
        return aliasMap.getOrDefault(rawName, rawName);
    }

    /**
     * Collects every node that declares at least one hard dependency not present among the
     * available plugins, mapped to EVERY such dependency it names, not just the first (#361 /
     * WR-05) - so a module missing three hard dependencies is told about all three in one
     * refusal instead of one per restart. Each declared dependency is resolved through
     * {@code aliasMap} before the availability check; the message itself still names the raw
     * declared value.
     */
    private Map<String, List<String>> collectMissingHardDependencies(
            Map<String, PluginNode> nodes, Set<String> availablePlugins, Map<String, String> aliasMap) {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (PluginNode node : nodes.values()) {
            List<String> missingForNode = new ArrayList<>();
            for (String hardDep : node.getHardDependencies()) {
                if (!availablePlugins.contains(resolveAlias(hardDep, aliasMap))) {
                    missingForNode.add(hardDep);
                }
            }
            if (!missingForNode.isEmpty()) {
                missing.put(node.getPluginName(), missingForNode);
            }
        }
        return missing;
    }

    /**
     * Builds the refusal message naming every declaring module and every missing dependency it
     * wanted (#361 / WR-05). A module with exactly one missing dependency keeps the exact
     * singular wording this framework already used ({@code "requires dependency 'X' which is
     * not available"}); a module with several gets the plural form joined into one clause
     * ({@code "requires dependencies 'X', 'Y' which are not available"}) so the single-element
     * case never reads as a degenerate list.
     */
    private String buildMissingDependencyMessage(Map<String, List<String>> missingDependenciesByNode) {
        StringBuilder message = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : missingDependenciesByNode.entrySet()) {
            if (message.length() > 0) {
                message.append("; ");
            }
            List<String> missingDeps = entry.getValue();
            String quotedDeps = missingDeps.stream()
                .map(dep -> "'" + dep + "'")
                .collect(Collectors.joining(", "));
            if (missingDeps.size() == 1) {
                message.append(String.format(
                    "Plugin '%s' requires dependency %s which is not available",
                    entry.getKey(), quotedDeps));
            } else {
                message.append(String.format(
                    "Plugin '%s' requires dependencies %s which are not available",
                    entry.getKey(), quotedDeps));
            }
        }
        return message.toString();
    }

    /**
     * Expands a seed set of node names forward along the adjacency edges (dependency -&gt;
     * dependent), returning the seeds plus every node transitively reachable from them. Used both
     * to compute the refused set for a missing hard dependency (this method) and, implicitly, by
     * Kahn's own partition for a cycle (a node depending on a cycle member never reaches
     * in-degree 0, so it is already excluded from {@code sortedNames} without needing this call).
     */
    private Set<String> expandForward(Set<String> seeds, Map<String, Set<String>> adjacencyList) {
        Set<String> visited = new LinkedHashSet<>(seeds);
        List<String> stack = new ArrayList<>(seeds);
        while (!stack.isEmpty()) {
            String current = stack.remove(stack.size() - 1);
            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptySet())) {
                if (visited.add(neighbor)) {
                    stack.add(neighbor);
                }
            }
        }
        return visited;
    }

    /**
     * Restricts an adjacency list to a subset of nodes, dropping any edge touching a node outside
     * the subset.
     */
    private Map<String, Set<String>> restrictAdjacency(Map<String, Set<String>> adjacencyList, Set<String> keep) {
        Map<String, Set<String>> restricted = new HashMap<>();
        for (String name : keep) {
            Set<String> neighbors = new HashSet<>();
            for (String neighbor : adjacencyList.getOrDefault(name, Collections.emptySet())) {
                if (keep.contains(neighbor)) {
                    neighbors.add(neighbor);
                }
            }
            restricted.put(name, neighbors);
        }
        return restricted;
    }

    /**
     * Finds every distinct cycle within the {@code remaining} sub-graph (the nodes Kahn's sort
     * could not place) via a depth-first search restricted to that sub-graph, recording the back
     * edge that closes each cycle. Each cycle is reported once, keyed by its member set, so
     * revisiting the same loop from a second entry point does not duplicate it.
     */
    private List<List<String>> findCyclePaths(Set<String> remaining, Map<String, Set<String>> adjacencyList) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new LinkedHashSet<>();
        Set<String> reportedKeys = new HashSet<>();
        List<String> path = new ArrayList<>();

        // Deterministic starting order.
        for (String start : new TreeSet<>(remaining)) {
            if (!visited.contains(start)) {
                findCyclesFrom(start, remaining, adjacencyList, visited, onStack, path, cycles, reportedKeys);
            }
        }
        return cycles;
    }

    private void findCyclesFrom(
            String node,
            Set<String> remaining,
            Map<String, Set<String>> adjacencyList,
            Set<String> visited,
            Set<String> onStack,
            List<String> path,
            List<List<String>> cycles,
            Set<String> reportedKeys) {

        visited.add(node);
        onStack.add(node);
        path.add(node);

        for (String neighbor : new TreeSet<>(adjacencyList.getOrDefault(node, Collections.emptySet()))) {
            if (!remaining.contains(neighbor)) {
                continue;
            }
            if (onStack.contains(neighbor)) {
                int idx = path.indexOf(neighbor);
                List<String> cyclePath = new ArrayList<>(path.subList(idx, path.size()));
                cyclePath.add(neighbor);
                String key = String.join(",", new TreeSet<>(cyclePath.subList(0, cyclePath.size() - 1)));
                if (reportedKeys.add(key)) {
                    cycles.add(cyclePath);
                }
            } else if (!visited.contains(neighbor)) {
                findCyclesFrom(neighbor, remaining, adjacencyList, visited, onStack, path, cycles, reportedKeys);
            }
        }

        path.remove(path.size() - 1);
        onStack.remove(node);
    }

    /**
     * Builds the adjacency list for the dependency graph.
     * An edge from A to B means B depends on A (so A must load before B).
     */
    private Map<String, Set<String>> buildAdjacencyList(
            Map<String, PluginNode> nodes, Set<String> availablePlugins, Map<String, String> aliasMap) {

        Map<String, Set<String>> adjacencyList = new HashMap<>();

        // Initialize empty sets
        for (String name : nodes.keySet()) {
            adjacencyList.put(name, new HashSet<>());
        }

        for (PluginNode node : nodes.values()) {
            String pluginName = node.getPluginName();

            // For each dependency, add edge: dependency -> this plugin. Each raw declared name is
            // resolved through the alias map first (D-12), so it may name either a simple class
            // name or a plugin.yml name.
            for (String dep : node.getAllDependencies(availablePlugins, aliasMap)) {
                String resolvedDep = resolveAlias(dep, aliasMap);
                if (adjacencyList.containsKey(resolvedDep)) {
                    adjacencyList.get(resolvedDep).add(pluginName);
                }
            }

            // For loadBefore, add edge: this plugin -> target.
            for (String target : node.getLoadBefore()) {
                String resolvedTarget = resolveAlias(target, aliasMap);
                if (availablePlugins.contains(resolvedTarget)) {
                    adjacencyList.get(pluginName).add(resolvedTarget);
                }
            }

            // For loadAfter (D-12, plugin.yml-only): this plugin loads after the named target, so
            // the edge direction matches depends: target -> this plugin. An entry naming a module
            // that is not installed is inert (Paper parity) - skip it silently rather than
            // treating it as an error the way an unresolved hard `depends` is.
            for (String after : node.getLoadAfter()) {
                String resolvedAfter = resolveAlias(after, aliasMap);
                if (availablePlugins.contains(resolvedAfter)) {
                    adjacencyList.get(resolvedAfter).add(pluginName);
                }
            }
        }

        return adjacencyList;
    }

    /**
     * Calculates in-degrees for a set of node names.
     */
    private Map<String, Integer> calculateInDegrees(
            Set<String> nodeNames,
            Map<String, Set<String>> adjacencyList) {

        Map<String, Integer> inDegree = new HashMap<>();

        // Initialize all in-degrees to 0
        for (String name : nodeNames) {
            inDegree.put(name, 0);
        }

        // Count incoming edges
        for (Set<String> neighbors : adjacencyList.values()) {
            for (String neighbor : neighbors) {
                if (inDegree.containsKey(neighbor)) {
                    inDegree.merge(neighbor, 1, Integer::sum);
                }
            }
        }

        return inDegree;
    }

    /**
     * Performs Kahn's algorithm for topological sorting.
     */
    private List<String> kahnSort(
            Set<String> nodes,
            Map<String, Set<String>> adjacencyList,
            Map<String, Integer> inDegree) {

        List<String> sorted = new ArrayList<>();

        // Queue of nodes with no incoming edges
        // Use PriorityQueue for deterministic ordering (alphabetical)
        Queue<String> queue = new PriorityQueue<>(nodes.size() > 0 ? nodes.size() : 1);

        // Add all nodes with in-degree 0
        for (String node : nodes) {
            if (inDegree.getOrDefault(node, 0) == 0) {
                queue.offer(node);
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);

            // For each neighbor, decrement in-degree
            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptySet())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        return sorted;
    }

    /**
     * Exception thrown when circular dependencies are detected.
     * <p>
     * Carries a structured partition alongside the message (D-10): {@link #getSortedPrefix()} is
     * every module Kahn's sort could still place, {@link #getRefusedPlugins()} is the cycle plus
     * everything transitively depending on it, and {@link #getCyclePaths()} names each detected
     * cycle as an edge path (first and last element equal), e.g. {@code A -> B -> C -> A}. The
     * caller obtains all three from these accessors, never by parsing {@link #getMessage()}.
     */
    public static class CircularDependencyException extends Exception {
        private final List<Class<? extends UltiToolsPlugin>> sortedPrefix;
        private final Set<String> refusedPlugins;
        private final List<List<String>> cyclePaths;

        public CircularDependencyException(String message) {
            this(message, Collections.emptyList(), Collections.emptySet(), Collections.emptyList());
        }

        /**
         * Constructs a circular-dependency refusal carrying the structured partition.
         *
         * @param message        the human-readable detection message
         * @param sortedPrefix   every plugin class Kahn's sort could still place, in load order
         * @param refusedPlugins the cycle members plus every plugin transitively depending on them,
         *                       by plugin name
         * @param cyclePaths     each detected cycle as an ordered list of plugin names whose first
         *                       and last entries are the same node
         * @since 6.3.0
         */
        public CircularDependencyException(
                String message,
                List<Class<? extends UltiToolsPlugin>> sortedPrefix,
                Set<String> refusedPlugins,
                List<List<String>> cyclePaths) {
            super(message);
            this.sortedPrefix = Collections.unmodifiableList(new ArrayList<>(sortedPrefix));
            this.refusedPlugins = Collections.unmodifiableSet(new LinkedHashSet<>(refusedPlugins));
            this.cyclePaths = Collections.unmodifiableList(new ArrayList<>(cyclePaths));
        }

        /**
         * The plugin classes Kahn's sort could still place, in load order. Never null.
         *
         * @return the sortable prefix
         * @since 6.3.0
         */
        public List<Class<? extends UltiToolsPlugin>> getSortedPrefix() {
            return sortedPrefix;
        }

        /**
         * The cycle members plus every plugin transitively depending on them, by plugin name.
         * Never null.
         *
         * @return the refused plugin names
         * @since 6.3.0
         */
        public Set<String> getRefusedPlugins() {
            return refusedPlugins;
        }

        /**
         * Each detected cycle as an ordered list of plugin names whose first and last entries are
         * the same node, e.g. {@code [A, B, C, A]}. Never null; one entry per distinct cycle.
         *
         * @return the detected cycle paths
         * @since 6.3.0
         */
        public List<List<String>> getCyclePaths() {
            return cyclePaths;
        }
    }

    /**
     * Exception thrown when required dependencies are missing.
     * <p>
     * Carries a structured partition alongside the message (D-10): {@link #getSortedPrefix()} is
     * every module that does not depend, directly or transitively, on the missing dependency, and
     * {@link #getRefusedPlugins()} is the declaring module(s) plus everything transitively
     * depending on them. The caller obtains both from these accessors, never by parsing
     * {@link #getMessage()}.
     */
    public static class MissingDependencyException extends Exception {
        private final List<Class<? extends UltiToolsPlugin>> sortedPrefix;
        private final Set<String> refusedPlugins;

        public MissingDependencyException(String message) {
            this(message, Collections.emptyList(), Collections.emptySet());
        }

        /**
         * Constructs a missing-hard-dependency refusal carrying the structured partition.
         *
         * @param message        the human-readable detection message, naming the declaring module(s)
         *                       and the dependency each wanted
         * @param sortedPrefix   every plugin class that survives, in load order
         * @param refusedPlugins the declaring module(s) plus every plugin transitively depending on
         *                       them, by plugin name
         * @since 6.3.0
         */
        public MissingDependencyException(
                String message,
                List<Class<? extends UltiToolsPlugin>> sortedPrefix,
                Set<String> refusedPlugins) {
            super(message);
            this.sortedPrefix = Collections.unmodifiableList(new ArrayList<>(sortedPrefix));
            this.refusedPlugins = Collections.unmodifiableSet(new LinkedHashSet<>(refusedPlugins));
        }

        /**
         * The plugin classes that survive - do not depend, directly or transitively, on a missing
         * hard dependency - in load order. Never null.
         *
         * @return the sortable prefix
         * @since 6.3.0
         */
        public List<Class<? extends UltiToolsPlugin>> getSortedPrefix() {
            return sortedPrefix;
        }

        /**
         * The declaring module(s) plus every plugin transitively depending on them, by plugin name.
         * Never null.
         *
         * @return the refused plugin names
         * @since 6.3.0
         */
        public Set<String> getRefusedPlugins() {
            return refusedPlugins;
        }
    }
}
