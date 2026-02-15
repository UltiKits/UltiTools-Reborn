package com.ultikits.ultitools.utils;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.entities.vo.ServerEntityVO;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;

/**
 * Utility class for HTTP request operations.
 * Provides methods for communicating with the UltiPanel API server.
 * This class handles authentication, server registration, and server updates.
 * <br>
 * HTTP请求操作的实用工具类。
 * 提供与UltiPanel API服务器通信的方法。
 * 此类处理身份验证、服务器注册和服务器更新。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class HttpRequestUtils {
    
    /**
     * Base URL for the API server (lazy initialized).
     * <br>
     * API服务器的基础URL（延迟初始化）。
     */
    private static volatile String baseUrl;
    
    /**
     * Custom base URL for testing purposes.
     * <br>
     * 用于测试的自定义基础URL。
     */
    private static String customBaseUrl;
    
    /**
     * Get the base URL for API calls.
     * Uses lazy initialization to avoid requiring UltiTools at class loading time.
     * <br>
     * 获取API调用的基础URL。
     * 使用延迟初始化以避免在类加载时需要UltiTools。
     *
     * @return the base URL
     */
    static String getBaseUrl() {
        if (customBaseUrl != null) {
            return customBaseUrl;
        }
        if (baseUrl == null) {
            synchronized (HttpRequestUtils.class) {
                if (baseUrl == null) {
                    baseUrl = UltiTools.getEnv().getString("api-url");
                }
            }
        }
        return baseUrl;
    }
    
    /**
     * Set a custom base URL for testing purposes.
     * This should only be used in unit tests.
     * <br>
     * 设置自定义的基础URL，仅用于测试目的。
     *
     * @param url the custom base URL, or null to reset to default
     */
    static void setBaseUrlForTesting(String url) {
        customBaseUrl = url;
    }
    
    /**
     * Reset the base URL to force re-initialization.
     * This should only be used in unit tests.
     * <br>
     * 重置基础URL以强制重新初始化，仅用于测试目的。
     */
    static void resetBaseUrl() {
        baseUrl = null;
        customBaseUrl = null;
    }

    /**
     * Get server information by UUID from the API server.
     * <br>
     * 通过UUID从API服务器获取服务器信息。
     *
     * @param uuid  the unique identifier of the server <br> 服务器的唯一标识符
     * @param token the authentication token <br> 身份验证令牌
     * @return HttpResponse containing the server information <br> 包含服务器信息的HttpResponse
     */
    protected static Response getServerByUUID(String uuid, TokenEntity token) {
        String cleanBaseUrl = getBaseUrl() != null ? getBaseUrl().trim() : "";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.getAccess_token());
        headers.put("Content-Type", "application/json");
        return SimpleHttpClient.get(cleanBaseUrl + "/server/getByUUID?uuid=" + uuid, headers);
    }

    /**
     * Register a new server with the API server.
     * <br>
     * 向API服务器注册新服务器。
     *
     * @param uuid   the unique identifier for the server <br> 服务器的唯一标识符
     * @param name   the display name for the server <br> 服务器的显示名称
     * @param port   the port number of the server <br> 服务器的端口号
     * @param domain the domain name of the server <br> 服务器的域名
     * @param ssl    whether SSL is enabled <br> 是否启用SSL
     * @param token  the authentication token <br> 身份验证令牌
     * @return HttpResponse containing the registration result <br> 包含注册结果的HttpResponse
     */
    protected static Response registerServer(String uuid, String name, int port, String domain, boolean ssl, TokenEntity token) {
        if (!ApiRateLimiter.isAllowed("registerServer", 60_000)) {
            throw new RuntimeException("Rate limited: please wait before retrying server registration");
        }
        String cleanBaseUrl = getBaseUrl() != null ? getBaseUrl().trim() : "";
        ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid(uuid)
                .name(name)
                .port(port)
                .ssl(ssl)
                .domain(domain)
                .build();
        
        // 使用 FormData 格式发送请求
        Map<String, Object> formMap = new HashMap<>();

        formMap.put("id", token.getUserIdAsString());  // 使用token中的用户ID（字符串形式避免浮点问题）
        formMap.put("serverData", new Gson().toJson(serverEntityVO));  // 使用JSON序列化确保格式正确
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.getAccess_token());
        
        return SimpleHttpClient.post(cleanBaseUrl + "/editor/register", headers, formMap);
    }

    /**
     * Update an existing server on the API server.
     * <br>
     * 在API服务器上更新现有服务器。
     *
     * @param uuid   the unique identifier of the server <br> 服务器的唯一标识符
     * @param port   the new port number <br> 新的端口号
     * @param domain the new domain name <br> 新的域名
     * @param ssl    whether SSL is enabled <br> 是否启用SSL
     * @param token  the authentication token <br> 身份验证令牌
     * @return HttpResponse containing the update result <br> 包含更新结果的HttpResponse
     */
    protected static Response updateServer(String uuid, int port, String domain, boolean ssl, TokenEntity token) {
        if (!ApiRateLimiter.isAllowed("updateServer", 60_000)) {
            throw new RuntimeException("Rate limited: please wait before retrying server update");
        }
        String cleanBaseUrl = getBaseUrl() != null ? getBaseUrl().trim() : "";
        ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid(uuid)
                .port(port)
                .ssl(ssl)
                .domain(domain)
                .build();
        
        // 使用 FormData 格式发送请求
        Map<String, Object> formMap = new HashMap<>();
        formMap.put("id", token.getUserIdAsString());  // 使用token中的用户ID（字符串形式避免浮点问题）
        formMap.put("serverData", new Gson().toJson(serverEntityVO));  // 使用JSON序列化确保格式正确
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.getAccess_token());
        
        return SimpleHttpClient.post(cleanBaseUrl + "/editor/updateServer", headers, formMap);
    }

}
