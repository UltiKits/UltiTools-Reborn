package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.BootLanguageFixture;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ResourceHashSidecar;

/**
 * #460: a module candidate that the load gates reject writes nothing to the shared language files
 * or their provenance record, and renames or backs up nothing. The language provenance decision is
 * computed during construction and committed only after the candidate has passed the gates.
 * <p>
 * Reachability, measured: two jars of the same module in {@code plugins/UltiTools/plugins/} share
 * one class loader and resolve to the same main class and version, so the duplicate-version gate
 * is reached only through a programmatic {@code register(UltiToolsPlugin)} / {@code register(Class)}
 * (the correction recorded on #506). The API-version gate ({@code api-version} newer than the
 * running framework) is reachable by an operator installing a module built for a newer framework.
 */
@DisplayName("#460: a candidate rejected by the load gates leaves the language files untouched")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerRejectedCandidateLanguageTest {

    private static final String LANG = "lang/en.json";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        DataStore dataStore = mock(DataStore.class, CALLS_REAL_METHODS);
        fixture = BootLanguageFixture.create(tempDir, ultiTools -> {
            Mockito.lenient().when(ultiTools.getDataFolder()).thenReturn(tempDir);
            Mockito.lenient().when(ultiTools.getDataStore()).thenReturn(dataStore);
            Mockito.lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        });
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
        MockBukkitHelper.safeUnmock();
    }

    /** A newer version of the fixture module, already loaded, so the fixture candidate is rejected. */
    private void loadNewerVersion() {
        UltiToolsPlugin loaded = mock(UltiToolsPlugin.class);
        when(loaded.getMainClass()).thenReturn(BootLanguageFixture.MAIN_CLASS);
        when(loaded.getPluginName()).thenReturn(BootLanguageFixture.MODULE);
        when(loaded.isNewerVersionThan(any())).thenReturn(true);
        pluginManager.getPluginList().add(loaded);
    }

    private String[] langDirectoryListing() {
        String[] names = fixture.disk(LANG).getParentFile().list();
        Arrays.sort(names);
        return names;
    }

    @Test
    @DisplayName("recorded, untouched file: a rejected older candidate does not refresh it or re-record it")
    void rejectedCandidateLeavesARecordedFileAndItsRecordByteIdentical() throws Exception {
        fixture.jarEntry(LANG, "{\"greeting\":\"text of the rejected older jar\"}")
                .onDisk(LANG, "{\"greeting\":\"text of the accepted newer version\"}")
                .recordCurrent(LANG);
        byte[] fileBefore = BootLanguageFixture.bytesOf(fixture.disk(LANG));
        byte[] recordBefore = BootLanguageFixture.bytesOf(fixture.provenanceRecord());
        loadNewerVersion();

        UltiToolsPlugin olderCandidate = fixture.construct("1.0.0");
        boolean registered = pluginManager.register(olderCandidate);

        assertThat(registered).isFalse();
        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(fileBefore);
        assertThat(BootLanguageFixture.bytesOf(fixture.provenanceRecord())).isEqualTo(recordBefore);
        assertThat(langDirectoryListing()).containsExactly("en.json");
    }

    @Test
    @DisplayName("unrecorded file that differs from the jar: a rejected candidate neither replaces nor backs it up")
    void rejectedCandidateNeitherReplacesNorBacksUpAnUnrecordedDifferingFile() throws Exception {
        fixture.jarEntry(LANG, "{\"greeting\":\"text of the rejected older jar\"}")
                .onDisk(LANG, "{\"greeting\":\"text extracted by a pre-6.3.0 jar\"}");
        byte[] fileBefore = BootLanguageFixture.bytesOf(fixture.disk(LANG));
        byte[] recordBefore = BootLanguageFixture.bytesOf(fixture.provenanceRecord());
        loadNewerVersion();

        UltiToolsPlugin olderCandidate = fixture.construct("1.0.0");
        boolean registered = pluginManager.register(olderCandidate);

        assertThat(registered).isFalse();
        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(fileBefore);
        assertThat(BootLanguageFixture.bytesOf(fixture.provenanceRecord())).isEqualTo(recordBefore);
        assertThat(langDirectoryListing()).containsExactly("en.json");
    }

    @Test
    @DisplayName("an accepted candidate commits its refresh as before")
    void acceptedCandidateCommitsTheRefresh() throws Exception {
        String jarText = "{\"greeting\":\"text of the accepted jar\"}";
        fixture.jarEntry(LANG, jarText)
                .onDisk(LANG, "{\"greeting\":\"text of the previous version\"}")
                .recordCurrent(LANG);

        UltiToolsPlugin candidate = fixture.construct("2.0.0");
        boolean registered;
        // getPluginVersion() reads the framework's own env.yml resource, which the mocked UltiTools
        // cannot serve; every other static member stays real.
        try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
            registered = pluginManager.register(candidate);
        }

        assertThat(registered).isTrue();
        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG)))
                .isEqualTo(jarText.getBytes(StandardCharsets.UTF_8));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), LANG))
                .contains(ResourceHashSidecar.sha256(fixture.disk(LANG)));
        assertThat(candidate.i18n("greeting")).isEqualTo("text of the accepted jar");
    }
}
