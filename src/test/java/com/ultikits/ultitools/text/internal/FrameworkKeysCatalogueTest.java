package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.UltiToolsKeys;
import com.ultikits.ultitools.abstracts.command.CommandKeys;
import com.ultikits.ultitools.bootstrap.BootstrapContentKeys;
import com.ultikits.ultitools.commands.CoreCommandKeys;
import com.ultikits.ultitools.handler.HandlerKeys;
import com.ultikits.ultitools.interfaces.impl.data.DataKeys;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.services.impl.ServiceKeys;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.UtilsKeys;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FrameworkKeysCatalogueTest {
    @Test
    void everyFrameworkConstant_existsInBothShippedCatalogues() throws Exception {
        for (Class<?> type : List.of(FrameworkKeys.class, CommandKeys.class, UltiToolsKeys.class,
                ManagerKeys.class, HandlerKeys.class, ServiceKeys.class, DataKeys.class,
                UtilsKeys.class, CoreCommandKeys.class, BootstrapContentKeys.class)) {
            assertKeysPresent(constants(type));
        }
    }

    @Test
    void everyUpmConstant_existsInBothShippedCatalogues() throws Exception {
        assertKeysPresent(constants(com.ultikits.ultitools.utils.UpmKeys.class));
    }

    @Test
    void legacyTextMigrationKeysExistInBothCatalogues() {
        assertKeysPresent(List.of(
                MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.module_done"),
                MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.module_failed"),
                MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.language_pending"),
                MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "startup.legacy_lang_folders"),
                MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "startup.legacy_lang_folders_more")));
    }

    @ParameterizedTest
    @CsvSource({"en, <locale>", "zh, <语言>"})
    void legacyFolderNoticeDisplaysTheLocalePlaceholderLiterally(String language, String placeholder) {
        Locale locale = Locale.forLanguageTag(language);
        try (var engine = new TextEngine(new FixedLocaleResolver(locale, Optional.empty()), Logger.getAnonymousLogger())) {
            var catalogue = new CatalogueLoader().load(MessageKey.FRAMEWORK_NAMESPACE, Locale.ENGLISH,
                    FrameworkKeys.class.getProtectionDomain().getCodeSource().getLocation()).catalogue();
            engine.publish(catalogue, new Object());
            var messages = engine.messagesFor(MessageKey.FRAMEWORK_NAMESPACE);
            var notice = Text.of(messages.key("startup.legacy_lang_folders"), Arg.string("folders", "lang/"));
            assertThat(PlainTextComponentSerializer.plainText().serialize(messages.render(notice, locale)))
                    .contains("lang/", "messages/" + placeholder + ".yml");
        }
    }

    @Test
    void positiveControl_detectsAMissingFixtureKey() throws Exception {
        assertThatThrownBy(() -> assertKeysPresent(constants(MissingFixture.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("ultitools:test.missing");
    }

    static void assertKeysPresent(Collection<MessageKey> keys) {
        Catalogue catalogue = new CatalogueLoader().load(MessageKey.FRAMEWORK_NAMESPACE, Locale.ENGLISH,
                FrameworkKeys.class.getProtectionDomain().getCodeSource().getLocation()).catalogue();
        assertThat(catalogue.hasLocale(Locale.ENGLISH)).isTrue();
        assertThat(catalogue.hasLocale(Locale.CHINESE)).isTrue();
        for (MessageKey key : keys) {
            assertThat(catalogue.isDefined(key.path(), Locale.ENGLISH)).as("en: %s", key.translationKey()).isTrue();
            assertThat(catalogue.isDefined(key.path(), Locale.CHINESE)).as("zh: %s", key.translationKey()).isTrue();
        }
    }

    private static List<MessageKey> constants(Class<?> type) throws Exception {
        List<MessageKey> keys = new ArrayList<>();
        for (var field : type.getFields()) {
            if (field.getType().equals(MessageKey.class) && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())) {
                keys.add((MessageKey) field.get(null));
            }
        }
        assertThat(keys).as("positive control: actually inspect message constants").isNotEmpty();
        return keys;
    }

    public static final class MissingFixture {
        public static final MessageKey MISSING = MessageKey.of("ultitools", "test.missing");
        private MissingFixture() {
            // Deliberately missing key verifies that the catalogue guard fails closed.
        }
    }
}
