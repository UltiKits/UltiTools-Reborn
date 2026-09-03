package com.ultikits.ultitools.services;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.ultikits.ultitools.interfaces.BaseService;

/**
 * Email service interface for sending emails via SMTP.
 * <p>
 * This service is optional and must be configured in config.yml.
 * Modules should always call {@link #isEnabled()} before attempting to send emails.
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
     *
     * @return true if email service is enabled and configured, false otherwise
     */
    boolean isEnabled();

    /**
     * Send a plain text email.
     *
     * @param to      recipient email address
     * @param subject email subject
     * @param content email content (plain text)
     * @return true if email was sent successfully, false otherwise
     */
    boolean sendEmail(String to, String subject, String content);

    /**
     * Send a plain text email to multiple recipients.
     *
     * @param recipients list of recipient email addresses
     * @param subject    email subject
     * @param content    email content (plain text)
     * @return true if email was sent successfully to all recipients, false otherwise
     */
    boolean sendEmail(List<String> recipients, String subject, String content);

    /**
     * Send an HTML email.
     *
     * @param to          recipient email address
     * @param subject     email subject
     * @param htmlContent email content (HTML format)
     * @return true if email was sent successfully, false otherwise
     */
    boolean sendHtmlEmail(String to, String subject, String htmlContent);

    /**
     * Send an HTML email to multiple recipients.
     *
     * @param recipients  list of recipient email addresses
     * @param subject     email subject
     * @param htmlContent email content (HTML format)
     * @return true if email was sent successfully to all recipients, false otherwise
     */
    boolean sendHtmlEmail(List<String> recipients, String subject, String htmlContent);

    /**
     * Send a plain text email asynchronously.
     *
     * @param to      recipient email address
     * @param subject email subject
     * @param content email content (plain text)
     * @return CompletableFuture that completes with true if successful, false otherwise
     */
    CompletableFuture<Boolean> sendEmailAsync(String to, String subject, String content);

    /**
     * Send an HTML email asynchronously.
     *
     * @param to          recipient email address
     * @param subject     email subject
     * @param htmlContent email content (HTML format)
     * @return CompletableFuture that completes with true if successful, false otherwise
     */
    CompletableFuture<Boolean> sendHtmlEmailAsync(String to, String subject, String htmlContent);

    /**
     * Generate a random verification code.
     *
     * @param length length of the verification code (4-10 recommended)
     * @return random numeric verification code
     */
    String generateVerificationCode(int length);

    /**
     * Generate a random alphanumeric verification code.
     *
     * @param length length of the verification code (4-10 recommended)
     * @return random alphanumeric verification code
     */
    String generateAlphanumericCode(int length);

    /**
     * Send a verification code email using a predefined template.
     *
     * @param to               recipient email address
     * @param verificationCode the verification code to send
     * @param serverName       server name to display in the email
     * @param expiryMinutes    validity period in minutes
     * @return true if email was sent successfully, false otherwise
     */
    boolean sendVerificationCodeEmail(String to, String verificationCode, String serverName, int expiryMinutes);

    /**
     * Send a verification code email asynchronously.
     *
     * @param to               recipient email address
     * @param verificationCode the verification code to send
     * @param serverName       server name to display in the email
     * @param expiryMinutes    validity period in minutes
     * @return CompletableFuture that completes with true if successful, false otherwise
     */
    CompletableFuture<Boolean> sendVerificationCodeEmailAsync(String to, String verificationCode, 
                                                               String serverName, int expiryMinutes);

    /**
     * Test the SMTP connection with current configuration.
     *
     * @return true if connection is successful, false otherwise
     */
    boolean testConnection();

    /**
     * Get the last error message if any operation failed.
     *
     * @return last error message or null if no error
     */
    String getLastError();
}
