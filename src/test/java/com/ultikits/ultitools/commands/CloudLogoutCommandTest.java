package com.ultikits.ultitools.commands;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

/**
 * {@code /ulticloud logout} 的拆线语义。
 *
 * <p>核心命题只有一条：<b>凭证的有效性不能作为生命周期拆解的门禁。</b>
 * access token 过期（例如主动刷新反复失败）之后，WebSocket、监控任务、日志 handler
 * 与玩家监听器可能全都还在跑，而 logout 是操作员唯一的停止手段——那正是最需要它
 * 生效的时刻，却恰恰是旧实现拒绝执行的时刻。
 */
@DisplayName("ulticloud logout 的拆线语义")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudLogoutCommandTest {

    /** 造一个已过期的 token：有 access_token，但 {@code hasValidToken()} 会判否。 */
    private TokenEntity expiredToken() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token("expired-access-token");
        token.setRefresh_token("some-refresh-token");
        // exp 落在过去 → isExpired() 为真
        token.setExp((System.currentTimeMillis() / 1000) - 3600);
        return token;
    }

    private TokenEntity validToken() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token("good-access-token");
        token.setRefresh_token("some-refresh-token");
        token.setExp((System.currentTimeMillis() / 1000) + 3600);
        return token;
    }

    @Nested
    @DisplayName("凭证有效性不得阻断拆线")
    class TeardownIsNotGatedByCredentials {

        @Test
        @DisplayName("access token 已过期时，logout 仍必须拆线并清凭证")
        void expiredTokenStillTearsDown() throws Exception {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class);
                 MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {

                auth.when(CloudAuthManager::hasValidToken).thenReturn(false);
                auth.when(CloudAuthManager::getCurrentToken).thenReturn(expiredToken());

                new CloudLoginCommand().logout(sender);

                // socket、监控任务、日志 handler、玩家监听器都只有这一条路能停下来
                init.verify(PluginInitiationUtils::disableCloud, times(1));
                auth.verify(CloudAuthManager::clearToken, times(1));
            }
        }

        @Test
        @DisplayName("token 有效时照常拆线")
        void validTokenTearsDownAsBefore() throws Exception {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class);
                 MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {

                auth.when(CloudAuthManager::hasValidToken).thenReturn(true);
                auth.when(CloudAuthManager::getCurrentToken).thenReturn(validToken());

                new CloudLoginCommand().logout(sender);

                init.verify(PluginInitiationUtils::disableCloud, times(1));
                auth.verify(CloudAuthManager::clearToken, times(1));
            }
        }

        @Test
        @DisplayName("从未登录过时不必清凭证，但拆线仍是无害且必要的")
        void neverLoggedInStillStopsAnythingLeftRunning() throws Exception {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class);
                 MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {

                auth.when(CloudAuthManager::hasValidToken).thenReturn(false);
                auth.when(CloudAuthManager::getCurrentToken).thenReturn(null);

                new CloudLoginCommand().logout(sender);

                // disableCloud 的每一步都对「本来就没起来」幂等，所以照调不误：
                // 判断「有没有东西在跑」比判断「凭证还在不在」可靠得多。
                init.verify(PluginInitiationUtils::disableCloud, times(1));
                // 没有凭证就没什么可清的，不必去碰磁盘
                auth.verify(CloudAuthManager::clearToken, never());
            }
        }
    }
}
