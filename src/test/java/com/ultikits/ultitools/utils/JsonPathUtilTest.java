package com.ultikits.ultitools.utils;

import org.junit.jupiter.api.*;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonPathUtil 工具类测试
 */
@DisplayName("JsonPathUtil 测试")
class JsonPathUtilTest {

    private Map<String, Object> json;

    @BeforeEach
    void setUp() {
        json = new LinkedHashMap<>();
    }

    @Nested
    @DisplayName("putByPath 测试")
    class PutByPathTests {

        @Test
        @DisplayName("设置单层路径")
        void testSingleLevel() {
            JsonPathUtil.putByPath(json, "key", "value");
            assertEquals("value", json.get("key"));
        }

        @Test
        @DisplayName("设置多层路径")
        void testMultiLevel() {
            JsonPathUtil.putByPath(json, "a.b.c", "deep");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) json.get("a");
            assertNotNull(a);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) a.get("b");
            assertNotNull(b);
            
            assertEquals("deep", b.get("c"));
        }

        @Test
        @DisplayName("覆盖现有值")
        void testOverwrite() {
            JsonPathUtil.putByPath(json, "key", "old");
            JsonPathUtil.putByPath(json, "key", "new");
            assertEquals("new", json.get("key"));
        }

        @Test
        @DisplayName("空路径不执行操作")
        void testEmptyPath() {
            JsonPathUtil.putByPath(json, "", "value");
            assertTrue(json.isEmpty());
        }

        @Test
        @DisplayName("null 路径不执行操作")
        void testNullPath() {
            JsonPathUtil.putByPath(json, null, "value");
            assertTrue(json.isEmpty());
        }

        @Test
        @DisplayName("null json 不抛异常")
        void testNullJson() {
            assertDoesNotThrow(() -> JsonPathUtil.putByPath(null, "key", "value"));
        }

        @Test
        @DisplayName("覆盖非 Map 中间节点")
        void testOverwriteNonMapNode() {
            JsonPathUtil.putByPath(json, "a", "string");
            JsonPathUtil.putByPath(json, "a.b", "value");
            
            // a 应该被覆盖为 Map
            assertTrue(json.get("a") instanceof Map);
            assertEquals("value", JsonPathUtil.getByPath(json, "a.b"));
        }
    }

    @Nested
    @DisplayName("getByPath 测试")
    class GetByPathTests {

        @Test
        @DisplayName("获取单层路径")
        void testSingleLevel() {
            json.put("key", "value");
            assertEquals("value", JsonPathUtil.getByPath(json, "key"));
        }

        @Test
        @DisplayName("获取多层路径")
        void testMultiLevel() {
            JsonPathUtil.putByPath(json, "a.b.c", "deep");
            assertEquals("deep", JsonPathUtil.getByPath(json, "a.b.c"));
        }

        @Test
        @DisplayName("获取不存在的路径返回 null")
        void testNonExistentPath() {
            assertNull(JsonPathUtil.getByPath(json, "nonexistent"));
            assertNull(JsonPathUtil.getByPath(json, "a.b.c"));
        }

        @Test
        @DisplayName("中间节点非 Map 返回 null")
        void testNonMapIntermediateNode() {
            json.put("a", "string");
            assertNull(JsonPathUtil.getByPath(json, "a.b"));
        }

        @Test
        @DisplayName("空路径返回 null")
        void testEmptyPath() {
            json.put("key", "value");
            assertNull(JsonPathUtil.getByPath(json, ""));
        }

        @Test
        @DisplayName("null 参数返回 null")
        void testNullParams() {
            assertNull(JsonPathUtil.getByPath(null, "key"));
            assertNull(JsonPathUtil.getByPath(json, null));
        }
    }

    @Nested
    @DisplayName("类型化 get 方法测试")
    class TypedGetTests {

        @BeforeEach
        void setUpData() {
            JsonPathUtil.putByPath(json, "string", "hello");
            JsonPathUtil.putByPath(json, "integer", 42);
            JsonPathUtil.putByPath(json, "long", 9999999999L);
            JsonPathUtil.putByPath(json, "double", 3.14);
            JsonPathUtil.putByPath(json, "boolTrue", true);
            JsonPathUtil.putByPath(json, "boolFalse", false);
            JsonPathUtil.putByPath(json, "stringNum", "123");
        }

        @Test
        @DisplayName("getStr 返回字符串")
        void testGetStr() {
            assertEquals("hello", JsonPathUtil.getStr(json, "string"));
            assertEquals("42", JsonPathUtil.getStr(json, "integer"));
            assertNull(JsonPathUtil.getStr(json, "nonexistent"));
        }

        @Test
        @DisplayName("getInt 返回整数")
        void testGetInt() {
            assertEquals(42, JsonPathUtil.getInt(json, "integer"));
            assertEquals(123, JsonPathUtil.getInt(json, "stringNum"));
            assertNull(JsonPathUtil.getInt(json, "nonexistent"));
            assertNull(JsonPathUtil.getInt(json, "string")); // "hello" 无法解析
        }

        @Test
        @DisplayName("getLong 返回长整数")
        void testGetLong() {
            assertEquals(9999999999L, JsonPathUtil.getLong(json, "long"));
            assertEquals(42L, JsonPathUtil.getLong(json, "integer"));
            assertNull(JsonPathUtil.getLong(json, "nonexistent"));
        }

        @Test
        @DisplayName("getDouble 返回双精度浮点数")
        void testGetDouble() {
            assertEquals(3.14, JsonPathUtil.getDouble(json, "double"), 0.001);
            assertEquals(42.0, JsonPathUtil.getDouble(json, "integer"), 0.001);
            assertNull(JsonPathUtil.getDouble(json, "nonexistent"));
        }

        @Test
        @DisplayName("getBool 返回布尔值")
        void testGetBool() {
            assertTrue(JsonPathUtil.getBool(json, "boolTrue"));
            assertFalse(JsonPathUtil.getBool(json, "boolFalse"));
            assertNull(JsonPathUtil.getBool(json, "nonexistent"));
        }
    }

    @Nested
    @DisplayName("removeByPath 测试")
    class RemoveByPathTests {

        @Test
        @DisplayName("删除单层路径")
        void testSingleLevel() {
            json.put("key", "value");
            assertEquals("value", JsonPathUtil.removeByPath(json, "key"));
            assertNull(json.get("key"));
        }

        @Test
        @DisplayName("删除多层路径")
        void testMultiLevel() {
            JsonPathUtil.putByPath(json, "a.b.c", "value");
            assertEquals("value", JsonPathUtil.removeByPath(json, "a.b.c"));
            assertNull(JsonPathUtil.getByPath(json, "a.b.c"));
            // 父节点仍然存在
            assertNotNull(JsonPathUtil.getByPath(json, "a.b"));
        }

        @Test
        @DisplayName("删除不存在的路径返回 null")
        void testNonExistentPath() {
            assertNull(JsonPathUtil.removeByPath(json, "nonexistent"));
        }
    }

    @Nested
    @DisplayName("containsPath 测试")
    class ContainsPathTests {

        @Test
        @DisplayName("路径存在返回 true")
        void testExistingPath() {
            JsonPathUtil.putByPath(json, "a.b", "value");
            assertTrue(JsonPathUtil.containsPath(json, "a.b"));
            assertTrue(JsonPathUtil.containsPath(json, "a"));
        }

        @Test
        @DisplayName("路径不存在返回 false")
        void testNonExistentPath() {
            assertFalse(JsonPathUtil.containsPath(json, "nonexistent"));
        }
    }
}
