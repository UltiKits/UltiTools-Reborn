package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;

class CommandCompilerDuplicatesTest {
    private final CommandTreeCompiler compiler = new CommandTreeCompiler(new StringNativeArgumentTypes());

    @Test void identicalFormatsInOneClassCannotDisappearInTheEffectiveMappingMap() {
        assertThatThrownBy(() -> compile(new Duplicate())).isInstanceOf(CommandCompilationException.class)
                .hasMessageContaining("duplicate").hasMessageContaining("one").hasMessageContaining("two");
    }

    @Test void anInheritedFormatCanStillBeShadowedByTheSubclass() {
        var compiled = compile(new Child());
        var mapping = (CommandTreeCompiler.MappingCommand) compiled.tree().getChild("same").getCommand();
        assertThat(mapping.method().getDeclaringClass()).isEqualTo(Child.class);
        assertThat(mapping.method().getName()).isEqualTo("replacement");
    }

    private CompiledCommand compile(BaseCommandExecutor executor) {
        return compiler.compile(executor, "test", (context, method, values) -> 1, context -> 1);
    }

    @CmdExecutor(alias = "test") static class Duplicate extends BaseCommandExecutor {
        Duplicate() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "same") public void one() { /* Compilation fixture. */ }
        @CmdMapping(format = "same") public void two() { /* Compilation fixture. */ }
        @Override protected void handleHelp(CommandSender sender) { /* Compilation fixture. */ }
    }
    @CmdExecutor(alias = "test") static class Parent extends BaseCommandExecutor {
        Parent() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "same") public void inherited() { /* Compilation fixture. */ }
        @Override protected void handleHelp(CommandSender sender) { /* Compilation fixture. */ }
    }
    @CmdExecutor(alias = "test") static class Child extends Parent {
        @CmdMapping(format = "same") public void replacement() { /* Compilation fixture. */ }
    }
}
