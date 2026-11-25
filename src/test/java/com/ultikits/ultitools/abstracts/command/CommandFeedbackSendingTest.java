package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.abstracts.command.validation.CommandValidator.ValidationResult;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.ultikits.ultitools.scheduling.TaskHandle;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(CommandTestExtension.class)
class CommandFeedbackSendingTest {
    @BeforeEach
    void executeAsyncBodiesExplicitly() {
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return mock(TaskHandle.class);
        });
    }

    @Test
    void validationAndHelpFailuresUseTheSameDeferredDeliveryMethod() {
        var sender = mock(CommandSender.class);
        Text text = Text.of(CommandKeys.PLAYER_ONLY);
        var executor = new Fixture(ValidatorChain.builder().add(context -> ValidationResult.failure(text, "player")).build());
        assertThat(CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"body"})).isTrue();
        verify(CommandRuntime.messages()).send(sender, text);
        BaseCommandExecutor.sendValidationFailure(sender, null, "<red>legacy module text");
        verify(sender).sendMessage("<red>legacy module text");
        BaseCommandExecutor.sendValidationFailure(sender, null, null);
        assertThat(executor.bodies).isZero();
    }

    @Test
    void throwingBodySendsTheUnwrappedReasonAsALiteralArgument() {
        var sender = mock(CommandSender.class);
        assertThat(CommandTestSupport.dispatch(new Fixture(), sender, null, "probe", new String[] {"throwing"})).isTrue();
        verify(CommandRuntime.messages()).send(sender, Text.of(CommandKeys.EXECUTION_FAILED,
                Arg.string("error", "reason <red>")));
    }

    @Test
    void defaultAndValidCustomProcessingKeysUseMessages() throws Exception {
        var sender = mock(CommandSender.class);
        Logger logger = Logger.getAnonymousLogger();
        var messages = spy(new InMemoryMessages("ultitools", new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger)));
        messages.put(MessageKey.of("probe", "busy"), Locale.ENGLISH, "<blue>Working");
        try (var runtime = CommandRuntime.override(messages, CommandRuntime.scheduler())) {
            var executor = new Fixture();
            assertThat(CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"processing"})).isTrue();
            assertThat(CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"custom"})).isTrue();
            verify(messages).send(sender, Text.of(CommandKeys.PROCESSING));
            verify(messages).send(sender, Text.of(MessageKey.of("probe", "busy")));
            assertThat(CommandMessageTestSupport.plain(messages.render(Text.of(MessageKey.of("probe", "busy")), Locale.ENGLISH)))
                    .isEqualTo("Working");
        }
    }

    @Test
    void invalidProcessingKeyWarnsOnceAndUsesTheDefaultKey() {
        Logger logger = Logger.getLogger(BaseCommandExecutor.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* Memory only. */ }
            @Override public void close() { /* No owned resources. */ }
        };
        logger.addHandler(handler);
        var sender = mock(CommandSender.class);
        try {
            var executor = new Fixture();
            assertThat(CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"invalid"})).isTrue();
            assertThat(CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"invalid"})).isTrue();
            verify(CommandRuntime.messages(), times(2)).send(sender, Text.of(CommandKeys.PROCESSING));
            assertThat(records).filteredOn(record -> Level.WARNING.equals(record.getLevel())).singleElement()
                    .satisfies(record -> {
                        assertThat(record.getMessage()).contains("Invalid processingMessageKey");
                        assertThat(record.getParameters()).contains(Fixture.class.getName(), "not namespaced");
                    });
        } finally {
            logger.removeHandler(handler);
        }
    }

    static class Fixture extends BaseCommandExecutor {
        int bodies;
        Fixture() { super(ValidatorChain.builder().build()); }
        Fixture(ValidatorChain chain) { super(chain); }
        @CmdMapping(format = "body")
        public void body() { bodies++; }
        @CmdMapping(format = "throwing")
        public void throwing() { throw new IllegalStateException("reason <red>"); }
        @AsyncCommand(timeout = 0)
        @CmdMapping(format = "processing")
        public void processing() { bodies++; }
        @AsyncCommand(timeout = 0, processingMessageKey = "probe:busy")
        @CmdMapping(format = "custom")
        public void custom() { bodies++; }
        @AsyncCommand(timeout = 0, processingMessageKey = "not namespaced")
        @CmdMapping(format = "invalid")
        public void invalid() { bodies++; }
        @Override protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
    }
}
