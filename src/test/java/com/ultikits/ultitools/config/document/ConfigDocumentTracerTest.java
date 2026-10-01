package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tracer of the config-layer refactor (plan 17-56, Task 1; maintainer decision of 2026-09-30, Follow-up 12:
 * an in-house document tree on the server's SnakeYAML 2.2): one file the 6.2 writer produces, plus two keys
 * an operator added by hand, goes through the new storage layer to disk and back.
 */
@DisplayName("ConfigDocument - tracer: a 6.2-written file through the storage layer and back")
class ConfigDocumentTracerTest {

    /**
     * What the {@code dfe71e01} writer produces for these shapes (a header comment, a section with an
     * entry comment, a quoted colour-code string, {@code 'yes'}, a block list, a list of maps, an empty
     * list, a non-ASCII value), followed by two keys an operator added by hand: a quoted dotted key and a
     * key ending in {@code .}.
     */
    static final String FIXTURE = "# UltiChat configuration\n"
            + "\n"
            + "chat:\n"
            + "  # The chat format\n"
            + "  format: '&7{player}&f: {message}'\n"
            + "  confirm: 'yes'\n"
            + "  worlds:\n"
            + "  - world\n"
            + "  - world_nether\n"
            + "  rewards:\n"
            + "  - item: DIAMOND\n"
            + "    amount: 5\n"
            + "  blocked: []\n"
            + "emojis:\n"
            + "  heart: \u2764\n"
            + "  \"o.O\": x\n"
            + "  wave.: z\n";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("parses and renders byte for byte")
    void rendersTheFileByteForByte() throws Exception {
        ConfigDocument document = ConfigDocument.parse(FIXTURE);

        assertThat(document.render()).isEqualTo(FIXTURE);
    }

    @Test
    @DisplayName("setting one value changes exactly one line")
    void oneValueChangesOneLine() throws Exception {
        ConfigDocument document = ConfigDocument.parse(FIXTURE);

        document.set(Arrays.asList("chat", "format"), "&a{player}: {message}");

        List<String> before = lines(FIXTURE);
        List<String> after = lines(document.render());
        assertThat(after).hasSameSizeAs(before);
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                changed.add(i);
            }
        }
        assertThat(changed).containsExactly(4);
        assertThat(after.get(4)).isEqualTo("  format: '&a{player}: {message}'");
    }

    @Test
    @DisplayName("written atomically to disk, the file reads back into the same plain tree with whole keys")
    void writtenFileReadsBackWithWholeKeys() throws Exception {
        ConfigDocument document = ConfigDocument.parse(FIXTURE);
        Path target = tempDir.resolve("config").resolve("chat.yml");
        Files.createDirectories(target.getParent());

        AtomicConfigWriter.write(target, document.render());

        ConfigDocument reread = ConfigDocument.parse(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertThat(reread.toPlain()).isEqualTo(document.toPlain());
        Object emojis = reread.get(Arrays.asList("emojis"));
        assertThat(emojis).isInstanceOf(Map.class);
        assertThat(new ArrayList<Object>(((Map<?, ?>) emojis).keySet())).containsExactly("heart", "o.O", "wave.");
        assertThat(reread.get(Arrays.asList("emojis", "o.O"))).isEqualTo("x");
        assertThat(reread.get(Arrays.asList("emojis", "wave."))).isEqualTo("z");
        assertThat(reread.contains(Arrays.asList("emojis", "o"))).isFalse();
        try (java.util.stream.Stream<Path> siblings = Files.list(target.getParent())) {
            assertThat(siblings).containsExactly(target);
        }
    }

    @Test
    @DisplayName("a UUID value is refused, naming the key path and the class")
    void nonPlainValueIsRefused() throws Exception {
        ConfigDocument document = ConfigDocument.parse(FIXTURE);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> document.set(Arrays.asList("chat", "owner"), id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[chat, owner]")
                .hasMessageContaining("java.util.UUID");
        assertThat(document.render()).isEqualTo(FIXTURE);
    }

    private static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }
}
