package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;

/** Keeps every original click assertion while exercising the production container engine. */
class FrameworkChestBackendClickTest extends ChestBackendClickTest {
    @Override ContainerRuntime createContainerRuntime() {
        Thread containerThread = Thread.currentThread();
        return new FrameworkContainerRuntime(() -> containerThread.equals(Thread.currentThread()),
                (viewer, text) -> viewer.sendMessage(messages.render(text, viewer)));
    }
}
