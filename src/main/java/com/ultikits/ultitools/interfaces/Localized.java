package com.ultikits.ultitools.interfaces;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Localized interface.
 */
public interface Localized {

    /**
     * The language-file extensions a code may be declared by, matching
     * {@code UltiToolsPlugin}'s loader.
     * <p>
     * {@code .yml}/{@code .yaml} were added in 6.3.0 (#389): eight of this ecosystem's modules
     * ship YAML, and while only {@code .json} counted here they reported no supported languages at
     * all, so {@code resolveLanguageCode} had nothing to consult.
     *
     * @since 6.3.0
     */
    String[] LANGUAGE_EXTENSIONS = {".json", ".yml", ".yaml"};

    /**
     * Returns {@code fileName} without its language extension, or {@code null} if it has none --
     * or if the remainder is not itself a plain language-code token.
     * <p>
     * <b>16-05 / CodeQL {@code java/zipslip} alert #11 (CWE-22).</b> The returned value is not
     * merely displayed: {@code UltiToolsPlugin} combines it directly into a {@link java.io.File}
     * path (e.g. {@code lang/<code>.json}) with no other sanitisation, and this method is fed raw
     * jar-entry names by {@link #scanLangJar(File)} as well as raw on-disk filenames by {@link
     * #scanLangDirectory(File)}. Before this fix, a jar entry named {@code lang/..\..\evil.json}
     * (or the URL-decoded equivalent {@code lang/../../evil.json}) survived unchanged as the code
     * {@code ..\..\evil}, which a downstream sink could then resolve outside the module's own
     * {@code lang/} directory.
     * <p>
     * The remainder is therefore also checked against a character allowlist -- {@code
     * [A-Za-z0-9][A-Za-z0-9_-]*} -- before being accepted as a code at all: it must start with an
     * ASCII letter or digit, and every character must be an ASCII letter, digit, {@code '_'} or
     * {@code '-'}. This is deliberately a character allowlist, not a BCP-47 (or any other)
     * language-tag grammar: every code this framework or any of its shipped modules actually uses
     * ({@code en}, {@code zh}, {@code en_US}, {@code zh-CN}, ...) matches it, and no plausible
     * language code needs a {@code '.'}, {@code '/'}, {@code '\'} or any other character this
     * allowlist rejects. A candidate failing the allowlist returns {@code null} -- exactly the
     * same "not a language file" outcome as a name with no recognised extension at all -- so a
     * caller cannot tell "wrong extension" apart from "rejected as unsafe", by design: neither is
     * a case a caller needs to react to differently.
     *
     * @param fileName a file or jar-entry name, without any directory part
     * @return the language code, or {@code null} when the name is not a language file or its
     *         stripped remainder is not a safe language-code token
     * @since 6.3.0
     */
    static String languageCodeOf(String fileName) {
        for (String extension : LANGUAGE_EXTENSIONS) {
            if (fileName.endsWith(extension) && fileName.length() > extension.length()) {
                String code = fileName.substring(0, fileName.length() - extension.length());
                return isSafeLanguageCode(code) ? code : null;
            }
        }
        return null;
    }

