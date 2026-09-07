package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.MergedAnnotationResolver;

import java.util.List;

/**
 * Reads {@code @UltiToolsModule}'s {@code cmdExecutor}/{@code eventListener}/{@code config}
 * registration switches from the module's entry class (Phase 10, D-10-04), surfaced at document
 * level as {@code registers_commands}/{@code registers_listeners}/{@code registers_config}.
 * <p>
 * A {@code false} switch is reported, never used to suppress the rows it governs — a suppressed
 * registration is a fact to verify, not a reason to hide the function it would have registered.
 *
 * @since 6.3.0
 */
public final class ModuleSwitchReader {

    /**
     * Finds the {@code @UltiToolsModule} entry class among {@code classes} and reads its
     * registration switches.
     *
     * @param classes the loaded (uninitialized) classes to scan
     * @return the module's switches, or {@code null} if no {@code @UltiToolsModule} class is present
     */
    public Switches read(List<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            UltiToolsModule module = MergedAnnotationResolver.find(clazz, UltiToolsModule.class);
            if (module != null) {
                return new Switches(module.cmdExecutor(), module.eventListener(), module.config());
            }
        }
        return null;
    }

    /** The three registration switches read from {@code @UltiToolsModule}. */
    public static final class Switches {
        private final boolean registersCommands;
        private final boolean registersListeners;
        private final boolean registersConfig;

        Switches(boolean registersCommands, boolean registersListeners, boolean registersConfig) {
            this.registersCommands = registersCommands;
            this.registersListeners = registersListeners;
            this.registersConfig = registersConfig;
        }

        public boolean isRegistersCommands() {
            return registersCommands;
        }

        public boolean isRegistersListeners() {
            return registersListeners;
        }

        public boolean isRegistersConfig() {
            return registersConfig;
        }
    }
}
