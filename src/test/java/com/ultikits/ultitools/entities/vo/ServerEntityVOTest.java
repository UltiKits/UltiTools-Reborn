package com.ultikits.ultitools.entities.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerEntityVOTest {

    @Test
    void testBuilder() {
        ServerEntityVO vo = ServerEntityVO.builder()
                .name("TestServer")
                .uuid("1234-5678")
                .port(25565)
                .domain("example.com")
                .ssl(true)
                .build();

        assertEquals("TestServer", vo.getName());
        assertEquals("1234-5678", vo.getUuid());
        assertEquals(25565, vo.getPort());
        assertEquals("example.com", vo.getDomain());
        assertTrue(vo.isSsl());
    }

    @Test
    void testToString() {
        ServerEntityVO vo = ServerEntityVO.builder()
                .name("TestServer")
                .port(25565)
                .build();
        
        String json = vo.toString();
        assertTrue(json.contains("\"name\":\"TestServer\""));
        assertTrue(json.contains("\"port\":25565"));
    }
}
