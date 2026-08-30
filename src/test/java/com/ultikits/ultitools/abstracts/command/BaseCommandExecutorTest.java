package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.interfaces.VersionWrapper;

/**
 * Unit tests for BaseCommandExecutor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseCommandExecutor Tests")
class BaseCommandExecutorTest {

    @Mock
    private Player mockPlayer;
    
    @Mock
    private CommandSender mockSender;
    
    @Mock
    private Command mockCommand;
    
    @Mock
    private UltiTools mockUltiTools;
    
    @Mock
    private VersionWrapper mockVersionWrapper;

    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockUltiTools.getVersionWrapper()).thenReturn(mockVersionWrapper);
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
        
        @Test
        @DisplayName("Should match method for valid arguments")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldMatchMethod() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method matchMethod = BaseCommandExecutor.class.getDeclaredMethod("matchMethod", String[].class);
            matchMethod.setAccessible(true);
            
            Method result = (Method) matchMethod.invoke(executor, (Object) new String[]{"action", "test"});
            assertNotNull(result);
            assertEquals("doAction", result.getName());
        }
        
        @Test
        @DisplayName("Should return null for no match")
        void shouldReturnNullForNoMatch() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method matchMethod = BaseCommandExecutor.class.getDeclaredMethod("matchMethod", String[].class);
            matchMethod.setAccessible(true);
            
            Method result = (Method) matchMethod.invoke(executor, (Object) new String[]{"unknown", "cmd"});
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Parameter Parsing Tests")
    class ParameterParsingTests {
        
        @Test
        @DisplayName("Should parse simple parameters")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldParseSimpleParameters() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod("parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{"action", "value"}, "action <param>");
            
            assertNotNull(result);
            assertTrue(result.containsKey("param"));
            assertEquals("value", result.get("param")[0]);
        }
        
        @Test
        @DisplayName("Should parse varargs parameters")
        void shouldParseVarargsParameters() throws Exception {
            VarargsCommandExecutor executor = new VarargsCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod("parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{"echo", "hello", "world", "test"}, "echo <message...>");
            
            assertNotNull(result);
            assertTrue(result.containsKey("message"));
            assertEquals(3, result.get("message").length);
        }
        
        @Test
        @DisplayName("Should handle empty parameters")
        void shouldHandleEmptyParameters() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod("parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{}, "");
            
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Help Command Tests")
    class HelpTests {
        
        @Test
        @DisplayName("Should handle help command")
        void shouldHandleHelpCommand() {
            TestCommandExecutor executor = new TestCommandExecutor();
            boolean result = executor.onCommand(mockPlayer, mockCommand, "test", new String[]{"help"});
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
            
            List<String> suggestions = executor.onTabComplete(mockPlayer, mockCommand, "test", new String[]{"a"});
            
            assertNotNull(suggestions);
            assertTrue(suggestions.contains("action"));
        }
        
        @Test
        @DisplayName("Should return null for console sender")
        void shouldReturnNullForConsoleSender() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = executor.onTabComplete(mockSender, mockCommand, "test", new String[]{"a"});
            
            assertNull(suggestions);
        }
        
        @Test
        @DisplayName("Should return null for console-only command with player")
        void shouldReturnNullForConsoleOnlyCommand() {
            ConsoleOnlyExecutor executor = new ConsoleOnlyExecutor();
            
            List<String> suggestions = executor.onTabComplete(mockPlayer, mockCommand, "consoleonly", new String[]{"r"});
            
            assertNull(suggestions);
        }
        
        @Test
        @DisplayName("Should return empty suggestions for non-matching prefix")
        void shouldReturnEmptyForNonMatchingPrefix() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = executor.onTabComplete(mockPlayer, mockCommand, "test", new String[]{"xyz"});
            
            assertNotNull(suggestions);
            assertFalse(suggestions.contains("action"));
        }
        
        @Test
        @DisplayName("Should return multiple suggestions")
        void shouldReturnMultipleSuggestions() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            // With empty prefix, should match action and multi
            List<String> suggestions = executor.onTabComplete(mockPlayer, mockCommand, "test", new String[]{""});
            
            assertNotNull(suggestions);
        }
    }

    @Nested
    @DisplayName("Calculate Match Score Tests")
    class MatchScoreTests {
        
        @Test
        @DisplayName("Should calculate higher score for exact match")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldCalculateHigherScoreForExactMatch() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method calcMethod = BaseCommandExecutor.class.getDeclaredMethod("calculateMatchScore", String[].class, String[].class);
            calcMethod.setAccessible(true);
            
            int exactScore = (int) calcMethod.invoke(executor, new String[]{"action"}, new String[]{"action"});
            int paramScore = (int) calcMethod.invoke(executor, new String[]{"<param>"}, new String[]{"action"});
            
            assertTrue(exactScore > paramScore);
        }
        
        @Test
        @DisplayName("Should return -1 for no match")
        void shouldReturnNegativeForNoMatch() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method calcMethod = BaseCommandExecutor.class.getDeclaredMethod("calculateMatchScore", String[].class, String[].class);
            calcMethod.setAccessible(true);
            
            int score = (int) calcMethod.invoke(executor, new String[]{"action"}, new String[]{"other"});
            
            assertEquals(-1, score);
        }
        
        @Test
        @DisplayName("Should handle empty arrays")
        void shouldHandleEmptyArrays() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method calcMethod = BaseCommandExecutor.class.getDeclaredMethod("calculateMatchScore", String[].class, String[].class);
            calcMethod.setAccessible(true);
            
            int score = (int) calcMethod.invoke(executor, new String[]{}, new String[]{});
            
            assertEquals(100, score);
        }
        
        @Test
        @DisplayName("Should handle varargs format")
        void shouldHandleVarargsFormat() throws Exception {
            VarargsCommandExecutor executor = new VarargsCommandExecutor();
            Method calcMethod = BaseCommandExecutor.class.getDeclaredMethod("calculateMatchScore", String[].class, String[].class);
            calcMethod.setAccessible(true);
            
            int score = (int) calcMethod.invoke(executor, new String[]{"echo", "<message...>"}, new String[]{"echo", "hello", "world"});
            
            assertTrue(score > 0);
        }
        
        @Test
        @DisplayName("Should return -1 when actual args longer than format without varargs")
        void shouldReturnNegativeWhenActualLonger() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method calcMethod = BaseCommandExecutor.class.getDeclaredMethod("calculateMatchScore", String[].class, String[].class);
            calcMethod.setAccessible(true);
            
            int score = (int) calcMethod.invoke(executor, new String[]{"action"}, new String[]{"action", "extra", "args"});
            
            assertEquals(-1, score);
        }
    }
    
    @Nested
    @DisplayName("onCommand Tests")
    class OnCommandTests {
        
        @Test
        @DisplayName("Should handle unknown command")
        void shouldHandleUnknownCommand() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            boolean result = executor.onCommand(mockPlayer, mockCommand, "test", new String[]{"unknown", "cmd"});
            
            assertTrue(result);
            assertTrue(executor.helpCalled); // Should show help for unknown command
        }
        
        @Test
        @DisplayName("Should match empty format for no args")
        void shouldMatchEmptyFormatForNoArgs() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method matchMethod = BaseCommandExecutor.class.getDeclaredMethod("matchMethod", String[].class);
            matchMethod.setAccessible(true);
            
            Method result = (Method) matchMethod.invoke(executor, (Object) new String[]{});
            
            assertNotNull(result);
            assertEquals("doDefault", result.getName());
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
            
            boolean result = executor.onCommand(mockPlayer, mockCommand, "failvalidation", new String[]{"run"});
            
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
            
            boolean result = executor.onCommand(mockPlayer, mockCommand, "failvalidation", new String[]{"run"});
            
            assertTrue(result);
        }
    }
    
    @Nested
    @DisplayName("Parameter Validation Tests")
    class ParameterValidationTests {
        
        @Test
        @DisplayName("Should validate parameter count correctly")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldValidateParameterCount() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method validateMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "validateParameterCount", String[].class, String.class, CommandSender.class, Command.class);
            validateMethod.setAccessible(true);
            
            // Correct count
            boolean correct = (boolean) validateMethod.invoke(executor, 
                    new String[]{"action", "value"}, "action <param>", mockPlayer, mockCommand);
            assertTrue(correct);
        }
        
        @Test
        @DisplayName("Should fail validation for wrong parameter count")
        void shouldFailValidationForWrongCount() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method validateMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "validateParameterCount", String[].class, String.class, CommandSender.class, Command.class);
            validateMethod.setAccessible(true);
            
            // Too few args
            boolean tooFew = (boolean) validateMethod.invoke(executor, 
                    new String[]{"action"}, "action <param>", mockPlayer, mockCommand);
            assertFalse(tooFew);
        }
        
        @Test
        @DisplayName("Should pass validation for varargs")
        void shouldPassValidationForVarargs() throws Exception {
            VarargsCommandExecutor executor = new VarargsCommandExecutor();
            Method validateMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "validateParameterCount", String[].class, String.class, CommandSender.class, Command.class);
            validateMethod.setAccessible(true);
            
            boolean result = (boolean) validateMethod.invoke(executor, 
                    new String[]{"echo", "hello", "world", "test"}, "echo <message...>", mockPlayer, mockCommand);
            assertTrue(result);
        }
        
        @Test
        @DisplayName("Should fail validation for varargs with too few args")
        void shouldFailValidationForVarargsWithTooFewArgs() throws Exception {
            VarargsCommandExecutor executor = new VarargsCommandExecutor();
            Method validateMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "validateParameterCount", String[].class, String.class, CommandSender.class, Command.class);
            validateMethod.setAccessible(true);
            
            // No message at all (need at least "echo")
            boolean result = (boolean) validateMethod.invoke(executor, 
                    new String[]{}, "echo <message...>", mockPlayer, mockCommand);
            assertFalse(result);
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
                    .command(mockCommand)
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
                    .command(mockCommand)
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
                    .command(mockCommand)
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
                    .command(mockCommand)
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
            Map<String, String[]> params = new HashMap<>();
            params.put("param", new String[]{"value"});
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
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
                    .command(mockCommand)
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
                    .command(mockCommand)
                    .alias("noannotparam")
                    .rawArgs(new String[]{"noannotation"})
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            assertNotNull(result);
            assertEquals(1, result.length);
            // Without @CmdParam, it should add null
            assertNull(result[0]);
        }
        
        @Test
        @DisplayName("Should return null when TypeParseException is thrown")
        void shouldReturnNullWhenTypeParseExceptionThrown() throws Exception {
            UnsupportedTypeExecutor executor = new UnsupportedTypeExecutor();
            Method buildMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "buildMethodParams", CommandContext.class, Method.class);
            buildMethod.setAccessible(true);
            
            Method targetMethod = UnsupportedTypeExecutor.class.getDeclaredMethod("doProcess", Player.class, java.util.Date.class);
            Map<String, String[]> params = new HashMap<>();
            params.put("data", new String[]{"2024-01-01"});
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("unsupportedtype")
                    .rawArgs(new String[]{"process", "2024-01-01"})
                    .parsedParams(params)
                    .build();
            
            Object[] result = (Object[]) buildMethod.invoke(executor, context, targetMethod);
            
            // Should return null due to TypeParseException for unsupported Date type
            assertNull(result);
        }
    }
    
    @Nested
    @DisplayName("Parse Parameter Value Tests")
    class ParseParameterValueTests {
        
        @Test
        @DisplayName("Should return empty string for null values")
        void shouldReturnEmptyStringForNullValues() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameterValue", String[].class, Class.class);
            parseMethod.setAccessible(true);
            
            String result = (String) parseMethod.invoke(executor, null, String.class);
            
            assertEquals("", result);
        }
        
        @Test
        @DisplayName("Should return null for non-String type with null values")
        void shouldReturnNullForNonStringTypeWithNullValues() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameterValue", String[].class, Class.class);
            parseMethod.setAccessible(true);
            
            Object result = parseMethod.invoke(executor, null, Integer.class);
            
            assertNull(result);
        }
        
        @Test
        @DisplayName("Should return null for empty array with non-String type")
        void shouldReturnNullForEmptyArrayWithNonStringType() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameterValue", String[].class, Class.class);
            parseMethod.setAccessible(true);
            
            Object result = parseMethod.invoke(executor, new String[]{}, Integer.class);
            
            assertNull(result);
        }
        
        @Test
        @DisplayName("Should parse single value")
        void shouldParseSingleValue() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameterValue", String[].class, Class.class);
            parseMethod.setAccessible(true);
            
            Object result = parseMethod.invoke(executor, new String[]{"hello"}, String.class);
            
            assertEquals("hello", result);
        }
        
        @Test
        @DisplayName("Should parse array type")
        void shouldParseArrayType() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameterValue", String[].class, Class.class);
            parseMethod.setAccessible(true);
            
            Object result = parseMethod.invoke(executor, new String[]{"a", "b", "c"}, String[].class);
            
            assertNotNull(result);
            assertTrue(result instanceof String[]);
            String[] array = (String[]) result;
            assertEquals(3, array.length);
        }
        
        @Test
        @DisplayName("Should join multiple values for String type")
        void shouldJoinMultipleValuesForStringType() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameterValue", String[].class, Class.class);
            parseMethod.setAccessible(true);
            
            String result = (String) parseMethod.invoke(executor, new String[]{"hello", "world"}, String.class);
            
            assertEquals("hello world", result);
        }
    }
    
    @Nested
    @DisplayName("Extract Parameter Name Tests")
    class ExtractParameterNameTests {
        
        @Test
        @DisplayName("Should extract name from simple parameter")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldExtractSimpleName() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method extractMethod = BaseCommandExecutor.class.getDeclaredMethod("extractParameterName", String.class);
            extractMethod.setAccessible(true);
            
            String name = (String) extractMethod.invoke(executor, "<param>");
            
            assertEquals("param", name);
        }
        
        @Test
        @DisplayName("Should extract name from varargs parameter")
        void shouldExtractVarargsName() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method extractMethod = BaseCommandExecutor.class.getDeclaredMethod("extractParameterName", String.class);
            extractMethod.setAccessible(true);
            
            String name = (String) extractMethod.invoke(executor, "<message...>");
            
            assertEquals("message", name);
        }
    }
    
    @Nested
    @DisplayName("Parse Parameters Tests")
    class ParseParametersTests {
        
        @Test
        @DisplayName("Should parse single parameter")
        void shouldParseSingleParameter() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{"action", "myvalue"}, "action <param>");
            
            assertNotNull(result);
            assertTrue(result.containsKey("param"));
            assertEquals("myvalue", result.get("param")[0]);
        }
        
        @Test
        @DisplayName("Should parse multiple parameters")
        void shouldParseMultipleParameters() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{"multi", "value1", "value2"}, "multi <a> <b>");
            
            assertNotNull(result);
            assertTrue(result.containsKey("a"));
            assertTrue(result.containsKey("b"));
            assertEquals("value1", result.get("a")[0]);
            assertEquals("value2", result.get("b")[0]);
        }
        
        @Test
        @DisplayName("Should return empty map for empty args")
        void shouldReturnEmptyMapForEmptyArgs() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{}, "action <param>");
            
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
        
        @Test
        @DisplayName("Should return empty map for null format")
        void shouldReturnEmptyMapForNullFormat() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{"action"}, null);
            
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
        
        @Test
        @DisplayName("Should parse varargs parameters")
        void shouldParseVarargsParameters() throws Exception {
            VarargsCommandExecutor executor = new VarargsCommandExecutor();
            Method parseMethod = BaseCommandExecutor.class.getDeclaredMethod(
                    "parseParameters", String[].class, String.class);
            parseMethod.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<String, String[]> result = (Map<String, String[]>) parseMethod.invoke(
                    executor, new String[]{"echo", "hello", "world", "test"}, "echo <message...>");
            
            assertNotNull(result);
            assertTrue(result.containsKey("message"));
            String[] messages = result.get("message");
            assertEquals(3, messages.length);
            assertEquals("hello", messages[0]);
            assertEquals("world", messages[1]);
            assertEquals("test", messages[2]);
        }
    }
    
    @Nested
    @DisplayName("Get Next Suggestions Tests")
    class GetNextSuggestionsTests {
        
        @Test
        @DisplayName("Should return literal suggestions for static parts")
        void shouldReturnLiteralSuggestions() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = executor.onTabComplete(mockPlayer, mockCommand, "test", new String[]{""});
            
            assertNotNull(suggestions);
            assertTrue(suggestions.contains("action") || suggestions.contains("multi"));
        }
    }
    
    @Nested
    @DisplayName("Is Parameter Tests")
    class IsParameterTests {
        
        @Test
        @DisplayName("Should detect parameter placeholder")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldDetectParameter() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method isParamMethod = BaseCommandExecutor.class.getDeclaredMethod("isParameter", String.class);
            isParamMethod.setAccessible(true);
            
            boolean result = (boolean) isParamMethod.invoke(executor, "<param>");
            
            assertTrue(result);
        }
        
        @Test
        @DisplayName("Should not detect literal as parameter")
        void shouldNotDetectLiteralAsParameter() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method isParamMethod = BaseCommandExecutor.class.getDeclaredMethod("isParameter", String.class);
            isParamMethod.setAccessible(true);
            
            boolean result = (boolean) isParamMethod.invoke(executor, "action");
            
            assertFalse(result);
        }
        
        @Test
        @DisplayName("Should detect varargs parameter")
        void shouldDetectVarargsParameter() throws Exception {
            TestCommandExecutor executor = new TestCommandExecutor();
            Method isParamMethod = BaseCommandExecutor.class.getDeclaredMethod("isParameter", String.class);
            isParamMethod.setAccessible(true);
            
            boolean result = (boolean) isParamMethod.invoke(executor, "<message...>");
            
            assertTrue(result);
        }
    }
    
    @Nested
    @DisplayName("Suggest Method Tests")
    class SuggestMethodTests {
        
        @Test
        @DisplayName("Should return suggestions for first argument")
        void shouldReturnSuggestionsForFirstArg() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = executor.onTabComplete(mockPlayer, mockCommand, "test", new String[]{"a"});
            
            assertNotNull(suggestions);
        }
        
        @Test
        @DisplayName("Should return null for console sender")
        void shouldReturnNullForConsoleSender() {
            TestCommandExecutor executor = new TestCommandExecutor();
            
            List<String> suggestions = executor.onTabComplete(mockSender, mockCommand, "test", new String[]{"action"});
            
            assertNull(suggestions);
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
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            lenient().when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
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
                executor.onCommand(mockPlayer, mockCommand, "seam", new String[]{"ping"});
                executor.onCommand(mockPlayer, mockCommand, "seam", new String[]{"ping"});
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
                executor.onCommand(mockPlayer, mockCommand, "seam", new String[]{"ping"});
                executor.onCommand(mockPlayer, mockCommand, "seam", new String[]{"ping"});
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
                executor.onCommand(mockPlayer, mockCommand, "postaction", new String[]{"go"});
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

            boolean result = executor.onCommand(mockPlayer, mockCommand, "postaction", new String[]{"go"});

            assertTrue(result);
            assertEquals(0, executor.invokeCount, "the mapped method must not run when validation fails");
            assertEquals(0, failing.completions.size());
            assertEquals(0, second.completions.size(),
                    "a validator the chain never reached must receive no post-action call");
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
                        executor.onCommand(mockPlayer, mockCommand, "throwing", new String[]{"boom"}));
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

            boolean result = executor.onCommand(mockPlayer, mockCommand, "bare", new String[]{});

            assertTrue(result);
            assertTrue(executor.bareCalled, "the body of the bare command should have been invoked");
            assertFalse(executor.actionCalled);
        }

        @Test
        @DisplayName("Should not send a usage message for a bare command")
        void shouldNotSendUsageForBareCommand() {
            BareCommandExecutor executor = new BareCommandExecutor();

            executor.onCommand(mockPlayer, mockCommand, "bare", new String[]{});

            verify(mockPlayer, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should accept an empty format when no arguments are given")
        void shouldValidateEmptyFormatWithNoArgs() {
            BareCommandExecutor executor = new BareCommandExecutor();

            assertTrue(executor.validateParameterCount(new String[]{}, "", mockPlayer, mockCommand));
        }

        @Test
        @DisplayName("Should reject an empty format when arguments are supplied")
        void shouldRejectEmptyFormatWithArgs() {
            BareCommandExecutor executor = new BareCommandExecutor();

            assertFalse(executor.validateParameterCount(new String[]{"extra"}, "", mockPlayer, mockCommand));
        }
    }

    // Test implementations
    
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
     * onCommand defers a synchronous command body by one tick through BukkitRunnable,
     * and this test class runs on plain Mockito with no Bukkit scheduler. Dispatch is
     * overridden to invoke inline, so the whole path is still exercised -- onCommand,
     * matchMethod, the validator chain, validateParameterCount -- minus the hop through
     * the scheduler.
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
