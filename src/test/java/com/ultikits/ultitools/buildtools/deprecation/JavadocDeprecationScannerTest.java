package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the one class in this package that had none (07-fix). Every sibling --
 * DeprecatedParagraphExtractor, JapicmpReportReader, RegistryKey, RegistryLedger,
 * RemovalConsistencyEvaluator -- carried a test class; the hand-written source scanner, the most
 * stateful piece of the generator, did not.
 * <p>
 * The two scanners are exercised directly rather than only through {@link
 * JavadocDeprecationScanner#scan(Path)}: they are pure {@code (String, int) -> int} functions, and
 * their edge cases (unbalanced input, structure inside string literals) are the ones the ledger's
 * japicmp cross-check cannot catch -- that check compares key sets, and has no opinion on the
 * {@code since} / {@code forRemoval} / {@code replacement} fields these scanners produce.
 */
@DisplayName("JavadocDeprecationScanner")
class JavadocDeprecationScannerTest {

    @Nested
    @DisplayName("scanBalancedParens")
    class ScanBalancedParens {

        @Test
        @DisplayName("returns the index just past the matching close paren")
        void returnsIndexPastMatchingClose() {
            String s = "@Deprecated(since = \"6.2.0\") void m();";
            int open = s.indexOf('(');
            assertThat(JavadocDeprecationScanner.scanBalancedParens(s, open))
                    .isEqualTo(s.indexOf(')') + 1);
        }

        @Test
        @DisplayName("matches the outer paren when arguments nest")
        void handlesNesting() {
            String s = "@Foo(a = bar(1, 2), b = 3) void m();";
            int open = s.indexOf('(');
            assertThat(JavadocDeprecationScanner.scanBalancedParens(s, open))
                    .isEqualTo(s.indexOf(") void") + 1);
        }

        @Test
        @DisplayName("refuses unbalanced input instead of running to the end of the source")
        void refusesUnbalanced() {
            // Before 07-fix the inline version left the cursor at the end of input and then took
            // substring(open + 1, end - 1), reporting a truncated slice as parsed arguments.
            assertThat(JavadocDeprecationScanner.scanBalancedParens("@Deprecated(since = \"6.2.0\"", 11))
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("refuses an offset that is not an open paren, and out-of-range offsets")
        void refusesNonParenOffsets() {
            assertThat(JavadocDeprecationScanner.scanBalancedParens("abc", 0)).isEqualTo(-1);
            assertThat(JavadocDeprecationScanner.scanBalancedParens("abc", -1)).isEqualTo(-1);
            assertThat(JavadocDeprecationScanner.scanBalancedParens("abc", 99)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("findDeclarationEnd")
    class FindDeclarationEnd {

        @Test
        @DisplayName("stops at a top-level semicolon")
        void stopsAtSemicolon() {
            String s = "public abstract void m();";
            assertThat(JavadocDeprecationScanner.findDeclarationEnd(s, 0)).isEqualTo(s.indexOf(';'));
        }

        @Test
        @DisplayName("stops at a top-level brace")
        void stopsAtBrace() {
            String s = "public void m() { body(); }";
            assertThat(JavadocDeprecationScanner.findDeclarationEnd(s, 0)).isEqualTo(s.indexOf('{'));
        }

        @Test
        @DisplayName("ignores braces and semicolons inside a parameter list")
        void ignoresInsideParens() {
            String s = "void m(Supplier<String> s, int i) { }";
            assertThat(JavadocDeprecationScanner.findDeclarationEnd(s, 0)).isEqualTo(s.indexOf('{'));
        }

        @Test
        @DisplayName("returns -1 when the input ends before any terminator")
        void returnsMinusOneWhenTruncated() {
            assertThat(JavadocDeprecationScanner.findDeclarationEnd("public void m()", 0)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("end to end, over a real compiled fixture")
    class EndToEnd {

        /**
         * The scanner resolves every parsed member reflectively, so it can only be pointed at
         * source whose classes are on the classpath -- a temp-file source tree fails before
         * reaching any parsing assertion. This scans the one fixture package.
         */
        private List<DeprecationEntry> scanFixtures() throws IOException {
            return new JavadocDeprecationScanner(getClass().getClassLoader())
                    .scan(Paths.get("src/test/java/com/ultikits/testfixtures/deprecationscan"));
        }

        private DeprecationEntry findByMember(List<DeprecationEntry> entries, String member) {
            for (DeprecationEntry entry : entries) {
                if (entry.getKey().toString().contains(member)) {
                    return entry;
                }
            }
            return null;
        }

        @Test
        @DisplayName("an unbalanced parenthesis inside a string argument does not derail the scan")
        void unbalancedParenInsideStringArgument() throws IOException {
            DeprecationEntry entry = findByMember(scanFixtures(), "unbalancedParenInSinceValue");

            assertThat(entry).isNotNull();
            assertThat(entry.getSince()).isEqualTo("6.2.0 (unclosed");
            assertThat(entry.isForRemoval()).isTrue();
        }

        /**
         * Characterisation, NOT a regression guard: measured both ways, this passes with and
         * without masking. A semicolon inside a field initialiser's string truncates declText, but
         * the truncation lands after classifyDeclaration has already read the kind and name, so the
         * entry comes out correct anyway. Kept because it pins that behaviour for classifyDeclaration,
         * not because it demonstrates the masking hazard -- only the unbalanced-paren case above does.
         */
        @Test
        @DisplayName("a semicolon inside a string still yields the right entry (characterisation)")
        void semicolonInsideStringStillYieldsRightEntry() throws IOException {
            DeprecationEntry entry = findByMember(scanFixtures(), "semicolonInStringBeforeTerminator");

            assertThat(entry).isNotNull();
            assertThat(entry.getSince()).isEqualTo("6.2.1");
        }

        @Test
        @DisplayName("a plain @Deprecated with no argument list is still recorded")
        void bareDeprecatedIsRecorded() throws IOException {
            assertThat(findByMember(scanFixtures(), "bareAnnotation")).isNotNull();
        }
    }
}
