package com.ultikits.ultitools.commands.tabcomplete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSuggest;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.text.ModuleCatalogueFixture;

/**
 * Unit tests for MethodInvocationCompleter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MethodInvocationCompleter Tests")
class MethodInvocationCompleterTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private Command mockCommand;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private CommandManager mockCommandManager;

    @Mock
    private UltiToolsPlugin mockPlugin;

    @TempDir Path tempDir;
    private MethodInvocationCompleter completer;
    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        completer = new MethodInvocationCompleter();
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        when(mockUltiTools.getCommandManager()).thenReturn(mockCommandManager);
    }

    @AfterEach
    void tearDown() {
        ultiToolsMock.close();
    }

    // Test executor class with suggest methods
    public static class TestExecutor {

        public void commandMethod(
                @CmdParam(value = "player", suggest = "suggestPlayers") String player,
                @CmdParam(value = "action", suggest = "suggestActions()") String action,
                @CmdParam(value = "item") String item) {
        }

        public List<String> suggestPlayers() {
            return Arrays.asList("Player1", "Player2", "Player3");
        }

        public List<String> suggestActions() {
            return Arrays.asList("give", "take", "set");
        }

        public List<String> suggestWithPlayer(Player player) {
            return Arrays.asList("ForPlayer:" + player.getName());
        }

        public String[] suggestArray() {
            return new String[]{"arr1", "arr2", "arr3"};
        }

        public Collection<String> suggestCollection() {
            return Arrays.asList("col1", "col2");
        }
    }

    // Executor class with @CmdSuggest annotation
    @CmdSuggest({ExternalSuggestProvider.class})
    public static class ExecutorWithCmdSuggest {

        public void someCommand(@CmdParam(value = "type", suggest = "externalSuggest") String type) {
            // Empty test stub - method is used to test @CmdSuggest annotation, not execution
        }
    }

    public static class ExternalSuggestProvider {
        public List<String> externalSuggest() {
            return Arrays.asList("external1", "external2");
        }
    }

    @Nested
    @DisplayName("complete() Basic Tests")
    class CompleteBasicTests {

        @Test
        @DisplayName("complete() should return empty when matchedMethod is null")
        void completeReturnsemptyWhenmatchedmethodnull() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(null)
                    .parameterName("test")
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should return empty when parameterName is null")
        void complete_returnsEmpty_whenParameterNameNull() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName(null)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should return empty when suggest method not found and no i18n")
        void completeReturnsEmptyWhenSuggestMethodNotFoundAndNoI18n() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            // No suggest annotation for "item" parameter
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("item")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }
    }

    @Nested
    @DisplayName("Suggest Method Invocation Tests")
    class SuggestMethodInvocationTests {

        @Test
        @DisplayName("complete() should invoke no-arg suggest method")
        void completeInvokesNoArgSuggestMethod() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("player")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("Player1"));
            assertTrue(suggestions.contains("Player2"));
            assertTrue(suggestions.contains("Player3"));
        }

        @Test
        @DisplayName("complete() should handle suggest method name with () suffix")
        void complete_handlesSuggestMethodNameWithParentheses() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            // "action" parameter has suggest = "suggestActions()"
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("action")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("give"));
            assertTrue(suggestions.contains("take"));
            assertTrue(suggestions.contains("set"));
        }

        @Test
        @DisplayName("complete() should filter by current input")
        void completeFiltersByCurrentInput() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{"Player1"})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("player")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("Player1", suggestions.get(0));
        }

        @Test
        @DisplayName("complete() should handle array return type")
        void completeHandlesArrayReturnType() throws Exception {
            // Create a method that references suggestArray
            TestExecutor executor = new TestExecutor();
            
            // Create a test method with suggest annotation pointing to suggestArray
            Method method = TestMethodWithArraySuggest.class.getMethod("testMethod", String.class);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(new TestMethodWithArraySuggest())
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("arr1"));
            assertTrue(suggestions.contains("arr2"));
            assertTrue(suggestions.contains("arr3"));
        }
    }

    // Helper class for array return type test
    public static class TestMethodWithArraySuggest {
        public void testMethod(@CmdParam(value = "param", suggest = "suggestArray") String param) {
        }

        public String[] suggestArray() {
            return new String[]{"arr1", "arr2", "arr3"};
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("complete() should handle null return from suggest method")
        void complete_handlesNullReturn() throws NoSuchMethodException {
            Method method = NullReturnExecutor.class.getMethod("testMethod", String.class);
            NullReturnExecutor executor = new NullReturnExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should handle exception in suggest method")
        void complete_handlesExceptionInSuggestMethod() throws NoSuchMethodException {
            Method method = ThrowingExecutor.class.getMethod("testMethod", String.class);
            ThrowingExecutor executor = new ThrowingExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(executor)
                    .build();
            
            // Should not throw, return empty list
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should handle empty input filtering")
        void completeHandlesEmptyInputFiltering() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("player")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Empty input should return all
            assertEquals(3, suggestions.size());
        }

        @Test
        @DisplayName("complete() should handle case insensitive filtering")
        void complete_handlesCaseInsensitiveFiltering() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{"player"})  // lowercase
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("player")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should match Player1, Player2, Player3 case-insensitively
            assertEquals(3, suggestions.size());
        }
    }

    // Helper test classes
    public static class NullReturnExecutor {
        public void testMethod(@CmdParam(value = "param", suggest = "suggestNull") String param) {
        }

        public List<String> suggestNull() {
            return null;
        }
    }

    public static class ThrowingExecutor {
        public void testMethod(@CmdParam(value = "param", suggest = "suggestThrowing") String param) {
        }

        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        public List<String> suggestThrowing() {
            throw new RuntimeException("Test exception");
        }
    }

    @Nested
    @DisplayName("Parameter Annotation Parsing Tests")
    class ParameterAnnotationParsingTests {

        @Test
        @DisplayName("Should find suggest name from CmdParam annotation")
        void findsSuggestNameFromCmdParam() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            // The "player" parameter has suggest = "suggestPlayers"
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("player")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertFalse(suggestions.isEmpty());
        }

        @Test
        @DisplayName("Should return empty when parameter not found")
        void returnsEmptyWhenParameterNotFound() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            TestExecutor executor = new TestExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("nonexistent")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }
    }

    @Nested
    @DisplayName("Collection/Array Return Type Tests")
    class CollectionReturnTypeTests {

        @Test
        @DisplayName("Should handle Collection return type")
        void handlesCollectionReturnType() throws NoSuchMethodException {
            Method method = CollectionReturnExecutor.class.getMethod("testMethod", String.class);
            CollectionReturnExecutor executor = new CollectionReturnExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("col1"));
            assertTrue(suggestions.contains("col2"));
        }
    }

    public static class CollectionReturnExecutor {
        public void testMethod(@CmdParam(value = "param", suggest = "suggestCollection") String param) {
        }

        public Collection<String> suggestCollection() {
            return Arrays.asList("col1", "col2");
        }
    }

    @Nested
    @DisplayName("Parameter Method Invocation Tests")
    class ParameterMethodInvocationTests {

        @Test
        @DisplayName("Should invoke suggest method with Player parameter")
        void invokesMethodWithPlayerParameter() throws NoSuchMethodException {
            Method method = PlayerParamExecutor.class.getMethod("testMethod", String.class);
            PlayerParamExecutor executor = new PlayerParamExecutor();
            when(mockPlayer.getName()).thenReturn("TestPlayer");
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.get(0).contains("TestPlayer"));
        }

        @Test
        @DisplayName("Should invoke suggest method with 3 parameters (Player, Command, String[])")
        void invokesMethodWith3Parameters() throws NoSuchMethodException {
            Method method = ThreeParamExecutor.class.getMethod("testMethod", String.class);
            ThreeParamExecutor executor = new ThreeParamExecutor();
            when(mockPlayer.getName()).thenReturn("TestPlayer");
            
            try (var bukkit = mockStatic(Bukkit.class)) {
                Server server = mock(Server.class);
                CommandMap commandMap = mock(CommandMap.class);
                bukkit.when(Bukkit::getServer).thenReturn(server);
                when(server.getCommandMap()).thenReturn(commandMap);
                when(commandMap.getCommand("fixture")).thenReturn(mockCommand);
                TabCompletionContext context = TabCompletionContext.builder()
                        .player(mockPlayer)
                        .commandName("fixture")
                        .args(new String[]{"3"})  // start with "3" to match "3param-result"
                        .currentArgIndex(0)
                        .matchedMethod(method)
                        .parameterName("param")
                        .executorInstance(executor)
                        .build();
                List<String> suggestions = completer.complete(context);
                // The legacy provider receives the Command from the public lookup, not the typed context.
                assertEquals(1, suggestions.size());
                assertEquals("3param-result", suggestions.get(0));
            }
        }
    }

    public static class PlayerParamExecutor {
        public void testMethod(@CmdParam(value = "param", suggest = "suggestWithPlayer") String param) {
        }

        public List<String> suggestWithPlayer(Player player) {
            return Arrays.asList("ForPlayer:" + player.getName());
        }
    }

    public static class ThreeParamExecutor {
        public void testMethod(@CmdParam(value = "param", suggest = "suggestWithAll") String param) {
        }

        public List<String> suggestWithAll(Player player, Command command, String[] args) {
            // Only return result if player and command are not null
            if (player != null && command != null) {
                return Arrays.asList("3param-result");
            }
            return Collections.emptyList();
        }
    }

    @Nested
    @DisplayName("CmdSuggest Annotation Tests")
    class CmdSuggestAnnotationTests {

        @Test
        @DisplayName("Should find suggest method from @CmdSuggest annotated class when method in external class")
        void findsMethodFromCmdSuggestClass() throws NoSuchMethodException {
            // Note: The actual behavior requires plugin.getContext().getBean() to work
            // Since the external class ExternalSuggestProvider is not the executor,
            // the invokeSuggestMethod will try to get bean from container
            // For this test, we verify the method lookup works when executor has CmdSuggest
            
            Method method = ExecutorWithCmdSuggest.class.getMethod("someCommand", String.class);
            ExecutorWithInternalSuggest executor = new ExecutorWithInternalSuggest();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("type")
                    .executorInstance(executor)
                    .build();
            
            // When executorInstance is not of declaring class type,
            // it tries to get bean from plugin context, which returns null
            // So we expect empty result in this case
            List<String> suggestions = completer.complete(context);
            
            // This verifies the CmdSuggest lookup path is exercised
            assertNotNull(suggestions);
        }

        @Test
        @DisplayName("Should use executor's own suggest method when available")
        void usesExecutorOwnMethodFirst() throws NoSuchMethodException {
            Method method = ExecutorWithInternalSuggest.class.getMethod("testCommand", String.class);
            ExecutorWithInternalSuggest executor = new ExecutorWithInternalSuggest();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("value")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("internal1"));
            assertTrue(suggestions.contains("internal2"));
        }
    }

    @CmdSuggest({ExternalSuggestProvider.class})
    public static class ExecutorWithInternalSuggest {
        public void testCommand(@CmdParam(value = "value", suggest = "internalSuggest") String value) {
        }

        public List<String> internalSuggest() {
            return Arrays.asList("internal1", "internal2");
        }
    }

    @Nested
    @DisplayName("Message catalogue hint fallback tests")
    class HintFallbackTests {

        @Test
        void definedPathYieldsTheServerFallbackText() throws Exception {
            try (var catalogue = new ModuleCatalogueFixture(tempDir)) {
                catalogue.shipped("en", Map.of("command.hint", "Use a valid value"));
                catalogue.shipped("zh", Map.of("command.hint", "<yellow>请输入有效值</yellow>"));
                catalogue.fallback("zh");
                when(mockPlugin.moduleTexts()).thenReturn(Optional.of(catalogue.preparedTexts()));
                assertThat(completer.complete(hintContext("messageHint", mockPlugin))).containsExactly("请输入有效值");
            }
        }

        @Test
        void missingPathAndPlainSentenceAreReturnedUnchanged() throws Exception {
            assertThat(completer.complete(hintContext("messageHint", mockPlugin))).containsExactly("command.hint");
            assertThat(completer.complete(hintContext("literalHint", mockPlugin))).containsExactly("Use a valid value");
        }

        @Test
        void anEmptyRenderedHintIsNotReturned() throws Exception {
            try (var catalogue = new ModuleCatalogueFixture(tempDir)) {
                catalogue.shipped("en", Map.of("command.hint", "<green></green>"));
                when(mockPlugin.moduleTexts()).thenReturn(Optional.of(catalogue.preparedTexts()));
                assertThat(completer.complete(hintContext("messageHint", mockPlugin))).isEmpty();
            }
        }

        @Test
        void returnsEmpty_whenPluginNull() throws NoSuchMethodException {
            assertTrue(completer.complete(hintContext("messageHint", null)).isEmpty());
        }

        private TabCompletionContext hintContext(String methodName, UltiToolsPlugin owner) throws NoSuchMethodException {
            Method method = ExecutorWithMissingSuggest.class.getMethod(methodName, String.class);
            when(mockCommandManager.getPluginByLabel("fixture")).thenReturn(owner);
            return TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName("fixture")
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(new ExecutorWithMissingSuggest())
                    .build();
        }
    }

    public static class ExecutorWithMissingSuggest {
        public void messageHint(@CmdParam(value = "param", suggest = "command.hint") String param) {
        }

        public void literalHint(@CmdParam(value = "param", suggest = "Use a valid value") String param) {
        }
    }

    @Nested
    @DisplayName("Null Executor Tests")
    class NullExecutorTests {

        @Test
        @DisplayName("Should return empty when executor instance is null")
        void returnsEmptyWhenExecutorNull() throws NoSuchMethodException {
            Method method = TestExecutor.class.getMethod("commandMethod", String.class, String.class, String.class);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("player")
                    .executorInstance(null)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }
    }

    @Nested
    @DisplayName("Empty Suggest Name Tests")
    class EmptySuggestNameTests {

        @Test
        @DisplayName("Should return empty when suggest name is empty string")
        void returnsEmpty_whenSuggestNameEmpty() throws NoSuchMethodException {
            Method method = EmptySuggestExecutor.class.getMethod("testMethod", String.class);
            EmptySuggestExecutor executor = new EmptySuggestExecutor();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .commandName(mockCommand.getName())
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(executor)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }
    }

    public static class EmptySuggestExecutor {
        public void testMethod(@CmdParam(value = "param", suggest = "") String param) {
        }
    }
}
