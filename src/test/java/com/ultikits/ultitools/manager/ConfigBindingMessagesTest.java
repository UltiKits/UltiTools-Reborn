package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.*;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigBindingMessagesTest {
    @Test
    void reloadFailedExceptionKeepsEnglishButReportUsesFallbackLanguage() throws Exception {
        try (var support = new CommandMessageTestSupport("zh");
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.CHINESE)) {
            var failure = new ConfigBindings.ReloadFailedException("config.yml");
            assertThat(failure).hasMessage("the reload of config.yml did not complete (for example, its write-back "
                    + "threw an IOException), so its values were not validated");
            assertThat(failure.reportReason())
                    .isEqualTo("config.yml 的重载未完成（例如写回时抛出了 IOException），因此其值尚未通过校验");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void timer_rule(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_TIMER_RULE,
                    Arg.number("max", 100)))
                    .isEqualTo(code.equals("en") ? "a bound @Scheduled period or delay must be a whole number of seconds from 1 to 100 (0 does not mean off)" : "绑定的 @Scheduled 周期或延迟必须是 1 到 100 范围内的整数秒（0 不表示关闭）");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void cooldown_rule(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_COOLDOWN_RULE,
                    Arg.number("max", 100)))
                    .isEqualTo(code.equals("en") ? "a bound @CmdCD cooldown must be a whole number of seconds from 0 (no cooldown) to 100" : "绑定的 @CmdCD 冷却必须是 0（无冷却）到 100 范围内的整数秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void cooldown_invalid(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_COOLDOWN_INVALID,
                    Arg.string("value", "0"), Arg.text("rule", Text.of(ManagerKeys.CONFIG_BINDING_COOLDOWN_RULE, Arg.number("max", 100)))))
                    .isEqualTo(code.equals("en") ? "it has value 0 after the reload; a bound @CmdCD cooldown must be a whole number of seconds from 0 (no cooldown) to 100" : "重载后的值为 0；绑定的 @CmdCD 冷却必须是 0（无冷却）到 100 范围内的整数秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void reload_incomplete(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_RELOAD_INCOMPLETE,
                    Arg.string("file", "config.yml")))
                    .isEqualTo(code.equals("en") ? "the reload of config.yml did not complete (for example, its write-back threw an IOException), so its values were not validated" : "config.yml 的重载未完成（例如写回时抛出了 IOException），因此其值尚未通过校验");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void scheduled_kept(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_SCHEDULED_KEPT,
                    Arg.string("task", "Task.tick"), Arg.string("config", "Config"), Arg.string("config_key", "key"), Arg.string("value", "0"), Arg.text("rule", Text.of(ManagerKeys.CONFIG_BINDING_TIMER_RULE, Arg.number("max", 100))), Arg.number("seconds", 5)))
                    .isEqualTo(code.equals("en") ? "config-bound @Scheduled Task.tick: Config key 'key' has value 0 after the reload (a bound @Scheduled period or delay must be a whole number of seconds from 1 to 100 (0 does not mean off)); the running 5s is kept" : "绑定配置的 @Scheduled Task.tick：Config 的键 'key' 重载后的值为 0（绑定的 @Scheduled 周期或延迟必须是 1 到 100 范围内的整数秒（0 不表示关闭））；继续使用运行中的 5 秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void scheduled_not_validated(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_SCHEDULED_NOT_VALIDATED,
                    Arg.string("task", "Task.tick"), Arg.string("file", "config.yml"), Arg.string("config", "Config"), Arg.string("config_key", "key"), Arg.number("seconds", 5)))
                    .isEqualTo(code.equals("en") ? "config-bound @Scheduled Task.tick: the reload of config.yml did not complete, so Config key 'key' was not applied; the running 5s is kept" : "绑定配置的 @Scheduled Task.tick：config.yml 的重载没有完成，因此 Config 的键 'key' 未生效；继续使用运行中的 5 秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void cooldown_kept(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_COOLDOWN_KEPT,
                    Arg.string("binding", "Config key"), Arg.string("reason", "detail"), Arg.number("seconds", 5)))
                    .isEqualTo(code.equals("en") ? "config-bound @CmdCD Config key was not updated: detail; the running 5s is kept" : "绑定配置的 @CmdCD Config key 未更新：detail；继续使用运行中的 5 秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void scheduled_invalid_warning(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_SCHEDULED_INVALID_WARNING,
                    Arg.string("module", "Probe"), Arg.string("task", "Task.tick"), Arg.string("config", "Config"), Arg.string("config_key", "key"), Arg.string("value", "0"), Arg.text("rule", Text.of(ManagerKeys.CONFIG_BINDING_TIMER_RULE, Arg.number("max", 100))), Arg.number("seconds", 5)))
                    .isEqualTo(code.equals("en") ? "Probe: Task.tick is bound to Config key 'key', which has value 0 after the reload; a bound @Scheduled period or delay must be a whole number of seconds from 1 to 100 (0 does not mean off); keeping 5s" : "Probe：Task.tick 绑定到 Config 的键 'key'，重载后的值为 0；绑定的 @Scheduled 周期或延迟必须是 1 到 100 范围内的整数秒（0 不表示关闭）；继续使用 5 秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void scheduled_not_validated_warning(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_SCHEDULED_NOT_VALIDATED_WARNING,
                    Arg.string("module", "Probe"), Arg.string("task", "Task.tick"), Arg.string("config", "Config"), Arg.string("config_key", "key"), Arg.string("file", "config.yml"), Arg.number("seconds", 5)))
                    .isEqualTo(code.equals("en") ? "Probe: Task.tick is bound to Config key 'key', but the reload of config.yml did not complete (for example, its write-back threw an IOException), so its values were not validated; keeping 5s" : "Probe：Task.tick 绑定到 Config 的键 'key'，但 config.yml 的重载未完成（例如写回时抛出了 IOException），因此其值尚未通过校验；继续使用 5 秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void cooldown_not_updated_warning(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_COOLDOWN_NOT_UPDATED_WARNING,
                    Arg.string("module", "Probe"), Arg.string("binding", "Config key"), Arg.string("reason", "detail"), Arg.number("seconds", 5)))
                    .isEqualTo(code.equals("en") ? "Probe: @CmdCD bound to Config key is not updated: detail; keeping 5s" : "Probe：绑定到 Config key 的 @CmdCD 未更新：detail；继续使用 5 秒");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void step_failed(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_STEP_FAILED,
                    Arg.string("what", "@Scheduled"), Arg.string("cause", "failed")))
                    .isEqualTo(code.equals("en") ? "applying the reloaded config-bound @Scheduled values failed: failed; updates applied before the failure remain in effect; remaining updates did not complete" : "应用重载后的绑定配置 @Scheduled 值失败：failed；失败前已应用的更新继续生效，其余更新未完成");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void off_thread(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertThat(ConfigBindings.reloadReason(ManagerKeys.CONFIG_BINDING_OFF_THREAD))
                    .isEqualTo(code.equals("en") ? "config-bound @Scheduled and @CmdCD values were not applied: the reload ran off the main thread" : "绑定配置的 @Scheduled 和 @CmdCD 值未生效：重载不是在主线程上执行的");
        }
    }

}
