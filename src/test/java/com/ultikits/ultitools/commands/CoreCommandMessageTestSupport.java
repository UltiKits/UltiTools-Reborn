package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.text.Messages;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Real catalogues reached through the same root-container lookup as production commands. */
final class CoreCommandMessageTestSupport implements AutoCloseable {
    final UltiTools plugin = mock(UltiTools.class);
    final Logger logger = mock(Logger.class);
    private final CommandMessageTestSupport support;
    private final MockedStatic<UltiTools> singleton;

    CoreCommandMessageTestSupport(String language) {
        support = new CommandMessageTestSupport(language);
        var dependencies = mock(DependenceManagers.class);
        var context = mock(SimpleContainer.class);
        when(plugin.getDependenceManagers()).thenReturn(dependencies);
        when(dependencies.getContext()).thenReturn(context);
        when(context.getBean(Messages.class)).thenReturn(support.messages());
        when(plugin.getLogger()).thenReturn(logger);
        singleton = mockStatic(UltiTools.class);
        singleton.when(UltiTools::getInstance).thenReturn(plugin);
    }

    static List<Component> components(CommandSender sender, int count) {
        var captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, times(count)).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    static List<Component> assertLines(CommandSender sender, String... expected) {
        List<Component> lines = components(sender, expected.length);
        assertThat(lines).extracting(CommandMessageTestSupport::plain).containsExactly(expected);
        verify(sender, times(0)).sendMessage(any(String.class));
        return lines;
    }

    static void assertColour(Component line, TextColor expected) {
        assertThat(firstColour(line)).isEqualTo(expected);
    }

    private static TextColor firstColour(Component line) {
        if (line.color() != null) {
            return line.color();
        }
        for (Component child : line.children()) {
            TextColor colour = firstColour(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }

    @Override
    public void close() {
        singleton.close();
        support.close();
    }
}
