package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.Catalogue;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies single-viewer titles with the production text engine rather than its in-memory substitute. */
class ChestTextEngineIntegrationTest extends ChestTestFixture {
    private String language = "auto";
    private TextEngine textEngine;

    @Override Messages createMessages() {
        Logger logger = Logger.getAnonymousLogger();
        textEngine = new TextEngine(new ConfiguredLocaleResolver(() -> language, () -> "en", logger), logger);
        textEngine.publish(new Catalogue.Builder("chest-test", Locale.ENGLISH)
                .put(Locale.ENGLISH, "title.first", "Chest")
                .put(Locale.ENGLISH, "title.second", "Updated")
                .put(Locale.FRENCH, "title.first", "Coffre")
                .put(Locale.FRENCH, "title.second", "Nouveau").build(), this);
        return textEngine.messagesFor("chest-test");
    }

    @Override @AfterEach void disposeChest() {
        try {
            super.disposeChest();
        } finally {
            if (textEngine != null) { textEngine.close(); }
        }
    }

    @ParameterizedTest
    @CsvSource({"en-US,Chest", "fr-FR,Coffre"})
    void openingResolvesTheViewersLocaleInsteadOfTheFallback(String locale, String expected) {
        player.setLocale(Locale.forLanguageTag(locale));
        GridSession session = open(titled("title.first"));
        assertThat(player.getOpenInventory().title()).isEqualTo(Component.text(expected));
        assertThat(session.isOpen()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"en-US,fr-FR,Nouveau", "fr-FR,en-US,Updated"})
    void titleUpdatesResolveTheViewersCurrentLocale(String original, String updated, String expected) {
        player.setLocale(Locale.forLanguageTag(original));
        GridSession session = open(titled("title.first"));
        player.setLocale(Locale.forLanguageTag(updated));
        session.update(titled("title.second"));
        tick();
        tick();
        assertThat(player.getOpenInventory().title()).isEqualTo(Component.text(expected));
        assertThat(session.result().toCompletableFuture()).isNotDone();
    }

    @Test void forcedLanguageAppliesToBothOpenAndTitleUpdates() {
        language = "en";
        player.setLocale(Locale.FRENCH);
        GridSession session = open(titled("title.first"));
        assertThat(player.getOpenInventory().title()).isEqualTo(Component.text("Chest"));
        session.update(titled("title.second"));
        tick();
        tick();
        assertThat(player.getOpenInventory().title()).isEqualTo(Component.text("Updated"));
        assertThat(session.isOpen()).isTrue();
    }

    private Grid titled(String path) {
        return Grid.builder(Text.of(messages.key(path)), 1).build();
    }
}
