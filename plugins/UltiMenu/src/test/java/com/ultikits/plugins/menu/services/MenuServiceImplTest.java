package com.ultikits.plugins.menu.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import com.ultikits.plugins.menu.model.ButtonDefinition;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("MenuServiceImpl Tests")
class MenuServiceImplTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin mockPlugin;
    private PluginLogger mockLogger;
    private File menusFolder;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(UltiToolsPlugin.class);
        mockLogger = mock(PluginLogger.class);
        when(mockPlugin.getLogger()).thenReturn(mockLogger);
        when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.toFile().getAbsolutePath());
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        menusFolder = new File(tempDir.toFile(), "menus");
        menusFolder.mkdirs();
    }

    private MenuServiceImpl createService() {
        return new MenuServiceImpl(mockPlugin);
    }

    private void writeMenuYaml(String fileName, String content) throws IOException {
        File file = new File(menusFolder, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    // ==================== Loading Tests ====================

    @Nested
    @DisplayName("Menu Loading Tests")
    class LoadingTests {

        @Test
        @DisplayName("Should load valid menu from YAML file")
        void shouldLoadValidMenu() throws IOException {
            writeMenuYaml("test.yml",
                "title: '&6Test Menu'\n" +
                "size: 27\n" +
                "buttons:\n" +
                "  btn1:\n" +
                "    item: DIAMOND\n" +
                "    position: 13\n" +
                "    name: '&bDiamond'\n"
            );

            MenuServiceImpl service = createService();

            assertThat(service.getAllMenus()).hasSize(1);
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getTitle()).isEqualTo("&6Test Menu");
            assertThat(menu.getSize()).isEqualTo(27);
            assertThat(menu.getButtons()).containsKey("btn1");
        }

        @Test
        @DisplayName("Should load multiple menus from folder")
        void shouldLoadMultipleMenus() throws IOException {
            writeMenuYaml("menu1.yml", "title: Menu 1\nsize: 9\nbuttons: {}");
            writeMenuYaml("menu2.yml", "title: Menu 2\nsize: 18\nbuttons: {}");
            writeMenuYaml("menu3.yml", "title: Menu 3\nsize: 27\nbuttons: {}");

            MenuServiceImpl service = createService();

            assertThat(service.getAllMenus()).hasSize(3);
            assertThat(service.getMenuNames()).containsExactlyInAnyOrder("menu1", "menu2", "menu3");
        }

        @Test
        @DisplayName("Should create menus folder if it does not exist")
        void shouldCreateMenusFolderIfMissing() {
            // Delete the menus folder so service creates it
            menusFolder.delete();

            createService();

            assertThat(menusFolder).exists();
            assertThat(menusFolder).isDirectory();
        }

        @Test
        @DisplayName("Should handle empty menus folder")
        void shouldHandleEmptyFolder() {
            MenuServiceImpl service = createService();
            assertThat(service.getAllMenus()).isEmpty();
        }

        @Test
        @DisplayName("Should ignore non-yml files in menus folder")
        void shouldIgnoreNonYmlFiles() throws IOException {
            writeMenuYaml("valid.yml", "title: Valid\nsize: 9\nbuttons: {}");
            new File(menusFolder, "readme.txt").createNewFile();
            new File(menusFolder, "backup.bak").createNewFile();

            MenuServiceImpl service = createService();

            assertThat(service.getAllMenus()).hasSize(1);
        }

        @Test
        @DisplayName("Should handle corrupted YAML gracefully")
        void shouldHandleCorruptedYaml() throws IOException {
            writeMenuYaml("corrupt.yml", "{{{{invalid yaml: [[[");
            writeMenuYaml("valid.yml", "title: Valid\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();

            // Should still load the valid menu
            assertThat(service.getAllMenus()).hasSize(1);
            assertThat(service.getMenu("valid")).isNotNull();
        }

        @Test
        @DisplayName("Should store menu names in lowercase")
        void shouldStoreLowercase() throws IOException {
            writeMenuYaml("MyMenu.yml", "title: Test\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();

            assertThat(service.getMenu("mymenu")).isNotNull();
            assertThat(service.getMenuNames()).contains("mymenu");
        }
    }

    // ==================== Size Validation Tests ====================

    @Nested
    @DisplayName("Size Validation Tests")
    class SizeValidationTests {

        @ParameterizedTest(name = "Should accept valid size {0}")
        @ValueSource(ints = {9, 18, 27, 36, 45, 54})
        void shouldAcceptValidSizes(int size) throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: " + size + "\nbuttons: {}"
            );

            MenuServiceImpl service = createService();

            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getSize()).isEqualTo(size);
        }

        @ParameterizedTest(name = "Should reject invalid size {0}")
        @ValueSource(ints = {0, 5, 10, 15, 55, 63, -9, 100})
        void shouldRejectInvalidSizes(int size) throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: " + size + "\nbuttons: {}"
            );

            MenuServiceImpl service = createService();

            assertThat(service.getMenu("test")).isNull();
        }

        @Test
        @DisplayName("Should default to 27 when size not specified")
        void shouldDefaultSizeTo27() throws IOException {
            writeMenuYaml("test.yml", "title: Test\nbuttons: {}");

            MenuServiceImpl service = createService();

            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getSize()).isEqualTo(27);
        }
    }

    // ==================== Button Parsing Tests ====================

    @Nested
    @DisplayName("Button Parsing Tests")
    class ButtonParsingTests {

        @Test
        @DisplayName("Should parse button with all fields")
        void shouldParseFullButton() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 27\n" +
                "buttons:\n" +
                "  mybutton:\n" +
                "    item: DIAMOND_SWORD\n" +
                "    position: 13\n" +
                "    name: '&cAwesome Sword'\n" +
                "    lore:\n" +
                "      - '&7Line 1'\n" +
                "      - '&7Line 2'\n" +
                "    player-commands:\n" +
                "      - 'spawn'\n" +
                "    console-commands:\n" +
                "      - 'give {player} diamond 1'\n" +
                "    price: 100.5\n" +
                "    close-on-click: false\n" +
                "    permission: 'vip.use'\n" +
                "    custom-model-data: 42\n"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();

            ButtonDefinition btn = menu.getButtons().get("mybutton");
            assertThat(btn).isNotNull();
            assertThat(btn.getItem().name()).isEqualTo("DIAMOND_SWORD");
            assertThat(btn.getPosition()).isEqualTo(13);
            assertThat(btn.getName()).isEqualTo("&cAwesome Sword");
            assertThat(btn.getLore()).containsExactly("&7Line 1", "&7Line 2");
            assertThat(btn.getPlayerCommands()).containsExactly("spawn");
            assertThat(btn.getConsoleCommands()).containsExactly("give {player} diamond 1");
            assertThat(btn.getPrice()).isEqualTo(100.5);
            assertThat(btn.isCloseOnClick()).isFalse();
            assertThat(btn.getPermission()).isEqualTo("vip.use");
            assertThat(btn.getCustomModelData()).isEqualTo(42);
        }

        @Test
        @DisplayName("Should skip button with no item material")
        void shouldSkipButtonWithNoItem() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "buttons:\n" +
                "  noitem:\n" +
                "    position: 0\n" +
                "    name: 'Missing'\n"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getButtons()).isEmpty();
        }

        @Test
        @DisplayName("Should skip button with invalid material")
        void shouldSkipButtonWithInvalidMaterial() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "buttons:\n" +
                "  bad:\n" +
                "    item: NOT_A_REAL_MATERIAL\n" +
                "    position: 0\n"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getButtons()).isEmpty();
        }

        @Test
        @DisplayName("Should parse material name case-insensitively")
        void shouldParseMaterialCaseInsensitive() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "buttons:\n" +
                "  btn:\n" +
                "    item: diamond_sword\n" +
                "    position: 0\n"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();

            ButtonDefinition btn = menu.getButtons().get("btn");
            assertThat(btn).isNotNull();
            assertThat(btn.getItem().name()).isEqualTo("DIAMOND_SWORD");
        }

        @Test
        @DisplayName("Should default close-on-click to true")
        void shouldDefaultCloseOnClickTrue() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "buttons:\n" +
                "  btn:\n" +
                "    item: STONE\n" +
                "    position: 0\n"
            );

            MenuServiceImpl service = createService();
            ButtonDefinition btn = service.getMenu("test").getButtons().get("btn");
            assertThat(btn.isCloseOnClick()).isTrue();
        }

        @Test
        @DisplayName("Should handle menu with no buttons section")
        void shouldHandleNoButtonsSection() throws IOException {
            writeMenuYaml("test.yml", "title: Test\nsize: 9");

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getButtons()).isEmpty();
        }
    }

    // ==================== Bind Item Parsing Tests ====================

    @Nested
    @DisplayName("Bind Item Parsing Tests")
    class BindItemTests {

        @Test
        @DisplayName("Should parse bind-item material")
        void shouldParseBindItem() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "bind-item: COMPASS\n" +
                "bind-name: '&6My Compass'\n" +
                "bind-lore: 'Right click'\n" +
                "buttons: {}"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getBindItem()).isNotNull();
            assertThat(menu.getBindItem().name()).isEqualTo("COMPASS");
            assertThat(menu.getBindName()).isEqualTo("&6My Compass");
            assertThat(menu.getBindLore()).isEqualTo("Right click");
        }

        @Test
        @DisplayName("Should ignore invalid bind-item material")
        void shouldIgnoreInvalidBindItem() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "bind-item: FAKE_MATERIAL\n" +
                "buttons: {}"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getBindItem()).isNull();
        }

        @Test
        @DisplayName("Should handle missing bind fields")
        void shouldHandleMissingBindFields() throws IOException {
            writeMenuYaml("test.yml", "title: Test\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu).isNotNull();
            assertThat(menu.getBindItem()).isNull();
            assertThat(menu.getBindName()).isNull();
            assertThat(menu.getBindLore()).isNull();
        }
    }

    // ==================== Lookup Tests ====================

    @Nested
    @DisplayName("Menu Lookup Tests")
    class LookupTests {

        @Test
        @DisplayName("Should look up menu case-insensitively")
        void shouldLookupCaseInsensitive() throws IOException {
            writeMenuYaml("TestMenu.yml", "title: Test\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();

            assertThat(service.getMenu("testmenu")).isNotNull();
            assertThat(service.getMenu("TESTMENU")).isNotNull();
            assertThat(service.getMenu("TestMenu")).isNotNull();
        }

        @Test
        @DisplayName("Should return null for nonexistent menu")
        void shouldReturnNullForMissing() throws IOException {
            writeMenuYaml("existing.yml", "title: Test\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();

            assertThat(service.getMenu("nonexistent")).isNull();
        }

        @Test
        @DisplayName("Should return unmodifiable collection from getAllMenus")
        void shouldReturnUnmodifiableCollection() throws IOException {
            writeMenuYaml("test.yml", "title: Test\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();
            Collection<MenuDefinition> menus = service.getAllMenus();

            org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> menus.clear()
            );
        }
    }

    // ==================== Reload Tests ====================

    @Nested
    @DisplayName("Reload Tests")
    class ReloadTests {

        @Test
        @DisplayName("Should reload menus from disk")
        void shouldReloadFromDisk() throws IOException {
            writeMenuYaml("test.yml", "title: Original\nsize: 9\nbuttons: {}");
            MenuServiceImpl service = createService();
            assertThat(service.getMenu("test").getTitle()).isEqualTo("Original");

            // Overwrite with new content
            writeMenuYaml("test.yml", "title: Updated\nsize: 18\nbuttons: {}");
            service.reload();

            assertThat(service.getMenu("test").getTitle()).isEqualTo("Updated");
            assertThat(service.getMenu("test").getSize()).isEqualTo(18);
        }

        @Test
        @DisplayName("Should detect new menus on reload")
        void shouldDetectNewMenus() throws IOException {
            writeMenuYaml("menu1.yml", "title: Menu1\nsize: 9\nbuttons: {}");
            MenuServiceImpl service = createService();
            assertThat(service.getAllMenus()).hasSize(1);

            writeMenuYaml("menu2.yml", "title: Menu2\nsize: 9\nbuttons: {}");
            service.reload();

            assertThat(service.getAllMenus()).hasSize(2);
        }

        @Test
        @DisplayName("Should detect deleted menus on reload")
        void shouldDetectDeletedMenus() throws IOException {
            writeMenuYaml("menu1.yml", "title: Menu1\nsize: 9\nbuttons: {}");
            writeMenuYaml("menu2.yml", "title: Menu2\nsize: 9\nbuttons: {}");
            MenuServiceImpl service = createService();
            assertThat(service.getAllMenus()).hasSize(2);

            new File(menusFolder, "menu2.yml").delete();
            service.reload();

            assertThat(service.getAllMenus()).hasSize(1);
            assertThat(service.getMenu("menu1")).isNotNull();
            assertThat(service.getMenu("menu2")).isNull();
        }
    }

    // ==================== BaseService Tests ====================

    @Nested
    @DisplayName("BaseService Implementation Tests")
    class BaseServiceTests {

        @Test
        @DisplayName("Should return correct service metadata")
        void shouldReturnServiceMetadata() throws IOException {
            writeMenuYaml("test.yml", "title: Test\nsize: 9\nbuttons: {}");
            MenuServiceImpl service = createService();

            assertThat(service.getName()).isNotNull().isNotEmpty();
            assertThat(service.getResourceFolderName()).isEqualTo("menu");
            assertThat(service.getAuthor()).isEqualTo("wisdomme");
            assertThat(service.getVersion()).isEqualTo(1);
        }
    }

    // ==================== Permission and Command Parsing Tests ====================

    @Nested
    @DisplayName("Optional Field Parsing Tests")
    class OptionalFieldTests {

        @Test
        @DisplayName("Should parse permission and command fields")
        void shouldParsePermissionAndCommand() throws IOException {
            writeMenuYaml("test.yml",
                "title: Test\nsize: 9\n" +
                "permission: 'vip.menu'\n" +
                "command: 'mymenu'\n" +
                "buttons: {}"
            );

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu.getPermission()).isEqualTo("vip.menu");
            assertThat(menu.getCommand()).isEqualTo("mymenu");
        }

        @Test
        @DisplayName("Should handle missing optional fields as null")
        void shouldHandleMissingOptionalFields() throws IOException {
            writeMenuYaml("test.yml", "title: Test\nsize: 9\nbuttons: {}");

            MenuServiceImpl service = createService();
            MenuDefinition menu = service.getMenu("test");
            assertThat(menu.getPermission()).isNull();
            assertThat(menu.getCommand()).isNull();
        }
    }
}
