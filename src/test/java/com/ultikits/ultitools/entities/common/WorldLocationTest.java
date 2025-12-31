package com.ultikits.ultitools.entities.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class WorldLocationTest {

    @Test
    void testConstructorAndGetters() {
        WorldLocation loc = new WorldLocation("world", 10.0, 20.0, 30.0, 90.0f, 45.0f);

        assertEquals("world", loc.getWorld());
        assertEquals(10.0, loc.getX());
        assertEquals(20.0, loc.getY());
        assertEquals(30.0, loc.getZ());
        assertEquals(90.0f, loc.getYaw());
        assertEquals(45.0f, loc.getPitch());
    }

    @Test
    void testLocationConstructor() {
        World mockWorld = mock(World.class);
        when(mockWorld.getName()).thenReturn("world_nether");
        Location location = new Location(mockWorld, 100.0, 64.0, -100.0, 180.0f, 0.0f);

        WorldLocation loc = new WorldLocation(location);

        assertEquals("world_nether", loc.getWorld());
        assertEquals(100.0, loc.getX());
        assertEquals(64.0, loc.getY());
        assertEquals(-100.0, loc.getZ());
        assertEquals(180.0f, loc.getYaw());
        assertEquals(0.0f, loc.getPitch());
    }

    @Test
    void testToLocation() {
        World mockWorld = mock(World.class);
        when(mockWorld.getName()).thenReturn("world_the_end");

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(mockWorld);

            WorldLocation loc = new WorldLocation("world_the_end", 0.5, 70.0, 0.5, 0.0f, 90.0f);
            Location location = loc.toLocation();

            assertNotNull(location);
            assertEquals(mockWorld, location.getWorld());
            assertEquals(0.5, location.getX());
            assertEquals(70.0, location.getY());
            assertEquals(0.5, location.getZ());
            assertEquals(0.0f, location.getYaw());
            assertEquals(90.0f, location.getPitch());
        }
    }

    @Test
    void testToString() {
        WorldLocation loc = new WorldLocation("world", 10.0, 20.0, 30.0, 90.0f, 45.0f);
        
        String json = loc.toString();
        // {"world":"world","x":10.0,"y":20.0,"z":30.0,"yaw":90.0,"pitch":45.0}
        String expected = "{\"world\":\"world\",\"x\":10.0,\"y\":20.0,\"z\":30.0,\"yaw\":90.0,\"pitch\":45.0}";
        assertEquals(expected, json);
    }
    
    @Test
    void testNoArgsConstructor() {
        WorldLocation loc = new WorldLocation();
        loc.setWorld("world_nether");
        loc.setX(1.0);
        loc.setY(2.0);
        loc.setZ(3.0);
        loc.setYaw(4.0f);
        loc.setPitch(5.0f);

        assertEquals("world_nether", loc.getWorld());
        assertEquals(1.0, loc.getX());
        assertEquals(2.0, loc.getY());
        assertEquals(3.0, loc.getZ());
        assertEquals(4.0f, loc.getYaw());
        assertEquals(5.0f, loc.getPitch());
    }

    @Test
    void testEqualsAndHashCode() {
        WorldLocation loc1 = new WorldLocation("world", 1.0, 2.0, 3.0, 0f, 0f);
        WorldLocation loc2 = new WorldLocation("world", 1.0, 2.0, 3.0, 0f, 0f);
        WorldLocation loc3 = new WorldLocation("world", 1.0, 2.0, 4.0, 0f, 0f);

        assertEquals(loc1, loc2);
        assertEquals(loc1.hashCode(), loc2.hashCode());
        assertNotEquals(loc1, loc3);
    }
}
