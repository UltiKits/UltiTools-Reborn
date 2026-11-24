package com.ultikits.ultitools.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.services.impl.InMemoryNotificationService;

class NotificationServiceSurfaceTest {

    @Test
    void toastWidgetIsGone() {
        assertThatThrownBy(() -> getClass().getClassLoader()
                .loadClass("com.ultikits.ultitools.widgets.Toast"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void hologramWidgetIsGone() {
        assertThatThrownBy(() -> getClass().getClassLoader()
                .loadClass("com.ultikits.ultitools.widgets.Hologram"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void notificationServiceHasNoToastMethod() {
        assertThat(NotificationService.class.getMethods())
                .extracting(Method::getName)
                .contains("sendActionBarNotification")
                .doesNotContain("sendToastNotification");
    }

    @Test
    void inMemoryNotificationServiceHasNoToastMethod() {
        assertThat(InMemoryNotificationService.class.getMethods())
                .extracting(Method::getName)
                .contains("sendActionBarNotification")
                .doesNotContain("sendToastNotification");
    }

    @Test
    void notificationParametersContainNoStringAndSendingMethodsReturnVoid() {
        Method[] methods = NotificationService.class.getDeclaredMethods();
        assertThat(methods).hasSize(12).allSatisfy(method -> {
            assertThat(method.getParameterTypes()).doesNotContain(String.class);
            assertThat(method.getReturnType()).isEqualTo(void.class);
        });
        assertThat(methods).filteredOn(method -> Modifier.isAbstract(method.getModifiers())).hasSize(4);
    }

    @Test
    void controlLoadsNotificationService() throws ClassNotFoundException {
        assertThat(getClass().getClassLoader()
                .loadClass("com.ultikits.ultitools.services.NotificationService"))
                .isEqualTo(NotificationService.class);
    }
}
