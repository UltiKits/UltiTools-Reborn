package com.ultikits.ultitools.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PanelMessageEvent}'s own shape (Task 1), and — added by later tasks in the same plan —
 * its bridging publish from {@code PluginInitiationUtils#handleInboundMessage} (Task 2) and the
 * slow-handler warning that publish produces (Task 3). Kept as one class because all three groups
 * exercise the same extension point end to end; see 06-07-PLAN.md.
 * <br>
 * {@link PanelMessageEvent} 自身的形状（Task 1），以及本计划后续任务在同一个类里补充的：
 * 从 {@code PluginInitiationUtils#handleInboundMessage} 发布该事件的桥接行为（Task 2），
 * 以及该发布产生的慢处理器告警（Task 3）。放在同一个类里是因为三组用例共同验证的是
 * 同一个扩展点的端到端行为。
 */
@DisplayName("PanelMessageEvent")
class PanelMessageEventDispatchTest {

    @Nested
    @DisplayName("事件自身的形状")
    class EventShape {

        @Test
        @DisplayName("不可赋值给 Cancellable")
        void isNotCancellable() {
            assertThat(Cancellable.class.isAssignableFrom(PanelMessageEvent.class)).isFalse();
        }

        @Test
        @DisplayName("是 ModuleEvent 的子类")
        void isModuleEvent() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            PanelMessageEvent event = new PanelMessageEvent("ping", data, new JsonObject());

            assertThat(event).isInstanceOf(ModuleEvent.class);
        }

        @Test
        @DisplayName("构造后暴露 type 与 data")
        void exposesTypeAndData() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "ping");

            PanelMessageEvent event = new PanelMessageEvent("ping", data, raw);

            assertThat(event.getType()).isEqualTo("ping");
            assertThat(event.getData().get("k").getAsString()).isEqualTo("v");
        }

        @Test
        @DisplayName("构造之后修改传入的 JsonObject 不影响 accessor 的返回值")
        void mutatingConstructorInputDoesNotLeakIn() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "original");

            PanelMessageEvent event = new PanelMessageEvent("ping", data, new JsonObject());
            data.addProperty("k", "mutated-after-construction");

            assertThat(event.getData().get("k").getAsString()).isEqualTo("original");
        }

        @Test
        @DisplayName("修改 accessor 返回的 JsonObject 不影响下一次调用的结果")
        void mutatingAccessorResultDoesNotLeakOut() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "original");
            PanelMessageEvent event = new PanelMessageEvent("ping", data, new JsonObject());

            JsonObject firstCall = event.getData();
            firstCall.addProperty("k", "mutated-after-accessor-call");

            assertThat(event.getData().get("k").getAsString()).isEqualTo("original");
        }

        @Test
        @DisplayName("data 为 null 时 accessor 返回非 null 的空对象")
        void nullDataYieldsNonNullEmptyObject() {
            PanelMessageEvent event = new PanelMessageEvent("ping", null, new JsonObject());

            assertThat(event.getData()).isNotNull();
            assertThat(event.getData().entrySet()).isEmpty();
        }

        @Test
        @DisplayName("type 为 null 或空字符串时拒绝构造")
        void nullOrEmptyTypeIsRejected() {
            assertThatThrownBy(() -> new PanelMessageEvent(null, new JsonObject(), new JsonObject()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PanelMessageEvent("", new JsonObject(), new JsonObject()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rawMessage 与 data 分开暴露")
        void rawMessageExposedSeparatelyFromData() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "ping");
            raw.addProperty("serverId", "test-server");
            raw.add("data", data);

            PanelMessageEvent event = new PanelMessageEvent("ping", data, raw);

            assertThat(event.getRawMessage().get("serverId").getAsString()).isEqualTo("test-server");
            assertThat(event.getData().has("serverId")).isFalse();
        }
    }
}
