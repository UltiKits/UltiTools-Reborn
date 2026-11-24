package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.util.List;
import java.util.logging.Logger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.junit.jupiter.api.Test;

class ProxyTreeFactoryTest {
    @Test void executionSuggestionsAndVisibilityReadTheCurrentBinding() throws Exception {
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        Object owner = new Object();
        bindings.bind(owner, "test", owner, suggested("old", 1));
        RecordingCommands commands = new RecordingCommands();
        bindings.onCommands(commands);
        var source = mock(CommandSourceStack.class);
        var dispatcher = commands.getDispatcher();
        assertThat(dispatcher.execute("test old", source)).isEqualTo(1);
        assertThat(dispatcher.getCompletionSuggestions(dispatcher.parse("test ", source)).get().getList())
                .extracting(s -> s.getText()).contains("old");
        bindings.unbind(owner);
        bindings.bind(owner, "test", new Object(), suggested("new", 2));
        assertThat(dispatcher.execute("test new", source)).isEqualTo(2);
        assertThat(dispatcher.getCompletionSuggestions(dispatcher.parse("test ", source)).get().getList())
                .extracting(s -> s.getText()).containsExactly("new");
        bindings.unbind(owner);
        assertThat(dispatcher.getCompletionSuggestions(dispatcher.parse("test ", source)).get().getList()).isEmpty();
    }

    @Test void redirectInsideTheTreeTargetsAProxyAndExternalTargetsRemainUnchanged() {
        var target = LiteralArgumentBuilder.<CommandSourceStack>literal("target").executes(c -> 1).build();
        var external = LiteralArgumentBuilder.<CommandSourceStack>literal("external").executes(c -> 2).build();
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal("test").then(target)
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("again").redirect(target))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("outside").redirect(external)).build();
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        var proxy = new ProxyTreeFactory(bindings).create("test", root);
        assertThat(proxy.getChild("again").getRedirect()).isSameAs(proxy.getChild("target"));
        assertThat(proxy.getChild("again").getRedirect()).isNotSameAs(target);
        assertThat(proxy.getChild("outside").getRedirect()).isSameAs(external);
    }

    @Test void aRedirectBackToTheRootDoesNotRecurseWhileCloning() {
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal("test").build();
        root.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("again").redirect(root).build());
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        var proxy = new ProxyTreeFactory(bindings).create("test", root);
        assertThat(proxy.getChild("again").getRedirect()).isSameAs(proxy);
    }

    @Test void aPathMissingFromTheCurrentTreeIsUnavailableAndDoesNotSuggest() throws Exception {
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        var root = suggested("old", 1).tree();
        var proxy = new ProxyTreeFactory(bindings).create("test", root);
        Object owner = new Object();
        bindings.bind(owner, "test", owner, CommandBindingsTest.command("test", "other", 2));
        bindings.onCommands(new RecordingCommands());
        var source = mock(CommandSourceStack.class);
        var value = proxy.getChild("value");
        assertThat(value.canUse(source)).isFalse();
        var dispatcher = new RecordingCommands().getDispatcher();
        var context = new CommandContextBuilder<>(dispatcher, source, dispatcher.getRoot(), 0).build("test ");
        assertThat(value.listSuggestions(context, new SuggestionsBuilder("test ", 5)).get().getList()).isEmpty();
    }

    private static CompiledCommand suggested(String suggestion, int result) {
        LiteralCommandNode<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("test")
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("value", StringArgumentType.word())
                        .suggests((context, builder) -> builder.suggest(suggestion).buildFuture())
                        .executes(context -> result)).build();
        return new CompiledCommand("test", List.of(), "test", root, "same");
    }
}
