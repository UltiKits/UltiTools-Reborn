package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentBuildersTest {
    private static final Text TITLE = Text.plain("Title");
    private static final Text LABEL = Text.plain("Label");

    @Test
    void everyIntent_requiresTitle() {
        assertThatNullPointerException().isThrownBy(() -> Notice.builder(null)).withMessage("title");
        assertThatNullPointerException().isThrownBy(() -> Confirm.builder(null)).withMessage("title");
        assertThatNullPointerException().isThrownBy(() -> Form.builder(null)).withMessage("title");
        assertThatNullPointerException().isThrownBy(() -> Choice.builder(null)).withMessage("title");
        assertThatNullPointerException().isThrownBy(() -> Grid.builder(null, 1)).withMessage("title");
    }

    @Test
    void defaults_areDeferredFrameworkMessages() {
        Notice notice = Notice.builder(TITLE).build();
        Confirm confirm = Confirm.builder(TITLE).build();
        Form form = Form.builder(TITLE).input(ToggleInput.of("yes", LABEL, false)).build();
        assertThat(notice.button()).isEqualTo(Text.of(MessageKey.of("ultitools", "ui.ok")));
        assertThat(confirm.yes()).isEqualTo(Text.of(MessageKey.of("ultitools", "ui.yes")));
        assertThat(confirm.no()).isEqualTo(Text.of(MessageKey.of("ultitools", "ui.no")));
        assertThat(form.submit()).isEqualTo(Text.of(MessageKey.of("ultitools", "ui.submit")));
        assertThat(notice.body()).isEmpty();
        assertThat(confirm.body()).isEmpty();
    }

    @Test
    void bodies_copyInputsAndBuilderSnapshots() {
        List<Text> lines = new ArrayList<>(List.of(LABEL));
        Notice.Builder noticeBuilder = Notice.builder(TITLE).body(lines).button(LABEL);
        Confirm.Builder confirmBuilder = Confirm.builder(TITLE).body(lines).yes(LABEL).no(TITLE);
        FormInput<?> input = ToggleInput.of("flag", LABEL, true);
        Form.Builder formBuilder = Form.builder(TITLE).body(lines).input(input).submit(LABEL);
        Choice.Builder<String> choiceBuilder = Choice.<String>builder(TITLE).body(lines).option("a", LABEL);
        Notice notice = noticeBuilder.build();
        Confirm confirm = confirmBuilder.build();
        Form form = formBuilder.build();
        Choice<String> choice = choiceBuilder.build();
        lines.clear();
        noticeBuilder.addBody(TITLE);
        confirmBuilder.addBody(TITLE);
        formBuilder.addBody(TITLE).input(TextInput.builder("name", LABEL).build());
        choiceBuilder.addBody(TITLE).option("b", TITLE);
        assertThat(notice.body()).containsExactly(LABEL);
        assertThat(confirm.body()).containsExactly(LABEL);
        assertThat(form.body()).containsExactly(LABEL);
        assertThat(choice.body()).containsExactly(LABEL);
        assertThat(form.inputs()).containsExactly(input);
        assertThat(choice.options()).containsExactly(ChoiceOption.of("a", LABEL));
        assertThatThrownBy(() -> notice.body().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> confirm.body().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> form.inputs().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> choice.options().clear()).isInstanceOf(UnsupportedOperationException.class);
        sameValue(notice, Notice.builder(TITLE).body(List.of(LABEL)).button(LABEL).build());
        sameValue(confirm, Confirm.builder(TITLE).body(List.of(LABEL)).yes(LABEL).no(TITLE).build());
        sameValue(form, Form.builder(TITLE).body(List.of(LABEL)).input(input).submit(LABEL).build());
        sameValue(choice, Choice.<String>builder(TITLE).body(List.of(LABEL)).option("a", LABEL).build());
    }

    @Test
    void form_requiresInputsAndUniqueKeys() {
        assertThatThrownBy(() -> Form.builder(TITLE).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Form.builder(TITLE).input(TextInput.builder("key", LABEL).build())
                .input(ToggleInput.of("key", LABEL, true)).build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(Form.builder(TITLE).input(TextInput.builder("first", LABEL).build())
                .input(ToggleInput.of("second", LABEL, true)).build().inputs()).hasSize(2);
    }

    @Test
    void allInputKinds_validateKeyAndLabel() {
        for (String key : List.of("", "UPPER", "with-dash", "with.dot", "with space", "x".repeat(33), "中文")) {
            assertThatThrownBy(() -> TextInput.builder(key, LABEL)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ToggleInput.of(key, LABEL, true)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ChoiceInput.of(key, LABEL, List.of(ChoiceOption.of("a", LABEL))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SliderInput.builder(key, LABEL, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TextInput.builder("a_0".repeat(10) + "ab", LABEL).build().key()).hasSize(32);
        assertThatNullPointerException().isThrownBy(() -> TextInput.builder(null, LABEL)).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> ToggleInput.of("a", null, true)).withMessage("label");
        assertThatNullPointerException().isThrownBy(() -> ChoiceInput.of("a", null, List.of(ChoiceOption.of(1, LABEL))));
        assertThatNullPointerException().isThrownBy(() -> SliderInput.builder("a", null, 0, 1));
    }

    @Test
    void textInput_validatesLengthAndLines() {
        TextInput defaults = TextInput.builder("name", LABEL).build();
        assertThat(defaults.initial()).isEmpty();
        assertThat(defaults.maxLength()).isEqualTo(32);
        assertThat(defaults.maxLines()).isOne();
        TextInput input = TextInput.builder("name", LABEL).initial("hello").maxLength(TextInput.MAX_LENGTH).maxLines(3).build();
        assertThat(input.key()).isEqualTo("name");
        assertThat(input.label()).isEqualTo(LABEL);
        assertThat(input.initial()).isEqualTo("hello");
        assertThat(input.maxLength()).isEqualTo(256);
        assertThat(input.maxLines()).isEqualTo(3);
        assertThat(TextInput.builder("name", LABEL).maxLength(1).build().maxLength()).isOne();
        for (int invalid : new int[] {0, -1, TextInput.MAX_LENGTH + 1}) {
            assertThatThrownBy(() -> TextInput.builder("name", LABEL).maxLength(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TextInput.builder("name", LABEL).maxLines(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException().isThrownBy(() -> TextInput.builder("name", LABEL).initial(null)).withMessage("value");
        sameValue(input, TextInput.builder("name", LABEL).initial("hello").maxLength(256).maxLines(3).build());
        assertThat(input).isNotEqualTo(defaults);
    }

    @Test
    void choiceInput_validatesOptionsAndInitialAndCopiesList() {
        List<ChoiceOption<Integer>> options = new ArrayList<>(List.of(ChoiceOption.of(1, LABEL), new ChoiceOption<>(2, TITLE, LABEL)));
        ChoiceInput<Integer> first = ChoiceInput.of("choice", LABEL, options);
        ChoiceInput<Integer> second = ChoiceInput.of("choice", LABEL, options, 2);
        options.clear();
        assertThat(first.initial()).isOne();
        assertThat(second.initial()).isEqualTo(2);
        assertThat(first.key()).isEqualTo("choice");
        assertThat(first.label()).isEqualTo(LABEL);
        assertThat(first.options()).hasSize(2);
        assertThat(first.options().get(1).tooltip()).isEqualTo(LABEL);
        assertThatThrownBy(() -> first.options().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ChoiceInput.of("choice", LABEL, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChoiceInput.of("choice", LABEL, List.of(ChoiceOption.of(1, LABEL), ChoiceOption.of(1, TITLE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChoiceInput.of("choice", LABEL, first.options(), 3)).isInstanceOf(IllegalArgumentException.class);
        sameValue(first, ChoiceInput.of("choice", LABEL, first.options()));
        assertThat(first).isNotEqualTo(second);
        sameValue(ToggleInput.of("flag", LABEL, true), ToggleInput.of("flag", LABEL, true));
        assertThat(ToggleInput.of("flag", LABEL, true).initial()).isTrue();
    }

    @Test
    void choice_validatesValuesNotLabels() {
        assertThatThrownBy(() -> Choice.builder(TITLE).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Choice.<Integer>builder(TITLE).option(1, LABEL).option(1, TITLE).build())
                .isInstanceOf(IllegalArgumentException.class);
        Choice<Integer> choice = Choice.<Integer>builder(TITLE).option(1, LABEL).option(2, LABEL).build();
        assertThat(choice.title()).isEqualTo(TITLE);
        assertThat(choice.options()).extracting(ChoiceOption::value).containsExactly(1, 2);
        assertThat(ChoiceOption.of(1, LABEL).tooltip()).isNull();
    }

    @Test
    void slider_validatesRangeStepAndInitial() {
        SliderInput input = SliderInput.builder("level", LABEL, -1, 3).step(0.5).initial(1).build();
        assertThat(input.key()).isEqualTo("level");
        assertThat(input.label()).isEqualTo(LABEL);
        assertThat(input.min()).isEqualTo(-1);
        assertThat(input.max()).isEqualTo(3);
        assertThat(input.step()).isEqualTo(0.5);
        assertThat(input.initial()).isEqualTo(1);
        sameValue(input, SliderInput.builder("level", LABEL, -1, 3).step(0.5).initial(1).build());
        assertThat(SliderInput.builder("n", LABEL, 0, 1).build().initial()).isZero();
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> SliderInput.builder("n", LABEL, 0, 1).step(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        for (double invalid : new double[] {-1, 2, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> SliderInput.builder("n", LABEL, 0, 1).initial(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SliderInput.builder("n", LABEL, 1, 1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SliderInput.builder("n", LABEL, 2, 1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SliderInput.builder("n", LABEL, Double.NaN, 1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SliderInput.builder("n", LABEL, 0, Double.POSITIVE_INFINITY).build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(SliderInput.builder("n", LABEL, 0, 1).initial(1).build().initial()).isOne();
    }

    @Test
    void options_validateTimeoutAndCopyBuilderState() {
        ShowOptions defaults = ShowOptions.defaults();
        assertThat(defaults.timeout()).isEmpty();
        assertThat(defaults.noTimeout()).isFalse();
        sameValue(defaults, ShowOptions.builder().build());
        ShowOptions.Builder builder = ShowOptions.builder().timeout(Duration.ofSeconds(1));
        ShowOptions timed = builder.build();
        ShowOptions untimed = builder.noTimeout().build();
        assertThat(timed.timeout()).contains(Duration.ofSeconds(1));
        assertThat(timed.noTimeout()).isFalse();
        assertThat(untimed.timeout()).isEmpty();
        assertThat(untimed.noTimeout()).isTrue();
        assertThat(builder.timeout(Duration.ofHours(12)).build().noTimeout()).isFalse();
        assertThat(builder.build().timeout()).contains(Duration.ofHours(12));
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(12).plusNanos(1))) {
            assertThatThrownBy(() -> ShowOptions.builder().timeout(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatNullPointerException().isThrownBy(() -> ShowOptions.builder().timeout(null)).withMessage("timeout");
    }

    @Test
    void builders_rejectNullAttributesAndCollectionEntries() {
        assertThatNullPointerException().isThrownBy(() -> Notice.builder(TITLE).body(null)).withMessage("lines");
        assertThatNullPointerException().isThrownBy(() -> Notice.builder(TITLE).body(Arrays.asList(LABEL, null))).withMessage("line");
        assertThatNullPointerException().isThrownBy(() -> Notice.builder(TITLE).addBody(null));
        assertThatNullPointerException().isThrownBy(() -> Notice.builder(TITLE).button(null)).withMessage("label");
        assertThatNullPointerException().isThrownBy(() -> Confirm.builder(TITLE).body(null));
        assertThatNullPointerException().isThrownBy(() -> Confirm.builder(TITLE).addBody(null));
        assertThatNullPointerException().isThrownBy(() -> Confirm.builder(TITLE).yes(null));
        assertThatNullPointerException().isThrownBy(() -> Confirm.builder(TITLE).no(null));
        assertThatNullPointerException().isThrownBy(() -> Form.builder(TITLE).body(null));
        assertThatNullPointerException().isThrownBy(() -> Form.builder(TITLE).addBody(null));
        assertThatNullPointerException().isThrownBy(() -> Form.builder(TITLE).input(null)).withMessage("input");
        assertThatNullPointerException().isThrownBy(() -> Form.builder(TITLE).submit(null));
        assertThatNullPointerException().isThrownBy(() -> Choice.builder(TITLE).body(null));
        assertThatNullPointerException().isThrownBy(() -> Choice.builder(TITLE).addBody(null));
        assertThatNullPointerException().isThrownBy(() -> Choice.builder(TITLE).option(null)).withMessage("option");
        assertThatNullPointerException().isThrownBy(() -> ChoiceOption.of(null, LABEL)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> ChoiceOption.of(1, null)).withMessage("label");
        assertThatNullPointerException().isThrownBy(() -> ChoiceInput.of("c", LABEL, null)).withMessage("options");
        assertThatNullPointerException().isThrownBy(() -> ChoiceInput.of("c", LABEL, Arrays.asList((ChoiceOption<Object>) null)))
                .withMessage("option");
        assertThatNullPointerException().isThrownBy(() -> ChoiceInput.of("c", LABEL, List.of(ChoiceOption.of(1, LABEL)), null))
                .withMessage("initial");
    }

    @Test
    void valueEquality_comparesEveryAttribute() {
        assertThat(Notice.builder(TITLE).build().title()).isSameAs(TITLE);
        assertThat(Confirm.builder(TITLE).build().title()).isSameAs(TITLE);
        assertThat(Form.builder(TITLE).input(ToggleInput.of("a", LABEL, true)).build().title()).isSameAs(TITLE);
        different(Notice.builder(TITLE).build(), Notice.builder(LABEL).build(),
                Notice.builder(TITLE).addBody(LABEL).build(), Notice.builder(TITLE).button(LABEL).build());
        different(Confirm.builder(TITLE).build(), Confirm.builder(LABEL).build(),
                Confirm.builder(TITLE).addBody(LABEL).build(), Confirm.builder(TITLE).yes(LABEL).build(),
                Confirm.builder(TITLE).no(LABEL).build());
        different(Form.builder(TITLE).input(ToggleInput.of("a", LABEL, true)).build(),
                Form.builder(LABEL).input(ToggleInput.of("a", LABEL, true)).build(),
                Form.builder(TITLE).addBody(LABEL).input(ToggleInput.of("a", LABEL, true)).build(),
                Form.builder(TITLE).input(ToggleInput.of("b", LABEL, true)).build(),
                Form.builder(TITLE).input(ToggleInput.of("a", LABEL, true)).submit(LABEL).build());
        different(TextInput.builder("a", LABEL).build(), TextInput.builder("b", LABEL).build(),
                TextInput.builder("a", TITLE).build(), TextInput.builder("a", LABEL).initial("x").build(),
                TextInput.builder("a", LABEL).maxLength(1).build(), TextInput.builder("a", LABEL).maxLines(2).build());
        different(ToggleInput.of("a", LABEL, true), ToggleInput.of("b", LABEL, true),
                ToggleInput.of("a", TITLE, true), ToggleInput.of("a", LABEL, false));
        different(Choice.<Integer>builder(TITLE).option(1, LABEL).build(),
                Choice.<Integer>builder(LABEL).option(1, LABEL).build(),
                Choice.<Integer>builder(TITLE).addBody(LABEL).option(1, LABEL).build(),
                Choice.<Integer>builder(TITLE).option(2, LABEL).build());
        different(ChoiceInput.of("a", LABEL, List.of(ChoiceOption.of(1, LABEL))),
                ChoiceInput.of("b", LABEL, List.of(ChoiceOption.of(1, LABEL))),
                ChoiceInput.of("a", TITLE, List.of(ChoiceOption.of(1, LABEL))),
                ChoiceInput.of("a", LABEL, List.of(ChoiceOption.of(2, LABEL))));
        different(SliderInput.builder("a", LABEL, 0, 2).build(), SliderInput.builder("b", LABEL, 0, 2).build(),
                SliderInput.builder("a", TITLE, 0, 2).build(), SliderInput.builder("a", LABEL, 1, 2).build(),
                SliderInput.builder("a", LABEL, 0, 3).build(), SliderInput.builder("a", LABEL, 0, 2).step(0.5).build(),
                SliderInput.builder("a", LABEL, 0, 2).initial(1).build());
        different(ShowOptions.defaults(), ShowOptions.builder().noTimeout().build(),
                ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
    }

    private static void different(Object baseline, Object... alternatives) {
        for (Object alternative : alternatives) {
            assertThat(baseline).isNotEqualTo(alternative);
            assertThat(alternative).isNotEqualTo(baseline);
        }
    }

    private static void sameValue(Object first, Object second) {
        assertThat(first).isEqualTo(second).isEqualTo(first).isNotEqualTo(null).isNotEqualTo("foreign");
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.toString()).isEqualTo(second.toString()).isNotBlank();
    }
}