    /**
     * Checks {@code code} against the character allowlist {@link #languageCodeOf(String)}
     * documents: non-empty, starting with an ASCII letter or digit, every character thereafter an
     * ASCII letter, digit, {@code '_'} or {@code '-'}.
     * <p>
     * Exposed as {@code public static} for the same reason {@link #scanLangJar(File)} and {@link
     * #scanLangDirectory(File)} are -- interface methods cannot be non-public before Java 9, so
     * this is a direct test seam rather than API meant for module authors to call.
     *
     * @param code the candidate language code, already stripped of its extension
     * @return whether {@code code} is safe to treat as a language code
     * @since 6.3.0
     */
    static boolean isSafeLanguageCode(String code) {
        if (code.isEmpty()) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean asciiAlphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (asciiAlphanumeric) {
                continue;
            }
            if (i > 0 && (c == '_' || c == '-')) {
                continue;
            }
            return false;
        }
        return true;
    }
    /**
     * Get the language code of the plugin module.
     * more <a href="https://en.wikipedia.org/wiki/IETF_language_tag">Language code list</a>
     * <br>
     * The returned list is derived from the {@code lang/*.json}, {@code lang/*.yml} and
     * {@code lang/*.yaml} resources the implementor's own
     * code source (its JAR, or an exploded directory in a development workspace) actually ships.
     * An empty list means no language resources were found -- not that no language is supported.
     * Overriding this method still wins over the derivation.
     * <br><br>
     * Gets the language codes a plugin supported.
     *
     * @return Supported language codes
     * @see <a href="https://dev.ultikits.com/en/guide/essentials/i18n.html">Internationalization</a>
     * @since 6.3.0 derived from lang/*.json resources rather than {@code @I18n} (D-20)
     */
    default List<String> supported() {
        CodeSource codeSource = this.getClass().getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return new ArrayList<>();
        }
        return scanLangResources(codeSource.getLocation());
    }

    /**
     * Enumerates the {@code lang/*} language resources at the given code-source location, handling
     * both a packaged JAR and an exploded directory (development workspace) layout. Reuses the
     * {@code getProtectionDomain().getCodeSource()} -&gt; {@code JarFile} idiom
     * {@code UltiToolsPlugin.saveResources()} already uses, so this is correct on a module's
     * first-ever startup, before its embedded resources have been extracted to disk.
     * <p>
     * Exposed as {@code public static} out of necessity, not invitation -- interface methods
     * cannot be non-public before Java 9, so this is a direct test seam for {@link #supported()}'s
     * derivation rather than API meant for module authors to call.
     *
     * @param codeSourceLocation the URL returned by {@code CodeSource.getLocation()}
     * @return the language codes found, sorted and de-duplicated; empty on any failure
     */
    static List<String> scanLangResources(URL codeSourceLocation) {
        try {
            String rawPath = codeSourceLocation.getPath();
            File location = new File(rawPath.startsWith("/") ? rawPath : rawPath.substring(1));
            if (location.isDirectory()) {
                return scanLangDirectory(new File(location, "lang"));
            }
            return scanLangJar(location);
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Lists the immediate language-file children of {@code langDir} -- nested entries such as
     * {@code lang/extra/en.json} are not descended into.
     *
     * @param langDir the {@code lang/} directory to scan
     * @return the language codes found; empty if {@code langDir} does not exist or is empty
     */
    static List<String> scanLangDirectory(File langDir) {
        if (langDir == null || !langDir.isDirectory()) {
            return new ArrayList<>();
        }
        File[] files = langDir.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }
        Set<String> codes = new TreeSet<>();
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String code = languageCodeOf(file.getName());
            if (code != null) {
                codes.add(code);
            }
        }
        return new ArrayList<>(codes);
    }

    /**
     * Enumerates {@code jarFile}'s entries for immediate {@code lang/*} language children --
     * neither non-language entries (e.g. {@code lang/README.txt}) nor nested entries (e.g.
     * {@code lang/extra/en.json}) are returned. No entry is extracted; this only reads names.
     *
     * @param jarFile the module's own JAR
     * @return the language codes found; empty if {@code jarFile} cannot be opened as a JAR
     */
    static List<String> scanLangJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Set<String> codes = new TreeSet<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("lang/")) {
                    continue;
                }
                String remainder = name.substring("lang/".length());
                if (remainder.isEmpty() || remainder.contains("/")) {
                    continue;
                }
                String code = languageCodeOf(remainder);
                if (code != null) {
                    codes.add(code);
                }
            }
            return new ArrayList<>(codes);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns a localized string for the given default text. The {@code code} parameter is not
     * honoured by the framework's own implementation -- {@code UltiToolsPlugin} overrides this
     * method {@code final} and resolves directly against the module's already-loaded {@code
     * Language}, discarding {@code code} entirely. Language selection happens once at load time,
     * via {@link #supported()}, not per call. {@code str} is returned unchanged when it is absent
     * as a key from that already-loaded dictionary.
     *
     * @param code language code -- not honoured by the framework's own {@code i18n(String,
     *             String)} override
     * @param str  default display text, also the dictionary lookup key
     * @return a localized string, or {@code str} unchanged if the key is absent from the loaded
     *         dictionary
     * @since 6.3.0 corrected wording -- see D-22 (#315)
     */
    default String i18n(String code, String str) {
        return str;
    }
}
