package com.ultikits.ultitools.buildtools.deprecation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the English replacement-guidance text from a raw {@code @deprecated} javadoc
 * paragraph.
 *
 * <p>This is deliberately a build-time, test-scoped tool (see the package-level rationale on
 * {@link JavadocDeprecationScanner}) — it is never shaded into the published {@code UltiTools-API}
 * jar and never falls inside japicmp's comparison scope.
 *
 * <p><b>The cut rule (measured against all 48 real {@code @deprecated} sites in this repository,
 * see 07-RESEARCH.md Priority 3):</b> cut the paragraph at the first occurrence of {@code <p>} or
 * {@code <br>}/{@code <br/>}, whichever comes first in the raw text. A {@code <p>}-only rule fails
 * on 6 of 48 sites that use {@code <br>} instead — this repository's Chinese/English convention is
 * inconsistent about which separator a given javadoc block uses.
 */
public final class DeprecatedParagraphExtractor {

    /** Matches {@code <p>} case-insensitively (an HTML paragraph break with no closing tag). */
    private static final Pattern P_TAG = Pattern.compile("<p>", Pattern.CASE_INSENSITIVE);

    /** Matches {@code <br>} or the self-closing {@code <br/>} form, case-insensitively. */
    private static final Pattern BR_TAG = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);

    /**
     * Matches a javadoc {@code {@link ...}} (or {@code {@linkplain ...}}) inline tag, capturing
     * everything between the tag name and the closing brace.
     */
    private static final Pattern LINK_TAG = Pattern.compile("\\{@link(?:plain)?\\s+([^}]+)}");

    /** CJK Unified Ideographs range, used by the corpus-wide regression guard (Test 9). */
    static final Pattern CJK_RANGE = Pattern.compile("[\\u4e00-\\u9fff]");

    private DeprecatedParagraphExtractor() {
    }

    /**
     * Extracts the English replacement-guidance text from a raw {@code @deprecated} javadoc
     * paragraph.
     *
     * @param rawDeprecatedParagraph the raw paragraph text following {@code @deprecated}, or
     *                               {@code null}
     * @return the trimmed English segment, with any {@code {@link}} tags resolved to their
     *         reference text; empty string for {@code null} or blank input
     */
    public static String extractReplacementText(String rawDeprecatedParagraph) {
        if (rawDeprecatedParagraph == null) {
            return "";
        }
        String trimmed = rawDeprecatedParagraph.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int cut = findCutPoint(trimmed);
        String segment = cut >= 0 ? trimmed.substring(0, cut) : trimmed;
        // Collapse whitespace runs left behind by javadoc's per-line "* " indentation once a
        // multi-line paragraph's newlines have already been folded to spaces by the caller.
        return resolveLinks(segment).trim().replaceAll("\\s+", " ");
    }

    /**
     * Finds the index of the first {@code <p>} or {@code <br>}/{@code <br/>} occurrence in
     * {@code text}, whichever comes first. Returns {@code -1} when neither is present.
     */
    private static int findCutPoint(String text) {
        Matcher pMatcher = P_TAG.matcher(text);
        Matcher brMatcher = BR_TAG.matcher(text);
        int pIndex = pMatcher.find() ? pMatcher.start() : -1;
        int brIndex = brMatcher.find() ? brMatcher.start() : -1;
        if (pIndex < 0) {
            return brIndex;
        }
        if (brIndex < 0) {
            return pIndex;
        }
        return Math.min(pIndex, brIndex);
    }

    /**
     * Replaces every {@code {@link X}} inline tag in {@code text} with its reference text — the
     * simple (unqualified) class name plus any {@code #member(paramTypes)} suffix, or the
     * explicit label when the link tag supplies one.
     */
    private static String resolveLinks(String text) {
        Matcher matcher = LINK_TAG.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String reference = simplifyLinkReference(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(reference));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Reduces a raw {@code {@link}} body (everything between "{@link " and the closing "}") to
     * its display text: an explicit label if the tag supplies one, otherwise the reference with
     * its package prefix stripped.
     */
    private static String simplifyLinkReference(String rawBody) {
        String trimmed = rawBody.trim();
        int splitAt = findLabelSplit(trimmed);
        String reference = splitAt >= 0 ? trimmed.substring(0, splitAt) : trimmed;
        String label = splitAt >= 0 ? trimmed.substring(splitAt).trim() : "";
        if (!label.isEmpty()) {
            return label;
        }
        return stripPackagePrefix(reference);
    }

    /**
     * Finds the index of the first whitespace character that lies outside any parenthesised
     * parameter list — that is the boundary between a javadoc reference and its optional label.
     * Returns {@code -1} when the whole body is a bare reference with no label.
     */
    private static int findLabelSplit(String body) {
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Strips the package qualifier from the class portion of a javadoc reference
     * ({@code pkg.Class}, {@code pkg.Class#member(...)}, or the same-class self-reference form
     * {@code #member(...)}), leaving the simple class name (if any) plus any member suffix.
     * A self-reference drops its leading {@code #} entirely, matching javadoc's own rendering.
     */
    private static String stripPackagePrefix(String reference) {
        int hashIndex = reference.indexOf('#');
        String classPart = hashIndex >= 0 ? reference.substring(0, hashIndex) : reference;
        String memberPart = hashIndex >= 0 ? reference.substring(hashIndex) : "";
        if (classPart.isEmpty()) {
            // Same-class self-reference ({@link #member(...)}) - drop the leading '#'.
            return memberPart.isEmpty() ? memberPart : memberPart.substring(1);
        }
        int lastDot = classPart.lastIndexOf('.');
        String simpleClass = lastDot >= 0 ? classPart.substring(lastDot + 1) : classPart;
        return simpleClass + memberPart;
    }

    /**
     * Reads {@code file} and returns every raw {@code @deprecated} paragraph found in its javadoc
     * comments — everything from {@code @deprecated} up to (but not including) the next
     * {@code @}-prefixed javadoc block tag, or the end of the comment.
     *
     * <p>Test-only helper backing the corpus-wide CJK regression guard (Test 9); this is a plain
     * text scan, not a javac-level parse — {@link JavadocDeprecationScanner} owns the real
     * source-of-truth extraction used by the generator itself.
     */
    static java.util.List<String> scanDeprecatedParagraphs(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        Pattern commentPattern = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
        Matcher commentMatcher = commentPattern.matcher(text);
        Pattern deprecatedPattern = Pattern.compile(
                "@deprecated(.*?)(?=\\n\\s*\\*\\s*@\\w|$)", Pattern.DOTALL);
        while (commentMatcher.find()) {
            String comment = commentMatcher.group(1);
            Matcher deprecatedMatcher = deprecatedPattern.matcher(comment);
            if (deprecatedMatcher.find()) {
                String raw = deprecatedMatcher.group(1);
                // Strip leading " * " javadoc line prefixes so <p>/<br> detection sees prose only.
                String cleaned = raw.replaceAll("\\r?\\n[ \\t]*\\*[ \\t]?", " ");
                paragraphs.add(cleaned.trim());
            }
        }
        return paragraphs;
    }
}
