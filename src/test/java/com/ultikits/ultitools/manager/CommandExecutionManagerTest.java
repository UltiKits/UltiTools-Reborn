package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.assertj.core.api.SoftAssertions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.TestHelper;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * CommandExecutionManager 测试
 */
@DisplayName("CommandExecutionManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CommandExecutionManagerTest {

    private ServerMock server;
    private CommandExecutionManager manager;
    private Logger mockLogger;

    /**
     * The valid list a reload row installs before it loads the invalid value, so the reload rows
     * can tell "the previous valid list was kept" apart from "the shipped default came back".
     */
    private static final String VALID_PREVIOUS_BLOCKLIST = "[stop, deop]";

    /** A string placed inside invalid values; no logged line may contain it. */
    private static final String SENTINEL = "zq-sentinel-84e6";

    private static final String BLOCKLIST_KEY = "ultipanel.commands.blocklist";

    @TempDir
    Path rawConfigDir;

    private int rawConfigCounter;

    /** Every synthetic configuration this test published, in order. */
    private final List<RawConfig> publishedRawConfigs = new ArrayList<>();

    /** The action log the published UltiTools mocks return. */
    private final RemoteActionLog rawActionLog = mock(RemoteActionLog.class);

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock logger
        mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
            // An empty config (absent ultipanel.commands.blocklist key) so the constructor's
            // loadConfiguration() falls through cleanly to the shipped default instead of hitting
            // its failure branch on every test — a real UltiTools.getConfig() never returns null.
            when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
        });

        manager = new CommandExecutionManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * Records what {@link CommandExecutionManager#execute} reports through its protocol v2 sink (the v1
     * {@code command_result} sender was removed in 6.4.0).
     */
    private static final class RecordingSink implements CommandExecutionManager.CommandResultSink {
        String blockedMatched;
        String blockedMessage;
        String failedMessage;
        List<String> completedLines;

        @Override
        public void blocked(String matched, String message) {
            blockedMatched = matched;
            blockedMessage = message;
        }

        @Override
        public void completed(List<String> lines, boolean truncated, long executionMs) {
            completedLines = lines;
        }

        @Override
        public void failed(String message) {
            failedMessage = message;
        }
    }

    /** A main-thread executor that only counts what it is handed, so a refusal can be shown never to dispatch. */
    private static final class CountingExecutor implements java.util.concurrent.Executor {
        int handed;

        @Override
        public void execute(Runnable task) {
            handed++;
        }
    }

    /**
     * Publishes a fresh {@link com.ultikits.ultitools.UltiTools} mock whose {@code getConfig()}
     * returns a real, standalone {@link YamlConfiguration} carrying only
     * {@code ultipanel.commands.blocklist} (or nothing, for {@code blocklist == null}, to pin the
     * absent-key default), then constructs a new {@link CommandExecutionManager} so its
     * constructor's {@code loadConfiguration()} call picks up the mocked config — mirrors
     * {@code FileOperationManagerTest#configureEditableRoots}.
     */
    private CommandExecutionManager createManagerWithConfiguredBlocklist(List<String> blocklist) {
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            YamlConfiguration config = new YamlConfiguration();
            if (blocklist != null) {
                config.set("ultipanel.commands.blocklist", blocklist);
            }
            when(ultiTools.getConfig()).thenReturn(config);
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });
        return new CommandExecutionManager();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该正确创建实例")
        void shouldCreateInstance() {
            // Act
            CommandExecutionManager newManager = new CommandExecutionManager();

            // Assert
            assertThat(newManager).isNotNull();
        }
    }

    @Nested
    @DisplayName("Command Blocklist Security Tests")
    class CommandBlocklistTests {

        @Test
        @DisplayName("should reject blocked commands")
        void shouldRejectBlockedCommands() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            java.util.Set<String> blocked = new java.util.HashSet<>(Arrays.asList("op", "deop", "stop", "ban-ip"));
            newManager.setBlockedCommands(blocked);

            // Assert
            assertThat(newManager.isCommandAllowed("op player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("stop").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("ban-ip 127.0.0.1").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should allow safe commands")
        void shouldAllowSafeCommands() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            java.util.Set<String> blocked = new java.util.HashSet<>(Arrays.asList("op", "deop", "stop"));
            newManager.setBlockedCommands(blocked);

            // Assert
            assertThat(newManager.isCommandAllowed("say Hello").isAllowed()).isTrue();
            assertThat(newManager.isCommandAllowed("list").isAllowed()).isTrue();
            assertThat(newManager.isCommandAllowed("tps").isAllowed()).isTrue();
        }

        @Test
        @DisplayName("should strip leading slash from command")
        void shouldStripLeadingSlash() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            java.util.Set<String> blocked = new java.util.HashSet<>(Collections.singletonList("op"));
            newManager.setBlockedCommands(blocked);

            // Assert
            assertThat(newManager.isCommandAllowed("/op player123").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should reject default dangerous commands")
        void shouldRejectDefaultDangerousCommands() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            // Using default blocklist

            // Assert - test various dangerous commands
            assertThat(newManager.isCommandAllowed("op someuser").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("deop someuser").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("stop").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("restart").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("reload").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("ban-ip 1.2.3.4").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("pardon-ip 1.2.3.4").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("whitelist add player").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("save-off").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("save-all").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should be case insensitive")
        void shouldBeCaseInsensitive() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            java.util.Set<String> blocked = new java.util.HashSet<>(Collections.singletonList("op"));
            newManager.setBlockedCommands(blocked);

            // Assert
            assertThat(newManager.isCommandAllowed("OP player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("Op player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("oP player123").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should reject null or empty commands")
        void shouldRejectNullOrEmptyCommands() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();

            // Assert
            assertThat(newManager.isCommandAllowed(null).isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("   ").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should extract base command correctly")
        void shouldExtractBaseCommandCorrectly() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            java.util.Set<String> blocked = new java.util.HashSet<>(Collections.singletonList("teleport"));
            newManager.setBlockedCommands(blocked);

            // Assert
            assertThat(newManager.isCommandAllowed("teleport player1 player2").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("teleport @a ~ ~ ~").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("  teleport   arg1   arg2  ").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("blocked command should be reported to the sink as blocked, naming the config key")
        void blockedCommandShouldSendErrorResult() {
            RecordingSink sink = new RecordingSink();

            manager.execute("op hacker123", "console", new CountingExecutor(), sink);

            assertThat(sink.blockedMatched).isEqualTo("op");
            // D-05: the refusal names its cause and remedy — the config key and the file —
            // instead of the old bare "blocked by security policy" text.
            assertThat(sink.blockedMessage)
                    .contains("ultipanel.commands.blocklist")
                    .contains("plugins/UltiTools/config.yml");
            assertThat(sink.completedLines).isNull();
        }

        @Test
        @DisplayName("blocked command should not be handed to the main thread")
        void blockedCommandShouldNotBeScheduled() {
            CountingExecutor mainThread = new CountingExecutor();

            manager.execute("stop", "console", mainThread, new RecordingSink());

            assertThat(mainThread.handed).isZero();
        }

        @Test
        @DisplayName("should block namespace-prefixed blocked commands (bukkit:op, minecraft:stop)")
        void shouldBlockNamespacePrefixedCommands() {
            CommandExecutionManager newManager = new CommandExecutionManager();

            assertThat(newManager.isCommandAllowed("bukkit:op player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("minecraft:op player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("bukkit:deop player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("minecraft:stop").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("bukkit:reload").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("minecraft:ban-ip 1.2.3.4").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("bukkit:whitelist add player").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("minecraft:save-off").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("minecraft:save-all").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("bukkit:pardon-ip 1.2.3.4").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("spigot:restart").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should block namespace-prefixed commands with leading slash")
        void shouldBlockNamespacePrefixedCommandsWithSlash() {
            CommandExecutionManager newManager = new CommandExecutionManager();

            assertThat(newManager.isCommandAllowed("/bukkit:op player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("/minecraft:stop").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should block namespace-prefixed commands case-insensitively")
        void shouldBlockNamespacePrefixedCommandsCaseInsensitive() {
            CommandExecutionManager newManager = new CommandExecutionManager();

            assertThat(newManager.isCommandAllowed("Bukkit:OP player123").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("MINECRAFT:STOP").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("Bukkit:Reload").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should allow namespace-prefixed commands that are not in blocklist")
        void shouldAllowSafeNamespacePrefixedCommands() {
            CommandExecutionManager newManager = new CommandExecutionManager();

            assertThat(newManager.isCommandAllowed("bukkit:help").isAllowed()).isTrue();
            assertThat(newManager.isCommandAllowed("minecraft:say Hello").isAllowed()).isTrue();
            assertThat(newManager.isCommandAllowed("essentials:tps").isAllowed()).isTrue();
        }

        @Test
        @DisplayName("namespace-prefixed blocked command should not be handed to the main thread")
        void namespacePrefixedBlockedCommandShouldNotBeScheduled() {
            CountingExecutor mainThread = new CountingExecutor();
            RecordingSink sink = new RecordingSink();

            manager.execute("bukkit:op hacker", "console", mainThread, sink);

            assertThat(mainThread.handed).isZero();
            assertThat(sink.blockedMessage).contains("blocklist");
        }
    }

    @Nested
    @DisplayName("Action-Log Recording Tests")
    class ActionLogRecordingTests {

        private RemoteActionLog mockRemoteActionLog;

        /**
         * Publishes a fresh {@link com.ultikits.ultitools.UltiTools} mock whose
         * {@code getRemoteActionLog()} returns a captured {@link RemoteActionLog} test double —
         * this is what keeps these tests sub-second and filesystem-independent, mirroring
         * {@code CapabilityGateTracerTest}'s pattern — then constructs a fresh
         * {@link CommandExecutionManager} against it.
         */
        private CommandExecutionManager createManagerWithMockedActionLog() {
            mockRemoteActionLog = mock(RemoteActionLog.class);
            // The protocol v2 entry point checks the commands capability itself (default off).
            YamlConfiguration config = new YamlConfiguration();
            config.set(Capability.COMMANDS.getConfigPath(), true);
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                when(ultiTools.getLogger()).thenReturn(mockLogger);
                when(ultiTools.getConfig()).thenReturn(config);
                when(ultiTools.getRemoteActionLog()).thenReturn(mockRemoteActionLog);
            });
            return new CommandExecutionManager();
        }

        @Test
        @DisplayName("a blocklisted command should record exactly one DENIED entry whose reason matches the panel message")
        void deniedCommandShouldRecordExactlyOneDeniedActionLogEntry() {
            // Arrange
            CommandExecutionManager newManager = createManagerWithMockedActionLog();
            RecordingSink sink = new RecordingSink();

            // Act
            newManager.execute("op hacker123", "some-admin", new CountingExecutor(), sink);

            // Assert — exactly one entry, DENIED, and its reason equals what was reported to the panel
            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            RemoteActionLog.Entry entry = entryCaptor.getValue();

            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
            assertThat(entry.getCapability()).isEqualTo(Capability.COMMANDS.name());
            assertThat(entry.getAction()).isEqualTo("execute_command");
            assertThat(entry.getTarget()).isEqualTo("op hacker123");
            assertThat(entry.getActor()).isEqualTo("some-admin");
            assertThat(entry.getReason()).isEqualTo(sink.blockedMessage);
        }

        @Test
        @DisplayName("an allowed command should record exactly one ALLOWED entry before dispatch")
        void allowedCommandShouldRecordExactlyOneAllowedActionLogEntry() {
            // Arrange
            CommandExecutionManager newManager = createManagerWithMockedActionLog();
            CountingExecutor mainThread = new CountingExecutor();

            // Act — no actor: it falls back to the literal "panel"
            newManager.execute("say hello", null, mainThread, new RecordingSink());

            // Assert
            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            RemoteActionLog.Entry entry = entryCaptor.getValue();

            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
            assertThat(entry.getCapability()).isEqualTo(Capability.COMMANDS.name());
            assertThat(entry.getAction()).isEqualTo("execute_command");
            assertThat(entry.getTarget()).isEqualTo("say hello");
            assertThat(entry.getActor()).isEqualTo("panel");
            assertThat(mainThread.handed).as("the decision is recorded before dispatch is handed over").isEqualTo(1);
        }

        @Test
        @DisplayName("an allowed command whose dispatch fails should still yield exactly one ALLOWED entry")
        void allowedCommandWhoseDispatchFailsShouldStillYieldExactlyOneAllowedEntry() {
            // Arrange — a command no registered handler will accept, so the dispatch is refused
            CommandExecutionManager newManager = createManagerWithMockedActionLog();
            RecordingSink sink = new RecordingSink();

            // Act — record the decision, then run the dispatch at once on this (main) thread
            newManager.execute("definitely-not-a-real-command-xyz", "console", Runnable::run, sink);

            // Assert — the policy decision, not the execution result, drives the log: still
            // exactly one entry, still ALLOWED
            assertThat(sink.failedMessage).as("control: the dispatch really was refused").isNotNull();
            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            assertThat(entryCaptor.getValue().getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        }

        @Test
        @DisplayName("a null RemoteActionLog should make every record call a silent no-op")
        void nullRemoteActionLogShouldBeSilentNoOp() {
            // Arrange — getRemoteActionLog() returns null (Mockito's unstubbed default)
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                when(ultiTools.getLogger()).thenReturn(mockLogger);
                when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            });
            CommandExecutionManager newManager = new CommandExecutionManager();
            RecordingSink sink = new RecordingSink();

            // Act & Assert — no exception, command still refused normally
            assertDoesNotThrow(() -> newManager.execute("op hacker", "console", new CountingExecutor(), sink));
            assertThat(sink.blockedMessage).isNotNull();
        }
    }

    // --- Top-level (not @Nested): Surefire's bare -Dtest=Class#method filter used by
    // 06-VALIDATION.md's automated commands does not descend into @Nested classes — verified
    // empirically by Plan 06-03 (FileOperationManagerTest). Placing these four here, not inside
    // CommandBlocklistTests, is what makes that exact command actually run them. ---

    @Test
    @DisplayName("should load the blocklist from ultipanel.commands.blocklist, honoring removals and empty lists")
    void shouldLoadBlocklistFromConfig() {
        // Arrange & Act — a configured list omitting "op" allows it, keeps "stop" blocked
        CommandExecutionManager configuredManager =
                createManagerWithConfiguredBlocklist(Arrays.asList("stop", "deop"));

        // Assert
        assertThat(configuredManager.isCommandAllowed("op").isAllowed()).isTrue();
        assertThat(configuredManager.isCommandAllowed("stop").isAllowed()).isFalse();

        // Act — an explicit empty list is D-03's honest escape value: block nothing
        CommandExecutionManager emptyBlocklistManager =
                createManagerWithConfiguredBlocklist(Collections.emptyList());

        // Assert
        assertThat(emptyBlocklistManager.isCommandAllowed("op").isAllowed()).isTrue();
        assertThat(emptyBlocklistManager.isCommandAllowed("stop").isAllowed()).isTrue();

        // Act — an absent key is NOT the same as an empty list; it falls back to the
        // shipped ten-command default (T-06-12)
        CommandExecutionManager defaultManager = createManagerWithConfiguredBlocklist(null);

        // Assert
        assertThat(defaultManager.isCommandAllowed("op").isAllowed()).isFalse();
        assertThat(defaultManager.isCommandAllowed("say hi").isAllowed()).isTrue();
    }

    @Test
    @DisplayName("should return a refusal that names its config key and file")
    void shouldReturnRefusalWithConfigKey() {
        // Arrange
        CommandExecutionManager newManager = new CommandExecutionManager();

        // Act
        AccessDecision decision = newManager.isCommandAllowed("op");

        // Assert
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.isConfigurable()).isTrue();
        assertThat(decision.getConfigKey()).isEqualTo("ultipanel.commands.blocklist");
        assertThat(decision.getMessage())
                .contains("ultipanel.commands.blocklist")
                .contains("plugins/UltiTools/config.yml");
    }

    @Test
    @DisplayName("should distinguish malformed input from a blocklist hit")
    void shouldDistinguishMalformedInputFromBlocklistHit() {
        // Arrange
        CommandExecutionManager newManager = new CommandExecutionManager();

        // Act
        AccessDecision blank = newManager.isCommandAllowed("   ");
        AccessDecision nullCommand = newManager.isCommandAllowed(null);
        AccessDecision blocked = newManager.isCommandAllowed("op");

        // Assert — malformed input carries no config key, since no config change makes a
        // blank command valid
        assertThat(blank.isConfigurable()).isFalse();
        assertThat(blank.getConfigKey()).isNull();
        assertThat(nullCommand.isConfigurable()).isFalse();
        assertThat(nullCommand.getConfigKey()).isNull();
        assertThat(blocked.isConfigurable()).isTrue();
        assertThat(blank.getReason()).isNotEqualTo(blocked.getReason());
    }

    @Test
    @DisplayName("a command refused by isCommandAllowed should never reach dispatch, through any code path")
    void refusedCommandShouldNeverReachDispatch() {
        // Arrange — the only production call site for the blocklist decision is execute's own
        // refusal branch; no other public method on this manager consults the blocklist
        CommandExecutionManager configuredManager =
                createManagerWithConfiguredBlocklist(Collections.singletonList("help"));
        CountingExecutor mainThread = new CountingExecutor();

        // Act
        configuredManager.execute("help", "console", mainThread, new RecordingSink());

        // Assert
        assertThat(mainThread.handed).isZero();
    }

    @Test
    @DisplayName("should deny blocklisted commands even under a Turkish default locale (dotless-i bypass)")
    void shouldDenyBlocklistedCommandsUnderTurkishLocale() {
        // In tr_TR, String.toLowerCase() (no Locale argument) maps 'I' to the dotless 'ı'
        // (U+0131), not 'i' — so "BAN-IP".toLowerCase() under that locale yields "ban-ıp", which
        // no longer matches the blocklist entry "ban-ip". Pin the fix with the real default-locale
        // switch, not a mocked Locale, since the defect is specifically in an unqualified
        // toLowerCase() call reading JVM global state.
        java.util.Locale originalLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));

            CommandExecutionManager newManager = new CommandExecutionManager();

            // Uppercase forms — these are the ones that diverge under tr_TR toLowerCase()
            assertThat(newManager.isCommandAllowed("BAN-IP 1.2.3.4").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("WHITELIST add player").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("PARDON-IP 1.2.3.4").isAllowed()).isFalse();

            // Lowercase forms must still be denied too, so this test cannot pass by accident
            // (e.g. by a blocklist mutation that happens to also break the uppercase case).
            assertThat(newManager.isCommandAllowed("ban-ip 1.2.3.4").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("whitelist add player").isAllowed()).isFalse();
            assertThat(newManager.isCommandAllowed("pardon-ip 1.2.3.4").isAllowed()).isFalse();
        } finally {
            // MUST restore: a leaked default locale corrupts every later test in the 5340-test
            // suite, not just this class.
            java.util.Locale.setDefault(originalLocale);
        }
    }

    // --- Raw-YAML blocklist validation (case 84e60349, D-05). Top-level, not @Nested, for the
    // same Surefire method-filter reason as the four tests above. ---

    /**
     * Writes a synthetic config.yml whose {@code ultipanel.commands.blocklist} is the given raw
     * YAML text ({@code null} writes a file without the key), loads it with
     * {@link YamlConfiguration#loadConfiguration(java.io.File)} so mappings, nested lists, nulls,
     * numbers and booleans reach the loader as the raw values YAML produced ({@code config.set}
     * cannot express a present null element), and publishes it through a fresh
     * {@link UltiTools} mock with this class's mocked logger.
     */
    private RawConfig publishRawConfig(String blocklistYaml) throws IOException {
        rawConfigCounter++;
        Path file = rawConfigDir.resolve("config-" + rawConfigCounter + ".yml");
        String text = blocklistYaml == null
                ? "ultipanel:\n  commands:\n    timeout: 30\n"
                : "ultipanel:\n  commands:\n    blocklist: " + blocklistYaml + "\n";
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        UltiTools ultiTools = TestHelper.mockUltiToolsInstance(mockUltiTools -> {
            when(mockUltiTools.getConfig()).thenReturn(config);
            when(mockUltiTools.getLogger()).thenReturn(mockLogger);
            when(mockUltiTools.getRemoteActionLog()).thenReturn(rawActionLog);
        });
        RawConfig published = new RawConfig(file, config, ultiTools);
        publishedRawConfigs.add(published);
        return published;
    }

    /**
     * Loads {@code blocklistYaml} through one of the two entries: a newly constructed manager, or
     * a public {@code loadConfiguration()} call on a manager that already loaded
     * {@link #VALID_PREVIOUS_BLOCKLIST}.
     */
    private CommandExecutionManager loadThroughEntry(String blocklistYaml, BlocklistEntry entry)
            throws IOException {
        if (entry == BlocklistEntry.CONSTRUCTION) {
            publishRawConfig(blocklistYaml);
            return new CommandExecutionManager();
        }
        publishRawConfig(VALID_PREVIOUS_BLOCKLIST);
        CommandExecutionManager reloaded = new CommandExecutionManager();
        assertThat(reloaded.isCommandAllowed("op").isAllowed())
                .as("precondition: the valid previous list [stop, deop] was installed and allows op")
                .isTrue();
        publishRawConfig(blocklistYaml);
        reloaded.loadConfiguration();
        return reloaded;
    }

    private static void assertConfigurableRefusal(SoftAssertions softly, CommandExecutionManager loaded,
            String command) {
        AccessDecision decision = loaded.isCommandAllowed(command);
        softly.assertThat(decision.isAllowed()).as("%s is refused", command).isFalse();
        softly.assertThat(decision.isConfigurable()).as("%s refusal is the configurable blocklist decision", command)
                .isTrue();
        softly.assertThat(decision.getConfigKey()).as("%s refusal names the blocklist key", command)
                .isEqualTo("ultipanel.commands.blocklist");
    }

    private static void assertAllowed(SoftAssertions softly, CommandExecutionManager loaded, String command) {
        softly.assertThat(loaded.isCommandAllowed(command).isAllowed()).as("%s is allowed", command).isTrue();
    }

    static Stream<Arguments> invalidBlocklistRows() {
        Stream.Builder<Arguments> rows = Stream.builder();
        for (InvalidBlocklist shape : InvalidBlocklist.values()) {
            for (BlocklistEntry entry : BlocklistEntry.values()) {
                rows.add(Arguments.of(shape, entry));
            }
        }
        return rows.build();
    }

    @ParameterizedTest
    @MethodSource("invalidBlocklistRows")
    @DisplayName("an invalid raw blocklist value is rejected as a whole and the current policy is kept")
    void invalidRawBlocklistIsRejectedWholeAndCurrentPolicyKept(InvalidBlocklist shape, BlocklistEntry entry)
            throws IOException {
        CommandExecutionManager loaded = loadThroughEntry(shape.yaml, entry);

        SoftAssertions softly = new SoftAssertions();
        // stop is in both the shipped default and the previous valid list: refused in every row.
        assertConfigurableRefusal(softly, loaded, "stop");
        assertConfigurableRefusal(softly, loaded, "minecraft:stop");
        // op is in the shipped default (construction) but not in the previous valid list (reload).
        if (entry.keepsShippedDefault()) {
            assertConfigurableRefusal(softly, loaded, "op");
            assertConfigurableRefusal(softly, loaded, "bukkit:op");
        } else {
            assertAllowed(softly, loaded, "op");
            assertAllowed(softly, loaded, "bukkit:op");
        }
        softly.assertAll();
    }

    // --- Task 2 pins: valid, empty, absent and top-level-null controls; warning content; audit
    // effect; and no write to the operator's file or the loaded section (D-05). ---

    /** Every WARNING-level message logged so far that names the blocklist key. */
    private List<String> warningsNamingBlocklistKey() {
        List<String> warnings = new ArrayList<>();
        for (Invocation invocation : Mockito.mockingDetails(mockLogger).getInvocations()) {
            String message = warningMessageOf(invocation);
            if (message != null && message.contains(BLOCKLIST_KEY)) {
                warnings.add(message);
            }
        }
        return warnings;
    }

    private static String warningMessageOf(Invocation invocation) {
        Object[] args = invocation.getArguments();
        String method = invocation.getMethod().getName();
        if ("warning".equals(method) && args.length == 1) {
            return String.valueOf(args[0]);
        }
        if ("log".equals(method) && args.length >= 2 && Level.WARNING.equals(args[0])) {
            return String.valueOf(args[1]);
        }
        return null;
    }

    /** Every argument of every call made on the mocked logger, rendered as text. */
    private String everythingLogged() {
        StringBuilder logged = new StringBuilder();
        for (Invocation invocation : Mockito.mockingDetails(mockLogger).getInvocations()) {
            logged.append(Arrays.deepToString(invocation.getArguments())).append('\n');
        }
        return logged.toString();
    }

    @ParameterizedTest
    @EnumSource(BlocklistEntry.class)
    @DisplayName("a valid list with padding and mixed case is normalized and replaces the policy as a whole")
    void validPaddedMixedCaseBlocklistReplacesThePolicy(BlocklistEntry entry) throws IOException {
        CommandExecutionManager loaded = loadThroughEntry("['  OP ', 'Stop']", entry);

        SoftAssertions softly = new SoftAssertions();
        assertConfigurableRefusal(softly, loaded, "op");
        assertConfigurableRefusal(softly, loaded, "bukkit:op");
        assertConfigurableRefusal(softly, loaded, "stop");
        assertConfigurableRefusal(softly, loaded, "minecraft:stop");
        assertAllowed(softly, loaded, "deop");
        assertAllowed(softly, loaded, "minecraft:deop");
        softly.assertThat(warningsNamingBlocklistKey()).as("a valid list logs no warning").isEmpty();
        softly.assertAll();
    }

    @ParameterizedTest
    @EnumSource(BlocklistEntry.class)
    @DisplayName("an explicit empty list is installed and blocks nothing")
    void explicitEmptyBlocklistBlocksNothing(BlocklistEntry entry) throws IOException {
        CommandExecutionManager loaded = loadThroughEntry("[]", entry);

        SoftAssertions softly = new SoftAssertions();
        for (String command : Arrays.asList("op", "bukkit:op", "stop", "minecraft:stop", "deop")) {
            assertAllowed(softly, loaded, command);
        }
        softly.assertThat(warningsNamingBlocklistKey()).as("an empty list logs no warning").isEmpty();
        softly.assertAll();
    }

    @ParameterizedTest
    @EnumSource(BlocklistEntry.class)
    @DisplayName("an absent key keeps the current policy: the default on construction, the previous list on reload")
    void absentBlocklistKeyKeepsTheCurrentPolicy(BlocklistEntry entry) throws IOException {
        CommandExecutionManager loaded = loadThroughEntry(null, entry);

        assertAbsentKeyOutcome(loaded, entry);
    }

    @ParameterizedTest
    @EnumSource(BlocklistEntry.class)
    @DisplayName("a top-level null value (blocklist: ~) reads as an absent key, not as an invalid value")
    void topLevelNullBlocklistReadsAsAbsentKey(BlocklistEntry entry) throws IOException {
        CommandExecutionManager loaded = loadThroughEntry("~", entry);

        // Characterization: YamlConfiguration drops a null-valued key while building its sections,
        // so the key is not set and this row is absent-key coverage, not invalid-value coverage.
        RawConfig nullFixture = publishedRawConfigs.get(publishedRawConfigs.size() - 1);
        assertThat(nullFixture.config.isSet(BLOCKLIST_KEY)).as("blocklist: ~ is reported as set").isFalse();
        assertAbsentKeyOutcome(loaded, entry);
    }

    private void assertAbsentKeyOutcome(CommandExecutionManager loaded, BlocklistEntry entry) {
        SoftAssertions softly = new SoftAssertions();
        assertConfigurableRefusal(softly, loaded, "stop");
        assertConfigurableRefusal(softly, loaded, "minecraft:stop");
        assertConfigurableRefusal(softly, loaded, "deop");
        assertAllowed(softly, loaded, "say hi");
        if (entry.keepsShippedDefault()) {
            assertConfigurableRefusal(softly, loaded, "op");
            assertConfigurableRefusal(softly, loaded, "reload");
        } else {
            assertAllowed(softly, loaded, "op");
            assertAllowed(softly, loaded, "reload");
        }
        softly.assertThat(warningsNamingBlocklistKey()).as("an absent key logs no warning").isEmpty();
        softly.assertAll();
    }

    @ParameterizedTest
    @MethodSource("invalidBlocklistRows")
    @DisplayName("an invalid value logs exactly one warning naming the key and its shape, never the value")
    void invalidBlocklistWarnsOnceWithShapeAndWithoutValue(InvalidBlocklist shape, BlocklistEntry entry)
            throws IOException {
        loadThroughEntry(shape.sentinelYaml, entry);

        List<String> warnings = warningsNamingBlocklistKey();
        assertThat(warnings).as("one warning for the one invalid load").hasSize(1);
        assertThat(warnings.get(0)).contains(BLOCKLIST_KEY).contains(shape.shape);
        assertThat(everythingLogged()).as("no logged line carries the raw value").doesNotContain(SENTINEL);
    }

    @ParameterizedTest
    @EnumSource(InvalidBlocklist.class)
    @DisplayName("after an invalid reload, execute still refuses stop with exactly one DENIED entry")
    void invalidReloadStillRefusesStopWithOneDeniedEntry(InvalidBlocklist shape) throws IOException {
        CommandExecutionManager loaded = loadThroughEntry(shape.yaml, BlocklistEntry.RELOAD);
        RecordingSink sink = new RecordingSink();
        CountingExecutor mainThread = new CountingExecutor();

        loaded.execute("stop", "some-admin", mainThread, sink);

        ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(rawActionLog, times(1)).record(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(entryCaptor.getValue().getCapability()).isEqualTo(Capability.COMMANDS.name());
        assertThat(mainThread.handed).isZero();
        assertThat(sink.blockedMessage).contains(BLOCKLIST_KEY);
    }

    static Stream<Arguments> everyBlocklistRow() {
        List<String> values = new ArrayList<>();
        for (InvalidBlocklist shape : InvalidBlocklist.values()) {
            values.add(shape.yaml);
        }
        values.addAll(Arrays.asList("['  OP ', 'Stop']", "[]", null, "~"));
        Stream.Builder<Arguments> rows = Stream.builder();
        for (String value : values) {
            for (BlocklistEntry entry : BlocklistEntry.values()) {
                rows.add(Arguments.of(value, entry));
            }
        }
        return rows.build();
    }

    @ParameterizedTest
    @MethodSource("everyBlocklistRow")
    @DisplayName("loading never writes the operator's file, never saves, and never rewrites the loaded value")
    void loadingNeverWritesTheOperatorConfiguration(String blocklistYaml, BlocklistEntry entry) throws IOException {
        List<ConfigSnapshot> before = new ArrayList<>();
        loadThroughEntryCapturing(blocklistYaml, entry, before);

        assertThat(publishedRawConfigs).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            before.get(i).assertUnchanged(publishedRawConfigs.get(i));
        }
    }

    /**
     * {@link #loadThroughEntry} that snapshots each fixture right after it is published, before
     * any load reads it.
     */
    private CommandExecutionManager loadThroughEntryCapturing(String blocklistYaml, BlocklistEntry entry,
            List<ConfigSnapshot> snapshots) throws IOException {
        if (entry == BlocklistEntry.RELOAD) {
            snapshots.add(new ConfigSnapshot(publishRawConfig(VALID_PREVIOUS_BLOCKLIST)));
            CommandExecutionManager reloaded = new CommandExecutionManager();
            snapshots.add(new ConfigSnapshot(publishRawConfig(blocklistYaml)));
            reloaded.loadConfiguration();
            return reloaded;
        }
        snapshots.add(new ConfigSnapshot(publishRawConfig(blocklistYaml)));
        return new CommandExecutionManager();
    }

    /** File bytes, key set and raw blocklist value of one published configuration. */
    private static final class ConfigSnapshot {
        final byte[] fileBytes;
        final Object rawValue;
        final List<Object> rawElements;
        final String rawText;
        final Object keys;

        ConfigSnapshot(RawConfig published) throws IOException {
            this.fileBytes = Files.readAllBytes(published.file);
            this.rawValue = published.config.get(BLOCKLIST_KEY);
            this.rawElements = rawValue instanceof List ? new ArrayList<>((List<?>) rawValue) : null;
            this.rawText = String.valueOf(rawValue);
            this.keys = published.config.getKeys(true);
        }

        void assertUnchanged(RawConfig published) throws IOException {
            assertThat(Files.readAllBytes(published.file)).as("file bytes").isEqualTo(fileBytes);
            verify(published.ultiTools, never()).saveConfig();
            Object rawAfter = published.config.get(BLOCKLIST_KEY);
            assertThat(rawAfter).as("raw value at the key is the same object").isSameAs(rawValue);
            if (rawElements != null) {
                assertThat(new ArrayList<Object>((List<?>) rawAfter)).as("raw list elements")
                        .containsExactlyElementsOf(rawElements);
            }
            assertThat(String.valueOf(rawAfter)).as("raw value content").isEqualTo(rawText);
            assertThat(published.config.getKeys(true)).as("loaded key set").isEqualTo(keys);
        }
    }

    /** The two ways a blocklist value reaches {@code loadConfiguration()}. */
    enum BlocklistEntry {
        /** A newly constructed manager; its current policy is the shipped ten-command default. */
        CONSTRUCTION,
        /** A repeated public call after {@link #VALID_PREVIOUS_BLOCKLIST} was installed. */
        RELOAD;

        boolean keepsShippedDefault() {
            return this == CONSTRUCTION;
        }
    }

    /**
     * Raw YAML values for the key that are not a list of strings (case 84e60349). {@code yaml} is
     * the Task 1 decision fixture; {@code sentinelYaml} is the same shape with {@link #SENTINEL}
     * placed inside the value wherever the shape has text to carry it (the empty-string scalar has
     * none, so its sentinel check is vacuous); {@code shape} is the phrase the warning must name.
     */
    enum InvalidBlocklist {
        SCALAR_STRING("op", SENTINEL, "the value is a string"),
        EMPTY_STRING_SCALAR("''", "''", "the value is a string"),
        LIST_OF_ONLY_A_MAPPING("[{name: op}]", "[{" + SENTINEL + ": " + SENTINEL + "}]", "element 1 is a map"),
        OP_WITH_A_MAPPING("[op, {name: deop}]", "[op, {name: " + SENTINEL + "}]", "element 2 is a map"),
        OP_WITH_A_NESTED_LIST("[op, [deop]]", "[op, [" + SENTINEL + "]]", "element 2 is a list"),
        OP_WITH_A_NULL("[op, null]", "[" + SENTINEL + ", null]", "element 2 is null"),
        OP_WITH_A_NUMBER("[op, 5]", "[" + SENTINEL + ", 5]", "element 2 is a number"),
        OP_WITH_A_BOOLEAN("[op, true]", "[" + SENTINEL + ", true]", "element 2 is a boolean");

        final String yaml;
        final String sentinelYaml;
        final String shape;

        InvalidBlocklist(String yaml, String sentinelYaml, String shape) {
            this.yaml = yaml;
            this.sentinelYaml = sentinelYaml;
            this.shape = shape;
        }
    }

    /** One published synthetic configuration: its file, the loaded section and the UltiTools mock. */
    private static final class RawConfig {
        final Path file;
        final YamlConfiguration config;
        final UltiTools ultiTools;

        RawConfig(Path file, YamlConfiguration config, UltiTools ultiTools) {
            this.file = file;
            this.config = config;
            this.ultiTools = ultiTools;
        }
    }
}
