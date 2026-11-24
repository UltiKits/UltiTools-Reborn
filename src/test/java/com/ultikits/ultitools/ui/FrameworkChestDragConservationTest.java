package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;

/** Replays the unchanged Paper event-order conservation suite on the production container engine. */
class FrameworkChestDragConservationTest extends AbstractChestDragConservationTest {
    @Override ContainerRuntime createContainerRuntime() {
        Thread containerThread = Thread.currentThread();
        return new FrameworkContainerRuntime(() -> containerThread.equals(Thread.currentThread()),
                (viewer, text) -> viewer.sendMessage(messages.render(text, viewer)));
    }
}
