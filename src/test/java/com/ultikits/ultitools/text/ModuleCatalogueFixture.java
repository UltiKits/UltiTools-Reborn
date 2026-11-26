package com.ultikits.ultitools.text;

import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.ModuleTexts;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Real module catalogue resources for tests that do not need to construct a module. */
public final class ModuleCatalogueFixture implements AutoCloseable {
    public static final String MODULE = "FixtureModule";
    private final Path codeSource;
    private final Path overrides;
    private final ConfiguredLocaleResolver resolver;
    private final TextEngine engine;
    private String fallback = "en";

    public ModuleCatalogueFixture(Path root) {
        codeSource = root.resolve("shipped");
        overrides = root.resolve("overrides");
        Logger logger = Logger.getAnonymousLogger();
        resolver = new ConfiguredLocaleResolver(() -> "auto", () -> fallback, logger);
        engine = new TextEngine(resolver, logger);
    }

    public void fallback(String language) {
        fallback = language;
        resolver.reload();
    }

    public void shipped(String language, Map<String, String> templates) throws IOException {
        Path directory = Files.createDirectories(codeSource.resolve("messages"));
        DumperOptions options = new DumperOptions();
        options.setNonPrintableStyle(DumperOptions.NonPrintableStyle.ESCAPE);
        Files.writeString(directory.resolve(language + ".yml"), new Yaml(options).dump(templates));
    }

    public ModuleTexts preparedTexts() throws IOException {
        var prepared = engine.prepare(MessageKey.namespaceFor(MODULE), MODULE, codeSource.toUri().toURL(), overrides, "en");
        return new ModuleTexts(engine, prepared);
    }

    @Override
    public void close() {
        engine.close();
    }
}
