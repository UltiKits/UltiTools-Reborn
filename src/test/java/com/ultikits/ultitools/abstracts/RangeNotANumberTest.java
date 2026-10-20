package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * #625: {@code @Range} treats NaN as out of range for every bound, and an infinity as out of range unless the bound
 * itself is infinite, with exactly the outcome an ordinary out-of-range value has - measured on the base: the module
 * refuses to load (at load) and the reload is refused with the running values kept (at reload). Before the fix,
 * {@code num < min || num > max} is false for NaN, so {@code .nan} loaded.
 */
@DisplayName("@Range: NaN and unbounded infinities are out of range, by the existing outcome (#625)")
class RangeNotANumberTest {

    private static final String VALID = "tax: 0.5\nftax: 0.5\nboxed: 0.5\nunbounded: 1.0\n";

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    @ConfigEntity("trade.yml")
    static class Rates extends AbstractConfigEntity {
        @Range(min = 0.0, max = 1.0)
        @ConfigEntry(path = "tax")
        double tax = 0.5;

        @Range(min = 0.0, max = 1.0)
        @ConfigEntry(path = "ftax")
        float ftax = 0.5f;

        @Range(min = 0.0, max = 1.0)
        @ConfigEntry(path = "boxed")
        Double boxed = 0.5;

        @Range(min = 0.0, max = Double.POSITIVE_INFINITY)
        @ConfigEntry(path = "unbounded")
        double unbounded = 1.0;

        Rates(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TradeModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.ensureCleanState();
    }

    /** Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (17-74 gate-1 F1). */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private void write(String text) throws Exception {
        Files.write(directory.resolve("trade.yml"), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("control: an ordinary out-of-range value refuses the module at load, naming field, value and bounds")
    void ordinaryOutOfRangeRefusesAtLoad() throws Exception {
        write(VALID.replace("tax: 0.5\n", "tax: 2.0\n"));

        assertThatThrownBy(() -> new Rates("trade.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'tax' value 2.0 is out of range [0.0, 1.0]");
    }

    @ParameterizedTest(name = "tax: {0} refuses the module at load like 2.0")
    @ValueSource(strings = {".nan", ".NaN", ".inf", "-.inf"})
    void notANumberAndInfinitiesRefuseAtLoad(String written) throws Exception {
        write(VALID.replace("tax: 0.5\n", "tax: " + written + "\n"));

        assertThatThrownBy(() -> new Rates("trade.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'tax' value ")
                .hasMessageContaining("is out of range [0.0, 1.0]");
    }

    @ParameterizedTest(name = "ftax: {0} (float) refuses the module at load")
    @ValueSource(strings = {".nan", ".inf", "-.inf"})
    void floatFieldBehavesTheSame(String written) throws Exception {
        write(VALID.replace("ftax: 0.5\n", "ftax: " + written + "\n"));

        assertThatThrownBy(() -> new Rates("trade.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("field 'ftax' value ").hasMessageContaining("is out of range [0.0, 1.0]");
    }

    @Test
    @DisplayName("a boxed Double holding .nan is refused too")
    void boxedDoubleNotANumberRefuses() throws Exception {
        write(VALID.replace("boxed: 0.5\n", "boxed: .nan\n"));

        assertThatThrownBy(() -> new Rates("trade.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("field 'boxed' value NaN is out of range [0.0, 1.0]");
    }

    @Test
    @DisplayName("at reload .nan is refused like 2.0: the running value is kept")
    void notANumberRefusedAtReloadKeepsTheRunningValue() throws Exception {
        write(VALID);
        Rates rates = new Rates("trade.yml");
        rates.init(plugin);

        write(VALID.replace("tax: 0.5\n", "tax: .nan\n"));
        assertThatThrownBy(rates::reload).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("did not reload").hasMessageContaining("field 'tax' value NaN is out of range");
        assertThat(rates.tax).isEqualTo(0.5);

        write(VALID.replace("tax: 0.5\n", "tax: 2.0\n"));
        assertThatThrownBy(rates::reload).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("did not reload").hasMessageContaining("field 'tax' value 2.0 is out of range");
        assertThat(rates.tax).isEqualTo(0.5);
    }

    @Test
    @DisplayName("an infinite bound accepts that infinity; NaN is still refused there")
    void infiniteBoundAcceptsItsInfinity() throws Exception {
        write(VALID.replace("unbounded: 1.0\n", "unbounded: .inf\n"));
        Rates rates = new Rates("trade.yml");
        rates.init(plugin);
        assertThat(rates.unbounded).isEqualTo(Double.POSITIVE_INFINITY);

        write(VALID.replace("unbounded: 1.0\n", "unbounded: .nan\n"));
        assertThatThrownBy(() -> new Rates("trade.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("field 'unbounded' value NaN is out of range");
    }

    @Test
    @DisplayName("control: values inside the range load")
    void valuesInsideTheRangeLoad() throws Exception {
        write(VALID);
        Rates rates = new Rates("trade.yml");
        rates.init(plugin);
        assertThat(rates.tax).isEqualTo(0.5);
        assertThat(rates.ftax).isEqualTo(0.5f);
    }
}
