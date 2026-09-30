package com.ultikits.ultitools.interfaces.impl.pasers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate-1 confirmation review of plan 17-41, item 4: a configuration section inside a list is written
 * as plain data (a map of its values), never by reflecting on the section object - which recursed
 * through its root and parent references without end.
 */
@DisplayName("DefaultConfigParser#fileForm - a section inside a list")
class DefaultConfigParserFileFormTest {

    @Test
    @DisplayName("a list holding a section becomes a list holding a map of the section's values")
    void sectionInsideAListBecomesAMap() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("a", 1);
        section.set("b.c", "x");
        List<Object> list = new ArrayList<>();
        list.add(section);

        Object[] form = new Object[1];
        assertThatCode(() -> form[0] = new DefaultConfigParser().fileForm(list)).doesNotThrowAnyException();
        assertThat(form[0]).isInstanceOf(List.class);
        Object element = ((List<?>) form[0]).get(0);
        assertThat(element).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) element;
        assertThat(map).containsEntry("a", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) map.get("b");
        assertThat(nested).containsEntry("c", "x");
    }
}
