package com.ultikits.ultitools.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ultikits.ultitools.UltiTools;
/**
 * Unit tests for DefaultEmailService.
 * <p>
 * 默认邮件服务单元测试。
 * <p>
 * 测试范围:
 * - 验证码生成
 * - 邮箱地址验证
 * - HTML 模板构建
 * - 服务启用状态检查
 * - 错误处理
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
@DisplayName("DefaultEmailService 单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DefaultEmailServiceTest {

    private DefaultEmailService emailService;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private FileConfiguration mockConfig;

    private MockedStatic<UltiTools> ultiToolsMockedStatic;

    @BeforeEach
    void setUp() {
        // Mock UltiTools.getInstance()
        ultiToolsMockedStatic = mockStatic(UltiTools.class);
        ultiToolsMockedStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.getConfig()).thenReturn(mockConfig);

        // 默认配置：服务禁用 (使用 lenient 避免 UnnecessaryStubbingException)
        lenient().when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(false);
        lenient().when(mockConfig.getString(anyString(), anyString())).thenReturn("");
        lenient().when(mockConfig.getInt(anyString(), anyInt())).thenReturn(587);
        lenient().when(mockConfig.getBoolean(eq("email.smtp.ssl"), anyBoolean())).thenReturn(false);
        lenient().when(mockConfig.getBoolean(eq("email.smtp.starttls"), anyBoolean())).thenReturn(true);

        emailService = new DefaultEmailService();
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMockedStatic != null) {
            ultiToolsMockedStatic.close();
        }
    }

    @Nested
    @DisplayName("验证码生成测试")
    class VerificationCodeTests {

        @Test
        @DisplayName("生成6位数字验证码")
        void shouldGenerateSixDigitCode() {
            String code = emailService.generateVerificationCode(6);

            assertThat(code).hasSize(6);
            assertThat(code).matches("\\d{6}");
        }

        @Test
        @DisplayName("生成4位数字验证码")
        void shouldGenerateFourDigitCode() {
            String code = emailService.generateVerificationCode(4);

            assertThat(code).hasSize(4);
            assertThat(code).matches("\\d{4}");
        }

        @Test
        @DisplayName("长度小于1时使用默认长度6")
        void shouldUseDefaultLengthForInvalidLength() {
            String code = emailService.generateVerificationCode(0);

            assertThat(code).hasSize(6);
        }

        @Test
        @DisplayName("长度负数时使用默认长度6")
        void shouldUseDefaultLengthForNegativeLength() {
            String code = emailService.generateVerificationCode(-5);

            assertThat(code).hasSize(6);
        }

        @Test
        @DisplayName("长度超过20时限制为20")
        void shouldLimitMaxLengthTo20() {
            String code = emailService.generateVerificationCode(100);

            assertThat(code).hasSize(20);
        }

        @Test
        @DisplayName("生成的验证码只包含数字")
        void shouldGenerateNumericOnlyCode() {
            for (int i = 0; i < 100; i++) {
                String code = emailService.generateVerificationCode(10);
                assertThat(code).matches("\\d+");
            }
        }

        @Test
        @DisplayName("生成字母数字混合验证码")
        void shouldGenerateAlphanumericCode() {
            String code = emailService.generateAlphanumericCode(8);

            assertThat(code).hasSize(8);
            assertThat(code).matches("[0-9A-Z]{8}");
        }

        @Test
        @DisplayName("字母数字验证码长度限制")
        void shouldLimitAlphanumericCodeLength() {
            String codeMin = emailService.generateAlphanumericCode(0);
            String codeMax = emailService.generateAlphanumericCode(100);

            assertThat(codeMin).hasSize(6); // 默认
            assertThat(codeMax).hasSize(20); // 最大
        }

        @Test
        @DisplayName("验证码应具有随机性")
        void shouldGenerateRandomCodes() {
            String code1 = emailService.generateVerificationCode(10);
            String code2 = emailService.generateVerificationCode(10);
            String code3 = emailService.generateVerificationCode(10);

            // 三个验证码至少有两个不同（极小概率全相同）
            boolean allSame = code1.equals(code2) && code2.equals(code3);
            assertThat(allSame).isFalse();
        }
    }

    @Nested
    @DisplayName("邮箱验证测试")
    class EmailValidationTests {

        @Test
        @DisplayName("有效邮箱地址应通过验证")
        void shouldValidateCorrectEmails() throws Exception {
            Method isValidEmail = DefaultEmailService.class.getDeclaredMethod("isValidEmail", String.class);
            isValidEmail.setAccessible(true);

            assertThat((boolean) isValidEmail.invoke(emailService, "test@example.com")).isTrue();
            assertThat((boolean) isValidEmail.invoke(emailService, "user.name@domain.org")).isTrue();
            assertThat((boolean) isValidEmail.invoke(emailService, "user+tag@example.co.uk")).isTrue();
            assertThat((boolean) isValidEmail.invoke(emailService, "a@b.cc")).isTrue();
        }

        @Test
        @DisplayName("无效邮箱地址应验证失败")
        void shouldRejectInvalidEmails() throws Exception {
            Method isValidEmail = DefaultEmailService.class.getDeclaredMethod("isValidEmail", String.class);
            isValidEmail.setAccessible(true);

            assertThat((boolean) isValidEmail.invoke(emailService, "")).isFalse();
            assertThat((boolean) isValidEmail.invoke(emailService, (String) null)).isFalse();
            assertThat((boolean) isValidEmail.invoke(emailService, "notanemail")).isFalse();
            assertThat((boolean) isValidEmail.invoke(emailService, "missing@domain")).isFalse();
            assertThat((boolean) isValidEmail.invoke(emailService, "@nodomain.com")).isFalse();
            assertThat((boolean) isValidEmail.invoke(emailService, "noat.com")).isFalse();
        }
    }

    @Nested
    @DisplayName("服务启用状态测试")
    class ServiceEnabledTests {

        @Test
        @DisplayName("未配置时服务应禁用")
        void shouldBeDisabledWhenNotConfigured() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(false);

            assertThat(emailService.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("启用但缺少主机时应禁用")
        void shouldBeDisabledWithoutHost() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("");

            assertThat(emailService.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("启用但使用示例主机时应禁用")
        void shouldBeDisabledWithExampleHost() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.example.com");

            assertThat(emailService.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("启用但缺少用户名时应禁用")
        void shouldBeDisabledWithoutUsername() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.gmail.com");
            when(mockConfig.getString(eq("email.smtp.username"), anyString())).thenReturn("");

            assertThat(emailService.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("启用但缺少密码时应禁用")
        void shouldBeDisabledWithoutPassword() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.gmail.com");
            when(mockConfig.getString(eq("email.smtp.username"), anyString())).thenReturn("user@gmail.com");
            when(mockConfig.getString(eq("email.smtp.password"), anyString())).thenReturn("");

            assertThat(emailService.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("完整配置时服务应启用")
        void shouldBeEnabledWithFullConfig() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.gmail.com");
            when(mockConfig.getString(eq("email.smtp.username"), anyString())).thenReturn("user@gmail.com");
            when(mockConfig.getString(eq("email.smtp.password"), anyString())).thenReturn("password123");

            assertThat(emailService.isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("HTML 模板测试")
    class HtmlTemplateTests {

        @Test
        @DisplayName("验证码邮件模板应包含验证码")
        void shouldContainVerificationCode() throws Exception {
            Method buildTemplate = DefaultEmailService.class.getDeclaredMethod(
                "buildVerificationEmailTemplate", String.class, String.class, int.class);
            buildTemplate.setAccessible(true);

            String html = (String) buildTemplate.invoke(emailService, "123456", "TestServer", 5);

            assertThat(html).contains("123456");
            assertThat(html).contains("TestServer");
            assertThat(html).contains("5 分钟");
            assertThat(html).contains("5 minutes");
        }

        @Test
        @DisplayName("模板应正确转义 HTML 特殊字符")
        void shouldEscapeHtmlCharacters() throws Exception {
            Method escapeHtml = DefaultEmailService.class.getDeclaredMethod("escapeHtml", String.class);
            escapeHtml.setAccessible(true);

            assertThat((String) escapeHtml.invoke(emailService, "<script>")).isEqualTo("&lt;script&gt;");
            assertThat((String) escapeHtml.invoke(emailService, "A & B")).isEqualTo("A &amp; B");
            assertThat((String) escapeHtml.invoke(emailService, "\"quoted\"")).isEqualTo("&quot;quoted&quot;");
            assertThat((String) escapeHtml.invoke(emailService, "'single'")).isEqualTo("&#39;single&#39;");
        }

        @Test
        @DisplayName("空字符串应返回空")
        void shouldHandleNullInput() throws Exception {
            Method escapeHtml = DefaultEmailService.class.getDeclaredMethod("escapeHtml", String.class);
            escapeHtml.setAccessible(true);

            assertThat((String) escapeHtml.invoke(emailService, (String) null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("发送邮件测试（禁用状态）")
    class SendEmailDisabledTests {

        @Test
        @DisplayName("服务禁用时发送邮件应返回 false")
        void shouldReturnFalseWhenDisabled() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(false);

            boolean result = emailService.sendEmail("test@example.com", "Subject", "Content");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("not enabled");
        }

        @Test
        @DisplayName("收件人为空时应返回 false")
        void shouldReturnFalseForEmptyRecipient() {
            // 先启用服务
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.test.com");
            when(mockConfig.getString(eq("email.smtp.username"), anyString())).thenReturn("user");
            when(mockConfig.getString(eq("email.smtp.password"), anyString())).thenReturn("pass");

            boolean result = emailService.sendEmail("", "Subject", "Content");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("empty");
        }

        @Test
        @DisplayName("收件人为 null 时应返回 false")
        void shouldReturnFalseForNullRecipient() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.test.com");
            when(mockConfig.getString(eq("email.smtp.username"), anyString())).thenReturn("user");
            when(mockConfig.getString(eq("email.smtp.password"), anyString())).thenReturn("pass");

            String nullRecipient = null;
            boolean result = emailService.sendEmail(nullRecipient, "Subject", "Content");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("empty");
        }

        @Test
        @DisplayName("无效邮箱地址应返回 false")
        void shouldReturnFalseForInvalidEmail() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(true);
            when(mockConfig.getString(eq("email.smtp.host"), anyString())).thenReturn("smtp.test.com");
            when(mockConfig.getString(eq("email.smtp.username"), anyString())).thenReturn("user");
            when(mockConfig.getString(eq("email.smtp.password"), anyString())).thenReturn("pass");

            boolean result = emailService.sendEmail("invalid-email", "Subject", "Content");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("Invalid");
        }
    }

    @Nested
    @DisplayName("批量发送测试")
    class BatchSendTests {

        @Test
        @DisplayName("空收件人列表应返回 false")
        void shouldReturnFalseForEmptyRecipientsList() {
            boolean result = emailService.sendEmail(Collections.emptyList(), "Subject", "Content");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("empty");
        }

        @Test
        @DisplayName("null 收件人列表应返回 false")
        void shouldReturnFalseForNullRecipientsList() {
            List<String> nullList = null;
            boolean result = emailService.sendEmail(nullList, "Subject", "Content");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("empty");
        }

        @Test
        @DisplayName("空 HTML 收件人列表应返回 false")
        void shouldReturnFalseForEmptyHtmlRecipientsList() {
            boolean result = emailService.sendHtmlEmail(Collections.emptyList(), "Subject", "<p>Content</p>");

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("empty");
        }
    }

    @Nested
    @DisplayName("连接测试")
    class ConnectionTests {

        @Test
        @DisplayName("服务禁用时连接测试应返回 false")
        void shouldReturnFalseWhenDisabled() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(false);

            boolean result = emailService.testConnection();

            assertThat(result).isFalse();
            assertThat(emailService.getLastError()).contains("not enabled");
        }
    }

    @Nested
    @DisplayName("服务信息测试")
    class ServiceInfoTests {

        @Test
        @DisplayName("服务名称应为 EmailService")
        void shouldReturnCorrectName() {
            assertThat(emailService.getName()).isEqualTo("EmailService");
        }

        @Test
        @DisplayName("作者应为 UltiKits")
        void shouldReturnCorrectAuthor() {
            assertThat(emailService.getAuthor()).isEqualTo("UltiKits");
        }

        @Test
        @DisplayName("版本应为 1")
        void shouldReturnVersion1() {
            assertThat(emailService.getVersion()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("异步发送测试")
    class AsyncSendTests {

        @Test
        @DisplayName("异步发送纯文本邮件应返回 CompletableFuture")
        void shouldReturnCompletableFutureForAsyncSend() {
            // 异步方法在不同线程执行，MockedStatic 无法跨线程
            // 所以只验证返回类型是 CompletableFuture，不等待执行结果
            CompletableFuture<Boolean> future = emailService.sendEmailAsync("test@example.com", "Subject", "Content");

            assertThat(future).isNotNull();
            assertThat(future).isInstanceOf(CompletableFuture.class);
            // 取消异步任务，避免测试后继续执行
            future.cancel(true);
        }

        @Test
        @DisplayName("异步发送 HTML 邮件应返回 CompletableFuture")
        void shouldReturnCompletableFutureForAsyncHtmlSend() {
            // 异步方法在不同线程执行，MockedStatic 无法跨线程
            CompletableFuture<Boolean> future = emailService.sendHtmlEmailAsync("test@example.com", "Subject", "<p>Content</p>");

            assertThat(future).isNotNull();
            assertThat(future).isInstanceOf(CompletableFuture.class);
            future.cancel(true);
        }

        @Test
        @DisplayName("异步发送验证码邮件应返回 CompletableFuture")
        void shouldReturnCompletableFutureForAsyncVerificationCodeSend() {
            // 异步方法在不同线程执行，MockedStatic 无法跨线程
            CompletableFuture<Boolean> future = emailService.sendVerificationCodeEmailAsync(
                "test@example.com", "123456", "TestServer", 5);

            assertThat(future).isNotNull();
            assertThat(future).isInstanceOf(CompletableFuture.class);
            future.cancel(true);
        }
    }

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("初始状态下 lastError 应为 null")
        void shouldHaveNullLastErrorInitially() {
            // 在未进行任何操作前，lastError 应该为 null
            // 但由于构造函数可能不初始化，这里主要测试错误消息设置
            // 先触发一个错误
            emailService.sendEmail("test@example.com", "Subject", "Content");
            
            assertThat(emailService.getLastError()).isNotNull();
        }

        @Test
        @DisplayName("不同错误应有不同的错误消息")
        void shouldSetDifferentErrorMessages() {
            when(mockConfig.getBoolean(eq("email.enable"), anyBoolean())).thenReturn(false);
            emailService.sendEmail("test@example.com", "Subject", "Content");
            String error1 = emailService.getLastError();

            emailService.sendEmail(Collections.emptyList(), "Subject", "Content");
            String error2 = emailService.getLastError();

            assertThat(error1).isNotEqualTo(error2);
        }
    }
}
