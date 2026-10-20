package com.ultikits.ultitools.entities;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import lombok.Data;

@Data
public class TokenEntity {
    // OAuth2 response fields
    private String access_token;
    private String refresh_token;
    private String token_type;
    private int expires_in;
    private String scope;
    private String jti;

    // JWT payload fields
    private Long user_id;      // User ID (numeric)
    private String user_name;  // Username
    private String email;      // Email
    private String[] authorities; // Authorities array
    private Long exp;          // Expiration timestamp
    private Long iat;          // Issued-at timestamp
    private String client_id;  // Client ID

    /**
     * Decode the JWT payload from access_token and populate the fields.
     */
    public void decodeJwtPayload() {
        if (access_token == null || access_token.isEmpty()) {
            return;
        }
        
        try {
            // JWT format: header.payload.signature
            String[] parts = access_token.split("\\.");
            if (parts.length != 3) {
                return;
            }

            // Decode the payload part (Base64)
            String payload = parts[1];
            // Add the necessary padding
            while (payload.length() % 4 != 0) {
                payload += "=";
            }

            byte[] decodedBytes = Base64.getDecoder().decode(payload);
            String decodedPayload = new String(decodedBytes, StandardCharsets.UTF_8);

            // Parse the JSON payload
            JsonObject payloadJson = JsonParser.parseString(decodedPayload).getAsJsonObject();

            // Populate the fields
            if (payloadJson.has("user_id")) {
                this.user_id = payloadJson.get("user_id").getAsLong();
            }
            if (payloadJson.has("user_name")) {
                this.user_name = payloadJson.get("user_name").getAsString();
            }
            if (payloadJson.has("email")) {
                this.email = payloadJson.get("email").getAsString();
            }
            if (payloadJson.has("authorities")) {
                JsonArray arr = payloadJson.getAsJsonArray("authorities");
                this.authorities = new String[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    this.authorities[i] = arr.get(i).getAsString();
                }
            }
            if (payloadJson.has("exp")) {
                this.exp = payloadJson.get("exp").getAsLong();
            }
            if (payloadJson.has("iat")) {
                this.iat = payloadJson.get("iat").getAsLong();
            }
            if (payloadJson.has("client_id")) {
                this.client_id = payloadJson.get("client_id").getAsString();
            }
            if (payloadJson.has("scope")) {
                this.scope = payloadJson.get("scope").getAsString();
            }
            
        } catch (Exception e) {
            // If decoding fails, log the error but do not throw
            java.util.logging.Logger.getLogger(TokenEntity.class.getName())
                    .log(java.util.logging.Level.WARNING, "Failed to decode JWT payload", e);
        }
    }
    
    /**
     * Get the decoded payload information (for debugging).
     * @return a string containing the decoded information
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
     * Check whether the token has expired.
     * @return true if expired, false if still valid
     */
    public boolean isExpired() {
        if (exp == null) {
            return false;
        }
        return System.currentTimeMillis() / 1000 > exp;
    }
    
    /**
     * Get the token's expiration time.
     * @return the expiration time as a Date, or null if not set
     */
    public Date getExpirationDate() {
        if (exp == null) {
            return null;
        }
        return new Date(exp * 1000);
    }
    
    /**
     * Get the token's issued-at time.
     * @return the issued-at time as a Date, or null if not set
     */
    public Date getIssuedAt() {
        if (iat == null) {
            return null;
        }
        return new Date(iat * 1000);
    }
    
    /**
     * Check whether the user has the given authority.
     * @param authority the authority to check
     * @return true if the user has that authority, false otherwise
     */
    public boolean hasAuthority(String authority) {
        if (authorities == null || authority == null) {
            return false;
        }
        return Arrays.asList(authorities).contains(authority);
    }
    
    /**
     * Get the user ID as a string (for compatibility).
     * @return the user ID as a string, or null if it is null
     */
    public String getUserIdAsString() {
        return user_id != null ? user_id.toString() : null;
    }
}
