package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.uat.fixtures.OutsideScanScopeCommand;
import com.ultikits.ultitools.uat.fixtures.scanscope.InScopeCommand;
import com.ultikits.ultitools.uat.fixtures.scanscope.ModuleWithDefaultScanScope;
import com.ultikits.ultitools.uat.fixtures.scanscope.ModuleWithExplicitScanScope;
import com.ultikits.ultitools.uat.fixtures.scanscope.declaredpackage.DeclaredPackageCommand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link SurfaceAssembler} scopes command/listener extraction to the module's own
 * runtime component-scan packages -- {@code @UltiToolsModule}'s {@code scanBasePackages()}/
 * {@code scanBasePackageClasses()}, merged through its {@code @ComponentScan} meta-annotation
 * exactly as {@code PluginManager.getPluginScanPackages} resolves it, defaulting to the entry
 * class's own package when neither is declared -- since {@code ComponentScanner} never
 * registers a class outside those packages as a bean at all (Phase 10, Codex review of PR
 * #427).
 *
 * @since 6.3.0
 */
@DisplayName("SurfaceAssembler command/listener scan-package scoping")
class SurfaceAssemblerScanScopeTest {

    @Test
    @DisplayName("a command in the module's own (default-scope) package is included")
    void commandInDefaultScopeIsIncluded() throws ExtractorException {
        List<Class<?>> classes = Arrays.asList(ModuleWithDefaultScanScope.class, InScopeCommand.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        assertThat(hasCommandRowFor(surface, InScopeCommand.class)).isTrue();
    }

    @Test
    @DisplayName("a command outside the module's default (own-package) scope is excluded -- ComponentScanner never registers it")
    void commandOutsideDefaultScopeIsExcluded() throws ExtractorException {
        List<Class<?>> classes = Arrays.asList(ModuleWithDefaultScanScope.class, OutsideScanScopeCommand.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        assertThat(hasCommandRowFor(surface, OutsideScanScopeCommand.class)).isFalse();
    }

    @Test
    @DisplayName("a command in an EXPLICITLY declared scanBasePackages() is included even though it is not the module's own package")
    void commandInExplicitlyDeclaredScopeIsIncluded() throws ExtractorException {
        List<Class<?>> classes = Arrays.asList(ModuleWithExplicitScanScope.class, DeclaredPackageCommand.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        assertThat(hasCommandRowFor(surface, DeclaredPackageCommand.class)).isTrue();
    }

    @Test
    @DisplayName("declaring scanBasePackages() explicitly does NOT also default to the module's own package")
    void explicitScanBasePackagesDoesNotAlsoDefaultToOwnPackage() throws ExtractorException {
        // InScopeCommand lives in ModuleWithExplicitScanScope's own package
        // (fixtures.scanscope), which ModuleWithExplicitScanScope's declared
        // scanBasePackages() does NOT name -- PluginManager.getPluginScanPackages only falls
        // back to the entry class's own package when NOTHING is declared, per its own javadoc.
        List<Class<?>> classes = Arrays.asList(ModuleWithExplicitScanScope.class, InScopeCommand.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        assertThat(hasCommandRowFor(surface, InScopeCommand.class)).isFalse();
    }

    @Test
    @DisplayName("with no @UltiToolsModule entry class present, no scan-package restriction applies at all")
    void noModuleClassMeansNoRestriction() throws ExtractorException {
        List<Class<?>> classes = Arrays.asList(OutsideScanScopeCommand.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("framework", classes);

        assertThat(hasCommandRowFor(surface, OutsideScanScopeCommand.class)).isTrue();
    }

    private static boolean hasCommandRowFor(SurfaceAssembler.AssembledSurface surface, Class<?> clazz) {
        for (Map<String, Object> row : surface.getRows()) {
            if ("command".equals(row.get("kind")) && clazz.getName().equals(row.get("class"))) {
                return true;
            }
        }
        return false;
    }
}
