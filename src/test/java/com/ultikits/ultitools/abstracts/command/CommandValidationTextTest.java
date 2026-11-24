package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator.ValidationResult;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import org.junit.jupiter.api.Test;

class CommandValidationTextTest {
    @Test
    void validationResultsCarryDeferredTextWithoutRenderingIt() {
        Text text = Text.of(CommandKeys.COOLDOWN, Arg.number("seconds", 5L));
        ValidationResult result = ValidationResult.failure(text, "cooldown");
        var chain = ValidatorChain.builder().add(context -> result).build();

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getErrorText()).isEqualTo(text);
        assertThat(result.getErrorKey()).isEqualTo("cooldown");
        assertThat(chain.validate(null).getErrorText()).isEqualTo(text);
        assertThat(chain.validateAll(null).getErrorText()).isEqualTo(text);
        assertThat(chain.validateAll(null).getErrorMessage()).isNull();
    }

    @Test
    void firstFailureAndLegacyStringsKeepTheirOriginalSemantics() {
        var legacy = ValidationResult.failure("<red>module text", "custom");
        var chain = ValidatorChain.builder().add(context -> legacy)
                .add(context -> ValidationResult.failure(Text.of(CommandKeys.TIMEOUT), "timeout")).build();

        assertThat(legacy.getErrorText()).isNull();
        assertThat(chain.validateAll(null).getErrorText()).isNull();
        assertThat(chain.validateAll(null).getErrorMessage()).isEqualTo("<red>module text");
        assertThat(ValidationResult.success().getErrorText()).isNull();
        assertThat(new ValidatorChain().validate(null).getErrorText()).isNull();
        assertThat(new ValidatorChain().validateAll(null).getErrorText()).isNull();
    }

    @Test
    void runtimeUsesRootServicesAndNestedOverridesRestoreThem() throws Exception {
        var messages = mock(Messages.class);
        var scheduler = mock(Scheduler.class);
        var plugin = mock(UltiTools.class);
        var dependencies = mock(DependenceManagers.class);
        var context = mock(SimpleContainer.class);
        when(plugin.getDependenceManagers()).thenReturn(dependencies);
        when(dependencies.getContext()).thenReturn(context);
        when(context.getBean(Messages.class)).thenReturn(messages);
        try (var ulti = mockStatic(UltiTools.class); var bridge = mockStatic(FrameworkScheduling.class)) {
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            bridge.when(FrameworkScheduling::scheduler).thenReturn(scheduler);
            assertThat(CommandRuntime.messages()).isSameAs(messages);
            assertThat(CommandRuntime.scheduler()).isSameAs(scheduler);
            var replacement = mock(Messages.class);
            try (var outer = CommandRuntime.override(replacement, scheduler)) {
                try (var inner = CommandRuntime.override(messages, scheduler)) {
                    assertThat(CommandRuntime.messages()).isSameAs(messages);
                }
                assertThat(CommandRuntime.messages()).isSameAs(replacement);
            }
            assertThat(CommandRuntime.messages()).isSameAs(messages);
        }
    }

    @Test
    void unavailableRuntimeMessagesHaveAnExplicitFailure() {
        try (var ulti = mockStatic(UltiTools.class)) {
            ulti.when(UltiTools::getInstance).thenReturn(null);
            assertThatThrownBy(CommandRuntime::messages).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Framework messages are not available");
        }
    }
}
