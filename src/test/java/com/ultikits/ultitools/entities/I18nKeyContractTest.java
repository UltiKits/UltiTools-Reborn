package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Static contract between the {@code i18n("...")} calls in {@code src/main} and {@code lang/en.json}.
 * <p>
 * {@code Language.get} is {@code dictionary.getOrDefault(str, str)}, and this framework's keys
 * <em>are</em> the Chinese source strings. A key missing from {@code zh.json} is therefore
 * harmless -- the fallback is the Chinese key, which is the correct Chinese output. A key missing
 * from {@code en.json} renders as Chinese on an English server, silently.
 * <p>
 * Two defects motivated this, and each is one of the two tests below:
 * <ul>
 *   <li>#395 -- twenty framework messages had no English entry, including both page-boundary
 *       messages in every paginated GUI and the whole {@code /upm} interface.</li>
 *   <li>#394 -- {@code BaseCommandExecutor} asked for {@code "正确用法"}; the key that exists is
 *       {@code "指令正确用法"}. The miss fell back to the key, and because the fallback carries no
 *       format specifiers, {@code String.format} silently discarded the command name and usage
 *       string it was given. Every wrong-argument-count error printed the bare words "正确用法"
 *       and nothing else -- the most frequently seen error message in the framework, saying
 *       nothing.</li>
 * </ul>
 * The second test is the one that makes #394 a build failure rather than a translation gap: adding
 * an {@code en.json} entry for the typo'd key would have satisfied the first test alone while
 * leaving the arguments still discarded.
 *
 * @since 6.3.0
 */
@DisplayName("i18n key contract")
class I18nKeyContractTest {

    private static final Path SOURCE_ROOT = Paths.get("src/main/java/com/ultikits/ultitools");
    private static final Path EN_JSON = Paths.get("src/main/resources/lang/en.json");

