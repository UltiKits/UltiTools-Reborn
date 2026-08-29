package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * Tests for config validation annotations (@Range, @NotEmpty, @Size, @Pattern).
 */
@DisplayName("Config Validation Annotations")
class ConfigValidationTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws IOException {
        mockPlugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.getPluginName()).thenReturn("TestModule");
        lenient().when(mockPlugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(mockPlugin.getConfigFile(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return new File(tempDir.toFile(), path);
        });
    }

    /**
     * SHA-256 of a file's bytes, used to prove a refused load never rewrites the operator's file
     * (D-01, D-04).
     */
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Nested
    @DisplayName("@Range validation")
    class RangeValidation {

        @Test
        @DisplayName("Should accept value within range")
        void shouldAcceptValueWithinRange() throws IOException {
            // Create config file with valid value
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 10".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(10);
        }

        @Test
        @DisplayName("Should refuse a value below minimum instead of resetting it")
        void shouldRefuseValueBelowMin() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 0\nrate: 0.01".getBytes());
            byte[] before = Files.readAllBytes(configFile.toPath());

            RangeConfig config = new RangeConfig("range.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("interval")
                    .hasMessageContaining("0");
            assertThat(Files.readAllBytes(configFile.toPath())).isEqualTo(before);
        }

        @Test
        @DisplayName("Should refuse a value above maximum instead of resetting it")
        void shouldRefuseValueAboveMax() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 9999\nrate: 0.01".getBytes());
            byte[] before = Files.readAllBytes(configFile.toPath());

            RangeConfig config = new RangeConfig("range.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("interval")
                    .hasMessageContaining("9999");
            assertThat(Files.readAllBytes(configFile.toPath())).isEqualTo(before);
        }

        @Test
        @DisplayName("Should accept value at exact minimum boundary")
        void shouldAcceptAtMinBoundary() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 1".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(1);
        }

        @Test
        @DisplayName("Should accept value at exact maximum boundary")
        void shouldAcceptAtMaxBoundary() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 1200".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(1200);
        }

        @Test
        @DisplayName("Should refuse an out-of-range double field")
        void shouldRefuseOutOfRangeDoubleField() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 20\nrate: -0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("rate")
                    .hasMessageContaining("-0.5");
        }
    }

    @Nested
    @DisplayName("@NotEmpty validation")
    class NotEmptyValidation {

        @Test
        @DisplayName("Should accept non-empty string")
        void shouldAcceptNonEmptyString() throws IOException {
            File configFile = new File(tempDir.toFile(), "notempty.yml");
            Files.write(configFile.toPath(), "title: MyServer".getBytes());

            NotEmptyConfig config = new NotEmptyConfig("notempty.yml");
            config.init(mockPlugin);

            assertThat(config.title).isEqualTo("MyServer");
        }

        @Test
        @DisplayName("Should refuse an empty string instead of resetting it")
        void shouldRefuseEmptyString() throws IOException {
            File configFile = new File(tempDir.toFile(), "notempty.yml");
            Files.write(configFile.toPath(), "title: \"\"".getBytes());
            byte[] before = Files.readAllBytes(configFile.toPath());

            NotEmptyConfig config = new NotEmptyConfig("notempty.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("title");
            assertThat(Files.readAllBytes(configFile.toPath())).isEqualTo(before);
        }

        @Test
        @DisplayName("Should refuse a whitespace-only string instead of resetting it")
        void shouldRefuseWhitespaceOnlyString() throws IOException {
            File configFile = new File(tempDir.toFile(), "notempty.yml");
            Files.write(configFile.toPath(), "title: \"   \"".getBytes());
            byte[] before = Files.readAllBytes(configFile.toPath());

            NotEmptyConfig config = new NotEmptyConfig("notempty.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("title");
            assertThat(Files.readAllBytes(configFile.toPath())).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("@Size validation")
    class SizeValidation {

        @Test
        @DisplayName("Should accept list within size bounds")
        void shouldAcceptListWithinBounds() throws IOException {
            File configFile = new File(tempDir.toFile(), "size.yml");
            Files.write(configFile.toPath(), "lines:\n- line1\n- line2".getBytes());

            SizeConfig config = new SizeConfig("size.yml");
            config.init(mockPlugin);

            assertThat(config.lines).hasSize(2);
        }

        @Test
        @DisplayName("Should refuse an oversized list instead of resetting it")
        void shouldRefuseOversizedList() throws IOException {
            File configFile = new File(tempDir.toFile(), "size.yml");
            StringBuilder sb = new StringBuilder("lines:\n");
            for (int i = 0; i < 20; i++) {
                sb.append("- line").append(i).append("\n");
            }
            Files.write(configFile.toPath(), sb.toString().getBytes());

            SizeConfig config = new SizeConfig("size.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("lines")
                    .hasMessageContaining("20");
        }
    }

    @Nested
    @DisplayName("@Pattern validation")
    class PatternValidation {

        @Test
        @DisplayName("Should accept string matching pattern")
        void shouldAcceptMatchingPattern() throws IOException {
            File configFile = new File(tempDir.toFile(), "pattern.yml");
            Files.write(configFile.toPath(), "currency: Gold".getBytes());

            PatternConfig config = new PatternConfig("pattern.yml");
            config.init(mockPlugin);

            assertThat(config.currency).isEqualTo("Gold");
        }

        @Test
        @DisplayName("Should refuse a non-matching string instead of resetting it")
        void shouldRefuseNonMatchingPattern() throws IOException {
            File configFile = new File(tempDir.toFile(), "pattern.yml");
            Files.write(configFile.toPath(), "currency: \"$$$invalid!!!\"".getBytes());
            byte[] before = Files.readAllBytes(configFile.toPath());

            PatternConfig config = new PatternConfig("pattern.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("currency")
                    .hasMessageContaining("$$$invalid!!!");
            assertThat(Files.readAllBytes(configFile.toPath())).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Validation on reload")
    class ReloadValidation {

        @Test
        @DisplayName("Should refuse an invalid value on reload instead of resetting it")
        void shouldRefuseOnReload() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 20\nrate: 0.01".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);
            assertThat(config.interval).isEqualTo(20);

            // Modify file to invalid value and reload
            Files.write(configFile.toPath(), "interval: -5\nrate: 0.01".getBytes());
            byte[] before = Files.readAllBytes(configFile.toPath());

            assertThatThrownBy(config::reload)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("interval")
                    .hasMessageContaining("-5");
            // The file itself is what D-01 protects - never rewritten by a refused reload.
            assertThat(Files.readAllBytes(configFile.toPath())).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Refusal wiring (D-01/D-02/D-03/D-04/D-05 tracer)")
    class RefusalWiring {

        @Test
        @DisplayName("Should throw ConfigurationException naming module, file, field, value and constraint")
        void shouldThrowNamingModuleFileFieldValueAndConstraint() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 5000".getBytes());

            RangeConfig config = new RangeConfig("range.yml");

            ConfigurationException thrown =
                    catchThrowableOfType(() -> config.init(mockPlugin), ConfigurationException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED);
            assertThat(thrown.getMessage())
                    .contains("TestModule")
                    .contains("range.yml")
                    .contains("interval")
                    .contains("5000")
                    .contains("1")
                    .contains("1200");
        }

        @Test
        @DisplayName("Should leave the yml file byte-identical across a throwing init()")
        void shouldLeaveFileByteIdenticalAcrossThrowingInit() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            // Both fields present and rate valid, so init()'s missing-key auto-add (D-01's one
            // sanctioned exception) never fires - any diff here can only come from the reset path.
            Files.write(configFile.toPath(), "interval: 5000\nrate: 0.01".getBytes());
            String shaBefore = sha256(configFile.toPath());

            RangeConfig config = new RangeConfig("range.yml");

            assertThatThrownBy(() -> config.init(mockPlugin)).isInstanceOf(ConfigurationException.class);

            assertThat(sha256(configFile.toPath())).isEqualTo(shaBefore);
        }

        @Test
        @DisplayName("Should name both fields when two violate at once")
        void shouldNameBothFieldsWhenTwoViolate() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 5000\nrate: 2.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("interval")
                    .hasMessageContaining("rate");
        }

        @Test
        @DisplayName("Should activate validation for the no-arg super(path) idiom (D-02)")
        void shouldActivateValidationForNoArgConstructorIdiom() throws IOException {
            File configFile = new File(tempDir.toFile(), "noargrange.yml");
            Files.write(configFile.toPath(), "interval: 9999".getBytes());

            NoArgRangeConfig config = new NoArgRangeConfig();

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("interval");
        }

        @Test
        @DisplayName("Should return normally for a config class with no @ConfigEntry fields")
        void shouldReturnNormallyWithNoConfigEntryFields() {
            EmptyConfig config = new EmptyConfig("empty.yml");

            assertThatCode(() -> config.init(mockPlugin)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should refuse by name a config class with neither a (String) nor a no-arg constructor (D-03)")
        void shouldRefuseUnconstructableClassByName() {
            UnconstructableConfig config = new UnconstructableConfig(1);

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(UnconstructableConfig.class.getName());
        }

        @Test
        @DisplayName("Should count String.length() UTF-16 code units for @Size on a String field")
        void shouldCountStringLengthForSizeOnString() throws IOException {
            File configFile = new File(tempDir.toFile(), "sizestring.yml");
            Files.write(configFile.toPath(), "code: \"toolong\"".getBytes());

            SizeStringConfig config = new SizeStringConfig("sizestring.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("code")
                    .hasMessageContaining("7"); // "toolong".length() == 7
        }

        @Test
        @DisplayName("Should not throw and should still write the missing key when a field is absent from the yml")
        void shouldNotThrowAndShouldWriteMissingKey() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "rate: 0.01".getBytes()); // interval absent

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(20); // kept its initializer, no throw
            String written = new String(Files.readAllBytes(configFile.toPath()));
            assertThat(written).contains("interval");
        }

        @Test
        @DisplayName("Should throw the same message and leave the file unchanged across two calls")
        void shouldThrowConsistentlyAcrossRepeatedInit() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 5000\nrate: 0.01".getBytes());
            String shaBefore = sha256(configFile.toPath());

            RangeConfig config1 = new RangeConfig("range.yml");
            ConfigurationException first =
                    catchThrowableOfType(() -> config1.init(mockPlugin), ConfigurationException.class);

            RangeConfig config2 = new RangeConfig("range.yml");
            ConfigurationException second =
                    catchThrowableOfType(() -> config2.init(mockPlugin), ConfigurationException.class);

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(second.getMessage()).isEqualTo(first.getMessage());
            assertThat(sha256(configFile.toPath())).isEqualTo(shaBefore);
        }
    }

    @Nested
    @DisplayName("Write-path refusal (updateProperties, closing SILENT-14's write half - CR-01)")
    class WritePathRefusal {

        @Test
        @DisplayName("Should throw ConfigurationException naming module, file, field, value and both bounds")
        void shouldThrowNamingModuleFileFieldValueAndBothBounds() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            JsonObject json = new JsonObject();
            json.addProperty("interval", 9999);

            ConfigurationException thrown =
                    catchThrowableOfType(() -> config.updateProperties(json), ConfigurationException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED);
            assertThat(thrown.getMessage())
                    .contains("TestModule")
                    .contains("range.yml")
                    .contains("interval")
                    .contains("9999")
                    .contains("1")
                    .contains("1200");
        }

        @Test
        @DisplayName("Should leave the yml file byte-identical across a throwing updateProperties")
        void shouldLeaveFileByteIdenticalAcrossThrowingUpdateProperties() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);
            String shaBefore = sha256(configFile.toPath());

            JsonObject json = new JsonObject();
            json.addProperty("interval", 9999);

            assertThatThrownBy(() -> config.updateProperties(json)).isInstanceOf(ConfigurationException.class);

            assertThat(sha256(configFile.toPath())).isEqualTo(shaBefore);
        }

        @Test
        @DisplayName("Should restore the in-memory field to its pre-call value after a refused updateProperties")
        void shouldRestoreFieldToPreCallValueAfterRefusal() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);
            assertThat(config.interval).isEqualTo(100);

            JsonObject json = new JsonObject();
            json.addProperty("interval", 9999);

            assertThatThrownBy(() -> config.updateProperties(json)).isInstanceOf(ConfigurationException.class);

            assertThat(config.interval).isEqualTo(100);
        }

        @Test
        @DisplayName("Should name both fields when two violate at once, leaving neither mutated")
        void shouldNameBothFieldsAndLeaveNeitherMutated() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            JsonObject json = new JsonObject();
            json.addProperty("interval", 9999);
            json.addProperty("rate", 2.5);

            assertThatThrownBy(() -> config.updateProperties(json))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("interval")
                    .hasMessageContaining("rate");

            assertThat(config.interval).isEqualTo(100);
            assertThat(config.rate).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should throw the same message and leave the file unchanged across two identical refused writes")
        void shouldRefuseIdempotently() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);
            String shaBefore = sha256(configFile.toPath());

            JsonObject json = new JsonObject();
            json.addProperty("interval", 9999);

            ConfigurationException first =
                    catchThrowableOfType(() -> config.updateProperties(json), ConfigurationException.class);
            assertThat(sha256(configFile.toPath())).isEqualTo(shaBefore);

            ConfigurationException second =
                    catchThrowableOfType(() -> config.updateProperties(json), ConfigurationException.class);
            assertThat(sha256(configFile.toPath())).isEqualTo(shaBefore);

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(second.getMessage()).isEqualTo(first.getMessage());
        }

        @Test
        @DisplayName("Should accept values at the exact range boundaries")
        void shouldAcceptExactBoundaryValues() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            JsonObject lowJson = new JsonObject();
            lowJson.addProperty("interval", 1);
            assertThatCode(() -> config.updateProperties(lowJson)).doesNotThrowAnyException();
            assertThat(config.interval).isEqualTo(1);
            assertThat(YamlConfiguration.loadConfiguration(configFile).getInt("interval")).isEqualTo(1);

            JsonObject highJson = new JsonObject();
            highJson.addProperty("interval", 1200);
            assertThatCode(() -> config.updateProperties(highJson)).doesNotThrowAnyException();
            assertThat(config.interval).isEqualTo(1200);
            assertThat(YamlConfiguration.loadConfiguration(configFile).getInt("interval")).isEqualTo(1200);
        }

        @Test
        @DisplayName("Should apply a valid write to both the field and the file")
        void shouldApplyValidWriteToFieldAndFile() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            JsonObject json = new JsonObject();
            json.addProperty("interval", 600);

            config.updateProperties(json);

            assertThat(config.interval).isEqualTo(600);
            assertThat(YamlConfiguration.loadConfiguration(configFile).getInt("interval")).isEqualTo(600);
        }

        @Test
        @DisplayName("Should apply a valid write for a @Pattern-constrained field")
        void shouldApplyValidWriteForPatternConstraint() throws IOException {
            File configFile = new File(tempDir.toFile(), "pattern.yml");
            Files.write(configFile.toPath(), "currency: Gold".getBytes());

            PatternConfig config = new PatternConfig("pattern.yml");
            config.init(mockPlugin);

            JsonObject json = new JsonObject();
            json.addProperty("currency", "Silver");

            config.updateProperties(json);

            assertThat(config.currency).isEqualTo("Silver");
            assertThat(YamlConfiguration.loadConfiguration(configFile).getString("currency")).isEqualTo("Silver");
        }

        @Test
        @DisplayName("Should not throw and not change the field when the JSON names no known @ConfigEntry path")
        void shouldNoOpForUnknownPath() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 100\nrate: 0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            JsonObject json = new JsonObject();
            json.addProperty("notARealField", "whatever");

            assertThatCode(() -> config.updateProperties(json)).doesNotThrowAnyException();

            assertThat(config.interval).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Secret-shaped field-name redaction (WR-03, widened for the new panel write egress)")
    class SecretShapedFieldRedaction {

        @Test
        @DisplayName("Should redact a @Pattern violation on a 'privateKey'-named field")
        void shouldRedactPrivateKeyNamedField() throws IOException {
            File configFile = new File(tempDir.toFile(), "privatekey.yml");
            Files.write(configFile.toPath(), "privateKey: \"sekrit!!!\"".getBytes());

            PrivateKeyPatternConfig config = new PrivateKeyPatternConfig("privatekey.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("privateKey")
                    .hasMessageContaining("<redacted>")
                    .hasMessageNotContaining("sekrit!!!");
        }

        @Test
        @DisplayName("Should redact a @Pattern violation on an 'authHeader'-named field")
        void shouldRedactAuthHeaderNamedField() throws IOException {
            File configFile = new File(tempDir.toFile(), "authheader.yml");
            Files.write(configFile.toPath(), "authHeader: \"sekrit!!!\"".getBytes());

            AuthHeaderPatternConfig config = new AuthHeaderPatternConfig("authheader.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("authHeader")
                    .hasMessageContaining("<redacted>")
                    .hasMessageNotContaining("sekrit!!!");
        }

        @Test
        @DisplayName("Should redact a @Pattern violation on a 'webhookKey'-named field")
        void shouldRedactWebhookKeyNamedField() throws IOException {
            File configFile = new File(tempDir.toFile(), "webhookkey.yml");
            Files.write(configFile.toPath(), "webhookKey: \"sekrit!!!\"".getBytes());

            WebhookKeyPatternConfig config = new WebhookKeyPatternConfig("webhookkey.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("webhookKey")
                    .hasMessageContaining("<redacted>")
                    .hasMessageNotContaining("sekrit!!!");
        }

        @Test
        @DisplayName("Should redact a @Pattern violation on a 'clientCert'-named field")
        void shouldRedactClientCertNamedField() throws IOException {
            File configFile = new File(tempDir.toFile(), "clientcert.yml");
            Files.write(configFile.toPath(), "clientCert: \"sekrit!!!\"".getBytes());

            ClientCertPatternConfig config = new ClientCertPatternConfig("clientcert.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("clientCert")
                    .hasMessageContaining("<redacted>")
                    .hasMessageNotContaining("sekrit!!!");
        }

        @Test
        @DisplayName("Should still redact the six pre-existing substrings (apiKey, dbPassword) - no regression")
        void shouldStillRedactPreExistingSubstrings() throws IOException {
            File configFile = new File(tempDir.toFile(), "apikey.yml");
            Files.write(configFile.toPath(), "apiKey: \"sekrit!!!\"".getBytes());

            ApiKeyPatternConfig apiKeyConfig = new ApiKeyPatternConfig("apikey.yml");
            assertThatThrownBy(() -> apiKeyConfig.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("apiKey")
                    .hasMessageContaining("<redacted>")
                    .hasMessageNotContaining("sekrit!!!");

            File configFile2 = new File(tempDir.toFile(), "dbpassword.yml");
            Files.write(configFile2.toPath(), "dbPassword: \"sekrit!!!\"".getBytes());

            DbPasswordPatternConfig dbPasswordConfig = new DbPasswordPatternConfig("dbpassword.yml");
            assertThatThrownBy(() -> dbPasswordConfig.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("dbPassword")
                    .hasMessageContaining("<redacted>")
                    .hasMessageNotContaining("sekrit!!!");
        }

        @Test
        @DisplayName("Should still echo the value for a non-secret-shaped field name - the widening is not blanket redaction")
        void shouldStillEchoNonSecretShapedFieldName() throws IOException {
            // Companion to the redaction tests above (mirrors PatternValidation's existing
            // shouldRefuseNonMatchingPattern on the same 'currency' fixture): one without the
            // other proves nothing about whether the widening became blanket redaction.
            File configFile = new File(tempDir.toFile(), "pattern.yml");
            Files.write(configFile.toPath(), "currency: \"$$$invalid!!!\"".getBytes());

            PatternConfig config = new PatternConfig("pattern.yml");

            assertThatThrownBy(() -> config.init(mockPlugin))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("currency")
                    .hasMessageContaining("$$$invalid!!!");
        }
    }

    // ==================== Test Config Classes ====================

    private static class RangeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "Update interval in ticks")
        @Range(min = 1, max = 1200)
        private int interval = 20;

        @ConfigEntry(path = "rate", comment = "Interest rate")
        @Range(min = 0.0, max = 1.0)
        private double rate = 0.01;

        public RangeConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class NotEmptyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "title", comment = "Sidebar title")
        @NotEmpty
        private String title = "Welcome";

        public NotEmptyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class SizeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "lines", comment = "Content lines (max 15)")
        @Size(max = 15)
        private List<String> lines = new ArrayList<>(Arrays.asList("Line 1", "Line 2", "Line 3"));

        public SizeConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class PatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "currency", comment = "Currency name")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String currency = "Coin";

        public PatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Field name shaped like WR-03's widened substring "private" - should redact. */
    private static class PrivateKeyPatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "privateKey", comment = "Should be redacted (WR-03)")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String privateKey = "valid";

        public PrivateKeyPatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Field name shaped like WR-03's widened substring "auth" - should redact. */
    private static class AuthHeaderPatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "authHeader", comment = "Should be redacted (WR-03)")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String authHeader = "valid";

        public AuthHeaderPatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Field name shaped like WR-03's widened substring "key" - should redact. */
    private static class WebhookKeyPatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "webhookKey", comment = "Should be redacted (WR-03)")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String webhookKey = "valid";

        public WebhookKeyPatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Field name shaped like WR-03's widened substring "cert" - should redact. */
    private static class ClientCertPatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "clientCert", comment = "Should be redacted (WR-03)")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String clientCert = "valid";

        public ClientCertPatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Field name shaped like one of the six substrings already redacted before this plan. */
    private static class ApiKeyPatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "apiKey", comment = "Already redacted before this plan")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String apiKey = "valid";

        public ApiKeyPatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Field name shaped like one of the six substrings already redacted before this plan. */
    private static class DbPasswordPatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "dbPassword", comment = "Already redacted before this plan")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String dbPassword = "valid";

        public DbPasswordPatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /**
     * Only a no-arg constructor, hardcoding its path via {@code super(...)} - the shape of the
     * 11 production config classes holding 146 constraints that were silently inert before this
     * plan (D-02). Validation must activate for this idiom, not just the {@code (String)} one.
     */
    private static class NoArgRangeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "Update interval in ticks")
        @Range(min = 1, max = 1200)
        private int interval = 20;

        public NoArgRangeConfig() {
            super("noargrange.yml");
        }
    }

    /**
     * No {@code @ConfigEntry} field at all - {@code validateFields()} must return normally
     * rather than requiring constructability for a class with nothing to validate.
     */
    private static class EmptyConfig extends AbstractConfigEntity {
        public EmptyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /**
     * Neither a {@code (String)} constructor nor an accessible no-arg constructor - the genuine
     * refusal D-03 keeps: this idiom is not the framework-documented one and must be refused by
     * name rather than silently skipped.
     */
    private static class UnconstructableConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "value", comment = "A value")
        private String value = "x";

        UnconstructableConfig(int notAStringOrNoArgConstructor) {
            super("unconstructable.yml");
        }
    }

    private static class SizeStringConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "code", comment = "Short code (max 5 chars)")
        @Size(max = 5)
        private String code = "abc";

        public SizeStringConfig(String configFilePath) {
            super(configFilePath);
        }
    }
}
