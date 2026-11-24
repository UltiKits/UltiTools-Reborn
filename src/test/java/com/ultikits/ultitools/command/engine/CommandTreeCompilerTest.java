package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.mojang.brigadier.CommandDispatcher;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.utils.MockBukkitHelper;

import io.papermc.paper.command.brigadier.CommandSourceStack;

class CommandTreeCompilerTest {
    private final CommandTreeCompiler compiler = new CommandTreeCompiler(new StringNativeArgumentTypes());

    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void literalAndArgumentShareTheSameParent() {
        var command = compiler.compile(new Good(), "test", (context, method, values) -> 1, context -> 1);
        var info = command.tree().getChild("info");
        assertThat(info.getChildren()).extracting(node -> node.getName()).containsExactly("all", "player");
        assertThat(command.signature()).isEqualTo(compiler.compile(new Good(), "test",
                (context, method, values) -> 1, context -> 1).signature());
    }

    @Test void greedyParentExecutesWithAnEmptyTypedArray() throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var command = compiler.compile(new Good(), "test", (context, method, values) -> {
            assertThat(method.getName()).isEqualTo("say");
            assertThat((String[]) values.get("words")).isEmpty();
            return 17;
        }, context -> 1);
        dispatcher.getRoot().addChild(command.tree());
        assertThat(dispatcher.execute("test say", mock(CommandSourceStack.class))).isEqualTo(17);
    }

    @Test void competingArgumentsNameBothMethodsInTheDiagnostic() {
        assertThatThrownBy(() -> compile(new Competing())).isInstanceOf(CommandCompilationException.class)
                .hasMessageContaining(Competing.class.getName()).hasMessageContaining("one")
                .hasMessageContaining("two");
    }

    @Test void duplicateNormalizedPathIsRejected() { assertRejected(new Duplicate(), "duplicate"); }
    @Test void nonTerminalGreedyIsRejected() { assertRejected(new MiddleGreedy(), "last"); }
    @Test void unmappedTypeIsRejected() { assertRejected(new Unmapped(), "type"); }
    @Test void parameterAbsentFromFormatIsRejected() { assertRejected(new Absent(), "value"); }

    @Test void aDifferentArgumentMappingChangesTheSignature() {
        assertThat(compile(new IntegerCommand()).signature()).isNotEqualTo(compile(new StringCommand()).signature());
    }

    private CompiledCommand compile(BaseCommandExecutor executor) {
        return compiler.compile(executor, "test", (context, method, values) -> 1, context -> 1);
    }
    private void assertRejected(BaseCommandExecutor executor, String text) {
        assertThatThrownBy(() -> compile(executor)).isInstanceOf(CommandCompilationException.class)
                .hasMessageContaining(executor.getClass().getName()).hasMessageContaining(text);
    }

    abstract static class Fixture extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { /* Not used by compiler tests. */ }
    }
    @CmdExecutor(alias = "test") static class Good extends Fixture {
        @CmdMapping(format = "info <player>") public void info(@CmdParam("player") String player) { /* Fixture. */ }
        @CmdMapping(format = "info all") public void all() { /* Fixture. */ }
        @CmdMapping(format = "say <words...>") public void say(@CmdParam("words") String[] words) { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class Competing extends Fixture {
        @CmdMapping(format = "info <a>") public void one(@CmdParam("a") String a) { /* Fixture. */ }
        @CmdMapping(format = "info <b>") public void two(@CmdParam("b") int b) { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class Duplicate extends Fixture {
        @CmdMapping(format = "info  all") public void one() { /* Fixture. */ }
        @CmdMapping(format = "info all") public void two() { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class MiddleGreedy extends Fixture {
        @CmdMapping(format = "say <words...> end") public void say(@CmdParam("words") String[] words) { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class Unmapped extends Fixture {
        @CmdMapping(format = "get <value>") public void get(@CmdParam("value") List<?> value) { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class Absent extends Fixture {
        @CmdMapping(format = "get") public void get(@CmdParam("value") String value) { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class IntegerCommand extends Fixture {
        @CmdMapping(format = "get <value>") public void get(@CmdParam("value") int value) { /* Fixture. */ }
    }
    @CmdExecutor(alias = "test") static class StringCommand extends Fixture {
        @CmdMapping(format = "get <value>") public void get(@CmdParam("value") String value) { /* Fixture. */ }
    }
}
