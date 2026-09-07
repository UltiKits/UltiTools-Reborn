package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.uat.fixtures.inheritance.BaseAdminCommands;
import com.ultikits.ultitools.uat.fixtures.inheritance.DerivedAdminCommands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the extractor's inheritance/override/multi-executor/bridge attribution against a
 * synthetic fixture pair UltiChat cannot exercise (Phase 10, D-10-05 names UltiChat plus "one
 * UltiEssentials sub-package" as the completeness control set precisely because a flat,
 * two-executor module has no shared base class to inherit or override from).
 * <p>
 * {@link BaseAdminCommands} and {@link DerivedAdminCommands} are compiled into this repository's
 * own test classpath, so -- unlike {@code SurfaceExtractorUltiChatControlTest} -- this test needs
 * no external module build and no system property: it runs {@link SurfaceAssembler} directly over
 * the two fixture classes and always executes for real.
 * <p>
 * The real-module half of Task 2 (one UltiEssentials sub-package, hand-audited against a real
 * {@code SurfaceExtractorMain} run) is recorded in this plan's local (non-committed) evidence
 * tree, and guarded by this plan's own {@code <verify>} shell command (a floor-count assertion
 * against the real UltiEssentials module) rather than by a JUnit test here, since it requires
 * building a separate module checkout.
 *
 * @since 6.3.0
 */
@DisplayName("Inheritance control: BaseAdminCommands/DerivedAdminCommands attribution")
class SurfaceExtractorInheritanceControlTest {

    private static final String CONTROL_RESOURCE = "/uat/inheritance-control.json";

    @Test
    @DisplayName("the fixture pair's identity set equals the hand-derived control, in both directions")
    void syntheticFixtureMatchesHandDerivedControl() throws IOException {
        SurfaceAssembler.AssembledSurface surface = assembleFixture();

        Set<Identity> generated = new HashSet<>();
        for (Map<String, Object> row : surface.getRows()) {
            generated.add(Identity.fromRow(row));
        }
        Set<Identity> expected = readControlIdentities();

        Set<Identity> missing = new HashSet<>(expected);
        missing.removeAll(generated);
        Set<Identity> extra = new HashSet<>(generated);
        extra.removeAll(expected);

        assertThat(missing).as("rows the control expects but the assembler did not produce").isEmpty();
        assertThat(extra).as("rows the assembler produced but the control does not expect").isEmpty();
    }

    @Test
    @DisplayName("the inherited 'status' mapping is attributed to DerivedAdminCommands, exactly once")
    void inheritedMappingEmitsExactlyOneRowAttributedToTheConcreteClass() {
        SurfaceAssembler.AssembledSurface surface = assembleFixture();
        long statusRowsOnDerived = countMatching(surface, "command", "DerivedAdminCommands", "status");
        assertThat(statusRowsOnDerived).isEqualTo(1L);
    }

    @Test
    @DisplayName("overriding 'reload' collapses to exactly one row, not two")
    void overriddenMappingEmitsExactlyOneRow() {
        SurfaceAssembler.AssembledSurface surface = assembleFixture();
        long reloadRowsOnDerived = countMatching(surface, "command", "DerivedAdminCommands", "reload");
        assertThat(reloadRowsOnDerived).isEqualTo(1L);
    }

    @Test
    @DisplayName("base and derived each carrying their own @CmdExecutor attribute rows without cross-attribution")
    void baseAndDerivedEachAttributeToThemselvesWithDistinctIds() {
        SurfaceAssembler.AssembledSurface surface = assembleFixture();

        Set<String> classesSeen = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> row : surface.getRows()) {
            classesSeen.add(String.valueOf(row.get("cls")));
            ids.add(String.valueOf(row.get("id")));
        }

        assertThat(classesSeen).containsExactlyInAnyOrder("BaseAdminCommands", "DerivedAdminCommands");
        // status/reload/help are named identically under both classes; every id must still be
        // distinct -- a shared id here would mean one class's row silently overwrote the other's.
        assertThat(ids).hasSize(surface.getRows().size());
    }

    @Test
    @DisplayName("the compiler-generated identify(Object) bridge never produces a row")
    void bridgeMethodNeverProducesARow() {
        SurfaceAssembler.AssembledSurface surface = assembleFixture();

        List<Map<String, Object>> identifyRows = new ArrayList<>();
        for (Map<String, Object> row : surface.getRows()) {
            if ("identify".equals(row.get("member"))) {
                identifyRows.add(row);
            }
        }

        assertThat(identifyRows).hasSize(1);
        assertThat(identifyRows.get(0).get("format")).isEqualTo("identify <value>");
        assertThat(identifyRows.get(0).get("cls")).isEqualTo("DerivedAdminCommands");
    }

    private static SurfaceAssembler.AssembledSurface assembleFixture() {
        try {
            List<Class<?>> classes = Arrays.asList(BaseAdminCommands.class, DerivedAdminCommands.class);
            return new SurfaceAssembler().assemble("Fixture", classes);
        } catch (ExtractorException e) {
            throw new IllegalStateException("Fixture assembly failed: " + e.getMessage(), e);
        }
    }

    private static long countMatching(SurfaceAssembler.AssembledSurface surface, String kind, String cls,
            String member) {
        return surface.getRows().stream()
                .filter(row -> kind.equals(row.get("kind")))
                .filter(row -> cls.equals(row.get("cls")))
                .filter(row -> member.equals(row.get("member")))
                .count();
    }

    private static Set<Identity> readControlIdentities() throws IOException {
        try (InputStream stream = SurfaceExtractorInheritanceControlTest.class.getResourceAsStream(CONTROL_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing control resource " + CONTROL_RESOURCE);
            }
            JsonArray rows = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            Set<Identity> identities = new HashSet<>();
            for (JsonElement element : rows) {
                identities.add(Identity.fromJson(element.getAsJsonObject()));
            }
            return identities;
        }
    }

    /** kind/cls/member/format identity tuple, independent of the extractor's own id hash. */
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

        static Identity fromRow(Map<String, Object> row) {
            return new Identity(str(row.get("kind")), str(row.get("cls")), str(row.get("member")),
                    str(row.get("format")));
        }

        static Identity fromJson(JsonObject row) {
            return new Identity(jsonStr(row, "kind"), jsonStr(row, "cls"), jsonStr(row, "member"),
                    jsonStr(row, "format"));
        }

        private static String str(Object value) {
            return value == null ? "" : value.toString();
        }

        private static String jsonStr(JsonObject row, String field) {
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
