package com.ultikits.ultitools.ui.container;

import com.ultikits.ultitools.ui.runtime.UiRuntime;
import com.ultikits.ultitools.utils.ReflectionUtil;
import java.time.Duration;

/** Keeps the existing package-private shortened container wait accessible to lifecycle integration tests. */
public final class UiUnloadTestAccess {
    private UiUnloadTestAccess() { }

    public static void shortenWait(UiRuntime runtime, Duration wait) {
        FrameworkContainerRuntime containers = (FrameworkContainerRuntime) ReflectionUtil.getFieldValue(runtime, "containers");
        containers.unloadWaitOverride(wait);
    }
}
