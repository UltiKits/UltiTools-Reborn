package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the matching, normalisation and redaction rules of {@link SensitiveCommandRegistry}.
 */
@DisplayName("SensitiveCommandRegistry rules")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SensitiveCommandRegistryTest {

    private SensitiveCommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SensitiveCommandRegistry();
    }

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("strips one leading slash and a namespace prefix, lower-cases")
        void stripsSlashAndNamespace() {
            assertThat(SensitiveCommandRegistry.normalize("/login")).isEqualTo("login");
            assertThat(SensitiveCommandRegistry.normalize("ultilogin:login")).isEqualTo("login");
            assertThat(SensitiveCommandRegistry.normalize("/UltiLogin:LOGIN")).isEqualTo("login");
        }

        @Test
        @DisplayName("strips only one leading slash")
        void onlyOneSlash() {
            assertThat(SensitiveCommandRegistry.normalize("//login")).isEqualTo("/login");
        }

        @Test
        @DisplayName("a bare namespace, null and blank give an empty string")
        void emptyCases() {
            assertThat(SensitiveCommandRegistry.normalize("login:")).isEmpty();
            assertThat(SensitiveCommandRegistry.normalize(null)).isEmpty();
            assertThat(SensitiveCommandRegistry.normalize("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("register and isSensitive")
    class Matching {

        @Test
        @DisplayName("matches with or without slash, any case, any namespace")
        void matchesVariants() {
            registry.register("l", "reg");
            assertThat(registry.isSensitive("/l pw")).isTrue();
            assertThat(registry.isSensitive("L pw")).isTrue();
            assertThat(registry.isSensitive("ultilogin:reg a b")).isTrue();
        }

        @Test
        @DisplayName("the four documented spellings of a registered login all match")
        void documentedSpellings() {
            registry.register("login");
            assertThat(registry.isSensitive("/login x")).isTrue();
            assertThat(registry.isSensitive("/LOGIN x")).isTrue();
            assertThat(registry.isSensitive("ultilogin:login x")).isTrue();
            assertThat(registry.isSensitive("/ultilogin:Login x")).isTrue();
        }

        @Test
        @DisplayName("a longer name, a split name and an empty line do not match")
        void nonMatches() {
            registry.register("login");
            assertThat(registry.isSensitive("/login2 x")).isFalse();
            assertThat(registry.isSensitive("/log in")).isFalse();
            assertThat(registry.isSensitive("")).isFalse();
            assertThat(registry.isSensitive(null)).isFalse();
        }

        @Test
        @DisplayName("null and blank names are ignored; registering twice is harmless")
        void ignoresNullAndBlank() {
            registry.register((String[]) null);
            registry.register(null, "", "  ", "login:");
            registry.register("login");
            registry.register("/LOGIN", "ultilogin:login");
            assertThat(registry.registeredNames()).containsExactly("login");
        }

        @Test
        @DisplayName("a Turkish default locale does not change matching of LOGIN")
        void localeIndependent() {
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(new Locale("tr", "TR"));
                registry.register("LOGIN");
                assertThat(SensitiveCommandRegistry.normalize("LOGIN")).isEqualTo("login");
                assertThat(registry.registeredNames()).containsExactly("login");
                assertThat(registry.isSensitive("/LOGIN pw")).isTrue();
                assertThat(registry.redactText("x /LOGIN pw")).isEqualTo("x /LOGIN ***");
            } finally {
                Locale.setDefault(previous);
            }
        }
    }

    @Nested
    @DisplayName("redactCommandLine")
    class RedactCommandLine {

        @BeforeEach
        void register() {
            registry.register("login");
        }

        @Test
        @DisplayName("replaces the arguments of a registered command")
        void replacesArguments() {
            assertThat(registry.redactCommandLine("/login hunter2")).isEqualTo("/login ***");
        }

        @Test
        @DisplayName("leaves a registered command without arguments unchanged")
        void noArguments() {
            assertThat(registry.redactCommandLine("/login")).isEqualTo("/login");
            assertThat(registry.redactCommandLine("/login   ")).isEqualTo("/login   ");
        }

        @Test
        @DisplayName("leaves an unregistered command and null unchanged")
        void unregistered() {
            assertThat(registry.redactCommandLine("/msg Alex hi")).isEqualTo("/msg Alex hi");
            assertThat(registry.redactCommandLine(null)).isNull();
        }
    }

    @Nested
    @DisplayName("redactText")
    class RedactText {

        @BeforeEach
        void register() {
            registry.register("login", "register");
        }

        @Test
        @DisplayName("redacts the console's command-dispatch phrasing")
        void dispatchPhrasing() {
            assertThat(registry.redactText("Steve issued server command: /register a b"))
                    .isEqualTo("Steve issued server command: /register ***");
        }

        @Test
        @DisplayName("redacts each line of multi-line text independently, keeping line breaks")
        void multiLine() {
            String text = "first /login pw1\nkept line\r\nthird /register a b\rlast /home x";
            assertThat(registry.redactText(text))
                    .isEqualTo("first /login ***\nkept line\r\nthird /register ***\rlast /home x");
        }

        @Test
        @DisplayName("redacts a command that follows a quote character")
        void quotedCommand() {
            assertThat(registry.redactText("said \"/login pw\"")).isEqualTo("said \"/login ***");
            assertThat(registry.redactText("said '/login pw'")).isEqualTo("said '/login ***");
        }

        @Test
        @DisplayName("a closing quote alone is not an argument")
        void closingQuoteOnly() {
            assertThat(registry.redactText("typed \"/login\" again")).isEqualTo("typed \"/login ***");
            assertThat(registry.redactText("typed \"/login\"")).isEqualTo("typed \"/login\"");
        }

        @Test
        @DisplayName("a slash inside a word or URL is not a command start")
        void urlUnchanged() {
            assertThat(registry.redactText("see http://x/login/y")).isEqualTo("see http://x/login/y");
            assertThat(registry.redactText("path a/login b")).isEqualTo("path a/login b");
        }

        @Test
        @DisplayName("a double-slash command is a different command")
        void doubleSlash() {
            assertThat(registry.redactText("//login pw")).isEqualTo("//login pw");
        }

        @Test
        @DisplayName("namespaced and upper-case spellings in text are redacted")
        void namespacedInText() {
            assertThat(registry.redactText("x /UltiLogin:LOGIN pw")).isEqualTo("x /UltiLogin:LOGIN ***");
        }

        @Test
        @DisplayName("a command without arguments, unrelated text and null are unchanged")
        void unchangedCases() {
            assertThat(registry.redactText("Steve issued server command: /login"))
                    .isEqualTo("Steve issued server command: /login");
            assertThat(registry.redactText("Steve issued server command: /home set base"))
                    .isEqualTo("Steve issued server command: /home set base");
            assertThat(registry.redactText("")).isEmpty();
            assertThat(registry.redactText(null)).isNull();
        }

        @Test
        @DisplayName("an earlier unregistered command on the line is kept")
        void earlierCommandKept() {
            assertThat(registry.redactText("/home a then /login pw"))
                    .isEqualTo("/home a then /login ***");
        }

        @Test
        @DisplayName("an empty registry returns the very same text")
        void emptyRegistryIdentity() {
            String text = "Steve issued server command: /login pw";
            assertThat(new SensitiveCommandRegistry().redactText(text)).isSameAs(text);
        }

        @Test
        @DisplayName("redaction is idempotent")
        void idempotent() {
            String once = registry.redactText("said \"/login pw\" and /register a");
            assertThat(registry.redactText(once)).isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("commandName")
    class CommandName {

        @Test
        @DisplayName("is the normalised first token")
        void normalisedFirstToken() {
            assertThat(SensitiveCommandRegistry.commandName("/ultilogin:Login pw")).isEqualTo("login");
            assertThat(SensitiveCommandRegistry.commandName("  /spawn")).isEqualTo("spawn");
            assertThat(SensitiveCommandRegistry.commandName("/MSG Alex hi")).isEqualTo("msg");
            assertThat(SensitiveCommandRegistry.commandName(null)).isEmpty();
            assertThat(SensitiveCommandRegistry.commandName("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("registeredNames")
    class RegisteredNames {

        @Test
        @DisplayName("is an unmodifiable snapshot")
        void unmodifiableSnapshot() {
            registry.register("login");
            Set<String> snapshot = registry.registeredNames();
            registry.register("register");

            assertThat(snapshot).containsExactly("login");
            assertThatThrownBy(() -> snapshot.add("x")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(registry.registeredNames()).containsExactlyInAnyOrder("login", "register");
        }

        @Test
        @DisplayName("concurrent registration from 8 threads loses nothing")
        void concurrentRegistration() throws Exception {
            int threads = 8;
            int perThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int id = t;
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            registry.register("cmd" + id + "x" + i);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(20, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(registry.registeredNames()).hasSize(threads * perThread);
        }
    }
}
