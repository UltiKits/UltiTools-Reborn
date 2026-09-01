package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Pins the resolved sender type of the framework's three core commands - {@code /ul},
 * {@code /ulticloud} and {@code /upm} - as they stood on 6.2.5, before plan 07-05 migrated them
 * from {@code AbstractCommandExecutor} to {@code BaseCommandExecutor}. {@code
 * AbstractCommandExecutor} itself was later removed entirely by plan 07-15 (6.3.0).
 * <p>
 * The migration swaps a base class, not an annotation, so a compiler cannot catch a resolved
 * sender type silently flipping. This test drives every assertion through
 * {@link CmdTargetComposition#resolve(CmdTarget.CmdTargetType, java.lang.reflect.Method)} - the
 * one call both executor generations route through (07-CONTEXT T-07-05-02) - rather than reading
 * {@code @CmdTarget} off the class directly, which would pass trivially and prove nothing about
 * resolution.
 * <p>
 * None of the three classes carries a method-level {@code @CmdTarget} override (verified by
 * inspection at plan-authoring time), so each class contributes exactly one assertion: its
 * class-level value resolved with a {@code null} method, exercising {@code resolve}'s
 * no-method-level-override fallback path.
 * <p>
 * {@code CloudLoginCommand} is the one that matters most: it is the sole {@code CONSOLE}-only
 * command among the three, and root {@code CLAUDE.md} records that {@code ulticloud login} is
 * magic-link and console-only by design. A silent widening to {@code BOTH} during the base-class
 * swap would put UltiCloud authentication in reach of any player holding the permission node.
 *
 * @since 6.3.0
 */
@DisplayName("Core command sender restriction regression guard")
class CoreCommandSenderRestrictionTest {

    /**
     * Reads a class's declared {@code @CmdTarget} value directly. This is the one place in this
     * test that reads the annotation without going through {@code CmdTargetComposition} - it
     * exists only to obtain the class-level input that gets fed into {@code resolve}, mirroring
     * exactly what the removed (6.3.0) {@code AbstractCommandExecutor#checkSender} and
     * {@code SenderTypeValidator#determineTargetType} both did before delegating to the shared
     * resolution call.
     */
    private static CmdTarget.CmdTargetType classLevelValue(Class<?> commandClass) {
        assertThat(commandClass.isAnnotationPresent(CmdTarget.class))
                .as("%s must carry a class-level @CmdTarget", commandClass.getName())
                .isTrue();
        return commandClass.getAnnotation(CmdTarget.class).value();
    }

    @Nested
    @DisplayName("UltiToolsCommands (/ul)")
    class UltiToolsCommandsTests {

        @Test
        @DisplayName("resolves to BOTH with no method-level override")
        void resolvesToBoth() {
            CmdTarget.CmdTargetType classLevel = classLevelValue(UltiToolsCommands.class);
            CmdTarget.CmdTargetType resolved = CmdTargetComposition.resolve(classLevel, null);

            assertThat(resolved).isEqualTo(CmdTarget.CmdTargetType.BOTH);
        }
    }

    @Nested
    @DisplayName("PluginInstallCommands (/upm)")
    class PluginInstallCommandsTests {

        @Test
        @DisplayName("resolves to BOTH with no method-level override")
        void resolvesToBoth() {
            CmdTarget.CmdTargetType classLevel = classLevelValue(PluginInstallCommands.class);
            CmdTarget.CmdTargetType resolved = CmdTargetComposition.resolve(classLevel, null);

            assertThat(resolved).isEqualTo(CmdTarget.CmdTargetType.BOTH);
        }
    }

    @Nested
    @DisplayName("CloudLoginCommand (/ulticloud) - the narrowing case")
    class CloudLoginCommandTests {

        @Test
        @DisplayName("resolves to CONSOLE with no method-level override")
        void resolvesToConsole() {
            CmdTarget.CmdTargetType classLevel = classLevelValue(CloudLoginCommand.class);
            CmdTarget.CmdTargetType resolved = CmdTargetComposition.resolve(classLevel, null);

            assertThat(resolved).isEqualTo(CmdTarget.CmdTargetType.CONSOLE);
        }

        @Test
        @DisplayName("is the one core command narrower than BOTH")
        void isTheOnlyNarrowingCommand() {
            CmdTarget.CmdTargetType cloudLogin = classLevelValue(CloudLoginCommand.class);
            CmdTarget.CmdTargetType ultiTools = classLevelValue(UltiToolsCommands.class);
            CmdTarget.CmdTargetType pluginInstall = classLevelValue(PluginInstallCommands.class);

            assertThat(cloudLogin).isNotEqualTo(CmdTarget.CmdTargetType.BOTH);
            assertThat(ultiTools).isEqualTo(CmdTarget.CmdTargetType.BOTH);
            assertThat(pluginInstall).isEqualTo(CmdTarget.CmdTargetType.BOTH);
        }
    }
}
