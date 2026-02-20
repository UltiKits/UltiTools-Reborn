package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ServerPropertiesManagerTest {

    private File tempDir;
    private File propsFile;
    private ServerPropertiesManager manager;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = new File(System.getProperty("java.io.tmpdir"), "spm-test-" + System.currentTimeMillis());
        tempDir.mkdirs();
        propsFile = new File(tempDir, "server.properties");
        // Write a sample server.properties
        try (PrintWriter pw = new PrintWriter(new FileWriter(propsFile))) {
            pw.println("motd=A Minecraft Server");
            pw.println("max-players=20");
            pw.println("view-distance=10");
            pw.println("pvp=true");
            pw.println("difficulty=normal");
            pw.println("gamemode=survival");
            pw.println("allow-nether=true");
            pw.println("rcon.password=secret123");
            pw.println("server-port=25565");
        }
        manager = new ServerPropertiesManager(tempDir);
    }

    @AfterEach
    void tearDown() {
        propsFile.delete();
        tempDir.delete();
    }

    @Test
    @DisplayName("getSafeProperties should return only safe keys")
    void shouldReturnOnlySafeKeys() {
        Map<String, String> props = manager.getSafeProperties();
        assertThat(props).containsKey("motd");
        assertThat(props).containsKey("max-players");
        assertThat(props).containsKey("pvp");
        // Should NOT contain sensitive keys
        assertThat(props).doesNotContainKey("rcon.password");
        assertThat(props).doesNotContainKey("server-port");
    }

    @Test
    @DisplayName("setProperty should update safe property")
    void shouldUpdateSafeProperty() throws IOException {
        boolean result = manager.setProperty("motd", "New MOTD");
        assertThat(result).isTrue();

        Map<String, String> props = manager.getSafeProperties();
        assertThat(props.get("motd")).isEqualTo("New MOTD");
    }

    @Test
    @DisplayName("setProperty should reject unsafe property")
    void shouldRejectUnsafeProperty() {
        boolean result = manager.setProperty("rcon.password", "hacked");
        assertThat(result).isFalse();
    }
}
