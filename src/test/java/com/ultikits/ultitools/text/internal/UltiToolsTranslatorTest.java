package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UltiToolsTranslatorTest {
    private TextEngine engine;
    private Translator translator;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        engine = new TextEngine(new ConfiguredLocaleResolver(() -> "auto", () -> "zh", logger), logger);
        engine.publish(new Catalogue.Builder("sample", Locale.ENGLISH)
                .put(Locale.ENGLISH, "number", "<n:'#,##0.00'>")
                .put(Locale.ENGLISH, "choice", "<n:choice:'0#no items|1#one item|1<<n> items'>")
                .put(Locale.ENGLISH, "hello", "Hello")
                .put(Locale.CHINESE, "hello", "你好").build(), this);
        translator = engine.translator();
    }

    @AfterEach
    void tearDown() { engine.close(); }

    @Test
    void canTranslate_withoutArguments_neverThrows() {
        assertThat(translator.canTranslate("sample:hello", Locale.US)).isTrue();
    }

    @Test
    void shared_patternsAndChoice_useTheSameRenderer() {
        var messages = engine.messagesFor("sample");
        Text number = Text.of(messages.key("number"), Arg.number("n", 1234.5));
        Text choice = Text.of(messages.key("choice"), Arg.number("n", 1234));
        for (Text text : java.util.List.of(number, choice)) {
            Component shared = messages.shared(text);
            assertThat(plain(GlobalTranslator.render(shared, Locale.GERMANY)))
                    .isEqualTo(plain(messages.render(text, Locale.GERMANY)));
            assertThat(plain(GlobalTranslator.render(Component.text("prefix ").append(shared), Locale.GERMANY)))
                    .isEqualTo("prefix " + plain(messages.render(text, Locale.GERMANY)));
        }
    }

    @Test
    void handWrittenComponent_rendersWithoutCarrierAndRetainsStyleAndChildren() {
        TranslatableComponent input = Component.translatable("sample:hello")
                .color(NamedTextColor.RED).append(Component.text("!"));
        Component translated = translator.translate(input, Locale.CHINESE);
        assertThat(translated).isNotNull();
        assertThat(plain(translated)).isEqualTo("你好!");
        assertThat(translated.color()).isEqualTo(NamedTextColor.RED);
        assertThat(plain(GlobalTranslator.render(input, Locale.US))).isEqualTo("Hello!");
    }

    @Test
    void serializedArgumentsWithoutACarrier_areLeftToTheStoredFallback() {
        TranslatableComponent serialized = Component.translatable("sample:number", Component.text("1,234.50"))
                .fallback("1,234.50");
        assertThat(translator.translate(serialized, Locale.GERMANY)).isNull();
        assertThat(plain(GlobalTranslator.render(serialized, Locale.GERMANY))).isEqualTo("1,234.50");
    }

    @Test
    void unknownOrForeignKeys_areLeftForOtherSources() {
        for (String key : java.util.List.of("minecraft.block.stone", "foreign:hello", "sample:absent", "Bad:key")) {
            assertThat(translator.canTranslate(key, Locale.US)).isFalse();
            assertThat(translator.translate(Component.translatable(key), Locale.US)).isNull();
        }
        assertThat(translator.translate("sample:hello", Locale.US)).isNull();
        assertThat(translator.name().asString()).isEqualTo("ultitools:messages");
        assertThat(engine.messagesFor("sample").isDefined(MessageKey.of("sample", "hello"), Locale.FRENCH)).isFalse();
    }

    @Test
    void close_removesOnlyThisEngineTranslator() {
        assertThat(GlobalTranslator.translator().sources()).anyMatch(translator::equals);
        engine.close();
        assertThat(GlobalTranslator.translator().sources()).noneMatch(translator::equals);
    }

    private static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }
}
