package com.ultikits.ultitools.uat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the extractor's real, generated UltiChat surface to a control fixture derived entirely
 * by reading {@code Modules/UltiChat/src/main/java} by hand (Phase 10, D-10-05) -- never by
 * running the extractor first and blessing its own output, which would attest to nothing.
 * <p>
 * The comparison uses each row's readable identity tuple ({@code kind}/{@code cls}/
 * {@code member}/{@code format}), not the extractor's own {@code id} hash: a control built from
 * the hash would only prove the hash is reproducible, not that the extractor found every
 * function a human reading the source finds.
 * <p>
 * A live UltiChat build is a separate module checkout with its own build lifecycle and is not
 * part of this repository's own {@code mvn test} run. Rather than require one unconditionally,
 * this test reads the path to an already-generated {@code surface.json} from the
 * {@value #SURFACE_PROPERTY} system property and skips -- with an explicit message, never a
 * silent pass -- when the property is absent, so a contributor's plain {@code mvn test} is never
 * broken by a module this repository does not build. The phase's own verification passes
 * {@code -Duat.surface=<path>}, and this test then genuinely compares real, freshly generated
 * output; skipping in that invocation is treated as a failure by the plan's own {@code fails_when}.
 *
 * @since 6.3.0
 */
@DisplayName("UltiChat control: generated surface equals a hand-read audit of the source")
class SurfaceExtractorUltiChatControlTest {

    private static final String SURFACE_PROPERTY = "uat.surface";
    private static final String CONTROL_RESOURCE = "/uat/ultichat-control.json";

    @Test
    @DisplayName("the generated UltiChat surface's identity set equals the hand-audited control, in both directions")
    void generatedSurfaceMatchesHandAuditedControl() throws IOException {
        String surfacePath = System.getProperty(SURFACE_PROPERTY);
        Assumptions.assumeTrue(surfacePath != null && !surfacePath.trim().isEmpty(),
                "Skipping: system property '" + SURFACE_PROPERTY + "' is not set. This control "
                        + "compares against a real, already-generated UltiChat surface.json, which "
                        + "requires building the separate UltiChat module checkout and is not part "
                        + "of a plain 'mvn test' run of this repository. Pass -D" + SURFACE_PROPERTY
                        + "=<path to generated surface.json> to run this control for real.");

        Set<Identity> generated = readGeneratedIdentities(Paths.get(surfacePath));
        Set<Identity> expected = readControlIdentities();

        Set<Identity> missingFromGenerated = new HashSet<>(expected);
        missingFromGenerated.removeAll(generated);
        Set<Identity> extraInGenerated = new HashSet<>(generated);
        extraInGenerated.removeAll(expected);

        assertThat(missingFromGenerated)
                .as("rows the hand audit expects but the extractor did not produce")
                .isEmpty();
        assertThat(extraInGenerated)
                .as("rows the extractor produced but the hand audit does not expect")
                .isEmpty();
    }

    private static Set<Identity> readGeneratedIdentities(Path surfacePath) throws IOException {
        String json = new String(Files.readAllBytes(surfacePath), StandardCharsets.UTF_8);
        JsonObject document = JsonParser.parseString(json).getAsJsonObject();
        JsonArray items = document.getAsJsonArray("items");
        Set<Identity> identities = new HashSet<>();
        for (JsonElement element : items) {
            identities.add(Identity.fromRow(element.getAsJsonObject()));
        }
        return identities;
    }

    private static Set<Identity> readControlIdentities() throws IOException {
        try (InputStream stream = SurfaceExtractorUltiChatControlTest.class.getResourceAsStream(CONTROL_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing control resource " + CONTROL_RESOURCE);
            }
            JsonArray rows = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            Set<Identity> identities = new HashSet<>();
            for (JsonElement element : rows) {
                identities.add(Identity.fromRow(element.getAsJsonObject()));
            }
            return identities;
        }
    }

    /**
     * The {@code kind}/{@code cls}/{@code member}/{@code format} identity tuple a human reads
     * off the source, independent of the extractor's own {@code id} hash scheme.
     */
    private static final class Identity {
        private final String kind;
        private final String cls;
        private final String member;
        private final String format;

        private Identity(String kind, String cls, String member, String format) {
            this.kind = kind;
            this.cls = cls;
            this.member = member;
            this.format = format;
        }

        static Identity fromRow(JsonObject row) {
            return new Identity(
                    stringOrEmpty(row, "kind"),
                    stringOrEmpty(row, "cls"),
                    stringOrEmpty(row, "member"),
                    stringOrEmpty(row, "format"));
        }

        private static String stringOrEmpty(JsonObject row, String field) {
            JsonElement value = row.get(field);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Identity)) {
                return false;
            }
            Identity other = (Identity) o;
            return kind.equals(other.kind) && cls.equals(other.cls)
                    && member.equals(other.member) && format.equals(other.format);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, cls, member, format);
        }

        @Override
        public String toString() {
            return kind + "|" + cls + "|" + member + "|" + format;
        }
    }
}
