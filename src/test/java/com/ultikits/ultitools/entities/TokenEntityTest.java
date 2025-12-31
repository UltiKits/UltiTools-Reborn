package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import org.junit.jupiter.api.Test;

class TokenEntityTest {

    @Test
    void testDecodeJwtPayload() {
        TokenEntity token = new TokenEntity();
        
        // Construct a fake JWT
        String header = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        String payloadJson = "{" +
                "\"user_id\": 123," +
                "\"user_name\": \"testuser\"," +
                "\"email\": \"test@example.com\"," +
                "\"authorities\": [\"ROLE_USER\"]," +
                "\"exp\": 1600000000," +
                "\"iat\": 1500000000," +
                "\"client_id\": \"client1\"," +
                "\"scope\": \"read\"" +
                "}";
        String payload = Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = "signature";
        
        String jwt = header + "." + payload + "." + signature;
        token.setAccess_token(jwt);
        
        token.decodeJwtPayload();
        
        assertEquals(123L, token.getUser_id());
        assertEquals("testuser", token.getUser_name());
        assertEquals("test@example.com", token.getEmail());
        assertArrayEquals(new String[]{"ROLE_USER"}, token.getAuthorities());
        assertEquals(1600000000L, token.getExp());
        assertEquals(1500000000L, token.getIat());
        assertEquals("client1", token.getClient_id());
        assertEquals("read", token.getScope());
    }

    @Test
    void testDecodeInvalidJwt() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token("invalid.token");
        token.decodeJwtPayload();
        
        assertNull(token.getUser_id());
    }

    @Test
    void testDecodeNullOrEmptyToken() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token(null);
        token.decodeJwtPayload();
        assertNull(token.getUser_id());

        token.setAccess_token("");
        token.decodeJwtPayload();
        assertNull(token.getUser_id());
    }

    @Test
    void testDecodeInvalidBase64() {
        TokenEntity token = new TokenEntity();
        String header = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        String payload = "invalid-base64";
        String signature = "signature";
        String jwt = header + "." + payload + "." + signature;
        
        token.setAccess_token(jwt);
        token.decodeJwtPayload();
        
        assertNull(token.getUser_id());
    }
    
    @Test
    void testGetDecodedInfo() {
        TokenEntity token = new TokenEntity();
        token.setUser_id(1L);
        token.setUser_name("test");
        token.setEmail("test@test.com");
        token.setAuthorities(new String[]{"ADMIN"});
        token.setExp(1000L);
        token.setIat(900L);
        token.setClient_id("client");
        token.setScope("all");
        
        String info = token.getDecodedInfo();
        assertTrue(info.contains("user_id=1"));
        assertTrue(info.contains("user_name='test'"));
        assertTrue(info.contains("email='test@test.com'"));
        assertTrue(info.contains("authorities=[ADMIN]"));
        assertTrue(info.contains("exp=1000"));
        assertTrue(info.contains("iat=900"));
        assertTrue(info.contains("client_id='client'"));
        assertTrue(info.contains("scope='all'"));
    }

    @Test
    void testIsExpired() {
        TokenEntity token = new TokenEntity();
        
        // Case 1: No exp set
        assertFalse(token.isExpired());
        
        // Case 2: Expired
        token.setExp(System.currentTimeMillis() / 1000 - 3600); // 1 hour ago
        assertTrue(token.isExpired());
        
        // Case 3: Not expired
        token.setExp(System.currentTimeMillis() / 1000 + 3600); // 1 hour later
        assertFalse(token.isExpired());
    }

    @Test
    void testGetExpirationDate() {
        TokenEntity token = new TokenEntity();
        assertNull(token.getExpirationDate());
        
        long exp = 1600000000L;
        token.setExp(exp);
        assertEquals(new Date(exp * 1000), token.getExpirationDate());
    }

    @Test
    void testGetIssuedAt() {
        TokenEntity token = new TokenEntity();
        assertNull(token.getIssuedAt());
        
        long iat = 1500000000L;
        token.setIat(iat);
        assertEquals(new Date(iat * 1000), token.getIssuedAt());
    }

    @Test
    void testHasAuthority() {
        TokenEntity token = new TokenEntity();
        
        // Case 1: No authorities
        assertFalse(token.hasAuthority("ADMIN"));
        
        // Case 2: Has authority
        token.setAuthorities(new String[]{"USER", "ADMIN"});
        assertTrue(token.hasAuthority("ADMIN"));
        assertTrue(token.hasAuthority("USER"));
        
        // Case 3: Does not have authority
        assertFalse(token.hasAuthority("SUPER_ADMIN"));
        
        // Case 4: Null authority check
        assertFalse(token.hasAuthority(null));
    }

    @Test
    void testGetUserIdAsString() {
        TokenEntity token = new TokenEntity();
        assertNull(token.getUserIdAsString());
        
        token.setUser_id(12345L);
        assertEquals("12345", token.getUserIdAsString());
    }
}
