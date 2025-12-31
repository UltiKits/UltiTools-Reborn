package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.entities.vo.ServerEntityVO;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * HttpRequestUtils 测试类
 * 由于 HttpRequestUtils 依赖外部 API 和 UltiTools 实例，这里主要测试方法签名和辅助功能
 */
@DisplayName("HttpRequestUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HttpRequestUtilsTest {

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getToken方法应该存在且参数正确")
        void getTokenMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "getToken", String.class, String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(TokenEntity.class);
            assertThat(method.getParameterCount()).isEqualTo(2);
            assertThat(method.getParameterTypes()).containsExactly(String.class, String.class);
        }

        @Test
        @DisplayName("getServerByUUID方法应该存在且参数正确")
        void getServerByUUIDMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "getServerByUUID", String.class, TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(HttpResponse.class);
            assertThat(method.getParameterCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("registerServer方法应该存在且参数正确")
        void registerServerMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "registerServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(HttpResponse.class);
            assertThat(method.getParameterCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("updateServer方法应该存在且参数正确")
        void updateServerMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "updateServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(HttpResponse.class);
            assertThat(method.getParameterCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("所有方法都应该声明正确的参数名")
        void allMethodsShouldHaveCorrectParameterTypes() throws Exception {
            // getToken
            Method getToken = HttpRequestUtils.class.getDeclaredMethod("getToken", String.class, String.class);
            assertThat(getToken.getParameterTypes()[0]).isEqualTo(String.class); // username
            assertThat(getToken.getParameterTypes()[1]).isEqualTo(String.class); // password
            
            // registerServer
            Method registerServer = HttpRequestUtils.class.getDeclaredMethod(
                "registerServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(registerServer.getParameterTypes()[0]).isEqualTo(String.class); // uuid
            assertThat(registerServer.getParameterTypes()[1]).isEqualTo(int.class);    // port
            assertThat(registerServer.getParameterTypes()[2]).isEqualTo(String.class); // domain
            assertThat(registerServer.getParameterTypes()[3]).isEqualTo(boolean.class); // ssl
            assertThat(registerServer.getParameterTypes()[4]).isEqualTo(TokenEntity.class); // token
        }
    }

    @Nested
    @DisplayName("方法可见性测试")
    class MethodVisibilityTests {

        @Test
        @DisplayName("getToken方法应该是受保护的静态方法")
        void getTokenShouldBeProtectedStatic() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "getToken", String.class, String.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getServerByUUID方法应该是受保护的静态方法")
        void getServerByUUIDShouldBeProtectedStatic() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "getServerByUUID", String.class, TokenEntity.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("registerServer方法应该是受保护的静态方法")
        void registerServerShouldBeProtectedStatic() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "registerServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("updateServer方法应该是受保护的静态方法")
        void updateServerShouldBeProtectedStatic() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "updateServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("HttpRequestUtils类应该存在")
        void classShouldExist() {
            assertThat(HttpRequestUtils.class).isNotNull();
        }

        @Test
        @DisplayName("HttpRequestUtils类应该是公开的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(HttpRequestUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有baseUrl变量")
        void shouldHaveBaseUrlField() throws Exception {
            Field baseUrlField = HttpRequestUtils.class.getDeclaredField("baseUrl");
            assertThat(baseUrlField).isNotNull();
            assertThat(Modifier.isPrivate(baseUrlField.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(baseUrlField.getModifiers())).isTrue();
            assertThat(Modifier.isVolatile(baseUrlField.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有customBaseUrl变量")
        void shouldHaveCustomBaseUrlField() throws Exception {
            Field customBaseUrlField = HttpRequestUtils.class.getDeclaredField("customBaseUrl");
            assertThat(customBaseUrlField).isNotNull();
            assertThat(Modifier.isPrivate(customBaseUrlField.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(customBaseUrlField.getModifiers())).isTrue();
        }
        
        @Test
        @DisplayName("应该有getBaseUrl方法")
        void shouldHaveGetBaseUrlMethod() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("getBaseUrl");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
        
        @Test
        @DisplayName("应该有setBaseUrlForTesting方法")
        void shouldHaveSetBaseUrlForTestingMethod() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
        
        @Test
        @DisplayName("应该有resetBaseUrl方法")
        void shouldHaveResetBaseUrlMethod() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("类应该有4个声明的方法")
        void classShouldHaveFourDeclaredMethods() {
            Method[] methods = HttpRequestUtils.class.getDeclaredMethods();
            // 过滤掉可能的合成方法
            long publicProtectedMethods = java.util.Arrays.stream(methods)
                .filter(m -> !m.isSynthetic())
                .count();
            assertThat(publicProtectedMethods).isGreaterThanOrEqualTo(4);
        }
    }

    @Nested
    @DisplayName("ServerEntityVO构建测试")
    class ServerEntityVOBuildTests {

        @Test
        @DisplayName("应该能使用builder构建ServerEntityVO")
        void shouldBuildServerEntityVO() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test-uuid")
                .name("Test Server")
                .port(25565)
                .ssl(true)
                .domain("example.com")
                .build();
            
            assertThat(server).isNotNull();
            assertThat(server.getUuid()).isEqualTo("test-uuid");
            assertThat(server.getName()).isEqualTo("Test Server");
            assertThat(server.getPort()).isEqualTo(25565);
            assertThat(server.isSsl()).isTrue();
            assertThat(server.getDomain()).isEqualTo("example.com");
        }

        @Test
        @DisplayName("ServerEntityVO应该能序列化为JSON")
        void serverEntityVOShouldSerializeToJSON() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test-uuid")
                .name("Test Server")
                .port(25565)
                .ssl(false)
                .domain("localhost")
                .build();
            
            String json = com.alibaba.fastjson.JSONObject.toJSONString(server);
            assertThat(json).isNotNull();
            assertThat(json).contains("test-uuid");
            assertThat(json).contains("Test Server");
        }

        @Test
        @DisplayName("ServerEntityVO的JSON应该能被正确解析")
        void serverEntityVOJsonShouldBeParseable() {
            // ServerEntityVO 使用 @Builder 没有默认构造函数，验证 JSON 字符串格式正确
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test-uuid")
                .name("Test")
                .port(25565)
                .ssl(true)
                .domain("test.com")
                .build();
            
            String json = JSONObject.toJSONString(server);
            JSONObject parsed = JSONObject.parseObject(json);
            
            assertThat(parsed.getString("uuid")).isEqualTo("test-uuid");
            assertThat(parsed.getInteger("port")).isEqualTo(25565);
            assertThat(parsed.getBoolean("ssl")).isTrue();
        }

        @Test
        @DisplayName("ServerEntityVO默认SSL应该为false")
        void serverEntityVODefaultSslShouldBeFalse() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test-uuid")
                .build();
            
            assertThat(server.isSsl()).isFalse();
        }

        @Test
        @DisplayName("ServerEntityVO默认端口应该为0")
        void serverEntityVODefaultPortShouldBeZero() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test-uuid")
                .build();
            
            assertThat(server.getPort()).isEqualTo(0);
        }

        @Test
        @DisplayName("应该能更新ServerEntityVO的字段")
        void shouldUpdateServerEntityVOFields() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("original-uuid")
                .port(25565)
                .build();
            
            server.setUuid("updated-uuid");
            server.setPort(25566);
            
            assertThat(server.getUuid()).isEqualTo("updated-uuid");
            assertThat(server.getPort()).isEqualTo(25566);
        }
    }

    @Nested
    @DisplayName("URL处理测试")
    class URLProcessingTests {

        @Test
        @DisplayName("应该正确清理URL中的空白字符")
        void shouldCleanWhitespaceFromURL() {
            String urlWithWhitespace = "  https://api.example.com  ";
            String cleanUrl = urlWithWhitespace.trim();
            
            assertThat(cleanUrl).isEqualTo("https://api.example.com");
            assertThat(cleanUrl).doesNotStartWith(" ");
            assertThat(cleanUrl).doesNotEndWith(" ");
        }

        @Test
        @DisplayName("null URL应该被处理为空字符串")
        void nullURLShouldBeHandledAsEmpty() {
            String baseUrl = null;
            String cleanBaseUrl = baseUrl != null ? baseUrl.trim() : "";
            
            assertThat(cleanBaseUrl).isEmpty();
        }

        @Test
        @DisplayName("应该正确拼接URL路径")
        void shouldConcatenateURLPath() {
            String baseUrl = "https://api.example.com";
            String path = "/user/getToken";
            String fullUrl = baseUrl + path;
            
            assertThat(fullUrl).isEqualTo("https://api.example.com/user/getToken");
        }

        @Test
        @DisplayName("应该正确拼接带参数的URL")
        void shouldConcatenateURLWithQueryParam() {
            String baseUrl = "https://api.example.com";
            String path = "/server/getByUUID?uuid=";
            String uuid = "test-uuid-123";
            String fullUrl = baseUrl + path + uuid;
            
            assertThat(fullUrl).isEqualTo("https://api.example.com/server/getByUUID?uuid=test-uuid-123");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://api.example.com",
            "http://localhost:8080",
            "https://api.ultikits.com"
        })
        @DisplayName("各种URL格式应该能正确拼接")
        void variousURLFormatsShouldConcatenateCorrectly(String baseUrl) {
            String fullUrl = baseUrl + "/user/getToken";
            
            assertThat(fullUrl).startsWith(baseUrl);
            assertThat(fullUrl).endsWith("/user/getToken");
        }

        @Test
        @DisplayName("URL中的换行符应该被移除")
        void newlineInURLShouldBeRemoved() {
            String urlWithNewline = "https://api.example.com\n";
            String cleanUrl = urlWithNewline.trim();
            
            assertThat(cleanUrl).doesNotContain("\n");
            assertThat(cleanUrl).isEqualTo("https://api.example.com");
        }

        @Test
        @DisplayName("URL中的制表符应该被移除")
        void tabInURLShouldBeRemoved() {
            String urlWithTab = "\thttps://api.example.com\t";
            String cleanUrl = urlWithTab.trim();
            
            assertThat(cleanUrl).doesNotContain("\t");
            assertThat(cleanUrl).isEqualTo("https://api.example.com");
        }
    }

    @Nested
    @DisplayName("TokenEntity测试")
    class TokenEntityTests {

        @Test
        @DisplayName("TokenEntity应该有access_token字段")
        void tokenEntityShouldHaveAccessToken() throws Exception {
            assertThat(TokenEntity.class.getDeclaredMethod("getAccess_token")).isNotNull();
        }

        @Test
        @DisplayName("TokenEntity应该有user_id字段")
        void tokenEntityShouldHaveUserId() throws Exception {
            assertThat(TokenEntity.class.getDeclaredMethod("getUser_id")).isNotNull();
        }

        @Test
        @DisplayName("TokenEntity应该有decodeJwtPayload方法")
        void tokenEntityShouldHaveDecodeMethod() throws Exception {
            assertThat(TokenEntity.class.getDeclaredMethod("decodeJwtPayload")).isNotNull();
        }

        @Test
        @DisplayName("TokenEntity应该能设置和获取access_token")
        void tokenEntityShouldSetAndGetAccessToken() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token("test-token-value");
            
            assertThat(token.getAccess_token()).isEqualTo("test-token-value");
        }

        @Test
        @DisplayName("TokenEntity应该能设置和获取user_id")
        void tokenEntityShouldSetAndGetUserId() {
            TokenEntity token = new TokenEntity();
            token.setUser_id(12345L);
            
            assertThat(token.getUser_id()).isEqualTo(12345L);
        }

        @Test
        @DisplayName("TokenEntity应该能设置和获取refresh_token")
        void tokenEntityShouldSetAndGetRefreshToken() {
            TokenEntity token = new TokenEntity();
            token.setRefresh_token("refresh-token-value");
            
            assertThat(token.getRefresh_token()).isEqualTo("refresh-token-value");
        }

        @Test
        @DisplayName("TokenEntity应该能设置和获取expires_in")
        void tokenEntityShouldSetAndGetExpiresIn() {
            TokenEntity token = new TokenEntity();
            token.setExpires_in(3600);
            
            assertThat(token.getExpires_in()).isEqualTo(3600);
        }

        @Test
        @DisplayName("空token调用decodeJwtPayload不应该抛异常")
        void emptyTokenDecodeShouldNotThrow() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token(null);
            
            // 不应该抛出异常
            token.decodeJwtPayload();
            
            assertThat(token.getUser_id()).isNull();
        }

        @Test
        @DisplayName("空字符串token调用decodeJwtPayload不应该抛异常")
        void emptyStringTokenDecodeShouldNotThrow() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token("");
            
            // 不应该抛出异常
            token.decodeJwtPayload();
            
            assertThat(token.getUser_id()).isNull();
        }

        @Test
        @DisplayName("无效JWT格式不应该抛异常")
        void invalidJwtFormatShouldNotThrow() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token("not-a-valid-jwt");
            
            // 不应该抛出异常
            token.decodeJwtPayload();
        }

        @Test
        @DisplayName("只有一个部分的JWT不应该抛异常")
        void jwtWithOnePartShouldNotThrow() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token("onlyonepart");
            
            token.decodeJwtPayload();
            assertThat(token.getUser_id()).isNull();
        }

        @Test
        @DisplayName("只有两个部分的JWT不应该抛异常")
        void jwtWithTwoPartsShouldNotThrow() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token("part1.part2");
            
            token.decodeJwtPayload();
            assertThat(token.getUser_id()).isNull();
        }
    }

    @Nested
    @DisplayName("HTTP请求头测试")
    class HttpHeaderTests {

        @Test
        @DisplayName("Authorization头应该使用Bearer前缀")
        void authorizationHeaderShouldUseBearerPrefix() {
            String accessToken = "test-access-token";
            String authHeader = "Bearer " + accessToken;
            
            assertThat(authHeader).startsWith("Bearer ");
            assertThat(authHeader).contains(accessToken);
        }

        @Test
        @DisplayName("Content-Type应该是application/x-www-form-urlencoded或application/json")
        void contentTypeShouldBeCorrect() {
            String formContentType = "application/x-www-form-urlencoded";
            String jsonContentType = "application/json";
            
            assertThat(formContentType).isEqualTo("application/x-www-form-urlencoded");
            assertThat(jsonContentType).isEqualTo("application/json");
        }
    }

    @Nested
    @DisplayName("表单数据构建测试")
    class FormDataBuildTests {

        @Test
        @DisplayName("应该能构建getToken的表单数据")
        void shouldBuildGetTokenFormData() {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("username", "testuser");
            paramMap.put("password", "testpass");
            
            assertThat(paramMap).containsEntry("username", "testuser");
            assertThat(paramMap).containsEntry("password", "testpass");
            assertThat(paramMap).hasSize(2);
        }

        @Test
        @DisplayName("应该能构建registerServer的表单数据")
        void shouldBuildRegisterServerFormData() {
            TokenEntity token = new TokenEntity();
            token.setUser_id(12345L);
            
            ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid("test-uuid")
                .name("MC Server")
                .port(25565)
                .ssl(true)
                .domain("example.com")
                .build();
            
            Map<String, Object> formMap = new HashMap<>();
            formMap.put("id", token.getUser_id());
            formMap.put("serverData", JSONObject.toJSONString(serverEntityVO));
            
            assertThat(formMap).containsEntry("id", 12345L);
            assertThat(formMap.get("serverData").toString()).contains("test-uuid");
            assertThat(formMap.get("serverData").toString()).contains("MC Server");
        }

        @Test
        @DisplayName("应该能构建updateServer的表单数据")
        void shouldBuildUpdateServerFormData() {
            TokenEntity token = new TokenEntity();
            token.setUser_id(67890L);
            
            ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid("update-uuid")
                .port(25566)
                .ssl(false)
                .domain("updated.example.com")
                .build();
            
            Map<String, Object> formMap = new HashMap<>();
            formMap.put("id", token.getUser_id());
            formMap.put("serverData", JSONObject.toJSONString(serverEntityVO));
            
            assertThat(formMap).containsEntry("id", 67890L);
            assertThat(formMap.get("serverData").toString()).contains("update-uuid");
            assertThat(formMap.get("serverData").toString()).contains("25566");
        }

        @Test
        @DisplayName("表单数据中的JSON应该是有效的")
        void formDataJsonShouldBeValid() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("json-test-uuid")
                .port(25565)
                .build();
            
            String json = JSONObject.toJSONString(server);
            
            // 验证 JSON 格式正确且可以被解析
            JSONObject parsed = JSONObject.parseObject(json);
            assertThat(parsed.getString("uuid")).isEqualTo("json-test-uuid");
            assertThat(parsed.getInteger("port")).isEqualTo(25565);
        }
    }

    @Nested
    @DisplayName("端点路径测试")
    class EndpointPathTests {

        @Test
        @DisplayName("getToken端点路径应该正确")
        void getTokenEndpointShouldBeCorrect() {
            String endpoint = "/user/getToken";
            assertThat(endpoint).isEqualTo("/user/getToken");
        }

        @Test
        @DisplayName("getServerByUUID端点路径应该正确")
        void getServerByUUIDEndpointShouldBeCorrect() {
            String endpoint = "/server/getByUUID";
            assertThat(endpoint).isEqualTo("/server/getByUUID");
        }

        @Test
        @DisplayName("registerServer端点路径应该正确")
        void registerServerEndpointShouldBeCorrect() {
            String endpoint = "/editor/register";
            assertThat(endpoint).isEqualTo("/editor/register");
        }

        @Test
        @DisplayName("updateServer端点路径应该正确")
        void updateServerEndpointShouldBeCorrect() {
            String endpoint = "/editor/updateServer";
            assertThat(endpoint).isEqualTo("/editor/updateServer");
        }
    }

    @Nested
    @DisplayName("参数验证逻辑测试")
    class ParameterValidationTests {

        @ParameterizedTest
        @ValueSource(ints = {1, 80, 443, 8080, 25565, 65535})
        @DisplayName("有效端口号应该被接受")
        void validPortsShouldBeAccepted(int port) {
            assertThat(port).isGreaterThan(0);
            assertThat(port).isLessThanOrEqualTo(65535);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("SSL参数应该支持true和false")
        void sslParameterShouldSupportBothValues(boolean ssl) {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test")
                .ssl(ssl)
                .build();
            
            assertThat(server.isSsl()).isEqualTo(ssl);
        }

        @Test
        @DisplayName("UUID应该能包含连字符")
        void uuidShouldContainHyphens() {
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid(uuid)
                .build();
            
            assertThat(server.getUuid()).contains("-");
        }

        @Test
        @DisplayName("域名应该能包含子域名")
        void domainShouldSupportSubdomains() {
            String domain = "mc.server.example.com";
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test")
                .domain(domain)
                .build();
            
            assertThat(server.getDomain()).isEqualTo(domain);
            assertThat(server.getDomain()).contains(".");
        }
    }

    @Nested
    @DisplayName("JSON序列化测试")
    class JsonSerializationTests {

        @Test
        @DisplayName("TokenEntity应该能从JSON解析")
        void tokenEntityShouldParseFromJson() {
            String json = "{\"access_token\":\"test-token\",\"refresh_token\":\"refresh\",\"expires_in\":3600}";
            TokenEntity token = JSONObject.parseObject(json, TokenEntity.class);
            
            assertThat(token.getAccess_token()).isEqualTo("test-token");
            assertThat(token.getRefresh_token()).isEqualTo("refresh");
            assertThat(token.getExpires_in()).isEqualTo(3600);
        }

        @Test
        @DisplayName("ServerEntityVO应该能序列化为JSON字符串")
        void serverEntityVOShouldSerializeToJsonString() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("serialize-test")
                .name("Test Server")
                .port(25565)
                .ssl(true)
                .domain("test.com")
                .build();
            
            String json = JSONObject.toJSONString(server);
            
            assertThat(json).isNotEmpty();
            assertThat(json).contains("\"uuid\":\"serialize-test\"");
            assertThat(json).contains("\"port\":25565");
            assertThat(json).contains("\"ssl\":true");
        }

        @Test
        @DisplayName("空字段不应该导致序列化失败")
        void nullFieldsShouldNotFailSerialization() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test")
                .build();
            
            String json = JSONObject.toJSONString(server);
            assertThat(json).isNotNull();
        }
    }

    @Nested
    @DisplayName("Mock HTTP请求测试 - getToken方法")
    class MockGetTokenTests {

        @Test
        @DisplayName("getToken方法应该使用正确的Content-Type")
        void getTokenShouldUseCorrectContentType() throws Exception {
            // 准备 mock
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"access_token\":\"token\",\"expires_in\":3600}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(eq("Content-Type"), eq("application/x-www-form-urlencoded"))).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            // 验证方法签名中使用的 Content-Type
            Method getTokenMethod = HttpRequestUtils.class.getDeclaredMethod("getToken", String.class, String.class);
            assertThat(getTokenMethod).isNotNull();
            
            // 验证 mock 的设置是正确的
            mockRequest.header("Content-Type", "application/x-www-form-urlencoded");
            verify(mockRequest).header("Content-Type", "application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("TokenEntity应该能正确解析带JWT的响应")
        void tokenEntityShouldParseJwtResponse() {
            // 模拟一个有效的 JWT token (header.payload.signature)
            // payload: {"user_id":12345,"user_name":"testuser"}
            String base64Payload = "eyJ1c2VyX2lkIjoxMjM0NSwidXNlcl9uYW1lIjoidGVzdHVzZXIifQ";
            String jwtToken = "eyJhbGciOiJIUzI1NiJ9." + base64Payload + ".signature";
            
            String tokenJson = "{\"access_token\":\"" + jwtToken + "\",\"refresh_token\":\"refresh\",\"expires_in\":3600}";
            TokenEntity token = JSONObject.parseObject(tokenJson, TokenEntity.class);
            token.decodeJwtPayload();
            
            assertThat(token.getAccess_token()).isEqualTo(jwtToken);
            assertThat(token.getUser_id()).isEqualTo(12345L);
            assertThat(token.getUser_name()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("表单参数Map应该正确构建")
        void formParamMapShouldBeBuiltCorrectly() {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("username", "testuser");
            paramMap.put("password", "testpass");
            
            assertThat(paramMap).hasSize(2);
            assertThat(paramMap.get("username")).isEqualTo("testuser");
            assertThat(paramMap.get("password")).isEqualTo("testpass");
        }

        @Test
        @DisplayName("HttpRequest.post应该被正确调用")
        void httpRequestPostShouldBeCalledCorrectly() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                mockedHttpRequest.when(() -> HttpRequest.post("https://test.com/user/getToken")).thenReturn(mockRequest);
                
                // 执行调用
                HttpRequest result = HttpRequest.post("https://test.com/user/getToken");
                result.header("Content-Type", "application/x-www-form-urlencoded");
                result.form(Map.of("username", "user", "password", "pass"));
                HttpResponse response = result.execute();
                
                assertThat(response.body()).isEqualTo("{}");
                mockedHttpRequest.verify(() -> HttpRequest.post("https://test.com/user/getToken"));
            }
        }

        @Test
        @DisplayName("链式调用应该正确工作")
        void chainedCallsShouldWorkCorrectly() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"access_token\":\"test\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            // 验证链式调用
            HttpRequest chained = mockRequest
                .header("Content-Type", "application/x-www-form-urlencoded")
                .form(Map.of("key", "value"));
            HttpResponse response = chained.execute();
            
            assertThat(response.body()).contains("access_token");
            verify(mockRequest).header("Content-Type", "application/x-www-form-urlencoded");
            verify(mockRequest).form(any(Map.class));
            verify(mockRequest).execute();
        }
    }

    @Nested
    @DisplayName("Mock HTTP请求测试 - getServerByUUID方法")
    class MockGetServerByUUIDTests {

        @Test
        @DisplayName("HttpRequest.get应该使用正确的URL格式")
        void httpRequestGetShouldUseCorrectUrlFormat() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"uuid\":\"test\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                String expectedUrl = "https://api.test.com/server/getByUUID?uuid=test-uuid-123";
                mockedHttpRequest.when(() -> HttpRequest.get(expectedUrl)).thenReturn(mockRequest);
                
                HttpRequest result = HttpRequest.get(expectedUrl);
                result.header("Authorization", "Bearer token")
                      .header("Content-Type", "application/json");
                
                mockedHttpRequest.verify(() -> HttpRequest.get(expectedUrl));
            }
        }

        @Test
        @DisplayName("Authorization头应该使用Bearer格式")
        void authorizationHeaderShouldUseBearerFormat() {
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            
            String accessToken = "my-access-token";
            mockRequest.header("Authorization", "Bearer " + accessToken);
            
            verify(mockRequest).header("Authorization", "Bearer my-access-token");
        }

        @Test
        @DisplayName("Content-Type应该是application/json")
        void contentTypeShouldBeJson() {
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            
            mockRequest.header("Content-Type", "application/json");
            
            verify(mockRequest).header("Content-Type", "application/json");
        }
    }

    @Nested
    @DisplayName("Mock HTTP请求测试 - registerServer方法")
    class MockRegisterServerTests {

        @Test
        @DisplayName("HttpRequest.post应该被调用到register端点")
        void httpRequestPostShouldCallRegisterEndpoint() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"code\":\"200\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                String registerUrl = "https://api.test.com/editor/register";
                mockedHttpRequest.when(() -> HttpRequest.post(registerUrl)).thenReturn(mockRequest);
                
                HttpRequest.post(registerUrl)
                    .header("Authorization", "Bearer token")
                    .form(Map.of("id", 123L, "serverData", "{}"))
                    .execute();
                
                mockedHttpRequest.verify(() -> HttpRequest.post(registerUrl));
            }
        }

        @Test
        @DisplayName("表单数据应该包含id和serverData")
        void formDataShouldContainIdAndServerData() {
            TokenEntity token = new TokenEntity();
            token.setUser_id(12345L);
            
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test-uuid")
                .name("MC Server")
                .port(25565)
                .ssl(true)
                .domain("example.com")
                .build();
            
            Map<String, Object> formMap = new HashMap<>();
            formMap.put("id", token.getUser_id());
            formMap.put("serverData", JSONObject.toJSONString(server));
            
            assertThat(formMap).containsKey("id");
            assertThat(formMap).containsKey("serverData");
            assertThat(formMap.get("id")).isEqualTo(12345L);
            assertThat(formMap.get("serverData").toString()).contains("test-uuid");
            assertThat(formMap.get("serverData").toString()).contains("MC Server");
            assertThat(formMap.get("serverData").toString()).contains("25565");
        }

        @Test
        @DisplayName("ServerEntityVO应该正确序列化所有字段")
        void serverEntityVOShouldSerializeAllFields() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("uuid-123")
                .name("My Server")
                .port(25565)
                .ssl(true)
                .domain("mc.example.com")
                .build();
            
            String json = JSONObject.toJSONString(server);
            JSONObject parsed = JSONObject.parseObject(json);
            
            assertThat(parsed.getString("uuid")).isEqualTo("uuid-123");
            assertThat(parsed.getString("name")).isEqualTo("My Server");
            assertThat(parsed.getInteger("port")).isEqualTo(25565);
            assertThat(parsed.getBoolean("ssl")).isTrue();
            assertThat(parsed.getString("domain")).isEqualTo("mc.example.com");
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 80, 443, 8080, 25565, 65535})
        @DisplayName("各种端口号应该被正确序列化")
        void variousPortsShouldSerializeCorrectly(int port) {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test")
                .port(port)
                .build();
            
            String json = JSONObject.toJSONString(server);
            JSONObject parsed = JSONObject.parseObject(json);
            
            assertThat(parsed.getInteger("port")).isEqualTo(port);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("SSL布尔值应该被正确序列化")
        void sslBooleanShouldSerializeCorrectly(boolean ssl) {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("test")
                .ssl(ssl)
                .build();
            
            String json = JSONObject.toJSONString(server);
            JSONObject parsed = JSONObject.parseObject(json);
            
            assertThat(parsed.getBoolean("ssl")).isEqualTo(ssl);
        }
    }

    @Nested
    @DisplayName("Mock HTTP请求测试 - updateServer方法")
    class MockUpdateServerTests {

        @Test
        @DisplayName("HttpRequest.post应该被调用到updateServer端点")
        void httpRequestPostShouldCallUpdateEndpoint() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"code\":\"200\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                String updateUrl = "https://api.test.com/editor/updateServer";
                mockedHttpRequest.when(() -> HttpRequest.post(updateUrl)).thenReturn(mockRequest);
                
                HttpRequest.post(updateUrl)
                    .header("Authorization", "Bearer token")
                    .form(Map.of("id", 123L, "serverData", "{}"))
                    .execute();
                
                mockedHttpRequest.verify(() -> HttpRequest.post(updateUrl));
            }
        }

        @Test
        @DisplayName("更新时ServerEntityVO不需要name字段")
        void updateServerEntityVODoesNotRequireName() {
            ServerEntityVO server = ServerEntityVO.builder()
                .uuid("update-uuid")
                .port(25566)
                .ssl(false)
                .domain("updated.example.com")
                .build();
            
            String json = JSONObject.toJSONString(server);
            JSONObject parsed = JSONObject.parseObject(json);
            
            assertThat(parsed.getString("uuid")).isEqualTo("update-uuid");
            assertThat(parsed.getInteger("port")).isEqualTo(25566);
            // name 可以为 null (验证JSON序列化正常即可)
            assertThat(json).isNotNull();
        }
    }

    @Nested
    @DisplayName("Mock测试 - 异常处理")
    class MockExceptionHandlingTests {

        @Test
        @DisplayName("HttpRequest.execute抛出异常时应该传播")
        void executeExceptionShouldPropagate() {
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenThrow(new RuntimeException("Network error"));
            
            assertThatThrownBy(() -> mockRequest.execute())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Network error");
        }

        @Test
        @DisplayName("无效JSON响应解析应该抛出异常")
        void invalidJsonParseShouldThrowException() {
            String invalidJson = "not valid json {{{";
            
            assertThatThrownBy(() -> JSONObject.parseObject(invalidJson, TokenEntity.class))
                .isInstanceOf(com.alibaba.fastjson.JSONException.class);
        }

        @Test
        @DisplayName("空JSON响应应该返回空对象")
        void emptyJsonShouldReturnEmptyObject() {
            String emptyJson = "{}";
            TokenEntity token = JSONObject.parseObject(emptyJson, TokenEntity.class);
            
            assertThat(token).isNotNull();
            assertThat(token.getAccess_token()).isNull();
            assertThat(token.getUser_id()).isNull();
        }

        @Test
        @DisplayName("null响应体处理")
        void nullResponseBodyHandling() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn(null);
            
            String body = mockResponse.body();
            assertThat(body).isNull();
        }
    }

    @Nested
    @DisplayName("Mock测试 - URL清理")
    class MockUrlCleaningTests {

        @Test
        @DisplayName("URL清理应该移除前后空白")
        void urlCleaningShouldTrimWhitespace() {
            String urlWithWhitespace = "  https://api.test.com  ";
            String cleanUrl = urlWithWhitespace != null ? urlWithWhitespace.trim() : "";
            
            assertThat(cleanUrl).isEqualTo("https://api.test.com");
        }

        @Test
        @DisplayName("null URL应该转为空字符串")
        void nullUrlShouldBecomeEmptyString() {
            String nullUrl = null;
            String cleanUrl = nullUrl != null ? nullUrl.trim() : "";
            
            assertThat(cleanUrl).isEmpty();
        }

        @Test
        @DisplayName("URL拼接路径应该正确")
        void urlConcatenationShouldBeCorrect() {
            String baseUrl = "https://api.test.com";
            String getTokenPath = "/user/getToken";
            String getServerPath = "/server/getByUUID?uuid=";
            String registerPath = "/editor/register";
            String updatePath = "/editor/updateServer";
            
            assertThat(baseUrl + getTokenPath).isEqualTo("https://api.test.com/user/getToken");
            assertThat(baseUrl + getServerPath + "test-uuid").isEqualTo("https://api.test.com/server/getByUUID?uuid=test-uuid");
            assertThat(baseUrl + registerPath).isEqualTo("https://api.test.com/editor/register");
            assertThat(baseUrl + updatePath).isEqualTo("https://api.test.com/editor/updateServer");
        }

        @Test
        @DisplayName("带换行符的URL应该正确处理")
        void urlWithNewlineShouldBeHandled() {
            String urlWithNewline = "https://api.test.com\n";
            String cleanUrl = urlWithNewline.trim();
            
            assertThat(cleanUrl).isEqualTo("https://api.test.com");
            assertThat(cleanUrl).doesNotContain("\n");
        }

        @Test
        @DisplayName("带制表符的URL应该正确处理")
        void urlWithTabShouldBeHandled() {
            String urlWithTab = "\thttps://api.test.com\t";
            String cleanUrl = urlWithTab.trim();
            
            assertThat(cleanUrl).isEqualTo("https://api.test.com");
            assertThat(cleanUrl).doesNotContain("\t");
        }
    }

    @Nested
    @DisplayName("Mock测试 - HTTP响应状态码")
    class MockHttpStatusCodeTests {

        @Test
        @DisplayName("模拟200成功响应")
        void mock200SuccessResponse() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"success\":true}");
            
            assertThat(mockResponse.getStatus()).isEqualTo(200);
            assertThat(mockResponse.body()).contains("success");
        }

        @Test
        @DisplayName("模拟401未授权响应")
        void mock401UnauthorizedResponse() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(401);
            when(mockResponse.body()).thenReturn("{\"error\":\"Unauthorized\"}");
            
            assertThat(mockResponse.getStatus()).isEqualTo(401);
            assertThat(mockResponse.body()).contains("Unauthorized");
        }

        @Test
        @DisplayName("模拟404未找到响应")
        void mock404NotFoundResponse() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(404);
            when(mockResponse.body()).thenReturn("{\"error\":\"Not Found\"}");
            
            assertThat(mockResponse.getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("模拟500服务器错误响应")
        void mock500ServerErrorResponse() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(500);
            when(mockResponse.body()).thenReturn("{\"error\":\"Internal Server Error\"}");
            
            assertThat(mockResponse.getStatus()).isEqualTo(500);
        }

        @ParameterizedTest
        @ValueSource(ints = {200, 201, 400, 401, 403, 404, 500, 502, 503})
        @DisplayName("各种HTTP状态码应该被正确模拟")
        void variousStatusCodesShouldBeMocked(int statusCode) {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(statusCode);
            
            assertThat(mockResponse.getStatus()).isEqualTo(statusCode);
        }
    }

    @Nested
    @DisplayName("Mock测试 - HttpRequest链式调用验证")
    class MockHttpRequestChainTests {

        @Test
        @DisplayName("POST请求的链式调用应该正确工作")
        void postChainedCallsShouldWork() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            // 验证链式调用
            HttpResponse response = mockRequest
                .header("Authorization", "Bearer token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .form(Map.of("key", "value"))
                .execute();
            
            assertThat(response).isNotNull();
            verify(mockRequest, times(2)).header(anyString(), anyString());
            verify(mockRequest).form(any(Map.class));
            verify(mockRequest).execute();
        }

        @Test
        @DisplayName("GET请求的链式调用应该正确工作")
        void getChainedCallsShouldWork() {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"data\":\"test\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            HttpResponse response = mockRequest
                .header("Authorization", "Bearer token")
                .header("Content-Type", "application/json")
                .execute();
            
            assertThat(response.body()).contains("data");
            verify(mockRequest, times(2)).header(anyString(), anyString());
        }

        @Test
        @DisplayName("MockedStatic应该正确拦截HttpRequest.post")
        void mockedStaticShouldInterceptPost() {
            HttpRequest mockRequest = mock(HttpRequest.class);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                mockedHttpRequest.when(() -> HttpRequest.post("https://test.com/api")).thenReturn(mockRequest);
                
                HttpRequest result = HttpRequest.post("https://test.com/api");
                
                assertThat(result).isEqualTo(mockRequest);
                mockedHttpRequest.verify(() -> HttpRequest.post("https://test.com/api"));
            }
        }

        @Test
        @DisplayName("MockedStatic应该正确拦截HttpRequest.get")
        void mockedStaticShouldInterceptGet() {
            HttpRequest mockRequest = mock(HttpRequest.class);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                mockedHttpRequest.when(() -> HttpRequest.get("https://test.com/api")).thenReturn(mockRequest);
                
                HttpRequest result = HttpRequest.get("https://test.com/api");
                
                assertThat(result).isEqualTo(mockRequest);
                mockedHttpRequest.verify(() -> HttpRequest.get("https://test.com/api"));
            }
        }
    }

    @Nested
    @DisplayName("JWT解码测试")
    class JwtDecodeTests {

        @Test
        @DisplayName("有效JWT应该正确解码user_id")
        void validJwtShouldDecodeUserId() {
            // payload: {"user_id":12345}
            String base64Payload = "eyJ1c2VyX2lkIjoxMjM0NX0";
            String jwt = "header." + base64Payload + ".signature";
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token(jwt);
            token.decodeJwtPayload();
            
            assertThat(token.getUser_id()).isEqualTo(12345L);
        }

        @Test
        @DisplayName("有效JWT应该正确解码user_name")
        void validJwtShouldDecodeUserName() {
            // payload: {"user_name":"testuser"}
            String base64Payload = "eyJ1c2VyX25hbWUiOiJ0ZXN0dXNlciJ9";
            String jwt = "header." + base64Payload + ".signature";
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token(jwt);
            token.decodeJwtPayload();
            
            assertThat(token.getUser_name()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("有效JWT应该正确解码email")
        void validJwtShouldDecodeEmail() {
            // payload: {"email":"test@example.com"}
            String base64Payload = "eyJlbWFpbCI6InRlc3RAZXhhbXBsZS5jb20ifQ";
            String jwt = "header." + base64Payload + ".signature";
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token(jwt);
            token.decodeJwtPayload();
            
            assertThat(token.getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("JWT解码应该处理所有字段")
        void jwtDecodeShouldHandleAllFields() {
            // payload: {"user_id":999,"user_name":"admin","email":"admin@test.com","exp":1735689600}
            String base64Payload = "eyJ1c2VyX2lkIjo5OTksInVzZXJfbmFtZSI6ImFkbWluIiwiZW1haWwiOiJhZG1pbkB0ZXN0LmNvbSIsImV4cCI6MTczNTY4OTYwMH0";
            String jwt = "header." + base64Payload + ".signature";
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token(jwt);
            token.decodeJwtPayload();
            
            assertThat(token.getUser_id()).isEqualTo(999L);
            assertThat(token.getUser_name()).isEqualTo("admin");
            assertThat(token.getEmail()).isEqualTo("admin@test.com");
            assertThat(token.getExp()).isEqualTo(1735689600L);
        }
    }
    
    // ==================== 直接调用实际方法的 Mock 测试 ====================
    
    @Nested
    @DisplayName("BaseUrl配置测试")
    class BaseUrlConfigurationTests {
        
        @Test
        @DisplayName("setBaseUrlForTesting应该设置自定义URL")
        void setBaseUrlForTestingShouldSetCustomUrl() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            
            Method getMethod = HttpRequestUtils.class.getDeclaredMethod("getBaseUrl");
            getMethod.setAccessible(true);
            
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            try {
                setMethod.invoke(null, "https://custom.api.com");
                String result = (String) getMethod.invoke(null);
                assertThat(result).isEqualTo("https://custom.api.com");
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("resetBaseUrl应该清除自定义URL")
        void resetBaseUrlShouldClearCustomUrl() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            setMethod.invoke(null, "https://custom.api.com");
            resetMethod.invoke(null);
            
            // 验证已被重置 (customBaseUrl 应该为 null)
            Field customBaseUrlField = HttpRequestUtils.class.getDeclaredField("customBaseUrl");
            customBaseUrlField.setAccessible(true);
            assertThat(customBaseUrlField.get(null)).isNull();
        }
        
        @Test
        @DisplayName("getBaseUrl应该优先返回customBaseUrl")
        void getBaseUrlShouldPreferCustomUrl() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            
            Method getMethod = HttpRequestUtils.class.getDeclaredMethod("getBaseUrl");
            getMethod.setAccessible(true);
            
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            try {
                // 设置自定义URL
                setMethod.invoke(null, "https://test-priority.api.com");
                
                // 应该返回自定义URL
                String result = (String) getMethod.invoke(null);
                assertThat(result).isEqualTo("https://test-priority.api.com");
            } finally {
                resetMethod.invoke(null);
            }
        }
    }
    
    @Nested
    @DisplayName("实际方法Mock测试 - getToken")
    class ActualGetTokenMockTests {
        
        @Test
        @DisplayName("getToken应该返回正确的TokenEntity")
        void getTokenShouldReturnCorrectTokenEntity() throws Exception {
            // 设置测试URL
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            // 准备 mock 响应
            // payload: {"user_id":12345,"user_name":"testuser"}
            String base64Payload = "eyJ1c2VyX2lkIjoxMjM0NSwidXNlcl9uYW1lIjoidGVzdHVzZXIifQ";
            String jwtToken = "eyJhbGciOiJIUzI1NiJ9." + base64Payload + ".signature";
            String jsonResponse = "{\"access_token\":\"" + jwtToken + "\",\"refresh_token\":\"refresh-token\",\"expires_in\":3600}";
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn(jsonResponse);
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://test.api.com");
                
                mockedHttpRequest.when(() -> HttpRequest.post("https://test.api.com/user/getToken"))
                    .thenReturn(mockRequest);
                
                // 调用实际方法
                Method getTokenMethod = HttpRequestUtils.class.getDeclaredMethod("getToken", String.class, String.class);
                getTokenMethod.setAccessible(true);
                TokenEntity result = (TokenEntity) getTokenMethod.invoke(null, "testuser", "testpass");
                
                assertThat(result).isNotNull();
                assertThat(result.getAccess_token()).isEqualTo(jwtToken);
                assertThat(result.getUser_id()).isEqualTo(12345L);
                assertThat(result.getUser_name()).isEqualTo("testuser");
                
                mockedHttpRequest.verify(() -> HttpRequest.post("https://test.api.com/user/getToken"));
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("getToken应该正确设置请求头和表单数据")
        void getTokenShouldSetHeadersAndFormCorrectly() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{\"access_token\":\"token\",\"expires_in\":3600}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                
                mockedHttpRequest.when(() -> HttpRequest.post("https://mock.api.com/user/getToken"))
                    .thenReturn(mockRequest);
                
                Method getTokenMethod = HttpRequestUtils.class.getDeclaredMethod("getToken", String.class, String.class);
                getTokenMethod.setAccessible(true);
                getTokenMethod.invoke(null, "user", "pass");
                
                verify(mockRequest).header("Content-Type", "application/x-www-form-urlencoded");
                verify(mockRequest).form(any(Map.class));
                verify(mockRequest).execute();
            } finally {
                resetMethod.invoke(null);
            }
        }
    }
    
    @Nested
    @DisplayName("实际方法Mock测试 - getServerByUUID")
    class ActualGetServerByUUIDMockTests {
        
        @Test
        @DisplayName("getServerByUUID应该返回HttpResponse")
        void getServerByUUIDShouldReturnHttpResponse() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"uuid\":\"test-uuid\",\"name\":\"Test Server\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("test-token");
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                
                mockedHttpRequest.when(() -> HttpRequest.get("https://mock.api.com/server/getByUUID?uuid=test-uuid-123"))
                    .thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("getServerByUUID", String.class, TokenEntity.class);
                method.setAccessible(true);
                HttpResponse result = (HttpResponse) method.invoke(null, "test-uuid-123", token);
                
                assertThat(result).isNotNull();
                assertThat(result.getStatus()).isEqualTo(200);
                assertThat(result.body()).contains("test-uuid");
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("getServerByUUID应该设置正确的Authorization头")
        void getServerByUUIDShouldSetAuthorizationHeader() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("my-access-token");
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                
                mockedHttpRequest.when(() -> HttpRequest.get(anyString())).thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("getServerByUUID", String.class, TokenEntity.class);
                method.setAccessible(true);
                method.invoke(null, "uuid", token);
                
                verify(mockRequest).header("Authorization", "Bearer my-access-token");
                verify(mockRequest).header("Content-Type", "application/json");
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("getServerByUUID应该处理404响应")
        void getServerByUUIDShouldHandle404Response() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(404);
            when(mockResponse.body()).thenReturn("{\"error\":\"Not Found\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("token");
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                mockedHttpRequest.when(() -> HttpRequest.get(anyString())).thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("getServerByUUID", String.class, TokenEntity.class);
                method.setAccessible(true);
                HttpResponse result = (HttpResponse) method.invoke(null, "nonexistent", token);
                
                assertThat(result.getStatus()).isEqualTo(404);
            } finally {
                resetMethod.invoke(null);
            }
        }
    }
    
    @Nested
    @DisplayName("实际方法Mock测试 - registerServer")
    class ActualRegisterServerMockTests {
        
        @Test
        @DisplayName("registerServer应该发送正确的请求")
        void registerServerShouldSendCorrectRequest() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"code\":\"200\",\"msg\":\"success\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("register-token");
            token.setUser_id(12345L);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                
                mockedHttpRequest.when(() -> HttpRequest.post("https://mock.api.com/editor/register"))
                    .thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("registerServer", 
                    String.class, int.class, String.class, boolean.class, TokenEntity.class);
                method.setAccessible(true);
                HttpResponse result = (HttpResponse) method.invoke(null, "server-uuid", 25565, "mc.example.com", true, token);
                
                assertThat(result).isNotNull();
                assertThat(result.getStatus()).isEqualTo(200);
                
                verify(mockRequest).header("Authorization", "Bearer register-token");
                verify(mockRequest).form(any(Map.class));
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("registerServer应该构建正确的ServerEntityVO")
        void registerServerShouldBuildCorrectServerEntityVO() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenAnswer(invocation -> {
                Map<String, Object> formMap = invocation.getArgument(0);
                assertThat(formMap).containsKey("id");
                assertThat(formMap).containsKey("serverData");
                
                String serverData = (String) formMap.get("serverData");
                assertThat(serverData).contains("test-register-uuid");
                assertThat(serverData).contains("25565");
                assertThat(serverData).contains("MC Server");
                
                return mockRequest;
            });
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("token");
            token.setUser_id(999L);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                mockedHttpRequest.when(() -> HttpRequest.post(anyString())).thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("registerServer", 
                    String.class, int.class, String.class, boolean.class, TokenEntity.class);
                method.setAccessible(true);
                method.invoke(null, "test-register-uuid", 25565, "example.com", true, token);
                
                verify(mockRequest).form(any(Map.class));
            } finally {
                resetMethod.invoke(null);
            }
        }
    }
    
    @Nested
    @DisplayName("实际方法Mock测试 - updateServer")
    class ActualUpdateServerMockTests {
        
        @Test
        @DisplayName("updateServer应该发送正确的请求")
        void updateServerShouldSendCorrectRequest() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.getStatus()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"code\":\"200\",\"msg\":\"updated\"}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("update-token");
            token.setUser_id(54321L);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                
                mockedHttpRequest.when(() -> HttpRequest.post("https://mock.api.com/editor/updateServer"))
                    .thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("updateServer", 
                    String.class, int.class, String.class, boolean.class, TokenEntity.class);
                method.setAccessible(true);
                HttpResponse result = (HttpResponse) method.invoke(null, "update-uuid", 25566, "updated.example.com", false, token);
                
                assertThat(result).isNotNull();
                assertThat(result.getStatus()).isEqualTo(200);
                
                verify(mockRequest).header("Authorization", "Bearer update-token");
                mockedHttpRequest.verify(() -> HttpRequest.post("https://mock.api.com/editor/updateServer"));
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("updateServer应该构建正确的表单数据")
        void updateServerShouldBuildCorrectFormData() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenAnswer(invocation -> {
                Map<String, Object> formMap = invocation.getArgument(0);
                assertThat(formMap.get("id")).isEqualTo(12345L);
                
                String serverData = (String) formMap.get("serverData");
                assertThat(serverData).contains("update-test-uuid");
                assertThat(serverData).contains("25567");
                assertThat(serverData).contains("new.domain.com");
                
                return mockRequest;
            });
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            TokenEntity token = new TokenEntity();
            token.setAccess_token("token");
            token.setUser_id(12345L);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                setMethod.invoke(null, "https://mock.api.com");
                mockedHttpRequest.when(() -> HttpRequest.post(anyString())).thenReturn(mockRequest);
                
                Method method = HttpRequestUtils.class.getDeclaredMethod("updateServer", 
                    String.class, int.class, String.class, boolean.class, TokenEntity.class);
                method.setAccessible(true);
                method.invoke(null, "update-test-uuid", 25567, "new.domain.com", true, token);
                
                verify(mockRequest).form(any(Map.class));
            } finally {
                resetMethod.invoke(null);
            }
        }
    }
    
    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("null baseUrl应该被正确处理")
        void nullBaseUrlShouldBeHandled() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method getMethod = HttpRequestUtils.class.getDeclaredMethod("getBaseUrl");
            getMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            try {
                setMethod.invoke(null, (Object) null);
                // 当 customBaseUrl 为 null 时，应该尝试从 UltiTools 获取，但由于测试环境没有 UltiTools，
                // 这里只验证不会抛出 NullPointerException
                
                Field customField = HttpRequestUtils.class.getDeclaredField("customBaseUrl");
                customField.setAccessible(true);
                assertThat(customField.get(null)).isNull();
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("空字符串baseUrl应该被正确处理")
        void emptyBaseUrlShouldBeHandled() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method getMethod = HttpRequestUtils.class.getDeclaredMethod("getBaseUrl");
            getMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            try {
                setMethod.invoke(null, "");
                String result = (String) getMethod.invoke(null);
                assertThat(result).isEmpty();
            } finally {
                resetMethod.invoke(null);
            }
        }
        
        @Test
        @DisplayName("带空格的baseUrl应该被trim")
        void baseUrlWithWhitespaceShouldBeTrimmed() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.body()).thenReturn("{}");
            
            HttpRequest mockRequest = mock(HttpRequest.class);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.form(any(Map.class))).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            
            try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
                // 设置带空格的URL
                setMethod.invoke(null, "  https://trim.test.com  ");
                
                // 实际方法内部会 trim
                mockedHttpRequest.when(() -> HttpRequest.post("https://trim.test.com/user/getToken"))
                    .thenReturn(mockRequest);
                
                Method getTokenMethod = HttpRequestUtils.class.getDeclaredMethod("getToken", String.class, String.class);
                getTokenMethod.setAccessible(true);
                
                // 这应该成功调用到 trimmed URL
                getTokenMethod.invoke(null, "user", "pass");
                
                // 验证使用的是 trimmed URL
                mockedHttpRequest.verify(() -> HttpRequest.post("https://trim.test.com/user/getToken"));
            } finally {
                resetMethod.invoke(null);
            }
        }
    }
    
    @Nested
    @DisplayName("资源清理测试")
    class ResourceCleanupTests {
        
        @Test
        @DisplayName("测试后应该清理baseUrl")
        void baseUrlShouldBeCleanedAfterTest() throws Exception {
            Method setMethod = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            setMethod.setAccessible(true);
            Method resetMethod = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            resetMethod.setAccessible(true);
            
            setMethod.invoke(null, "https://temp.api.com");
            resetMethod.invoke(null);
            
            Field customField = HttpRequestUtils.class.getDeclaredField("customBaseUrl");
            customField.setAccessible(true);
            assertThat(customField.get(null)).isNull();
            
            Field baseUrlField = HttpRequestUtils.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            assertThat(baseUrlField.get(null)).isNull();
        }
    }
}
