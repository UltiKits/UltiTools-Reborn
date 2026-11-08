package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.utils.TestHelper;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * FileOperationManager 测试
 */
@DisplayName("FileOperationManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class FileOperationManagerTest {

    @TempDir
    File tempDir;

    private FileOperationManager fileOperationManager;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock logger
        mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        fileOperationManager = new FileOperationManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * Points both {@code serverRoot} and the new {@code editableRoots} field at the same
     * directory, so that a handler test built around {@code @TempDir} still clears the D-15
     * editable-root gate. Reflecting {@code serverRoot} alone (the file's pre-existing pattern)
     * is no longer sufficient once {@code editableRoots} is resolved independently at
     * construction time — see Task 1's read_first notes.
     */
    private void setServerRootAndEditableRoots(File root) throws Exception {
        setServerRootAndEditableRoots(root, root);
    }

    /**
     * Overload letting a Task 3 containment test point {@code serverRoot} at the temp directory
     * while the single editable root is a real subdirectory of it (e.g. {@code <tmp>/root}) —
     * the shape every Task 3 bypass behaviour is described against.
     */
    private void setServerRootAndEditableRoots(File serverRootDir, File editableRoot) throws Exception {
        setServerRootAndEditableRoots(fileOperationManager, serverRootDir, editableRoot);
    }

    /**
     * Manager-parameterized overload (Plan 06-04) letting a test point a freshly-constructed
     * {@link FileOperationManager} — one built against its own mocked {@code getRemoteActionLog()}
     * — at the shared {@code tempDir}, rather than only ever reflecting into the base
     * {@link #fileOperationManager} field.
     */
    private void setServerRootAndEditableRoots(FileOperationManager manager, File serverRootDir, File editableRoot)
            throws Exception {
        Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
        serverRootField.setAccessible(true);
        serverRootField.set(manager, serverRootDir);

        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(manager, Collections.singletonList(editableRoot));
    }

    /**
     * Calls the package-private follow-mode resolver. Phase 20.2 replaced the private
     * {@code getSecureFile(String)} (which these tests used to reach by reflection, asserting a
     * thrown {@code SecurityException}) with {@code resolveTarget}, which returns the refusal as
     * an {@link AccessDecision} instead of throwing — so a refusal is asserted unconditionally on
     * the returned decision, and an admission on the returned operand.
     */
    private FileOperationManager.ResolvedTarget resolveFollowing(String path) {
        return fileOperationManager.resolveTarget(path, FileOperationManager.ResolveMode.FOLLOW);
    }

    /** Asserts a refusal that names {@code ultipanel.files.editable-roots} as the key to change. */
    private static void assertDeniedOutsideRoots(FileOperationManager.ResolvedTarget target) {
        assertThat(target.getDecision().isAllowed()).isFalse();
        assertThat(target.getDecision().isConfigurable()).isTrue();
        assertThat(target.getDecision().getConfigKey()).isEqualTo("ultipanel.files.editable-roots");
        assertThat(target.getOperand()).isNull();
    }

    /**
     * Publishes a fresh {@link UltiTools} mock whose {@code getConfig()} returns a real,
     * standalone {@link YamlConfiguration} carrying only {@code ultipanel.files.editable-roots}
     * (or nothing, for {@code roots == null}, to pin the absent-key default), then re-reads it
     * into {@link #fileOperationManager} via the public {@code reloadRootsFromConfig()} seam —
     * exactly the reload path the plan calls out as the reason that method is public.
     */
    private void configureEditableRoots(List<String> roots) {
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            YamlConfiguration config = new YamlConfiguration();
            if (roots != null) {
                config.set("ultipanel.files.editable-roots", roots);
            }
            lenient().when(ultiTools.getConfig()).thenReturn(config);
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
        });
        fileOperationManager.reloadRootsFromConfig();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该初始化 serverRoot")
        void shouldInitializeServerRoot() throws Exception {
            // Arrange & Act
            FileOperationManager manager = new FileOperationManager();

            // Assert
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            File serverRoot = (File) serverRootField.get(manager);

            assertThat(serverRoot).isNotNull();
            assertThat(serverRoot.getAbsolutePath()).isEqualTo(System.getProperty("user.dir"));
        }
    }

    /**
     * Migrated from the former {@code GetSecureFileTests} (Phase 20.2): the empty-path, null-path
     * and traversal refusals used to assert only inside a {@code catch}, so they passed vacuously
     * when nothing was thrown; they now assert the returned decision unconditionally.
     */
    @Nested
    @DisplayName("resolveTarget basics (formerly getSecureFile)")
    class ResolveTargetBasicsTests {

        @Test
        @DisplayName("an empty path is denied, and the refusal is not configurable")
        void emptyPathIsDeniedNonConfigurably() {
            FileOperationManager.ResolvedTarget target = resolveFollowing("");

            assertThat(target.getDecision().isAllowed()).isFalse();
            assertThat(target.getDecision().isConfigurable()).isFalse();
            assertThat(target.getOperand()).isNull();
        }

        @Test
        @DisplayName("a null path is denied, and the refusal is not configurable")
        void nullPathIsDeniedNonConfigurably() {
            FileOperationManager.ResolvedTarget target = resolveFollowing(null);

            assertThat(target.getDecision().isAllowed()).isFalse();
            assertThat(target.getDecision().isConfigurable()).isFalse();
            assertThat(target.getOperand()).isNull();
        }

        @Test
        @DisplayName("a leading slash is removed")
        void shouldRemoveLeadingSlash() throws Exception {
            // Point both fields at tempDir so the default-cwd fallback (no plugins/logs on disk)
            // is not what's under test here.
            setServerRootAndEditableRoots(tempDir);

            FileOperationManager.ResolvedTarget target = resolveFollowing("/test.txt");

            assertThat(target.getDecision().isAllowed()).isTrue();
            assertThat(target.getOperand().getName()).isEqualTo("test.txt");
        }

        @Test
        @DisplayName("a traversal path is denied as outside the editable roots")
        void shouldDetectPathTraversalAttack() {
            assertDeniedOutsideRoots(resolveFollowing("../../../etc/passwd"));
        }
    }

    @Nested
    @DisplayName("serverRoot 字段测试")
    class ServerRootFieldTests {

        @Test
        @DisplayName("应该是 final")
        void shouldBeFinal() throws Exception {
            Field field = FileOperationManager.class.getDeclaredField("serverRoot");
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该是 private")
        void shouldBePrivate() throws Exception {
            Field field = FileOperationManager.class.getDeclaredField("serverRoot");
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("isPathAllowed 敏感文件阻止测试")
    class IsPathAllowedTests {

        @Test
        @DisplayName("should block access to sensitive files")
        void shouldBlockSensitiveFiles() {
            FileOperationManager manager = new FileOperationManager();

            assertThat(manager.isPathAllowed("server.properties").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("ops.json").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("whitelist.json").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("banned-ips.json").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("banned-players.json").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("eula.txt").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should allow access to plugin config files")
        void shouldAllowPluginConfigs() {
            FileOperationManager manager = new FileOperationManager();

            assertThat(manager.isPathAllowed("plugins/UltiTools/config.yml").isAllowed()).isTrue();
            assertThat(manager.isPathAllowed("plugins/MyPlugin/settings.yml").isAllowed()).isTrue();
        }

        @Test
        @DisplayName("data.json 现在无论目录如何都被拒绝——凭据模式匹配文件名，不再局限于 UltiTools 自己的目录")
        void dataJsonIsNowDeniedRegardlessOfDirectory() {
            FileOperationManager manager = new FileOperationManager();

            assertThat(manager.isPathAllowed("plugins/MyPlugin/data.json").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should block write to .jar files")
        void shouldBlockJarWrites() {
            FileOperationManager manager = new FileOperationManager();

            assertThat(manager.isPathAllowed("plugins/evil.jar").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("server.jar").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("should block null and empty paths")
        void shouldBlockNullAndEmptyPaths() {
            FileOperationManager manager = new FileOperationManager();

            assertThat(manager.isPathAllowed(null).isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("").isAllowed()).isFalse();
            assertThat(manager.isPathAllowed("  ").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("null/empty/whitespace 路径的拒绝是不可配置的")
        void nullEmptyAndWhitespacePathsAreDeniedNonConfigurably() {
            FileOperationManager manager = new FileOperationManager();

            assertThat(manager.isPathAllowed(null).isConfigurable()).isFalse();
            assertThat(manager.isPathAllowed("").isConfigurable()).isFalse();
            assertThat(manager.isPathAllowed("   ").isConfigurable()).isFalse();
        }
    }

    @Nested
    @DisplayName("可编辑根目录集合测试（D-14/D-15/D-17）")
    class EditableRootSetTests {

        @Test
        @DisplayName("缺省情况下根集合恰好是 plugins 和 logs")
        void defaultRootsArePluginsAndLogs() {
            AccessDecision pluginsDecision = fileOperationManager.isPathAllowed("plugins/SomeModule/config.yml");
            AccessDecision logsDecision = fileOperationManager.isPathAllowed("logs/latest.log");
            AccessDecision worldDecision = fileOperationManager.isPathAllowed("world/level.dat");

            assertThat(pluginsDecision.isAllowed()).isTrue();
            assertThat(logsDecision.isAllowed()).isTrue();
            assertThat(worldDecision.isAllowed()).isFalse();
        }

        @Test
        @DisplayName("world/level.dat 与 server.properties 均被拒绝")
        void worldAndServerPropertiesAreRefused() {
            assertThat(fileOperationManager.isPathAllowed("world/level.dat").isAllowed()).isFalse();
            assertThat(fileOperationManager.isPathAllowed("server.properties").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("根集合之外的路径给出可配置拒绝，命名 ultipanel.files.editable-roots 与 config.yml")
        void pathOutsideRootsReportsConfigurableRefusalNamingKeyAndFile() {
            AccessDecision decision = fileOperationManager.isPathAllowed("world/level.dat");

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.isConfigurable()).isTrue();
            assertThat(decision.getConfigKey()).isEqualTo("ultipanel.files.editable-roots");
            assertThat(decision.getMessage())
                    .contains("ultipanel.files.editable-roots")
                    .contains("plugins/UltiTools/config.yml");
        }

        @Test
        @DisplayName("单根不变量：配置为单一目录时，服务器根下其它路径被拒绝——若整服务器根边界回归，此用例必须变红")
        void singleConfiguredRootRefusesEverythingElseUnderServerRoot() {
            configureEditableRoots(Collections.singletonList("onlyroot"));

            assertThat(fileOperationManager.isPathAllowed("onlyroot/config.yml").isAllowed()).isTrue();
            assertThat(fileOperationManager.isPathAllowed("elsewhere/config.yml").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("根集合为空列表时，一切路径被拒绝，且拒绝是可配置的")
        void emptyRootListRefusesEverythingConfigurably() {
            configureEditableRoots(Collections.emptyList());

            AccessDecision decision = fileOperationManager.isPathAllowed("plugins/UltiTools/config.yml");

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.isConfigurable()).isTrue();
        }

        @Test
        @DisplayName("缺失键回退到出厂默认值，与显式空列表（故意不授予任何目录）不同")
        void absentKeyFallsBackToDefaultsDistinctFromExplicitEmptyList() {
            configureEditableRoots(null);
            assertThat(fileOperationManager.isPathAllowed("plugins/x.yml").isAllowed()).isTrue();

            configureEditableRoots(Collections.emptyList());
            assertThat(fileOperationManager.isPathAllowed("plugins/x.yml").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("同一段 outside-roots 原因文本，可配置与不可配置两条消息不相等")
        void configurableAndNonConfigurableRefusalMessagesDiffer() {
            String outsideRootsMessage = fileOperationManager.isPathAllowed("world/level.dat").getMessage();
            String credentialMessage = fileOperationManager.isPathAllowed("server.properties").getMessage();

            assertThat(outsideRootsMessage).isNotEqualTo(credentialMessage);
        }
    }

    /**
     * Top-level, NOT nested under a {@code @Nested} class: 06-VALIDATION.md's automated command
     * is the bare {@code mvn -B -o test -Dtest=FileOperationManagerTest#shouldRejectCredentialFilesUnconditionally}
     * with no {@code $NestedClass} qualifier, and Surefire's method-name filter does not descend
     * into {@code @Nested} classes for that syntax — verified empirically: the identical method
     * nested one level down ran 0 tests under that exact command.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("凭据文件无论 editable-roots 如何配置都被拒绝，且拒绝是不可配置的")
    @ValueSource(strings = {
        "plugins/UltiTools/data.json",
        "foo.key",
        "plugins/certs/foo.pem",
        "foo.p12",
        "foo.jks",
        "foo.keystore",
        "secring.gpg",
        ".env",
        ".env.local",
        "access_key.txt",
        ".dev.vars",
        "ops.json",
        "server.properties",
        "whitelist.json",
        "banned-ips.json",
        "eula.txt"
    })
    void shouldRejectCredentialFilesUnconditionally(String path) {
        AccessDecision decision = fileOperationManager.isPathAllowed(path);

        assertThat(decision.isAllowed()).as("path: " + path).isFalse();
        assertThat(decision.isConfigurable()).as("path: " + path).isFalse();
    }

    /**
     * Top-level for the same Surefire filtering reason as
     * {@link #shouldRejectCredentialFilesUnconditionally(String)} — 06-VALIDATION.md's automated
     * command is the bare {@code FileOperationManagerTest#shouldRejectSiblingPrefixEscape}.
     * <p>
     * Pins T-06-16 (D-21): with the editable root's real path at {@code <tmp>/root}, a request
     * resolving to the sibling {@code <tmp>/root-evil/x} must be refused. This is exactly ROADMAP
     * criterion 4's {@code /allowed-root-evil} shape, reproduced against the editable root; the
     * pre-6.3.0 defect was {@code String.startsWith} admitting it because {@code "/root-evil"}
     * has {@code "/root"} as a literal character prefix.
     */
    @Test
    @DisplayName("兄弟目录前缀转义（<root>-evil）必须被拒绝")
    void shouldRejectSiblingPrefixEscape() throws Exception {
        File root = new File(tempDir, "root");
        root.mkdirs();
        File siblingEvil = new File(tempDir, "root-evil");
        siblingEvil.mkdirs();

        setServerRootAndEditableRoots(tempDir, root);

        assertDeniedOutsideRoots(resolveFollowing("root-evil/x"));
    }

    /**
     * Top-level for the same Surefire filtering reason as above.
     * <p>
     * Pins T-06-17 (D-21): a symlink inside the editable root whose target resolves outside every
     * root must be refused, because containment is checked against the {@code toRealPath()}
     * result, never the requested path. Skips (does not fail) on a filesystem/user that refuses
     * symlink creation, per Task 3's own instruction — this behaviour cannot be pinned there.
     */
    @Test
    @DisplayName("根目录内指向根目录外的符号链接必须被拒绝")
    void shouldRejectSymlinkEscape() throws Exception {
        File root = new File(tempDir, "root");
        root.mkdirs();
        File outside = new File(tempDir, "outside");
        outside.mkdirs();
        File outsideFile = new File(outside, "somefile.txt");
        assertThat(outsideFile.createNewFile()).isTrue();

        try {
            Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false,
                    "symlink creation not permitted on this filesystem/user: " + e.getMessage());
            return;
        }

        setServerRootAndEditableRoots(tempDir, root);

        assertDeniedOutsideRoots(resolveFollowing("root/link/somefile.txt"));
    }

    /**
     * Top-level for the same Surefire filtering reason as above.
     * <p>
     * Pins T-06-22 (D-21): on a case-sensitive filesystem (the CI runner), a request naming the
     * wrong case for a real directory must be refused, not silently resolved. Guards itself with
     * an explicit sensitivity probe (create a lowercase marker, check whether the uppercase name
     * also resolves) and skips — rather than passing vacuously — on a case-insensitive filesystem,
     * per Task 3's own instruction.
     */
    @Test
    @DisplayName("大小写不匹配的路径在大小写敏感文件系统上必须被拒绝")
    void shouldRejectCaseMismatchOnCaseSensitiveFs() throws Exception {
        File pluginsDir = new File(tempDir, "plugins");
        pluginsDir.mkdirs();
        File fooFile = new File(pluginsDir, "foo.yml");
        assertThat(fooFile.createNewFile()).isTrue();

        boolean caseSensitiveFs = !new File(tempDir, "Plugins").exists();
        Assumptions.assumeTrue(caseSensitiveFs,
                "filesystem is case-insensitive; the case-mismatch refusal cannot be pinned here");

        setServerRootAndEditableRoots(tempDir, pluginsDir);

        assertDeniedOutsideRoots(resolveFollowing("Plugins/foo.yml"));
    }

    @Nested
    @DisplayName("不可配置的凭据拒绝层测试（D-16/D-19/D-23）")
    class CredentialDenyLayerTests {

        @Test
        @DisplayName("granting 'plugins' as an editable root does not make plugins/UltiTools/data.json readable")
        void grantingPluginsRootDoesNotUnlockDataJson() {
            configureEditableRoots(Collections.singletonList("plugins"));

            AccessDecision decision = fileOperationManager.isPathAllowed("plugins/UltiTools/data.json");

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.isConfigurable()).isFalse();
        }

        @Test
        @DisplayName("data.json.bak 不被 data.json 规则捕获——精确 basename 匹配，不是子串匹配")
        void dataJsonBakIsNotCaughtByTheDataJsonRule() {
            AccessDecision decision = fileOperationManager.isPathAllowed("plugins/SomeModule/data.json.bak");

            assertThat(decision.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("plugins/UltiTools/security/ 下的任何路径与该目录本身都被拒绝——动作日志无法通过它记录的 API 被读取或删除")
        void securityDirectoryAndItsContentsAreRejectedUnconditionally() {
            assertThat(fileOperationManager.isPathAllowed("plugins/UltiTools/security/action.log").isAllowed())
                    .isFalse();
            assertThat(fileOperationManager.isPathAllowed("plugins/UltiTools/security/action.log.0").isAllowed())
                    .isFalse();
            assertThat(fileOperationManager.isPathAllowed("plugins/UltiTools/security").isAllowed()).isFalse();
        }

        @Test
        @DisplayName("同名前缀的兄弟目录 security-backup 不被 security/ 前缀规则捕获")
        void siblingDirectoryWithSamePrefixIsNotCaughtBySecurityRule() {
            AccessDecision decision = fileOperationManager.isPathAllowed(
                    "plugins/UltiTools/security-backup/note.txt");

            assertThat(decision.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("凭据拒绝层不读取任何配置键——即便根集合为空，凭据文件的拒绝原因依旧是不可配置")
        void denyLayerDoesNotConsultConfiguration() {
            configureEditableRoots(Collections.emptyList());

            AccessDecision decision = fileOperationManager.isPathAllowed("plugins/UltiTools/data.json");

            assertThat(decision.isConfigurable())
                    .as("credential refusal must stay non-configurable even when the root set is also refusing")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("框架自有状态目录 .ultikits/ 的拒绝层（plan 22-07）")
    class FrameworkStateDirectoryTests {

        private File stateDirectory;

        /**
         * Widens the editable roots to the whole server root — the operator grant the guard has to
         * survive — and gives the state directory the two framework-owned files it protects plus an
         * unreadable sibling and a temporary-write sibling.
         */
        @BeforeEach
        void widenRootsAndCreateStateFiles() throws Exception {
            setServerRootAndEditableRoots(tempDir, tempDir);
            stateDirectory = new File(tempDir, ".ultikits");
            assertThat(stateDirectory.mkdirs()).isTrue();
            for (String name : Arrays.asList("credentials.json", "cloud-outbox.json",
                    "cloud-outbox.json.unreadable", "cloud-outbox.json.01ARYZ6S41TSV4RRFFQ69G5FAV.tmp")) {
                assertThat(new File(stateDirectory, name).createNewFile()).isTrue();
            }
        }

        @Test
        @DisplayName("widened roots 下读取 .ultikits 里的凭据、状态文件及其兄弟文件一律被拒绝，且不可配置")
        void everyStateFileIsRefusedEvenWithWidenedRoots() {
            for (String path : Arrays.asList(".ultikits/credentials.json", ".ultikits/cloud-outbox.json",
                    ".ultikits/cloud-outbox.json.unreadable",
                    ".ultikits/cloud-outbox.json.01ARYZ6S41TSV4RRFFQ69G5FAV.tmp")) {
                FileOperationManager.ResolvedTarget target = resolveFollowing(path);
                assertThat(target.getDecision().isAllowed()).as(path).isFalse();
                assertThat(target.getDecision().isConfigurable()).as(path).isFalse();
                assertThat(target.getOperand()).as(path).isNull();
            }
            // The credential basename keeps its own refusal; the state file's names, its temporary
            // siblings and its moved-aside copy are the ones this plan's directory rule has to name.
            for (String path : Arrays.asList(".ultikits/cloud-outbox.json", ".ultikits/cloud-outbox.json.unreadable",
                    ".ultikits/cloud-outbox.json.01ARYZ6S41TSV4RRFFQ69G5FAV.tmp")) {
                assertThat(resolveFollowing(path).getDecision().getReason()).as(path).contains(".ultikits");
            }
            assertThat(resolveFollowing(".ultikits/credentials.json").getDecision().getReason())
                    .contains("protected credential");
        }

        @Test
        @DisplayName("目录本身与它的列表请求同样被拒绝，删除模式也一样")
        void theDirectoryItselfIsRefusedInBothModes() {
            FileOperationManager.ResolvedTarget listed = resolveFollowing(".ultikits");
            FileOperationManager.ResolvedTarget deleted =
                    fileOperationManager.resolveTarget(".ultikits", FileOperationManager.ResolveMode.ENTRY);

            assertThat(listed.getDecision().isAllowed()).isFalse();
            assertThat(deleted.getDecision().isAllowed()).isFalse();
            assertThat(deleted.getDecision().isConfigurable()).isFalse();
            assertThat(deleted.getOperand()).isNull();
        }

        @Test
        @DisplayName("同名前缀的兄弟目录 .ultikits-backup 不被捕获——按名字元素判断，不是字符串前缀")
        void siblingDirectoryWithSamePrefixIsNotCaught() throws Exception {
            File sibling = new File(tempDir, ".ultikits-backup");
            assertThat(sibling.mkdirs()).isTrue();
            assertThat(new File(sibling, "note.txt").createNewFile()).isTrue();

            FileOperationManager.ResolvedTarget target = resolveFollowing(".ultikits-backup/note.txt");

            assertThat(target.getDecision().isAllowed()).isTrue();
            assertThat(target.getMappedPath()).isEqualTo(".ultikits-backup/note.txt");
        }

        @Test
        @DisplayName("可编辑根内的符号链接指向 .ultikits 时，按真实路径同样被拒绝")
        void aliasReachingTheStateDirectoryIsRefusedByRealPath() throws Exception {
            File alias = new File(tempDir, "alias");
            try {
                Files.createSymbolicLink(alias.toPath(), stateDirectory.toPath());
            } catch (UnsupportedOperationException | IOException e) {
                Assumptions.assumeTrue(false, "symbolic links are not available here: "
                        + e.getClass().getSimpleName());
                return;
            }

            FileOperationManager.ResolvedTarget target = resolveFollowing("alias/cloud-outbox.json");

            assertThat(target.getDecision().isAllowed()).isFalse();
            assertThat(target.getDecision().isConfigurable()).isFalse();
            assertThat(target.getOperand()).isNull();
        }
    }

    @Nested
    @DisplayName("resolveTarget segment-aware containment (D-21, formerly getSecureFile)")
    class SegmentAwareContainmentTests {

        @Test
        @DisplayName("<root>/sub/file.yml 应被放行")
        void requestUnderRootIsAdmitted() throws Exception {
            File root = new File(tempDir, "root");
            root.mkdirs();
            File sub = new File(root, "sub");
            sub.mkdirs();
            setServerRootAndEditableRoots(tempDir, root);

            FileOperationManager.ResolvedTarget target = resolveFollowing("root/sub/file.yml");

            assertThat(target.getDecision().isAllowed()).isTrue();
            assertThat(target.getOperand().getName()).isEqualTo("file.yml");
        }

        @Test
        @DisplayName("写入尚不存在的目录下的新文件——解析最近的已存在祖先而不是抛出异常")
        void createIntoNotYetExistingDirectoryIsAdmitted() throws Exception {
            File root = new File(tempDir, "root");
            root.mkdirs();
            setServerRootAndEditableRoots(tempDir, root);

            // Neither "new" nor "file.yml" exist yet — only "root" does.
            FileOperationManager.ResolvedTarget target = resolveFollowing("root/new/file.yml");

            assertThat(target.getDecision().isAllowed()).isTrue();
            assertThat(target.getOperand().getName()).isEqualTo("file.yml");
        }

        @Test
        @DisplayName("最近已存在祖先解析到根集合之外时被拒绝——祖先松弛不能被用作逃逸手段")
        void requestWhoseNearestExistingAncestorIsOutsideEveryRootIsRefused() throws Exception {
            File root = new File(tempDir, "root");
            root.mkdirs();
            setServerRootAndEditableRoots(tempDir, root);

            // "nowhere" does not exist anywhere under tempDir — its nearest existing ancestor is
            // tempDir itself, which is NOT the configured root (tempDir/root).
            assertDeniedOutsideRoots(resolveFollowing("nowhere/deep/file.yml"));
        }

        @Test
        @DisplayName("规范化后落在根内的 .. 路径被放行")
        void dotDotPathThatCanonicalizesInsideRootIsAdmitted() throws Exception {
            File root = new File(tempDir, "root");
            root.mkdirs();
            File sub = new File(root, "sub");
            sub.mkdirs();
            setServerRootAndEditableRoots(tempDir, root);

            FileOperationManager.ResolvedTarget target = resolveFollowing("root/sub/../file.yml");

            assertThat(target.getDecision().isAllowed()).isTrue();
            assertThat(target.getOperand().getName()).isEqualTo("file.yml");
        }

        @Test
        @DisplayName("规范化后落在根外的 .. 路径被拒绝")
        void dotDotPathThatCanonicalizesOutsideEveryRootIsRefused() throws Exception {
            File root = new File(tempDir, "root");
            root.mkdirs();
            setServerRootAndEditableRoots(tempDir, root);

            assertDeniedOutsideRoots(resolveFollowing("root/../elsewhere/file.yml"));
        }
    }

    @Nested
    @DisplayName("目录列表标记的补充覆盖测试（D-18，Plan 06-04 Task 2）")
    class ListMarkingTests {

        /**
         * Unit-tests the reason-code derivation directly (D-18's two codes): a configurable
         * {@link AccessDecision} maps to {@code OUTSIDE_ROOTS}, a non-configurable one maps to
         * {@code PROTECTED_CREDENTIAL}.
         * <p>
         * End-to-end, since Phase 20.2 a listed file child is marked {@code OUTSIDE_ROOTS} when it
         * is an in-root alias whose real target lies outside every editable root — list marking
         * takes each child's decision from the same follow-mode resolver a read would use (pinned
         * in {@code FileOperationTargetConsistencyTest}). This method pins the mapping directly.
         */
        @Test
        @DisplayName("原因码派生：可配置拒绝映射为 OUTSIDE_ROOTS，不可配置拒绝映射为 PROTECTED_CREDENTIAL")
        void reasonCodeDerivationMapsBothCauses() throws Exception {
            Method reasonCodeFor =
                    FileOperationManager.class.getDeclaredMethod("reasonCodeFor", AccessDecision.class);
            reasonCodeFor.setAccessible(true);

            String outsideRoots = (String) reasonCodeFor.invoke(null,
                    AccessDecision.deniedConfigurable("outside", "ultipanel.files.editable-roots"));
            String protectedCredential = (String) reasonCodeFor.invoke(null,
                    AccessDecision.deniedNonConfigurable("credential"));

            assertThat(outsideRoots).isEqualTo("OUTSIDE_ROOTS");
            assertThat(protectedCredential).isEqualTo("PROTECTED_CREDENTIAL");
        }
    }
}
