package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.text.Arg.number;
import static com.ultikits.ultitools.text.Arg.string;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.utils.ModuleContent;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StageResult;
import com.ultikits.ultitools.utils.PluginInstallUtils.PendingReply;
import com.ultikits.ultitools.utils.PluginInstallUtils.PendingResult;
import com.ultikits.ultitools.utils.UpmKeys;

final class PendingCommandTestSupport {
    private PendingCommandTestSupport() { }

    static PendingResult reply(boolean queued, MessageKey key, Arg... args) {
        return new PendingResult(queued, List.of(new PendingReply(key, List.of(args))), null, ModuleContent.NONE);
    }

    static PendingResult install(String module, String version) {
        return reply(true, UpmKeys.INSTALL_QUEUED, string("module", module), string("version", version));
    }

    static PendingResult remove(String module, String file) {
        return reply(true, UpmKeys.UNINSTALL_QUEUED, string("module", module), string("files", file));
    }

    static PendingResult refusal(MessageKey key, String module, String tag, String detail) {
        return reply(false, key, string("module", module), string(tag, detail));
    }

    static PendingResult unknown(String module) {
        return new PendingResult(true, List.of(
                new PendingReply(UpmKeys.UNINSTALL_QUEUED, List.of(string("module", module), string("files", "module.jar"))),
                new PendingReply(UpmKeys.UNINSTALL_UNDETERMINED, List.of(number("count", 1), string("files", "uncertain.jar")))),
                null, ModuleContent.NONE);
    }

    static PendingResult replacesUpdate() {
        return new PendingResult(true, List.of(
                new PendingReply(UpmKeys.UNINSTALL_QUEUED, List.of(string("module", "Demo"), string("files", "Demo.jar"))),
                new PendingReply(UpmKeys.UNINSTALL_ALSO_CANCELLED_UPDATE, List.of(string("module", "Demo"), string("version", "1.1")))),
                null, ModuleContent.NONE);
    }

    static StageResult staged() {
        StageResult result = mock(StageResult.class);
        when(result.getOutcome()).thenReturn(StageResult.Outcome.STAGED);
        when(result.getOldVersion()).thenReturn("1.0");
        when(result.getNewVersion()).thenReturn("1.1");
        return result;
    }
}
