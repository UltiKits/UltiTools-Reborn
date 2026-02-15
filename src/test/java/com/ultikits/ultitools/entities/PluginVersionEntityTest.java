package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PluginVersionEntityTest {

    @Test
    void testGettersAndSetters() {
        PluginVersionEntity entity = new PluginVersionEntity();
        entity.setPluginId(1L);
        entity.setVersion("1.0.0");
        entity.setDownloadLink("http://example.com");

        assertEquals(1L, entity.getPluginId());
        assertEquals("1.0.0", entity.getVersion());
        assertEquals("http://example.com", entity.getDownloadLink());
    }

    @Test
    void testToString() {
        PluginVersionEntity entity = new PluginVersionEntity();
        entity.setPluginId(1L);
        entity.setVersion("1.0.0");
        
        String json = entity.toString();
        assertTrue(json.contains("\"pluginId\":1"));
        assertTrue(json.contains("\"version\":\"1.0.0\""));
    }
}
