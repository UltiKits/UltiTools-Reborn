package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.uat.fixtures.additionalentities.AbstractModuleBaseWithAdditionalEntities;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ConcreteModuleNotRedeclaringAdditionalEntities;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ExternalEntity;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ModuleWithAdditionalEntities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ModuleSwitchReader} reads {@code additionalEntities()} from the CONCRETE
 * runtime entry class directly, not through the same merged (hierarchy-walking) resolution
 * used for the registration switches -- matching {@code PluginManager.scanPluginEntities}'s
 * own direct, non-{@code @Inherited} lookup exactly. Also proves the entry-class search itself
 * skips interfaces and {@code abstract} classes, matching
 * {@code PluginManager.loadPluginMainClass}'s own selection criteria -- otherwise an abstract
 * module base sorting before its concrete subclass in {@code ModuleClassIndex}'s (typically
 * lexicographic) class order would be picked first (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@DisplayName("ModuleSwitchReader additionalEntities() concrete-class lookup")
class ModuleSwitchReaderTest {

    @Test
    @DisplayName("a module class directly annotated with additionalEntities() reports them")
    void directlyAnnotatedModuleReportsAdditionalEntities() {
        ModuleSwitchReader.Switches switches = new ModuleSwitchReader()
                .read(Collections.singletonList(ModuleWithAdditionalEntities.class));

        assertThat(switches).isNotNull();
        assertThat(switches.getAdditionalEntities()).containsExactly(ExternalEntity.class);
    }

    @Test
    @DisplayName("a concrete module class that does NOT redeclare @UltiToolsModule reports NO additionalEntities, even though its abstract base declares them")
    void subclassNotRedeclaringReportsNoAdditionalEntities() {
        ModuleSwitchReader.Switches switches = new ModuleSwitchReader()
                .read(Collections.singletonList(ConcreteModuleNotRedeclaringAdditionalEntities.class));

        assertThat(switches).isNotNull();
        assertThat(switches.getAdditionalEntities()).isEmpty();
    }

    @Test
    @DisplayName("the same subclass still resolves its registration switches via merged (hierarchy-walking) resolution")
    void subclassNotRedeclaringStillResolvesSwitchesFromTheBase() {
        // UltiToolsPlugin.initConfig itself reads its switch via
        // MergedAnnotationResolver.find(this.getClass(), ...) -- an unannotated subclass of an
        // annotated abstract module base DOES inherit the switches at runtime, unlike
        // additionalEntities(). Both AbstractModuleBaseWithAdditionalEntities and its subclass
        // declare no explicit cmdExecutor()/eventListener()/config() override, so both default
        // to true; this asserts the merged lookup itself did not silently fail and fall through
        // to some other default.
        ModuleSwitchReader.Switches switches = new ModuleSwitchReader()
                .read(Collections.singletonList(ConcreteModuleNotRedeclaringAdditionalEntities.class));

        assertThat(switches).isNotNull();
        assertThat(switches.isRegistersCommands()).isTrue();
        assertThat(switches.isRegistersListeners()).isTrue();
        assertThat(switches.isRegistersConfig()).isTrue();
    }

    @Test
    @DisplayName("an abstract module base alone (no concrete subclass present) resolves no switches at all -- PluginManager.loadPluginMainClass never selects an abstract class as a module's entry point")
    void abstractBaseAloneResolvesNoSwitches() {
        ModuleSwitchReader.Switches switches = new ModuleSwitchReader()
                .read(Collections.singletonList(AbstractModuleBaseWithAdditionalEntities.class));

        assertThat(switches).isNull();
    }

    @Test
    @DisplayName("when the abstract base sorts BEFORE its concrete subclass in the input list, the concrete subclass is still selected as the entry class -- not the abstract base")
    void abstractBaseSortingFirstDoesNotShadowTheConcreteSubclass() {
        // ModuleClassIndex supplies classes in an arbitrary (typically lexicographic) order;
        // "AbstractModuleBaseWithAdditionalEntities" sorts before
        // "ConcreteModuleNotRedeclaringAdditionalEntities" alphabetically, so this list order
        // is deliberately the one that would have picked the wrong class before this fix
        // (Codex review of PR #427).
        List<Class<?>> classes = Arrays.asList(
                AbstractModuleBaseWithAdditionalEntities.class,
                ConcreteModuleNotRedeclaringAdditionalEntities.class);

        Class<?> entryClass = ModuleSwitchReader.findModuleEntryClass(classes);

        assertThat(entryClass).isEqualTo(ConcreteModuleNotRedeclaringAdditionalEntities.class);

        ModuleSwitchReader.Switches switches = new ModuleSwitchReader().read(classes);
        assertThat(switches).isNotNull();
        assertThat(switches.getAdditionalEntities()).isEmpty();
    }
}
