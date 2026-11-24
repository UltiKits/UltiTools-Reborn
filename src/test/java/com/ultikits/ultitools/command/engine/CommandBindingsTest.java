package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.junit.jupiter.api.Test;

class CommandBindingsTest {
    @Test void eventRegistersAllRootsAndUnbindHidesAndRefusesTheExistingProxy() throws Exception {
        CommandBindings bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        Object owner = new Object();
        bindings.bind(owner, "module", owner, command("probe", "same", 7));
        bindings.bind(owner, "module", owner, command("other", "same", 9));
        RecordingCommands commands = new RecordingCommands();
        bindings.onCommands(commands);
        CommandSourceStack source = mock(CommandSourceStack.class);
        assertThat(commands.registrations).isEqualTo(2);
        assertThat(commands.getDispatcher().execute("probe ping", source)).isEqualTo(7);
        bindings.unbind(owner);
        assertThat(commands.getDispatcher().getRoot().getChild("probe").canUse(source)).isFalse();
        assertThatThrownBy(() -> commands.getDispatcher().execute("probe ping", source))
                .isInstanceOf(CommandSyntaxException.class);
    }

    @Test void sameSignatureRebindsToNewInstanceWithoutASecondEvent() throws Exception {
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        Object owner = new Object();
        bindings.bind(owner, "module", new Object(), command("probe", "same", 1));
        RecordingCommands commands = new RecordingCommands();
        bindings.onCommands(commands);
        bindings.unbind(owner);
        bindings.bind(owner, "module", new Object(), command("probe", "same", 2));
        assertThat(commands.registrations).isEqualTo(1);
        assertThat(commands.getDispatcher().execute("probe ping", mock(CommandSourceStack.class))).isEqualTo(2);
    }

    @Test void changedSignatureWaitsForTheNextEventAndWarnsExactlyOnce() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        List<String> warnings = capture(logger);
        var bindings = new CommandBindings(logger, () -> {});
        Object owner = new Object();
        bindings.bind(owner, "module", owner, command("probe", "one", 1));
        bindings.onCommands(new RecordingCommands());
        bindings.unbind(owner);
        bindings.bind(owner, "module", owner, command("probe", "two", 2));
        assertThat(warnings).containsExactly("Command /probe from module was registered after start-up; it becomes available after /minecraft:reload or a restart.");
        RecordingCommands reload = new RecordingCommands();
        bindings.onCommands(reload);
        assertThat(reload.getDispatcher().execute("probe ping", mock(CommandSourceStack.class))).isEqualTo(2);
    }

    @Test void aDifferentOwnerCannotTakeTheRootOrReleaseItsBinding() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        var messages = capture(logger);
        var bindings = new CommandBindings(logger, () -> {});
        Object owner = new Object();
        Object intruder = new Object();
        assertThat(bindings.bind(owner, "first", owner, command("probe", "same", 1))).isTrue();
        assertThat(bindings.bind(intruder, "second", intruder, command("probe", "same", 2))).isFalse();
        bindings.unbind(intruder);
        RecordingCommands commands = new RecordingCommands();
        bindings.onCommands(commands);
        assertThat(commands.getDispatcher().execute("probe ping", mock(CommandSourceStack.class))).isEqualTo(1);
        assertThat(messages).anyMatch(s -> s.contains("first") && s.contains("second") && s.contains("java.lang.Object"));
    }

    @Test void ownershipConflictsCoverPrimaryAgainstAliasAliasAgainstPrimaryAndAliasAgainstAlias() {
        for (String spelling : List.of("probe", "alias")) {
            Logger logger = Logger.getAnonymousLogger();
            List<String> messages = capture(logger);
            var bindings = new CommandBindings(logger, () -> { /* No client synchronization. */ });
            Object owner = new Object();
            Object intruder = new Object();
            CompiledCommand first = command("probe", "same", 1);
            assertThat(bindings.bind(owner, "first", owner, new CompiledCommand("probe", List.of("alias"),
                    first.description(), first.tree(), first.signature()))).isTrue();
            assertThat(bindings.bind(intruder, "second", intruder, command(spelling, "same", 2))).isFalse();
            CompiledCommand other = command("other", "same", 3);
            assertThat(bindings.bind(intruder, "second", intruder, new CompiledCommand("other", List.of(spelling),
                    other.description(), other.tree(), other.signature()))).isFalse();
            assertThat(bindings.ownerByLabel(spelling)).isSameAs(owner);
            assertThat(bindings.ownerByLabel("other")).isNull();
            assertThat(messages).hasSize(2).allMatch(message -> message.contains("first") && message.contains("second"));
        }
    }

    @Test void freshRootsAfterStartWaitForAnEventAndCloseReleasesAllOwners() {
        Logger logger = Logger.getAnonymousLogger();
        var messages = capture(logger);
        var bindings = new CommandBindings(logger, () -> {});
        bindings.onCommands(new RecordingCommands());
        Object owner = new Object();
        bindings.bind(owner, "late", owner, command("probe", "same", 1));
        assertThat(messages).hasSize(1);
        assertThat(bindings.find("probe")).isNull();
        RecordingCommands reload = new RecordingCommands();
        bindings.onCommands(reload);
        assertThat(bindings.find("probe")).isNotNull();
        bindings.clear();
        assertThat(reload.getDispatcher().getRoot().getChild("probe").canUse(mock(CommandSourceStack.class))).isFalse();
    }

    static CompiledCommand command(String name, String signature, int result) {
        return new CompiledCommand(name, List.of(), "test", LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("ping").executes(context -> result)).build(), signature);
    }
    private static List<String> capture(Logger logger) {
        List<String> result = new ArrayList<>();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { result.add(record.getMessage()); }
            @Override public void flush() { /* No buffered output. */ }
            @Override public void close() { /* In-memory handler. */ }
        });
        return result;
    }
}
