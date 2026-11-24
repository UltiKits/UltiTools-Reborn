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
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.ToggleInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                new DialogModel.Text("name", Component.text("Name"), "hi", 16, 3),
                new DialogModel.Toggle("flag", Component.text("Flag"), true),
                new DialogModel.Single("mode", Component.text("Mode"), List.of(
                        new DialogModel.Option("0", Component.text("a"), false), new DialogModel.Option("1", Component.text("b"), true))),
                new DialogModel.Range("level", Component.text("Level"), -1f, 10f, 0.5f, 2.5f));
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

    private record Value(int value) {
        @Override public String toString() { return "same"; }
    }
}
