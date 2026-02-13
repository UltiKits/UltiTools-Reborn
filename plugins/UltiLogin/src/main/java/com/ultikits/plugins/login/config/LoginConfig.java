package com.ultikits.plugins.login.config;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for UltiLogin.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@Getter
@Setter
@ConfigEntity("config/login.yml")
public class LoginConfig extends AbstractConfigEntity {
    
    // ==================== 基础设置 ====================

    @Range(min = 10, max = 600)
    @ConfigEntry(path = "login-timeout", comment = "登录超时时间（秒），超时将被踢出")
    private int loginTimeout = 60;

    @ConfigEntry(path = "session-enabled", comment = "启用会话功能（同一IP短期内无需重新登录）")
    private boolean sessionEnabled = true;

    @Range(min = 1, max = 1440)
    @ConfigEntry(path = "session-timeout", comment = "会话过期时间（分钟）")
    private int sessionTimeout = 30;

    @Range(min = 0, max = 100)
    @ConfigEntry(path = "max-register-per-ip", comment = "同一IP最大注册账户数（0为不限制）")
    private int maxRegisterPerIp = 3;
    
    // ==================== GUI 模式设置 ====================

    @ConfigEntry(path = "gui-mode.enabled", comment = "启用GUI登录模式（数字键盘界面）")
    private boolean guiModeEnabled = false;

    @Range(min = 1, max = 9)
    @ConfigEntry(path = "gui-mode.password-length", comment = "GUI模式密码位数（1-9数字，推荐4-6位）")
    private int guiPasswordLength = 4;

    @NotEmpty
    @ConfigEntry(path = "gui-mode.title-login", comment = "GUI登录界面标题")
    private String guiLoginTitle = "&6请输入密码";

    @NotEmpty
    @ConfigEntry(path = "gui-mode.title-register", comment = "GUI注册界面标题")
    private String guiRegisterTitle = "&6请设置密码";

    @NotEmpty
    @ConfigEntry(path = "gui-mode.title-confirm", comment = "GUI确认密码界面标题")
    private String guiConfirmTitle = "&6请再次输入密码";
    
    // ==================== 命令模式密码设置 ====================

    @Range(min = 4, max = 32)
    @ConfigEntry(path = "password.min-length", comment = "命令模式密码最小长度")
    private int minPasswordLength = 6;

    @Range(min = 6, max = 128)
    @ConfigEntry(path = "password.max-length", comment = "命令模式密码最大长度")
    private int maxPasswordLength = 32;
    
    // ==================== 登录安全保护 ====================

    @Range(min = 0, max = 20)
    @ConfigEntry(path = "security.max-login-attempts", comment = "最大登录失败次数（0为不限制）")
    private int maxLoginAttempts = 5;

    @Range(min = 60, max = 86400)
    @ConfigEntry(path = "security.lockout-duration", comment = "登录失败封禁时长（秒）")
    private int lockoutDuration = 900;

    @NotEmpty
    @ConfigEntry(path = "security.lockout-type", comment = "封禁类型：IP / UUID / BOTH")
    private String lockoutType = "IP";
    
    // ==================== 位置设置 ====================

    @ConfigEntry(path = "spawn-location.enabled", comment = "未登录时传送到指定位置")
    private boolean spawnLocationEnabled = false;

    @NotEmpty
    @ConfigEntry(path = "spawn-location.world", comment = "出生点世界")
    private String spawnWorld = "world";

    @ConfigEntry(path = "spawn-location.x", comment = "出生点X")
    private double spawnX = 0;

    @Range(min = -64, max = 320)
    @ConfigEntry(path = "spawn-location.y", comment = "出生点Y")
    private double spawnY = 64;

    @ConfigEntry(path = "spawn-location.z", comment = "出生点Z")
    private double spawnZ = 0;
    
    // ==================== 其他设置 ====================

    @NotEmpty
    @ConfigEntry(path = "allowed-commands", comment = "未登录时允许执行的命令")
    private List<String> allowedCommands = Arrays.asList("login", "l", "register", "reg", "panel");

    @ConfigEntry(path = "blind-effect", comment = "未登录时给予失明效果")
    private boolean blindEffect = true;

    // ==================== UltiCloud 集成 ====================

    @ConfigEntry(path = "ulticloud.enabled", comment = "Enable UltiCloud integration for web panel login")
    private boolean ulticloudEnabled = false;

    // ==================== 消息配置 ====================

    @NotEmpty
    @ConfigEntry(path = "messages.register-prompt", comment = "注册提示（命令模式）")
    private String registerPrompt = "&e请使用 /register <密码> <确认密码> 注册账号";

