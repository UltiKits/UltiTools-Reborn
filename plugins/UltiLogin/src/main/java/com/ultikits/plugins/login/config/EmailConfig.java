package com.ultikits.plugins.login.config;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for email binding and password recovery.
 * <p>
 * 邮箱绑定和密码找回配置。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/email.yml")
public class EmailConfig extends AbstractConfigEntity {

    // ==================== 验证码设置 ====================

    @Range(min = 4, max = 8)
    @ConfigEntry(path = "verification.code-length", comment = "验证码长度 (4-8)")
    private int codeLength = 6;

    @Range(min = 60, max = 1800)
    @ConfigEntry(path = "verification.code-expiry-seconds", comment = "验证码有效期（秒）")
    private int codeExpirySeconds = 300;

    @Range(min = 1, max = 10)
    @ConfigEntry(path = "verification.max-attempts", comment = "每个验证码最大尝试次数")
    private int maxAttempts = 3;

    @Range(min = 30, max = 600)
    @ConfigEntry(path = "verification.cooldown-seconds", comment = "重新发送验证码冷却时间（秒）")
    private int cooldownSeconds = 60;

    // ==================== 邮箱域名黑名单 ====================

    @ConfigEntry(path = "domain-blacklist", comment = "邮箱域名黑名单（临时邮箱）")
    private List<String> domainBlacklist = Arrays.asList(
            "10minutemail.com",
            "tempmail.com",
            "guerrillamail.com",
            "mailinator.com",
            "throwaway.email"
    );

    // ==================== 账号限制 ====================

    @Range(min = 1, max = 10)
    @ConfigEntry(path = "max-accounts-per-email", comment = "每个邮箱最多绑定账号数")
    private int maxAccountsPerEmail = 1;

    // ==================== 绑定奖励 ====================

    @ConfigEntry(path = "reward.enabled", comment = "启用绑定邮箱奖励")
    private boolean rewardEnabled = false;

    @ConfigEntry(path = "reward.commands", comment = "绑定奖励命令（%player% 替换为玩家名）")
    private List<String> rewardCommands = Arrays.asList("givemoney %player% 500");

    public EmailConfig() {
        super("config/email.yml");
    }
}
