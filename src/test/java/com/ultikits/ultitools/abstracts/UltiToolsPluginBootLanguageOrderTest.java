package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #540: a module resolves its language catalogue only after its bundled resources are extracted,
 * so the catalogue in use right after start-up is the one the next reload resolves.
 * <p>
 * Before the fix, the constructors resolved the language first and extracted resources second. A
 * stale {@code lang/en.yml} left by an older jar beside a deleted {@code lang/en.json} was picked at
 * boot (the first extension present on disk), the fresh {@code en.json} was extracted a moment
 * later, and the next {@code /ul reload} switched to it -- two catalogues from the same files.
 */
@DisplayName("#540: the boot catalogue is resolved after resource extraction")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiToolsPluginBootLanguageOrderTest {

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = BootLanguageFixture.create(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    @Test
    @DisplayName("a stale en.yml with en.json deleted: boot uses the freshly extracted en.json, same as reload")
    void bootUsesTheFreshlyExtractedJsonAndMatchesTheNextReload() throws Exception {
        fixture.jarEntry("lang/en.json", "{\"greeting\":\"B from the jar's en.json\"}")
                .jarEntry("lang/en.yml", "greeting: C from the jar's en.yml\n")
                .onDisk("lang/en.yml", "greeting: A from a stale en.yml\n");

        UltiToolsPlugin plugin = fixture.construct("1.0.0");
        String atBoot = plugin.i18n("greeting");
        plugin.reloadSelf();
        String afterReload = plugin.i18n("greeting");

        assertThat(fixture.disk("lang/en.json")).exists();
        assertThat(atBoot).isEqualTo("B from the jar's en.json");
        assertThat(afterReload).isEqualTo(atBoot);
    }

    @Test
    @DisplayName("control: with en.json already on disk, boot and reload agree (no behaviour change)")
    void bootAndReloadAgreeWhenJsonIsAlreadyPresent() throws Exception {
        fixture.jarEntry("lang/en.json", "{\"greeting\":\"B from the jar's en.json\"}")
                .onDisk("lang/en.json", "{\"greeting\":\"B from the jar's en.json\"}");

        UltiToolsPlugin plugin = fixture.construct("1.0.0");
        String atBoot = plugin.i18n("greeting");
        plugin.reloadSelf();

        assertThat(atBoot).isEqualTo("B from the jar's en.json").isEqualTo(plugin.i18n("greeting"));
    }
}
