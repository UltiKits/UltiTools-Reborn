package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves #495 is fixed: the shipped {@code config.yml} and the {@link Capability#LOGS} description
 * compiled into the jar no longer claim the panel can control the live console log stream, and the
 * {@code ultipanel.logging.batch.interval} comment no longer claims the key is read once at server
 * start. Every assertion here fails on the pre-fix wording quoted verbatim in issue #495 and passes
 * on the corrected wording — this is the red-when-reverted proof for the wording change itself.
 */
@DisplayName("#495：日志流描述不再声明控制能力，批量间隔注释不再声明只读一次")
class CapabilityDescriptionWordingTest {

    /**
     * Reads {@code src/main/resources/config.yml} the same way the running framework would load it
     * from the packaged jar — off the classpath, since {@code src/main/resources} is on the test
     * classpath by Maven's default layout.
     */
    private static String readConfigYmlFromClasspath() throws IOException {
        try (InputStream in = CapabilityDescriptionWordingTest.class
                .getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).as("config.yml must be present on the test classpath").isNotNull();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads {@code config-example.yml} from the repository root, relative to the project base
     * directory Maven Surefire launches the JVM in by default (never a path relative to whatever
     * directory the test happened to be invoked from).
     */
    private static String readConfigExampleYmlFromDisk() throws IOException {
        File file = new File(System.getProperty("user.dir"), "config-example.yml");
        assertThat(file).as("config-example.yml must exist at the project base directory").exists();
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Capability.LOGS 的描述不含英文 control / 中文「控制」，且陈述的是日志流的投递")
    void capabilityLogsDescriptionDoesNotClaimControl() {
        List<String> lines = Capability.LOGS.getCommentLines();
        assertThat(lines).isNotEmpty();

        String joinedLower = String.join(" ", lines).toLowerCase(Locale.ROOT);
        assertThat(joinedLower)
                .as("Capability.LOGS must not use the English word \"control\"")
                .doesNotContain("control");
        String joined = String.join(" ", lines);
        // "控制台" (console) legitimately contains the substring "控制" -- checking the bare
        // substring would false-positive against the correct wording too. The stale claim was
        // specifically "接收并控制" (receive AND control); that phrase is what must be gone.
        assertThat(joined)
                .as("Capability.LOGS must not claim the panel may receive AND control the stream")
                .doesNotContain("接收并控制");
        // IN-02 (gate-1 review): assert the exact corrected sentence in both languages, not the
        // weak `.contains("stream")` proxy a future edit could keep passing while dropping the
        // substantive "receives" claim.
        assertThat(lines)
                .as("Capability.LOGS's English description must be exactly the corrected sentence")
                .contains("Whether the panel receives the live console log stream.");
        assertThat(lines)
                .as("Capability.LOGS's Chinese description must be exactly the corrected sentence")
                .contains("面板是否可以接收实时控制台日志流。");
    }

    @Test
    @DisplayName("classpath 上的 config.yml：logs 注释（中英文）不再声明流可被控制")
    void configYmlLogsCommentDoesNotClaimControl() throws IOException {
        String content = readConfigYmlFromClasspath();

        assertThat(content.toLowerCase(Locale.ROOT))
                .as("no line may claim the panel both streams and controls the log")
                .doesNotContain("stream and control");
        assertThat(content)
                .as("no line may claim the panel may receive AND control the stream (Chinese)")
                .doesNotContain("接收并控制");

        // The corrected sentence must be present, in both languages, on the `logs` capability's
        // comment lines (immediately preceding the `logs: true` key).
        String[] lines = content.split("\n", -1);
        boolean englishFound = false;
        boolean chineseFound = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("logs: true")) {
                // Walk backwards over the comment block directly above this key.
                for (int j = i - 1; j >= 0 && lines[j].trim().startsWith("#"); j--) {
                    String line = lines[j];
                    if (line.contains("Whether the panel receives the live console log stream.")) {
                        englishFound = true;
                    }
                    if (line.contains("面板是否可以接收实时控制台日志流")) {
                        chineseFound = true;
                    }
                }
            }
        }
        assertThat(englishFound)
                .as("the corrected English `logs` sentence must precede `logs: true`")
                .isTrue();
        assertThat(chineseFound)
                .as("the corrected Chinese `logs` sentence must precede `logs: true`")
                .isTrue();
    }

    @Test
    @DisplayName("classpath 上的 config.yml：batch.interval 注释不再声明只在启动时读取一次")
    void configYmlBatchIntervalCommentNoLongerClaimsReadOnce() throws IOException {
        String content = readConfigYmlFromClasspath();
        String lower = content.toLowerCase(Locale.ROOT);

        assertThat(lower)
                .as("the corrected English comment must say the key is re-read each time a panel connection opens")
                .contains("each time");
        assertThat(lower)
                .as("the stale \"read once\" claim must be gone")
                .doesNotContain("read once");
        assertThat(lower)
                .as("the stale \"once, at server start\" claim must be gone")
                .doesNotContain("once, at server start");

        // Chinese must carry the same corrected meaning: "每次...连接" (each time a connection
        // happens) present, and the stale "只在服务器启动时读取一次" claim gone.
        assertThat(content)
                .as("the Chinese comment must state the key is re-read on each panel connection")
                .contains("每次面板连接");
        assertThat(content)
                .as("the stale Chinese \"read once at server start\" claim must be gone")
                .doesNotContain("只在服务器启动时读取一次");
    }

    @Test
    @DisplayName("config-example.yml：logs 注释携带与 config.yml 相同的修正后语句")
    void configExampleYmlCarriesTheCorrectedLogsSentence() throws IOException {
        String content = readConfigExampleYmlFromDisk();

        assertThat(content.toLowerCase(Locale.ROOT))
                .as("no line may claim the panel both streams and controls the log")
                .doesNotContain("stream and control");
        assertThat(content)
                .as("no line may claim the panel may receive AND control the stream (Chinese)")
                .doesNotContain("接收并控制");
        assertThat(content)
                .as("config-example.yml must carry the same corrected English `logs` sentence as config.yml")
                .contains("Whether the panel receives the live console log stream.");
        assertThat(content)
                .as("config-example.yml must carry the same corrected Chinese `logs` sentence as config.yml")
                .contains("面板是否可以接收实时控制台日志流");
    }
}
