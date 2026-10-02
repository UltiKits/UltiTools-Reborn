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
 *       so a new literal in an exempt file still fails;</li>
 *   <li>the whole initializer of a {@code static final String} declared in a nested class named
 *       {@code Keys} (a catalogue-key holder), when the literal is a key of both catalogues and every
 *       reference to the constant in {@code src/main/java} is a qualified whole value: a call argument,
 *       a ternary branch or a return value, never concatenated, assigned, statically imported or used
 *       as a method receiver. {@code Keys.class} (a reflective read of the holder) is not a reference.
 *       Known weakness: a {@code Keys.X} passed straight to a raw output call would pass, because the
 *       holder's own catalogue test proves completeness, not translation at the point of use.</li>
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

    /** Simple name of a nested class whose {@code static final String} constants are catalogue keys. */
    private static final String HOLDER = "Keys";

    /** Tokens that may directly precede a qualified holder reference: argument, ternary branch, return. */
    private static final Set<String> HOLDER_PREV = new HashSet<>(Arrays.asList("(", ",", "?", ":", "return"));

    /** Tokens that may directly follow a qualified holder reference: end of argument, branch or statement. */
    private static final Set<String> HOLDER_NEXT = new HashSet<>(Arrays.asList(")", ",", ":", ";"));

    /** Fixture catalogue key held by a fixture {@code Keys} class, present in both fixture catalogues. */
    private static final String HOLDER_KEY = "夹具键：模块 %s 已更新";

    /** Fixture catalogue key present in the English fixture catalogue only. */
    private static final String EN_ONLY_KEY = "夹具键：只在英文目录里";

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
                    "the resource-folder name returned by getResourceFolderName(), kept at its pre-6.3.0 "
                            + "value so an existing install keeps its folder; an identifier, never shown "
                            + "(the display name getName() is English)"));

    /** Literals that reach the catalogue through a variable or a constructor field, not a literal argument. */
    private static final List<String> KEYS_VIA_VARIABLE = Arrays.asList(
            "上一页", "下一页", "返回", "退出", "确认", "取消",
            "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n/ul reload <模块名> 重载指定模块\n"
                    + "/ul list 查看已加载的模块列表\n================");

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

    private static JsonObject catalogue(String code) throws IOException {
        try (Reader reader = Files.newBufferedReader(LANG.resolve(code + ".json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** Every {@code .java} file under {@code src/main/java}, keyed by its path relative to that root. */
    private static Map<String, String> mainSources() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        assertThat(files.size()).as("control: the source tree was found").isGreaterThan(200);
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path file : files) {
            String relative = MAIN_JAVA.relativize(file).toString().replace('\\', '/');
            sources.put(relative, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        return sources;
    }

    private static List<JavaLiteralScanner.Literal> cjkLiterals() throws IOException {
        return cjkLiterals(mainSources());
    }

    private static List<JavaLiteralScanner.Literal> cjkLiterals(Map<String, String> sources) {
        List<JavaLiteralScanner.Literal> all = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            for (JavaLiteralScanner.Literal literal : JavaLiteralScanner.scan(source.getKey(), source.getValue())) {
                if (JavaLiteralScanner.containsCjkOrFullWidth(literal.value)) {
                    all.add(literal);
                }
            }
        }
        return all;
    }

    /**
     * The invariant itself, over any set of sources and catalogues, so the fixtures below exercise the
     * same code path as the real scan.
     *
     * @return one line per Chinese literal that is neither translated, listed, exempt nor accepted
     */
    static List<String> findViolations(Map<String, String> sources, JsonObject en, JsonObject zh) {
        List<JavaLiteralScanner.Literal> literals = cjkLiterals(sources);
        Set<String> holderConstants = new HashSet<>();
        for (JavaLiteralScanner.Literal literal : literals) {
            if (isHolderConstant(literal)) {
                holderConstants.add(literal.constantName);
            }
        }
        Map<String, String> misused = holderMisuses(sources, holderConstants);
        List<String> violations = new ArrayList<>();
        for (JavaLiteralScanner.Literal literal : literals) {
            if (isTranslated(literal) || KEYS_VIA_VARIABLE.contains(literal.value)
                    || exemptionFor(literal) != null) {
                continue;
            }
            if (!isHolderConstant(literal)) {
                violations.add(literal.toString());
            } else if (!en.has(literal.value) || !zh.has(literal.value)) {
                violations.add(literal + " (a " + HOLDER + " constant that is not a key of both catalogues)");
            } else if (misused.containsKey(literal.constantName)) {
                violations.add(literal + " (" + HOLDER + "." + literal.constantName + " used as "
                        + misused.get(literal.constantName) + ")");
            }
        }
        return violations;
    }

    private static boolean isHolderConstant(JavaLiteralScanner.Literal literal) {
        return literal.constantName != null && HOLDER.equals(literal.enclosingType);
    }

    /**
     * Finds every reference to a holder constant that is not a qualified whole value.
     *
     * @return constant name to the first offending reference, as {@code file:line prev Keys.NAME next}
     */
    private static Map<String, String> holderMisuses(Map<String, String> sources, Set<String> constants) {
        Map<String, String> misused = new LinkedHashMap<>();
        if (constants.isEmpty()) {
            return misused;
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            List<JavaLiteralScanner.Token> tokens = JavaLiteralScanner.tokens(source.getKey(), source.getValue());
            for (int i = 0; i < tokens.size(); i++) {
                String misuse = holderMisuseAt(tokens, i, constants);
                if (misuse != null) {
                    misused.putIfAbsent(tokens.get(i).text,
                            source.getKey() + ":" + tokens.get(i).line + " " + misuse);
                }
            }
        }
        return misused;
    }

    /** Describes the misuse when token {@code i} names a holder constant outside the accepted shapes, else null. */
    private static String holderMisuseAt(List<JavaLiteralScanner.Token> tokens, int i, Set<String> constants) {
        String name = tokens.get(i).text;
        if (!constants.contains(name)) {
            return null;
        }
        return ".".equals(text(tokens, i - 1)) ? qualifiedMisuseAt(tokens, i) : unqualifiedMisuseAt(tokens, i);
    }

    /** An unqualified name can reach a holder constant only inside the holder; there it is not a whole value. */
    private static String unqualifiedMisuseAt(List<JavaLiteralScanner.Token> tokens, int i) {
        boolean declaration = "String".equals(text(tokens, i - 1)) && "=".equals(text(tokens, i + 1));
        boolean insideHolder = HOLDER.equals(tokens.get(i).enclosingType);
        return insideHolder && !declaration ? "an unqualified name" : null;
    }

    /** A {@code Keys.NAME} reference, possibly further qualified, must sit in an accepted whole-value position. */
    private static String qualifiedMisuseAt(List<JavaLiteralScanner.Token> tokens, int i) {
        String name = tokens.get(i).text;
        String after = text(tokens, i + 1);
        if (!HOLDER.equals(text(tokens, i - 2))) {
            return null;
        }
        int start = i - 2;
        while (".".equals(text(tokens, start - 1)) && isName(text(tokens, start - 2))) {
            start -= 2;
        }
        String prev = text(tokens, start - 1);
        return HOLDER_PREV.contains(prev) && HOLDER_NEXT.contains(after)
                ? null
                : "'" + prev + " " + HOLDER + "." + name + " " + after + "'";
    }

    private static String text(List<JavaLiteralScanner.Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index).text : "";
    }

    private static boolean isName(String token) {
        return !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0));
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

        List<String> violations = findViolations(mainSources(), catalogue("en"), catalogue("zh"));
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

    // ---- fixtures for the invariant: a catalogue-key holder class (#561 integration) ----

    private static JsonObject fixtureCatalogue(boolean chinese) {
        JsonObject catalogue = new JsonObject();
        catalogue.addProperty(HOLDER_KEY, chinese ? HOLDER_KEY : "Fixture key: module %s updated");
        catalogue.addProperty("夹具键：直接翻译", chinese ? "夹具键：直接翻译" : "Fixture key: translated directly");
        if (!chinese) {
            catalogue.addProperty(EN_ONLY_KEY, "Fixture key: English catalogue only");
        }
        return catalogue;
    }

    private static List<String> fixtureViolations(String holderUse) {
        String source = String.join("\n",
                "package fixture;",
                "public final class Holder {",
                "    void use(java.util.logging.Logger logger, boolean flag) {",
                "        " + holderUse,
                "    }",
                "    static String pick(boolean flag) {",
                "        return flag ? Keys.UPDATED : Keys.UPDATED;",
                "    }",
                "    public static final class Keys {",
                "        public static final String UPDATED = \"" + HOLDER_KEY + "\";",
                "        private Keys() {",
                "        }",
                "        static java.lang.reflect.Field[] all() {",
                "            return Keys.class.getFields();",
                "        }",
                "    }",
                "}");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/Holder.java", source);
        return findViolations(sources, fixtureCatalogue(false), fixtureCatalogue(true));
    }

    @Test
    @DisplayName("fixture: a Keys holder constant used only as a whole value is accepted")
    void holderConstantUsedAsWholeValueIsAccepted() {
        assertThat(fixtureViolations("report(java.util.logging.Level.INFO, Keys.UPDATED, \"m\");")).isEmpty();
        assertThat(fixtureViolations("Object r = failed(null, flag ? Keys.UPDATED\n : Keys.UPDATED, 1);"))
                .as("a ternary branch spread over two lines").isEmpty();
        assertThat(fixtureViolations("report(null, fixture.Holder.Keys.UPDATED);"))
                .as("a fully qualified reference").isEmpty();
    }

    @Test
    @DisplayName("fixture: a Keys holder constant concatenated anywhere is a violation")
    void holderConstantConcatenatedIsAViolation() {
        assertThat(fixtureViolations("logger.info(\"x\" + Keys.UPDATED);")).hasSize(1);
        assertThat(fixtureViolations("logger.info(Keys.UPDATED + \"x\");")).hasSize(1);
    }

    @Test
    @DisplayName("fixture: a Keys holder constant used as a method receiver is a violation")
    void holderConstantAsReceiverIsAViolation() {
        assertThat(fixtureViolations("logger.info(Keys.UPDATED.trim());")).hasSize(1);
    }

    @Test
    @DisplayName("fixture: a Keys holder constant assigned to a variable or statically imported is a violation")
    void holderConstantAssignedIsAViolation() {
        assertThat(fixtureViolations("String raw = Keys.UPDATED;")).hasSize(1);
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/Holder.java", String.join("\n",
                "class Holder {",
                "    void use() { report(Keys.UPDATED); }",
                "    static final class Keys {",
                "        static final String UPDATED = \"" + HOLDER_KEY + "\";",
                "    }",
                "}"));
        sources.put("fixture/Other.java", String.join("\n",
                "import static fixture.Holder.Keys.UPDATED;",
                "class Other {",
                "}"));
        assertThat(findViolations(sources, fixtureCatalogue(false), fixtureCatalogue(true)))
                .as("a static import opens an unqualified, unchecked use").hasSize(1);
    }

    @Test
    @DisplayName("fixture: a Keys holder literal missing from either catalogue is a violation")
    void holderLiteralMissingFromACatalogueIsAViolation() {
        String source = String.join("\n",
                "class Holder {",
                "    void use() { report(Keys.EN_ONLY); report(Keys.NOWHERE); }",
                "    static final class Keys {",
                "        static final String EN_ONLY = \"" + EN_ONLY_KEY + "\";",
                "        static final String NOWHERE = \"夹具键：两个目录都没有\";",
                "    }",
                "}");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/Holder.java", source);
        assertThat(findViolations(sources, fixtureCatalogue(false), fixtureCatalogue(true))).hasSize(2);
    }

    @Test
    @DisplayName("fixture: the same constant outside a class named Keys is a violation")
    void constantOutsideAKeysHolderIsAViolation() {
        String source = String.join("\n",
                "class Holder {",
                "    void use() { report(Messages.UPDATED); report(UPDATED_TOO); }",
                "    static final String UPDATED_TOO = \"" + HOLDER_KEY + "\";",
                "    static final class Messages {",
                "        static final String UPDATED = \"" + HOLDER_KEY + "\";",
                "    }",
                "}");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/Holder.java", source);
        assertThat(findViolations(sources, fixtureCatalogue(false), fixtureCatalogue(true))).hasSize(2);
    }

    @Test
    @DisplayName("fixture: direct translator calls and listed keys stay accepted; a raw literal stays a violation")
    void existingRulesAreUnchanged() {
        String source = String.join("\n",
                "class Plain {",
                "    void use() {",
                "        i18n(\"夹具键：直接翻译\");",
                "        FrameworkText.format(\"夹具键：直接翻译\", 1);",
                "        String back = \"上一页\";",
                "        player.sendMessage(\"夹具：原样发给玩家\");",
                "    }",
                "}");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/Plain.java", source);
        assertThat(findViolations(sources, fixtureCatalogue(false), fixtureCatalogue(true)))
                .containsExactly("fixture/Plain.java:6 \"夹具：原样发给玩家\"");
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
