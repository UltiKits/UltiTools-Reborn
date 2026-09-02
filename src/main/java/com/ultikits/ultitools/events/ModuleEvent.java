package com.ultikits.ultitools.events;

import lombok.Getter;
import lombok.Setter;

/**
 * Base class for all module events. Extend this to create custom events.
 *
 * @since 6.2.2
 */
public abstract class ModuleEvent {
    @Getter
    @Setter
    private String sourceModule;

    @Getter
    private final long timestamp;

    protected ModuleEvent() {
        this.timestamp = System.currentTimeMillis();
    }
}
