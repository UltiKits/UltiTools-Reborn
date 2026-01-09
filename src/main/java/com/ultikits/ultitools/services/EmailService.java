package com.ultikits.ultitools.services;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.ultikits.ultitools.interfaces.BaseService;

/**
 * Email service interface for sending emails via SMTP.
 * <p>
 * 邮件服务接口，通过SMTP发送邮件。
 * <p>
 * This service is optional and must be configured in config.yml.
 * Modules should always call {@link #isEnabled()} before attempting to send emails.
 * <p>
 * 此服务是可选的，需要在config.yml中配置。
 * 模块在尝试发送邮件前应始终调用 {@link #isEnabled()} 检查服务是否可用。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public interface EmailService extends BaseService {

    /**
     * Check if the email service is enabled and properly configured.
     * <p>
     * Modules MUST call this method before attempting to send emails.
     * The service is considered enabled only when:
     * <ul>
     *   <li>email.enable is true in config.yml</li>
     *   <li>SMTP host, username, and password are configured</li>
     * </ul>
     * <p>
     * 检查邮件服务是否已启用并正确配置。
     * <p>
     * 模块必须在尝试发送邮件前调用此方法。
     * 只有在以下条件满足时服务才被视为启用：
     * <ul>
     *   <li>config.yml中的email.enable为true</li>
     *   <li>SMTP主机、用户名和密码已配置</li>
     * </ul>
     *
     * @return true if email service is enabled and configured, false otherwise
     *         <br>如果邮件服务已启用且配置正确则返回true，否则返回false
     */
    boolean isEnabled();

    /**
     * Send a plain text email.
     * <p>
     * 发送纯文本邮件。
     *
     * @param to      recipient email address <br>收件人邮箱地址
     * @param subject email subject <br>邮件主题
     * @param content email content (plain text) <br>邮件内容（纯文本）
     * @return true if email was sent successfully, false otherwise
     *         <br>如果邮件发送成功则返回true，否则返回false
     */
    boolean sendEmail(String to, String subject, String content);

    /**
     * Send a plain text email to multiple recipients.
     * <p>
     * 发送纯文本邮件给多个收件人。
     *
     * @param recipients list of recipient email addresses <br>收件人邮箱地址列表
     * @param subject    email subject <br>邮件主题
     * @param content    email content (plain text) <br>邮件内容（纯文本）
     * @return true if email was sent successfully to all recipients, false otherwise
     *         <br>如果邮件成功发送给所有收件人则返回true，否则返回false
     */
    boolean sendEmail(List<String> recipients, String subject, String content);

    /**
     * Send an HTML email.
     * <p>
     * 发送HTML邮件。
     *
     * @param to          recipient email address <br>收件人邮箱地址
     * @param subject     email subject <br>邮件主题
     * @param htmlContent email content (HTML format) <br>邮件内容（HTML格式）
     * @return true if email was sent successfully, false otherwise
     *         <br>如果邮件发送成功则返回true，否则返回false
     */
    boolean sendHtmlEmail(String to, String subject, String htmlContent);

    /**
     * Send an HTML email to multiple recipients.
     * <p>
     * 发送HTML邮件给多个收件人。
     *
     * @param recipients  list of recipient email addresses <br>收件人邮箱地址列表
     * @param subject     email subject <br>邮件主题
     * @param htmlContent email content (HTML format) <br>邮件内容（HTML格式）
     * @return true if email was sent successfully to all recipients, false otherwise
     *         <br>如果邮件成功发送给所有收件人则返回true，否则返回false
     */
    boolean sendHtmlEmail(List<String> recipients, String subject, String htmlContent);

    /**
     * Send a plain text email asynchronously.
     * <p>
     * 异步发送纯文本邮件。
     *
     * @param to      recipient email address <br>收件人邮箱地址
     * @param subject email subject <br>邮件主题
     * @param content email content (plain text) <br>邮件内容（纯文本）
     * @return CompletableFuture that completes with true if successful, false otherwise
     *         <br>异步任务，成功时返回true，否则返回false
     */
    CompletableFuture<Boolean> sendEmailAsync(String to, String subject, String content);

    /**
     * Send an HTML email asynchronously.
     * <p>
     * 异步发送HTML邮件。
     *
     * @param to          recipient email address <br>收件人邮箱地址
     * @param subject     email subject <br>邮件主题
     * @param htmlContent email content (HTML format) <br>邮件内容（HTML格式）
     * @return CompletableFuture that completes with true if successful, false otherwise
     *         <br>异步任务，成功时返回true，否则返回false
     */
    CompletableFuture<Boolean> sendHtmlEmailAsync(String to, String subject, String htmlContent);

    /**
     * Generate a random verification code.
     * <p>
     * 生成随机验证码。
     *
     * @param length length of the verification code (4-10 recommended)
     *               <br>验证码长度（推荐4-10）
     * @return random numeric verification code
     *         <br>随机数字验证码
     */
    String generateVerificationCode(int length);

    /**
     * Generate a random alphanumeric verification code.
     * <p>
     * 生成随机字母数字混合验证码。
     *
     * @param length length of the verification code (4-10 recommended)
     *               <br>验证码长度（推荐4-10）
     * @return random alphanumeric verification code
     *         <br>随机字母数字混合验证码
     */
    String generateAlphanumericCode(int length);

    /**
     * Send a verification code email using a predefined template.
     * <p>
     * 使用预定义模板发送验证码邮件。
     *
     * @param to               recipient email address <br>收件人邮箱地址
     * @param verificationCode the verification code to send <br>要发送的验证码
     * @param serverName       server name to display in the email <br>要在邮件中显示的服务器名称
     * @param expiryMinutes    validity period in minutes <br>有效期（分钟）
     * @return true if email was sent successfully, false otherwise
     *         <br>如果邮件发送成功则返回true，否则返回false
     */
    boolean sendVerificationCodeEmail(String to, String verificationCode, String serverName, int expiryMinutes);

    /**
     * Send a verification code email asynchronously.
     * <p>
     * 异步发送验证码邮件。
     *
     * @param to               recipient email address <br>收件人邮箱地址
     * @param verificationCode the verification code to send <br>要发送的验证码
     * @param serverName       server name to display in the email <br>要在邮件中显示的服务器名称
     * @param expiryMinutes    validity period in minutes <br>有效期（分钟）
     * @return CompletableFuture that completes with true if successful, false otherwise
     *         <br>异步任务，成功时返回true，否则返回false
     */
    CompletableFuture<Boolean> sendVerificationCodeEmailAsync(String to, String verificationCode, 
                                                               String serverName, int expiryMinutes);

    /**
     * Test the SMTP connection with current configuration.
     * <p>
     * 使用当前配置测试SMTP连接。
     *
     * @return true if connection is successful, false otherwise
     *         <br>如果连接成功则返回true，否则返回false
     */
    boolean testConnection();

    /**
     * Get the last error message if any operation failed.
     * <p>
     * 获取上次操作失败的错误信息。
     *
     * @return last error message or null if no error
     *         <br>上次错误信息，如果没有错误则返回null
     */
    String getLastError();
}
