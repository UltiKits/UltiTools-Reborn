package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.ChoiceInput;
import com.ultikits.ultitools.ui.ChoiceOption;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.FormInput;
import com.ultikits.ultitools.ui.FormResponse;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.ToggleInput;
import com.ultikits.ultitools.ui.UiResult;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DialogMapperTest {
    private final Player viewer = mock(Player.class);
    private final LocaleResolver locales = mock(LocaleResolver.class);
    private final InMemoryMessages messages = new InMemoryMessages("ultitools", locales);
    private final DialogMapper mapper = new DialogMapper(messages);

    @BeforeEach
    void setUp() {
        when(locales.resolve(viewer)).thenReturn(Locale.US);
        when(locales.fallback()).thenReturn(Locale.US);
    }

    @Test
    void notice_mapsTitleBodyOrderAndDefaultButtonKey() {
        when(locales.resolve(viewer)).thenReturn(Locale.US);
        DialogModel model = mapper.map(Notice.builder(Text.plain("Title"))
                .addBody(Text.plain("one")).addBody(Text.plain("two")).build(), viewer, true);
        assertThat(model.title()).isEqualTo(Component.text("Title"));
        assertThat(model.body()).containsExactly(Component.text("one"), Component.text("two"));
        assertThat(model.inputs()).isEmpty();
        assertThat(model.kind()).isEqualTo(DialogModel.Kind.NOTICE);
        assertThat(model.buttons()).containsExactly(new DialogModel.Button(Component.text("ultitools:ui.ok"), null, new ButtonRole.Ack()));
        assertThat(model.exit()).isNull();
        assertThat(model.escapeCloses()).isTrue();
    }

    @Test
    void confirm_mapsDefaultKeysAndCustomLabels() {
        when(locales.resolve(viewer)).thenReturn(Locale.US);
        DialogModel defaults = mapper.map(Confirm.builder(Text.plain("Confirm"))
                .addBody(Text.plain("question")).build(), viewer, true);
        assertThat(defaults.title()).isEqualTo(Component.text("Confirm"));
        assertThat(defaults.body()).containsExactly(Component.text("question"));
        assertThat(defaults.kind()).isEqualTo(DialogModel.Kind.CONFIRMATION);
        assertThat(defaults.buttons()).containsExactly(
                new DialogModel.Button(Component.text("ultitools:ui.yes"), null, new ButtonRole.Yes()),
                new DialogModel.Button(Component.text("ultitools:ui.no"), null, new ButtonRole.No()));
        DialogModel custom = mapper.map(Confirm.builder(Text.plain("t"))
                .yes(Text.plain("accept")).no(Text.plain("decline")).build(), viewer, true);
        assertThat(custom.buttons()).extracting(DialogModel.Button::label)
                .containsExactly(Component.text("accept"), Component.text("decline"));
    }

    @Test
    void form_mapsEveryControlAndPositionalIds() {
        when(locales.resolve(viewer)).thenReturn(Locale.US);
        Value first = new Value(1);
        Value second = new Value(2);
        Form form = Form.builder(Text.plain("Form"))
                .addBody(Text.plain("details"))
                .input(TextInput.builder("name", Text.plain("Name")).initial("hi").maxLength(16).maxLines(3).build())
                .input(ToggleInput.of("flag", Text.plain("Flag"), true))
                .input(ChoiceInput.of("mode", Text.plain("Mode"), List.of(
                        ChoiceOption.of(first, Text.plain("a")), ChoiceOption.of(second, Text.plain("b"))), second))
                .input(SliderInput.builder("level", Text.plain("Level"), -1, 10).step(0.5).initial(2.5).build())
                .build();
        DialogModel model = mapper.map(form, viewer, true);
        assertThat(model.title()).isEqualTo(Component.text("Form"));
        assertThat(model.body()).containsExactly(Component.text("details"));
        assertThat(model.kind()).isEqualTo(DialogModel.Kind.NOTICE);
        assertThat(model.buttons()).containsExactly(new DialogModel.Button(Component.text("ultitools:ui.submit"), null, new ButtonRole.Submit()));
        assertThat(model.inputs()).containsExactly(
                new DialogModel.Text("i0", Component.text("Name"), "hi", 16, 3),
                new DialogModel.Toggle("i1", Component.text("Flag"), true),
                new DialogModel.Single("i2", Component.text("Mode"), List.of(
                        new DialogModel.Option("0", Component.text("a"), false), new DialogModel.Option("1", Component.text("b"), true))),
                new DialogModel.Range("i3", Component.text("Level"), -1f, 10f, 0.5f, 2.5f));
    }

    @Test
    void choice_mapsValuesByIndexAndPreservesTooltips() {
        Choice<Value> choice = Choice.<Value>builder(Text.plain("Pick"))
                .addBody(Text.plain("choose one"))
                .option(new ChoiceOption<>(new Value(1), Text.plain("a"), Text.plain("tip")))
                .option(new Value(2), Text.plain("b")).build();
        DialogModel model = mapper.map(choice, viewer, true);
        assertThat(model.title()).isEqualTo(Component.text("Pick"));
        assertThat(model.body()).containsExactly(Component.text("choose one"));
        assertThat(model.kind()).isEqualTo(DialogModel.Kind.MULTI_ACTION);
        assertThat(model.buttons()).containsExactly(
                new DialogModel.Button(Component.text("a"), Component.text("tip"), new ButtonRole.Pick(0)),
                new DialogModel.Button(Component.text("b"), null, new ButtonRole.Pick(1)));
        assertThat(model.exit()).isNull();
    }

    @Test
    void allTextPositions_renderInEachViewersLocale() {
        MessageKey key = MessageKey.of("probe", "text");
        messages.put(key, Locale.US, "English");
        messages.put(key, Locale.SIMPLIFIED_CHINESE, "Chinese");
        Text translated = Text.of(key);
        for (Locale locale : List.of(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            when(locales.resolve(viewer)).thenReturn(locale);
            Component expected = Component.text(locale.equals(Locale.US) ? "English" : "Chinese");
            DialogModel notice = mapper.map(Notice.builder(translated).addBody(translated).button(translated).build(), viewer, true);
            assertThat(notice.title()).isEqualTo(expected);
            assertThat(notice.body()).containsExactly(expected);
            assertThat(notice.buttons().getFirst().label()).isEqualTo(expected);
            DialogModel form = mapper.map(Form.builder(translated)
                    .input(ChoiceInput.of("pick", translated, List.of(ChoiceOption.of(1, translated))))
                    .submit(translated).build(), viewer, true);
            assertThat(form.inputs().getFirst().label()).isEqualTo(expected);
            assertThat(((DialogModel.Single) form.inputs().getFirst()).options().getFirst().display()).isEqualTo(expected);
            DialogModel choice = mapper.map(Choice.<Integer>builder(translated)
                    .option(new ChoiceOption<>(1, translated, translated)).build(), viewer, true);
            assertThat(choice.buttons().getFirst().tooltip()).isEqualTo(expected);
        }
    }

    @Test
    void grid_isRejectedAndModelCollectionsAreDefensive() {
        assertThatThrownBy(() -> mapper.map(Grid.builder(Text.plain("grid"), 1).build(), viewer, true))
                .isInstanceOf(IllegalArgumentException.class);
        List<Component> lines = new ArrayList<>(List.of(Component.text("line")));
        List<DialogModel.Option> options = new ArrayList<>(List.of(new DialogModel.Option("0", Component.text("one"), true)));
        DialogModel.Single single = new DialogModel.Single("pick", Component.text("Pick"), options);
        DialogModel model = new DialogModel(Component.text("t"), lines, List.of(single), List.of(), null, DialogModel.Kind.NOTICE, true);
        lines.clear();
        options.clear();
        assertThat(model.body()).hasSize(1);
        assertThat(single.options()).hasSize(1);
        assertThatThrownBy(() -> model.body().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> single.options().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void form_reservedCallbackKeyIsIsolatedForEveryControl(int reservedPosition) {
        List<String> keys = new ArrayList<>(List.of("name", "flag", "mode", "level"));
        keys.set(reservedPosition, "id");
        TextInput text = TextInput.builder(keys.get(0), Text.plain("Name")).build();
        ToggleInput toggle = ToggleInput.of(keys.get(1), Text.plain("Flag"), false);
        ChoiceInput<String> single = ChoiceInput.of(keys.get(2), Text.plain("Mode"), List.of(
                ChoiceOption.of("first", Text.plain("same")), ChoiceOption.of("second", Text.plain("same"))));
        SliderInput slider = SliderInput.builder(keys.get(3), Text.plain("Level"), 0, 10).step(0.5).build();
        Form form = Form.builder(Text.plain("Form")).input(text).input(toggle).input(single).input(slider).build();
        DialogModel model = mapper.map(form, viewer, false);
        assertThat(model.inputs()).extracting(DialogModel.Input::key)
                .containsExactly("i0", "i1", "i2", "i3")
                .doesNotContain("id").doesNotContainAnyElementsOf(form.inputs().stream().map(FormInput::key).toList());
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getText("i0")).thenReturn("answer");
        when(response.getBoolean("i1")).thenReturn(true);
        when(response.getText("i2")).thenReturn("1");
        when(response.getFloat("i3")).thenReturn(7.5f);
        DialogAnswers answers = new DialogAnswers(mock(Logger.class));
        UiResult<?> result = answers.read(form, new ButtonRole.Submit(), response, "Probe");
        assertThat(result).isInstanceOf(UiResult.Completed.class);
        FormResponse values = (FormResponse) result.value();
        assertThat(values.get(text)).isEqualTo("answer");
        assertThat(values.get(toggle)).isTrue();
        assertThat(values.get(single)).isEqualTo("second");
        assertThat(values.get(slider)).isEqualTo(7.5);
    }

    @Test
    void form_keysDifferingOnlyByCaseCannotEnterTheBackend() {
        assertThat(TextInput.builder("id", Text.plain("ID")).build().key()).isEqualTo("id");
        assertThatThrownBy(() -> TextInput.builder("ID", Text.plain("ID")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[a-z0-9_]+");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void choice_identicalRenderedLabelsKeepDistinctPositionalAnswers(int index) {
        Value first = new Value(1);
        Value second = new Value(2);
        MessageKey key = MessageKey.of("probe", "same");
        messages.put(key, Locale.US, "same");
        Choice<Value> choice = Choice.<Value>builder(Text.plain("Pick"))
                .option(first, Text.plain("same")).option(second, Text.of(key)).build();
        DialogModel model = mapper.map(choice, viewer, false);
        assertThat(model.buttons()).extracting(DialogModel.Button::label)
                .containsExactly(Component.text("same"), Component.text("same"));
        DialogAnswers answers = new DialogAnswers(mock(Logger.class));
        assertThat(answers.read(choice, model.buttons().get(index).role(), null, "Probe").value())
                .isSameAs(index == 0 ? first : second);
    }

    private record Value(int value) {
        @Override public String toString() { return "same"; }
    }
}
