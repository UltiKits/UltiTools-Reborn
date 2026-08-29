package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.PluginDependency;
import com.ultikits.ultitools.manager.PluginDependencyResolver.CircularDependencyException;
import com.ultikits.ultitools.manager.PluginDependencyResolver.MissingDependencyException;

/**
 * Unit tests for {@link PluginDependencyResolver}.
 * Tests Kahn's algorithm for topological sorting of plugin dependencies.
 */
@DisplayName("PluginDependencyResolver Tests")
class PluginDependencyResolverTest {

    private PluginDependencyResolver resolver;
    private Logger testLogger;

    @BeforeEach
    void setUp() {
        testLogger = Logger.getLogger(PluginDependencyResolverTest.class.getName());
        resolver = new PluginDependencyResolver(testLogger);
    }

    // Test plugin classes with various dependency configurations
    public static class PluginA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    public static class PluginB extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    public static class PluginC extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(depends = {"PluginA"})
    public static class PluginDependsOnA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(depends = {"PluginA", "PluginB"})
    public static class PluginDependsOnAB extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(softDepends = {"PluginA"})
    public static class PluginSoftDependsOnA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(loadBefore = {"PluginC"})
    public static class PluginLoadBeforeC extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(depends = {"MissingPlugin"})
    public static class PluginWithMissingDep extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    // Circular dependency plugins
    @PluginDependency(depends = {"CircularB"})
    public static class CircularA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(depends = {"CircularA"})
    public static class CircularB extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    // A second, independent circular pair - proves two cycles in one input are each reported.
    @PluginDependency(depends = {"CircularD"})
    public static class CircularC extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @PluginDependency(depends = {"CircularC"})
    public static class CircularD extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    // Depends on a cycle member without being part of the cycle itself.
    @PluginDependency(depends = {"CircularA"})
    public static class PluginDependsOnCircularA extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    // Depends on a plugin that itself has a missing hard dependency.
    @PluginDependency(depends = {"PluginWithMissingDep"})
    public static class PluginDependsOnMissingDepPlugin extends UltiToolsPlugin {
        @Override public boolean registerSelf() { return true; }
        @Override public void unregisterSelf() { }
    }

    @Nested
    @DisplayName("Empty and Null Input Tests")
    class EmptyInputTests {

        @Test
        @DisplayName("should return empty list for null input")
        void shouldReturnEmptyForNull() throws Exception {
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyForEmptyList() throws Exception {
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(new ArrayList<>());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("No Dependencies Tests")
    class NoDependenciesTests {

        @Test
        @DisplayName("should return single plugin unchanged")
        void shouldReturnSinglePluginUnchanged() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Collections.singletonList(PluginA.class);
            
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(1, result.size());
            assertEquals(PluginA.class, result.get(0));
        }

        @Test
        @DisplayName("should handle multiple plugins with no dependencies")
        void shouldHandleMultiplePluginsNoDeps() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginA.class, PluginB.class, PluginC.class
            );
            
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(3, result.size());
            // Order should be alphabetical when no dependencies (due to PriorityQueue)
            assertTrue(result.containsAll(plugins));
        }
    }

    @Nested
    @DisplayName("Hard Dependencies Tests")
    class HardDependenciesTests {

