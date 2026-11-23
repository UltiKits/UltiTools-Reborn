package com.ultikits.ultitools.item;

import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;

class MinimalItemRendererContractTest extends AbstractItemRendererContractTest {
    @Override
    protected ItemRenderer createRenderer(Messages messages, LocaleResolver localeResolver) {
        return new MinimalItemRenderer(messages, localeResolver);
    }
}
