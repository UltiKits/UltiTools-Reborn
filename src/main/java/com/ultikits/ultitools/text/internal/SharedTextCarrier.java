package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.VirtualComponentRenderer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Carries the entire deferred text through Adventure without a second argument formatter.
 * @param text the original translated text and immutable arguments
 * @param fallbackString plain text for consumers that do not use the framework translator
 */
@ApiStatus.Internal
public record SharedTextCarrier(Text.Translated text, String fallbackString) implements VirtualComponentRenderer<Void> {
    @Override
    public Component apply(@Nullable Void context) {
        return Component.text(fallbackString);
    }
}