        @Test
        @DisplayName("should load dependency before dependent plugin")
        void shouldLoadDependencyFirst() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginDependsOnA.class, PluginA.class
            );
            
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(2, result.size());
            // PluginA must come before PluginDependsOnA
            assertTrue(result.indexOf(PluginA.class) < result.indexOf(PluginDependsOnA.class));
        }

        @Test
        @DisplayName("should handle multiple dependencies")
        void shouldHandleMultipleDependencies() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginDependsOnAB.class, PluginB.class, PluginA.class
            );
            
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(3, result.size());
            // Both A and B must come before PluginDependsOnAB
            int indexAB = result.indexOf(PluginDependsOnAB.class);
            assertTrue(result.indexOf(PluginA.class) < indexAB);
            assertTrue(result.indexOf(PluginB.class) < indexAB);
        }

        @Test
        @DisplayName("should throw exception for missing hard dependency")
        void shouldThrowForMissingDependency() {
            List<Class<? extends UltiToolsPlugin>> plugins = Collections.singletonList(
                PluginWithMissingDep.class
            );
            
            assertThrows(MissingDependencyException.class, () -> resolver.resolve(plugins));
        }
    }

    @Nested
    @DisplayName("Soft Dependencies Tests")
    class SoftDependenciesTests {

        @Test
        @DisplayName("should load soft dependency before dependent when available")
        void shouldLoadSoftDepWhenAvailable() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginSoftDependsOnA.class, PluginA.class
            );
            
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(2, result.size());
            // PluginA should come before PluginSoftDependsOnA
            assertTrue(result.indexOf(PluginA.class) < result.indexOf(PluginSoftDependsOnA.class));
        }

        @Test
        @DisplayName("should load plugin even when soft dependency is missing")
        void shouldLoadWhenSoftDepMissing() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Collections.singletonList(
                PluginSoftDependsOnA.class
            );
            
            // Should not throw - soft dependencies are optional
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(1, result.size());
            assertEquals(PluginSoftDependsOnA.class, result.get(0));
        }
    }

    @Nested
    @DisplayName("LoadBefore Tests")
    class LoadBeforeTests {

        @Test
        @DisplayName("should load plugin before target specified in loadBefore")
        void shouldLoadBeforeTarget() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginC.class, PluginLoadBeforeC.class
            );
            
            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);
            
            assertEquals(2, result.size());
            // PluginLoadBeforeC should come before PluginC
            assertTrue(result.indexOf(PluginLoadBeforeC.class) < result.indexOf(PluginC.class));
        }
    }

    @Nested
    @DisplayName("Circular Dependency Tests")
    class CircularDependencyTests {

        @Test
        @DisplayName("should throw exception for circular dependencies")
        void shouldThrowForCircularDeps() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                CircularA.class, CircularB.class
            );
            
            assertThrows(CircularDependencyException.class, () -> resolver.resolve(plugins));
        }
    }

    @Nested
    @DisplayName("Partition and Cycle Path Tests")
    class PartitionAndCyclePathTests {

        @Test
        @DisplayName("a cycle refuses only its members while an unrelated plugin stays sortable")
        void cycleRefusesOnlyItsMembersWhileUnrelatedStaysSortable() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                CircularA.class, CircularB.class, PluginC.class
            );

            CircularDependencyException ex = assertThrows(CircularDependencyException.class,
                () -> resolver.resolve(plugins));

            // Positive and negative asserted together so neither can pass vacuously.
            assertTrue(ex.getSortedPrefix().contains(PluginC.class));
            assertFalse(ex.getRefusedPlugins().contains("PluginC"));
            assertEquals(new HashSet<>(Arrays.asList("CircularA", "CircularB")), ex.getRefusedPlugins());
        }

        @Test
        @DisplayName("a returned cycle path's first element equals its last element")
        void cyclePathFirstAndLastElementsAreEqual() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                CircularA.class, CircularB.class
            );

            CircularDependencyException ex = assertThrows(CircularDependencyException.class,
                () -> resolver.resolve(plugins));

            assertFalse(ex.getCyclePaths().isEmpty());
            List<String> path = ex.getCyclePaths().get(0);
            assertTrue(path.size() >= 2);
            assertEquals(path.get(0), path.get(path.size() - 1));
        }

        @Test
        @DisplayName("a module depending on a cycle member is refused although it is not itself in the cycle")
        void dependentOfCycleMemberIsRefused() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                CircularA.class, CircularB.class, PluginDependsOnCircularA.class
            );

            CircularDependencyException ex = assertThrows(CircularDependencyException.class,
                () -> resolver.resolve(plugins));

            assertTrue(ex.getRefusedPlugins().contains("PluginDependsOnCircularA"));
            boolean inAnyCyclePath = ex.getCyclePaths().stream()
                .anyMatch(path -> path.contains("PluginDependsOnCircularA"));
            assertFalse(inAnyCyclePath);
        }

        @Test
        @DisplayName("two independent cycles in one input are both reported as separate paths and fully refused")
        void twoIndependentCyclesAreBothReported() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                CircularA.class, CircularB.class, CircularC.class, CircularD.class
            );

            CircularDependencyException ex = assertThrows(CircularDependencyException.class,
                () -> resolver.resolve(plugins));

            assertEquals(2, ex.getCyclePaths().size());
            assertEquals(
                new HashSet<>(Arrays.asList("CircularA", "CircularB", "CircularC", "CircularD")),
                ex.getRefusedPlugins()
            );
        }

        @Test
        @DisplayName("a missing hard dependency yields a sortable prefix and an exact refused set")
        void missingHardDependencyPartition() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginWithMissingDep.class, PluginA.class, PluginB.class
            );

            MissingDependencyException ex = assertThrows(MissingDependencyException.class,
                () -> resolver.resolve(plugins));

            assertTrue(ex.getSortedPrefix().contains(PluginA.class));
            assertTrue(ex.getSortedPrefix().contains(PluginB.class));
            assertEquals(Collections.singleton("PluginWithMissingDep"), ex.getRefusedPlugins());
        }

        @Test
        @DisplayName("a module depending on a missing-dependency module is refused alongside it; unrelated modules survive")
        void dependentOfMissingDependencyModuleIsRefused() {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginWithMissingDep.class, PluginDependsOnMissingDepPlugin.class, PluginA.class
            );

            MissingDependencyException ex = assertThrows(MissingDependencyException.class,
                () -> resolver.resolve(plugins));

            assertTrue(ex.getSortedPrefix().contains(PluginA.class));
            assertEquals(
                new HashSet<>(Arrays.asList("PluginWithMissingDep", "PluginDependsOnMissingDepPlugin")),
                ex.getRefusedPlugins()
            );
        }

        @Test
        @DisplayName("no cycle and no missing dependency: neither exception is constructed")
        void noFailureMeansUnchangedBehaviour() throws Exception {
            List<Class<? extends UltiToolsPlugin>> plugins = Arrays.asList(
                PluginA.class, PluginB.class, PluginC.class
            );

            List<Class<? extends UltiToolsPlugin>> result = resolver.resolve(plugins);

            assertEquals(3, result.size());
            assertTrue(result.containsAll(plugins));
        }
    }

    @Nested
    @DisplayName("PluginNode Tests")
    class PluginNodeTests {

        @Test
        @DisplayName("should extract plugin name from class")
        void shouldExtractPluginName() {
            PluginDependencyResolver.PluginNode node = 
                new PluginDependencyResolver.PluginNode(PluginA.class);
            
            assertEquals("PluginA", node.getPluginName());
            assertEquals(PluginA.class, node.getPluginClass());
        }

        @Test
        @DisplayName("should extract hard dependencies from annotation")
        void shouldExtractHardDeps() {
            PluginDependencyResolver.PluginNode node = 
                new PluginDependencyResolver.PluginNode(PluginDependsOnA.class);
            
            assertTrue(node.getHardDependencies().contains("PluginA"));
        }

        @Test
        @DisplayName("should extract soft dependencies from annotation")
        void shouldExtractSoftDeps() {
            PluginDependencyResolver.PluginNode node = 
                new PluginDependencyResolver.PluginNode(PluginSoftDependsOnA.class);
            
            assertTrue(node.getSoftDependencies().contains("PluginA"));
        }

        @Test
        @DisplayName("should have empty dependencies when no annotation")
        void shouldHaveEmptyDepsWhenNoAnnotation() {
            PluginDependencyResolver.PluginNode node = 
                new PluginDependencyResolver.PluginNode(PluginA.class);
            
            assertTrue(node.getHardDependencies().isEmpty());
            assertTrue(node.getSoftDependencies().isEmpty());
            assertTrue(node.getLoadBefore().isEmpty());
        }
    }
}
