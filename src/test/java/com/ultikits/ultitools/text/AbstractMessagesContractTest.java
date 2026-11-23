package com.ultikits.ultitools.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Reusable catalogue, locale, delivery and concurrency assertions for any Messages implementation. */
public abstract class AbstractMessagesContractTest {
    private static final MessageKey GREETING = MessageKey.of("sample", "greeting");
    private final List<Fixture> fixtures = new ArrayList<>();
    private ServerMock server;
    private Fixture fixture;
    private Messages messages;

    /**
     * Creates a fresh module-bound Messages service with namespace {@code sample} and the supplied
     * resolver mode. A real implementation subclass adapts its catalogue loading and warning logger
     * through Fixture: put/sourceLanguage must make catalogue changes visible before returning.
     * Close releases any resources owned by that service, without unmocking the shared server.
     */
    protected abstract Fixture createFixture(Locale fallback, Optional<Locale> forced);

    /** Test controls, intentionally not part of the production Messages API. */
    public interface Fixture extends AutoCloseable {
        Messages messages();
        void put(MessageKey key, Locale locale, String template);
        void sourceLanguage(String namespace, Locale locale);
        List<LogRecord> warnings();
        @Override default void close() { }
    }

    @BeforeEach
    void setUpMessagesContract() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        fixture = configuredFixture(Optional.empty());
        messages = fixture.messages();
    }

    private Fixture configuredFixture(Optional<Locale> forced) {
        Fixture created = createFixture(Locale.CHINESE, forced);
        fixtures.add(created);
        created.sourceLanguage("sample", Locale.ENGLISH);
        created.put(GREETING, Locale.ENGLISH, "Hello");
        created.put(GREETING, Locale.CHINESE, "你好");
        return created;
    }

    @AfterEach
    void tearDownMessagesContract() {
        try {
            fixtures.forEach(Fixture::close);
        } finally {
            fixtures.clear();
            MockBukkitHelper.safeUnmock();
        }
    }

    @Test
    void render_missingKey_returnsKeyPlaceholderAndNeverThrows() {
        for (MessageKey key : List.of(MessageKey.of("sample", "absent"), MessageKey.of("no_catalogue", "absent"))) {
            assertThat(plain(messages.render(Text.of(key), Locale.FRENCH))).isEqualTo(key.translationKey());
        }
    }

    @Test
    void render_missingKey_logsOncePerKeyAndLocale() {
        MessageKey missing = MessageKey.of("sample", "missing");
        MessageKey another = MessageKey.of("sample", "another");
        int before = fixture.warnings().size();
        for (int i = 0; i < 5; i++) {
            messages.render(missing, Locale.FRENCH);
        }
        messages.render(missing, Locale.GERMAN);
        messages.render(another, Locale.FRENCH);
        List<LogRecord> records = fixture.warnings().subList(before, fixture.warnings().size());
        assertThat(records).hasSize(3).allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("sample:");
        });
        assertThat(records.stream().filter(record -> record.getMessage().contains(missing.translationKey())))
                .hasSize(2);
    }

    @Test
    void render_regionLocale_fallsBackToLanguage() {
        assertThat(plain(messages.render(GREETING, Locale.SIMPLIFIED_CHINESE))).isEqualTo("你好");
    }

    @Test
    void render_exactLocale_precedesLanguage() {
        fixture.put(GREETING, Locale.SIMPLIFIED_CHINESE, "中国");
        assertThat(plain(messages.render(GREETING, Locale.SIMPLIFIED_CHINESE))).isEqualTo("中国");
    }

    @Test
    void render_unknownLocale_usesOperatorFallback() {
        assertThat(plain(messages.render(GREETING, Locale.FRENCH))).isEqualTo("你好");
    }

    @Test
    void render_keyOnlyInSourceLanguage_usesSourceLanguage() {
        MessageKey source = MessageKey.of("sample", "source_only");
        fixture.put(source, Locale.ENGLISH, "Source text");
        for (Locale locale : List.of(Locale.FRENCH, Locale.CHINESE, Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            assertThat(plain(messages.render(source, locale))).isEqualTo("Source text");
        }
        MessageKey foreign = MessageKey.of("other", "source_only");
        fixture.sourceLanguage("other", Locale.JAPANESE);
        fixture.put(foreign, Locale.JAPANESE, "別の言語");
        assertThat(plain(messages.render(foreign, Locale.FRENCH))).isEqualTo("別の言語");
    }

    @Test
    void render_stringArg_isNotParsedAsMiniMessage() {
        MessageKey key = MessageKey.of("sample", "argument");
        fixture.put(key, Locale.ENGLISH, "<p>");
        Component rendered = messages.render(key, Locale.US, Arg.string("p", "<red>x"));
        assertThat(plain(rendered)).isEqualTo("<red>x");
        assertThat(rendered.compact()).isEqualTo(Component.text("<red>x"));
    }

    @Test
    void render_numberArg_usesTargetLocaleFormat() {
        MessageKey key = MessageKey.of("sample", "number");
        fixture.put(key, Locale.ENGLISH, "<n>");
        assertThat(plain(messages.render(key, Locale.US, Arg.number("n", 1234.5)))).isEqualTo("1,234.5");
        assertThat(plain(messages.render(key, Locale.GERMANY, Arg.number("n", 1234.5)))).isEqualTo("1.234,5");
    }

    @Test
    void render_textArg_rendersInSameLocale() {
        MessageKey key = MessageKey.of("sample", "nested");
        fixture.put(key, Locale.ENGLISH, "[<t>]");
        assertThat(plain(messages.render(key, Locale.US, Arg.text("t", Text.of(GREETING))))).isEqualTo("[Hello]");
        assertThat(plain(messages.render(key, Locale.SIMPLIFIED_CHINESE, Arg.text("t", Text.of(GREETING)))))
                .isEqualTo("[你好]");
        assertThat(plain(messages.render(key, Locale.US, Arg.component("t", Component.text("literal")))))
                .isEqualTo("[literal]");
    }

    @Test
    void render_timeArg_usesTargetLocaleAndTranslationPattern() {
        MessageKey key = MessageKey.of("sample", "date");
        LocalDate date = LocalDate.of(2026, 11, 23);
        fixture.put(key, Locale.ENGLISH, "<date>");
        assertThat(plain(messages.render(key, Locale.GERMANY, Arg.time("date", date))))
                .isEqualTo(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMANY).format(date));
        fixture.put(key, Locale.ENGLISH, "<date:yyyy-MM-dd>");
        assertThat(plain(messages.render(key, Locale.US, Arg.time("date", date)))).isEqualTo("2026-11-23");
    }

    @Test
    void forEachRecipient_forwardingAudience_rendersPerMemberLocale() {
        PlayerMock english = server.addPlayer();
        PlayerMock chinese = server.addPlayer();
        english.setLocale(Locale.US);
        chinese.setLocale(Locale.SIMPLIFIED_CHINESE);
        Audience group = Audience.audience(english, Audience.audience(chinese));
        Map<Audience, String> delivered = new IdentityHashMap<>();
        List<Audience> calls = new ArrayList<>();
        messages.forEachRecipient(group, Text.of(GREETING), (recipient, component) -> {
            calls.add(recipient);
            delivered.put(recipient, plain(component));
        });
        assertThat(calls).containsExactlyInAnyOrder(english, chinese);
        assertThat(delivered.get(english)).isEqualTo("Hello");
        assertThat(delivered.get(chinese)).isEqualTo("你好");
        assertThat(delivered).doesNotContainKey(group);
    }

    @Test
    void render_console_usesFallback() {
        assertThat(plain(messages.render(Text.of(GREETING), server.getConsoleSender()))).isEqualTo("你好");
        assertThat(plain(messages.render(GREETING, server.getConsoleSender()))).isEqualTo("你好");
    }

    @Test
    void shared_autoMode_returnsTranslatable() {
        Component rendered = messages.shared(Text.of(GREETING));
        assertThat(rendered).isInstanceOfSatisfying(TranslatableComponent.class,
                translatable -> assertThat(translatable.key()).isEqualTo(GREETING.translationKey()));
        assertThat(messages.shared(Text.plain("literal"))).isEqualTo(Component.text("literal"));
    }

    @Test
    void shared_forcedMode_returnsRenderedComponent() {
        Messages forced = configuredFixture(Optional.of(Locale.US)).messages();
        Component rendered = forced.shared(Text.of(GREETING));
        assertThat(rendered).isNotInstanceOf(TranslatableComponent.class);
        assertThat(plain(rendered)).isEqualTo("Hello");
        assertThat(plain(forced.render(Text.of(GREETING), server.getConsoleSender()))).isEqualTo("你好");
    }

    @Test
    void isDefined_doesNotApplyFallback() {
        assertThat(messages.isDefined(GREETING, Locale.SIMPLIFIED_CHINESE)).isTrue();
        assertThat(messages.isDefined(GREETING, Locale.US)).isTrue();
        assertThat(messages.isDefined(GREETING, Locale.FRENCH)).isFalse();
        assertThat(messages.isDefined(MessageKey.of("absent", "absent"), Locale.US)).isFalse();
        assertThat(messages.namespace()).isEqualTo("sample");
        assertThat(messages.key("greeting")).isEqualTo(GREETING);
    }

    @Test
    void render_concurrentCalls_areConsistent() throws Exception {
        MessageKey missing = MessageKey.of("sample", "concurrent_missing");
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> work = new ArrayList<>();
        Audience recipient = new Audience() { };
        for (int thread = 0; thread < 8; thread++) {
            work.add(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent render start timed out");
                }
                for (int i = 0; i < 100; i++) {
                    assertThat(plain(messages.render(GREETING, Locale.US))).isEqualTo("Hello");
                    assertThat(plain(messages.render(GREETING, Locale.SIMPLIFIED_CHINESE))).isEqualTo("你好");
                    assertThat(plain(messages.render(Text.of(GREETING), recipient))).isEqualTo("你好");
                    assertThat(messages.shared(Text.of(GREETING))).isInstanceOf(TranslatableComponent.class);
                    assertThat(messages.isDefined(GREETING, Locale.US)).isTrue();
                    assertThat(messages.key("greeting")).isEqualTo(GREETING);
                    messages.send(recipient, GREETING);
                    messages.sendActionBar(recipient, Text.of(GREETING));
                    assertThat(plain(messages.render(missing, Locale.US))).isEqualTo(missing.translationKey());
                }
                return true;
            });
        }
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> task : work) {
                results.add(executor.submit(task));
            }
            start.countDown();
            for (java.util.concurrent.Future<Boolean> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(fixture.warnings().stream().filter(record -> record.getMessage().contains(missing.translationKey())))
                .hasSize(1);
    }

    @Test
    void defaultDeliveryMethods_renderEachRecipient() {
        List<Component> chat = new ArrayList<>();
        List<Component> actionBar = new ArrayList<>();
        Audience recipient = new Audience() {
            @Override public void sendMessage(Component message) { chat.add(message); }
            @Override public void sendActionBar(Component message) { actionBar.add(message); }
        };
        messages.send(recipient, Text.of(GREETING));
        messages.send(recipient, GREETING);
        messages.sendActionBar(recipient, Text.of(GREETING));
        assertThat(chat.stream().map(AbstractMessagesContractTest::plain)).containsExactly("你好", "你好");
        assertThat(actionBar.stream().map(AbstractMessagesContractTest::plain)).containsExactly("你好");
        assertThat(messages.render(Text.plain("<red>literal"), Locale.US)).isEqualTo(Component.text("<red>literal"));
    }

    @Test
    void defaultMethods_nullArguments_areRejectedByName() {
        Audience viewer = server.getConsoleSender();
        Text text = Text.of(GREETING);
        assertThatNullPointerException().isThrownBy(() -> messages.render(null, Locale.US, new Arg[0])).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> messages.render(GREETING, (Locale) null)).withMessage("locale");
        assertThatNullPointerException().isThrownBy(() -> messages.render(GREETING, (Audience) null)).withMessage("viewer");
        assertThatNullPointerException().isThrownBy(() -> messages.render(GREETING, Locale.US, (Arg[]) null)).withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> messages.render(GREETING, viewer, (Arg[]) null)).withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> messages.render(null, viewer, new Arg[0])).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> messages.send(null, text)).withMessage("audience");
        assertThatNullPointerException().isThrownBy(() -> messages.send(viewer, (Text) null)).withMessage("text");
        assertThatNullPointerException().isThrownBy(() -> messages.send(null, GREETING)).withMessage("audience");
        assertThatNullPointerException().isThrownBy(() -> messages.send(viewer, null, new Arg[0])).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> messages.send(viewer, GREETING, (Arg[]) null)).withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> messages.sendActionBar(null, text)).withMessage("audience");
        assertThatNullPointerException().isThrownBy(() -> messages.sendActionBar(viewer, null)).withMessage("text");
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
