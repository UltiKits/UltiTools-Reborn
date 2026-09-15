package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * #381 -- the help short-circuit used to run before the {@code CommandContext} was built, so it
 * bypassed the whole validator chain.
 * <p>
 * Two things were measured on a real Paper 1.21.4 server. A console received the complete help of
 * a {@code @CmdTarget(PLAYER)} command, in one module including an admin section gated only on
 * {@code hasPermission} -- which a console always satisfies. And the three modules exercised
 * behaved three different ways for the same input, because each author had guessed at a guarantee
 * the framework did not give: one printed everything, one tested {@code sender instanceof Player}
 * and silently printed nothing, one printed nothing at all.
 * <p>
 * The tests below pin the gate and, just as importantly, pin what it deliberately does <em>not</em>
 * gate on.
 *
 * @since 6.3.0
 */
@DisplayName("#381 help is gated on sender type and permission")
class HelpGateTest {

    private Command command;
    private Player player;
    private ConsoleCommandSender console;
    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        // The refusal messages are built through UltiTools.getInstance().i18n(...), so producing
        // a failure result at all needs the singleton -- mirroring BaseCommandExecutorTest.
        UltiTools instance = mock(UltiTools.class);
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(instance);
        lenient().when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("fixture");

        player = mock(Player.class);
        lenient().when(player.hasPermission(anyString())).thenReturn(true);
        lenient().when(player.isOp()).thenReturn(true);

