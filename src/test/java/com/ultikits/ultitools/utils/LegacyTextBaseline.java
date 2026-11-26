package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads the frozen 6.x text baseline, independent of the current runtime catalogues. */
public final class LegacyTextBaseline {
    private LegacyTextBaseline() {
        // Static test fixture reader.
    }

    public static JsonObject read(Locale locale) throws IOException {
        String resource = "/legacy-text-baseline/" + locale.getLanguage() + ".json";
        try (InputStream stream = LegacyTextBaseline.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing frozen catalogue " + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
