package com.ultikits.ultitools.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class TextTest {
    private static final MessageKey KEY = MessageKey.of("test", "key");

    @Test
    void literals_preserveComponentsWithoutParsing() {
        Component component = Component.text("<red>literal");
        assertThat(Text.literal(component)).isEqualTo(new Text.Literal(component));
        assertThat(Text.literal(Component.text().content("builder"))).isEqualTo(Text.plain("builder"));
        assertThat(((Text.Literal) Text.plain("<red>literal")).component()).isEqualTo(component);
    }

    @Test
    void translatedArguments_areDefensivelyCopiedFromListsAndArrays() {
        List<Arg> args = new ArrayList<>(List.of(Arg.string("a", "first")));
        Text.Translated fromList = (Text.Translated) Text.of(KEY, args);
        Text.Translated direct = new Text.Translated(KEY, args);
        args.clear();
        assertThat(fromList.args()).containsExactly(Arg.string("a", "first"));
        assertThat(direct).isEqualTo(fromList);
        assertThatThrownBy(() -> fromList.args().clear()).isInstanceOf(UnsupportedOperationException.class);
        Arg[] array = {Arg.string("a", "first")};
        Text.Translated fromArray = (Text.Translated) Text.of(KEY, array);
        array[0] = Arg.string("b", "second");
        assertThat(fromArray).isEqualTo(fromList);
        assertThat(fromArray.key()).isEqualTo(KEY);
    }

    @Test
    void duplicateArgumentNames_areRejectedByFactoriesAndConstructor() {
        Arg first = Arg.string("same", "x");
        Arg second = Arg.number("same", 1);
        assertThatThrownBy(() -> Text.of(KEY, first, second)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Text.of(KEY, List.of(first, second))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Text.Translated(KEY, List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullArguments_areRejected() {
        assertThatNullPointerException().isThrownBy(() -> Text.literal(null)).withMessage("component");
        assertThatNullPointerException().isThrownBy(() -> new Text.Literal(null)).withMessage("component");
        assertThatNullPointerException().isThrownBy(() -> Text.plain(null)).withMessage("plain");
        assertThatNullPointerException().isThrownBy(() -> Text.of(null)).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> Text.of(KEY, (Arg[]) null)).withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> Text.of(KEY, (List<Arg>) null)).withMessage("args");
        assertThatNullPointerException().isThrownBy(() -> Text.of(KEY, new Arg[] {null}));
        assertThatNullPointerException().isThrownBy(() -> Text.of(KEY, Arrays.asList((Arg) null)));
        assertThatNullPointerException().isThrownBy(() -> new Text.Translated(null, List.of())).withMessage("key");
        assertThatNullPointerException().isThrownBy(() -> new Text.Translated(KEY, null)).withMessage("args");
    }
}
