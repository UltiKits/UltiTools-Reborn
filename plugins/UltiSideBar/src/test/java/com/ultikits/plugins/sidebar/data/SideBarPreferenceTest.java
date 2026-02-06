package com.ultikits.plugins.sidebar.data;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SideBarPreference Tests")
class SideBarPreferenceTest {

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should create preference with UUID and enabled state")
        void createWithParameters() {
            String uuid = UUID.randomUUID().toString();
            SideBarPreference pref = new SideBarPreference(uuid, true);

            assertThat(pref.getPlayerUuid()).isEqualTo(uuid);
            assertThat(pref.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should create preference with disabled state")
        void createDisabled() {
            String uuid = UUID.randomUUID().toString();
            SideBarPreference pref = new SideBarPreference(uuid, false);

            assertThat(pref.getPlayerUuid()).isEqualTo(uuid);
            assertThat(pref.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should create with no-args constructor")
        void noArgsConstructor() {
            SideBarPreference pref = new SideBarPreference();

            assertThat(pref).isNotNull();
            assertThat(pref.getPlayerUuid()).isNull();
            assertThat(pref.getEnabled()).isNull();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersAndSetters {

        @Test
        @DisplayName("Should set and get player UUID")
        void playerUuid() {
            SideBarPreference pref = new SideBarPreference();
            String uuid = UUID.randomUUID().toString();

            pref.setPlayerUuid(uuid);

            assertThat(pref.getPlayerUuid()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("Should set and get enabled state")
        void enabled() {
            SideBarPreference pref = new SideBarPreference();

            pref.setEnabled(true);
            assertThat(pref.getEnabled()).isTrue();

            pref.setEnabled(false);
            assertThat(pref.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should handle null enabled state")
        void nullEnabled() {
            SideBarPreference pref = new SideBarPreference();

            pref.setEnabled(null);

            assertThat(pref.getEnabled()).isNull();
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Should be equal when same UUID and enabled state")
        void equalPreferences() {
            String uuid = UUID.randomUUID().toString();
            SideBarPreference pref1 = new SideBarPreference(uuid, true);
            SideBarPreference pref2 = new SideBarPreference(uuid, true);

            assertThat(pref1).isEqualTo(pref2);
            assertThat(pref1.hashCode()).isEqualTo(pref2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when different UUID")
        void differentUuid() {
            SideBarPreference pref1 = new SideBarPreference(UUID.randomUUID().toString(), true);
            SideBarPreference pref2 = new SideBarPreference(UUID.randomUUID().toString(), true);

            assertThat(pref1).isNotEqualTo(pref2);
        }

        @Test
        @DisplayName("Should not be equal when different enabled state")
        void differentEnabled() {
            String uuid = UUID.randomUUID().toString();
            SideBarPreference pref1 = new SideBarPreference(uuid, true);
            SideBarPreference pref2 = new SideBarPreference(uuid, false);

            assertThat(pref1).isNotEqualTo(pref2);
        }

        @Test
        @DisplayName("Should handle null in equals")
        void equalsNull() {
            SideBarPreference pref = new SideBarPreference(UUID.randomUUID().toString(), true);

            assertThat(pref).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should be equal to itself")
        void equalsItself() {
            SideBarPreference pref = new SideBarPreference(UUID.randomUUID().toString(), true);

            assertThat(pref).isEqualTo(pref);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("Should contain UUID and enabled state in toString")
        void toStringContent() {
            String uuid = UUID.randomUUID().toString();
            SideBarPreference pref = new SideBarPreference(uuid, true);

            String str = pref.toString();

            assertThat(str).contains(uuid);
            assertThat(str).contains("true");
        }
    }
}
