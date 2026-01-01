package com.ultikits.ultitools.entities;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

import com.alibaba.fastjson.JSONObject;

import lombok.Data;

@Data
public class TokenEntity {
    // OAuth2 响应字段
    private String access_token;
    private String refresh_token;
    private String token_type;
    private int expires_in;
    private String scope;
    private String jti;
    
    // JWT payload 字段
    private Long user_id;      // 用户ID (数字类型)
    private String user_name;  // 用户名
    private String email;      // 邮箱
    private String[] authorities; // 权限数组
    private Long exp;          // 过期时间戳
    private Long iat;          // 签发时间戳
    private String client_id;  // 客户端ID
    
    /**
     * 从access_token中解码JWT payload并填充字段
     */
    public void decodeJwtPayload() {
        if (access_token == null || access_token.isEmpty()) {
            return;
        }
        
        try {
            // JWT格式: header.payload.signature
            String[] parts = access_token.split("\\.");
            if (parts.length != 3) {
                return;
            }
            
            // 解码payload部分 (Base64)
            String payload = parts[1];
            // 添加必要的padding
            while (payload.length() % 4 != 0) {
                payload += "=";
            }
            
            byte[] decodedBytes = Base64.getDecoder().decode(payload);
            String decodedPayload = new String(decodedBytes, StandardCharsets.UTF_8);
            
            // 解析JSON payload
            JSONObject payloadJson = JSONObject.parseObject(decodedPayload);
            
            // 填充字段
            if (payloadJson.containsKey("user_id")) {
                this.user_id = payloadJson.getLong("user_id");
            }
            if (payloadJson.containsKey("user_name")) {
                this.user_name = payloadJson.getString("user_name");
            }
            if (payloadJson.containsKey("email")) {
                this.email = payloadJson.getString("email");
            }
            if (payloadJson.containsKey("authorities")) {
                this.authorities = payloadJson.getObject("authorities", String[].class);
            }
            if (payloadJson.containsKey("exp")) {
                this.exp = payloadJson.getLong("exp");
            }
            if (payloadJson.containsKey("iat")) {
                this.iat = payloadJson.getLong("iat");
            }
            if (payloadJson.containsKey("client_id")) {
                this.client_id = payloadJson.getString("client_id");
            }
            if (payloadJson.containsKey("scope")) {
                this.scope = payloadJson.getString("scope");
            }
            
        } catch (Exception e) {
            // 如果解码失败，记录错误但不抛出异常
            java.util.logging.Logger.getLogger(TokenEntity.class.getName())
                    .log(java.util.logging.Level.WARNING, "Failed to decode JWT payload", e);
        }
    }
    
    /**
     * 获取解码后的payload信息（用于调试）
     * @return 包含解码信息的字符串
     */
    public String getDecodedInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("TokenEntity{\n");
        sb.append("  user_id=").append(user_id).append(",\n");
        sb.append("  user_name='").append(user_name).append("',\n");
        sb.append("  email='").append(email).append("',\n");
        sb.append("  authorities=").append(Arrays.toString(authorities)).append(",\n");
        sb.append("  exp=").append(exp).append(",\n");
        sb.append("  iat=").append(iat).append(",\n");
        sb.append("  client_id='").append(client_id).append("',\n");
        sb.append("  scope='").append(scope).append("'\n");
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * 检查token是否已过期
     * @return true如果已过期，false如果仍有效
     */
    public boolean isExpired() {
        if (exp == null) {
            return false;
        }
        return System.currentTimeMillis() / 1000 > exp;
    }
    
    /**
     * 获取token过期时间
     * @return 过期时间的Date对象，如果没有设置则返回null
     */
    public Date getExpirationDate() {
        if (exp == null) {
            return null;
        }
        return new Date(exp * 1000);
    }
    
    /**
     * 获取token签发时间
     * @return 签发时间的Date对象，如果没有设置则返回null
     */
    public Date getIssuedAt() {
        if (iat == null) {
            return null;
        }
        return new Date(iat * 1000);
    }
    
    /**
     * 检查用户是否具有指定权限
     * @param authority 要检查的权限
     * @return true如果用户具有该权限，false否则
     */
    public boolean hasAuthority(String authority) {
        if (authorities == null || authority == null) {
            return false;
        }
        return Arrays.asList(authorities).contains(authority);
    }
    
    /**
     * 获取用户ID的字符串形式（为了兼容性）
     * @return 用户ID的字符串形式，如果为null则返回null
     */
    public String getUserIdAsString() {
        return user_id != null ? user_id.toString() : null;
    }
}
