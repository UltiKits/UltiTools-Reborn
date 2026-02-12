package com.ultikits.plugins.menu.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MenuDefinition Tests")
class MenuDefinitionTest {

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaults() {
            MenuDefinition menu = new MenuDefinition();

            assertThat(menu.getSize()).isEqualTo(27);
            assertThat(menu.getTitle()).isEqualTo("&7Menu");
            assertThat(menu.getCommand()).isNull();
            assertThat(menu.getPermission()).isNull();
            assertThat(menu.getBindItem()).isNull();
            assertThat(menu.getBindName()).isNull();
            assertThat(menu.getBindLore()).isNull();
            assertThat(menu.getButtons()).isNotNull().isEmpty();
            assertThat(menu.getFileName()).isNull();
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should set and get all fields")
        void shouldSetAndGetAllFields() {
            MenuDefinition menu = new MenuDefinition();
            menu.setFileName("test");
            menu.setSize(54);
            menu.setTitle("&6Custom Title");
            menu.setCommand("testcmd");
            menu.setPermission("test.perm");
            menu.setBindItem(Material.COMPASS);
            menu.setBindName("&6Compass");
            menu.setBindLore("Right click");

            LinkedHashMap<String, ButtonDefinition> buttons = new LinkedHashMap<>();
            buttons.put("btn1", new ButtonDefinition());
            menu.setButtons(buttons);

            assertThat(menu.getFileName()).isEqualTo("test");
            assertThat(menu.getSize()).isEqualTo(54);
            assertThat(menu.getTitle()).isEqualTo("&6Custom Title");
            assertThat(menu.getCommand()).isEqualTo("testcmd");
            assertThat(menu.getPermission()).isEqualTo("test.perm");
            assertThat(menu.getBindItem()).isEqualTo(Material.COMPASS);
            assertThat(menu.getBindName()).isEqualTo("&6Compass");
            assertThat(menu.getBindLore()).isEqualTo("Right click");
            assertThat(menu.getButtons()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsTests {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenFieldsMatch() {
            MenuDefinition menu1 = new MenuDefinition();
            menu1.setFileName("test");
            menu1.setSize(27);

            MenuDefinition menu2 = new MenuDefinition();
            menu2.setFileName("test");
            menu2.setSize(27);

            assertThat(menu1).isEqualTo(menu2);
            assertThat(menu1.hashCode()).isEqualTo(menu2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when fields differ")
        void shouldNotBeEqualWhenFieldsDiffer() {
            MenuDefinition menu1 = new MenuDefinition();
            menu1.setFileName("test1");

            MenuDefinition menu2 = new MenuDefinition();
            menu2.setFileName("test2");

            assertThat(menu1).isNotEqualTo(menu2);
        }
    }
}
