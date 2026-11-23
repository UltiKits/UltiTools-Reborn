package com.ultikits.ultitools.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MessageKeyTest {
    @Test
    void validKey_roundTripsAndConvertsToAdventureKey() {
        MessageKey key = MessageKey.of("ultiessentials", "home.set");
        assertThat(key.namespace()).isEqualTo("ultiessentials");
        assertThat(key.path()).isEqualTo("home.set");
        assertThat(key.translationKey()).isEqualTo("ultiessentials:home.set");
        assertThat(key.asKey().asString()).isEqualTo(key.translationKey());
        assertThat(MessageKey.parse(key.translationKey())).isEqualTo(key).hasSameHashCodeAs(key);
        assertThat(key.toString()).isEqualTo(key.translationKey());
        assertThat(MessageKey.FRAMEWORK_NAMESPACE).isEqualTo("ultitools");
        assertThat(MessageKey.of("a_.-0", "a_0-b.c").path()).isEqualTo("a_0-b.c");
    }

    @Test
    void invalidParts_areRejected() {
        for (String namespace : new String[] {"UltiEssentials", "a b", "..", "", "a:b", "a".repeat(129)}) {
            assertThatThrownBy(() -> MessageKey.of(namespace, "valid")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String path : new String[] {"Home", "a..b", ".a", "a.", "", "a/b", "a:b", "a".repeat(129)}) {
            assertThatThrownBy(() -> MessageKey.of("x", path)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(MessageKey.of("a".repeat(128), "b".repeat(128)).path()).hasSize(128);
        for (String key : new String[] {"key", "a:b:c", ":b", "a:"}) {
            assertThatThrownBy(() -> MessageKey.parse(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void namespaceFor_usesRootLocaleAndReplacesInvalidCharacters() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(MessageKey.namespaceFor("UltiEssentials")).isEqualTo("ultiessentials");
            assertThat(MessageKey.namespaceFor("My Mod!")).isEqualTo("my_mod_");
            assertThat(MessageKey.namespaceFor("ITEM")).isEqualTo("item");
            assertThat(MessageKey.namespaceFor("A._-9")).isEqualTo("a._-9");
        } finally {
            Locale.setDefault(previous);
        }
        assertThatThrownBy(() -> MessageKey.namespaceFor("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageKey.namespaceFor("..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namespaceFor_overLongModuleName_isRejected() {
        String moduleName = "a".repeat(129);
        assertThatThrownBy(() -> MessageKey.namespaceFor(moduleName))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(moduleName);
        assertThat(MessageKey.namespaceFor("A".repeat(128))).hasSize(128);
    }

    @Test
    void nullArguments_nameTheRejectedParameter() {
        assertThatNullPointerException().isThrownBy(() -> MessageKey.of(null, "a")).withMessage("namespace");
        assertThatNullPointerException().isThrownBy(() -> MessageKey.of("a", null)).withMessage("path");
        assertThatNullPointerException().isThrownBy(() -> MessageKey.parse(null)).withMessage("namespacedKey");
        assertThatNullPointerException().isThrownBy(() -> MessageKey.namespaceFor(null)).withMessage("moduleName");
    }
}
