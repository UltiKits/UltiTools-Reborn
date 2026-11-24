package com.ultikits.ultitools.abstracts.command;

import static org.mockito.ArgumentMatchers.argThat;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Unit tests for BaseCommandExecutor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseCommandExecutor Tests")
@ExtendWith(CommandTestExtension.class)
class BaseCommandExecutorTest {

    @Mock
    private Player mockPlayer;
    
    @Mock
    private CommandSender mockSender;
    
    @Mock
    private Command mockCommand;
    
    @Mock
    private UltiTools mockUltiTools;

    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockCommand.getName()).thenReturn("test");
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        
        @Test
        @DisplayName("Should create executor with default constructor")
        void shouldCreateWithDefaultConstructor() {
            TestCommandExecutor executor = new TestCommandExecutor();
            assertNotNull(executor);
            assertNotNull(executor.getValidatorChain());
            assertNotNull(executor.getCooldownValidator());
            assertNotNull(executor.getLockValidator());
        }
        
        @Test
        @DisplayName("Should create executor with custom validator chain")
        void shouldCreateWithCustomValidatorChain() {
            ValidatorChain customChain = ValidatorChain.builder().build();
            CustomChainExecutor executor = new CustomChainExecutor(customChain);
            assertEquals(customChain, executor.getValidatorChain());
        }
    }

    @Nested
    @DisplayName("Command Mapping Tests")
    class MappingTests {
        
        @Test
        @DisplayName("Should scan command mappings")
        void shouldScanMappings() {
            TestCommandExecutor executor = new TestCommandExecutor();
            Map<String, Method> mappings = executor.getMappings();
            assertFalse(mappings.isEmpty());
            assertTrue(mappings.containsKey("action <param>"));
        }
        

        

    }



    @Nested
    @DisplayName("Help Command Tests")
    class HelpTests {
        
        @Test
        @DisplayName("Should handle help command")
        void shouldHandleHelpCommand() {
            TestCommandExecutor executor = new TestCommandExecutor();
            boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "test", new String[]{"help"});
            assertTrue(result);
            assertTrue(executor.helpCalled);
        }
        
        @Test
        @DisplayName("Should use custom help command string")
        void shouldUseCustomHelpCommand() {
            TestCommandExecutor executor = new TestCommandExecutor();
            assertEquals("help", executor.getHelpCommand());
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {
        
        @Test
        @DisplayName("Should add custom validator")
        void shouldAddCustomValidator() {
            TestCommandExecutor executor = new TestCommandExecutor();
            CommandValidator customValidator = context -> CommandValidator.ValidationResult.success();
            
            executor.addValidator(customValidator);
            // Validator was added without exception
            assertNotNull(executor.getValidatorChain());
        }
        
        @Test
        @DisplayName("Should remove validator")
        void shouldRemoveValidator() {
            TestCommandExecutor executor = new TestCommandExecutor();
            CooldownValidator cooldownValidator = executor.getCooldownValidator();
            
            executor.removeValidator(cooldownValidator);
            // Validator was removed without exception
            assertNotNull(executor.getValidatorChain());
        }
    }

    @Nested
    @DisplayName("Tab Completion Tests")
    class TabCompletionTests {
        
        @Test
        @DisplayName("Should return suggestions for first argument")
        void shouldReturnSuggestionsForFirstArg() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockPlayer, mockCommand, "test", new String[]{"a"});
            
            assertNotNull(suggestions);
            assertTrue(suggestions.contains("action"));
        }
        
        @Test
        @DisplayName("Should return mapped literals for console sender")
        void shouldReturnMappedLiteralsForConsoleSender() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockSender, mockCommand, "test", new String[]{"a"});
            
            assertTrue(suggestions.contains("action"));
        }
        
        @Test
        @DisplayName("Should return no suggestions for console-only command with player")
        void shouldReturnNoSuggestionsForConsoleOnlyCommand() {
            ConsoleOnlyExecutor executor = new ConsoleOnlyExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockPlayer, mockCommand, "consoleonly", new String[]{"r"});
            
            assertTrue(suggestions.isEmpty());
        }
        
        @Test
        @DisplayName("Should return empty suggestions for non-matching prefix")
        void shouldReturnEmptyForNonMatchingPrefix() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockPlayer, mockCommand, "test", new String[]{"xyz"});
            
            assertNotNull(suggestions);
            assertFalse(suggestions.contains("action"));
        }
        
        @Test
        @DisplayName("Should return multiple suggestions")
        void shouldReturnMultipleSuggestions() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            // With empty prefix, should match action and multi
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockPlayer, mockCommand, "test", new String[]{""});
            
            assertNotNull(suggestions);
        }
    }


    
    @Nested
    @DisplayName("onCommand Tests")
    class OnCommandTests {
        
        @Test
        @DisplayName("Should handle unknown command")
        void shouldHandleUnknownCommand() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "test", new String[]{"unknown", "cmd"});
            
            assertTrue(result);
            assertTrue(executor.helpCalled);
            verify(mockPlayer).sendMessage(argThat((Component component) -> CommandMessageTestSupport.plain(component).contains("未知指令")));
        }
        

        
        @Test
        @DisplayName("Should return true when validation fails with error message")
        void shouldReturnTrueWhenValidationFailsWithMessage() {
            // Create a validator that always fails with an error message
            CommandValidator failingValidator = new CommandValidator() {
                @Override
                public ValidationResult validate(CommandContext context) {
                    return ValidationResult.failure("Validation failed for testing");
                }
            };
            ValidatorChain failChain = ValidatorChain.builder().add(failingValidator).build();
            FailValidationExecutor executor = new FailValidationExecutor(failChain);
            
            boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "failvalidation", new String[]{"run"});
            
            assertTrue(result);
            // The validation failure should have sent the error message to the player
        }
        
        @Test
        @DisplayName("Should return true when validation fails with null message")
        void shouldReturnTrueWhenValidationFailsWithNullMessage() {
            // Create a validator that always fails with no message
            CommandValidator failingValidator = new CommandValidator() {
                @Override
                public ValidationResult validate(CommandContext context) {
                    return ValidationResult.failure(null);
                }
            };
            ValidatorChain failChain = ValidatorChain.builder().add(failingValidator).build();
            FailValidationExecutor executor = new FailValidationExecutor(failChain);
            
            boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "failvalidation", new String[]{"run"});
            
            assertTrue(result);
        }
    }
    

    
    @Nested
    @DisplayName("Build Method Params Tests")
    class BuildMethodParamsTests {
        
        @Test
        @DisplayName("Should build params with no parameters")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldBuildParamsWithNoParameters() throws Exception {
            TypedParamsExecutor executor = new TypedParamsExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = TypedParamsExecutor.class.getDeclaredMethod("doNoParam");
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .commandName(mockCommand.getName())
                    .alias("types")
                    .rawArgs(new String[]{"noparam"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(0, result.length);
        }
        
        @Test
        @DisplayName("Should inject sender with @CmdSender annotation")
        void shouldInjectSenderWithAnnotation() throws Exception {
            TypedParamsExecutor executor = new TypedParamsExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = TypedParamsExecutor.class.getDeclaredMethod("doSender", CommandSender.class);
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .commandName(mockCommand.getName())
                    .alias("types")
                    .rawArgs(new String[]{"sender"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(1, result.length);
            assertEquals(mockPlayer, result[0]);
        }
        
        @Test
        @DisplayName("Should inject player with @CmdSender annotation for Player type")
        void shouldInjectPlayerWithCmdSenderAnnotation() throws Exception {
            PlayerSenderExecutor executor = new PlayerSenderExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = PlayerSenderExecutor.class.getDeclaredMethod("doPlayerSender", Player.class);
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .commandName(mockCommand.getName())
                    .alias("playersender")
                    .rawArgs(new String[]{"playersender"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(1, result.length);
            // mockPlayer is Player type, so it should be injected
            assertEquals(mockPlayer, result[0]);
        }
        
        @Test
        @DisplayName("Should inject null for non-player sender with Player type")
        void shouldInjectNullForNonPlayerSender() throws Exception {
            PlayerSenderExecutor executor = new PlayerSenderExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = PlayerSenderExecutor.class.getDeclaredMethod("doPlayerSender", Player.class);
            CommandContext context = CommandContext.builder()
                    .sender(mockSender) // Not a Player
                    .commandName(mockCommand.getName())
                    .alias("playersender")
                    .rawArgs(new String[]{"playersender"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(1, result.length);
            assertNull(result[0]);
        }
        
        @Test
        @DisplayName("Should handle Player parameter without annotation")
        void shouldHandlePlayerParamWithoutAnnotation() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = TestCommandExecutor.class.getDeclaredMethod("doAction", Player.class, String.class);
            Map<String, Object> params = new HashMap<>();
            params.put("param", new String[]{"value"});
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .commandName(mockCommand.getName())
                    .alias("test")
                    .rawArgs(new String[]{"action", "value"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
        }
        
        @Test
        @DisplayName("Should handle CommandSender parameter without annotation")
        void shouldHandleCommandSenderParamWithoutAnnotation() throws Exception {
            SenderNoAnnotationExecutor executor = new SenderNoAnnotationExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = SenderNoAnnotationExecutor.class.getDeclaredMethod("doSenderNoAnnotation", CommandSender.class);
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .commandName(mockCommand.getName())
                    .alias("sendernoannot")
                    .rawArgs(new String[]{"sendernoannot"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(1, result.length);
            assertEquals(mockPlayer, result[0]);
        }
        
        @Test
        @DisplayName("Should handle parameter without CmdParam annotation")
        void shouldHandleParamWithoutCmdParamAnnotation() throws Exception {
            NoAnnotationParamExecutor executor = new NoAnnotationParamExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = NoAnnotationParamExecutor.class.getDeclaredMethod("doNoAnnotation", String.class);
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .commandName(mockCommand.getName())
                    .alias("noannotparam")
                    .rawArgs(new String[]{"noannotation"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(1, result.length);
            // Without @CmdParam, it should add null
            assertNull(result[0]);
        }
        

    }
    

    

    

    
    @Nested
    @DisplayName("Get Next Suggestions Tests")
    class GetNextSuggestionsTests {
        
        @Test
        @DisplayName("Should return literal suggestions for static parts")
        void shouldReturnLiteralSuggestions() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockPlayer, mockCommand, "test", new String[]{""});
            
            assertNotNull(suggestions);
            assertTrue(suggestions.contains("action") || suggestions.contains("multi"));
        }
    }
    

    
    @Nested
    @DisplayName("Suggest Method Tests")
    class SuggestMethodTests {
        
        @Test
        @DisplayName("Should return suggestions for first argument")
        void shouldReturnSuggestionsForFirstArg() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockPlayer, mockCommand, "test", new String[]{"a"});
            
            assertNotNull(suggestions);
        }
        
        @Test
        @DisplayName("Should return mapped literals for console sender")
        void shouldReturnMappedLiteralsForConsoleSender() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = com.ultikits.ultitools.command.engine.CommandTestSupport.suggest(executor, mockSender, mockCommand, "test", new String[]{"a"});
            
            assertTrue(suggestions.contains("action"));
        }
    }

    @Nested
    @DisplayName("Chain-Driven Post-Action Tests")
    class ChainDrivenPostActionTests {

        private UUID player1UUID;

        @BeforeEach
        void stubPlayerUuid() {
            player1UUID = UUID.randomUUID();
            lenient().when(mockPlayer.getUniqueId()).thenReturn(player1UUID);
        }

        /**
         * Stubs {@code Bukkit.getScheduler()} so {@code BukkitRunnable#runTask} invokes the
         * runnable synchronously instead of touching a real Bukkit scheduler, which does not
         * exist under plain Mockito. Mirrors the pattern already used in
         * {@code GuiSchedulerTest#testRunOnMainThread_WhenNotOnMainThread}.
         */
        private void stubSyncScheduler(MockedStatic<Bukkit> bukkit) {
            Scheduler scheduler = CommandRuntime.scheduler();

            lenient().when(scheduler.global().run(any(Runnable.class))).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(0);
                task.run();
                return null;
            });
        }

        @Test
        @DisplayName("SEAM: custom chain with CooldownValidator rejects the second dispatch within the cooldown window")
        void customChainWithCooldownValidatorRejectsSecondDispatch() {
            ValidatorChain chain = ValidatorChain.builder().add(new CooldownValidator()).build();
            CooldownSeamExecutor executor = new CooldownSeamExecutor(chain);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubSyncScheduler(bukkit);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "seam", new String[]{"ping"});
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "seam", new String[]{"ping"});
            }

            assertEquals(1, executor.pingCount,
                    "the second dispatch inside the cooldown window must be rejected -- the mapped method must not run");
        }

        @Test
        @DisplayName("Negative half: custom chain WITHOUT CooldownValidator permits both dispatches and records no cooldown state")
        void customChainWithoutCooldownValidatorRecordsNoCooldownState() throws Exception {
            ValidatorChain chain = ValidatorChain.builder().build();
            CooldownSeamExecutor executor = new CooldownSeamExecutor(chain);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubSyncScheduler(bukkit);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "seam", new String[]{"ping"});
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "seam", new String[]{"ping"});
            }

            assertEquals(2, executor.pingCount,
                    "both dispatches must proceed when the chain lacks a CooldownValidator");

            Method pingMethod = CooldownSeamExecutor.class.getDeclaredMethod("doPing", Player.class);
            long remaining = executor.getCooldownValidator().getRemainingCooldown(player1UUID, pingMethod.toString());
            assertEquals(0, remaining,
                    "the field-created cooldownValidator (absent from the custom chain) must record no cooldown state");
        }

        @Test
        @DisplayName("Two passing validators each receive exactly one post-action call, in chain order")
        void twoPassingValidatorsEachReceiveOnePostActionInOrder() {
            List<String> completionOrder = new ArrayList<>();
            RecordingValidator first = new RecordingValidator("first", 10, true, completionOrder);
            RecordingValidator second = new RecordingValidator("second", 20, true, completionOrder);
            // Added out of order to also prove the chain still sorts by getOrder().
            ValidatorChain chain = ValidatorChain.builder().add(second).add(first).build();
            PostActionOrderExecutor executor = new PostActionOrderExecutor(chain);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubSyncScheduler(bukkit);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "postaction", new String[]{"go"});
            }

            assertEquals(1, executor.invokeCount);
            assertEquals(1, first.completions.size());
            assertEquals(1, second.completions.size());
            assertTrue(first.completions.get(0));
            assertTrue(second.completions.get(0));
            assertEquals(Arrays.asList("first", "second"), completionOrder,
                    "post-actions must fire in chain order (by getOrder())");
        }

        @Test
        @DisplayName("Failure short-circuit: when the first validator fails, the second receives no post-action call")
        void failingFirstValidatorPreventsSecondPostAction() {
            RecordingValidator failing = new RecordingValidator("failing", 10, false, null);
            RecordingValidator second = new RecordingValidator("second", 20, true, null);
            ValidatorChain chain = ValidatorChain.builder().add(failing).add(second).build();
            PostActionOrderExecutor executor = new PostActionOrderExecutor(chain);

            boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "postaction", new String[]{"go"});

            assertTrue(result);
            assertEquals(0, executor.invokeCount, "the mapped method must not run when validation fails");
            assertEquals(0, failing.completions.size());
            assertEquals(0, second.completions.size(),
                    "a validator the chain never reached must receive no post-action call");
        }

        @Test
        @DisplayName("A throwing mapped invocation still starts the player's cooldown")
        void throwingMappedMethodStillStartsCooldown() throws Exception {
            CooldownValidator cooldown = new CooldownValidator(60);
            ValidatorChain chain = ValidatorChain.builder().add(cooldown).build();
            ThrowingCommandExecutor executor = new ThrowingCommandExecutor(chain);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubSyncScheduler(bukkit);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "throwing", new String[]{"boom"});
            }

            Method method = ThrowingCommandExecutor.class.getDeclaredMethod("doBoom", Player.class);
            assertTrue(cooldown.getRemainingCooldown(player1UUID, executor, method.toString()) > 0,
                    "a failed invocation must start cooldown just like a successful one");
        }

        @Test
        @DisplayName("Dispatch refused before invocation does not start cooldown")
        void refusedDispatchDoesNotStartCooldown() throws Exception {
            CooldownValidator cooldown = new CooldownValidator(60);
            RecordingValidator refusing = new RecordingValidator("refusing", 1000, false, null);
            ValidatorChain chain = ValidatorChain.builder().add(cooldown).add(refusing).build();
            ThrowingCommandExecutor executor = new ThrowingCommandExecutor(chain);

            com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "throwing", new String[]{"boom"});

            Method method = ThrowingCommandExecutor.class.getDeclaredMethod("doBoom", Player.class);
            assertEquals(0, cooldown.getRemainingCooldown(player1UUID, executor, method.toString()));
        }

        @Test
        @DisplayName("When the mapped method throws, validators that ran still receive their post-action call with success=false")
        void throwingMappedMethodStillInvokesPostActionWithFailureFlag() {
            RecordingValidator recorder = new RecordingValidator("recorder", 10, true, null);
            ValidatorChain chain = ValidatorChain.builder().add(recorder).build();
            ThrowingCommandExecutor executor = new ThrowingCommandExecutor(chain);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubSyncScheduler(bukkit);
                assertDoesNotThrow(() ->
                        com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "throwing", new String[]{"boom"}));
            }

            assertEquals(1, recorder.completions.size());
            assertFalse(recorder.completions.get(0), "the success flag must be false when the mapped method threw");
        }
    }

    @Nested
    @DisplayName("Bare Command Tests")
    class BareCommandTests {

        @Test
        @DisplayName("Should run the body of @CmdMapping(format = \"\") when invoked with no arguments")
        void shouldExecuteBareCommandBody() {
            BareCommandExecutor executor = new BareCommandExecutor();

            boolean result = com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "bare", new String[]{});

            assertTrue(result);
            assertTrue(executor.bareCalled, "the body of the bare command should have been invoked");
            assertFalse(executor.actionCalled);
        }

        @Test
        @DisplayName("Should not send a usage message for a bare command")
        void shouldNotSendUsageForBareCommand() {
            BareCommandExecutor executor = new BareCommandExecutor();

            com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "bare", new String[]{});

            verify(mockPlayer, never()).sendMessage(anyString());
        }




    }

    /**
     * GEN-08 / D-03: {@link CooldownValidator}/{@link com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator}
     * register their per-player state with {@link PlayerCacheManager} lazily, from their own
     * first {@code validate(CommandContext)} call -- NOT from this executor's constructor. A
     * bare construction (the shape a unit test, or a real module's own constructor, reaches
     * long before any command is ever dispatched) must never even attempt contact with a core
     * plugin that may not exist yet.
     */
    @Nested
    @DisplayName("PlayerCacheManager registration is lazy, not eager at construction (GEN-08, D-03)")
    class LazyPlayerCacheRegistrationTests {

        @Test
        @DisplayName("Constructing a subclass with no core plugin available neither throws nor registers")
        void constructionWithNoCorePluginNeitherThrowsNorRegisters() {
            assertDoesNotThrow(() -> new BareCommandExecutor());
        }

        @Test
        @DisplayName("Constructing a subclass does not register anything even when the core plugin IS "
                + "available -- registration is deferred to the validators' first validate() call")
        void constructionDoesNotRegisterEvenWhenCoreAvailable() {
            // The class-level @BeforeEach already makes UltiTools.getInstance() resolve to
            // mockUltiTools -- wire IT (rather than registering a second, conflicting static
            // mock) through to a real, live PlayerCacheManager so "core available" is genuine.
            PlayerCacheManager liveManager = new PlayerCacheManager();
            PluginManager mockPluginManager = mock(PluginManager.class);
            // lenient(): the whole point of this test is that construction never reaches either
            // stub -- a strict-stubbing "unnecessary stubbing" failure here would be a false
            // negative on the very behaviour being asserted.
            lenient().when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
            lenient().when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);

            new BareCommandExecutor();

            assertEquals(0, liveManager.getTrackedBeanCount(),
                    "construction alone must not register the validators with PlayerCacheManager; "
                            + "that only happens on their own first validate() call");
        }
    }

    /**
     * WIRE-12 / T-05-29..33 -- single async dispatch plus a timeout watcher.
     * <p>
     * At HEAD, {@code executeCommand}'s {@code timeout() > 0} branch wraps the already-built
     * runnable in a second runnable whose only body calls {@code runTaskAsynchronously} again,
     * and the outer is scheduled async too -- and {@code timeout()}'s default is 30, so the
     * double dispatch is the DEFAULT path for every {@code @AsyncCommand} (D-13). These tests
     * assert the fixed shape: the body is scheduled asynchronously exactly once, and a
     * SEPARATE watcher reports a timeout to the sender without interrupting the body.
     * <p>
     * The scheduler seam mirrors {@code ChainDrivenPostActionTests.stubSyncScheduler}: rather
     * than letting a real {@code BukkitScheduler} run tasks, {@code Bukkit.getScheduler()} is
     * mocked and every call to {@code runTaskAsynchronously}/{@code runTaskLaterAsynchronously}
     * is CAPTURED (not auto-invoked), so each test controls exactly when and on which thread
     * the body and the watcher run -- a deterministic seam instead of a wall-clock race. Genuine
     * concurrency (the body actually still executing when the watcher fires) is modeled with a
     * real background {@link Thread} synchronized via {@link CountDownLatch}, never
     * {@code Thread.sleep}.
     */
    @Nested
    @DisplayName("AsyncCommand timeout watcher tests (WIRE-12)")
    class AsyncCommandTimeoutTests {

        @BeforeEach
        void stubPlayerUuid() {
            // TriggerContext.command() dereferences Player#getUniqueId() unconditionally for
            // any Player sender; mockPlayer otherwise returns null here under plain Mockito.
            lenient().when(mockPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        }

        /**
         * Stubs {@code Bukkit.getScheduler()} so every dispatch is captured rather than run.
         * {@code runTask} (main-thread hop, used by the timeout report) still runs synchronously
         * in place, mirroring {@code ChainDrivenPostActionTests.stubSyncScheduler}.
         */
        private void stubCapturingAsyncScheduler(MockedStatic<Bukkit> bukkit,
                                                  List<Runnable> asyncDispatches,
                                                  List<Runnable> delayedAsyncDispatches) {
            Scheduler scheduler = CommandRuntime.scheduler();

            lenient().when(scheduler.async().run(any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        asyncDispatches.add(invocation.getArgument(0));
                        return null;
                    });
            lenient().when(scheduler.entity(mockPlayer).runLater(any(Duration.class), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        delayedAsyncDispatches.add(invocation.getArgument(1));
                        return null;
                    });
            lenient().when(scheduler.global().run(any(Runnable.class))).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(0);
                task.run();
                return null;
            });
        }

        @Test
        @DisplayName("Test 1: the DEFAULT timeout() schedules the body asynchronously exactly once")
        void defaultTimeoutSchedulesBodyExactlyOnce() {
            AsyncCommandExecutor executor = new AsyncCommandExecutor();
            List<Runnable> asyncDispatches = new ArrayList<>();
            List<Runnable> delayedDispatches = new ArrayList<>();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubCapturingAsyncScheduler(bukkit, asyncDispatches, delayedDispatches);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "asynctest", new String[]{"defaultTimeout"});
                // Run whatever the initial dispatch captured -- on the pre-fix build this is the
                // outer wrapping runnable, and running it triggers a SECOND
                // runTaskAsynchronously call from inside its own run() body.
                for (Runnable r : new ArrayList<>(asyncDispatches)) {
                    r.run();
                }
            }

            assertEquals(1, asyncDispatches.size(),
                    "the command body must be scheduled asynchronously exactly once, even on the "
                            + "default timeout() path -- this fails on the pre-fix build, which schedules twice");
        }

        @Test
        @DisplayName("Test 2: timeout()=0 schedules once and arms no watcher")
        void zeroTimeoutSchedulesOnceAndArmsNoWatcher() {
            AsyncCommandExecutor executor = new AsyncCommandExecutor();
            List<Runnable> asyncDispatches = new ArrayList<>();
            List<Runnable> delayedDispatches = new ArrayList<>();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubCapturingAsyncScheduler(bukkit, asyncDispatches, delayedDispatches);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "asynctest", new String[]{"noTimeout"});
                for (Runnable r : new ArrayList<>(asyncDispatches)) {
                    r.run();
                }
            }

            assertEquals(1, asyncDispatches.size(), "timeout()=0 must still schedule the body exactly once");
            assertEquals(0, delayedDispatches.size(), "timeout()=0 must arm no watcher task");
            assertEquals(1, executor.syncInvocations.get());
        }

        @Test
        @DisplayName("Test 3+4: a timeout report fires while the body is still running, "
                + "and the body still runs to completion, unharmed")
        void timeoutReportsWithoutInterruptingTheStillRunningBody() throws InterruptedException {
            AsyncCommandExecutor executor = new AsyncCommandExecutor();
            List<Runnable> asyncDispatches = new ArrayList<>();
            List<Runnable> delayedDispatches = new ArrayList<>();
            Thread bodyThread;

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubCapturingAsyncScheduler(bukkit, asyncDispatches, delayedDispatches);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "asynctest", new String[]{"slow"});

                assertEquals(1, asyncDispatches.size(),
                        "the body must be scheduled exactly once even with an explicit timeout");
                assertEquals(1, delayedDispatches.size(),
                        "a non-zero timeout must arm exactly one watcher task");

                // Run the body on a REAL background thread so it is genuinely still in flight
                // -- not merely simulated -- when the watcher fires below.
                bodyThread = new Thread(asyncDispatches.get(0), "async-body-test-thread");
                bodyThread.start();

                assertTrue(executor.bodyStarted.await(5, TimeUnit.SECONDS),
                        "the body must actually start running before the watcher fires");

                // The watcher firing while the body is still blocked on bodyContinue models
                // "the deadline passed with the body still running" without any real sleep.
                delayedDispatches.get(0).run();
            }

            // Match on the timeout-specific message content rather than "any string" -- the
            // default @AsyncCommand also sends a "processing" message before dispatch, which is
            // expected and orthogonal to the timeout report being asserted here.
            verify(mockPlayer, times(1)).sendMessage(argThat((Component component) -> CommandMessageTestSupport.plain(component).contains("命令执行超时")));

            // Release the body and confirm it is NOT interrupted and runs to completion.
            executor.bodyContinue.countDown();
            bodyThread.join(5000);
            assertFalse(bodyThread.isAlive(), "the body thread must finish on its own after being released");
            assertTrue(executor.bodyCompleted.get(),
                    "the body must run to completion after the timeout report fires");
            assertFalse(executor.bodyInterrupted.get(),
                    "the body must NOT be interrupted when the timeout fires");

            // Test 5 (first half): completing after the timeout must not produce a SECOND
            // timeout report.
            verify(mockPlayer, times(1)).sendMessage(argThat((Component component) -> CommandMessageTestSupport.plain(component).contains("命令执行超时")));
        }

        @Test
        @DisplayName("Test 5 (second half): a body completing before the deadline produces no timeout report")
        void bodyCompletingBeforeDeadlineProducesNoTimeoutReport() {
            AsyncCommandExecutor executor = new AsyncCommandExecutor();
            List<Runnable> asyncDispatches = new ArrayList<>();
            List<Runnable> delayedDispatches = new ArrayList<>();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubCapturingAsyncScheduler(bukkit, asyncDispatches, delayedDispatches);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "asynctest", new String[]{"fast"});

                assertEquals(1, asyncDispatches.size());
                assertEquals(1, delayedDispatches.size());

                // The body completes well before any deadline.
                asyncDispatches.get(0).run();

                // Simulate the scheduler eventually firing the already-armed watcher AFTER the
                // body has already completed -- the race the "already reported" flag guards.
                delayedDispatches.get(0).run();
            }

            assertEquals(1, executor.fastInvocations.get());
            // The default @AsyncCommand still sends its "processing" message; only the
            // TIMEOUT-specific report must be absent.
            verify(mockPlayer, never()).sendMessage(argThat((Component component) -> CommandMessageTestSupport.plain(component).contains("命令执行超时")));
        }

        @Test
        @DisplayName("Test 6: a body that throws after the deadline produces no second report "
                + "and no exception escaping the scheduler")
        void bodyThrowingAfterDeadlineProducesNoSecondReportOrEscape() throws InterruptedException {
            AsyncCommandExecutor executor = new AsyncCommandExecutor();
            List<Runnable> asyncDispatches = new ArrayList<>();
            List<Runnable> delayedDispatches = new ArrayList<>();
            Thread bodyThread;
            AtomicReference<Throwable> escaped = new AtomicReference<>();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubCapturingAsyncScheduler(bukkit, asyncDispatches, delayedDispatches);
                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "asynctest", new String[]{"slowThrow"});

                assertEquals(1, asyncDispatches.size());
                assertEquals(1, delayedDispatches.size());

                bodyThread = new Thread(() -> {
                    try {
                        asyncDispatches.get(0).run();
                    } catch (Throwable t) {
                        escaped.set(t);
                    }
                }, "async-throwing-body-test-thread");
                bodyThread.start();

                assertTrue(executor.bodyStarted.await(5, TimeUnit.SECONDS));
                delayedDispatches.get(0).run();
            }

            verify(mockPlayer, times(1)).sendMessage(argThat((Component component) -> CommandMessageTestSupport.plain(component).contains("命令执行超时")));

            executor.bodyContinue.countDown();
            bodyThread.join(5000);
            assertFalse(bodyThread.isAlive());
            assertNull(escaped.get(),
                    "an exception thrown by the mapped method after the deadline must not escape "
                            + "the scheduled runnable");
            // Still exactly one report from the WATCHER -- the framework's own generic
            // "command execution failed" handler (unrelated to this task) may additionally
            // message the sender for the thrown exception itself, so this asserts no SECOND
            // timeout-shaped report rather than a total message count.
            verify(mockPlayer, times(1)).sendMessage(argThat((Component component) -> CommandMessageTestSupport.plain(component).contains("命令执行超时")));
        }

        @Test
        @DisplayName("Test 7: a synchronous mapping runs inline at dispatch on the primary thread, arms no watcher")
        void synchronousMappingUnaffectedAndArmsNoWatcher() {
            AsyncCommandExecutor executor = new AsyncCommandExecutor();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                Scheduler scheduler = CommandRuntime.scheduler();

                // #541: on the primary thread the body runs at dispatch; nothing is scheduled.
                lenient().when(CommandRuntime.scheduler().global().isCurrentThread()).thenReturn(true);

                com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(executor, mockPlayer, mockCommand, "asynctest", new String[]{"sync"});

                verify(scheduler.global(), never()).run(any(Runnable.class));
                verify(scheduler.async(), never()).run(any(Runnable.class));
                verify(scheduler.entity(mockPlayer), never()).runLater(any(Duration.class), any(Runnable.class));
            }

            assertEquals(1, executor.syncInvocations.get());
        }
    }


    // Test implementations

    /**
     * Fixture for {@link AsyncCommandTimeoutTests}. {@code doSlow}/{@code doSlowThrow} block on
     * {@code bodyContinue} so a test can hold them "still running" across a real background
     * thread boundary without any {@code Thread.sleep} -- the test releases the latch itself
     * once it has observed the timeout-fired state it wants to assert against.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"asynctest"})
    static class AsyncCommandExecutor extends BaseCommandExecutor {
        final AtomicInteger syncInvocations = new AtomicInteger();
        final AtomicInteger fastInvocations = new AtomicInteger();
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch bodyContinue = new CountDownLatch(1);
        final AtomicBoolean bodyCompleted = new AtomicBoolean(false);
        final AtomicBoolean bodyInterrupted = new AtomicBoolean(false);

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in these tests
        }

        @CmdMapping(format = "defaultTimeout")
        @AsyncCommand
        public void doDefaultTimeout(Player player) {
            syncInvocations.incrementAndGet();
        }

        @CmdMapping(format = "noTimeout")
        @AsyncCommand(timeout = 0)
        public void doNoTimeout(Player player) {
            syncInvocations.incrementAndGet();
        }

        @CmdMapping(format = "slow")
        @AsyncCommand(timeout = 5)
        public void doSlow(Player player) {
            bodyStarted.countDown();
            try {
                bodyContinue.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                bodyInterrupted.set(true);
                Thread.currentThread().interrupt();
                return;
            }
            bodyCompleted.set(true);
        }

        @CmdMapping(format = "fast")
        @AsyncCommand(timeout = 5)
        public void doFast(Player player) {
            fastInvocations.incrementAndGet();
        }

        @CmdMapping(format = "slowThrow")
        @AsyncCommand(timeout = 5)
        public void doSlowThrow(Player player) {
            bodyStarted.countDown();
            try {
                bodyContinue.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            throw new IllegalStateException("boom-after-deadline");
        }

        @CmdMapping(format = "sync")
        public void doSync(Player player) {
            syncInvocations.incrementAndGet();
        }
    }

    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"test"})
    static class TestCommandExecutor extends BaseCommandExecutor {
        boolean helpCalled = false;
        boolean actionCalled = false;
        String lastParam = null;
        
        @Override
        protected void handleHelp(CommandSender sender) {
            helpCalled = true;
        }
        
        @CmdMapping(format = "action <param>")
        public void doAction(Player player, @CmdParam("param") String param) {
            actionCalled = true;
            lastParam = param;
        }
        
        @CmdMapping(format = "")
        public void doDefault(Player player) {
            // Default command (empty format)
        }
        
        @CmdMapping(format = "multi <a> <b>")
        public void doMultiParam(Player player, @CmdParam("a") String a, @CmdParam("b") String b) {
        }
    }

    /**
     * Fixture for the bare-command path.
     * <p>
     * onCommand runs a synchronous command body inline when it is on the primary thread and
     * hands it to runTask when it is not (#541); either way it asks Bukkit, and this test class
     * runs on plain Mockito with no Bukkit server. Dispatch is overridden to invoke inline, so
     * the whole path is still exercised -- onCommand, matchMethod, the validator chain,
     * validateParameterCount -- minus the primary-thread check.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"bare"})
    static class BareCommandExecutor extends BaseCommandExecutor {
        boolean bareCalled = false;
        boolean actionCalled = false;

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised by the bare command tests
        }

        @Override
        protected void executeCommand(CommandContext context, Method method, Object[] params,
                                       ValidatorChain.ChainValidationResult validationResult) {
            // No setAccessible here: the invocation happens inside this class on its own
            // public methods, so the access check already passes.
            try {
                method.invoke(this, params);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @CmdMapping(format = "")
        public void doBare(Player player) {
            bareCalled = true;
        }

        @CmdMapping(format = "action <param>")
        public void doAction(Player player, @CmdParam("param") String param) {
            actionCalled = true;
        }
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"varargs"})
    static class VarargsCommandExecutor extends BaseCommandExecutor {
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }

        @CmdMapping(format = "echo <message...>")
        public void doEcho(Player player, @CmdParam("message") String[] messages) {
            // Empty implementation - method is used to test varargs command mapping, not execution logic
        }
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"custom"})
    static class CustomChainExecutor extends BaseCommandExecutor {

        public CustomChainExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
    }

    /**
     * Fixture for the SILENT-11 seam assertion: constructed via the custom-{@link ValidatorChain}
     * constructor, carries a {@code @CmdCD}-annotated mapping so enforcement can be observed
     * through a real dispatch.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"seam"})
    static class CooldownSeamExecutor extends BaseCommandExecutor {
        int pingCount = 0;

        CooldownSeamExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }

        @CmdMapping(format = "ping")
        @CmdCD(5)
        public void doPing(Player player) {
            pingCount++;
        }
    }

    /**
     * Fixture for the post-action order/arity/failure-short-circuit tests. Carries no
     * cooldown/lock annotation so only the {@link RecordingValidator}s injected via the custom
     * chain gate and receive post-actions.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"postaction"})
    static class PostActionOrderExecutor extends BaseCommandExecutor {
        int invokeCount = 0;

        PostActionOrderExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }

        @CmdMapping(format = "go")
        public void doGo(Player player) {
            invokeCount++;
        }
    }

    /**
     * Fixture whose mapped method always throws, for asserting that post-actions still fire
     * with a false success flag.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"throwing"})
    static class ThrowingCommandExecutor extends BaseCommandExecutor {

        ThrowingCommandExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }

        @CmdMapping(format = "boom")
        public void doBoom(Player player) {
            throw new IllegalStateException("boom");
        }
    }

    /**
     * A {@link CommandValidator} test double that records each {@code onComplete} invocation's
     * success flag and, optionally, appends its name to a shared log so relative call order
     * across multiple validators can be asserted.
     */
    static class RecordingValidator implements CommandValidator {
        private final String name;
        private final int order;
        private final boolean succeeds;
        private final List<String> sharedLog;
        final List<Boolean> completions = new ArrayList<>();

        RecordingValidator(String name, int order, boolean succeeds, List<String> sharedLog) {
            this.name = name;
            this.order = order;
            this.succeeds = succeeds;
            this.sharedLog = sharedLog;
        }

        @Override
        public ValidationResult validate(CommandContext context) {
            return succeeds ? ValidationResult.success() : ValidationResult.failure("forced failure: " + name);
        }

        @Override
        public void onComplete(CommandContext context, boolean commandSucceeded) {
            completions.add(commandSucceeded);
            if (sharedLog != null) {
                sharedLog.add(name);
            }
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
    @CmdExecutor(alias = {"consoleonly"})
    static class ConsoleOnlyExecutor extends BaseCommandExecutor {
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
        
        @CmdMapping(format = "run")
        public void doRun(CommandSender sender) {
        }
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"types"})
    static class TypedParamsExecutor extends BaseCommandExecutor {
        int lastInt = 0;
        double lastDouble = 0;
        boolean lastBool = false;
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
        
        @CmdMapping(format = "int <num>")
        public void doInt(Player player, @CmdParam("num") int num) {
            lastInt = num;
        }
        
        @CmdMapping(format = "double <num>")
        public void doDouble(Player player, @CmdParam("num") double num) {
            lastDouble = num;
        }
        
        @CmdMapping(format = "bool <flag>")
        public void doBool(Player player, @CmdParam("flag") boolean flag) {
            lastBool = flag;
        }
        
        @CmdMapping(format = "sender")
        public void doSender(@CmdSender CommandSender sender) {
            // Test stub - validates sender injection
        }

        @CmdMapping(format = "noparam")
        public void doNoParam() {
            // Test stub - validates no-parameter commands
        }
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"playersender"})
    static class PlayerSenderExecutor extends BaseCommandExecutor {

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }

        @CmdMapping(format = "playersender")
        public void doPlayerSender(@CmdSender Player player) {
            // Test stub - validates player sender injection
        }
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"sendernoannot"})
    static class SenderNoAnnotationExecutor extends BaseCommandExecutor {
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
        
        @CmdMapping(format = "sendernoannot")
        public void doSenderNoAnnotation(CommandSender sender) {
            // CommandSender without annotation
        }
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"noannotparam"})
    static class NoAnnotationParamExecutor extends BaseCommandExecutor {
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
        
        @CmdMapping(format = "noannotation")
        public void doNoAnnotation(String someValue) {
            // String parameter without @CmdParam annotation
        }
    }
    
    /**
     * Executor with an unsupported parameter type to trigger TypeParseException.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"unsupportedtype"})
    static class UnsupportedTypeExecutor extends BaseCommandExecutor {
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
        
        @CmdMapping(format = "process <data>")
        public void doProcess(Player player, @CmdParam("data") java.util.Date data) {
            // java.util.Date is not a supported type in TypeParserRegistry
        }
    }
    
    /**
     * Executor for testing failing validation scenarios.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"failvalidation"})
    static class FailValidationExecutor extends BaseCommandExecutor {
        
        public FailValidationExecutor(ValidatorChain chain) {
            super(chain);
        }
        
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - help handler not used in tests
        }
        
        @CmdMapping(format = "run")
        public void doRun(Player player) {
        }
    }
}
