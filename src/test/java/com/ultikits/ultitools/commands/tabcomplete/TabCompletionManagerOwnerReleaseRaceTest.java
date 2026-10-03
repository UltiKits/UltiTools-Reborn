package com.ultikits.ultitools.commands.tabcomplete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Codex review of UltiTools-Reborn#564, round 4: releasing a module's completers must remove a key
 * only while it still holds the exact registration being released.
 * <p>
 * The completer and its ownership were three separate map entries, removed one after another. A
 * {@code register} of the same key landing between the ownership check and the completer removal
 * -- another thread, or anything the removal itself triggers -- lost its completer. The race is made
 * deterministic here: the manager's completer map is wrapped so that its {@code remove} first
 * performs that competing {@code register}, then removes.
 */
@DisplayName("Releasing a module's completers never deletes a registration made meanwhile (#506, Codex round 4)")
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "unchecked", "rawtypes"}) // swaps the singleton's private map for the test
class TabCompletionManagerOwnerReleaseRaceTest {

    private static final String KEY = "@owner-release-race";

    private TabCompletionManager manager;
    private Field completersField;
    private Object originalCompleters;

    /** Runs {@code beforeRemove} once, on the first removal of {@link #KEY}, then removes. */
    private static final class InterleavingMap extends ConcurrentHashMap {
        private final transient Runnable beforeRemove;
        private boolean fired;

        InterleavingMap(Map original, Runnable beforeRemove) {
            super(original);
            this.beforeRemove = beforeRemove;
        }

        private void fireOnce(Object key) {
            if (!fired && KEY.equals(key)) {
                fired = true;
                beforeRemove.run();
            }
        }

        @Override
        public Object remove(Object key) {
            fireOnce(key);
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            fireOnce(key);
            return super.remove(key, value);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = TabCompletionManager.getInstance();
        completersField = TabCompletionManager.class.getDeclaredField("completers");
        completersField.setAccessible(true);
        originalCompleters = completersField.get(manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        completersField.set(manager, originalCompleters);
        manager.unregister(KEY);
    }

    @Test
    @DisplayName("a register of the same key during the release keeps its completer")
    void registerDuringInstanceReleaseSurvives() throws Exception {
        UltiToolsPlugin unloading = mock(UltiToolsPlugin.class);
        TabCompleter unloadingCompleter = context -> Collections.singletonList("old");
        TabCompleter newcomer = context -> Collections.singletonList("new");
        manager.beginRegistrationScope("Unloading", unloading);
        try {
            manager.register(KEY, unloadingCompleter);
        } finally {
            manager.endRegistrationScope();
        }
        completersField.set(manager, new InterleavingMap((Map) completersField.get(manager),
                () -> manager.register(KEY, newcomer)));

        manager.unregisterByOwnerInstance(unloading);

        assertThat(manager.getCompleter(KEY))
                .as("the registration made while the unloading module's completers were released survives")
                .isSameAs(newcomer);
    }

    @Test
    @DisplayName("the name sweep keeps a register of the same key made during it, too")
    void registerDuringNameReleaseSurvives() throws Exception {
        TabCompleter unloadingCompleter = context -> Collections.singletonList("old");
        TabCompleter newcomer = context -> Collections.singletonList("new");
        manager.beginRegistrationScope("UnloadingByName");
        try {
            manager.register(KEY, unloadingCompleter);
        } finally {
            manager.endRegistrationScope();
        }
        completersField.set(manager, new InterleavingMap((Map) completersField.get(manager),
                () -> manager.register(KEY, newcomer)));

        manager.unregisterByOwner("UnloadingByName");

        assertThat(manager.getCompleter(KEY)).isSameAs(newcomer);
    }
}
