package com.ultikits.testfixtures.economyattribution.registering;

import java.util.Collections;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.utils.EconomyUtils;

/**
 * A module that requests the economy while it is still being registered (#483): from its
 * constructor, from its own {@code @PostConstruct} method, or from {@code registerSelf()} --
 * whichever {@link #window} names. It is constructed through the connector constructor from its
 * own no-argument constructor, so {@code PluginManager#register(Class)} can construct it without a
 * module jar.
 */
public class RegisteringEconomyModule extends UltiToolsPlugin {

    /** Module name; equal to the class's simple name, the name attribution can derive before construction. */
    public static final String NAME = "RegisteringEconomyModule";

    /**
     * Which registration step makes the economy request: "constructor", "postconstruct",
     * "registerself", "otherthread" (registerSelf() starts a second thread that makes it), or
     * "refuse" (no request; registerSelf() returns false, so the registration fails).
     */
    public static volatile String window = "";

    /** The resource folder the connector constructor is given. */
    public static volatile String resourceFolder = "";

    public RegisteringEconomyModule() {
        super(NAME, "1.0.0", Collections.emptyList(), Collections.emptyList(), 0,
                RegisteringEconomyModule.class.getName(), resourceFolder);
        if ("constructor".equals(window)) {
            EconomyUtils.isAvailable();
        }
    }

    @PostConstruct
    public void requestDuringPostConstruct() {
        if ("postconstruct".equals(window)) {
            EconomyUtils.isAvailable();
        }
    }

    @Override
    public boolean registerSelf() {
        if ("registerself".equals(window)) {
            EconomyUtils.isAvailable();
        }
        if ("otherthread".equals(window)) {
            Thread other = new Thread(RegisteringEconomyModule::requestEconomyNow, "economy-attribution-other-thread");
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return !"refuse".equals(window);
    }

    /** An economy request from this module's package, made whenever the test calls it. */
    public static boolean requestEconomyNow() {
        return EconomyUtils.isAvailable();
    }
}
