package com.ultikits.ultitools.entities;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Language entity class.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class Language {
    private static final Logger LOGGER = Logger.getLogger(Language.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Map<String, String> dictionary;

    /**
     * Creates a language dictionary by reading a JSON file.
     *
     * @param file the language JSON file
     */
    public Language(File file) {
        Map<String, String> tempDict = Collections.emptyMap();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            tempDict = GSON.fromJson(reader, MAP_TYPE);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load language file: " + file.getPath(), e);
        }
        this.dictionary = tempDict != null ? tempDict : Collections.emptyMap();
    }

    /**
     * Creates directly from a dictionary.
     *
     * @param dictionary the language dictionary
     */
    public Language(Map<String, String> dictionary) {
        this.dictionary = dictionary != null ? dictionary : Collections.emptyMap();
    }

    /**
     * Creates a language dictionary from JSON.
     *
     * @param json the JSON string
     */
    public Language(String json) {
        Map<String, String> tempDict = GSON.fromJson(json, MAP_TYPE);
        this.dictionary = tempDict != null ? tempDict : Collections.emptyMap();
    }

    /**
     * Gets the corresponding language translation.
     *
     * @param str the string to translate
     * @return the translated string, or the original text if not found in the dictionary
     */
    /**
     * Builds a {@code Language} from a YAML language file.
     * <p>
     * Eight of this ecosystem's sixteen modules ship {@code lang/*.yml} rather than
     * {@code lang/*.json}, and until 6.3.0 the loader looked only for {@code .json} and returned an
     * empty dictionary when it found nothing -- silently, so every message in those modules
     * rendered as its own raw key (#389).
     * <p>
     * The YAML is nested and the modules address it with dotted keys
     * ({@code i18n("world.delete.deleting")}), which is exactly the form
     * {@link org.bukkit.configuration.ConfigurationSection#getKeys(boolean) getKeys(true)}
     * produces, so the flattening is the library's own and not a convention invented here.
     * Section nodes are skipped: only leaves that are actually strings become entries.
     *
     * @param reader the YAML source; the caller owns closing it
     * @return a language whose dictionary is the flattened YAML
     * @since 6.3.0
     */
    public static Language fromYaml(Reader reader) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
        Map<String, String> flattened = new LinkedHashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) {
                flattened.put(key, yaml.getString(key));
            }
        }
        return new Language(flattened);
    }

    public String getLocalizedText(String str) {
        return dictionary.getOrDefault(str, str);
    }
}
