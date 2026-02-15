package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

class PluginEntityTest {

    @Test
    void testGettersAndSetters() {
        PluginEntity entity = new PluginEntity();
        long id = 1L;
        int developerId = 100;
        String name = "TestPlugin";
        Date date = new Date();
        Date modifiedTime = new Date();
        String identifyString = "test-plugin";
        String description = "A test plugin";
        String shortDescription = "Test";
        String icon = "icon.png";

        entity.setId(id);
        entity.setDeveloperId(developerId);
        entity.setName(name);
        entity.setDate(date);
        entity.setModifiedTime(modifiedTime);
        entity.setIdentifyString(identifyString);
        entity.setDescription(description);
        entity.setShortDescription(shortDescription);
        entity.setIcon(icon);

        assertEquals(id, entity.getId());
        assertEquals(developerId, entity.getDeveloperId());
        assertEquals(name, entity.getName());
        assertEquals(date, entity.getDate());
        assertEquals(modifiedTime, entity.getModifiedTime());
        assertEquals(identifyString, entity.getIdentifyString());
        assertEquals(description, entity.getDescription());
        assertEquals(shortDescription, entity.getShortDescription());
        assertEquals(icon, entity.getIcon());
    }

    @Test
    void testToString() {
        PluginEntity entity = new PluginEntity();
        entity.setId(1L);
        entity.setName("TestPlugin");
        
        String json = entity.toString();
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"name\":\"TestPlugin\""));
    }
}
