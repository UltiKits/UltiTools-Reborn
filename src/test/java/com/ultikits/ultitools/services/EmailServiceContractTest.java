package com.ultikits.ultitools.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Interface contract tests for EmailService.
 * <p>
 * 邮件服务接口契约测试。
 * <p>
 * 验证接口定义的方法签名是否正确。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
@DisplayName("EmailService 接口契约测试")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class EmailServiceContractTest {

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("isEnabled() 方法应存在")
        void shouldHaveIsEnabledMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("isEnabled");
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isZero();
        }

        @Test
        @DisplayName("sendEmail(String, String, String) 方法应存在")
        void shouldHaveSendEmailMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendEmail", String.class, String.class, String.class);
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("sendEmail(List, String, String) 方法应存在")
        void shouldHaveSendEmailToMultipleMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendEmail", List.class, String.class, String.class);
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("sendHtmlEmail(String, String, String) 方法应存在")
        void shouldHaveSendHtmlEmailMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendHtmlEmail", String.class, String.class, String.class);
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("sendHtmlEmail(List, String, String) 方法应存在")
        void shouldHaveSendHtmlEmailToMultipleMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendHtmlEmail", List.class, String.class, String.class);
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("sendEmailAsync(String, String, String) 方法应存在")
        void shouldHaveSendEmailAsyncMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendEmailAsync", String.class, String.class, String.class);
            
            assertThat(method.getReturnType()).isEqualTo(CompletableFuture.class);
            assertThat(method.getParameterCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("sendHtmlEmailAsync(String, String, String) 方法应存在")
        void shouldHaveSendHtmlEmailAsyncMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendHtmlEmailAsync", String.class, String.class, String.class);
            
            assertThat(method.getReturnType()).isEqualTo(CompletableFuture.class);
            assertThat(method.getParameterCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("generateVerificationCode(int) 方法应存在")
        void shouldHaveGenerateVerificationCodeMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("generateVerificationCode", int.class);
            
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(method.getParameterCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("generateAlphanumericCode(int) 方法应存在")
        void shouldHaveGenerateAlphanumericCodeMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("generateAlphanumericCode", int.class);
            
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(method.getParameterCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("sendVerificationCodeEmail(String, String, String, int) 方法应存在")
        void shouldHaveSendVerificationCodeEmailMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendVerificationCodeEmail", 
                String.class, String.class, String.class, int.class);
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("sendVerificationCodeEmailAsync(String, String, String, int) 方法应存在")
        void shouldHaveSendVerificationCodeEmailAsyncMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("sendVerificationCodeEmailAsync", 
                String.class, String.class, String.class, int.class);
            
            assertThat(method.getReturnType()).isEqualTo(CompletableFuture.class);
            assertThat(method.getParameterCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("testConnection() 方法应存在")
        void shouldHaveTestConnectionMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("testConnection");
            
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
            assertThat(method.getParameterCount()).isZero();
        }

        @Test
        @DisplayName("getLastError() 方法应存在")
        void shouldHaveGetLastErrorMethod() throws NoSuchMethodException {
            Method method = EmailService.class.getMethod("getLastError");
            
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(method.getParameterCount()).isZero();
        }
    }

    @Nested
    @DisplayName("接口继承测试")
    class InterfaceInheritanceTests {

        @Test
        @DisplayName("EmailService 应继承 BaseService")
        void shouldExtendBaseService() {
            Class<?>[] interfaces = EmailService.class.getInterfaces();
            
            boolean extendsBaseService = Arrays.stream(interfaces)
                .anyMatch(i -> i.getSimpleName().equals("BaseService"));
            
            assertThat(extendsBaseService).isTrue();
        }
    }

    @Nested
    @DisplayName("方法数量测试")
    class MethodCountTests {

        @Test
        @DisplayName("接口应有至少12个方法")
        void shouldHaveAtLeast12Methods() {
            Method[] methods = EmailService.class.getDeclaredMethods();
            
            // EmailService 自己声明的方法（不包括从 BaseService 继承的）
            assertThat(methods.length).isGreaterThanOrEqualTo(12);
        }
    }
}
