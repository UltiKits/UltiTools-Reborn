package com.ultikits.plugins.login.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration for UltiLogin.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/login.yml")
public class LoginConfig extends AbstractConfigEntity {
    
    @ConfigEntry(path = "login-timeout", comment = "登录超时时间（秒），超时将被踢出")
    private int loginTimeout = 60;
    
    @ConfigEntry(path = "session-enabled", comment = "启用会话功能（同一IP短期内无需重新登录）")
    private boolean sessionEnabled = true;
    
    @ConfigEntry(path = "session-timeout", comment = "会话过期时间（分钟）")
    private int sessionTimeout = 30;
    
    @ConfigEntry(path = "max-register-per-ip", comment = "同一IP最大注册账户数（0为不限制）")
    private int maxRegisterPerIp = 3;
    
    @ConfigEntry(path = "min-password-length", comment = "密码最小长度")
    private int minPasswordLength = 6;
    
    @ConfigEntry(path = "max-password-length", comment = "密码最大长度")
    private int maxPasswordLength = 32;
    
    @ConfigEntry(path = "spawn-location.enabled", comment = "未登录时传送到指定位置")
    private boolean spawnLocationEnabled = false;
    
    @ConfigEntry(path = "spawn-location.world", comment = "出生点世界")
    private String spawnWorld = "world";
    
    @ConfigEntry(path = "spawn-location.x", comment = "出生点X")
    private double spawnX = 0;
    
    @ConfigEntry(path = "spawn-location.y", comment = "出生点Y")
    private double spawnY = 64;
    
    @ConfigEntry(path = "spawn-location.z", comment = "出生点Z")
    private double spawnZ = 0;
    
    @ConfigEntry(path = "allowed-commands", comment = "未登录时允许执行的命令")
    private List<String> allowedCommands = Arrays.asList("login", "l", "register", "reg");
    
    @ConfigEntry(path = "blind-effect", comment = "未登录时给予失明效果")
    private boolean blindEffect = true;
    
    @ConfigEntry(path = "messages.register-prompt", comment = "注册提示")
    private String registerPrompt = "&e请使用 /register <密码> <确认密码> 注册账号";
    
    @ConfigEntry(path = "messages.login-prompt", comment = "登录提示")
    private String loginPrompt = "&e请使用 /login <密码> 登录";
    
    @ConfigEntry(path = "messages.register-success", comment = "注册成功")
    private String registerSuccess = "&a注册成功！欢迎加入服务器！";
    
    @ConfigEntry(path = "messages.login-success", comment = "登录成功")
    private String loginSuccess = "&a登录成功！欢迎回来！";
    
    @ConfigEntry(path = "messages.wrong-password", comment = "密码错误")
    private String wrongPassword = "&c密码错误！请重试。";
    
    @ConfigEntry(path = "messages.already-logged", comment = "已经登录")
    private String alreadyLogged = "&e你已经登录了！";
    
    @ConfigEntry(path = "messages.not-registered", comment = "未注册")
    private String notRegistered = "&c你还没有注册！请先注册。";
    
    @ConfigEntry(path = "messages.already-registered", comment = "已注册")
    private String alreadyRegistered = "&c你已经注册过了！请直接登录。";
    
    @ConfigEntry(path = "messages.password-mismatch", comment = "密码不匹配")
    private String passwordMismatch = "&c两次输入的密码不一致！";
    
    @ConfigEntry(path = "messages.password-too-short", comment = "密码太短")
    private String passwordTooShort = "&c密码太短！至少需要 {MIN} 个字符。";
    
    @ConfigEntry(path = "messages.password-too-long", comment = "密码太长")
    private String passwordTooLong = "&c密码太长！最多 {MAX} 个字符。";
    
    @ConfigEntry(path = "messages.timeout-kick", comment = "超时踢出")
    private String timeoutKick = "&c登录超时！请重新连接。";
    
    public LoginConfig() {
        super("config/login.yml");
    }
}
