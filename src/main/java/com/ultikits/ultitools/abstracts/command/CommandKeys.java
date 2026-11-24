package com.ultikits.ultitools.abstracts.command;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned command feedback; rendered only when its recipient is known. */
@ApiStatus.Internal
public final class CommandKeys {
    public static final MessageKey OP_REQUIRED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.op_required");
    public static final MessageKey NO_PERMISSION = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.no_permission");
    public static final MessageKey PLAYER_ONLY = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.player_only");
    public static final MessageKey CONSOLE_ONLY = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.console_only");
    public static final MessageKey SENDER_LOCKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.sender_locked");
    public static final MessageKey SERVER_LOCKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.server_locked");
    public static final MessageKey COOLDOWN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.cooldown");
    public static final MessageKey EXECUTION_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.error.execution_failed");
    public static final MessageKey PROCESSING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.async.processing");
    public static final MessageKey TIMEOUT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.async.timeout");
    public static final MessageKey UNKNOWN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.reply.unknown");
    public static final MessageKey USAGE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.reply.usage");
    public static final MessageKey ARGUMENT_ERROR = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "command.reply.argument_error");

    private CommandKeys() {
        // Static keys only.
    }
}