    @NotEmpty
    @ConfigEntry(path = "messages.register-prompt-gui", comment = "注册提示（GUI模式）")
    private String registerPromptGui = "&e请在弹出的界面中设置密码";

    @NotEmpty
    @ConfigEntry(path = "messages.login-prompt", comment = "登录提示（命令模式）")
    private String loginPrompt = "&e请使用 /login <密码> 登录";

    @NotEmpty
    @ConfigEntry(path = "messages.login-prompt-gui", comment = "登录提示（GUI模式）")
    private String loginPromptGui = "&e请在弹出的界面中输入密码";

    @NotEmpty
    @ConfigEntry(path = "messages.register-success", comment = "注册成功")
    private String registerSuccess = "&a注册成功！欢迎加入服务器！";

    @NotEmpty
    @ConfigEntry(path = "messages.login-success", comment = "登录成功")
    private String loginSuccess = "&a登录成功！欢迎回来！";

    @NotEmpty
    @ConfigEntry(path = "messages.wrong-password", comment = "密码错误")
    private String wrongPassword = "&c密码错误！请重试。";

    @NotEmpty
    @ConfigEntry(path = "messages.already-logged", comment = "已经登录")
    private String alreadyLogged = "&e你已经登录了！";

    @NotEmpty
    @ConfigEntry(path = "messages.not-registered", comment = "未注册")
    private String notRegistered = "&c你还没有注册！请先注册。";

    @NotEmpty
    @ConfigEntry(path = "messages.already-registered", comment = "已注册")
    private String alreadyRegistered = "&c你已经注册过了！请直接登录。";

    @NotEmpty
    @ConfigEntry(path = "messages.password-mismatch", comment = "密码不匹配")
    private String passwordMismatch = "&c两次输入的密码不一致！";

    @NotEmpty
    @ConfigEntry(path = "messages.password-too-short", comment = "密码太短")
    private String passwordTooShort = "&c密码太短！至少需要 {MIN} 个字符。";

    @NotEmpty
    @ConfigEntry(path = "messages.password-too-long", comment = "密码太长")
    private String passwordTooLong = "&c密码太长！最多 {MAX} 个字符。";

    @NotEmpty
    @ConfigEntry(path = "messages.timeout-kick", comment = "超时踢出")
    private String timeoutKick = "&c登录超时！请重新连接。";

    @NotEmpty
    @ConfigEntry(path = "messages.account-locked", comment = "账户被锁定")
    private String accountLocked = "&c登录失败次数过多！请在 {TIME} 秒后重试。";

    @NotEmpty
    @ConfigEntry(path = "messages.attempts-remaining", comment = "剩余尝试次数")
    private String attemptsRemaining = "&c密码错误！剩余尝试次数: {COUNT}";

    @NotEmpty
    @ConfigEntry(path = "messages.gui-password-invalid", comment = "GUI密码无效")
    private String guiPasswordInvalid = "&c密码必须是 {LENGTH} 位数字！";

    // ==================== 管理员消息 ====================

    @NotEmpty
    @ConfigEntry(path = "messages.admin.password-reset", comment = "管理员重置密码成功")
    private String adminPasswordReset = "&a已重置玩家 {PLAYER} 的密码为: {PASSWORD}";

    @NotEmpty
    @ConfigEntry(path = "messages.admin.force-login", comment = "管理员强制登录")
    private String adminForceLogin = "&a已强制登录玩家 {PLAYER}";

    @NotEmpty
    @ConfigEntry(path = "messages.admin.unregister", comment = "管理员删除账号")
    private String adminUnregister = "&a已删除玩家 {PLAYER} 的账号";

    @NotEmpty
    @ConfigEntry(path = "messages.admin.player-not-found", comment = "玩家不存在")
    private String adminPlayerNotFound = "&c找不到玩家 {PLAYER}";

    @NotEmpty
    @ConfigEntry(path = "messages.admin.account-not-found", comment = "账号不存在")
    private String adminAccountNotFound = "&c玩家 {PLAYER} 尚未注册";
    
    public LoginConfig() {
        super("config/login.yml");
    }
    
    /**
     * Get the effective password min length based on mode.
     * GUI mode uses guiPasswordLength, command mode uses minPasswordLength.
     */
    public int getEffectiveMinPasswordLength() {
        return guiModeEnabled ? guiPasswordLength : minPasswordLength;
    }
    
    /**
     * Get the effective password max length based on mode.
     * GUI mode uses guiPasswordLength, command mode uses maxPasswordLength.
     */
    public int getEffectiveMaxPasswordLength() {
        return guiModeEnabled ? guiPasswordLength : maxPasswordLength;
    }
}
