package com.ultikits.ultitools.item.impl;

import com.ultikits.ultitools.item.AbstractItemRendererContractTest;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class PaperItemRendererContractTest extends AbstractItemRendererContractTest {
    @Override
    protected ItemRenderer createRenderer(Messages messages, LocaleResolver localeResolver) {
        return new PaperItemRenderer(messages, localeResolver);
    }
}