        console = mock(ConsoleCommandSender.class);
        lenient().when(console.hasPermission(anyString())).thenReturn(true);
        lenient().when(console.isOp()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    @Nested
    @DisplayName("@CmdTarget")
    class SenderType {

        @Test
        @DisplayName("console is refused the help of a PLAYER-only command")
        void consoleIsRefusedPlayerOnlyHelp() {
            PlayerOnlyExecutor executor = new PlayerOnlyExecutor();

            executor.onCommand(console, command, "fixture", new String[]{"help"});

            assertThat(executor.helpShown)
                    .as("before #381 this printed the full help to a console, including an admin "
                            + "section a console's hasPermission always satisfies")
                    .isFalse();
        }

        @Test
        @DisplayName("a player still gets the help of a PLAYER-only command")
        void playerStillGetsHelp() {
            PlayerOnlyExecutor executor = new PlayerOnlyExecutor();

            executor.onCommand(player, command, "fixture", new String[]{"help"});

            // The control. Without it, a gate that refused everyone would pass the test above.
            assertThat(executor.helpShown).isTrue();
        }

        @Test
        @DisplayName("an unmatched subcommand goes through the same gate")
        void unmatchedSubcommandIsGatedToo() {
            PlayerOnlyExecutor executor = new PlayerOnlyExecutor();

            // matchMethod finds nothing, and that path also falls back to help -- it must not
            // become a way around the gate.
            executor.onCommand(console, command, "fixture", new String[]{"no-such-subcommand"});

            assertThat(executor.helpShown).isFalse();
        }
    }

    @Nested
    @DisplayName("what the gate deliberately does not apply")
    class DeliberateOmissions {

        @Test
        @DisplayName("a cooldown on the command does not withhold its help")
        void cooldownDoesNotBlockHelp() {
            CooldownExecutor executor = new CooldownExecutor();

            executor.onCommand(player, command, "fixture", new String[]{"help"});
            executor.onCommand(player, command, "fixture", new String[]{"help"});

            // Help is not the guarded action. Refusing to explain a command because the command is
            // on cooldown would be perverse, and UsageLockValidator acquires a lock only the
            // invocation path releases. Stated as a test so it cannot be mistaken later for the
            // same oversight #381 was.
            assertThat(executor.helpCount)
                    .as("help must remain available while the command itself is on cooldown")
                    .isEqualTo(2);
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    @CmdExecutor(alias = {"fixture"}, description = "player-only fixture")
    static class PlayerOnlyExecutor extends BaseCommandExecutor {
        boolean helpShown;

        @Override
        protected void handleHelp(CommandSender sender) {
            helpShown = true;
        }

        @CmdMapping(format = "act")
        public void act(@CmdSender Player sender) {
            // not exercised
        }
    }

    @CmdExecutor(alias = {"fixture"}, description = "cooldown fixture")
    static class CooldownExecutor extends BaseCommandExecutor {
        int helpCount;

        @Override
        protected void handleHelp(CommandSender sender) {
            helpCount++;
        }

        @CmdMapping(format = "act")
        @CmdCD(600)
        public void act(@CmdSender Player sender) {
            // not exercised
        }
    }

    /**
     * #413: {@code handleGatedHelp} invoked every sender-type and permission validator
     * unconditionally, never consulting {@link CommandValidator#shouldValidate}, unlike the
     * normal dispatch path ({@code ValidatorChain#validate}). These tests pin the fix: the help
     * path now consults applicability, and doing so does not weaken enforcement.
     */
    @Nested
    @DisplayName("#413 gated help consults each validator's applicability")
    class Applicability {

        @Test
        @DisplayName("a validator whose shouldValidate() reports false is not asked to validate")
        void inapplicableValidatorIsNeverAskedToValidate() {
            NeverApplicablePermissionValidator neverApplicable = new NeverApplicablePermissionValidator();
            ValidatorChain chain = ValidatorChain.builder().add(neverApplicable).build();
            CustomChainExecutor executor = new CustomChainExecutor(chain);

            executor.onCommand(player, command, "fixture", new String[]{"help"});

            assertThat(executor.helpShown)
                    .as("the inapplicable validator must be skipped, not invoked and happen to pass")
                    .isTrue();
            assertThat(neverApplicable.validateWasCalled)
                    .as("shouldValidate() reported false; validate() must never run")
                    .isFalse();
        }

        @Test
        @DisplayName("a validator that applies and denies still denies help")
        void applicableValidatorThatDeniesStillDeniesHelp() {
            AlwaysDenyingPermissionValidator denies = new AlwaysDenyingPermissionValidator();
            ValidatorChain chain = ValidatorChain.builder().add(denies).build();
            CustomChainExecutor executor = new CustomChainExecutor(chain);

            executor.onCommand(player, command, "fixture", new String[]{"help"});

            assertThat(executor.helpShown)
                    .as("consulting applicability must not weaken enforcement -- an applicable, "
                            + "denying validator must still deny")
                    .isFalse();
        }

        @Test
        @DisplayName("an empty validator chain on the help path produces help, not an error")
        void emptyValidatorChainStillProducesHelp() {
            ValidatorChain emptyChain = ValidatorChain.builder().build();
            CustomChainExecutor executor = new CustomChainExecutor(emptyChain);

            executor.onCommand(player, command, "fixture", new String[]{"help"});

            assertThat(executor.helpShown).isTrue();
        }
    }

    @CmdExecutor(alias = {"fixture"}, description = "custom-chain fixture")
    static class CustomChainExecutor extends BaseCommandExecutor {
        boolean helpShown;

        CustomChainExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            helpShown = true;
        }

        @CmdMapping(format = "act")
        public void act(@CmdSender Player sender) {
            // not exercised
        }
    }

    static class NeverApplicablePermissionValidator extends PermissionValidator {
        boolean validateWasCalled;

        @Override
        public boolean shouldValidate(CommandContext context) {
            return false;
        }

        @Override
        public CommandValidator.ValidationResult validate(CommandContext context) {
            validateWasCalled = true;
            return CommandValidator.ValidationResult.success();
        }
    }

    static class AlwaysDenyingPermissionValidator extends PermissionValidator {
        @Override
        public boolean shouldValidate(CommandContext context) {
            return true;
        }

        @Override
        public CommandValidator.ValidationResult validate(CommandContext context) {
            return CommandValidator.ValidationResult.failure("denied");
        }
    }
}
