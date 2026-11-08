package com.ultikits.ultitools.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * structured sortable prefix and a refused set alongside the message - disjoint, and together
 * covering every input module - so a caller can load the unaffected modules instead of falling
 * back to filesystem order.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@ApiStatus.Internal
public class PluginDependencyResolver {

    /**
     * Shared start of the cycle sentence, used by the pure-cycle message and appended to a
     * missing-dependency message when an independent cycle occurs in the same resolve.
     */
    private static final String CIRCULAR_DEPENDENCY_MESSAGE_PREFIX =
        "Circular dependency detected among plugins: ";

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

        /** Unique graph identity, not an additional dependency-name syntax. */
        private String getId() {
            return pluginClass.getName();
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
         * @param availablePlugins the plugin names present in this resolve, as the keys
         *                         {@code aliasMap} maps to; the resolver passes its internal node
         *                         keys (fully qualified class names)
         * @param aliasMap         maps a declared name (simple class name or plugin.yml name) to
         *                         the canonical node key (independent of its display name). An
         *                         entry missing from it is looked up in {@code availablePlugins}
         *                         verbatim, so with the resolver's own inputs a soft dependency
         *                         spelled as a fully qualified class name counts as available but
         *                         adds no ordering edge, because the resolver itself resolves only
         *                         declared names (#841)
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
     * <p>
     * Ordering uses every edge kind ({@code depends}, present {@code softDepends},
     * {@code loadBefore}, {@code plugin.yml} {@code loadAfter}). A failed resolve partitions the
     * input: the sortable prefix and the refused set are disjoint and together contain every
     * input module. Refusal for a missing hard dependency follows {@code depends} edges only;
     * anything the sort cannot place (a cycle and every module ordered after it) is refused as a
     * cycle. When both occur, {@link MissingDependencyException} is thrown and also carries the
     * cycle paths.
     *
     * @param pluginClasses the list of plugin classes to sort
     * @return sorted list of plugin classes
     * @throws CircularDependencyException if a dependency cycle is detected and no hard dependency
     *                                      is missing; the exception itself carries the sortable
     *                                      prefix, the refused set and the cycle paths
     * @throws MissingDependencyException  if a required hard dependency is missing; the exception
     *                                      itself carries the sortable prefix, the refused set and
     *                                      the paths of any independent cycle
     */
    public List<Class<? extends UltiToolsPlugin>> resolve(
            List<Class<? extends UltiToolsPlugin>> pluginClasses)
            throws CircularDependencyException, MissingDependencyException {

        if (pluginClasses == null || pluginClasses.isEmpty()) {
            return new ArrayList<>();
        }

        // #752: aliases are not identities. Keep every distinct main class in the one graph.
        Map<String, PluginNode> nodes = new LinkedHashMap<>();
        for (Class<? extends UltiToolsPlugin> pluginClass : pluginClasses) {
            PluginNode node = new PluginNode(pluginClass);
            nodes.put(node.getId(), node);
        }
        Set<String> availablePlugins = nodes.keySet();
        Map<String, String> displayNames = buildDisplayNames(nodes);
        Comparator<String> loadOrder = Comparator
                .comparing((String id) -> nodes.get(id).getPluginName())
                .thenComparing(Comparator.naturalOrder());

        // D-12: build the alias map before touching the adjacency list, so every depends/
        // softDepends/loadBefore/loadAfter entry resolves against both a node's simple class name
        // and its plugin.yml name: - the two naming conventions this framework already mixes.
        Map<String, String> aliasMap = buildAliasMap(nodes, displayNames);

        // Build the adjacency list once, over the full node set and every edge kind (depends,
        // present softDepends, loadBefore, plugin.yml loadAfter). It decides load ORDER only. A
        // missing hard dependency simply produces no edge (its target is not a node).
        Map<String, Set<String>> adjacencyList = buildAdjacencyList(nodes, availablePlugins, aliasMap);

        // Missing hard dependencies: collect-all instead of throw-on-first (D-10), and every
        // missing dependency PER NODE is collected too, not just the first (#361 / WR-05). A
        // node whose hard dependency does not exist as a node is unloadable; everything that
        // transitively DEPENDS on it (forward along depends edges only, #676) is unloadable with
        // it. A module that only softDepends on, is named by a loadBefore of, or lists in
        // plugin.yml loadAfter a refused module is not refused for that: those edges only order
        // modules that are present and never carry a missing-dependency refusal. The survivors
        // are then still sorted on the full adjacency, so every ordering constraint among them
        // holds (and a cycle among them is refused by the cycle rule, see below).
        Map<String, List<String>> missingDependenciesByNode =
            collectMissingHardDependencies(nodes, availablePlugins, aliasMap);
        if (!missingDependenciesByNode.isEmpty()) {
            Map<String, Set<String>> hardAdjacency = buildHardAdjacencyList(nodes, aliasMap);
            Set<String> refused = expandForward(missingDependenciesByNode.keySet(), hardAdjacency);
            Set<String> survivorNames = new LinkedHashSet<>(nodes.keySet());
            survivorNames.removeAll(refused);

            Map<String, Set<String>> survivorAdjacency = restrictAdjacency(adjacencyList, survivorNames);
            Map<String, Integer> survivorInDegree = calculateInDegrees(survivorNames, survivorAdjacency);
            List<String> survivorSorted = kahnSort(survivorNames, survivorAdjacency, survivorInDegree, loadOrder);

            // An independent cycle among the survivors (#676): every survivor the sort cannot
            // place is refused by the same rule the pure-cycle path below uses, and its cycles
            // are diagnosed, so the prefix and the refused set together cover every input.
            String message = buildMissingDependencyMessage(missingDependenciesByNode, displayNames);
            List<List<String>> cyclePaths = Collections.emptyList();
            if (survivorSorted.size() != survivorNames.size()) {
                Set<String> cycleBlocked = new TreeSet<>(survivorNames);
                cycleBlocked.removeAll(survivorSorted);
                cyclePaths = findCyclePaths(cycleBlocked, survivorAdjacency);
                refused.addAll(cycleBlocked);
                message = message + ". " + CIRCULAR_DEPENDENCY_MESSAGE_PREFIX
                        + displaySet(cycleBlocked, displayNames);
            }

            throw new MissingDependencyException(
                message,
                toClassList(survivorSorted, nodes),
                displaySet(refused, displayNames),
                displayCycles(cyclePaths, displayNames)
            );
        }

        // Kahn's algorithm over the full graph.
        Map<String, Integer> inDegree = calculateInDegrees(nodes.keySet(), adjacencyList);
        List<String> sortedNames = kahnSort(nodes.keySet(), adjacencyList, inDegree, loadOrder);

        // Check for circular dependency
        if (sortedNames.size() != nodes.size()) {
            Set<String> remaining = new HashSet<>(nodes.keySet());
            remaining.removeAll(sortedNames);

            List<Class<? extends UltiToolsPlugin>> sortedPrefix = toClassList(sortedNames, nodes);
            List<List<String>> cyclePaths = findCyclePaths(remaining, adjacencyList);

            throw new CircularDependencyException(
                CIRCULAR_DEPENDENCY_MESSAGE_PREFIX + displaySet(remaining, displayNames),
                sortedPrefix,
                displaySet(remaining, displayNames),
                displayCycles(cyclePaths, displayNames)
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
     * Derives aliases from the complete graph without losing nodes. Simple names keep their
     * historical last-discovered target and take priority over plugin.yml names. LinkedHashMap
     * replacement retains the first insertion position, matching the old graph's alias order.
     * Previously established plugin.yml aliases are filled first; recovered nodes only add names
     * that were unavailable before. This index chooses names, never which classes can load.
     */
    private Map<String, String> buildAliasMap(Map<String, PluginNode> nodes,
            Map<String, String> displayNames) {
        Map<String, String> simpleAliases = new LinkedHashMap<>();
        for (PluginNode node : nodes.values()) {
            simpleAliases.put(node.getPluginName(), node.getId());
        }
        Map<String, String> aliasMap = new HashMap<>(simpleAliases);
        Set<String> aliasOrder = new LinkedHashSet<>(simpleAliases.values());
        aliasOrder.addAll(nodes.keySet());
        for (String id : aliasOrder) {
            String ymlName = nodes.get(id).getPluginYmlName();
            if (ymlName != null && !ymlName.isEmpty()) {
                aliasMap.putIfAbsent(ymlName, id);
            }
        }
        warnOnDuplicatePluginYmlNames(nodes, aliasMap, displayNames);
        warnOnAmbiguousReferencedSimpleNames(nodes, aliasMap);
        return aliasMap;
    }

    /**
     * Logs one WARNING per main-class simple name that two or more modules share and that some
     * module actually names in {@code depends}, {@code softDepends}, {@code loadBefore} or a
     * plugin.yml {@code loadAfter} (#841). Such a name resolves to the last-discovered module
     * (historical behaviour, unchanged); the warning lists every fully qualified class and the
     * winner, as {@link #warnOnDuplicatePluginYmlNames} does for plugin.yml names. A shared simple
     * name that nothing references is not reported: it decides nothing.
     */
    private void warnOnAmbiguousReferencedSimpleNames(Map<String, PluginNode> nodes, Map<String, String> aliasMap) {
        Map<String, List<String>> classesBySimpleName = new LinkedHashMap<>();
        for (PluginNode node : nodes.values()) {
            classesBySimpleName.computeIfAbsent(node.getPluginName(), k -> new ArrayList<>()).add(node.getId());
        }
        Set<String> referenced = new HashSet<>();
        for (PluginNode node : nodes.values()) {
            referenced.addAll(node.getHardDependencies());
            referenced.addAll(node.getSoftDependencies());
            referenced.addAll(node.getLoadBefore());
            referenced.addAll(node.getLoadAfter());
        }
        for (Map.Entry<String, List<String>> entry : classesBySimpleName.entrySet()) {
            String simpleName = entry.getKey();
            if (entry.getValue().size() > 1 && referenced.contains(simpleName)) {
                logger.warning(String.format(
                    "[UltiTools-API] Multiple modules share the main class simple name '%s': %s. "
                        + "Dependency resolution will resolve this name to '%s' (the module discovered "
                        + "last); name the intended module by its plugin.yml name: instead to remove "
                        + "the ambiguity.",
                    simpleName, entry.getValue(), aliasMap.get(simpleName)));
            }
        }
    }

    /** Keep existing diagnostic names, qualifying only colliding simple names. */
    private Map<String, String> buildDisplayNames(Map<String, PluginNode> nodes) {
        Map<String, Integer> counts = new HashMap<>();
        for (PluginNode node : nodes.values()) {
            counts.merge(node.getPluginName(), 1, Integer::sum);
        }
        Map<String, String> displayNames = new HashMap<>();
        for (PluginNode node : nodes.values()) {
            displayNames.put(node.getId(), counts.get(node.getPluginName()) > 1
                    ? node.getId() : node.getPluginName());
        }
        return displayNames;
    }

    private Set<String> displaySet(Set<String> ids, Map<String, String> displayNames) {
        return ids.stream().map(displayNames::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<List<String>> displayCycles(List<List<String>> cycles, Map<String, String> displayNames) {
        return cycles.stream().map(path -> path.stream().map(displayNames::get)
                .collect(Collectors.toList())).collect(Collectors.toList());
    }

    /**
     * Detects two or more nodes declaring the same {@code plugin.yml} {@code name:} and logs one
     * WARNING per colliding name, naming every module that declared it (#361).
     * <p>
     * This does not refuse either module or change the alias selection. The existing winners
     * retain priority, and recovered nodes only supply previously unavailable aliases. The
     * warning makes the operator aware that a pick happened instead of silently choosing a target.
     * Ordinary, unique-simple-name inputs still use the first declarer in discovery order.
     * <p>
     * <b>IN-01 (gate-1 review, 16-REVIEW-residue.md):</b> "discovery order" here is deterministic
     * GIVEN a deterministic {@code nodes} iteration order. As of 6.3.0 (#476) this class's only
     * production caller, {@code PluginManager.init(ClassLoader)}, builds its input list from the
     * modules folder in file-name order ({@code ModuleFileTransactions#moduleJars}) -- before that
     * it was a raw {@code File.listFiles()} call, measured non-alphabetical and non-creation-order
     * on ext4 -- so this WARNING names the same "winner" for the same folder on every file
     * system.
     * <p>
     * The actual winner is read from the completed alias index, not inferred from graph keys:
     * simple-name aliases take priority (#478), and recovered same-simple-name nodes must not
     * steal an established plugin.yml alias (#752). Diagnostics and graph edges therefore use
     * the same authoritative alias selection.
     */
    private void warnOnDuplicatePluginYmlNames(Map<String, PluginNode> nodes,
            Map<String, String> aliasMap, Map<String, String> displayNames) {
        Map<String, List<String>> declaringModulesByYmlName = new LinkedHashMap<>();
        for (PluginNode node : nodes.values()) {
            String ymlName = node.getPluginYmlName();
            if (ymlName != null && !ymlName.isEmpty()) {
                declaringModulesByYmlName
                    .computeIfAbsent(ymlName, k -> new ArrayList<>())
                    .add(node.getId());
            }
        }
        for (Map.Entry<String, List<String>> entry : declaringModulesByYmlName.entrySet()) {
            List<String> declaringModules = entry.getValue();
            if (declaringModules.size() > 1) {
                String ymlName = entry.getKey();
                String winnerId = aliasMap.get(ymlName);
                boolean claimedBySimpleClassName = nodes.get(winnerId).getPluginName().equals(ymlName);
                String winner = displayNames.get(winnerId);
                String winnerReason = claimedBySimpleClassName
                    ? "a module class literally named '" + ymlName + "', which always claims "
                        + "this alias before any plugin.yml name: is considered"
                    : winnerId.equals(declaringModules.get(0)) ? "the module discovered first"
                        : "the established alias winner, before aliases from recovered modules";
                logger.warning(String.format(
                    "[UltiTools-API] Multiple modules declare the same plugin.yml name '%s': "
                        + "%s. Dependency resolution will resolve this name to '%s' (%s); rename "
                        + "one module's plugin.yml name: to remove the ambiguity.",
                    ymlName, declaringModules.stream().map(displayNames::get).collect(Collectors.toList()),
                    winner, winnerReason));
            }
        }
    }

    private String resolveAlias(String rawName, Map<String, String> aliasMap) {
        // Do not let an unrecognized raw name match a full-name internal key (#752).
        return aliasMap.get(rawName);
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
                missing.put(node.getId(), missingForNode);
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
    private String buildMissingDependencyMessage(Map<String, List<String>> missingDependenciesByNode,
            Map<String, String> displayNames) {
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
                    displayNames.get(entry.getKey()), quotedDeps));
            } else {
                message.append(String.format(
                    "Plugin '%s' requires dependencies %s which are not available",
                    displayNames.get(entry.getKey()), quotedDeps));
            }
        }
        return message.toString();
    }

    /**
     * Expands a seed set of node names forward along the given adjacency edges (dependency -&gt;
     * dependent), returning the seeds plus every node transitively reachable from them. Its only
     * caller passes the hard-only adjacency from {@link #buildHardAdjacencyList}, so the refused
     * set for a missing hard dependency follows {@code depends} edges alone (#676). A cycle does
     * not use this method: Kahn's own partition already leaves out every node it cannot place.
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
            String pluginName = node.getId();

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
     * Builds the hard-only adjacency list: an edge from A to B exactly when B declares
     * {@code depends} on A (resolved through {@code aliasMap}) and A is present. This is the only
     * graph a missing-dependency refusal propagates over (#676); {@code softDepends},
     * {@code loadBefore} and {@code plugin.yml} {@code loadAfter} only order modules that are
     * present and never carry a missing-dependency refusal. (A cycle is refused by Kahn's
     * partition over the full adjacency instead, which does follow every edge kind.)
     */
    private Map<String, Set<String>> buildHardAdjacencyList(
            Map<String, PluginNode> nodes, Map<String, String> aliasMap) {
        Map<String, Set<String>> hardAdjacency = new HashMap<>();
        for (String name : nodes.keySet()) {
            hardAdjacency.put(name, new HashSet<>());
        }
        for (PluginNode node : nodes.values()) {
            for (String dep : node.getHardDependencies()) {
                Set<String> dependents = hardAdjacency.get(resolveAlias(dep, aliasMap));
                if (dependents != null) {
                    dependents.add(node.getId());
                }
            }
        }
        return hardAdjacency;
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
            Map<String, Integer> inDegree,
            Comparator<String> loadOrder) {

        List<String> sorted = new ArrayList<>();

        // Queue of nodes with no incoming edges
        // Preserve historical simple-name order; only newly representable ties use full names.
        Queue<String> queue = new PriorityQueue<>(loadOrder);

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
     * every module Kahn's sort could still place, {@link #getRefusedPlugins()} is every module it
     * could not place - the cycle members plus every module ordered after them over any edge kind
     * ({@code depends}, present {@code softDepends}, {@code loadBefore}, {@code plugin.yml}
     * {@code loadAfter}) - and {@link #getCyclePaths()} names each detected
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
         * @param refusedPlugins every plugin the sort could not place - the cycle members plus every
         *                       plugin ordered after them - by plugin name
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
         * Every plugin the sort could not place - the cycle members plus every plugin ordered
         * after them over any edge kind - by plugin name. Never null.
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
     * Carries a structured partition alongside the message (D-10): {@link #getRefusedPlugins()} is
     * the declaring module(s) plus everything that transitively {@code depends} on them (#676:
     * {@code softDepends}, {@code loadBefore} and {@code plugin.yml} {@code loadAfter} never
     * carry this refusal), plus - when an independent cycle occurs in the same resolve - every
     * remaining module the sort cannot place, refused by the same rule as
     * {@link CircularDependencyException}. {@link #getSortedPrefix()} is every other module, in
     * load order; the two are disjoint and together cover every input module.
     * {@link #getCyclePaths()} names each such cycle and is empty when there is none. The caller
     * obtains all three from these accessors, never by parsing {@link #getMessage()}.
     * <p>
     * A missing dependency takes precedence: when both occur, this exception is thrown, not
     * {@link CircularDependencyException}.
     */
    public static class MissingDependencyException extends Exception {
        private final List<Class<? extends UltiToolsPlugin>> sortedPrefix;
        private final Set<String> refusedPlugins;
        private final List<List<String>> cyclePaths;

        public MissingDependencyException(String message) {
            this(message, Collections.emptyList(), Collections.emptySet(), Collections.emptyList());
        }

        /**
         * Constructs a missing-hard-dependency refusal carrying the structured partition and no
         * cycle paths.
         *
         * @param message        the human-readable detection message, naming the declaring module(s)
         *                       and the dependency each wanted
         * @param sortedPrefix   every plugin class that survives, in load order
         * @param refusedPlugins every refused plugin, by plugin name
         * @since 6.3.0
         */
        public MissingDependencyException(
                String message,
                List<Class<? extends UltiToolsPlugin>> sortedPrefix,
                Set<String> refusedPlugins) {
            this(message, sortedPrefix, refusedPlugins, Collections.emptyList());
        }

        /**
         * Constructs a missing-hard-dependency refusal that also carries the paths of independent
         * cycles found among the modules the missing dependency did not refuse.
         *
         * @param message        the human-readable detection message, naming the declaring module(s),
         *                       the dependency each wanted and, when cycles exist, the modules
         *                       they block
         * @param sortedPrefix   every plugin class that survives, in load order
         * @param refusedPlugins every refused plugin, by plugin name: the depends closure of the
         *                       declaring modules plus every module the sort could not place
         * @param cyclePaths     each detected cycle as an ordered list of plugin names whose first
         *                       and last entries are the same node; empty when there is none
         * @since 6.3.0
         */
        public MissingDependencyException(
                String message,
                List<Class<? extends UltiToolsPlugin>> sortedPrefix,
                Set<String> refusedPlugins,
                List<List<String>> cyclePaths) {
            super(message);
            this.sortedPrefix = Collections.unmodifiableList(new ArrayList<>(sortedPrefix));
            this.refusedPlugins = Collections.unmodifiableSet(new LinkedHashSet<>(refusedPlugins));
            List<List<String>> copiedPaths = new ArrayList<>();
            for (List<String> path : cyclePaths) {
                copiedPaths.add(Collections.unmodifiableList(new ArrayList<>(path)));
            }
            this.cyclePaths = Collections.unmodifiableList(copiedPaths);
        }

        /**
         * The plugin classes that survive, in load order: every module neither in the
         * {@code depends} closure of a module with a missing hard dependency nor blocked by a
         * cycle. Never null.
         *
         * @return the sortable prefix
         * @since 6.3.0
         */
        public List<Class<? extends UltiToolsPlugin>> getSortedPrefix() {
            return sortedPrefix;
        }

        /**
         * The declaring module(s), every plugin that transitively {@code depends} on them, and
         * every module an independent cycle blocks, by plugin name. Never null.
         *
         * @return the refused plugin names
         * @since 6.3.0
         */
        public Set<String> getRefusedPlugins() {
            return refusedPlugins;
        }

        /**
         * Each independent cycle found in the same resolve, as an ordered list of plugin names
         * whose first and last entries are the same node, e.g. {@code [A, B, A]}. Never null;
         * empty when the only failure is a missing dependency.
         *
         * @return the detected cycle paths
         * @since 6.3.0
         */
        public List<List<String>> getCyclePaths() {
            return cyclePaths;
        }
    }
}
