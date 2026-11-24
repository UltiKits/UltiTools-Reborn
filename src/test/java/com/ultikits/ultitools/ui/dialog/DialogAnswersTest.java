package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.ChoiceInput;
import com.ultikits.ultitools.ui.ChoiceOption;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.FormResponse;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.ToggleInput;
import com.ultikits.ultitools.ui.UiResult;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DialogAnswersTest {
    private final Logger logger = mock(Logger.class);
    private final DialogAnswers answers = new DialogAnswers(logger);
    private final DialogResponseView response = mock(DialogResponseView.class);
    private final TextInput name = TextInput.builder("name", Text.plain("Name")).maxLength(4).maxLines(2).build();
    private final ToggleInput flag = ToggleInput.of("flag", Text.plain("Flag"), false);
    private final ChoiceInput<String> mode = ChoiceInput.of("mode", Text.plain("Mode"), List.of(
            ChoiceOption.of("a", Text.plain("A")), ChoiceOption.of("b", Text.plain("B"))));
    private final SliderInput level = SliderInput.builder("level", Text.plain("Level"), 0, 10).step(0.5).build();
    private final Form form = Form.builder(Text.plain("Form")).input(name).input(flag).input(mode).input(level).build();

    private void validResponse() {
        when(response.getText("i0")).thenReturn("hi");
        when(response.getBoolean("i1")).thenReturn(true);
        when(response.getText("i2")).thenReturn("1");
        when(response.getFloat("i3")).thenReturn(7.5f);
    }

    @Test
    void form_readsAllValuesUsingOriginalHandles() {
        validResponse();
        UiResult<?> result = answers.read(form, new ButtonRole.Submit(), response, "Shop");
        assertThat(result).isInstanceOf(UiResult.Completed.class);
        FormResponse values = (FormResponse) result.value();
        assertThat(values.get(name)).isEqualTo("hi");
        assertThat(values.get(flag)).isTrue();
        assertThat(values.get(mode)).isEqualTo("b");
        assertThat(values.get(level)).isEqualTo(7.5);
        verifyNoInteractions(logger);
    }

    @Test
    void form_reservedBusinessKeyReadsOnlyThePositionalWireKey() {
        TextInput id = TextInput.builder("id", Text.plain("ID")).build();
        Form reserved = Form.builder(Text.plain("Form")).input(id).build();
        when(response.getText("i0")).thenReturn("answer");
        UiResult<?> result = answers.read(reserved, new ButtonRole.Submit(), response, "Shop");
        assertThat(result).isInstanceOf(UiResult.Completed.class);
        assertThat(((FormResponse) result.value()).get(id)).isEqualTo("answer");
        verifyNoInteractions(logger);
    }

    @Test
    void form_letsTheContractSnapFloatValuesOnce() {
        validResponse();
        when(response.getFloat("i3")).thenReturn(7.500001f);
        UiResult<?> result = answers.read(form, new ButtonRole.Submit(), response, "Shop");
        assertThat(((FormResponse) result.value()).get(level)).isEqualTo(7.5);
        when(response.getFloat("i3")).thenReturn(10.25f);
        assertThat(((FormResponse) answers.read(form, new ButtonRole.Submit(), response, "Shop").value()).get(level)).isEqualTo(10.0);
        verifyNoInteractions(logger);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"too long", "a\nb\nc"})
    void form_missingOrInvalidTextClosesAndWarns(String text) {
        validResponse();
        when(response.getText("i0")).thenReturn(text);
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"i0", "i1", "i2", "i3"})
    void form_eachMissingControlClosesAndWarns(String key) {
        validResponse();
        switch (key) {
            case "i1" -> when(response.getBoolean(key)).thenReturn(null);
            case "i3" -> when(response.getFloat(key)).thenReturn(null);
            default -> when(response.getText(key)).thenReturn(null);
        }
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2", "nonsense", "+1", "01", "99999999999999"})
    void form_unknownOptionIdClosesAndWarns(String id) {
        validResponse();
        when(response.getText("i2")).thenReturn(id);
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(floats = {-0.26f, 10.26f, Float.NaN, Float.POSITIVE_INFINITY})
    void form_invalidSliderClosesAndWarns(float value) {
        validResponse();
        when(response.getFloat("i3")).thenReturn(value);
        assertRejected();
    }

    private void assertRejected() {
        assertThat(answers.read(form, new ButtonRole.Submit(), response, "Shop"))
                .isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        verify(logger).log(eq(Level.WARNING), contains("Shop"), any(Throwable.class));
    }

    @Test
    void noticeAndConfirm_decodeOnlyTheirSemanticButtons() {
        assertThat(answers.read(Notice.builder(Text.plain("t")).build(), new ButtonRole.Ack(), null, "Shop"))
                .isEqualTo(new UiResult.Completed<>(Ack.ACK));
        Confirm confirm = Confirm.builder(Text.plain("t")).build();
        assertThat(answers.read(confirm, new ButtonRole.Yes(), null, "Shop")).isEqualTo(new UiResult.Completed<>(true));
        assertThat(answers.read(confirm, new ButtonRole.No(), null, "Shop")).isEqualTo(new UiResult.Completed<>(false));
        assertThat(answers.read(confirm, new ButtonRole.Cancel(), null, "Shop")).isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        verifyNoInteractions(logger);
    }

    @Test
    void choice_returnsDomainValueNotItsWireIdAndCancelIsClosed() {
        Object value = new Object();
        Choice<Object> choice = Choice.<Object>builder(Text.plain("Pick")).option(value, Text.plain("same")).build();
        assertThat(answers.read(choice, new ButtonRole.Pick(0), null, "Shop").value()).isSameAs(value);
        assertThat(answers.read(choice, new ButtonRole.Cancel(), null, "Shop")).isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        assertThat(answers.read(form, new ButtonRole.Cancel(), null, "Shop")).isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        verifyNoInteractions(logger);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, Integer.MAX_VALUE})
    void choice_rejectsOutOfRangePositions(int index) {
        Choice<Integer> choice = Choice.<Integer>builder(Text.plain("t")).option(1, Text.plain("one")).build();
        assertThat(answers.read(choice, new ButtonRole.Pick(index), null, "Shop"))
                .isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        verify(logger).log(eq(Level.WARNING), contains("Shop"), any(Throwable.class));
    }

    @Test
    void unexpectedRoleAndMissingPayloadAreRejected() {
        Confirm confirm = Confirm.builder(Text.plain("t")).build();
        assertThat(answers.read(confirm, new ButtonRole.Ack(), response, "Shop")).isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        assertThat(answers.read(form, new ButtonRole.Submit(), null, "Shop")).isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
    }
}
