package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandKeys;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import com.ultikits.ultitools.abstracts.command.parser.TypeParseException;
import com.ultikits.ultitools.abstracts.command.parser.TypeParser;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.Text;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(CommandTestExtension.class)
class FrameworkRepliesMessagesTest {
    @Test
    void unknownReplyCarriesTheLabelAndHelpThenCallsModuleHelp() throws Exception {
        var executor = new Fixture();
        var sender = mock(CommandSender.class);
        assertThat(CommandTestSupport.dispatcher(executor, "probe").execute("probe nosuch", CommandTestSupport.source(sender)))
                .isEqualTo(1);
        verify(CommandRuntime.messages()).send(sender, Text.of(CommandKeys.UNKNOWN,
                Arg.string("label", "probe"), Arg.string("help", "help")));
        assertThat(executor.helpCalls).isEqualTo(1);
    }

    @Test
    void usageKeepsAngleBracketsInTheFormatArgument() throws Exception {
        var sender = mock(CommandSender.class);
        assertThat(CommandTestSupport.dispatcher(new Fixture(), "probe").execute("probe give", CommandTestSupport.source(sender)))
                .isEqualTo(1);
        verify(CommandRuntime.messages()).send(sender, Text.of(CommandKeys.USAGE,
                Arg.string("label", "probe"), Arg.string("format", "give <target>")));
    }

    @Test
    void callbackParserFailureUsesTheOriginalMessageComponent() throws Exception {
        var sender = mock(CommandSender.class);
        var serializer = mock(MessageComponentSerializer.class);
        var component = Component.translatable("argument.player.unknown");
        when(serializer.deserialize(any())).thenReturn(component);
        try (var service = mockStatic(MessageComponentSerializer.class)) {
            service.when(MessageComponentSerializer::message).thenReturn(serializer);
            var commands = repliesWithParserError();
            assertThat(commands.execute("probe invoke", CommandTestSupport.source(sender))).isEqualTo(1);
            verify(CommandRuntime.messages()).send(sender, Text.of(CommandKeys.ARGUMENT_ERROR, Arg.component("error", component)));
        }
    }

    @Test
    void recoveryWalkKeepsTheRawParserMessageAndFallsBackToLiteralText() throws Exception {
        var sender = mock(CommandSender.class);
        try (var service = mockStatic(MessageComponentSerializer.class)) {
            service.when(MessageComponentSerializer::message).thenThrow(new IllegalStateException("service unavailable"));
            assertThat(repliesWithParserError().execute("probe typed bad", CommandTestSupport.source(sender))).isEqualTo(1);
            verify(CommandRuntime.messages()).send(sender, Text.of(CommandKeys.ARGUMENT_ERROR,
                    Arg.string("error", "Cannot parse 'bad' as Value: invalid format")));
        }
    }

    @Test
    void callbackParserFailureAlsoFallsBackWithoutParsingItsTextAsATemplate() throws Exception {
        var sender = mock(CommandSender.class);
        try (var service = mockStatic(MessageComponentSerializer.class)) {
            service.when(MessageComponentSerializer::message).thenThrow(new IllegalStateException("service unavailable"));
            assertThat(repliesWithParserError().execute("probe invoke", CommandTestSupport.source(sender))).isEqualTo(1);
            verify(CommandRuntime.messages()).send(sender, Text.of(CommandKeys.ARGUMENT_ERROR, Arg.string("error", "literal <red>")));
        }
    }

    private static CommandDispatcher<CommandSourceStack> repliesWithParserError() throws TypeParseException {
        TypeParser<Value> parser = new ValueParser();
        var tree = LiteralArgumentBuilder.<CommandSourceStack>literal("probe")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("invoke").executes(context -> {
                    throw new TypeParserArgumentType.ParserFailure("literal <red>").create();
                }))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("typed")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Value>argument("value", new TypeParserArgumentType<>(parser))))
                .build();
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.getRoot().addChild(new FrameworkReplies(new Fixture(), tree, dispatcher::execute).tree());
        return dispatcher;
    }

    static class Value { }

    private static final class ValueParser implements TypeParser<Value> {
        @Override
        public Class<Value> getPrimaryType() {
            return Value.class;
        }
        @Override
        public List<Class<?>> getSupportedTypes() {
            return List.of(Value.class);
        }
        @Override
        public Value parse(String value) throws TypeParseException {
            throw TypeParseException.invalidFormat(value, Value.class);
        }
    }

    static class Fixture extends BaseCommandExecutor {
        int helpCalls;
        Fixture() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "give <target>")
        public void give(@CmdParam("target") String target) { /* Metadata for the usage reply. */ }
        @Override protected void handleHelp(CommandSender sender) { helpCalls++; }
    }
}
