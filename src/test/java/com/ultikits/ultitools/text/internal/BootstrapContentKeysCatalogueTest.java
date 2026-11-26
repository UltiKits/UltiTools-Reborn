package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.bootstrap.BootstrapContentKeys;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;

import net.kyori.adventure.text.format.NamedTextColor;

class BootstrapContentKeysCatalogueTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void bothCataloguesRenderSummariesWithoutParsingModuleNamesAsMiniMessage(String language) throws Exception {
        Locale locale = Locale.forLanguageTag(language);
        var resolver = new FixedLocaleResolver(locale, Optional.empty());
        try (var engine = new TextEngine(resolver, Logger.getAnonymousLogger())) {
            Catalogue catalogue = new CatalogueLoader().load(MessageKey.FRAMEWORK_NAMESPACE, Locale.ENGLISH,
                    BootstrapContentKeys.class.getProtectionDomain().getCodeSource().getLocation()).catalogue();
            assertThat(List.of(BootstrapContentKeys.SUMMARY, BootstrapContentKeys.REMOVED, BootstrapContentKeys.DROPPED,
                    BootstrapContentKeys.UNKNOWN, BootstrapContentKeys.LIST, BootstrapContentKeys.CONSEQUENCE_ENCHANTMENT,
                    BootstrapContentKeys.CONSEQUENCE_DATAPACK, BootstrapContentKeys.CONSEQUENCE_TAG,
                    BootstrapContentKeys.WORLDGEN_REFUSED))
                    .allSatisfy(key -> assertThat(catalogue.isDefined(key.path(), locale)).as("%s", key).isTrue());
            engine.publish(catalogue, new Object());
            var messages = engine.messagesFor(MessageKey.FRAMEWORK_NAMESPACE);
            try (var scope = FrameworkMessages.overrideForTests(messages, locale)) {
                assertThat(FrameworkMessages.plain(BootstrapContentKeys.SUMMARY, Arg.string("module", "<blue>Test"),
                        Arg.number("enchantments", 1), Arg.number("tags", 2), Arg.number("datapacks", 1)))
                        .isEqualTo(language.equals("en")
                                ? "Module '<blue>Test' added custom content at this start: 1 enchantment(s), 2 tag change(s), 1 data pack(s)."
                                : "模块 '<blue>Test' 本次启动往游戏里加入了自定义内容：附魔 1 个，标签改动 2 处，数据包 1 个。");
            }
            assertThat(messages.render(Text.of(BootstrapContentKeys.CONSEQUENCE_ENCHANTMENT), locale).color())
                    .isEqualTo(NamedTextColor.RED);
            assertThat(messages.render(Text.of(BootstrapContentKeys.CONSEQUENCE_DATAPACK), locale).color())
                    .isEqualTo(NamedTextColor.RED);
            assertThat(messages.render(Text.of(BootstrapContentKeys.CONSEQUENCE_TAG), locale).color()).isNull();
        }
    }
}
