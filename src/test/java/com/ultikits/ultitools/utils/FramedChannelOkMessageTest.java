package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The optional message of an {@code ok} result: a result type whose ok variant declares one (the
 * frozen {@code config.update.result} does) carries it in the payload, and a reply without one is
 * byte-for-byte the frame the channel always produced.
 */
class FramedChannelOkMessageTest {

    private static final String REPLY_TO = "01JB2Z8Y3K4M5N6P7Q8R9S0TV1";

    @Test
    void anOkReplyCarriesItsOptionalMessageAndStaysSchemaValid() {
        List<JsonObject> sent = new ArrayList<>();
        CloudChannel channel = channel(sent);

        assertThat(channel.replyOk(REPLY_TO, "config.update.result", data(), "Saved to the file.")).isTrue();

        JsonObject payload = only(sent).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.get("message").getAsString()).isEqualTo("Saved to the file.");
        assertThat(payload.getAsJsonObject("data").toString()).isEqualTo(data().toString());
    }

    @Test
    void anOkReplyWithoutAMessageOmitsTheField() {
        List<JsonObject> sent = new ArrayList<>();
        CloudChannel channel = channel(sent);

        assertThat(channel.replyOk(REPLY_TO, "config.update.result", data(), null)).isTrue();

        assertThat(only(sent).getAsJsonObject("payload").has("message")).isFalse();
    }

    @Test
    void aChannelThatCannotCarryTheMessageRefuses() {
        CloudChannel minimum = new CloudChannel() {
            @Override public boolean isOpen() { return true; }
            @Override public CloudLimits limits() { return CloudLimits.defaults(); }
            @Override public boolean send(String type, JsonObject payload) { return false; }
            @Override public boolean reply(String replyTo, String type, JsonObject payload) { return false; }
            @Override public boolean replyOk(String replyTo, String type, JsonElement data) { return false; }
            @Override public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull,
                    String messageOrNull) { return false; }
            @Override public boolean replyError(String replyTo, String type, String code, String message,
                    JsonObject detailsOrNull) { return false; }
        };

        assertThatThrownBy(() -> minimum.replyOk(REPLY_TO, "config.update.result", data(), "Saved."))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CloudChannel channel(List<JsonObject> sent) {
        return new FramedChannel(frame -> {
            JsonObject json = JsonParser.parseString(frame).getAsJsonObject();
            ContractSchemas.assertFrameValid(json);
            sent.add(json);
            return true;
        }, () -> true, CloudLimits::defaults, Ulid::next, () -> 1000L);
    }

    private static JsonObject only(List<JsonObject> sent) {
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    private static JsonObject data() {
        JsonObject key = new JsonObject();
        key.addProperty("key", "chat.prefix");
        key.addProperty("ok", true);
        key.add("message", com.google.gson.JsonNull.INSTANCE);
        JsonArray keys = new JsonArray();
        keys.add(key);
        JsonObject data = new JsonObject();
        data.add("keys", keys);
        data.addProperty("version", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        return data;
    }
}
