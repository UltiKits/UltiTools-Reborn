package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #556: no text the framework shows an operator, a player, the panel or an e-mail reader is a Chinese
 * string literal outside the language catalogue.
 * <p>
 * Every string literal in {@code src/main/java} holding Han (extensions included), CJK symbols and
 * punctuation or full-width forms must be one of
 * <ol>
 *   <li>the direct first argument of an {@code i18n(...)}, {@code localize(...)} or
 *       {@code FrameworkText.text/format(...)} call, with the key present in {@code en.json};</li>
 *   <li>a catalogue key reached through a variable, listed in {@link #KEYS_VIA_VARIABLE} and present in
 *       both catalogues;</li>
 *   <li>a named exemption in {@link #EXEMPTIONS}, each with its reason and its exact literal count,
 *       so a new literal in an exempt file still fails.</li>
 * </ol>
 * The scan is Java-aware ({@link JavaLiteralScanner}), not a same-line grep.
 */
@DisplayName("Framework text goes through the language catalogue (#556)")
class FrameworkTextCatalogueInvariantTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path LANG = Paths.get("src/main/resources/lang");

    /** Method names that resolve their first argument through the language catalogue. */
    private static final Set<String> TRANSLATORS = new HashSet<>(Arrays.asList("i18n", "localize"));

    /** The helper class added by #556; its calls are checked against both catalogues. */
    private static final String HELPER = "FrameworkText";

    /**
     * A named exemption: every CJK literal of {@code file} that is not a translated catalogue key is
     * exempt, and there are exactly {@code count} of them.
     */
    private static final class Exemption {
        final String file;
        final int count;
        final String reason;

        Exemption(String file, int count, String reason) {
            this.file = file;
            this.count = count;
            this.reason = reason;
        }
    }

    private static final List<Exemption> EXEMPTIONS = Arrays.asList(
            new Exemption("com/ultikits/ultitools/UltiTools.java", 4,
                    "the four bilingual config.yml comment constants: English first, Chinese supplement, "
                            + "written into the operator's config file, not shown as output "
                            + "(root CLAUDE.md, Code Style & Constraints)"),
            new Exemption("com/ultikits/ultitools/entities/Capability.java", 9,
                    "the capability comment lines the migration writes into config.yml, bilingual by design "
                            + "(English first, Chinese supplement) like the four constants above"),
            new Exemption("com/ultikits/ultitools/services/impl/DefaultEmailService.java", 6,
                    "the verification e-mail is bilingual on purpose: each Chinese line is paired with its "
                            + "English line, because the recipient's language is not the server's"),
            new Exemption("com/ultikits/ultitools/services/impl/InMemeryTeleportService.java", 1,
                    "getName() is the service identifier, and BaseService.getResourceFolderName() defaults "
                            + "to it, so translating it would make a folder name depend on the language; "
                            + "reported for a decision, not changed here"));

    /** Literals that reach the catalogue through a variable or a constructor field, not a literal argument. */
    private static final List<String> KEYS_VIA_VARIABLE = Arrays.asList(
            "上一页", "下一页", "返回", "退出", "确认", "取消",
            "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n/ul reload <模块名> 重载指定模块\n"
                    + "/ul list 查看已加载的模块列表\n================");

    private static JsonObject catalogue(String code) throws IOException {
        try (Reader reader = Files.newBufferedReader(LANG.resolve(code + ".json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<JavaLiteralScanner.Literal> cjkLiterals() throws IOException {
        List<JavaLiteralScanner.Literal> all = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        assertThat(files.size()).as("control: the source tree was found").isGreaterThan(200);
        for (Path file : files) {
            String relative = MAIN_JAVA.relativize(file).toString().replace('\\', '/');
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (JavaLiteralScanner.Literal literal : JavaLiteralScanner.scan(relative, source)) {
                if (JavaLiteralScanner.containsCjkOrFullWidth(literal.value)) {
                    all.add(literal);
                }
            }
        }
        return all;
    }

    private static boolean isTranslated(JavaLiteralScanner.Literal literal) {
        if (literal.callName == null) {
            return false;
        }
        return TRANSLATORS.contains(literal.callName)
                || (HELPER.equals(literal.receiver)
                        && ("text".equals(literal.callName) || "format".equals(literal.callName)));
    }

    private static boolean isHelperCall(JavaLiteralScanner.Literal literal) {
        return HELPER.equals(literal.receiver)
                && ("text".equals(literal.callName) || "format".equals(literal.callName));
    }

    private static Exemption exemptionFor(JavaLiteralScanner.Literal literal) {
        for (Exemption exemption : EXEMPTIONS) {
            if (exemption.file.equals(literal.file)) {
                return exemption;
            }
        }
        return null;
    }

    @Test
    @DisplayName("no Chinese literal in src/main/java bypasses the catalogue")
    void noChineseLiteralBypassesTheCatalogue() throws IOException {
        List<JavaLiteralScanner.Literal> literals = cjkLiterals();
        // Control: the scan sees literals that are translated today, so a zero below is not a blind scan.
        assertThat(literals.stream().filter(FrameworkTextCatalogueInvariantTest::isTranslated).count())
                .as("control: the scan finds literals that are direct arguments of a translator call")
                .isGreaterThan(50);

        List<String> violations = new ArrayList<>();
        for (JavaLiteralScanner.Literal literal : literals) {
            if (isTranslated(literal) || KEYS_VIA_VARIABLE.contains(literal.value)
                    || exemptionFor(literal) != null) {
                continue;
            }
            violations.add(literal.toString());
        }
        assertThat(violations)
                .as("Chinese string literals that are neither catalogue keys nor named exemptions")
                .isEmpty();
    }

    @Test
    @DisplayName("every named exemption matches exactly the literals it claims, and gives a reason")
    void exemptionsAreExact() throws IOException {
        List<JavaLiteralScanner.Literal> literals = cjkLiterals();
        for (Exemption exemption : EXEMPTIONS) {
            long found = literals.stream()
                    .filter(l -> l.file.equals(exemption.file) && !isTranslated(l))
                    .count();
            assertThat(found).as("untranslated CJK literals in exempt file %s", exemption.file)
                    .isEqualTo(exemption.count);
            assertThat(exemption.reason).isNotBlank();
        }
    }

    @Test
    @DisplayName("keys reached through a variable exist in the source and in both catalogues")
    void variableKeysExistInBothCatalogues() throws IOException {
        List<JavaLiteralScanner.Literal> literals = cjkLiterals();
        JsonObject en = catalogue("en");
        JsonObject zh = catalogue("zh");
        for (String key : KEYS_VIA_VARIABLE) {
            assertThat(literals.stream().anyMatch(l -> l.value.equals(key)))
                    .as("listed key is still in the source: %s", key).isTrue();
            assertThat(en.has(key)).as("en.json has %s", key).isTrue();
            assertThat(zh.has(key)).as("zh.json has %s", key).isTrue();
        }
    }

    @Test
    @DisplayName("every translated literal is an en.json key; helper keys are in both catalogues")
    void translatedKeysAreInTheCatalogues() throws IOException {
        JsonObject en = catalogue("en");
        JsonObject zh = catalogue("zh");
        List<String> missingEn = new ArrayList<>();
        List<String> missingZh = new ArrayList<>();
        for (JavaLiteralScanner.Literal literal : cjkLiterals()) {
            if (!isTranslated(literal)) {
                continue;
            }
            if (!en.has(literal.value)) {
                missingEn.add(literal.toString());
            }
            if (isHelperCall(literal) && !zh.has(literal.value)) {
                missingZh.add(literal.toString());
            }
        }
        assertThat(missingEn).as("translated literals with no en.json entry").isEmpty();
        assertThat(missingZh).as("helper keys with no zh.json entry").isEmpty();
    }

    @Test
    @DisplayName("a helper format key and its English value carry the same placeholders, in the same order")
    void helperFormatKeysKeepTheirPlaceholders() throws IOException {
        Pattern spec = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");
        JsonObject en = catalogue("en");
        List<String> mismatched = new ArrayList<>();
        for (JavaLiteralScanner.Literal literal : cjkLiterals()) {
            if (!isHelperCall(literal) || !"format".equals(literal.callName) || !en.has(literal.value)) {
                continue;
            }
            String english = en.get(literal.value).getAsString();
            if (!placeholders(spec, literal.value).equals(placeholders(spec, english))) {
                mismatched.add(literal.value + " -> " + english);
            }
        }
        assertThat(mismatched).isEmpty();
    }

    private static List<String> placeholders(Pattern spec, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = spec.matcher(text.replace("%%", ""));
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    // ---- controls for the scanner itself ----

    @Test
    @DisplayName("control: the scanner sees a literal in a multi-line call and in a concatenation, and ignores comments")
    void scannerControl() {
        String source = String.join("\n",
                "class X {",
                "  // \"注释里的引号\" is not a literal",
                "  /* \"块注释\" */",
                "  void f() {",
                "    log(",
                "        \"多行调用\",",
                "        a);",
                "    String s = \"前半\" + \"后半\";",
                "    i18n(\"已翻译\");",
                "    FrameworkText.format(\"格式 %s\", x);",
                "    char q = '\"'; String t = \"带引号的\\\"中文\";",
                "  }",
                "}");
        Map<String, JavaLiteralScanner.Literal> byValue = new LinkedHashMap<>();
        for (JavaLiteralScanner.Literal literal : JavaLiteralScanner.scan("X.java", source)) {
            byValue.put(literal.value, literal);
        }
        assertThat(byValue.keySet()).containsExactly(
                "多行调用", "前半", "后半", "已翻译", "格式 %s", "带引号的\"中文");
        assertThat(byValue.get("多行调用").line).isEqualTo(6);
        assertThat(byValue.get("多行调用").callName).isEqualTo("log");
        assertThat(byValue.get("前半").callName).isNull();
        assertThat(byValue.get("后半").callName).as("the second operand of a concatenation").isNull();
        assertThat(byValue.get("已翻译").callName).isEqualTo("i18n");
        assertThat(byValue.get("格式 %s").callName).isEqualTo("format");
        assertThat(byValue.get("格式 %s").receiver).isEqualTo(HELPER);
        assertThat(JavaLiteralScanner.containsCjkOrFullWidth("Install Command：")).isTrue();
        assertThat(JavaLiteralScanner.containsCjkOrFullWidth("plain ascii")).isFalse();
    }
}
