package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Round on {@code PosixAttributePreserver.java:104} (thread {@code PRRT_kwDOIcF9Es6i12fM}, P2):
 * {@link PosixAttributePreserver#copyIfSupported} used to fold two different failure modes into
 * one outer catch and return {@code true} (proceed) for both -- "this filesystem has no POSIX
 * view" (nothing to preserve, proceeding is correct) and "a POSIX view exists but reading it
 * failed" (something WAS there to preserve and this call does not know what). The second case
 * silently let the caller's replacement keep {@code createTempFile}'s process-default identity,
 * reopening on the error path exactly the hole the ownership-preservation fix (Codex rounds 8/9)
 * closed on the happy path.
 * <p>
 * {@link PosixAttributePreserver#copyIfSupported} takes a {@link File}, not an injectable {@link
 * PosixFileAttributeView} -- it is not directly testable by constructing a stub view and passing
 * it in without changing the method's public signature (a change out of proportion to this fix).
 * Mockito's static mocking of {@link Files#getFileAttributeView} is the route used instead: it
 * lets a mock {@link PosixFileAttributeView} stand in for the real one this method looks up
 * internally, while every other {@code Files} static method keeps its real behaviour via {@code
 * mockStatic(Files.class, RETURNS_DEFAULTS)}'s selective stubbing (only the specific overload
 * called with this test's exact {@code source} path is overridden).
 */
@DisplayName("PosixAttributePreserver.copyIfSupported -- unreadable source attributes vs. no POSIX view (round on :104, P2)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PosixAttributePreserverTest {

    @TempDir
    File tempDir;

    @Test
    @DisplayName("a POSIX view that exists but throws IOException from readAttributes() abandons "
            + "the replacement (returns false) and reports it as an unreadable-attributes failure, "
            + "not a permission or ownership failure")
    void unreadableSourceAttributesAbandonsReplacement() throws IOException {
        File source = new File(tempDir, "source.txt");
        Files.write(source.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        File target = File.createTempFile("target", ".tmp", tempDir);

        PosixFileAttributeView unreadableView = mock(PosixFileAttributeView.class);
        when(unreadableView.readAttributes()).thenThrow(new IOException("simulated transient read failure"));

        List<String> attributesUnreadableCalls = new ArrayList<>();
        List<String> permissionFailureCalls = new ArrayList<>();
        List<String> ownershipFailureCalls = new ArrayList<>();

        boolean result;
        // RETURNS_DEFAULTS as the fallback answer: every Files static call this test does not
        // explicitly stub (Files.write above already ran before the mock was installed, and
        // Files.createTempFile is on java.io.File, not java.nio.file.Files) keeps its real
        // behaviour; only the one getFileAttributeView(source.toPath(), ...) overload is replaced.
        try (MockedStatic<Files> filesMock = mockStatic(Files.class, RETURNS_DEFAULTS)) {
            filesMock.when(() -> Files.getFileAttributeView(eq(source.toPath()), eq(PosixFileAttributeView.class)))
                    .thenReturn(unreadableView);

            result = PosixAttributePreserver.copyIfSupported(source, target,
                    () -> attributesUnreadableCalls.add("called"),
                    () -> permissionFailureCalls.add("called"),
                    (owner, group) -> ownershipFailureCalls.add(owner + ":" + group));
        }

        assertThat(result).isFalse();
        assertThat(attributesUnreadableCalls).hasSize(1);
        assertThat(permissionFailureCalls).isEmpty();
        assertThat(ownershipFailureCalls).isEmpty();
    }

    @Test
    @DisplayName("no POSIX view at all (the lookup itself returns null) still proceeds -- pinning "
            + "the two branches apart: absence of a view is NOT treated as a read failure")
    void noPosixViewAtAllStillProceeds() throws IOException {
        File source = new File(tempDir, "source-no-view.txt");
        Files.write(source.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        File target = File.createTempFile("target-no-view", ".tmp", tempDir);

        List<String> attributesUnreadableCalls = new ArrayList<>();
        List<String> permissionFailureCalls = new ArrayList<>();
        List<String> ownershipFailureCalls = new ArrayList<>();

        boolean result;
        try (MockedStatic<Files> filesMock = mockStatic(Files.class, RETURNS_DEFAULTS)) {
            filesMock.when(() -> Files.getFileAttributeView(eq(source.toPath()), eq(PosixFileAttributeView.class)))
                    .thenReturn(null);

            result = PosixAttributePreserver.copyIfSupported(source, target,
                    () -> attributesUnreadableCalls.add("called"),
                    () -> permissionFailureCalls.add("called"),
                    (owner, group) -> ownershipFailureCalls.add(owner + ":" + group));
        }

        assertThat(result).isTrue();
        assertThat(attributesUnreadableCalls).isEmpty();
        assertThat(permissionFailureCalls).isEmpty();
        assertThat(ownershipFailureCalls).isEmpty();
    }
}
