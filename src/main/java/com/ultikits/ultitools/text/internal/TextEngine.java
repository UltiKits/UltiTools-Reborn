package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Framework-owned registry and rendering engine. */
@ApiStatus.Internal
public final class TextEngine implements AutoCloseable {
    private final ConcurrentMap<String, Published> published = new ConcurrentHashMap<>();
    private final LocaleResolver resolver;
    private final MessageRenderer renderer;

    /** Creates an engine over the current locale configuration. */
    public TextEngine(LocaleResolver resolver, Logger logger) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.renderer = new MessageRenderer(this::catalogue, resolver, Objects.requireNonNull(logger, "logger"));
    }

    /** Returns a namespace-bound view; lookups always use the latest published snapshot. */
    public Messages messagesFor(String namespace) {
        return new ModuleMessages(namespace, this, renderer, resolver);
    }

    /** Atomically publishes an immutable catalogue for its concrete owning instance. */
    public void publish(Catalogue catalogue, Object owner) {
        published.put(catalogue.namespace(), new Published(Objects.requireNonNull(owner, "owner"), catalogue));
    }

    @Nullable Catalogue catalogue(String namespace) {
        Published current = published.get(namespace);
        return current == null ? null : current.catalogue();
    }

    @Override
    @PreDestroy
    public void close() {
        published.clear();
    }

    private record Published(Object owner, Catalogue catalogue) { }
}
