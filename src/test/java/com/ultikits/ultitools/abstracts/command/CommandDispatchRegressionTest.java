package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.command.CmdMapping;

/**
 * Regressions for two dispatch defects that were only ever visible from a running server.
 *
 * @since 6.3.0
 */
@DisplayName("Command dispatch regressions")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // both fixes live behind protected/private members
class CommandDispatchRegressionTest {

    private final Fixture executor = new Fixture();



    @Nested
    @DisplayName("#385 the user sees the cause, never the literal word null")
    class ErrorMessage {

        private String describe(Throwable t) throws Exception {
            Method m = BaseCommandExecutor.class.getDeclaredMethod("describe", Throwable.class);
            m.setAccessible(true);
            return (String) m.invoke(null, t);
        }

        /**
         * Command bodies are invoked reflectively, so anything they throw arrives wrapped in an
         * {@link InvocationTargetException} whose own message is {@code null} by construction. The
         * old code reported that wrapper's message, so <em>every</em> command failure in the
         * framework read {@code "命令执行出错: null"} and the real reason stayed in the server log.
         */
        @Test
        @DisplayName("a cause with a message is reported by that message")
        void causeMessageIsUsed() throws Exception {
            assertThat(describe(new IllegalStateException("world is not loaded")))
                    .isEqualTo("world is not loaded");
        }

        @Test
        @DisplayName("a cause with no message falls back to its class name, never \"null\"")
        void nullMessageFallsBackToClassName() throws Exception {
            assertThat(describe(new NullPointerException()))
                    .isEqualTo("NullPointerException")
                    .isNotEqualTo("null");
        }

        @Test
        @DisplayName("a blank message falls back too")
        void blankMessageFallsBackToClassName() throws Exception {
            assertThat(describe(new IllegalArgumentException("   ")))
                    .isEqualTo("IllegalArgumentException");
        }

        /**
         * The shape the fix actually has to handle: what {@code invoke} throws when a command body
         * fails. Its {@code getMessage()} is null; its cause carries the detail.
         */
        @Test
        @DisplayName("control: an InvocationTargetException's own message really is null")
        void invocationTargetExceptionHasNoMessageOfItsOwn() {
            InvocationTargetException wrapper =
                    new InvocationTargetException(new IllegalStateException("the real reason"));

            assertThat(wrapper.getMessage())
                    .as("if this ever stops being null the premise of #385 has changed")
                    .isNull();
            assertThat(wrapper.getCause().getMessage()).isEqualTo("the real reason");
        }
    }

    /** Minimal concrete executor: these tests reach the binder and the formatter, not dispatch. */
    static class Fixture extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // not exercised here
        }

        @CmdMapping(format = "echo <message...>")
        public void echo(String[] message) {
            // present only so the class has a mapping; never invoked by these tests
        }
    }
}
