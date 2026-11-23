package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.text.MessageFormat;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.VirtualComponent;
import net.kyori.adventure.translation.Translator;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One framework translation source, backed by the live namespace registry. */
@ApiStatus.Internal
public final class UltiToolsTranslator implements Translator {
    private final TextEngine engine;
    private final MessageRenderer renderer;

    UltiToolsTranslator(TextEngine engine, MessageRenderer renderer) {
        this.engine = engine;
        this.renderer = renderer;
    }

    @Override public Key name() { return Key.key("ultitools", "messages"); }

    @Override
    public boolean canTranslate(String key, Locale locale) {
        return knownKey(key) != null;
    }

    @Override
    public @Nullable MessageFormat translate(String key, Locale locale) {
        return null;
    }

    @Override
    public @Nullable Component translate(TranslatableComponent component, Locale locale) {
        MessageKey key = knownKey(component.key());
        if (key == null) {
            return null;
        }
        Object argument = component.arguments().isEmpty() ? null : component.arguments().getFirst().value();
        Text text = argument instanceof VirtualComponent virtual && virtual.renderer() instanceof SharedTextCarrier carrier
                ? carrier.text() : Text.of(key);
        return renderer.render(text, locale).applyFallbackStyle(component.style()).append(component.children());
    }

    private @Nullable MessageKey knownKey(String value) {
        try {
            MessageKey key = MessageKey.parse(value);
            Catalogue catalogue = engine.catalogue(key.namespace());
            return catalogue != null && catalogue.contains(key.path()) ? key : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
