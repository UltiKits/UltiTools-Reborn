package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/** Every split of a declared setting path's dots into keys a document holds (#612). */
class ConfigDocumentReadingsTest {

    @Test void nestedFlatAndMixedFormsAreEachOneReading() throws ConfigParseException {
        assertThat(ConfigDocument.parse("a:\n  b:\n    c: 1\n").readings("a.b.c"))
                .containsExactly(Arrays.asList("a", "b", "c"));
        assertThat(ConfigDocument.parse("a.b.c: 1\n").readings("a.b.c"))
                .containsExactly(Collections.singletonList("a.b.c"));
        assertThat(ConfigDocument.parse("a.b:\n  c: 1\n").readings("a.b.c"))
                .containsExactly(Arrays.asList("a.b", "c"));
        assertThat(ConfigDocument.parse("a:\n  b.c: 1\n").readings("a.b.c"))
                .containsExactly(Arrays.asList("a", "b.c"));
    }

    @Test void aSettingHeldInTwoFormsHasTwoReadingsNestedFirst() throws ConfigParseException {
        assertThat(ConfigDocument.parse("features.chat: false\nfeatures:\n  chat: true\n").readings("features.chat"))
                .containsExactly(Arrays.asList("features", "chat"), Collections.singletonList("features.chat"));
    }

    @Test void anExplicitNullIsAReadingAndAScalarParentIsNot() throws ConfigParseException {
        assertThat(ConfigDocument.parse("a:\n  b:\n").readings("a.b")).containsExactly(Arrays.asList("a", "b"));
        assertThat(ConfigDocument.parse("a: 5\na.b: 1\n").readings("a.b"))
                .containsExactly(Collections.singletonList("a.b"));
        assertThat(ConfigDocument.parse("other: 1\n").readings("a.b")).isEmpty();
    }

    @Test void keysInsideTheSettingsValueAreNotSplit() throws ConfigParseException {
        ConfigDocument document = ConfigDocument.parse("emoji:\n  o.O: wink\n  o:\n    O: split\n");
        assertThat(document.readings("emoji")).containsExactly(Collections.singletonList("emoji"));
        assertThat(ConfigDocument.readings(document.toPlain(), "emoji")).containsExactly(Collections.singletonList("emoji"));
        assertThat(document.get(Arrays.asList("emoji", "o.O"))).isEqualTo("wink");
    }
}
