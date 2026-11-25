package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned message lookups for console logs and panel text. */
@ApiStatus.Internal
public final class FrameworkMessages {
    private static volatile Services testServices;

    private FrameworkMessages() {
        // Static service lookup only.
    }

    /**
     * Returns the framework's own Messages from the root container.
     * @return the framework message service
     * @throws IllegalStateException when the framework is not running
     */
    public static Messages messages() {
        Services override = testServices;
        if (override != null) {
            return override.messages();
        }
        try {
            Messages messages = rootContext().getBean(Messages.class);
            if (messages != null) {
                return messages;
            }
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("Framework messages are not available", unavailable);
        }
        throw new IllegalStateException("Framework messages are not available");
    }

    /**
     * Renders plain text in the server's fallback language without logging or throwing on unavailability.
     * @param key the framework message key
     * @param args named message arguments
     * @return rendered text, or the key string when framework services or rendering are unavailable
     */
    public static String plain(MessageKey key, Arg... args) {
        try {
            Services override = testServices;
            Messages messages;
            Locale fallback;
            if (override != null) {
                messages = override.messages();
                fallback = override.fallback();
            } else {
                SimpleContainer context = rootContext();
                messages = context.getBean(Messages.class);
                fallback = context.getBean(LocaleResolver.class).fallback();
            }
            return PlainTextComponentSerializer.plainText().serialize(messages.render(Text.of(key, args), fallback));
        } catch (RuntimeException unavailable) {
            // Logging here could recurse through the system log handler.
            return key.toString();
        }
    }

    /**
     * Replaces both lookups until the returned scope is closed; close scopes in reverse order.
     * @param messages the test message service
     * @param fallback the test fallback locale
     * @return a scope restoring both previous lookups
     */
    public static AutoCloseable overrideForTests(Messages messages, Locale fallback) {
        Services previous = testServices;
        testServices = new Services(Objects.requireNonNull(messages, "messages"),
                Objects.requireNonNull(fallback, "fallback"));
        return () -> testServices = previous;
    }

    private static SimpleContainer rootContext() {
        UltiTools plugin = UltiTools.getInstance();
        if (plugin != null && plugin.getDependenceManagers() != null) {
            return plugin.getDependenceManagers().getContext();
        }
        throw new IllegalStateException("Framework messages are not available");
    }

    private record Services(Messages messages, Locale fallback) { }
}
