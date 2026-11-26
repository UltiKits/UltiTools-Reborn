package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.LegacyTextBaseline;
import com.ultikits.ultitools.UltiToolsKeys;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.handler.HandlerKeys;
import com.ultikits.ultitools.services.impl.ServiceKeys;
import com.ultikits.ultitools.interfaces.impl.data.DataKeys;
import com.ultikits.ultitools.utils.UtilsKeys;
import com.ultikits.ultitools.commands.CoreCommandKeys;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FrameworkCatalogueParityTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final List<Row> ROWS = List.of(
        new Row(UltiToolsKeys.STARTUP_DATA_STORE, "数据存储方式：%s", null, "",
                List.of(Arg.string("store", "s1"))),
        new Row(UltiToolsKeys.STARTUP_ENABLED, "UltiTools-API已启动，当前版本：%s", null, "",
                List.of(Arg.string("version", "s1"))),
        new Row(UltiToolsKeys.STARTUP_SERVER_UUID, "服务器UUID: %s", null, "",
                List.of(Arg.string("uuid", "s1"))),
        new Row(UltiToolsKeys.STARTUP_SERVER_UUID_FAILED, "获取服务器UUID失败！", null, "",
                List.of(Arg.string("error", "s1"))),
        new Row(ManagerKeys.MODULE_DUPLICATE_MAIN_CLASS, "以下 JAR 文件都声明了同一个模块主类 %s：%s。该模块的类只从 %s 加载，其余副本不会加载；请只保留其中一个。", null, "",
                List.of(Arg.string("main_class", "s1"), Arg.string("jars", "s2"), Arg.string("loaded_from", "s3"))),
        new Row(ManagerKeys.MODULE_MISSING_REQUIRED_PLUGIN, "Module '%s' requires %s, which is not installed or not enabled; the module is not loaded.", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("plugins", "s2"))),
        new Row(ManagerKeys.MODULE_API_VERSION_UNREADABLE, "<missing>", null, "",
                List.of(Arg.string("missing", "unescaped-replacement"))),
        new Row(ManagerKeys.MODULE_API_VERSION_INVALID, "Module '%s' has no valid api-version (read: %s); UltiTools-API %s requires an integer api-version of %d or higher -- refusing to load. Install a module rebuilt for the current UltiTools-API.", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("read", "s2"), Arg.string("framework_version", "s3"), Arg.number("min_level", 14566))),
        new Row(ManagerKeys.MODULE_API_VERSION_TOO_LOW, "Module '%s' declares api-version %d, below the minimum %d required by UltiTools-API %s; refusing to load. Install a module rebuilt for the current UltiTools-API; changing api-version alone does not make an old module compatible.", null, "",
                List.of(Arg.string("module", "s1"), Arg.number("level", 5678), Arg.number("min_level", 10122), Arg.string("framework_version", "s4"))),
        new Row(ManagerKeys.MODULE_BOOTSTRAP_FAILED, "module.bootstrap.failed", null, "",
                List.of(Arg.string("module", "s1"))),
        new Row(ManagerKeys.RELOAD_PARTIAL, "Module %s reloaded partially; not reloaded: %s", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("reasons", "s2"))),
        new Row(ManagerKeys.RELOAD_SUMMARY_FAILED, "Failed to reload %d of %d modules: %s. See the console for each failure.", null, "",
                List.of(Arg.number("failed", 1234), Arg.number("total", 5678), Arg.string("modules", "s3"))),
        new Row(ManagerKeys.RELOAD_SUMMARY_MIXED_PARTIAL, "%d of the others reloaded only partially:", null, "",
                List.of(Arg.number("partial", 1234))),
        new Row(ManagerKeys.RELOAD_SUMMARY_PARTIAL, "Reloaded %d modules; %d only partially:", null, "",
                List.of(Arg.number("total", 1234), Arg.number("partial", 5678))),
        new Row(ManagerKeys.RELOAD_SUMMARY_ALL, "All %d modules reloaded.", null, "",
                List.of(Arg.number("total", 1234))),
        new Row(ManagerKeys.CONFIG_BINDING_TIMER_RULE, "a bound @Scheduled period or delay must be a whole number of seconds from 1 to %d (0 does not mean off)", null, "",
                List.of(Arg.number("max", 1234))),
        new Row(ManagerKeys.CONFIG_BINDING_COOLDOWN_RULE, "a bound @CmdCD cooldown must be a whole number of seconds from 0 (no cooldown) to %d", null, "",
                List.of(Arg.number("max", 1234))),
        new Row(ManagerKeys.CONFIG_BINDING_COOLDOWN_INVALID, "it has value %s after the reload; %s", null, "",
                List.of(Arg.string("value", "s1"), Arg.string("rule", "s2"))),
        new Row(ManagerKeys.CONFIG_BINDING_RELOAD_INCOMPLETE, "the reload of %s did not complete (for example, its write-back threw an IOException), so its values were not validated", null, "",
                List.of(Arg.string("file", "s1"))),
        new Row(ManagerKeys.CONFIG_BINDING_SCHEDULED_KEPT, "config-bound @Scheduled %s: %s key '%s' has value %s after the reload (%s); the running %ds is kept", null, "",
                List.of(Arg.string("task", "s1"), Arg.string("config", "s2"), Arg.string("config_key", "s3"), Arg.string("value", "s4"), Arg.string("rule", "s5"), Arg.number("seconds", 23454))),
        new Row(ManagerKeys.CONFIG_BINDING_SCHEDULED_NOT_VALIDATED, "config-bound @Scheduled %s: the reload of %s did not complete, so %s key '%s' was not applied; the running %ds is kept", null, "",
                List.of(Arg.string("task", "s1"), Arg.string("file", "s2"), Arg.string("config", "s3"), Arg.string("config_key", "s4"), Arg.number("seconds", 19010))),
        new Row(ManagerKeys.CONFIG_BINDING_COOLDOWN_KEPT, "config-bound @CmdCD %s was not updated: %s; the running %ds is kept", null, "",
                List.of(Arg.string("binding", "s1"), Arg.string("reason", "s2"), Arg.number("seconds", 10122))),
        new Row(ManagerKeys.CONFIG_BINDING_SCHEDULED_INVALID_WARNING, "%s: %s is bound to %s key '%s', which has value %s after the reload; %s; keeping %ds", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("task", "s2"), Arg.string("config", "s3"), Arg.string("config_key", "s4"), Arg.string("value", "s5"), Arg.string("rule", "s6"), Arg.number("seconds", 27898))),
        new Row(ManagerKeys.CONFIG_BINDING_SCHEDULED_NOT_VALIDATED_WARNING, "%s: %s is bound to %s key '%s', but the reload of %s did not complete (for example, its write-back threw an IOException), so its values were not validated; keeping %ds", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("task", "s2"), Arg.string("config", "s3"), Arg.string("config_key", "s4"), Arg.string("file", "s5"), Arg.number("seconds", 23454))),
        new Row(ManagerKeys.CONFIG_BINDING_COOLDOWN_NOT_UPDATED_WARNING, "%s: @CmdCD bound to %s is not updated: %s; keeping %ds", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("binding", "s2"), Arg.string("reason", "s3"), Arg.number("seconds", 14566))),
        new Row(ManagerKeys.CONFIG_BINDING_STEP_FAILED, "applying the reloaded config-bound %s values failed: %s; updates applied before the failure remain in effect; remaining updates did not complete", null, "",
                List.of(Arg.string("what", "s1"), Arg.string("cause", "s2"))),
        new Row(ManagerKeys.CONFIG_BINDING_OFF_THREAD, "config-bound @Scheduled and @CmdCD values were not applied: the reload ran off the main thread", null, "",
                List.of()),
        new Row(ManagerKeys.DATASTORE_REASON_MYSQL_DISABLED, "mysql.enable 为 false，MySQL 数据源从未初始化", null, "",
                List.of()),
        new Row(ManagerKeys.DATASTORE_REASON_MYSQL_FAILED, "MySQL 连接失败，数据源不可用（详见上方的连接错误）", null, "",
                List.of()),
        new Row(ManagerKeys.DATASTORE_REASON_NO_PROVIDER, "没有已注册的数据存储提供该类型", null, "",
                List.of()),
        new Row(ManagerKeys.DATASTORE_DOWNGRADED, "数据存储降级：config.yml 里 datasource.type 配置为 '%s'，实际使用的是 '%s'。原因：%s", null, "",
                List.of(Arg.string("configured", "s1"), Arg.string("actual", "s2"), Arg.string("reason", "s3"))),
        new Row(ManagerKeys.DATASTORE_PLAYER_DATA_STORE, "玩家数据将读写 '%s' 存储；不同后端之间的数据并不互通，如果这不是你要的，请停服修正 config.yml 后重启", null, "",
                List.of(Arg.string("store", "s1"))),
        new Row(ManagerKeys.UPDATE_CHECKING, "正在检查版本更新...", null, "",
                List.of()),
        new Row(ManagerKeys.UPDATE_FRAMEWORK_AVAILABLE, "UltiTools-API有新版本 %s 可用（当前：%s）", null, "",
                List.of(Arg.string("latest", "s1"), Arg.string("current", "s2"))),
        new Row(ManagerKeys.UPDATE_DOWNLOAD, "下载地址：%s", null, "",
                List.of(Arg.string("url", "s1"))),
        new Row(ManagerKeys.UPDATE_MODULES_AVAILABLE, "模块更新可用（%d个）：", null, "",
                List.of(Arg.number("count", 1234))),
        new Row(ManagerKeys.UPDATE_INCOMPLETE, "更新检查未完成：无法确认 %s 的最新版本。", null, "",
                List.of(Arg.string("modules", "s1"))),
        new Row(ManagerKeys.UPDATE_UP_TO_DATE, "所有插件已是最新版本！", null, "",
                List.of()),
        new Row(ServiceKeys.TELEPORT_FAILED_TITLE, "传送失败！", null, "§c",
                List.of()),
        new Row(ServiceKeys.TELEPORT_FAILED_SUBTITLE, "请勿移动！", null, "",
                List.of()),
        new Row(ServiceKeys.TELEPORT_SUCCESS_TITLE, "传送成功！", null, "§a",
                List.of()),
        new Row(ServiceKeys.TELEPORT_PENDING_TITLE, "传送中...", null, "§a",
                List.of()),
        new Row(ServiceKeys.TELEPORT_PENDING_SUBTITLE, "离传送还有%d秒", null, "",
                List.of(Arg.number("seconds", 1234))),
        new Row(DataKeys.DATA_JSON_CORRUPTED, "发现一个数据损坏！位置：%s", null, "§c",
                List.of(Arg.string("path", "s1"))),
        new Row(DataKeys.DATA_MYSQL_CONNECTION_FAILED, "Mysql数据库连接失败：", null, "",
                List.of(Arg.string("error", "s1"))),
        new Row(UtilsKeys.CLOUD_LEGACY_TOKEN_REMOVED, "The account login saved by an earlier UltiTools version is no longer used and was removed from %s. Run /ulticloud login to re-attach this server.", null, "",
                List.of(Arg.string("file", "s1"))),
        new Row(HandlerKeys.PANEL_SYSLOG_CONFIG_LOADED, "[UltiPanel] 系统日志处理器配置已加载", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_CONFIG_LOAD_FAILED, "[UltiPanel] 加载日志配置失败，使用默认配置: %s", null, "",
                List.of(Arg.string("error", "s1"))),
        new Row(HandlerKeys.PANEL_SYSLOG_RECORD_FAILED, "[UltiPanel] SystemLogHandler处理日志记录失败: %s", null, "",
                List.of(Arg.string("error", "s1"))),
        new Row(HandlerKeys.PANEL_SYSLOG_PARAMETERS, " [参数: ", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_TITLE, "SystemLogHandler配置信息:", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_MINIMUM_LEVEL, "- 最小日志级别: ", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_ENABLED_LEVELS, "- 启用的级别: ", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_EXCLUDED_LOGGERS, "- 排除的记录器数量: ", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_TRANSMITTER, "- 日志传输器状态: ", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_CONNECTED, "已连接", null, "",
                List.of()),
        new Row(HandlerKeys.PANEL_SYSLOG_SUMMARY_NOT_CONNECTED, "未连接", null, "",
                List.of()),
        new Row(ManagerKeys.REMOTE_COMMAND_BLOCKED, "[远程命令] 已拦截: %s", null, "",
                List.of(Arg.string("command", "s1"))),
        new Row(ManagerKeys.REMOTE_COMMAND_ECHO, "[远程命令] > %s", null, "",
                List.of(Arg.string("command", "s1"))),
        new Row(ManagerKeys.REMOTE_COMMAND_FAILED, "[远程命令] 命令执行异常: %s", null, "",
                List.of(Arg.string("command", "s1"))),
        new Row(ManagerKeys.PANEL_LOG_STREAM_LISTENER_FAILED, "注册事件监听器失败: %s", null, "",
                List.of(Arg.string("error", "s1"))),
        new Row(ManagerKeys.MONITOR_CPU_UNAVAILABLE, "无法获取CPU使用率: %s", null, "",
                List.of(Arg.string("error", "s1"))),
        new Row(ManagerKeys.MONITOR_OFF_MAIN_THREAD, "[ServerMonitor] 试图在非主线程上采样 Bukkit 状态，已拒绝。这是一个编程错误，请检查调度。", null, "",
                List.of()),
        new Row(ManagerKeys.MONITOR_SAMPLE_FAILED, "[ServerMonitor] 采样服务器状态失败", null, "",
                List.of()),
        new Row(ManagerKeys.MONITOR_TPS_UPDATE, "TPS更新: 当前TPS=%.2f, 时间间隔=%dms", null, "",
                List.of(Arg.number("tps", 19.5), Arg.number("interval", 5678))),
        new Row(ManagerKeys.SERVER_PROPERTIES_BATCH_INCOMPLETE, "server.properties 批量设置未完全生效", null, "",
                List.of()),
        new Row(ManagerKeys.SERVER_PROPERTIES_BATCH_REJECTED, "；不在白名单因而被拒的键: ", null, "",
                List.of()),
        new Row(ManagerKeys.SERVER_PROPERTIES_BATCH_WRITE_FAILED, "；写入失败的键: ", null, "",
                List.of()),
        new Row(ManagerKeys.SERVER_PROPERTIES_BATCH_MALFORMED, "；值不是字符串或数字因而无法写入的键: ", null, "",
                List.of()),
        new Row(ManagerKeys.SERVER_PROPERTIES_BATCH_NOT_PRESENT, "；本服务器版本没有的键: ", null, "",
                List.of()),
        new Row(ManagerKeys.PANEL_LOG_ENABLED, "[UltiPanel] 日志传输已启用", null, "",
                List.of()),
        new Row(ManagerKeys.PANEL_LOG_DISABLED, "[UltiPanel] 日志传输已禁用", null, "",
                List.of()),
        new Row(ManagerKeys.PANEL_LOG_CLOSED, "[UltiPanel] 日志传输器已关闭", null, "",
                List.of()),
        new Row(UtilsKeys.CLOUD_SESSION_SERVER_ID_MISMATCH, "UltiCloud answered this server's connection with a different server ID than the one it is linked to. Reconnecting has stopped and the credential is kept. Contact UltiCloud support, or run /ulticloud logout and then /ulticloud login to link this server again.", null, "",
                List.of()),
        new Row(UtilsKeys.INSTALL_REASON_LICENCE_REQUIRED, "无法安装 %s %s：该版本需要许可证，不提供公开下载。模块目录没有任何改动。", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("version", "s2"))),
        new Row(UtilsKeys.INSTALL_REASON_INTEGRITY_FAILED, "已拒绝安装 %s %s：文件没有通过云端目录的完整性校验（SHA-256 或大小与目录不符，或目录没有给出可用的校验值）。模块目录没有任何改动。", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("version", "s2"))),
        new Row(CoreCommandKeys.UL_RELOAD_FAILED, "Module %s failed to reload: %s. See the console for details.", null, "",
                List.of(Arg.string("module", "s1"), Arg.string("error", "s2"))),
        new Row(CoreCommandKeys.UL_RELOAD_DONE, "模块 %s 已重载", null, "",
                List.of(Arg.string("module", "s1"))),
        new Row(CoreCommandKeys.UL_RELOAD_NOT_FOUND, "模块 %s 不存在，请使用 /ul list 查看已加载的模块", null, "",
                List.of(Arg.string("module", "s1"))),
        new Row(CoreCommandKeys.UL_HELP, "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n/ul reload <模块名> 重载指定模块\n/ul list 查看已加载的模块列表\n================", null, "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_UNLINKING, "Unlinking this server from UltiCloud...", null, "§b",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_REVOKED, "Unlinked this server from UltiCloud and revoked its credential. Cloud features are now stopped.", null, "§a",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_LINK_AGAIN, "Use /ulticloud login to link it again.", null, "§7",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_REVOKE_FAILED, "Unlinked this server locally, but UltiCloud could not be reached to revoke its credential.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_REVOKE_IN_PANEL, "The cloud credential may still be valid. Revoke it in the panel: server settings > Revoke credential.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_CANCELLED, "Cancelled the pending UltiCloud link request. Cloud features are stopped.", null, "§a",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_LOCAL_DELETE_FAILED, "Could not delete this server's UltiCloud credential from the local credentials file. Check the server log, then run /ulticloud logout again.", null, "§c",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_STOPPED, "Cloud features have been stopped regardless.", null, "§7",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGOUT_NOT_LINKED, "This server is not linked to UltiCloud.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_INVALID_CODE, "That is not a valid UltiCloud re-pair code. Copy the whole code from the panel and try again.", null, "§c",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_STARTED, "Reconnecting to UltiCloud now.", null, "§a",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_STATUS_HINT, "Use /ulticloud status to see the result.", null, "§7",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_ALREADY_CONNECTED, "This server is already connected to UltiCloud.", null, "§a",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_ALREADY_CONNECTING, "A connection attempt to UltiCloud is already in progress.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_UPGRADE_REQUIRED, "UltiCloud no longer accepts this UltiTools version. Upgrade UltiTools, then restart the server.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RATE_LIMITED, "Please wait %d seconds before trying again.", null, "§c",
                List.of(Arg.number("seconds", 1234))),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_NOT_LINKED, "This server is not linked to UltiCloud. Use /ulticloud login to link it.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_RECONNECT_SERVER_ID_MISMATCH, "Reconnecting stays stopped: UltiCloud answered with a different server ID than this server is linked to. Contact UltiCloud support, or run /ulticloud logout and then /ulticloud login.", null, "§e",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_PANEL_REFUSED, null, "§cUltiCloud login/logout must be run from the server console, not the panel.", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_STATUS_LINKED, null, "§aUltiCloud: Linked as %s (server ID %s)", "",
                List.of(Arg.string("name", "s1"), Arg.string("server_id", "s2"))),
        new Row(CoreCommandKeys.ULTICLOUD_STATUS_UNNAMED, null, "an unnamed server", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_STATUS_CONNECTION, null, "§7Connection: %s", "",
                List.of(Arg.string("connection", "s1"))),
        new Row(CoreCommandKeys.ULTICLOUD_STATUS_NOT_LINKED, null, "§eUltiCloud: Not linked", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_STATUS_LOGIN_HINT, null, "§7Use /ulticloud login to link this server.", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_HELP_HEADER, null, "§b=== UltiCloud Commands ===", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_HELP_LOGIN, null, "§f/ulticloud login§7 - Link this server to UltiCloud", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_HELP_LOGIN_CODE, null, "§f/ulticloud login <code>§7 - Reattach this server with the re-pair code from the panel", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_HELP_LOGOUT, null, "§f/ulticloud logout§7 - Unlink this server and revoke its credential", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_HELP_STATUS, null, "§f/ulticloud status§7 - Show link and connection status", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_HELP_RECONNECT, null, "§f/ulticloud reconnect§7 - Retry the UltiCloud connection now", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_REQUESTING, null, "§bRequesting a device code from UltiCloud...", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_REQUESTING_REMOTE, null, "§bRequesting a device code from UltiCloud. The code and the link will be printed in the server console.", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_REDEEMING, null, "§bRedeeming the re-pair code with UltiCloud...", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_REDEEMING_REMOTE, null, "§bRedeeming the re-pair code with UltiCloud. The result will be printed in the server console.", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_ALREADY_LINKED, null, "§eThis server is already linked to UltiCloud as %s. Use /ulticloud logout first to link it again.", "",
                List.of(Arg.string("server", "s1"))),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_SEPARATOR, null, "§a========================================", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_OPEN, null, "§a To link this server to UltiCloud, open:", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_URI, null, "§b %s", "",
                List.of(Arg.string("uri", "s1"))),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_ENTER_CODE, null, "§a and enter the code:", "",
                List.of()),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_CODE, null, "§b %s", "",
                List.of(Arg.string("code", "s1"))),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_DIRECT_LINK, null, "§7 Or open this link directly: %s", "",
                List.of(Arg.string("uri", "s1"))),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_EXPIRES, null, "§7The code expires in %d minutes. Waiting for confirmation...", "",
                List.of(Arg.number("minutes", 1234))),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_CODE_FAILED, null, "§cCould not use the re-pair code: %s", "",
                List.of(Arg.string("reason", "s1"))),
        new Row(CoreCommandKeys.ULTICLOUD_LOGIN_REQUEST_FAILED, null, "§cFailed to get a device code: %s", "",
                List.of(Arg.string("reason", "s1"))),
        new Row(ManagerKeys.UPDATE_JOIN_NOTICE, null, "§a[UltiTools] §e%d update(s) available. Run /upm check for details.", "",
                List.of(Arg.number("count", 1234))));

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("translations")
    void everyNewKey_preservesLegacyTextAndColours(Locale locale, Row row) throws IOException {
        JsonObject legacy = LegacyTextBaseline.read(locale);
        String template = row.legacyKey() == null ? row.hardCodedEnglish()
                : legacy.has(row.legacyKey()) ? legacy.get(row.legacyKey()).getAsString() : row.legacyKey();
        Object[] samples = row.args().stream().map(FrameworkCatalogueParityTest::sample).toArray();
        String expected = row.colourPrefix() + String.format(Locale.ROOT, template, samples);
        if (row.key().equals(UltiToolsKeys.STARTUP_SERVER_UUID_FAILED)
                || row.key().equals(DataKeys.DATA_MYSQL_CONNECTION_FAILED)) {
            expected += samples[samples.length - 1];
        }
        Catalogue catalogue = new CatalogueLoader().load(MessageKey.FRAMEWORK_NAMESPACE, Locale.ENGLISH,
                FrameworkKeys.class.getProtectionDomain().getCodeSource().getLocation()).catalogue();
        List<LogRecord> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }
            @Override public void flush() { /* Records are collected synchronously. */ }
            @Override public void close() { /* No external resources. */ }
        });
        MessageRenderer renderer = new MessageRenderer(ns -> catalogue,
                new FixedLocaleResolver(locale, Optional.empty()), logger);
        Component actual = renderer.render(Text.of(row.key(), row.args().toArray(Arg[]::new)), locale);
        assertThat(warnings).as("no missing keys or format warnings: %s", row.key()).isEmpty();
        assertThat(PLAIN.serialize(actual)).isEqualTo(expected.replaceAll("§.", ""));
        if (expected.contains("§")) {
            assertThat(LEGACY.serialize(actual)).as("colours: %s", row.key()).isEqualTo(expected);
        }
    }

    @Test
    void inventory_coversExactly125DistinctKeys() {
        assertThat(ROWS).hasSize(125);
        assertThat(new HashSet<>(ROWS.stream().map(Row::key).toList())).hasSize(125);
    }

    @Test
    void positiveControl_registeredBareTagsAreReplacedButEscapedLiteralsAreNot() {
        // Unknown tags survive MiniMessage's lenient mode; registered arguments do not.
        assertThat(PLAIN.serialize(MiniMessage.miniMessage().deserialize("<missing>"))).isEqualTo("<missing>");
        var argument = Placeholder.unparsed("min_level", "1234");
        assertThat(PLAIN.serialize(MiniMessage.miniMessage().deserialize("<min_level>", argument)))
                .isEqualTo("1234").isNotEqualTo("<min_level>");
        assertThat(PLAIN.serialize(MiniMessage.miniMessage().deserialize("\\<min_level>", argument)))
                .isEqualTo("<min_level>");
    }

    private static Stream<Arguments> translations() {
        return Stream.of(Locale.ENGLISH, Locale.CHINESE)
                .flatMap(locale -> ROWS.stream().map(row -> Arguments.of(locale, row)));
    }


    private static Object sample(Arg arg) {
        return switch (arg) {
            case Arg.Unparsed string -> string.value();
            case Arg.Numeric number -> number.value();
            default -> throw new IllegalArgumentException("Unexpected parity sample: " + arg);
        };
    }

    private record Row(MessageKey key, String legacyKey, String hardCodedEnglish,
            String colourPrefix, List<Arg> args) {
        @Override public String toString() { return key.path(); }
    }
}
