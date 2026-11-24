package com.ultikits.ultitools.abstracts.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;

import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.CatalogueLoader;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/** Real bundled catalogues, with explicit leaf delivery for Mockito CommandSender audiences. */
public final class CommandMessageTestSupport implements AutoCloseable {
    private final TextEngine engine;
    private final Messages messages;

    public CommandMessageTestSupport(String fallback) {
        Logger logger = Logger.getAnonymousLogger();
        var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> fallback, logger);
        engine = new TextEngine(resolver, logger);
        var loaded = new CatalogueLoader().load("ultitools", Locale.ENGLISH,
                CommandKeys.class.getProtectionDomain().getCodeSource().getLocation());
        engine.publish(loaded.catalogue(), this);
        messages = spy(engine.messagesFor("ultitools"));
        lenient().doAnswer(call -> {
            Audience recipient = call.getArgument(0);
            Text text = call.getArgument(1);
            BiConsumer<Audience, Component> action = call.getArgument(2);
            // Older leaf mocks predate Player.locale(); supply only that missing fixture value.
            Component rendered = recipient instanceof Player player && player.locale() == null
                    ? messages.render(text, Locale.forLanguageTag(fallback)) : messages.render(text, recipient);
            action.accept(recipient, rendered);
            return null;
        }).when(messages).forEachRecipient(any(), any(), any());
    }

    public Messages messages() { return messages; }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Override
    public void close() {
        engine.close();
    }
}
