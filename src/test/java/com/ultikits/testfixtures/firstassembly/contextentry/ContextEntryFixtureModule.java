package com.ultikits.testfixtures.firstassembly.contextentry;

import java.util.Collections;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;

/**
 * C62 fixture: the module declares {@code @ContextEntry} on {@link EntryBean}, whose
 * {@code @PostConstruct} registers a tab completer under {@link #COMPLETER_KEY}. The framework
 * assembles that bean while it loads the module, so the completer must be recorded against this
 * module instance and leave with it.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.firstassembly.contextentry")
@ContextEntry(ContextEntryFixtureModule.EntryBean.class)
public class ContextEntryFixtureModule extends UltiToolsPlugin {

    /** The completer key, unique to this fixture. */
    public static final String COMPLETER_KEY = "@first-assembly-context-entry";

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** The {@code @ContextEntry} target; not a component, so the scan does not register it. */
    public static class EntryBean {

        @PostConstruct
        void registerCompleter() {
            TabCompletionManager.getInstance().register(COMPLETER_KEY,
                    context -> Collections.singletonList("context-entry"));
        }
    }
}
