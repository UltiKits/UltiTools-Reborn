package com.ultikits.testfixtures.firstassembly.contextentryrefused;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;

/**
 * C62 fixture: the same shape as {@code ContextEntryFixtureModule}, with its own completer key,
 * but {@code registerSelf()} returns {@code false}, so the framework refuses the module after it
 * assembled the {@code @ContextEntry} bean. The completer that bean registered must not survive
 * the refusal.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.firstassembly.contextentryrefused")
@ContextEntry(RefusedContextEntryFixtureModule.EntryBean.class)
public class RefusedContextEntryFixtureModule extends UltiToolsPlugin {

    /** The completer key, unique to this fixture. */
    public static final String COMPLETER_KEY = "@first-assembly-context-entry-refused";

    @Override
    public boolean registerSelf() {
        return false;
    }

    /** The {@code @ContextEntry} target; not a component, so the scan does not register it. */
    public static class EntryBean {

        /** How many times {@code @PostConstruct} registered the completer, in this class loader. */
        public static final AtomicInteger REGISTRATIONS = new AtomicInteger();

        @PostConstruct
        void registerCompleter() {
            TabCompletionManager.getInstance().register(COMPLETER_KEY,
                    context -> Collections.singletonList("context-entry-refused"));
            REGISTRATIONS.incrementAndGet();
        }
    }
}
