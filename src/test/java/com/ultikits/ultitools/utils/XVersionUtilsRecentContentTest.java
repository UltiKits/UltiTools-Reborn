package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.cryptomorin.xseries.XSound;

/** Guards the shipped XSeries lookup paths for Minecraft 1.21.5 content. */
class XVersionUtilsRecentContentTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void materialAddedInRecentMinecraftResolves() {
        ItemStackBuilder oldMaterial = ItemStackBuilder.of("STONE");
        assertThat(oldMaterial).as("long-standing material positive control").isNotNull();
        assertThat(oldMaterial.build().getType()).isEqualTo(Material.STONE);

        // Jar field listings prove this 1.21.5 block is absent in 13.0.0 and present in 13.7.1.
        ItemStackBuilder recentMaterial = ItemStackBuilder.of("CACTUS_FLOWER");
        assertThat(recentMaterial).as("framework lookup resolves CACTUS_FLOWER").isNotNull();
        ItemStack item = recentMaterial.build();
        assertThat(item.getType()).isEqualTo(Material.CACTUS_FLOWER);
    }

    /** Sounds resolve through the live Bukkit registry in both XSeries 13.0.0 and 13.7.1. */
    @Test
    void shippedXSeriesResolvesARecentSoundByName() {
        Optional<XSound> oldSound = XSound.matchXSound("BLOCK_CHEST_OPEN");
        assertThat(oldSound).as("long-standing sound positive control").isPresent();
        assertThat(oldSound.get().parseSound()).isEqualTo(Sound.BLOCK_CHEST_OPEN);

        // Use the same name-based path as modules, not a new constant unavailable to the old jar.
        Optional<XSound> recentSound = XSound.matchXSound("BLOCK_CACTUS_FLOWER_BREAK");
        assertThat(recentSound).as("shipped XSeries resolves the 1.21.5 sound").isPresent();
        assertThat(recentSound.get().parseSound()).isEqualTo(Sound.BLOCK_CACTUS_FLOWER_BREAK);
    }
}
