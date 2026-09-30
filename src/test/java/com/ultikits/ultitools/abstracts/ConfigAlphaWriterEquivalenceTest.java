package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * UltiKits/UltiTools-Reborn#553, maintainer answers of 2026-09-30 (the write path stays as in 6.2, "只警告，
 * 写入保持 6.2"; then no check at all, documentation only, "不做检查，只写文档"): saving a configuration writes exactly the text {@code origin/alpha}
 * ({@code dfe71e01}) writes. The expected texts below were produced by running this same fixture
 * ({@link AlphaWriterFixture}) against {@code dfe71e01} in a throwaway worktree on 2026-09-30: map fields
 * and nested maps with dotted keys (split on save, as 6.2 does), a map that is a list element (kept
 * whole), and a subclass of a registered {@code ConfigurationSerializable} whose registered alias is its
 * parent - the shape of Paper's {@code CraftItemStack} - in a list and in a map; and, per the
 * orchestrator's third ruling, a list-element map with integer keys (written {@code 1: a}, not {@code
 * '1': a}) and a {@code Set} field with a module's own parser (its first-boot default written raw, as
 * alpha writes it; its save through the parser).
 */
@DisplayName("AbstractConfigEntity - the write path writes what alpha writes (#553 documentation only)")
class ConfigAlphaWriterEquivalenceTest {

    private static final String PATH = "config/alpha-writer.yml";

    /** {@code dfe71e01}'s first-boot write of the fixture's declared defaults. */
    private static final String ALPHA_FIRST_BOOT = "flat:\n  plain: '1'\n  my.rule: x\nnested:\n  r:\n    x.y: 1\n    k: v\n"
            + "rewards:\n- minecraft.diamond: '5'\n  stick: '1'\n"
            + "items:\n- ==: com.ultikits.ultitools.abstracts.AlphaWriterFixture$ParentCs\n  x: 1\n"
            + "kits:\n  starter:\n    ==: com.ultikits.ultitools.abstracts.AlphaWriterFixture$ParentCs\n    x: 1\n"
            + "tiers:\n- 1: a\n"
            + "tags: !!set\n  red: null\n";

    /** {@code dfe71e01}'s {@code save()} of the same fixture right after that first boot. */
    private static final String ALPHA_SAVE = "flat:\n  plain: '1'\n  my:\n    rule: x\nnested:\n  r:\n    x:\n      y: 1\n    k: v\n"
            + "rewards:\n- minecraft.diamond: '5'\n  stick: '1'\n"
            + "items:\n- ==: com.ultikits.ultitools.abstracts.AlphaWriterFixture$ParentCs\n  x: 1\n"
            + "kits:\n  starter:\n    extra: 2\n    x: 1\n"
            + "tiers:\n- 1: a\n"
            + "tags:\n  joined: red\n";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void registerParent() {
        ConfigurationSerialization.registerClass(AlphaWriterFixture.ParentCs.class);
    }

    @Test
    @DisplayName("first boot and save() write byte for byte what alpha writes")
    void writesWhatAlphaWrites() throws IOException {
        UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("AlphaWriterModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        Path file = tempDir.resolve(PATH);

        AlphaWriterFixture.Fixture fixture = new AlphaWriterFixture.Fixture();
        fixture.init(plugin);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo(ALPHA_FIRST_BOOT);

        fixture.save();
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo(ALPHA_SAVE);
    }
}
