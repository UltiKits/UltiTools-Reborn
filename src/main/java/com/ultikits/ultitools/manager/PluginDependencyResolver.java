package com.ultikits.ultitools.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Logger;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.PluginDependency;

/**
 * Plugin dependency resolver using Kahn's algorithm for topological sorting.
 * Ensures plugins are loaded in the correct order based on their dependencies.
 * <p>
 * 使用 Kahn 算法进行拓扑排序的插件依赖解析器。
 * 确保插件根据其依赖关系以正确的顺序加载。
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class PluginDependencyResolver {
    
    private final Logger logger;
    
    /**
     * Constructs a new PluginDependencyResolver.
     *
     * @param logger the logger to use for warnings and errors
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
        
        public PluginNode(Class<? extends UltiToolsPlugin> pluginClass) {
            this.pluginClass = pluginClass;
            this.pluginName = pluginClass.getSimpleName();
            this.hardDependencies = new HashSet<>();
            this.softDependencies = new HashSet<>();
            this.loadBefore = new HashSet<>();
            
            // Extract dependencies from annotation
            if (pluginClass.isAnnotationPresent(PluginDependency.class)) {
                PluginDependency dep = pluginClass.getAnnotation(PluginDependency.class);
                Collections.addAll(hardDependencies, dep.depends());
                Collections.addAll(softDependencies, dep.softDepends());
                Collections.addAll(loadBefore, dep.loadBefore());
            }
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
         * Gets all dependencies (hard + soft) that exist in the available plugins.
         */
        public Set<String> getAllDependencies(Set<String> availablePlugins) {
            Set<String> allDeps = new HashSet<>(hardDependencies);
            for (String softDep : softDependencies) {
                if (availablePlugins.contains(softDep)) {
                    allDeps.add(softDep);
                }
            }
            return allDeps;
        }
    }
    
    /**
     * Resolves plugin load order using Kahn's algorithm for topological sorting.
     * <p>
     * 使用 Kahn 算法解析插件加载顺序。
     *
     * @param pluginClasses the list of plugin classes to sort
     * @return sorted list of plugin classes, or null if circular dependency detected
     * @throws CircularDependencyException if circular dependencies are detected
     * @throws MissingDependencyException if required dependencies are missing
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
        
        // Validate hard dependencies
        validateHardDependencies(nodes, availablePlugins);
        
        // Build adjacency list and calculate in-degrees
        Map<String, Set<String>> adjacencyList = buildAdjacencyList(nodes, availablePlugins);
        Map<String, Integer> inDegree = calculateInDegrees(nodes, adjacencyList, availablePlugins);
        
        // Kahn's algorithm
        List<String> sortedNames = kahnSort(nodes.keySet(), adjacencyList, inDegree);
        
        // Check for circular dependency
        if (sortedNames.size() != nodes.size()) {
            Set<String> remaining = new HashSet<>(nodes.keySet());
            remaining.removeAll(sortedNames);
            throw new CircularDependencyException(
                "Circular dependency detected among plugins: " + remaining
            );
        }
        
        // Convert back to class list
        List<Class<? extends UltiToolsPlugin>> result = new ArrayList<>();
        for (String name : sortedNames) {
            result.add(nodes.get(name).getPluginClass());
        }
        
        return result;
    }
    
    /**
     * Validates that all hard dependencies exist.
     */
    private void validateHardDependencies(Map<String, PluginNode> nodes, Set<String> availablePlugins) 
            throws MissingDependencyException {
        for (PluginNode node : nodes.values()) {
            for (String hardDep : node.getHardDependencies()) {
                if (!availablePlugins.contains(hardDep)) {
                    throw new MissingDependencyException(
                        String.format("Plugin '%s' requires dependency '%s' which is not available",
                            node.getPluginName(), hardDep)
                    );
                }
            }
        }
    }
    
    /**
     * Builds the adjacency list for the dependency graph.
     * An edge from A to B means B depends on A (so A must load before B).
     */
    private Map<String, Set<String>> buildAdjacencyList(
            Map<String, PluginNode> nodes, Set<String> availablePlugins) {
        
        Map<String, Set<String>> adjacencyList = new HashMap<>();
        
        // Initialize empty sets
        for (String name : nodes.keySet()) {
            adjacencyList.put(name, new HashSet<>());
        }
        
        for (PluginNode node : nodes.values()) {
            String pluginName = node.getPluginName();
            
            // For each dependency, add edge: dependency -> this plugin
            for (String dep : node.getAllDependencies(availablePlugins)) {
                if (adjacencyList.containsKey(dep)) {
                    adjacencyList.get(dep).add(pluginName);
                }
            }
            
            // For loadBefore, add edge: this plugin -> target
            for (String target : node.getLoadBefore()) {
                if (availablePlugins.contains(target)) {
                    adjacencyList.get(pluginName).add(target);
                }
            }
        }
        
        return adjacencyList;
    }
    
    /**
     * Calculates in-degrees for all nodes.
     */
    private Map<String, Integer> calculateInDegrees(
            Map<String, PluginNode> nodes,
            Map<String, Set<String>> adjacencyList,
            Set<String> availablePlugins) {
        
        Map<String, Integer> inDegree = new HashMap<>();
        
        // Initialize all in-degrees to 0
        for (String name : nodes.keySet()) {
            inDegree.put(name, 0);
        }
        
        // Count incoming edges
        for (Set<String> neighbors : adjacencyList.values()) {
            for (String neighbor : neighbors) {
                inDegree.merge(neighbor, 1, Integer::sum);
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
     */
    public static class CircularDependencyException extends Exception {
        public CircularDependencyException(String message) {
            super(message);
        }
    }
    
    /**
     * Exception thrown when required dependencies are missing.
     */
    public static class MissingDependencyException extends Exception {
        public MissingDependencyException(String message) {
            super(message);
        }
    }
}
