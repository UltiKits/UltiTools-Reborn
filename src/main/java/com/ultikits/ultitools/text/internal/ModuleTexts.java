package com.ultikits.ultitools.text.internal;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Plain, server-fallback text for framework callers that accept a module message path or a literal. */
@ApiStatus.Internal
public final class ModuleTexts {
    // Same path grammar and length limit as MessageKey, checked before attempting a lookup.
    private static final Pattern PATH = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)*");
    private static final MiniMessage TEMPLATES = MiniMessage.builder().tags(TagResolver.standard()).build();
    // Parse the shipped-only snapshot once on demand. A value does not retain its prepared snapshot or module.
    private static final Map<Catalogue, Catalogue> SHIPPED = Collections.synchronizedMap(new WeakHashMap<>());

    private final TextEngine engine;
    private final TextEngine.Prepared prepared;

    /** Binds to a module without publishing its prepared catalogue. */
    public ModuleTexts(TextEngine engine, TextEngine.Prepared prepared) {
        this.engine = engine;
        this.prepared = prepared;
    }

    /**
     * Renders a defined path as plain text, or returns empty for literals, missing entries or rendering failures.
     * The effective catalogue includes operator overrides. During construction only the prepared snapshot is available.
     * @param name the message path or literal
     * @return the rendered text if a template is found in the server fallback or module source locale chain
     */
    public Optional<String> resolve(@Nullable String name) {
        if (!isPath(name)) {
            return Optional.empty();
        }
        try {
            Catalogue candidate = prepared.loaded().catalogue();
            Catalogue published = engine.catalogue(candidate.namespace());
            Catalogue catalogue = published == null ? candidate : published;
            Locale fallback = engine.fallback();
            Locale source = catalogue.sourceLocale();
            // As in MessageRenderer, each exact locale precedes its base language. Do not render missing keys.
            return catalogue.lookup(name, List.of(fallback, Locale.of(fallback.getLanguage()),
                    source, Locale.of(source.getLanguage()))).flatMap(ModuleTexts::plain);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Returns distinct plain texts in language-tag order from the module's bundled catalogues only.
     * @param name the module message path
     * @return framework comment candidates, without any operator override entries
     */
    public List<String> shippedTemplates(String name) {
        if (!isPath(name)) {
            return List.of();
        }
        try {
            Catalogue shipped = SHIPPED.computeIfAbsent(prepared.loaded().catalogue(), catalogue ->
                    new CatalogueLoader().load(catalogue.namespace(), catalogue.sourceLocale(), prepared.codeSource()).catalogue());
            return shipped.templates(name).stream().map(ModuleTexts::plain).flatMap(Optional::stream).distinct().toList();
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private static boolean isPath(@Nullable String name) {
        return name != null && name.length() <= 128 && PATH.matcher(name).matches();
    }

    private static Optional<String> plain(String template) {
        try {
            // These annotations take no arguments; use the same standard tags as the message renderer.
            return Optional.of(PlainTextComponentSerializer.plainText().serialize(TEMPLATES.deserialize(template)));
        } catch (RuntimeException unrenderable) {
            return Optional.empty();
        }
    }
}
