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
    @DisplayName("#396 empty varargs binds an empty array, not null")
    class EmptyVarargs {

        private Object parse(String[] values, Class<?> type) throws Exception {
            Method m = BaseCommandExecutor.class
                    .getDeclaredMethod("parseParameterValue", String[].class, Class.class);
            m.setAccessible(true);
            return m.invoke(executor, values, type);
        }

        /**
         * The defect: {@code validateParameterCount} deliberately accepts a varargs command with no
         * trailing arguments, {@code parseParameters} hands the binder a zero-length array, and the
         * binder turned that into {@code null}. {@code /friend msg <player>} with no message died
         * in {@code String.join} with a {@code NullPointerException}.
         * <p>
         * {@code BaseCommandExecutorTest} already covers the acceptance half and stops one step
         * short of this one, which is why the defect survived.
         */
        @Test
        @DisplayName("String[] with no values -> zero-length array")
        void emptyStringArrayIsNotNull() throws Exception {
            Object bound = parse(new String[0], String[].class);

            assertThat(bound)
                    .as("a varargs parameter that legitimately received zero values must be handed "
                            + "the empty array; null makes every handler that touches it throw")
                    .isNotNull()
                    .isInstanceOf(String[].class);
            assertThat((String[]) bound).isEmpty();
        }

        @Test
        @DisplayName("null values for an array type -> zero-length array")
        void nullValuesForArrayTypeIsNotNull() throws Exception {
            assertThat((String[]) parse(null, String[].class)).isEmpty();
        }

        @Test
        @DisplayName("component type is preserved, not erased to Object[]")
        void componentTypeIsPreserved() throws Exception {
            Object bound = parse(new String[0], int[].class);

            assertThat(bound).isInstanceOf(int[].class);
            assertThat((int[]) bound).isEmpty();
        }

        @Test
        @DisplayName("control: the String and non-array cases are unchanged")
        void nonArrayBehaviourIsUnchanged() throws Exception {
            // These two were already correct and must stay that way -- without them, returning the
            // empty array for *everything* would also pass the assertions above.
            assertThat(parse(new String[0], String.class)).isEqualTo("");
            assertThat(parse(new String[0], Integer.class)).isNull();
        }
    }

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
