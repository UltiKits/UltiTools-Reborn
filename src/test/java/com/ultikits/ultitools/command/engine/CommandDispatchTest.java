package com.ultikits.ultitools.command.engine;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.annotations.command.CmdSender;
import io.papermc.paper.command.brigadier.CommandSourceStack;

@ExtendWith(CommandTestExtension.class)
class CommandDispatchTest {
    @Test void passesTypedValuesAndTheActualSenderThroughTheDispatcher() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Player sender = mock(Player.class);
            when(sender.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
            Fixture executor = new Fixture();
            assertThat(dispatcher(executor).execute("test count 5", source(sender))).isEqualTo(1);
            assertThat(executor.count).isEqualTo(5);
            assertThat(executor.sender).isSameAs(sender);
        }
    }
    @Test void refusalNotifiesPassedValidatorsInOrderWithoutCallingTheMethod() throws Exception {
        List<String> events = new ArrayList<>();
        Fixture executor = new Fixture(ValidatorChain.builder().add(validator("one", true, events))
                .add(validator("two", false, events)).add(validator("three", true, events)).build());
        assertThat(dispatcher(executor).execute("test count 5", source(mock(Player.class)))).isEqualTo(1);
        assertThat(executor.count).isNull();
        assertThat(events).containsExactly("validate one", "validate two", "refused one");
    }
    @Test void runAsyncUsesADifferentThread() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            UltiTools plugin = mock(UltiTools.class);
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var async = CommandRuntime.scheduler().async();
            AtomicReference<Thread> invoked = new AtomicReference<>();
            doAnswer(call -> {
                Runnable task = call.getArgument(0);
                Thread thread = new Thread(task, "command-test-worker");
                thread.start();
                thread.join(5000);
                assertThat(thread.isAlive()).isFalse();
                invoked.set(thread);
                return mock(TaskHandle.class);
            }).when(async).run(any(Runnable.class));
            Fixture executor = new Fixture();
            assertThat(dispatcher(executor).execute("test async", source(mock(CommandSender.class)))).isEqualTo(1);
            assertThat(executor.thread).isSameAs(invoked.get()).isNotSameAs(Thread.currentThread());
        }
    }
    static com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher(BaseCommandExecutor executor) {
        var compiler = new CommandTreeCompiler(new StringNativeArgumentTypes());
        var compiled = compiler.compile(executor, "test", new CommandInvocation(executor)::run,
                context -> new CommandInvocation(executor).help(context));
        var commands = new RecordingCommands();
        commands.register(compiled.tree());
        return commands.getDispatcher();
    }
    static CommandSourceStack source(CommandSender sender) {
        var source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }
    private static CommandValidator validator(String name, boolean pass, List<String> events) {
        return new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) {
                events.add("validate " + name);
                return pass ? ValidationResult.success() : ValidationResult.failure("denied");
            }
            @Override public void onRefused(CommandContext context) { events.add("refused " + name); }
        };
    }
    @CmdExecutor(alias = "test") static class Fixture extends BaseCommandExecutor {
        Integer count;
        Player sender;
        Thread thread;
        Fixture() { super(ValidatorChain.builder().build()); }
        Fixture(ValidatorChain chain) { super(chain); }
        @CmdMapping(format = "count <n>") public void count(@CmdSender Player player, @CmdParam("n") Integer n) {
            count = n;
            sender = player;
        }
        @RunAsync @CmdMapping(format = "async") public void async() { thread = Thread.currentThread(); }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }
}
