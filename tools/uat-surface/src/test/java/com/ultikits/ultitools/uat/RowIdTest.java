package com.ultikits.ultitools.uat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link RowId#of} reproduces {@code ~/servers/uat/gen-registry.py}'s {@code uid()}
 * scheme byte for byte (Phase 10, D-10-08) against three real ids lifted from
 * {@code ~/servers/uat/registry.json}, and that changing either the format or the member of an
 * otherwise-identical call produces a different id.
 */
@DisplayName("RowId")
class RowIdTest {

    @Nested
    @DisplayName("reproduces gen-registry.py ids")
    class ReproducesRegistryIds {

        @Test
        @DisplayName("ChatAdminCommands.onReload / reload")
        void reload() {
            assertThat(RowId.of("command", "UltiChat", "ChatAdminCommands", "onReload", "reload"))
                    .isEqualTo("COM-fc907195");
        }

        @Test
        @DisplayName("ChatAdminCommands.onAutoReplyList / autoreply list")
        void autoReplyList() {
            assertThat(RowId.of("command", "UltiChat", "ChatAdminCommands", "onAutoReplyList", "autoreply list"))
                    .isEqualTo("COM-e61f9d20");
        }

        @Test
        @DisplayName("ChannelCommands.onList / list")
        void channelList() {
            assertThat(RowId.of("command", "UltiChat", "ChannelCommands", "onList", "list"))
                    .isEqualTo("COM-31f93dd5");
        }
    }

    @Nested
    @DisplayName("disambiguates on every id-forming part")
    class Disambiguation {

        @Test
        @DisplayName("differing only in format yields different ids")
        void differsByFormat() {
            String first = RowId.of("command", "X", "C", "m", "fmt1");
            String second = RowId.of("command", "X", "C", "m", "fmt2");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("differing only in member yields different ids")
        void differsByMember() {
            String first = RowId.of("command", "X", "C", "m1", "fmt");
            String second = RowId.of("command", "X", "C", "m2", "fmt");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("identical inputs are deterministic")
        void deterministic() {
            String first = RowId.of("command", "X", "C", "m", "fmt");
            String second = RowId.of("command", "X", "C", "m", "fmt");
            assertThat(first).isEqualTo(second);
        }
    }
}
