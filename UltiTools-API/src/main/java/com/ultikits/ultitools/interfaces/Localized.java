package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.entities.Language;

import java.util.ArrayList;
import java.util.List;

public interface Localized {
    /**
     * Gets the language codes a plugin supported.
     * <p></p>
     * Check <a href="https://en.wikipedia.org/wiki/IETF_language_tag">language code list</a>
     *
     * @return Supported language codes
     */
    default List<String> supported() {
        return new ArrayList<>();
    }

    /**
     * Returns a localized string that indicated by user. If the chosen language code is not
     * presented in {@link #supported()}, this method will return the text by default.
     *
     * @param str default display text
     * @return A localized string, or the default text if the localization is not supported
     */
    default String i18n(String code, String str) {
        return str;
    }
}
