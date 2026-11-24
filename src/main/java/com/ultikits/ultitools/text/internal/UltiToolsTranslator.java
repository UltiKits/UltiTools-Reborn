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
        Text text = null;
        for (var argument : component.arguments()) {
            if (argument.value() instanceof VirtualComponent virtual
                    && virtual.renderer() instanceof SharedTextCarrier carrier) {
                text = carrier.text();
                break;
            }
        }
        if (text == null && !component.arguments().isEmpty()) {
            // Serialization may replace the virtual carrier with its rendered fallback, losing the original Text.
            return null;
        }
        return renderer.render(text == null ? Text.of(key) : text, locale)
                .applyFallbackStyle(component.style()).append(component.children());
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
