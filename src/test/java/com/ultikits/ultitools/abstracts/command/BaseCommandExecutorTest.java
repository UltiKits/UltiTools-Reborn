package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
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
    }

    @Nested
    @DisplayName("Calculate Match Score Tests")
    class MatchScoreTests {
        
        @Test
        @DisplayName("Should calculate higher score for exact match")
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
    }
    
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"varargs"})
    static class VarargsCommandExecutor extends BaseCommandExecutor {
        
        @Override
        protected void handleHelp(CommandSender sender) {
        }
        
        @CmdMapping(format = "echo <message...>")
        public void doEcho(Player player, @CmdParam("message") String[] messages) {
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
        }
    }
}
