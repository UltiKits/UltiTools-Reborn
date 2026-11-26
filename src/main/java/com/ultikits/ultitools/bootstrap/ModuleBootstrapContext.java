package com.ultikits.ultitools.bootstrap;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

/** Identity supplied to a {@link ModuleBootstrap}; modules must not implement this interface. */
@ApiStatus.NonExtendable
public interface ModuleBootstrapContext {
    /** @return the name declared by this module, or its JAR filename if no name was declared */
    String moduleName();

    /** @return the absolute, normalized path of this module's JAR */
    Path moduleJar();

    /** @return this module's early-start JUL logger, forwarded to Paper's bootstrap logger */
    Logger logger();
    /** @return custom enchantments this module adds; entries take effect only if bootstrap() returns normally */
    @ApiStatus.Experimental
    EnchantmentChannel enchantments();

    /** @return tag additions and new tags this module contributes; pure data, replayed on every data reload */
    @ApiStatus.Experimental
    TagChannel tags();

    /** @return data packs bundled in this module's JAR under {@code datapacks/<name>/} */
    @ApiStatus.Experimental
    DatapackChannel datapacks();
}
