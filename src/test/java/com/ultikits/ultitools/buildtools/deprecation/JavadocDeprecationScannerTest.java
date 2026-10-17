package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Observable declaration, documentation and reflection contracts for the build-only scanner. */
@DisplayName("JavadocDeprecationScanner")
class JavadocDeprecationScannerTest {

    private static final String MATRIX = "com.ultikits.testfixtures.deprecationscan.DeclarationMatrixSubject";

    private List<DeprecationEntry> matrixEntries() throws IOException {
        return new JavadocDeprecationScanner(getClass().getClassLoader())
                .scan(Paths.get("src/test/java/com/ultikits/testfixtures/deprecationscan"));
    }

    private DeprecationEntry exactEntry(String key) throws IOException {
        return matrixEntries().stream().filter(e -> e.getKey().toString().equals(key)).findFirst().orElse(null);
    }

    @Test
    void documentedMemberIsPositiveControl() throws IOException {
        DeprecationEntry entry = exactEntry(MATRIX + "#documented()");
        assertThat(entry).isNotNull();
        assertThat(entry.getKind()).isEqualTo(DeprecationEntry.Kind.METHOD);
        assertThat(entry.getSince()).isEqualTo("6.2.3");
        assertThat(entry.isForRemoval()).isTrue();
        assertThat(entry.getRemoveIn()).isEqualTo("7.0.0");
        assertThat(entry.getReplacement()).isEqualTo("Use String instead.");
        assertThat(entry.getStatus()).isEqualTo(DeprecationEntry.Status.ANNOUNCED);
    }

    @Test
    void annotationOnlyDeclarationIsRegistered() throws IOException {
        DeprecationEntry entry = exactEntry(MATRIX + "#annotationOnly()");
        assertThat(entry).as("annotation-only exact METHOD key").isNotNull();
        assertThat(entry.getKind()).isEqualTo(DeprecationEntry.Kind.METHOD);
        assertThat(entry.getSince()).isEqualTo("6.2.0");
        assertThat(entry.getReplacement()).isEmpty();
        assertThat(entry.getRemoveIn()).isNull();
        assertThat(exactEntry(MATRIX + "#ordinaryDoc()")).isNotNull();
    }

    @Test
    void initializedFieldHasFieldKey() throws IOException {
        DeprecationEntry entry = exactEntry(MATRIX + "#initializedField");
        assertThat(entry).as("initialized field exact FIELD key without parentheses").isNotNull();
        assertThat(entry.getKind()).isEqualTo(DeprecationEntry.Kind.FIELD);
        assertThat(entry.getKey().isField()).isTrue();
        assertThat(entry.getSince()).isEqualTo("6.2.2");
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

        /** Characterization of semicolons in initializer strings through the public scan API. */
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
