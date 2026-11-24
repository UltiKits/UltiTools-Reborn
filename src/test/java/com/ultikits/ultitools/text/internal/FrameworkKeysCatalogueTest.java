package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.MessageKey;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class FrameworkKeysCatalogueTest {
    @Test
    void everyFrameworkConstant_existsInBothShippedCatalogues() throws Exception {
        assertKeysPresent(constants(FrameworkKeys.class));
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
