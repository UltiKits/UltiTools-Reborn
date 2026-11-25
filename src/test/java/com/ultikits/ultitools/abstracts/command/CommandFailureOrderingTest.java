package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.TestHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

@ExtendWith(CommandTestExtension.class)
class CommandFailureOrderingTest {
    @Test
    void throwingReplyCannotHideTheCommandLogOrErrorReport() {
        ErrorReportCollector collector = mock(ErrorReportCollector.class);
        TestHelper.mockUltiToolsInstance(plugin -> Mockito.when(plugin.getErrorReportCollector()).thenReturn(collector));
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(BaseCommandExecutor.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* Memory only. */ }
            @Override public void close() { /* No owned resources. */ }
        };
        logger.addHandler(handler);
        CommandSender sender = mock(CommandSender.class);
        Messages messages = CommandRuntime.messages();
        var executor = new Fixture();
        doAnswer(call -> {
            assertThat(records).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                assertThat(record.getMessage()).contains("Command execution failed: boom");
            });
            verify(collector).reportErrorFromClass(any(Throwable.class), any(), any());
            throw new IllegalStateException("reply service unavailable");
        }).when(messages).send(any(), any(Text.class));
        try {
            assertThatCode(() -> CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"boom"}))
                    .doesNotThrowAnyException();
            assertThat(records).isNotEmpty();
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void refusalReleasesTheUsageLockBeforeTryingToSendItsReply() {
        var executor = new Fixture();
        Player sender = mock(Player.class);
        UUID id = UUID.randomUUID();
        Mockito.when(sender.getUniqueId()).thenReturn(id);
        executor.addValidator(new CommandValidator() {
            @Override public int getOrder() { return 400; }
            @Override public ValidationResult validate(CommandContext context) {
                assertThat(executor.getLockValidator().isLocked(id, context.getMatchedMethod().toString())).isTrue();
                doAnswer(call -> {
                    assertThat(executor.getLockValidator().isLocked(id, context.getMatchedMethod().toString())).isFalse();
                    throw new IllegalStateException("reply service unavailable");
                }).when(CommandRuntime.messages()).send(any(), any(Text.class));
                return ValidationResult.failure(Text.of(CommandKeys.COOLDOWN), "test refusal");
            }
        });
        assertThatThrownBy(() -> CommandTestSupport.dispatch(executor, sender, null, "probe", new String[] {"locked"}))
                .isInstanceOf(IllegalStateException.class).hasMessage("reply service unavailable");
    }

    static class Fixture extends BaseCommandExecutor {
        @CmdMapping(format = "boom")
        public void boom() { throw new IllegalStateException("command body failed"); }
        @CmdMapping(format = "locked")
        @UsageLimit(UsageLimit.LimitType.SENDER)
        public void locked() { throw new IllegalStateException("a refused command must not run"); }
        @Override protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
    }
}
