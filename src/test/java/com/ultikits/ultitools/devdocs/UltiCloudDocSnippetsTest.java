package com.ultikits.ultitools.devdocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;

/**
 * The Java code blocks of the UltiCloud developer documentation (plan 22-27), compiled and run here.
 * <p>
 * Each block a documentation page shows lives in this class between a pair of
 * {@code docs-snippet:<name>:begin} / {@code :end} marker comments, as an inner class written exactly as a
 * module would write it, and a test runs it against the real public API. A page marks the block it shows
 * with the HTML comment {@code <!-- UltiCloudDocSnippetsTest: <name> -->} on the line before the fence;
 * when the system property {@value #DEVDOC_ROOT_PROPERTY} names a checkout of the documentation
 * repository, the page tests assert that the block equals the snippet text (indentation removed), so the
 * page and the compiled code cannot drift apart. Without the property (the framework's own CI has no
 * documentation checkout) the page comparisons are skipped by assumption and only the compile-and-run
 * tests run.
 * <p>
 * Run with the comparison:
 * {@code mvn -B test -Dtest=UltiCloudDocSnippetsTest -Dultitools.devdoc.root=/path/to/UltiTools-Dev-Doc}.
 */
@DisplayName("UltiCloud documentation snippets compile, run and match the pages (6.3.1)")
class UltiCloudDocSnippetsTest {

    /** The system property naming the documentation checkout's root directory. */
    static final String DEVDOC_ROOT_PROPERTY = "ultitools.devdoc.root";

    private static final Path SOURCE =
            Paths.get("src/test/java/com/ultikits/ultitools/devdocs/UltiCloudDocSnippetsTest.java");

    private static final String PLAYER_LINK_EN = "docs/src/guide/advanced/player-link.md";
    private static final String PLAYER_LINK_ZH = "docs/src/zh/guide/advanced/player-link.md";

    // docs-snippet:player-link-handler:begin
    public class PanelLinkHandler implements CloudPlayerLinks.LinkEventHandler {

        private final Map<UUID, String> accounts = new ConcurrentHashMap<>();

        /** Call from registerSelf() with UltiTools.getInstance().getCloudPlayerLinks(). */
        public void install(UltiToolsPlugin module, CloudPlayerLinks links) {
            links.setHandler(module, this);
        }

        /** Call from onUnregister(); the framework also releases the handler when the module unloads. */
        public void uninstall(UltiToolsPlugin module, CloudPlayerLinks links) {
            links.removeHandler(module);
        }

        @Override
        public CompletableFuture<CloudPlayerLinks.GameAccountResult> onCompleted(
                CloudPlayerLinks.LinkCompletion completion) {
            if (!completion.provisionGameAccount()) {
                return CompletableFuture.completedFuture(CloudPlayerLinks.GameAccountResult.NOT_REQUESTED);
            }
            // Runs on a framework worker thread: blocking work, such as a database write, is allowed here.
            String previous = accounts.putIfAbsent(completion.playerUuid(), completion.playerName());
            return CompletableFuture.completedFuture(previous == null
                    ? CloudPlayerLinks.GameAccountResult.CREATED
                    : CloudPlayerLinks.GameAccountResult.ACCOUNT_EXISTS);
        }

        @Override
        public void onExpired(CloudPlayerLinks.LinkExpiry expiry) {
            // Optional: tell the player, if online, that the link can no longer be used.
        }
    }
    // docs-snippet:player-link-handler:end

    // docs-snippet:player-link-create:begin
    public class PanelLinkRequest {

        /** Asks for a link for one player; send the returned text to the player on the main thread. */
        public CompletableFuture<String> request(UltiToolsPlugin module, CloudPlayerLinks links,
                                                 UUID playerUuid, String playerName, boolean loggedIn) {
            CloudPlayerLinks.GameAuth auth = CloudPlayerLinks.GameAuth.authPlugin(loggedIn, true);
            return links.create(module, playerUuid, playerName, auth,
                            EnumSet.of(CloudPlayerLinks.LinkProof.IN_GAME_SESSION,
                                    CloudPlayerLinks.LinkProof.LINKED_WEB_ACCOUNT))
                    .thenApply(creation -> {
                        if (creation.failure() == null) {
                            return "Open " + creation.url() + " within 10 minutes. The link works once.";
                        }
                        switch (creation.failure()) {
                            case NOT_CONNECTED:
                                return "This server is not connected to UltiCloud.";
                            case RATE_LIMITED:
                                return "Too many links were requested. Try again later.";
                            case LINK_NOT_PERMITTED:
                                return "UltiCloud does not allow a link for this server or player.";
                            default:
                                return "No link could be created. Try again later.";
                        }
                    });
        }
    }
    // docs-snippet:player-link-create:end

