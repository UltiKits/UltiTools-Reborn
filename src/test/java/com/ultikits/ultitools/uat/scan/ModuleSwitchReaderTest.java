package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.uat.fixtures.additionalentities.AbstractModuleBaseWithAdditionalEntities;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ConcreteModuleNotRedeclaringAdditionalEntities;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ExternalEntity;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ModuleWithAdditionalEntities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ModuleSwitchReader} reads {@code additionalEntities()} from the CONCRETE
 * runtime entry class directly, not through the same merged (hierarchy-walking) resolution
 * used for the registration switches -- matching {@code PluginManager.scanPluginEntities}'s
 * own direct, non-{@code @Inherited} lookup exactly (Phase 10, Codex review of PR #427).
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
    @DisplayName("the abstract base class itself (if ever scanned directly) still reports its own directly-declared additionalEntities")
    void theAbstractBaseItselfStillReportsItsOwnAdditionalEntities() {
        ModuleSwitchReader.Switches switches = new ModuleSwitchReader()
                .read(Collections.singletonList(AbstractModuleBaseWithAdditionalEntities.class));

        assertThat(switches).isNotNull();
        assertThat(switches.getAdditionalEntities()).containsExactly(ExternalEntity.class);
    }
}
