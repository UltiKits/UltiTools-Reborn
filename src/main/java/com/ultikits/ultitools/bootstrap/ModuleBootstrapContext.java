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
}
