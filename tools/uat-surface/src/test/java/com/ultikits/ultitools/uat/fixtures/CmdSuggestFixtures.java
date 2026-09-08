package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdSuggest;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Exercises the three ways {@code CmdParam.suggest()} can resolve (Codex review, restructure
 * head): a {@code "@key"} built-in completer (no {@code @CmdSuggest} involvement at all), a plain
 * method name found on the executor class itself (again, no fall-through), and a plain method
 * name found only via the class-level {@code @CmdSuggest}'s declared provider classes -- the
 * case whose provider-class list previously had no surface representation at all.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"suggestfix"})
@CmdSuggest({SuggestProviderOne.class, SuggestProviderTwo.class})
public class CmdSuggestFixtures extends BaseCommandExecutor {

    @CmdMapping(format = "builtin <arg>")
    public void withBuiltInKey(@CmdSender CommandSender sender, @CmdParam(value = "arg", suggest = "@players") String arg) {
        // no-op: the scanner reads the annotation, never invokes this method
    }

    @CmdMapping(format = "own <arg>")
    public void withOwnMethod(@CmdSender CommandSender sender, @CmdParam(value = "arg", suggest = "ownSuggestions") String arg) {
        // no-op: the scanner reads the annotation, never invokes this method
    }

    public List<String> ownSuggestions() {
        return Collections.emptyList();
    }

    @CmdMapping(format = "delegated <arg>")
    public void withDelegatedMethod(@CmdSender CommandSender sender, @CmdParam(value = "arg", suggest = "providerOneSuggestions") String arg) {
        // no-op: the scanner reads the annotation, never invokes this method
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("suggestfix help");
    }
}

class SuggestProviderOne {
    public List<String> providerOneSuggestions() {
        return Collections.emptyList();
    }
}

class SuggestProviderTwo {
}
