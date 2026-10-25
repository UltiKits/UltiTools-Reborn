package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.contract.PendingFixture;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * Every way a module download can disagree with its metadata (plan 22-18, D-28, S-23): a body
 * longer or shorter than {@code sizeBytes}, a hash that differs from the metadata, a response
 * header that differs from the metadata while the body matches, metadata without a usable hash,
 * a redirect, an unavailable version and a stream that breaks midway. None of them may leave a file
 * behind, and a body larger than its declared size must be cut off while it is streaming.
 */
@DisplayName("VerifiedDownload: every integrity failure is detected and leaves nothing behind")
@PendingFixture
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class VerifiedDownloadTest {

    @TempDir
    Path tempDir;

    private StubCatalogue catalogue;
    private Path part;

    @BeforeEach
    void setUp() throws IOException {
        catalogue = StubCatalogue.start();
        PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());
        part = tempDir.resolve("demo-1.1.jar.part");
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        catalogue.close();
    }

    private String downloadUrl() {
        CatalogueClient.Version version = CatalogueClient.version("demo", StubCatalogue.VERSION);
        assertThat(version).isNotNull();
        return CatalogueClient.absoluteDownloadUrl(version.downloadUrl());
    }

    private VerifiedDownload.Result fetchWith(StubCatalogue.Mode mode) {
        catalogue.mode(mode);
        CatalogueClient.Version version = CatalogueClient.version("demo", StubCatalogue.VERSION);
        return VerifiedDownload.fetch(downloadUrl(), version.sha256(), version.sizeBytes(), part);
    }

    private void assertNothingLeft() {
        assertThat(Files.notExists(part)).as("temporary file removed").isTrue();
    }

    @Test
    @DisplayName("a matching body is accepted and the file kept")
    void matchingBody_isAccepted() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.NORMAL);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.OK);
        assertThat(result.path()).isEqualTo(part);
        assertThat(Files.exists(part)).isTrue();
    }

    @Test
    @DisplayName("a body shorter than sizeBytes is a size mismatch")
    void smallerBody_isSizeMismatch() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.TAMPER_SIZE);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.SIZE_MISMATCH);
        assertNothingLeft();
    }

    @Test
    @DisplayName("a body larger than sizeBytes is cut off while streaming, not read to the end")
    void largerBody_isAbortedWhileStreaming() {
        catalogue.mode(StubCatalogue.Mode.HUGE_BODY);
        String url = downloadUrl();

        CatalogueHttp.Download capped = CatalogueHttp.download(url, part, 1024);

        assertThat(capped.kind()).isEqualTo(CatalogueHttp.Kind.OVERSIZE);
        assertThat(capped.bytes()).as("only the cap was kept").isEqualTo(1024);

        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.HUGE_BODY);
        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.SIZE_MISMATCH);
        assertThat(catalogue.clientAborted())
                .as("the client closed the connection instead of reading all 10 MiB")
                .isTrue();
        assertNothingLeft();
    }

    @Test
    @DisplayName("a header that names another hash is a header mismatch even though the body matches")
    void wrongHeader_isHeaderMismatch() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.WRONG_HEADER);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.HEADER_MISMATCH);
        assertNothingLeft();
    }

    @Test
    @DisplayName("no header at all leaves the metadata as the only source, and the file is accepted")
    void absentHeader_leavesTheMetadataAlone() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.NO_HEADER);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.OK);
        assertThat(Files.exists(part)).isTrue();
    }

    @Test
    @DisplayName("upper-case hex is accepted from the response header")
    void upperCaseHeader_isAccepted() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.UPPER_HEADER);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.OK);
    }

    @Test
    @DisplayName("upper-case hex in the metadata is accepted too")
    void upperCaseMetadata_isAccepted() {
        CatalogueClient.Version version = CatalogueClient.version("demo", StubCatalogue.VERSION);

        VerifiedDownload.Result result = VerifiedDownload.fetch(downloadUrl(),
                version.sha256().toUpperCase(java.util.Locale.ROOT), version.sizeBytes(), part);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.OK);
    }

    @Test
    @DisplayName("a missing, empty or malformed sha256 refuses the install without any request")
    void unusableMetadata_makesNoRequest() {
        String url = downloadUrl();
        int requestsBefore = catalogue.paths().size();
        List<String> urls = java.util.Arrays.asList(null, "", "   ", "abc", repeat('a', 63), repeat('z', 64),
                repeat('a', 65));

        for (String sha256 : urls) {
            VerifiedDownload.Result result = VerifiedDownload.fetch(url, sha256, 100, part);

            assertThat(result.kind()).as("sha256 %s", sha256).isEqualTo(VerifiedDownload.Kind.MISSING_METADATA);
            assertNothingLeft();
        }
        VerifiedDownload.Result noSize = VerifiedDownload.fetch(url, repeat('a', 64), 0, part);
        assertThat(noSize.kind()).isEqualTo(VerifiedDownload.Kind.MISSING_METADATA);
        assertThat(catalogue.paths()).as("no request was made for unusable metadata").hasSize(requestsBefore);
    }

    @Test
    @DisplayName("a 3xx is a failure and is never followed: the redirect target is not requested")
    void redirect_isNotFollowed() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.REDIRECT);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.IO_ERROR);
        assertThat(catalogue.paths()).noneMatch(path -> path.contains("redirect-"));
        assertNothingLeft();
    }

    @Test
    @DisplayName("403 and 404 are 'not available', not an integrity failure")
    void unavailableVersion_isNotAvailable() {
        VerifiedDownload.Result forbidden = fetchWith(StubCatalogue.Mode.FORBIDDEN);
        assertThat(forbidden.kind()).isEqualTo(VerifiedDownload.Kind.NOT_AVAILABLE);

        CatalogueClient.Version version = CatalogueClient.version("demo", StubCatalogue.VERSION);
        VerifiedDownload.Result missing = VerifiedDownload.fetch(
                catalogue.baseUrl() + "/v1/catalogue/modules/demo/versions/9.9.9/download",
                version.sha256(), version.sizeBytes(), part);
        assertThat(missing.kind()).isEqualTo(VerifiedDownload.Kind.NOT_AVAILABLE);
        assertNothingLeft();
    }

    @Test
    @DisplayName("a stream that breaks midway is an I/O failure and leaves no file")
    void brokenStream_isIoError() {
        VerifiedDownload.Result result = fetchWith(StubCatalogue.Mode.BROKEN_STREAM);

        assertThat(result.kind()).isEqualTo(VerifiedDownload.Kind.IO_ERROR);
        assertNothingLeft();
    }

    @Test
    @DisplayName("no non-OK result ever leaves the temporary file")
    void noFailureLeavesAFile() {
        for (StubCatalogue.Mode mode : StubCatalogue.Mode.values()) {
            if (mode == StubCatalogue.Mode.NORMAL || mode == StubCatalogue.Mode.NO_HEADER
                    || mode == StubCatalogue.Mode.UPPER_HEADER) {
                continue;
            }
            VerifiedDownload.Result result = fetchWith(mode);
            assertThat(result.kind()).as("%s", mode).isNotEqualTo(VerifiedDownload.Kind.OK);
            assertNothingLeft();
        }
        List<String> leftovers = list();
        assertThat(leftovers).as("no .part file survives a refused download").isEmpty();
    }

    private List<String> list() {
        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }
}
