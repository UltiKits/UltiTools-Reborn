package com.ultikits.ultitools.utils;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.entities.vo.ServerEntityVO;

import java.util.HashMap;
import java.util.Map;

public class HttpRequestUtils {
    private static final String BASE_URL = UltiTools.getEnv().getString("api-url");

    protected static TokenEntity getToken(String username, String password) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("username", username);
        paramMap.put("password", password);
        String tokenJson = HttpUtil.post(BASE_URL + "/user/getToken", paramMap);
        return JSONObject.parseObject(tokenJson, TokenEntity.class);
    }

    protected static HttpResponse getServerByUUID(String uuid, TokenEntity token) {
        return HttpRequest.get(BASE_URL + "/server/getByUUID?uuid=" + uuid)
                .bearerAuth(token.getAccess_token())
                .execute();
    }

    protected static HttpResponse registerServer(String uuid, int port, String domain, boolean ssl, TokenEntity token) {
        ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid(uuid)
                .name("MC Server")
                .port(port)
                .ssl(ssl)
                .domain(domain)
                .build();
        return HttpRequest.post(BASE_URL + "/editor/register?id=" + token.getId())
                .bearerAuth(token.getAccess_token())
                .body(serverEntityVO.toString())
                .execute();
    }

    protected static HttpResponse updateServer(String uuid, int port, String domain, boolean ssl, TokenEntity token) {
        ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid(uuid)
                .port(port)
                .ssl(ssl)
                .domain(domain)
                .build();
        return HttpRequest.post(BASE_URL + "/editor/updateServer?id=" + token.getId())
                .bearerAuth(token.getAccess_token())
                .body(serverEntityVO.toString())
                .execute();
    }

}
