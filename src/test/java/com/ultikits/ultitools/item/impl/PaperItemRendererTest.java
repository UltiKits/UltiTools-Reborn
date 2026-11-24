package com.ultikits.ultitools.item.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(30)
class PaperItemRendererTest {
    private LocaleResolver resolver;
    private InMemoryMessages messages;
    private PaperItemRenderer renderer;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        resolver = new FixedLocaleResolver(Locale.US, Optional.empty());
        messages = new InMemoryMessages("sample", resolver);
        renderer = new PaperItemRenderer(messages, resolver);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void render_blockOnlyKey_isRejected() {
        ItemSpec water = ItemSpec.builder(Key.key("minecraft:water")).build();
        assertThatThrownBy(() -> renderer.render(water, Locale.US))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:water");
    }

    @Test
    void render_flagAndPrototypeRemoval_areApplied() {
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND_SWORD)
                .data(DataComponentTypes.GLIDER).remove(DataComponentTypes.MAX_DAMAGE).build();
        ItemStack stack = renderer.render(spec, Locale.US);
        assertThat(stack.hasData(DataComponentTypes.GLIDER)).isTrue();
        assertThat(stack.hasData(DataComponentTypes.MAX_DAMAGE)).isFalse();
    }

    @Test
    void render_markersOfSeveralTypes_roundTrip() {
        NamespacedKey number = new NamespacedKey("sample", "number");
        NamespacedKey bytes = new NamespacedKey("sample", "bytes");
        NamespacedKey text = new NamespacedKey("sample", "text");
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND)
                .marker(number, PersistentDataType.INTEGER, 7)
                .marker(bytes, PersistentDataType.BYTE_ARRAY, new byte[] {1, 2, 3})
                .marker(text, PersistentDataType.STRING, "first")
                .marker(text, PersistentDataType.STRING, "last").build();
        ItemStack stack = renderer.render(spec, Locale.US);
        assertThat(stack.getPersistentDataContainer().get(number, PersistentDataType.INTEGER)).isEqualTo(7);
        assertThat(stack.getPersistentDataContainer().get(bytes, PersistentDataType.BYTE_ARRAY))
                .containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(stack.getPersistentDataContainer().get(text, PersistentDataType.STRING)).isEqualTo("last");
    }

    @Test
    void render_patchesThenMarkers_keepInsertionOrder() {
        NamespacedKey marker = new NamespacedKey("sample", "order");
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND)
                .data(DataComponentTypes.MAX_STACK_SIZE, 16).data(DataComponentTypes.MAX_STACK_SIZE, 32)
                .marker(marker, PersistentDataType.STRING, "first")
                .marker(marker, PersistentDataType.STRING, "last").build();
        ItemStack stack = renderer.render(spec, Locale.US);
        assertThat(stack.getData(DataComponentTypes.MAX_STACK_SIZE)).isEqualTo(32);
        assertThat(stack.getPersistentDataContainer().get(marker, PersistentDataType.STRING)).isEqualTo("last");
    }

    @Test
    void render_loreBeyondPaperLimit_throwsIllegalArgument() {
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND)
                .lore(Collections.nCopies(300, Text.plain("line"))).build();
        // MockBukkit 4.123.0 reports Paper's measured limit in its exception message.
        assertThatThrownBy(() -> renderer.render(spec, Locale.US))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("256");
    }

    @Test
    void render_concurrentCalls_produceConsistentStacks() throws Exception {
        MessageKey name = MessageKey.of("sample", "name");
        messages.put(name, Locale.US, "Diamond");
        messages.put(name, Locale.SIMPLIFIED_CHINESE, "钻石");
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND).name(name).build();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 8; thread++) {
                Locale locale = thread % 2 == 0 ? Locale.US : Locale.SIMPLIFIED_CHINESE;
                Component expected = Component.text(locale.equals(Locale.US) ? "Diamond" : "钻石");
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    for (int iteration = 0; iteration < 200; iteration++) {
                        ItemStack stack = renderer.render(spec, locale);
                        assertThat(stack.getData(DataComponentTypes.ITEM_NAME)).isEqualTo(expected);
                        assertThat(stack.getAmount()).isEqualTo(1);
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            assertThat(futures).hasSize(8);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void constructor_nullArguments_areRejectedByName() {
        assertThatNullPointerException().isThrownBy(() -> new PaperItemRenderer(null, resolver))
                .withMessage("messages");
        assertThatNullPointerException().isThrownBy(() -> new PaperItemRenderer(messages, null))
                .withMessage("localeResolver");
    }
}
