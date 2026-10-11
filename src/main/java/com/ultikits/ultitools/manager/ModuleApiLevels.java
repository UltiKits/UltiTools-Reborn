package com.ultikits.ultitools.manager;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;

/**
 * Which module API levels {@code /upm} installs from a catalogue.
 *
 * <p>A module declares the UltiTools API level it is built for as the integer {@code api-version}
 * of its {@code plugin.yml}. {@code /upm} installs, installs by version and stages updates only of
 * modules at {@link #MIN_INSTALLABLE_API_LEVEL} up to this framework's own level, and sends both
 * numbers to the catalogue on every request so that the catalogue offers nothing else.
 *
 * <p><b>Why the floor exists.</b> UltiTools-API 6.3.0 made {@code UltiToolsPlugin.unregisterSelf()}
 * final, and every module built before 6.3.0 overrides it, so such a module fails to link
 * ({@code IncompatibleClassChangeError}) at the next start. A catalogue that serves a pre-6.3.0
 * build -- one that does not filter by level, or a version recorded wrongly -- would otherwise be
 * downloaded, reported installed and then refused at restart.
 *
 * <p><b>The module loader's admission rule is separate</b> and unchanged: a JAR an operator places
 * in the modules folder by hand is still judged by {@code PluginManager}'s own rule. This class is an
 * install policy of {@code /upm}, which only fetches from a catalogue.
 *
 * <p>The upper bound reuses {@code PluginManager.requiresNewerFramework(int, int)}, the single
 * definition of "the module needs a newer framework", so that comparison still exists once.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ModuleApiLevels {

    /**
     * The lowest module {@code api-version} {@code /upm} installs: the first level built against
     * the 6.3.0 module contract. Sent to the catalogue as {@code minApiLevel}, and used by the check
     * of every downloaded JAR, so the catalogue never holds a second copy of this policy.
     */
    public static final int MIN_INSTALLABLE_API_LEVEL = 630;

    private static final Logger LOGGER = Logger.getLogger(ModuleApiLevels.class.getName());

    private ModuleApiLevels() {
    }

    /**
     * This framework's own API level, as {@code UltiTools.getPluginVersion()} reads it from the
     * bundled {@code env.yml} (630 for 6.3.0 and its snapshots).
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
