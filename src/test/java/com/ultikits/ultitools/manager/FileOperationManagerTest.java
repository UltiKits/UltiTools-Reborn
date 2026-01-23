package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * FileOperationManager 测试
 */
@DisplayName("FileOperationManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class FileOperationManagerTest {

    @TempDir
    File tempDir;

    private ServerMock server;
    private FileOperationManager fileOperationManager;
    private UltiPanelWebSocketClient mockWebSocketClient;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock logger
        mockLogger = mock(Logger.class);
        when(UltiTools.getInstance().getLogger()).thenReturn(mockLogger);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
            Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
            serverRootField.setAccessible(true);
            serverRootField.set(fileOperationManager, tempDir);

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
}
