package com.ultikits.ultitools.commands;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned corecommand messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class CoreCommandKeys {
    public static final MessageKey UL_RELOAD_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ul.reload.failed");
    public static final MessageKey UL_RELOAD_DONE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ul.reload.done");
    public static final MessageKey UL_RELOAD_NOT_FOUND = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ul.reload.not_found");
    public static final MessageKey UL_HELP = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ul.help");
    public static final MessageKey ULTICLOUD_LOGOUT_UNLINKING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.unlinking");
    public static final MessageKey ULTICLOUD_LOGOUT_REVOKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.revoked");
    public static final MessageKey ULTICLOUD_LOGOUT_LINK_AGAIN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.link_again");
    public static final MessageKey ULTICLOUD_LOGOUT_REVOKE_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.revoke_failed");
    public static final MessageKey ULTICLOUD_LOGOUT_REVOKE_IN_PANEL = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.revoke_in_panel");
    public static final MessageKey ULTICLOUD_LOGOUT_CANCELLED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.cancelled");
    public static final MessageKey ULTICLOUD_LOGOUT_LOCAL_DELETE_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.local_delete_failed");
    public static final MessageKey ULTICLOUD_LOGOUT_STOPPED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.stopped");
    public static final MessageKey ULTICLOUD_LOGOUT_NOT_LINKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.logout.not_linked");
    public static final MessageKey ULTICLOUD_LOGIN_INVALID_CODE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.invalid_code");
    public static final MessageKey ULTICLOUD_RECONNECT_STARTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.started");
    public static final MessageKey ULTICLOUD_RECONNECT_STATUS_HINT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.status_hint");
    public static final MessageKey ULTICLOUD_RECONNECT_ALREADY_CONNECTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.already_connected");
    public static final MessageKey ULTICLOUD_RECONNECT_ALREADY_CONNECTING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.already_connecting");
    public static final MessageKey ULTICLOUD_RECONNECT_UPGRADE_REQUIRED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.upgrade_required");
    public static final MessageKey ULTICLOUD_RATE_LIMITED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.rate_limited");
    public static final MessageKey ULTICLOUD_RECONNECT_NOT_LINKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.not_linked");
    public static final MessageKey ULTICLOUD_RECONNECT_SERVER_ID_MISMATCH = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.reconnect.server_id_mismatch");
    public static final MessageKey ULTICLOUD_PANEL_REFUSED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.panel_refused");
    public static final MessageKey ULTICLOUD_STATUS_LINKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.status.linked");
    public static final MessageKey ULTICLOUD_STATUS_UNNAMED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.status.unnamed");
    public static final MessageKey ULTICLOUD_STATUS_CONNECTION = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.status.connection");
    public static final MessageKey ULTICLOUD_STATUS_NOT_LINKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.status.not_linked");
    public static final MessageKey ULTICLOUD_STATUS_LOGIN_HINT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.status.login_hint");
    public static final MessageKey ULTICLOUD_HELP_HEADER = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.help.header");
    public static final MessageKey ULTICLOUD_HELP_LOGIN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.help.login");
    public static final MessageKey ULTICLOUD_HELP_LOGIN_CODE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.help.login_code");
    public static final MessageKey ULTICLOUD_HELP_LOGOUT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.help.logout");
    public static final MessageKey ULTICLOUD_HELP_STATUS = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.help.status");
    public static final MessageKey ULTICLOUD_HELP_RECONNECT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.help.reconnect");
    public static final MessageKey ULTICLOUD_LOGIN_REQUESTING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.requesting");
    public static final MessageKey ULTICLOUD_LOGIN_REQUESTING_REMOTE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.requesting_remote");
    public static final MessageKey ULTICLOUD_LOGIN_REDEEMING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.redeeming");
    public static final MessageKey ULTICLOUD_LOGIN_REDEEMING_REMOTE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.redeeming_remote");
    public static final MessageKey ULTICLOUD_LOGIN_ALREADY_LINKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.already_linked");
    public static final MessageKey ULTICLOUD_LOGIN_SEPARATOR = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.separator");
    public static final MessageKey ULTICLOUD_LOGIN_OPEN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.open");
    public static final MessageKey ULTICLOUD_LOGIN_URI = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.uri");
    public static final MessageKey ULTICLOUD_LOGIN_ENTER_CODE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.enter_code");
    public static final MessageKey ULTICLOUD_LOGIN_CODE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.code");
    public static final MessageKey ULTICLOUD_LOGIN_DIRECT_LINK = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.direct_link");
    public static final MessageKey ULTICLOUD_LOGIN_EXPIRES = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.expires");
    public static final MessageKey ULTICLOUD_LOGIN_CODE_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.code_failed");
    public static final MessageKey ULTICLOUD_LOGIN_REQUEST_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ulticloud.login.request_failed");

    private CoreCommandKeys() {
        // Static keys only.
    }
}
