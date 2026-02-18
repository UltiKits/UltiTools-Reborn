package com.ultikits.ultitools.events;

/**
 * Interface for events that can be cancelled by handlers.
 * Only meaningful for synchronous events — async events must NOT implement this.
 * <p>
 * 可被处理器取消的事件接口。
 * 仅对同步事件有意义 — 异步事件不得实现此接口。
 *
 * @since 6.2.2
 */
public interface Cancellable {
    boolean isCancelled();
    void setCancelled(boolean cancelled);
}
