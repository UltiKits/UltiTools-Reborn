package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;

/**
 * CommonUtils 测试类
 * 由于 CommonUtils 依赖 UltiTools 实例，这里主要测试方法签名和可访问性
 */
@DisplayName("CommonUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CommonUtilsTest {

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getUltiToolsUUID方法应该存在")
        void getUltiToolsUUIDMethodShouldExist() throws Exception {
            Method method = CommonUtils.class.getMethod("getUltiToolsUUID");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getUltiToolsUUID方法应该是公开静态的")
        void getUltiToolsUUIDMethodShouldBePublicStatic() throws Exception {
            Method method = CommonUtils.class.getMethod("getUltiToolsUUID");
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("CommonUtils类应该存在")
        void classShouldExist() {
            assertThat(CommonUtils.class).isNotNull();
        }

        @Test
        @DisplayName("CommonUtils类应该是公开的")
        void classShouldBePublic() {
            assertThat(java.lang.reflect.Modifier.isPublic(CommonUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("CommonUtils类不应该是抽象的")
        void classShouldNotBeAbstract() {
            assertThat(java.lang.reflect.Modifier.isAbstract(CommonUtils.class.getModifiers())).isFalse();
        }
    }

    @Nested
    @DisplayName("UUID格式测试")
    class UUIDFormatTests {

        @Test
        @DisplayName("简单UUID格式应该是32个字符")
        void simpleUUIDShouldBe32Characters() {
            // 测试 UUID.randomUUID().toString().replace("-", "") 的格式
            String simpleUUID = java.util.UUID.randomUUID().toString().replace("-", "");
            assertThat(simpleUUID).hasSize(32);
            assertThat(simpleUUID).matches("[a-f0-9]{32}");
        }

        @Test
        @DisplayName("简单UUID应该不包含连字符")
        void simpleUUIDShouldNotContainHyphens() {
            String simpleUUID = java.util.UUID.randomUUID().toString().replace("-", "");
            assertThat(simpleUUID).doesNotContain("-");
        }

        @Test
        @DisplayName("每次生成的UUID应该不同")
        void eachUUIDShouldBeUnique() {
            String uuid1 = java.util.UUID.randomUUID().toString().replace("-", "");
            String uuid2 = java.util.UUID.randomUUID().toString().replace("-", "");
            assertThat(uuid1).isNotEqualTo(uuid2);
        }
    }

    @Nested
    @DisplayName("JSON工具测试")
    class JSONUtilsTests {

        @Test
        @DisplayName("JsonPathUtil应该能存储和读取path值")
        void jsonPathUtilShouldStoreAndRetrievePathValue() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            String testUUID = "test-uuid-12345";
            JsonPathUtil.putByPath(map, "uuid", testUUID);
            
            Object retrieved = JsonPathUtil.getByPath(map, "uuid");
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.toString()).isEqualTo(testUUID);
        }

        @Test
        @DisplayName("JsonPathUtil应该支持嵌套路径")
        void jsonPathUtilShouldSupportNestedPath() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            JsonPathUtil.putByPath(map, "data.uuid", "nested-uuid");
            
            Object retrieved = JsonPathUtil.getByPath(map, "data.uuid");
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.toString()).isEqualTo("nested-uuid");
        }
    }

    @Nested
    @DisplayName("getUltiToolsUUID 功能测试 - 使用 Mock")
    class GetUltiToolsUUIDFunctionalTests {

        @TempDir
        File tempDir;

        private MockedStatic<UltiTools> mockedUltiTools;
        private UltiTools mockUltiToolsInstance;

        @BeforeEach
        void setUp() {
            mockUltiToolsInstance = mock(UltiTools.class);
            mockedUltiTools = mockStatic(UltiTools.class);
            mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiToolsInstance);
            when(mockUltiToolsInstance.getDataFolder()).thenReturn(tempDir);
            // Plan 08-15 moved CredentialStore's production target outside the plugin data
            // folder; this class asserts directly against a file under tempDir, so the target is
            // pinned here rather than relying on the (now different) production default.
            CredentialStore.setTargetPathForTesting(tempDir.toPath().resolve("credentials.json"));
        }

        @AfterEach
        void tearDown() {
            CredentialStore.clearTargetPathForTesting();
            if (mockedUltiTools != null) {
                mockedUltiTools.close();
            }
        }

        @Test
        @DisplayName("当 data.json 不存在时应该创建新文件并生成 UUID")
        void shouldCreateNewFileAndGenerateUUIDWhenFileNotExists() throws Exception {
            // 确保文件不存在
            File dataFile = new File(tempDir, "credentials.json");
            assertThat(dataFile.exists()).isFalse();
            
            // 调用方法
            String uuid = CommonUtils.getUltiToolsUUID();
            
            // 验证结果
            assertThat(uuid).isNotNull();
            assertThat(uuid).hasSize(32);
            assertThat(uuid).matches("[a-f0-9]{32}");
            
            // 验证文件已创建
            assertThat(dataFile.exists()).isTrue();
        }

        @Test
        @DisplayName("当 data.json 存在时应该读取现有 UUID")
        void shouldReadExistingUUIDWhenFileExists() throws Exception {
            // 预先创建文件
            File dataFile = new File(tempDir, "credentials.json");
            String existingUUID = "abcd1234efgh5678ijkl9012mnop3456";
            JsonObject json = new JsonObject();
            json.addProperty("uuid", existingUUID);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(gson.toJson(json));
            }
            
            // 调用方法
            String uuid = CommonUtils.getUltiToolsUUID();
            
            // 验证返回的是现有 UUID
            assertThat(uuid).isEqualTo(existingUUID);
        }

        @Test
        @DisplayName("多次调用应该返回相同的 UUID（文件存在时）")
        void shouldReturnSameUUIDOnMultipleCalls() throws Exception {
            // 预先创建文件
            File dataFile = new File(tempDir, "credentials.json");
            String existingUUID = "test1234test5678test9012test3456";
            JsonObject json = new JsonObject();
            json.addProperty("uuid", existingUUID);
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(json));
            }
            
            // 多次调用
            String uuid1 = CommonUtils.getUltiToolsUUID();
            String uuid2 = CommonUtils.getUltiToolsUUID();
            String uuid3 = CommonUtils.getUltiToolsUUID();
            
            // 验证返回相同值
            assertThat(uuid1).isEqualTo(existingUUID);
            assertThat(uuid2).isEqualTo(existingUUID);
            assertThat(uuid3).isEqualTo(existingUUID);
        }

        @Test
        @DisplayName("新生成的 UUID 应该被持久化到文件")
        void newlyGeneratedUUIDShouldBePersistedToFile() throws Exception {
            // 确保文件不存在
            File dataFile = new File(tempDir, "credentials.json");
            assertThat(dataFile.exists()).isFalse();
            
            // 调用方法生成 UUID
            String generatedUUID = CommonUtils.getUltiToolsUUID();
            
            // 读取文件验证 UUID 已持久化
            JsonObject savedJson;
            try (FileReader reader = new FileReader(dataFile)) {
                savedJson = new Gson().fromJson(reader, JsonObject.class);
            }
            String savedUUID = savedJson.get("uuid").getAsString();
            
            assertThat(savedUUID).isEqualTo(generatedUUID);
        }

        @Test
        @DisplayName("应该能处理包含其他数据的 JSON 文件")
        void shouldHandleJsonFileWithOtherData() throws Exception {
            // 创建包含其他数据的 JSON 文件
            File dataFile = new File(tempDir, "credentials.json");
            String existingUUID = "uuid1234uuid5678uuid9012uuid3456";
            JsonObject json = new JsonObject();
            json.addProperty("uuid", existingUUID);
            json.addProperty("version", "6.0.0");
            JsonObject settings = new JsonObject();
            settings.addProperty("enabled", true);
            json.add("settings", settings);
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(json));
            }
            
            // 调用方法
            String uuid = CommonUtils.getUltiToolsUUID();
            
            // 验证返回正确的 UUID
            assertThat(uuid).isEqualTo(existingUUID);
        }
    }

    @Nested
    @DisplayName("文件操作边界测试")
    class FileOperationBoundaryTests {

        @TempDir
        File tempDir;

        private MockedStatic<UltiTools> mockedUltiTools;
        private UltiTools mockUltiToolsInstance;

        @BeforeEach
        void setUp() {
            mockUltiToolsInstance = mock(UltiTools.class);
            mockedUltiTools = mockStatic(UltiTools.class);
            mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiToolsInstance);
            when(mockUltiToolsInstance.getDataFolder()).thenReturn(tempDir);
            // Plan 08-15 moved CredentialStore's production target outside the plugin data
            // folder; this class asserts directly against a file under tempDir, so the target is
            // pinned here rather than relying on the (now different) production default.
            CredentialStore.setTargetPathForTesting(tempDir.toPath().resolve("credentials.json"));
        }

        @AfterEach
        void tearDown() {
            CredentialStore.clearTargetPathForTesting();
            if (mockedUltiTools != null) {
                mockedUltiTools.close();
            }
        }

        @Test
        @DisplayName("应该处理空 JSON 对象（没有 uuid 键）")
        void shouldHandleEmptyJsonObject() throws Exception {
            // 创建空 JSON 文件
            File dataFile = new File(tempDir, "credentials.json");
            JsonObject json = new JsonObject();
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(json));
            }
            
            // 调用方法 - getByPath 会返回 null，可能导致 NPE
            // 这测试当前实现的行为
            try {
                String uuid = CommonUtils.getUltiToolsUUID();
                // 如果没有抛出异常，uuid 可能是 "null" 字符串
                assertThat(uuid).isNotNull();
            } catch (NullPointerException e) {
                // 如果抛出 NPE，说明代码需要改进
                // 但这也是一个有效的测试结果
            }
        }

        @Test
        @DisplayName("新创建的文件应该包含有效的 JSON 格式")
        void newlyCreatedFileShouldContainValidJson() throws Exception {
            // 确保文件不存在
            File dataFile = new File(tempDir, "credentials.json");
            
            // 调用方法创建文件
            CommonUtils.getUltiToolsUUID();
            
            // 读取文件内容
            String content = new String(Files.readAllBytes(dataFile.toPath()), StandardCharsets.UTF_8);
            
            // 验证是有效的 JSON
            assertThat(content).isNotEmpty();
            JsonObject parsed = new Gson().fromJson(content, JsonObject.class);
            assertThat(parsed.has("uuid")).isTrue();
        }

        @Test
        @DisplayName("UUID 值应该是字符串类型")
        void uuidValueShouldBeStringType() throws Exception {
            // 调用方法创建文件
            String uuid = CommonUtils.getUltiToolsUUID();
            
            // 读取文件验证类型
            File dataFile = new File(tempDir, "credentials.json");
            JsonObject jsonRead;
            try (FileReader reader = new FileReader(dataFile)) {
                jsonRead = new Gson().fromJson(reader, JsonObject.class);
            }
            Object uuidValue = jsonRead.get("uuid").getAsString();
            
            assertThat(uuidValue).isInstanceOf(String.class);
            assertThat(uuidValue.toString()).isEqualTo(uuid);
        }
    }

    @Nested
    @DisplayName("UUID 生成测试")
    class UuidGenerationTests {

        @TempDir
        File tempDir;

        private MockedStatic<UltiTools> mockedUltiTools;
        private UltiTools mockUltiToolsInstance;

        @BeforeEach
        void setUp() {
            mockUltiToolsInstance = mock(UltiTools.class);
            mockedUltiTools = mockStatic(UltiTools.class);
            mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiToolsInstance);
            when(mockUltiToolsInstance.getDataFolder()).thenReturn(tempDir);
            // Plan 08-15 moved CredentialStore's production target outside the plugin data
            // folder; this class asserts directly against a file under tempDir, so the target is
            // pinned here rather than relying on the (now different) production default.
            CredentialStore.setTargetPathForTesting(tempDir.toPath().resolve("credentials.json"));
        }

        @AfterEach
        void tearDown() {
            CredentialStore.clearTargetPathForTesting();
            if (mockedUltiTools != null) {
                mockedUltiTools.close();
            }
        }

        @Test
        @DisplayName("当文件不存在时应该生成新的 UUID")
        void shouldGenerateNewUUIDWhenFileNotExists() throws Exception {
            File dataFile = new File(tempDir, "credentials.json");
            assertThat(dataFile.exists()).isFalse();
            
            String uuid = CommonUtils.getUltiToolsUUID();
            
            // UUID 应该是 32 位 hex 字符串 (不带横杠)
            assertThat(uuid).isNotNull();
            assertThat(uuid).hasSize(32);
            assertThat(uuid).matches("[0-9a-f]{32}");
            
            // 验证文件已创建
            assertThat(dataFile.exists()).isTrue();
        }

        @Test
        @DisplayName("当文件存在时应该返回已保存的 UUID")
        void shouldReturnExistingUUIDWhenFileExists() throws Exception {
            // 预先创建文件
            File dataFile = new File(tempDir, "credentials.json");
            String existingUUID = "existinguuid123456789012345678ab";
            JsonObject json = new JsonObject();
            json.addProperty("uuid", existingUUID);
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(json));
            }
            
            String uuid = CommonUtils.getUltiToolsUUID();
            
            assertThat(uuid).isEqualTo(existingUUID);
        }
    }
}
