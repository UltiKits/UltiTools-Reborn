package com.ultikits.ultitools.utils;

import java.util.IllegalFormatException;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;

/**
 * Resolves the framework's own operator-, player-, panel- and e-mail-facing text through the
 * language catalogue ({@code lang/en.json}, {@code lang/zh.json}), so it follows the server's
 * {@code language} setting.
 * <p>
 * By the catalogue's convention the key is the Chinese source text, {@code zh.json} maps it to
 * itself and {@code en.json} gives the English. A text that has no entry in the active catalogue is
 * returned unchanged, which is why a key is never a placeholder that could leak: it is always a
 * readable sentence.
 * <p>
 * <b>Never fails the caller.</b> These calls sit in diagnostic and failure paths (a log line, an error
 * reply), so a missing plugin instance (a unit test, or a call before the language is loaded) or a
 * malformed translation must not turn the original problem into a second one. In both cases the key
 * itself is used.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class FrameworkText {

    private FrameworkText() {
    }

    /**
     * Returns the text for {@code key} in the server's language.
     *
     * @param key the catalogue key (the Chinese source text)
     * @return the translation, or {@code key} itself when there is none or the catalogue is unavailable
     */
    // PMD.AvoidCatchingGenericException: the plugin may exist while its language is not loaded yet
    // (i18n then throws a NullPointerException), and a diagnostic line must still be written.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static String text(String key) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return key;
            }
            String localized = instance.i18n(key);
            return localized == null ? key : localized;
        } catch (RuntimeException e) {
            return key;
        }
    }

    /**
     * Returns {@code key} in the server's language with {@code args} applied as
     * {@link String#format(String, Object...)} would.
     * <p>
     * The translation must carry the same placeholders, in the same order, as the key; the catalogue
     * invariant test enforces that. If a translation nevertheless fails to format (an operator's edited
     * language file, say), the key is formatted instead, so the line is still written.
     *
     * @param key  the catalogue key (the Chinese source text), holding the format specifiers
     * @param args the format arguments
     * @return the formatted text
     */
    public static String format(String key, Object... args) {
        try {
            return String.format(text(key), args);
        } catch (IllegalFormatException e) {
            return String.format(key, args);
        }
    }
}
