package com.ultikits.ultitools.text;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.text.internal.ModuleTexts;
import java.util.Optional;

/** Test-only text seam for write-gate fixtures that focus on config bytes, not catalogue lookup. */
public final class ModuleTextStubs {
    private ModuleTextStubs() {
        // Static fixture methods only.
    }

    public static ModuleTexts stubModuleTexts(UltiToolsPlugin plugin) {
        ModuleTexts texts = mock(ModuleTexts.class);
        lenient().when(plugin.moduleTexts()).thenReturn(Optional.of(texts));
        return texts;
    }
}
