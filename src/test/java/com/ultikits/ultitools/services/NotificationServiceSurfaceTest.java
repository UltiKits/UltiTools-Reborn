package com.ultikits.ultitools.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class NotificationServiceSurfaceTest {

    @Test
    void toastWidgetIsGone() {
        assertThatThrownBy(() -> getClass().getClassLoader()
                .loadClass("com.ultikits.ultitools.widgets.Toast"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void notificationServiceHasNoToastMethod() {
        assertThat(NotificationService.class.getMethods())
                .extracting(Method::getName)
                .doesNotContain("sendToastNotification");
    }

    @Test
    void controlLoadsNotificationService() throws ClassNotFoundException {
        assertThat(getClass().getClassLoader()
                .loadClass("com.ultikits.ultitools.services.NotificationService"))
                .isEqualTo(NotificationService.class);
    }
}
