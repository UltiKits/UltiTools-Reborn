package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.ApiStatus;

/** A lightweight view binding key construction, not rendering, to one module namespace. */
@ApiStatus.Internal
public final class ModuleMessages implements Messages {
    private final String namespace;
    private final TextEngine engine;
    private final MessageRenderer renderer;
    private final LocaleResolver resolver;

    ModuleMessages(String namespace, TextEngine engine, MessageRenderer renderer, LocaleResolver resolver) {
        this.namespace = MessageKey.namespaceFor(Objects.requireNonNull(namespace, "namespace"));
        this.engine = engine;
        this.renderer = renderer;
        this.resolver = resolver;
    }

    @Override public String namespace() { return namespace; }

    @Override public MessageKey key(String path) {
        return MessageKey.of(namespace, Objects.requireNonNull(path, "path"));
    }

    @Override public Component render(Text text, Locale locale) {
        return renderer.render(text, locale);
    }

    @Override public Component render(Text text, Audience viewer) {
        Objects.requireNonNull(text, "text");
        return render(text, resolver.resolve(Objects.requireNonNull(viewer, "viewer")));
    }

    @Override
    public void forEachRecipient(Audience audience, Text text, BiConsumer<? super Audience, ? super Component> action) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(action, "action");
        audience.forEachAudience(recipient -> action.accept(recipient, render(text, recipient)));
    }

    @Override
    public Component shared(Text text) {
        Objects.requireNonNull(text, "text");
        if (text instanceof Text.Literal literal) {
            return literal.component();
        }
        Optional<Locale> forced = resolver.forced();
        if (forced.isPresent()) {
            return render(text, forced.orElseThrow());
        }
        Text.Translated translated = (Text.Translated) text;
        String fallback = PlainTextComponentSerializer.plainText().serialize(render(text, resolver.fallback()));
        return Component.translatable(translated.key().translationKey()).fallback(fallback);
    }

    @Override
    public boolean isDefined(MessageKey key, Locale locale) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Catalogue catalogue = engine.catalogue(key.namespace());
        return catalogue != null && catalogue.isDefined(key.path(), locale);
    }
}
