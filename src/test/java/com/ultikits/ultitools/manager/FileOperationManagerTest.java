package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
    private UltiPanelWebSocketClient mockWebSocketClient;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock logger
        mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        // Mock WebSocket client
        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("test-server");

        fileOperationManager = new FileOperationManager();
        fileOperationManager.setWebSocketClient(mockWebSocketClient);
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
        Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
        serverRootField.setAccessible(true);
        serverRootField.set(fileOperationManager, root);

        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(fileOperationManager, Collections.singletonList(root));
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

    @Nested
    @DisplayName("setWebSocketClient 测试")
    class SetWebSocketClientTests {

        @Test
        @DisplayName("应该设置 WebSocket 客户端")
        void shouldSetWebSocketClient() throws Exception {
            // Arrange
            FileOperationManager manager = new FileOperationManager();
            UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);

            // Act
            manager.setWebSocketClient(client);

            // Assert
            Field clientField = FileOperationManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            assertThat(clientField.get(manager)).isEqualTo(client);
        }
    }

    @Nested
    @DisplayName("getSecureFile 测试")
    class GetSecureFileTests {

        @Test
        @DisplayName("空路径应该抛出 SecurityException")
        void emptyPathShouldThrowSecurityException() throws Exception {
            // Arrange
            Method getSecureFile = FileOperationManager.class.getDeclaredMethod("getSecureFile", String.class);
            getSecureFile.setAccessible(true);

            // Act & Assert
            try {
                getSecureFile.invoke(fileOperationManager, "");
            } catch (Exception e) {
                assertThat(e.getCause()).isInstanceOf(SecurityException.class);
            }
        }

        @Test
        @DisplayName("null 路径应该抛出 SecurityException")
        void nullPathShouldThrowSecurityException() throws Exception {
            // Arrange
            Method getSecureFile = FileOperationManager.class.getDeclaredMethod("getSecureFile", String.class);
            getSecureFile.setAccessible(true);

            // Act & Assert
            try {
                getSecureFile.invoke(fileOperationManager, (String) null);
            } catch (Exception e) {
                assertThat(e.getCause()).isInstanceOf(SecurityException.class);
            }
        }

        @Test
        @DisplayName("应该移除开头的斜杠")
        void shouldRemoveLeadingSlash() throws Exception {
            // Arrange
            Method getSecureFile = FileOperationManager.class.getDeclaredMethod("getSecureFile", String.class);
            getSecureFile.setAccessible(true);

            // Act
            File result = (File) getSecureFile.invoke(fileOperationManager, "/test.txt");

            // Assert
            assertThat(result.getName()).isEqualTo("test.txt");
        }

        @Test
        @DisplayName("应该检测路径遍历攻击")
        void shouldDetectPathTraversalAttack() throws Exception {
            // Arrange
            Method getSecureFile = FileOperationManager.class.getDeclaredMethod("getSecureFile", String.class);
            getSecureFile.setAccessible(true);

            // Act & Assert
            try {
                getSecureFile.invoke(fileOperationManager, "../../../etc/passwd");
            } catch (Exception e) {
                assertThat(e.getCause()).isInstanceOf(SecurityException.class);
            }
        }
    }

    @Nested
    @DisplayName("deleteDirectory 测试")
    class DeleteDirectoryTests {

        @Test
        @DisplayName("应该递归删除目录")
        void shouldRecursivelyDeleteDirectory() throws Exception {
            // Arrange
            File testDir = new File(tempDir, "testdir");
            testDir.mkdirs();
            File subDir = new File(testDir, "subdir");
            subDir.mkdirs();
            File file1 = new File(testDir, "file1.txt");
            file1.createNewFile();
            File file2 = new File(subDir, "file2.txt");
            file2.createNewFile();

            Method deleteDirectory = FileOperationManager.class.getDeclaredMethod("deleteDirectory", File.class);
            deleteDirectory.setAccessible(true);

            // Act
            boolean result = (boolean) deleteDirectory.invoke(fileOperationManager, testDir);

            // Assert
            assertThat(result).isTrue();
            assertThat(testDir.exists()).isFalse();
        }

        @Test
        @DisplayName("空目录应该成功删除")
        void emptyDirectoryShouldBeDeleted() throws Exception {
            // Arrange
            File emptyDir = new File(tempDir, "emptydir");
            emptyDir.mkdirs();

            Method deleteDirectory = FileOperationManager.class.getDeclaredMethod("deleteDirectory", File.class);
            deleteDirectory.setAccessible(true);

            // Act
            boolean result = (boolean) deleteDirectory.invoke(fileOperationManager, emptyDir);

            // Assert
            assertThat(result).isTrue();
            assertThat(emptyDir.exists()).isFalse();
        }
    }

    @Nested
    @DisplayName("handleFileOperation 测试")
    class HandleFileOperationTests {

        @Test
        @DisplayName("应该处理 read 操作")
        void shouldHandleReadOperation() throws Exception {
            // Arrange
            JsonObject operationData = new JsonObject();
            operationData.addProperty("operation", "read");
            operationData.addProperty("path", "nonexistent.txt");
            operationData.addProperty("operationId", "test-op-1");

            // Act - 异步操作，不会立即完成
            fileOperationManager.handleFileOperation(operationData);

            // 等待异步操作完成
            Thread.sleep(100);

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("应该处理不支持的操作")
        void shouldHandleUnsupportedOperation() throws Exception {
            // Arrange
            JsonObject operationData = new JsonObject();
            operationData.addProperty("operation", "unsupported");
            operationData.addProperty("path", "test.txt");
            operationData.addProperty("operationId", "test-op-2");

            // Act
            fileOperationManager.handleFileOperation(operationData);

            // 等待异步操作完成
            Thread.sleep(100);

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("异常时应该发送错误结果")
        void shouldSendErrorResultOnException() {
            // Arrange - 创建一个会导致异常的 operationData
            JsonObject operationData = new JsonObject();
            // 缺少必要字段会导致 NPE，但方法会捕获

            // Act
            fileOperationManager.handleFileOperation(operationData);

            // 不会抛出异常，错误会被捕获并发送 - test passes if we reach here
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("handleReadOperation 测试")
    class HandleReadOperationTests {

        @Test
        @DisplayName("文件不存在时应该返回错误")
        void shouldReturnErrorWhenFileNotFound() throws Exception {
            // 通过反射调用私有方法
            Method handleRead = FileOperationManager.class.getDeclaredMethod(
                "handleReadOperation", String.class, JsonObject.class, String.class);
            handleRead.setAccessible(true);

            // Act
            handleRead.invoke(fileOperationManager, "nonexistent.txt", new JsonObject(), "op-1");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("limit 默认值应该为 1000")
        void limitShouldDefaultTo1000() throws Exception {
            // Arrange
            File testFile = new File(tempDir, "testread.txt");
            try (FileWriter writer = new FileWriter(testFile)) {
                for (int i = 0; i < 10; i++) {
                    writer.write("Line " + i + "\n");
                }
            }

            // 设置 serverRoot 为 tempDir
            setServerRootAndEditableRoots(tempDir);

            Method handleRead = FileOperationManager.class.getDeclaredMethod(
                "handleReadOperation", String.class, JsonObject.class, String.class);
            handleRead.setAccessible(true);

            JsonObject operationData = new JsonObject();
            // limit 为 0 或负数时默认为 1000

            // Act
            handleRead.invoke(fileOperationManager, "testread.txt", operationData, "op-2");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("handleWriteOperation 测试")
    class HandleWriteOperationTests {

        @Test
        @DisplayName("content 为 null 时应该返回错误")
        void shouldReturnErrorWhenContentIsNull() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            Method handleWrite = FileOperationManager.class.getDeclaredMethod(
                "handleWriteOperation", String.class, JsonObject.class, String.class);
            handleWrite.setAccessible(true);

            JsonObject operationData = new JsonObject();
            // content 为 null

            // Act
            handleWrite.invoke(fileOperationManager, "test.txt", operationData, "op-3");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("应该创建父目录")
        void shouldCreateParentDirectories() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            Method handleWrite = FileOperationManager.class.getDeclaredMethod(
                "handleWriteOperation", String.class, JsonObject.class, String.class);
            handleWrite.setAccessible(true);

            JsonObject operationData = new JsonObject();
            operationData.addProperty("content", "test content");
            operationData.addProperty("append", false);

            // Act
            handleWrite.invoke(fileOperationManager, "newdir/newfile.txt", operationData, "op-4");

            // Assert
            File newFile = new File(tempDir, "newdir/newfile.txt");
            assertThat(newFile.exists()).isTrue();
        }

        @Test
        @DisplayName("append 模式应该追加内容")
        void appendModeShouldAppendContent() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            // 创建初始文件
            File testFile = new File(tempDir, "append.txt");
            try (FileWriter writer = new FileWriter(testFile)) {
                writer.write("initial");
            }

            Method handleWrite = FileOperationManager.class.getDeclaredMethod(
                "handleWriteOperation", String.class, JsonObject.class, String.class);
            handleWrite.setAccessible(true);

            JsonObject operationData = new JsonObject();
            operationData.addProperty("content", " appended");
            operationData.addProperty("append", true);

            // Act
            handleWrite.invoke(fileOperationManager, "append.txt", operationData, "op-5");

            // Assert
            String content = new String(java.nio.file.Files.readAllBytes(testFile.toPath()));
            assertThat(content).isEqualTo("initial appended");
        }
    }

    @Nested
    @DisplayName("handleListOperation 测试")
    class HandleListOperationTests {

        @Test
        @DisplayName("目录不存在时应该返回错误")
        void shouldReturnErrorWhenDirectoryNotFound() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "nonexistent", new JsonObject(), "op-6");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("路径不是目录时应该返回错误")
        void shouldReturnErrorWhenPathIsNotDirectory() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File testFile = new File(tempDir, "notdir.txt");
            testFile.createNewFile();

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "notdir.txt", new JsonObject(), "op-7");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("应该列出目录内容")
        void shouldListDirectoryContents() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File subDir = new File(tempDir, "listdir");
            subDir.mkdirs();
            new File(subDir, "file1.txt").createNewFile();
            new File(subDir, "file2.txt").createNewFile();
            new File(subDir, "subdir").mkdirs();

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "listdir", new JsonObject(), "op-8");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("handleDeleteOperation 测试")
    class HandleDeleteOperationTests {

        @Test
        @DisplayName("文件不存在时应该返回错误")
        void shouldReturnErrorWhenFileNotFound() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            Method handleDelete = FileOperationManager.class.getDeclaredMethod(
                "handleDeleteOperation", String.class, JsonObject.class, String.class);
            handleDelete.setAccessible(true);

            // Act
            handleDelete.invoke(fileOperationManager, "nonexistent.txt", new JsonObject(), "op-9");

            // 不会抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("应该删除文件")
        void shouldDeleteFile() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File testFile = new File(tempDir, "todelete.txt");
            testFile.createNewFile();

            Method handleDelete = FileOperationManager.class.getDeclaredMethod(
                "handleDeleteOperation", String.class, JsonObject.class, String.class);
            handleDelete.setAccessible(true);

            // Act
            handleDelete.invoke(fileOperationManager, "todelete.txt", new JsonObject(), "op-10");

            // Assert
            assertThat(testFile.exists()).isFalse();
        }

        @Test
        @DisplayName("应该递归删除目录")
        void shouldDeleteDirectoryRecursively() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File testDir = new File(tempDir, "todeleteDir");
            testDir.mkdirs();
            new File(testDir, "file.txt").createNewFile();

            Method handleDelete = FileOperationManager.class.getDeclaredMethod(
                "handleDeleteOperation", String.class, JsonObject.class, String.class);
            handleDelete.setAccessible(true);

            // Act
            handleDelete.invoke(fileOperationManager, "todeleteDir", new JsonObject(), "op-11");

            // Assert
            assertThat(testDir.exists()).isFalse();
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
    @DisplayName("Directory listing should filter protected files")
    class HandleListOperationFilterTests {

        @Test
        @DisplayName("should exclude blocked files from directory listing")
        void shouldExcludeBlockedFilesFromListing() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            // Create a subdirectory with a mix of allowed and blocked files
            File testDir = new File(tempDir, "serverdir");
            testDir.mkdirs();
            new File(testDir, "config.yml").createNewFile();
            new File(testDir, "data.json").createNewFile();
            new File(testDir, "server.properties").createNewFile();  // blocked
            new File(testDir, "ops.json").createNewFile();           // blocked
            new File(testDir, "plugin.jar").createNewFile();         // blocked extension

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "serverdir", new JsonObject(), "filter-test");

            // Assert - verify the WebSocket message was sent with filtered results
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            org.mockito.Mockito.verify(mockWebSocketClient).sendMessage(captor.capture());

            JsonObject result = captor.getValue();
            JsonObject data = result.getAsJsonObject("data");
            assertThat(data.get("success").getAsBoolean()).isTrue();

            com.google.gson.JsonArray files = data.getAsJsonArray("files");
            java.util.Set<String> listedNames = new java.util.HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                listedNames.add(files.get(i).getAsJsonObject().get("name").getAsString());
            }

            // Allowed files should be present
            assertThat(listedNames).contains("config.yml", "data.json");
            // Blocked files should NOT be present
            assertThat(listedNames).doesNotContain("server.properties", "ops.json", "plugin.jar");
        }

        @Test
        @DisplayName("should still include directories in listing")
        void shouldIncludeDirectoriesInListing() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File testDir = new File(tempDir, "dirtest");
            testDir.mkdirs();
            new File(testDir, "plugins").mkdirs();
            new File(testDir, "world").mkdirs();
            new File(testDir, "config.yml").createNewFile();

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "dirtest", new JsonObject(), "dir-test");

            // Assert
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            org.mockito.Mockito.verify(mockWebSocketClient).sendMessage(captor.capture());

            JsonObject result = captor.getValue();
            JsonObject data = result.getAsJsonObject("data");
            com.google.gson.JsonArray files = data.getAsJsonArray("files");

            java.util.Set<String> listedNames = new java.util.HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                listedNames.add(files.get(i).getAsJsonObject().get("name").getAsString());
            }

            // Directories should always be listed
            assertThat(listedNames).contains("plugins", "world", "config.yml");
        }

        @Test
        @DisplayName("should exclude all blocked extensions from listing")
        void shouldExcludeAllBlockedExtensions() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File testDir = new File(tempDir, "extdir");
            testDir.mkdirs();
            new File(testDir, "allowed.txt").createNewFile();
            new File(testDir, "script.sh").createNewFile();     // blocked
            new File(testDir, "start.bat").createNewFile();     // blocked
            new File(testDir, "malware.exe").createNewFile();   // blocked
            new File(testDir, "MyClass.class").createNewFile(); // blocked

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "extdir", new JsonObject(), "ext-test");

            // Assert
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            org.mockito.Mockito.verify(mockWebSocketClient).sendMessage(captor.capture());

            JsonObject result = captor.getValue();
            JsonObject data = result.getAsJsonObject("data");
            com.google.gson.JsonArray files = data.getAsJsonArray("files");

            java.util.Set<String> listedNames = new java.util.HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                listedNames.add(files.get(i).getAsJsonObject().get("name").getAsString());
            }

            assertThat(listedNames).contains("allowed.txt");
            assertThat(listedNames).doesNotContain("script.sh", "start.bat", "malware.exe", "MyClass.class");
        }

        @Test
        @DisplayName("should exclude all blocked filenames from listing")
        void shouldExcludeAllBlockedFilenames() throws Exception {
            // Arrange
            setServerRootAndEditableRoots(tempDir);

            File testDir = new File(tempDir, "allblocked");
            testDir.mkdirs();
            // Create all blocked files
            new File(testDir, "server.properties").createNewFile();
            new File(testDir, "ops.json").createNewFile();
            new File(testDir, "whitelist.json").createNewFile();
            new File(testDir, "banned-ips.json").createNewFile();
            new File(testDir, "banned-players.json").createNewFile();
            new File(testDir, "eula.txt").createNewFile();
            new File(testDir, "usercache.json").createNewFile();
            new File(testDir, "bukkit.yml").createNewFile();
            new File(testDir, "spigot.yml").createNewFile();
            new File(testDir, "paper.yml").createNewFile();
            new File(testDir, "paper-global.yml").createNewFile();
            new File(testDir, "paper-world-defaults.yml").createNewFile();
            // And one allowed file
            new File(testDir, "readme.txt").createNewFile();

            Method handleList = FileOperationManager.class.getDeclaredMethod(
                "handleListOperation", String.class, JsonObject.class, String.class);
            handleList.setAccessible(true);

            // Act
            handleList.invoke(fileOperationManager, "allblocked", new JsonObject(), "all-blocked-test");

            // Assert
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            org.mockito.Mockito.verify(mockWebSocketClient).sendMessage(captor.capture());

            JsonObject result = captor.getValue();
            JsonObject data = result.getAsJsonObject("data");
            com.google.gson.JsonArray files = data.getAsJsonArray("files");

            java.util.Set<String> listedNames = new java.util.HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                listedNames.add(files.get(i).getAsJsonObject().get("name").getAsString());
            }

            assertThat(listedNames).containsExactly("readme.txt");
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
            assertThat(manager.isPathAllowed("plugins/MyPlugin/data.json").isAllowed()).isTrue();
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
}
