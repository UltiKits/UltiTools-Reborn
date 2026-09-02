package com.ultikits.ultitools.entities;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ultikits.ultitools.UltiTools;

/**
 * The panel-facing remote surface, sliced into eight independently-switchable capabilities plus
 * the {@link #NONE} sentinel for protocol-level messages.
 * <p>
 * Each config-backed constant owns its own {@code ultipanel.capabilities.*} key, its own D-08
 * shipped default, and the comment lines the capability migration attaches when it writes the key
 * for the first time. {@link #isEnabled()} is the single config read every inbound and outbound
 * capability gate in this phase consults — see {@code PluginInitiationUtils.handleInboundMessage}.
 *
 * @since 6.3.0
 */
public enum Capability {

    MONITORING("monitoring", true, Arrays.asList(
            "Whether the panel may receive live server monitoring data (TPS, memory, world/player snapshots).",
            "This is the panel's only \"server is alive\" signal — turning it off makes an upgraded server read as offline.",
            "面板是否可以接收服务器实时监控数据（TPS、内存、世界/玩家快照）。",
            "这是面板判断「服务器是否在线」的唯一依据——关闭它会让升级后的服务器在面板上显示为离线。"
    )),
    LOGS("logs", true, Arrays.asList(
            "Whether the panel may stream and control the live console log.",
            "面板是否可以接收并控制实时控制台日志流。"
    )),
    PLAYER_EVENTS("player-events", true, Arrays.asList(
            "Whether the panel may receive live player join/quit/chat events.",
            "面板是否可以接收玩家上线/下线/聊天等实时事件。"
    )),
    FILE_READ("file-read", true, Arrays.asList(
            "Whether the panel may read and list files within the editable roots.",
            "面板是否可以在可编辑根目录内读取与列出文件。"
    )),
    FILE_WRITE("file-write", false, Arrays.asList(
            "Whether the panel may write or upload files within the editable roots.",
            "面板是否可以在可编辑根目录内写入或上传文件。"
    )),
    FILE_DELETE("file-delete", false, Arrays.asList(
            "Whether the panel may delete files or directories within the editable roots.",
            "面板是否可以在可编辑根目录内删除文件或目录。"
    )),
    COMMANDS("commands", false, Arrays.asList(
            "Whether the panel may execute server commands remotely.",
            "面板是否可以远程执行服务器命令。"
    )),
    SERVER_PROPERTIES("server-properties", false, Arrays.asList(
            "Whether the panel may read and edit server.properties safe keys.",
            "面板是否可以读取与编辑 server.properties 的安全白名单键。"
    )),

    /**
     * The D-10 sentinel for protocol-level and echo messages that carry no operator-facing policy.
     * Never written to config.yml and never gated — {@link #isEnabled()} always returns
     * {@code true} without touching config.
     */
    NONE(null, true, Collections.emptyList());

    private final String configKey;
    private final boolean defaultEnabled;
    private final List<String> commentLines;

    Capability(String configKey, boolean defaultEnabled, List<String> commentLines) {
        this.configKey = configKey;
        this.defaultEnabled = defaultEnabled;
        this.commentLines = Collections.unmodifiableList(commentLines);
    }

    /**
     * The literal config-key prefix every capability lives under in {@code config.yml}.
     *
     * @return {@code "ultipanel.capabilities."}
     */
    public static String configBasePath() {
        return "ultipanel.capabilities.";
    }

    /**
     * This capability's config key suffix (e.g. {@code "commands"}).
     *
     * @return the config key suffix, or {@code null} for {@link #NONE}
     */
    public String getConfigKey() {
        return configKey;
    }

    /**
     * The D-08 shipped default this capability resolves to when its key is absent from
     * {@code config.yml}.
     *
     * @return the shipped default
     */
    public boolean getDefaultEnabled() {
        return defaultEnabled;
    }

    /**
     * The explanatory comment lines the capability migration attaches when it writes this key for
     * the first time. English first with Chinese as supplement.
     *
     * @return an unmodifiable list of comment lines
     */
    public List<String> getCommentLines() {
        return commentLines;
    }

    /**
     * The full dotted config path for this capability.
     *
     * @return e.g. {@code "ultipanel.capabilities.commands"}
     */
    public String getConfigPath() {
        return configBasePath() + configKey;
    }

    /**
     * Whether this capability is currently enabled.
     * <p>
     * Never throws and never propagates a config-read failure — modelled on
     * {@code ErrorReportCollector.loadConfiguration()}'s try/catch shape. When
     * {@link UltiTools#getInstance()} is {@code null} or its config is unavailable, this returns
     * the constant's own {@link #getDefaultEnabled()} rather than a swallowed
     * {@code NullPointerException}, so a test harness without a live config sees shipped
     * behaviour. Not cached — the operator may reload config.
     *
     * @return {@code true} if this capability is enabled
     */
    public boolean isEnabled() {
        if (this == NONE) {
            return true;
        }
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return defaultEnabled;
            }
            return instance.getConfig().getBoolean(getConfigPath(), defaultEnabled);
        } catch (Exception e) {
            return defaultEnabled;
        }
    }

    /**
     * A refusal sentence for a capability whose gate an operator CAN change through configuration.
     * Names the exact key and file, per D-05/D-13/D-17. This and {@link #nonConfigurableRefusal}
     * are the single source of refusal wording for the whole phase — callers must not format their
     * own refusal strings.
     *
     * @param configKey the full dotted config path that would flip this refusal
     * @return the refusal sentence
     */
    public static String configurableRefusal(String configKey) {
        return "Blocked by policy — edit '" + configKey
                + "' in plugins/UltiTools/config.yml to change this.";
    }

    /**
     * A refusal sentence for a restriction no configuration can lift. States plainly that it
     * cannot be changed, per D-17's requirement that the two refusal causes never collapse into
     * one sentence.
     *
     * @param reason why the restriction exists
     * @return the refusal sentence
     */
    public static String nonConfigurableRefusal(String reason) {
        return "Blocked — " + reason + ". This restriction cannot be changed through configuration.";
    }

    /**
     * This capability's own refusal message, naming its config path.
     *
     * @return the refusal sentence produced by {@link #configurableRefusal(String)} for this
     *         capability's own config path
     */
    public String refusalMessage() {
        return configurableRefusal(getConfigPath());
    }
}
