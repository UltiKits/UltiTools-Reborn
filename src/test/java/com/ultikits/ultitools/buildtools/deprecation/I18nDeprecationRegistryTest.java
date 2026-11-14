package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** #877: the build's source scanner must register both retained language declarations. */
class I18nDeprecationRegistryTest {
    @Test
    void sourceScannerAnnouncesBothDeclarationsWithAccurateHistoryAndEarliestRemoval() throws IOException {
        String annotationPackage = "com.ultikits.ultitools.annotations.";
        List<DeprecationEntry> entries = new JavadocDeprecationScanner(getClass().getClassLoader())
                .scan(Paths.get("src/main/java/com/ultikits/ultitools/annotations")).stream()
                .filter(entry -> entry.getKey().toString().equals(annotationPackage + "I18n")
                        || entry.getKey().toString().equals(annotationPackage + "UltiToolsModule#i18n()"))
                .collect(Collectors.toList());

        assertThat(entries).hasSize(2);
        for (DeprecationEntry entry : entries) {
            assertThat(entry.getSince()).isEqualTo("6.5.0");
            assertThat(entry.isForRemoval()).isTrue();
            assertThat(entry.getStatus()).isEqualTo(DeprecationEntry.Status.ANNOUNCED);
            assertThat(entry.getRemoveIn()).isNull();
            assertThat(entry.getReplacement()).contains("Deprecated since 6.5.0.",
                    "Since 6.3.0, languages come from", "lang/*", "may be removed as early as 6.6.0")
                    .doesNotContain("future major version");
        }
    }
}
