package com.ultikits.ultitools.events;

import com.google.gson.JsonObject;

/**
 * Published once for every inbound panel message the framework has already handled — appended as
 * the very last statement of {@code PluginInitiationUtils#handleInboundMessage(JsonObject)}, after
 * the framework's own dispatch has completed. A module subscribes to this event on the existing
 * {@link EventBus} to observe panel traffic without the framework growing a second, module-visible
 * protocol surface.
 * <p>
 * This event deliberately does <b>not</b> implement {@link Cancellable}. Cancellation is only
 * meaningful before an outcome has been decided; this event is published after the framework has
 * already handled the message, so a cancel flag here would be a callable method with no observable
 * effect — exactly the "declared but does not do what it declares" defect class this milestone
 * (6.3.0) exists to remove. This is consistent with an existing in-repo contract rather than an
 * isolated exception: {@link EventBus#publishAsync(ModuleEvent)} already rejects any
 * {@code Cancellable} event outright, for the same underlying reasoning that cancellation requires
 * synchronous dispatch before the result is settled.
 * <p>
 * Handlers run on the main server thread — the publish site wraps {@link EventBus#publish} in
 * {@code Bukkit.getScheduler().runTask(...)} — so a handler may use Bukkit API freely. That same
 * hop means a slow handler occupies a server tick; the publish site logs one warning per slow
 * publish naming the elapsed time.
 * <p>
 * The {@code data} and raw envelope this event carries are defensive copies, taken both when the
 * event is constructed and again on every accessor call: this event crosses a thread hop and is
 * delivered to an unknown number of third-party handlers, and one handler mutating shared state
 * must not change what the next handler sees or what the framework already acted on.
 * <br>
 * 每一条框架已经处理完的入站面板消息都会发布一次此事件 —— 作为
 * {@code PluginInitiationUtils#handleInboundMessage(JsonObject)} 的最后一条语句追加，在框架自身的
 * 分发已经完成之后。模块通过订阅现有 {@link EventBus} 上的这个事件来观察面板流量，而不需要框架
 * 再长出第二个、模块可见的协议面。
 * <p>
 * 本事件刻意<b>不</b>实现 {@link Cancellable}。取消只有在结果尚未确定时才有意义；本事件是在框架
 * 已经处理完消息之后才发布的，此时挂一个取消标记只会是一个可调用却毫无实际效果的方法 —— 这正是
 * 6.3.0 这个里程碑要清除的"声明了却不生效"这一类缺陷。这与仓库里已有的约定一致，而不是一个孤立
 * 的例外：{@link EventBus#publishAsync(ModuleEvent)} 已经无条件拒绝任何 {@code Cancellable} 事件，
 * 依据的是同一条理由 —— 取消只对结果尚未确定的同步分发有意义。
 * <p>
 * 处理器运行在服务器主线程上 —— 发布点把 {@link EventBus#publish} 包在
 * {@code Bukkit.getScheduler().runTask(...)} 里 —— 因此处理器可以自由使用 Bukkit API。同一个
 * 线程跳转也意味着慢处理器会占用一个 tick；发布点针对每次慢发布记一条告警，注明耗时。
 * <p>
 * 本事件携带的 {@code data} 与原始信封都是防御性拷贝：构造时拷贝一次，每次调用 accessor 时再拷贝
 * 一次 —— 因为本事件跨越了一次线程跳转，会被投递给数量未知的第三方处理器，一个处理器修改共享状态
 * 不应当影响下一个处理器看到的内容，也不应当影响框架自己已经采取的动作。
 *
 * @since 6.3.0
 */
public final class PanelMessageEvent extends ModuleEvent {

    private final String type;
    private final JsonObject data;
    private final JsonObject rawMessage;

    /**
     * @param type       the panel message's {@code type} field; must not be {@code null} or empty
     * @param data       the message's {@code data} object; {@code null} becomes a non-null empty object
     * @param rawMessage the full inbound envelope; {@code null} becomes a non-null empty object
     * @throws IllegalArgumentException if {@code type} is {@code null} or empty
     */
    public PanelMessageEvent(String type, JsonObject data, JsonObject rawMessage) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("PanelMessageEvent requires a non-empty type");
        }
        this.type = type;
        this.data = data != null ? data.deepCopy() : new JsonObject();
        this.rawMessage = rawMessage != null ? rawMessage.deepCopy() : new JsonObject();
    }

    /** @return the panel message's {@code type} field; never {@code null} or empty */
    public String getType() {
        return type;
    }

    /** @return a defensive copy of the message's {@code data} object; never {@code null} */
    public JsonObject getData() {
        return data.deepCopy();
    }

    /** @return a defensive copy of the full inbound envelope; never {@code null} */
    public JsonObject getRawMessage() {
        return rawMessage.deepCopy();
    }
}