    @Test
    @DisplayName("player-link request: offers the proofs, renders a link and every failure")
    void playerLinkRequestRuns() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        CloudPlayerLinks links = new CloudPlayerLinks(transport);
        UltiToolsPlugin module = mock(UltiToolsPlugin.class);
        UUID player = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        PanelLinkRequest request = new PanelLinkRequest();

        transport.answer = CloudPlayerLinks.LinkCreation.created("01JBQ0000000000000000000L1",
                "https://panel.example.invalid/link/abc", java.time.Instant.ofEpochSecond(1_800_000_600L),
                Arrays.asList(CloudPlayerLinks.LinkProof.IN_GAME_SESSION));
        assertThat(request.request(module, links, player, "Steve", true).get(5, TimeUnit.SECONDS))
                .isEqualTo("Open https://panel.example.invalid/link/abc within 10 minutes. The link works once.");
        assertThat(transport.offered.get(0)).containsExactlyInAnyOrder(CloudPlayerLinks.LinkProof.IN_GAME_SESSION,
                CloudPlayerLinks.LinkProof.LINKED_WEB_ACCOUNT);

        for (CloudPlayerLinks.Failure failure : CloudPlayerLinks.Failure.values()) {
            transport.answer = CloudPlayerLinks.LinkCreation.failed(failure, "safe text");
            assertThat(request.request(module, links, player, "Steve", false).get(5, TimeUnit.SECONDS))
                    .as("text for %s", failure).isNotEmpty().doesNotContain("null");
        }
        assertThat(transport.events).containsOnly("create");
    }

    @Test
    @DisplayName("player-link request: both pages show the compiled snippet unchanged")
    void playerLinkRequestPages() throws IOException {
        assertPageShows(PLAYER_LINK_EN, "player-link-create");
        assertPageShows(PLAYER_LINK_ZH, "player-link-create");
    }

    @Test
    @DisplayName("player-link handler: installs on the API, provisions once, answers every completion")
    void playerLinkHandlerRuns() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        CloudPlayerLinks links = new CloudPlayerLinks(transport);
        UltiToolsPlugin module = mock(UltiToolsPlugin.class);
        PanelLinkHandler handler = new PanelLinkHandler();

        handler.install(module, links);
        assertThat(transport.events).containsExactly("setHandler");
        assertThat(transport.handler).isSameAs(handler);
        assertThat(transport.owner).isSameAs(module);

        UUID player = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        CloudPlayerLinks.LinkCompletion provision = new CloudPlayerLinks.LinkCompletion("01JBQ0000000000000000000A1",
                "01JBQ0000000000000000000L1", CloudPlayerLinks.LinkProof.WEB_REGISTRATION, player, "Steve", true,
                "steve-web");
        assertThat(transport.handler.onCompleted(provision).get(5, TimeUnit.SECONDS))
                .isEqualTo(CloudPlayerLinks.GameAccountResult.CREATED);
        assertThat(transport.handler.onCompleted(provision).get(5, TimeUnit.SECONDS))
                .isEqualTo(CloudPlayerLinks.GameAccountResult.ACCOUNT_EXISTS);

        CloudPlayerLinks.LinkCompletion noAccount = new CloudPlayerLinks.LinkCompletion("01JBQ0000000000000000000A2",
                "01JBQ0000000000000000000L2", CloudPlayerLinks.LinkProof.IN_GAME_SESSION, player, "Steve", false, null);
        assertThat(transport.handler.onCompleted(noAccount).get(5, TimeUnit.SECONDS))
                .isEqualTo(CloudPlayerLinks.GameAccountResult.NOT_REQUESTED);

        transport.handler.onExpired(new CloudPlayerLinks.LinkExpiry("01JBQ0000000000000000000L3", player, "Steve",
                CloudPlayerLinks.ExpiryReason.EXPIRED));

        handler.uninstall(module, links);
        assertThat(transport.events).containsExactly("setHandler", "removeHandler");
    }

    @Test
    @DisplayName("player-link handler: the English page shows the compiled snippet unchanged")
    void playerLinkHandlerEnglishPage() throws IOException {
        assertPageShows(PLAYER_LINK_EN, "player-link-handler");
    }

    @Test
    @DisplayName("player-link handler: the Chinese page shows the compiled snippet unchanged")
    void playerLinkHandlerChinesePage() throws IOException {
        assertPageShows(PLAYER_LINK_ZH, "player-link-handler");
    }

    // ---------------------------------------------------------------------------------------------
    // Page comparison
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts that the page under the documentation root shows, in the fenced block after its
     * {@code UltiCloudDocSnippetsTest: <name>} marker, exactly the snippet {@code name} of this source file.
     */
    static void assertPageShows(String page, String name) throws IOException {
        String root = System.getProperty(DEVDOC_ROOT_PROPERTY);
        assumeTrue(root != null && !root.trim().isEmpty(),
                "no documentation checkout given (-D" + DEVDOC_ROOT_PROPERTY + "); page comparison skipped");
        Path file = Paths.get(root, page);
        assertThat(file).as("documentation page %s", page).isRegularFile();
        assertThat(pageBlock(read(file), name)).as("the %s block of %s", name, page).isEqualTo(snippet(name));
    }

    /** The snippet {@code name} of this source file, its common indentation removed. */
    static String snippet(String name) throws IOException {
        List<String> lines = lines(read(SOURCE));
        int begin = indexOf(lines, "// docs-snippet:" + name + ":begin", 0);
        int end = indexOf(lines, "// docs-snippet:" + name + ":end", begin + 1);
        assertThat(begin).as("begin marker of snippet %s", name).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker of snippet %s", name).isGreaterThan(begin);
        return dedent(lines.subList(begin + 1, end));
    }

    /** The fenced block that follows the page's marker for {@code name}. */
    static String pageBlock(String page, String name) {
        List<String> lines = lines(page);
        int marker = indexOf(lines, "<!-- UltiCloudDocSnippetsTest: " + name + " -->", 0);
        assertThat(marker).as("page marker for snippet %s", name).isGreaterThanOrEqualTo(0);
        assertThat(marker + 1).as("a fence after the marker of %s", name).isLessThan(lines.size());
        assertThat(lines.get(marker + 1).trim()).as("fence after the marker of %s", name).isEqualTo("```java");
        int close = indexOf(lines, "```", marker + 2);
        assertThat(close).as("closing fence of %s", name).isGreaterThan(marker + 1);
        return dedent(lines.subList(marker + 2, close));
    }

    private static int indexOf(List<String> lines, String trimmed, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(trimmed)) {
                return i;
            }
        }
        return -1;
    }

    private static String dedent(List<String> block) {
        int indent = Integer.MAX_VALUE;
        for (String line : block) {
            if (!line.trim().isEmpty()) {
                int spaces = 0;
                while (spaces < line.length() && line.charAt(spaces) == ' ') {
                    spaces++;
                }
                indent = Math.min(indent, spaces);
            }
        }
        List<String> out = new ArrayList<>(block.size());
        for (String line : block) {
            String stripped = line.trim().isEmpty() ? "" : line.substring(indent);
            out.add(stripTrailing(stripped));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return String.join("\n", out);
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static List<String> lines(String text) {
        return Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------------------------------

    /** A player-link transport that records what the public API forwards to it. */
    static final class RecordingTransport implements CloudPlayerLinks.Transport {
        final List<String> events = new ArrayList<>();
        UltiToolsPlugin owner;
        CloudPlayerLinks.LinkEventHandler handler;
        final List<Set<CloudPlayerLinks.LinkProof>> offered = new ArrayList<>();
        CloudPlayerLinks.LinkCreation answer = CloudPlayerLinks.LinkCreation.failed(
                CloudPlayerLinks.Failure.NOT_CONNECTED, "not connected");

        @Override
        public CompletableFuture<CloudPlayerLinks.LinkCreation> create(UltiToolsPlugin requester, UUID playerUuid,
                String playerName, CloudPlayerLinks.GameAuth gameAuth, Set<CloudPlayerLinks.LinkProof> offeredProofs) {
            events.add("create");
            offered.add(offeredProofs);
            return CompletableFuture.completedFuture(answer);
        }

        @Override
        public void setHandler(UltiToolsPlugin owner, CloudPlayerLinks.LinkEventHandler handler) {
            events.add("setHandler");
            this.owner = owner;
            this.handler = handler;
        }

        @Override
        public void removeHandler(UltiToolsPlugin owner) {
            events.add("removeHandler");
            if (this.owner == owner) {
                this.handler = null;
                this.owner = null;
            }
        }
    }
}
