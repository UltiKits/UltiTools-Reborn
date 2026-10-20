package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginListSeeding;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ResourceHashSidecar;

/**
 * Tracer of plan 17-78 (#567 item 1; contract-batching row 01:18 of 2026-10-06): a module copy the load gates reject
 * writes no language file to disk. Construction resolves the module's language from the jar's own catalogue as if the
 * file were on disk; the {@code lang/} half of resource extraction runs only at the commit step, once the copy has
 * passed the gates. The {@code config/} and {@code res/} halves keep their construction-time timing, because
 * {@code initConfig()} reads them right after (#540).
 * <p>
 * Gates measured as reachable after construction: the duplicate-version gate (a newer copy of the same module already
 * loaded; reached through {@code register(UltiToolsPlugin)}, see #506) and the API-version gate (a module built for a
 * newer framework, which an operator can install).
 */
@DisplayName("#567 item 1 tracer: a rejected module copy leaves no language file; an accepted copy extracts at commit")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the framework singleton a test class leaves behind
class RejectedCandidateLanguageTracerTest {

    private static final String EN = "greeting: \"Hello from the jar\"\n";
    private static final String ZH = "greeting: \"来自 jar 的问候\"\n";
    private static final String CONFIG = "x: 1\n";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() throws Exception {
        clearLeakedUltiToolsInstance();
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
        fixture.jarEntry("lang/en.yml", EN).jarEntry("lang/zh.yml", ZH).jarEntry("config/x.yml", CONFIG);
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
        MockBukkitHelper.safeUnmock();
        clearLeakedUltiToolsInstance();
    }

    /**
     * Clears a mocked {@code UltiTools} instance a test class leaves behind (17-74 gate-1 F1): a later class would
     * otherwise log through a mock whose logger is {@code null}.
     */
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private File langDir() {
        return fixture.disk("lang/x").getParentFile();
    }

    private String[] langFiles() {
        String[] names = langDir().list();
        return names == null ? new String[0] : names;
    }

    @Test
    @DisplayName("duplicate-version gate: the rejected older copy creates no file under lang/; config/x.yml is extracted as before")
    void copyRejectedAsAnOlderDuplicateWritesNoLanguageFile() throws Exception {
        UltiToolsPlugin loaded = mock(UltiToolsPlugin.class);
        when(loaded.getMainClass()).thenReturn(BootLanguageFixture.MAIN_CLASS);
        when(loaded.getPluginName()).thenReturn(BootLanguageFixture.MODULE);
        when(loaded.isNewerVersionThan(any())).thenReturn(true);
        PluginListSeeding.add(pluginManager, loaded);

        UltiToolsPlugin olderCopy = fixture.construct("1.0.0");
        assertThat(langFiles()).as("after construction").isEmpty();
        boolean registered = pluginManager.register(olderCopy);

        assertThat(registered).isFalse();
        assertThat(langFiles()).as("after the rejection").isEmpty();
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), "lang/en.yml")).isEmpty();
        assertThat(fixture.disk("config/x.yml")).as("config extraction timing unchanged").exists();
        verify(fixture.logger(), never()).warning(argThat((String message) -> message.contains("lang")));
    }

    @Test
    @DisplayName("API-version gate: a copy built for a newer framework creates no file under lang/")
    void copyRejectedForANewerFrameworkWritesNoLanguageFile() throws Exception {
        UltiToolsPlugin candidate = fixture.construct("1.0.0");
        boolean registered;
        try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(-1);
            registered = pluginManager.register(candidate);
        }

        assertThat(registered).isFalse();
        assertThat(langFiles()).isEmpty();
    }

    @Test
    @DisplayName("accepted copy: construction renders the jar's text; lang/en.yml and lang/zh.yml are extracted and recorded at commit")
    void acceptedCopyExtractsAndRecordsAtCommit() throws Exception {
        UltiToolsPlugin candidate = fixture.construct("2.0.0");
        String duringConstruction = candidate.i18n("greeting");
        assertThat(langFiles()).as("nothing under lang/ before the gates").isEmpty();

        boolean registered;
        try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
            registered = pluginManager.register(candidate);
        }

        assertThat(registered).isTrue();
        assertThat(duringConstruction).isEqualTo("Hello from the jar");
        assertThat(candidate.i18n("greeting")).isEqualTo(duringConstruction);
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/en.yml"))).isEqualTo(EN.getBytes(StandardCharsets.UTF_8));
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.yml"))).isEqualTo(ZH.getBytes(StandardCharsets.UTF_8));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), "lang/en.yml"))
                .contains(ResourceHashSidecar.sha256(fixture.disk("lang/en.yml")));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), "lang/zh.yml"))
                .contains(ResourceHashSidecar.sha256(fixture.disk("lang/zh.yml")));
        verify(fixture.logger(), never()).warning(anyString());
    }

    @Test
    @DisplayName("#540 kept: a stale en.yml with en.json deleted renders the jar's en.json at construction, before anything is extracted")
    void constructionResolvesAsIfTheJarCopyWereOnDisk() throws Exception {
        fixture.jarEntry("lang/en.json", "{\"greeting\":\"B from the jar's en.json\"}")
                .onDisk("lang/en.yml", "greeting: A from a stale en.yml\n");

        UltiToolsPlugin candidate = fixture.construct("1.0.0");

        assertThat(candidate.i18n("greeting")).isEqualTo("B from the jar's en.json");
        assertThat(fixture.disk("lang/en.json")).doesNotExist();
    }
}
