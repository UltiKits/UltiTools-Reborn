package com.ultikits.ultitools.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class ArgTest {
    @Test
    void factories_preserveNamesAndValues() {
        assertThat(Arg.string("p", "<red>x")).isEqualTo(new Arg.Unparsed("p", "<red>x"));
        assertThat(Arg.number("n", 1234.5)).isEqualTo(new Arg.Numeric("n", 1234.5));
        LocalDate date = LocalDate.of(2026, 11, 23);
        assertThat(Arg.time("date", date)).isEqualTo(new Arg.Moment("date", date));
        Text text = Text.plain("literal");
        assertThat(Arg.text("t", text)).isEqualTo(new Arg.Localized("t", text));
        assertThat(Arg.component("t", Component.text("literal"))).isEqualTo(Arg.text("t", text));
        assertThat(Arg.string("a_0-b", "value").name()).isEqualTo("a_0-b");
    }

    @Test
    void invalidNames_areRejectedForEveryKindAndConstructor() {
        assertThat(Arg.string("-", "x").name()).isEqualTo("-");
        for (String name : new String[] {"", "Upper", "a.b", "a b", "+a", "a:b"}) {
            assertThatThrownBy(() -> Arg.string(name, "x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Arg.number(name, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Arg.time(name, LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Arg.text(name, Text.plain("x"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Arg.Unparsed(name, "x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Arg.Numeric(name, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Arg.Moment(name, LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Arg.Localized(name, Text.plain("x"))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nullNamesAndValues_areRejected() {
        assertThatNullPointerException().isThrownBy(() -> Arg.string(null, "x")).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> Arg.number(null, 1)).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> Arg.time(null, LocalDate.now())).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> Arg.text(null, Text.plain("x"))).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> Arg.string("a", null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> Arg.number("a", null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> Arg.time("a", null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> Arg.text("a", null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> Arg.component("a", null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> Arg.component(null, Component.empty())).withMessage("name");
    }
}
