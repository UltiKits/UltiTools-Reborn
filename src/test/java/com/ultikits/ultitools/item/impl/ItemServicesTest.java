package com.ultikits.ultitools.item.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ItemServicesTest {
    private final SimpleContainer root = new SimpleContainer();

    @AfterEach
    void tearDown() {
        root.close();
    }

    @Test
    void register_resolvesLazilyAndReturnsOneInstance() {
        assertThatCode(() -> ItemServices.register(root)).doesNotThrowAnyException();
        registerTextServices();
        ItemRenderer first = root.getBean(ItemRenderer.class);
        assertThat(first).isInstanceOf(PaperItemRenderer.class).isSameAs(root.getBean(ItemRenderer.class));
    }

    @Test
    void register_withoutTextServices_failsAndRecoversAfterRegistration() {
        ItemServices.register(root);
        // A missing by-type binding returns null; the renderer names the missing constructor argument.
        assertThatNullPointerException().isThrownBy(() -> root.getBean(ItemRenderer.class)).withMessage("messages");
        registerTextServices();
        assertThat(root.getBean(ItemRenderer.class)).isInstanceOf(PaperItemRenderer.class);
    }

    @Test
    void register_childContainerAndAutowiredField_resolveRootInstance() {
        ItemServices.register(root);
        registerTextServices();
        ItemRenderer renderer = root.getBean(ItemRenderer.class);
        SimpleContainer child = new SimpleContainer(root);
        try {
            assertThat(child.getBean(ItemRenderer.class)).isSameAs(renderer);
            child.registerBean(Holder.class);
            assertThat(child.getBean(Holder.class).renderer).isSameAs(renderer);
        } finally {
            child.close();
        }
    }

    @Test
    void register_nullRoot_isRejectedByName() {
        assertThatNullPointerException().isThrownBy(() -> ItemServices.register(null)).withMessage("root");
    }

    private void registerTextServices() {
        LocaleResolver resolver = new FixedLocaleResolver(Locale.US, Optional.empty());
        root.registerType(Messages.class, new InMemoryMessages("sample", resolver));
        root.registerType(LocaleResolver.class, resolver);
    }

    public static class Holder {
        @Autowired
        private ItemRenderer renderer;
    }
}
