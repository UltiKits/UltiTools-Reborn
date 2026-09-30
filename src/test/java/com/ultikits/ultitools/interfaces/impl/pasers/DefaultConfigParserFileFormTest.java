package com.ultikits.ultitools.interfaces.impl.pasers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 17-41 (#553 warn-only, write path as in 6.2): a configuration section inside a list is left in the
 * list as it is, exactly as {@code origin/alpha} {@code dfe71e01} leaves it (measured), never walked by
 * reflection - an earlier revision of this branch recursed through the section's root and parent
 * references and overflowed the stack.
 */
@DisplayName("DefaultConfigParser#fileForm - a section inside a list")
class DefaultConfigParserFileFormTest {

    @Test
    @DisplayName("a list holding a section keeps the section itself, as alpha does")
    void sectionInsideAListIsKept() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("a", 1);
        List<Object> list = new ArrayList<>();
        list.add(section);

        Object[] form = new Object[1];
        assertThatCode(() -> form[0] = new DefaultConfigParser().fileForm(list)).doesNotThrowAnyException();
        assertThat(form[0]).isInstanceOf(List.class);
        assertThat(((List<?>) form[0]).get(0)).isSameAs(section);
    }
}
