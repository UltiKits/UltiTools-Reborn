package com.ultikits.ultitools.manager;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;

/**
 * The shared minimum module API level for loading and catalogue installation.
 *
 * <p>A module declares its UltiTools API level as the integer {@code api-version} in
 * {@code plugin.yml}. UltiTools-API 7.0 is a new baseline: a declared integer below
 * {@link #MIN_INSTALLABLE_API_LEVEL} is refused before the module's main class loads.
 * Missing or non-integer declarations retain the existing manual-loading behavior.
 *
 * <p>{@code /upm} requires an integer declaration and installs or stages updates only from this
 * floor through the framework's own level. Its catalogue version requests send both bounds.
 * Rebuilding against 7.0 is required; changing an old module's declaration alone does not repair
 * bytecode that references removed APIs.
 *
 * <p>The upper bound reuses {@code PluginManager.requiresNewerFramework(int, int)}, the single
 * definition of "the module needs a newer framework", so that comparison still exists once.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ModuleApiLevels {

    /**
     * The lowest declared module {@code api-version} loaded or installed on the 7.0 baseline.
     * Sent to the catalogue as {@code minApiLevel} and checked before loading a module main class
     * and before accepting a downloaded JAR.
     */
    public static final int MIN_INSTALLABLE_API_LEVEL = 700;

    private static final Logger LOGGER = Logger.getLogger(ModuleApiLevels.class.getName());

    private ModuleApiLevels() {
    }

    /**
     * This framework's own API level, as {@code UltiTools.getPluginVersion()} reads it from the
     * bundled {@code env.yml} (700 for 7.0.0 and its snapshots).
     *
     * <p>When that cannot be read, or reads below {@link #MIN_INSTALLABLE_API_LEVEL} -- impossible
     * for a build that contains this class, so a misreading -- the floor is used instead and a
     * warning is logged. The floor is a lower bound of the true level, so the fallback can only
     * refuse a module this framework could load; it never admits one it cannot.
     *
     * @return the level to send as {@code apiLevel} and to check downloads against
     */
    public static int frameworkLevel() {
        int level;
        try {
            level = UltiTools.getPluginVersion();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[UltiTools-API] Could not read this UltiTools-API's API level ("
                    + e.getMessage() + "); /upm uses " + MIN_INSTALLABLE_API_LEVEL + " instead");
            return MIN_INSTALLABLE_API_LEVEL;
        }
        if (level < MIN_INSTALLABLE_API_LEVEL) {
            LOGGER.log(Level.WARNING, "[UltiTools-API] This UltiTools-API's API level read as " + level
                    + ", below " + MIN_INSTALLABLE_API_LEVEL + "; /upm uses " + MIN_INSTALLABLE_API_LEVEL
                    + " instead");
            return MIN_INSTALLABLE_API_LEVEL;
        }
        return level;
    }

    /**
     * Whether {@code /upm} installs a module declaring {@code moduleLevel}.
     *
     * @param moduleLevel    the module's declared {@code api-version}
     * @param floor          the lowest level installed, {@link #MIN_INSTALLABLE_API_LEVEL}
     * @param frameworkLevel this framework's level, {@link #frameworkLevel()}
     * @return {@code true} when {@code floor <= moduleLevel <= frameworkLevel}
     */
    public static boolean isInstallable(int moduleLevel, int floor, int frameworkLevel) {
        return moduleLevel >= floor && !PluginManager.requiresNewerFramework(moduleLevel, frameworkLevel);
    }

    /**
     * A {@code plugin.yml} {@code api-version} as an integer level.
     *
     * @param declared the value as read, or {@code null} when the key is absent
     * @return the level, or {@code null} when absent or not an integer (for example Bukkit's
     *         {@code '1.19'})
     */
    public static Integer parse(String declared) {
        if (declared == null) {
            return null;
        }
        try {
            return Integer.valueOf(declared.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
