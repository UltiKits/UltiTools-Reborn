package com.ultikits.ultitools.events;

import lombok.Getter;
import lombok.Setter;

/**
 * Base class for all module events. Extend this to create custom events.
 * <p>
 * 所有模块事件的基类。扩展此类以创建自定义事件。
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
