package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #531 gate-1 CR-01, end to end: a binding to a {@code Long} {@code @ConfigEntry} field -- which
 * the binding documents as supported -- through real YAML, a real {@link ConfigManager}, a second
 * boot from the file the first boot wrote, and the real {@code final reloadSelf()} after the
 * operator edits the file. Before the config layer widened YAML numbers, the second boot threw
 * {@code IllegalArgumentException} from {@code Field.set(Long, Integer)}.
 */
@DisplayName("config-bound Long field survives a YAML round trip, a second boot and /ul reload (#531 CR-01)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // wires a real TaskManager and sets resourceFolderPath
class ConfigBoundLongRoundTripTest {

    private static final String PATH = "config/interest.yml";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private JavaPlugin host;
    private FixturePlugin module;
    private TaskManager taskManager;
    private PluginManager pluginManager;

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @ConfigEntity(PATH)
    public static class InterestConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "interest.interval", comment = "seconds")
        private Long interval = 5L;

        @ConfigEntry(path = "interest.cooldown", comment = "seconds")
        private Long cooldown = 60L;

        public InterestConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    public static class InterestService {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = InterestConfig.class, periodKey = "interest.interval", delayKey = "interest.interval")
        public void pay() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"fw531interest"})
    public static class InterestCommand extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "claim")
        @CmdCD(config = InterestConfig.class, key = "interest.cooldown")
        public void claim(Player player) {
            // Test stub - not exercised
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        host = MockBukkit.createMockPlugin();

        module = mock(FixturePlugin.class);
        lenient().when(module.getPluginName()).thenReturn("InterestModule");
        lenient().when(module.getLogger()).thenReturn(mock(PluginLogger.class));
        lenient().when(module.getMinUltiToolsVersion()).thenReturn(630);
        lenient().when(module.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(module, tempDir.toFile());
        Field resourceFolderPath = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPath.setAccessible(true);
        resourceFolderPath.set(module, tempDir.toString());
        doCallRealMethod().when(module).reloadSelf();

        taskManager = new TaskManager(host);
        pluginManager = new PluginManager();
        Field taskManagerField = PluginManager.class.getDeclaredField("taskManager");
        taskManagerField.setAccessible(true);
        taskManagerField.set(pluginManager, taskManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private ConfigManager boot() {
        ConfigManager configManager = new ConfigManager();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getConfigManager()).thenReturn(configManager);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        return configManager;
    }

    private int liveTasks() {
        int live = 0;
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (!task.isCancelled() && task.getOwner() == host) {
                live++;
            }
        }
        return live;
    }

    private void advanceTo(int tick) {
        server.getScheduler().performTicks(tick - Bukkit.getCurrentTick());
    }

    private static CooldownValidator cooldownValidatorOf(BaseCommandExecutor executor) {
        for (CommandValidator validator : executor.getValidatorChain().getValidators()) {
            if (validator instanceof CooldownValidator) {
                return (CooldownValidator) validator;
            }
        }
        throw new AssertionError("the default chain carries a CooldownValidator");
    }

    @Test
    @DisplayName("first boot writes the default, a second boot loads it, and /ul reload applies an edit")
    void boundLongFieldSurvivesTheRoundTrip() throws IOException, NoSuchMethodException {
        ConfigManager firstBoot = boot();
        firstBoot.register(module, new InterestConfig(PATH));
        Path file = tempDir.resolve(PATH);
        assertEquals(true, Files.exists(file), "the first boot writes the defaults");

        ConfigManager secondBoot = boot();
        assertDoesNotThrow(() -> secondBoot.register(module, new InterestConfig(PATH)),
                "a second boot must load interest.interval: 5 into the Long field");

        InterestService service = new InterestService();
        InterestCommand command = new InterestCommand();
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("interestService", service);
        container.registerSingleton("interestCommand", command);
        lenient().when(module.getContext()).thenReturn(container);
        PluginManager.validateConfigBindings(module, container);
        taskManager.registerScheduledMethods(module, service);
        String cooldownKey = CooldownValidator.bindingKey(
                InterestCommand.class.getMethod("claim", Player.class).getAnnotation(CmdCD.class));
        assertEquals(Integer.valueOf(60), cooldownValidatorOf(command).getBoundCooldownSeconds().get(cooldownKey));

        advanceTo(150);
        assertEquals(Arrays.asList(100), service.fireTicks, "delay = period = 5 s");

        String yaml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("interval: 5", "interval: 10").replace("cooldown: 60", "cooldown: 30");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(module::reloadSelf, "/ul reload must load the edited whole numbers into the Long fields");

        assertEquals(1, liveTasks(), "one live task after the reload");
        assertEquals(Integer.valueOf(30), cooldownValidatorOf(command).getBoundCooldownSeconds().get(cooldownKey));
        advanceTo(300);
        assertEquals(Arrays.asList(100, 300), service.fireTicks, "last run 100 + 10 s");
    }
}
