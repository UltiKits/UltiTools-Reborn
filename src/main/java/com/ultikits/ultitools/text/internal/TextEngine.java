package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Framework-owned registry and rendering engine. */
@ApiStatus.Internal
public final class TextEngine implements AutoCloseable {
    private final ConcurrentMap<String, Published> published = new ConcurrentHashMap<>();
    private final LocaleResolver resolver;
    private final MessageRenderer renderer;
    private final UltiToolsTranslator translator;
    private final CatalogueLoader loader = new CatalogueLoader();
    private final Logger logger;

    /**
     * A read-only preparation result: constructing it neither registers nor writes anything.
     * @param pluginName the unnormalized module name, used for namespace conflict diagnostics
     * @param codeSource the module's own resource source, never a shared-loader lookup
     * @param overrideDirectory the operator-owned messages directory
     * @param loaded parsed shipped resources and deferred diagnostics
     */
    @ApiStatus.Internal
    public record Prepared(String pluginName, @Nullable URL codeSource, Path overrideDirectory,
            CatalogueLoader.Loaded loaded) { }

    /** Creates an engine over the current locale configuration. */
    public TextEngine(LocaleResolver resolver, Logger logger) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.renderer = new MessageRenderer(this::catalogue, resolver, logger);
        this.translator = new UltiToolsTranslator(this, renderer);
        GlobalTranslator.translator().addSource(translator);
    }

    /** Creates and publishes the framework catalogue at startup. */
    public TextEngine(LocaleResolver resolver, Logger logger, @Nullable URL codeSource, Path overrideDirectory) {
        this(resolver, logger);
        CatalogueLoader.Loaded loaded = loader.withOverrides(
                loader.load(MessageKey.FRAMEWORK_NAMESPACE, Locale.ENGLISH, codeSource), overrideDirectory);
        publish(new Prepared("UltiTools framework", codeSource, overrideDirectory, loaded), this);
    }

    UltiToolsTranslator translator() { return translator; }

    /** Returns the current fallback locale for the retained legacy-language seam. */
    public Locale fallback() { return resolver.fallback(); }

    /** Refreshes the startup/configuration snapshot and reports why its fallback was chosen. */
    public void reloadLocaleConfiguration() {
        if (resolver instanceof ConfiguredLocaleResolver configured) {
            configured.reload();
            Locale fallback = configured.fallback();
            Text selection = configured.automaticZone().<Text>map(zone -> Text.of(FrameworkKeys.FALLBACK_TIME_ZONE,
                    Arg.string("language", fallback.toLanguageTag()), Arg.string("zone", zone.getId())))
                    .orElseGet(() -> Text.of(FrameworkKeys.FALLBACK_CONFIGURED,
                            Arg.string("language", fallback.toLanguageTag())));
            logger.info(PlainTextComponentSerializer.plainText().serialize(renderer.render(selection, fallback)));
        }
    }

    /** Returns a namespace-bound view; lookups always use the latest published snapshot. */
    public Messages messagesFor(String namespace) {
        return new ModuleMessages(namespace, this, renderer, resolver);
    }

    /** Parses a module's resources without logging, publishing or changing the filesystem. */
    public Prepared prepare(String namespace, String pluginName, @Nullable URL codeSource,
            Path overrideDirectory, Locale sourceLocale) {
        if (MessageKey.FRAMEWORK_NAMESPACE.equals(namespace)) {
            throw conflict(namespace, pluginName, "UltiTools framework");
        }
        checkNamespace(namespace, pluginName);
        CatalogueLoader.Loaded loaded = loader.load(namespace, sourceLocale, codeSource);
        if (!loaded.catalogue().hasLocale(sourceLocale)) {
            List<String> diagnostics = new ArrayList<>(loaded.diagnostics());
            diagnostics.add("Module '" + pluginName + "' has no shipped messages catalogue for source language '"
                    + sourceLocale.toLanguageTag() + "'");
            loaded = new CatalogueLoader.Loaded(loaded.catalogue(), diagnostics, loaded.shippedFiles());
        }
        return new Prepared(pluginName, codeSource, overrideDirectory, loader.withOverrides(loaded, overrideDirectory));
    }

    /** Prepares resources using plugin.yml's optional source-language, defaulting to English. */
    public Prepared prepare(String namespace, String pluginName, @Nullable URL codeSource,
            Path overrideDirectory, @Nullable String sourceLanguage) {
        Locale source = sourceLanguage == null ? Locale.ENGLISH
                : LocaleNames.parse(sourceLanguage).orElse(Locale.ENGLISH);
        Prepared prepared = prepare(namespace, pluginName, codeSource, overrideDirectory, source);
        if (sourceLanguage == null || LocaleNames.parse(sourceLanguage).isPresent()) {
            return prepared;
        }
        List<String> diagnostics = new ArrayList<>(prepared.loaded().diagnostics());
        diagnostics.add("Module '" + pluginName + "' has invalid source-language '" + sourceLanguage + "'; using en");
        return new Prepared(pluginName, codeSource, overrideDirectory, new CatalogueLoader.Loaded(
                prepared.loaded().catalogue(), diagnostics, prepared.loaded().shippedFiles()));
    }

    /** Publishes accepted module resources, retaining the prior owner until activation succeeds or rolls back. */
    public synchronized void publish(Prepared prepared, Object owner) {
        String namespace = prepared.loaded().catalogue().namespace();
        checkNamespace(namespace, prepared.pluginName());
        Published current = published.get(namespace);
        if (current != null && ownedBy(current, owner)) {
            return;
        }
        published.put(namespace, new Published(Objects.requireNonNull(owner, "owner"), prepared.pluginName(),
                prepared.loaded().catalogue(), prepared, current));
        prepared.loaded().diagnostics().forEach(logger::warning);
        loader.writeReferences(prepared.loaded(), prepared.overrideDirectory()).forEach(logger::warning);
    }

    /** Atomically publishes a prebuilt immutable catalogue, used by framework-internal fixtures. */
    public synchronized void publish(Catalogue catalogue, Object owner) {
        Published current = published.get(catalogue.namespace());
        Published previous = current != null && ownedBy(current, owner) ? current.previous() : current;
        published.put(catalogue.namespace(), new Published(Objects.requireNonNull(owner, "owner"),
                catalogue.namespace(), catalogue, null, previous));
    }

    /** Withdraws an unloading owner, including any retained snapshot of a successfully superseded copy. */
    public synchronized void withdraw(String namespace, Object owner) {
        published.computeIfPresent(namespace, (key, current) -> ownedBy(current, owner) ? null
                : current.withPrevious(removePreviousOwner(current.previous(), owner)));
    }

    /** Restores the prior catalogue only while the failed instance still owns the current publication. */
    public synchronized void rollback(String namespace, Object owner) {
        published.computeIfPresent(namespace, (key, current) -> ownedBy(current, owner) ? current.previous() : current);
    }

    private static boolean ownedBy(Published current, Object owner) {
        return current.owner() == owner; // NOPMD - Only the publishing instance owns this registration.
    }

    private static @Nullable Published removePreviousOwner(@Nullable Published current, Object owner) {
        if (current == null) {
            return null;
        }
        if (ownedBy(current, owner)) {
            return removePreviousOwner(current.previous(), owner);
        }
        return current.withPrevious(removePreviousOwner(current.previous(), owner));
    }

    /** Reloads a prepared catalogue by replacing the entire immutable snapshot. */
    public synchronized void reloadOverrides(String namespace) {
        Published current = published.get(namespace);
        if (current == null || current.prepared() == null) {
            return;
        }
        Prepared prepared = current.prepared();
        CatalogueLoader.Loaded loaded = loader.withOverrides(
                loader.load(namespace, current.catalogue().sourceLocale(), prepared.codeSource()), prepared.overrideDirectory());
        published.put(namespace, new Published(current.owner(), current.pluginName(), loaded.catalogue(), prepared,
                current.previous()));
        loaded.diagnostics().forEach(logger::warning);
    }

    @Nullable Catalogue catalogue(String namespace) {
        Published current = published.get(namespace);
        return current == null ? null : current.catalogue();
    }

    private void checkNamespace(String namespace, String pluginName) {
        Published current = published.get(namespace);
        if (current != null && !current.pluginName().equals(pluginName)) {
            throw conflict(namespace, pluginName, current.pluginName());
        }
    }

    private static PluginModuleException conflict(String namespace, String pluginName, String existing) {
        return new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                "Module '" + pluginName + "' and '" + existing + "' share message namespace '" + namespace + "'");
    }

    @Override
    @PreDestroy
    public void close() {
        GlobalTranslator.translator().removeSource(translator);
        published.clear();
    }

    private record Published(Object owner, String pluginName, Catalogue catalogue, @Nullable Prepared prepared,
            @Nullable Published previous) {
        Published withPrevious(@Nullable Published snapshot) {
            return new Published(owner, pluginName, catalogue, prepared, snapshot);
        }
    }
}
