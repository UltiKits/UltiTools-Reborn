package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.util.Locale;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Real English framework catalogue for existing manager regression fixtures. */
public final class FrameworkMessagesExtension implements BeforeEachCallback, AfterEachCallback {
    private CommandMessageTestSupport support;
    private AutoCloseable scope;

    @Override
    public void beforeEach(ExtensionContext context) {
        support = new CommandMessageTestSupport("en");
        scope = FrameworkMessages.overrideForTests(support.messages(), Locale.ENGLISH);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        try {
            scope.close();
        } finally {
            support.close();
        }
    }
}
