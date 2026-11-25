package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.UltiToolsKeys;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrameworkMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void plain_rendersTheShippedCatalogueInTheFallbackLanguage(String language) throws Exception {
        Locale locale = Locale.forLanguageTag(language);
        var resolver = new FixedLocaleResolver(locale, Optional.empty());
        try (TextEngine engine = new TextEngine(resolver, Logger.getAnonymousLogger())) {
            Catalogue catalogue = new CatalogueLoader().load(MessageKey.FRAMEWORK_NAMESPACE, Locale.ENGLISH,
                    FrameworkKeys.class.getProtectionDomain().getCodeSource().getLocation()).catalogue();
            engine.publish(catalogue, new Object());
            Messages messages = engine.messagesFor(MessageKey.FRAMEWORK_NAMESPACE);
            try (var scope = FrameworkMessages.overrideForTests(messages, locale)) {
                assertThat(FrameworkMessages.messages()).isSameAs(messages);
                assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID, Arg.string("uuid", "test-uuid")))
                        .isEqualTo("en".equals(language) ? "Server UUID: test-uuid" : "服务器UUID: test-uuid");
            }
        }
    }

    @Test
    void unavailableFramework_returnsTheKeyWithoutThrowing() {
        try (var singleton = mockStatic(UltiTools.class)) {
            singleton.when(UltiTools::getInstance).thenReturn(null);
            assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID))
                    .isEqualTo(UltiToolsKeys.STARTUP_SERVER_UUID.toString());
            assertThatThrownBy(FrameworkMessages::messages).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void nestedScopes_restoreBothLookupsAndThenTheUnavailableFramework() throws Exception {
        Messages outer = mock(Messages.class);
        Messages inner = mock(Messages.class);
        Text text = Text.of(UltiToolsKeys.STARTUP_SERVER_UUID);
        when(outer.render(text, Locale.ENGLISH)).thenReturn(Component.text("outer"));
        when(inner.render(text, Locale.CHINESE)).thenReturn(Component.text("inner"));
        try (var singleton = mockStatic(UltiTools.class)) {
            singleton.when(UltiTools::getInstance).thenReturn(null);
            try (var outerScope = FrameworkMessages.overrideForTests(outer, Locale.ENGLISH)) {
                assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID)).isEqualTo("outer");
                try (var innerScope = FrameworkMessages.overrideForTests(inner, Locale.CHINESE)) {
                    assertThat(FrameworkMessages.messages()).isSameAs(inner);
                    assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID)).isEqualTo("inner");
                }
                assertThat(FrameworkMessages.messages()).isSameAs(outer);
                assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID)).isEqualTo("outer");
            }
            assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID))
                    .isEqualTo(UltiToolsKeys.STARTUP_SERVER_UUID.toString());
        }
    }

    @Test
    void renderingFailure_returnsTheKey() throws Exception {
        Messages messages = mock(Messages.class);
        when(messages.render(any(Text.class), any(Locale.class))).thenThrow(new IllegalStateException("not ready"));
        try (var scope = FrameworkMessages.overrideForTests(messages, Locale.ENGLISH)) {
            assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID))
                    .isEqualTo(UltiToolsKeys.STARTUP_SERVER_UUID.toString());
        }
    }

    @Test
    void rootContainer_suppliesMessagesAndFallbackAndStripsColour() {
        UltiTools plugin = mock(UltiTools.class);
        DependenceManagers managers = mock(DependenceManagers.class);
        Messages messages = mock(Messages.class);
        when(plugin.getDependenceManagers()).thenReturn(managers);
        when(messages.render(any(Text.class), any(Locale.class)))
                .thenReturn(Component.text("root", NamedTextColor.GREEN));
        SimpleContainer root = new SimpleContainer();
        try (var singleton = mockStatic(UltiTools.class)) {
            when(managers.getContext()).thenReturn(root);
            root.registerType(Messages.class, messages);
            root.registerType(LocaleResolver.class, new FixedLocaleResolver(Locale.CHINESE, Optional.empty()));
            singleton.when(UltiTools::getInstance).thenReturn(plugin);
            assertThat(FrameworkMessages.messages()).isSameAs(messages);
            assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID)).isEqualTo("root");
            verify(messages).render(Text.of(UltiToolsKeys.STARTUP_SERVER_UUID), Locale.CHINESE);
        } finally {
            root.close();
        }
    }

    @Test
    void missingManagers_returnsTheKeyAndMessagesExplainsUnavailability() {
        UltiTools plugin = mock(UltiTools.class);
        try (var singleton = mockStatic(UltiTools.class)) {
            singleton.when(UltiTools::getInstance).thenReturn(plugin);
            assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID))
                    .isEqualTo(UltiToolsKeys.STARTUP_SERVER_UUID.toString());
            assertThatThrownBy(FrameworkMessages::messages).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void brokenContainer_returnsTheKeyAndWrapsMessageLookupFailure() {
        UltiTools plugin = mock(UltiTools.class);
        DependenceManagers managers = mock(DependenceManagers.class);
        IllegalArgumentException failure = new IllegalArgumentException("closed");
        when(plugin.getDependenceManagers()).thenReturn(managers);
        when(managers.getContext()).thenThrow(failure);
        try (var singleton = mockStatic(UltiTools.class)) {
            singleton.when(UltiTools::getInstance).thenReturn(plugin);
            assertThat(FrameworkMessages.plain(UltiToolsKeys.STARTUP_SERVER_UUID))
                    .isEqualTo(UltiToolsKeys.STARTUP_SERVER_UUID.toString());
            assertThatThrownBy(FrameworkMessages::messages)
                    .isInstanceOf(IllegalStateException.class).hasCause(failure);
        }
    }
}
