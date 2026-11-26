package com.ultikits.ultitools.utils;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.text.MessageKey;

/** Framework-owned /upm and pending-change messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class UpmKeys {
    public static final MessageKey CHECK_DOWNLOAD =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.download");
    public static final MessageKey CHECK_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.failed");
    public static final MessageKey CHECK_FRAMEWORK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.framework");
    public static final MessageKey CHECK_HEADER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.header");
    public static final MessageKey CHECK_INCOMPLETE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.incomplete");
    public static final MessageKey CHECK_MODULE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.module");
    public static final MessageKey CHECK_NONE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.none");
    public static final MessageKey CHECK_RUNNING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.check.running");
    public static final MessageKey HELP_CHECK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.check");
    public static final MessageKey HELP_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.install");
    public static final MessageKey HELP_INSTALL_VERSION =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.install_version");
    public static final MessageKey HELP_LIST =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.list");
    public static final MessageKey HELP_PENDING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.pending");
    public static final MessageKey HELP_REVOKE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.revoke");
    public static final MessageKey HELP_TITLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.title");
    public static final MessageKey HELP_UNINSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.uninstall");
    public static final MessageKey HELP_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.update");
    public static final MessageKey HELP_UPDATE_ALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.update_all");
    public static final MessageKey HELP_VERSIONS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.help.versions");
    public static final MessageKey INSTALL_ALREADY_CURRENT =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.already_current");
    public static final MessageKey INSTALL_AS_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.as_update");
    public static final MessageKey INSTALL_CANCELLED_UNINSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.cancelled_uninstall");
    public static final MessageKey INSTALL_CANCELLED_UNINSTALL_SAME =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.cancelled_uninstall_same");
    public static final MessageKey INSTALL_CANCELLED_UPDATE_SAME =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.cancelled_update_same");
    public static final MessageKey INSTALL_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.failed");
    public static final MessageKey INSTALL_NOT_STAGED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.not_staged");
    public static final MessageKey INSTALL_NOT_RECORDED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.not_recorded");
    public static final MessageKey INSTALL_QUEUED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.queued");
    public static final MessageKey INSTALL_REFUSED_INTEGRITY_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.integrity_failed");
    public static final MessageKey INSTALL_REFUSED_JAR_INVALID =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.jar_invalid");
    public static final MessageKey INSTALL_REFUSED_JAR_LEVEL_REFUSED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.jar_level_refused");
    public static final MessageKey INSTALL_REFUSED_JAR_NO_LEVEL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.jar_no_level");
    public static final MessageKey INSTALL_REFUSED_LICENCE_REQUIRED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.licence_required");
    public static final MessageKey INSTALL_REFUSED_NO_COMPATIBLE_VERSION =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.no_compatible_version");
    public static final MessageKey INSTALL_REFUSED_TARGET_EXISTS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.target_exists");
    public static final MessageKey INSTALL_REFUSED_VERSION_INCOMPATIBLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.refused.version_incompatible");
    public static final MessageKey INSTALL_REPLACED_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.install.replaced_install");
    public static final MessageKey LIST_ENTRY_INSTALL_BUTTON =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.install_button");
    public static final MessageKey LIST_ENTRY_INSTALL_COMMAND =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.install_command");
    public static final MessageKey LIST_ENTRY_INSTALL_HOVER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.install_hover");
    public static final MessageKey LIST_ENTRY_INTRO =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.intro");
    public static final MessageKey LIST_ENTRY_NAME =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.name");
    public static final MessageKey LIST_ENTRY_STATUS_INSTALLED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.status.installed");
    public static final MessageKey LIST_ENTRY_STATUS_NOT_INSTALLED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.status.not_installed");
    public static final MessageKey LIST_ENTRY_UNINSTALL_BUTTON =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.uninstall_button");
    public static final MessageKey LIST_ENTRY_UNINSTALL_HOVER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.entry.uninstall_hover");
    public static final MessageKey LIST_HEADER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.header");
    public static final MessageKey LIST_PAGE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.list.page");
    public static final MessageKey PENDING_BUSY =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.busy");
    public static final MessageKey PENDING_EMPTY =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.empty");
    public static final MessageKey PENDING_ENTRY_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.entry.install");
    public static final MessageKey PENDING_ENTRY_REQUESTED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.entry.requested");
    public static final MessageKey PENDING_ENTRY_UNINSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.entry.uninstall");
    public static final MessageKey PENDING_ENTRY_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.entry.update");
    public static final MessageKey PENDING_FOOTER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.footer");
    public static final MessageKey PENDING_HEADER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.header");
    public static final MessageKey PENDING_LAST_START_APPLIED_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.applied.install");
    public static final MessageKey PENDING_LAST_START_APPLIED_UNINSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.applied.uninstall");
    public static final MessageKey PENDING_LAST_START_APPLIED_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.applied.update");
    public static final MessageKey PENDING_LAST_START_HEADER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.header");
    public static final MessageKey PENDING_LAST_START_NEEDS_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.needs_operator");
    public static final MessageKey PENDING_LAST_START_NOT_APPLIED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.not_applied");
    public static final MessageKey PENDING_LAST_START_ROLLED_BACK_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.rolled_back.install");
    public static final MessageKey PENDING_LAST_START_ROLLED_BACK_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.last_start.rolled_back.update");
    public static final MessageKey PENDING_NEEDS_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.needs_operator");
    public static final MessageKey PENDING_NOTICE_LAST_START_OK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.notice.last_start_ok");
    public static final MessageKey PENDING_NOTICE_LAST_START_PROBLEMS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.notice.last_start_problems");
    public static final MessageKey PENDING_NOTICE_WAITING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.notice.waiting");
    public static final MessageKey PENDING_PREVIOUS_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.previous_failed");
    public static final MessageKey PENDING_REQUESTER_CONSOLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.requester.console");
    public static final MessageKey PENDING_REQUESTER_PLAYER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.requester.player");
    public static final MessageKey PENDING_REQUESTER_REMOTE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.requester.remote");
    public static final MessageKey PENDING_REQUESTER_UNKNOWN =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.requester.unknown");
    public static final MessageKey PENDING_STATE_DOWNLOADING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.state.downloading");
    public static final MessageKey PENDING_STATE_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.state.failed");
    public static final MessageKey PENDING_STATE_IN_PROGRESS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.state.in_progress");
    public static final MessageKey PENDING_STATE_NEEDS_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.state.needs_operator");
    public static final MessageKey PENDING_TRUNCATED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.truncated");
    public static final MessageKey PENDING_UNCHANGED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.unchanged");
    public static final MessageKey PENDING_UNFINISHED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.unfinished");
    public static final MessageKey PENDING_WARNING_DATAPACK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.warning.datapack");
    public static final MessageKey PENDING_WARNING_ENCHANTMENT =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.warning.enchantment");
    public static final MessageKey PENDING_WARNING_OTHER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.warning.other");
    public static final MessageKey PENDING_WARNING_REVOKE_HINT =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.warning.revoke_hint");
    public static final MessageKey PENDING_WARNING_TAG =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.warning.tag");
    public static final MessageKey PENDING_WARNING_UNKNOWN =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.pending.warning.unknown");
    public static final MessageKey REASON_ACCESS_DENIED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.access_denied");
    public static final MessageKey REASON_AMBIGUOUS_IDENTIFY_STRING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.ambiguous_identify_string");
    public static final MessageKey REASON_CANCELLED_WHILE_DOWNLOADING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.cancelled_while_downloading");
    public static final MessageKey REASON_DOWNLOAD_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.download_failed");
    public static final MessageKey REASON_INCOMPATIBLE_API_LEVEL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.incompatible_api_level");
    public static final MessageKey REASON_INVALID_JAR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.invalid_jar");
    public static final MessageKey REASON_LEFTOVER_BACKUP =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.leftover_backup");
    public static final MessageKey REASON_NEEDS_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.needs_operator");
    public static final MessageKey REASON_NO_API_LEVEL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.no_api_level");
    public static final MessageKey REASON_NO_COMPATIBLE_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.no_compatible_update");
    public static final MessageKey REASON_NO_DOWNLOAD =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.no_download");
    public static final MessageKey REASON_NO_IDENTIFY_STRING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.no_identify_string");
    public static final MessageKey REASON_NOT_IN_MODULES_FOLDER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.not_in_modules_folder");
    public static final MessageKey REASON_NOT_LOADED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.not_loaded");
    public static final MessageKey REASON_OLD_JAR_UNREADABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.old_jar_unreadable");
    public static final MessageKey REASON_OTHER_COPY_LOADS_FIRST =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.other_copy_loads_first");
    public static final MessageKey REASON_PREVIOUS_UNRESTORED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.previous_unrestored");
    public static final MessageKey REASON_RECORD_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.record_failed");
    public static final MessageKey REASON_RECORD_UNREADABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.record_unreadable");
    public static final MessageKey REASON_SHARED_JAR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.shared_jar");
    public static final MessageKey REASON_STAGED_UNREADABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.staged_unreadable");
    public static final MessageKey REASON_WRONG_IDENTITY =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.wrong_identity");
    public static final MessageKey REASON_WRONG_VERSION =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.reason.wrong_version");
    public static final MessageKey REVOKE_DONE_DOWNLOADING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.done.downloading");
    public static final MessageKey REVOKE_DONE_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.done.install");
    public static final MessageKey REVOKE_DONE_UNINSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.done.uninstall");
    public static final MessageKey REVOKE_DONE_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.done.update");
    public static final MessageKey REVOKE_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.failed");
    public static final MessageKey REVOKE_NOT_FOUND =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.not_found");
    public static final MessageKey REVOKE_NOT_REVOCABLE_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.not_revocable.failed");
    public static final MessageKey REVOKE_NOT_REVOCABLE_IN_PROGRESS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.not_revocable.in_progress");
    public static final MessageKey REVOKE_NOT_REVOCABLE_NEEDS_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.revoke.not_revocable.needs_operator");
    public static final MessageKey START_INSTALL_CLEANUP_DEFERRED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.cleanup_deferred");
    public static final MessageKey START_INSTALL_COMMITTED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.committed");
    public static final MessageKey START_INSTALL_CROSS_FILE_SYSTEM =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.cross_file_system");
    public static final MessageKey START_INSTALL_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.failed");
    public static final MessageKey START_INSTALL_NOT_RECORDED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.not_recorded");
    public static final MessageKey START_INSTALL_ROLLBACK_DEFERRED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.rollback_deferred");
    public static final MessageKey START_INSTALL_ROLLED_BACK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.rolled_back");
    public static final MessageKey START_INSTALL_TARGET_EXISTS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.target_exists");
    public static final MessageKey START_INSTALL_UNCONFIRMED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.install.unconfirmed");
    public static final MessageKey START_RECORDS_UNLISTABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.records.unlistable");
    public static final MessageKey START_RECORD_REFUSED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.record.refused");
    public static final MessageKey START_RECORD_TRANSACTION_ERROR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.record.transaction_error");
    public static final MessageKey START_RECORD_UNREADABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.record.unreadable");
    public static final MessageKey START_RECORD_WRITE_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.record.write_failed");
    public static final MessageKey START_REMOVAL_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.removal.failed");
    public static final MessageKey START_REMOVAL_REMOVED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.removal.removed");
    public static final MessageKey START_REMOVAL_SKIPPED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.removal.skipped");
    public static final MessageKey START_REMOVAL_UNREADABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.removal.unreadable");
    public static final MessageKey START_UPDATE_ABANDONED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.abandoned");
    public static final MessageKey START_UPDATE_APPLY_CROSS_FILE_SYSTEM =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.apply_cross_file_system");
    public static final MessageKey START_UPDATE_APPLY_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.apply_failed");
    public static final MessageKey START_UPDATE_APPLY_FAILED_UNRESTORED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.apply_failed_unrestored");
    public static final MessageKey START_UPDATE_CLEANUP_DEFERRED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.cleanup_deferred");
    public static final MessageKey START_UPDATE_COMMITTED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.committed");
    public static final MessageKey START_UPDATE_NEEDS_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.needs_operator");
    public static final MessageKey START_UPDATE_ORPHAN_BACKUP =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.orphan_backup");
    public static final MessageKey START_UPDATE_RESTORE_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.restore_failed");
    public static final MessageKey START_UPDATE_ROLLBACK_DEFERRED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.rollback_deferred");
    public static final MessageKey START_UPDATE_ROLLBACK_FAILED_BEFORE_LOAD =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.rollback_failed_before_load");
    public static final MessageKey START_UPDATE_ROLLBACK_FINISHED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.rollback_finished");
    public static final MessageKey START_UPDATE_ROLLBACK_NO_BACKUP =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.rollback_no_backup");
    public static final MessageKey START_UPDATE_ROLLED_BACK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.rolled_back");
    public static final MessageKey START_UPDATE_STAGED_JAR_MISMATCH =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.staged_jar_mismatch");
    public static final MessageKey START_UPDATE_UNCONFIRMED_ROLLED_BACK =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.start.update.unconfirmed_rolled_back");
    public static final MessageKey UNINSTALL_ALREADY_QUEUED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.already_queued");
    public static final MessageKey UNINSTALL_ALSO_CANCELLED_UPDATE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.also_cancelled_update");
    public static final MessageKey UNINSTALL_CANCELLED_INSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.cancelled_install");
    public static final MessageKey UNINSTALL_FOLDER_UNREADABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.folder_unreadable");
    public static final MessageKey UNINSTALL_NO_JAR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.no_jar");
    public static final MessageKey UNINSTALL_NOT_FOUND =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.not_found");
    public static final MessageKey UNINSTALL_NOT_RECORDED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.not_recorded");
    public static final MessageKey UNINSTALL_QUEUED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.queued");
    public static final MessageKey UNINSTALL_REFUSED_AMBIGUOUS =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.refused.ambiguous");
    public static final MessageKey UNINSTALL_REFUSED_HELD_FOR_OPERATOR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.refused.held_for_operator");
    public static final MessageKey UNINSTALL_REFUSED_SHARED_JAR =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.refused.shared_jar");
    public static final MessageKey UNINSTALL_UNDETERMINED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.uninstall.undetermined");
    public static final MessageKey UPDATE_ALL_DONE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.all_done");
    public static final MessageKey UPDATE_NONE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.none");
    public static final MessageKey UPDATE_NOT_STAGED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.not_staged");
    public static final MessageKey UPDATE_PENDING_UNINSTALL =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.pending_uninstall");
    public static final MessageKey UPDATE_REPLACED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.replaced");
    public static final MessageKey UPDATE_STAGED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.staged");
    public static final MessageKey UPDATE_UPDATING =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.update.updating");
    public static final MessageKey VERSIONS_ENTRY =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.versions.entry");
    public static final MessageKey VERSIONS_FAILED =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.versions.failed");
    public static final MessageKey VERSIONS_HEADER =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.versions.header");
    public static final MessageKey VERSIONS_INSTALL_COMMAND =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.versions.install_command");
    public static final MessageKey VERSIONS_NONE_INSTALLABLE =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.versions.none_installable");
    public static final MessageKey VERSIONS_NOT_FOUND =
            MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "upm.versions.not_found");

    private UpmKeys() {
        // Key constants only.
    }
}
