package com.ultikits.plugins.cleaner.commands;

import com.ultikits.plugins.cleaner.UltiCleanerTestHelper;
import com.ultikits.plugins.cleaner.service.ChunkUnloadService;
import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.plugins.cleaner.service.TpsAwareScheduler;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("CleanCommand Tests")
class CleanCommandTest {

    private CleanerService cleanerService;
    private ChunkUnloadService chunkUnloadService;
    private TpsAwareScheduler tpsScheduler;
    private CleanCommand command;
    private Player player;
    private CommandSender sender;

    @BeforeEach
    void setUp() throws Exception {
        UltiCleanerTestHelper.setUp();
        cleanerService = mock(CleanerService.class);
        chunkUnloadService = mock(ChunkUnloadService.class);
        tpsScheduler = mock(TpsAwareScheduler.class);

        command = new CleanCommand(cleanerService, chunkUnloadService);

        player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
        sender = mock(CommandSender.class);

        when(cleanerService.getTpsScheduler()).thenReturn(tpsScheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiCleanerTestHelper.tearDown();
    }

    // ==================== cleanItems ====================

    @Nested
    @DisplayName("cleanItems")
    class CleanItems {

        @Test
        @DisplayName("Should clean items when not in progress")
        void cleanItems() {
            when(cleanerService.isCleaningInProgress()).thenReturn(false);
            when(cleanerService.forceCleanItems()).thenReturn(100);

            command.cleanItems(sender);

            verify(cleanerService).forceCleanItems();
            verify(sender).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show message when cleaning in progress")
        void cleaningInProgress() {
            when(cleanerService.isCleaningInProgress()).thenReturn(true);

            command.cleanItems(sender);

            verify(cleanerService, never()).forceCleanItems();
            verify(sender).sendMessage(anyString());
        }
    }

    // ==================== cleanEntities ====================

    @Nested
    @DisplayName("cleanEntities")
    class CleanEntities {

        @Test
        @DisplayName("Should clean entities when not in progress")
        void cleanEntities() {
            when(cleanerService.isCleaningInProgress()).thenReturn(false);
            when(cleanerService.forceCleanEntities()).thenReturn(50);

            command.cleanEntities(sender);

            verify(cleanerService).forceCleanEntities();
            verify(sender).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show message when cleaning in progress")
        void cleaningInProgress() {
            when(cleanerService.isCleaningInProgress()).thenReturn(true);

            command.cleanEntities(sender);

            verify(cleanerService, never()).forceCleanEntities();
            verify(sender).sendMessage(anyString());
        }
    }

    // ==================== cleanAll ====================

    @Nested
    @DisplayName("cleanAll")
    class CleanAll {

        @Test
        @DisplayName("Should clean both items and entities")
        void cleanAll() {
            when(cleanerService.isCleaningInProgress()).thenReturn(false);
            when(cleanerService.forceCleanItems()).thenReturn(100);
            when(cleanerService.forceCleanEntities()).thenReturn(50);

            command.cleanAll(sender);

            verify(cleanerService).forceCleanItems();
            verify(cleanerService).forceCleanEntities();
            verify(sender).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show message when cleaning in progress")
        void cleaningInProgress() {
            when(cleanerService.isCleaningInProgress()).thenReturn(true);

            command.cleanAll(sender);

            verify(cleanerService, never()).forceCleanItems();
            verify(cleanerService, never()).forceCleanEntities();
        }
    }

    // ==================== cleanChunks ====================

    @Nested
    @DisplayName("cleanChunks")
    class CleanChunks {

        @Test
        @DisplayName("Should clean chunks when service available")
        void cleanChunks() {
            when(chunkUnloadService.forceUnloadChunks()).thenReturn(10);

            command.cleanChunks(sender);

            verify(chunkUnloadService).forceUnloadChunks();
            verify(sender).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show error when service not available")
        void serviceNotAvailable() {
            CleanCommand commandWithoutChunks = new CleanCommand(cleanerService, null);

            commandWithoutChunks.cleanChunks(sender);

            verify(sender).sendMessage(anyString());
        }
    }

    // ==================== check ====================

    @Nested
    @DisplayName("check")
    class Check {

        @Test
        @DisplayName("Should display entity statistics")
        void displayStats() {
            Map<String, Integer> counts = new HashMap<>();
            counts.put("items", 100);
            counts.put("mobs", 50);
            counts.put("total", 200);

            when(cleanerService.getEntityCounts()).thenReturn(counts);
            when(chunkUnloadService.getTotalLoadedChunks()).thenReturn(500);
            when(chunkUnloadService.getUnloadableChunkCount()).thenReturn(50);
            when(tpsScheduler.getTpsStatus()).thenReturn("20.0 (Normal)");

            command.check(sender);

            verify(sender, atLeast(5)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should work without chunk service")
        void withoutChunkService() {
            CleanCommand commandWithoutChunks = new CleanCommand(cleanerService, null);
            Map<String, Integer> counts = new HashMap<>();
            counts.put("items", 100);
            counts.put("mobs", 50);
            counts.put("total", 200);

            when(cleanerService.getEntityCounts()).thenReturn(counts);
            when(tpsScheduler.getTpsStatus()).thenReturn("20.0 (Normal)");

            commandWithoutChunks.check(sender);

            verify(sender, atLeast(3)).sendMessage(anyString());
        }
    }

    // ==================== status ====================

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("Should display status when idle")
        void displayStatusIdle() {
            when(cleanerService.getItemCountdown()).thenReturn(300);
            when(cleanerService.getEntityCountdown()).thenReturn(600);
            when(cleanerService.isCleaningInProgress()).thenReturn(false);
            when(tpsScheduler.getTpsStatus()).thenReturn("20.0 (Normal)");
            when(tpsScheduler.isCriticalTps()).thenReturn(false);
            when(tpsScheduler.isLowTps()).thenReturn(false);

            command.status(sender);

            verify(sender, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should display status when cleaning in progress")
        void displayStatusCleaning() {
            when(cleanerService.getItemCountdown()).thenReturn(300);
            when(cleanerService.getEntityCountdown()).thenReturn(600);
            when(cleanerService.isCleaningInProgress()).thenReturn(true);
            when(tpsScheduler.getTpsStatus()).thenReturn("20.0 (Normal)");
            when(tpsScheduler.isCriticalTps()).thenReturn(false);
            when(tpsScheduler.isLowTps()).thenReturn(false);

            command.status(sender);

            verify(sender, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show low TPS warning")
        void lowTpsWarning() {
            when(cleanerService.getItemCountdown()).thenReturn(300);
            when(cleanerService.getEntityCountdown()).thenReturn(600);
            when(cleanerService.isCleaningInProgress()).thenReturn(false);
            when(tpsScheduler.getTpsStatus()).thenReturn("17.5 (Low)");
            when(tpsScheduler.isCriticalTps()).thenReturn(false);
            when(tpsScheduler.isLowTps()).thenReturn(true);

            command.status(sender);

            verify(sender, atLeast(4)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show critical TPS warning")
        void criticalTpsWarning() {
            when(cleanerService.getItemCountdown()).thenReturn(300);
            when(cleanerService.getEntityCountdown()).thenReturn(600);
            when(cleanerService.isCleaningInProgress()).thenReturn(false);
            when(tpsScheduler.getTpsStatus()).thenReturn("14.0 (Critical)");
            when(tpsScheduler.isCriticalTps()).thenReturn(true);
            when(tpsScheduler.isLowTps()).thenReturn(true);

            command.status(sender);

            verify(sender, atLeast(4)).sendMessage(anyString());
        }
    }

    // ==================== help ====================

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("Should display help message")
        void displayHelp() {
            command.help(sender);

            verify(sender, atLeast(6)).sendMessage(anyString());
        }
    }

    // ==================== handleHelp ====================

    @Nested
    @DisplayName("handleHelp")
    class HandleHelp {

        @Test
        @DisplayName("Should call help method")
        void callsHelp() {
            command.handleHelp(sender);

            verify(sender, atLeast(6)).sendMessage(anyString());
        }
    }
}
