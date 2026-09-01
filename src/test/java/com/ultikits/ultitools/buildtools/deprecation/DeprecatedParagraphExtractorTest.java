package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link DeprecatedParagraphExtractor}'s cut rule (07-RESEARCH.md Priority 3): cut at the
 * first {@code <p>} or {@code <br>}/{@code <br/>}, whichever comes first.
 */
@DisplayName("DeprecatedParagraphExtractor tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DeprecatedParagraphExtractorTest {

    @Nested
    @DisplayName("cut-point selection")
    class CutPointTests {

        @Test
        @DisplayName("Test 1: <p> before any <br> cuts at <p>")
        void pTagBeforeBrCutsAtP() {
            String raw = "Use the new API instead.<p>请改用新 API。";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("Use the new API instead.");
        }

        @Test
        @DisplayName("Test 2: <br> before any <p> cuts at <br> (the TempListener shape)")
        void brTagBeforePCutsAtBr() {
            String raw = "Use {@link #common(Class)} instead. You can filter events too.<br>请改用 {@link #common(Class)} 代替。";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("Use common(Class) instead. You can filter events too.");
        }

        @Test
        @DisplayName("Test 3: self-closing <br/> cuts identically to <br>")
        void selfClosingBrCutsIdentically() {
            String raw = "English guidance.<br/>中文说明。";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("English guidance.");
        }

        @Test
        @DisplayName("Test 4a: both present, <p> first -> cut at <p>")
        void bothPresentPFirst() {
            String raw = "English.<p>中文<br>更多中文";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("English.");
        }

        @Test
        @DisplayName("Test 4b: both present, <br> first -> cut at <br>")
        void bothPresentBrFirst() {
            String raw = "English.<br>中文<p>更多中文";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("English.");
        }

        @Test
        @DisplayName("Test 5: neither present -> whole paragraph, trimmed")
        void neitherPresentReturnsWholeParagraph() {
            String raw = "  Just plain English guidance with no separator.  ";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("Just plain English guidance with no separator.");
        }
    }

    @Nested
    @DisplayName("{@link} resolution")
    class LinkResolutionTests {

        @Test
        @DisplayName("Test 6: {@link com.foo.Bar#baz(int)} renders as its reference text Bar#baz(int)")
        void linkTagRendersAsReferenceText() {
            String raw = "Use {@link com.foo.Bar#baz(int)} instead.<p>请改用 {@link com.foo.Bar#baz(int)}。";
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(raw))
                    .isEqualTo("Use Bar#baz(int) instead.");
        }
    }

    @Nested
    @DisplayName("empty-input edge cases (GEN-04)")
    class EmptyInputTests {

        @Test
        @DisplayName("Test 7: null input returns empty string, does not throw")
        void nullInputReturnsEmptyString() {
            assertThat(DeprecatedParagraphExtractor.extractReplacementText(null)).isEqualTo("");
        }

        @Test
        @DisplayName("Test 8: whitespace-only input returns empty string")
        void whitespaceOnlyInputReturnsEmptyString() {
            assertThat(DeprecatedParagraphExtractor.extractReplacementText("   \n\t  ")).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("corpus-wide regression guard")
    class CorpusGuardTests {

        private final Pattern cjkRange = Pattern.compile("[\\u4e00-\\u9fff]");

        @Test
        @DisplayName("Test 9: no extracted replacement from src/main/java contains a CJK character")
        void noExtractedReplacementContainsCjk() throws IOException {
            Path srcRoot = Paths.get("src/main/java");
            List<Path> javaFiles = new ArrayList<>();
            Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            int paragraphsScanned = 0;
            List<String> leaks = new ArrayList<>();
            for (Path file : javaFiles) {
                List<String> paragraphs = DeprecatedParagraphExtractor.scanDeprecatedParagraphs(file);
                for (String paragraph : paragraphs) {
                    paragraphsScanned++;
                    String extracted = DeprecatedParagraphExtractor.extractReplacementText(paragraph);
                    if (cjkRange.matcher(extracted).find()) {
                        leaks.add(file + " -> [" + extracted + "]");
                    }
                }
            }

            // Control assertion: a zero-paragraph scan would pass the CJK assertion vacuously.
            assertThat(paragraphsScanned)
                    .as("expected at least 40 @deprecated paragraphs in src/main/java")
                    .isGreaterThanOrEqualTo(40);
            assertThat(leaks).as("CJK leaked into extracted replacement text").isEmpty();
        }
    }
}
