package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the five behaviours 06-01-PLAN.md Task 3 assigns {@link AccessDecision}: the allowed shape,
 * the two denied shapes, their message wording distinguishing D-17's two refusal causes, and the
 * blank-config-key guard on {@link AccessDecision#deniedConfigurable}.
 */
@DisplayName("AccessDecision")
class AccessDecisionTest {

    @Nested
    @DisplayName("allowed()")
    class Allowed {

        @Test
        @DisplayName("放行：isAllowed 为 true，reason 与 configKey 均为 null")
        void reportsAllowedWithNullReasonAndConfigKey() {
            AccessDecision decision = AccessDecision.allowed();

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.getReason()).isNull();
            assertThat(decision.getConfigKey()).isNull();
            assertThat(decision.getMessage()).isEmpty();
        }
    }

    @Nested
    @DisplayName("deniedConfigurable(reason, configKey)")
    class DeniedConfigurable {

        @Test
        @DisplayName("拒绝，可配置，携带 configKey，消息中同时出现该键与配置文件路径")
        void reportsDeniedConfigurableWithMessageNamingKeyAndFile() {
            AccessDecision decision = AccessDecision.deniedConfigurable(
                    "outside the editable roots", "ultipanel.files.editable-roots");

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.isConfigurable()).isTrue();
            assertThat(decision.getConfigKey()).isEqualTo("ultipanel.files.editable-roots");
            assertThat(decision.getMessage())
                    .contains("ultipanel.files.editable-roots")
                    .contains("plugins/UltiTools/config.yml");
        }

        @Test
        @DisplayName("configKey 为 null 时抛出 IllegalArgumentException")
        void rejectsNullConfigKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccessDecision.deniedConfigurable("reason", null));
        }

        @Test
        @DisplayName("configKey 为空白字符串时抛出 IllegalArgumentException")
        void rejectsBlankConfigKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccessDecision.deniedConfigurable("reason", "   "));
        }
    }

    @Nested
    @DisplayName("deniedNonConfigurable(reason)")
    class DeniedNonConfigurable {

        @Test
        @DisplayName("拒绝，不可配置，configKey 为 null，消息声明无法通过配置改变")
        void reportsDeniedNonConfigurableWithConfigKeyNull() {
            AccessDecision decision = AccessDecision.deniedNonConfigurable("this file holds credentials");

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.isConfigurable()).isFalse();
            assertThat(decision.getConfigKey()).isNull();
            assertThat(decision.getMessage())
                    .containsIgnoringCase("cannot be changed through configuration");
        }
    }

    @Nested
    @DisplayName("两种拒绝消息的可区分性（D-17）")
    class MessageDistinguishability {

        @Test
        @DisplayName("同一段 reason 文本分别走可配置与不可配置两条路径，产出的消息不相等")
        void configurableAndNonConfigurableMessagesDifferForTheSameReasonText() {
            String reason = "outside the editable roots";

            String configurableMessage = AccessDecision.deniedConfigurable(reason, "ultipanel.files.editable-roots")
                    .getMessage();
            String nonConfigurableMessage = AccessDecision.deniedNonConfigurable(reason).getMessage();

            assertThat(configurableMessage)
                    .as("操作员必须能从消息本身判断这条拒绝是否存在开关")
                    .isNotEqualTo(nonConfigurableMessage);
        }
    }
}
