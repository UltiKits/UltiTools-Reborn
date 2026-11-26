package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.utils.UpmKeys;

/**
 * The framework no longer adds literal keys to legacy {@code i18n}. Fixture calls prove that the
 * scanner still recognizes the patterns that lost English translations or format arguments
 * (#394, #395). The new catalogue must render the old reply without changing its wording.
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
    @DisplayName("the migrated versions reply is unchanged and no literal i18n calls remain in src/main")
    void noLiteralI18nKeysRemain() throws IOException {
        Map<String, String> fixture = new LinkedHashMap<>();
        collectLiteralKeys("Fixture.java", """
                // i18n("ignored")
                UltiTools.getInstance().i18n("获取版本列表失败！");
                """, fixture);
        assertThat(fixture).containsExactlyEntriesOf(Map.of("获取版本列表失败！", "Fixture.java:2"));
        String expected = readEnglishDictionary().get("获取版本列表失败！");
        assertThat(expected).isEqualTo("Could not fetch the version list!");
        try (var text = new CommandMessageTestSupport("en")) {
            assertThat(CommandMessageTestSupport.plain(text.messages().render(UpmKeys.VERSIONS_FAILED, Locale.ENGLISH)))
                    .isEqualTo(expected);
        }
        assertThat(collectLiteralKeys()).as("no literal keys may be passed to legacy i18n").isEmpty();
    }

    @Test
    @DisplayName("the legacy format scanner distinguishes an argument consumer from a fallback that discards arguments")
    void fixtureDetectsInsufficientLegacyFormatSpecifiers() throws IOException {
        Map<String, String> en = readEnglishDictionary();
        String text = stripComments("""
                String.format(UltiTools.getInstance().i18n("下载地址：%s"), links.current());
                String.format(UltiTools.getInstance().i18n("夹具：缺少占位符"), command.getName(), usage.getHelp());
                """);
        Matcher call = FORMAT_CALL.matcher(text);
        assertThat(call.find()).isTrue();
        assertThat(call.group(1)).isEqualTo("下载地址：%s");
        assertThat(argumentsAfter(text, call.end())).isEqualTo(1);
        assertThat(countSpecifiers(en.get(call.group(1)))).isEqualTo(1);

        // As in #394, a missing dictionary key fell back to itself and discarded both arguments.
        assertThat(call.find()).isTrue();
        String missing = unescape(call.group(1));
        assertThat(en).doesNotContainKey(missing);
        assertThat(argumentsAfter(text, call.end())).isEqualTo(2);
        assertThat(countSpecifiers(en.getOrDefault(missing, missing))).isZero();
        assertThat(call.find()).isFalse();
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
        List<Path> files = sourceFiles();
        assertThat(files.size()).as("control: the source tree was found").isGreaterThan(200);
        for (Path file : files) {
            collectLiteralKeys(file.getFileName().toString(), Files.readString(file), keys);
        }
        return keys;
    }

    private void collectLiteralKeys(String fileName, String source, Map<String, String> keys) {
        String text = stripComments(source);
        Matcher m = I18N_CALL.matcher(text);
        while (m.find()) {
            int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            keys.putIfAbsent(unescape(m.group(1)), fileName + ":" + line);
        }
    }

    /**
     * Blanks out comments so documentation cannot be mistaken for a call site.
     * <p>
     * Javadoc routinely quotes {@code i18n("some.key")} to explain behaviour, and without this the
     * scan reports those examples as untranslated keys. It reported one the first time a javadoc
     * example was written, which is how this was found. Comment bodies are replaced with spaces
     * rather than removed so every subsequent offset -- and therefore every reported line number --
     * stays correct.
     * <p>
     * String literals are tracked, so a {@code "//"} inside one (a URL, say) does not start a
     * comment. Character literals are tracked for the same reason.
     */
    private String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < source.length()) {
                    char d = source.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < source.length()) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                while (i < source.length()
                        && !(source.charAt(i) == '*' && i + 1 < source.length()
                                && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
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
