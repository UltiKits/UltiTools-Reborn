package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.Text;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormResponseTest {
    private static final Text LABEL = Text.plain("Input");

    @Test
    void get_returnsEveryTypedValueAndCopiesMap() {
        TextInput name = TextInput.builder("name", LABEL).build();
        ToggleInput flag = ToggleInput.of("flag", LABEL, false);
        SliderInput count = SliderInput.builder("count", LABEL, 0, 10).build();
        ChoiceInput<Integer> choice = ChoiceInput.of("choice", LABEL, List.of(ChoiceOption.of(1, LABEL), ChoiceOption.of(2, LABEL)));
        Form form = Form.builder(LABEL).input(name).input(flag).input(count).input(choice).build();
        Map<FormInput<?>, Object> values = new HashMap<>(Map.of(name, "Ada", flag, true, count, 2.0, choice, 2));
        FormResponse response = FormResponse.of(form, values);
        values.clear();
        String entered = response.get(name);
        boolean checked = response.get(flag);
        double amount = response.get(count);
        int selected = response.get(choice);
        assertThat(entered).isEqualTo("Ada");
        assertThat(checked).isTrue();
        assertThat(amount).isEqualTo(2);
        assertThat(selected).isEqualTo(2);
        assertThat(response.toString()).contains("Ada");
    }

    @Test
    void get_rejectsForeignHandleEvenIfValueEqual() {
        TextInput own = TextInput.builder("name", LABEL).build();
        TextInput foreign = TextInput.builder("name", LABEL).build();
        Form form = Form.builder(LABEL).input(own).build();
        FormResponse response = FormResponse.of(form, Map.of(own, "own"));
        assertThat(own).isEqualTo(foreign).isNotSameAs(foreign);
        assertThatThrownBy(() -> response.get(foreign)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormResponse.of(form, Map.of(foreign, "foreign"))).isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException().isThrownBy(() -> response.get(null)).withMessage("input");
    }

    @Test
    void of_rejectsWrongTypesForEveryKind() {
        List<FormInput<?>> inputs = List.of(TextInput.builder("name", LABEL).build(), ToggleInput.of("flag", LABEL, false),
                SliderInput.builder("count", LABEL, 0, 10).build(), ChoiceInput.of("choice", LABEL, List.of(ChoiceOption.of(1, LABEL))));
        for (FormInput<?> input : inputs) {
            Form form = Form.builder(LABEL).input(input).build();
            assertThatThrownBy(() -> FormResponse.of(form, Map.of(input, new Object())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ChoiceInput<Integer> choice = ChoiceInput.of("choice", LABEL, List.of(ChoiceOption.of(1, LABEL)));
        assertThatThrownBy(() -> FormResponse.of(Form.builder(LABEL).input(choice).build(), Map.of(choice, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        SliderInput slider = SliderInput.builder("slider", LABEL, 0, 10).build();
        assertThatThrownBy(() -> FormResponse.of(Form.builder(LABEL).input(slider).build(), Map.of(slider, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_sliderBeyondHalfStepOutsideRange_isRejected() {
        SliderInput slider = SliderInput.builder("count", LABEL, 1, 64).build();
        Form form = Form.builder(LABEL).input(slider).build();
        for (double value : new double[] {-1000.0, 0.4, 64.6, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> FormResponse.of(form, Map.of(slider, value)))
                    .as("reject slider answer %s", value).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void of_sliderValue_isSnappedToStepGrid() {
        SliderInput slider = SliderInput.builder("count", LABEL, 0, 10).step(0.5).build();
        Form form = Form.builder(LABEL).input(slider).build();
        assertThat(FormResponse.of(form, Map.of(slider, 3.26)).get(slider)).isEqualTo(3.5);
        assertThat(FormResponse.of(form, Map.of(slider, 0.6)).get(slider)).isEqualTo(0.5);
        assertThat(FormResponse.of(form, Map.of(slider, -0.2)).get(slider)).isEqualTo(0.0);
    }

    @Test
    void of_sliderNearMax_returnsMaxExactly() {
        SliderInput slider = SliderInput.builder("count", LABEL, 0, 0.3).step(0.1).build();
        Form form = Form.builder(LABEL).input(slider).build();
        assertThat(FormResponse.of(form, Map.of(slider, 0.30000001192092896)).get(slider)).isEqualTo(0.3);
    }

    @Test
    void of_textTooLongOrTooManyLines_isRejected() {
        TextInput shortText = TextInput.builder("short", LABEL).maxLength(4).build();
        Form shortForm = Form.builder(LABEL).input(shortText).build();
        assertThatThrownBy(() -> FormResponse.of(shortForm, Map.of(shortText, "12345")))
                .isInstanceOf(IllegalArgumentException.class);
        TextInput lines = TextInput.builder("lines", LABEL).maxLines(2).build();
        Form linesForm = Form.builder(LABEL).input(lines).build();
        assertThatThrownBy(() -> FormResponse.of(linesForm, Map.of(lines, "a\nb\nc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FormResponse.of(linesForm, Map.of(lines, "a\nb")).get(lines)).isEqualTo("a\nb");
    }

    @Test
    void of_requiresExactlyAllHandlesAndNonNullValues() {
        TextInput input = TextInput.builder("name", LABEL).build();
        Form form = Form.builder(LABEL).input(input).build();
        assertThatThrownBy(() -> FormResponse.of(form, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormResponse.of(form, Map.of(input, "a", ToggleInput.of("other", LABEL, true), false)))
                .isInstanceOf(IllegalArgumentException.class);
        Map<FormInput<?>, Object> values = new IdentityHashMap<>();
        values.put(input, null);
        assertThatNullPointerException().isThrownBy(() -> FormResponse.of(form, values)).withMessage("value");
        values.clear();
        values.put(null, "a");
        assertThatNullPointerException().isThrownBy(() -> FormResponse.of(form, values)).withMessage("input");
        assertThatNullPointerException().isThrownBy(() -> FormResponse.of(null, Map.of())).withMessage("form");
        assertThatNullPointerException().isThrownBy(() -> FormResponse.of(form, null)).withMessage("values");
    }
}
