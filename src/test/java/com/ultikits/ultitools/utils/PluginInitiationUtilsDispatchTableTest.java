package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Parity proof for {@link PluginInitiationUtils}'s inbound dispatch table.
 * <p>
 * The table replaced a 24-case {@code switch} inside {@code handleInboundMessage} (NPath
 * complexity 1514 against a threshold of 200). This class pins the routing record the table must
 * reproduce: exactly the 24 known message types, {@code log_stream} and
 * {@code log_stream_control} sharing one handler as the former fall-through did, no null entries,
 * and an unknown type absent rather than defaulted to something.
 * <p>
 * Set equality (not a size comparison) is deliberate for the key-set assertion: a size-only check
 * would pass if one type were silently dropped and a different one silently added, which is the
 * exact regression this test exists to catch when a later phase adds a message type.
 * <p>
 * {@code PluginInitiationUtilsInboundMessageTest} already covers the guards (null message, absent
 * / JSON-null / empty-string / non-primitive {@code type}) and the default branch; it does not
 * cover per-type routing, so this class closes that gap rather than duplicating the guard tests.
 *
 * @see PluginInitiationUtils#inboundDispatchTable()
 */
@DisplayName("PluginInitiationUtils 入站分发表")
class PluginInitiationUtilsDispatchTableTest {

    /**
     * The 24 message types the pre-refactor 24-case switch routed. Enumerated from the switch's
     * case labels before the refactor, cross-checked against the switch body one more time after —
     * see plan 01-05's domain context on why an empty grep result is not sufficient evidence here.
     */
    private static final Set<String> EXPECTED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
            "ping", "pong", "subscribe", "unsubscribe", "notification", "error",
            "server_status", "plugin_list", "player_event", "metrics_data",
            "execute_command", "command_result", "file_operation", "file_operation_result",
            "log_stream", "log_stream_control", "backup_operation", "backup_progress",
            "upload_config", "update_config", "server_properties", "server_properties_result",
            "auth_complete", "magic_link_response"
    ));

    private Map<String, BiConsumer<JsonObject, JsonObject>> table() {
        return PluginInitiationUtils.inboundDispatchTable();
    }

    @Nested
    @DisplayName("键集合")
    class KeySet {

        @Test
        @DisplayName("恰好等于这 24 个已知类型 —— 集合相等，而非仅比较数量")
        void keySetEqualsExactlyTheTwentyFourKnownTypes() {
            assertThat(table().keySet())
                    .as("a size-only comparison would pass if one type were dropped and another "
                            + "added; set equality fails on both a drop and an addition")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_TYPES);
        }

        @Test
        @DisplayName("表中 24 个条目都不是 null")
        void noEntryIsNull() {
            assertThat(table().values()).doesNotContainNull();
        }

        @Test
        @DisplayName("未知类型不在表中")
        void unknownTypeIsAbsent() {
            assertThat(table()).doesNotContainKey("definitely_not_a_real_type");
        }
    }

    @Nested
    @DisplayName("log_stream 与 log_stream_control")
    class LogStreamFallThrough {

        @Test
        @DisplayName("两个 type 共享同一个处理器实例，与原先的 fall-through 等价")
        void logStreamAndLogStreamControlShareOneHandler() {
            Map<String, BiConsumer<JsonObject, JsonObject>> table = table();

            assertThat(table.get("log_stream"))
                    .as("log_stream 与 log_stream_control 原先靠 case 标签之间的 fall-through 共享"
                            + "同一段处理逻辑；查表版本必须用同一个 handler 实例复现这一点")
                    .isSameAs(table.get("log_stream_control"));
        }
    }
}
