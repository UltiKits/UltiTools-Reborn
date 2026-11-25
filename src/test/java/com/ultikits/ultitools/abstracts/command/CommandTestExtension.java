package com.ultikits.ultitools.abstracts.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Answers;

/** Supplies the new root services to older tests that stand up only a command executor. */
public final class CommandTestExtension implements BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace STORE = ExtensionContext.Namespace.create(CommandTestExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        var messages = new CommandMessageTestSupport("zh");
        Scheduler scheduler = mock(Scheduler.class);
        TaskContext global = mock(TaskContext.class);
        TaskContext async = mock(TaskContext.class, call -> {
            // Mockito's any(Runnable.class) is null while when(...).thenAnswer(...) is installed.
            // Actual command submissions are non-null and must be explicitly captured or executed.
            if (call.getArguments().length > 0 && call.getArgument(0) instanceof Runnable) {
                throw new AssertionError("unstubbed async submission: capture or run the command body explicitly");
            }
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        TaskContext entity = mock(TaskContext.class);
        lenient().when(scheduler.global()).thenReturn(global);
        lenient().when(scheduler.async()).thenReturn(async);
        lenient().when(scheduler.entity(any())).thenReturn(entity);
        lenient().when(global.isCurrentThread()).thenReturn(true);
        context.getStore(STORE).put("messages", messages);
        context.getStore(STORE).put("scope", CommandRuntime.override(messages.messages(), scheduler));
        context.getStore(STORE).put("timing", CommandRuntime.overrideWaitBudgets(CommandWaitBudget::new));
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        AutoCloseable scope = context.getStore(STORE).remove("scope", AutoCloseable.class);
        CommandMessageTestSupport messages = context.getStore(STORE).remove("messages", CommandMessageTestSupport.class);
        try {
            context.getStore(STORE).remove("timing", AutoCloseable.class).close();
            scope.close();
        } finally {
            messages.close();
        }
    }
}
