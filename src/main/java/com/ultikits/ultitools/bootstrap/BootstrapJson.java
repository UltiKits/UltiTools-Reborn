package com.ultikits.ultitools.bootstrap;

import java.io.IOException;
import java.io.Reader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Reads one complete JSON object, without Gson's lenient parser mode. */
final class BootstrapJson {
    private static final TypeAdapter<JsonElement> ELEMENT = new Gson().getAdapter(JsonElement.class);

    private BootstrapJson() {
        // Static startup file reader only.
    }

    static JsonObject readObject(Reader input) throws IOException {
        JsonReader reader = new JsonReader(input);
        JsonElement value = ELEMENT.read(reader);
        if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("expected one JSON object");
        }
        return value.getAsJsonObject();
    }
}
