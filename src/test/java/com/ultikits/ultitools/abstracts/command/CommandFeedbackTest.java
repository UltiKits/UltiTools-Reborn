package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator.ValidationResult;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.SenderTypeValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.Text;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandFeedbackTest {
    private Player player;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(new UUID(0, 123));
    }

    @Test
    void classOperatorRejectionCarriesItsKey() throws Exception {
        assertFailure(() -> new PermissionValidator("", true).validate(context(player, "plain")),
                Text.of(CommandKeys.OP_REQUIRED));
    }

    @Test
    void methodOperatorRejectionCarriesItsKey() throws Exception {
        assertFailure(() -> new PermissionValidator().validate(context(player, "operator")),
                Text.of(CommandKeys.OP_REQUIRED));
    }

    @Test
    void classPermissionRejectionCarriesThePermissionLiterally() throws Exception {
        assertFailure(() -> new PermissionValidator("probe.<red>", false).validate(context(player, "plain")),
                Text.of(CommandKeys.NO_PERMISSION, Arg.string("permission", "probe.<red>")));
    }

    @Test
    void methodPermissionRejectionCarriesThePermissionLiterally() throws Exception {
        assertFailure(() -> new PermissionValidator().validate(context(player, "permission")),
                Text.of(CommandKeys.NO_PERMISSION, Arg.string("permission", "probe.secret")));
    }

    @Test
    void playerOnlyRejectionCarriesItsKey() throws Exception {
        assertFailure(() -> new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER)
                        .validate(context(mock(CommandSender.class), "plain")), Text.of(CommandKeys.PLAYER_ONLY));
    }

    @Test
    void consoleOnlyRejectionCarriesItsKey() throws Exception {
        assertFailure(() -> new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE).validate(context(player, "plain")),
                Text.of(CommandKeys.CONSOLE_ONLY));
    }

    @Test
    void senderUsageLockCarriesItsKey() throws Exception {
        UsageLockValidator validator = new UsageLockValidator();
        var context = context(player, "senderLock");
        assertThat(validator.validate(context).isValid()).isTrue();
        assertFailure(() -> validator.validate(context), Text.of(CommandKeys.SENDER_LOCKED));
        validator.onComplete(context, true);
    }

    @Test
    void serverUsageLockCarriesItsKey() throws Exception {
        UsageLockValidator validator = new UsageLockValidator();
        var context = context(player, "serverLock");
        assertThat(validator.validate(context).isValid()).isTrue();
        assertFailure(() -> validator.validate(context), Text.of(CommandKeys.SERVER_LOCKED));
        validator.onComplete(context, true);
    }

    @Test
    void cooldownCarriesAnIntegerNumberArgument() throws Exception {
        CooldownValidator validator = new CooldownValidator();
        var context = context(player, "cooldown");
        validator.onComplete(context, true);
        try (var ulti = mockStatic(UltiTools.class)) {
            UltiTools plugin = legacyPlugin();
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var result = validator.validate(context);
            assertThat(result.getErrorText()).isInstanceOf(Text.Translated.class);
            Text.Translated text = (Text.Translated) result.getErrorText();
            assertThat(text.key()).isEqualTo(CommandKeys.COOLDOWN);
            assertThat(text.args()).singleElement().isInstanceOfSatisfying(Arg.Numeric.class, argument -> {
                assertThat(argument.name()).isEqualTo("seconds");
                assertThat(argument.value()).isInstanceOf(Long.class);
                assertThat(argument.value().longValue()).isBetween(1L, 6L);
            });
            assertThat(result.getErrorMessage()).isNull();
        }
    }

    @Test
    void unresolvedBindingCarriesTheExecutionFailureKeyAndTheUnchangedReason() throws Exception {
        try (var ulti = mockStatic(UltiTools.class)) {
            UltiTools plugin = legacyPlugin();
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var result = new CooldownValidator().validate(context(player, "bound"));
            assertThat(result.getErrorText()).isInstanceOf(Text.Translated.class);
            var text = (Text.Translated) result.getErrorText();
            assertThat(text.key()).isEqualTo(CommandKeys.EXECUTION_FAILED);
            assertThat(text.args()).singleElement().isInstanceOfSatisfying(Arg.Unparsed.class, argument -> {
                assertThat(argument.name()).isEqualTo("error");
                assertThat(argument.value()).contains("@CmdCD on Methods.bound", "never resolved");
            });
            assertThat(result.getErrorKey()).isEqualTo("command.error.cooldown-unresolved");
        }
    }

    private static UltiTools legacyPlugin() {
        UltiTools plugin = mock(UltiTools.class);
        return plugin;
    }

    private static void assertFailure(FailureCall call, Text expected) throws Exception {
        try (var ulti = mockStatic(UltiTools.class)) {
            UltiTools plugin = legacyPlugin();
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var result = call.validate();
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorText()).isEqualTo(expected);
            assertThat(result.getErrorMessage()).isNull();
        }
    }

    private static CommandContext context(CommandSender sender, String name) throws Exception {
        return CommandContext.builder().sender(sender).commandName("test").alias("test")
                .matchedMethod(Methods.class.getMethod(name)).executorClass(Methods.class).build();
    }

    @FunctionalInterface
    private interface FailureCall {
        ValidationResult validate() throws Exception;
    }

    static class Methods {
        public void plain() { /* Metadata only. */ }
        @CmdMapping(format = "operator", requireOp = true)
        public void operator() { /* Metadata only. */ }
        @CmdMapping(format = "permission", permission = "probe.secret")
        public void permission() { /* Metadata only. */ }
        @UsageLimit(UsageLimit.LimitType.SENDER)
        public void senderLock() { /* Metadata only. */ }
        @UsageLimit(UsageLimit.LimitType.ALL)
        public void serverLock() { /* Metadata only. */ }
        @CmdCD(5)
        public void cooldown() { /* Metadata only. */ }
        @CmdCD(config = AbstractConfigEntity.class, key = "delay")
        public void bound() { /* Metadata only. */ }
    }
}
