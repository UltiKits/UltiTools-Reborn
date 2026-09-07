package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.fixtures.FixtureListeners;
import com.ultikits.ultitools.uat.fixtures.listeneredgecases.ListenerEdgeCaseFixtures;
import com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsa.ReloadEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ListenerRowScanner} emits one {@code listener} row per {@code @EventHandler}
 * method — not one per {@code @EventListener} class — and represents a
 * {@code manualRegister = true} class rather than suppressing it (Phase 10, D-10-04/D-10-05).
 */
@DisplayName("ListenerRowScanner")
class ListenerRowScannerTest {

    @Test
    @DisplayName("emits one row per @EventHandler method, carrying event type and handler priority")
    void emitsOneRowPerHandlerMethod() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(FixtureListeners.JoinListener.class, FixtureListeners.MultiHandlerListener.class));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get("id")).doesNotHaveDuplicates();
        assertThat(rows).allSatisfy(row -> assertThat(row.get("kind")).isEqualTo("listener"));

        Map<String, Object> joinRow = rowFor(rows, FixtureListeners.JoinListener.class.getSimpleName(), "onJoin");
        // Fully qualified, not the simple name (Codex review of PR #427): two distinct event
        // classes in different packages sharing a simple name must remain distinguishable to
        // tools/uat/uat.py next's listener-batch grouping, which keys off this exact field.
        assertThat(joinRow.get("event")).isEqualTo("org.bukkit.event.player.PlayerJoinEvent");
        assertThat(joinRow.get("handler_priority")).isEqualTo("HIGH");

        Map<String, Object> multiJoinRow = rowFor(rows, FixtureListeners.MultiHandlerListener.class.getSimpleName(), "onJoin");
        assertThat(multiJoinRow.get("handler_priority")).isEqualTo("NORMAL");

        Map<String, Object> multiQuitRow = rowFor(rows, FixtureListeners.MultiHandlerListener.class.getSimpleName(), "onQuit");
        assertThat(multiQuitRow.get("event")).isEqualTo("org.bukkit.event.player.PlayerQuitEvent");
        assertThat(multiQuitRow.get("handler_priority")).isEqualTo("MONITOR");
    }

    @Test
    @DisplayName("a legitimately overloaded handler method name (different event types) produces distinct rows, not a collision")
    void overloadedHandlerMethodNameProducesDistinctRows() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(ListenerEdgeCaseFixtures.OverloadedHandlerListener.class));

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("id")).doesNotHaveDuplicates();
        assertThat(rows).extracting(row -> row.get("event"))
                .containsExactlyInAnyOrder(
                        "org.bukkit.event.player.PlayerJoinEvent",
                        "org.bukkit.event.player.PlayerQuitEvent");
    }

    @Test
    @DisplayName("a non-public @EventHandler inherited unchanged from a superclass produces no row -- Bukkit never registers it for the subclass")
    void inheritedNonPublicHandlerProducesNoRow() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(ListenerEdgeCaseFixtures.SubclassNotOverridingHandler.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a non-public @EventHandler declared directly on the concrete class still produces a row -- Bukkit's declared-methods half of discovery ignores visibility")
    void directlyDeclaredNonPublicHandlerStillProducesARow() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(ListenerEdgeCaseFixtures.SubclassWithOwnNonPublicHandler.class));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("member")).isEqualTo("onDirectlyDeclaredNonPublicHandler");
    }

    @Test
    @DisplayName("an @EventListener class that does not implement Listener produces no rows -- Bukkit's getBeanNamesForType(Listener.class) never returns it")
    void eventListenerClassNotImplementingListenerProducesNoRows() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(ListenerEdgeCaseFixtures.AnnotatedButNotAListener.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("two different-package events sharing a simple name are distinguished by the fully qualified event field")
    void crossPackageSameSimpleNameEventsAreDistinguished() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(ListenerEdgeCaseFixtures.CrossPackageSameSimpleNameListener.class));

        assertThat(rows).hasSize(2);
        Map<String, Object> rowA = rowFor(rows, ListenerEdgeCaseFixtures.CrossPackageSameSimpleNameListener.class.getSimpleName(), "onReloadA");
        Map<String, Object> rowB = rowFor(rows, ListenerEdgeCaseFixtures.CrossPackageSameSimpleNameListener.class.getSimpleName(), "onReloadB");
        assertThat(rowA.get("event")).isEqualTo(ReloadEvent.class.getName());
        assertThat(rowB.get("event"))
                .isEqualTo("com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsb.ReloadEvent")
                .isNotEqualTo(rowA.get("event"));
    }

    @Test
    @DisplayName("a manualRegister=true class is still represented, never suppressed")
    void manualRegisterClassIsStillRepresented() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(FixtureListeners.ManualListener.class));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("member")).isEqualTo("onQuit");
        // ListenerManager.registerAll deliberately skips automatic registration for a
        // manualRegister = true class; without recording that fact here, this row would be
        // indistinguishable from an automatically-registered handler (Codex review of PR
        // #427), and an executor could fail it for never firing when its absence from a
        // listener dump is the expected, documented shape.
        assertThat(rows.get(0).get("manual_register")).isEqualTo(true);
    }

    @Test
    @DisplayName("an automatically-registered class's rows carry manual_register=false")
    void automaticallyRegisteredClassCarriesManualRegisterFalse() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(FixtureListeners.JoinListener.class));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("manual_register")).isEqualTo(false);
    }

    @Test
    @DisplayName("a handler signature Bukkit's own registerEvents would reject or skip produces no row -- zero parameters, too many parameters, or a non-Event parameter")
    void invalidHandlerSignaturesProduceNoRows() throws ExtractorException {
        List<Map<String, Object>> rows = new ListenerRowScanner().scan("Fixture",
                Arrays.asList(FixtureListeners.InvalidSignatureListener.class));

        assertThat(rows).isEmpty();
    }

    private static Map<String, Object> rowFor(List<Map<String, Object>> rows, String cls, String member) {
        Optional<Map<String, Object>> match = rows.stream()
                .filter(row -> cls.equals(row.get("cls")) && member.equals(row.get("member")))
                .findFirst();
        assertThat(match).as("row for " + cls + "#" + member).isPresent();
        return match.get();
    }
}
