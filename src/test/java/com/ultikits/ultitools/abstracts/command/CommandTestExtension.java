package com.ultikits.ultitools.abstracts.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Supplies the new root services to older tests that stand up only a command executor. */
public final class CommandTestExtension implements BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace STORE = ExtensionContext.Namespace.create(CommandTestExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        var messages = new CommandMessageTestSupport("zh");
        Scheduler scheduler = mock(Scheduler.class);
        TaskContext global = mock(TaskContext.class);
        TaskContext async = mock(TaskContext.class);
        TaskContext entity = mock(TaskContext.class);
        lenient().when(scheduler.global()).thenReturn(global);
        lenient().when(scheduler.async()).thenReturn(async);
        lenient().when(scheduler.entity(any())).thenReturn(entity);
        lenient().when(global.isCurrentThread()).thenReturn(true);
        context.getStore(STORE).put("messages", messages);
        context.getStore(STORE).put("scope", CommandRuntime.override(messages.messages(), scheduler));
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        AutoCloseable scope = context.getStore(STORE).remove("scope", AutoCloseable.class);
        CommandMessageTestSupport messages = context.getStore(STORE).remove("messages", CommandMessageTestSupport.class);
        try {
            scope.close();
        } finally {
            messages.close();
        }
    }
}
