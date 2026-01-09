package com.ultikits.plugins.mail.utils;

import com.ultikits.plugins.mail.UltiMail;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试辅助工具类
 * 提供 Mock UltiTools 和 UltiMail 实例的功能
 */
public final class TestHelper {

    private TestHelper() {
    }

    /**
     * Mock UltiTools 单例实例
     */
    public static UltiTools mockUltiToolsInstance() {
        try {
            UltiTools mockUltiTools = mock(UltiTools.class);
            Logger mockLogger = mock(Logger.class);
            when(mockUltiTools.getLogger()).thenReturn(mockLogger);
            
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, mockUltiTools);
            
            return mockUltiTools;
        } catch (Exception e) {
            throw new RuntimeException("Failed to mock UltiTools instance", e);
        }
    }

    /**
     * Mock UltiMail 单例实例
     */
    @SuppressWarnings("unchecked")
    public static UltiMail mockUltiMailInstance() {
        try {
            UltiMail mockPlugin = mock(UltiMail.class);
            PluginLogger mockLogger = mock(PluginLogger.class);
            
            // Mock i18n
            when(mockPlugin.i18n(any(String.class))).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                return "[" + key + "]"; // 返回 [key] 格式便于测试验证
            });
            
            when(mockPlugin.getLogger()).thenReturn(mockLogger);
            
            // Mock DataOperator
            DataOperator<?> mockDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(any())).thenReturn((DataOperator) mockDataOperator);
            
            // Set instance via reflection
            Field instanceField = UltiMail.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, mockPlugin);
            
            return mockPlugin;
        } catch (Exception e) {
            throw new RuntimeException("Failed to mock UltiMail instance", e);
        }
    }

    /**
     * 创建 Mock MailConfig
     */
    public static MailConfig createMockConfig() {
        MailConfig config = mock(MailConfig.class);
        when(config.getMaxSubjectLength()).thenReturn(50);
        when(config.getMaxContentLength()).thenReturn(500);
        when(config.getMaxItems()).thenReturn(27);
        when(config.getSendCooldown()).thenReturn(10);
        when(config.isNotifyOnJoin()).thenReturn(true);
        when(config.getNotifyDelay()).thenReturn(3);
        when(config.getNewMailMessage()).thenReturn("&e[邮件] &f你有 &a{COUNT} &f封未读邮件！");
        when(config.getMailReceivedMessage()).thenReturn("&e[邮件] &f你收到了来自 &a{SENDER} &f的新邮件！");
        return config;
    }

    /**
     * 清理所有 Mock 实例
     */
    public static void cleanupMocks() {
        try {
            // Clean UltiMail instance
            Field ultiMailField = UltiMail.class.getDeclaredField("instance");
            ultiMailField.setAccessible(true);
            ultiMailField.set(null, null);
        } catch (Exception ignored) {
        }
        
        try {
            // Clean UltiTools instance
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true);
            ultiToolsField.set(null, null);
        } catch (Exception ignored) {
        }
    }
}
