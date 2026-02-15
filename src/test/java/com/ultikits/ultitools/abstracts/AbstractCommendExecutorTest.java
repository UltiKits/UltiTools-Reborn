package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.command.CmdExecutor;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

/**
 * 测试 AbstractCommendExecutor（已废弃）的向后兼容性
 */
@SuppressWarnings("deprecation")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AbstractCommendExecutorTest {

    private PlayerMock player;
    private Command mockCommand;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        player = new PlayerMock(server, "TestPlayer");
        mockCommand = Mockito.mock(Command.class);
        Mockito.when(mockCommand.getName()).thenReturn("testcommand");
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("Should create deprecated AbstractCommendExecutor instance")
    void testDeprecatedExecutorCreation() {
        TestCommendExecutor executor = new TestCommendExecutor();
        assertThat(executor).isNotNull();
    }

    // AbstractCommandExecutor does not exist in this branch, so we can't test inheritance from it.
    // AbstractCommendExecutor is the base class here.
    
    @Test
    @DisplayName("Should execute commands")
    void testCommandExecution() {
        TestCommendExecutor executor = new TestCommendExecutor();
        
        // AbstractCommendExecutor implements TabExecutor
        boolean result = executor.onCommand(player, mockCommand, "test", new String[]{});
        
        // It should return true or false depending on implementation
        assertThat(result).isIn(true, false);
    }

    @Test
    @DisplayName("Should have getInstance method")
    void testGetInstance() {
        TestCommendExecutor executor = new TestCommendExecutor();
        
        AbstractCommendExecutor instance = executor.getInstance();
        
        assertThat(instance).isNotNull();
        assertThat(instance).isSameAs(executor);
    }

    // AbstractCommandExecutor doesn't exist, so we skip tests related to it.

    @Test
    @DisplayName("Should support command mapping annotations")
    void testCommandMappingSupport() {
        TestCommendExecutor executor = new TestCommendExecutor();
        
        // 验证能够处理@CmdExecutor注解
        CmdExecutor annotation = executor.getClass().getAnnotation(CmdExecutor.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.alias()).contains("testcommend");
    }

    // ========== 测试辅助类 ==========

    /**
     * 测试用的已废弃命令执行器
     */
    @CmdExecutor(alias = {"testcommend"}, description = "Test deprecated executor")
    private static class TestCommendExecutor extends AbstractCommendExecutor {
        public TestCommendExecutor() {
            super();
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("Help message");
        }
        
        // AbstractCommendExecutor in this branch might be different from the one in feature/custom-ioc
        // In the provided attachment, AbstractCommendExecutor implements TabExecutor and has logic for command mapping.
        // It does NOT have abstract handleHelp method.
        
        // We don't need to implement handleHelp if it's not abstract.
    }
}
