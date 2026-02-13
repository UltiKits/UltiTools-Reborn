package com.ultikits.plugins.mail.utils;

import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Test helper for mocking UltiTools framework dependencies.
 * <p>
 * Since UltiMail no longer uses a static singleton, this helper creates
 * a mock UltiToolsPlugin that can be injected into services and commands
 * via reflection (simulating @Autowired injection).
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
public final class TestHelper {

    private TestHelper() {
    }

    private static UltiToolsPlugin mockPlugin;

    /**
     * Create a mock UltiToolsPlugin for injection into beans.
     * Returns the same instance on repeated calls within a test lifecycle.
     */
    @SuppressWarnings("unchecked")
    public static UltiToolsPlugin mockUltiToolsPlugin() {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class, withSettings().lenient());
        PluginLogger mockLogger = mock(PluginLogger.class);

        // Mock i18n - returns [key] format for test assertions
        lenient().when(plugin.i18n(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return "[" + key + "]";
        });

        lenient().when(plugin.getLogger()).thenReturn(mockLogger);

        // Mock DataOperator
        DataOperator<?> mockDataOperator = mock(DataOperator.class);
        lenient().when(plugin.getDataOperator(any())).thenReturn((DataOperator) mockDataOperator);

        mockPlugin = plugin;
        return plugin;
    }

    /**
     * @deprecated Use {@link #mockUltiToolsPlugin()} instead.
     * Kept for backward compatibility during migration.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static UltiToolsPlugin mockUltiMailInstance() {
        return mockUltiToolsPlugin();
    }

    /**
     * Get the most recently created mock plugin.
     */
    public static UltiToolsPlugin getMockPlugin() {
        return mockPlugin;
    }

    /**
     * Create a mock MailConfig with default values.
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
     * Inject a value into a field via reflection (simulates @Autowired).
     */
    public static void injectField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true); // NOPMD - intentional reflection for test mock injection
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found in " + target.getClass().getName());
    }

    /**
     * Clean up mock state.
     */
    public static void cleanupMocks() {
        mockPlugin = null;

        try {
            // Clean UltiTools instance if set
            Field ultiToolsField = UltiTools.class.getDeclaredField("ultiTools");
            ultiToolsField.setAccessible(true); // NOPMD - intentional reflection for cleanup
            ultiToolsField.set(null, null);
        } catch (Exception ignored) {
        }
    }
}