    /** {@code i18n("...")}, capturing the literal with its escapes intact. */
    private static final Pattern I18N_CALL =
            Pattern.compile("\\bi18n\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Locates {@code String.format(<optional receiver>.i18n("KEY")} and captures the key. The
     * argument list is NOT captured here: a regex that stopped at the first {@code ')'} counted
     * {@code command.getName(} as the whole list and reported one argument where two are passed,
     * which under-reports and could miss a real mismatch. {@link #argumentsAfter} walks the rest
     * with balanced parentheses instead.
     */
    private static final Pattern FORMAT_CALL = Pattern.compile(
            "String\\.format\\(\\s*(?:[\\w.]+\\(\\)\\.)*i18n\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)",
            Pattern.DOTALL);

    /** A format specifier {@code String.format} would consume an argument for. */
    private static final Pattern SPECIFIER =
            Pattern.compile("%(?!%)[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");

    @Test
    @DisplayName("every literal i18n key used in src/main has an en.json entry")
    void everyLiteralKeyIsTranslated() throws IOException {
        Map<String, String> en = readEnglishDictionary();
        Map<String, String> keys = collectLiteralKeys();

        // Positive control: a scan that reads nothing returns an empty map, which is
        // indistinguishable from "no violations" and would make this test pass forever.
        assertThat(keys)
                .as("positive control: the scan must find a known key. An empty result means the "
                        + "scan is not reading %s, not that the codebase is clean.", SOURCE_ROOT)
                .containsKey("指令正确用法");

        List<String> untranslated = keys.entrySet().stream()
                .filter(e -> !en.containsKey(e.getKey()))
                .map(e -> "\"" + e.getKey().replace("\n", "\\n") + "\"  used at " + e.getValue())
                .collect(Collectors.toList());

        assertThat(untranslated)
                .as("These keys have no en.json entry. Language.get falls back to the key itself, "
                        + "and these keys are Chinese, so an English server shows Chinese for each "
                        + "of them -- silently (#395). Add the entry, or fix the call if the key "
                        + "is a typo for one that exists (#394).")
                .isEmpty();
    }

    @Test
    @DisplayName("no String.format(i18n(k), ...) passes more arguments than k has specifiers")
    void formatCallsHaveEnoughSpecifiers() throws IOException {
        Map<String, String> en = readEnglishDictionary();
        List<String> mismatches = new ArrayList<>();
        int examined = 0;

        for (Path file : sourceFiles()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher m = FORMAT_CALL.matcher(text);
            while (m.find()) {
                String key = unescape(m.group(1));
                int arguments = argumentsAfter(text, m.end());
                if (arguments <= 0) {
                    continue;
                }
                examined++;
                // The rendered string is the dictionary entry when present, and the key itself
                // when not -- exactly what Language.get returns. Checking the resolved string is
                // the point: #394's key was absent, so the fallback carried no specifiers at all.
                String rendered = en.getOrDefault(key, key);
                int specifiers = countSpecifiers(rendered);
                if (specifiers < arguments) {
                    mismatches.add(String.format(
                            "%s: key \"%s\" resolves to \"%s\" (%d specifier(s)) but %d argument(s) "
                                    + "are passed",
                            file.getFileName(), key.replace("\n", "\\n"),
                            rendered.replace("\n", "\\n"), specifiers, arguments));
                }
            }
        }

        assertThat(examined)
                .as("positive control: no String.format(i18n(...), args) call was examined at all, "
                        + "so this test proved nothing. The pattern has stopped matching.")
                .isPositive();

        assertThat(mismatches)
                .as("String.format silently discards extra arguments when the format string has "
                        + "no place for them, so this is never reported at runtime -- the user "
                        + "just sees a message with the useful part missing (#394).")
                .isEmpty();
    }

    private Map<String, String> readEnglishDictionary() throws IOException {
        String json = new String(Files.readAllBytes(EN_JSON), StandardCharsets.UTF_8);
        Map<String, String> en =
                new Gson().fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
        assertThat(en)
                .as("control: %s parsed to an empty dictionary, so every key would read as "
                        + "untranslated", EN_JSON)
                .isNotEmpty();
        return en;
    }

    /** Every literal i18n key in {@code src/main}, mapped to the first site that uses it. */
    private Map<String, String> collectLiteralKeys() throws IOException {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Path file : sourceFiles()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher m = I18N_CALL.matcher(text);
            while (m.find()) {
                int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                keys.putIfAbsent(unescape(m.group(1)), file.getFileName() + ":" + line);
            }
        }
        return keys;
    }

    private List<Path> sourceFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    /**
     * Count the arguments that follow the format string in a {@code String.format(...)} call.
     * <p>
     * {@code from} is the offset just past the {@code i18n("...")} argument. Scanning proceeds with
     * a paren/bracket depth counter so a nested call such as {@code player.getName()} counts as one
     * argument rather than terminating the list, and stops at the {@code ')'} that closes
     * {@code String.format} itself. Returns {@code -1} if the call is unbalanced, so a parse the
     * walker cannot trust is skipped rather than guessed at.
     */
    private int argumentsAfter(String text, int from) {
        int depth = 1;
        int count = 0;
        boolean sawContent = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth == 0) {
                    return sawContent ? count : 0;
                }
            } else if (c == ',' && depth == 1) {
                count++;
                sawContent = false;
                continue;
            }
            if (depth >= 1 && !Character.isWhitespace(c)) {
                sawContent = true;
            }
        }
        return -1;
    }

    private int countSpecifiers(String format) {
        Matcher m = SPECIFIER.matcher(format);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * Turn a Java source literal into the string the compiler would produce.
     * <p>
     * An escaped backslash is parked on NUL first, so the later passes cannot see the backslash
     * it produced and re-interpret it. The sentinel has to be a character that cannot occur in a
     * source literal: an earlier draft parked it on a space, which would have rewritten every
     * space in every key into a backslash.
     */
    private String unescape(String literal) {
        return literal
                .replace("\\\\", "\u0000")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\u0000", "\\");
    }
}
