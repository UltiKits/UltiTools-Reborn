package com.ultikits.ultitools.buildtools.deprecation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Extracts the English replacement-guidance text from a raw {@code @deprecated} javadoc
 * paragraph.
 *
 * <p>RED-phase stub: intentionally not implemented yet, so the accompanying tests fail before
 * the real cut rule lands (see 07-RESEARCH.md Priority 3).
 */
public final class DeprecatedParagraphExtractor {

    /** CJK Unified Ideographs range, used by the corpus-wide regression guard (Test 9). */
    static final Pattern CJK_RANGE = Pattern.compile("[\\u4e00-\\u9fff]");

    private DeprecatedParagraphExtractor() {
    }

    public static String extractReplacementText(String rawDeprecatedParagraph) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    static java.util.List<String> scanDeprecatedParagraphs(Path file) throws IOException {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }
}
