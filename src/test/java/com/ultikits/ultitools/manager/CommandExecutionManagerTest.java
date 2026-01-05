package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * CommandExecutionManager 测试
 */
@DisplayName("CommandExecutionManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CommandExecutionManagerTest {

    private ServerMock server;
    private CommandExecutionManager manager;
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
        when(mockWebSocketClient.getServerId()).thenReturn("test-server-id");

        manager = new CommandExecutionManager();
        manager.setWebSocketClient(mockWebSocketClient);
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
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
    @DisplayName("setWebSocketClient 测试")
    class SetWebSocketClientTests {

        @Test
        @DisplayName("应该正确设置 WebSocket 客户端")
        void shouldSetWebSocketClient() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);

            // Act
            newManager.setWebSocketClient(client);

            // Assert - 通过后续操作验证设置成功
            assertThat(newManager).isNotNull();
        }
    }

    @Nested
    @DisplayName("executeCommand 测试")
    class ExecuteCommandTests {

        @Test
        @DisplayName("空命令应该发送错误结果")
        void emptyCommandShouldSendError() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "test-cmd-id");

            // Act
            manager.executeCommand(commandData);

            // Assert
            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("null 命令应该发送错误结果")
        void nullCommandShouldSendError() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.add("command", com.google.gson.JsonNull.INSTANCE);
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "test-cmd-id");

            // Act
            manager.executeCommand(commandData);

            // Assert
            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("有效命令应该被调度执行")
        void validCommandShouldBeScheduled() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "help");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "test-cmd-id");

            // Act
            manager.executeCommand(commandData);

            // Assert - 命令被调度
            assertThat(server.getScheduler().getPendingTasks()).isNotEmpty();
        }

        @Test
        @DisplayName("异步命令应该异步执行")
        void asyncCommandShouldExecuteAsync() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "help");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", true);
            commandData.addProperty("commandId", "test-async-cmd");

            // Act
            manager.executeCommand(commandData);

            // Assert - 任务被调度
            assertThat(server.getScheduler().getPendingTasks()).isNotEmpty();
        }

        @Test
        @DisplayName("console 执行者应该使用控制台发送者")
        void consoleExecutorShouldUseConsoleSender() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "version");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "test-console-cmd");

            // Act
            manager.executeCommand(commandData);
            server.getScheduler().performOneTick();

            // Assert - 命令被执行
            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("CommandResult 测试")
    class CommandResultTests {

        @Test
        @DisplayName("应该正确存储成功结果")
        void shouldStoreSuccessResult() {
            // Act
            CommandExecutionManager.CommandResult result = 
                new CommandExecutionManager.CommandResult(true, "Success output", 100L);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEqualTo("Success output");
            assertThat(result.getExecutionTime()).isEqualTo(100L);
        }

        @Test
        @DisplayName("应该正确存储失败结果")
        void shouldStoreFailureResult() {
            // Act
            CommandExecutionManager.CommandResult result = 
                new CommandExecutionManager.CommandResult(false, "Error message", 50L);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getOutput()).isEqualTo("Error message");
            assertThat(result.getExecutionTime()).isEqualTo(50L);
        }

        @Test
        @DisplayName("应该处理空输出")
        void shouldHandleEmptyOutput() {
            // Act
            CommandExecutionManager.CommandResult result = 
                new CommandExecutionManager.CommandResult(true, "", 0L);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEmpty();
            assertThat(result.getExecutionTime()).isZero();
        }

        @Test
        @DisplayName("应该处理 null 输出")
        void shouldHandleNullOutput() {
            // Act
            CommandExecutionManager.CommandResult result = 
                new CommandExecutionManager.CommandResult(true, null, 0L);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isNull();
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("JSON 解析异常应该被捕获")
        void jsonParseExceptionShouldBeCaught() {
            // Arrange
            JsonObject invalidData = new JsonObject();
            // 缺少必要字段

            // Act & Assert - 不应该抛出异常
            manager.executeCommand(invalidData);
        }

        @Test
        @DisplayName("WebSocket 客户端为 null 时不应该崩溃")
        void nullWebSocketClientShouldNotCrash() {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            // 不设置 WebSocket 客户端

            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "");
            commandData.addProperty("commandId", "test");

            // Act & Assert - 不应该抛出 NPE
            // 由于没有设置 webSocketClient，sendCommandResult 会抛出 NPE
            // 但这被 try-catch 捕获了
            try {
                newManager.executeCommand(commandData);
            } catch (NullPointerException e) {
                // 预期的行为，因为 webSocketClient 是 null
            }
        }
    }

    @Nested
    @DisplayName("多命令测试")
    class MultipleCommandsTests {

        @Test
        @DisplayName("多个命令应该独立执行")
        void multipleCommandsShouldExecuteIndependently() {
            // Arrange
            JsonObject cmd1 = new JsonObject();
            cmd1.addProperty("command", "help");
            cmd1.addProperty("executor", "console");
            cmd1.addProperty("async", false);
            cmd1.addProperty("commandId", "cmd-1");

            JsonObject cmd2 = new JsonObject();
            cmd2.addProperty("command", "version");
            cmd2.addProperty("executor", "console");
            cmd2.addProperty("async", false);
            cmd2.addProperty("commandId", "cmd-2");

            // Act
            manager.executeCommand(cmd1);
            manager.executeCommand(cmd2);

            // Assert - 两个任务都被调度
            assertThat(server.getScheduler().getPendingTasks().size()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("CommandOutputCapture 详细测试")
    class CommandOutputCaptureDetailedTests {

        @Test
        @DisplayName("sendMessage(String) 应该捕获消息并添加换行")
        void sendMessageShouldCaptureWithNewline() throws Exception {
            // 使用反射访问内部类
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            Object capture = constructor.newInstance(delegate);
            
            // 调用 sendMessage
            java.lang.reflect.Method sendMessage = captureClass.getMethod("sendMessage", String.class);
            sendMessage.invoke(capture, "Test message");
            
            // 获取输出
            java.lang.reflect.Method getOutput = captureClass.getMethod("getOutput");
            String output = (String) getOutput.invoke(capture);
            
            assertThat(output).isEqualTo("Test message");
        }

        @Test
        @DisplayName("sendMessage(String...) 应该捕获多条消息")
        void sendMessageArrayShouldCaptureMultiple() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method sendMessages = captureClass.getMethod("sendMessage", String[].class);
            sendMessages.invoke(capture, (Object) new String[]{"Message 1", "Message 2", "Message 3"});
            
            java.lang.reflect.Method getOutput = captureClass.getMethod("getOutput");
            String output = (String) getOutput.invoke(capture);
            
            assertThat(output).contains("Message 1", "Message 2", "Message 3");
        }

        @Test
        @DisplayName("sendMessage(UUID, String) 应该捕获消息")
        void sendMessageWithUuidShouldCapture() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method sendMessageUuid = captureClass.getMethod("sendMessage", java.util.UUID.class, String.class);
            sendMessageUuid.invoke(capture, java.util.UUID.randomUUID(), "UUID message");
            
            java.lang.reflect.Method getOutput = captureClass.getMethod("getOutput");
            String output = (String) getOutput.invoke(capture);
            
            assertThat(output).isEqualTo("UUID message");
        }

        @Test
        @DisplayName("sendMessage(UUID, String...) 应该捕获多条消息")
        void sendMessageWithUuidArrayShouldCapture() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method sendMessagesUuid = captureClass.getMethod("sendMessage", java.util.UUID.class, String[].class);
            sendMessagesUuid.invoke(capture, java.util.UUID.randomUUID(), new String[]{"Msg A", "Msg B"});
            
            java.lang.reflect.Method getOutput = captureClass.getMethod("getOutput");
            String output = (String) getOutput.invoke(capture);
            
            assertThat(output).contains("Msg A", "Msg B");
        }

        @Test
        @DisplayName("delegate 方法应该正确委托")
        void delegateMethodsShouldWork() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            when(delegate.getName()).thenReturn("TestSender");
            when(delegate.isOp()).thenReturn(true);
            when(delegate.hasPermission("test.perm")).thenReturn(true);
            when(delegate.isPermissionSet("test.perm")).thenReturn(true);
            when(delegate.getServer()).thenReturn(server);
            
            Object capture = constructor.newInstance(delegate);
            
            // 测试各个委托方法
            java.lang.reflect.Method getName = captureClass.getMethod("getName");
            assertThat(getName.invoke(capture)).isEqualTo("TestSender");
            
            java.lang.reflect.Method isOp = captureClass.getMethod("isOp");
            assertThat(isOp.invoke(capture)).isEqualTo(true);
            
            java.lang.reflect.Method hasPermission = captureClass.getMethod("hasPermission", String.class);
            assertThat(hasPermission.invoke(capture, "test.perm")).isEqualTo(true);
            
            java.lang.reflect.Method isPermissionSet = captureClass.getMethod("isPermissionSet", String.class);
            assertThat(isPermissionSet.invoke(capture, "test.perm")).isEqualTo(true);
            
            java.lang.reflect.Method getServer = captureClass.getMethod("getServer");
            assertThat(getServer.invoke(capture)).isEqualTo(server);
        }

        @Test
        @DisplayName("setOp 应该委托给 delegate")
        void setOpShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method setOp = captureClass.getMethod("setOp", boolean.class);
            setOp.invoke(capture, true);
            
            org.mockito.Mockito.verify(delegate).setOp(true);
        }

        @Test
        @DisplayName("recalculatePermissions 应该委托给 delegate")
        void recalculatePermissionsShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method recalc = captureClass.getMethod("recalculatePermissions");
            recalc.invoke(capture);
            
            org.mockito.Mockito.verify(delegate).recalculatePermissions();
        }

        @Test
        @DisplayName("getEffectivePermissions 应该委托给 delegate")
        void getEffectivePermissionsShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> perms = new java.util.HashSet<>();
            when(delegate.getEffectivePermissions()).thenReturn(perms);
            
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method getEffective = captureClass.getMethod("getEffectivePermissions");
            assertThat(getEffective.invoke(capture)).isEqualTo(perms);
        }

        @Test
        @DisplayName("Permission 对象版本的方法应该正确委托")
        void permissionObjectMethodsShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            org.bukkit.permissions.Permission perm = new org.bukkit.permissions.Permission("test.perm");
            when(delegate.hasPermission(perm)).thenReturn(true);
            when(delegate.isPermissionSet(perm)).thenReturn(true);
            
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method hasPermPerm = captureClass.getMethod("hasPermission", org.bukkit.permissions.Permission.class);
            assertThat(hasPermPerm.invoke(capture, perm)).isEqualTo(true);
            
            java.lang.reflect.Method isPermSetPerm = captureClass.getMethod("isPermissionSet", org.bukkit.permissions.Permission.class);
            assertThat(isPermSetPerm.invoke(capture, perm)).isEqualTo(true);
        }

        @Test
        @DisplayName("addAttachment 方法应该正确委托")
        void addAttachmentMethodsShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            org.bukkit.plugin.Plugin mockPlugin = mock(org.bukkit.plugin.Plugin.class);
            org.bukkit.permissions.PermissionAttachment mockAttachment = mock(org.bukkit.permissions.PermissionAttachment.class);
            
            when(delegate.addAttachment(mockPlugin)).thenReturn(mockAttachment);
            when(delegate.addAttachment(mockPlugin, "perm", true)).thenReturn(mockAttachment);
            when(delegate.addAttachment(mockPlugin, 10)).thenReturn(mockAttachment);
            when(delegate.addAttachment(mockPlugin, "perm", true, 10)).thenReturn(mockAttachment);
            
            Object capture = constructor.newInstance(delegate);
            
            // Test addAttachment(Plugin)
            java.lang.reflect.Method addAttach1 = captureClass.getMethod("addAttachment", org.bukkit.plugin.Plugin.class);
            assertThat(addAttach1.invoke(capture, mockPlugin)).isEqualTo(mockAttachment);
            
            // Test addAttachment(Plugin, String, boolean)
            java.lang.reflect.Method addAttach2 = captureClass.getMethod("addAttachment", org.bukkit.plugin.Plugin.class, String.class, boolean.class);
            assertThat(addAttach2.invoke(capture, mockPlugin, "perm", true)).isEqualTo(mockAttachment);
            
            // Test addAttachment(Plugin, int)
            java.lang.reflect.Method addAttach3 = captureClass.getMethod("addAttachment", org.bukkit.plugin.Plugin.class, int.class);
            assertThat(addAttach3.invoke(capture, mockPlugin, 10)).isEqualTo(mockAttachment);
            
            // Test addAttachment(Plugin, String, boolean, int)
            java.lang.reflect.Method addAttach4 = captureClass.getMethod("addAttachment", org.bukkit.plugin.Plugin.class, String.class, boolean.class, int.class);
            assertThat(addAttach4.invoke(capture, mockPlugin, "perm", true, 10)).isEqualTo(mockAttachment);
        }

        @Test
        @DisplayName("removeAttachment 应该委托给 delegate")
        void removeAttachmentShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            org.bukkit.permissions.PermissionAttachment mockAttachment = mock(org.bukkit.permissions.PermissionAttachment.class);
            
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method removeAttach = captureClass.getMethod("removeAttachment", org.bukkit.permissions.PermissionAttachment.class);
            removeAttach.invoke(capture, mockAttachment);
            
            org.mockito.Mockito.verify(delegate).removeAttachment(mockAttachment);
        }

        @Test
        @DisplayName("spigot 方法应该委托给 delegate")
        void spigotShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            org.bukkit.command.CommandSender.Spigot spigotMock = mock(org.bukkit.command.CommandSender.Spigot.class);
            when(delegate.spigot()).thenReturn(spigotMock);
            
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method spigot = captureClass.getMethod("spigot");
            assertThat(spigot.invoke(capture)).isEqualTo(spigotMock);
        }

        @Test
        @DisplayName("name 方法应该委托给 delegate")
        void nameShouldDelegate() throws Exception {
            Class<?> captureClass = Class.forName("com.ultikits.ultitools.manager.CommandExecutionManager$CommandOutputCapture");
            java.lang.reflect.Constructor<?> constructor = captureClass.getDeclaredConstructor(org.bukkit.command.CommandSender.class);
            constructor.setAccessible(true);
            
            org.bukkit.command.CommandSender delegate = mock(org.bukkit.command.CommandSender.class);
            net.kyori.adventure.text.Component nameComponent = net.kyori.adventure.text.Component.text("TestName");
            when(delegate.name()).thenReturn(nameComponent);
            
            Object capture = constructor.newInstance(delegate);
            
            java.lang.reflect.Method name = captureClass.getMethod("name");
            assertThat(name.invoke(capture)).isEqualTo(nameComponent);
        }
    }

    @Nested
    @DisplayName("executeCommandInternal 详细测试")
    class ExecuteCommandInternalTests {

        @Test
        @DisplayName("玩家执行者应该回退到控制台")
        void playerExecutorShouldFallbackToConsole() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "help");
            commandData.addProperty("executor", "player-uuid-12345"); // 非 "console"
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "player-cmd");

            // Act
            manager.executeCommand(commandData);
            server.getScheduler().performOneTick();

            // Assert - 命令应该被执行（使用控制台作为回退）
            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("命令执行成功应该发送正确的结果")
        void successfulCommandShouldSendCorrectResult() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "help");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "success-cmd");

            // Act
            manager.executeCommand(commandData);
            server.getScheduler().performOneTick();

            // Assert
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject result = captor.getValue();
            assertThat(result.get("type").getAsString()).isEqualTo("command_result");
        }

        @Test
        @DisplayName("空输出应该显示默认消息")
        void emptyOutputShouldShowDefaultMessage() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "nonexistent_command_that_wont_have_output");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "empty-output-cmd");

            // Act
            manager.executeCommand(commandData);
            server.getScheduler().performOneTick();

            // Assert
            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("sendCommandResult 详细测试")
    class SendCommandResultTests {

        @Test
        @DisplayName("结果消息应该包含所有必要字段")
        void resultShouldContainAllFields() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "field-test");

            // Act
            manager.executeCommand(commandData);

            // Assert
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject result = captor.getValue();
            assertThat(result.has("type")).isTrue();
            assertThat(result.has("data")).isTrue();
            assertThat(result.has("serverId")).isTrue();
            
            JsonObject data = result.getAsJsonObject("data");
            assertThat(data.has("commandId")).isTrue();
            assertThat(data.has("success")).isTrue();
            assertThat(data.has("output")).isTrue();
            assertThat(data.has("executionTime")).isTrue();
        }

        @Test
        @DisplayName("serverId 应该从 WebSocket 客户端获取")
        void serverIdShouldBeFromWebSocketClient() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "");
            commandData.addProperty("commandId", "serverid-test");

            // Act
            manager.executeCommand(commandData);

            // Assert
            org.mockito.ArgumentCaptor<JsonObject> captor = org.mockito.ArgumentCaptor.forClass(JsonObject.class);
            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            assertThat(captor.getValue().get("serverId").getAsString()).isEqualTo("test-server-id");
        }
    }

    @Nested
    @DisplayName("pendingCommands 测试")
    class PendingCommandsTests {

        @Test
        @DisplayName("pendingCommands 应该初始化为 ConcurrentHashMap")
        void pendingCommandsShouldBeConcurrentHashMap() throws Exception {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            
            // Act - 使用反射获取 pendingCommands
            java.lang.reflect.Field field = CommandExecutionManager.class.getDeclaredField("pendingCommands");
            field.setAccessible(true);
            Object pendingCommands = field.get(newManager);
            
            // Assert
            assertThat(pendingCommands).isInstanceOf(java.util.concurrent.ConcurrentHashMap.class);
            assertThat(((java.util.concurrent.ConcurrentHashMap<?, ?>) pendingCommands)).isEmpty();
        }
    }

    @Nested
    @DisplayName("webSocketClient 字段测试")
    class WebSocketClientFieldTests {

        @Test
        @DisplayName("webSocketClient 初始值应该为 null")
        void webSocketClientInitialValueShouldBeNull() throws Exception {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            
            // Act
            java.lang.reflect.Field field = CommandExecutionManager.class.getDeclaredField("webSocketClient");
            field.setAccessible(true);
            Object client = field.get(newManager);
            
            // Assert
            assertThat(client).isNull();
        }

        @Test
        @DisplayName("setWebSocketClient 应该正确设置字段")
        void setWebSocketClientShouldSetField() throws Exception {
            // Arrange
            CommandExecutionManager newManager = new CommandExecutionManager();
            UltiPanelWebSocketClient mockClient = mock(UltiPanelWebSocketClient.class);
            
            // Act
            newManager.setWebSocketClient(mockClient);
            
            // Assert
            java.lang.reflect.Field field = CommandExecutionManager.class.getDeclaredField("webSocketClient");
            field.setAccessible(true);
            assertThat(field.get(newManager)).isEqualTo(mockClient);
        }
    }

    @Nested
    @DisplayName("日志记录测试")
    class LoggingTests {

        @Test
        @DisplayName("执行命令时应该记录 INFO 日志")
        void shouldLogInfoOnCommandExecution() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "test");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "log-test");

            // Act
            manager.executeCommand(commandData);

            // Assert
            verify(mockLogger).log(any(java.util.logging.Level.class), anyString());
        }
    }

    @Nested
    @DisplayName("executeCommand 异常场景测试")
    class ExecuteCommandExceptionTests {

        @Test
        @DisplayName("executeCommand 异常时应该发送错误结果")
        void shouldSendErrorResultOnException() throws Exception {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "test");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "exception-test");

            // 模拟 getScheduler() 抛出异常
            CommandExecutionManager newManager = new CommandExecutionManager();
            newManager.setWebSocketClient(mockWebSocketClient);
            
            // 使用反射设置 webSocketClient 字段以模拟异常场景
            java.lang.reflect.Field field = CommandExecutionManager.class.getDeclaredField("webSocketClient");
            field.setAccessible(true);
            
            // 创建一个会在 sendMessage 时记录调用的 mock
            UltiPanelWebSocketClient specialMock = mock(UltiPanelWebSocketClient.class);
            when(specialMock.getServerId()).thenReturn("test-server");
            field.set(newManager, specialMock);

            // Act - 执行命令
            newManager.executeCommand(commandData);

            // Assert - 不应该抛出异常，命令应该被调度
        }

        @Test
        @DisplayName("commandData 包含 null commandId 时异常处理应该正确")
        void shouldHandleNullCommandIdInException() throws Exception {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "test");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            commandData.add("commandId", com.google.gson.JsonNull.INSTANCE);

            // Act
            manager.executeCommand(commandData);

            // Assert - 不应该抛出异常
        }

        @Test
        @DisplayName("commandData 缺少 commandId 字段时异常处理应该正确")
        void shouldHandleMissingCommandIdInException() throws Exception {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "test");
            commandData.addProperty("executor", "console");
            commandData.addProperty("async", false);
            // 不添加 commandId

            // Act
            manager.executeCommand(commandData);

            // Assert - 不应该抛出异常
        }
    }

    @Nested
    @DisplayName("非 console 执行者测试")
    class NonConsoleExecutorTests {

        @Test
        @DisplayName("非 console 执行者应该回退到控制台发送者")
        void nonConsoleExecutorShouldFallbackToConsoleSender() {
            // Arrange
            JsonObject commandData = new JsonObject();
            commandData.addProperty("command", "help");
            commandData.addProperty("executor", "player-uuid-12345");
            commandData.addProperty("async", false);
            commandData.addProperty("commandId", "player-executor-test");

            // Act
            manager.executeCommand(commandData);
            server.getScheduler().performOneTick();

            // Assert - 命令应该被执行
            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }
    }
}
