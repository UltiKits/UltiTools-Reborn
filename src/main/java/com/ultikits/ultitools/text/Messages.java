package com.ultikits.ultitools.text;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * Thread-safe message rendering, callable from any thread. Modules and external plugins with
 * bundled catalogues receive a view bound to their own namespace. External plugins without a
 * bundled catalogue inherit the framework instance without a namespace guarantee. All views can
 * render explicit keys from any published namespace.
 *
 * <p>Lookup order: requested exactly, requested language; fallback() exactly, fallback() language;
 * source language exactly, source language's language; then the key itself. Each candidate is tried
 * exactly and then by language only; a candidate equal to an earlier one is skipped.
 * An absent catalogue or key never throws and logs one WARNING per (key, requested locale) pair.
 * For argument formatting syntax: see {@link Arg}.
 */
@ApiStatus.NonExtendable
public interface Messages {
    /** Returns this instance's module namespace, or {@code ultitools} for the framework. */
    String namespace();

    /** Returns {@code MessageKey.of(namespace(), path)}. */
    MessageKey key(String path);

    /** Renders in the requested locale; never throws for a missing key or translation. */
    Component render(Text text, Locale locale);

    /** Renders using the viewer's {@link LocaleResolver#resolve(Audience) resolved locale}. */
    Component render(Text text, Audience viewer);

    /** Shorthand for {@code render(Text.of(key, args), locale)}. */
    default Component render(MessageKey key, Locale locale, Arg... args) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(args, "args");
        return render(Text.of(key, args), locale);
    }

    /** Shorthand for {@code render(Text.of(key, args), viewer)}. */
    default Component render(MessageKey key, Audience viewer, Arg... args) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(args, "args");
        return render(Text.of(key, args), viewer);
    }

    /**
     * Expands a forwarding audience with {@link Audience#forEachAudience(java.util.function.Consumer)}
     * and invokes the action once per leaf recipient with text rendered in that recipient's locale.
     * Never invokes the action for the forwarding audience itself.
     * Expanding the server audience is safe from any thread: Paper iterates a copy-on-write snapshot
     * of the online players, so a player who joins during the call may be skipped and one who quits
     * during the call may still be passed to the action, where sending is a no-op.
     */
    void forEachRecipient(Audience audience, Text text, BiConsumer<? super Audience, ? super Component> action);

    /** Sends chat text in each recipient's resolved locale. */
    default void send(Audience audience, Text text) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(text, "text");
        forEachRecipient(audience, text, Audience::sendMessage);
    }

    /** Shorthand for {@code send(audience, Text.of(key, args))}. */
    default void send(Audience audience, MessageKey key, Arg... args) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(args, "args");
        send(audience, Text.of(key, args));
    }

    /** Sends action-bar text in each recipient's resolved locale. */
    default void sendActionBar(Audience audience, Text text) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(text, "text");
        forEachRecipient(audience, text, Audience::sendActionBar);
    }

    /**
     * Returns text for a shared object (entity name, text display or shared boss bar).
     * In auto mode a translated text becomes a translatable component for Paper's per-client
     * rendering; in forced mode it is rendered server-side in the forced locale. Per-player
     * overrides cannot apply to shared objects. Literal text remains literal.
     * In auto mode the translatable component's fallback is the plain text rendered under
     * {@link LocaleResolver#fallback()}, with arguments formatted in that fallback locale.
     */
    Component shared(Text text);

    /** Returns whether the exact locale or its language defines the key, without fallback. */
    boolean isDefined(MessageKey key, Locale locale);
}
