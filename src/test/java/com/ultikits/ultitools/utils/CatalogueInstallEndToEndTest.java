package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.PendingFixture;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * {@code /upm install} against the catalogue of protocol v2, end to end (plan 22-18, D-27, D-28).
 * A JAR whose byte count and SHA-256 match the version's metadata is installed; a JAR one byte
 * away from the hash its metadata names is discarded before anything reaches the modules folder,
 * and the operator is told once, without the installed file ever existing.
 */
@DisplayName("/upm install against the v2 catalogue: a matching JAR installs, a tampered one is discarded")
@PendingFixture
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CatalogueInstallEndToEndTest {

    @TempDir
    File serverRoot;

    private File modules;
    private StubCatalogue catalogue;
    private MockedStatic<UltiTools> ultiTools;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = new File(new File(serverRoot, "plugins"), "UltiTools");
        assertThat(dataFolder.mkdirs()).isTrue();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        assertThat(modules.mkdirs()).isTrue();

        UltiTools instance = mock(UltiTools.class);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        ultiTools = mockStatic(UltiTools.class);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(630);

        catalogue = StubCatalogue.start();
        PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());

        Logger logger = Logger.getLogger(PluginInstallUtils.class.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(PluginInstallUtils.class.getName()).removeHandler(capture);
        PluginInstallUtils.resetBaseUrl();
        catalogue.close();
        ultiTools.close();
    }

    private List<String> modulesFolderNames() {
        String[] names = modules.list();
        List<String> list = new ArrayList<>();
        if (names != null) {
            Collections.addAll(list, names);
        }
        Collections.sort(list);
        return list;
    }

    /** What an operator sees: the records at WARNING or above. */
    private List<String> messages() {
        List<String> messages = new ArrayList<>();
        synchronized (records) {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    messages.add(record.getMessage());
                }
            }
        }
        return messages;
    }

    @Test
    @DisplayName("a JAR whose hash and byte count match the version is installed, byte for byte")
    void matchingJar_isInstalled() throws IOException {
        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

        assertThat(result.isInstalled()).isTrue();
        assertThat(result.getFileName()).isEqualTo("demo-1.1.jar");
        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        assertThat(Files.readAllBytes(new File(modules, "demo-1.1.jar").toPath()))
                .isEqualTo(catalogue.jar());
    }

    @Test
    @DisplayName("one byte away from the metadata's hash: nothing installed, no .part left, one integrity line")
    void tamperedJar_isDiscardedBeforeStaging() {
        catalogue.mode(StubCatalogue.Mode.TAMPER_HASH);

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getFileName()).isNull();
        assertThat(modulesFolderNames()).isEmpty();
        // The catalogue text of plan 22-22; the mocked framework has no language loaded, so the key itself is shown.
        assertThat(messages()).containsExactly(
                String.format(PluginInstallUtils.InstallResult.Keys.REASON_INTEGRITY_FAILED, "demo", "1.1"));
    }

    @Test
    @DisplayName("only /v1/catalogue/* is requested, no request carries Authorization, no /plugin/* is reached")
    void onlyV2CataloguePathsAreRequested() {
        assertThat(PluginInstallUtils.installReporting("demo", "1.1").isInstalled()).isTrue();

        List<String> paths = catalogue.paths();
        assertThat(paths).isNotEmpty();
        for (StubCatalogue.Request request : catalogue.requests()) {
            assertThat(request.path()).as(request.toString()).startsWith("/v1/catalogue/");
            assertThat(request.authorization()).as(request.toString()).isNull();
        }
        assertThat(paths).noneMatch(path -> path.startsWith("/plugin/"));
    }

    /**
     * Answers as long as the catalogue contract allows must be read whole (plan 22-18 fix round,
     * gap 1): a client that cuts every answer at 64 KiB turns a module with a long description, or a
     * version page with long changelogs, into an unreadable answer -- an operator sees "Install
     * failed!" and the update lookup reports FAILED. The bound is therefore derived from the
     * contract, and an answer beyond it is a refused answer with its own line, never a parse error.
     */
    @Nested
    @DisplayName("answers the contract allows are read whole; one beyond the bound is a refused answer")
    class LongAnswers {

        @Test
        @DisplayName("a module describing 25,000 four-byte characters is read, installed and looked up")
        void longDescription_isRead() {
            catalogue.longDescription(25_000);

            PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

            assertThat(result.isInstalled()).isTrue();
            assertThat(PluginInstallUtils.getPlugin("demo").getDescription()).hasSize(50_000);
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.FOUND);
        }

        @Test
        @DisplayName("a page of 20 versions with 4,000-character changelogs is read and the latest installed")
        void longChangelogs_areRead() {
            catalogue.versionChangelogs(20, 4_000);

            assertThat(PluginInstallUtils.getPluginVersions("demo")).hasSize(20);
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.FOUND);
            assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();
        }

        @Test
        @DisplayName("an answer beyond the bound is 'response too large' with one value-free line, never a parse error")
        void answerBeyondTheBound_isRefusedAsTooLarge() {
            // 2,000,000 four-byte characters: about 8 MB, above any bound the contract needs.
            catalogue.longDescription(2_000_000);
            List<LogRecord> lines = new ArrayList<>();
            Handler captureHttp = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    synchronized (lines) {
                        lines.add(record);
                    }
                }

                @Override
                public void flush() {
                    // nothing buffered
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
            Logger http = Logger.getLogger(CatalogueHttp.class.getName());
            http.setUseParentHandlers(false);
            http.addHandler(captureHttp);
            try {
                VersionLookup lookup = PluginInstallUtils.lookUpPluginLatestVersion("demo");

                assertThat(lookup.getOutcome()).isEqualTo(VersionLookup.Outcome.FAILED);
                assertThat(lookup.getFailure()).isEqualTo("response too large");
                synchronized (lines) {
                    assertThat(lines).anySatisfy(record -> assertThat(record.getMessage())
                            .contains("byte limit")
                            .doesNotContain("Demo"));
                }
            } finally {
                http.removeHandler(captureHttp);
            }
        }
    }
    /**
     * The operator's line has to name what actually happened (plan 22-18 fix round, gap 3): a
     * timeout or a 5xx is a transport failure, a download URL outside the catalogue's host is a
     * refusal, a 403/404 is a version that is not offered, and a {@code license_required} entry
     * needs a licence. One line each, naming module, version and reason, never a URL.
     */
    @Nested
    @DisplayName("each download refusal names its own reason")
    class RefusalReasons {

        @Test
        @DisplayName("a download URL on another host is refused before any request, said as a refusal")
        void foreignDownloadUrl_isRefusedWithoutARequest() {
            catalogue.mode(StubCatalogue.Mode.FOREIGN_URL);

            PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

            assertThat(result.isInstalled()).isFalse();
            assertThat(catalogue.paths()).as("nothing was requested from the foreign host or locally")
                    .noneMatch(path -> path.endsWith("/download"));
            assertThat(messages()).containsExactly(
                    "demo 1.1 was refused: the download is not on the catalogue host; nothing was installed.");
            assertThat(modulesFolderNames()).isEmpty();
        }

        @Test
        @DisplayName("403 is 'not available', neither a licence nor an integrity failure")
        void forbidden_isNotAvailable() {
            catalogue.mode(StubCatalogue.Mode.FORBIDDEN);

            assertThat(PluginInstallUtils.installReporting("demo", "1.1").isInstalled()).isFalse();

            assertThat(messages()).containsExactly("demo 1.1 is not available for download"
                    + " (withdrawn, unpublished or not licensed); nothing was installed.");
            assertThat(modulesFolderNames()).isEmpty();
        }

        @Test
        @DisplayName("500 is a transport failure ('could not download'), never the catalogue-host wording")
        void serverError_isATransportFailure() {
            catalogue.mode(StubCatalogue.Mode.SERVER_ERROR);

            assertThat(PluginInstallUtils.installReporting("demo", "1.1").isInstalled()).isFalse();

            assertThat(messages()).containsExactly(
                    "Could not download demo 1.1 (the download did not complete); nothing was installed.");
            assertThat(modulesFolderNames()).isEmpty();
        }

        @Test
        @DisplayName("integrity failures share the catalogue reason; unavailable and incomplete downloads keep their own lines")
        void everyKindHasItsOwnLine() {
            List<String> lines = new ArrayList<>();
            lines.add(PluginInstallUtils.failureLine("demo", "1.1", VerifiedDownload.Kind.HASH_MISMATCH));
            lines.add(PluginInstallUtils.failureLine("demo", "1.1", VerifiedDownload.Kind.SIZE_MISMATCH));
            lines.add(PluginInstallUtils.failureLine("demo", "1.1", VerifiedDownload.Kind.HEADER_MISMATCH));
            lines.add(PluginInstallUtils.failureLine("demo", "1.1", VerifiedDownload.Kind.MISSING_METADATA));
            lines.add(PluginInstallUtils.failureLine("demo", "1.1", VerifiedDownload.Kind.NOT_AVAILABLE));
            lines.add(PluginInstallUtils.failureLine("demo", "1.1", VerifiedDownload.Kind.IO_ERROR));

            // Plan 22-22: every integrity failure is the one catalogue reason (which check failed is on the FINE line);
            // a version not offered and a download that did not complete keep their own lines.
            String integrity = String.format(PluginInstallUtils.InstallResult.Keys.REASON_INTEGRITY_FAILED, "demo", "1.1");
            assertThat(lines.subList(0, 4)).containsOnly(integrity);
            assertThat(lines.subList(3, 6)).doesNotHaveDuplicates();
            assertThat(lines.get(4)).isEqualTo("demo 1.1 is not available for download"
                    + " (withdrawn, unpublished or not licensed); nothing was installed.");
            assertThat(lines.get(5)).isEqualTo(
                    "Could not download demo 1.1 (the download did not complete); nothing was installed.");
        }
    }

    /**
     * A catalogue answer may point a download only at the catalogue's own origin (plan 22-18 fix
     * round, gap 4). Every other spelling -- another host, a user-info host, a protocol-relative
     * path, a scheme change -- resolves to nothing, and the install then requests no download.
     */
    @Nested
    @DisplayName("a download URL is accepted only on the catalogue's own origin")
    class DownloadOrigin {

        @Test
        @DisplayName("a relative path is joined; an absolute URL is accepted only on the same origin")
        void onlyTheSameOriginIsAccepted() {
            String base = catalogue.baseUrl();
            String path = "/v1/catalogue/modules/demo/versions/1.1/download";

            assertThat(CatalogueClient.absoluteDownloadUrl(path)).isEqualTo(base + path);
            assertThat(CatalogueClient.absoluteDownloadUrl(base + path)).isEqualTo(base + path);

            String host = base.substring("http://".length());
            for (String foreign : new String[]{
                    "https://evil.example/module.jar",
                    "//evil.example/module.jar",
                    "http://evil.example" + path,
                    base + "@evil.example" + path,
                    "https://" + host + path,
                    "https://" + host + ".evil.example" + path,
                    "evil.example/module.jar"}) {
                assertThat(CatalogueClient.absoluteDownloadUrl(foreign)).as(foreign).isNull();
            }
        }

        @Test
        @DisplayName("a foreign download URL makes the install stop before any download request")
        void foreignUrlMakesNoRequest() {
            catalogue.mode(StubCatalogue.Mode.FOREIGN_URL);

            assertThat(PluginInstallUtils.installReporting("demo", "1.1").isInstalled()).isFalse();

            assertThat(catalogue.paths()).as("only the metadata routes were asked")
                    .noneMatch(requested -> requested.endsWith("/download"));
        }
    }
}
