package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyTokenMessageTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void removingAnUnlinkedLegacyTokenLogsTheCompleteSentence(String code) throws Exception {
        List<String> lines = new ArrayList<>();
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() { /* Immediate capture. */ }
            @Override public void close() { /* No resource. */ }
        });
        var plugin = mock(UltiTools.class);
        when(plugin.getLogger()).thenReturn(logger);
        CloudTestAccess.useTemporaryCredentials(temp);
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
             var tools = mockStatic(UltiTools.class)) {
            tools.when(UltiTools::getInstance).thenReturn(plugin);
            CredentialStore.update(doc -> { doc.put("cloud_token", Map.of("refresh_token", "fixture")); return doc; });
            assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
            String path = temp.resolve(CredentialStore.CREDENTIAL_FILE_NAME).toAbsolutePath().toString();
            assertThat(lines).containsExactly(code.equals("en")
                    ? "The account login saved by an earlier UltiTools version is no longer used and was removed from " + path + ". Run /ulticloud login to re-attach this server."
                    : "早期 UltiTools 版本保存的账号登录已不再使用，已从 " + path + " 删除。请执行 /ulticloud login 接回此服务器。");
        } finally {
            CloudTestAccess.reset();
        }
    }
}
