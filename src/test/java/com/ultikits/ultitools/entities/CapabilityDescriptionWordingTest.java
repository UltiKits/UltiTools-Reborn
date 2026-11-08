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
 * {@code ultipanel.logging.batch.*} comments no longer claim the key is read once at server
 * start (they now say the keys belong to the deprecated v1 sender only). Every assertion here fails on the pre-fix wording quoted verbatim in issue #495 and passes
 * on the corrected wording — this is the red-when-reverted proof for the wording change itself.
 */
@DisplayName("#495：日志流描述不再声明控制能力，批量间隔注释不再声明只读一次")
class CapabilityDescriptionWordingTest {

    @Test
    @DisplayName("两个远程动作开关的注释说明各自管什么：电源动作不经命令黑名单，玩家动作是踢人、改模式与传送")
    void remoteActionSwitchCommentsStateWhatEachSwitchBounds() {
        List<String> power = Capability.POWER_ACTIONS.getCommentLines();
        assertThat(power).anySatisfy(line ->
                assertThat(line).contains("does not pass through the command blocklist"));
        assertThat(power).anySatisfy(line -> assertThat(line).contains("黑名单"));

        List<String> player = Capability.PLAYER_ACTIONS.getCommentLines();
        assertThat(player).anySatisfy(line -> assertThat(line)
                .contains("kick").contains("game mode").contains("teleport"));
        assertThat(player).anySatisfy(line -> assertThat(line)
                .contains("踢出").contains("游戏模式").contains("传送"));
    }

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
    @DisplayName("classpath 上的 config.yml：batch.* 注释说明只属于已弃用的 v1 发送器，协议 2.1.0 会话忽略，且不再声明只读一次")
    void configYmlBatchCommentsStateTheyOnlyServeTheDeprecatedV1Sender() throws IOException {
        String content = readConfigYmlFromClasspath();
        String lower = content.toLowerCase(Locale.ROOT);

        assertThat(lower)
                .as("the batch comments must say the protocol 2.1.0 session ignores the keys")
                .contains("session this framework runs ignores all three keys")
                .contains("ignored by the protocol 2.1.0 session");
        assertThat(lower)
                .as("the batch comments must name the deprecated v1 sender")
                .contains("deprecated protocol v1 sender");
        assertThat(lower)
                .as("the stale \"read once\" claim must stay gone")
                .doesNotContain("read once")
                .doesNotContain("once, at server start");
        assertThat(lower)
                .as("the v1 comments must no longer claim a reconnect re-reads the value")
                .doesNotContain("each time a panel connection opens");

        assertThat(content)
                .as("the Chinese comment must carry the same meaning")
                .contains("协议 2.1.0 会话忽略此键")
                .contains("已弃用的协议 v1 发送器");
        assertThat(content)
                .as("the stale Chinese \"read once at server start\" claim must stay gone")
                .doesNotContain("只在服务器启动时读取一次");
    }

    @Test
    @DisplayName("player-events 描述不再提聊天事件，file-delete 描述只涉及文件（config.yml、Capability、config-example.yml 一致）")
    void playerEventsHasNoChatAndFileDeleteCoversFilesOnly() throws IOException {
        String config = readConfigYmlFromClasspath();
        String example = readConfigExampleYmlFromDisk();

        List<String> events = Capability.PLAYER_EVENTS.getCommentLines();
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("join, quit and command events").contains("there is no chat event"));
        assertThat(events).anySatisfy(line -> assertThat(line).contains("没有聊天事件"));
        List<String> delete = Capability.FILE_DELETE.getCommentLines();
        assertThat(delete).anySatisfy(line -> assertThat(line)
                .contains("delete files within the editable roots").contains("Directories are never deleted remotely"));
        assertThat(delete).anySatisfy(line -> assertThat(line).contains("目录永远不会被远程删除"));

        for (String content : new String[] {config, example}) {
            assertThat(content)
                    .doesNotContain("join/quit/chat")
                    .doesNotContain("上线/下线/聊天")
                    .doesNotContain("files or directories")
                    .doesNotContain("文件或目录");
        }
        for (String line : events) {
            assertThat(config).contains(line);
        }
        for (String line : delete) {
            assertThat(config).contains(line);
        }
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
