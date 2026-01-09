package com.ultikits.ultitools.services.impl;

import java.security.SecureRandom;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.services.EmailService;

/**
 * Default implementation of EmailService using JavaMail.
 * <p>
 * 使用JavaMail实现的默认邮件服务。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
@Service
public class DefaultEmailService implements EmailService {

    private static final Logger LOGGER = Logger.getLogger(DefaultEmailService.class.getName());
    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String NUMERIC = "0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private volatile Session mailSession;
    private volatile String lastError;
    private volatile boolean initialized = false;

    /**
     * Initialize the mail session with configuration from config.yml
     */
    private synchronized void initializeSession() {
        if (initialized) {
            return;
        }

        try {
            if (!isConfigured()) {
                lastError = "Email service is not configured in config.yml";
                return;
            }

            Properties props = new Properties();
            String host = getConfig("email.smtp.host");
            int port = UltiTools.getInstance().getConfig().getInt("email.smtp.port", 587);
            boolean ssl = UltiTools.getInstance().getConfig().getBoolean("email.smtp.ssl", false);
            boolean starttls = UltiTools.getInstance().getConfig().getBoolean("email.smtp.starttls", true);
            int connectionTimeout = UltiTools.getInstance().getConfig().getInt("email.smtp.connectionTimeout", 10000);
            int readTimeout = UltiTools.getInstance().getConfig().getInt("email.smtp.readTimeout", 10000);
            boolean debug = UltiTools.getInstance().getConfig().getBoolean("email.debug", false);

            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", String.valueOf(connectionTimeout));
            props.put("mail.smtp.timeout", String.valueOf(readTimeout));

            if (ssl) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            }

            if (starttls) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }

            final String username = getConfig("email.smtp.username");
            final String password = getConfig("email.smtp.password");

            mailSession = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            mailSession.setDebug(debug);
            initialized = true;
            lastError = null;

            LOGGER.info("[UltiTools] Email service initialized successfully");
        } catch (Exception e) {
            lastError = "Failed to initialize email service: " + e.getMessage();
            LOGGER.log(Level.WARNING, "[UltiTools] " + lastError, e);
        }
    }

    private String getConfig(String path) {
        return UltiTools.getInstance().getConfig().getString(path, "");
    }

    private boolean isConfigured() {
        boolean enabled = UltiTools.getInstance().getConfig().getBoolean("email.enable", false);
        String host = getConfig("email.smtp.host");
        String username = getConfig("email.smtp.username");
        String password = getConfig("email.smtp.password");

        return enabled &&
                host != null && !host.isEmpty() && !host.equals("smtp.example.com") &&
                username != null && !username.isEmpty() &&
                password != null && !password.isEmpty();
    }

    @Override
    public boolean isEnabled() {
        return isConfigured();
    }

    @Override
    public boolean sendEmail(String to, String subject, String content) {
        return sendMailInternal(to, subject, content, false);
    }

    @Override
    public boolean sendEmail(List<String> recipients, String subject, String content) {
        if (recipients == null || recipients.isEmpty()) {
            lastError = "Recipients list is empty";
            return false;
        }

        boolean allSuccess = true;
        for (String recipient : recipients) {
            if (!sendEmail(recipient, subject, content)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    public boolean sendHtmlEmail(String to, String subject, String htmlContent) {
        return sendMailInternal(to, subject, htmlContent, true);
    }

    @Override
    public boolean sendHtmlEmail(List<String> recipients, String subject, String htmlContent) {
        if (recipients == null || recipients.isEmpty()) {
            lastError = "Recipients list is empty";
            return false;
        }

        boolean allSuccess = true;
        for (String recipient : recipients) {
            if (!sendHtmlEmail(recipient, subject, htmlContent)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    public CompletableFuture<Boolean> sendEmailAsync(String to, String subject, String content) {
        return CompletableFuture.supplyAsync(() -> sendEmail(to, subject, content));
    }

    @Override
    public CompletableFuture<Boolean> sendHtmlEmailAsync(String to, String subject, String htmlContent) {
        return CompletableFuture.supplyAsync(() -> sendHtmlEmail(to, subject, htmlContent));
    }

    @Override
    public String generateVerificationCode(int length) {
        if (length < 1) length = 6;
        if (length > 20) length = 20;

        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(NUMERIC.charAt(RANDOM.nextInt(NUMERIC.length())));
        }
        return code.toString();
    }

    @Override
    public String generateAlphanumericCode(int length) {
        if (length < 1) length = 6;
        if (length > 20) length = 20;

        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return code.toString();
    }

    @Override
    public boolean sendVerificationCodeEmail(String to, String verificationCode, String serverName, int expiryMinutes) {
        String subject = String.format("[%s] 邮箱验证码 / Email Verification Code", serverName);
        String htmlContent = buildVerificationEmailTemplate(verificationCode, serverName, expiryMinutes);
        return sendHtmlEmail(to, subject, htmlContent);
    }

    @Override
    public CompletableFuture<Boolean> sendVerificationCodeEmailAsync(String to, String verificationCode,
                                                                      String serverName, int expiryMinutes) {
        return CompletableFuture.supplyAsync(() -> 
            sendVerificationCodeEmail(to, verificationCode, serverName, expiryMinutes));
    }

    @Override
    public boolean testConnection() {
        if (!isEnabled()) {
            lastError = "Email service is not enabled";
            return false;
        }

        initializeSession();
        if (mailSession == null) {
            return false;
        }

        try {
            Transport transport = mailSession.getTransport("smtp");
            String host = getConfig("email.smtp.host");
            int port = UltiTools.getInstance().getConfig().getInt("email.smtp.port", 587);
            String username = getConfig("email.smtp.username");
            String password = getConfig("email.smtp.password");

            transport.connect(host, port, username, password);
            transport.close();

            lastError = null;
            LOGGER.info("[UltiTools] Email SMTP connection test successful");
            return true;
        } catch (MessagingException e) {
            lastError = "SMTP connection test failed: " + e.getMessage();
            LOGGER.log(Level.WARNING, "[UltiTools] " + lastError, e);
            return false;
        }
    }

    @Override
    public String getLastError() {
        return lastError;
    }

    @Override
    public String getName() {
        return "EmailService";
    }

    @Override
    public String getAuthor() {
        return "UltiKits";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    /**
     * Internal method to send email
     */
    private boolean sendMailInternal(String to, String subject, String content, boolean isHtml) {
        if (!isEnabled()) {
            lastError = "Email service is not enabled. Please configure it in config.yml";
            LOGGER.warning("[UltiTools] " + lastError);
            return false;
        }

        if (to == null || to.isEmpty()) {
            lastError = "Recipient email address is empty";
            return false;
        }

        if (!isValidEmail(to)) {
            lastError = "Invalid recipient email address: " + to;
            return false;
        }

        initializeSession();
        if (mailSession == null) {
            return false;
        }

        try {
            MimeMessage message = new MimeMessage(mailSession);

            String fromAddress = getConfig("email.from.address");
            String fromName = getConfig("email.from.name");

            if (fromAddress == null || fromAddress.isEmpty()) {
                fromAddress = getConfig("email.smtp.username");
            }
            if (fromName == null || fromName.isEmpty()) {
                fromName = "UltiTools";
            }

            message.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
            message.setSubject(subject, "UTF-8");

            if (isHtml) {
                message.setContent(content, "text/html; charset=UTF-8");
            } else {
                message.setText(content, "UTF-8");
            }

            Transport.send(message);
            lastError = null;

            LOGGER.info("[UltiTools] Email sent successfully to: " + to);
            return true;
        } catch (Exception e) {
            lastError = "Failed to send email: " + e.getMessage();
            LOGGER.log(Level.WARNING, "[UltiTools] " + lastError, e);
            return false;
        }
    }

    /**
     * Simple email validation
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /**
     * Build verification email HTML template
     */
    private String buildVerificationEmailTemplate(String code, String serverName, int expiryMinutes) {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <style>\n" +
                "        body { font-family: 'Segoe UI', Arial, sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }\n" +
                "        .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                "        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; }\n" +
                "        .header h1 { margin: 0; font-size: 24px; }\n" +
                "        .content { padding: 40px 30px; text-align: center; }\n" +
                "        .code-box { background-color: #f8f9fa; border: 2px dashed #667eea; border-radius: 8px; padding: 20px; margin: 30px 0; }\n" +
                "        .code { font-size: 36px; font-weight: bold; color: #667eea; letter-spacing: 8px; font-family: monospace; }\n" +
                "        .message { color: #666; font-size: 14px; line-height: 1.6; }\n" +
                "        .warning { color: #e74c3c; font-size: 12px; margin-top: 20px; }\n" +
                "        .footer { background-color: #f8f9fa; padding: 20px; text-align: center; color: #999; font-size: 12px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"header\">\n" +
                "            <h1>" + escapeHtml(serverName) + "</h1>\n" +
                "        </div>\n" +
                "        <div class=\"content\">\n" +
                "            <p class=\"message\">您正在进行邮箱验证，请使用以下验证码：</p>\n" +
                "            <p class=\"message\">You are verifying your email. Please use the following code:</p>\n" +
                "            <div class=\"code-box\">\n" +
                "                <span class=\"code\">" + escapeHtml(code) + "</span>\n" +
                "            </div>\n" +
                "            <p class=\"message\">验证码有效期为 <strong>" + expiryMinutes + " 分钟</strong>。</p>\n" +
                "            <p class=\"message\">This code will expire in <strong>" + expiryMinutes + " minutes</strong>.</p>\n" +
                "            <p class=\"warning\">如果这不是您的操作，请忽略此邮件。</p>\n" +
                "            <p class=\"warning\">If you did not request this, please ignore this email.</p>\n" +
                "        </div>\n" +
                "        <div class=\"footer\">\n" +
                "            <p>此邮件由 UltiTools 自动发送，请勿直接回复。</p>\n" +
                "            <p>This email was automatically sent by UltiTools. Please do not reply.</p>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * Escape HTML special characters
     */
    private String escapeHtml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }
}
