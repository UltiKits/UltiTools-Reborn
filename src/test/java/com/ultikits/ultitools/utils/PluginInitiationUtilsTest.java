package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;

/**
 * PluginInitiationUtils 测试类
 * 测试插件初始化工具类的结构
 */
@DisplayName("PluginInitiationUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for internal state verification
class PluginInitiationUtilsTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("类应该是public的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(PluginInitiationUtils.class.getModifiers())).isTrue();
        }
    }

    /**
     * Gate-2 finding (review round 14, pull request #467). {@code handleConfigUploadLogic} used
     * to unconditionally dereference {@code configName}, {@code format} and {@code backup} --
     * fields relevant only to {@code plugin_config} uploads -- BEFORE branching on {@code
     * configType}. The documented negative-request shape in {@code UAT-CHECKLIST.md}
     * ({@code ultitools.remote.upload-config.neg-permissions}) sends only {@code configType},
     * so those three {@code JsonObject#get(String)} calls returned {@code null} and the
     * unconditional {@code .getAsString()}/{@code .getAsBoolean()} calls threw an
     * implementation-specific {@code NullPointerException} instead of the intended, documented
     * rejection message ("Unsupported config type: permissions" / the {@code server_properties}
     * redirect message). Fixed by branching on {@code configType} first and only reading the
     * plugin_config-only fields inside that one case.
     */
    @Nested
    @DisplayName("handleConfigUploadLogic rejects unsupported types before reading write-only fields")
    class HandleConfigUploadLogicRejectionOrderingTests {

        private Object invokeWithConfigTypeOnly(String configType) throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("configType", configType);
            // Deliberately no configName/configContent/format/backup -- the documented
            // negative-request shape (UAT-CHECKLIST.md: ultitools.remote.upload-config.neg-permissions).

            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleConfigUploadLogic", JsonObject.class);
            method.setAccessible(true);

            try {
                return method.invoke(null, data);
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        @Test
        @DisplayName("configType: permissions -> IllegalArgumentException naming the type, not an NPE")
        void permissionsTypeIsRejectedWithoutReadingWriteFields() {
            Throwable thrown = catchThrowable(() -> invokeWithConfigTypeOnly("permissions"));

            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported config type: permissions");
        }

        @Test
        @DisplayName("configType: server_properties -> IllegalArgumentException naming the redirect, not an NPE")
        void serverPropertiesTypeIsRejectedWithoutReadingWriteFields() {
            Throwable thrown = catchThrowable(() -> invokeWithConfigTypeOnly("server_properties"));

            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("server_properties config is not accepted via upload_config; "
                    + "send it as a server_properties message instead");
        }
    }
}
